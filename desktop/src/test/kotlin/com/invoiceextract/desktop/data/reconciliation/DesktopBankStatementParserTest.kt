package com.invoiceextract.desktop.data.reconciliation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.charset.Charset

/**
 * Hermetic tests for [parseBankStatement], written against real files in a
 * throwaway folder: encodings, delimiters, header dialects and the binary
 * guard. No bank fixture ever ships — every case composes its own export.
 */
class DesktopBankStatementParserTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `utf8 comma export with debit-credit columns parses deposits`() {
        val file = statement(
            "statement.csv",
            """
            تاریخ,شرح,بدهکار,بستانکار,مانده
            1403/05/20,واریز حقوق,0,"1,250,000",5000000
            1403/05/21,خرید فروشگاه,200000,0,4800000
            """.trimIndent().toByteArray(Charsets.UTF_8),
        )

        val outcome = parseBankStatement(file)
        assertTrue(outcome is StatementParseOutcome.Parsed)
        val transactions = (outcome as StatementParseOutcome.Parsed).transactions

        assertEquals(1, transactions.size)
        assertEquals("1403/05/20", transactions.single().rawDate)
        assertEquals(1_250_000L, transactions.single().amount)
        assertEquals("واریز حقوق", transactions.single().description)
    }

    @Test
    fun `windows-1256 semicolon export decodes without mojibake`() {
        // Note: the Persian Yeh (ی) is deliberately absent from this fixture:
        // this JDK's windows-1256 *encoder* replaces it with `?`, so composing
        // the fixture through `String.toByteArray` would test the encoder, not
        // the parser. Decoding (the production direction) is a straight
        // charset decode and handles every byte the JDK maps.
        val body = "date;شرح;بدهکار;بستانکار\n" +
            "1403/05/20;حقوق ماهانه;0;2500000\n"
        val file = statement("statement.csv", body.toByteArray(Charset.forName("windows-1256")))

        val outcome = parseBankStatement(file)
        assertTrue(outcome is StatementParseOutcome.Parsed)
        val transactions = (outcome as StatementParseOutcome.Parsed).transactions

        assertEquals(1, transactions.size)
        assertEquals(2_500_000L, transactions.single().amount)
        assertEquals("حقوق ماهانه", transactions.single().description)
    }

    @Test
    fun `rial header converts to toman`() {
        val file = statement(
            "statement.csv",
            """
            تاریخ,شرح,مبلغ (ریال)
            1403/05/20,واریز,12500000
            """.trimIndent().toByteArray(Charsets.UTF_8),
        )

        val outcome = parseBankStatement(file)
        assertTrue(outcome is StatementParseOutcome.Parsed)
        val transactions = (outcome as StatementParseOutcome.Parsed).transactions

        assertEquals(1_250_000L, transactions.single().amount)
    }

    @Test
    fun `tracking column rides along when present`() {
        val file = statement(
            "statement.csv",
            """
            Date,Description,Debit,Credit,Tracking Number
            2024-08-01,Client transfer,0,300000,TRK-9918
            """.trimIndent().toByteArray(Charsets.UTF_8),
        )

        val outcome = parseBankStatement(file)
        assertTrue(outcome is StatementParseOutcome.Parsed)
        val transactions = (outcome as StatementParseOutcome.Parsed).transactions

        assertEquals("TRK-9918", transactions.single().trackingNumber)
        assertEquals(300_000L, transactions.single().amount)
    }

    @Test
    fun `binary xls fails as unsupported instead of misreading`() {
        val bytes = ByteArray(64)
        bytes[0] = 0xD0.toByte()
        bytes[1] = 0xCF.toByte()
        bytes[2] = 0x11.toByte()
        bytes[3] = 0xE0.toByte()
        val file = statement("statement.xls", bytes)

        val outcome = parseBankStatement(file)

        assertTrue(outcome is StatementParseOutcome.Failed)
        assertEquals(
            StatementParseFailure.UnsupportedFormat,
            (outcome as StatementParseOutcome.Failed).failure,
        )
    }

    @Test
    fun `an empty file reports no transactions`() {
        val file = statement("empty.csv", ByteArray(0))

        val outcome = parseBankStatement(file)

        assertTrue(outcome is StatementParseOutcome.Failed)
        assertEquals(
            StatementParseFailure.NoTransactionsFound,
            (outcome as StatementParseOutcome.Failed).failure,
        )
    }

    @Test
    fun `quoted commas stay inside their cell`() {
        val file = statement(
            "statement.csv",
            ("تاریخ,شرح,بدهکار,بستانکار\n" +
                "1403/05/20,\"واریز بابت قرارداد, مرحله دوم\",0,4000000\n")
                .toByteArray(Charsets.UTF_8),
        )

        val outcome = parseBankStatement(file)
        assertTrue(outcome is StatementParseOutcome.Parsed)
        val transactions = (outcome as StatementParseOutcome.Parsed).transactions

        assertEquals(1, transactions.size)
        assertEquals("واریز بابت قرارداد, مرحله دوم", transactions.single().description)
        assertEquals(4_000_000L, transactions.single().amount)
    }

    private fun statement(name: String, bytes: ByteArray): File {
        val file = File(tempFolder.root, name)
        file.writeBytes(bytes)
        return file
    }
}
