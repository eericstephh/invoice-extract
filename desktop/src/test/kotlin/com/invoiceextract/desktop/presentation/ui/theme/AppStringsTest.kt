package com.invoiceextract.desktop.presentation.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

/**
 * Hermetic tests for the bilingual dictionaries: every chrome string exists
 * in both languages, the two copies differ, and the greeting/date helpers
 * honor the active language.
 */
class AppStringsTest {

    @Test
    fun `every chrome string is present in both languages`() {
        val fa = appStrings(AppLanguage.FA)
        val en = appStrings(AppLanguage.EN)

        faStrings(fa).forEach { value ->
            assertTrue("blank FA string", value.isNotBlank())
        }
        faStrings(en).forEach { value ->
            assertTrue("blank EN string", value.isNotBlank())
        }
    }

    @Test
    fun `translations differ from the persian source`() {
        val fa = appStrings(AppLanguage.FA)
        val en = appStrings(AppLanguage.EN)

        val faValues = faStrings(fa)
        val enValues = faStrings(en)
        assertEquals(faValues.size, enValues.size)
        faValues.zip(enValues).forEach { (first, second) ->
            assertFalse("untranslated copy: $first", first == second)
        }
    }

    @Test
    fun `greeting honors time of day and language`() {
        val morning = LocalTime.of(9, 0)
        val night = LocalTime.of(23, 0)

        assertEquals("صبح بخیر، کاربر گرامی", appStrings(AppLanguage.FA).greetingText(morning))
        assertEquals("شب بخیر، کاربر گرامی", appStrings(AppLanguage.FA).greetingText(night))
        assertEquals("Good morning, User", appStrings(AppLanguage.EN).greetingText(morning))
        assertEquals("Good night, User", appStrings(AppLanguage.EN).greetingText(night))
    }

    @Test
    fun `date label is jalali in fa and gregorian in en`() {
        val day = LocalDate.of(2026, 9, 30)

        assertEquals(jalaliDateLabel(day), appStrings(AppLanguage.FA).dateText(day))
        assertEquals("Sep 30, 2026", appStrings(AppLanguage.EN).dateText(day))
    }

    private fun faStrings(strings: AppStrings): List<String> = listOf(
        strings.appSubtitle,
        strings.metricSaved,
        strings.metricEngine,
        strings.metricBatch,
        strings.engineReady,
        strings.engineError,
        strings.engineStarting,
        strings.dockScan,
        strings.dockBatch,
        strings.dockAnalytics,
        strings.dockHistory,
        strings.dockMapping,
        strings.dockTheme,
        strings.dropTitle,
        strings.dropSingle,
        strings.dropBatch,
        strings.emptyBatchTitle,
        strings.emptyBatchSubtitle,
        strings.tableRow,
        strings.tableProduct,
        strings.tableCode,
        strings.tableQty,
        strings.tableUnitPrice,
        strings.tableDiscount,
        strings.tableTax,
        strings.tableTotal,
        strings.tableEmpty,
        strings.btnViewEdit,
        strings.btnExportExcel,
        strings.btnSave,
        strings.btnClientStatement,
        strings.btnPettyCash,
        strings.btnBack,
        strings.connectedStatus,
        strings.ollamaOffStatus,
        strings.totalSpend,
        strings.invoiceCountLabel,
        strings.averageInvoiceLabel,
        strings.vendorsCount,
        strings.analyticsEmpty,
        strings.monthlyExpensesTitle,
        strings.topVendorsTitle,
        strings.topItemsTitle,
        strings.quantityPrefix,
        strings.noItemsRecorded,
        strings.historyDialogTitle,
        strings.searchPlaceholder,
        strings.emptyHistory,
        strings.statusValid,
        strings.statusNeedsReview,
        strings.statusInvalid,
        strings.currencyToman,
        strings.currencyRial,
        strings.allYears,
        strings.backupRestoreTitle,
        strings.btnBackupRestore,
        strings.backupSectionTitle,
        strings.backupSectionDescription,
        strings.btnCreateBackup,
        strings.restoreSectionTitle,
        strings.restoreSectionDescription,
        strings.btnRestoreBackup,
        strings.backupSuccess,
        strings.backupInProgress,
        strings.restoreInProgress,
        strings.restoreWarning,
        strings.btnClose,
        strings.btnPrintFormalInvoice,
        strings.formalInvoiceGenerated,
        strings.commercialInvoiceGenerated,
        strings.btnPrintCommercialInvoice,
        strings.shortcutsTitle,
        strings.btnDonate,
        strings.donationTitle,
        strings.donationSubtitle,
        strings.copyWalletAddress,
        strings.addressCopied,
        strings.networkWarning,
        strings.paymentPaid,
        strings.paymentPending,
        strings.paymentOverdue,
        strings.paymentSection,
        strings.dueDateLabel,
        strings.followUpTitle,
        strings.toneFriendly,
        strings.toneFormal,
        strings.toneUrgent,
        strings.copyMessage,
        strings.messageCopied,
        strings.sendViaWhatsapp,
        strings.markAsPaid,
        strings.remindPayment,
        strings.receivablesLabel(2),
        strings.reconcileTitle,
        strings.reconcilePickFile,
        strings.reconcileChooseHint,
        strings.reconcileMatchedTab,
        strings.reconcileUnmatchedTab,
        strings.reconcileMatchedCount,
        strings.reconcileUnmatchedCount,
        strings.reconcileTotalMatched,
        strings.reconcileApplyMatched,
        strings.reconcileBankButton,
        strings.matchBadge100,
        strings.trackingNumberLabel,
        strings.reconcileParseError,
        strings.btnExportAnalyticsReport,
        strings.analyticsReportSuccess,
        strings.exportQuickBooks,
        strings.exportXero,
        strings.taxEinValid,
        strings.taxVatValid,
        strings.taxIdInvalid,
        strings.mappingsTitle,
        strings.mappingsSearchPlaceholder,
        strings.mappingsEmpty,
        strings.mappingsNoMatch,
        strings.mappingsItemName,
        strings.mappingsItemCode,
        strings.mappingsVendor,
        strings.mappingsInternalName,
        strings.mappingsAddBtn,
        strings.mappingsScopeGlobal,
        strings.hotFolderTitle,
        strings.hotFolderActive,
        strings.hotFolderInfo,
        strings.btnSelectFolder,
        strings.watcherOn,
        strings.watcherOff,
        strings.autoProcessedSuccess("scan.pdf", "شرکت نمونه"),
        strings.restoreSuccess(3),
        strings.itemsCount(3),
        strings.modelMissingStatus("qwen2.5:3b"),
    )
}
