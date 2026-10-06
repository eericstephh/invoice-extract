package com.invoiceextract.desktop.presentation.ui.theme

import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * One status badge in both modes: wash background, legible text, lift border.
 */
data class ClayBadgeColors(
    val background: Color,
    val text: Color,
    val border: Color,
)

/**
 * Claymorphic / 3D Fluid Glass theme for InvoiceExtract Desktop.
 *
 * Single source of truth for Day/Night palettes. All screens read from
 * [ClayColors] via [clayColors] so toggling [isDarkMode] re-skins the whole
 * window without touching ViewModel logic.
 *
 * Contrast contract: [textPrimary]/[textSecondary]/[textTertiary] sit on
 * theme-aware surfaces and [surfaceCard]; the [badgeGreen]/[badgeAmber]/
 * [badgeRed]/[badgeBlue] pairs stay legible on their own washes in both
 * modes — solid pastels with deep text in Light, translucent washes with
 * glowing text in Dark. Fixed-light glass (drop zone, frosted empties) keeps
 * its dark copy on purpose and never reads these tokens.
 */
data class ClayColors(
    val isDarkMode: Boolean,
    val ambientStops: List<Color>,
    val claySurface: Color,
    val clayBorderHighlight: Color,
    val clayBorderWidth: Dp,
    val clayCornerRadius: Dp,
    val primaryText: Color,
    val subtitleText: Color,
    val dockContainer: Color,
    val dockBorder: Color,
    val greetingChip: Color,
    val greetingChipBorder: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val surfaceCard: Color,
    val inputContainer: Color,
    val inputBorder: Color,
    val tableRowZebra: Color,
    val badgeGreen: ClayBadgeColors,
    val badgeAmber: ClayBadgeColors,
    val badgeRed: ClayBadgeColors,
    val badgeBlue: ClayBadgeColors,
)

fun lightClayColors(): ClayColors = ClayColors(
    isDarkMode = false,
    ambientStops = listOf(
        Color(0xFFFFF1EB),
        Color(0xFFF3E8FF),
        Color(0xFFE0F2FE),
    ),
    claySurface = Color(0xF0FFFFFF),
    clayBorderHighlight = Color(0x99FFFFFF),
    clayBorderWidth = 1.5.dp,
    clayCornerRadius = 28.dp,
    primaryText = Color(0xFF1E293B),
    subtitleText = Color(0xFF64748B),
    dockContainer = Color(0xE6FFFFFF),
    dockBorder = Color(0x99FFFFFF),
    greetingChip = Color(0xE6FFFFFF),
    greetingChipBorder = Color(0x99FFFFFF),
    textPrimary = Color(0xFF0F172A),
    textSecondary = Color(0xFF475569),
    textTertiary = Color(0xFF64748B),
    surfaceCard = Color(0xF0FFFFFF),
    inputContainer = Color(0x80FFFFFF),
    inputBorder = Color(0xFFCBD5E1),
    tableRowZebra = Color(0xFFF8FAFC),
    badgeGreen = ClayBadgeColors(
        background = Color(0xFFECFDF5),
        text = Color(0xFF065F46),
        border = Color(0xFFA7F3D0),
    ),
    badgeAmber = ClayBadgeColors(
        background = Color(0xFFFFFBEB),
        text = Color(0xFFB45309),
        border = Color(0xFFFDE68A),
    ),
    badgeRed = ClayBadgeColors(
        background = Color(0xFFFEF2F2),
        text = Color(0xFF991B1B),
        border = Color(0xFFFECACA),
    ),
    badgeBlue = ClayBadgeColors(
        background = Color(0xFFEFF6FF),
        text = Color(0xFF1D4ED8),
        border = Color(0xFFBFDBFE),
    ),
)

