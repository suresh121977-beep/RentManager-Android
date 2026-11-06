@file:OptIn(ExperimentalMaterial3Api::class)
package com.rentmanager.ui

import android.Manifest
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rentmanager.domain.fmtDay
import com.rentmanager.util.AudioRec
import com.rentmanager.util.Attachments
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.time.LocalDate

@Composable fun AttachmentsDialog(fileName: String, close: () -> Unit) {
    val ctx = LocalContext.current; val scope = rememberCoroutineScope()
    var type by remember { mutableStateOf("ID_Proof") }; var tick by remember { mutableIntStateOf(0) }
    var files by remember { mutableStateOf<List<File>>(emptyList()) }; var counts by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
    var msg by remember { mutableStateOf("") }; var del by remember { mutableStateOf<File?>(null) }
    val rec = remember { AudioRec(ctx) }; var recording by remember { mutableStateOf(false) }; var secs by remember { mutableIntStateOf(0) }
    var pending by remember { mutableStateOf<File?>(null) }
    DisposableEffect(Unit) { onDispose { if (rec.recording) rec.stop() } }

    // list is read from the folder on a background thread – the screen never blocks
    LaunchedEffect(type, tick) { files = Attachments.list(ctx, fileName, type); counts = Attachments.counts(ctx, fileName) }
    LaunchedEffect(recording) { secs = 0; while (recording) { delay(1000); secs++ } }

    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
        scope.launch { var n = 0; uris.forEach { if (Attachments.importUri(ctx, it, fileName, type) != null) n++ }; msg = "$n file(s) saved to folder ✓"; tick++ } }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (ok) { msg = "Photo saved to folder ✓" } else { pending?.delete() }; pending = null; tick++ }
    val micPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) { val f = Attachments.newFile(ctx, fileName, "Audio", "m4a"); if (rec.start(f)) { recording = true } else { msg = "Could not start recording" } } else { msg = "Microphone permission denied" } }

    fun toggleRecord() {
        if (recording) { val f = rec.stop(); recording = false; msg = if (f != null) "Recording saved to folder ✓" else "Recording failed"; tick++ }
        else micPerm.launch(Manifest.permission.RECORD_AUDIO)
    }

    AlertDialog(onDismissRequest = close, title = { Text("📎 $fileName attachments") }, text = {
        Column {
            Chips(Attachments.TYPES.map { Attachments.LABEL[it]!! + " (${counts[it] ?: 0})" }, Attachments.LABEL[type]!! + " (${counts[type] ?: 0})") { s ->
                Attachments.TYPES.firstOrNull { s.startsWith(Attachments.LABEL[it]!!) }?.let { type = it } }
            Row(Modifier.padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (type == "Photo" || type == "ID_Proof") OutlinedButton({ val f = Attachments.newFile(ctx, fileName, type, "jpg"); pending = f; camera.launch(Attachments.uriFor(ctx, f.also { it.createNewFile() })) }) { Text("📷 Camera") }
                if (type == "Audio") Button({ toggleRecord() }, colors = if (recording) ButtonDefaults.buttonColors(containerColor = Red) else ButtonDefaults.buttonColors()) { Text(if (recording) "■ Stop ${secs}s" else "🎙 Record") }
                OutlinedButton({ pickFiles.launch(when (type) { "Photo" -> arrayOf("image/*"); "Audio" -> arrayOf("audio/*"); "ID_Proof" -> arrayOf("image/*", "application/pdf"); else -> arrayOf("*/*") }) }) { Text("＋ Add file") }
            }
            if (msg.isNotBlank()) Text(msg, color = Green, fontSize = 12.sp)
            if (files.isEmpty()) Empty("No files yet. They are saved straight into this tenant's folder.")
            LazyColumn(Modifier.heightIn(max = 300.dp)) { items(files) { f ->
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Text(f.name, fontSize = 13.sp, maxLines = 1); Lbl("${Attachments.size(f)} • ${fmtDay(java.time.Instant.ofEpochMilli(f.lastModified()).atZone(java.time.ZoneId.systemDefault()).toLocalDate().toString())}") }
                    TextButton({ Attachments.open(ctx, f) }) { Text("👁 View") }
                    TextButton({ Attachments.share(ctx, f) }) { Text("↗") }
                    TextButton({ del = f }) { Text("🗑", color = Red) } } } }
            Lbl("📁 ${Attachments.folderPath(ctx, fileName)}")
        } },
        confirmButton = { TextButton(close) { Text("Close") } })
    del?.let { f -> AlertDialog(onDismissRequest = { del = null }, title = { Text("Delete ${f.name}?") },
        confirmButton = { TextButton({ Attachments.delete(f); del = null; tick++ }) { Text("Delete", color = Red) } }, dismissButton = { TextButton({ del = null }) { Text("Cancel") } }) }
}
