package com.chimerateam.offsetextractor.data.native

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies that an [EngineFailure] raised by the native engine carries the right
 * constructor for the JNI boundary.
 *
 * `JNIEnv::throw_new` uses JNI `ThrowNew`, which always looks for
 * `\u003cinit\u003e(Ljava/lang/String;)V`. A class without that constructor makes the
 * JVM raise a `NoSuchMethodError` instead, so the intended failure is lost and
 * every typed error becomes an "unexpected failure". These tests pin the
 * constructor the native side needs.
 */
class EngineFailureTest {

    @Test
    fun aSingleMessageConstructorSplitsKindAndDetail() {
        val failure = EngineFailure("missing_so: libminecraftpe.so not found")

        assertEquals("missing_so", failure.kind)
        assertEquals("libminecraftpe.so not found", failure.detail)
        assertEquals("missing_so: libminecraftpe.so not found", failure.message)
    }

    @Test
    fun aMessageWithoutASeparatorBecomesUnknown() {
        val failure = EngineFailure("no separator here")

        assertEquals("unknown", failure.kind)
        assertEquals("no separator here", failure.detail)
    }

    @Test
    fun theConstructorThrowNewNeedsIsReachable() {
        // The exact signature JNI `ThrowNew` resolves. If this is ever removed or
        // renamed, every native error would surface as a NoSuchMethodError.
        val constructor = EngineFailure::class.java.getConstructor(String::class.java)

        assertTrue(constructor.parameterTypes.size == 1)
        assertEquals("java.lang.String", constructor.parameterTypes[0].name)
    }
}
