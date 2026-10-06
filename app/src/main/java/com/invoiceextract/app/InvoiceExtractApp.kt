package com.invoiceextract.app

import android.app.Application
import com.invoiceextract.app.data.file.CacheCleaner
import com.invoiceextract.app.di.appModules
import com.invoiceextract.app.di.coreModules
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin
import org.koin.core.logger.Level
import org.koin.java.KoinJavaComponent
/**
 * Application entry point.
 *
 * Boots the Koin dependency graph as early as possible so that every later
 * component (view models, repositories, engines) can resolve its dependencies
 * without carrying a manual initialization order.
 *
 * **Startup hygiene (Phase 17).** The invoice cache is pruned from a background
 * coroutine on an application-scoped [SupervisorJob], so `onCreate` returns the instant
 * the graph is up and the main thread never touches the filesystem. The sweep is
 * fire-and-forget: its result is consumed here rather than surfaced to the user, because
 * a cache that did not get cleaned is a cosmetic problem, not a crash.
 */
class InvoiceExtractApp : Application() {

    /**
     * Application-scoped background scope.
     *
     * A [SupervisorJob] so a failure in one background task cannot cancel another —
     * a cache sweep that throws must not take down anything else launched here later.
     * Dispatched on [Dispatchers.IO] by default, which is where every launch from this
     * class belongs.
     */
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()

        startKoin {
            androidLogger(Level.ERROR)
            androidContext(this@InvoiceExtractApp)
            modules(coreModules + appModules)
        }

        // Resolved inside the coroutine rather than here: startKoin above has already
        // returned by the time the body runs, so the graph is guaranteed complete, and
        // the lookup itself stays off the main thread. Launched, not awaited — onCreate
        // returns immediately and the sweep runs on IO.
        applicationScope.launch {
            KoinJavaComponent.get<CacheCleaner>(CacheCleaner::class.java).pruneStaleCache()
        }
    }
}
