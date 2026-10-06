package com.invoiceextract.desktop.data.reconciliation

import com.invoiceextract.desktop.domain.reconciliation.BankTransaction
import java.io.File
import java.nio.charset.Charset

/**
 * Reads an Iranian bank statement export into [BankTransaction]s.
 *
 * Pure Kotlin, no spreadsheet library: the desktop stays free of POI-class
 * dependencies, and a statement is line-oriented text anyway. Handles the two
 * shapes Iranian banks actually export:
 *
 * - **CSV text** (`.csv`, `.txt`, `.tsv`): UTF-8 (with or without BOM) or
 *   Windows-1256, comma- or semicolon-delimited, quote-aware.
 * - Anything else — notably binary `.xls` (OLE2) workbooks — fails as
 *   [StatementParseFailure.UnsupportedFormat] telling the user to re-save as
 *   CSV, instead of misreading binary as text.
 *
 * Column discovery is header-driven: the first row carrying at least two
 * recognised keywords (Jalali or Latin) maps the date / debit / credit /
 * description / tracking columns. Amounts unify Persian and Arabic-Indic
 * digits, drop grouping separators, and honour `(…)` / `-` negatives.
 *
 * Rial-to-Toman is decided by the header's own unit words (`ریال`, `rial`,
 * `irr` → divide by 10; `تومان`, `toman` → as-is) and defaults to Toman when
 * the export names no unit — a magnitude guess would silently corrupt every
 * match, so it is never attempted.
 *
 * A deposit is the credit cell when the export splits debit/credit, else the
 * single positive amount cell; balance columns are excluded by header role,
 * never by magnitude.
 */
sealed interface StatementParseOutcome {
    data class Parsed(val transactions: List<BankTransaction>) : StatementParseOutcome
    data class Failed(val failure: StatementParseFailure) : StatementParseOutcome
}

/** Why a statement file could not be turned into transactions. */
enum class StatementParseFailure {
    /** Binary workbook (OLE2 `.xls`) or any non-text payload: re-save as CSV. */
    UnsupportedFormat,

    /** No header and no parseable amount cells at all. */
    NoTransactionsFound,
}

fun parseBankStatement(file: File): StatementParseOutcome {
    if (!file.isFile) return StatementParseOutcome.Failed(StatementParseFailure.NoTransactionsFound)
    val bytes = runCatching { file.readBytes() }.getOrNull()
        ?: return StatementParseOutcome.Failed(StatementParseFailure.NoTransactionsFound)
    if (bytes.isEmpty()) {
        return StatementParseOutcome.Failed(StatementParseFailure.NoTransactionsFound)
    }
    if (isOle2Workbook(bytes) || isZipWorkbook(bytes)) {
        return StatementParseOutcome.Failed(StatementParseFailure.UnsupportedFormat)
    }

    val text = decodeText(bytes)
    val rows = text.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .map { splitRow(it, detectDelimiter(it)) }
        .filter { row -> row.any { cell -> cell.isNotBlank() } }
        .toList()
    if (rows.isEmpty()) {
        return StatementParseOutcome.Failed(StatementParseFailure.NoTransactionsFound)
    }

    val header = findHeader(rows)
    val transactions = if (header != null) {
        parseWithHeader(rows.drop(header + 1), rows[header])
    } else {
        parsePositional(rows)
    }
    if (transactions.isEmpty()) {
        return StatementParseOutcome.Failed(StatementParseFailure.NoTransactionsFound)
    }
    return StatementParseOutcome.Parsed(transactions)
}

/** OLE2 signature (`D0 CF 11 E0`): a real binary `.xls` workbook. */
private fun isOle2Workbook(bytes: ByteArray): Boolean =
    bytes.size > 4 &&
        bytes[0] == 0xD0.toByte() &&
        bytes[1] == 0xCF.toByte() &&
        bytes[2] == 0x11.toByte() &&
        bytes[3] == 0xE0.toByte()

/** ZIP signature (`PK..`): an `.xlsx` workbook, equally unparseable here. */
private fun isZipWorkbook(bytes: ByteArray): Boolean =
    bytes.size > 2 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte()

