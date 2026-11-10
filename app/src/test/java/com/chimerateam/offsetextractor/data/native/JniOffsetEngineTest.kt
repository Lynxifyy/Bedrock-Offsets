package com.chimerateam.offsetextractor.data.native

import com.chimerateam.offsetextractor.data.saf.EngineErrorKind
import com.chimerateam.offsetextractor.data.saf.EngineException
import com.chimerateam.offsetextractor.data.saf.ExtractedNativeLibrary
import com.chimerateam.offsetextractor.data.native.PatternSpec
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies that [JniOffsetEngine] turns native JSON into typed results and that
 * every failure mode surfaces as an [EngineException] rather than a crash.
 *
 * The bindings are replaced by a double, but the payload parsing and error
 * translation under test are the real implementations.
 */
class JniOffsetEngineTest {

    private val extracted = ExtractedNativeLibrary(
        path = "/cache/libminecraftpe.so",
        sha256 = "abc123",
        sizeBytes = 4096,
    )

    @Test
    fun extractionPayloadIsParsedIntoATypedResult() = runTest {
        val engine = JniOffsetEngine(
            FakeBindings(
                extraction = """{"path":"/cache/libminecraftpe.so","sha256":"abc123","size":4096}""",
            ),
        )

        val result = engine.extractNativeLibrary(rawFd = 42, destDir = File("/cache"))

        assertEquals("/cache/libminecraftpe.so", result.path)
        assertEquals("abc123", result.sha256)
        assertEquals(4096L, result.sizeBytes)
    }

    @Test
    fun mappingPayloadIsParsedIntoLibraryMetadata() = runTest {
        val engine = JniOffsetEngine(FakeBindings(mapping = METADATA_JSON))

        val metadata = engine.mapLibrary(extracted)

        assertEquals("AArch64", metadata.architecture)
        assertEquals(64, metadata.bits)
        assertEquals("DYN", metadata.elfType)
        assertEquals("0x4000", metadata.entryPoint)
        assertEquals(33, metadata.sectionCount)
        assertEquals("libminecraftpe.so", metadata.soname)
        assertEquals(4096L, metadata.sizeBytes)
    }

    @Test
    fun missingSonameIsTolerated() = runTest {
        // Real libraries are sometimes built without DT_SONAME, so a null
        // soname must parse rather than fail.
        val withoutSoname = METADATA_JSON.replace(
            "\"libminecraftpe.so\"",
            "null",
        )
        val engine = JniOffsetEngine(FakeBindings(mapping = withoutSoname))

        assertEquals(null, engine.mapLibrary(extracted).soname)
    }

    @Test
    fun nativeFailureBecomesATypedEngineException() = runTest {
        val engine = JniOffsetEngine(
            FakeBindings(failure = EngineFailure("missing_so", "libminecraftpe.so not found")),
        )

        val error = runCatching { engine.extractNativeLibrary(42, File("/cache")) }
            .exceptionOrNull()

        assertTrue("expected EngineException but was $error", error is EngineException)
        assertEquals(EngineErrorKind.MissingSoFile, (error as EngineException).kind)
    }

    @Test
    fun unsupportedArchitectureIsClassifiedForTheUi() = runTest {
        val engine = JniOffsetEngine(
            FakeBindings(failure = EngineFailure("unsupported_arch", "found 32-bit ARM")),
        )

        val error = runCatching { engine.mapLibrary(extracted) }.exceptionOrNull()

        assertEquals(
            EngineErrorKind.UnsupportedArchitecture,
            (error as EngineException).kind,
        )
    }

    @Test
    fun missingNativeLibraryIsReportedNotCrashing() = runTest {
        val engine = JniOffsetEngine(
            FakeBindings(failure = UnsatisfiedLinkError("liboffset_core.so not found")),
        )

        val error = runCatching { engine.extractNativeLibrary(42, File("/cache")) }
            .exceptionOrNull()

        assertTrue("expected EngineException but was $error", error is EngineException)
        assertEquals(EngineErrorKind.InvalidDescriptor, (error as EngineException).kind)
    }

    @Test
    fun sectionPayloadIsParsedIntoALayout() = runTest {
        val engine = JniOffsetEngine(FakeBindings(sections = SECTION_MAP_JSON))

        val map = engine.describeSections(extracted)

        assertEquals(0x400000L, map.imageBase)
        assertEquals(".text", map.text.name)
        assertEquals(0x400000L, map.text.address)
        assertEquals(0x7a0000L, map.text.size)
        assertEquals(0x3fd000L, map.text.delta)
        assertEquals(2, map.sections.size)
        assertEquals(".rodata", map.find(".rodata")?.name)
    }

