package com.invoiceextract.desktop.presentation.ui.theme

import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The window language. FA renders Persian copy right-to-left; EN renders
 * English copy left-to-right.
 */
enum class AppLanguage {
    FA,
    EN,
}

/**
 * Every localizable string of the main desktop views in one dictionary.
 *
 * Screens read copy off this value instead of hardcoding it, so flipping the
 * language re-skins the window without touching any ViewModel logic. Dialogs
 * outside the main views keep their own Persian copy.
 */
data class AppStrings(
    val language: AppLanguage,
    val appSubtitle: String,
    val metricSaved: String,
    val metricEngine: String,
    val metricBatch: String,
    val engineReady: String,
    val engineError: String,
    val engineStarting: String,
    val dockScan: String,
    val dockBatch: String,
    val dockAnalytics: String,
    val dockHistory: String,
    val dockMapping: String,
    val dockTheme: String,
    val dropTitle: String,
    val dropSingle: String,
    val dropBatch: String,
    val emptyBatchTitle: String,
    val emptyBatchSubtitle: String,
    val tableRow: String,
    val tableProduct: String,
    val tableCode: String,
    val tableQty: String,
    val tableUnitPrice: String,
    val tableDiscount: String,
    val tableTax: String,
    val tableTotal: String,
    val tableEmpty: String,
    val btnViewEdit: String,
    val btnExportExcel: String,
    val btnSave: String,
    val btnClientStatement: String,
    val btnPettyCash: String,
    val btnBack: String,
    val connectedStatus: String,
    val ollamaOffStatus: String,
    val totalSpend: String,
    val invoiceCountLabel: String,
    val averageInvoiceLabel: String,
    val vendorsCount: String,
    val analyticsEmpty: String,
    val monthlyExpensesTitle: String,
    val topVendorsTitle: String,
    val topItemsTitle: String,
    val quantityPrefix: String,
    val noItemsRecorded: String,
    val historyDialogTitle: String,
    val searchPlaceholder: String,
    val emptyHistory: String,
    val statusValid: String,
    val statusNeedsReview: String,
    val statusInvalid: String,
    val currencyToman: String,
    val currencyRial: String,
    val allYears: String,
    val backupRestoreTitle: String,
    val btnBackupRestore: String,
    val backupSectionTitle: String,
    val backupSectionDescription: String,
    val btnCreateBackup: String,
    val restoreSectionTitle: String,
    val restoreSectionDescription: String,
    val btnRestoreBackup: String,
    val backupSuccess: String,
    val backupInProgress: String,
    val restoreInProgress: String,
    val restoreWarning: String,
    val btnClose: String,
    val btnPrintFormalInvoice: String,
    val formalInvoiceGenerated: String,
    val commercialInvoiceGenerated: String,
    val btnPrintCommercialInvoice: String,
    val shortcutsTitle: String,
    val hotFolderTitle: String,
    val hotFolderActive: String,
    val hotFolderInfo: String,
    val btnSelectFolder: String,
    val watcherOn: String,
    val watcherOff: String,
    val btnDonate: String,
    val donationTitle: String,
    val donationSubtitle: String,
    val copyWalletAddress: String,
    val addressCopied: String,
    val networkWarning: String,
    val paymentPaid: String,
    val paymentPending: String,
    val paymentOverdue: String,
    val paymentSection: String,
    val dueDateLabel: String,
    val followUpTitle: String,
    val toneFriendly: String,
    val toneFormal: String,
    val toneUrgent: String,
    val copyMessage: String,
    val messageCopied: String,
    val sendViaWhatsapp: String,
    val markAsPaid: String,
    val remindPayment: String,
    val reconcileTitle: String,
    val reconcilePickFile: String,
    val reconcileChooseHint: String,
    val reconcileMatchedTab: String,
    val reconcileUnmatchedTab: String,
    val reconcileMatchedCount: String,
    val reconcileUnmatchedCount: String,
    val reconcileTotalMatched: String,
    val reconcileApplyMatched: String,
    val reconcileBankButton: String,
    val matchBadge100: String,
    val trackingNumberLabel: String,
    val reconcileParseError: String,
    val btnExportAnalyticsReport: String,
    val analyticsReportSuccess: String,
    val exportQuickBooks: String,
    val exportXero: String,
    val taxEinValid: String,
    val taxVatValid: String,
    val taxIdInvalid: String,
    val mappingsTitle: String,
    val mappingsSearchPlaceholder: String,
    val mappingsEmpty: String,
    val mappingsNoMatch: String,
    val mappingsItemName: String,
    val mappingsItemCode: String,
    val mappingsVendor: String,
    val mappingsInternalName: String,
    val mappingsAddBtn: String,
    val mappingsScopeGlobal: String,
) {
    /**
     * The auto-process confirmation, e.g.
     * "فاکتور scan.pdf (شرکت نمونه) خودکار پردازش و ذخیره شد." or
     * "Auto-processed scan.pdf (Sample Co.) successfully."
     */
    fun autoProcessedSuccess(fileName: String, sellerName: String): String = when (language) {
        AppLanguage.FA -> "فاکتور $fileName ($sellerName) خودکار پردازش و ذخیره شد."
        AppLanguage.EN -> "Auto-processed $fileName ($sellerName) successfully."
    }
    /**
     * The restore confirmation, e.g. "۳ رکورد با موفقیت بازیابی شد." or
     * "Restored 3 records successfully."
     */
    fun restoreSuccess(count: Int): String = when (language) {
        AppLanguage.FA -> "${count.toPersianDigits()} رکورد با موفقیت بازیابی شد."
        AppLanguage.EN -> "Restored $count records successfully."
    }
    /**
     * Time-aware greeting plus the user suffix, e.g. "روز بخیر، کاربر گرامی"
     * or "Good day, User".
     */
    fun greetingText(now: LocalTime = LocalTime.now()): String {
        val dayPart = when (language) {
            AppLanguage.FA -> when (now.hour) {
                in 5..11 -> "صبح بخیر"
                in 12..16 -> "روز بخیر"
                in 17..19 -> "عصر بخیر"
                else -> "شب بخیر"
            }
            AppLanguage.EN -> when (now.hour) {
                in 5..11 -> "Good morning"
                in 12..17 -> "Good day"
                in 18..22 -> "Good evening"
                else -> "Good night"
            }
        }
        val suffix = when (language) {
            AppLanguage.FA -> "، کاربر گرامی"
            AppLanguage.EN -> ", User"
        }
        return dayPart + suffix
    }

    /**
     * The item-count badge, e.g. "۳ قلم کالا" or "3 Items".
     */
    fun itemsCount(count: Int): String = when (language) {
        AppLanguage.FA -> "${count.toPersianDigits()} قلم کالا"
        AppLanguage.EN -> "$count Items"
    }

    /**
     * The missing-model verdict naming the required tag, e.g.
     * "مدل qwen2.5:3b یافت نشد" or "Model qwen2.5:3b not found".
     */
    fun modelMissingStatus(requiredModel: String): String = when (language) {
        AppLanguage.FA -> "مدل $requiredModel یافت نشد"
        AppLanguage.EN -> "Model $requiredModel not found"
    }

    /**
     * The receivables KPI caption carrying the overdue slice, e.g.
     * "مطالبات وصول‌نشده • ۲ معوق" or "Unpaid Receivables • 2 overdue".
     */
    fun receivablesLabel(overdueCount: Int): String = when (language) {
        AppLanguage.FA -> "مطالبات وصول‌نشده • ${overdueCount.toPersianDigits()} معوق"
        AppLanguage.EN -> "Unpaid Receivables • $overdueCount overdue"
    }

    /**
     * The date pill: the Jalali date in Persian digits for FA, the Gregorian
     * date for EN.
     */
    fun dateText(today: LocalDate = LocalDate.now()): String = when (language) {
        AppLanguage.FA -> jalaliDateLabel(today)
        AppLanguage.EN -> today.format(EN_DATE_FORMAT)
    }
}