/**
 * UTF-8 when it decodes cleanly (BOM stripped), Windows-1256 otherwise: the
 * two encodings Iranian banks export in, tried in that order so a valid UTF-8
 * file is never mojibake'd through 1256.
 */
private fun decodeText(bytes: ByteArray): String {
    val withoutBom = if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() &&
        bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()
    ) {
        bytes.copyOfRange(3, bytes.size)
    } else {
        bytes
    }
    return runCatching {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
            .decode(java.nio.ByteBuffer.wrap(withoutBom))
            .toString()
    }.getOrElse {
        String(withoutBom, Charset.forName("windows-1256"))
    }
}

/** Semicolons win on sight (Excel-FA CSVs); else commas, else tabs. */
private fun detectDelimiter(sampleRow: String): Char {
    if (';' in sampleRow) return ';'
    if ('\t' in sampleRow) return '\t'
    return ','
}

/** Quote-aware split: separators inside `"…"` (with `""` escapes) don't split. */
private fun splitRow(row: String, delimiter: Char): List<String> {
    val cells = mutableListOf<String>()
    val current = StringBuilder()
    var inQuotes = false
    var index = 0
    while (index < row.length) {
        val char = row[index]
        when {
            char == '"' -> {
                if (inQuotes && index + 1 < row.length && row[index + 1] == '"') {
                    current.append('"')
                    index++
                } else {
                    inQuotes = !inQuotes
                }
            }
            char == delimiter && !inQuotes -> {
                cells.add(current.toString().trim())
                current.clear()
            }
            else -> current.append(char)
        }
        index++
    }
    cells.add(current.toString().trim())
    return cells
}

private val DATE_KEYWORDS = listOf("تاریخ", "date")
private val DEBIT_KEYWORDS = listOf("بدهکار", "بدهكار", "برداشت", "debit", "withdraw")
private val CREDIT_KEYWORDS = listOf("بستانکار", "بستانكار", "واریز", "credit", "deposit")
/** A lone amount column with no debit/credit split reads as the deposit. */
private val AMOUNT_KEYWORDS = listOf("مبلغ", "amount", "مبلغ")
private val DESCRIPTION_KEYWORDS = listOf("شرح", "بابت", "description", "memo", "narration")
private val TRACKING_KEYWORDS =
    listOf("پیگیری", "رهگیری", "شماره", "tracking", "reference", "ref")
private val BALANCE_KEYWORDS = listOf("مانده", "balance")
private val RIAL_KEYWORDS = listOf("ریال", "rial", "irr")
private val TOMAN_KEYWORDS = listOf("تومان", "toman", "tt")

private val DATE_CELL = Regex("""\d{4}\s*[/\-.]\s*\d{1,2}\s*[/\-.]\s*\d{1,2}""")

private data class ColumnMap(
    val date: Int,
    val debit: Int,
    val credit: Int,
    val amount: Int,
    val description: Int,
    val tracking: Int,
    val divideByTen: Boolean,
)

/** First row carrying at least two recognised header keywords, else `null`. */
private fun findHeader(rows: List<List<String>>): Int? {
    val vocabulary = DATE_KEYWORDS + DEBIT_KEYWORDS + CREDIT_KEYWORDS +
        AMOUNT_KEYWORDS + DESCRIPTION_KEYWORDS + TRACKING_KEYWORDS +
        BALANCE_KEYWORDS + RIAL_KEYWORDS + TOMAN_KEYWORDS
    for ((index, row) in rows.withIndex()) {
        val hits = row.count { cell ->
            val lower = cell.lowercase()
            vocabulary.any { keyword -> lower.contains(keyword) }
        }
        if (hits >= 2) return index
    }
    return null
}

private fun columnOf(header: List<String>, keywords: List<String>): Int =
    header.indexOfFirst { cell ->
        val lower = cell.lowercase()
        keywords.any { keyword -> lower.contains(keyword) }
    }

