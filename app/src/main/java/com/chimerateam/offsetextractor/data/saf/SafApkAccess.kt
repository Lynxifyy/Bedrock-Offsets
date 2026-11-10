package com.chimerateam.offsetextractor.data.saf

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID

/** MIME type of an Android application package. */
const val APK_MIME_TYPE = "application/vnd.android.package-archive"

/**
 * Opens APKs chosen through the Storage Access Framework.
 *
 * Implemented as an interface so the ViewModel can be tested without a real
 * content provider.
 */
interface SafApkAccess {

    /**
     * Reads display metadata for [uri].
     *
     * Never throws; unknown values fall back to sensible defaults.
     *
     * @param uri document URI returned by the picker.
     * @return best-effort metadata for the document.
     */
    fun queryMetadata(uri: Uri): ApkMetadata

    /**
     * Opens [uri] for zero-copy native reading.
     *
     * Prefers [ContentResolver.openFileDescriptor]. Only when the provider
     * cannot supply a usable descriptor is the APK streamed into the private
     * cache directory as a fallback, using a fixed-size buffer so a 2GB APK
     * never lands in memory.
     *
     * @param uri document URI returned by the picker.
     * @return a success carrying an owned [ApkHandle], or a typed failure.
     */
    fun openApk(uri: Uri): ApkOpenResult
}

/**
 * [SafApkAccess] backed by the platform [ContentResolver].
 *
 * @param context any context; only the application context is retained, so no
 *   Activity is leaked.
 */
class AndroidSafApkAccess(context: Context) : SafApkAccess {

    private val appContext = context.applicationContext
    private val resolver: ContentResolver = appContext.contentResolver

    override fun queryMetadata(uri: Uri): ApkMetadata {
        var name: String? = null
        var size = -1L

        runCatching {
            resolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex >= 0 && !cursor.isNull(nameIndex)) {
                        name = cursor.getString(nameIndex)
                    }
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                        size = cursor.getLong(sizeIndex)
                    }
                }
            }
        }

        val displayName = name
            ?: DocumentFile.fromSingleUri(appContext, uri)?.name
            ?: uri.lastPathSegment
            ?: "unknown.apk"

        return ApkMetadata(uri = uri, displayName = displayName, sizeBytes = size)
    }

    override fun openApk(uri: Uri): ApkOpenResult {
        val metadata = queryMetadata(uri)

        openDirectDescriptor(uri)?.let { descriptor ->
            val size = metadata.sizeBytes.takeIf { it >= 0 }
                ?: runCatching { descriptor.statSize }.getOrDefault(-1L)
            return ApkOpenResult.Success(
                handle = ApkHandle(
                    metadata = metadata,
                    usedCacheCopy = false,
                    lengthBytes = size,
                    descriptor = descriptor,
                    cacheFile = null
                ),
                usedCacheCopy = false
            )
        }

        return copyToCache(uri, metadata)
    }

    /** Tries the direct, zero-copy path. Returns `null` when it is unusable. */
    private fun openDirectDescriptor(uri: Uri): ParcelFileDescriptor? {
        return try {
            resolver.openFileDescriptor(uri, "r")
        } catch (security: SecurityException) {
            null
        } catch (io: IOException) {
            null
        } catch (illegal: IllegalArgumentException) {
            null
        }
    }

    /**
     * Fallback: stream the APK into the private cache directory.
     *
     * Only the fixed [COPY_BUFFER_BYTES] buffer is held in memory regardless of
     * the source size.
     */
    private fun copyToCache(uri: Uri, metadata: ApkMetadata): ApkOpenResult {
        val target = File(appContext.cacheDir, "apk-${UUID.randomUUID()}.apk")
        try {
            resolver.openInputStream(uri).use { input ->
                if (input == null) {
                    return ApkOpenResult.Failure(
                        SafFailure.NotFound,
                        "Provider returned no stream for ${metadata.displayName}"
                    )
                }
                FileOutputStream(target).use { output ->
                    val buffer = ByteArray(COPY_BUFFER_BYTES)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                    }
                    output.flush()
                }
            }
        } catch (security: SecurityException) {
            target.delete()
            return ApkOpenResult.Failure(
                SafFailure.SecurityDenied,
                "Read access to ${metadata.displayName} was denied"
            )
        } catch (io: IOException) {
            target.delete()
            return ApkOpenResult.Failure(
                SafFailure.IoError,
                "Could not copy ${metadata.displayName}: ${io.message ?: "I/O error"}"
            )
        }

        val descriptor = try {
            ParcelFileDescriptor.open(target, ParcelFileDescriptor.MODE_READ_ONLY)
        } catch (io: IOException) {
            target.delete()
            return ApkOpenResult.Failure(
                SafFailure.IoError,
                "Could not reopen cache copy of ${metadata.displayName}"
            )
        }

        val cachedMetadata = metadata.copy(sizeBytes = target.length())
        return ApkOpenResult.Success(
            handle = ApkHandle(
                metadata = cachedMetadata,
                usedCacheCopy = true,
                lengthBytes = target.length(),
                descriptor = descriptor,
                cacheFile = target
            ),
            usedCacheCopy = true
        )
    }

    private companion object {
        /** Streaming buffer for the cache fallback; never grows with file size. */
        const val COPY_BUFFER_BYTES = 64 * 1024
    }
}