    @Test
    fun sectionMapWithoutTextIsRejected() = runTest {
        val withoutText = SECTION_MAP_JSON
            .substringBefore("\"text\":")
            .plus("\"text\":null,\"sections\":[]}")
        val engine = JniOffsetEngine(FakeBindings(sections = withoutText))

        val error = runCatching { engine.describeSections(extracted) }.exceptionOrNull()

        assertTrue("expected EngineException but was $error", error is EngineException)
        assertEquals(EngineErrorKind.MissingSection, (error as EngineException).kind)
    }

    @Test
    fun sectionAddressConversionUsesTheLoadDelta() = runTest {
        val engine = JniOffsetEngine(FakeBindings(sections = SECTION_MAP_JSON))

        val map = engine.describeSections(extracted)

        assertEquals(0x400000L, map.text.addressOf(0x3000))
        assertEquals(0x3000L, map.text.offsetOf(0x400000))
        assertEquals(null, map.text.offsetOf(0x100))
    }

    @Test
    fun symbolPayloadIsParsedIntoAReport() = runTest {
        val engine = JniOffsetEngine(FakeBindings(symbols = SYMBOL_REPORT_JSON))

        val report = engine.describeSymbols(extracted)

        assertEquals("410ac215603eebd7", report.identity.buildId)
        assertEquals("note", report.identity.source)
        assertEquals(false, report.identity.isDerived)
        assertEquals(42, report.symbols.total)
        assertEquals(1, report.symbols.jni.size)
        assertEquals(0x410000L, report.symbol(NAME)?.address)
        assertEquals("0x410000", report.symbol(NAME)?.addressHex)
    }

    @Test
    fun derivedBuildIdIsFlagged() = runTest {
        val derived = SYMBOL_REPORT_JSON.replace("\"source\": \"note\"", "\"source\": \"text_hash\"")
        val engine = JniOffsetEngine(FakeBindings(symbols = derived))

        val report = engine.describeSymbols(extracted)

        assertEquals(true, report.identity.isDerived)
    }

    @Test
    fun symbolReportWithoutIdentityIsRejected() = runTest {
        val engine = JniOffsetEngine(FakeBindings(symbols = "{\"identity\":null,\"symbols\":null}"))

        val error = runCatching { engine.describeSymbols(extracted) }.exceptionOrNull()

        assertTrue("expected EngineException but was $error", error is EngineException)
        assertEquals(EngineErrorKind.InvalidElf, (error as EngineException).kind)
    }

    @Test
    fun scanPayloadIsParsedIntoHits() = runTest {
        val engine = JniOffsetEngine(FakeBindings(scan = SCAN_JSON))

        val outcome = engine.scanText(extracted, listOf(PatternSpec("prologue", "48 8B 05")))

        assertEquals(".text", outcome.section)
        assertEquals(0x7a0000L, outcome.scannedBytes)
        assertEquals(19, outcome.chunks)
        assertEquals(8, outcome.workers)
        assertEquals(1, outcome.hits.size)
        assertEquals(0x400000L, outcome.hits[0].address)
        assertEquals("0x400000", outcome.hits[0].addressHex)
        assertEquals(1, outcome.countOf("prologue"))
        assertEquals(0, outcome.countOf("absent"))
        assertEquals(64L * 1024 * 1024, outcome.peakResidentBytes)
    }

    @Test
    fun scanPayloadWithoutASectionIsRejected() = runTest {
        val engine = JniOffsetEngine(FakeBindings(scan = "{\"section\":null,\"hits\":null}"))

        val error = runCatching {
            engine.scanText(extracted, listOf(PatternSpec("x", "41")))
        }.exceptionOrNull()

        assertTrue("expected EngineException but was $error", error is EngineException)
        assertEquals(EngineErrorKind.InvalidElf, (error as EngineException).kind)
    }

    @Test
    fun patternsAreSerialisedForTheEngine() = runTest {
        val bindings = FakeBindings(scan = SCAN_JSON)
        val engine = JniOffsetEngine(bindings)

        engine.scanText(
            extracted,
            listOf(PatternSpec("one", "48 8B ??"), PatternSpec("two", "DE AD")),
        )

        val sent = bindings.lastPatterns
        assertTrue("missing first pattern in $sent", sent.contains("\"name\":\"one\""))
        assertTrue("missing wildcard in $sent", sent.contains("48 8B ??"))
        assertTrue("missing second pattern in $sent", sent.contains("\"name\":\"two\""))
    }

