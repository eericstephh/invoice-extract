package com.invoiceextract.desktop.presentation.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.invoiceextract.desktop.data.ai.OllamaStatus

/**
 * The live Ollama engine status in a frosted glass pill: the current date,
 * a verdict-colored dot, and the connection sentence in the window language.
 *
 * Exhaustive over [OllamaStatus], so a fourth engine state is a compile error
 * here until it gets its own dot and sentence.
 *
 * Only core Material icons are used (date); nothing here needs the extended
 * set the build deliberately stays off.
 */
@Composable
fun OllamaStatusIndicator(
    ollamaStatus: OllamaStatus,
    dateLabel: String,
    clay: ClayColors,
    strings: AppStrings = appStrings(AppLanguage.FA),
    modifier: Modifier = Modifier,
) {
    val dotColor: Color
    val statusText: String
    when (ollamaStatus) {
        is OllamaStatus.Ready -> {
            dotColor = Color(0xFF10B981)
            statusText = "${strings.connectedStatus} (${ollamaStatus.modelName})"
        }
        is OllamaStatus.ModelMissing -> {
            dotColor = Color(0xFFF59E0B)
            statusText = strings.modelMissingStatus(ollamaStatus.requiredModel)
        }
        OllamaStatus.OllamaNotRunning -> {
            dotColor = Color(0xFFEF4444)
            statusText = strings.ollamaOffStatus
        }
    }

    Surface(
        color = clay.greetingChip,
        shape = RoundedCornerShape(50),
        modifier = modifier.border(
            1.dp, clay.greetingChipBorder, RoundedCornerShape(50),
        ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(INDICATOR_DOT_SIZE)
                    .background(dotColor, CircleShape),
            )
            Icon(
                imageVector = Icons.Filled.DateRange,
                contentDescription = null,
                tint = clay.subtitleText,
                modifier = Modifier.size(INDICATOR_ICON_SIZE),
            )
            Text(
                text = "$dateLabel • $statusText",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = clay.primaryText,
                maxLines = 1,
            )
        }
    }
}

private val INDICATOR_DOT_SIZE = 9.dp
private val INDICATOR_ICON_SIZE = 16.dp
