package com.invoiceextract.desktop.presentation.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.invoiceextract.desktop.presentation.ui.theme.ClayTheme
import com.invoiceextract.domain.validation.ValidationIssue
import com.invoiceextract.domain.validation.ValidationStatus

/**
 * The verdict strip above the invoice table.
 *
 * It is the answer to "can I trust these numbers?": after extraction the invoice is a
 * probabilistic guess, and this banner is the difference between "looks like an invoice"
 * and "arithmetically reconciles". Each of the three outcomes gets one unambiguous color.
 *
 * `when` is exhaustive over the sealed [ValidationStatus], so adding a fourth verdict is a
 * compile error here until it is drawn. [ValidationStatus.Warning] shows its full issue
 * list rather than a count, because "۲ هشدار" tells the user nothing about which line to
 * fix; the list points at the field.
 *
 * @param modifier Modifier for placement.
 */
@Composable
fun ValidationBanner(
    status: ValidationStatus,
    modifier: Modifier = Modifier,
) {
    when (status) {
        ValidationStatus.Valid -> ValidationCard(
            modifier = modifier,
            containerColor = ClayTheme.colors.badgeGreen.background,
            contentColor = ClayTheme.colors.badgeGreen.text,
            icon = Icons.Filled.CheckCircle,
            title = "فاکتور معتبر است",
            issues = emptyList(),
        )

        is ValidationStatus.Warning -> ValidationCard(
            modifier = modifier,
            containerColor = ClayTheme.colors.badgeAmber.background,
            contentColor = ClayTheme.colors.badgeAmber.text,
            icon = Icons.Filled.Warning,
            title = "فاکتور قابل استفاده است، اما بررسی کنید",
            issues = status.reasons,
        )

        is ValidationStatus.Invalid -> ValidationCard(
            modifier = modifier,
            containerColor = ClayTheme.colors.badgeRed.background,
            contentColor = ClayTheme.colors.badgeRed.text,
            icon = Icons.Filled.Close,
            title = "فاکتور نامعتبر است",
            issues = status.criticalErrors,
        )
    }
}

/**
 * One colored card. With no issues it collapses to a single confirmation line; with issues
 * it lists each one's description, which the domain already phrases for the user.
 */
@Composable
private fun ValidationCard(
    modifier: Modifier,
    containerColor: Color,
    contentColor: Color,
    icon: ImageVector,
    title: String,
    issues: List<ValidationIssue>,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = containerColor,
        contentColor = contentColor,
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(imageVector = icon, contentDescription = null)
                Text(text = title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            }

            if (issues.isNotEmpty()) {
                issues.forEach { issue ->
                    Row(
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        // A bullet, not the raw severity enum: the color of the card already
                        // conveys severity, and the field path is developer-facing detail.
                        Text(text = BULLET, style = MaterialTheme.typography.bodySmall)
                        Text(
                            text = issue.description,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }
}

private const val BULLET = "•"
