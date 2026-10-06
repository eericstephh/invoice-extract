package com.invoiceextract.desktop.domain.analytics

import com.invoiceextract.domain.model.Invoice
import com.invoiceextract.domain.model.PaymentStatus
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hermetic tests for [generateReminderMessage].
 *
 * The generator is pure, so each case is one invoice, one tone and one
 * language in, one message out. Every case asserts the interpolation contract
 * (names, number, amount, deadline all land in the text) plus the tone's own
 * register, in both Persian and English.
 */
class InvoiceFollowUpGeneratorTest {

    private fun invoice() = Invoice(
        id = "inv-1",
        invoiceNumber = "1024",
        date = "1403/05/20",
        sellerName = "آژانس نمونه",
        buyerName = "شرکت مشتری",
        clientName = "کارفرمای نمونه",
        grandTotal = 1_250_000.0,
        dueDate = "1403/06/31",
        paymentStatus = PaymentStatus.PENDING,
    )

    @Test
    fun `friendly persian reminder names everyone and the money`() {
        val text = generateReminderMessage(invoice(), FollowUpTone.FRIENDLY, isEnglish = false)

        assertTrue(text.contains("کارفرمای نمونه"))
        assertTrue(text.contains("آژانس نمونه"))
        assertTrue(text.contains("1024"))
        assertTrue(text.contains("1,250,000"))
        assertTrue(text.contains("تومان"))
        assertTrue(text.contains("1403/06/31"))
        assertTrue(text.contains("دوستانه") || text.contains("یادآوری دوستانه"))
    }

    @Test
    fun `formal persian reminder reads as correspondence`() {
        val text = generateReminderMessage(invoice(), FollowUpTone.FORMAL, isEnglish = false)

        assertTrue(text.contains("کارفرمای نمونه"))
        assertTrue(text.contains("1024"))
        assertTrue(text.contains("1,250,000"))
        assertTrue(text.contains("احتراماً"))
    }

    @Test
    fun `urgent persian reminder names the deadline pressure`() {
        val text = generateReminderMessage(invoice(), FollowUpTone.URGENT, isEnglish = false)

        assertTrue(text.contains("1024"))
        assertTrue(text.contains("1,250,000"))
        assertTrue(text.contains("۴۸ ساعت"))
    }

    @Test
    fun `friendly english reminder names everyone and the money`() {
        val text = generateReminderMessage(invoice(), FollowUpTone.FRIENDLY, isEnglish = true)

        assertTrue(text.contains("کارفرمای نمونه"))
        assertTrue(text.contains("آژانس نمونه"))
        assertTrue(text.contains("1024"))
        assertTrue(text.contains("1,250,000"))
        assertTrue(text.contains("Toman"))
        assertTrue(text.contains("friendly"))
    }

    @Test
    fun `formal english reminder reads as correspondence`() {
        val text = generateReminderMessage(invoice(), FollowUpTone.FORMAL, isEnglish = true)

        assertTrue(text.contains("1024"))
        assertTrue(text.contains("1,250,000"))
        assertTrue(text.contains("kindly inform"))
    }

    @Test
    fun `urgent english reminder names the time box`() {
        val text = generateReminderMessage(invoice(), FollowUpTone.URGENT, isEnglish = true)

        assertTrue(text.contains("1024"))
        assertTrue(text.contains("48 hours"))
    }

    @Test
    fun `missing fields degrade to placeholders instead of blanks`() {
        val bare = Invoice(id = "bare", grandTotal = 500_000.0)

        val fa = generateReminderMessage(bare, FollowUpTone.FORMAL, isEnglish = false)
        assertTrue(fa.contains("مشتری گرامی"))
        assertTrue(fa.contains("آژانس ما"))
        assertTrue(fa.contains("500,000"))

        val en = generateReminderMessage(bare, FollowUpTone.FORMAL, isEnglish = true)
        assertTrue(en.contains("valued client"))
        assertTrue(en.contains("our agency"))
    }

    @Test
    fun `no due date means no dangling deadline clause`() {
        val text = generateReminderMessage(
            invoice().copy(dueDate = null),
            FollowUpTone.FRIENDLY,
            isEnglish = true,
        )

        assertTrue(!text.contains("due null"))
        assertTrue(text.contains("1024"))
    }
}
