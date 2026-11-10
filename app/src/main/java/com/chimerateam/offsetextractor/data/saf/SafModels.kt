package com.chimerateam.offsetextractor.data.saf

import android.net.Uri

/**
 * Metadata about a user-selected APK.
 *
 * @property uri the SAF document URI returned by the picker.
 * @property displayName file name as reported by the document provider.
 * @property sizeBytes file size in bytes, or `-1` when the provider cannot
 *   report it.
 */
data class ApkMetadata(
    val uri: Uri,
    val displayName: String,
    val sizeBytes: Long,
)

/** Reason an APK could not be opened. */
enum class SafFailure {
    /** The provider denied read access (revoked or expired grant). */
    SecurityDenied,

    /** The document no longer exists. */
    NotFound,

    /** The provider reported an I/O problem. */
    IoError,

    /** The provider returned something that is not an APK. */
    InvalidMime,
}

/**
 * Outcome of [SafApkAccess.openApk].
 *
 * Sealed so callers must handle the failure branch; the app must never crash
 * when a provider misbehaves.
 */
sealed interface ApkOpenResult {
    /**
     * The APK is readable.
     *
     * @property handle owned descriptor; the caller must close it.
     * @property usedCacheCopy whether the descriptor points at a private cache
     *   copy because the provider could not hand out a usable one.
     */
    data class Success(
        val handle: ApkHandle,
        val usedCacheCopy: Boolean,
    ) : ApkOpenResult

    /**
     * The APK could not be opened.
     *
     * @property reason machine-readable cause.
     * @property message detail suitable for the live log.
     */
    data class Failure(
        val reason: SafFailure,
        val message: String,
    ) : ApkOpenResult
}
