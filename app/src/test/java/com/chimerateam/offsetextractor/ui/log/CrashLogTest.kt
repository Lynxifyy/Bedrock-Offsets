package com.chimerateam.offsetextractor.ui.log

import kotlin.io.path.createTempDirectory
import org.junit.Assert.assertTrue
import org.junit.Test

/** A crash has to leave text the log pane can replay on the next start. */
class CrashLogTest {

    private fun crashText(): String {
        val dir = createTempDirectory("crash-").toFile()
        val error = IllegalStateException("signature table ran out of memory")
        CrashLog(dir).record(Thread.currentThread(), error)
        return LogFile(dir).read()
    }

    @Test
    fun writesTheTypeMessageAndFramesAsCrashLines() {
        val text = crashText()

        assertTrue(text, text.contains("CRASH on "))
        assertTrue(text, text.contains("java.lang.IllegalStateException: signature table ran out of memory"))
        assertTrue(text, text.contains("CRASH end of report"))
    }

    @Test
    fun everyLineStartsWithTheMarkerSoThePaneCanFilter() {
        val lines = crashText().lineSequence().filter { it.isNotBlank() }.toList()

        assertTrue(lines.isNotEmpty())
        for (line in lines) {
            assertTrue(line, line.contains("CRASH"))
        }
    }

    @Test
    fun aSecondCrashAppendsInsteadOfReplacingTheFirst() {
        val dir = createTempDirectory("crash-").toFile()
        val log = CrashLog(dir)

        log.record(Thread.currentThread(), IllegalStateException("first"))
        log.record(Thread.currentThread(), IllegalStateException("second"))
        val text = LogFile(dir).read()

        assertTrue(text, text.contains("first"))
        assertTrue(text, text.contains("second"))
    }
}
