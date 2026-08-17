package app.nisos.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.nisos.android.AndroidPhone
import app.nisos.android.Ears
import app.nisos.android.Memory
import app.nisos.android.Voice
import app.nisos.core.ClaudeBrain
import app.nisos.core.Pending
import app.nisos.core.SmsBalanceSource
import app.nisos.core.Turn
import app.nisos.core.approve
import app.nisos.core.decline
import app.nisos.core.handle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/** Everything the screen draws, in one immutable snapshot. */
data class AssistantState(
    val mood: Mood = Mood.Idle,
    val level: Float = 0f,
    val heard: String = "",
    val spoken: String = "",
    val actions: List<String> = emptyList(),
    val language: String = "el",
    /**
     * Held back, awaiting a tap. Non-empty means the card is up and nothing has
     * been written yet.
     */
    val pending: List<Pending> = emptyList(),
    val busy: Boolean = false,
    val busyLabel: String = "",
    val brainLabel: String = "",
    val hint: String = "Tap Speak",
)

/**
 * One turn, orchestrated for the screen.
 *
 * The interesting decision here is where the work happens. [handle] is
 * synchronous and does real I/O -- a content-provider query, possibly an HTTP
 * round trip -- so it runs on [Dispatchers.IO]. Everything the UI reads is
 * assigned back on the main thread as a whole new [AssistantState], so there
 * is never a half-updated screen.
 *
 * The busy label counts seconds after the third one. That is carried straight
 * over from the web UI, where the fix was for the same problem: a button that
 * sits amber with nothing to show turns "is it dead?" into a guess, and a
 * legitimately slow turn and a wedged one look identical.
 */
class AssistantViewModel(context: Context) : ViewModel() {

    // The application context, which is what the factory passes. Held because
    // the settings screen has to ask whether READ_SMS is granted, and that is
    // a question only a Context can answer.
    private val appContext = context

    private val memory = Memory(context)
    private val phone = AndroidPhone(context, memory)
    private val ears = Ears(context)
    private val voice = Voice(context)

    private val brain = ClaudeBrain(keyProvider = { memory.apiKey })

    private val _state = mutableStateOf(AssistantState(brainLabel = brainLabel()))
    val state: State<AssistantState> get() = _state

    init {
        // The stored voice has to be pushed into the engine before the first
        // reply, not when the settings screen happens to open -- otherwise a
        // chosen voice silently reverts to the default on every launch.
        applyVoice()
    }

    private fun brainLabel(): String = when {
        !memory.hasKey -> "router only · no key"
        else -> "router + claude"
    }

    fun toggleLanguage() {
        _state.value = _state.value.copy(
            language = if (_state.value.language == "el") "en" else "el"
        )
    }

    /**
     * Store the API key.
     *
     * Typed into the app rather than pasted into a settings file, for the same
     * reason the Python read it from stdin and never from an argument: a
     * secret that lands anywhere quotable ends up in a bug report eventually.
     * Blank clears it, which is the only way to go back to router-only without
     * reinstalling.
     */
    fun saveKey(key: String) {
        memory.apiKey = key.trim().ifBlank { null }
        _state.value = _state.value.copy(
            brainLabel = brainLabel(),
            hint = if (memory.hasKey) "Tap Speak" else "Router only — no key",
        )
        refreshSettings()
    }

    val hasKey: Boolean get() = memory.hasKey

    // ----------------------------------------------------------------------
    // Settings
    //
    // One state object, refreshed from [Memory] after every change, for the
    // same reason [AssistantState] exists: a screen that reads the store
    // directly does not recompose when the store changes, so a saved token
    // stays invisible until you leave and come back.
    //
    // Settings live in this ViewModel rather than their own because they must
    // act on *these* instances. A second [Voice] would mean a second TTS
    // engine, and a pitch change would apply to a voice nobody is listening
    // to.
    // ----------------------------------------------------------------------

    private val _settings = mutableStateOf(SettingsState())
    val settings: State<SettingsState> get() = _settings

    /** Re-read everything the settings screen shows. */
    fun refreshSettings() {
        _settings.value = _settings.value.copy(
            hasKey = memory.hasKey,
            wiseSet = memory.wiseToken != null,
            senders = memory.smsSenders(),
            balances = memory.balanceAccounts().mapNotNull { account ->
                memory.balance(account)?.let { (amount, at) -> ManualBalance(account, amount, at) }
            },
            // Asked every refresh rather than remembered, because it can be
            // revoked from Android's own settings while this app is alive.
            smsGranted = ContextCompat.checkSelfPermission(
                appContext, Manifest.permission.READ_SMS
            ) == PackageManager.PERMISSION_GRANTED,
            voices = voice.englishVoices(),
            voiceName = memory.voiceName,
            pitch = memory.voicePitch,
            rate = memory.voiceRate,
        )
    }

    // -- money -------------------------------------------------------------

