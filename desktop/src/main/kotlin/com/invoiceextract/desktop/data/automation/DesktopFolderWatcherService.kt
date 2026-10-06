package com.invoiceextract.desktop.data.automation

import com.invoiceextract.desktop.data.engine.DesktopOllamaLifecycleManager
import com.invoiceextract.desktop.data.engine.OllamaState
import com.invoiceextract.desktop.data.licensing.DesktopLicenseManager
import com.invoiceextract.desktop.domain.DesktopProcessInvoiceUseCase
import com.invoiceextract.domain.repository.InvoiceRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.nio.channels.ClosedByInterruptException
import java.nio.file.FileSystems
import java.nio.file.Path
import java.nio.file.StandardWatchEventKinds
import java.nio.file.WatchEvent
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/**
 * Which hot folder to watch, and whether watching is on.
 *
 * @property folderPath Absolute path of the watched directory, or `null` when
 *   none was chosen yet.
 * @property isEnabled True only while a live watch loop runs behind it.
 */
data class FolderWatcherConfig(
    val folderPath: String? = null,
    val isEnabled: Boolean = false,
) {
    /**
     * Spec-facing alias for [folderPath]: the watched directory, or `null`
     * when none was chosen yet. Derived, so `copy()` and equality still speak
     * only [folderPath] and the two can never disagree.
     */
    val path: String?
        get() = folderPath
}

/**
 * One hot-folder outcome, surfaced to the window as a toast.
 *
 * Sealed on purpose: the banner switches on this type to render one exact
 * line per outcome, and a fourth outcome is a compile error there until it
 * gets its own copy.
 */
sealed interface WatcherEvent {
    /** A new invoice file was noticed and entered the pipeline. */
    data class Processing(val fileName: String) : WatcherEvent

    /** The file extracted, saved, and now lives in the history. */
    data class Success(val fileName: String, val sellerName: String, val amount: Long) : WatcherEvent

    /** Anything that stopped a file: lock timeout, engine down, pipeline error. */
    data class Failure(val fileName: String, val reason: String) : WatcherEvent
}

/**
 * Scanner hot-folder auto-watcher: a background NIO watch loop that turns
 * freshly scanned PDFs and photos into saved invoices without a click.
 *
 * Pure JVM `WatchService`, zero extra dependencies. Files already sitting in
 * the folder when watching starts are ignored — only new arrivals fire — and
 * each arrival waits out the scanner's write lock ([waitForStable]) before
 * the pipeline ever opens it, so a half-scanned page can never become a
 * half-extracted invoice. Non-invoice files pass through silently.
 *
 * **Threading.** Everything runs on one dedicated IO scope owned here; files
 * are handled sequentially in arrival order, which doubles as backpressure
 * while a dense invoice keeps the model busy. [shutdown] cancels the scope,
 * so closing the window drops the native watch service with no thread leaks.
 *
 * @param processInvoiceUseCase The extract → structure → validate pipeline.
 * @param invoiceRepository The history the finished invoices land in.
 * @param lifecycleManager Read for the engine gate: files arriving before the
 *   engine is [OllamaState.Ready] fail fast with a Persian reason instead of
 *   burning a doomed model call each.
 * @param licenseManager Read for the lifetime odometer only — donation-ware
 *   processes everything, so there is no commercial gate left to check.
 */
