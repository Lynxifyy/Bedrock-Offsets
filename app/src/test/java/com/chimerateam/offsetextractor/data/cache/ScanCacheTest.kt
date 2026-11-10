package com.chimerateam.offsetextractor.data.cache

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Verifies the on-disk cache never throws and always cleans up after itself. */
class ScanCacheTest {

    @get:Rule
    val temporary = TemporaryFolder()

    private fun cache(): ScanCache = ScanCache(temporary.newFolder("cache"))

    @Test
    fun aStoredDocumentCanBeReadBack() {
        val cache = cache()

        assertTrue(cache.writeDocument("deadbeef", "{\"a\":1}"))

        assertEquals("{\"a\":1}", cache.readDocument("deadbeef"))
        assertTrue(cache.hasDocument("deadbeef"))
    }

    @Test
    fun readingAMissingDocumentYieldsNull() {
        val cache = cache()

        assertNull(cache.readDocument("absent"))
        assertFalse(cache.hasDocument("absent"))
    }

    @Test
    fun aBlankBuildIdIsNeverStored() {
        val cache = cache()

        assertFalse(cache.writeDocument("", "{}"))
        assertFalse(cache.hasDocument(""))
        assertNull(cache.readDocument(""))
    }

    @Test
    fun overwritingADocumentReplacesItRatherThanAppending() {
        val cache = cache()
        cache.writeDocument("id", "first")

        cache.writeDocument("id", "second")

        assertEquals("second", cache.readDocument("id"))
    }

    @Test
    fun noTemporaryFileIsLeftBehindAfterAWrite() {
        val cache = cache()
        cache.writeDocument("id", "payload")

        val leftovers = cache.documentDir.listFiles().orEmpty().filter { it.name.endsWith(".tmp") }

        assertTrue("temporary files must be renamed away: $leftovers", leftovers.isEmpty())
    }

    @Test
    fun theTotalSizeCountsLibrariesAndDocuments() {
        val cache = cache()
        cache.ensureDirectories()
        File(cache.libraryDir, "libminecraftpe.so").writeBytes(ByteArray(2048))
        cache.writeDocument("id", "0123456789")

        assertEquals(2048L + 10L, cache.totalSizeBytes())
    }

    @Test
    fun clearingRemovesEverythingAndReportsAnEmptyCache() {
        val cache = cache()
        cache.ensureDirectories()
        File(cache.libraryDir, "libminecraftpe.so").writeBytes(ByteArray(1024))
        cache.writeDocument("id", "payload")

        assertTrue(cache.clear())

        assertEquals(0L, cache.totalSizeBytes())
        assertNull(cache.readDocument("id"))
    }

    @Test
    fun shreddingOverwritesTheFileWithZerosBeforeItIsDeleted() {
        val cache = cache()
        val secret = File(temporary.root, "libminecraftpe.so")
        secret.writeBytes(ByteArray(200_000) { 0x5A })

        cache.shred(secret)

        assertTrue("the file must still exist for the caller to delete", secret.isFile)
        assertEquals(
            "every byte must be zeroed, including across block boundaries",
            0,
            secret.readBytes().count { it != 0.toByte() },
        )
    }

    @Test
    fun shreddingAnEmptyFileIsHarmless() {
        val cache = cache()
        val empty = File(temporary.root, "empty.so")
        empty.writeBytes(ByteArray(0))

        cache.shred(empty)

        assertEquals(0L, empty.length())
    }

    @Test
    fun clearingShredsTheCachedLibraries() {
        val cache = cache()
        cache.ensureDirectories()
        val library = File(cache.libraryDir, "libminecraftpe.so")
        library.writeBytes(ByteArray(70_000) { 0x11 })

        assertTrue(cache.clear())

        assertFalse("the library must be gone", library.exists())
        assertEquals(0L, cache.totalSizeBytes())
    }

    @Test
    fun aMissingCacheDirectoryIsNotAnError() {
        val cache = ScanCache(File(temporary.root, "does-not-exist"))

        assertEquals(0L, cache.totalSizeBytes())
        assertFalse(cache.hasDocument("id"))
    }

    @Test
    fun theDocumentFileNameIsSanitised() {
        assertEquals("offsets_deadbeef.json", ScanCache.documentFileName("deadbeef"))
        assertEquals(
            "offsets_deadbeef.json",
            ScanCache.documentFileName("dead/../beef"),
        )
        assertEquals("offsets_unknown.json", ScanCache.documentFileName("///"))
    }
}
