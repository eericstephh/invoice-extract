package com.invoiceextract.desktop.domain

import com.invoiceextract.domain.model.InvoiceItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hermetic tests for [InvoiceLineItemSanitizer].
 *
 * Every summary keyword from the contract gets a ghost row that must fall out,
 * while genuine goods — including bulk lines that merely mention a keyword —
 * must survive untouched and in order.
 */
class InvoiceLineItemSanitizerTest {

    @Test
    fun `persian summary rows are stripped`() {
        val items = listOf(
            product("هدفون بی‌سیم"),
            ghost("جمع جزء"),
            ghost("جزء جمع"),
            ghost("جمع کل"),
            ghost("سرجمع"),
            ghost("تخفیف کل"),
            ghost("مالیات بر ارزش افزوده"),
            ghost("مجموع فاکتور"),
        )

        val cleaned = InvoiceLineItemSanitizer.sanitize(items)

        assertEquals(listOf("هدفون بی‌سیم"), cleaned.map { it.name })
    }

    @Test
    fun `latin summary rows match case insensitively`() {
        val items = listOf(
            ghost("SUBTOTAL"),
            ghost("Grand Total"),
            ghost("TOTAL DISCOUNT"),
        )

        assertTrue(InvoiceLineItemSanitizer.sanitize(items).isEmpty())
    }

    @Test
    fun `arabic orthography and invisible joiners still match`() {
        val items = listOf(
            ghost("جمع كل"),
            ghost("سر‌جمع"),
            ghost("ماليات بر ارزش افزوده"),
        )

        assertTrue(InvoiceLineItemSanitizer.sanitize(items).isEmpty())
    }

    @Test
    fun `a bulk line that merely mentions a keyword survives`() {
        val bulk = InvoiceItem(
            id = "bulk",
            name = "پک تخفیف کل ویژه",
            quantity = 5.0,
            unitPrice = 200_000.0,
            discount = 0.0,
            tax = 0.0,
            totalPrice = 1_000_000.0,
            confidence = 0.9f,
        )

        assertFalse(InvoiceLineItemSanitizer.isSummaryRow(bulk))
        assertEquals(listOf(bulk), InvoiceLineItemSanitizer.sanitize(listOf(bulk)))
    }

    @Test
    fun `genuine goods never match`() {
        val items = listOf(
            product("کابل شارژ"),
            product("  "),
            product("Grand"),
        )

        assertEquals(items, InvoiceLineItemSanitizer.sanitize(items))
    }

    @Test
    fun `single unit ghost with tax still falls out`() {
        val ghost = InvoiceItem(
            id = "g",
            name = "جمع کل",
            quantity = 1.0,
            unitPrice = 100.0,
            discount = 0.0,
            tax = 5.0,
            totalPrice = 105.0,
            confidence = 0.9f,
        )

        assertTrue(InvoiceLineItemSanitizer.isSummaryRow(ghost))
    }

    /** A routine good: distinct quantity and unit breakdown, never a ghost. */
    private fun product(name: String): InvoiceItem = InvoiceItem(
        id = "p-$name",
        name = name,
        quantity = 2.0,
        unitPrice = 500_000.0,
        discount = 0.0,
        tax = 0.0,
        totalPrice = 1_000_000.0,
        confidence = 0.9f,
    )

    /** The classic ghost: one unit, unit price equal to the line total. */
    private fun ghost(name: String): InvoiceItem = InvoiceItem(
        id = "g-$name",
        name = name,
        quantity = 1.0,
        unitPrice = 1_000_000.0,
        discount = 0.0,
        tax = 0.0,
        totalPrice = 1_000_000.0,
        confidence = 0.9f,
    )
}
