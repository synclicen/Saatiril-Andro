package com.saatiril.andro.data

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.saatiril.andro.server.ClientInfo
import com.saatiril.andro.server.SaatirilServer
import com.saatiril.andro.server.ServerService
import com.saatiril.andro.server.ServerStats
import com.saatiril.andro.util.ExcelImporter
import com.saatiril.andro.util.PhotoSaver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ═════════════════════════════════════════════════════════════════════════
 * AdminViewModel — orchestrator for the Saatiril Android Admin app.
 * ═════════════════════════════════════════════════════════════════════════
 *
 * This is the Android equivalent of the Electron `main.ts` + the React
 * `main-app.tsx`/`project-setup.tsx`/`admin-dashboard.tsx` combined. It:
 *
 *  - Enforces the offline license ([LicenseManager]).
 *  - Manages the project list + current project ([ProjectStore]).
 *  - Drives project setup (name, mode, ratio, Excel import, output folder,
 *    session password).
 *  - Starts/stops the LAN Socket.io server ([SaatirilServer] via
 *    [ServerService] foreground service).
 *  - Handles incoming lan-messages: saves photos ([PhotoSaver]), updates the
 *    project DB, and responds to REQUEST_STATE / REQUEST_FRAME.
 *  - Exposes MC actions (callStudent / resetStudent / markStudentDone) which
 *    broadcast to operators via the server.
 *  - Drives top-level navigation (LICENSE → HUB → SETUP → MAIN).
 */
class AdminViewModel(application: Application) : AndroidViewModel(application) {

    companion object { private const val TAG = "AdminViewModel" }

    private val app = application
    private val gson = Gson()

    // ─── Sub-systems ────────────────────────────────────────────
    private val licenseManager = LicenseManager(application)
    private val projectStore = ProjectStore(application)
    val photoSaver = PhotoSaver(application)

    // ─── License ────────────────────────────────────────────────
    private val _licenseStatus = MutableStateFlow(licenseManager.getStatus())
    val licenseStatus: StateFlow<LicenseStatus> = _licenseStatus.asStateFlow()

    fun activateLicense(code: String): Boolean {
        val ok = licenseManager.activate(code.trim())
        if (ok) {
            _licenseStatus.value = licenseManager.getStatus()
            _screen.value = Screen.HUB
        }
        return ok
    }

    fun deactivateLicense() {
        licenseManager.deactivate()
        _licenseStatus.value = licenseManager.getStatus()
        _screen.value = Screen.LICENSE
    }

    // ─── Navigation ─────────────────────────────────────────────
    enum class Screen { LICENSE, HUB, SETUP, MAIN, GENERATOR }

    private val _screen = MutableStateFlow(
        if (licenseManager.getStatus().active) Screen.HUB else Screen.LICENSE
    )
    val screen: StateFlow<Screen> = _screen.asStateFlow()

    /** Open the developer license-code generator screen. */
    fun openGenerator() { _screen.value = Screen.GENERATOR }

    /** Close the generator — return to the previous sensible screen. */
    fun closeGenerator() {
        _screen.value = if (licenseManager.getStatus().active) Screen.HUB else Screen.LICENSE
    }

    /**
     * Generate a 30-day license code for a customer's Machine ID, authorized
     * by the developer Admin Key. Returns the full [LicenseManager.GenerateResult]
     * (code + expiry + display fields) or null on invalid input / wrong admin key.
     */
    fun generateLicenseCode(machineId: String, adminKey: String): LicenseManager.GenerateResult? {
        return licenseManager.generateLicenseFull(machineId.trim(), adminKey.trim())
    }

    // ─── Saved projects (Hub) ───────────────────────────────────
    private val _savedProjects = MutableStateFlow<List<Project>>(emptyList())
    val savedProjects: StateFlow<List<Project>> = _savedProjects.asStateFlow()

    fun refreshProjects() {
        _savedProjects.value = projectStore.list()
    }

