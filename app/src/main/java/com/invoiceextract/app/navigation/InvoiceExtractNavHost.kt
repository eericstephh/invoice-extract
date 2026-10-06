package com.invoiceextract.app.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.invoiceextract.app.data.session.InvoiceSessionHolder
import com.invoiceextract.app.presentation.batch.BatchScreen
import com.invoiceextract.app.presentation.review.ReviewScreen
import com.invoiceextract.app.ui.screens.HistoryScreen
import com.invoiceextract.app.ui.screens.home.HomeScreen
import org.koin.compose.koinInject

/**
 * The single navigation graph of the application.
 *
 * Destinations are declared against the [Screen] sealed contract rather than raw
 * strings, so routes and destinations stay in sync at compile time.
 *
 * The hand-off from Home to Review goes through [InvoiceSessionHolder] rather than a
 * navigation argument: the invoice carries the full OCR text and a list of edited items,
 * which is far more than a `Bundle` can bear, and the holder is the process-scoped place
 * both screens already agree on.
 *
 * @param navController The controller that owns the back stack.
 * @param startDestination Route of the first visible destination.
 */
@Composable
fun InvoiceExtractNavHost(
    navController: NavHostController,
    startDestination: String = Screen.Home.route,
) {
    val sessionHolder: InvoiceSessionHolder = koinInject()

    NavHost(
        navController = navController,
        startDestination = startDestination,
    ) {
        composable(route = Screen.Home.route) {
            HomeScreen(
                onReview = { invoice ->
                    sessionHolder.setActiveInvoice(invoice)
                    navController.navigate(Screen.Review.route)
                },
                onNavigateToBatch = { navController.navigate(Screen.Batch.route) },
            )
        }
        composable(route = Screen.Review.route) {
            ReviewScreen(
                onNavigateBack = { navController.popBackStack() },
                // Save is committed before this fires, so popping lands the user back
                // on Home with the session already cleared. Swap for a
                // navigate(Screen.History.route) once the History screen reads the repo.
                onSaveSuccess = { navController.popBackStack() },
            )
        }
        composable(route = Screen.History.route) {
            HistoryScreen()
        }
        composable(route = Screen.Batch.route) {
            BatchScreen(
                onNavigateBack = { navController.popBackStack() },
            )
        }
    }
}
