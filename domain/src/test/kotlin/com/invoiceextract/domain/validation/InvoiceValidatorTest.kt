package com.invoiceextract.domain.validation

import com.invoiceextract.domain.model.CurrencyType
import com.invoiceextract.domain.model.Invoice
import com.invoiceextract.domain.model.InvoiceItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hermetic unit tests for [InvoiceValidator] (Phase 15.1).
 *
 * The validator is a pure function — no clock, no I/O, no Android — so every test is one
 * `validate` call against a hand-built [Invoice] plus assertions over the returned copy.
 * Each case breaks exactly one rule and asserts that rule's own severity, so a failure
 * points at a single condition instead of "validation is broken".
 *
 * Every fixture is built through [aValidInvoice], an invoice that passes every rule;
 * a test then mutates the one field that introduces the defect under test. That keeps
 * each case's preconditions explicit instead of hiding them in a shared `@Before`, and it
 * is what makes "no other rule fired" a meaningful assertion rather than a guess.
 *
 * Runs on a plain JVM — [InvoiceValidator] and the domain models are pure Kotlin.
 */
class InvoiceValidatorTest {

    private val validator = InvoiceValidator()

    @Test
    fun validInvoice_returnsValidStatus() {
        val invoice = aValidInvoice()

        val result = validator.validate(invoice)

        assertEquals(
            "an invoice with clean arithmetic and every mandatory field is valid",
            ValidationStatus.Valid,
            result.validationStatus,
        )
        result.items.forEach { item ->
            assertFalse("a clean line must not be flagged suspicious", item.isSuspicious)
        }
        // The validator must never mutate its input: the extraction layer's original
        // verdict is an audit trail and has to survive validation untouched.
        invoice.items.forEach { item ->
            assertFalse("the input invoice must be left untouched", item.isSuspicious)
        }
    }

    @Test
    fun mismatchedItemTotal_flagsSuspiciousAndWarning() {
        // Printed line total is 3000 but quantity * unitPrice is 2000: a digit flipped
        // somewhere in OCR. The invoice footer sums the *printed* 3000, so the invoice
        // totals still reconcile — the only failing rule is the line arithmetic.
        val printedTotal = 3_000.0
        val invoice = anInvoice(
            items = listOf(
                anItem(id = "i1", quantity = 2.0, unitPrice = 1_000.0, totalPrice = printedTotal),
            ),
            subtotal = printedTotal,
            grandTotal = printedTotal,
        )

        val result = validator.validate(invoice)

        assertTrue("the mismatched line must be flagged for review", result.items.single().isSuspicious)
        val status = result.validationStatus
        assertTrue("a non-critical issue must downgrade the verdict to Warning",
            status is ValidationStatus.Warning)
        val issues = (status as ValidationStatus.Warning).reasons
        assertEquals("only the arithmetic rule should fire", 1, issues.size)
        val issue = issues.single()
        assertEquals("the offending field must be pinpointed", "items[0].totalPrice", issue.field)
        assertEquals(IssueSeverity.WARNING, issue.severity)
    }

    @Test
    fun lowConfidenceItem_flagsSuspicious() {
        // 0.60 sits below the 0.70 confidence floor. The line may well be perfectly
        // legible, so the issue is INFO and the invoice stays usable (Warning), but the
        // line is still flagged for a human to glance at.
        val invoice = anInvoice(
            items = listOf(
                anItem(id = "i1", quantity = 2.0, unitPrice = 1_000.0, totalPrice = 2_000.0, confidence = 0.60f),
            ),
            subtotal = 2_000.0,
            grandTotal = 2_000.0,
        )

        val result = validator.validate(invoice)

        assertTrue("an uncertain line must be flagged for review", result.items.single().isSuspicious)
        assertTrue("low confidence is informational, not invalid",
            result.validationStatus is ValidationStatus.Warning)
        val confidenceIssue = issuesOf(result).single { it.field == "items[0].confidence" }
        assertEquals(IssueSeverity.INFO, confidenceIssue.severity)
    }

    @Test
    fun mismatchedGrandTotal_flagsWarning() {
        // grandTotal is 9390 above subtotal + totalTax - totalDiscount, far past the
        // 1.0 absolute tolerance. A relative epsilon would have swallowed a far larger
        // error here, which is exactly why the tolerance is absolute.
        val invoice = aValidInvoice().copy(grandTotal = 40_000.0)

        val result = validator.validate(invoice)

        val grandTotalIssue = issuesOf(result).singleOrNull { it.field == "grandTotal" }
        assertNotNull("a diverging grand total must be reported", grandTotalIssue)
        assertEquals(IssueSeverity.WARNING, grandTotalIssue!!.severity)
        assertTrue("a totals mismatch must not invalidate the whole invoice",
            result.validationStatus is ValidationStatus.Warning)
    }

