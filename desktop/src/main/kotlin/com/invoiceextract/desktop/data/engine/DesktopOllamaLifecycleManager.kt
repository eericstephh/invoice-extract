package com.invoiceextract.desktop.data.engine

import com.invoiceextract.desktop.data.ai.LocalOllamaAiExtractor
import com.invoiceextract.desktop.data.ai.OllamaStatus
import com.invoiceextract.desktop.data.ai.PullProgress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * Where the local Ollama engine stands, as owned by
 * [DesktopOllamaLifecycleManager][com.invoiceextract.desktop.data.engine.DesktopOllamaLifecycleManager].
 *
 * The manager walks this hierarchy in one direction — [Checking] → download/install/
 * start/model stages → [Ready] — and any stage that cannot proceed lands on [Error]
 * instead of stalling. The window renders exactly one banner per state, so a user who
 * just installed the app always sees *why* extraction is not available yet and what
 * is happening about it, rather than a dead drop zone.
 *
 * Sealed on purpose: the UI switches on this type, so adding a stage is a compile
 * error in the screen until it is drawn. Progress values are pre-clamped to `0f..1f`
 * by the manager — the progress bar divides nothing and clamps nothing itself.
 */
sealed interface OllamaState {

    /**
     * The daemon answers on the loopback port and the required model is installed;
     * invoice processing actions are enabled.
     */
    data object Ready : OllamaState

    /**
     * Probing the loopback port and the installed-model catalog. Transient by design:
     * no banner copy is needed for it beyond the generic "starting" title, because a
     * healthy machine passes through here in milliseconds.
     */
    data object Checking : OllamaState

    /**
     * Fetching the Windows installer into the temp folder.
     *
     * @property progress Bytes received over bytes expected, `0f..1f`. Unknown-length
     *   responses report `0f` until the first byte lands, so the bar pulses instead of
     *   lying about a percentage it cannot know yet.
     */
    data class DownloadingInstaller(val progress: Float) : OllamaState

    /** Running the installer silently; no progress is observable from Inno Setup. */
    data object Installing : OllamaState

    /** The binary exists but nothing listens yet: launching `ollama serve` and polling. */
    data object StartingService : OllamaState

    /**
     * Pulling the required model through the daemon's streaming endpoint.
     *
     * @property status The daemon's own status line (e.g. `"pulling manifest"`), shown
     *   verbatim beside the bar — English daemon vocabulary the user can search for.
     * @property progress Layer bytes received over layer bytes total, `0f..1f`.
     */
    data class DownloadingModel(val status: String, val progress: Float) : OllamaState

    /**
     * A stage failed and the manager stopped rather than retry-looping on its own.
     *
     * @property message A Persian sentence naming what broke and what the user can do
     *   about it; shown verbatim with the retry button.
     * @property canRetry `false` when retrying is pointless without the user changing
     *   something first (no disk space, no network route) — the button still shows,
     *   because re-probing after fixing the cause must stay one tap away.
     */
    data class Error(val message: String, val canRetry: Boolean = true) : OllamaState
}

/**
 * Auto-provisions the local Ollama stack on Windows and reports each stage as
 * [OllamaState] for the status banner.
 *
 * Walk: [OllamaState.Checking] → (download installer → install → start service) →
 * pull model → [OllamaState.Ready]. Any unrecoverable stage lands on
 * [OllamaState.Error] and stops; the UI retries via [ensureReady].
 *
 * Serialized by [mutex] so concurrent [ensureReady] calls (window init + manual
 * retry) never run two installers or two model pulls at once.
 *
 * All blocking work (process launch, installer download, daemon polling) runs on
 * [Dispatchers.IO]; [state] itself is thread-safe and may be collected on any thread.
 *
 * @param extractor Health probe and model-pull driver for the local daemon.
 * @param requiredModel Tag to pull when the daemon is up but no drivable model exists.
 */
