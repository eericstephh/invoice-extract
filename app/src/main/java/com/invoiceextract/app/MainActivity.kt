package com.invoiceextract.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.navigation.compose.rememberNavController
import com.invoiceextract.app.navigation.InvoiceExtractNavHost
import com.invoiceextract.app.ui.theme.InvoiceExtractTheme

/**
 * The only activity in the application.
 *
 * [installSplashScreen] runs *before* `super.onCreate` because the starting window has
 * to be claimed during the activity's window initialization — after that point the
 * system has already drawn its default launch theme. It reads
 * `Theme.App.Starting` from the manifest, holds the window until the first frame is
 * composited, then swaps in [InvoiceExtractTheme] via `postSplashScreenTheme`.
 *
 * Edge-to-edge is enabled before `setContent` so the very first frame is laid out
 * behind the system bars; the transparent bars are then styled by
 * [InvoiceExtractTheme] to match the resolved light/dark scheme.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            InvoiceExtractTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val navController = rememberNavController()
                    InvoiceExtractNavHost(navController = navController)
                }
            }
        }
    }
}
