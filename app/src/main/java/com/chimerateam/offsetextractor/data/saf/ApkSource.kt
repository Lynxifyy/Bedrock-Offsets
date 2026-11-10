package com.chimerateam.offsetextractor.data.saf

import java.io.Closeable

/**
 * Read-only access to a selected APK that the engine can consume.
 *
 * The ViewModel depends on this rather than on the concrete handle so it can be
 * exercised without a real content provider, while production code still owns
 * exactly one descriptor through [ApkHandle].
 */
interface ApkSource : Closeable {

    /** Display information about the selected APK. */
    val metadata: ApkMetadata

    /** The integer descriptor to hand to native code via JNI. */
    val rawFd: Int

    /** Length of the backing file in bytes, or `-1` when unknown. */
    val lengthBytes: Long

    /** Whether the descriptor refers to a private cache copy. */
    val usedCacheCopy: Boolean
}
