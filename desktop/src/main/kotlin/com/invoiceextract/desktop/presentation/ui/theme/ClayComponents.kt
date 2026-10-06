package com.invoiceextract.desktop.presentation.ui.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.invoiceextract.desktop.data.ai.OllamaStatus

/**
 * Clay surface card: 28.dp corners, translucent clay fill, 1.5.dp inner
 * highlight border and a soft multi-shadow (emulated with [shadow]).
 */
@Composable
fun ClayCard(
    clay: ClayColors,
    modifier: Modifier = Modifier,
    contentPadding: androidx.compose.ui.unit.Dp = 20.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier
            .shadow(12.dp, RoundedCornerShape(clay.clayCornerRadius), clip = false)
            .border(
                width = clay.clayBorderWidth,
                color = clay.clayBorderHighlight,
                shape = RoundedCornerShape(clay.clayCornerRadius),
            ),
        color = clay.claySurface,
        shape = RoundedCornerShape(clay.clayCornerRadius),
        tonalElevation = 2.dp,
        shadowElevation = 8.dp,
    ) {
        Column(
            modifier = Modifier.padding(contentPadding),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}

/**
 * Fluid greeting bar: back pill and greeting on the leading side, date +
 * Ollama dot pill beside them, and the language and day/night toggles
 * trailing.
 *
 * Mirrors [LayoutDirection]: as the first content under the screen's direction
 * provider it reads naturally in both RTL Persian and LTR English.
 *
 * @param showBack Whether the clay back pill leads the row, returning to the
 *   home scanner. Shown while an invoice is open or off the scanner tab.
 */
@Composable
fun ClayGreetingHeader(
    clay: ClayColors,
    greeting: String,
    jalaliDate: String,
    ollamaStatus: OllamaStatus,
    isDarkMode: Boolean,
    onToggleTheme: () -> Unit,
    strings: AppStrings = appStrings(AppLanguage.FA),
    isEnglish: Boolean = false,
    onToggleLanguage: () -> Unit = {},
    showBack: Boolean = false,
    onBack: () -> Unit = {},
    onShowShortcuts: () -> Unit = {},
    watcherActive: Boolean = false,
    onWatcherClick: () -> Unit = {},
    onDonateClick: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (showBack) {
            ClayBackButton(
                label = strings.btnBack,
                clay = clay,
                onClick = onBack,
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = greeting,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = clay.primaryText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = strings.appSubtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = clay.subtitleText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OllamaStatusIndicator(
                ollamaStatus = ollamaStatus,
                dateLabel = jalaliDate,
                clay = clay,
                strings = strings,
            )
            if (watcherActive) {
                WatcherActiveChip(
                    label = strings.hotFolderActive,
                    clay = clay,
                    onClick = onWatcherClick,
                )
            }
            DonatePill(
                label = strings.btnDonate,
                badge = clay.badgeRed,
                onClick = onDonateClick,
            )
            DayNightToggle(
                isDarkMode = isDarkMode,
                onToggle = onToggleTheme,
            )
            LanguageToggle(
                isEnglish = isEnglish,
                onToggle = onToggleLanguage,
            )
            ShortcutsButton(onClick = onShowShortcuts)
        }
    }
}

/**
 * The watcher-active chip: a soft emerald pill with a gently pulsating dot,
 * opening the hot-folder config when tapped. Rendered only while the watch
 * loop runs, so a dark header never promises watching that is off.
 */
@Composable
private fun WatcherActiveChip(
    label: String,
    clay: ClayColors,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val pulse = rememberInfiniteTransition(label = "watcher-pulse").animateFloat(
        initialValue = 1f,
        targetValue = 0.45f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "watcher-dot",
    )
    Surface(
        onClick = onClick,
        modifier = modifier
            .shadow(6.dp, RoundedCornerShape(50))
            .border(1.dp, clay.greetingChipBorder, RoundedCornerShape(50)),
        color = Color(0xFF065F46).copy(alpha = 0.85f),
        contentColor = Color.White,
        shape = RoundedCornerShape(50),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(9.dp)
                    .graphicsLayer { alpha = pulse.value }
                    .background(Color(0xFF34D399), CircleShape),
            )
            Text(
                text = "🟢 $label",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
            )
        }
    }
}

/**
 * The donation pill: a compact rose clay pill with a coffee cup, opening the
 * crypto donation dialog when tapped. Always visible — donation-ware has no
 * quota to advertise, only gratitude to offer.
 */
