package app.nisos.ui

import android.content.Context
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.nisos.android.AndroidPhone
import app.nisos.android.Ears
import app.nisos.android.Memory
import app.nisos.android.Voice
import app.nisos.core.ClaudeBrain
import app.nisos.core.Turn
import app.nisos.core.handle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Everything the screen draws, in one immutable snapshot. */
data class AssistantState(
    val mood: Mood = Mood.Idle,
    val level: Float = 0f,
    val heard: String = "",
    val spoken: String = "",
    val actions: List<String> = emptyList(),
    val language: String = "el",
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

    private val memory = Memory(context)
    private val phone = AndroidPhone(context, memory)
    private val ears = Ears(context)
    private val voice = Voice(context)

    private val brain = ClaudeBrain(keyProvider = { memory.apiKey })

    private val _state = mutableStateOf(AssistantState(brainLabel = brainLabel()))
    val state: State<AssistantState> get() = _state

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
    }

    val hasKey: Boolean get() = memory.hasKey

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
        _state.value = _state.value.copy(heard = text, spoken = "", actions = emptyList())
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

        fun factory(context: Context) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                AssistantViewModel(context) as T
        }
    }
}
