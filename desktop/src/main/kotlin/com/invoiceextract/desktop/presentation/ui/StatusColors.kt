package com.invoiceextract.desktop.presentation.ui

import androidx.compose.ui.graphics.Color

/**
 * The green / amber / red triad used by the Ollama status chip and the validation banner.
 *
 * Deliberately *not* the Material 3 tonal roles from the theme: those are derived from a
 * single brand seed and cannot express "this is a warning, not a brand accent". These
 * fixed, high-contrast pairs carry an unambiguous state at a glance on either side of the
 * light/dark divide. They mirror the confidence palette the Android app already uses for
 * the same three states so both fronts read identically.
 */
internal object StatusColors {

    /** Daemon reachable and a model is installed: extraction can proceed. */
    val greenContainer = Color(0xFFDFF5E4)
    val onGreenContainer = Color(0xFF1B7A3D)

    /** Daemon is up but the model must be pulled first: recoverable, needs one action. */
    val amberContainer = Color(0xFFFDF1DC)
    val onAmberContainer = Color(0xFFB26B00)

    /** Daemon is not running: nothing can proceed until the user starts it. */
    val redContainer = Color(0xFFFDE4E1)
    val onRedContainer = Color(0xFFB3261E)

    // --- FinTech canvas -------------------------------------------------------
    /** Dashboard canvas (soft slate blue): the ambient background of the main area. */
    val appBackground = Color(0xFFEEF2F6)

    /** Clean slate headers on light surfaces. */
    val slateHeader = Color(0xFF0F172A)

    /** Muted slate subtitles on light surfaces. */
    val slateSubtitle = Color(0xFF64748B)

    /** Hairline borders and dividers (Slate 200), e.g. under the header bar. */
    val hairlineBorder = Color(0xFFE2E8F0)

    /** Zebra-stripe wash for alternate table rows (Slate 100). */
    val zebraStripe = Color(0xFFF1F5F9)

    // --- Status pills ----------------------------------------------------------
    /**
     * Healthy engine (Emerald 50 / 900): soft green fill, bold dark green text and a
     * bright green dot — the "ready to extract" pill in the header.
     */
    val emeraldContainer = Color(0xFFECFDF5)
    val onEmeraldContainer = Color(0xFF065F46)
    val emeraldBorder = Color(0xFFA7F3D0)
    val emeraldDot = Color(0xFF10B981)

    /** In-flight work (Blue 50 / 700), e.g. the batch PROCESSING chip. */
    val blueContainer = Color(0xFFEFF6FF)
    val onBlueContainer = Color(0xFF1D4ED8)
    val blueBorder = Color(0xFFBFDBFE)

    /** Idle queue state (Slate 100 / 700), e.g. the batch PENDING chip. */
    val slateContainer = Color(0xFFF1F5F9)
    val onSlateContainer = Color(0xFF334155)

    /** Client/project attribution (Indigo 50 / 800), e.g. the history tag pill. */
    val indigoContainer = Color(0xFFEEF2FF)
    val onIndigoContainer = Color(0xFF4338CA)

    /** Borders that lift the amber and red pills off a white card. */
    val amberBorder = Color(0xFFFDE68A)
    val redBorder = Color(0xFFFECACA)
}
