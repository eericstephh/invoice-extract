package com.invoiceextract.domain.model

/**
 * The app's notion of a user (Phase 12.1).
 *
 * One model serves both the authenticated and the guest identity, distinguished only by
 * [isGuest]. That is the core of the offline-first design: a guest is a real, persistent
 * user with a stable [id], not a "no user" state, so every invoice the app saves is
 * associated with a user identity from the very first launch — before any network login
 * is possible or required. A later sign-in upgrades the identity; the invoices are not
 * orphaned, because the guest id can be carried over.
 *
 * @property id              Stable, unique identifier. A UUID in every case: generated
 *                           locally for guests, supplied by the auth provider for a
 *                           signed-in account. Serves as the ownership key on every
 *                           persisted record.
 * @property displayName     Human-readable name. `null` for a fresh guest, whose identity
 *                           is not known until the user signs in or picks one.
 * @property contact         Phone number or email the account is reachable at, as given
 *                           at sign-in. `null` for guests.
 * @property isGuest         `true` while the user is operating under the local anonymous
 *                           identity rather than a signed-in account.
 * @property createdAtEpochMs Wall-clock creation time of this identity in milliseconds
 *                           since the Unix epoch. For a guest this is the first-launch
 *                           time, so it survives process death and stays stable.
 */
data class User(
    val id: String,
    val displayName: String?,
    val contact: String?,
    val isGuest: Boolean,
    val createdAtEpochMs: Long,
)
