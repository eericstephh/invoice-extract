package com.invoiceextract.app.presentation.profile

import com.invoiceextract.domain.model.AuthState

/**
 * UI state of the Profile bottom sheet (Phase 12.2).
 *
 * A single data class rather than a sealed hierarchy: the sheet switches its body on the
 * type of [authState], while [isSubmitting] and [errorMessage] are two orthogonal flags
 * that apply to *both* the guest and the authenticated layout. Folding them into
 * [AuthState] would have been wrong — the domain contract knows nothing about UI progress
 * or user-facing messages — so the domain stays pure and the UI keeps its own concerns.
 *
 * @property authState    The current identity. Drives which body the sheet renders.
 * @property isSubmitting `true` while a sign-in or sign-out call is in flight, so the
 *                        action buttons can disable themselves and a double tap can never
 *                        launch a competing credential operation.
 * @property errorMessage A localized, user-facing failure message from the last credential
 *                        operation, or `null` when it succeeded or none has run yet.
 */
data class ProfileUiState(
    val authState: AuthState = AuthState.Loading,
    val isSubmitting: Boolean = false,
    val errorMessage: String? = null,
)