class DesktopOllamaLifecycleManager(
    private val extractor: LocalOllamaAiExtractor,
    private val requiredModel: String = DEFAULT_MODEL,
) {
    private val mutex = Mutex()
    private val _state = MutableStateFlow<OllamaState>(OllamaState.Checking)
    val state: StateFlow<OllamaState> = _state.asStateFlow()

    /**
     * Drives the stack to [OllamaState.Ready], emitting each intermediate stage.
     *
     * Never throws except on coroutine cancellation: failures become
     * [OllamaState.Error] so the banner always has something to render.
     */
    suspend fun ensureReady() {
        mutex.withLock { runProvisioning() }
    }

    private suspend fun runProvisioning() {
        try {
            _state.value = OllamaState.Checking
            when (val status = queryStatus()) {
                is OllamaStatus.Ready -> {
                    _state.value = OllamaState.Ready
                    return
                }
                is OllamaStatus.ModelMissing -> {
                    pullModel()
                    return
                }
                OllamaStatus.OllamaNotRunning -> ensureServiceThenModel()
            }
        } catch (cause: CancellationException) {
            throw cause
        } catch (cause: Exception) {
            _state.value = OllamaState.Error(
                message = cause.message ?: MSG_PROVISION_FAILED,
                canRetry = true,
            )
        }
    }

    private suspend fun ensureServiceThenModel() {
        if (!isDaemonReachable()) {
            _state.value = OllamaState.StartingService
            val binary = findOllamaBinary()
            if (binary == null) {
                if (!isWindows()) {
                    _state.value = OllamaState.Error(MSG_NOT_RUNNING_NON_WINDOWS, canRetry = true)
                    return
                }
                downloadAndInstall()
                // Installer may have placed the binary; re-resolve before launching.
                if (!launchServe(findOllamaBinary())) {
                    _state.value = OllamaState.Error(MSG_INSTALL_START_FAILED, canRetry = true)
                    return
                }
            } else {
                launchServe(binary)
            }
            if (!awaitDaemon()) {
                _state.value = OllamaState.Error(MSG_SERVICE_START_FAILED, canRetry = true)
                return
            }
        }
        when (queryStatus()) {
            is OllamaStatus.Ready -> _state.value = OllamaState.Ready
            is OllamaStatus.ModelMissing -> pullModel()
            OllamaStatus.OllamaNotRunning -> {
                _state.value = OllamaState.Error(MSG_SERVICE_START_FAILED, canRetry = true)
            }
        }
    }

    private suspend fun pullModel() {
        try {
            extractor.pullModel(requiredModel).collect { progress ->
                _state.value = when (progress) {
                    is PullProgress.Downloading -> OllamaState.DownloadingModel(
                        status = DOWNLOAD_STATUS,
                        progress = progress.percent.coerceIn(0f, 1f),
                    )
                    is PullProgress.Status -> OllamaState.DownloadingModel(
                        status = progress.message,
                        progress = 0f,
                    )
                    PullProgress.Completed -> OllamaState.Ready
                }
            }
            if (_state.value !is OllamaState.Ready) {
                _state.value = OllamaState.Ready
            }
        } catch (cause: CancellationException) {
            throw cause
        } catch (cause: Exception) {
            _state.value = OllamaState.Error(
                message = cause.message ?: MSG_MODEL_PULL_FAILED,
                canRetry = true,
            )
        }
    }

    private suspend fun queryStatus(): OllamaStatus =
        withContext(Dispatchers.IO) { extractor.checkStatus() }

    private suspend fun isDaemonReachable(): Boolean =
        queryStatus() != OllamaStatus.OllamaNotRunning

    private suspend fun awaitDaemon(): Boolean = withContext(Dispatchers.IO) {
        repeat(SERVICE_POLL_ATTEMPTS) {
            if (runCatching { extractor.checkStatus() }.getOrNull() != OllamaStatus.OllamaNotRunning) {
                return@withContext true
            }
            delay(SERVICE_POLL_INTERVAL_MS)
        }
        false
    }

    private suspend fun downloadAndInstall() {
        val target = withContext(Dispatchers.IO) {
            File.createTempFile(INSTALLER_PREFIX, INSTALLER_SUFFIX)
        }
        try {
            downloadInstaller(target)
            _state.value = OllamaState.Installing
            runInstallerSilently(target)
        } finally {
            withContext(Dispatchers.IO) { runCatching { target.delete() } }
        }
    }

    private suspend fun downloadInstaller(target: File) = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            connection = (URL(OLLAMA_SETUP_URL).openConnection() as HttpURLConnection).apply {
                connectTimeout = INSTALLER_CONNECT_TIMEOUT_MS
                readTimeout = INSTALLER_READ_TIMEOUT_MS
                instanceFollowRedirects = true
            }
            connection.connect()
            val total = connection.contentLengthLong.takeIf { it > 0 }
            var received = 0L
            connection.inputStream.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(DOWNLOAD_BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        received += read
                        val progress = if (total != null) {
                            (received.toFloat() / total.toFloat()).coerceIn(0f, 1f)
                        } else {
                            0f
                        }
                        _state.value = OllamaState.DownloadingInstaller(progress = progress)
                    }
                }
            }
        } finally {
            connection?.disconnect()
        }
    }

    private suspend fun runInstallerSilently(installer: File) = withContext(Dispatchers.IO) {
        val process = ProcessBuilder(installer.absolutePath, INSTALLER_SILENT_FLAG)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
        val finished = process.waitFor(INSTALL_TIMEOUT_MINUTES, TimeUnit.MINUTES)
        if (!finished) {
            runCatching { process.destroyForcibly() }
            throw IllegalStateException(MSG_INSTALL_TIMEOUT)
        }
        if (process.exitValue() != 0) {
            throw IllegalStateException(MSG_INSTALL_FAILED)
        }
    }

    private suspend fun launchServe(binary: File?): Boolean = withContext(Dispatchers.IO) {
        if (binary == null) return@withContext false
        runCatching {
            ProcessBuilder(binary.absolutePath, SERVE_ARG)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
        }.isSuccess
    }

    private suspend fun findOllamaBinary(): File? = withContext(Dispatchers.IO) {
        resolveOnPath()?.let { return@withContext it }
        KNOWN_WINDOWS_PATHS
            .map { raw -> expandWindowsPath(raw) }
            .map(::File)
            .firstOrNull { it.isFile && it.canExecute() }
    }

    private fun resolveOnPath(): File? {
        val probe = if (isWindows()) WHERE_COMMAND else WHICH_COMMAND
        return runCatching {
            val process = ProcessBuilder(probe, OLLAMA_BINARY)
                .redirectErrorStream(true)
                .start()
            val firstLine = process.inputStream.bufferedReader().readLine()
            val finished = process.waitFor(PATH_PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            if (!finished || firstLine.isNullOrBlank()) return@runCatching null
            File(firstLine.trim()).takeIf { it.isFile }
        }.getOrNull()
    }

    private fun expandWindowsPath(raw: String): String {
        var expanded = raw
        for ((key, value) in System.getenv()) {
            expanded = expanded.replace("%$key%", value, ignoreCase = true)
        }
        return expanded
    }

    private fun isWindows(): Boolean =
        System.getProperty(OS_NAME_PROPERTY).orEmpty().contains(WINDOWS_TOKEN, ignoreCase = true)

    private companion object {
        const val DEFAULT_MODEL = "qwen2.5:3b"
        const val OLLAMA_SETUP_URL = "https://ollama.com/download/OllamaSetup.exe"

        const val OLLAMA_BINARY = "ollama"
        const val SERVE_ARG = "serve"
        const val WHERE_COMMAND = "where"
        const val WHICH_COMMAND = "which"
        const val OS_NAME_PROPERTY = "os.name"
        const val WINDOWS_TOKEN = "win"

        val KNOWN_WINDOWS_PATHS = listOf(
            "%LOCALAPPDATA%\\Programs\\Ollama\\ollama.exe",
            "%PROGRAMFILES%\\Ollama\\ollama.exe",
            "%PROGRAMFILES(X86)%\\Ollama\\ollama.exe",
        )

        const val INSTALLER_PREFIX = "OllamaSetup"
        const val INSTALLER_SUFFIX = ".exe"
        const val INSTALLER_SILENT_FLAG = "/VERYSILENT"
        const val INSTALLER_CONNECT_TIMEOUT_MS = 30_000
        const val INSTALLER_READ_TIMEOUT_MS = 60_000
        const val DOWNLOAD_BUFFER_SIZE = 64 * 1024
        const val INSTALL_TIMEOUT_MINUTES = 10L
        const val PATH_PROBE_TIMEOUT_SECONDS = 10L

        const val SERVICE_POLL_ATTEMPTS = 30
        const val SERVICE_POLL_INTERVAL_MS = 1_000L

        const val DOWNLOAD_STATUS = "downloading"

        const val MSG_PROVISION_FAILED = "آماده‌سازی سرویس Ollama ناموفق بود. لطفاً دوباره تلاش کنید."
        const val MSG_NOT_RUNNING_NON_WINDOWS =
            "سرویس Ollama در حال اجرا نیست. آن را با دستور «ollama serve» اجرا کنید."
        const val MSG_INSTALL_START_FAILED =
            "نصب خودکار Ollama انجام شد اما سرویس راه‌اندازی نشد. سیستم را بررسی و دوباره تلاش کنید."
        const val MSG_SERVICE_START_FAILED =
            "سرویس Ollama راه‌اندازی نشد. مطمئن شوید Ollama نصب است و دوباره تلاش کنید."
        const val MSG_MODEL_PULL_FAILED = "دریافت مدل هوش مصنوعی ناموفق بود. اتصال اینترنت را بررسی و دوباره تلاش کنید."
        const val MSG_INSTALL_TIMEOUT = "نصب Ollama بیش از حد طول کشید و متوقف شد."
        const val MSG_INSTALL_FAILED = "نصب خودکار Ollama ناموفق بود."
    }
}
