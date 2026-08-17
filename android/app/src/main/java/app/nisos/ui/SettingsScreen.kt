package app.nisos.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.nisos.android.Lock
import app.nisos.android.Memory
import app.nisos.android.Voice
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Everything the settings screen shows, in one snapshot.
 *
 * Same reason [AssistantState] exists. A screen that reads [Memory] directly
 * does not recompose when [Memory] changes, so a token you just saved stays
 * invisible until you leave the screen and come back -- which reads as "it
 * didn't save".
 */
data class SettingsState(
    val lockEnabled: Boolean = false,
    val hasKey: Boolean = false,
    val wiseSet: Boolean = false,
    val senders: List<String> = emptyList(),
    val smsGranted: Boolean = false,
    val balances: List<ManualBalance> = emptyList(),
    val voices: List<String> = emptyList(),
    val voiceName: String? = null,
    val pitch: Float = Voice.DEFAULT_PITCH,
    val rate: Float = Voice.DEFAULT_RATE,
    val probe: List<SourceReading> = emptyList(),
    val probing: Boolean = false,
)

/** A figure you typed in, and when you typed it. */
data class ManualBalance(
    val account: String,
    val amount: Double,
    val asOf: LocalDateTime?,
)

/** What one money source said when asked directly. */
data class SourceReading(val label: String, val answer: String, val ok: Boolean)

// The palette, matching the assistant screen. Duplicated rather than shared
// because that screen holds its colours inline and pulling them out would mean
// editing every line of the one screen that has already been commissioned on a
// phone. Worth unifying once both are proven.
private val INK = Color(0xFF05070C)
private val CYAN = Color(0xFF35E0F0)
private val TEXT = Color(0xFFDCE8F0)
private val DIM = Color(0xFF4A5D6E)
private val FAINT = Color(0xFF3A4A5A)
private val GHOST = Color(0xFF2A3A4A)
private val EDGE = Color(0xFF1E2A36)
private val PANEL = Color(0xFF16202A)
private val WARN = Color(0xFFC79A3A)
private val GOOD = Color(0xFF5FC98A)
private val BAD = Color(0xFFE0483C)

private val WHEN_SEEN: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM HH:mm")

/**
 * The second screen: the things that have no voice command.
 *
 * Three of the four money sources were unreachable before this existed. Wise
 * needs a token, the bank sources need sender names, and both had setters in
 * [Memory] with nothing in front of them -- so «πόσα λεφτά έχω» could only ever
 * count figures you had dictated by hand. The other half of the screen is the
 * things worth being able to *check* rather than set: whether a token actually
 * works, and what the voice actually sounds like.
 */
@Composable
fun SettingsScreen(model: AssistantViewModel, onBack: () -> Unit) {
    val settings by model.settings

    // Re-read on entry rather than once at construction. Two things can change
    // behind this screen's back: the TTS engine finishes starting up and only
    // then reports its voices, and READ_SMS can be revoked in Android's own
    // settings while this app is still alive.
    LaunchedEffect(Unit) { model.refreshSettings() }

    Surface(modifier = Modifier.fillMaxSize(), color = INK) {
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(listOf(INK, Color(0xFF0A1018), INK))
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    // Same Android 15 rule as the assistant screen, and it also
                    // covers the keyboard -- which is up for most of this page.
                    .safeDrawingPadding()
                    .padding(horizontal = 20.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onBack) {
                        Text("‹  back", color = CYAN, fontSize = 14.sp)
                    }
                    Text(
                        "settings",
                        color = DIM,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 1.sp,
                    )
                }

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                ) {
                    LockSection(model, settings)
                    BrainSection(model, settings)
                    MoneySection(model, settings)
                    VoiceSection(model, settings)
                    Spacer(Modifier.height(32.dp))
                }
            }
        }
    }
}

// --------------------------------------------------------------------------
// The lock
// --------------------------------------------------------------------------

/**
 * Require a fingerprint, PIN or password to open the app.
 *
 * **Turning it on authenticates first.** That ordering is the entire safety
 * argument, not a nicety: you cannot enable a lock you are unable to open, so a
 * sideloaded app with no recovery path cannot shut you out of your own API key.
 * Turning it *off* asks as well, for the obvious reason — otherwise anyone
 * holding the phone once, unlocked, can remove the lock for good.
 */
