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
}
