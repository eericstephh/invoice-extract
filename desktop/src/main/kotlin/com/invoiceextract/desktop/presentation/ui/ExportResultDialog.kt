package com.invoiceextract.desktop.presentation.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable

/**
 * The outcome of an export, dismissed with one click.
 *
 * Export results are reported in a dialog rather than in the state banner because a save
 * can fail *after* a good invoice is on screen, and the banner is already speaking for
 * the invoice's validation. The dialog reports the file name and clears itself, leaving
 * the invoice exactly where it was.
 *
 * @param message Persian outcome sentence, including the target file name.
 * @param onDismiss Called when the dialog is dismissed; the view model clears the message
 *   so it is not shown again on the next recomposition.
 */
@Composable
fun ExportResultDialog(
    message: String,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = "نتیجه خروجی") },
        text = { Text(text = message) },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(text = "بستن")
            }
        },
    )
}
