package com.chimerateam.offsetextractor.ui.state

import com.chimerateam.offsetextractor.data.saf.EngineErrorKind
import com.chimerateam.offsetextractor.data.saf.EngineException

/**
 * Whether a failed internal pass can succeed on a second attempt.
 *
 * The retry button used to be offered for every failure, including the ones
 * that are deterministic for a given APK and engine: a stale signature pack, an
 * unsupported ABI or a payload the app cannot parse will fail identically every
 * time, so "Retry" was a loop with no exit. The kind comes from the engine's own
 * classification, which is the only place that knows why the pass stopped.
 */
object InternalFailure {

    /** Kinds that describe the input or the build, not a transient condition. */
    private val deterministic: Set<EngineErrorKind> = setOf(
        EngineErrorKind.MissingSoFile,
        EngineErrorKind.InvalidApk,
        EngineErrorKind.InvalidDescriptor,
        EngineErrorKind.InvalidElf,
        EngineErrorKind.UnsupportedArchitecture,
        EngineErrorKind.MissingSection,
        EngineErrorKind.InvalidPattern,
        EngineErrorKind.Payload
    )

    /**
     * Whether [error] may go away on its own.
     *
     * @param error the failure the pass recorded, or `null` when none.
     * @return false when the failure is a property of the input or the build.
     */
    fun isRetryable(error: Throwable?): Boolean = when (error) {
        null -> false
        is EngineException -> error.kind !in deterministic
        is OutOfMemoryError -> true
        else -> true
    }

    /**
     * One line naming the reason, for the results screen.
     *
     * @param error the failure the pass recorded, or `null`.
     * @return a sentence a modder can act on.
     */
    fun describe(error: Throwable?): String? = when (error) {
        null -> null
        is EngineException -> describeKind(error.kind, error.message)
        is OutOfMemoryError -> "The device ran out of memory during the internal pass."
        else -> error.message ?: error.javaClass.simpleName
    }

    private fun describeKind(kind: EngineErrorKind, detail: String?): String {
        val reason = when (kind) {
            EngineErrorKind.OutOfMemory ->
                "The device ran out of memory during the internal pass."
            EngineErrorKind.StorageFull ->
                "The device ran out of storage while writing the offsets."
            EngineErrorKind.MissingSection ->
                "The library does not contain a section this pass needs."
            EngineErrorKind.InvalidPattern ->
                "A signature or category rule could not be compiled."
            EngineErrorKind.UnsupportedArchitecture ->
                "The library is not 64-bit AArch64."
            EngineErrorKind.InvalidElf -> "The library is not a readable ELF object."
            EngineErrorKind.Payload ->
                "The engine's reply could not be read. This is an app defect, not a bad APK."
            EngineErrorKind.Cancelled -> "The pass was cancelled."
            else -> detail ?: kind.name
        }
        return if (detail.isNullOrBlank() || detail == reason) reason else "$reason $detail"
    }
}
