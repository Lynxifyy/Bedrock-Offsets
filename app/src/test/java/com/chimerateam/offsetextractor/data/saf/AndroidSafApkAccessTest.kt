package com.chimerateam.offsetextractor.data.saf

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import java.io.ByteArrayInputStream
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/**
 * Exercises the two SAF paths: the direct descriptor and the cache fallback.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidSafApkAccessTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun opensFileUriWithDirectDescriptorAndNoCacheCopy() {
        val apk = File(context.cacheDir, "sample.apk")
        apk.writeBytes(ByteArray(2048) { 1 })

        val access = AndroidSafApkAccess(context)
        val result = access.openApk(Uri.fromFile(apk))

        assertTrue("expected success but was $result", result is ApkOpenResult.Success)
        val success = result as ApkOpenResult.Success
        assertFalse(success.usedCacheCopy)
        assertTrue("descriptor must be valid", success.handle.rawFd > 0)
        assertEquals(2048L, success.handle.lengthBytes)

        success.handle.close()
    }

    @Test
    fun fallsBackToStreamingCacheCopyWhenNoDescriptorIsAvailable() {
        val uri = Uri.parse("content://test.provider/apk")
        val payload = ByteArray(4096) { 7 }
        Shadows.shadowOf(context.contentResolver)
            .registerInputStream(uri, ByteArrayInputStream(payload))

        val access = AndroidSafApkAccess(context)
        val result = access.openApk(uri)

        assertTrue("expected success but was $result", result is ApkOpenResult.Success)
        val success = result as ApkOpenResult.Success
        assertTrue(success.usedCacheCopy)
        assertEquals(payload.size.toLong(), success.handle.lengthBytes)
        assertTrue(success.handle.rawFd > 0)

        success.handle.close()
    }

    @Test
    fun reportsFailureForMissingDocument() {
        val missing = Uri.fromFile(File(context.cacheDir, "does-not-exist.apk"))

        val access = AndroidSafApkAccess(context)
        val result = access.openApk(missing)

        assertTrue("expected failure but was $result", result is ApkOpenResult.Failure)
        assertEquals(SafFailure.IoError, (result as ApkOpenResult.Failure).reason)
    }

    @Test
    fun metadataFallsBackToUriNameForFileDocuments() {
        val apk = File(context.cacheDir, "named.apk")
        apk.writeBytes(ByteArray(16))

        val access = AndroidSafApkAccess(context)
        val metadata = access.queryMetadata(Uri.fromFile(apk))

        assertEquals("named.apk", metadata.displayName)
    }
}