/** The dictionary for [language]: one value, whole-window copy. */
fun appStrings(language: AppLanguage): AppStrings = when (language) {
    AppLanguage.FA -> AppStrings(
        language = language,
        appSubtitle = "استخراج هوشمند فاکتور • پیشخوان دسکتاپ",
        metricSaved = "فاکتورهای ذخیره‌شده",
        metricEngine = "وضعیت موتور",
        metricBatch = "پردازش دسته‌ای",
        engineReady = "آماده",
        engineError = "خطا",
        engineStarting = "در حال آماده‌سازی",
        dockScan = "اسکن فاکتور",
        dockBatch = "پردازش گروهی",
        dockAnalytics = "گزارشات تحلیلی",
        dockHistory = "سوابق و آرشیو",
        dockMapping = "نگاشت کالاها",
        dockTheme = "حالت شب / روز",
        dropTitle = "فایل‌های فاکتور (PDF یا عکس) را اینجا بکشید و رها کنید",
        dropSingle = "انتخاب یک فاکتور",
        dropBatch = "انتخاب گروهی فاکتورها",
        emptyBatchTitle = "هنوز پردازش گروهی فعالی نیست",
        emptyBatchSubtitle = "چند فاکتور را یکجا انتخاب کنید تا استخراج شوند و خروجی تجمیعی بگیرید",
        tableRow = "ردیف",
        tableProduct = "نام کالا",
        tableCode = "کد کالا / انبار",
        tableQty = "تعداد",
        tableUnitPrice = "قیمت واحد",
        tableDiscount = "تخفیف",
        tableTax = "مالیات",
        tableTotal = "مبلغ کل",
        tableEmpty = "هیچ قلم کالایی استخراج نشد",
        btnViewEdit = "مشاهده و ویرایش",
        btnExportExcel = "خروجی اکسل",
        btnSave = "ذخیره در سوابق",
        btnClientStatement = "صورت‌وضعیت کارفرما",
        btnPettyCash = "تسویه تنخواه",
        btnBack = "بازگشت به اسکنر",
        connectedStatus = "متصل",
        ollamaOffStatus = "Ollama خاموش",
        totalSpend = "مجموع کل مخارج (تومان)",
        invoiceCountLabel = "تعداد فاکتورها",
        averageInvoiceLabel = "میانگین ارزش هر فاکتور",
        vendorsCount = "تعداد فروشندگان",
        analyticsEmpty = "هنوز فاکتوری در آرشیو ثبت نشده است. فاکتورها را ذخیره کنید تا گزارش‌ها تشکیل شوند.",
        monthlyExpensesTitle = "هزینه‌ها به تفکیک ماه",
        topVendorsTitle = "۵ تأمین‌کننده برتر",
        topItemsTitle = "پرهزینه‌ترین کالاهای خریداری‌شده",
        quantityPrefix = "تعداد",
        noItemsRecorded = "قالمی برای نمایش ثبت نشده است.",
        historyDialogTitle = "آرشیو و سوابق فاکتورها",
        searchPlaceholder = "جستجو: فروشنده، شماره فاکتور، تاریخ...",
        emptyHistory = "هیچ فاکتوری در این بخش یافت نشد.",
        statusValid = "معتبر",
        statusNeedsReview = "نیاز به بررسی",
        statusInvalid = "نامعتبر",
        currencyToman = "تومان",
        currencyRial = "ریال",
        allYears = "همه سال‌ها",
        backupRestoreTitle = "پشتیبان‌گیری و بازیابی داده‌ها",
        btnBackupRestore = "پشتیبان‌گیری / بازیابی",
        backupSectionTitle = "پشتیبان‌گیری",
        backupSectionDescription = "کل فاکتورها و نگاشت کالاها در یک فایل زیپ ذخیره می‌شود.",
        btnCreateBackup = "تهیه نسخه پشتیبان (Backup)",
        restoreSectionTitle = "بازیابی",
        restoreSectionDescription = "بازگردانی اطلاعات از یک فایل پشتیبان (.zip).",
        btnRestoreBackup = "بازیابی اطلاعات (Restore)",
        backupSuccess = "نسخه پشتیبان با موفقیت ذخیره شد.",
        backupInProgress = "در حال تهیه نسخه پشتیبان…",
        restoreInProgress = "در حال بازیابی اطلاعات…",
        restoreWarning = "بازیابی فایل پشتیبان اطلاعات جاری را جایگزین می‌کند. آیا مطمئن هستید؟",
        btnClose = "بستن",
        btnPrintFormalInvoice = "چاپ فاکتور رسمی (A4)",
        formalInvoiceGenerated = "فاکتور رسمی A4 آماده چاپ شد.",
        commercialInvoiceGenerated = "فاکتور تجاری آماده چاپ شد.",
        btnPrintCommercialInvoice = "چاپ فاکتور تجاری",
        shortcutsTitle = "کلیدهای میانبر کیبورد",
        hotFolderTitle = "پوشه نظارت خودکار اسکنر",
        hotFolderActive = "نظارت اسکنر فعال است",
        hotFolderInfo = "اسکنر یا چاپگر اداره فاکتورها را در این پوشه می‌اندازد؛ هر فایل تازه پس از کامل شدن خودکار پردازش و در سوابق ذخیره می‌شود.",
        btnSelectFolder = "انتخاب پوشه",
        watcherOn = "فعال",
        watcherOff = "غیرفعال",
        btnDonate = "حمایت مالی",
        donationTitle = "حمایت از نرم‌افزار با کریپتو",
        donationSubtitle = "این نرم‌افزار کاملاً رایگان است. در صورت تمایل می‌توانید با اهدای تتر از توسعه و سرورها حمایت کنید.",
        copyWalletAddress = "کپی آدرس کیف‌پول",
        addressCopied = "آدرس کپی شد! سپاس 🙏",
        networkWarning = "⚠️ لطفاً فقط ارز USDT را از طریق شبکه BNB Smart Chain (BEP-20) به این آدرس ارسال کنید.",
        paymentPaid = "پرداخت‌شده",
        paymentPending = "در انتظار پرداخت",
        paymentOverdue = "سررسید گذشته",
        paymentSection = "وضعیت پرداخت",
        dueDateLabel = "تاریخ سررسید (اختیاری)",
        followUpTitle = "دستیار پیگیری و یادآوری پرداخت",
        toneFriendly = "دوستانه",
        toneFormal = "رسمی",
        toneUrgent = "فوری",
        copyMessage = "کپی متن پیام",
        messageCopied = "متن پیام در حافظه کپی شد ✓",
        sendViaWhatsapp = "ارسال در واتساپ",
        markAsPaid = "تغییر وضعیت به پرداخت‌شده",
        remindPayment = "یادآوری پرداخت",
        reconcileTitle = "تطبیق فاکتورها با صورتحساب بانکی",
        reconcilePickFile = "انتخاب فایل صورتحساب (CSV)",
        reconcileChooseHint = "یک خروجی CSV بانک را انتخاب کنید تا واریزی‌ها با فاکتورهای باز تطبیق داده شوند.",
        reconcileMatchedTab = "واریزی‌های شناسایی‌شده",
        reconcileUnmatchedTab = "فاکتورهای بدون واریز",
        reconcileMatchedCount = "تعداد تطبیق‌یافته‌ها",
        reconcileUnmatchedCount = "فاکتورهای بدون واریزی",
        reconcileTotalMatched = "مبلغ کل شناسایی‌شده (تومان)",
        reconcileApplyMatched = "تسویه و پرداخت خودکار فاکتورهای تطبیق‌یافته",
        reconcileBankButton = "تطبیق با بانک",
        matchBadge100 = "تطبیق ۱۰۰٪",
        trackingNumberLabel = "شماره پیگیری",
        reconcileParseError = "خطا در خواندن فایل صورتحساب.",
        btnExportAnalyticsReport = "خروجی گزارش مدیریتی (.xls)",
        analyticsReportSuccess = "گزارش تحلیلی اکسل با موفقیت ذخیره شد.",
        exportQuickBooks = "کوییک‌بوکس (.csv)",
        exportXero = "زیرو (.csv)",
        taxEinValid = "EIN معتبر ✅",
        taxVatValid = "VAT معتبر ✅",
        taxIdInvalid = "قالب نامعتبر ❌",
        mappingsTitle = "نگاشت کالاها به کد انبار",
        mappingsSearchPlaceholder = "جستجو بر اساس نام کالا، فروشنده یا کد انبار...",
        mappingsEmpty = "هنوز نگاشتی ثبت نشده است؛ از فرم زیر اضافه کنید.",
        mappingsNoMatch = "موردی با این مشخصات یافت نشد.",
        mappingsItemName = "نام کالا در فاکتور *",
        mappingsItemCode = "کد انبار شما *",
        mappingsVendor = "فروشنده (خالی = همه)",
        mappingsInternalName = "نام داخلی (اختیاری)",
        mappingsAddBtn = "ثبت +",
        mappingsScopeGlobal = "همه فروشندگان",
    )
    AppLanguage.EN -> AppStrings(
        language = language,
        appSubtitle = "Smart Invoice Extraction • Desktop",
        metricSaved = "Saved Invoices",
        metricEngine = "Engine Status",
        metricBatch = "Batch Mode",
        engineReady = "Ready",
        engineError = "Error",
        engineStarting = "Starting",
        dockScan = "Scan Invoice",
        dockBatch = "Batch Process",
        dockAnalytics = "Analytics",
        dockHistory = "History",
        dockMapping = "Product Codes",
        dockTheme = "Night/Day",
        dropTitle = "Drag & drop invoices here (PDF or image)",
        dropSingle = "Select Single Invoice",
        dropBatch = "Select Batch Invoices",
        emptyBatchTitle = "No active batch process",
        emptyBatchSubtitle = "Select several invoices at once to extract them and get a combined output",
        tableRow = "Row",
        tableProduct = "Product / Service",
        tableCode = "Code",
        tableQty = "Qty",
        tableUnitPrice = "Unit Price",
        tableDiscount = "Discount",
        tableTax = "Tax",
        tableTotal = "Total",
        tableEmpty = "No items extracted",
        btnViewEdit = "View & Edit",
        btnExportExcel = "Export Excel",
        btnSave = "Save Invoice",
        btnClientStatement = "Client Statement",
        btnPettyCash = "Petty Cash",
        btnBack = "Back to Scanner",
        connectedStatus = "Connected",
        ollamaOffStatus = "Ollama is off",
        totalSpend = "Total Spend (Toman)",
        invoiceCountLabel = "Invoice Count",
        averageInvoiceLabel = "Average Invoice",
        vendorsCount = "Vendors Count",
        analyticsEmpty = "No invoices archived yet. Save invoices to build reports.",
        monthlyExpensesTitle = "Monthly Expenses",
        topVendorsTitle = "Top 5 Vendors",
        topItemsTitle = "Top Purchased Items",
        quantityPrefix = "Qty",
        noItemsRecorded = "No items recorded.",
        historyDialogTitle = "Invoice Archive & History",
        searchPlaceholder = "Search: Seller, Invoice #, Date, Project...",
        emptyHistory = "No invoices found in archive.",
        statusValid = "Valid",
        statusNeedsReview = "Needs Review",
        statusInvalid = "Invalid",
        currencyToman = "Toman",
        currencyRial = "Rial",
        allYears = "All Years",
        backupRestoreTitle = "Backup & Restore",
        btnBackupRestore = "Backup / Restore",
        backupSectionTitle = "Backup",
        backupSectionDescription = "All invoices and product mappings are stored in a single zip file.",
        btnCreateBackup = "Create Backup (.zip)",
        restoreSectionTitle = "Restore",
        restoreSectionDescription = "Bring back data from a backup file (.zip).",
        btnRestoreBackup = "Restore from Backup",
        backupSuccess = "Backup created successfully.",
        backupInProgress = "Creating backup…",
        restoreInProgress = "Restoring data…",
        restoreWarning = "Restoring will merge/overwrite existing local records. Are you sure?",
        btnClose = "Close",
        btnPrintFormalInvoice = "Print Formal A4 Invoice",
        formalInvoiceGenerated = "Formal invoice ready for print.",
        commercialInvoiceGenerated = "Commercial invoice ready for print.",
        btnPrintCommercialInvoice = "Print Commercial Invoice",
        shortcutsTitle = "Keyboard Shortcuts",
        hotFolderTitle = "Scanner Hot Folder",
        hotFolderActive = "Scanner Watcher Active",
        hotFolderInfo = "Office scanners and printers drop files into this folder; each new file is auto-processed once complete and saved to history.",
        btnSelectFolder = "Choose Folder",
        watcherOn = "On",
        watcherOff = "Off",
        btnDonate = "Donate",
        donationTitle = "Support with Crypto",
        donationSubtitle = "InvoiceExtract is 100% free & open. If it saves you time, support development with USDT on BNB Chain!",
        copyWalletAddress = "Copy Wallet Address",
        addressCopied = "Copied to clipboard! 🙏",
        networkWarning = "⚠️ Please send only USDT via BNB Smart Chain (BEP-20) to this address.",
        paymentPaid = "Paid",
        paymentPending = "Pending",
        paymentOverdue = "Overdue",
        paymentSection = "Payment status",
        dueDateLabel = "Due date (optional)",
        followUpTitle = "Payment Follow-up Copilot",
        toneFriendly = "Friendly",
        toneFormal = "Formal",
        toneUrgent = "Urgent",
        copyMessage = "Copy message",
        messageCopied = "Message copied to clipboard ✓",
        sendViaWhatsapp = "Send via WhatsApp",
        markAsPaid = "Mark as Paid",
        remindPayment = "Send Reminder",
        reconcileTitle = "Bank Statement Reconciliation",
        reconcilePickFile = "Choose statement file (CSV)",
        reconcileChooseHint = "Pick a bank CSV export to link its deposits with open invoices.",
        reconcileMatchedTab = "Matched Deposits",
        reconcileUnmatchedTab = "Unmatched Invoices",
        reconcileMatchedCount = "Matched count",
        reconcileUnmatchedCount = "Invoices without deposit",
        reconcileTotalMatched = "Total matched (Toman)",
        reconcileApplyMatched = "Mark Matched as Paid",
        reconcileBankButton = "Reconcile Bank",
        matchBadge100 = "100% Match",
        trackingNumberLabel = "Tracking No.",
        reconcileParseError = "Could not read the statement file.",
        btnExportAnalyticsReport = "Export BI Report (.xls)",
        analyticsReportSuccess = "Analytics report exported successfully.",
        exportQuickBooks = "QuickBooks (.csv)",
        exportXero = "Xero (.csv)",
        taxEinValid = "EIN Valid ✅",
        taxVatValid = "VAT Valid ✅",
        taxIdInvalid = "Invalid Format ❌",
        mappingsTitle = "Product & Inventory Code Mappings",
        mappingsSearchPlaceholder = "Search by item name, vendor, or warehouse code...",
        mappingsEmpty = "No mappings saved yet. Add one using the form below.",
        mappingsNoMatch = "No mappings match this search.",
        mappingsItemName = "Invoice Item Name *",
        mappingsItemCode = "Your Warehouse Code *",
        mappingsVendor = "Vendor (Blank = All)",
        mappingsInternalName = "Internal Name (Optional)",
        mappingsAddBtn = "+ Add Mapping",
        mappingsScopeGlobal = "All Vendors",
    )
}

private val EN_DATE_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.ENGLISH)
