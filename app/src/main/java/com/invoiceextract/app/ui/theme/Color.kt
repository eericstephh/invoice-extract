package com.invoiceextract.app.ui.theme

import androidx.compose.ui.graphics.Color

// region Light scheme ------------------------------------------------------------
// Deep teal as the brand anchor: it reads as "financial / document" tooling while
// staying far from the low-contrast blues that hurt scanned-document legibility.
// All on* colors are near-black or pure white for a high-contrast productivity app.

val md_primary = Color(0xFF00696D)
val md_onPrimary = Color(0xFFFFFFFF)
val md_primaryContainer = Color(0xFF6FF6FA)
val md_onPrimaryContainer = Color(0xFF002020)

val md_secondary = Color(0xFF4A6365)
val md_onSecondary = Color(0xFFFFFFFF)
val md_secondaryContainer = Color(0xFFCCE8E9)
val md_onSecondaryContainer = Color(0xFF051F20)

val md_tertiary = Color(0xFF4C607C)
val md_onTertiary = Color(0xFFFFFFFF)
val md_tertiaryContainer = Color(0xFFD5E3FF)
val md_onTertiaryContainer = Color(0xFF071C36)

val md_error = Color(0xFFBA1A1A)
val md_onError = Color(0xFFFFFFFF)
val md_errorContainer = Color(0xFFFFDAD6)
val md_onErrorContainer = Color(0xFF410002)

val md_background = Color(0xFFF7F9FA)
val md_onBackground = Color(0xFF191C1C)
val md_surface = Color(0xFFF7F9FA)
val md_onSurface = Color(0xFF191C1C)
val md_surfaceVariant = Color(0xFFDAE4E4)
val md_onSurfaceVariant = Color(0xFF3F4949)
val md_outline = Color(0xFF6F7979)
val md_outlineVariant = Color(0xFFBEC8C8)
val md_scrim = Color(0xFF000000)
val md_inverseSurface = Color(0xFF2D3131)
val md_inverseOnSurface = Color(0xFFEFF1F0)
val md_inversePrimary = Color(0xFF4BD8DD)

// endregion

// region Dark scheme ------------------------------------------------------------

val md_primary_dark = Color(0xFF4BD8DD)
val md_onPrimary_dark = Color(0xFF003739)
val md_primaryContainer_dark = Color(0xFF004F52)
val md_onPrimaryContainer_dark = Color(0xFF6FF6FA)

val md_secondary_dark = Color(0xFFB0CCCD)
val md_onSecondary_dark = Color(0xFF1B3435)
val md_secondaryContainer_dark = Color(0xFF324B4C)
val md_onSecondaryContainer_dark = Color(0xFFCCE8E9)

val md_tertiary_dark = Color(0xFFB4C8E4)
val md_onTertiary_dark = Color(0xFF1C3149)
val md_tertiaryContainer_dark = Color(0xFF334860)
val md_onTertiaryContainer_dark = Color(0xFFD5E3FF)

val md_error_dark = Color(0xFFFFB4AB)
val md_onError_dark = Color(0xFF690005)
val md_errorContainer_dark = Color(0xFF93000A)
val md_onErrorContainer_dark = Color(0xFFFFDAD6)

val md_background_dark = Color(0xFF191C1C)
val md_onBackground_dark = Color(0xFFE0E3E2)
val md_surface_dark = Color(0xFF191C1C)
val md_onSurface_dark = Color(0xFFE0E3E2)
val md_surfaceVariant_dark = Color(0xFF3F4949)
val md_onSurfaceVariant_dark = Color(0xFFBEC8C8)
val md_outline_dark = Color(0xFF899392)
val md_outlineVariant_dark = Color(0xFF3F4949)
val md_scrim_dark = Color(0xFF000000)
val md_inverseSurface_dark = Color(0xFFE0E3E2)
val md_inverseOnSurface_dark = Color(0xFF2D3131)
val md_inversePrimary_dark = Color(0xFF00696D)

// endregion

// Semantic brand colors used outside the M3 color roles (e.g. scan overlays,
// confidence chips). Kept explicit so dark/light tweaks stay in one place.

/** Teal used for success / high-confidence states. */
val ConfidenceHigh = Color(0xFF1B7A3D)

/** Amber used for warnings / medium-confidence states. */
val ConfidenceMedium = Color(0xFFB26B00)

/** Red used for critical errors / low-confidence states. */
val ConfidenceLow = Color(0xFFB3261E)