@Composable
private fun LockSection(model: AssistantViewModel, settings: SettingsState) {
    val context = LocalContext.current
    val activity = remember(context) { context.hostActivity() }
    val strength = remember(context) { Lock.strength(context) }
    var refused by remember { mutableStateOf<String?>(null) }

    Section("Opening the app") {
        if (strength == null || activity == null) {
            Note(
                "This phone has no fingerprint enrolled and no screen lock, so " +
                    "there is nothing to ask you for. Set one up in Android " +
                    "settings and this becomes available."
            )
            return@Section
        }

        Note(
            "The app holds your key, your balances and your calendar. " +
                "App-private storage keeps those from other apps; it does " +
                "nothing about somebody holding your phone while it is unlocked. " +
                "This is the part that does."
        )

        Choice(
            label = if (settings.lockEnabled) "on" else "off",
            detail = if (settings.lockEnabled) "asked when you open it" else "opens straight away",
            selected = settings.lockEnabled,
            onClick = {
                refused = null
                authenticate(
                    activity = activity,
                    strength = strength,
                    title = if (settings.lockEnabled) "Turn the lock off" else "Turn the lock on",
                    // Named before it happens, because the system prompt on its
                    // own says only "nisos" and gives no clue which way it is
                    // about to go.
                    subtitle = if (settings.lockEnabled) {
                        "Confirm to stop asking"
                    } else {
                        "Confirm you can open it this way"
                    },
                ) { ok, message ->
                    if (ok) model.setLockEnabled(!settings.lockEnabled) else refused = message
                }
            },
        )

        refused?.let { Warning(it) }

        Note(
            "Asked again after ten seconds away — short enough to be a lock, " +
                "long enough that a permission dialog does not trip it."
        )
        Warning(
            "If you remove your phone's screen lock this stops applying, " +
                "because there is then no way to prove who you are and an app " +
                "you can never open again is not a security feature."
        )
    }
}

// --------------------------------------------------------------------------
// The brain
// --------------------------------------------------------------------------

@Composable
private fun BrainSection(model: AssistantViewModel, settings: SettingsState) {
    Section("The brain") {
        Note(
            "Without a key the router still answers most commands entirely on " +
                "the phone — the torch, timers, the calendar. Anything it does " +
                "not recognise gets a spoken apology instead of an answer."
        )
        Spacer(Modifier.height(12.dp))
        Secret(
            placeholder = "sk-ant-…",
            isSet = settings.hasKey,
            setLabel = "key stored",
            onSave = model::saveKey,
        )
        Note(
            "Kept in app-private storage, which other apps cannot read. Not " +
                "encrypted against someone holding your unlocked phone."
        )
    }
}

// --------------------------------------------------------------------------
// Money
// --------------------------------------------------------------------------

