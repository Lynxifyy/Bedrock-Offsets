package com.chimerateam.offsetextractor.ui.error

import com.chimerateam.offsetextractor.data.saf.EngineErrorKind
import com.chimerateam.offsetextractor.data.saf.EngineException
import com.chimerateam.offsetextractor.data.saf.SafFailure
import java.io.FileNotFoundException
import java.io.IOException
import java.util.zip.ZipException

/**
 * A failure translated for display.
 *
 * @property message primary explanation shown to the user.
 * @property hint actionable follow-up, or `null` when none applies.
 */
data class UserFacingError(
    val message: String,
    val hint: String?,
)

/**
 * Translates raw exceptions into messages a user can act on.
 *
 * Every branch is fail-closed: an unrecognised throwable still yields a usable
 * message rather than propagating.
 */
object ErrorMapper {

    /**
     * Maps a throwable raised while opening or scanning an APK.
     *
     * @param throwable the failure to translate.
     * @param displayName name of the APK involved, when known.
     * @return a message and optional hint for the UI.
     */
    fun fromThrowable(throwable: Throwable, displayName: String? = null): UserFacingError {
        val subject = displayName?.let { "\"$it\"" } ?: "the selected APK"
        return when (throwable) {
            is EngineException -> fromEngine(throwable, subject)

            is ZipException -> UserFacingError(
                message = "$subject is not a valid APK archive.",
                hint = "Pick a file that ends in .apk and was installed by Android.",
            )

            is FileNotFoundException -> UserFacingError(
                message = "Android could not find $subject.",
                hint = "The file may have been moved or deleted since it was selected.",
            )

            is SecurityException -> UserFacingError(
                message = "Read access to $subject was revoked.",
                hint = "Select the APK again to grant a fresh permission.",
            )

            is OutOfMemoryError -> UserFacingError(
                message = "The scan ran out of memory.",
                hint = "Close other apps and try again; extraction itself is streaming.",
            )

            is IOException -> UserFacingError(
                message = "Reading $subject failed: ${throwable.message ?: "I/O error"}.",
                hint = "Check that there is free storage space and try again.",
            )

            else -> UserFacingError(
                message = "Unexpected failure while handling $subject: " +
                    "${throwable.message ?: throwable::class.java.simpleName}.",
                hint = "Try again, and report this message if it persists.",
            )
        }
    }

    /**
     * Maps a typed engine failure.
     *
     * @param exception the engine failure.
     * @param subject display name of the APK involved.
     * @return a message and optional hint for the UI.
     */
    fun fromEngine(exception: EngineException, subject: String): UserFacingError =
        when (exception.kind) {
            EngineErrorKind.MissingSoFile -> UserFacingError(
                message = "$subject does not contain libminecraftpe.so.",
                hint = "This looks like the Java Edition or a bundle without " +
                    "arm64-v8a natives.",
            )

            EngineErrorKind.InvalidApk -> UserFacingError(
                message = "$subject could not be parsed as an APK.",
                hint = "The archive may be truncated or corrupted.",
            )

            EngineErrorKind.InvalidDescriptor -> UserFacingError(
                message = "Android refused to hand over a readable descriptor.",
                hint = "Select the APK again; the previous permission may have expired.",
            )

            EngineErrorKind.StorageFull -> UserFacingError(
                message = "There is not enough free storage to extract the library.",
                hint = "Free some space and retry.",
            )

            EngineErrorKind.InvalidElf -> UserFacingError(
                message = "$subject is not a valid ELF shared object.",
                hint = "The file inside the APK may be corrupted or truncated.",
            )

            EngineErrorKind.UnsupportedArchitecture -> UserFacingError(
                message = "Only arm64-v8a is supported.",
                hint = "This APK ships a different architecture, such as 32-bit ARM or x86.",
            )

            EngineErrorKind.MissingSection -> UserFacingError(
                message = "A required section is missing from the native library.",
                hint = "The library looks stripped or corrupted.",
            )

            EngineErrorKind.InvalidPattern -> UserFacingError(
                message = "A signature pattern could not be compiled.",
                hint = "Check patterns.json for malformed wildcard syntax.",
            )

            EngineErrorKind.Cancelled -> UserFacingError(
                message = "The scan was cancelled.",
                hint = null,
            )

            EngineErrorKind.Unknown -> UserFacingError(
                message = exception.message ?: "The scan engine reported an unknown failure.",
                hint = "Try again, and report this message if it persists.",
            )
        }

    /**
     * Maps a failure reported by the storage-access layer.
     *
     * @param failure the typed SAF failure.
     * @param displayName name of the APK involved, when known.
     * @return a message and optional hint for the UI.
     */
    fun fromSaf(failure: SafFailure, displayName: String? = null): UserFacingError {
        val subject = displayName?.let { "\"$it\"" } ?: "the selected APK"
        return when (failure) {
            SafFailure.SecurityDenied -> UserFacingError(
                message = "Android denied read access to $subject.",
                hint = "Select the APK again to grant a fresh permission.",
            )

            SafFailure.NotFound -> UserFacingError(
                message = "$subject could no longer be found.",
                hint = "It may have been moved or deleted since it was selected.",
            )

            SafFailure.IoError -> UserFacingError(
                message = "Reading $subject failed.",
                hint = "Check that there is free storage space and try again.",
            )

            SafFailure.InvalidMime -> UserFacingError(
                message = "$subject is not an APK.",
                hint = "Choose a file with the .apk extension.",
            )
        }
    }
}
