package com.chimerateam.offsetextractor.data.export

import com.chimerateam.offsetextractor.data.native.PatternHit
import com.chimerateam.offsetextractor.ui.state.ScanResultSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Build-to-build comparison of two scans. */
class OffsetDiffTest {

    private fun summary(
        buildId: String,
        expected: List<String>,
        hits: Map<String, Pair<Long, String>>
    ): ScanResultSummary = ScanResultSummary(
        displayName = "$buildId.apk",
        sha256 = buildId,
        sizeBytes = 1,
        architecture = "AArch64",
        entryPoint = "0x0",
        sectionCount = 30,
        buildId = buildId,
        expectedPatterns = expected,
        patternHits = hits.map { (name, value) ->
            PatternHit(
                name = name,
                sectionOffset = value.first,
                fileOffset = value.first,
                address = value.first,
                addressHex = "0x%x".format(value.first),
                prologueKind = value.second
            )
        }
    )

    private val before = summary(
        buildId = "old",
        expected = listOf("Shifted", "Broken", "Removed", "Matched"),
        hits = mapOf(
            "Shifted" to (0x1000L to "frame_push"),
            "Broken" to (0x2000L to "frame_push"),
            "Removed" to (0x3000L to "frame_push"),
            "Matched" to (0x4000L to "frame_push")
        )
    )

    private val after = summary(
        buildId = "new",
        expected = listOf("Shifted", "Broken", "Matched", "New"),
        hits = mapOf(
            "Shifted" to (0x1010L to "frame_push"),
            "Broken" to (0x2000L to "leaf"),
            "Matched" to (0x4000L to "frame_push"),
            "New" to (0x5000L to "frame_push")
        )
    )

    @Test
    fun everyCategoryIsReported() {
        val entries = OffsetDiff.compare(before, after)

        assertEquals(1, OffsetDiff.count(entries, DiffKind.Shifted))
        assertEquals(1, OffsetDiff.count(entries, DiffKind.BrokenSignature))
        assertEquals(1, OffsetDiff.count(entries, DiffKind.Removed))
        assertEquals(1, OffsetDiff.count(entries, DiffKind.Matched))
        assertEquals(1, OffsetDiff.count(entries, DiffKind.New))
    }

    @Test
    fun aMovedAddressCarriesItsDelta() {
        val shifted = OffsetDiff.compare(before, after)
            .first { it.kind == DiffKind.Shifted }

        assertEquals("Shifted", shifted.name)
        assertEquals(0x10L, shifted.delta)
        assertEquals("0x1000", shifted.beforeAddress)
        assertEquals("0x1010", shifted.afterAddress)
    }

    @Test
    fun aChangedSignatureIsNotCountedAsMatched() {
        val broken = OffsetDiff.compare(before, after)
            .first { it.kind == DiffKind.BrokenSignature }

        assertEquals("Broken", broken.name)
        assertEquals("frame_push -> leaf", broken.detail)
    }

    @Test
    fun theSameAddressAndDetailIsMatched() {
        val matched = OffsetDiff.compare(before, after)
            .first { it.kind == DiffKind.Matched }

        assertEquals("Matched", matched.name)
        assertEquals(0L, matched.delta)
    }

    @Test
    fun aBuildThatOnlyGainedRowsHasNoRemovals() {
        val entries = OffsetDiff.compare(before, after)

        assertTrue(entries.none { it.kind == DiffKind.Removed && it.name == "New" })
        assertTrue(entries.any { it.kind == DiffKind.New && it.name == "New" })
    }
}
