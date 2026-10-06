package com.invoiceextract.desktop.presentation.ui

import com.invoiceextract.desktop.data.document.InvoiceFileFormats
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

/**
 * Native file dialogs for the desktop window, built on AWT [FileDialog] rather than
 * Swing's `JFileChooser`.
 *
 * **Why native AWT.** `FileDialog` maps straight onto the Windows common dialog: it is
 * what the user expects, it honours their Explorer settings and remembered folders, and
 * its native peer never paints a Swing component tree over the Compose surface — a real
 * hazard when a `JDialog` is layered above a Compose window.
 *
 * **Threading.** Both helpers *block the calling thread* until the user picks a file or
 * cancels, because a modal [FileDialog] returns only once it is dismissed. The AWT render
 * thread is the same thread Compose recomposes on, so calling these from a button's
 * `onClick` directly would freeze the window for the whole duration of the dialog. Callers
 * therefore run them on [kotlinx.coroutines.Dispatchers.IO] — see `DesktopMainScreen` —
 * which leaves the render thread free and the window recomposing while the dialog is open.
 *
 * Neither function throws: cancelling the dialog is reported as `null`, and every other
 * path returns a well-formed file or `null`, so a dialog problem can never propagate into
 * the pipeline as an exception.
 */

/**
 * Opens the platform's native open dialog, filtered to invoice files: PDFs and images.
 *
 * @param parent The owning window; the dialog is modal relative to it.
 * @return the chosen invoice file, or `null` if the user cancelled the dialog.
 */
fun openInvoiceFileDialog(parent: Frame): File? {
    val dialog = FileDialog(parent, OPEN_DIALOG_TITLE, FileDialog.LOAD).apply {
        isMultipleMode = false
        // Windows narrows the listed files through the `file` pattern — semicolon-separated
        // globs are the native multi-format syntax; the `FilenameFilter` is what the X11
        // peers honour instead. Both are set so the list the user is shown contains only
        // invoice files on every platform the app runs on.
        setFile(INVOICE_FILTER_PATTERN)
        setFilenameFilter { _, name ->
            InvoiceFileFormats.ALL.any { extension ->
                name.endsWith(".$extension", ignoreCase = true)
            }
        }
    }

    dialog.isVisible = true

    val name = dialog.file ?: return null
    val directory = dialog.directory ?: return null

    val selected = File(directory, name)
    // The platform filter is advisory — a typed name or an Explorer shortcut can still
    // hand back something outside the set — so the extension is verified here before the
    // file reaches the pipeline.
    return selected.takeIf { InvoiceFileFormats.isSupported(it.extension) }
}

/**
 * Opens the platform's native open dialog in multi-select mode, filtered to invoice
 * files.
 *
 * The same Windows glob and X11 `FilenameFilter` pair as [openInvoiceFileDialog] keeps
 * the visible list to invoice files on every peer, and the same advisory-filter caveat
 * applies — so every returned file is re-verified against [allowedExtensions] before it
 * reaches the caller. The caller decides the lifecycle: an empty list (cancelled dialog
 * or nothing matching) is a no-op for the batch path, never an error screen.
 *
 * @param parent The owning window; the dialog is modal relative to it.
 * @param allowedExtensions Lowercased-safe extensions to keep, e.g. `listOf("pdf",
 *   "png", "jpg", "jpeg")`. Compared case-insensitively.
 * @return the chosen invoice files in selection order, or an empty list when the user
 *   cancelled or picked nothing the pipeline accepts.
 */
fun openMultipleFilesDialog(parentFrame: Frame, allowedExtensions: List<String>): List<File> {
    val dialog = FileDialog(parentFrame, OPEN_MULTIPLE_DIALOG_TITLE, FileDialog.LOAD).apply {
        isMultipleMode = true
        // Same native filter pair as the single-file dialog so both pickers show the
        // same list; the extension gate below is what actually decides.
        setFile(INVOICE_FILTER_PATTERN)
        setFilenameFilter { _, name ->
            allowedExtensions.any { extension ->
                name.endsWith(".$extension", ignoreCase = true)
            }
        }
    }

    dialog.isVisible = true

    return dialog.files.toList().filter { file ->
        file.isFile && allowedExtensions.any { extension ->
            file.extension.equals(extension, ignoreCase = true)
        }
    }
}

