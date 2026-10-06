package com.invoiceextract.desktop.presentation.ui.hotkeys

import androidx.compose.ui.input.key.Key

/**
 * One row of the shortcuts cheatsheet: the keycap legend plus the action
 * title in both window languages. The dialog picks the side matching the
 * active [com.invoiceextract.desktop.presentation.ui.theme.AppLanguage].
 */
data class ShortcutItem(
    val keyCombination: String,
    val titleFa: String,
    val titleEn: String,
)

/**
 * The window actions reachable by keyboard, in cheatsheet order.
 *
 * Kept separate from [ShortcutItem] on purpose: the matcher below is a pure
 * function of modifier flags and a [Key], unit-testable on a plain JVM with
 * no window, while the items are display copy.
 */
enum class HotkeyAction {
    OPEN_SINGLE,
    OPEN_BATCH,
    SAVE,
    PRINT,
    EXPORT_EXCEL,
    HISTORY,
    THEME,
    LANGUAGE,
    BACK,
    HELP,
}

/**
 * Every shortcut the window honors, cheatsheet order. The matching contract
 * lives in [matchHotkey]; this list is display copy only, so adding a row
 * here without a matcher branch shows a shortcut that does nothing — keep
 * the two in sync.
 */
val ALL_SHORTCUTS: List<ShortcutItem> = listOf(
    ShortcutItem("Ctrl + O", "انتخاب یک فاکتور", "Open Invoice"),
    ShortcutItem("Ctrl + Shift + O", "انتخاب گروهی", "Batch Open"),
    ShortcutItem("Ctrl + S", "ذخیره فاکتور", "Save Invoice"),
    ShortcutItem("Ctrl + P", "چاپ فاکتور رسمی", "Print Formal A4"),
    ShortcutItem("Ctrl + E", "خروجی اکسل", "Export Excel"),
    ShortcutItem("Ctrl + H", "سوابق و آرشیو", "Invoice History"),
    ShortcutItem("Ctrl + D", "تغییر تم شب/روز", "Toggle Theme"),
    ShortcutItem("Ctrl + L", "تغییر زبان", "Toggle Language"),
    ShortcutItem("Esc", "بازگشت به اسکنر / بستن", "Back / Close"),
    ShortcutItem("F1", "راهنمای کلیدها", "Shortcuts Help"),
)

/**
 * Matches a key press to its window action, or `null` when the press is not
 * a shortcut.
 *
 * Pure function of primitives and [Key] constants — no `KeyEvent`, no window —
 * so the whole contract is unit-testable on a plain JVM. Rules:
 * - Only key-down presses match; releases return `null`.
 * - `F1` and `Esc` match without modifiers only: `Ctrl+Esc` belongs to the OS
 *   (Start menu) and must never be swallowed.
 * - `Ctrl+Shift+O` is the sole shift shortcut; any other shifted chord is
 *   left alone for future use.
 * - `Ctrl+Slash` is the keyboard-layout-proof alias of `F1`.
 */
fun matchHotkey(
    ctrlPressed: Boolean,
    shiftPressed: Boolean,
    key: Key,
    keyDown: Boolean,
): HotkeyAction? {
    if (!keyDown) return null

    if (!ctrlPressed) {
        return when (key) {
            Key.F1 -> HotkeyAction.HELP
            Key.Escape -> HotkeyAction.BACK
            else -> null
        }
    }

    if (shiftPressed) {
        return if (key == Key.O) HotkeyAction.OPEN_BATCH else null
    }

    return when (key) {
        Key.O -> HotkeyAction.OPEN_SINGLE
        Key.S -> HotkeyAction.SAVE
        Key.P -> HotkeyAction.PRINT
        Key.E -> HotkeyAction.EXPORT_EXCEL
        Key.H -> HotkeyAction.HISTORY
        Key.D -> HotkeyAction.THEME
        Key.L -> HotkeyAction.LANGUAGE
        Key.Slash -> HotkeyAction.HELP
        else -> null
    }
}
