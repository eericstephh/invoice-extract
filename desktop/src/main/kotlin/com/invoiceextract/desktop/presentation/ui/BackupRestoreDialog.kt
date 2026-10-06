package com.invoiceextract.desktop.presentation.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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
import com.invoiceextract.desktop.data.backup.BackupResult
import com.invoiceextract.desktop.data.backup.RestoreResult
import com.invoiceextract.desktop.presentation.ui.theme.AppLanguage
import com.invoiceextract.desktop.presentation.ui.theme.AppStrings
import com.invoiceextract.desktop.presentation.ui.theme.appStrings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.awt.Frame
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * The backup & restore window: one claymorphic dialog over the archive with a
 * card per direction — pack the whole merchant state into a zip, or bring one
 * back with a confirm-first restore.
 *
 * Both actions run off the render thread: the native file dialogs block their
 * calling thread, and the zip work itself is IO-bound. While an action runs,
 * its card shows an indeterminate bar; when it lands, the outcome line reports
 * it inline — success in the normal tone, failure in the error tone — so the
 * dialog never needs a second window to say what happened.
 *
 * Only core Material icons are used (settings, close); nothing here needs the
 * extended set the build deliberately stays off.
 *
 * @param invoiceCount Live archive size, shown so the merchant sees what a
 *   backup would pack before packing it.
 * @param mappingCount Live mapping-table size, shown for the same reason.
 * @param parentFrame The underlying AWT window, used as the dialogs' parent.
 * @param onDismiss Called when the dialog is closed by any path.
 * @param onCreateBackup Packs the stores into the dialog-chosen target file.
 * @param onRestoreBackup Validates and promotes the dialog-chosen archive,
 *   reloading both stores so the window refreshes without a restart.
 */