class DesktopFolderWatcherService(
    private val processInvoiceUseCase: DesktopProcessInvoiceUseCase,
    private val invoiceRepository: InvoiceRepository,
    private val lifecycleManager: DesktopOllamaLifecycleManager,
    private val licenseManager: DesktopLicenseManager,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _config = MutableStateFlow(FolderWatcherConfig())
    val config: StateFlow<FolderWatcherConfig> = _config.asStateFlow()

    private val _latestEvent = MutableStateFlow<WatcherEvent?>(null)
    val latestEvent: StateFlow<WatcherEvent?> = _latestEvent.asStateFlow()

    /** The live watch loop, if any. Cancelled on retarget, on disable, on shutdown. */
    private var watchJob: Job? = null

    /**
     * Points the watcher at [path], creating the folder when it does not exist
     * yet — a scanner target is often named before its first scan. A path that
     * is neither a directory nor creatable is ignored, keeping the previous
     * configuration instead of stranding the watcher on a dead path.
     */
    fun setFolder(path: String) {
        val dir = File(path.trim())
        val usable = dir.isDirectory ||
            runCatching { dir.mkdirs() && dir.isDirectory }.getOrDefault(false)
        if (!usable) return

        _config.value = _config.value.copy(folderPath = dir.absolutePath)
        if (_config.value.isEnabled) restart()
    }

    /**
     * Turns watching on or off. Enabling without a usable folder is refused —
     * the config stays disabled rather than running a loop over nothing.
     */
    fun setEnabled(enabled: Boolean) {
        val dirUsable = _config.value.folderPath
            ?.let(::File)
            ?.isDirectory == true
        if (enabled && !dirUsable) {
            _config.value = _config.value.copy(isEnabled = false)
            return
        }
        _config.value = _config.value.copy(isEnabled = enabled)
        if (enabled) restart() else stopWatching()
    }

    /** True for invoice files the watcher cares about, case-insensitively. */
    fun isWatchedFile(fileName: String): Boolean {
        val extension = fileName.substringAfterLast('.', "").lowercase()
        return extension in WATCHED_EXTENSIONS
    }

    /** Dismisses the current toast, if any. The archive is untouched. */
    fun dismissEvent() {
        _latestEvent.value = null
    }

    /**
     * Stops the loop and drops the scope. Call once when the window closes —
     * the native watch service and its threads must not outlive it.
     */
    fun shutdown() {
        stopWatching()
        scope.cancel()
    }

    private fun restart() {
        stopWatching()
        val dir = _config.value.folderPath?.let(::File)?.takeIf { it.isDirectory } ?: run {
            _config.value = _config.value.copy(isEnabled = false)
            return
        }
        watchJob = scope.launch { watchLoop(dir) }
    }

    private fun stopWatching() {
        watchJob?.cancel()
        watchJob = null
    }

    private suspend fun watchLoop(dir: File) {
        FileSystems.getDefault().newWatchService().use { service ->
            dir.toPath().register(service, StandardWatchEventKinds.ENTRY_CREATE)
            while (coroutineContext.isActive) {
                coroutineContext.ensureActive()
                val key = try {
                    service.poll(WATCH_POLL_MS, TimeUnit.MILLISECONDS)
                } catch (e: ClosedByInterruptException) {
                    // Our own cancellation interrupting the poll: unwind as
                    // cancellation, keep watching on a spurious interrupt.
                    coroutineContext.ensureActive()
                    continue
                } ?: continue

                key.pollEvents().forEach { event -> handleCreateEvent(dir, event) }
                if (!key.reset()) break
            }
        }
    }

    private suspend fun handleCreateEvent(dir: File, event: WatchEvent<*>) {
        if (event.kind() != StandardWatchEventKinds.ENTRY_CREATE) return
        val fileName = (event.context() as? Path)?.fileName?.toString() ?: return
        if (!isWatchedFile(fileName)) return

        _latestEvent.value = WatcherEvent.Processing(fileName)
        val file = dir.resolve(fileName)

        if (!waitForStable(file)) {
            _latestEvent.value = WatcherEvent.Failure(fileName, STABILITY_TIMEOUT_MESSAGE)
            return
        }
        if (lifecycleManager.state.value !is OllamaState.Ready) {
            _latestEvent.value = WatcherEvent.Failure(fileName, OLLAMA_NOT_READY_MESSAGE)
            return
        }

        val invoice = processInvoiceUseCase.processInvoice(file).getOrElse { cause ->
            _latestEvent.value = WatcherEvent.Failure(fileName, diagnosticOf(cause))
            return
        }
        invoiceRepository.saveInvoice(invoice).onFailure { cause ->
            _latestEvent.value = WatcherEvent.Failure(fileName, diagnosticOf(cause))
            return
        }
        licenseManager.recordProcessed()

        _latestEvent.value = WatcherEvent.Success(
            fileName = fileName,
            sellerName = invoice.sellerName?.ifBlank { null } ?: UNKNOWN_SELLER,
            amount = invoice.effectiveTomanTotal,
        )
    }

    /**
     * Waits until [file] stops changing and opens readably — the scanner's
     * write lock showing as an open failure on Windows, a moving length
     * everywhere else.
     *
     * Internal (rather than private) so hermetic tests can drive it with fast
     * parameters; production uses the defaults (~1.2s of stability, ~16s of
     * patience). Returns `false` on timeout or cancellation-by-timeout only —
     * coroutine cancellation still throws, so stopping the watcher never hangs
     * inside a long wait.
     */
    internal suspend fun waitForStable(
        file: File,
        pollMs: Long = STABILITY_POLL_MS,
        requiredStableRounds: Int = STABILITY_ROUNDS,
        maxAttempts: Int = STABILITY_MAX_ATTEMPTS,
    ): Boolean {
        var lastLength = -1L
        var stableRounds = 0
        repeat(maxAttempts) {
            coroutineContext.ensureActive()
            val length = try {
                if (!file.isFile) {
                    -1L
                } else {
                    // Opening first: length alone cannot see an exclusive lock.
                    file.inputStream().use { it.read() }
                    file.length()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                -1L
            } catch (e: SecurityException) {
                -1L
            }

            if (length >= 0L && length == lastLength) {
                stableRounds++
                if (stableRounds >= requiredStableRounds) return true
            } else {
                stableRounds = 0
                lastLength = length
            }
            delay(pollMs)
        }
        return false
    }

    private fun diagnosticOf(cause: Throwable): String =
        cause.localizedMessage?.takeIf { it.isNotBlank() }
            ?: cause.message?.takeIf { it.isNotBlank() }
            ?: cause.javaClass.simpleName

    private companion object {
        val WATCHED_EXTENSIONS = setOf("pdf", "png", "jpg", "jpeg")

        /** How long one watch-service poll blocks before rechecking cancellation. */
        const val WATCH_POLL_MS = 500L

        /** Stability sampling: ~400ms apart, three agreements, ~16s of patience. */
        const val STABILITY_POLL_MS = 400L
        const val STABILITY_ROUNDS = 3
        const val STABILITY_MAX_ATTEMPTS = 40

        const val UNKNOWN_SELLER = "نامشخص"

        const val STABILITY_TIMEOUT_MESSAGE =
            "فایل پس از انتظار آزاد نشد؛ بعداً دوباره امتحان می‌شود."

        const val OLLAMA_NOT_READY_MESSAGE =
            "سرویس هوش مصنوعی هنوز آماده نیست؛ فایل برای پردازش بعدی باقی ماند."
    }
}
