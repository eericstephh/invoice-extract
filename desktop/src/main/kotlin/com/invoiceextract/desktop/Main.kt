package com.invoiceextract.desktop

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.invoiceextract.desktop.data.document.InvoiceFileFormats
import com.invoiceextract.desktop.di.desktopModule
import com.invoiceextract.desktop.presentation.DesktopViewModel
import com.invoiceextract.desktop.presentation.ui.DesktopMainScreen
import com.invoiceextract.desktop.presentation.ui.ExportResultDialog
import org.jetbrains.skia.Image
import org.koin.core.context.GlobalContext
import java.awt.datatransfer.DataFlavor
import java.awt.dnd.DnDConstants
import java.awt.dnd.DropTarget
import java.awt.dnd.DropTargetAdapter
import java.awt.dnd.DropTargetDragEvent
import java.awt.dnd.DropTargetDropEvent
import java.awt.dnd.DropTargetEvent
import java.io.File

/**
 * The brand palette, mirroring the Android `:app` theme so both fronts read as one
 * product. Deep teal reads as financial/document tooling and keeps high contrast on
 * scanned-document backgrounds; the canvas is a soft slate blue and every card floats
 * on it in white, which is what makes the dashboard read as floating glass.
 */
private val DesktopLightColors = lightColorScheme(
    primary = Color(0xFF00696D),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFF6FF6FA),
    onPrimaryContainer = Color(0xFF002020),
    secondary = Color(0xFF4A6365),
    onSecondary = Color(0xFFFFFFFF),
    background = Color(0xFFEEF2F6),
    onBackground = Color(0xFF191C1C),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF191C1C),
    surfaceVariant = Color(0xFFDAE4E4),
    onSurfaceVariant = Color(0xFF3F4949),
    outline = Color(0xFF6F7979),
    outlineVariant = Color(0xFFBEC8C8),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
)

private val DesktopDarkColors = darkColorScheme(
    primary = Color(0xFF6FF6FA),
    onPrimary = Color(0xFF002020),
    primaryContainer = Color(0xFF004F52),
    onPrimaryContainer = Color(0xFF6FF6FA),
    secondary = Color(0xFFB0CCCC),
    onSecondary = Color(0xFF1B3436),
    background = Color(0xFF0B0F19),
    onBackground = Color(0xFFF8FAFC),
    surface = Color(0xFF1E293B),
    onSurface = Color(0xFFF8FAFC),
    surfaceVariant = Color(0xFF334155),
    onSurfaceVariant = Color(0xFF94A3B8),
    outline = Color(0xFF94A3B8),
    outlineVariant = Color(0xFF475569),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
)

/**
 * Desktop entry point of InvoiceExtract.
 *
 * Boots the Koin graph once, builds the window-scoped view model, and renders a single
 * 1100x750 window. The layout direction follows the window language — RTL Persian by
 * default, LTR English after the FA | EN toggle — owned by the screen itself.
 *
 * File ingestion is a native AWT [DropTarget] attached to the window, so a PDF dropped
 * anywhere over the application reaches the pipeline directly, including over the table
 * once an invoice is on screen. Compose has no built-in drop target for files on the JVM;
 * the AWT layer is the one that actually receives them.
 */
fun main() = application {
    val viewModel = remember { initKoinAndCreateViewModel() }

    // The export outcome is reported here rather than inside the screen because a save can
    // fail after a good invoice is on screen, and the dialog must not replace it.
    val exportMessage by viewModel.exportMessage.collectAsState()

    // Lit from the AWT drop target while a file hovers over the window. Set on the AWT
    // event thread, which is the same thread Compose renders on, so the window reacts
    // without marshalling.
    var isDragOver by remember { mutableStateOf(false) }
    var isDarkMode by remember { mutableStateOf(false) }
    // Global SaaS default: the window opens in English (LTR). The Persian
    // experience is one toggle away and stays fully supported.
    var isEnglish by remember { mutableStateOf(true) }

    Window(
        onCloseRequest = {
            // The window is going away: drop the view model's coroutines so the status
            // probe and any in-flight extraction do not outlive it.
            viewModel.dispose()
            exitApplication()
        },
        title = "InvoiceExtract",
        icon = rememberWindowIcon(),
    ) {
        // The total AWT frame size including the OS chrome, so this is a true 1100x750
        // window on Windows rather than a 1100x750 content area.
        window.setSize(WINDOW_WIDTH, WINDOW_HEIGHT)

        // Native drag-and-drop, attached for the lifetime of the window.
        DisposableEffect(Unit) {
            val target = attachInvoiceDropTarget(
                component = window,
                onDragOverChanged = { isDragOver = it },
                onFileDropped = viewModel::processFile,
                onFilesDropped = viewModel::startBatchProcessing,
            )
            onDispose {
                target.setActive(false)
                window.dropTarget = null
            }
        }

        CompositionLocalProvider(
            LocalLayoutDirection provides if (isEnglish) LayoutDirection.Ltr else LayoutDirection.Rtl,
        ) {
            MaterialTheme(colorScheme = if (isDarkMode) DesktopDarkColors else DesktopLightColors) {
                DesktopMainScreen(
                    viewModel = viewModel,
                    parentFrame = window,
                    isDragOver = isDragOver,
                    isDarkMode = isDarkMode,
                    onToggleTheme = { isDarkMode = !isDarkMode },
                    isEnglish = isEnglish,
                    onToggleLanguage = { isEnglish = !isEnglish },
                )

                exportMessage?.let { message ->
                    ExportResultDialog(
                        message = message,
                        onDismiss = viewModel::clearExportMessage,
                    )
                }
            }
        }
    }
}

