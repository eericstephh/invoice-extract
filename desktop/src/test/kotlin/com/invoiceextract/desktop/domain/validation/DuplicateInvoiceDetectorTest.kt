package com.invoiceextract.desktop.domain.validation

import com.invoiceextract.desktop.data.text.DesktopPersianNormalizer
import com.invoiceextract.domain.model.Invoice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Hermetic JVM tests for [DuplicateInvoiceDetector].
 *
 * The detector is pure comparison over in-memory lists — no daemon, no store, no
 * dispatcher — so each case is one target plus one archive, asserted on the verdict.
 * The digit-style case is the load-bearing one: invoice numbers routinely arrive half
 * in Persian digits and half in ASCII, and the guard must see through that.
 */
class DuplicateInvoiceDetectorTest {

    private val detector = DuplicateInvoiceDetector(DesktopPersianNormalizer())

    @Test
    fun `exact number match across persian and ascii digit styles`() {
        val archived = invoice(id = "old", number = "1001", seller = "شرکت نمونه")

        val match = detector.detectDuplicate(
            target = invoice(id = "new", number = "۱۰۰۱", seller = "شرکت نمونه"),
            existingInvoices = listOf(archived),
        )

        assertEquals(DuplicateMatch(archived, DuplicateReason.EXACT_NUMBER), match)
    }

    @Test
    fun `exact number tolerates spacing and arabic letter variants`() {
        val archived = invoice(id = "old", number = "1001", seller = "شركت نمونه")

        val match = detector.detectDuplicate(
            target = invoice(id = "new", number = " 1001 ", seller = "شرکت نمونه"),
            existingInvoices = listOf(archived),
        )

        assertEquals(DuplicateReason.EXACT_NUMBER, match?.reason)
    }

    @Test
    fun `fingerprint matches seller date and amount without a number`() {
        val archived = invoice(
            id = "old",
            number = null,
            seller = "شرکت نمونه",
            date = "1403/05/20",
            total = 2_000_000.0,
        )

        val match = detector.detectDuplicate(
            target = invoice(
                id = "new",
                number = null,
                seller = "شرکت نمونه",
                date = "1403/05/20",
                total = 2_000_000.0,
            ),
            existingInvoices = listOf(archived),
        )

        assertEquals(DuplicateMatch(archived, DuplicateReason.FINGERPRINT_MATCH), match)
    }

    @Test
    fun `exact number wins over fingerprint`() {
        val byNumber = invoice(id = "by-number", number = "1001", seller = "شرکت نمونه")
        val byPrint = invoice(
            id = "by-print",
            number = "9999",
            seller = "شرکت نمونه",
            date = "1403/05/20",
            total = 2_000_000.0,
        )

        val match = detector.detectDuplicate(
            target = invoice(
                id = "new",
                number = "1001",
                seller = "شرکت نمونه",
                date = "1403/05/20",
                total = 2_000_000.0,
            ),
            existingInvoices = listOf(byPrint, byNumber),
        )

        assertEquals(DuplicateReason.EXACT_NUMBER, match?.reason)
        assertEquals("by-number", match?.existingInvoice?.id)
    }

    @Test
    fun `same number from a different seller is not a duplicate`() {
        val archived = invoice(id = "old", number = "1001", seller = "شرکت الف")

        val match = detector.detectDuplicate(
            target = invoice(
                id = "new",
                number = "1001",
                seller = "شرکت ب",
                date = "1403/05/20",
                total = 2_000_000.0,
            ),
            existingInvoices = listOf(archived),
        )

        assertNull(match)
    }

    @Test
    fun `same seller and date with a different amount is not a duplicate`() {
        val archived = invoice(
            id = "old",
            number = null,
            seller = "شرکت نمونه",
            date = "1403/05/20",
            total = 2_000_000.0,
        )

        val match = detector.detectDuplicate(
            target = invoice(
                id = "new",
                number = null,
                seller = "شرکت نمونه",
                date = "1403/05/20",
                total = 3_000_000.0,
            ),
            existingInvoices = listOf(archived),
        )

        assertNull(match)
    }

    @Test
    fun `editing a saved invoice does not flag itself`() {
        val stored = invoice(
            id = "kept",
            number = "1001",
            seller = "شرکت نمونه",
            date = "1403/05/20",
            total = 2_000_000.0,
        )

        // The edited copy keeps the stored id, as the view model does on every edit.
        val match = detector.detectDuplicate(
            target = stored.copy(date = "1403/05/21"),
            existingInvoices = listOf(stored),
        )

        assertNull(match)
    }

    @Test
    fun `blank numbers never match by number`() {
        val archived = invoice(id = "old", number = null, seller = "شرکت نمونه")

        // Same seller, different date and amount: no rule may fire on blanks alone.
        val match = detector.detectDuplicate(
            target = invoice(
                id = "new",
                number = "  ",
                seller = "شرکت نمونه",
                date = "1403/06/01",
                total = 5_000_000.0,
            ),
            existingInvoices = listOf(archived),
        )

        assertNull(match)
    }

    private fun invoice(
        id: String,
        number: String?,
        seller: String?,
        date: String? = null,
        total: Double = 0.0,
    ): Invoice = Invoice(
        id = id,
        invoiceNumber = number,
        date = date,
        sellerName = seller,
        grandTotal = total,
    )
}
