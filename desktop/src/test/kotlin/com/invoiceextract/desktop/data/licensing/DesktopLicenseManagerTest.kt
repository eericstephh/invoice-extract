package com.invoiceextract.desktop.data.licensing

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Hermetic JVM tests for [DesktopLicenseManager].
 *
 * Donation-ware has no quota: every store points at a throwaway folder — never
 * `%APPDATA%` — and asserts the unlimited contract (activated by default,
 * everything processes, legacy trial records migrate) plus the atomic disk
 * round-trip, through the public contract.
 */
class DesktopLicenseManagerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }

    @Test
    fun `fresh install starts activated and unlimited`() = runBlocking {
        val manager = managerIn("stores")
        manager.awaitLoaded()

        val state = manager.state.first()
        assertTrue(state.isActivated)
        assertNull(state.licenseKey)
        assertEquals(Int.MAX_VALUE, state.remainingQuota)
        assertFalse(state.isQuotaExhausted)
        assertTrue(manager.canProcess())
        assertTrue(manager.canProcess(10))
        assertTrue(manager.canProcess(10_000))
    }

    @Test
    fun `lifetime count still increments as diagnostics`() = runBlocking {
        val manager = managerIn("stores")
        manager.awaitLoaded()

        manager.recordProcessed()
        manager.recordProcessed(2)

        assertEquals(3, manager.state.first().processedLifetimeCount)
        assertTrue(manager.canProcess(10_000))
    }

    @Test
    fun `legacy trial records migrate to activated on load`() = runBlocking {
        val dir = tempFolder.root.resolve("stores")
        dir.mkdirs()
        File(dir, "license_state.json").writeText(
            """{"isActivated":false,"processedLifetimeCount":10,"licenseKey":null}""",
        )

        val manager = DesktopLicenseManager(json = json, storageDirectory = dir)
        manager.awaitLoaded()

        val state = manager.state.first()
        assertTrue(state.isActivated)
        assertFalse(state.isQuotaExhausted)
        assertTrue(manager.canProcess(10_000))
        assertEquals(10, state.processedLifetimeCount)
    }

    @Test
    fun `shaped keys still activate case insensitively`() = runBlocking {
        val manager = managerIn("stores")
        manager.awaitLoaded()

        val result = manager.activateLicense("  inv-pro-ab12-cd34  ")

        assertTrue(result.isSuccess)
        assertTrue(manager.state.first().isActivated)
        assertEquals("INV-PRO-AB12-CD34", manager.state.first().licenseKey)
    }

    @Test
    fun `junk keys fail without touching state`() = runBlocking {
        val manager = managerIn("stores")
        manager.awaitLoaded()
        manager.recordProcessed()

        assertTrue(manager.activateLicense("WRONG-KEY").isFailure)
        assertTrue(manager.activateLicense("INV-PRO-ABC-DEF").isFailure)
        assertTrue(manager.activateLicense("").isFailure)

        val state = manager.state.first()
        assertTrue(state.isActivated)
        assertNull(state.licenseKey)
        assertEquals(1, state.processedLifetimeCount)
    }

    @Test
    fun `state survives a manager restart`() = runBlocking {
        val dir = tempFolder.root.resolve("stores")
        managerIn("stores", dir).apply {
            awaitLoaded()
            recordProcessed(4)
        }

        val reloaded = DesktopLicenseManager(json = json, storageDirectory = dir)
        reloaded.awaitLoaded()

        assertEquals(4, reloaded.state.first().processedLifetimeCount)
        assertTrue(reloaded.state.first().isActivated)
        assertTrue(reloaded.canProcess(10_000))
    }

    private fun managerIn(name: String, dir: File = tempFolder.root.resolve(name)): DesktopLicenseManager =
        DesktopLicenseManager(json = json, storageDirectory = dir)
}
