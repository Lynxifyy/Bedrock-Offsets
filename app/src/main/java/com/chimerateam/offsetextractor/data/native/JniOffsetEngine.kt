package com.chimerateam.offsetextractor.data.native

import com.chimerateam.offsetextractor.data.saf.EngineErrorKind
import com.chimerateam.offsetextractor.data.saf.EngineException
import com.chimerateam.offsetextractor.data.saf.ExtractedNativeLibrary
import com.chimerateam.offsetextractor.data.saf.OffsetEngine
import java.io.File

/**
 * [OffsetEngine] backed by the Rust library through JNI.
 *
 * Extraction streams the entry out of the archive; mapping then `mmap`s the
 * result, so neither operation copies the file into the JVM heap.
 *
 * @param nativeLibrary the loaded native bindings; injectable for tests.
 */
class JniOffsetEngine(
    private val nativeLibrary: NativeLibrary = JniNativeLibrary,
) : OffsetEngine {

    /**
     * Streams the native library out of the APK behind [rawFd].
     *
     * @param rawFd read-only descriptor owned by the caller.
     * @param destDir cache directory that receives the extracted file.
     * @return the extracted path, its SHA-256 and its size.
     * @throws EngineException when the archive is unreadable or the entry is
     *   absent.
     */
    override suspend fun extractNativeLibrary(
        rawFd: Int,
        destDir: File,
    ): ExtractedNativeLibrary {
        val json = call("extracting the native library") {
            nativeLibrary.extractNativeLibrary(rawFd, destDir.absolutePath)
        }
        return EnginePayloads.parseExtraction(json)
    }

    /**
     * Maps the extracted library and reports its ELF metadata.
     *
     * @param library the previously extracted library.
     * @return the mapped library description.
     * @throws EngineException when the file is not a 64-bit AArch64 ELF object.
     */
    override suspend fun mapLibrary(library: ExtractedNativeLibrary): NativeLibraryMetadata {
        val json = call("mapping the native library") {
            nativeLibrary.mapNativeLibrary(library.path, library.sha256)
        }
        return EnginePayloads.parseLibraryMetadata(json)
    }

    /**
     * Reads the section header table of the mapped library.
     *
     * @param library the previously extracted library.
     * @return the section layout, including the `.text` boundaries.
     * @throws EngineException when the library is stripped or has no `.text`.
     */
    override suspend fun describeSections(library: ExtractedNativeLibrary): SectionMap {
        val json = call("reading section headers") {
            nativeLibrary.describeSections(library.path, library.sha256)
        }
        return EnginePayloads.parseSectionMap(json)
    }

    /**
     * Reads the build identifier and the exported JNI symbols.
     *
     * @param library the previously extracted library.
     * @return the library identity and its exported entry points.
     * @throws EngineException when the library cannot be parsed.
     */
    override suspend fun describeSymbols(library: ExtractedNativeLibrary): SymbolReport {
        val json = call("reading symbols") {
            nativeLibrary.describeSymbols(library.path, library.sha256)
        }
        return EnginePayloads.parseSymbolReport(json)
    }

    /**
     * Scans the executable section for every supplied signature.
     *
     * @param library the previously extracted library.
     * @param patterns signatures to look for.
     * @return every verified hit, with its file offset and virtual address.
     * @throws EngineException when the library or a pattern is unusable.
     */
    override suspend fun scanText(
        library: ExtractedNativeLibrary,
        patterns: List<PatternSpec>,
        progress: ProgressCallback?,
    ): ScanOutcome {
        val json = call("scanning the executable section") {
            nativeLibrary.scanText(
                library.path,
                library.sha256,
                EnginePayloads.encodePatternSpecs(patterns),
                progress,
            )
        }
        return EnginePayloads.parseScanOutcome(json)
    }

    /**
     * Resolves the RTTI vtables the library declares.
     *
     * @param library the library produced by [extractNativeLibrary].
     * @return the resolved vtables keyed by class name.
     * @throws EngineException when the library cannot be parsed.
     */
    override suspend fun resolveVtables(library: ExtractedNativeLibrary): VtableMap {
        val json = call("resolving vtables") {
            nativeLibrary.resolveVtables(library.path, library.sha256)
        }
        return EnginePayloads.parseVtableMap(json)
    }

    private inline fun call(what: String, block: () -> String): String =
        try {
            block()
        } catch (failure: EngineFailure) {
            throw EnginePayloads.toEngineException(failure)
        } catch (unsatisfied: UnsatisfiedLinkError) {
            throw EngineException(
                kind = EngineErrorKind.InvalidDescriptor,
                message = "the native engine is unavailable: ${unsatisfied.message ?: "link error"}",
                cause = unsatisfied,
            )
        }
}