@Composable
private fun DonatePill(
    label: String,
    badge: ClayBadgeColors,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        modifier = modifier
            .shadow(6.dp, RoundedCornerShape(50))
            .border(1.dp, badge.border, RoundedCornerShape(50)),
        color = badge.background,
        contentColor = badge.text,
        shape = RoundedCornerShape(50),
    ) {
        Text(
            text = "☕ $label",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
        )
    }
}

/**
 * The cheatsheet trigger: a small frosted info dot beside the language
 * switch, opening the keyboard-shortcuts modal.
 */
@Composable
private fun ShortcutsButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        modifier = modifier
            .shadow(6.dp, CircleShape)
            .border(1.dp, Color.White.copy(alpha = 0.5f), CircleShape)
            .size(38.dp),
        color = Color.White.copy(alpha = 0.22f),
        contentColor = Color.White,
        shape = CircleShape,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = Icons.Filled.Info,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/**
 * The universal clay back pill: a frosted capsule with a mirrored arrow that
 * returns to the home scanner. Auto-mirrored, so the arrow points the right
 * way in both RTL Persian and LTR English.
 */
@Composable
fun ClayBackButton(
    label: String,
    clay: ClayColors,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        modifier = modifier
            .shadow(6.dp, RoundedCornerShape(50))
            .border(1.dp, clay.greetingChipBorder, RoundedCornerShape(50)),
        color = clay.greetingChip,
        contentColor = clay.primaryText,
        shape = RoundedCornerShape(50),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
            )
        }
    }
}

/**
 * The FA | EN language pill: a frosted toggle beside the day/night switch.
 * The active language wears bold type; the pill itself only flips the flag,
 * so the screen's direction provider does the actual RTL/LTR switch.
 */
@Composable
fun LanguageToggle(
    isEnglish: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onToggle,
        modifier = modifier
            .shadow(6.dp, RoundedCornerShape(50))
            .border(1.dp, Color.White.copy(alpha = 0.5f), RoundedCornerShape(50)),
        color = Color.White.copy(alpha = 0.22f),
        contentColor = Color.White,
        shape = RoundedCornerShape(50),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = "FA",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (!isEnglish) FontWeight.Bold else FontWeight.Normal,
                color = Color.White.copy(alpha = if (!isEnglish) 1f else 0.65f),
            )
            Text(
                text = "|",
                style = MaterialTheme.typography.labelMedium,
                color = Color.White.copy(alpha = 0.5f),
            )
            Text(
                text = "EN",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (isEnglish) FontWeight.Bold else FontWeight.Normal,
                color = Color.White.copy(alpha = if (isEnglish) 1f else 0.65f),
            )
        }
    }
}

/** Animated 3D toggle pill for Day/Night. */
@Composable
fun DayNightToggle(
    isDarkMode: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val bg by animateColorAsState(
        if (isDarkMode) Color(0xFF1E1B4B) else Color(0xFFFFE0B2),
    )
    val knobColor by animateColorAsState(
        if (isDarkMode) Color(0xFF312E81) else Color(0xFFFFB300),
    )
    Box(
        modifier = modifier
            .shadow(6.dp, RoundedCornerShape(50))
            .clip(RoundedCornerShape(50))
            .background(bg)
            .border(1.dp, Color.White.copy(alpha = 0.5f), RoundedCornerShape(50))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onToggle,
            )
            .padding(4.dp)
            .width(52.dp),
        contentAlignment = if (isDarkMode) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(
            modifier = Modifier
                .size(24.dp)
                .background(knobColor, CircleShape)
                .border(1.dp, Color.White.copy(alpha = 0.6f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = if (isDarkMode) "☾" else "☀",
                style = MaterialTheme.typography.labelMedium,
                color = Color.White,
            )
        }
    }
}

/**
 * One glossy 3D capsule button for the floating dock.
 */
@Composable
fun ClayDockButton(
    label: String,
    icon: ImageVector,
    gradient: Brush,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val border = if (selected) Color.White else Color.White.copy(alpha = 0.55f)
    Column(
        modifier = modifier
            .shadow(if (selected) 10.dp else 6.dp, RoundedCornerShape(20.dp))
            .clip(RoundedCornerShape(20.dp))
            .background(gradient)
            .border(
                if (selected) 2.dp else 1.dp, border, RoundedCornerShape(20.dp),
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(Color.White.copy(alpha = 0.28f))
                .padding(4.dp),
        ) {
            Icon(imageVector = icon, contentDescription = null, tint = Color.White)
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
