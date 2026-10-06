package com.invoiceextract.desktop.presentation.ui.reminders

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState
import com.invoiceextract.desktop.domain.analytics.FollowUpTone
import com.invoiceextract.desktop.domain.analytics.generateReminderMessage
import com.invoiceextract.desktop.presentation.AmountFormatter
import com.invoiceextract.desktop.presentation.ui.theme.AppLanguage
import com.invoiceextract.desktop.presentation.ui.theme.AppStrings
import com.invoiceextract.desktop.presentation.ui.theme.ClayTheme
import com.invoiceextract.desktop.presentation.ui.theme.appStrings
import com.invoiceextract.desktop.presentation.ui.theme.clayTextFieldColors
import com.invoiceextract.domain.model.Invoice
import com.invoiceextract.domain.model.PaymentStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.awt.Desktop
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * The Follow-Up Copilot: a claymorphic dialog that drafts the payment reminder
 * for one unpaid invoice, lets the user retune it, and hands it to the
 * clipboard, to WhatsApp, or — when the money is in — straight to the ledger.
 *
 * A `DialogWindow` like the history archive (a separate window needs its own
 * direction provision), dressed in the clay surface: 28.dp corners, inner
 * highlight border and soft shadow, following the window language.
 *
 * Everything here reads the invoice and never writes it, except through the
 * two explicit exits: [onMarkPaid] (a paid copy, persisted by the caller) and
 * [onDismiss]. The preview starts from [generateReminderMessage] and is then
 * the user's own text — switching tone re-drafts, editing never does.
 *
 * Only core Compose icons are used (copy, share, paid, close).
 *
 * @param invoice The unpaid invoice being chased.
 * @param onMarkPaid Called with the invoice copied to [PaymentStatus.PAID];
 *   the caller persists it and the dialog closes.
 */
