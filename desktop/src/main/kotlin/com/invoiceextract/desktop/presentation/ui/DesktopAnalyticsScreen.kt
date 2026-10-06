package com.invoiceextract.desktop.presentation.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.invoiceextract.desktop.domain.analytics.DashboardAnalytics
import com.invoiceextract.desktop.domain.analytics.MonthlyExpense
import com.invoiceextract.desktop.presentation.AmountFormatter
import com.invoiceextract.desktop.presentation.ui.theme.AppLanguage
import com.invoiceextract.desktop.presentation.ui.theme.AppStrings
import com.invoiceextract.desktop.presentation.ui.theme.ClayTheme
import com.invoiceextract.desktop.presentation.ui.theme.appStrings

/**
 * The Mini-BI expense dashboard: KPIs, a monthly spend chart and the vendor/item
 * leaderboards, all read off one [DashboardAnalytics] snapshot.
 *
 * Takes the snapshot as a plain value rather than the view model, so the layout is a
 * pure function of its input — previewable, and unit-testable everywhere except the
 * [Canvas] pixels. An [DashboardAnalytics.invoiceCount] of zero is exactly the empty
 * archive ([computeAnalytics][com.invoiceextract.desktop.domain.analytics.computeAnalytics]
 * counts what it was given), so that branch renders the empty state.
 *
 * Explicitly wrapped RTL: the window tree already forces RTL, and restating it here
 * keeps this screen correct if it is ever hosted outside that tree.
 */
@Composable
fun DesktopAnalyticsScreen(
    analytics: DashboardAnalytics,
    modifier: Modifier = Modifier,
    strings: AppStrings = appStrings(AppLanguage.FA),
    isEnglish: Boolean = false,
    years: List<Int> = emptyList(),
    selectedYear: Int? = null,
    onSelectYear: (Int?) -> Unit = {},
    onExportAnalytics: () -> Unit = {},
) {
    CompositionLocalProvider(
        LocalLayoutDirection provides if (isEnglish) LayoutDirection.Ltr else LayoutDirection.Rtl,
    ) {
        if (analytics.invoiceCount == 0 && years.isEmpty()) {
            AnalyticsEmptyState(strings = strings, modifier = modifier.fillMaxSize())
        } else {
            LazyColumn(
                modifier = modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item(key = "kpis") {
                    AnalyticsKpiRow(
                        analytics = analytics,
                        strings = strings,
                        isEnglish = isEnglish,
                    )
                }

                item(key = "export") { AnalyticsExportAction(onExport = onExportAnalytics, strings = strings) }

                if (years.isNotEmpty()) {
                    item(key = "period") {
                        PeriodFilterChips(
                            strings = strings,
                            years = years,
                            selectedYear = selectedYear,
                            onSelectYear = onSelectYear,
                        )
                    }
                }

                if (analytics.monthlyExpenses.isNotEmpty()) {
                    item(key = "chart") {
                        MonthlyChartCard(
                            expenses = analytics.monthlyExpenses,
                            strings = strings,
                            isEnglish = isEnglish,
                        )
                    }
                }

                item(key = "leaders") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(IntrinsicSize.Min),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        TopVendorsCard(
                            analytics = analytics,
                            strings = strings,
                            isEnglish = isEnglish,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight(),
                        )
                        TopItemsCard(
                            analytics = analytics,
                            strings = strings,
                            isEnglish = isEnglish,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight(),
                        )
                    }
                }
            }
        }
    }
}

/**
 * The one-tap BI export: a full-width clay action that opens the native save
 * dialog through the caller and streams the filtered snapshot to `.xls`.
 *
 * Full-width rather than a trailing chip so the target stays a comfortable
 * tap on every canvas width, and placed above the period chips so it exports
 * what the chips currently scope.
 */
@Composable
private fun AnalyticsExportAction(
    onExport: () -> Unit,
    strings: AppStrings,
    modifier: Modifier = Modifier,
) {
    OutlinedButton(
        onClick = onExport,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.List,
            contentDescription = null,
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(text = strings.btnExportAnalyticsReport, maxLines = 1)
    }
}

/**
 * One money formatter for the whole dashboard: Western `$12,450.00` in
 * English mode, grouped Toman with its unit word otherwise. Amounts stay
 * Toman-denominated either way (see `effectiveTomanTotal`) — only the
 * rendering follows the language, so a ledger and its chart never disagree.
 */
private fun dashboardMoney(amount: Double, strings: AppStrings, isEnglish: Boolean): String =
    if (isEnglish) {
        AmountFormatter.formatUsd(amount)
    } else {
        "${AmountFormatter.formatToman(amount)} ${strings.currencyToman}"
    }

/**
 * The period filter: an "All Years" chip plus one chip per fiscal year present
 * in the archive. Selecting a year rescopes the KPIs, chart and leaderboards
 * through the view model; the chips themselves always read the unfiltered
 * archive, so a selection can never strand the dashboard with no way back.
 */