    fun deleteProject(id: String) {
        projectStore.delete(id)
        refreshProjects()
    }

    fun openHub() { refreshProjects(); _screen.value = Screen.HUB }

    // ─── Project Setup state ────────────────────────────────────
    private val _setupName = MutableStateFlow("Wisuda " + android.text.format.DateFormat.format("yyyy", System.currentTimeMillis()))
    val setupName: StateFlow<String> = _setupName.asStateFlow()
    fun setSetupName(v: String) { _setupName.value = v }

    private val _setupMode = MutableStateFlow(CameraModes.SINGLE)
    val setupMode: StateFlow<String> = _setupMode.asStateFlow()
    fun setSetupMode(v: String) { _setupMode.value = v }

    private val _setupRatio = MutableStateFlow("4:3")
    val setupRatio: StateFlow<String> = _setupRatio.asStateFlow()
    fun setSetupRatio(v: String) { _setupRatio.value = v }

    private val _setupPreset = MutableStateFlow("original")
    val setupPreset: StateFlow<String> = _setupPreset.asStateFlow()
    fun setSetupPreset(v: String) { _setupPreset.value = v }

    private val _setupPassword = MutableStateFlow<String?>(null)
    val setupPassword: StateFlow<String?> = _setupPassword.asStateFlow()
    fun setSetupPassword(v: String?) { _setupPassword.value = v?.takeIf { it.isNotBlank() } }

    private val _setupStudents = MutableStateFlow<List<Student>>(emptyList())
    val setupStudents: StateFlow<List<Student>> = _setupStudents.asStateFlow()

    private val _setupOutputFolderUri = MutableStateFlow<String?>(photoSaver.getOutputFolder()?.toString())
    val setupOutputFolderUri: StateFlow<String?> = _setupOutputFolderUri.asStateFlow()

    private val _importStatus = MutableStateFlow<String?>(null)
    val importStatus: StateFlow<String?> = _importStatus.asStateFlow()

    /** Begin a new project (resets setup state). */
    fun startNewProjectSetup() {
        _setupName.value = "Wisuda " + android.text.format.DateFormat.format("yyyy", System.currentTimeMillis())
        _setupMode.value = CameraModes.SINGLE
        _setupRatio.value = "4:3"
        _setupPreset.value = "original"
        _setupPassword.value = null
        _setupStudents.value = emptyList()
        _importStatus.value = null
        _editingProjectId.value = null
        _screen.value = Screen.SETUP
    }

    /** Edit an existing saved project (resume). */
    fun editProject(id: String) {
        val p = projectStore.load(id) ?: return
        _editingProjectId.value = p.id
        _setupName.value = p.name
        _setupMode.value = p.config.mode
        _setupRatio.value = p.config.ratio
        _setupPreset.value = p.config.preset
        _setupPassword.value = p.config.sessionPassword?.takeIf { it != "__PASSWORD_SET__" }
        _setupStudents.value = p.database
        _importStatus.value = "${p.database.size} mahasiswa dimuat"
        _screen.value = Screen.SETUP
    }
    private val _editingProjectId = MutableStateFlow<String?>(null)

    /** Import students from an Excel (.xlsx) or CSV file at the given Uri. */
    fun importExcel(uri: Uri) {
        _importStatus.value = "Mengimpor…"
        viewModelScope.launch {
            try {
                val students = withContext(Dispatchers.IO) { ExcelImporter.import(app, uri) }
                _setupStudents.value = students
                _importStatus.value = "Berhasil: ${students.size} mahasiswa"
            } catch (e: Exception) {
                Log.e(TAG, "Excel import failed", e)
                _importStatus.value = "Gagal impor: ${e.message}"
            }
        }
    }

    /** Set the SAF output folder (from ACTION_OPEN_DOCUMENT_TREE). */
    fun pickOutputFolder(treeUri: Uri) {
        photoSaver.setOutputFolder(treeUri)
        _setupOutputFolderUri.value = treeUri.toString()
    }

