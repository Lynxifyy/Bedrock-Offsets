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
     * @return a JSON object describing the scan and every hit.
     * @throws EngineFailure when the library or a pattern is unusable.
     */
    fun scanText(path: String, sha256: String, patternsJson: String): String {
        ensureLoaded()
        return scanTextNative(path, sha256, patternsJson)
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

    private fun ensureLoaded() {
        if (!isAvailable) {
            throw EngineFailure(
                kind = "invalid_descriptor",
                detail = "native engine unavailable: ${loadFailure ?: "unknown reason"}",
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
    ): String

    private external fun resolveVtablesNative(path: String, sha256: String): String
}