@Composable
private fun MoneySection(model: AssistantViewModel, settings: SettingsState) {
    Section("Money") {
        Note(
            "Every source here is incapable of moving money: a read-only " +
                "token, a message your bank already sent you, a figure you " +
                "typed. No banking password is stored, ever."
        )

        Spacer(Modifier.height(20.dp))
        Subhead("Figures you tell it")
        Note(
            "For accounts nothing can reach — myEurolife and most pension or " +
                "insurance portals have no API at all. As accurate as the last " +
                "time you looked, which is why the date is kept and spoken " +
                "once it is old."
        )

        settings.balances.forEach { balance ->
            Line(
                label = balance.account,
                value = money(balance.amount) +
                    (balance.asOf?.let { "  ·  ${it.format(WHEN_SEEN)}" } ?: ""),
                onRemove = { model.forgetBalance(balance.account) },
            )
        }
        if (settings.balances.isEmpty()) Empty("None yet.")

        AddBalance(model)

        Spacer(Modifier.height(24.dp))
        Subhead("Banks that text you a balance")
        Note(
            "Bank of Cyprus, Hellenic and Alpha have no personal API, but they " +
                "text you after a card transaction and the message is already " +
                "on the phone. Name the sender exactly as it appears in your " +
                "messages list."
        )

        if (settings.senders.isNotEmpty() && !settings.smsGranted) {
            Warning(
                "Reading messages is not permitted, so these are being " +
                    "skipped. If the prompt no longer appears, grant it in " +
                    "Android settings → Apps → nisos → Permissions."
            )
        }

        settings.senders.forEach { sender ->
            Line(
                label = sender,
                value = if (settings.smsGranted) "reading" else "blocked",
                valueColour = if (settings.smsGranted) GOOD else WARN,
                onRemove = { model.removeSmsSender(sender) },
            )
        }
        if (settings.senders.isEmpty()) Empty("None yet. Reading messages stays off until you add one.")

        AddSender(model, settings)

        Spacer(Modifier.height(24.dp))
        Subhead("Wise")
        Note(
            "In the Wise app: Settings → Integrations and tools → API tokens. " +
                "Make it read-only — a full-access token could move money and " +
                "this app has no use for one that can. Revoke it from the same " +
                "screen whenever you like."
        )
        Secret(
            placeholder = "read-only personal token",
            isSet = settings.wiseSet,
            setLabel = "token stored",
            onSave = model::saveWiseToken,
        )
        Warning(
            "This request has never been made against the real Wise API. " +
                "Check below is how you find out — a token that does not work " +
                "is silently skipped in the spoken total."
        )

        Spacer(Modifier.height(24.dp))
        Subhead("Check")
        Note(
            "Reads every source now and reports each one separately. The " +
                "spoken total says \"3 of 4\" without saying which four, and a " +
                "reply is the wrong place to debug a token."
        )
        Button(
            onClick = { model.checkMoney() },
            enabled = !settings.probing,
            colors = ButtonDefaults.buttonColors(
                containerColor = PANEL,
                contentColor = Color(0xFF9BE9F2),
                disabledContainerColor = PANEL,
                disabledContentColor = FAINT,
            ),
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        ) { Text(if (settings.probing) "Reading…" else "Check sources now") }

        settings.probe.forEach { reading ->
            Line(
                label = reading.label,
                value = reading.answer,
                valueColour = if (reading.ok) GOOD else WARN,
            )
        }
        if (settings.probe.isEmpty() && !settings.probing) {
            Empty("Not checked yet.")
        }
    }
}

@Composable
private fun AddBalance(model: AssistantViewModel) {
    var account by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    var bad by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Field(
            value = account,
            onValueChange = { account = it; bad = false },
            placeholder = "eurolife",
            modifier = Modifier.weight(1.2f),
        )
        Spacer(Modifier.width(8.dp))
        Field(
            value = amount,
            onValueChange = { amount = it; bad = false },
            placeholder = "12.000,50",
            modifier = Modifier.weight(1f),
        )
    }
    Button(
        onClick = {
            if (model.saveBalance(account, amount)) {
                account = ""
                amount = ""
                bad = false
            } else {
                bad = true
            }
        },
        enabled = account.isNotBlank() && amount.isNotBlank(),
        colors = ButtonDefaults.buttonColors(
            containerColor = PANEL,
            contentColor = Color(0xFF9BE9F2),
            disabledContainerColor = PANEL,
            disabledContentColor = FAINT,
        ),
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
    ) { Text("Add figure") }

    if (bad) {
        Warning("Could not read that amount. Both 12.000,50 and 12000.50 work.")
    }
}

@Composable
private fun AddSender(model: AssistantViewModel, settings: SettingsState) {
    var sender by remember { mutableStateOf("") }
    var duplicate by remember { mutableStateOf(false) }

    // Asked for only when the first sender is named, never at launch. READ_SMS
    // is access to every message on the phone, so the request has to be
    // attached to the moment it becomes useful -- otherwise it is a permission
    // prompt on first run for a feature nobody switched on.
    val readSms = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { model.refreshSettings() }

    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Field(
            value = sender,
            onValueChange = { sender = it; duplicate = false },
            placeholder = "BOC",
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        Button(
            onClick = {
                if (model.addSmsSender(sender)) {
                    sender = ""
                    if (!settings.smsGranted) readSms.launch(Manifest.permission.READ_SMS)
                } else {
                    duplicate = true
                }
            },
            enabled = sender.isNotBlank(),
            colors = ButtonDefaults.buttonColors(
                containerColor = PANEL,
                contentColor = Color(0xFF9BE9F2),
                disabledContainerColor = PANEL,
                disabledContentColor = FAINT,
            ),
        ) { Text("Add") }
    }

    if (duplicate) Warning("That sender is already on the list.")

    if (settings.senders.isNotEmpty() && !settings.smsGranted) {
        Button(
            onClick = { readSms.launch(Manifest.permission.READ_SMS) },
            colors = ButtonDefaults.buttonColors(
                containerColor = PANEL,
                contentColor = WARN,
            ),
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        ) { Text("Allow reading messages") }
    }
}

