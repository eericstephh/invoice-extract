package com.invoiceextract.app.core.network

import com.invoiceextract.app.BuildConfig

/**
 * Endpoint configuration for the extraction proxy (Phase 11.2).
 *
 * Every value the network layer needs lives in one object so a staging or
 * self-hosted deployment is a one-place change, not a grep across the codebase.
 *
 * **Secrets.** [CLIENT_SECRET_KEY] and [DEFAULT_BASE_URL] are injected from
 * `local.properties` into `BuildConfig` by the build (Phase 16), so no credential
 * lives in committed source. Anyone can still extract them from a shipped APK —
 * `BuildConfig` is not a secret store — but the value is a low-scope client key that
 * only gates access to the worker's own rate-limited budget, and moving it out of
 * source control is what makes rotation a config change rather than an app release.
 */
object NetworkConfig {

    /**
     * Base URL of the Cloudflare Worker reverse proxy.
     *
     * No trailing slash: callers join with `"$BASE_URL/api/v1/extract"`.
     */
    val DEFAULT_BASE_URL: String = BuildConfig.BASE_URL

    /**
     * Shared secret mirrored from the worker's `APP_CLIENT_KEY`. Injected at build
     * time from `local.properties` (Phase 16); never hardcoded.
     */
    val CLIENT_SECRET_KEY: String = BuildConfig.CLIENT_KEY

    /**
     * Connect timeout. Generous because the worker fronts two LLM providers and
     * a cold start of either can take seconds, but finite, because a hung TCP
     * connection must not leave the user staring at a spinner forever.
     */
    const val CONNECT_TIMEOUT_SECONDS = 45L

    /**
     * Read timeout. LLM reasoning is the slowest hop in the whole pipeline —
     * a large invoice can take 30s+ for the first token — so this is
     * deliberately larger than a typical API timeout.
     */
    const val READ_TIMEOUT_SECONDS = 45L

    /**
     * Write timeout. The request body is OCR text; it is small, so this is the
     * tightest of the three.
     */
    const val WRITE_TIMEOUT_SECONDS = 30L

    /** Full extraction endpoint, derived once so callers never concatenate by hand. */
    val EXTRACTION_ENDPOINT = "$DEFAULT_BASE_URL/api/v1/extract"

    /** The header the worker checks; must match the worker exactly. */
    const val CLIENT_KEY_HEADER = "X-App-Client-Key"
}
