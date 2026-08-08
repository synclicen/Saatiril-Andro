package com.saatiril.andro.backup

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log

/**
 * SQLite-backed upload queue for Google Drive backup.
 *
 * Tracks the upload status of every photo that needs to be backed up to
 * Google Drive. Photos are enqueued immediately after being saved to the
 * local folder. A background worker ([DriveUploadWorker]) dequeues items
 * and uploads them.
 *
 * If the upload fails (e.g. no internet, Google Drive not synced), the item
 * stays in the queue with an incremented retry count. The worker will try
 * again later.
 *
 * States:
 *  - PENDING: queued, waiting for upload
 *  - UPLOADING: currently being uploaded
 *  - DONE: uploaded successfully (removed from queue)
 *  - FAILED: exceeded max retries (stays in queue for manual retry)
 */
class UploadQueueStore(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

    companion object {
        private const val TAG = "UploadQueueStore"
        private const val DB_NAME = "saatiril_upload_queue.db"
        private const val DB_VERSION = 1

        private const val TABLE = "upload_queue"
        private const val COL_ID = "id"
        private const val COL_FILENAME = "filename"
        private const val COL_LOCAL_URI = "local_uri"
        private const val COL_PROJECT_NAME = "project_name"
        private const val COL_STATUS = "status"
        private const val COL_RETRY_COUNT = "retry_count"
        private const val COL_CREATED_AT = "created_at"
        private const val COL_UPLOADED_AT = "uploaded_at"
        private const val COL_ERROR = "error"

        const val STATUS_PENDING = "pending"
        const val STATUS_UPLOADING = "uploading"
        const val STATUS_DONE = "done"
        const val STATUS_FAILED = "failed"

        private const val MAX_RETRIES = 5
    }

    init {
        // Create the table on first access
        readableDatabase
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE $TABLE (
                $COL_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_FILENAME TEXT NOT NULL,
                $COL_LOCAL_URI TEXT NOT NULL,
                $COL_PROJECT_NAME TEXT NOT NULL,
                $COL_STATUS TEXT NOT NULL DEFAULT '$STATUS_PENDING',
                $COL_RETRY_COUNT INTEGER NOT NULL DEFAULT 0,
                $COL_CREATED_AT INTEGER NOT NULL,
                $COL_UPLOADED_AT INTEGER,
                $COL_ERROR TEXT
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX idx_status ON $TABLE ($COL_STATUS)")
        db.execSQL("CREATE INDEX idx_project ON $TABLE ($COL_PROJECT_NAME)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE")
        onCreate(db)
    }

    /**
     * Add a photo to the upload queue.
     * Called immediately after [com.saatiril.andro.util.PhotoSaver.savePhoto] succeeds.
     */
    fun enqueue(filename: String, localUri: String, projectName: String): Long {
        val values = ContentValues().apply {
            put(COL_FILENAME, filename)
            put(COL_LOCAL_URI, localUri)
            put(COL_PROJECT_NAME, projectName)
            put(COL_STATUS, STATUS_PENDING)
            put(COL_RETRY_COUNT, 0)
            put(COL_CREATED_AT, System.currentTimeMillis())
        }
        val id = writableDatabase.insertWithOnConflict(TABLE, null, values, SQLiteDatabase.CONFLICT_REPLACE)
        Log.i(TAG, "Enqueued upload: $filename (id=$id)")
        return id
    }

    /**
     * Get the next pending item to upload (FIFO order).
     * Returns null if queue is empty.
     */
    fun dequeue(): UploadItem? {
        val cursor = readableDatabase.query(
            TABLE, null,
            "$COL_STATUS = ?",
            arrayOf(STATUS_PENDING),
            null, null,
            "$COL_ID ASC",
            "1"
        )
        return cursor.use { c ->
            if (c.moveToFirst()) cursorToItem(c) else null
        }
    }

    /** Mark an item as currently uploading. */
    fun markUploading(id: Long) {
        val values = ContentValues().apply { put(COL_STATUS, STATUS_UPLOADING) }
        writableDatabase.update(TABLE, values, "$COL_ID = ?", arrayOf(id.toString()))
    }

    /** Mark an item as successfully uploaded — removes from queue. */
    fun markDone(id: Long) {
        val values = ContentValues().apply {
            put(COL_STATUS, STATUS_DONE)
            put(COL_UPLOADED_AT, System.currentTimeMillis())
        }
        writableDatabase.delete(TABLE, "$COL_ID = ?", arrayOf(id.toString()))
        Log.i(TAG, "Upload done: id=$id")
    }

    /** Mark an item as failed, increment retry count. If max retries exceeded, mark as FAILED. */
    fun markFailed(id: Long, error: String) {
        val cursor = readableDatabase.query(
            TABLE, arrayOf(COL_RETRY_COUNT),
            "$COL_ID = ?", arrayOf(id.toString()),
            null, null, null
        )
        val retryCount = cursor.use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }

        if (retryCount + 1 >= MAX_RETRIES) {
            val values = ContentValues().apply {
                put(COL_STATUS, STATUS_FAILED)
                put(COL_RETRY_COUNT, retryCount + 1)
                put(COL_ERROR, error)
            }
            writableDatabase.update(TABLE, values, "$COL_ID = ?", arrayOf(id.toString()))
            Log.w(TAG, "Upload FAILED permanently (max retries): id=$id, error=$error")
        } else {
            val values = ContentValues().apply {
                put(COL_STATUS, STATUS_PENDING)
                put(COL_RETRY_COUNT, retryCount + 1)
                put(COL_ERROR, error)
            }
            writableDatabase.update(TABLE, values, "$COL_ID = ?", arrayOf(id.toString()))
            Log.w(TAG, "Upload failed (retry ${retryCount + 1}/$MAX_RETRIES): id=$id, error=$error")
        }
    }

    /** Get queue statistics for UI display. */
    fun getStats(): UploadStats {
        val pending = countByStatus(STATUS_PENDING)
        val uploading = countByStatus(STATUS_UPLOADING)
        val failed = countByStatus(STATUS_FAILED)
        val totalUploaded = getTotalUploaded()
        return UploadStats(pending = pending, uploading = uploading, failed = failed, totalUploaded = totalUploaded)
    }

    private fun countByStatus(status: String): Int {
        val cursor = readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM $TABLE WHERE $COL_STATUS = ?",
            arrayOf(status)
        )
        return cursor.use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
    }

    private fun getTotalUploaded(): Int {
        // Track in a separate counter table for simplicity
        val cursor = readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM upload_counter WHERE type = 'uploaded'", null
        )
        return cursor.use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
    }

    /** Increment the uploaded counter (called when an item is marked done). */
    fun incrementUploaded() {
        try {
            writableDatabase.execSQL("""
                CREATE TABLE IF NOT EXISTS upload_counter (
                    type TEXT PRIMARY KEY,
                    count INTEGER NOT NULL DEFAULT 0
                )
            """.trimIndent())
            writableDatabase.execSQL(
                "INSERT OR REPLACE INTO upload_counter (type, count) VALUES ('uploaded', COALESCE((SELECT count FROM upload_counter WHERE type='uploaded'), 0) + 1)"
            )
        } catch (e: Exception) {
            Log.w(TAG, "incrementUploaded error: ${e.message}")
        }
    }

    /** Reset the uploaded counter (e.g. when starting a new project). */
    fun resetCounter() {
        try {
            writableDatabase.execSQL("DROP TABLE IF EXISTS upload_counter")
        } catch (_: Exception) {}
    }

    /** Retry all FAILED items. */
    fun retryAllFailed() {
        val values = ContentValues().apply {
            put(COL_STATUS, STATUS_PENDING)
            put(COL_RETRY_COUNT, 0)
            put(COL_ERROR, null as String?)
        }
        val count = writableDatabase.update(TABLE, values, "$COL_STATUS = ?", arrayOf(STATUS_FAILED))
        Log.i(TAG, "Retried $count failed uploads")
    }

    /** Clear all items for a specific project. */
    fun clearProject(projectName: String) {
        val count = writableDatabase.delete(TABLE, "$COL_PROJECT_NAME = ?", arrayOf(projectName))
        Log.i(TAG, "Cleared $count items for project '$projectName'")
    }

    private fun cursorToItem(c: android.database.Cursor): UploadItem {
        return UploadItem(
            id = c.getLong(c.getColumnIndexOrThrow(COL_ID)),
            filename = c.getString(c.getColumnIndexOrThrow(COL_FILENAME)),
            localUri = c.getString(c.getColumnIndexOrThrow(COL_LOCAL_URI)),
            projectName = c.getString(c.getColumnIndexOrThrow(COL_PROJECT_NAME)),
            status = c.getString(c.getColumnIndexOrThrow(COL_STATUS)),
            retryCount = c.getInt(c.getColumnIndexOrThrow(COL_RETRY_COUNT)),
            createdAt = c.getLong(c.getColumnIndexOrThrow(COL_CREATED_AT))
        )
    }
}

data class UploadItem(
    val id: Long,
    val filename: String,
    val localUri: String,
    val projectName: String,
    val status: String,
    val retryCount: Int,
    val createdAt: Long
)

data class UploadStats(
    val pending: Int,
    val uploading: Int,
    val failed: Int,
    val totalUploaded: Int
) {
    val total: Int get() = pending + uploading + failed + totalUploaded
}
