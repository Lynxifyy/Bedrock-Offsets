package com.chimerateam.offsetextractor.ui.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Behaviour of the progress throttle the scan reports through. */
class ProgressGateTest {
    private var now = 1_000L
    private val seen = mutableListOf<Triple<Int, String, Boolean>>()

    private fun gate(maxLogLines: Int = ProgressGate.MAX_LOG_LINES) = ProgressGate(
        forward = { percent, message, writeLine ->
            seen += Triple(percent, message, writeLine)
        },
        clock = { now },
        maxLogLines = maxLogLines
    )

    @Test
    fun `the first report always gets through`() {
        gate().report(0, "Starting")
        assertEquals(listOf(Triple(0, "Starting", true)), seen)
    }

    @Test
    fun `a new message gets through even inside the interval`() {
        val gate = gate()
        gate.report(10, "Pass A")
        now += 1
        gate.report(10, "Pass B")
        assertEquals(2, seen.size)
        assertEquals("Pass B", seen[1].second)
    }

    @Test
    fun `a repeated percentage inside the interval is dropped`() {
        val gate = gate()
        gate.report(10, "Pass A")
        now += 50
        gate.report(11, "Pass A")
        assertEquals(1, seen.size)
    }

    @Test
    fun `the percentage moves once the interval has passed`() {
        val gate = gate()
        gate.report(10, "Pass A")
        now += ProgressGate.INTERVAL_MILLIS
        gate.report(11, "Pass A")
        assertEquals(2, seen.size)
        assertEquals(11, seen[1].first)
        // Only the percentage moved, so no new log row was asked for.
        assertEquals(false, seen[1].third)
    }

    @Test
    fun `a stage that restarts its percentage is never dropped`() {
        val gate = gate()
        gate.report(60, "Pass D")
        now += 1
        gate.report(2, "Pass E")
        assertEquals(2, seen.size)
        assertEquals(2, seen[1].first)
        assertTrue(seen[1].third)
    }

    @Test
    fun `completion is always forwarded`() {
        val gate = gate()
        gate.report(99, "Writing")
        now += 1
        gate.report(100, "Writing")
        assertEquals(100, seen.last().first)
        assertTrue(seen.last().third)
    }

    @Test
    fun `a flood of messages stops writing rows but keeps the bar moving`() {
        val gate = gate(maxLogLines = 5)
        repeat(50) { step ->
            now += ProgressGate.INTERVAL_MILLIS
            gate.report(step, "step $step")
        }
        assertTrue("log rows capped", seen.count { it.third } <= 6)
        assertEquals(49, seen.last().first)
        assertTrue("suppression is counted", gate.suppressed() >= 40)
    }
}
