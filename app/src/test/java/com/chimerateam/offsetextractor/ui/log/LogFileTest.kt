package com.chimerateam.offsetextractor.ui.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

/** The spilled log is what Copy and Share hand the user. */
class LogFileTest {

    private fun entry(message: String, at: Long = 0L) =
        LogEntry(at, LogLevel.Info, "TAG", message)

    @Test
    fun appendsEveryLineInOrder() {
        val file = LogFile(createTempDirectory("log-").toFile())

        file.append(entry("first"))
        file.append(entry("second"))

        val text = file.read()
        assertTrue(text.indexOf("first") in 0 until text.indexOf("second"))
        assertEquals(2, text.trim().lines().size)
    }

    @Test
    fun readingWithoutAWriteReturnsAnEmptyString() {
        val file = LogFile(File(createTempDirectory("log-").toFile(), "missing"))

        assertEquals("", file.read())
        assertEquals(0L, file.sizeBytes())
    }

    @Test
    fun aFilePastTheLimitKeepsItsNewestHalf() {
        val dir = createTempDirectory("log-").toFile()
        val file = LogFile(dir, maxBytes = 200L)

        repeat(40) { file.append(entry("line $it " + "x".repeat(20))) }

        val text = file.read()
        assertTrue("the file is trimmed", file.sizeBytes() <= 400L)
        assertTrue("the newest line survives", text.contains("line 39"))
        assertTrue("the oldest line is gone", !text.contains("line 0 "))
    }

    @Test
    fun deleteRemovesTheFileAndReportsItsSize() {
        val file = LogFile(createTempDirectory("log-").toFile())
        file.append(entry("something"))

        val freed = file.delete()

        assertTrue(freed > 0L)
        assertTrue(!file.file.exists())
    }
}
