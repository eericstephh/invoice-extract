package com.invoiceextract.desktop.presentation.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState
import com.invoiceextract.desktop.data.mapping.ProductMapping
import com.invoiceextract.desktop.presentation.ui.theme.AppLanguage
import com.invoiceextract.desktop.presentation.ui.theme.AppStrings
import com.invoiceextract.desktop.presentation.ui.theme.ClayTheme
import com.invoiceextract.desktop.presentation.ui.theme.appStrings
import com.invoiceextract.desktop.presentation.ui.theme.clayTextFieldColors

/**
 * The warehouse-code mapping manager: every invoice-name → internal-code row the
 * merchant has taught, searchable and actionable.
 *
 * A fixed-size window over the mapping store (760×600), mirroring the history
 * dialog's windowing contract: a `DialogWindow` never inherits the app-wide RTL
 * provision, so it is re-provided here explicitly, and the size lives on the window
 * state itself — fixed and non-resizable, so the layout never sits inside dead window
 * space.
 *
 * Search filters in real time across the three fields a merchant remembers a row by
 * (invoice name, vendor, warehouse code); a blank query shows everything. The add
 * form below the list records a new row; deleting one forgets the *rule*, never the
 * codes already stamped onto saved invoices.
 *
 * Only core Material icons are used (close, search, delete, add); nothing here needs
 * the extended set the build deliberately stays off.
 *
 * @param mappings The store's current content, in insertion order; re-collected by
 *   the caller so every save and delete re-renders.
 * @param onDismiss Called when the dialog is closed by any path.
 * @param onAdd Called with the form's four fields when the user records a row.
 * @param onDelete Called with the row's id when the user deletes it.
 */
@Composable
fun ProductMappingsDialog(
    mappings: List<ProductMapping>,
    onDismiss: () -> Unit,
    onAdd: (rawName: String, vendor: String?, code: String, internalName: String?) -> Unit,
    onDelete: (String) -> Unit,
    strings: AppStrings = appStrings(AppLanguage.FA),
    isEnglish: Boolean = false,
) {
    var query by remember { mutableStateOf("") }

    // Real-time filtering across the identifying fields; a blank query shows everything.
    // `remember` on both inputs so typing filters without re-reading the store.
    val filtered = remember(query, mappings) {
        if (query.isBlank()) {
            mappings
        } else {
            mappings.filter { mapping ->
                mapping.rawItemName.contains(query, ignoreCase = true) ||
                    mapping.vendorName?.contains(query, ignoreCase = true) == true ||
                    mapping.internalProductCode.contains(query, ignoreCase = true)
            }
        }
    }

    CompositionLocalProvider(
        LocalLayoutDirection provides if (isEnglish) LayoutDirection.Ltr else LayoutDirection.Rtl,
    ) {
        DialogWindow(
            onCloseRequest = onDismiss,
            state = rememberDialogState(size = DpSize(DIALOG_WIDTH, DIALOG_HEIGHT)),
            title = strings.mappingsTitle,
            resizable = false,
        ) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                tonalElevation = 3.dp,
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = strings.mappingsTitle,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = onDismiss) {
                            Icon(imageVector = Icons.Filled.Close, contentDescription = null)
                        }
                    }

                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 12.dp),
                        placeholder = { Text(text = strings.mappingsSearchPlaceholder) },
                        leadingIcon = {
                            Icon(imageVector = Icons.Filled.Search, contentDescription = null)
                        },
                        singleLine = true,
                        colors = clayTextFieldColors(ClayTheme.colors),
                    )

                    if (filtered.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = if (mappings.isEmpty()) {
                                    strings.mappingsEmpty
                                } else {
                                    strings.mappingsNoMatch
                                },
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                            )
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(filtered, key = { it.id }) { mapping ->
                                MappingRow(
                                    mapping = mapping,
                                    scopeGlobalLabel = strings.mappingsScopeGlobal,
                                    onDelete = { onDelete(mapping.id) },
                                )
                            }
                        }
                    }

                    MappingAddForm(
                        onAdd = onAdd,
                        itemNameLabel = strings.mappingsItemName,
                        itemCodeLabel = strings.mappingsItemCode,
                        vendorLabel = strings.mappingsVendor,
                        internalNameLabel = strings.mappingsInternalName,
                        addLabel = strings.mappingsAddBtn,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
            }
        }
    }
}

/**
 * One mapping row: the invoice name, the vendor scope, the warehouse code as a chip,
 * and the delete action.
 */
@Composable
private fun MappingRow(
    mapping: ProductMapping,
    scopeGlobalLabel: String,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ElevatedCard(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = mapping.rawItemName,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = mapping.vendorName?.ifBlank { null } ?: scopeGlobalLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Surface(
                color = StatusColors.slateContainer,
                contentColor = StatusColors.onSlateContainer,
                shape = RoundedCornerShape(50),
            ) {
                Text(
                    text = mapping.internalProductCode,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
            IconButton(onClick = onDelete) {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

/**
 * The manual-entry form: invoice name and warehouse code are required, vendor and
 * internal name optional (a missing vendor records a global mapping). The action
 * stays disabled until the required pair is present, and clears itself after each
 * record so consecutive entries start clean.
 */
@Composable
private fun MappingAddForm(
    onAdd: (rawName: String, vendor: String?, code: String, internalName: String?) -> Unit,
    itemNameLabel: String,
    itemCodeLabel: String,
    vendorLabel: String,
    internalNameLabel: String,
    addLabel: String,
    modifier: Modifier = Modifier,
) {
    var rawName by remember { mutableStateOf("") }
    var vendor by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var internalName by remember { mutableStateOf("") }

    val canAdd = rawName.isNotBlank() && code.isNotBlank()

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = rawName,
                onValueChange = { rawName = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text(text = itemNameLabel) },
                singleLine = true,
                colors = clayTextFieldColors(ClayTheme.colors),
            )
            OutlinedTextField(
                value = code,
                onValueChange = { code = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text(text = itemCodeLabel) },
                singleLine = true,
                colors = clayTextFieldColors(ClayTheme.colors),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = vendor,
                onValueChange = { vendor = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text(text = vendorLabel) },
                singleLine = true,
                colors = clayTextFieldColors(ClayTheme.colors),
            )
            OutlinedTextField(
                value = internalName,
                onValueChange = { internalName = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text(text = internalNameLabel) },
                singleLine = true,
                colors = clayTextFieldColors(ClayTheme.colors),
            )
            Button(
                onClick = {
                    onAdd(rawName, vendor.ifBlank { null }, code, internalName.ifBlank { null })
                    rawName = ""
                    vendor = ""
                    code = ""
                    internalName = ""
                },
                enabled = canAdd,
            ) {
                Icon(imageVector = Icons.Filled.Add, contentDescription = null)
                Spacer(modifier = Modifier.width(6.dp))
                Text(text = addLabel, maxLines = 1)
            }
        }
    }
}

private val DIALOG_WIDTH = 760.dp
private val DIALOG_HEIGHT = 600.dp
