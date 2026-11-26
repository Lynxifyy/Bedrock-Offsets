package com.chimerateam.offsetextractor.data.storage

import com.chimerateam.offsetextractor.data.cache.ScanCache
import com.chimerateam.offsetextractor.util.formatBytes
import java.io.File
import java.io.IOException

/** Where an artefact lives, used to group the storage list. */
enum class StorageAreaId {
    /** APKs the APKs page downloaded. */
    Apks,

    /** Extracted native libraries. */
    Libraries,

    /** Internal tables and the category shards built from them. */
    Internal,

    /** Built ZIP archives waiting to be copied. */
    Exports,

    /** Offset documents written by the Save actions. */
    Documents,

    /** Spilled live-log files. */
    Logs
}

/**
 * One file the app holds.
 *
 * @property file the file on disk.
 * @property area which group it belongs to.
 * @property bytes its size.
 * @property neededByResult whether deleting it would break the current result.
 */
data class StorageFile(
    val file: File,
    val area: StorageAreaId,
    val bytes: Long,
    val neededByResult: Boolean
)

/**
 * One group of files with its total.
 *
 * @property id the group.
 * @property bytes total size of its files.
 * @property files the files, largest first.
 */
data class StorageArea(
    val id: StorageAreaId,
    val bytes: Long,
    val files: List<StorageFile>
) {
    /** Human-readable label of the group. */
    val label: String
        get() = when (id) {
            StorageAreaId.Apks -> "Downloaded APKs"
            StorageAreaId.Libraries -> "Extracted libraries"
            StorageAreaId.Internal -> "Internal tables and shards"
            StorageAreaId.Exports -> "Built ZIPs"
            StorageAreaId.Documents -> "Saved offsets"
            StorageAreaId.Logs -> "Logs"
        }

    /** Number of files in the group. */
    val count: Int get() = files.size

    /** Whether at least one file can be deleted without breaking the result. */
    val deletable: Boolean get() = files.any { !it.neededByResult }
}

/**
 * Everything the app holds, with a total.
 *
 * @property areas one entry per non-empty group, in a stable order.
 * @property totalBytes sum of every file.
 */
data class StorageUsage(val areas: List<StorageArea>, val totalBytes: Long) {

    /** Size of the group [id], or zero when it is empty. */
    fun bytesOf(id: StorageAreaId): Long = areas.firstOrNull { it.id == id }?.bytes ?: 0L

    /** Whether nothing is held. */
    val isEmpty: Boolean get() = totalBytes == 0L
}

/**
 * Result of comparing the space a job needs with the space a volume has.
 *
 * @property neededBytes estimated requirement.
 * @property availableBytes free bytes on the target volume.
 */
data class SpaceCheck(val neededBytes: Long, val availableBytes: Long) {

    /** Whether the job may start. */
    val enough: Boolean get() = availableBytes >= neededBytes

    /** One-line message explaining a shortfall. */
    val message: String
        get() = if (enough) {
            "${formatBytes(availableBytes)} free"
        } else {
            "Need about ${formatBytes(neededBytes)} free, " +
                "${formatBytes(availableBytes)} available."
        }
}

/**
 * Owns the on-disk footprint of the app.
 *
 * One scan of a 1 GB APK touches several copies of the same data: the APK, the
 * extracted library, the internal table, the category shards and the ZIP. Every
 * one of them has a point after which it is dead weight, and this class is the
 * single place that knows those points, so no stage has to guess.
 *
 * All methods are fail-closed: an unreadable directory counts as empty and a
 * failed delete is reported as `false` rather than thrown, because storage
 * housekeeping must never stop a scan.
 *
 * @param cacheDir app cache directory, holds downloaded APKs and built ZIPs.
 * @param libraryDir extracted native libraries.
 * @param internalDir internal tables and shard work directories.
 * @param exportDir built ZIP archives.
 * @param documentDir offset documents written by Save.
 * @param logDir spilled live-log files.
 */
