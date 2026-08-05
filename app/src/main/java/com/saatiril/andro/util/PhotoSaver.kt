package com.saatiril.andro.util

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Base64
import android.util.Log

/**
 * Saves captured photos to a user-picked folder via the Android
 * Storage Access Framework (SAF).
 *
 * The output folder is chosen by the Admin through
 * [Intent.ACTION_OPEN_DOCUMENT_TREE], which returns a tree [Uri]. We
 * [persist][ContentResolver.takePersistableUriPermission] the URI
 * permission so it survives reboots, and stash the URI string in
 * SharedPreferences for later retrieval.
 *
 * All file operations go through [DocumentsContract] + the
 * [ContentResolver] — no `androidx.documentfile` dependency required.
 *
 * Each instance is bound to a [Context]; safe to instantiate per-scope
 * (e.g. inside a ViewModel).
 */
class PhotoSaver(private val context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val resolver = context.contentResolver

    /**
     * Set the output folder (a SAF tree [Uri] from [Intent.ACTION_OPEN_DOCUMENT_TREE]).
     *
     * Persists the URI permission (read + write) so the folder remains
     * accessible after process death or device reboot, then stores the
     * URI string in SharedPreferences.
     */
    fun setOutputFolder(treeUri: Uri) {
        try {
            resolver.takePersistableUriPermission(
                treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (e: SecurityException) {
            // Some providers don't support persistable permissions — log & continue.
            Log.w(TAG, "Could not take persistable URI permission: ${e.message}")
        }
        prefs.edit().putString(KEY_OUTPUT_URI, treeUri.toString()).apply()
        Log.i(TAG, "Output folder set: $treeUri")
    }

    /** Returns `true` if an output folder has been set. */
    fun hasOutputFolder(): Boolean = getOutputFolder() != null

    /** Returns the current output folder tree [Uri], or `null` if none set. */
    fun getOutputFolder(): Uri? {
        val s = prefs.getString(KEY_OUTPUT_URI, null) ?: return null
        return try {
            Uri.parse(s)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Save a photo (base64 data URL **or** raw base64) to the output folder
     * with the given [filename] (e.g. `"2024001_Budi_1_Toga.jpg"`).
     *
     * Strips a leading `data:image/jpeg;base64,` prefix if present.
     *
     * @return the [Uri] of the created file, or `null` on failure (also
     *   returns `null` if no output folder is set).
     */
    fun savePhoto(base64Data: String, filename: String): Uri? {
        val treeUri = getOutputFolder() ?: run {
            Log.w(TAG, "savePhoto: no output folder set")
            return null
        }

        val pureBase64 = if (base64Data.contains(",")) base64Data.substringAfter(",") else base64Data
        val bytes = try {
            Base64.decode(pureBase64, Base64.DEFAULT)
        } catch (e: Exception) {
            Log.e(TAG, "savePhoto: base64 decode failed — ${e.message}")
            return null
        }
        if (bytes.isEmpty()) {
            Log.w(TAG, "savePhoto: decoded bytes are empty for $filename")
            return null
        }

        return try {
            val docUri = DocumentsContract.createDocument(
                resolver,
                treeUri,
                MIME_JPEG,
                filename
            ) ?: run {
                Log.w(TAG, "savePhoto: createDocument returned null for $filename")
                return null
            }

            resolver.openOutputStream(docUri)?.use { out ->
                out.write(bytes)
                out.flush()
            } ?: run {
                Log.w(TAG, "savePhoto: could not open output stream for $docUri")
                try { resolver.delete(docUri, null, null) } catch (_: Exception) {}
                return null
            }

            Log.i(TAG, "Photo saved: $filename (${bytes.size} bytes) → $docUri")
            docUri
        } catch (e: Exception) {
            Log.e(TAG, "savePhoto failed for $filename — ${e.message}")
            null
        }
    }

    /**
     * List the filenames of all `.jpg` files in the output folder.
     * Returns an empty list if no folder is set or the folder is empty.
     */
    fun listPhotos(): List<String> {
        val treeUri = getOutputFolder() ?: return emptyList()
        val out = mutableListOf<String>()
        try {
            val parentDocId = DocumentsContract.getTreeDocumentId(treeUri)
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
            resolver.query(
                childrenUri,
                arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null, null, null
            )?.use { c ->
                while (c.moveToNext()) {
                    val name = c.getString(0) ?: continue
                    if (name.endsWith(".jpg", ignoreCase = true) ||
                        name.endsWith(".jpeg", ignoreCase = true)
                    ) {
                        out.add(name)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "listPhotos failed — ${e.message}")
        }
        return out
    }

    /** Count of `.jpg` photos in the folder. Equivalent to `listPhotos().size`. */
    fun countPhotos(): Int = listPhotos().size

    /**
     * Save a text file (e.g. CSV export) to the output folder.
     * @param content the file contents
     * @param filename e.g. "Daftar_Peserta_20260805.csv"
     * @param mimeType e.g. "text/csv"
     * @return the Uri of the created file, or null on failure
     */
    fun saveTextFile(content: String, filename: String, mimeType: String = "text/csv"): Uri? {
        val treeUri = getOutputFolder() ?: return null
        return try {
            val docUri = DocumentsContract.createDocument(context.contentResolver, treeUri, mimeType, filename) ?: return null
            context.contentResolver.openOutputStream(docUri)?.use { os ->
                os.write(content.toByteArray(Charsets.UTF_8))
            }
            docUri
        } catch (e: Exception) {
            Log.e("PhotoSaver", "saveTextFile failed: ${e.message}", e)
            null
        }
    }

    companion object {
        private const val TAG = "PhotoSaver"
        private const val PREFS_NAME = "photo_saver_prefs"
        private const val KEY_OUTPUT_URI = "output_folder_uri"
        private const val MIME_JPEG = "image/jpeg"
    }
}