    fun saveWiseToken(token: String) {
        memory.wiseToken = token.trim().ifBlank { null }
        refreshSettings()
    }

    /**
     * Add a bank whose texts get read for a balance.
     *
     * The rule itself is in [SmsBalanceSource.addSender], in `core/`, where it
     * is tested. @return false when it was refused, so the field can say so.
     */
    fun addSmsSender(raw: String): Boolean {
        val updated = SmsBalanceSource.addSender(memory.smsSenders(), raw) ?: return false
        memory.setSmsSenders(updated)
        refreshSettings()
        return true
    }

    fun removeSmsSender(name: String) {
        memory.setSmsSenders(memory.smsSenders().filterNot { it == name })
        refreshSettings()
    }

    /**
     * Store a typed figure.
     *
     * The amount goes through the same parser the bank texts do, so "12.500,40"
     * and "12500.40" both work -- which matters more here than it looks,
     * because the keyboard a Greek phone offers types the first one.
     *
     * @return false when the amount could not be read, so the field can say so
     *   rather than silently storing nothing.
     */
    fun saveBalance(account: String, typed: String): Boolean {
        val name = account.trim()
        if (name.isEmpty()) return false
        val amount = SmsBalanceSource.europeanNumber(typed.trim()) ?: return false
        memory.setBalance(name, amount)
        refreshSettings()
        return true
    }

    fun forgetBalance(account: String) {
        memory.forgetBalance(account)
        refreshSettings()
    }

    /**
     * Read every source now and report each one separately.
     *
     * This exists because of a specific gap: the Wise request has never been
     * made against the real API, and «πόσα λεφτά έχω» answers "3 of 4" without
     * saying *which* four or which one is missing. A spoken total is the wrong
     * place to debug a token. Here each source names itself and its answer, so
     * a bad token, a revoked permission and a bank that simply has not texted
     * you recently are three visibly different outcomes instead of one silence.
     */
    fun checkMoney() {
        if (_settings.value.probing) return
        _settings.value = _settings.value.copy(probing = true, probe = emptyList())

        viewModelScope.launch {
            val readings = withContext(Dispatchers.IO) {
                phone.moneySources().map { source ->
                    // Mirrors total()'s contract: a source that throws is a
                    // source that cannot answer, never a failed check.
                    val balance = try {
                        source.read()
                    } catch (_: Exception) {
                        null
                    }
                    SourceReading(
                        label = source.label,
                        answer = balance?.let {
                            buildString {
                                append(String.format(Locale.UK, "%,.2f %s", it.amount, it.currency))
                                it.asOf?.let { at -> append(" · ${at.format(WHEN_READ)}") }
                            }
                        } ?: "no answer",
                        ok = balance != null,
                    )
                }
            }
            _settings.value = _settings.value.copy(probing = false, probe = readings)
        }
    }

    // -- the voice ---------------------------------------------------------

    private fun applyVoice() {
        voice.preferredEnglishVoice = memory.voiceName
        voice.pitch = memory.voicePitch
        voice.rate = memory.voiceRate
    }

    fun saveVoiceName(name: String?) {
        memory.voiceName = name
        applyVoice()
        refreshSettings()
    }

    fun saveVoicePitch(pitch: Float) {
        memory.voicePitch = twoPlaces(pitch)
        applyVoice()
        refreshSettings()
    }

    fun saveVoiceRate(rate: Float) {
        memory.voiceRate = twoPlaces(rate)
        applyVoice()
        refreshSettings()
    }

    /**
     * Round a slider position to something a person would write down.
     *
     * A continuous slider yields 0.8734112, which then has to be displayed,
     * compared against the default and stored. Two places is finer than the ear
     * can tell apart and keeps "reset" landing exactly on 0.96.
     */
    private fun twoPlaces(value: Float): Float = (value * 100f).roundToInt() / 100f

    /**
     * Say a sample so a choice can be heard rather than read.
     *
     * A voice name like `en-gb-x-rjs-local` tells you nothing about how it
     * sounds, and picking one blind then waiting for the next real reply to
     * find out is a poor way to tune three settings that interact.
     */
    fun previewVoice() {
        applyVoice()
        voice.speak("Torch on. Your next appointment is at nine.", "en")
    }

    /** Listen once, then run whatever was heard. */
    fun listen() {
        if (_state.value.busy) return
        if (!ears.available()) {
            _state.value = _state.value.copy(
                mood = Mood.Failed,
                hint = "No speech recogniser on this phone",
            )
            return
        }

        _state.value = _state.value.copy(
            mood = Mood.Listening,
            busy = true,
            busyLabel = "Listening",
            heard = "",
            spoken = "",
            actions = emptyList(),
            // Talking again abandons whatever was waiting. An un-approved
            // appointment must not stay tappable behind a new command.
            pending = emptyList(),
        )

        // The ring is driven by the recogniser's own amplitude callback, so
        // this loop is only here to pull it into Compose state.
        viewModelScope.launch {
            while (isActive && _state.value.mood == Mood.Listening) {
                _state.value = _state.value.copy(level = ears.level)
                delay(50)
            }
            _state.value = _state.value.copy(level = 0f)
        }

        ears.listen(
            language = _state.value.language,
            onPartial = { partial -> _state.value = _state.value.copy(heard = partial) },
            onResult = { heard ->
                if (heard.isBlank()) {
                    _state.value = _state.value.copy(
                        mood = Mood.Failed, busy = false, busyLabel = "",
                        hint = "Didn't catch that",
                    )
                } else {
                    run(heard)
                }
            },
        )
    }

