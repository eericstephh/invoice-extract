package com.invoiceextract.app.data.file

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * Automated pruning of the staged-invoice cache (Phase 17).
 *
 * [FileMetadataHelper] copies every imported document into `cacheDir/invoices/` and
 * nothing in the pipeline ever deletes what it writes. On a low-end device a few weeks
 * of batch imports would fill the cache and the next import would fail with ENOSPC.
 * This class is the garbage collector for that directory.
 *
 * Two policies, applied in order:
 *
 *  1. **Expiry.** Any file older than [MAX_CACHE_AGE_MS] is deleted. A staged copy is
 *     only useful for as long as the user is still working on that invoice; yesterday's
 *     copy is dead weight.
 *  2. **Eviction.** If the survivors still exceed [MAX_CACHE_SIZE_BYTES], the oldest are
 *     removed until the directory fits. Oldest-first keeps the file the user just
 *     imported — the one most likely to be re-opened from the review screen.
 *
 * **Failure isolation.** Nothing this class does may crash the app. The cache directory
 * may be missing, a file may be half-deleted, or the filesystem may refuse a delete; each
 * failure is logged and the sweep continues over the remaining files. [pruneStaleCache]
 * reports aggregate failure through [Result] but never throws.
 *
 * **Off the main thread by contract.** [pruneStaleCache] is a suspend function that
 * dispatches on [Dispatchers.IO], so a caller may invoke it from `onCreate` without
 * blocking startup.
 */
class CacheCleaner(private val context: Context) {

    /**
     * Runs one expiry pass followed by one size-eviction pass over the invoice cache.
     *
     * Idempotent and safe to call repeatedly: a file already gone costs one no-op
     * [File.delete]. Safe to call concurrently as well — the worst case is two
     * overlapping sweeps that both try to delete the same file, and one of them wins.
     *
     * @return [Result.success] when the sweep ran to completion (individual file
     *   deletions that failed are logged, not fatal), or [Result.failure] carrying the
     *   [IOException] that stopped the directory from being scanned at all.
     */
    suspend fun pruneStaleCache(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val cacheDir = File(context.cacheDir, CACHE_DIR_NAME)

            // Not a directory means nothing was ever staged (or the user cleared the
            // cache); either way there is nothing to do.
            if (!cacheDir.isDirectory) return@runCatching

            val now = System.currentTimeMillis()

            // (1) Expiry. lastModified() is wall-clock time, so it is compared against
            // the wall clock, not a monotonic source.
            cacheDir.listFiles()
                ?.filter { it.isFile }
                ?.filter { now - it.lastModified() > MAX_CACHE_AGE_MS }
                ?.forEach { deleteQuietly(it) }

            // (2) Eviction. Re-list after the expiry pass, which may already have
            // brought the directory back under budget.
            val survivors = cacheDir.listFiles()?.filter { it.isFile } ?: emptyList()
            var totalSize = survivors.sumOf { it.length() }
            if (totalSize <= MAX_CACHE_SIZE_BYTES) return@runCatching

            // Oldest first: lastModified ascending. Iterate until the budget holds.
            val byOldest = survivors.sortedBy { it.lastModified() }
            val iterator = byOldest.iterator()
            while (totalSize > MAX_CACHE_SIZE_BYTES && iterator.hasNext()) {
                val candidate = iterator.next()
                val size = candidate.length()
                if (deleteQuietly(candidate)) {
                    totalSize -= size
                }
            }
        }
    }

    /**
     * Deletes [file] and absorbs the failure instead of propagating it.
     *
     * @return `true` when the file is gone from disk; `false` when it could not be
     *   deleted, so the caller can skip subtracting it from a running total.
     */
    private fun deleteQuietly(file: File): Boolean {
        return try {
            if (file.delete()) {
                true
            } else {
                Log.w(TAG, "Could not delete cached file: ${file.absolutePath}")
                false
            }
        } catch (cause: IOException) {
            // A half-deleted file or a transient I/O error: log and move on so one bad
            // file cannot abort the sweep of the rest.
            Log.w(TAG, "I/O error deleting cached file: ${file.absolutePath}", cause)
            false
        }
    }

    private companion object {
        private const val TAG = "CacheCleaner"

        /** Staging directory; matches [FileMetadataHelper]'s CACHE_DIR_NAME. */
        private const val CACHE_DIR_NAME = "invoices"

        /** Retention window: 24 hours. */
        const val MAX_CACHE_AGE_MS = 24 * 60 * 60 * 1000L

        /** Hard ceiling on the whole staging directory: 50 MB. */
        const val MAX_CACHE_SIZE_BYTES = 50 * 1024 * 1024L
    }
}
