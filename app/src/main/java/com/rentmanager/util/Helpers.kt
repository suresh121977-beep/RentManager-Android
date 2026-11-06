package com.rentmanager.util

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.telephony.SmsManager
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
    fun canCall(ctx: Context) = ContextCompat.checkSelfPermission(ctx, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED
    /** direct = true calls straight away (needs CALL_PHONE permission), otherwise opens the dialer */
    fun call(ctx: Context, mobile: String, direct: Boolean = false) {
        val n = mobile.filter { it.isDigit() || it == '+' }
        val action = if (direct && canCall(ctx)) Intent.ACTION_CALL else Intent.ACTION_DIAL
        ctx.startActivity(Intent(action, Uri.parse("tel:$n")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

/** Sends SMS straight from the app (no message box opens) */
object Sms {
    fun has(ctx: Context) = ContextCompat.checkSelfPermission(ctx, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED
    fun send(ctx: Context, mobile: String, text: String): Boolean = try {
        val sm: SmsManager = if (Build.VERSION.SDK_INT >= 31) ctx.getSystemService(SmsManager::class.java)
            else @Suppress("DEPRECATION") SmsManager.getDefault()
        val to = mobile.filter { it.isDigit() || it == '+' }
        sm.sendMultipartTextMessage(to, null, sm.divideMessage(text), null, null); true
    } catch (e: Exception) { false }
}

/** Native speech-to-text + text-to-speech. say(text){ ... } runs the block after the speech has finished. */
class Voice(private val ctx: Context) {
    private val main = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var sr: SpeechRecognizer? = null
    private val pendingDone = HashMap<String, () -> Unit>()
    var speakerOn = true

    init {
        tts = TextToSpeech(ctx) { st ->
            if (st == TextToSpeech.SUCCESS) {
                tts?.language = Locale("en", "IN")
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) {}
                    override fun onDone(id: String?) { fire(id) }
                    override fun onError(id: String?) { fire(id) }
                    override fun onError(id: String?, code: Int) { fire(id) }
                })
            }
        }
    }

    private fun fire(id: String?) {
        if (id == null) return
        val cb = synchronized(pendingDone) { pendingDone.remove(id) }
        if (cb != null) main.post { cb() }
    }

    fun hasMic() = ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    fun say(t: String, onDone: (() -> Unit)? = null) {
        val id = "u" + System.nanoTime()
        if (onDone != null) synchronized(pendingDone) { pendingDone[id] = onDone }
        if (!speakerOn) { main.post { fire(id) }; return }
        val r = tts?.speak(t, TextToSpeech.QUEUE_FLUSH, null, id)
        if (r != TextToSpeech.SUCCESS) { main.postDelayed({ fire(id) }, 300); return }
        // safety net: if the engine never reports completion, continue anyway
        if (onDone != null) main.postDelayed({ fire(id) }, 1500L + 110L * t.length)
    }

    fun listen(lang: String = "en-IN", onText: (String) -> Unit, onError: (String) -> Unit) {
        main.post {
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
                override fun onError(e: Int) {
                    onFail(when (e) {
                        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Didn't hear anything"
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission needed"
                        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network error"
                        else -> "Voice error $e"
                    })
                }
                override fun onResults(b: Bundle?) {
                    val l = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    if (l.isNullOrEmpty()) onFail("Didn't hear anything") else onText(l.first())
                }
            })
            val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang).putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            sr?.startListening(i)
        }
    }
    fun stop() { main.post { sr?.stopListening() } }
    /** stop everything right now (call on the main thread) */
    fun cancel() {
        synchronized(pendingDone) { pendingDone.clear() }
        try { sr?.cancel() } catch (e: Exception) { }
        tts?.stop()
    }
    fun shutdown() { cancel(); sr?.destroy(); tts?.shutdown() }
}
