package com.invoiceextract.desktop.presentation.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hermetic tests for the clay palette contract.
 *
 * Colors are plain values, so every case asserts on the tokens directly: the
 * mode flag, the spec's exact text/surface values, badge text distinct from
 * badge wash in both modes, and the light/dark palettes actually differing —
 * a copy-paste palette would render one mode unreadable and must fail here.
 */
class ClayThemeTest {

    @Test
    fun `mode flags match their factory`() {
        assertFalse(lightClayColors().isDarkMode)
        assertTrue(darkClayColors().isDarkMode)
        assertFalse(clayColors(false).isDarkMode)
        assertTrue(clayColors(true).isDarkMode)
    }

    @Test
    fun `text and surface tokens match the spec`() {
        val light = lightClayColors()
        assertEquals(Color(0xFF0F172A), light.textPrimary)
        assertEquals(Color(0xFF475569), light.textSecondary)
        assertEquals(Color(0xFF64748B), light.textTertiary)
        assertEquals(Color(0xF0FFFFFF), light.surfaceCard)
        assertEquals(Color(0x80FFFFFF), light.inputContainer)
        assertEquals(Color(0xFFCBD5E1), light.inputBorder)
        assertEquals(Color(0xFFF8FAFC), light.tableRowZebra)

        val dark = darkClayColors()
        assertEquals(Color(0xFFF8FAFC), dark.textPrimary)
        assertEquals(Color(0xFFCBD5E1), dark.textSecondary)
        assertEquals(Color(0xFF94A3B8), dark.textTertiary)
        assertEquals(Color(0xFF1E293B), dark.surfaceCard)
        assertEquals(Color(0xFF0F172A), dark.inputContainer)
        assertEquals(Color(0xFF475569), dark.inputBorder)
        assertEquals(Color(0xFF172033), dark.tableRowZebra)
    }

    @Test
    fun `badge text stays distinct from its wash`() {
        listOf(lightClayColors(), darkClayColors()).forEach { clay ->
            listOf(clay.badgeGreen, clay.badgeAmber, clay.badgeRed, clay.badgeBlue)
                .forEach { badge ->
                    assertNotEquals(badge.background, badge.text)
                }
        }
    }

    @Test
    fun `dark badges glow translucent with bright text`() {
        val dark = darkClayColors()
        assertEquals(Color(0x2610B981), dark.badgeGreen.background)
        assertEquals(Color(0xFF34D399), dark.badgeGreen.text)
        assertEquals(Color(0x26F59E0B), dark.badgeAmber.background)
        assertEquals(Color(0xFFFBBF24), dark.badgeAmber.text)
        assertEquals(Color(0x26EF4444), dark.badgeRed.background)
        assertEquals(Color(0xFFF87171), dark.badgeRed.text)
        assertEquals(Color(0x2638BDF8), dark.badgeBlue.background)
        assertEquals(Color(0xFF38BDF8), dark.badgeBlue.text)
    }

    @Test
    fun `light and dark palettes differ everywhere it matters`() {
        val light = lightClayColors()
        val dark = darkClayColors()
        assertNotEquals(light.textPrimary, dark.textPrimary)
        assertNotEquals(light.textSecondary, dark.textSecondary)
        assertNotEquals(light.surfaceCard, dark.surfaceCard)
        assertNotEquals(light.inputContainer, dark.inputContainer)
        assertNotEquals(light.tableRowZebra, dark.tableRowZebra)
        assertNotEquals(light.badgeGreen.text, dark.badgeGreen.text)
        assertNotEquals(light.badgeAmber.text, dark.badgeAmber.text)
        assertNotEquals(light.badgeRed.text, dark.badgeRed.text)
        assertNotEquals(light.badgeBlue.text, dark.badgeBlue.text)
    }
}
