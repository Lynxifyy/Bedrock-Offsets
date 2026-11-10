package com.chimerateam.offsetextractor.ui.viewmodel

import android.net.Uri
import com.chimerateam.offsetextractor.ui.state.BatchProgress
import com.chimerateam.offsetextractor.ui.state.BatchSummary
import kotlinx.coroutines.CancellationException

/**
 * Runs a list of APKs through one worker, in order.
 *
 * Sequential rather than parallel on purpose: the engine maps an entire native
 * library at once, so two scans at the same time would double the peak memory
 * for no gain on a device with limited RAM.
 *
 * A failure on one item is counted and the queue moves on, so a single bad file
 * cannot sink a batch. Cancellation is different: it propagates, because the
 * caller asked for the whole batch to stop.
 *
 * @param process handles one APK; returns whether it produced a result.
 * @param onProgress called before each item with the totals so far.
 * @param onFailure called when an item throws, so the caller can log it. It is
 *   a suspend function because reporting a failure may itself touch state.
 */
class BatchQueue(
    private val process: suspend (Uri) -> Boolean,
    private val onProgress: (BatchProgress) -> Unit = {},
    private val onFailure: suspend (Throwable) -> Unit = {}
) {

    /**
     * Processes every URI in order.
     *
     * @param uris documents returned by the multi-document picker.
     * @return how many succeeded and how many failed.
     * @throws CancellationException when the queue is cancelled; the exception
     *   is never converted into a failure count.
     */
    suspend fun run(uris: List<Uri>): BatchSummary {
        var succeeded = 0
        var failed = 0

        uris.forEachIndexed { index, uri ->
            onProgress(
                BatchProgress(
                    total = uris.size,
                    completed = index,
                    succeeded = succeeded,
                    failed = failed
                )
            )

            val ok = try {
                process(uri)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                onFailure(throwable)
                false
            }
            if (ok) succeeded++ else failed++
        }

        return BatchSummary(total = uris.size, succeeded = succeeded, failed = failed)
    }
}