private fun parseWithHeader(rows: List<List<String>>, header: List<String>): List<BankTransaction> {
    val dateCol = columnOf(header, DATE_KEYWORDS)
    val debitCol = columnOf(header, DEBIT_KEYWORDS)
    val creditCol = columnOf(header, CREDIT_KEYWORDS)
    val amountCol = columnOf(header, AMOUNT_KEYWORDS)
    val descCol = columnOf(header, DESCRIPTION_KEYWORDS)
    val trackCol = columnOf(header, TRACKING_KEYWORDS)
    val headerText = header.joinToString(" ").lowercase()
    val divideByTen = RIAL_KEYWORDS.any { headerText.contains(it) } &&
        TOMAN_KEYWORDS.none { headerText.contains(it) }
    val map = ColumnMap(dateCol, debitCol, creditCol, amountCol, descCol, trackCol, divideByTen)

    return rows.mapNotNull { row -> parseHeaderRow(row, map) }
}

private fun parseHeaderRow(row: List<String>, map: ColumnMap): BankTransaction? {
    fun cell(index: Int): String? =
        if (index in row.indices) row[index].trim().takeIf { it.isNotBlank() } else null

    val credit = if (map.credit >= 0) cell(map.credit)?.let(::parseAmount) else null
    val debit = if (map.debit >= 0) cell(map.debit)?.let(::parseAmount) else null
    val lone = if (map.credit < 0 && map.debit < 0 && map.amount >= 0) {
        cell(map.amount)?.let(::parseAmount)
    } else {
        null
    }
    // The credit cell is the deposit; a lone amount column with no split reads
    // the same way. A lone debit-only row is a withdrawal and can never settle
    // an invoice, so it is dropped rather than negated.
    val amount = (credit ?: lone)?.takeIf { it > 0L }
    if (amount == null || amount == 0L) return null

    val date = cell(map.date)?.takeIf { DATE_CELL.containsMatchIn(it) }
        ?: row.firstOrNull { DATE_CELL.containsMatchIn(it) }
        ?: return null
    val description = cell(map.description).orEmpty()
    val tracking = cell(map.tracking)?.takeIf { it.isNotBlank() }
    return BankTransaction(
        rawDate = date,
        amount = if (map.divideByTen) amount / 10L else amount,
        description = description,
        trackingNumber = tracking,
    )
}

/**
 * Headerless fallback: date from the first date-like cell, deposit from the
 * single positive amount cell, description from the longest text cell. Rows
 * with two or more positive amounts are ambiguous without a header (which one
 * is the balance?) and are skipped rather than guessed.
 */
private fun parsePositional(rows: List<List<String>>): List<BankTransaction> {
    return rows.mapNotNull { row ->
        val date = row.firstOrNull { DATE_CELL.containsMatchIn(it) } ?: return@mapNotNull null
        val amounts = row.mapNotNull { parseAmount(it) }.filter { it > 0L }
        if (amounts.size != 1) return@mapNotNull null
        val description = row
            .filter { cell -> cell.isNotBlank() && parseAmount(cell) == null && !DATE_CELL.containsMatchIn(cell) }
            .maxByOrNull { it.length }
            .orEmpty()
        BankTransaction(rawDate = date, amount = amounts.single(), description = description)
    }
}

/**
 * Lenient amount reader: unifies Persian/Arabic-Indic digits, drops grouping
 * separators (`,`, `٬`, space, `٬`), reads `(…)` and leading `-` as negative,
 * and takes the integer part — bank exports print no subunits that matter.
 */
private fun parseAmount(raw: String): Long? {
    var text = raw.trim()
    if (text.isEmpty()) return null
    var negative = false
    if (text.startsWith("(") && text.endsWith(")")) {
        negative = true
        text = text.substring(1, text.length - 1)
    }
    val unified = buildString(text.length) {
        for (char in text) {
            when (char) {
                in '۰'..'۹' -> append('0' + (char - '۰'))
                in '٠'..'٩' -> append('0' + (char - '٠'))
                '٫' -> append('.')
                ',', '٬', ' ', '\'' -> { /* grouping noise: dropped */ }
                else -> append(char)
            }
        }
    }.trim()
    if (unified.isEmpty()) return null
    val negativePrefix = unified.startsWith("-")
    val magnitude = unified.trimStart('-', '+').substringBefore('.')
    if (magnitude.isEmpty() || magnitude.any { !it.isDigit() }) return null
    val value = magnitude.toLongOrNull() ?: return null
    return if (negative || negativePrefix) -value else value
}