@Composable
private fun PeriodFilterChips(
    strings: AppStrings,
    years: List<Int>,
    selectedYear: Int?,
    onSelectYear: (Int?) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PeriodChip(
            label = strings.allYears,
            selected = selectedYear == null,
            onClick = { onSelectYear(null) },
        )
        years.forEach { year ->
            PeriodChip(
                label = if (strings.language == AppLanguage.EN) {
                    year.toString()
                } else {
                    year.toPersianDigits()
                },
                selected = selectedYear == year,
                onClick = { onSelectYear(year) },
            )
        }
    }
}

/**
 * One period option: the selected chip wears the primary container, the rest
 * sit on the surface variant, so the active scope reads at a glance.
 */
@Composable
private fun PeriodChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
        contentColor = if (selected) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        shape = RoundedCornerShape(50),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}

/**
 * The empty archive: a frosted card naming the one action that fills this screen —
 * saving invoices — instead of an empty grid of zeros.
 */
@Composable
private fun AnalyticsEmptyState(
    strings: AppStrings,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        GlassCard {
            Icon(
                imageVector = Icons.Filled.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(EMPTY_STATE_ICON_SIZE),
            )
            Text(
                text = strings.analyticsEmpty,
                style = MaterialTheme.typography.bodyLarge,
                color = ClayTheme.colors.textPrimary,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * Five KPI cards over vibrant gradients: total spend, invoice count, average ticket,
 * seller count and open receivables. Reuses the dashboard [MetricCard] so every
 * metric on the window shares one shape.
 */
@Composable
private fun AnalyticsKpiRow(
    analytics: DashboardAnalytics,
    strings: AppStrings,
    isEnglish: Boolean,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        MetricCard(
            value = dashboardMoney(analytics.totalSpend.toDouble(), strings, isEnglish),
            label = strings.totalSpend,
            icon = Icons.Filled.ShoppingCart,
            background = Brush.linearGradient(
                listOf(Color(0xFF0284C7), Color(0xFF06B6D4)),
            ),
            modifier = Modifier.weight(1f),
        )
        MetricCard(
            value = analytics.invoiceCount.toString(),
            label = strings.invoiceCountLabel,
            icon = Icons.AutoMirrored.Filled.List,
            background = Brush.linearGradient(
                listOf(Color(0xFF4F46E5), Color(0xFF6366F1)),
            ),
            modifier = Modifier.weight(1f),
        )
        MetricCard(
            value = dashboardMoney(analytics.averageInvoice.toDouble(), strings, isEnglish),
            label = strings.averageInvoiceLabel,
            icon = Icons.Filled.Info,
            background = Brush.linearGradient(
                listOf(Color(0xFFDB2777), Color(0xFF9333EA)),
            ),
            modifier = Modifier.weight(1f),
        )
        MetricCard(
            value = analytics.vendorCount.toString(),
            label = strings.vendorsCount,
            icon = Icons.Filled.Person,
            background = Brush.linearGradient(
                listOf(Color(0xFF059669), Color(0xFF10B981)),
            ),
            modifier = Modifier.weight(1f),
        )
        MetricCard(
            value = dashboardMoney(analytics.totalUnpaidAmount.toDouble(), strings, isEnglish),
            label = strings.receivablesLabel(analytics.overdueCount),
            icon = Icons.Filled.Warning,
            background = Brush.linearGradient(
                listOf(Color(0xFFD97706), Color(0xFFF59E0B)),
            ),
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * The monthly spend chart on a floating white surface: one rounded-top gradient bar
 * per Jalali month, the landed amount above it and the month label beneath.
 */
@Composable
private fun MonthlyChartCard(
    expenses: List<MonthlyExpense>,
    strings: AppStrings,
    isEnglish: Boolean,
    modifier: Modifier = Modifier,
) {
    ElevatedCard(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = strings.monthlyExpensesTitle,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = ClayTheme.colors.textPrimary,
            )
            MonthlyBarChart(expenses = expenses, strings = strings, isEnglish = isEnglish)
        }
    }
}

/**
 * Pure-Canvas bars: each month owns a weighted column (bar canvas plus its two
 * labels), so labels can never drift out from under their bar no matter how many
 * months the archive spans. Bars anchor to the baseline; the value rides on top.
 */
@Composable
private fun MonthlyBarChart(
    expenses: List<MonthlyExpense>,
    strings: AppStrings,
    isEnglish: Boolean,
    modifier: Modifier = Modifier,
) {
    // Guarded: an archive of zero-total invoices still draws, as flat nubs on the
    // baseline, instead of dividing by zero.
    val max = expenses.maxOf { it.totalAmount }.coerceAtLeast(1L).toFloat()

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(CHART_HEIGHT),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        expenses.forEach { expense ->
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = dashboardMoney(expense.totalAmount.toDouble(), strings, isEnglish),
                    style = MaterialTheme.typography.labelSmall,
                    color = ClayTheme.colors.textSecondary,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth(),
                )
                Canvas(modifier = Modifier.fillMaxWidth().weight(1f)) {
                    val fraction = (expense.totalAmount.toFloat() / max).coerceIn(0f, 1f)
                    val barHeight = size.height * fraction
                    if (barHeight <= 0f) return@Canvas

                    // Slim the bar inside its slot; the slot count varies with the
                    // archive, so this is proportional rather than a fixed inset.
                    val inset = (size.width * BAR_INSET_FRACTION).coerceAtMost(BAR_MAX_INSET_PX)
                    val left = inset
                    val width = (size.width - inset * 2f).coerceAtLeast(0f)
                    if (width <= 0f) return@Canvas

                    val brush = Brush.verticalGradient(
                        listOf(Color(0xFF38BDF8), Color(0xFF6366F1)),
                    )
                    val top = size.height - barHeight
                    val radius = (width / 2f).coerceAtMost(BAR_MAX_RADIUS_PX)

                    // Round rect for the domed top, then a square patch over the bottom
                    // curve so only the top stays round.
                    drawRoundRect(
                        brush = brush,
                        topLeft = Offset(left, top),
                        size = Size(width, barHeight),
                        cornerRadius = CornerRadius(radius, radius),
                    )
                    drawRect(
                        brush = brush,
                        topLeft = Offset(left, size.height - radius),
                        size = Size(width, radius),
                    )
                }
                Text(
                    text = expense.monthName,
                    style = MaterialTheme.typography.labelSmall,
                    color = ClayTheme.colors.textPrimary,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/**
 * Right-hand leaderboard: the top five sellers with landed amounts and a share bar
 * each. First child of the RTL row, so it docks right as specified.
 */
@Composable
private fun TopVendorsCard(
    analytics: DashboardAnalytics,
    strings: AppStrings,
    isEnglish: Boolean,
    modifier: Modifier = Modifier,
) {
    ElevatedCard(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = strings.topVendorsTitle,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = ClayTheme.colors.textPrimary,
            )
            analytics.topVendors.forEach { vendor ->
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = vendor.name,
                            style = MaterialTheme.typography.bodyMedium,
                            color = ClayTheme.colors.textPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = dashboardMoney(vendor.totalAmount.toDouble(), strings, isEnglish),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = ClayTheme.colors.textPrimary,
                            maxLines = 1,
                        )
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        LinearProgressIndicator(
                            progress = { (vendor.percentage / 100f).coerceIn(0f, 1f) },
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = "${vendor.percentage.toInt()}٪",
                            style = MaterialTheme.typography.labelSmall,
                            color = ClayTheme.colors.textSecondary,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Left-hand leaderboard: the priciest goods bought, ranked, with quantities and row
 * totals. An archive whose invoices carry no line items shows one honest line instead
 * of an empty card.
 */
@Composable
private fun TopItemsCard(
    analytics: DashboardAnalytics,
    strings: AppStrings,
    isEnglish: Boolean,
    modifier: Modifier = Modifier,
) {
    ElevatedCard(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = strings.topItemsTitle,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = ClayTheme.colors.textPrimary,
            )
            if (analytics.topItems.isEmpty()) {
                Text(
                    text = strings.noItemsRecorded,
                    style = MaterialTheme.typography.bodyMedium,
                    color = ClayTheme.colors.textSecondary,
                )
            } else {
                analytics.topItems.forEachIndexed { index, item ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Surface(
                            color = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            shape = CircleShape,
                            modifier = Modifier.size(RANK_BADGE_SIZE),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    text = (index + 1).toPersianDigits(),
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = item.name,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = ClayTheme.colors.textPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = "${strings.quantityPrefix}: ${AmountFormatter.formatQuantity(item.quantity)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = ClayTheme.colors.textSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Text(
                            text = dashboardMoney(item.totalAmount.toDouble(), strings, isEnglish),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = ClayTheme.colors.textPrimary,
                            maxLines = 1,
                        )
                    }
                    if (index != analytics.topItems.lastIndex) {
                        HorizontalDivider(color = StatusColors.hairlineBorder)
                    }
                }
            }
        }
    }
}

/** Renders ASCII digits as Persian-Indic, leaving every other character untouched. */
private fun Int.toPersianDigits(): String = toString().map { char ->
    if (char in '0'..'9') PERSIAN_DIGITS[char - '0'] else char
}.joinToString("")

private const val PERSIAN_DIGITS = "۰۱۲۳۴۵۶۷۸۹"

private val CHART_HEIGHT = 190.dp
private val EMPTY_STATE_ICON_SIZE = 44.dp
private val RANK_BADGE_SIZE = 30.dp

private const val BAR_INSET_FRACTION = 0.16f
private const val BAR_MAX_INSET_PX = 14f
private const val BAR_MAX_RADIUS_PX = 18f
