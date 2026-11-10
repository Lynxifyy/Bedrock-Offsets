package com.chimerateam.offsetextractor.data.saf

import android.os.ParcelFileDescriptor
import java.io.Closeable
import java.io.File

/**
 * Owns the read-only access to a selected APK.
 *
 * The handle is the single owner of the file descriptor that is passed to
 * native code, so it must stay open for the whole extraction and be closed
 * exactly once. Closing also removes any private cache copy so a cancelled or
 * finished selection leaves no residue.
 *
 * @property metadata display information about the selected APK.
 * @property usedCacheCopy whether [rawFd] refers to a private cache copy.
 * @property lengthBytes resolved length of the backing file in bytes, or `-1`
 *   when the provider and descriptor could not report it.
 */
class ApkHandle internal constructor(
    override val metadata: ApkMetadata,
    override val usedCacheCopy: Boolean,
    override val lengthBytes: Long,
    private val descriptor: ParcelFileDescriptor,
    private val cacheFile: File?,
) : ApkSource {

    private var closed = false

    /** The integer descriptor to hand to native code via JNI. */
    override val rawFd: Int
        get() = descriptor.fd

    /**
     * Releases the descriptor and deletes any private cache copy.
     *
     * Safe to call more than once.
     */
    override fun close() {
        if (closed) return
        closed = true
        runCatching { descriptor.close() }
        cacheFile?.let { runCatching { it.delete() } }
    }
}
