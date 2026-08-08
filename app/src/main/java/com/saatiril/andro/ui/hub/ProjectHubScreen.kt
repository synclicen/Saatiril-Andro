package com.saatiril.andro.ui.hub

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.saatiril.andro.data.AdminViewModel
import com.saatiril.andro.data.Project

private val BG = Color(0xFF1a0b2e)
private val PANEL = Color(0xFF2a164a)
private val CARD = Color(0xFF3b2263)
private val BORDER = Color(0xFF533485)
private val GOLD = Color(0xFFd4af37)
private val MUTED = Color(0xFFc4b5fd)
private val CYAN = Color(0xFF06b6d4)
private val GREEN = Color(0xFF4ade80)
private val RED = Color(0xFFef4444)

/**
 * Project Hub — list saved projects (resume) or create a new one.
 * Mirrors the Electron `project-hub.tsx`.
 */
@Composable
fun ProjectHubScreen(viewModel: AdminViewModel) {
    val projects by viewModel.savedProjects.collectAsState()

    LaunchedEffect(Unit) { viewModel.refreshProjects() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BG)
            .statusBarsPadding()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Header — clean, no logo, no license text (user request)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Proyek Wisuda", style = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White))
            }
            // Switch role button — go back to role selection
            Card(
                modifier = Modifier.size(40.dp).clip(RoundedCornerShape(10.dp))
                    .clickable { viewModel.backToRoleSelect() },
                colors = CardDefaults.cardColors(containerColor = PANEL),
                shape = RoundedCornerShape(10.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, BORDER)
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.SwapHoriz, contentDescription = "Ganti Peran", tint = GOLD, modifier = Modifier.size(20.dp))
                }
            }
        }

        // New project button (big, primary)
        Button(
            onClick = { viewModel.startNewProjectSetup() },
            modifier = Modifier.fillMaxWidth().height(56.dp),
            colors = ButtonDefaults.buttonColors(containerColor = GOLD),
            shape = RoundedCornerShape(14.dp)
        ) {
            Icon(Icons.Default.AddCircle, contentDescription = null, modifier = Modifier.size(22.dp), tint = BG)
            Spacer(Modifier.width(8.dp))
            Text("Buat Proyek Baru", color = BG, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        }

        // Saved projects list
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(Icons.Default.Folder, contentDescription = null, tint = MUTED, modifier = Modifier.size(16.dp))
            Text("Proyek Tersimpan (${projects.size})", style = TextStyle(color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp))
        }

        if (projects.isEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = PANEL),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(Icons.Default.Inbox, contentDescription = null, tint = MUTED, modifier = Modifier.size(32.dp))
                    Text("Belum ada proyek", style = TextStyle(color = MUTED, fontSize = 13.sp))
                    Text("Ketuk \"Buat Proyek Baru\" untuk memulai", style = TextStyle(color = MUTED.copy(alpha = 0.6f), fontSize = 11.sp))
                }
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.weight(1f)
            ) {
                items(projects, key = { it.id }) { project ->
                    ProjectCard(project = project,
                        onOpen = { viewModel.editProject(project.id) },
                        onDelete = { viewModel.deleteProject(project.id) }
                    )
                }
            }
        }
    }
}

@Composable
private fun ProjectCard(project: Project, onOpen: () -> Unit, onDelete: () -> Unit) {
    val done = project.database.count { it.status == "done" }
    val total = project.database.size
    val progress = if (total > 0) done * 100 / total else 0

    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen),
        colors = CardDefaults.cardColors(containerColor = PANEL),
        shape = RoundedCornerShape(12.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, BORDER)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(Icons.Default.Event, contentDescription = null, tint = GOLD, modifier = Modifier.size(24.dp))
            Column(Modifier.weight(1f)) {
                Text(project.name.ifBlank { "Proyek Wisuda" }, style = TextStyle(color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp), maxLines = 1)
                Text("$total mahasiswa • $done selesai ($progress%) • Mode ${project.config.mode}", style = TextStyle(color = MUTED, fontSize = 11.sp))
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.DeleteOutline, contentDescription = "Hapus", tint = RED, modifier = Modifier.size(18.dp))
            }
        }
    }
}
