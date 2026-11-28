package com.chimerateam.offsetextractor.data.native

/**
 * Loads the Rust engine once and exposes its JNI entry points.
 *
 * Loading is fail-closed: if the shared object is missing or cannot be loaded,
 * [isAvailable] stays `false` and the entry points throw [EngineFailure]
 * instead of leaving the process in a half-initialised state. Nothing here
 * throws during class initialisation, so the app still starts on a device where
 * the native library is absent.
 */
object NativeLib {

    /** Name of the shared object produced by the `offset_core` crate. */
    const val LIBRARY_NAME: String = "offset_core"

    /** Set when [System.loadLibrary] succeeded. */
    var isAvailable: Boolean = false
        private set

    /** Failure detail recorded when loading did not succeed. */
    var loadFailure: String? = null
        private set

    init {
        try {
            System.loadLibrary(LIBRARY_NAME)
            isAvailable = true
        } catch (unsatisfied: UnsatisfiedLinkError) {
            isAvailable = false
            loadFailure = unsatisfied.message ?: "library not found"
        } catch (security: SecurityException) {
            isAvailable = false
            loadFailure = security.message ?: "library loading denied"
        }
    }

    /**
     * Returns the engine version string.
     *
     * @return the version reported by the native library.
     * @throws EngineFailure when the native library is not loaded.
     */
    fun rustTest(): String {
        ensureLoaded()
        return rustTestNative()
    }

    /**
     * Extracts the native library from the APK behind [fd].
     *
     * @param fd read-only descriptor owned by the caller.
     * @param destinationDir absolute cache directory for the extracted file.
     * @return a JSON object with `path`, `sha256` and `size`.
     * @throws EngineFailure when the archive is unreadable or the entry is
     *   missing.
     */
    fun extractNativeLibrary(fd: Int, destinationDir: String): String {
        ensureLoaded()
        return extractNativeLibraryNative(fd, destinationDir)
    }

    /**
     * Maps the library at [path] and returns its ELF metadata as JSON.
     *
     * @param path absolute path of the extracted library.
     * @param sha256 SHA-256 recorded during extraction.
     * @return a JSON object describing the mapped library.
     * @throws EngineFailure when the file is not a 64-bit AArch64 ELF object.
     */
    fun mapNativeLibrary(path: String, sha256: String): String {
        ensureLoaded()
        return mapNativeLibraryNative(path, sha256)
    }

    /**
     * Reads the section header table of the library at [path].
     *
     * @param path absolute path of the extracted library.
     * @param sha256 SHA-256 recorded during extraction.
     * @return a JSON object describing every section.
     * @throws EngineFailure when the library has no section table or no `.text`.
     */
    fun describeSections(path: String, sha256: String): String {
        ensureLoaded()
        return describeSectionsNative(path, sha256)
    }

    /**
     * Reads the build identifier and exported symbols of the library at [path].
     *
     * @param path absolute path of the extracted library.
     * @param sha256 SHA-256 recorded during extraction.
     * @return a JSON object with `identity` and `symbols`.
     * @throws EngineFailure when the library cannot be parsed.
     */
    fun describeSymbols(path: String, sha256: String): String {
        ensureLoaded()
        return describeSymbolsNative(path, sha256)
    }

    /**
     * Scans the executable section of the library at [path].
     *
     * @param path absolute path of the extracted library.
     * @param sha256 SHA-256 recorded during extraction.
     * @param patternsJson JSON array of `{"name": ..., "pattern": ...}` objects.
     * @param callback receives progress reports, or `null` to scan silently.
     * @return a JSON object describing the scan and every hit.
     * @throws EngineFailure when the library or a pattern is unusable.
     */
    fun scanText(
        path: String,
        sha256: String,
        patternsJson: String,
        callback: ProgressCallback? = null
    ): String {
        ensureLoaded()
        return scanTextNative(path, sha256, patternsJson, callback)
    }

    /**
     * Asks the scan in flight to stop.
     *
     * The scan is a blocking call, so this is the only way to interrupt it from
     * another thread. A no-op when no scan is running.
     */
    fun cancelScan() {
        ensureLoaded()
        cancelScanNative()
    }

    /**
     * Releases the engine's shared mapping of the last opened library.
     *
     * The mapping must not outlive the file. Deleting or replacing an extracted
     * `.so` while the engine still maps it faults with `SIGBUS` on the next
     * access, and that signal cannot be caught in Rust. Call this before a
     * library is deleted or the cache directory is cleared.
     *
     * @return `true` when a mapping was released.
     */
    fun clearMappedCache(): Boolean {
        ensureLoaded()
        return clearMappedCacheNative()
    }

