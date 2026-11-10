package com.chimerateam.offsetextractor.data.native

import com.chimerateam.offsetextractor.data.saf.EngineErrorKind
import com.chimerateam.offsetextractor.data.saf.EngineException
import com.chimerateam.offsetextractor.data.saf.ExtractedNativeLibrary
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import com.google.gson.annotations.SerializedName

/**
 * Description of a native library as reported by the engine's ELF parser.
 *
 * @property path absolute path of the mapped file.
 * @property sha256 SHA-256 of the mapped file.
 * @property sizeBytes size of the mapped file.
 * @property architecture target architecture, `AArch64` for supported files.
 * @property bits word size in bits.
 * @property endianness byte order of the file.
 * @property elfType ELF file type, for example `DYN`.
 * @property entryPoint entry point as a hexadecimal string.
 * @property sectionCount number of section headers.
 * @property programHeaderCount number of program headers.
 * @property soname `DT_SONAME` of the library, when present.
 */
data class NativeLibraryMetadata(
    val path: String,
    val sha256: String,
    val sizeBytes: Long,
    val architecture: String,
    val bits: Int,
    val endianness: String,
    val elfType: String,
    val entryPoint: String,
    val sectionCount: Int,
    val programHeaderCount: Int,
    val soname: String?,
)

/** Wire model for the extraction payload; field names match the Rust output. */
private data class ExtractionPayload(
    val path: String?,
    val sha256: String?,
    val size: Long?,
)

/** Wire model for the ELF metadata payload; field names match the Rust output. */
private data class ElfMetadataPayload(
    val path: String?,
    val sha256: String?,
    val size: Long?,
    val format: String?,
    val architecture: String?,
    val bits: Int?,
    val endianness: String?,
    @SerializedName("elf_type") val elfType: String?,
    @SerializedName("entry_point") val entryPoint: String?,
    @SerializedName("section_count") val sectionCount: Int?,
    @SerializedName("program_header_count") val programHeaderCount: Int?,
    val soname: String?,
)

/**
 * Translates the engine's JSON payloads and error kinds into Kotlin types.
 *
 * Every parse is fail-closed: a malformed or truncated payload becomes an
 * [EngineException] describing the problem rather than a crash or a partially
 * populated object.
 */
object EnginePayloads {

    private val gson = Gson()

    /**
     * Parses the JSON returned by the extraction entry point.
     *
     * @param json payload produced by the engine.
     * @return the extracted library description.
     * @throws EngineException when the payload is malformed or incomplete.
     */
    fun parseExtraction(json: String?): ExtractedNativeLibrary {
        val payload = decode(json, ExtractionPayload::class.java, "extraction")
        val path = payload.path
        val hash = payload.sha256
        val size = payload.size
        if (path.isNullOrEmpty() || hash.isNullOrEmpty() || size == null) {
            throw EngineException(
                kind = EngineErrorKind.Unknown,
                message = "the engine returned an incomplete extraction payload",
            )
        }
        return ExtractedNativeLibrary(path = path, sha256 = hash, sizeBytes = size)
    }

    /**
     * Parses the JSON returned by the mapping entry point.
     *
     * @param json payload produced by the engine.
     * @return the mapped library description.
     * @throws EngineException when the payload is malformed or incomplete.
     */
    fun parseLibraryMetadata(json: String?): NativeLibraryMetadata {
        val payload = decode(json, ElfMetadataPayload::class.java, "ELF metadata")
        val path = payload.path
        val hash = payload.sha256
        val architecture = payload.architecture
        if (path.isNullOrEmpty() || hash.isNullOrEmpty() || architecture.isNullOrEmpty()) {
            throw EngineException(
                kind = EngineErrorKind.Unknown,
                message = "the engine returned an incomplete ELF metadata payload",
            )
        }
        return NativeLibraryMetadata(
            path = path,
            sha256 = hash,
            sizeBytes = payload.size ?: 0L,
            architecture = architecture,
            bits = payload.bits ?: 0,
            endianness = payload.endianness ?: "little",
            elfType = payload.elfType ?: "OTHER",
            entryPoint = payload.entryPoint ?: "0x0",
            sectionCount = payload.sectionCount ?: 0,
            programHeaderCount = payload.programHeaderCount ?: 0,
            soname = payload.soname,
        )
    }

    /**
     * Converts a failure thrown across the JNI boundary into a typed exception.
     *
     * @param failure the failure reported by the engine.
     * @return an exception carrying the mapped [EngineErrorKind].
     */
    fun toEngineException(failure: EngineFailure): EngineException =
        EngineException(
            kind = kindOf(failure.kind),
            message = failure.detail.ifEmpty { failure.message ?: "engine failure" },
            cause = failure,
        )

    /**
     * Maps an engine failure kind string onto the UI classification.
     *
     * @param kind kind reported by the engine.
     * @return the matching classification, defaulting to [EngineErrorKind.Unknown].
     */
    fun kindOf(kind: String): EngineErrorKind = when (kind) {
        "missing_so" -> EngineErrorKind.MissingSoFile
        "invalid_apk" -> EngineErrorKind.InvalidApk
        "invalid_descriptor" -> EngineErrorKind.InvalidDescriptor
        "storage_full" -> EngineErrorKind.StorageFull
        "invalid_elf" -> EngineErrorKind.InvalidElf
        "unsupported_arch" -> EngineErrorKind.UnsupportedArchitecture
        "missing_section" -> EngineErrorKind.MissingSection
        "invalid_pattern" -> EngineErrorKind.InvalidPattern
        "cancelled" -> EngineErrorKind.Cancelled
        else -> EngineErrorKind.Unknown
    }

    private fun <T> decode(json: String?, type: Class<T>, what: String): T {
        if (json.isNullOrBlank()) {
            throw EngineException(
                kind = EngineErrorKind.Unknown,
                message = "the engine returned no $what payload",
            )
        }
        return try {
            gson.fromJson(json, type)
                ?: throw EngineException(
                    kind = EngineErrorKind.Unknown,
                    message = "the engine returned an empty $what payload",
                )
        } catch (malformed: JsonSyntaxException) {
            throw EngineException(
                kind = EngineErrorKind.Unknown,
                message = "the engine returned a malformed $what payload",
                cause = malformed,
            )
        }
    }
}
