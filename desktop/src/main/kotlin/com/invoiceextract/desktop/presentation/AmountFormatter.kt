package com.invoiceextract.desktop.presentation

import com.invoiceextract.domain.model.CurrencyType
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

/**
 * Formatting of invoice amounts for the desktop window.
 *
 * Grouping is locale-independent on purpose: [Locale.ROOT] with a plain ASCII separator
 * keeps the digits unambiguous both on screen and in any copy-paste into another tool.
 * An RTL layout reshapes *around* the number; it does not change the digits themselves.
 *
 * Kept dependency-free and side-effect-free: the formatters are thread-safe singletons,
 * so one window and its export paths share them.
 */
internal object AmountFormatter {

    /** Grouped thousands, no decimals: Toman has no subunit, so `1250000` → `1,250,000`. */
    private val grouped: DecimalFormat = DecimalFormat("#,###", DecimalFormatSymbols(Locale.ROOT))

    /**
     * Formats a monetary amount. Non-finite values — which can only come from a corrupt
     * model — degrade to a bare zero rather than leaking `NaN` or `Infinity` into the UI.
     */
    fun formatToman(amount: Double): String = formatFinite(amount) { grouped.format(it.toLong()) }

    /**
     * Western rendering of a dashboard amount: `$12,450.00`. Locale-proof by
     * construction (`Locale.ROOT`), because a German decimal comma inside an
     * English dashboard would read as a different number.
     *
     * The dashboard aggregates in Toman everywhere (see `effectiveTomanTotal`),
     * so this changes the rendering only — the denomination stays documented
     * on the KPI labels — never the money.
     */
    fun formatUsd(amount: Double): String =
        if (amount.isFinite()) {
            "$" + groupedUsd.format(amount)
        } else {
            "$0.00"
        }

    /** Grouped thousands with exactly two decimals: `1250000` → `1,250,000.00`. */
    private val groupedUsd: DecimalFormat =
        DecimalFormat("#,##0.00", DecimalFormatSymbols(Locale.ROOT))

    /**
     * Formats a quantity, which may legitimately be fractional (`1.5` metres). Integer
     * values drop the trailing `.0` to read like the document does.
     */
    fun formatQuantity(quantity: Double): String =
        formatFinite(quantity) { if (it % 1.0 == 0.0) it.toLong().toString() else it.toString() }

    private inline fun formatFinite(value: Double, block: (Double) -> String): String =
        if (value.isFinite()) block(value) else "0"
}

/** Persian label for a [CurrencyType], for the totals card and the export headers. */
internal fun CurrencyType.toPersianLabel(): String = when (this) {
    CurrencyType.TOMAN -> "تومان"
    CurrencyType.RIAL -> "ریال"
    CurrencyType.USD -> "دلار"
    CurrencyType.EUR -> "یورو"
    CurrencyType.USDT -> "تتر"
    CurrencyType.UNKNOWN -> "نامشخص"
}
