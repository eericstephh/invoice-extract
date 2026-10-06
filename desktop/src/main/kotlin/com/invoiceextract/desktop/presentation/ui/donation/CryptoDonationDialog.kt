package com.invoiceextract.desktop.presentation.ui.donation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState
import com.invoiceextract.desktop.presentation.ui.theme.AppLanguage
import com.invoiceextract.desktop.presentation.ui.theme.AppStrings
import com.invoiceextract.desktop.presentation.ui.theme.ClayTheme
import com.invoiceextract.desktop.presentation.ui.theme.appStrings
import kotlinx.coroutines.delay

/**
 * The crypto donation dialog: one focused clay card carrying the project's
 * USDT wallet on BNB Smart Chain.
 *
 * A `DialogWindow` like the archive dialogs, with its own direction provision
 * following the window language: a rose/amber heart beside the title, the
 * appreciation line, one donation box (network badge, asset, selectable
 * monospaced address, copy action with confirmation), the network warning,
 * and close. Everything reads top to bottom with no tabs or lists — one
 * asset, one network, one address, zero ambiguity about where to send.
 *
 * Only core Compose icons are used (favorite, close).
 *
 * @param onDismiss Called when the dialog is closed by any path.
 */
@Composable
fun CryptoDonationDialog(
    onDismiss: () -> Unit,
    strings: AppStrings = appStrings(AppLanguage.FA),
    isEnglish: Boolean = false,
) {
    var copiedRecently by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current

    if (copiedRecently) {
        LaunchedEffect(copiedRecently) {
            delay(COPIED_CONFIRM_MS)
            copiedRecently = false
        }
    }

    CompositionLocalProvider(
        LocalLayoutDirection provides if (isEnglish) LayoutDirection.Ltr else LayoutDirection.Rtl,
    ) {
        DialogWindow(
            onCloseRequest = onDismiss,
            state = rememberDialogState(size = DpSize(DIALOG_WIDTH, DIALOG_HEIGHT)),
            title = strings.donationTitle,
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
                        BoxHeart()
                        Text(
                            text = strings.donationTitle,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 12.dp),
                        )
                        IconButton(onClick = onDismiss) {
                            Icon(imageVector = Icons.Filled.Close, contentDescription = null)
                        }
                    }

                    Text(
                        text = strings.donationSubtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

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
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Surface(
                                    color = ClayTheme.colors.badgeAmber.background,
                                    contentColor = ClayTheme.colors.badgeAmber.text,
                                    shape = RoundedCornerShape(50),
                                ) {
                                    Text(
                                        text = NETWORK_BADGE,
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                    )
                                }
                                Text(
                                    text = ASSET_TICKER,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                            }

                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant,
                            ) {
                                SelectionContainer {
                                    Text(
                                        text = DONATION_ADDRESS,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontFamily = FontFamily.Monospace,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 14.dp, vertical = 12.dp),
                                    )
                                }
                            }

                            Button(
                                onClick = {
                                    clipboard.setText(AnnotatedString(DONATION_ADDRESS))
                                    copiedRecently = true
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = ClayTheme.colors.badgeGreen.background,
                                    contentColor = ClayTheme.colors.badgeGreen.text,
                                ),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(
                                    text = if (copiedRecently) {
                                        strings.addressCopied
                                    } else {
                                        strings.copyWalletAddress
                                    },
                                )
                            }
                        }
                    }

                    Text(
                        text = strings.networkWarning,
                        style = MaterialTheme.typography.bodySmall,
                        color = ClayTheme.colors.badgeAmber.text,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    TextButton(
                        onClick = onDismiss,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(text = strings.btnClose)
                    }
                }
            }
        }
    }
}

/** The rose/amber heart leading the title row. */
@Composable
private fun BoxHeart(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(44.dp)
            .shadow(6.dp, CircleShape, clip = false)
            .background(
                brush = Brush.linearGradient(
                    listOf(Color(0xFFF472B6), Color(0xFFFBBF24)),
                ),
                shape = CircleShape,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.Favorite,
            contentDescription = null,
            tint = Color.White,
        )
    }
}

/** The project's receiving wallet: USDT on BNB Smart Chain (BEP-20). */
private const val DONATION_ADDRESS = "0x76bC20fed1Cc02Ea574217477BBB964949BE9629"

/** Network and asset captions are protocol constants, never translated. */
private const val NETWORK_BADGE = "BNB Smart Chain (BEP-20)"
private const val ASSET_TICKER = "USDT"

private const val COPIED_CONFIRM_MS = 2_500L

private val DIALOG_WIDTH = 560.dp
private val DIALOG_HEIGHT = 640.dp
