package com.invoiceextract.desktop.presentation.ui

import androidx.compose.foundation.Image
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.invoiceextract.desktop.presentation.ui.theme.ClayTheme
import java.io.File

/**
 * The left pane of the split-view invoice editor: the source document itself, beside
 * the table extracted from it.
 *
 * A white floating surface with a header (title plus the file name, a page indicator
 * with steppers on multi-page PDFs, and the collapse action) over a smooth
 * vertically-scrolling page. The page scales to the pane width with rounded corners
 * and a soft glass border, so a dense scan stays proofreadable without horizontal
 * scrolling. While the engine renders, a spinner holds the pane; when the source is
 * gone, the pane draws the Persian fallback instead of an empty frame.
 *
 * RTL reading order: forward steps toward the *left*. The auto-mirrored arrows carry
 * that mapping themselves — [KeyboardArrowRight] renders left-pointing and
 * [KeyboardArrowLeft] right-pointing under the forced-RTL tree — so next wears Right
 * and previous wears Left, and the pair stays correct even if the tree direction ever
 * changes.
 *
 * Only core Material icons are used; nothing here needs the extended set.
 *
 * @param sourceFile The invoice document on disk, for the header caption.
 * @param pageCount Rendered pages; `1` for a plain image, `0` when empty.
 * @param pageIndex Zero-based page on screen.
 * @param bitmap The page to draw, or `null` while loading or on failure.
 * @param errorMessage Persian fallback when the source cannot be shown.
 */
@Composable
fun DesktopDocumentPreviewPanel(
    sourceFile: File,
    pageCount: Int,
    pageIndex: Int,
    bitmap: ImageBitmap?,
    errorMessage: String?,
    onNextPage: () -> Unit,
    onPrevPage: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(16.dp),
        tonalElevation = 2.dp,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = PREVIEW_TITLE,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = ClayTheme.colors.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = sourceFile.name,
                        style = MaterialTheme.typography.labelSmall,
                        color = ClayTheme.colors.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                if (pageCount > 1) {
                    Text(
                        text = "صفحه ${pageIndex + 1} از $pageCount",
                        style = MaterialTheme.typography.labelMedium,
                        color = ClayTheme.colors.textSecondary,
                        maxLines = 1,
                    )
                    IconButton(
                        onClick = onPrevPage,
                        enabled = pageIndex > 0,
                    ) {
                        Icon(imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = null)
                    }
                    IconButton(
                        onClick = onNextPage,
                        enabled = pageIndex < pageCount - 1,
                    ) {
                        Icon(imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                    }
                }

                IconButton(onClick = onClose) {
                    Icon(imageVector = Icons.Filled.Close, contentDescription = null)
                }
            }

            HorizontalDivider(color = StatusColors.hairlineBorder)

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                when {
                    bitmap != null -> Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Image(
                            bitmap = bitmap,
                            contentDescription = null,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .border(
                                    width = 1.dp,
                                    color = StatusColors.hairlineBorder,
                                    shape = RoundedCornerShape(12.dp),
                                ),
                            contentScale = ContentScale.FillWidth,
                        )
                    }

                    errorMessage != null -> Text(
                        text = errorMessage,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(24.dp),
                    )

                    else -> CircularProgressIndicator(modifier = Modifier.size(PROGRESS_SIZE))
                }
            }
        }
    }
}

private const val PREVIEW_TITLE = "سند مبدأ (فاکتور)"

private val PROGRESS_SIZE = 40.dp