    // ─── Current (running) project ──────────────────────────────
    private val _project = MutableStateFlow<Project?>(null)
    val project: StateFlow<Project?> = _project.asStateFlow()

    /** Startup error message (shown on the Setup screen if the server fails to start). */
    private val _startupError = MutableStateFlow<String?>(null)
    val startupError: StateFlow<String?> = _startupError.asStateFlow()

    /** True while the server is starting (disables the Start button). */
    private val _starting = MutableStateFlow(false)
    val starting: StateFlow<Boolean> = _starting.asStateFlow()

    /**
     * Create the project from setup state, persist it, start the server, and
     * move to MAIN. This is the "Start" button on the Setup screen.
     *
     * Wrapped in comprehensive error handling: if the foreground service or the
     * ktor server fails to start, the error is surfaced to the user via
     * [startupError] instead of crashing the app.
     */
    fun createAndStartProject() {
        if (_starting.value) return  // prevent double-tap
        Log.i(TAG, "createAndStartProject called — starting server initialization")
        _starting.value = true
        _startupError.value = null

        viewModelScope.launch {
            try {
                val name = _setupName.value.trim().ifEmpty { "Proyek Wisuda" }
                Log.i(TAG, "Project: name='$name', students=${_setupStudents.value.size}, mode=${_setupMode.value}")
                val students = _setupStudents.value
                val password = _setupPassword.value

                val projectId = _editingProjectId.value ?: java.util.UUID.randomUUID().toString()
                val project = Project(
                    id = projectId,
                    name = name,
                    config = ProjectConfig(
                        mode = _setupMode.value,
                        ratio = _setupRatio.value,
                        preset = _setupPreset.value,
                        targetFolder = _setupOutputFolderUri.value ?: "",
                        frame = null,
                        sessionPassword = password,
                        localFolder = ""
                    ),
                    database = students,
                    photoHistory = emptyList(),
                    captureVersions = emptyMap()
                )

                projectStore.save(project)
                _project.value = project
                refreshProjects()

                // Wire the lan-message handler BEFORE starting the server.
                SaatirilServer.onLanMessage = ::onLanMessage
                SaatirilServer.setSessionPasswordHash(password?.let { sha256(it) })

                // Start the foreground service first (keeps the process alive).
                // This calls startForegroundService() which is async — the service's
                // onStartCommand runs on the main thread shortly after.
                withContext(Dispatchers.IO) {
                    try {
                        Log.i(TAG, "Starting foreground service...")
                        ServerService.start(app)
                        Log.i(TAG, "Foreground service start requested")
                    } catch (e: Exception) {
                        Log.e(TAG, "Foreground service start failed (non-fatal): ${e.message}", e)
                        // Non-fatal — the ktor server can still run while app is in foreground.
                    }

                    // Start the ktor server (singleton). This is the call most likely
                    // to throw if there's a port conflict or a ktor init error.
                    try {
                        Log.i(TAG, "Starting ktor SaatirilServer...")
                        SaatirilServer.start(app)
                        Log.i(TAG, "SaatirilServer started successfully on port ${SaatirilServer.port.value}")
                    } catch (e: Exception) {
                        Log.e(TAG, "SaatirilServer.start FAILED", e)
                        throw e
                    }
                }

                // Verify server is actually running before navigating
                if (!SaatirilServer.running.value) {
                    Log.e(TAG, "Server reported not running after start() returned")
                    throw RuntimeException("Server tidak berhasil dimulai (running=false)")
                }

                Log.i(TAG, "Project created successfully — navigating to MAIN")
                _screen.value = Screen.MAIN
            } catch (e: Exception) {
                Log.e(TAG, "createAndStartProject FAILED", e)
                _startupError.value = "Gagal memulai server: ${e.message ?: e.javaClass.simpleName}"
                // Clean up partial state
                try { SaatirilServer.stop() } catch (_: Exception) {}
                try { ServerService.stop(app) } catch (_: Exception) {}
                SaatirilServer.onLanMessage = null
            } finally {
                _starting.value = false
            }
        }
    }

