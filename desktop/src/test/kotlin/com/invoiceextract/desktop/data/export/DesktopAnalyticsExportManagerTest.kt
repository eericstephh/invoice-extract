package com.invoiceextract.desktop.data.export

import com.invoiceextract.desktop.domain.analytics.DashboardAnalytics
import com.invoiceextract.desktop.domain.analytics.MonthlyExpense
import com.invoiceextract.desktop.domain.analytics.TopItemStat
import com.invoiceextract.desktop.domain.analytics.VendorStat
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.LocalDate

/**
 * Hermetic tests for [DesktopAnalyticsExportManager].
 *
 * The workbook is asserted as text: sections, shares, direction flag and the
 * period line all read off the emitted XML, so a broken layout fails here
 * instead of in front of a manager.
 */
class DesktopAnalyticsExportManagerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val manager = DesktopAnalyticsExportManager()
    private val fixedDay = LocalDate.of(2026, 9, 30)

    @Test
    fun `empty analytics still emits all four sections`() {
        val target = tempFolder.root.resolve("empty.xls")

        val result = manager.exportAnalyticsReport(
            targetFile = target,
            analytics = DashboardAnalytics(),
            selectedYear = null,
            isEnglish = false,
            today = fixedDay,
        )

        assertTrue(result.isSuccess)
        val xml = target.readText(Charsets.UTF_8)
        assertTrue(xml.contains("شاخص‌های کلیدی عملکرد"))
        assertTrue(xml.contains("روند مخارج به تفکیک ماه"))
        assertTrue(xml.contains("۵ تأمین‌کننده برتر"))
        assertTrue(xml.contains("پرهزینه‌ترین اقلام خریداری‌شده"))
        assertTrue(xml.contains("کل دوره‌ها (همه سال‌ها)"))
        // Matches the sibling emitters' converter bit-for-bit (see
        // `gregorianToJalali`): sheet-to-sheet agreement beats the almanac.
        assertTrue(xml.contains("1405/07/09"))
    }

    @Test
    fun `full dataset carries shares rtl and all rows`() {
        val target = tempFolder.root.resolve("full.xls")
        val analytics = DashboardAnalytics(
            totalSpend = 10_000L,
            invoiceCount = 4,
            averageInvoice = 2_500L,
            vendorCount = 2,
            topVendors = listOf(
                VendorStat("الف", 7_500L, 75f),
                VendorStat("ب", 2_500L, 25f),
            ),
            monthlyExpenses = listOf(
                MonthlyExpense("مرداد 1403", 6_000L),
                MonthlyExpense("شهریور 1403", 4_000L),
            ),
            topItems = listOf(
                TopItemStat("کالا", 2.0, 6_000L),
            ),
            totalUnpaidAmount = 6_000L,
            unpaidCount = 2,
            overdueCount = 1,
        )

        val result = manager.exportAnalyticsReport(
            targetFile = target,
            analytics = analytics,
            selectedYear = null,
            isEnglish = false,
            today = fixedDay,
        )

        assertTrue(result.isSuccess)
        val xml = target.readText(Charsets.UTF_8)
        // RTL worksheet, BOM-prefixed.
        assertTrue(xml.contains("ss:RightToLeft=\"1\""))
        assertTrue(target.readBytes().take(3) == listOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()))
        // KPI rows.
        assertTrue(xml.contains("10000"))
        assertTrue(xml.contains("6000"))
        // Monthly shares: 60% and 40% of the budget.
        assertTrue(xml.contains("60%"))
        assertTrue(xml.contains("40%"))
        assertTrue(xml.contains("مرداد 1403"))
        // Vendor shares come from the snapshot percentages.
        assertTrue(xml.contains("75%"))
        assertTrue(xml.contains("25%"))
        assertTrue(xml.contains(">الف<") || xml.contains("الف"))
        // Item row with rank and quantity.
        assertTrue(xml.contains("کالا"))
    }

    @Test
    fun `single year scopes the period line and english copy translates`() {
        val target = tempFolder.root.resolve("year.xls")

        val result = manager.exportAnalyticsReport(
            targetFile = target,
            analytics = DashboardAnalytics(totalSpend = 1_000L, invoiceCount = 1),
            selectedYear = 1403,
            isEnglish = true,
            today = fixedDay,
        )

        assertTrue(result.isSuccess)
        val xml = target.readText(Charsets.UTF_8)
        assertTrue(xml.contains("Fiscal year 1403"))
        assertTrue(xml.contains("Financial Analytics Report"))
        assertTrue(xml.contains("Sep 30, 2026"))
        assertTrue(xml.contains("Key Performance Indicators"))
    }

    @Test
    fun `xml-significant characters cannot break the sheet`() {
        val target = tempFolder.root.resolve("escape.xls")
        val analytics = DashboardAnalytics(
            totalSpend = 100L,
            invoiceCount = 1,
            topVendors = listOf(VendorStat("A&B <Co>", 100L, 100f)),
            monthlyExpenses = emptyList(),
            topItems = emptyList(),
        )

        assertTrue(
            manager.exportAnalyticsReport(
                targetFile = target,
                analytics = analytics,
                selectedYear = null,
                isEnglish = true,
                today = fixedDay,
            ).isSuccess,
        )
        val xml = target.readText(Charsets.UTF_8)
        assertTrue(xml.contains("A&amp;B &lt;Co&gt;"))
    }
}
