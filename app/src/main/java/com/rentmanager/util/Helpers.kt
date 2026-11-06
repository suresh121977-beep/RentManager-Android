package com.rentmanager.util

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File
import java.net.URLEncoder
import java.util.Locale

object Files {
    /** Save text into cache and open the Android share sheet (Save to Drive, WhatsApp, Files ...) */
    fun share(ctx: Context, fileName: String, mime: String, text: String) {
        val dir = File(ctx.cacheDir, "exports").apply { mkdirs() }
        val f = File(dir, fileName); f.writeText(text, Charsets.UTF_8)
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
        val i = Intent(Intent.ACTION_SEND).apply {
            type = mime; putExtra(Intent.EXTRA_STREAM, uri); putExtra(Intent.EXTRA_SUBJECT, fileName)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ctx.startActivity(Intent.createChooser(i, "Save / Share $fileName").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
    fun readUri(ctx: Context, uri: Uri): String =
        ctx.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""

    fun sms(ctx: Context, mobile: String, body: String) {
        val i = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + mobile.filter { it.isDigit() || it == '+' })).putExtra("sms_body", body)
        ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
    fun whatsapp(ctx: Context, mobile: String, body: String) {
        var d = mobile.filter { it.isDigit() }; if (d.length == 10) d = "91$d"
        val u = Uri.parse("https://wa.me/$d?text=" + URLEncoder.encode(body, "UTF-8"))
        ctx.startActivity(Intent(Intent.ACTION_VIEW, u).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
    fun call(ctx: Context, mobile: String) =
        ctx.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + mobile.filter { it.isDigit() || it == '+' })).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

/** Native speech-to-text + text-to-speech (replaces the browser/Capacitor bridge) */
class Voice(private val ctx: Context) {
    private val main = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var sr: SpeechRecognizer? = null
    var speakerOn = true
    init { tts = TextToSpeech(ctx) { if (it == TextToSpeech.SUCCESS) tts?.language = Locale("en", "IN") } }

    fun hasMic() = ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    fun say(t: String) { if (speakerOn) tts?.speak(t, TextToSpeech.QUEUE_FLUSH, null, "rm") }

    fun listen(lang: String = "en-IN", onText: (String) -> Unit, onError: (String) -> Unit) { main.post {
        val onFail = onError
        if (!SpeechRecognizer.isRecognitionAvailable(ctx)) { onFail("Speech recognition not available on this phone"); return@post }
        sr?.destroy(); sr = SpeechRecognizer.createSpeechRecognizer(ctx)
        sr?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(p: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(v: Float) {}
            override fun onBufferReceived(b: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(b: Bundle?) {}
            override fun onEvent(t: Int, b: Bundle?) {}
            override fun onError(e: Int) { onFail(when (e) {
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Didn't hear anything"
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission needed"
                SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network error"
                else -> "Voice error $e"
            }) }
            override fun onResults(b: Bundle?) {
                val l = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (l.isNullOrEmpty()) onFail("Didn't hear anything") else onText(l.first())
            }
        })
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang).putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        sr?.startListening(i)
    } }
    fun stop() = main.post { sr?.stopListening() }
    fun shutdown() { sr?.destroy(); tts?.shutdown() }
}
