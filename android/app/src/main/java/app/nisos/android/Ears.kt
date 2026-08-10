package app.nisos.android

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.util.Locale

/**
 * The microphone, via Android's own recogniser.
 *
 * The Python raced two recognisers -- Android's fast one against Whisper --
 * because Android's needs to be told which language to expect and Whisper
 * detects it. That race cost a 2.5 GB model and a cross-compiled binary, and
 * it is gone: this asks for one language and lets you switch.
 *
 * Losing automatic language detection is a real cost and worth being honest
 * about. What softens it is that the router does not care what the recogniser
 * *thought* the language was -- whichever table matches decides -- so a Greek
 * phrase recognised under an English locale still routes correctly whenever
 * the recogniser produces Greek characters at all.
 *
 * [amplitude] is what the HUD ring breathes to. It arrives roughly ten times a
 * second from the recogniser itself, which is free, rather than by opening a
 * second stream on a microphone that is already in use.
 */
class Ears(private val context: Context) {

    private var recogniser: SpeechRecognizer? = null

    /** -2..10 or so, per the platform. Normalised for the UI in [level]. */
    var amplitude: Float = 0f
        private set

    /** 0..1, smoothed, for anything that wants to draw it. */
    val level: Float get() = ((amplitude + 2f) / 12f).coerceIn(0f, 1f)

    var partial: String = ""
        private set

    fun available(): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

    /**
     * Listen once.
     *
     * @param language "el" or "en" -- which locale to hand the recogniser.
     * @param onPartial called as words arrive, for the live transcript.
     * @param onResult the final transcript, or an empty string if nothing was
     *   heard. Always called exactly once, which is the property that stops
     *   the UI sitting on a spinner forever.
     */
    fun listen(
        language: String,
        onPartial: (String) -> Unit = {},
        onResult: (String) -> Unit,
    ) {
        stop()

        val locale = if (language == "el") Locale("el", "GR") else Locale.UK
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
            )
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, locale.toLanguageTag())
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            // Prefer on-device where the phone has it: no round trip, and the
            // transcript of everything you say does not leave the device
            // before the router has even seen it.
            .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)

        val engine = SpeechRecognizer.createSpeechRecognizer(context)
        recogniser = engine

        var answered = false
        fun answer(text: String) {
            if (answered) return
            answered = true
            onResult(text)
        }

        engine.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { partial = "" }
            override fun onBeginningOfSpeech() {}

            override fun onRmsChanged(rmsdB: Float) { amplitude = rmsdB }

            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { amplitude = 0f }

            override fun onError(error: Int) {
                amplitude = 0f
                answer("")
            }

            override fun onResults(results: Bundle?) {
                amplitude = 0f
                val heard = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    .orEmpty()
                answer(heard)
            }

            override fun onPartialResults(results: Bundle?) {
                val heard = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    .orEmpty()
                if (heard.isNotEmpty()) {
                    partial = heard
                    onPartial(heard)
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        engine.startListening(intent)
    }

    fun stop() {
        recogniser?.let {
            try {
                it.stopListening()
                it.destroy()
            } catch (_: Exception) {
                // Destroying a recogniser that never started is not a problem
                // worth surfacing to somebody holding a phone.
            }
        }
        recogniser = null
        amplitude = 0f
    }
}
