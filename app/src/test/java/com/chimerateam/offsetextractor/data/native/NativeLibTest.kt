package com.chimerateam.offsetextractor.data.native

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies the fail-closed contract of [NativeLib] and the parsing of failures
 * raised across the JNI boundary.
 *
 * The shared object is intentionally absent from the JVM unit-test runtime, so
 * these tests assert that the app degrades safely instead of crashing.
 */
class NativeLibTest {

    @Test
    fun loadingFailureIsRecordedRatherThanThrown() {
        // Reaching this assertion at all proves class initialisation did not
        // throw, which is the fail-closed guarantee under test.
        if (!NativeLib.isAvailable) {
            assertNotNull(NativeLib.loadFailure)
        }
    }

    @Test
    fun engineCallsThrowEngineFailureWhenTheLibraryIsUnavailable() {
        if (NativeLib.isAvailable) return

        val error = runCatching { NativeLib.rustTest() }.exceptionOrNull()
        assertTrue("expected EngineFailure but was $error", error is EngineFailure)
    }

    @Test
    fun extractThrowsEngineFailureWhenTheLibraryIsUnavailable() {
        if (NativeLib.isAvailable) return

        val error = runCatching {
            NativeLib.extractNativeLibrary(fd = -1, destinationDir = "/tmp")
        }.exceptionOrNull()
        assertTrue("expected EngineFailure but was $error", error is EngineFailure)
    }

    @Test
    fun libraryNameMatchesTheCrateOutput() {
        assertEquals("offset_core", NativeLib.LIBRARY_NAME)
    }

    @Test
    fun failureMessagesAreParsedIntoKindAndDetail() {
        val parsed = EngineFailure.parse("missing_so: libminecraftpe.so not found")
        assertEquals("missing_so", parsed.kind)
        assertEquals("libminecraftpe.so not found", parsed.detail)
    }

    @Test
    fun unparseableFailureMessagesBecomeUnknown() {
        val parsed = EngineFailure.parse("no separator here")
        assertEquals("unknown", parsed.kind)
        assertFalse(parsed.detail.isEmpty())
    }

    @Test
    fun nullFailureMessageStillYieldsAUsableFailure() {
        val parsed = EngineFailure.parse(null)
        assertEquals("unknown", parsed.kind)
        assertEquals("native failure", parsed.detail)
    }
}
