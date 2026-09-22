package com.saatiril.andro.backup

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import java.io.InputStream
import java.io.OutputStream

/**
 * Manages Google Drive backup via Android's Storage Access Framework (SAF).
 *
 * HOW IT WORKS:
 * The admin picks a folder using [Intent.ACTION_OPEN_DOCUMENT_TREE]. If they
 * pick a folder inside Google Drive, Android returns a SAF tree URI that
 * points to the Google Drive folder. We can then use [DocumentsContract] to
 * create files inside that folder — Google Drive's document provider handles
 * the actual cloud upload (async, with built-in retry and sync).
 *
 * ADVANTAGES (vs. OAuth REST API):
 *  - No Google Cloud Project setup needed
 *  - No OAuth client ID / API key needed
 *  - No google-api-services-drive dependency (saves ~2MB APK size)
 *  - Upload happens via Google Drive app's sync mechanism (battle-tested)
 *  - Works offline (files saved to Drive app's local cache, synced when online)
 *  - Admin can pick ANY cloud provider (Drive, Dropbox, OneDrive) — not just Drive
 *
 * FLOW:
 *  1. Admin taps "Pilih Folder Google Drive" → SAF folder picker opens
 *  2. Admin navigates to Google Drive → picks/creates a folder
 *  3. We persist the URI permission (survives reboots)
 *  4. For each photo in the upload queue:
 *     a. Open input stream from local photo URI
 *     b. Create document in Drive folder via DocumentsContract.createDocument
 *     c. Open output stream, copy bytes
 *     d. Google Drive app syncs the file to cloud (async)
 */
class DriveBackupManager(private val context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val resolver: ContentResolver = context.contentResolver
    private val queueStore = UploadQueueStore(context)

    /**
     * Set the Google Drive (or any cloud) backup folder from SAF tree URI.
     * Persists URI permission so the folder remains accessible after reboot.
     */
    fun setBackupFolder(treeUri: Uri) {
        try {
            resolver.takePersistableUriPermission(
                treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (e: SecurityException) {
            Log.w(TAG, "Could not take persistable URI permission: ${e.message}")
        }
        prefs.edit().putString(KEY_DRIVE_URI, treeUri.toString()).apply()
        Log.i(TAG, "Backup folder set: $treeUri")
    }

    /** Returns true if a backup folder has been configured. */
    fun hasBackupFolder(): Boolean = getBackupFolder() != null

    /** Returns the configured backup folder tree URI, or null if not set. */
    fun getBackupFolder(): Uri? {
        val s = prefs.getString(KEY_DRIVE_URI, null) ?: return null
        return try { Uri.parse(s) } catch (_: Exception) { null }
    }

    /** Clear the backup folder (disable backup). */
    fun clearBackupFolder() {
        getBackupFolder()?.let { uri ->
            try {
                resolver.releasePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (e: Exception) {
                Log.w(TAG, "Could not release URI permission: ${e.message}")
            }
        }
        prefs.edit().remove(KEY_DRIVE_URI).apply()
        Log.i(TAG, "Backup folder cleared")
    }

    /**
     * Upload a single photo to the Google Drive backup folder.
     *
     * @param localUri The content URI of the locally-saved photo (from PhotoSaver)
     * @param filename The filename to use in the Drive folder
     * @return true if upload succeeded, false otherwise
     */
    fun uploadPhoto(localUri: String, filename: String): Boolean {
        val treeUri = getBackupFolder() ?: run {
            Log.e(TAG, "uploadPhoto FAILED: no backup folder set")
            return false
        }

        // Normalize tree URI to document URI (same fix as PhotoSaver)
        val parentDocUri = normalizeToDocumentUri(treeUri)

        return try {
            // Create the document in the Drive folder
            val docUri = DocumentsContract.createDocument(
                resolver,
                parentDocUri,
                MIME_JPEG,
                filename
            ) ?: run {
                Log.e(TAG, "uploadPhoto FAILED: createDocument returned null for '$filename'")
                return false
            }

            // Copy bytes: local photo → Drive document
            val input: InputStream = resolver.openInputStream(Uri.parse(localUri))
                ?: run {
                    Log.e(TAG, "uploadPhoto FAILED: cannot open input stream for $localUri")
                    try { resolver.delete(docUri, null, null) } catch (_: Exception) {}
                    return false
                }

            val output: OutputStream = resolver.openOutputStream(docUri)
                ?: run {
                    Log.e(TAG, "uploadPhoto FAILED: cannot open output stream for $docUri")
                    input.close()
                    try { resolver.delete(docUri, null, null) } catch (_: Exception) {}
                    return false
                }

            input.use { inp ->
                output.use { out ->
                    val buffer = ByteArray(8192)
                    var read: Int
                    while (inp.read(buffer).also { read = it } > 0) {
                        out.write(buffer, 0, read)
                    }
                    out.flush()
                }
            }

            Log.i(TAG, "uploadPhoto SUCCESS: $filename → $docUri")
            true
        } catch (e: Exception) {
            Log.e(TAG, "uploadPhoto EXCEPTION for $filename — ${e.message}", e)
            false
        }
    }

    /**
     * Enqueue a photo for upload. Called after PhotoSaver.savePhoto() succeeds.
     * The actual upload is done by [DriveUploadWorker] in the background.
     */
    fun enqueueUpload(filename: String, localUri: String, projectName: String) {
        if (!hasBackupFolder()) {
            Log.i(TAG, "enqueueUpload: no backup folder set — skipping (local save only)")
            return
        }
        queueStore.enqueue(filename, localUri, projectName)
    }

    /**
     * Process the next item in the upload queue. Called by [DriveUploadWorker].
     * @return true if an item was processed (uploaded or failed), false if queue empty
     */
    fun processNext(): Boolean {
        val item = queueStore.dequeue() ?: return false

        queueStore.markUploading(item.id)

        val success = uploadPhoto(item.localUri, item.filename)

        if (success) {
            queueStore.markDone(item.id)
            queueStore.incrementUploaded()
        } else {
            queueStore.markFailed(item.id, "Upload failed (see logs)")
        }

        return true
    }

    /** Get current upload queue statistics. */
    fun getStats(): UploadStats = queueStore.getStats()

    /** Retry all failed uploads. */
    fun retryAllFailed() = queueStore.retryAllFailed()

    /**
     * Normalize a tree URI to a document URI.
     * Same logic as PhotoSaver.normalizeToDocumentUri — needed because
     * DocumentsContract.createDocument requires a document URI.
     */
    private fun normalizeToDocumentUri(uri: Uri): Uri {
        val path = uri.path ?: return uri
        return if (path.contains("/document/")) {
            uri
        } else {
            try {
                val rootDocId = DocumentsContract.getTreeDocumentId(uri)
                DocumentsContract.buildDocumentUriUsingTree(uri, rootDocId)
            } catch (e: Exception) {
                Log.e(TAG, "normalizeToDocumentUri failed: ${e.message}")
                uri
            }
        }
    }

    companion object {
        private const val TAG = "DriveBackupMgr"
        private const val PREFS_NAME = "drive_backup_prefs"
        private const val KEY_DRIVE_URI = "backup_folder_uri"
        private const val MIME_JPEG = "image/jpeg"
    }
}
