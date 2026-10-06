package com.invoiceextract.app.presentation.profile

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.invoiceextract.app.R
import com.invoiceextract.domain.model.AuthState
import com.invoiceextract.domain.model.User
import org.koin.androidx.compose.koinViewModel

/** Icon / spacing metrics shared by the sheet's buttons and banners. */
private object SheetMetrics {
    val InlineIconSize = 18.dp
    val InlineIconSpacing = 8.dp
    val HeaderIconSize = 40.dp
    val ProgressStrokeWidth = 2.dp
}

/**
 * The Profile & Authentication bottom sheet (Phase 12.2).
 *
 * The single entry point into the account: a guest sees the sign-in form, an authenticated
 * user sees the account summary and the sign-out action. Pure function of
 * [ProfileUiState] — every input is delegated to [ProfileViewModel], which re-derives and
 * re-emits, so the sheet never holds auth state of its own beyond the two text fields.
 *
 * Layout direction is pinned to RTL locally rather than relying solely on
 * `InvoiceExtractTheme(forceRtl = true)`: Persian is right-to-left, and pinning it here
 * keeps the sheet correct even if it is ever hosted outside the forced-RTL tree.
 *
 * @param onDismissRequest Called when the sheet is dismissed by swipe, scrim tap or back;
 *   the host uses it to drop the sheet from its own state.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileBottomSheet(
    onDismissRequest: () -> Unit,
    viewModel: ProfileViewModel = koinViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val sheetState = rememberModalBottomSheetState()

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
    ) {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            ProfileSheetBody(
                uiState = uiState,
                onSignIn = viewModel::signIn,
                onSignOut = viewModel::signOut,
                onClearError = viewModel::clearError,
            )
        }
    }
}

/**
 * Switches the sheet's body on the auth state and renders the shared error banner, so the
 * two layouts stay structurally identical apart from their identity-driven content.
 */
@Composable
private fun ProfileSheetBody(
    uiState: ProfileUiState,
    onSignIn: (identifier: String, secret: String) -> Unit,
    onSignOut: () -> Unit,
    onClearError: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .padding(top = 4.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        when (val authState = uiState.authState) {
            is AuthState.Loading -> ProfileLoading()

            is AuthState.Guest -> GuestProfile(
                isSubmitting = uiState.isSubmitting,
                errorMessage = uiState.errorMessage,
                onSignIn = onSignIn,
                onClearError = onClearError,
            )

            is AuthState.Authenticated -> AuthenticatedProfile(
                user = authState.user,
                isSubmitting = uiState.isSubmitting,
                errorMessage = uiState.errorMessage,
                onSignOut = onSignOut,
                onClearError = onClearError,
            )
        }
    }
}

/**
 * The settle state while the session store resolves. Normally too brief to see — the
 * ViewModel's guest bootstrap replaces it on the next emission — but rendering an explicit
 * spinner keeps the sheet's height stable instead of flashing an empty sheet.
 */
@Composable
private fun ProfileLoading(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 24.dp),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator()
    }
}

/**
 * The guest layout: identity header, credential form and the primary sign-in action.
 */
@Composable
private fun GuestProfile(
    isSubmitting: Boolean,
    errorMessage: String?,
    onSignIn: (identifier: String, secret: String) -> Unit,
    onClearError: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // saveable so an orientation change does not wipe a half-typed phone number.
    var identifier by rememberSaveable { mutableStateOf("") }
    var secret by rememberSaveable { mutableStateOf("") }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ProfileHeader(
            icon = Icons.Outlined.Person,
            title = stringResource(R.string.profile_guest_title),
            subtitle = stringResource(R.string.profile_guest_subtitle),
        )

        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = identifier,
                onValueChange = { identifier = it },
                label = { Text(stringResource(R.string.profile_identifier_label)) },
                singleLine = true,
                enabled = !isSubmitting,
                // Accepts both an email and a phone number, so the most permissive keyboard
                // is offered; the field stays a plain text input either way.
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = secret,
                onValueChange = { secret = it },
                label = { Text(stringResource(R.string.profile_secret_label)) },
                singleLine = true,
                enabled = !isSubmitting,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
        }

        ErrorBanner(errorMessage = errorMessage, onDismiss = onClearError)

        Button(
            onClick = { onSignIn(identifier, secret) },
            enabled = !isSubmitting,
            modifier = Modifier.fillMaxWidth(),
        ) {
            SignInButtonContent(isSubmitting = isSubmitting)
        }
    }
}

/**
 * The authenticated layout: identity header, the active-account badge and the destructive
 * sign-out action.
 */
@Composable
private fun AuthenticatedProfile(
    user: User,
    isSubmitting: Boolean,
    errorMessage: String?,
    onSignOut: () -> Unit,
    onClearError: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ProfileHeader(
            icon = Icons.Filled.Verified,
            title = user.displayName ?: stringResource(R.string.profile_authenticated_title),
            subtitle = user.contact ?: stringResource(R.string.profile_authenticated_subtitle),
        )

        ActiveAccountBadge()

        ErrorBanner(errorMessage = errorMessage, onDismiss = onClearError)

        OutlinedButton(
            onClick = onSignOut,
            enabled = !isSubmitting,
            // The M3 error role is the destructive idiom: red on the surface, bordered so it
            // reads as a secondary action rather than a primary one.
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = MaterialTheme.colorScheme.error,
            ),
            border = BorderStroke(
                width = 1.dp,
                color = MaterialTheme.colorScheme.error,
            ),
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (isSubmitting) {
                CircularProgressIndicator(
                    modifier = Modifier.size(SheetMetrics.InlineIconSize),
                    strokeWidth = SheetMetrics.ProgressStrokeWidth,
                    color = MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.width(SheetMetrics.InlineIconSpacing))
            }
            Text(text = stringResource(R.string.profile_sign_out_action))
        }
    }
}

/**
 * Identity header shared by both layouts: a leading icon with the display name and the
 * contact line beneath it, mirroring the label-above-value pattern the rest of the app uses
 * so long values wrap instead of clipping in either layout direction.
 */
@Composable
private fun ProfileHeader(
    icon: ImageVector,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(SheetMetrics.HeaderIconSize),
            tint = MaterialTheme.colorScheme.primary,
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * The "account is active and synced" badge: a tonal primary container with a check icon, so
 * the signed-in state reads as a confirmation rather than a neutral label.
 */
@Composable
private fun ActiveAccountBadge(modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Filled.CheckCircle,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
            )
            Text(
                text = stringResource(R.string.profile_status_active),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

/**
 * A small red banner for the last credential failure. Rendered only when there is a
 * message; the dismiss button hands the clear back to the ViewModel so the message cannot
 * linger after the user has read it.
 */
@Composable
private fun ErrorBanner(
    errorMessage: String?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (errorMessage == null) return

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Filled.Error,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = errorMessage,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
        IconButton(onClick = onDismiss) {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = stringResource(R.string.profile_dismiss_error),
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

/** The primary sign-in button's label, swapped for a spinner while the call is in flight. */
@Composable
private fun SignInButtonContent(isSubmitting: Boolean, modifier: Modifier = Modifier) {
    if (isSubmitting) {
        CircularProgressIndicator(
            modifier = modifier.size(SheetMetrics.InlineIconSize),
            strokeWidth = SheetMetrics.ProgressStrokeWidth,
            color = MaterialTheme.colorScheme.onPrimary,
        )
        Spacer(Modifier.width(SheetMetrics.InlineIconSpacing))
    }
    Text(text = stringResource(R.string.profile_sign_in_action))
}
