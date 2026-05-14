package com.helix.assistant

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.CountDownTimer
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import java.util.Locale

class HelixBridge(
    private val context: Context,
    private val webView: WebView
) : TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = null
    private var recognizer: SpeechRecognizer? = null
    private val handler = Handler(Looper.getMainLooper())
    private var ttsReady = false
    private var timer: CountDownTimer? = null
    private var notifCounter = 2000

    // ─────────────────────────────────────────
    // INIT
    // ─────────────────────────────────────────

    init {
        tts = TextToSpeech(context, this)
        createNotificationChannel()
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale.US
            tts?.setSpeechRate(1.05f)
            tts?.setPitch(1.0f)
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) = callJS("window.onTTSStart && window.onTTSStart()")
                override fun onDone(id: String?) = callJS("window.onTTSDone && window.onTTSDone()")
                @Deprecated("Deprecated in Java")
                override fun onError(id: String?) = callJS("window.onTTSDone && window.onTTSDone()")
            })
            ttsReady = true
            callJS("window.onBridgeReady && window.onBridgeReady()")
        }
    }

    // ─────────────────────────────────────────
    // TTS
    // ─────────────────────────────────────────

    @JavascriptInterface
    fun speak(text: String) {
        if (!ttsReady) return
        val clean = text.replace(Regex("[*#`]"), "").trim()
        if (clean.isEmpty()) return
        val params = Bundle()
        val uid = "helix_${System.currentTimeMillis()}"
        params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, uid)
        tts?.speak(clean, TextToSpeech.QUEUE_FLUSH, params, uid)
    }

    @JavascriptInterface
    fun stopSpeaking() {
        tts?.stop()
        callJS("window.onTTSDone && window.onTTSDone()")
    }

    @JavascriptInterface
    fun isTTSReady(): Boolean = ttsReady

    // ─────────────────────────────────────────
    // SPEECH RECOGNITION
    // ─────────────────────────────────────────

    @JavascriptInterface
    fun startListening(language: String) {
        handler.post {
            if (!SpeechRecognizer.isRecognitionAvailable(context)) {
                callJS("window.onSpeechError && window.onSpeechError('unavailable')")
                return@post
            }
            recognizer?.destroy()
            recognizer = SpeechRecognizer.createSpeechRecognizer(context)
            recognizer?.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(p: Bundle?) {
                    callJS("window.onListenStart && window.onListenStart()")
                }
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rms: Float) {
                    val v = ((rms.coerceIn(-2f, 10f) + 2f) / 12f * 100).toInt()
                    callJS("window.onVolume && window.onVolume($v)")
                }
                override fun onBufferReceived(b: ByteArray?) {}
                override fun onEndOfSpeech() {
                    callJS("window.onListenEnd && window.onListenEnd()")
                }
                override fun onError(code: Int) {
                    val msg = when (code) {
                        SpeechRecognizer.ERROR_NO_MATCH -> "no_match"
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "timeout"
                        SpeechRecognizer.ERROR_AUDIO -> "audio"
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "no_permission"
                        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "busy"
                        else -> "error_$code"
                    }
                    callJS("window.onSpeechError && window.onSpeechError('$msg')")
                }
                override fun onResults(results: Bundle?) {
                    val text = results
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull() ?: return
                    val escaped = text.replace("\\", "\\\\").replace("'", "\\'")
                    callJS("window.onSpeechResult && window.onSpeechResult('$escaped')")
                }
                override fun onPartialResults(partial: Bundle?) {
                    val text = partial
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull() ?: return
                    val escaped = text.replace("\\", "\\\\").replace("'", "\\'")
                    callJS("window.onPartialResult && window.onPartialResult('$escaped')")
                }
                override fun onEvent(type: Int, params: Bundle?) {}
            })

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, language.ifEmpty { "en-US" })
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1000L)
            }
            recognizer?.startListening(intent)
        }
    }

    @JavascriptInterface
    fun stopListening() {
        handler.post { recognizer?.stopListening() }
    }

    // ─────────────────────────────────────────
    // APP OPENING
    // ─────────────────────────────────────────

    @JavascriptInterface
    fun openApp(packageName: String, fallbackUrl: String) {
        handler.post {
            try {
                val launch = context.packageManager.getLaunchIntentForPackage(packageName)
                if (launch != null) {
                    context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } else {
                    openUrl(fallbackUrl)
                }
            } catch (e: Exception) {
                openUrl(fallbackUrl)
            }
        }
    }

    @JavascriptInterface
    fun openUrl(url: String) {
        handler.post {
            try {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            } catch (e: Exception) {}
        }
    }

    @JavascriptInterface
    fun openMaps(query: String) {
        handler.post {
            try {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=${Uri.encode(query)}"))
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            } catch (e: Exception) {
                openUrl("https://maps.google.com/?q=${Uri.encode(query)}")
            }
        }
    }

    @JavascriptInterface
    fun call(number: String) {
        handler.post {
            val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number"))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }

    @JavascriptInterface
    fun sendSMS(number: String, message: String) {
        handler.post {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("sms:$number")).apply {
                putExtra("sms_body", message)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }

    @JavascriptInterface
    fun openSearch(query: String) {
        openUrl("https://www.google.com/search?q=${Uri.encode(query)}")
    }

    // ─────────────────────────────────────────
    // TIMER
    // ─────────────────────────────────────────

    @JavascriptInterface
    fun setTimer(seconds: Int, label: String) {
        handler.post {
            timer?.cancel()
            timer = object : CountDownTimer(seconds * 1000L, 1000L) {
                override fun onTick(ms: Long) {}
                override fun onFinish() {
                    notify("Helix Assistant ⏰", label.ifEmpty { "Timer finished!" })
                    callJS("window.onTimerDone && window.onTimerDone()")
                }
            }.start()
            callJS("window.onTimerSet && window.onTimerSet($seconds)")
        }
    }

    // ─────────────────────────────────────────
    // HAPTIC
    // ─────────────────────────────────────────

    @JavascriptInterface
    fun vibrate(ms: Long) {
        try {
            val v = context.getSystemService(Context.VIBRATOR_SERVICE) as android.os.Vibrator
            @Suppress("DEPRECATION")
            v.vibrate(ms.coerceIn(10, 500))
        } catch (e: Exception) {}
    }

    // ─────────────────────────────────────────
    // NOTIFICATIONS
    // ─────────────────────────────────────────

    private fun notify(title: String, body: String) {
        try {
            val n = NotificationCompat.Builder(context, "helix")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(body)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .build()
            NotificationManagerCompat.from(context).notify(notifCounter++, n)
        } catch (e: Exception) {}
    }

    private fun createNotificationChannel() {
        val ch = NotificationChannel("helix", "Helix Assistant", NotificationManager.IMPORTANCE_HIGH)
        ch.description = "Helix Assistant notifications"
        val mgr = context.getSystemService(NotificationManager::class.java)
        mgr.createNotificationChannel(ch)
    }

    // ─────────────────────────────────────────
    // HELPERS
    // ─────────────────────────────────────────

    private fun callJS(js: String) {
        handler.post { webView.evaluateJavascript(js, null) }
    }

    fun onPermissionsGranted() {
        callJS("window.onPermissionsGranted && window.onPermissionsGranted()")
    }

    fun destroy() {
        tts?.shutdown()
        recognizer?.destroy()
        timer?.cancel()
    }
}