    @Test
    fun missingCrucialFields_flagsCriticalInvalid() {
        // (a) No line items at all: there is nothing to invoice, so the record is
        // unusable regardless of how tidy the rest of it looks.
        val emptyItems = anInvoice(items = emptyList(), grandTotal = 10_000.0)
        val emptyResult = validator.validate(emptyItems)
        assertTrue("an empty item list must invalidate the invoice",
            emptyResult.validationStatus is ValidationStatus.Invalid)
        val criticalForItems = (emptyResult.validationStatus as ValidationStatus.Invalid).criticalErrors
        assertTrue(
            "the empty item list must be reported as critical",
            criticalForItems.any { it.field == "items" && it.severity == IssueSeverity.CRITICAL },
        )

        // (b) A grand total of zero or less makes the payable amount meaningless. This is
        // the case that must never be persisted as a financial record.
        val invoice = anInvoice(
            items = listOf(anItem(id = "i1", quantity = 1.0, unitPrice = 2_000.0, totalPrice = 2_000.0)),
            subtotal = 2_000.0,
            grandTotal = 0.0,
        )
        val zeroResult = validator.validate(invoice)
        assertTrue("a non-positive grand total must invalidate the invoice",
            zeroResult.validationStatus is ValidationStatus.Invalid)
        val criticalForTotal = (zeroResult.validationStatus as ValidationStatus.Invalid).criticalErrors
        assertTrue(
            "the non-positive grand total must be reported as critical",
            criticalForTotal.any { it.field == "grandTotal" && it.severity == IssueSeverity.CRITICAL },
        )
    }

    // ------------------------------------------------------------------ fixtures

    /** Every issue the validator reported for [result], or an empty list when it is valid. */
    private fun issuesOf(result: Invoice): List<ValidationIssue> = when (val status = result.validationStatus) {
        is ValidationStatus.Valid -> emptyList()
        is ValidationStatus.Warning -> status.reasons
        is ValidationStatus.Invalid -> status.criticalErrors
    }

    /**
     * An invoice that passes every rule: three lines whose totals equal
     * `quantity * unitPrice`, confidence above the floor on every line, and a grand total
     * that reconciles exactly with `subtotal + totalTax - totalDiscount`.
     */
    private fun aValidInvoice(): Invoice {
        val items = listOf(
            anItem(id = "i1", quantity = 2.0, unitPrice = 1_000.0, totalPrice = 2_000.0, confidence = 0.95f),
            anItem(id = "i2", quantity = 3.0, unitPrice = 5_000.0, totalPrice = 15_000.0, confidence = 0.90f),
            anItem(id = "i3", quantity = 1.0, unitPrice = 12_000.0, totalPrice = 12_000.0, confidence = 0.85f),
        )
        val subtotal = 29_000.0 // 2_000 + 15_000 + 12_000
        val totalTax = 2_610.0 // 9% VAT
        val totalDiscount = 1_000.0
        return anInvoice(
            items = items,
            subtotal = subtotal,
            totalTax = totalTax,
            totalDiscount = totalDiscount,
            grandTotal = subtotal + totalTax - totalDiscount, // 30_610
        )
    }

    private fun anInvoice(
        items: List<InvoiceItem> = emptyList(),
        subtotal: Double = 0.0,
        totalTax: Double = 0.0,
        totalDiscount: Double = 0.0,
        grandTotal: Double = subtotal + totalTax - totalDiscount,
    ): Invoice = Invoice(
        id = "invoice-1",
        invoiceNumber = "10234567890",
        date = "1403/07/15",
        sellerName = "فروشگاه نمونه",
        sellerTaxId = "10234567890",
        buyerName = "خریدار نمونه",
        buyerTaxId = "10987654321",
        items = items,
        subtotal = subtotal,
        totalTax = totalTax,
        totalDiscount = totalDiscount,
        grandTotal = grandTotal,
        currency = CurrencyType.TOMAN,
    )

    private fun anItem(
        id: String = "item",
        name: String = "کالا",
        quantity: Double = 1.0,
        unitPrice: Double = 0.0,
        discount: Double = 0.0,
        tax: Double = 0.0,
        totalPrice: Double = quantity * unitPrice - discount + tax,
        confidence: Float = 0.90f,
    ): InvoiceItem = InvoiceItem(
        id = id,
        name = name,
        quantity = quantity,
        unitPrice = unitPrice,
        discount = discount,
        tax = tax,
        totalPrice = totalPrice,
        confidence = confidence,
    )
}