/**
 * The window icon, decoded once and held for the life of the window.
 *
 * This is the same Skia decode the retired `painterResource("icon.ico")` performed
 * internally — raw classpath bytes through `Image.makeFromEncoded`, which is what
 * understands the ICO container that `ImageIO` cannot read — spelled out explicitly
 * because that helper is deprecated. A missing or corrupt icon degrades to the
 * platform default instead of crashing startup: the icon is cosmetic, the window
 * is not.
 */
@Composable
private fun rememberWindowIcon(): Painter? = remember {
    runCatching {
        val bytes = Thread.currentThread().contextClassLoader
            ?.getResourceAsStream(WINDOW_ICON_RESOURCE)
            ?.use { it.readAllBytes() }
            ?: return@runCatching null
        BitmapPainter(Image.makeFromEncoded(bytes).toComposeImageBitmap())
    }.getOrNull()
}

/**
 * Builds the object graph exactly once and returns a window-scoped view model.
 *
 * The [GlobalContext.getOrNull] guard makes start-up idempotent: a second window (or a
 * restarted application in the same JVM) reuses the graph already standing instead of
 * asserting that Koin is already started.
 *
 * The view model is constructed here rather than bound in the Koin module on purpose: a
 * Koin `single` would outlive the window and keep its coroutines and its last invoice
 * alive after close. Constructing it in `remember` ties it to this window, so it is
 * disposed alongside it.
 */
private fun initKoinAndCreateViewModel(): DesktopViewModel {
    if (GlobalContext.getOrNull() == null) {
        GlobalContext.startKoin { modules(desktopModule) }
    }

    val koin = GlobalContext.get()
    return DesktopViewModel(
        processInvoiceUseCase = koin.get(),
        ollamaExtractor = koin.get(),
        invoiceValidator = koin.get(),
        invoiceRepository = koin.get(),
        batchCoordinator = koin.get(),
        batchExportManager = koin.get(),
        accountingExportManager = koin.get(),
        clientStatementExportManager = koin.get(),
        pettyCashExportManager = koin.get(),
        lifecycleManager = koin.get(),
        mappingRepository = koin.get(),
        duplicateDetector = koin.get(),
        backupManager = koin.get(),
        formalInvoiceGenerator = koin.get(),
        watcherService = koin.get(),
        licenseManager = koin.get(),
    )
}

/**
 * Attaches a native drop target to [component] that accepts invoice files — PDFs and
 * images — and nothing else.
 *
 * The whole window is the target because a document app's primary gesture is "drop the
 * file anywhere". Non-file flavors and unsupported files are rejected with `dropComplete(false)`
 * so the OS shows its "not accepted" cursor and the application never hands the pipeline
 * something it cannot process.
 *
 * One file drops straight into the single-file pipeline; several start a batch run
 * instead, which is what makes a folder's worth of invoices a one-gesture job.
 *
 * Every step is defensive: a malformed transfer object, an unreadable list or a dropped
 * folder must never propagate as an exception into the window.
 *
 * The callbacks fire on the AWT event thread. [DesktopViewModel.processFile] and
 * [DesktopViewModel.startBatchProcessing] are safe to call from it — the heavy work is
 * dispatched onto the view model's own scope — so the drop never stalls the render thread.
 */
private fun attachInvoiceDropTarget(
    component: java.awt.Component,
    onDragOverChanged: (Boolean) -> Unit,
    onFileDropped: (File) -> Unit,
    onFilesDropped: (List<File>) -> Unit,
): DropTarget {
    val listener = object : DropTargetAdapter() {
        override fun dragEnter(event: DropTargetDragEvent) {
            event.acceptDrag(DnDConstants.ACTION_COPY)
            onDragOverChanged(true)
        }

        override fun dragExit(event: DropTargetEvent) {
            onDragOverChanged(false)
        }

        override fun drop(event: DropTargetDropEvent) {
            onDragOverChanged(false)
            event.acceptDrop(DnDConstants.ACTION_COPY)

            val dropped = runCatching {
                val transferable = event.transferable
                if (!transferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                    return@runCatching emptyList<File>()
                }

                @Suppress("UNCHECKED_CAST")
                val files = transferable.getTransferData(DataFlavor.javaFileListFlavor) as List<File>
                files.filter { file -> file.isFile && InvoiceFileFormats.isSupported(file.extension) }
            }.getOrDefault(emptyList())

            event.dropComplete(dropped.isNotEmpty())
            when {
                dropped.size == 1 -> onFileDropped(dropped.single())
                dropped.size > 1 -> onFilesDropped(dropped)
            }
        }
    }

    // The active flag registers this target with the component as it is constructed.
    return DropTarget(component, DnDConstants.ACTION_COPY, listener, isActive)
}

private const val WINDOW_WIDTH = 1100
private const val WINDOW_HEIGHT = 750
private const val WINDOW_ICON_RESOURCE = "icon.ico"
private const val isActive = true