fun darkClayColors(): ClayColors = ClayColors(
    isDarkMode = true,
    ambientStops = listOf(
        Color(0xFF0B0F19),
        Color(0xFF111827),
        Color(0xFF1E1B4B),
    ),
    claySurface = Color(0xD91E293B),
    clayBorderHighlight = Color(0x3394A3B8),
    clayBorderWidth = 1.5.dp,
    clayCornerRadius = 28.dp,
    primaryText = Color(0xFFF8FAFC),
    subtitleText = Color(0xFF94A3B8),
    dockContainer = Color(0xD91E293B),
    dockBorder = Color(0x3394A3B8),
    greetingChip = Color(0x331E293B),
    greetingChipBorder = Color(0x3394A3B8),
    textPrimary = Color(0xFFF8FAFC),
    textSecondary = Color(0xFFCBD5E1),
    textTertiary = Color(0xFF94A3B8),
    surfaceCard = Color(0xFF1E293B),
    inputContainer = Color(0xFF0F172A),
    inputBorder = Color(0xFF475569),
    tableRowZebra = Color(0xFF172033),
    badgeGreen = ClayBadgeColors(
        background = Color(0x2610B981),
        text = Color(0xFF34D399),
        border = Color(0xFF34D399),
    ),
    badgeAmber = ClayBadgeColors(
        background = Color(0x26F59E0B),
        text = Color(0xFFFBBF24),
        border = Color(0xFFFBBF24),
    ),
    badgeRed = ClayBadgeColors(
        background = Color(0x26EF4444),
        text = Color(0xFFF87171),
        border = Color(0xFFF87171),
    ),
    badgeBlue = ClayBadgeColors(
        background = Color(0x2638BDF8),
        text = Color(0xFF38BDF8),
        border = Color(0xFF38BDF8),
    ),
)

fun clayColors(isDarkMode: Boolean): ClayColors =
    if (isDarkMode) darkClayColors() else lightClayColors()

fun ClayColors.ambientBrush(): Brush = Brush.linearGradient(ambientStops)

/**
 * Ambient clay palette for the composition subtree. Provided once at the
 * main-screen root (which every dialog composes under), defaulting to Light
 * so previews and tests never crash on an absent provider.
 */
val LocalClayColors = compositionLocalOf { lightClayColors() }

/** Composition entry point: `ClayTheme.colors.textPrimary`, etc. */
object ClayTheme {
    val colors: ClayColors
        @Composable
        get() = LocalClayColors.current
}

/**
 * Unified text-field colors for every [androidx.compose.material3.OutlinedTextField]
 * in the window: clay text on the clay input wash, indigo focus accents,
 * secondary labels — identical in every dialog, table cell and search field,
 * legible in both modes.
 */
@Composable
fun clayTextFieldColors(clay: ClayColors): TextFieldColors =
    OutlinedTextFieldDefaults.colors(
        focusedTextColor = clay.textPrimary,
        unfocusedTextColor = clay.textPrimary,
        disabledTextColor = clay.textSecondary,
        focusedContainerColor = clay.inputContainer,
        unfocusedContainerColor = clay.inputContainer,
        disabledContainerColor = clay.inputContainer,
        cursorColor = FOCUS_INDIGO,
        focusedBorderColor = FOCUS_INDIGO,
        unfocusedBorderColor = clay.inputBorder,
        disabledBorderColor = clay.inputBorder,
        focusedLabelColor = FOCUS_INDIGO,
        unfocusedLabelColor = clay.textSecondary,
        focusedPlaceholderColor = clay.textTertiary,
        unfocusedPlaceholderColor = clay.textTertiary,
    )

private val FOCUS_INDIGO = Color(0xFF6366F1)

/** 3D glossy button gradients for the floating dock. */
object ClayDockButtons {
    val scan = Brush.verticalGradient(listOf(Color(0xFFFF8A65), Color(0xFFFF5722)))
    val batch = Brush.verticalGradient(listOf(Color(0xFF26C6DA), Color(0xFF0097A7)))
    val analytics = Brush.verticalGradient(listOf(Color(0xFFAB47BC), Color(0xFF7B1FA2)))
    val archive = Brush.verticalGradient(listOf(Color(0xFF42A5F5), Color(0xFF1976D2)))
    val mapping = Brush.verticalGradient(listOf(Color(0xFF66BB6A), Color(0xFF388E3C)))
    val themeToggleLight = Brush.verticalGradient(listOf(Color(0xFFFFD54F), Color(0xFFFF8F00)))
    val themeToggleDark = Brush.verticalGradient(listOf(Color(0xFF5C6BC0), Color(0xFF1A237E)))
}