/**
 * Opens the platform's native open dialog, filtered to backup archives.
 *
 * @param parent The owning window; the dialog is modal relative to it.
 * @return the chosen `.zip` file, or `null` if the user cancelled the dialog
 *   or picked something outside the set.
 */
fun openZipFileDialog(parent: Frame): File? {
    val dialog = FileDialog(parent, OPEN_ZIP_DIALOG_TITLE, FileDialog.LOAD).apply {
        isMultipleMode = false
        setFile(ZIP_FILTER_PATTERN)
        setFilenameFilter { _, name ->
            name.endsWith(".zip", ignoreCase = true)
        }
    }

    dialog.isVisible = true

    val name = dialog.file ?: return null
    val directory = dialog.directory ?: return null

    val selected = File(directory, name)
    return selected.takeIf { it.isFile && it.extension.equals("zip", ignoreCase = true) }
}

/**
 * Opens the platform's native open dialog, filtered to bank statement exports.
 *
 * Text CSV (and separator variants) parse directly; a binary `.xls` is
 * accepted by the picker but refused by the parser with guidance, because no
 * spreadsheet engine ships with the desktop. The extension gate below is what
 * actually decides, like every other picker in this file.
 *
 * @param parent The owning window; the dialog is modal relative to it.
 * @return the chosen statement file, or `null` if the user cancelled the
 *   dialog or picked something outside the set.
 */
fun openStatementFileDialog(parent: Frame): File? {
    val dialog = FileDialog(parent, OPEN_STATEMENT_DIALOG_TITLE, FileDialog.LOAD).apply {
        isMultipleMode = false
        setFile(STATEMENT_FILTER_PATTERN)
        setFilenameFilter { _, name ->
            STATEMENT_EXTENSIONS.any { extension ->
                name.endsWith(".$extension", ignoreCase = true)
            }
        }
    }

    dialog.isVisible = true

    val name = dialog.file ?: return null
    val directory = dialog.directory ?: return null

    val selected = File(directory, name)
    return selected.takeIf { file ->
        file.isFile && STATEMENT_EXTENSIONS.any { extension ->
            file.extension.equals(extension, ignoreCase = true)
        }
    }
}

/**
 * Opens the platform's native save dialog for a single output file.
 *
 * The returned file is guaranteed to carry [extension]: a user who types `invoice` gets
 * `invoice.<extension>`, because a saved file Windows cannot associate with the right
 * application is a support ticket this one line prevents.
 *
 * @param parent      The owning window; the dialog is modal relative to it.
 * @param defaultName The pre-filled file name, including a suggested extension.
 * @param extension   The extension appended when the user omits it.
 * @return the target file, or `null` if the user cancelled the dialog.
 */
fun saveFileDialog(parent: Frame, defaultName: String, extension: String): File? {
    val dialog = FileDialog(parent, SAVE_DIALOG_TITLE, FileDialog.SAVE).apply {
        setFile(defaultName)
    }

    dialog.isVisible = true

    val name = dialog.file ?: return null
    val directory = dialog.directory ?: return null

    val selected = File(directory, name)
    return if (selected.extension.equals(extension, ignoreCase = true)) {
        selected
    } else {
        File(selected.parentFile, "${selected.name}.$extension")
    }
}

private const val OPEN_DIALOG_TITLE = "انتخاب فاکتور (PDF یا تصویر)"
private const val OPEN_MULTIPLE_DIALOG_TITLE = "انتخاب فاکتورها"
private const val OPEN_ZIP_DIALOG_TITLE = "انتخاب فایل پشتیبان (.zip)"
private const val OPEN_STATEMENT_DIALOG_TITLE = "انتخاب صورتحساب بانکی (CSV)"
private const val SAVE_DIALOG_TITLE = "انتخاب محل ذخیره خروجی"

// The native Windows multi-format filter: one glob per accepted suffix.
private const val INVOICE_FILTER_PATTERN = "*.pdf;*.png;*.jpg;*.jpeg"
private const val ZIP_FILTER_PATTERN = "*.zip"
private const val STATEMENT_FILTER_PATTERN = "*.csv;*.txt;*.tsv;*.xls"

private val STATEMENT_EXTENSIONS = listOf("csv", "txt", "tsv", "xls")
