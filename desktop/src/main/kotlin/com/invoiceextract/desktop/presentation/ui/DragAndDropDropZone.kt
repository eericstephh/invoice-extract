package com.invoiceextract.desktop.presentation.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.foundation.border
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.invoiceextract.desktop.presentation.ui.theme.AppLanguage
import com.invoiceextract.desktop.presentation.ui.theme.AppStrings
import com.invoiceextract.desktop.presentation.ui.theme.appStrings

/**
 * The drop target of the idle window: a large dashed, elevated FinTech card that explains
 * the drag gesture and offers both entries into the pipeline — one file straight into
 * extraction, many files into the batch dashboard.
 *
 * The dashed border is the visual vocabulary for "drop here" across every desktop app the
 * user has met; it is drawn by hand because Compose's `border` modifier has no dash
 * option. See [Modifier.dashedBorder]. While a file hovers over the window the card
 * tints and the border thickens, so the gesture is acknowledged before the release.
 *
 * **Why the buttons are callbacks.** The panel does not open the file dialog itself: a
 * native dialog needs the window's AWT frame as its parent and must be shown off the
 * render thread, both of which the screen decides. Each button therefore hands the click
 * to its callback and lets the screen run the dialog on a background coroutine.
 *
 * Only core Material icons ([Add] for one file, [List] for many); nothing here needs
 * the extended set the build deliberately stays off.
 *
 * @param isDragOver Lit by the window-level AWT drop target while a file is hovering over
 *   the window, so the panel can acknowledge the gesture before the user releases.
 * @param onPickFile Called for the single-file picker. Owns the whole open
 *   dialog → pipeline hand-off.
 * @param onPickMultipleFiles Called for the multi-file picker. Owns the whole open
 *   dialog → batch hand-off.
 * @param isEnabled `false` while Ollama is provisioning: both picker buttons are disabled
 *   so processing cannot be invoked prematurely.
 */
@Composable
fun DragAndDropDropZone(
    onPickFile: () -> Unit,
    onPickMultipleFiles: () -> Unit,
    modifier: Modifier = Modifier,
    isDragOver: Boolean = false,
    isEnabled: Boolean = true,
    strings: AppStrings = appStrings(AppLanguage.FA),
) {
    val borderColor = if (isDragOver) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.outline
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .height(DROP_ZONE_HEIGHT)
            .dashedBorder(color = borderColor, width = if (isDragOver) 3.dp else 2.dp)
            .border(
                width = 1.dp,
                color = Color(0x80FFFFFF),
                shape = RoundedCornerShape(DROP_ZONE_CORNER),
            ),
        color = if (isDragOver) StatusColors.emeraldContainer else Color(0xF5FFFFFF),
        shape = RoundedCornerShape(DROP_ZONE_CORNER),
        tonalElevation = DROP_ZONE_ELEVATION,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                imageVector = Icons.Filled.Add,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(DROP_ZONE_ICON_SIZE),
            )

            Text(
                text = strings.dropTitle,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = StatusColors.slateHeader,
                textAlign = TextAlign.Center,
            )

            // Supported formats as pill badges, so the accepted set is visible without
            // reading a sentence.
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FormatBadge(text = "PDF")
                FormatBadge(text = "JPG")
                FormatBadge(text = "PNG")
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    onClick = onPickFile,
                    enabled = isEnabled,
                ) {
                    Icon(imageVector = Icons.Filled.Add, contentDescription = null)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(text = strings.dropSingle, maxLines = 1)
                }
                OutlinedButton(
                    onClick = onPickMultipleFiles,
                    enabled = isEnabled,
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.primary,
                    ),
                ) {
                    Icon(imageVector = Icons.AutoMirrored.Filled.List, contentDescription = null)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(text = strings.dropBatch, maxLines = 1)
                }
            }
        }
    }
}

/**
 * One supported-format pill: a slate chip that names an accepted suffix at a glance.
 */
@Composable
private fun FormatBadge(text: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        color = StatusColors.slateContainer,
        contentColor = StatusColors.onSlateContainer,
        shape = RoundedCornerShape(50),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
        )
    }
}

/**
 * Draws a dashed outline around the component, following [shape].
 *
 * Compose's `border` modifier offers solid strokes only, so the dash is painted here with
 * a [PathEffect]. The path is built from the shape's [Outline] via [Path.addOutline] so a
 * rounded container gets dashed corners rather than a dashed rectangle clipped through
 * them.
 */
private fun Modifier.dashedBorder(
    color: Color,
    width: Dp = 2.dp,
    shape: Shape = RoundedCornerShape(DROP_ZONE_CORNER),
): Modifier = drawWithContent {
    val outline = shape.createOutline(size, layoutDirection, this)
    val borderPath = Path().apply { addOutline(outline) }
    drawContent()
    drawPath(
        path = borderPath,
        color = color,
        style = Stroke(
            width = width.toPx(),
            pathEffect = PathEffect.dashPathEffect(DASH_INTERVALS, phase = 0f),
        ),
    )
}

private val DROP_ZONE_HEIGHT = 360.dp
private val DROP_ZONE_CORNER = 20.dp
private val DROP_ZONE_ELEVATION = 2.dp
private val DROP_ZONE_ICON_SIZE = 44.dp

// On / off dash lengths in pixels: a 12-8 dash reads clearly at any window scale.
private val DASH_INTERVALS = floatArrayOf(12f, 8f)
