package com.chimerateam.offsetextractor.data.native

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the JSON the Rust engine emits against the Kotlin payload models.
 *
 * The engine serialises with serde, so the wire document uses `snake_case`
 * names and omits absent optional fields. The literals below are the shape the
 * engine produces, not the output of the app's own serialiser: a round-trip
 * through one Gson instance would pass even when both directions share the same
 * wrong field name, which is exactly the bug that ships.
 */
class EnginePayloadsWireTest {

    @Test
    fun theExtractionPayloadIsReadAsTheEngineWritesIt() {
        val json = """{"path":"/data/cache/libminecraftpe.so",""" +
            """"sha256":"9f2c","size":1234}"""

        val library = EnginePayloads.parseExtraction(json)

        assertEquals("/data/cache/libminecraftpe.so", library.path)
        assertEquals("9f2c", library.sha256)
        assertEquals(1234L, library.sizeBytes)
    }

    @Test
    fun theElfMetadataPayloadIsReadAsTheEngineWritesIt() {
        val json = """
            {
              "path": "/data/cache/libminecraftpe.so",
              "sha256": "9f2c",
              "size": 812345678,
              "format": "ELF",
              "architecture": "AArch64",
              "bits": 64,
              "endianness": "little",
              "elf_type": "DYN",
              "entry_point": "0x1a2b3c",
              "section_count": 31,
              "program_header_count": 8,
              "soname": "libminecraftpe.so"
            }
        """.trimIndent()

        val metadata = EnginePayloads.parseLibraryMetadata(json)

        assertEquals("AArch64", metadata.architecture)
        assertEquals(64, metadata.bits)
        assertEquals("DYN", metadata.elfType)
        assertEquals("0x1a2b3c", metadata.entryPoint)
        assertEquals(31, metadata.sectionCount)
        assertEquals(8, metadata.programHeaderCount)
        assertEquals("libminecraftpe.so", metadata.soname)
        assertEquals(812345678L, metadata.sizeBytes)
    }

    @Test
    fun aMissingSonameBecomesNull() {
        val json = """
            {
              "path": "/p", "sha256": "h", "size": 1, "format": "ELF",
              "architecture": "AArch64", "bits": 64, "endianness": "little",
              "elf_type": "DYN", "entry_point": "0x0",
              "section_count": 1, "program_header_count": 0, "soname": null
            }
        """.trimIndent()

        assertNull(EnginePayloads.parseLibraryMetadata(json).soname)
    }

    @Test
    fun theSectionMapPayloadIsReadAsTheEngineWritesIt() {
        val json = """
            {
              "image_base": 4096,
              "text": {
                "name": ".text", "kind": "text", "address": 4096, "size": 2048,
                "offset": 4096, "align": 16, "delta": 0, "allocated": true,
                "executable": true, "writable": false, "occupies_file": true
              },
              "sections": [
                {
                  "name": ".text", "kind": "text", "address": 4096, "size": 2048,
                  "offset": 4096, "align": 16, "delta": 0, "allocated": true,
                  "executable": true, "writable": false, "occupies_file": true
                }
              ]
            }
        """.trimIndent()

        val map = EnginePayloads.parseSectionMap(json)

        assertEquals(4096L, map.imageBase)
        assertEquals(".text", map.text.name)
        assertTrue(map.text.executable)
        assertTrue(map.text.occupiesFile)
        assertEquals(1, map.sections.size)
    }

    @Test
    fun aNonLoadedSectionDeltaArrivesAsANegativeLong() {
        // `.shstrtab`-style sections have address 0 but a body inside the file,
        // so their load delta is negative. The engine serialises it as a signed
        // value; an unsigned one above `Long.MAX_VALUE` would make Gson reject
        // the whole section map with "malformed section map payload".
        val json = """
            {
              "image_base": 4096,
              "text": {
                "name": ".text", "kind": "text", "address": 4096, "size": 2048,
                "offset": 4096, "align": 16, "delta": 0, "allocated": true,
                "executable": true, "writable": false, "occupies_file": true
              },
              "sections": [
                {
                  "name": ".text", "kind": "text", "address": 4096, "size": 2048,
                  "offset": 4096, "align": 16, "delta": 0, "allocated": true,
                  "executable": true, "writable": false, "occupies_file": true
                },
                {
                  "name": ".shstrtab", "kind": "other", "address": 0, "size": 64,
                  "offset": 20971520, "align": 1, "delta": -20971520,
                  "allocated": false, "executable": false, "writable": false,
                  "occupies_file": true
                }
              ]
            }
        """.trimIndent()

        val map = EnginePayloads.parseSectionMap(json)

        val shstrtab = map.find(".shstrtab")
        assertNotNull(shstrtab)
        assertEquals(-20971520L, shstrtab!!.delta)
        assertNull(shstrtab.addressOf(64))
    }

