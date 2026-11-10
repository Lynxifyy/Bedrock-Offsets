package com.chimerateam.offsetextractor.data.cache

import java.io.File

/**
 * On-disk cache of extracted libraries and exported offset documents.
 *
 * Every method is fail-closed: a directory that cannot be read yields an empty
 * listing or a zero size rather than an exception, so a broken cache never
 * stops a scan.
 *
 * @param root directory that holds the cached artefacts.
 */
class ScanCache(private val root: File) {

    /** Directory that receives extracted native libraries. */
    val libraryDir: File = File(root, "libs")

    /** Directory that receives exported offset documents. */
    val documentDir: File = File(root, "documents")

    /** Creates the cache directories, ignoring a failure to do so. */
    fun ensureDirectories() {
        runCatching { libraryDir.mkdirs() }
        runCatching { documentDir.mkdirs() }
    }

    /**
     * Absolute path of the cached document for [buildId].
     *
     * @param buildId build identifier of the library.
     * @return the file the document would be stored in.
     */
    fun documentFor(buildId: String): File = File(documentDir, documentFileName(buildId))

    /**
     * Reads the cached document for [buildId].
     *
     * @param buildId build identifier of the library.
     * @return the document text, or `null` when it is absent or unreadable.
     */
    fun readDocument(buildId: String): String? {
        if (buildId.isBlank()) return null
        val file = documentFor(buildId)
        if (!file.isFile) return null
        return runCatching { file.readText() }.getOrNull()
    }

    /**
     * Stores [json] as the document for [buildId].
     *
     * The write is atomic: the text lands in a temporary file that is then
     * renamed over the target, so a scan interrupted mid-write cannot leave a
     * half-written document that a later run would treat as a cache hit.
     *
     * @param buildId build identifier of the library.
     * @param json the document text.
     * @return whether the document was stored.
     */
    fun writeDocument(buildId: String, json: String): Boolean {
        if (buildId.isBlank()) return false
        ensureDirectories()
        val target = documentFor(buildId)
        val temporary = File(target.parentFile, "${target.name}.tmp")
        return runCatching {
            temporary.writeText(json)
            if (target.exists()) {
                target.delete()
            }
            temporary.renameTo(target)
        }.getOrDefault(false)
    }

    /**
     * Whether a cached document exists for [buildId].
     *
     * @param buildId build identifier of the library.
     * @return true when the document can be read back.
     */
    fun hasDocument(buildId: String): Boolean {
        if (buildId.isBlank()) return false
        return documentFor(buildId).isFile
    }

    /** Total size in bytes of everything in the cache. */
    fun totalSizeBytes(): Long =
        listFiles(libraryDir).sumOf { it.length() } + listFiles(documentDir).sumOf { it.length() }

    /**
     * Deletes every cached artefact.
     *
     * @return whether the cache is empty afterwards.
     */
    fun clear(): Boolean {
        val librariesCleared = clearDirectory(libraryDir)
        val documentsCleared = clearDirectory(documentDir)
        return librariesCleared && documentsCleared
    }

    private fun listFiles(directory: File): List<File> =
        runCatching { directory.listFiles()?.filter { it.isFile }.orEmpty() }.getOrDefault(emptyList())

    private fun clearDirectory(directory: File): Boolean = runCatching {
        directory.listFiles()?.forEach { entry ->
            if (entry.isDirectory) {
                entry.deleteRecursively()
            } else {
                entry.delete()
            }
        }
        true
    }.getOrDefault(false)

    companion object {
        /**
         * File name used for the document of [buildId].
         *
         * @param buildId build identifier of the library.
         * @return a name safe to use on every supported file system.
         */
        fun documentFileName(buildId: String): String {
            val safe = buildId.filter { it.isLetterOrDigit() || it == '-' || it == '_' }
            return "offsets_${safe.ifBlank { "unknown" }}.json"
        }
    }
}