// --------------------------------------------------------------------------
// The voice
// --------------------------------------------------------------------------

@Composable
private fun VoiceSection(model: AssistantViewModel, settings: SettingsState) {
    Section("The voice") {
        Note(
            "Not a clone of anybody — the register. An RP British male voice, " +
                "pitched slightly down and slowed a touch, which is most of " +
                "what makes that delivery recognisable. Automatic takes the " +
                "closest one your phone has."
        )

        Spacer(Modifier.height(12.dp))
        Choice(
            label = "automatic",
            detail = "best en-GB available",
            selected = settings.voiceName == null,
            onClick = { model.saveVoiceName(null) },
        )
        settings.voices.forEach { name ->
            Choice(
                label = name,
                detail = null,
                selected = settings.voiceName == name,
                onClick = { model.saveVoiceName(name) },
            )
        }
        if (settings.voices.isEmpty()) {
            Empty("The speech engine has not reported any voices. Come back in a moment.")
        }

        Spacer(Modifier.height(20.dp))
        Dial(
            label = "Pitch",
            value = settings.pitch,
            default = Voice.DEFAULT_PITCH,
            onChange = model::saveVoicePitch,
        )
        Dial(
            label = "Speed",
            value = settings.rate,
            default = Voice.DEFAULT_RATE,
            onChange = model::saveVoiceRate,
        )

        Button(
            onClick = { model.previewVoice() },
            colors = ButtonDefaults.buttonColors(
                containerColor = PANEL,
                contentColor = Color(0xFF9BE9F2),
            ),
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
        ) { Text("Say something") }

        Note(
            "Greek uses a different voice and will sound like another person, " +
                "because it is one. These settings do not apply to it — nothing " +
                "free fixes that."
        )
    }
}

/**
 * One slider, with its number and a way back.
 *
 * The readout matters: a bare slider gives you no way to return to the value
 * that was recommended, and these two interact enough that "somewhere near the
 * middle" is not good enough once you have moved both.
 *
 * The value is held locally during a drag and committed on release. Compose
 * calls `onValueChange` on every frame, and committing there would mean a
 * preferences write *and* a full re-read of the phone's voice list per pixel
 * dragged -- which would stutter the drag it is meant to be driving.
 */
@Composable
private fun Dial(label: String, value: Float, default: Float, onChange: (Float) -> Unit) {
    var live by remember(value) { mutableStateOf(value) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = DIM, fontSize = 12.sp, modifier = Modifier.width(52.dp))
        Text(
            String.format(Locale.UK, "%.2f", live),
            color = TEXT,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.width(44.dp),
        )
        if (live != default) {
            Text(
                "reset",
                color = CYAN,
                fontSize = 11.sp,
                modifier = Modifier
                    .clickable { onChange(default) }
                    .padding(horizontal = 4.dp, vertical = 6.dp),
            )
        }
    }
    Slider(
        value = live,
        onValueChange = { live = it },
        onValueChangeFinished = { onChange(live) },
        // Continuous rather than stepped. 0.05 steps would be coarse for
        // something perceptual, and the recommended speed of 0.96 does not sit
        // on a 0.05 grid -- so "reset" could never land exactly on it. The
        // committed value is rounded to two places instead.
        valueRange = Memory.VOICE_RANGE,
        colors = SliderDefaults.colors(
            thumbColor = CYAN,
            activeTrackColor = CYAN.copy(alpha = 0.55f),
            inactiveTrackColor = EDGE,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}

// --------------------------------------------------------------------------
// Pieces
// --------------------------------------------------------------------------

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Spacer(Modifier.height(24.dp))
    Text(
        title,
        color = CYAN,
        fontSize = 15.sp,
        fontWeight = FontWeight.Light,
        letterSpacing = 2.sp,
    )
    Spacer(Modifier.height(4.dp))
    content()
}