    /** Stop the server and return to the Hub. */
    fun stopServer() {
        SaatirilServer.stop()
        ServerService.stop(app)
        SaatirilServer.onLanMessage = null
        _project.value = null
        _screen.value = Screen.HUB
        refreshProjects()
    }

    /**
     * Handle a photo captured locally by the admin's own phone camera (the
     * Operator tab in MainScaffold). This saves the photo to the output folder,
     * updates the project DB, and broadcasts SYNC_DB to all connected clients —
     * the same flow as [handlePhotosSaved] but initiated locally instead of
     * arriving via socket from a remote operator.
     */
    fun handleLocalCapture(student: Student, base64Photo: String, channel: Int) {
        val proj = _project.value ?: return

        // Build the filename: NIM_Nama_1_Toga.jpg (standard mode, version 1)
        val filename = com.saatiril.andro.util.FilenameUtils.buildStandardFilename(
            student.nim, student.nama, 1, "Toga", 1
        )

        // Save to the SAF output folder (on IO dispatcher)
        viewModelScope.launch(Dispatchers.IO) {
            try {
                photoSaver.savePhoto(base64Photo, filename)
                Log.i(TAG, "Local capture saved: $filename")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save local capture: ${e.message}", e)
            }
        }

        // Update project: add to photoHistory, mark student done
        val updatedDb = proj.database.map { s ->
            if (s.id == student.id) s.copy(status = "done") else s
        }
        val historyItem = PhotoHistoryItem(student = student, photos = listOf(base64Photo), channel = channel)
        val updatedHistory = proj.photoHistory.filterNot { it.student.id == student.id && it.channel == channel } + historyItem
        _project.value = proj.copy(database = updatedDb, photoHistory = updatedHistory)
        _project.value?.let { projectStore.save(it) }

        // Broadcast SYNC_DB so all connected MC/operator clients update their state
        pushSyncDb()
    }

    // ─── Server state (delegated to SaatirilServer singleton) ───
    val serverRunning: StateFlow<Boolean> = SaatirilServer.running
    val serverClients: StateFlow<List<ClientInfo>> = SaatirilServer.clients
    val serverStats: StateFlow<ServerStats> = SaatirilServer.stats
    val lanIp: StateFlow<String?> = SaatirilServer.lanIp
    val serverPort: StateFlow<Int> = SaatirilServer.port

    // ─── MC actions (broadcast via server) ─────────────────────
    fun callStudent(student: Student, channel: Int) {
        // Update local DB immediately
        _project.value?.let { proj ->
            val updatedDb = proj.database.map { s ->
                if (s.id == student.id) s.copy(status = "active_$channel")
                else if (isActiveStatus(s.status) && getActiveChannel(s.status) == channel) s.copy(status = "pending")
                else s
            }
            _project.value = proj.copy(database = updatedDb)
        }
        SaatirilServer.broadcastLanMessage(SocketEvents.MC_CALL, McCallData(student, channel))
        pushSyncDb()
    }

    fun resetStudent(studentId: String, channel: Int) {
        _project.value?.let { proj ->
            val updatedDb = proj.database.map { s ->
                if (s.id == studentId) s.copy(status = "pending") else s
            }
            _project.value = proj.copy(database = updatedDb)
        }
        SaatirilServer.broadcastLanMessage(SocketEvents.STUDENT_RESET, StudentResetData(studentId, channel))
        pushSyncDb()
    }

    fun markStudentDone(studentId: String, channel: Int) {
        _project.value?.let { proj ->
            val updatedDb = proj.database.map { s ->
                if (s.id == studentId) s.copy(status = "done") else s
            }
            _project.value = proj.copy(database = updatedDb)
        }
        SaatirilServer.broadcastLanMessage(SocketEvents.STUDENT_DONE, StudentDoneData(studentId, channel))
        pushSyncDb()
    }