    @Test
    fun theScanPayloadFlattensTheOutcomeAndKeepsItsSnakeCaseNames() {
        val json = """
            {
              "section": ".text",
              "scanned_bytes": 4096,
              "chunks": 1,
              "workers": 4,
              "raw_matches": 3,
              "discarded": 2,
              "verified": true,
              "truncated": true,
              "hits": [
                {
                  "name": "client_instance",
                  "section_offset": 16,
                  "file_offset": 4112,
                  "address": 4112,
                  "address_hex": "0x1010",
                  "prologue_kind": "stp_fp_lr",
                  "prologue_instruction": "stp x29, x30, [sp, #-16]!",
                  "verification_status": "verified"
                }
              ],
              "verification": {
                "verified": 1, "conflicts": 0, "unverified": 0,
                "symbols_available": true
              },
              "peak_resident_bytes": 104857600
            }
        """.trimIndent()

        val outcome = EnginePayloads.parseScanOutcome(json)

        assertEquals(".text", outcome.section)
        assertEquals(4096L, outcome.scannedBytes)
        assertEquals(4, outcome.workers)
        assertEquals(3, outcome.rawMatches)
        assertEquals(2, outcome.discarded)
        assertTrue(outcome.verified)
        assertTrue(outcome.truncated)
        assertEquals(104857600L, outcome.peakResidentBytes)

        val hit = outcome.hits.single()
        assertEquals("client_instance", hit.name)
        assertEquals(4112L, hit.address)
        assertEquals("0x1010", hit.addressHex)
        assertEquals("stp x29, x30, [sp, #-16]!", hit.prologueInstruction)
        assertEquals(VerificationStatus.Verified, hit.verificationStatus)
        assertTrue(hit.isVerified)

        assertNotNull(outcome.verification)
        assertEquals(1, outcome.verification!!.verified)
        assertTrue(outcome.verification!!.symbolsAvailable)
    }

    @Test
    fun aScanPayloadWithoutRawMatchesFallsBackToTheHitCount() {
        val json = """
            {
              "section": ".text",
              "scanned_bytes": 4096,
              "chunks": 1,
              "workers": 4,
              "hits": [
                {
                  "name": "client_instance",
                  "section_offset": 16,
                  "file_offset": 4112,
                  "address": 4112,
                  "address_hex": "0x1010"
                }
              ]
            }
        """.trimIndent()

        val outcome = EnginePayloads.parseScanOutcome(json)

        assertEquals(1, outcome.rawMatches)
    }

    @Test
    fun theVtablePayloadIsReadAsTheEngineWritesIt() {
        val json = """
            {
              "source": "relocations",
              "type_info_candidates": 12,
              "relocations_examined": 40,
              "entries": {
                "ClientInstance": {
                  "name": "ClientInstance",
                  "mangled": "14ClientInstance",
                  "vtable_address": 8192,
                  "vtable_hex": "0x2000",
                  "vtable_offset": 4096,
                  "type_info_address": 8160
                }
              }
            }
        """.trimIndent()

        val map = EnginePayloads.parseVtableMap(json)

        assertEquals(VtableMap.SOURCE_RELOCATIONS, map.source)
        assertEquals(12, map.typeInfoCandidates)
        assertEquals(40, map.relocationsExamined)
        val vtable = map.entries.getValue("ClientInstance")
        assertEquals(8192L, vtable.vtableAddress)
        assertEquals(4096L, vtable.vtableOffset)
        assertEquals("0x2000", vtable.vtableHex)
    }

    @Test
    fun theSymbolReportPayloadIsReadAsTheEngineWritesIt() {
        val json = """
            {
              "identity": {
                "build_id": "a1b2c3", "source": "note", "text_size": 4096
              },
              "symbols": {
                "total": 3,
                "defined": 2,
                "truncated": false,
                "jni": [
                  {
                    "name": "Java_com_x_Y", "address": 4096,
                    "address_hex": "0x1000", "size": 32, "binding": 1
                  }
                ]
              }
            }
        """.trimIndent()

        val report = EnginePayloads.parseSymbolReport(json)

        assertEquals("a1b2c3", report.identity.buildId)
        assertEquals("note", report.identity.source)
        assertEquals(4096L, report.identity.textSize)
        assertEquals(3, report.symbols.total)
        assertEquals(2, report.symbols.defined)
        val symbol = report.symbols.jni.single()
        assertEquals("Java_com_x_Y", symbol.name)
        assertEquals(4096L, symbol.address)
        assertEquals(1, symbol.binding)
        assertEquals(symbol, report.symbol("Java_com_x_Y"))
    }

    @Test
    fun aDerivedBuildIdIsFlagged() {
        val json = """
            {"identity":{"build_id":"deadbeef","source":"text_hash","text_size":99},
             "symbols":{"total":0,"defined":0,"truncated":false,"jni":[]}}
        """.trimIndent()

        assertTrue(EnginePayloads.parseSymbolReport(json).identity.isDerived)
    }

    @Test
    fun thePatternSpecsAreEncodedWithTheNamesTheEngineReads() {
        val encoded = EnginePayloads.encodePatternSpecs(
            listOf(PatternSpec(name = "client", pattern = "48 8B ?? ??"))
        )

        assertEquals("""[{"name":"client","pattern":"48 8B ?? ??"}]""", encoded)
    }
}
