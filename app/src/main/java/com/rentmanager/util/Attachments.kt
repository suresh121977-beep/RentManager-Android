package com.rentmanager.util

import android.content.Context
import android.content.Intent
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Tenant attachments live as real files in a folder per tenant file:
 *   <phone>/Android/data/com.rentmanager/files/RentManager/Tenants/NS5/ID_Proof|Photo|Audio|Other/
 * Nothing is stored in the database or loaded into memory – the folder is the source of truth,
 * and "View" hands the file to the phone's own viewer/player, so the app never hangs.
 */
object Attachments {
    val TYPES = listOf("ID_Proof", "Photo", "Audio", "Other")
    val LABEL = mapOf("ID_Proof" to "🪪 ID Proof", "Photo" to "📷 Photo", "Audio" to "🎙 Audio", "Other" to "📎 Other")

    private fun safe(s: String) = s.replace(Regex("[^A-Za-z0-9_-]"), "_").ifBlank { "file" }
    private fun root(ctx: Context) = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "RentManager/Tenants")
    fun dir(ctx: Context, fileName: String, type: String) = File(root(ctx), "${safe(fileName)}/$type").apply { mkdirs() }
    fun folderPath(ctx: Context, fileName: String) = File(root(ctx), safe(fileName)).absolutePath

    suspend fun list(ctx: Context, fileName: String, type: String): List<File> = withContext(Dispatchers.IO) {
        dir(ctx, fileName, type).listFiles()?.filter { it.isFile }?.sortedByDescending { it.lastModified() } ?: emptyList()
    }
    suspend fun counts(ctx: Context, fileName: String): Map<String, Int> = withContext(Dispatchers.IO) {
        TYPES.associateWith { dir(ctx, fileName, it).listFiles()?.count { f -> f.isFile } ?: 0 }
    }
    fun newFile(ctx: Context, fileName: String, type: String, ext: String): File =
        File(dir(ctx, fileName, type), "${type}_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.$ext")

    /** Copies a picked file into the tenant folder (runs on the IO thread, streams – no big memory use) */
    suspend fun importUri(ctx: Context, uri: Uri, fileName: String, type: String): File? = withContext(Dispatchers.IO) {
        try {
            var name = "file"
            ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME); if (i >= 0 && c.moveToFirst()) name = c.getString(i) ?: name
            }
            val ext = name.substringAfterLast('.', "bin")
            val base = safe(name.substringBeforeLast('.', name))
            var out = File(dir(ctx, fileName, type), "$base.$ext"); var n = 1
            while (out.exists()) { out = File(dir(ctx, fileName, type), "${base}_${n++}.$ext") }
            ctx.contentResolver.openInputStream(uri)?.use { inp -> out.outputStream().use { inp.copyTo(it, 64 * 1024) } }
            out
        } catch (e: Exception) { null }
    }

    fun uriFor(ctx: Context, f: File): Uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)

    /** Opens from the folder in the phone's own viewer / gallery / audio player */
    fun open(ctx: Context, f: File) {
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(f.extension.lowercase()) ?: "*/*"
        val i = Intent(Intent.ACTION_VIEW).setDataAndType(uriFor(ctx, f), mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        try { ctx.startActivity(i) } catch (e: Exception) {
            ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uriFor(ctx, f))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Open with").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
    fun share(ctx: Context, f: File) {
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(f.extension.lowercase()) ?: "*/*"
        ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uriFor(ctx, f))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Share ${f.name}").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
    fun delete(f: File) = f.delete()
    fun size(f: File): String { val k = f.length() / 1024; return if (k < 1024) "$k KB" else "%.1f MB".format(k / 1024.0) }
}

/** Voice/audio recorder – writes straight to the tenant's Audio folder */
class AudioRec(private val ctx: Context) {
    private var rec: MediaRecorder? = null
    var current: File? = null; private set
    val recording get() = rec != null

    fun start(target: File): Boolean = try {
        val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(ctx) else @Suppress("DEPRECATION") MediaRecorder()
        r.setAudioSource(MediaRecorder.AudioSource.MIC); r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC); r.setAudioEncodingBitRate(64000); r.setAudioSamplingRate(22050)
        r.setOutputFile(target.absolutePath); r.prepare(); r.start(); rec = r; current = target; true
    } catch (e: Exception) { rec?.release(); rec = null; target.delete(); false }

    fun stop(): File? { val f = current
        try { rec?.stop() } catch (e: Exception) { f?.delete(); current = null } finally { rec?.release(); rec = null }
        return current?.takeIf { it.exists() && it.length() > 0 } }
}
