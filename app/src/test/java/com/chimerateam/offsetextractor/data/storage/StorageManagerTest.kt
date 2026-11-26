package com.chimerateam.offsetextractor.data.storage

import java.io.File
import java.io.IOException
import kotlin.io.path.createTempDirectory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Verifies the storage lifecycle: what is deleted, when, and what survives. */
class StorageManagerTest {

    private val root = createTempDirectory("storage-").toFile()
    private val cacheDir = File(root, "cache").apply { mkdirs() }
    private val libraryDir = File(root, "libs").apply { mkdirs() }
    private val internalDir = File(root, "internal").apply { mkdirs() }
    private val exportDir = File(root, "exports").apply { mkdirs() }
    private val documentDir = File(root, "documents").apply { mkdirs() }
    private val logDir = File(root, "logs").apply { mkdirs() }

    private val manager = StorageManager(
        cacheDir = cacheDir,
        libraryDir = libraryDir,
        internalDir = internalDir,
        exportDir = exportDir,
        documentDir = documentDir,
        logDir = logDir
    )

    private fun file(dir: File, name: String, bytes: Int = 16): File = File(dir, name).apply {
        parentFile?.mkdirs()
        writeBytes(ByteArray(bytes))
    }

    @Test
    fun `usage groups files by area and totals them`() {
        file(manager.apkDir, "a.apk", 100)
        file(libraryDir, "lib.so", 200)
        file(exportDir, "z.zip", 300)

        val usage = manager.usage()

        assertEquals(100L, usage.bytesOf(StorageAreaId.Apks))
        assertEquals(200L, usage.bytesOf(StorageAreaId.Libraries))
        assertEquals(300L, usage.bytesOf(StorageAreaId.Exports))
        assertEquals(600L, usage.totalBytes)
        assertEquals(3, usage.areas.size)
    }

    @Test
    fun `sweep removes partial files, libraries and APKs`() {
        file(manager.apkDir, "down.apk.part", 40)
        file(manager.apkDir, "kept.apk", 50)
        file(libraryDir, "lib.so", 60)
        file(exportDir, "old.zip", 70)

        val freed = manager.sweep(keepApks = false)

        assertTrue(freed >= 220L)
        assertFalse(File(manager.apkDir, "down.apk.part").exists())
        assertFalse(File(manager.apkDir, "kept.apk").exists())
        assertFalse(File(libraryDir, "lib.so").exists())
        assertFalse(File(exportDir, "old.zip").exists())
    }

    @Test
    fun `sweep keeps downloaded APKs when the setting is off`() {
        val apk = file(manager.apkDir, "kept.apk", 50)

        manager.sweep(keepApks = true)

        assertTrue(apk.isFile)
    }

    @Test
    fun `sweep keeps the table the current result points at and drops the rest`() {
        val kept = file(internalDir, "abc.ndjson", 10)
        val stale = file(internalDir, "old.ndjson", 10)
        val shards = File(internalDir, "categories-1").apply {
            mkdirs()
            file(this, "block.classes.ndjson", 10)
        }

        manager.sweep(keepTablePath = kept.absolutePath)

        assertTrue(kept.isFile)
        assertFalse(stale.exists())
        assertFalse(shards.exists())
    }

    @Test
    fun `sweep keeps the newest table so a cached result can still export`() {
        val older = file(internalDir, "older.ndjson", 10)
        val newer = file(internalDir, "newer.ndjson", 10)
        older.setLastModified(1_000L)
        newer.setLastModified(2_000L)

        assertEquals(newer.absolutePath, manager.newestTable()?.absolutePath)

        manager.sweep(keepTablePath = manager.newestTable()?.absolutePath)

        assertFalse(older.exists())
        assertTrue(newer.isFile)
    }

    @Test
    fun `freeUpSpace keeps the current table and clears everything else`() {
        val table = file(internalDir, "abc.ndjson", 10)
        file(manager.apkDir, "a.apk", 10)
        file(libraryDir, "lib.so", 10)
        file(exportDir, "z.zip", 10)
        file(documentDir, "doc.json", 10)

        manager.freeUpSpace(table.absolutePath)

        assertTrue(table.isFile)
        assertFalse(File(manager.apkDir, "a.apk").exists())
        assertFalse(File(libraryDir, "lib.so").exists())
        assertFalse(File(exportDir, "z.zip").exists())
        assertFalse(File(documentDir, "doc.json").exists())
    }

