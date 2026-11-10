package com.chimerateam.offsetextractor.data.saf

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import java.io.IOException

/** Outcome of writing a document through the Storage Access Framework. */
sealed interface WriteResult {
    /** The document was written and closed. */
    data object Success : WriteResult

    /**
     * The document could not be written.
     *
     * @property message detail suitable for the live log.
     */
    data class Failure(val message: String) : WriteResult
}

/**
 * Writes text to a document chosen through the Storage Access Framework.
 *
 * Implemented as an interface so the ViewModel can be tested without a real
 * content provider.
 */
fun interface DocumentWriter {

    /**
     * Replaces the contents of [uri] with [text].
     *
     * The stream is always closed, including when the write fails.
     *
     * @param uri destination document returned by the create-document picker.
     * @param text the full document body.
     * @return whether the document was written.
     */
    fun writeText(uri: Uri, text: String): WriteResult
}

/**
 * [DocumentWriter] backed by the platform [ContentResolver].
 *
 * @param context any context; only the application context is retained.
 */
class AndroidDocumentWriter(context: Context) : DocumentWriter {

    private val resolver: ContentResolver = context.applicationContext.contentResolver

    override fun writeText(uri: Uri, text: String): WriteResult {
        return try {
            // "wt" truncates, so re-saving over an existing file replaces it
            // rather than appending a second document to the first.
            val stream = resolver.openOutputStream(uri, "wt")
                ?: return WriteResult.Failure("The provider returned no output stream.")
            stream.bufferedWriter().use { writer -> writer.write(text) }
            WriteResult.Success
        } catch (security: SecurityException) {
            WriteResult.Failure("Write access was denied for the chosen location.")
        } catch (io: IOException) {
            WriteResult.Failure("Could not write the file: ${io.message ?: "I/O error"}")
        } catch (illegal: IllegalArgumentException) {
            WriteResult.Failure("The chosen location is not writable.")
        }
    }
}
