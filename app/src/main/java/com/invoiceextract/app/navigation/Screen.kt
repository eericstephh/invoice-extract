package com.invoiceextract.app.navigation

/**
 * Type-safe navigation contract.
 *
 * A sealed hierarchy guarantees that the NavHost is exhaustive: the compiler
 * refuses a `when` over [Screen] that misses a destination, so a new screen can
 * never be silently unreachable.
 */
sealed class Screen(val route: String) {

    /** Landing screen: scan trigger and quota summary. */
    data object Home : Screen(ROUTE_HOME)

    /** Post-scan review of the extracted invoice before it is persisted. */
    data object Review : Screen(ROUTE_REVIEW)

    /** List of previously saved invoices. */
    data object History : Screen(ROUTE_HISTORY)

    /** Multi-document batch processing and consolidated export. */
    data object Batch : Screen(ROUTE_BATCH)

    companion object {
        const val ROUTE_HOME = "home"
        const val ROUTE_REVIEW = "review"
        const val ROUTE_HISTORY = "history"
        const val ROUTE_BATCH = "batch"
    }
}