/**
 * The subset of the native bindings the engine uses.
 *
 * Declaring it as an interface lets tests substitute a deterministic double
 * without loading a shared object.
 */
interface NativeLibrary {

    /**
     * Extracts the native library from the APK behind [fd].
     *
     * @param fd read-only descriptor owned by the caller.
     * @param destinationDir absolute cache directory for the extracted file.
     * @return a JSON payload with `path`, `sha256` and `size`.
     */
    fun extractNativeLibrary(fd: Int, destinationDir: String): String

    /**
     * Maps the library at [path] and returns its ELF metadata as JSON.
     *
     * @param path absolute path of the extracted library.
     * @param sha256 SHA-256 recorded during extraction.
     * @return a JSON payload describing the mapped library.
     */
    fun mapNativeLibrary(path: String, sha256: String): String

    /**
     * Reads the section header table of the library at [path].
     *
     * @param path absolute path of the extracted library.
     * @param sha256 SHA-256 recorded during extraction.
     * @return a JSON payload describing every section.
     */
    fun describeSections(path: String, sha256: String): String

    /**
     * Reads the build identifier and exported symbols of the library at [path].
     *
     * @param path absolute path of the extracted library.
     * @param sha256 SHA-256 recorded during extraction.
     * @return a JSON payload with `identity` and `symbols`.
     */
    fun describeSymbols(path: String, sha256: String): String

    /**
     * Scans the executable section of the library at [path].
     *
     * @param path absolute path of the extracted library.
     * @param sha256 SHA-256 recorded during extraction.
     * @param patternsJson JSON array of `{"name": ..., "pattern": ...}` objects.
     * @return a JSON payload describing the scan and every hit.
     */
    fun scanText(
        path: String,
        sha256: String,
        patternsJson: String,
        callback: ProgressCallback? = null,
    ): String

    /**
     * Resolves the RTTI vtables of the library at [path].
     *
     * @param path absolute path of the extracted library.
     * @param sha256 SHA-256 recorded during extraction.
     * @return a JSON object describing every resolved vtable.
     * @throws EngineFailure when the library cannot be parsed.
     */
    fun resolveVtables(path: String, sha256: String): String
}

/** Production bindings, backed by [NativeLib]. */
object JniNativeLibrary : NativeLibrary {

    override fun extractNativeLibrary(fd: Int, destinationDir: String): String =
        NativeLib.extractNativeLibrary(fd, destinationDir)

    override fun mapNativeLibrary(path: String, sha256: String): String =
        NativeLib.mapNativeLibrary(path, sha256)

    override fun describeSections(path: String, sha256: String): String =
        NativeLib.describeSections(path, sha256)

    override fun describeSymbols(path: String, sha256: String): String =
        NativeLib.describeSymbols(path, sha256)

    override fun scanText(
        path: String,
        sha256: String,
        patternsJson: String,
        callback: ProgressCallback?,
    ): String = NativeLib.scanText(path, sha256, patternsJson, callback)

    override fun resolveVtables(path: String, sha256: String): String =
        NativeLib.resolveVtables(path, sha256)
}