@Composable
fun InvoiceFollowUpDialog(
    invoice: Invoice,
    onDismiss: () -> Unit,
    onMarkPaid: (Invoice) -> Unit = {},
    strings: AppStrings = appStrings(AppLanguage.FA),
    isEnglish: Boolean = false,
) {
    var tone by remember { mutableStateOf(defaultToneFor(invoice)) }
    var draft by remember(invoice.id, tone, isEnglish) {
        mutableStateOf(generateReminderMessage(invoice, tone, isEnglish))
    }
    var copiedRecently by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()

    if (copiedRecently) {
        LaunchedEffect(copiedRecently) {
            delay(COPIED_CONFIRM_MS)
            copiedRecently = false
        }
    }

    val clientLine = listOfNotNull(
        invoice.clientName?.trim()?.takeIf { it.isNotBlank() }
            ?: invoice.buyerName?.trim()?.takeIf { it.isNotBlank() },
        invoice.invoiceNumber?.trim()?.takeIf { it.isNotBlank() }?.let { "#$it" },
        "${AmountFormatter.formatToman(invoice.effectiveTomanTotal.toDouble())} ${strings.currencyToman}",
    ).joinToString(" • ")

    CompositionLocalProvider(
        LocalLayoutDirection provides if (isEnglish) LayoutDirection.Ltr else LayoutDirection.Rtl,
    ) {
        DialogWindow(
            onCloseRequest = onDismiss,
            state = rememberDialogState(size = DpSize(DIALOG_WIDTH, DIALOG_HEIGHT)),
            title = strings.followUpTitle,
            resizable = false,
        ) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                tonalElevation = 3.dp,
            ) {
                Column(
                    modifier = Modifier
                        .padding(20.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = strings.followUpTitle,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = onDismiss) {
                            Icon(imageVector = Icons.Filled.Close, contentDescription = null)
                        }
                    }

                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .shadow(12.dp, RoundedCornerShape(28.dp), clip = false)
                            .border(
                                width = ClayTheme.colors.clayBorderWidth,
                                color = ClayTheme.colors.clayBorderHighlight,
                                shape = RoundedCornerShape(28.dp),
                            ),
                        color = ClayTheme.colors.claySurface,
                        shape = RoundedCornerShape(28.dp),
                        tonalElevation = 2.dp,
                        shadowElevation = 8.dp,
                    ) {
                        Column(
                            modifier = Modifier.padding(18.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Text(
                                text = clientLine,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface,
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                ToneChip(
                                    label = strings.toneFriendly,
                                    selected = tone == FollowUpTone.FRIENDLY,
                                    onClick = { tone = FollowUpTone.FRIENDLY },
                                    modifier = Modifier.weight(1f),
                                )
                                ToneChip(
                                    label = strings.toneFormal,
                                    selected = tone == FollowUpTone.FORMAL,
                                    onClick = { tone = FollowUpTone.FORMAL },
                                    modifier = Modifier.weight(1f),
                                )
                                ToneChip(
                                    label = strings.toneUrgent,
                                    selected = tone == FollowUpTone.URGENT,
                                    onClick = { tone = FollowUpTone.URGENT },
                                    modifier = Modifier.weight(1f),
                                )
                            }

                            OutlinedTextField(
                                value = draft,
                                onValueChange = { draft = it },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f, fill = false),
                                minLines = 6,
                                textStyle = MaterialTheme.typography.bodyMedium,
                                colors = clayTextFieldColors(ClayTheme.colors),
                            )

                            if (copiedRecently) {
                                Text(
                                    text = strings.messageCopied,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = ClayTheme.colors.badgeGreen.text,
                                )
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Button(
                                    onClick = {
                                        clipboard.setText(AnnotatedString(draft))
                                        copiedRecently = true
                                    },
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Text(text = strings.copyMessage)
                                }
                                OutlinedButton(
                                    onClick = {
                                        scope.launch(Dispatchers.IO) {
                                            openWhatsApp(draft)
                                        }
                                    },
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Icon(imageVector = Icons.Filled.Share, contentDescription = null)
                                    Text(text = strings.sendViaWhatsapp)
                                }
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                if (invoice.paymentStatus != PaymentStatus.PAID) {
                                    Button(
                                        onClick = {
                                            onMarkPaid(invoice.copy(paymentStatus = PaymentStatus.PAID))
                                            onDismiss()
                                        },
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = ClayTheme.colors.badgeGreen.background,
                                            contentColor = ClayTheme.colors.badgeGreen.text,
                                        ),
                                        modifier = Modifier.weight(1f),
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.CheckCircle,
                                            contentDescription = null,
                                        )
                                        Text(text = strings.markAsPaid)
                                    }
                                }
                                TextButton(
                                    onClick = onDismiss,
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Text(text = strings.btnClose)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * One tone option: the picked tone wears the primary container, the rest sit
 * on the surface variant — the filter-row language the currency and payment
 * chips already speak.
 */
@Composable
private fun ToneChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
        contentColor = if (selected) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        shape = RoundedCornerShape(50),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp),
        )
    }
}

/** Overdue invoices open on the urgent draft; pending ones on friendly. */
private fun defaultToneFor(invoice: Invoice): FollowUpTone =
    if (invoice.paymentStatus == PaymentStatus.OVERDUE) {
        FollowUpTone.URGENT
    } else {
        FollowUpTone.FRIENDLY
    }

/**
 * Hands [text] to WhatsApp Web's share endpoint. No-op where the host cannot
 * browse — a headless CI worker must never throw opening a browser.
 */
private fun openWhatsApp(text: String) {
    if (!Desktop.isDesktopSupported()) return
    val desktop = Desktop.getDesktop()
    if (!desktop.isSupported(Desktop.Action.BROWSE)) return
    val url = "https://wa.me/?text=" + URLEncoder.encode(text, StandardCharsets.UTF_8)
    runCatching { desktop.browse(URI.create(url)) }
}

private const val COPIED_CONFIRM_MS = 2_500L

private val DIALOG_WIDTH = 560.dp
private val DIALOG_HEIGHT = 660.dp