    /** Run a typed command. The same path, minus the microphone. */
    fun handleText(text: String) {
        if (text.isBlank() || _state.value.busy) return
        _state.value = _state.value.copy(
            heard = text, spoken = "", actions = emptyList(), pending = emptyList(),
        )
        run(text)
    }

    private fun run(text: String) {
        _state.value = _state.value.copy(
            mood = Mood.Thinking, busy = true, busyLabel = "Working", heard = text,
        )

        viewModelScope.launch {
            val ticker = launch {
                var seconds = 0
                while (isActive) {
                    delay(1000)
                    seconds++
                    // After the third second, say how long. Before that it is
                    // noise -- most turns are done by then.
                    if (seconds >= 3) {
                        _state.value = _state.value.copy(busyLabel = "Working ${seconds}s")
                    }
                }
            }

            val turn: Turn = withContext(Dispatchers.IO) {
                handle(
                    text = text,
                    phone = phone,
                    brain = if (memory.hasKey) brain else null,
                    languageHint = _state.value.language,
                    memories = memory.relevant(text),
                )
            }
            ticker.cancel()
            settle(turn)
        }
    }

    /**
     * Put a finished turn on screen and say it.
     *
     * Shared by the three ways a turn can end -- run, approved, declined --
     * because the alternative is three copies of the mood rules and the
     * back-to-idle timing, which is how two of them quietly drift apart.
     */
    private suspend fun settle(turn: Turn) {
        _state.value = _state.value.copy(
            mood = if (turn.action in FAILURES) Mood.Failed else Mood.Speaking,
            busy = false,
            busyLabel = "",
            spoken = turn.spoken,
            actions = turn.steps.map { it.action },
            // The router decides the language, so a Greek sentence spoken
            // while the toggle says English corrects the toggle rather
            // than being answered in the wrong language.
            language = turn.language,
            brainLabel = brainLabel(),
            // Whatever is waiting after this turn -- normally nothing. Assigned
            // rather than merged, so a new command clears a card left over from
            // the last one: if you have moved on, the old appointment has not
            // been approved and must not stay tappable.
            pending = turn.pending,
        )

        voice.speak(turn.spoken, turn.language)

        // Back to idle once it has plausibly finished speaking. Android's
        // TTS will tell you properly through an UtteranceProgressListener;
        // this is the cheap version and the cost of being wrong is a ring
        // that stays lit a moment too long.
        delay(400L + turn.spoken.length * 55L)
        if (_state.value.mood == Mood.Speaking) {
            _state.value = _state.value.copy(mood = Mood.Idle)
        }
    }

    // ----------------------------------------------------------------------
    // Approval
    //
    // `calendar.add` is held back by [handle] and waits here. Nothing has
    // touched the calendar at this point -- the [Pending] carries the exact
    // [app.nisos.core.Step] that will run, so what was shown is what runs.
    // ----------------------------------------------------------------------

    /** Do the thing that was held back. */
    fun confirm() {
        val waiting = _state.value.pending
        if (waiting.isEmpty() || _state.value.busy) return

        _state.value = _state.value.copy(
            // Cleared before the work rather than after, so a second tap on a
            // slow write cannot add the appointment twice.
            pending = emptyList(),
            mood = Mood.Thinking,
            busy = true,
            busyLabel = "Adding",
        )

        viewModelScope.launch {
            val turn = withContext(Dispatchers.IO) {
                approve(waiting, phone, _state.value.language, _state.value.heard)
            }
            settle(turn)
        }
    }

    /** Say no. Touches nothing -- [decline] cannot reach the phone. */
    fun cancel() {
        if (_state.value.pending.isEmpty()) return
        _state.value = _state.value.copy(pending = emptyList())
        viewModelScope.launch {
            settle(decline(_state.value.language, _state.value.heard))
        }
    }

    override fun onCleared() {
        ears.stop()
        voice.release()
        super.onCleared()
    }

    companion object {
        /** Reply keys that mean the turn did not do what was asked. */
        private val FAILURES =
            setOf("failed", "unclear", "unavailable", "no_key", "refused", "no_permission")

        /** When a checked source last had a number, for the settings screen. */
        private val WHEN_READ: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM HH:mm")

        fun factory(context: Context) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                AssistantViewModel(context) as T
        }
    }
}