    @Test
    fun malformedPayloadIsRejected() = runTest {
        val engine = JniOffsetEngine(FakeBindings(extraction = "{not json"))

        val error = runCatching { engine.extractNativeLibrary(42, File("/cache")) }
            .exceptionOrNull()

        assertTrue("expected EngineException but was $error", error is EngineException)
        assertTrue(error!!.message!!.contains("malformed"))
    }

    @Test
    fun incompleteExtractionPayloadIsRejected() = runTest {
        val engine = JniOffsetEngine(FakeBindings(extraction = """{"path":"/cache/x.so"}"""))

        val error = runCatching { engine.extractNativeLibrary(42, File("/cache")) }
            .exceptionOrNull()

        assertTrue(error is EngineException)
        assertTrue(error!!.message!!.contains("incomplete"))
    }

    @Test
    fun emptyPayloadIsRejected() = runTest {
        val engine = JniOffsetEngine(FakeBindings(mapping = ""))

        val error = runCatching { engine.mapLibrary(extracted) }.exceptionOrNull()

        assertTrue(error is EngineException)
        assertTrue(error!!.message!!.contains("no ELF metadata payload"))
    }

    private class FakeBindings(
        private val extraction: String = "",
        private val mapping: String = "",
        private val sections: String = "",
        private val symbols: String = "",
        private val scan: String = "",
        var lastPatterns: String = "",

        private val failure: Throwable? = null,
    ) : NativeLibrary {

        override fun describeSections(path: String, sha256: String): String {
            failure?.let { throw it }
            return sections
        }

        override fun describeSymbols(path: String, sha256: String): String {
            failure?.let { throw it }
            return symbols
        }

        override fun scanText(path: String, sha256: String, patternsJson: String): String {
            failure?.let { throw it }
            lastPatterns = patternsJson
            return scan
        }

        override fun extractNativeLibrary(fd: Int, destinationDir: String): String {
            failure?.let { throw it }
            return extraction
        }

        override fun mapNativeLibrary(path: String, sha256: String): String {
            failure?.let { throw it }
            return mapping
        }
    }

    private companion object {
        const val NAME = "Java_com_chimerateam_offsetextractor_data_native_NativeLib_rustTestNative"

        const val SYMBOL_REPORT_JSON = """
            {
              "identity": {
                "build_id": "410ac215603eebd7",
                "source": "note",
                "text_size": 0
              },
              "symbols": {
                "total": 42,
                "defined": 7,
                "jni": [
                  {
                    "name": "Java_com_chimerateam_offsetextractor_data_native_NativeLib_rustTestNative",
                    "address": 4259840,
                    "address_hex": "0x410000",
                    "size": 32,
                    "binding": 1
                  }
                ],
                "truncated": false
              }
            }
        """

        const val SCAN_JSON = """
            {
              "section": ".text",
              "scanned_bytes": 7995392,
              "chunks": 19,
              "workers": 8,
              "peak_resident_bytes": 67108864,
              "hits": [
                {
                  "name": "prologue",
                  "section_offset": 0,
                  "file_offset": 12288,
                  "address": 4194304,
                  "address_hex": "0x400000"
                }
              ]
            }
        """

        const val SECTION_MAP_JSON = """
            {
              "image_base": 4194304,
              "text": {
                "name": ".text",
                "kind": "text",
                "address": 4194304,
                "size": 7995392,
                "offset": 12288,
                "align": 16,
                "delta": 4182016,
                "allocated": true,
                "executable": true,
                "writable": false,
                "occupies_file": true
              },
              "sections": [
                {
                  "name": ".text",
                  "kind": "text",
                  "address": 4194304,
                  "size": 7995392,
                  "offset": 12288,
                  "align": 16,
                  "delta": 4182016,
                  "allocated": true,
                  "executable": true,
                  "writable": false,
                  "occupies_file": true
                },
                {
                  "name": ".rodata",
                  "kind": "rodata",
                  "address": 12189696,
                  "size": 8192,
                  "offset": 8007680,
                  "align": 16,
                  "delta": 4182016,
                  "allocated": true,
                  "executable": false,
                  "writable": false,
                  "occupies_file": true
                }
              ]
            }
        """

        const val METADATA_JSON = """
            {
              "path": "/cache/libminecraftpe.so",
              "sha256": "abc123",
              "size": 4096,
              "format": "ELF",
              "architecture": "AArch64",
              "bits": 64,
              "endianness": "little",
              "elf_type": "DYN",
              "entry_point": "0x4000",
              "section_count": 33,
              "program_header_count": 9,
              "soname": "libminecraftpe.so"
            }
        """
    }
}