@Composable
private fun Subhead(text: String) {
    Text(
        text,
        color = TEXT,
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(bottom = 4.dp),
    )
}

@Composable
private fun Note(text: String) {
    Text(
        text,
        color = FAINT,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        modifier = Modifier.padding(top = 4.dp, bottom = 2.dp),
    )
}

@Composable
private fun Warning(text: String) {
    Text(
        "⚠  $text",
        color = WARN,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        modifier = Modifier.padding(top = 6.dp),
    )
}

@Composable
private fun Empty(text: String) {
    Text(
        text,
        color = GHOST,
        fontSize = 11.sp,
        modifier = Modifier.padding(vertical = 6.dp),
    )
}

/** One configured thing: what it is, what it says, and a way to remove it. */
@Composable
private fun Line(
    label: String,
    value: String,
    valueColour: Color = TEXT,
    onRemove: (() -> Unit)? = null,
) {
    Surface(
        color = PANEL.copy(alpha = 0.6f),
        shape = RoundedCornerShape(6.dp),
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, color = TEXT, fontSize = 13.sp, modifier = Modifier.weight(1f))
            Text(
                value,
                color = valueColour,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
            )
            if (onRemove != null) {
                Text(
                    "✕",
                    color = BAD.copy(alpha = 0.8f),
                    fontSize = 14.sp,
                    modifier = Modifier
                        .clickable(onClick = onRemove)
                        .padding(start = 14.dp, top = 2.dp, bottom = 2.dp),
                )
            }
        }
    }
}

/** A radio row, without pulling in a radio button. */
@Composable
private fun Choice(label: String, detail: String?, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (selected) "●" else "○",
            color = if (selected) CYAN else EDGE,
            fontSize = 13.sp,
            modifier = Modifier.width(24.dp),
        )
        Text(
            label,
            color = if (selected) TEXT else DIM,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
        )
        if (detail != null) {
            Spacer(Modifier.width(8.dp))
            Text(detail, color = GHOST, fontSize = 11.sp)
        }
    }
}

/**
 * A secret, typed in and never shown back.
 *
 * Typed rather than pasted into a settings file, for the reason the Python read
 * it from stdin and never from an argument: a secret that lands anywhere
 * quotable ends up in a bug report eventually. Saving an empty field clears it,
 * which is the only way back to not having one.
 */
@Composable
private fun Secret(
    placeholder: String,
    isSet: Boolean,
    setLabel: String,
    onSave: (String) -> Unit,
) {
    var value by remember { mutableStateOf("") }

    Text(
        if (isSet) "✓  $setLabel" else "not set",
        color = if (isSet) GOOD else WARN,
        fontSize = 11.sp,
        fontFamily = FontFamily.Monospace,
        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
    )
    OutlinedTextField(
        value = value,
        onValueChange = { value = it },
        placeholder = { Text(placeholder, color = GHOST, fontSize = 13.sp) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier.fillMaxWidth(),
        colors = fieldColours(),
    )
    Button(
        onClick = {
            onSave(value)
            value = ""
        },
        colors = ButtonDefaults.buttonColors(
            containerColor = PANEL,
            contentColor = Color(0xFF9BE9F2),
        ),
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
    ) { Text(if (value.isBlank()) "Clear" else "Save") }
}

@Composable
private fun Field(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        placeholder = { Text(placeholder, color = GHOST, fontSize = 13.sp) },
        singleLine = true,
        modifier = modifier,
        colors = fieldColours(),
    )
}

@Composable
private fun fieldColours() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = TEXT,
    unfocusedTextColor = TEXT,
    focusedBorderColor = CYAN.copy(alpha = 0.4f),
    unfocusedBorderColor = EDGE,
    cursorColor = CYAN,
)

/**
 * An amount with its cents.
 *
 * Not the spoken `formatMoney`, which rounds to whole euros on purpose --
 * nobody asking "how much money do I have" wants to hear "and forty-three
 * cents". Here you are checking a figure against a bank app, so the cents are
 * the whole point.
 */
private fun money(amount: Double) = String.format(Locale.UK, "€%,.2f", amount)