    /** Broadcast the current project state to all clients (SYNC_DB). */
    fun pushSyncDb() {
        val proj = _project.value ?: return
        // Strip sensitive/large fields (matches the protocol).
        val stripped = proj.copy(
            config = proj.config.copy(
                sessionPassword = if (proj.config.sessionPassword != null) "__PASSWORD_SET__" else null
            ),
            photoHistory = proj.photoHistory.map { it.copy(photos = emptyList()) }
        )
        SaatirilServer.broadcastLanMessage(SocketEvents.SYNC_DB, SyncDbData(stripped))
    }

    // ─── Lan-message handler (called by SaatirilServer) ─────────
    private fun onLanMessage(event: String, data: JsonElement?, senderSid: String?) {
        try {
            when (event) {
                SocketEvents.PHOTOS_SAVED -> handlePhotosSaved(data)
                SocketEvents.MC_CALL -> handleMcCall(data)
                SocketEvents.STUDENT_DONE -> handleStudentDone(data)
                SocketEvents.STUDENT_RESET -> handleStudentReset(data)
                SocketEvents.REQUEST_STATE -> handleRequestState(senderSid)
                SocketEvents.REQUEST_FRAME -> handleRequestFrame(data, senderSid)
                SocketEvents.OP_PROGRESS -> { /* informational only */ }
                SocketEvents.SYNC_DB -> { /* we are the source of truth; ignore */ }
                else -> Log.d(TAG, "lan-message '$event' (unhandled)")
            }
        } catch (e: Exception) {
            Log.e(TAG, "onLanMessage error for $event: ${e.message}", e)
        }
    }

    private fun handlePhotosSaved(data: JsonElement?) {
        val obj = data as? JsonObject ?: return
        val student = parseStudent(obj.get("student") as? JsonObject)
        val photos = parseStringList(obj.get("photos"))
        val channel = obj.get("channel")?.takeIf { !it.isJsonNull }?.asInt ?: 1
        val version = obj.get("version")?.takeIf { !it.isJsonNull }?.asInt ?: 1
        val primaryFilename = obj.get("filename")?.takeIf { !it.isJsonNull }?.asString ?: ""

        if (photos.isEmpty() || student.id.isBlank()) return

        // Save each photo to the SAF output folder.
        viewModelScope.launch(Dispatchers.IO) {
            for ((i, photo) in photos.withIndex()) {
                val filename = deriveFilename(primaryFilename, student, channel, version, i, photos.size)
                try {
                    photoSaver.savePhoto(photo, filename)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to save photo $filename: ${e.message}")
                }
            }
        }

        // Update project: add to photoHistory, mark student done.
        _project.value?.let { proj ->
            val updatedDb = proj.database.map { s ->
                if (s.id == student.id) s.copy(status = "done") else s
            }
            val historyItem = PhotoHistoryItem(student = student, photos = photos, channel = channel)
            val updatedHistory = proj.photoHistory.filterNot { it.student.id == student.id && it.channel == channel } + historyItem
            val updatedVersions = proj.captureVersions.toMutableMap().apply {
                put("${student.id}_$channel", version)
            }
            _project.value = proj.copy(
                database = updatedDb,
                photoHistory = updatedHistory,
                captureVersions = updatedVersions
            )
        }
        // Persist updated project
        _project.value?.let { projectStore.save(it) }
    }

    private fun handleMcCall(data: JsonElement?) {
        val obj = data as? JsonObject ?: return
        val studentObj = obj.get("student") as? JsonObject ?: return
        val student = parseStudent(studentObj)
        val channel = obj.get("channel")?.takeIf { !it.isJsonNull }?.asInt ?: 1
        _project.value?.let { proj ->
            val updatedDb = proj.database.map { s ->
                if (s.id == student.id) s.copy(status = "active_$channel")
                else if (isActiveStatus(s.status) && getActiveChannel(s.status) == channel) s.copy(status = "pending")
                else s
            }
            _project.value = proj.copy(database = updatedDb)
        }
    }

