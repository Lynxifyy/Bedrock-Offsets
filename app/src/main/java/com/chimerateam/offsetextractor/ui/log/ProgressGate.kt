package com.chimerateam.offsetextractor.ui.log

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Throttles the progress reports a scan produces.
 *
 * The engine reports from its own thread, and on a large library a stage can
 * report thousands of times. Every report used to update the screen state, write
 * a log line and append that line to the log file, so the pane spent more time
 * laying out log rows than the scan spent working, and the progress bar stuttered
 * instead of moving.
 *
 * This gate lets a report through when it carries news:
 *
 * - the message changed, which is what the live log shows;
 * - the percentage moved and at least [INTERVAL_MILLIS] passed since the last
 *   forward, so the bar animates without flooding;
 * - the run started or finished, which must never be dropped.
 *
 * Everything else is folded into the next report, so the last state a scan
 * reports is always the one on screen.
 *
 * @param forward receives the reports that survive the gate, with a flag that
 *   says whether the report is worth a row in the live log.
 * @param clock millisecond clock, injectable for tests.
 * @param maxLogLines how many messages one run may write to the live log; a
 *   run that reports more keeps updating the bar and stops writing rows.
 */
class ProgressGate(
    private val forward: (percent: Int, message: String, writeLine: Boolean) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
    private val maxLogLines: Int = MAX_LOG_LINES
) {
    private val lastPercent = AtomicInteger(NOT_STARTED)
    private val lastMessage = AtomicLong(0L)
    private val lastAt = AtomicLong(Long.MIN_VALUE)
    private val written = AtomicInteger(0)
    private val reported = AtomicInteger(0)

    /**
     * Offers one report.
     *
     * Safe to call from any thread, and cheap enough for a hot loop: a dropped
     * report costs two atomic reads.
     *
     * @param percent completion between 0 and 100.
     * @param message short description of the current step.
     */
    fun report(percent: Int, message: String) {
        val now = clock()
        val previous = lastPercent.get()
        val fingerprint = message.hashCode().toLong()
        val messageChanged = lastMessage.get() != fingerprint
        val first = previous == NOT_STARTED
        val finished = percent >= 100 && previous < 100
        // A percentage that went backwards is a new stage; it must be visible.
        val restarted = percent < previous
        val due = now - lastAt.get() >= INTERVAL_MILLIS
        val moved = percent != previous
        if (!first && !finished && !restarted && !messageChanged && !(due && moved)) {
            return
        }
        lastPercent.set(percent)
        lastMessage.set(fingerprint)
        lastAt.set(now)
        val news = first || finished || restarted || messageChanged
        if (news && written.incrementAndGet() > maxLogLines) {
            reported.incrementAndGet()
            forward(percent, message, false)
            return
        }
        forward(percent, message, news)
    }

    /**
     * How many messages this run had to drop to keep the pane responsive.
     *
     * @return the count, which the caller reports once at the end of the run so
     *   a silent gap in the log is explained instead of hidden.
     */
    fun suppressed(): Int = reported.get()

    /** Constants for the gate. */
    companion object {
        /** Shortest gap between two forwards that only move the percentage. */
        const val INTERVAL_MILLIS = 250L

        /** Log lines one run may write; the bar keeps updating past this. */
        const val MAX_LOG_LINES = 400

        /** Value of [lastPercent] before the first report. */
        private const val NOT_STARTED = -1
    }
}
