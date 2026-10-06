package com.invoiceextract.desktop.presentation.ui.hotkeys

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState
import com.invoiceextract.desktop.presentation.ui.theme.AppLanguage
import com.invoiceextract.desktop.presentation.ui.theme.AppStrings
import com.invoiceextract.desktop.presentation.ui.theme.appStrings

/**
 * The shortcuts cheatsheet: every window hotkey as a 3D-keycap row with its
 * description in the active language.
 *
 * A `DialogWindow` like the archive dialogs, so it floats above the workflow
 * instead of replacing it, with its own direction provision following the
 * window language. Read-only by design — no action runs from here; it only
 * teaches the chords the root interceptor already honors.
 *
 * Only core Material icons are used (close); nothing here needs the extended
 * set the build deliberately stays off.
 */
@Composable
fun KeyboardShortcutsDialog(
    onDismiss: () -> Unit,
    strings: AppStrings = appStrings(AppLanguage.FA),
    isEnglish: Boolean = false,
) {
    CompositionLocalProvider(
        LocalLayoutDirection provides if (isEnglish) LayoutDirection.Ltr else LayoutDirection.Rtl,
    ) {
        DialogWindow(
            onCloseRequest = onDismiss,
            state = rememberDialogState(size = DpSize(DIALOG_WIDTH, DIALOG_HEIGHT)),
            title = strings.shortcutsTitle,
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
                            text = strings.shortcutsTitle,
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

                    LazyColumn(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(ALL_SHORTCUTS, key = { it.keyCombination }) { shortcut ->
                            ShortcutRow(
                                shortcut = shortcut,
                                isEnglish = isEnglish,
                            )
                        }
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
 * One cheatsheet row: the action title leading, the chord as 3D keycaps
 * trailing — `[Ctrl] + [S]` — so the eye scans titles and the fingers scan
 * caps.
 */
@Composable
private fun ShortcutRow(
    shortcut: ShortcutItem,
    isEnglish: Boolean,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = if (isEnglish) shortcut.titleEn else shortcut.titleFa,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            shortcut.keyCombination.split(KEY_SEPARATOR).forEachIndexed { index, cap ->
                if (index > 0) {
                    Text(
                        text = KEY_SEPARATOR.trim(),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                KeycapBadge(label = cap.trim())
            }
        }
    }
}

/**
 * One 3D keycap: surface-variant key with a soft lift and hairline border,
 * like a key pulled from the keyboard it documents.
 */
@Composable
private fun KeycapBadge(
    label: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .shadow(3.dp, RoundedCornerShape(8.dp))
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(8.dp),
            ),
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        shape = RoundedCornerShape(8.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
        )
    }
}

private const val KEY_SEPARATOR = "+"

private val DIALOG_WIDTH = 520.dp
private val DIALOG_HEIGHT = 620.dp