    @Test
    fun `enforceCap deletes the oldest files first`() {
        val oldest = file(exportDir, "old.zip", 600).apply { setLastModified(1_000L) }
        val newer = file(exportDir, "new.zip", 600).apply { setLastModified(2_000L) }

        manager.enforceCap(700L)

        assertFalse(oldest.exists())
        assertTrue(newer.isFile)
    }

    @Test
    fun `enforceCap never deletes the table the result needs`() {
        val table = file(internalDir, "abc.ndjson", 900).apply { setLastModified(1_000L) }
        file(exportDir, "z.zip", 900).apply { setLastModified(2_000L) }

        manager.enforceCap(100L, keepTablePath = table.absolutePath)

        assertTrue(table.isFile)
        assertFalse(File(exportDir, "z.zip").exists())
    }

    @Test
    fun `deleteArea clears one group only`() {
        file(manager.apkDir, "a.apk", 10)
        file(libraryDir, "lib.so", 10)

        manager.deleteArea(StorageAreaId.Apks)

        assertFalse(File(manager.apkDir, "a.apk").exists())
        assertTrue(File(libraryDir, "lib.so").isFile)
    }

    @Test
    fun `preflight reports the shortfall in both numbers`() {
        val check = SpaceCheck(neededBytes = 1_900_000_000L, availableBytes = 640_000_000L)

        assertFalse(check.enough)
        assertTrue(check.message.contains("Need about"))
        assertTrue(check.message.contains("available"))
    }

    @Test
    fun `preflight passes when the volume has room`() {
        val check = StorageManager.preflight(1_000L, root)

        assertTrue(check.enough)
        assertTrue(check.availableBytes > 0L)
    }

    @Test
    fun `estimate covers the APK, the library, the shards and the archive`() {
        val oneGigabyte = 1_000_000_000L

        val estimate = StorageManager.estimateBytes(oneGigabyte)

        // APK + library + shards + zip is about 1.96 GB before headroom.
        assertTrue(estimate > 1_800_000_000L)
        assertTrue(estimate < 2_400_000_000L)
        assertEquals(
            estimate,
            StorageManager.estimateBytes(oneGigabyte, (oneGigabyte * 0.36).toLong())
        )
    }

    @Test
    fun `archive estimate scales with the library`() {
        val small = StorageManager.estimateArchiveBytes(100_000_000L)
        val large = StorageManager.estimateArchiveBytes(400_000_000L)

        assertTrue(large > small)
        assertTrue(small > 0L)
    }

    @Test
    fun `ENOSPC is recognised through the cause chain`() {
        val wrapped = IOException("write failed", IOException("No space left on device"))

        assertTrue(StorageManager.isNoSpace(wrapped))
        assertFalse(StorageManager.isNoSpace(IOException("permission denied")))
    }

    @Test
    fun `no-space message names the numbers and the next step`() {
        val message = StorageManager.noSpaceMessage(1_900_000_000L, 640_000_000L)

        assertTrue(message.contains("Need about"))
        assertTrue(message.contains("Free some space"))
    }

    @Test
    fun `no-space exception carries the estimate`() {
        val exception = StorageManager.noSpaceException(SpaceCheck(2_000L, 500L))

        assertEquals(2_000L, exception.neededBytes)
        assertEquals(500L, exception.availableBytes)
    }

    @Test
    fun `deleteDownloadedApks touches only the download directory`() {
        file(manager.apkDir, "a.apk", 10)
        val pickerCopy = file(cacheDir, "picked.apk", 10)

        manager.deleteDownloadedApks()

        assertFalse(File(manager.apkDir, "a.apk").exists())
        assertTrue(pickerCopy.isFile)
    }

    @Test
    fun `usage marks the kept table as needed by the result`() {
        val table = file(internalDir, "abc.ndjson", 10)
        file(internalDir, "old.ndjson", 10)

        val usage = manager.usage(keepTablePath = table.absolutePath)

        val needed = usage.areas
            .first { it.id == StorageAreaId.Internal }
            .files
            .filter { it.neededByResult }
            .map { it.file.name }

        assertEquals(listOf("abc.ndjson"), needed)
        assertNotNull(usage.areas.firstOrNull { it.id == StorageAreaId.Internal })
        assertNull(usage.areas.firstOrNull { it.id == StorageAreaId.Logs })
    }
}
