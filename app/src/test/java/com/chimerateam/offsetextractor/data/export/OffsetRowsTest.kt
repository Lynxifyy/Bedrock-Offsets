package com.chimerateam.offsetextractor.data.export

import com.chimerateam.offsetextractor.data.native.ExportedSymbol
import com.chimerateam.offsetextractor.data.native.PatternHit
import com.chimerateam.offsetextractor.data.native.SectionInfo
import com.chimerateam.offsetextractor.data.native.TypeVTable
import com.chimerateam.offsetextractor.data.native.VerificationStatus
import com.chimerateam.offsetextractor.data.native.VtableMap
import com.chimerateam.offsetextractor.ui.state.ScanResultSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Verifies the row model behind the results list. */
class OffsetRowsTest {

    private val text = SectionInfo(
        name = ".text",
        kind = "text",
        address = 0x400000,
        size = 0x7a0000,
        offset = 0x3000,
        align = 16,
        delta = 0x3fd000,
        allocated = true,
        executable = true,
        writable = false,
        occupiesFile = true
    )

    private fun summary(
        hits: List<PatternHit> = emptyList(),
        expected: List<String> = emptyList(),
        vtables: VtableMap = VtableMap.EMPTY,
        symbols: List<ExportedSymbol> = emptyList()
    ) = ScanResultSummary(
        displayName = "minecraft.apk",
        sha256 = "abc",
        sizeBytes = 1024,
        architecture = "AArch64",
        entryPoint = "0x4000",
        sectionCount = 33,
        textSection = text,
        buildId = "deadbeef",
        buildIdSource = "note",
        patternHits = hits,
        vtables = vtables,
        symbols = symbols,
        expectedPatterns = expected
    )

    private fun hit(
        name: String,
        address: Long,
        kind: String? = "frame_push",
        status: VerificationStatus = VerificationStatus.Unverified
    ) = PatternHit(
        name = name,
        sectionOffset = address - text.address,
        fileOffset = address - text.delta,
        address = address,
        addressHex = "0x%x".format(address),
        prologueKind = kind,
        prologueInstruction = "stp x29, x30, [sp, #-16]!",
        verificationStatus = status
    )

    @Test
    fun rowsAreGroupedByCategoryInOrder() {
        val rows = OffsetRows.from(
            summary(
                hits = listOf(hit("clientInstance", 0x401000)),
                expected = listOf("clientInstance"),
                vtables = VtableMap(
                    source = VtableMap.SOURCE_RELOCATIONS,
                    typeInfoCandidates = 1,
                    relocationsExamined = 2,
                    entries = mapOf(
                        "ClientInstance" to TypeVTable(
                            name = "ClientInstance",
                            mangled = "14ClientInstance",
                            vtableAddress = 0x403000,
                            vtableHex = "0x403000",
                            vtableOffset = 0x3000,
                            typeInfoAddress = 0x403080
                        )
                    )
                ),
                symbols = listOf(
                    ExportedSymbol(
                        name = "Java_com_example_start",
                        address = 0x410000,
                        addressHex = "0x410000",
                        size = 32,
                        binding = 1
                    )
                )
            )
        )

        assertEquals(
            listOf(
                OffsetCategory.VTable,
                OffsetCategory.Pattern,
                OffsetCategory.Symbol
            ),
            rows.map { it.category }
        )
    }

    @Test
    fun aSignatureThatDidNotMatchIsReportedAsMissing() {
        val rows = OffsetRows.from(
            summary(hits = emptyList(), expected = listOf("absentSignature"))
        )

        assertEquals(1, rows.size)
        val row = rows.single()
        assertEquals("absentSignature", row.name)
        assertEquals(OffsetRows.MISSING_ADDRESS, row.addressHex)
        assertTrue("a missing signature must be flagged", row.missing)
    }

    @Test
    fun aSignatureThatMatchedIsNotFlaggedAsMissing() {
        val rows = OffsetRows.from(
            summary(hits = listOf(hit("present", 0x401000)), expected = listOf("present"))
        )

        val row = rows.single()
        assertFalse(row.missing)
        assertEquals("0x401000", row.addressHex)
        assertEquals("frame_push", row.detail)
    }

    @Test
    fun duplicateMatchesReportTheLowestAddress() {
        val rows = OffsetRows.from(
            summary(
                hits = listOf(hit("dup", 0x402000), hit("dup", 0x401000)),
                expected = listOf("dup")
            )
        )

        assertEquals(1, rows.size)
        assertEquals("0x401000", rows.single().addressHex)
    }

    @Test
    fun aHitWithNoConfiguredPatternStillAppears() {
        val rows = OffsetRows.from(
            summary(hits = listOf(hit("legacySignature", 0x401000)), expected = emptyList())
        )

        assertEquals(1, rows.size)
        assertEquals("legacySignature", rows.single().name)
    }

    @Test
    fun searchMatchesNameAddressAndDetailCaseInsensitively() {
        val row = OffsetRow(
            category = OffsetCategory.Pattern,
            name = "ClientInstance",
            addressHex = "0x401000",
            detail = "frame_push"
        )

        assertTrue(row.matches("client"))
        assertTrue(row.matches("0X401000"))
        assertTrue(row.matches("FRAME"))
        assertTrue(row.matches("  "))
        assertFalse(row.matches("nope"))
    }

    @Test
    fun anEmptyQueryKeepsEveryRow() {
        val rows = OffsetRows.from(
            summary(hits = listOf(hit("a", 0x401000)), expected = listOf("a", "b"))
        )

        assertEquals(2, rows.filter { it.matches("") }.size)
    }

    @Test
    fun aVerifiedHitCarriesItsStatusOntoTheRow() {
        val rows = OffsetRows.from(
            summary(
                hits = listOf(
                    hit("clientInstance", 0x401000, status = VerificationStatus.Verified)
                ),
                expected = listOf("clientInstance")
            )
        )

        val row = rows.single { it.name == "clientInstance" }
        assertEquals(VerificationStatus.Verified, row.status)
        assertTrue(row.status.isTrusted)
    }

    @Test
    fun aConflictingHitIsMarkedAsAConflict() {
        val rows = OffsetRows.from(
            summary(
                hits = listOf(
                    hit("clientInstance", 0x401000, status = VerificationStatus.Conflict)
                ),
                expected = listOf("clientInstance")
            )
        )

        val row = rows.single { it.name == "clientInstance" }
        assertEquals(VerificationStatus.Conflict, row.status)
        assertTrue(row.status.isConflict)
    }

    @Test
    fun aMissingSignatureIsNeverTrusted() {
        val rows = OffsetRows.from(summary(expected = listOf("clientInstance")))

        val row = rows.single { it.name == "clientInstance" }
        assertTrue(row.missing)
        assertEquals(VerificationStatus.Unverified, row.status)
        assertFalse(row.status.isTrusted)
    }
}