    private fun handleStudentDone(data: JsonElement?) {
        val obj = data as? JsonObject ?: return
        val studentId = obj.get("studentId")?.takeIf { !it.isJsonNull }?.asString ?: return
        _project.value?.let { proj ->
            val updatedDb = proj.database.map { s ->
                if (s.id == studentId) s.copy(status = "done") else s
            }
            _project.value = proj.copy(database = updatedDb)
        }
    }

    private fun handleStudentReset(data: JsonElement?) {
        val obj = data as? JsonObject ?: return
        val studentId = obj.get("studentId")?.takeIf { !it.isJsonNull }?.asString ?: return
        _project.value?.let { proj ->
            val updatedDb = proj.database.map { s ->
                if (s.id == studentId) s.copy(status = "pending") else s
            }
            _project.value = proj.copy(database = updatedDb)
        }
    }

    private fun handleRequestState(senderSid: String?) {
        // A client just connected and wants the current project state.
        pushSyncDb()
    }

    private fun handleRequestFrame(data: JsonElement?, senderSid: String?) {
        val proj = _project.value ?: return
        val frame = proj.config.frame
        if (senderSid == null) return
        if (frame.isNullOrBlank() || frame == "__FRAME_SAVED__") {
            SaatirilServer.sendLanMessageToClient(senderSid, SocketEvents.FRAME_DATA,
                FrameDataPayload(projectId = proj.id, frame = ""))
        } else {
            SaatirilServer.sendLanMessageToClient(senderSid, SocketEvents.FRAME_DATA,
                FrameDataPayload(projectId = proj.id, frame = frame))
        }
    }

    // ─── Helpers ────────────────────────────────────────────────
    private fun sha256(input: String): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        return md.digest(input.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    private fun parseStudent(obj: JsonObject?): Student {
        if (obj == null) return Student()
        return Student(
            id = obj.get("id")?.takeIf { !it.isJsonNull }?.asString ?: "",
            nim = obj.get("nim")?.takeIf { !it.isJsonNull }?.asString ?: "",
            nama = obj.get("nama")?.takeIf { !it.isJsonNull }?.asString ?: "",
            status = obj.get("status")?.takeIf { !it.isJsonNull }?.asString ?: "pending",
            assignedChannel = obj.get("assignedChannel")?.takeIf { !it.isJsonNull }?.asInt
                ?: obj.get("assigned_channel")?.takeIf { !it.isJsonNull }?.asInt ?: 1
        )
    }

    private fun parseStringList(el: JsonElement?): List<String> {
        if (el == null || !el.isJsonArray) return emptyList()
        return el.asArray.mapNotNull { it.takeIf { !it.isJsonNull }?.asString }
    }

    private val JsonElement.asArray get() = this.asJsonArray.toList()

    /**
     * Derive a filename for photo[index] given the primary filename from the
     * operator. Matches the NIM_Nama_N_Toga.jpg / NIM_Nama_N_Ijazah.jpg
     * convention (see FilenameUtils).
     */
    private fun deriveFilename(
        primary: String, student: Student, channel: Int, version: Int,
        index: Int, total: Int
    ): String {
        if (primary.isBlank()) {
            return com.saatiril.andro.util.FilenameUtils.buildStandardFilename(
                student.nim, student.nama, index + 1, if (index == 0) "Toga" else "Ijazah", version)
        }
        if (index == 0) return primary
        // Second photo in dual mode → Toga → Ijazah
        return if (primary.contains("Toga")) primary.replace("Toga", "Ijazah")
        else primary.replace(".jpg", "_${index + 1}.jpg")
    }

    override fun onCleared() {
        // Don't stop the server here — it should outlive the ViewModel (foreground service).
        super.onCleared()
    }
}
