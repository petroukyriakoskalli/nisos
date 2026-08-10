package app.nisos.android

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice as AndroidVoice
import java.util.Locale

/**
 * Text to speech, and the closest thing to a JARVIS voice that ships free.
 *
 * **What this is not.** It is not a clone of Paul Bettany. Cloning a named
 * actor's performance needs a voice model trained on their recordings, which
 * is both a large download and somebody's likeness -- not something to put in
 * a public repository. What it *is* is the register: an RP British male voice,
 * pitched slightly down and slowed a touch, which is most of what makes that
 * delivery recognisable.
 *
 * Google's TTS ships exactly those voices and they are free, offline once
 * downloaded, and instant. [ENGLISH_PREFERENCES] is a list in descending order
 * of how close each one is; the first that exists on the device wins, and
 * anything unrecognised falls back to whatever en-GB the phone has.
 *
 * Greek is the honest limitation. JARVIS is an English voice; «Άναψα τον
 * φακό» comes out of the best available el-GR voice and sounds like a
 * different person, because it is one. Nothing free fixes that.
 */
class Voice(context: Context, private val onReady: () -> Unit = {}) {

    private var engine: TextToSpeech? = null
    private var ready = false

    /** How much lower than default. Small: overdo it and it sounds unwell. */
    var pitch = 0.90f

    /** Slightly under conversational, which is what reads as considered. */
    var rate = 0.96f

    /** Set to a voice name to pin it; null follows [ENGLISH_PREFERENCES]. */
    var preferredEnglishVoice: String? = null

    init {
        engine = TextToSpeech(context) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (ready) onReady()
        }
    }

    /**
     * Say something in [language], picking the best voice for it.
     *
     * @param flush true to interrupt whatever is being said. A turn answers
     *   once, so the default is to interrupt: a queued reply from a command
     *   you have already moved on from is worse than silence.
     */
    fun speak(text: String, language: String, flush: Boolean = true) {
        val tts = engine ?: return
        if (!ready || text.isBlank()) return

        val locale = if (language == "el") Locale("el", "GR") else Locale.UK
        tts.language = locale
        tts.setPitch(if (language == "el") 1.0f else pitch)
        tts.setSpeechRate(rate)

        if (language != "el") bestEnglishVoice(tts)?.let { tts.voice = it }

        tts.speak(
            text,
            if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD,
            null,
            "nisos",
        )
    }

    /**
     * The first of [ENGLISH_PREFERENCES] the device actually has.
     *
     * Android will not tell you a voice's gender, so matching on name is the
     * only route. These are Google's own identifiers and they are stable; a
     * phone with a different TTS engine simply matches none of them and keeps
     * its default en-GB, which is the right failure.
     */
    private fun bestEnglishVoice(tts: TextToSpeech): AndroidVoice? {
        val available = try {
            tts.voices ?: return null
        } catch (_: Exception) {
            return null
        }

        preferredEnglishVoice?.let { pinned ->
            available.firstOrNull { it.name == pinned }?.let { return it }
        }
        for (wanted in ENGLISH_PREFERENCES) {
            available.firstOrNull { it.name.startsWith(wanted) }?.let { return it }
        }
        return available.firstOrNull {
            it.locale.language == "en" && it.locale.country == "GB"
        }
    }

    /** Every en-GB voice on this device, for the settings screen. */
    fun englishVoices(): List<String> = try {
        engine?.voices.orEmpty()
            .filter { it.locale.language == "en" }
            .map { it.name }
            .sorted()
    } catch (_: Exception) {
        emptyList()
    }

    fun stop() {
        engine?.stop()
    }

    fun release() {
        engine?.stop()
        engine?.shutdown()
        engine = null
    }

    companion object {
        /**
         * Google's en-GB male voices, closest first.
         *
         * `rjs` and `gbb` are the two male British voices; `gba` and `gbc` are
         * female, and are here only as a last resort before the generic
         * fallback. `-local` before `-network` on purpose: the network ones
         * sound marginally better and add a round trip to every single reply,
         * which is the wrong trade for an assistant whose whole argument is
         * that it answers in a second.
         */
        val ENGLISH_PREFERENCES = listOf(
            "en-gb-x-rjs-local",
            "en-gb-x-gbb-local",
            "en-GB-language",
            "en-gb-x-rjs-network",
            "en-gb-x-gbb-network",
        )
    }
}
