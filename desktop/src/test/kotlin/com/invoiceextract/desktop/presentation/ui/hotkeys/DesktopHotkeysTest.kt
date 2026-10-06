package com.invoiceextract.desktop.presentation.ui.hotkeys

import androidx.compose.ui.input.key.Key
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hermetic JVM tests for the hotkey contract.
 *
 * [matchHotkey] is a pure function of modifier flags and [Key] constants — no
 * `KeyEvent`, no window — so every chord, guard and negative asserts here on
 * a plain JVM in milliseconds.
 */
class DesktopHotkeysTest {

    @Test
    fun `ctrl letter chords map to their actions`() {
        assertEquals(HotkeyAction.OPEN_SINGLE, press(ctrl = true, key = Key.O))
        assertEquals(HotkeyAction.SAVE, press(ctrl = true, key = Key.S))
        assertEquals(HotkeyAction.PRINT, press(ctrl = true, key = Key.P))
        assertEquals(HotkeyAction.EXPORT_EXCEL, press(ctrl = true, key = Key.E))
        assertEquals(HotkeyAction.HISTORY, press(ctrl = true, key = Key.H))
        assertEquals(HotkeyAction.THEME, press(ctrl = true, key = Key.D))
        assertEquals(HotkeyAction.LANGUAGE, press(ctrl = true, key = Key.L))
    }

    @Test
    fun `ctrl shift o opens the batch picker`() {
        assertEquals(
            HotkeyAction.OPEN_BATCH,
            matchHotkey(ctrlPressed = true, shiftPressed = true, key = Key.O, keyDown = true),
        )
    }

    @Test
    fun `other shifted chords stay unassigned`() {
        assertNull(matchHotkey(ctrlPressed = true, shiftPressed = true, key = Key.S, keyDown = true))
        assertNull(matchHotkey(ctrlPressed = true, shiftPressed = true, key = Key.E, keyDown = true))
        assertNull(matchHotkey(ctrlPressed = false, shiftPressed = true, key = Key.O, keyDown = true))
    }

    @Test
    fun `function keys match without modifiers`() {
        assertEquals(HotkeyAction.HELP, press(ctrl = false, key = Key.F1))
        assertEquals(HotkeyAction.BACK, press(ctrl = false, key = Key.Escape))
    }

    @Test
    fun `ctrl slash aliases f1`() {
        assertEquals(HotkeyAction.HELP, press(ctrl = true, key = Key.Slash))
    }

    @Test
    fun `modified f1 and escape stay unassigned`() {
        assertNull(matchHotkey(ctrlPressed = true, shiftPressed = false, key = Key.F1, keyDown = true))
        assertNull(matchHotkey(ctrlPressed = true, shiftPressed = false, key = Key.Escape, keyDown = true))
    }

    @Test
    fun `key releases never match`() {
        assertNull(matchHotkey(ctrlPressed = true, shiftPressed = false, key = Key.S, keyDown = false))
        assertNull(matchHotkey(ctrlPressed = false, shiftPressed = false, key = Key.F1, keyDown = false))
        assertNull(matchHotkey(ctrlPressed = false, shiftPressed = false, key = Key.Escape, keyDown = false))
    }

    @Test
    fun `plain typing never matches`() {
        assertNull(press(ctrl = false, key = Key.O))
        assertNull(press(ctrl = false, key = Key.S))
        assertNull(press(ctrl = false, key = Key.A))
        assertNull(press(ctrl = true, key = Key.A))
        assertNull(press(ctrl = true, key = Key.Enter))
    }

    @Test
    fun `cheatsheet lists every shortcut with both languages`() {
        assertEquals(10, ALL_SHORTCUTS.size)
        ALL_SHORTCUTS.forEach { shortcut ->
            assertTrue(shortcut.keyCombination.isNotBlank())
            assertTrue(shortcut.titleFa.isNotBlank())
            assertTrue(shortcut.titleEn.isNotBlank())
        }
    }

    private fun press(ctrl: Boolean, key: Key): HotkeyAction? =
        matchHotkey(ctrlPressed = ctrl, shiftPressed = false, key = key, keyDown = true)
}