class StorageManager(
    private val cacheDir: File,
    private val libraryDir: File,
    private val internalDir: File,
    private val exportDir: File,
    private val documentDir: File,
    private val logDir: File
) {

    /** Directory that receives APKs downloaded from the APKs page. */
    val apkDir: File get() = File(cacheDir, APK_DIRECTORY)

    /**
     * Lists every artefact with its size.
     *
     * @param keepTablePath internal table the current result still needs, or
     *   `null` when there is no result.
     * @param keepZipPath cached ZIP an interrupted job still needs.
     */
    fun usage(keepTablePath: String? = null, keepZipPath: String? = null): StorageUsage {
        val keep = setOfNotNull(keepTablePath?.takeIf { it.isNotBlank() }, keepZipPath)
        val areas = listOf(
            StorageAreaId.Apks to apkDir,
            StorageAreaId.Libraries to libraryDir,
            StorageAreaId.Internal to internalDir,
            StorageAreaId.Exports to exportDir,
            StorageAreaId.Documents to documentDir,
            StorageAreaId.Logs to logDir
        ).map { (id, dir) -> areaOf(id, dir, keep) }.filter { it.files.isNotEmpty() }
        return StorageUsage(areas, areas.sumOf { it.bytes })
    }

    /**
     * Deletes what no current job needs.
     *
     * This is the sweep that runs on app start and after every job: partial
     * downloads, shard directories without a job, libraries without a result,
     * tables the result does not point at and ZIPs older than the persisted
     * job.
     *
     * @param keepTablePath table the current result needs, if any.
     * @param keepZipPath ZIP an interrupted job needs, if any.
     * @param keepApks whether downloaded APKs are kept (the setting is off).
     * @return bytes reclaimed.
     */
    fun sweep(
        keepTablePath: String? = null,
        keepZipPath: String? = null,
        keepApks: Boolean = false
    ): Long {
        var freed = 0L
        val keep = setOfNotNull(keepTablePath?.takeIf { it.isNotBlank() }, keepZipPath)
        // A `.part` or `.tmp` file is always a leftover: every writer either
        // renames it into place or deletes it in its own failure path.
        for (dir in listOf(apkDir, libraryDir, internalDir, exportDir, documentDir, logDir)) {
            for (file in listFiles(dir)) {
                if (file.name.endsWith(PART_SUFFIX) || file.name.endsWith(TMP_SUFFIX)) {
                    freed += sizeOf(file)
                    delete(file)
                }
            }
        }
        if (!keepApks) {
            for (file in listFiles(apkDir)) {
                if (file.isFile) {
                    freed += sizeOf(file)
                    delete(file)
                }
            }
        }
        val table = keepTablePath?.takeIf { it.isNotBlank() }?.let(::File)
        val keepDirectory = table?.parentFile
        for (file in listFiles(internalDir)) {
            if (file.isFile && file.absolutePath !in keep) {
                freed += sizeOf(file)
                delete(file)
            } else if (file.isDirectory && file.absolutePath != keepDirectory?.absolutePath) {
                freed += sizeOf(file)
                deleteTree(file)
            }
        }
        for (file in listFiles(libraryDir)) {
            if (file.isFile) {
                freed += sizeOf(file)
                delete(file)
            }
        }
        for (file in listFiles(exportDir)) {
            if (file.isFile && file.absolutePath !in keep) {
                freed += sizeOf(file)
                delete(file)
            }
        }
        return freed
    }

    /**
     * Deletes everything that is not needed to export the current result.
     *
     * @param keepTablePath table the current result needs, if any.
     * @return bytes reclaimed.
     */
    fun freeUpSpace(keepTablePath: String? = null): Long {
        val table = keepTablePath?.takeIf { it.isNotBlank() }?.let(::File)
        var freed = 0L
        for (dir in listOf(apkDir, libraryDir, exportDir, documentDir, logDir)) {
            freed += sizeOf(dir)
            deleteTree(dir)
        }
        val keepDirectory = table?.parentFile
        for (file in listFiles(internalDir)) {
            val keep = file.absolutePath == table?.absolutePath ||
                file.absolutePath == keepDirectory?.absolutePath
            if (!keep) {
                freed += sizeOf(file)
                if (file.isDirectory) deleteTree(file) else delete(file)
            }
        }
        return freed
    }

    /**
     * Deletes one group.
     *
     * @param id the group to clear.
     * @param keepTablePath table the current result needs, if any.
     * @return bytes reclaimed.
     */
    fun deleteArea(id: StorageAreaId, keepTablePath: String? = null): Long {
        val dir = directoryOf(id)
        val table = keepTablePath?.takeIf { it.isNotBlank() }?.let(::File)
        var freed = 0L
        for (file in listFiles(dir)) {
            val keep = id == StorageAreaId.Internal &&
                (file.absolutePath == table?.absolutePath ||
                    file.absolutePath == table?.parentFile?.absolutePath)
            if (keep) continue
            freed += sizeOf(file)
            if (file.isDirectory) deleteTree(file) else delete(file)
        }
        return freed
    }

    /**
     * Enforces the cache cap, oldest file first.
     *
     * @param maxBytes cap in bytes.
     * @param keepTablePath table the current result needs, if any.
     * @return bytes reclaimed.
     */
    fun enforceCap(maxBytes: Long, keepTablePath: String? = null): Long {
        if (maxBytes <= 0L) return 0L
        val usage = usage(keepTablePath)
        if (usage.totalBytes <= maxBytes) return 0L
        var freed = 0L
        var total = usage.totalBytes
        val candidates = usage.areas
            .flatMap { it.files }
            .filterNot { it.neededByResult }
            .sortedBy { it.file.lastModified() }
        for (candidate in candidates) {
            if (total <= maxBytes) break
            freed += candidate.bytes
            total -= candidate.bytes
            delete(candidate.file)
        }
        return freed
    }

    /**
     * Deletes every downloaded APK.
     *
     * @return bytes reclaimed.
     */
    fun deleteDownloadedApks(): Long {
        val freed = sizeOf(apkDir)
        deleteTree(apkDir)
        return freed
    }

    /**
     * Newest internal table on disk, or `null` when there is none.
     *
     * The sweep keeps it so the cached result of the previous scan can still be
     * exported after the app restarts.
     */
    fun newestTable(): File? =
        listFiles(internalDir)
            .filter { it.isFile && it.name.endsWith(".ndjson") }
            .maxByOrNull { it.lastModified() }

    /**
     * Deletes one file, used by the per-item delete button.
     *
     * @param file the file to remove.
     * @return bytes reclaimed.
     */
    fun delete(file: File): Long {
        val bytes = sizeOf(file)
        val removed = if (file.isDirectory) {
            deleteTree(file)
        } else {
            runCatching { file.delete() }.getOrDefault(false)
        }
        return if (removed) bytes else 0L
    }

    /** Directory backing [id]. */
    fun directoryOf(id: StorageAreaId): File = when (id) {
        StorageAreaId.Apks -> apkDir
        StorageAreaId.Libraries -> libraryDir
        StorageAreaId.Internal -> internalDir
        StorageAreaId.Exports -> exportDir
        StorageAreaId.Documents -> documentDir
        StorageAreaId.Logs -> logDir
    }

    private fun areaOf(
        id: StorageAreaId,
        dir: File,
        keep: Set<String>
    ): StorageArea {
        val keepParents = keep.mapNotNull { File(it).parentFile?.absolutePath }.toSet()
        val files = listFiles(dir).map { file ->
            StorageFile(
                file = file,
                area = id,
                bytes = sizeOf(file),
                neededByResult = file.absolutePath in keep ||
                    file.absolutePath in keepParents
            )
        }.sortedByDescending { it.bytes }
        return StorageArea(id, files.sumOf { it.bytes }, files)
    }

    companion object {
        /** Cache sub-directory that receives downloaded APKs. */
        const val APK_DIRECTORY = "apks"

        /** Suffix of an in-progress download. */
        const val PART_SUFFIX = ".part"

        /** Suffix of an atomic-write temporary file. */
        const val TMP_SUFFIX = ".tmp"

        /** How much of the library size the category shards add. */
        private const val SHARD_FACTOR = 0.35

        /** How much of the library size the finished ZIP adds. */
        private const val ZIP_FACTOR = 0.25

        /** Safety margin on top of the estimate. */
        private const val HEADROOM_FACTOR = 1.15

        /**
         * Builds a manager from the app's directories.
         *
         * @param cacheDir app cache directory.
         * @param cache the persistent scan cache.
         * @param exportDir directory that receives built ZIPs.
         * @param logDir directory that receives spilled logs.
         */
        fun create(
            cacheDir: File,
            cache: ScanCache,
            exportDir: File,
            logDir: File
        ): StorageManager = StorageManager(
            cacheDir = cacheDir,
            libraryDir = cache.libraryDir,
            internalDir = cache.internalDir,
            exportDir = exportDir,
            documentDir = cache.documentDir,
            logDir = logDir
        )

        /**
         * Estimates the peak space one scan needs.
         *
         * The APK is held until the library is extracted, the library until the
         * internal pass ends, and the shards and the ZIP overlap while the
         * archive is written. A safe factor covers a library larger than the
         * APK's compressed entry suggests.
         *
         * @param apkBytes size of the APK, or `-1` when unknown.
         * @param libraryBytes size of the extracted library, when known.
         * @return bytes to reserve.
         */
        fun estimateBytes(apkBytes: Long, libraryBytes: Long = 0L): Long {
            val apk = apkBytes.coerceAtLeast(0L)
            val library = if (libraryBytes > 0L) {
                libraryBytes
            } else {
                (apk * 0.36).toLong()
            }
            val shards = (library * SHARD_FACTOR).toLong()
            val zip = (library * ZIP_FACTOR).toLong()
            return ((apk + library + shards + zip) * HEADROOM_FACTOR).toLong()
        }

        /**
         * Estimates the space the ZIP stage needs on top of the table.
         *
         * @param libraryBytes size of the extracted library.
         */
        fun estimateArchiveBytes(libraryBytes: Long): Long =
            (libraryBytes.coerceAtLeast(0L) * (SHARD_FACTOR + ZIP_FACTOR) * HEADROOM_FACTOR)
                .toLong()

        /**
         * Compares [neededBytes] with the free space of [volume].
         *
         * @param neededBytes estimate from [estimateBytes].
         * @param volume directory on the target volume.
         */
        fun preflight(neededBytes: Long, volume: File): SpaceCheck =
            SpaceCheck(neededBytes, runCatching { volume.usableSpace }.getOrDefault(0L))

        /**
         * Whether [throwable] is the system running out of space.
         *
         * Android reports it either as an `IOException` with the Linux wording
         * or as an `ErrnoException` wrapped in one, so both the message and the
         * cause chain are inspected.
         *
         * @param throwable failure from any stage.
         */
        fun isNoSpace(throwable: Throwable): Boolean {
            var current: Throwable? = throwable
            var depth = 0
            while (current != null && depth < 8) {
                val message = current.message.orEmpty().lowercase()
                if (message.contains("no space left") ||
                    message.contains("enospc") ||
                    message.contains("disk full") ||
                    message.contains("not enough space")
                ) {
                    return true
                }
                current = current.cause
                depth += 1
            }
            return false
        }

        /**
         * Message shown when a stage ran out of space.
         *
         * @param neededBytes estimated requirement.
         * @param availableBytes free bytes at the time of the failure.
         */
        fun noSpaceMessage(neededBytes: Long, availableBytes: Long): String =
            SpaceCheck(neededBytes, availableBytes).message +
                " Free some space and try again."

        /**
         * Builds the exception thrown by a preflight that fails.
         *
         * @param check the failed comparison.
         */
        fun noSpaceException(check: SpaceCheck): NoSpaceException =
            NoSpaceException(check.message, check.neededBytes, check.availableBytes)

        /** Lists [dir], tolerating an unreadable or missing directory. */
        private fun listFiles(dir: File): List<File> =
            runCatching { dir.listFiles()?.toList() }.getOrNull().orEmpty()

        /** Total size of [file], recursively for a directory. */
        private fun sizeOf(file: File): Long {
            if (!file.exists()) return 0L
            if (file.isFile) return file.length()
            return runCatching {
                file.walkTopDown().filter { it.isFile }.sumOf { it.length() }
            }.getOrDefault(0L)
        }

        /** Deletes [dir] and everything under it. */
        private fun deleteTree(dir: File): Boolean =
            runCatching { dir.deleteRecursively() }.getOrDefault(false)
    }
}

/**
 * Thrown by a preflight that cannot start because the volume is too small.
 *
 * @property neededBytes estimated requirement.
 * @property availableBytes free bytes on the volume.
 */
class NoSpaceException(
    message: String,
    val neededBytes: Long,
    val availableBytes: Long
) : IOException(message)