    /**
     * Disassembles up to 512 bytes of `.text` at [address].
     *
     * @param path absolute path of the extracted library.
     * @param sha256 SHA-256 recorded during extraction.
     * @param address virtual address of the first instruction.
     * @param byteCount number of bytes to decode, clamped to 512.
     * @return a JSON object with the decoded instructions and their references.
     * @throws EngineFailure when the address is outside `.text`.
     */
    fun disassembleRange(path: String, sha256: String, address: Long, byteCount: Int): String {
        ensureLoaded()
        return disassembleRangeNative(path, sha256, address, byteCount)
    }

    /**
     * Inspects one address: its function, callers, callees, strings and vtables.
     *
     * @param path absolute path of the extracted library.
     * @param sha256 SHA-256 recorded during extraction.
     * @param address virtual address to inspect.
     * @return a JSON object describing everything the engine knows about it.
     * @throws EngineFailure when the library cannot be parsed.
     */
    fun inspectAddress(path: String, sha256: String, address: Long): String {
        ensureLoaded()
        return inspectAddressNative(path, sha256, address)
    }

    /**
     * Resolves the RTTI vtables the library at [path] declares.
     *
     * @param path absolute path of the extracted library.
     * @param sha256 SHA-256 recorded during extraction.
     * @return a JSON object describing every resolved vtable.
     * @throws EngineFailure when the library cannot be parsed.
     */
    fun resolveVtables(path: String, sha256: String): String {
        ensureLoaded()
        return resolveVtablesNative(path, sha256)
    }

    /**
     * Resolves the RTTI vtables and writes the JSON directly to [outputPath].
     *
     * @param path absolute path of the extracted library.
     * @param sha256 SHA-256 recorded during extraction.
     * @param outputPath file that receives the JSON payload.
     * @return whether the file was written.
     * @throws EngineFailure when the library cannot be parsed.
     */
    fun resolveVtablesToFile(path: String, sha256: String, outputPath: String): Boolean {
        ensureLoaded()
        return resolveVtablesToFileNative(path, sha256, outputPath)
    }

    /**
     * Extracts every internal C++ function and virtual slot into an NDJSON file.
     *
     * @param path absolute path of the extracted library.
     * @param sha256 SHA-256 recorded during extraction.
     * @param optionsJson JSON options selecting the passes.
     * @param outputDir cache directory that receives the NDJSON table.
     * @param callback receives progress reports, or `null` to run silently.
     * @return a JSON object with `internal` counts and `peak_resident_bytes`.
     * @throws EngineFailure when the library cannot be parsed.
     */
    fun analyzeInternal(
        path: String,
        sha256: String,
        optionsJson: String,
        outputDir: String,
        callback: ProgressCallback? = null
    ): String {
        ensureLoaded()
        return analyzeInternalNative(path, sha256, optionsJson, outputDir, callback)
    }

    /**
     * Reads `versionName` from the APK behind [fd].
     *
     * @param fd read-only descriptor owned by the caller.
     * @return a JSON object with `versionName`, which may be `null`.
     */
    fun readApkVersion(fd: Int): String {
        ensureLoaded()
        return readApkVersionNative(fd)
    }

    private fun ensureLoaded() {
        if (!isAvailable) {
            throw EngineFailure(
                kind = "invalid_descriptor",
                detail = "native engine unavailable: ${loadFailure ?: "unknown reason"}"
            )
        }
    }

    private external fun rustTestNative(): String

    private external fun extractNativeLibraryNative(fd: Int, destinationDir: String): String

    private external fun mapNativeLibraryNative(path: String, sha256: String): String
    private external fun describeSectionsNative(path: String, sha256: String): String
    private external fun describeSymbolsNative(path: String, sha256: String): String
    private external fun scanTextNative(
        path: String,
        sha256: String,
        patternsJson: String,
        callback: ProgressCallback?
    ): String

    private external fun resolveVtablesNative(path: String, sha256: String): String
    private external fun resolveVtablesToFileNative(
        path: String,
        sha256: String,
        outputPath: String
    ): Boolean

    private external fun analyzeInternalNative(
        path: String,
        sha256: String,
        optionsJson: String,
        outputDir: String,
        callback: ProgressCallback?
    ): String

    private external fun readApkVersionNative(fd: Int): String

    private external fun cancelScanNative()

    private external fun clearMappedCacheNative(): Boolean

    private external fun disassembleRangeNative(
        path: String,
        sha256: String,
        address: Long,
        byteCount: Int
    ): String

    private external fun inspectAddressNative(path: String, sha256: String, address: Long): String
}
