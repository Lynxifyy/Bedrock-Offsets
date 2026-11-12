package com.chimerateam.offsetextractor.ui.viewmodel

import android.net.Uri
import com.chimerateam.offsetextractor.ui.state.BatchSummary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for [BatchQueue].
 *
 * The queue is the part of batch processing that has real logic: what runs, in
 * what order, and what happens when one item throws. Pulling it out of the
 * ViewModel is what makes it testable without a dispatcher, a state flow or a
 * fake engine.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BatchQueueTest {

    private fun uri(name: String): Uri = Uri.parse("content://test.provider/$name")

    @Test
    fun `items run in order and every success is counted`() = runTest {
        val order = mutableListOf<String>()
        val queue = BatchQueue(process = { u ->
            order += u.lastPathSegment.orEmpty()
            true
        })

        val summary = queue.run(listOf(uri("a.apk"), uri("b.apk"), uri("c.apk")))

        assertEquals(listOf("a.apk", "b.apk", "c.apk"), order)
        assertEquals(BatchSummary(total = 3, succeeded = 3, failed = 0), summary)
    }

    @Test
    fun `a throwing item is counted as a failure and the queue continues`() = runTest {
        val processed = mutableListOf<String>()
        val reported = mutableListOf<String>()
        val queue = BatchQueue(
            process = { u ->
                processed += u.lastPathSegment.orEmpty()
                if (u.lastPathSegment == "bad.apk") throw IllegalStateException("boom")
                true
            },
            onFailure = { reported += it.message.orEmpty() }
        )

        val summary = queue.run(listOf(uri("one.apk"), uri("bad.apk"), uri("two.apk")))

        assertEquals(listOf("one.apk", "bad.apk", "two.apk"), processed)
        assertEquals(listOf("boom"), reported)
        assertEquals(BatchSummary(total = 3, succeeded = 2, failed = 1), summary)
    }

    @Test
    fun `an item that reports failure without throwing is counted once`() = runTest {
        val queue = BatchQueue(process = { u -> u.lastPathSegment != "bad.apk" })

        val summary = queue.run(listOf(uri("bad.apk"), uri("good.apk")))

        assertEquals(BatchSummary(total = 2, succeeded = 1, failed = 1), summary)
    }

    @Test
    fun `progress reports the item about to run and the running totals`() = runTest {
        val progress = mutableListOf<String>()
        val queue = BatchQueue(
            process = { u -> u.lastPathSegment != "bad.apk" },
            onProgress = {
                progress += "${it.completed}/${it.total} ok=${it.succeeded} bad=${it.failed}"
            }
        )

        queue.run(listOf(uri("a.apk"), uri("bad.apk"), uri("c.apk")))

        assertEquals(
            listOf("0/3 ok=0 bad=0", "1/3 ok=1 bad=0", "2/3 ok=1 bad=1"),
            progress
        )
    }

    @Test
    fun `progress names the item about to run`() = runTest {
        // The dialog shows the file being scanned, so the queue has to say
        // which one it is about to hand to the engine, not just its index.
        val names = mutableListOf<String?>()
        val queue = BatchQueue(
            process = { true },
            onProgress = { names += it.currentName },
            describe = { u -> "${u.lastPathSegment.orEmpty()} (12 MB)" }
        )

        queue.run(listOf(uri("a.apk"), uri("b.apk")))

        assertEquals(listOf("a.apk (12 MB)", "b.apk (12 MB)"), names)
    }

    @Test
    fun `an unnamed item reports no name rather than a blank one`() = runTest {
        val names = mutableListOf<String?>()
        val queue = BatchQueue(
            process = { true },
            onProgress = { names += it.currentName },
            describe = { "" }
        )

        queue.run(listOf(uri("a.apk")))

        assertEquals(listOf(null), names)
    }

    @Test
    fun `cancellation propagates instead of being counted as a failure`() = runTest {
        val queue = BatchQueue(process = { u ->
            if (u.lastPathSegment == "stop.apk") throw CancellationException("cancelled")
            true
        })

        val thrown = runCatching {
            queue.run(listOf(uri("a.apk"), uri("stop.apk"), uri("c.apk")))
        }.exceptionOrNull()

        assertTrue(
            "cancellation must not be swallowed, got $thrown",
            thrown is CancellationException
        )
    }

    @Test
    fun `an empty queue yields a zero summary`() = runTest {
        val summary = BatchQueue(process = { true }).run(emptyList())

        assertEquals(BatchSummary(total = 0, succeeded = 0, failed = 0), summary)
    }
}
