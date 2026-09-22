package com.saatiril.andro.data

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import java.io.File
import java.io.IOException

/**
 * Persists the project list (JSON) to internal storage so the Project Hub
 * can list/resume projects across app restarts.
 *
 * Each [Project] is stored as a single JSON file under
 * `context.filesDir/projects/<projectId>.json`. Files are tiny (a few KB
 * per project) so we keep one file per project for simple atomic
 * create / update / delete semantics.
 *
 * Uses Gson (already a dependency — see `app/build.gradle.kts`). No
 * additional AndroidX or third-party dependencies required.
 */
class ProjectStore(private val context: Context) {

    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()
    private val dir: File = File(context.filesDir, DIR_NAME)

    /**
     * Save (create or update) a [project].
     *
     * If the project has a blank `id` the call is a no-op (we cannot
     * construct a filename without an id).
     */
    fun save(project: Project) {
        if (project.id.isBlank()) {
            Log.w(TAG, "save: project.id is blank — skipping")
            return
        }
        try {
            if (!dir.exists() && !dir.mkdirs()) {
                Log.e(TAG, "save: could not create projects dir at ${dir.absolutePath}")
                return
            }
            val file = File(dir, "${project.id}.json")
            // Write to a temp file then rename for atomic-ish update.
            val tmp = File(dir, "${project.id}.json.tmp")
            tmp.writeText(gson.toJson(project), Charsets.UTF_8)
            if (!tmp.renameTo(file)) {
                // Fallback: copy then delete tmp.
                tmp.copyTo(file, overwrite = true)
                tmp.delete()
            }
            Log.d(TAG, "Saved project ${project.id} (${project.name}) → ${file.name}")
        } catch (e: IOException) {
            Log.e(TAG, "save failed for ${project.id} — ${e.message}")
        }
    }

    /**
     * Load all saved projects, sorted **newest first** by file modification time.
     * Returns an empty list if the projects directory does not exist or is empty.
     */
    fun list(): List<Project> {
        if (!dir.exists() || !dir.isDirectory) return emptyList()
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".json") }
            ?: return emptyList()
        return files
            .sortedByDescending { it.lastModified() }
            .mapNotNull { file -> loadFromFile(file) }
    }

    /**
     * Load a single project by [projectId]. Returns `null` if the project
     * file does not exist or fails to deserialize.
     */
    fun load(projectId: String): Project? {
        if (projectId.isBlank()) return null
        val file = File(dir, "$projectId.json")
        if (!file.exists()) return null
        return loadFromFile(file)
    }

    /** Delete a project by [projectId]. No-op if the project doesn't exist. */
    fun delete(projectId: String) {
        if (projectId.isBlank()) return
        val file = File(dir, "$projectId.json")
        if (file.exists()) {
            val ok = file.delete()
            if (!ok) Log.w(TAG, "delete: File.delete() returned false for ${file.absolutePath}")
        }
    }

    /** Delete all saved projects (the projects directory itself is left in place). */
    fun clear() {
        if (!dir.exists()) return
        dir.listFiles()?.forEach { f ->
            if (f.isFile) f.delete()
        }
    }

    // ──────────────────────────────────────────────────────────────────
    //  Internals
    // ──────────────────────────────────────────────────────────────────

    private fun loadFromFile(file: File): Project? {
        return try {
            val json = file.readText(Charsets.UTF_8)
            gson.fromJson(json, Project::class.java)
        } catch (e: IOException) {
            Log.e(TAG, "loadFromFile: read failed for ${file.name} — ${e.message}")
            null
        } catch (e: com.google.gson.JsonSyntaxException) {
            Log.e(TAG, "loadFromFile: JSON parse failed for ${file.name} — ${e.message}")
            null
        }
    }

    companion object {
        private const val TAG = "ProjectStore"
        private const val DIR_NAME = "projects"
    }
}
