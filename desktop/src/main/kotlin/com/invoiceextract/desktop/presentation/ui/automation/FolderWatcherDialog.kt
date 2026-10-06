package com.invoiceextract.desktop.presentation.ui.automation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState
import com.invoiceextract.desktop.data.automation.FolderWatcherConfig
import com.invoiceextract.desktop.presentation.ui.theme.AppLanguage
import com.invoiceextract.desktop.presentation.ui.theme.AppStrings
import com.invoiceextract.desktop.presentation.ui.theme.appStrings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.awt.Frame
import java.io.File
import javax.swing.JFileChooser

/**
 * The hot-folder configuration window: where the scanner drops files, and
 * whether the watcher runs.
 *
 * A `DialogWindow` like the archive dialogs, with its own direction provision
 * following the window language. The folder picker is a native directory
 * chooser on IO — it blocks its thread, never the render thread — and the
 * toggle writes straight through to the watcher service, so the header chip
 * lights the moment watching starts.
 *
 * Only core Material icons are used (close); nothing here needs the extended
 * set the build deliberately stays off.
 *
 * @param config The live watcher configuration; re-collected by the caller.
 * @param parentFrame The underlying AWT window, used as the picker's parent.
 * @param onDismiss Called when the dialog is closed by any path.
 * @param onFolderChosen Called with the picked directory's absolute path.
 * @param onToggle Called with the switch's new state.
 */
@Composable
fun FolderWatcherDialog(
    config: FolderWatcherConfig,
    parentFrame: Frame,
    onDismiss: () -> Unit,
    onFolderChosen: (String) -> Unit,
    onToggle: (Boolean) -> Unit,
    strings: AppStrings = appStrings(AppLanguage.FA),
    isEnglish: Boolean = false,
) {
    val scope = rememberCoroutineScope()

    CompositionLocalProvider(
        LocalLayoutDirection provides if (isEnglish) LayoutDirection.Ltr else LayoutDirection.Rtl,
    ) {
        DialogWindow(
            onCloseRequest = onDismiss,
            state = rememberDialogState(size = DpSize(DIALOG_WIDTH, DIALOG_HEIGHT)),
            title = strings.hotFolderTitle,
            resizable = false,
        ) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                tonalElevation = 3.dp,
                shape = RoundedCornerShape(28.dp),
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = strings.hotFolderTitle,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = onDismiss) {
                            Icon(imageVector = Icons.Filled.Close, contentDescription = null)
                        }
                    }

                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(16.dp),
                    ) {
                        Text(
                            text = strings.hotFolderInfo,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = if (config.isEnabled) strings.watcherOn else strings.watcherOff,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                        )
                        Switch(
                            checked = config.isEnabled,
                            onCheckedChange = onToggle,
                        )
                    }

                    // Live status: while the loop runs the dialog says what it
                    // is doing — searching for new arrivals — instead of only
                    // showing a lit switch.
                    if (config.isEnabled) {
                        Text(
                            text = strings.hotFolderActive,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                    Text(
                        text = config.folderPath ?: "—",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(
                        onClick = {
                            scope.launch(Dispatchers.IO) {
                                pickDirectoryDialog(parentFrame)
                                    ?.takeIf { it.isDirectory }
                                    ?.let { onFolderChosen(it.absolutePath) }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(text = strings.btnSelectFolder, maxLines = 1)
                    }

                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(text = strings.btnClose, maxLines = 1)
                    }
                }
            }
        }
    }
}

/**
 * Native directory picker. AWT's `FileDialog` cannot select folders, so the
 * Swing chooser covers this one case — modal to the window, blocking only
 * the IO thread its caller runs it on, exactly like the file dialogs.
 *
 * @return the chosen directory, or `null` on cancel.
 */
private fun pickDirectoryDialog(parent: Frame): File? {
    val chooser = JFileChooser().apply {
        dialogTitle = "Scanner Hot Folder"
        fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
        isAcceptAllFileFilterUsed = false
    }
    return if (chooser.showOpenDialog(parent) == JFileChooser.APPROVE_OPTION) {
        chooser.selectedFile?.takeIf { it.isDirectory }
    } else {
        null
    }
}

private val DIALOG_WIDTH = 560.dp
private val DIALOG_HEIGHT = 560.dp
