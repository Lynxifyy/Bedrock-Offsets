package com.chimerateam.offsetextractor.data.saf

import com.chimerateam.offsetextractor.data.native.NativeLibraryMetadata
import com.chimerateam.offsetextractor.data.native.PatternSpec
import com.chimerateam.offsetextractor.data.native.ProgressCallback
import com.chimerateam.offsetextractor.data.native.ScanOutcome
import com.chimerateam.offsetextractor.data.native.SectionMap
import com.chimerateam.offsetextractor.data.native.SymbolReport
import com.chimerateam.offsetextractor.data.native.VtableMap
import java.io.File

/**
 * The extraction engine the ViewModel drives.
 *
 * Declared as an interface so unit tests can substitute a deterministic double
 * while production uses the JNI-backed implementation.
 */
interface OffsetEngine {

    /**
     * Extracts the native library from the APK behind [rawFd] and hashes it.
     *
     * Declared `suspend` so callers can cancel it and so implementations are
     * free to hop dispatchers. Implementations must not run on the main thread.
     *
     * @param rawFd read-only descriptor owned by the caller.
     * @param destDir cache directory that receives the extracted file.
     * @return the extracted path, its SHA-256 and its size.
     * @throws EngineException when the archive is unreadable or the entry is
     *   absent.
     */
    suspend fun extractNativeLibrary(rawFd: Int, destDir: File): ExtractedNativeLibrary

    /**
     * Maps the extracted library with `mmap` and reports its ELF metadata.
     *
     * Implementations must not read the file into a heap buffer: a shared
     * object can be hundreds of megabytes.
     *
     * @param library the library produced by [extractNativeLibrary].
     * @return the mapped library description.
     * @throws EngineException when the file is not a 64-bit AArch64 ELF object.
     */
    suspend fun mapLibrary(library: ExtractedNativeLibrary): NativeLibraryMetadata

    /**
     * Reads the section header table of the mapped library.
     *
     * Only headers are read; no section body is copied.
     *
     * @param library the library produced by [extractNativeLibrary].
     * @return the section layout, including the `.text` boundaries.
     * @throws EngineException when the library is stripped or has no `.text`.
     */
    suspend fun describeSections(library: ExtractedNativeLibrary): SectionMap

    /**
     * Reads the build identifier and the exported JNI symbols.
     *
     * @param library the library produced by [extractNativeLibrary].
     * @return the library identity and its exported entry points.
     * @throws EngineException when the library cannot be parsed.
     */
    suspend fun describeSymbols(library: ExtractedNativeLibrary): SymbolReport

    /**
     * Scans the executable section for every supplied signature.
     *
     * @param library the library produced by [extractNativeLibrary].
     * @param patterns signatures to look for.
     * @param progress receives progress reports, called from a background
     *   thread. Pass `null` to scan without progress reporting.
     * @return every verified hit, with its file offset and virtual address.
     * @throws EngineException when the library or a pattern is unusable.
     */
    suspend fun scanText(
        library: ExtractedNativeLibrary,
        patterns: List<PatternSpec>,
        progress: ProgressCallback? = null
    ): ScanOutcome

    /**
     * Resolves the RTTI vtables the library declares.
     *
     * A library that carries no C++ RTTI, which is the case for the Rust engine
     * itself, yields an empty map rather than an error.
     *
     * @param library the library produced by [extractNativeLibrary].
     * @return the resolved vtables keyed by class name.
     * @throws EngineException when the library cannot be parsed.
     */
    suspend fun resolveVtables(library: ExtractedNativeLibrary): VtableMap
}

/**
 * Outcome of a successful native library extraction.
 *
 * @property path absolute path of the extracted file.
 * @property sha256 lower-case hex SHA-256 of the file contents.
 * @property sizeBytes size of the extracted file.
 */
data class ExtractedNativeLibrary(
    val path: String,
    val sha256: String,
    val sizeBytes: Long
)

/**
 * Engine failure carrying a machine-readable [kind] plus a user-facing message.
 *
 * @property kind classification used to choose the UI response.
 * @property message human-readable detail.
 */
class EngineException(
    val kind: EngineErrorKind,
    message: String,
    cause: Throwable? = null
) : Exception(message, cause)

/** Classification of engine failures. */
enum class EngineErrorKind {
    /** The APK does not contain the expected native library. */
    MissingSoFile,

    /** The APK is not a readable archive. */
    InvalidApk,

    /** The descriptor could not be used. */
    InvalidDescriptor,

    /** The device ran out of storage while writing the extracted file. */
    StorageFull,

    /** The file is not a parseable ELF object. */
    InvalidElf,

    /** The file targets an architecture other than 64-bit AArch64. */
    UnsupportedArchitecture,

    /** A section the scanner needs is absent from the library. */
    MissingSection,

    /** A signature pattern could not be compiled. */
    InvalidPattern,

    /**
     * The engine's JSON payload could not be turned into a model.
     *
     * This is an app defect rather than a bad APK: a release build that shrinks
     * the payload classes away fails here, and the cause is not something the
     * user can act on.
     */
    Payload,

    /** The user cancelled the operation. */
    Cancelled,

    /** Anything else. */
    Unknown
}
