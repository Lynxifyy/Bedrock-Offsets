package com.chimerateam.offsetextractor.ui.error

import com.chimerateam.offsetextractor.data.native.EnginePayloads
import com.chimerateam.offsetextractor.data.saf.EngineErrorKind
import com.chimerateam.offsetextractor.data.saf.EngineException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Verifies that engine failures are translated without leaking internals. */
class ErrorMapperTest {

    @Test
    fun aPayloadFailureIsDescribedWithoutTheObfuscatedClassName() {
        // Gson names the class it could not instantiate. After R8 that name is a
        // single letter, so it must never reach the user.
        val gson = EngineException(
            kind = EngineErrorKind.Payload,
            message = "Abstract classes can't be instantiated! " +
                "Class name: com.chimerateam.offsetextractor.data.native.e"
        )

        val error = ErrorMapper.fromEngine(gson, "\"scan.apk\"")

        assertEquals("The engine's response could not be read.", error.message)
        assertNotNull(error.hint)
        assertFalse(error.message.contains("data.native.e"))
        assertTrue(error.hint!!.contains("release-build"))
    }

    @Test
    fun everyEngineKindHasItsOwnMessage() {
        val messages = EngineErrorKind.entries.map { kind ->
            ErrorMapper.fromEngine(EngineException(kind, "detail"), "\"scan.apk\"").message
        }

        assertEquals(
            "two kinds share a message, so the UI cannot tell them apart",
            EngineErrorKind.entries.size,
            messages.toSet().size
        )
    }

    @Test
    fun anUnknownThrowableStillProducesAUsableMessage() {
        val error = ErrorMapper.fromThrowable(IllegalStateException("boom"), "scan.apk")

        assertTrue(error.message.contains("boom"))
        assertNotNull(error.hint)
    }

    @Test
    fun aNativeAllocationFailureIsReportedAsALowMemoryProblem() {
        // The engine reports `out_of_memory` instead of aborting the process, so
        // the mapping must exist or the user sees an unknown failure for what is
        // really a memory problem.
        val failure = EngineException(EngineErrorKind.OutOfMemory, "relocation index")

        val error = ErrorMapper.fromEngine(failure, "\"scan.apk\"")

        assertTrue(error.message.contains("memory", ignoreCase = true))
        assertNotNull(error.hint)
    }

    @Test
    fun theEngineKindStringMapsOntoTheLowMemoryClassification() {
        assertEquals(
            EngineErrorKind.OutOfMemory,
            EnginePayloads.kindOf("out_of_memory")
        )
    }
}
