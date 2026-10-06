package com.invoiceextract.desktop.domain.analytics

import com.invoiceextract.domain.model.Invoice
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

/**
 * The voice of a payment reminder: how hard the message leans on the reader.
 */
enum class FollowUpTone {
    /** Warm nudge between partners who work together regularly. */
    FRIENDLY,

    /** Neutral business correspondence with full invoice references. */
    FORMAL,

    /** Final, time-boxed demand naming the overdue deadline. */
    URGENT,
}

/**
 * Builds the payment-reminder text the Follow-Up Copilot previews, copies and
 * hands to WhatsApp.
 *
 * Pure and total: the same invoice, tone and language always yield the same
 * message, so the preview, the clipboard and the WhatsApp draft can never
 * disagree. Interpolates the agency/seller name, the client/buyer name, the
 * invoice number, the Toman total and the due date; any missing field degrades
 * to a neutral placeholder instead of a blank, because a reminder with a hole
 * in it reads as a bug to the client receiving it.
 *
 * @param invoice The invoice being chased; amounts read off
 *   [Invoice.effectiveTomanTotal] so foreign money quotes in Toman.
 * @param tone How hard the message leans ([FollowUpTone]).
 * @param isEnglish `true` for the English copy, `false` for Persian.
 */
fun generateReminderMessage(
    invoice: Invoice,
    tone: FollowUpTone,
    isEnglish: Boolean,
): String {
    val agency = invoice.sellerName?.trim()?.takeIf { it.isNotBlank() }
        ?: if (isEnglish) "our agency" else "آژانس ما"
    val client = invoice.clientName?.trim()?.takeIf { it.isNotBlank() }
        ?: invoice.buyerName?.trim()?.takeIf { it.isNotBlank() }
        ?: if (isEnglish) "valued client" else "مشتری گرامی"
    val number = invoice.invoiceNumber?.trim()?.takeIf { it.isNotBlank() }
        ?: if (isEnglish) "—" else "—"
    val amount = formatTomanAmount(invoice.effectiveTomanTotal, isEnglish)
    val due = invoice.dueDate?.trim()?.takeIf { it.isNotBlank() }

    return if (isEnglish) {
        englishMessage(agency, client, number, amount, due, tone)
    } else {
        persianMessage(agency, client, number, amount, due, tone)
    }
}

private fun persianMessage(
    agency: String,
    client: String,
    number: String,
    amount: String,
    due: String?,
    tone: FollowUpTone,
): String {
    val dueClause = if (due != null) " با سررسید $due" else ""
    return when (tone) {
        FollowUpTone.FRIENDLY -> "سلام $client عزیز،\n" +
            "امیدوارم عالی باشید. این یک یادآوری دوستانه از طرف $agency است " +
            "بابت فاکتور شماره $number به مبلغ $amount$dueClause.\n" +
            "هر وقت فرصت کردید ممنون می‌شویم پرداخت را انجام دهید. " +
            "اگر سوالی درباره فاکتور دارید در خدمتم. 🙏"
        FollowUpTone.FORMAL -> "با سلام، $client گرامی؛\n" +
            "احتراماً به استحضار می‌رساند فاکتور شماره $number صادرشده از سوی $agency " +
            "به مبلغ $amount$dueClause تاکنون تسویه نشده است.\n" +
            "خواهشمند است دستور پرداخت آن را صادر فرمایید. " +
            "پیشاپیش از همکاری شما سپاسگزاریم."
        FollowUpTone.URGENT -> "جناب/سرکار $client، سلام؛\n" +
            "فاکتور شماره $number از $agency به مبلغ $amount$dueClause " +
            "به مرحله پیگیری فوری رسیده و تاکنون پرداخت نشده است.\n" +
            "لطفاً حداکثر ظرف ۴۸ ساعت آینده تسویه فرمایید تا خدمات دچار وقفه نشود. " +
            "در صورت پرداخت، این پیام را نادیده بگیرید."
    }
}

private fun englishMessage(
    agency: String,
    client: String,
    number: String,
    amount: String,
    due: String?,
    tone: FollowUpTone,
): String {
    val dueClause = if (due != null) " due $due" else ""
    return when (tone) {
        FollowUpTone.FRIENDLY -> "Hi $client,\n" +
            "Hope you are doing great! This is just a friendly reminder from $agency " +
            "about invoice #$number for $amount$dueClause.\n" +
            "Whenever you get a chance, we would appreciate your payment. " +
            "Let us know if you have any questions about the invoice. 🙏"
        FollowUpTone.FORMAL -> "Dear $client,\n" +
            "This is to kindly inform you that invoice #$number issued by $agency " +
            "for $amount$dueClause remains unpaid.\n" +
            "We would appreciate it if you could arrange the payment at your " +
            "earliest convenience. Thank you for your cooperation."
        FollowUpTone.URGENT -> "Dear $client,\n" +
            "Invoice #$number from $agency for $amount$dueClause " +
            "has reached urgent follow-up and is still outstanding.\n" +
            "Please settle it within the next 48 hours to avoid any service " +
            "interruption. If already paid, kindly disregard this message."
    }
}

/**
 * Grouped Toman rendering for message copy: `1250000` → `1,250,000 تومان`.
 * Locale-independent grouping (mirroring `AmountFormatter`) so a pasted message
 * reads the same digits everywhere.
 */
private fun formatTomanAmount(totalToman: Long, isEnglish: Boolean): String {
    val grouped = groupedFormat.format(totalToman)
    return if (isEnglish) "$grouped Toman" else "$grouped تومان"
}

private val groupedFormat = DecimalFormat("#,###", DecimalFormatSymbols(Locale.ROOT))