@Composable
fun BackupRestoreDialog(
    invoiceCount: Int,
    mappingCount: Int,
    parentFrame: Frame,
    onDismiss: () -> Unit,
    onCreateBackup: suspend (targetFile: File) -> Result<BackupResult>,
    onRestoreBackup: suspend (sourceFile: File) -> Result<RestoreResult>,
    strings: AppStrings = appStrings(AppLanguage.FA),
    isEnglish: Boolean = false,
) {
    val scope = rememberCoroutineScope()
    var busyAction by remember { mutableStateOf<BusyAction?>(null) }
    var outcome by remember { mutableStateOf<Outcome?>(null) }
    var confirmRestore by remember { mutableStateOf(false) }

    // A DialogWindow opens a separate window, so the app-wide direction
    // provision does not reach it; it is re-provided here explicitly,
    // following the window language like the history dialog does.
    CompositionLocalProvider(
        LocalLayoutDirection provides if (isEnglish) LayoutDirection.Ltr else LayoutDirection.Rtl,
    ) {
        DialogWindow(
            onCloseRequest = onDismiss,
            state = rememberDialogState(size = DpSize(DIALOG_WIDTH, DIALOG_HEIGHT)),
            title = strings.backupRestoreTitle,
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
                        Icon(
                            imageVector = Icons.Filled.Settings,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            text = strings.backupRestoreTitle,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 8.dp),
                        )
                        IconButton(onClick = onDismiss) {
                            Icon(imageVector = Icons.Filled.Close, contentDescription = null)
                        }
                    }

                    BackupCard(
                        title = strings.backupSectionTitle,
                        description = strings.backupSectionDescription,
                        counter = counterText(invoiceCount, mappingCount, isEnglish),
                        actionLabel = strings.btnCreateBackup,
                        busy = busyAction == BusyAction.BACKUP,
                        enabled = busyAction == null,
                        onAction = {
                            outcome = null
                            busyAction = BusyAction.BACKUP
                            scope.launch(Dispatchers.IO) {
                                val target = saveFileDialog(
                                    parentFrame,
                                    defaultBackupName(),
                                    ZIP_EXTENSION,
                                )
                                if (target == null) {
                                    busyAction = null
                                } else {
                                    outcome = onCreateBackup(target).fold(
                                        onSuccess = { Outcome.Success(strings.backupSuccess) },
                                        onFailure = { cause -> Outcome.Failure(messageOf(cause)) },
                                    )
                                    busyAction = null
                                }
                            }
                        },
                    )

                    BackupCard(
                        title = strings.restoreSectionTitle,
                        description = strings.restoreSectionDescription,
                        counter = null,
                        actionLabel = strings.btnRestoreBackup,
                        busy = busyAction == BusyAction.RESTORE,
                        enabled = busyAction == null,
                        onAction = {
                            if (!confirmRestore) {
                                confirmRestore = true
                                return@BackupCard
                            }
                            confirmRestore = false
                            outcome = null
                            busyAction = BusyAction.RESTORE
                            scope.launch(Dispatchers.IO) {
                                val source = openZipFileDialog(parentFrame)
                                if (source == null) {
                                    busyAction = null
                                } else {
                                    outcome = onRestoreBackup(source).fold(
                                        onSuccess = { result ->
                                            Outcome.Success(
                                                strings.restoreSuccess(
                                                    result.restoredInvoices + result.restoredMappings,
                                                ),
                                            )
                                        },
                                        onFailure = { cause -> Outcome.Failure(messageOf(cause)) },
                                    )
                                    busyAction = null
                                }
                            }
                        },
                    )

                    // Confirm-first restore: the first tap arms the warning, the
                    // second tap opens the picker — an accidental tap can never
                    // swap the live stores.
                    if (confirmRestore) {
                        Text(
                            text = strings.restoreWarning,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                    outcome?.let { current ->
                        Text(
                            text = when (current) {
                                is Outcome.Success -> current.message
                                is Outcome.Failure -> current.message
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = when (current) {
                                is Outcome.Success -> MaterialTheme.colorScheme.primary
                                is Outcome.Failure -> MaterialTheme.colorScheme.error
                            },
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(),
                        )
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

/** Which direction is currently working; both cards gate on it. */
private enum class BusyAction {
    BACKUP,
    RESTORE,
}

/** An inline outcome line: success in the normal tone, failure in error red. */
private sealed interface Outcome {
    data class Success(val message: String) : Outcome
    data class Failure(val message: String) : Outcome
}

/**
 * One direction card: title, description, an optional live counter, and the
 * action slot — which a progress bar occupies while the action runs.
 */
@Composable
private fun BackupCard(
    title: String,
    description: String,
    counter: String?,
    actionLabel: String,
    busy: Boolean,
    enabled: Boolean,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            counter?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            if (busy) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            } else {
                Button(
                    onClick = onAction,
                    enabled = enabled,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(text = actionLabel, maxLines = 1)
                }
            }
        }
    }
}

/** `InvoiceExtract_Backup_2026-10-02.zip`: Gregorian date, filename-safe everywhere. */
private fun defaultBackupName(): String =
    "InvoiceExtract_Backup_${LocalDate.now().format(DateTimeFormatter.ISO_DATE)}.zip"

/** The live counts as one line, in the window language. */
private fun counterText(invoiceCount: Int, mappingCount: Int, isEnglish: Boolean): String =
    if (isEnglish) {
        "$invoiceCount invoices • $mappingCount mappings"
    } else {
        "تعداد فاکتورها: ${invoiceCount.toPersianDigits()} • تعداد نگاشت‌ها: ${mappingCount.toPersianDigits()}"
    }

/** Prefers the typed Persian sentence; falls back to the class name, never blank. */
private fun messageOf(cause: Throwable): String =
    cause.message?.takeIf { it.isNotBlank() }
        ?: cause.javaClass.simpleName

/** Latin digits in, Persian digits out. */
private fun Int.toPersianDigits(): String = toString().map { char ->
    if (char in '0'..'9') PERSIAN_DIGITS[char - '0'] else char
}.joinToString("")

private const val PERSIAN_DIGITS = "۰۱۲۳۴۵۶۷۸۹"
private const val ZIP_EXTENSION = "zip"

private val DIALOG_WIDTH = 620.dp
private val DIALOG_HEIGHT = 640.dp
