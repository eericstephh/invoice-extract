package com.invoiceextract.desktop.domain.reconciliation

import com.invoiceextract.domain.model.Invoice
import com.invoiceextract.domain.model.PaymentStatus
import com.invoiceextract.domain.model.isUnpaid

/**
 * One parsed line of a bank statement: a single movement of money.
 *
 * @property rawDate The date cell verbatim (Jalali or Gregorian — kept raw like
 *   [Invoice.date], because parsing it is a presentation concern).
 * @property amount Signed Toman: positive for deposits, negative for withdrawals.
 * @property description The bank's narration cell verbatim.
 * @property trackingNumber The bank tracking/reference cell, when the export
 *   carries one; `null` otherwise.
 */
data class BankTransaction(
    val rawDate: String,
    val amount: Long,
    val description: String,
    val trackingNumber: String? = null,
)

/**
 * One invoice-to-deposit link the matcher is confident about.
 *
 * @property confidence Always `1.0f` today: a match requires an exact Toman
 *   equality, so there is no weaker grade to express. Kept as a field (rather
 *   than assumed by callers) so a future date-window or tracking-number tie
 *   can lower it without changing the shape. Date proximity is deliberately
 *   *not* scored: both sides keep raw, mixed-calendar strings, and any
 *   proximity computed on unparsed text would be a guess wearing a number.
 */
data class ReconciliationMatch(
    val invoice: Invoice,
    val transaction: BankTransaction,
    val confidence: Float = 1.0f,
)

/**
 * The whole reconciliation answer: what linked, what is still open on each
 * side, and the Toman value of the linked half.
 */
data class ReconciliationResult(
    val matched: List<ReconciliationMatch>,
    val unmatchedInvoices: List<Invoice>,
    val unmatchedTransactions: List<BankTransaction>,
    val totalMatchedAmount: Long = matched.sumOf { it.invoice.effectiveTomanTotal },
)

/**
 * Links open receivables to bank deposits by exact amount.
 *
 * Pure and deterministic: the same statement over the same archive always
 * yields the same links, so re-running reconciliation never surprises the
 * ledger. Only [PaymentStatus.PENDING] and [PaymentStatus.OVERDUE] invoices
 * participate — paid records are settled history, not open questions — and
 * only positive deposits can settle anything.
 *
 * Each deposit funds at most one invoice (first unpaid invoice in archive
 * order wins), so two identical bills never both claim the same transfer.
 * Amounts compare in Toman via [Invoice.effectiveTomanTotal], which is what
 * lets a dollar invoice match its Rial-denominated deposit line.
 */
fun matchStatement(
    transactions: List<BankTransaction>,
    pendingInvoices: List<Invoice>,
): ReconciliationResult {
    val open = pendingInvoices.filter { it.isUnpaid }
    val used = BooleanArray(transactions.size)
    val matched = mutableListOf<ReconciliationMatch>()
    val unmatchedInvoices = mutableListOf<Invoice>()

    for (invoice in open) {
        val want = invoice.effectiveTomanTotal
        if (want <= 0L) {
            unmatchedInvoices.add(invoice)
            continue
        }
        var link: Int? = null
        for (index in transactions.indices) {
            val tx = transactions[index]
            if (!used[index] && tx.amount == want) {
                link = index
                break
            }
        }
        if (link == null) {
            unmatchedInvoices.add(invoice)
        } else {
            used[link] = true
            matched.add(ReconciliationMatch(invoice, transactions[link]))
        }
    }

    val unmatchedTransactions = transactions.filterIndexed { index, _ -> !used[index] }
    return ReconciliationResult(
        matched = matched,
        unmatchedInvoices = unmatchedInvoices,
        unmatchedTransactions = unmatchedTransactions,
    )
}
