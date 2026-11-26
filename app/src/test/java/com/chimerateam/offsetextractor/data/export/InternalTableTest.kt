package com.chimerateam.offsetextractor.data.export

import com.chimerateam.offsetextractor.data.native.InternalSummary
import com.chimerateam.offsetextractor.ui.state.ScanResultSummary
import java.io.File
import kotlin.io.path.createTempDirectory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [InternalTable], the guard that keeps an archive from being written
 * without the internal offsets it promises.
 */
class InternalTableTest {

    private fun summary(
        path: String,
        classes: Int = 2,
        functions: Int = 3,
        dataSymbols: Int = 1,
        tableBytes: Long = 0,
        resolved: Boolean = true,
        engineVersion: String = "",
        imageBase: Long = 0,
        scanEngine: String = "",
        scanBase: Long = 0
    ) = ScanResultSummary(
        displayName = "minecraft.apk",
        sha256 = "abc",
        sizeBytes = 1,
        architecture = "AArch64",
        entryPoint = "0x4000",
        sectionCount = 3,
        internal = InternalSummary(
            classes = classes,
            functions = functions,
            dataSymbols = dataSymbols,
            path = path,
            tableBytes = tableBytes,
            engineVersion = engineVersion,
            imageBase = imageBase
        ),
        internalResolved = resolved,
        engineVersion = scanEngine,
        imageBase = scanBase
    )

    private fun table(content: String = "{}\n"): File =
        File(createTempDirectory().toFile(), "table.ndjson").apply { writeText(content) }

    @Test
    fun aPresentTableIsReturnedWithItsRowCount() {
        val file = table()

        val validated = InternalTable.require(summary(file.absolutePath))

        assertEquals(file, validated?.file)
        assertEquals(6, validated?.rows ?: -1)
        assertNull(InternalTable.problem(summary(file.absolutePath)))
    }

    @Test
    fun aMissingTableIsReportedWithItsPath() {
        val file = table()
        val path = file.absolutePath
        assertTrue(file.delete())

        val problem = InternalTable.problem(summary(path))

        assertTrue("the reason must name the file: $problem", problem?.contains(path) == true)
        assertTrue(
            "the reason must say how to recover: $problem",
            problem?.contains("Re-run") == true
        )
    }

    @Test
    fun anEmptyTableIsReported() {
        val file = table("")

        val problem = InternalTable.problem(summary(file.absolutePath))

        assertTrue("an empty table is useless: $problem", problem?.contains("empty") == true)
    }

    @Test
    fun aTableShorterThanReportedIsReportedAsTruncated() {
        val file = table()
        val summary = summary(file.absolutePath, tableBytes = file.length() + 1)

        val problem = InternalTable.problem(summary)

        assertTrue("a short table must not pass: $problem", problem?.contains("truncated") == true)
    }

    @Test
    fun aScanWithoutInternalRowsNeedsNoTable() {
        val summary = summary("", classes = 0, functions = 0, dataSymbols = 0, resolved = false)

        assertNull(InternalTable.require(summary))
        assertNull(InternalTable.problem(summary))
        assertTrue(!InternalTable.isRequired(summary))
    }

    @Test
    fun aPassThatFinishedWithNoRowsIsRefused() {
        // A result cached by an older version deserialises with every count at
        // zero; treating that as "nothing to export" shipped an archive with an
        // empty InternalCPP folder.
        val summary = summary("", classes = 0, functions = 0, dataSymbols = 0)

        assertTrue("such a result must not export", InternalTable.isPromised(summary))
        val problem = InternalTable.problem(summary)
        assertTrue(
            "the reason must be explicit: $problem",
            problem?.contains("without recording any rows") == true
        )
    }

    @Test
    fun aBlankPathIsReportedRatherThanCrashing() {
        val problem = InternalTable.problem(summary(""))

        assertTrue("a blank path must be named: $problem", problem?.contains("no path") == true)
    }

    @Test
    fun aTableWrittenByAnotherBaseIsRejected() {
        // The 26.60 bug: the table's `sub_<hex>` names and vtable offsets came
        // from a base 0x2463f0 too high. A table that records a different base
        // than the scan must not be exported as if it matched.
        val file = table()
        val summary = summary(
            file.absolutePath,
            engineVersion = "1.0.12",
            imageBase = 0x2463f0,
            scanEngine = "1.0.12",
            scanBase = 0
        )

        val problem = InternalTable.problem(summary)

        assertTrue("expected a stale-table problem, got $problem", problem != null)
        assertTrue(problem!!.contains("written by engine"))
    }

    @Test
    fun aTableWrittenByAnotherEngineIsRejected() {
        val file = table()
        val summary = summary(
            file.absolutePath,
            engineVersion = "1.0.11",
            imageBase = 0,
            scanEngine = "1.0.12",
            scanBase = 0
        )

        assertTrue(InternalTable.problem(summary) != null)
    }

    @Test
    fun aTableThatAgreesWithTheScanIsAccepted() {
        val file = table()
        val summary = summary(
            file.absolutePath,
            engineVersion = "1.0.12",
            imageBase = 0,
            scanEngine = "1.0.12",
            scanBase = 0
        )

        assertNull(InternalTable.problem(summary))
    }

    @Test
    fun aTableWithoutRecordedIdentityIsStillAccepted() {
        // A table from an engine predating the fields deserialises with them
        // empty; the row-count checks still apply, so it is not silently trusted
        // beyond what the old engine could have produced.
        val file = table()
        val summary = summary(file.absolutePath, scanEngine = "1.0.12", scanBase = 0x2463f0)

        assertNull(InternalTable.problem(summary))
    }
}
