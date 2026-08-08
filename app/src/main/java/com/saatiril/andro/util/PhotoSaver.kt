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
        Log.i(TAG, "savePhoto START: filename=$filename, base64Length=${base64Data.length}")

        val treeUri = getOutputFolder() ?: run {
            Log.e(TAG, "savePhoto FAILED: no output folder set (getOutputFolder returned null)")
            return null
        }
        Log.i(TAG, "savePhoto: treeUri=$treeUri")

        // Verify we still have permission to write to this folder
        val perms = resolver.persistedUriPermissions
        val hasWrite = perms.any { it.uri == treeUri && it.isWritePermission }
        if (!hasWrite) {
            Log.w(TAG, "savePhoto: no persisted write permission for $treeUri — trying anyway")
        }

        val pureBase64 = if (base64Data.contains(",")) base64Data.substringAfter(",") else base64Data
        Log.i(TAG, "savePhoto: pureBase64 length=${pureBase64.length}")

        val bytes = try {
            Base64.decode(pureBase64, Base64.DEFAULT)
        } catch (e: Exception) {
            Log.e(TAG, "savePhoto FAILED: base64 decode error — ${e.message}")
            return null
        }
        Log.i(TAG, "savePhoto: decoded ${bytes.size} bytes")

        if (bytes.isEmpty()) {
            Log.e(TAG, "savePhoto FAILED: decoded bytes are empty")
            return null
        }

        return try {
            Log.i(TAG, "savePhoto: calling DocumentsContract.createDocument(treeUri=$treeUri, mime=$MIME_JPEG, name=$filename)")
            val docUri = DocumentsContract.createDocument(
                resolver,
                treeUri,
                MIME_JPEG,
                filename
            ) ?: run {
                Log.e(TAG, "savePhoto FAILED: createDocument returned null for '$filename'")
                // Try listing what's in the folder to verify access
                try {
                    val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, null)
                    val cursor = resolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)
                    val count = cursor?.count ?: -1
                    cursor?.close()
                    Log.i(TAG, "savePhoto: folder contains $count existing documents")
                } catch (e2: Exception) {
                    Log.e(TAG, "savePhoto: cannot list folder contents — ${e2.message}")
                }
                return null
            }
            Log.i(TAG, "savePhoto: createDocument returned $docUri")

            val stream = resolver.openOutputStream(docUri)
            if (stream == null) {
                Log.e(TAG, "savePhoto FAILED: openOutputStream returned null for $docUri")
                try { resolver.delete(docUri, null, null) } catch (_: Exception) {}
                return null
            }
            stream.use { out ->
                out.write(bytes)
                out.flush()
                Log.i(TAG, "savePhoto: wrote ${bytes.size} bytes to output stream")
            }
            Log.i(TAG, "savePhoto SUCCESS: $filename (${bytes.size} bytes) → $docUri")
            docUri
        } catch (e: Exception) {
            Log.e(TAG, "savePhoto EXCEPTION for $filename — ${e.javaClass.simpleName}: ${e.message}", e)
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

    /**
     * Create a subfolder inside the given tree URI.
     * Matches Electron's `createFolder(targetFolder)` which uses `fs.mkdirSync(path, {recursive:true})`.
     *
     * On Android SAF, we use `DocumentsContract.createDocument` with MIME type
     * `vnd.android.document/directory` to create a folder inside the tree.
     *
     * @param treeUri The parent folder's tree URI
     * @param folderName The name for the new subfolder (e.g. "Wisuda_2026")
     * @return The tree URI of the new subfolder, or null on failure
     */
    fun createSubfolder(treeUri: Uri, folderName: String): Uri? {
        Log.i(TAG, "createSubfolder: parent=$treeUri, name=$folderName")
        return try {
            // Check if folder already exists (e.g. resuming a project)
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, null)
            val cursor = resolver.query(
                childrenUri,
                arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE),
                null, null, null
            )
            cursor?.use { c ->
                while (c.moveToNext()) {
                    val name = c.getString(1)
                    val mime = c.getString(2)
                    if (name == folderName && mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                        val docId = c.getString(0)
                        val subTreeUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                        Log.i(TAG, "createSubfolder: folder already exists → $subTreeUri")
                        return subTreeUri
                    }
                }
            }

            // Create new folder
            val docUri = DocumentsContract.createDocument(
                resolver,
                treeUri,
                DocumentsContract.Document.MIME_TYPE_DIR,  // "vnd.android.document/directory"
                folderName
            )

            if (docUri == null) {
                Log.e(TAG, "createSubfolder FAILED: createDocument returned null for '$folderName'")
                return null
            }

            // Convert the document URI to a tree URI so we can use it as a new output folder
            val docId = DocumentsContract.getDocumentId(docUri)
            val subTreeUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)

            Log.i(TAG, "createSubfolder SUCCESS: $folderName → $subTreeUri")
            subTreeUri
        } catch (e: Exception) {
            Log.e(TAG, "createSubfolder FAILED: ${e.message}", e)
            null
        }
    }

    /**
     * Save a binary file (e.g. .xlsx Excel export) to the output folder.
     * @param bytes the file contents as a byte array
     * @param filename e.g. "Daftar_Peserta_20260805.xlsx"
     * @param mimeType e.g. "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
     * @return the Uri of the created file, or null on failure
     */
    fun saveBinaryFile(bytes: ByteArray, filename: String, mimeType: String): Uri? {
        val treeUri = getOutputFolder() ?: return null
        return try {
            val docUri = DocumentsContract.createDocument(context.contentResolver, treeUri, mimeType, filename) ?: return null
            context.contentResolver.openOutputStream(docUri)?.use { os ->
                os.write(bytes)
            }
            docUri
        } catch (e: Exception) {
            Log.e("PhotoSaver", "saveBinaryFile failed: ${e.message}", e)
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
