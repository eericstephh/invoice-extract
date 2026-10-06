package com.invoiceextract.desktop.domain.analytics

import com.invoiceextract.domain.model.Invoice

/**
 * Which way a petty-cash reconciliation leans.
 */
enum class SettlementStatus {
    /** Advance covers expenses exactly: nothing changes hands. */
    BALANCED,

    /** Advance exceeds expenses: the remainder returns to the client. */
    SURPLUS,

    /** Expenses exceed the advance: the holder is owed the difference. */
    DEFICIT,
}

/**
 * The reconciliation of one cash advance against its project expenses, in Toman.
 *
 * @property advanceAmount The imprest received, in Toman.
 * @property totalExpenses Attached invoices summed via [Invoice.effectiveTomanTotal].
 * @property balance `advanceAmount - totalExpenses`: positive is a surplus to
 *   return, negative a deficit owed to the holder.
 * @property status The [SettlementStatus] read off [balance].
 * @property invoiceCount How many invoices the reconciliation covers.
 */
data class PettyCashSettlement(
    val advanceAmount: Long,
    val totalExpenses: Long,
    val balance: Long,
    val status: SettlementStatus,
    val invoiceCount: Int,
)

/**
 * Reconciles a cash advance against [invoices].
 *
 * Pure: no I/O, no clock, no mutable state, so the same advance and archive
 * always yield the same settlement. Every invoice counts at its
 * [Invoice.effectiveTomanTotal], so foreign and Rial receipts join local ones
 * without currency mixing.
 */
fun computePettyCashSettlement(
    advanceAmount: Long,
    invoices: List<Invoice>,
): PettyCashSettlement {
    val totalExpenses = invoices.sumOf { it.effectiveTomanTotal }
    val balance = advanceAmount - totalExpenses
    val status = when {
        balance == 0L -> SettlementStatus.BALANCED
        balance > 0L -> SettlementStatus.SURPLUS
        else -> SettlementStatus.DEFICIT
    }
    return PettyCashSettlement(
        advanceAmount = advanceAmount,
        totalExpenses = totalExpenses,
        balance = balance,
        status = status,
        invoiceCount = invoices.size,
    )
}
