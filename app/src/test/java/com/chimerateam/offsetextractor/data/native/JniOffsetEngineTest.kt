package com.chimerateam.offsetextractor.data.native

import com.chimerateam.offsetextractor.data.saf.EngineErrorKind
import com.chimerateam.offsetextractor.data.saf.EngineException
import com.chimerateam.offsetextractor.data.saf.ExtractedNativeLibrary
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
        private val failure: Throwable? = null,
    ) : NativeLibrary {

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
