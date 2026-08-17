package app.nisos.ui

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * The one screen.
 *
 * Deliberately not a chat transcript. An assistant you talk to is not a
 * messaging app, and a scrolling history of everything you have ever said
 * makes the useful thing -- what it just did -- the smallest element on
 * screen. What matters is: is it hearing me, what did it understand, what did
 * it do. In that order, largest first.
 *
 * Settings are a second screen rather than more rows on this one, because this
 * screen is for *operating* the assistant and a token you paste once a year is
 * not an operation. See [SettingsScreen].
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            NisosTheme {
                val model: AssistantViewModel = viewModel(
                    factory = AssistantViewModel.factory(applicationContext)
                )

                val microphone = androidx.activity.compose.rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestMultiplePermissions()
                ) { /* the turn itself reports anything still missing, out loud */ }

                androidx.compose.runtime.LaunchedEffect(Unit) {
                    microphone.launch(
                        arrayOf(
                            Manifest.permission.RECORD_AUDIO,
                            Manifest.permission.READ_CALENDAR,
                            Manifest.permission.WRITE_CALENDAR,
                            Manifest.permission.READ_CONTACTS,
                            Manifest.permission.SEND_SMS,
                        )
                    )
                }

                var settings by remember { mutableStateOf(false) }

                // Without this, the phone's back gesture closes the app from
                // the settings screen instead of the screen -- which loses
                // whatever you were half way through typing.
                BackHandler(enabled = settings) { settings = false }

                if (settings) {
                    SettingsScreen(model, onBack = { settings = false })
                } else {
                    Assistant(model, onSettings = { settings = true })
                }
            }
        }
    }
}

@Composable
private fun Assistant(model: AssistantViewModel, onSettings: () -> Unit) {
    val state by model.state

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color(0xFF05070C),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(Color(0xFF05070C), Color(0xFF0A1018), Color(0xFF05070C))
                    )
                )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    // Android 15 draws every app edge to edge whether it asked
                    // to or not, so the window is taller than the part you can
                    // actually see and the layout has to subtract the system
                    // bars itself. Without this the bottom row -- type,
                    // language, settings -- sits underneath the navigation bar,
                    // half legible and completely untappable, which is exactly
                    // how it shipped and exactly what the first run showed.
                    //
                    // safeDrawing also covers the soft keyboard, which is why
                    // the middle section below has to be able to give way.
                    .safeDrawingPadding()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                // NOT SpaceBetween. That was the second layout bug found on a
                // phone: SpaceBetween divides the *leftover* space between
                // children, and when there is no leftover the share it hands
                // out goes negative -- so the three blocks were laid on top of
                // one another, and because a Column draws in order the
                // controls were painted over the ring and the text. The middle
                // block carries the weight instead; it absorbs whatever is
                // spare and shrinks when there is none, so there is never a
                // negative gap to distribute.
            ) {
                Header(state, onSettings)

                Middle(state)

                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    // The card replaces the reply rather than sitting under it.
                    // The question is already spoken and the card restates it,
                    // so showing both would say the same thing twice in the one
                    // place where the wording has to be read carefully.
                    if (state.pending.isNotEmpty()) Approval(model, state) else Did(state)
                    Spacer(Modifier.height(20.dp))
                    Controls(model, state)
                }
            }
        }
    }
}

/**
 * The ring and what it heard, in whatever room is left over.
 *
 * The sizing is the fix for the collision. A fixed 260dp ring cannot give way,
 * so when the keyboard came up or a panel opened something had to -- and what
 * gave way was the layout, silently, by overlapping. Here the ring is measured
 * against the space that actually exists: it shrinks, and below the point where
 * a ring would be more obstruction than ornament it stops being drawn at all.
 * Anything still too tall scrolls, which is the one outcome that cannot hide a
 * control behind a graphic.
 */
@Composable
private fun ColumnScope.Middle(state: AssistantState) {
    BoxWithConstraints(
        modifier = Modifier.weight(1f).fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        val diameter = minOf(260.dp, maxWidth - 16.dp, maxHeight * 0.60f)

        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (diameter >= 120.dp) {
                Reactor(mood = state.mood, level = state.level, diameter = diameter)
                Spacer(Modifier.height(28.dp))
            }
            Heard(state)
        }
    }
}

@Composable
private fun Header(state: AssistantState, onSettings: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "nisos",
            color = Color(0xFF35E0F0),
            fontSize = 18.sp,
            fontWeight = FontWeight.Light,
            letterSpacing = 6.sp,
        )
        // Says which brain, and whether there is one. "No key" is a state you
        // need to be able to see rather than discover by asking a question
        // the router happens not to know.
        //
        // It is also the way in to settings, which puts the door next to the
        // thing that makes you want it -- and up here rather than in the
        // bottom row, which is where the crowding was.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clickable(onClick = onSettings)
                .padding(start = 12.dp, top = 6.dp, bottom = 6.dp),
        ) {
            Text(
                state.brainLabel,
                color = Color(0xFF4A5D6E),
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 1.sp,
            )
            Spacer(Modifier.width(8.dp))
            Text("⚙", color = Color(0xFF4A5D6E), fontSize = 15.sp)
        }
    }
}

/** What it heard, live. The largest text on screen while you are talking. */
@Composable
private fun Heard(state: AssistantState) {
    Text(
        text = state.heard.ifBlank { state.hint },
        color = if (state.heard.isBlank()) Color(0xFF3A4A5A) else Color(0xFFDCE8F0),
        fontSize = if (state.heard.isBlank()) 15.sp else 22.sp,
        fontWeight = FontWeight.Light,
        textAlign = TextAlign.Center,
        lineHeight = 30.sp,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
    )
}

/** What it said, and a chip per action so you can see it did both. */
@Composable
private fun Did(state: AssistantState) {
    if (state.spoken.isNotBlank()) {
        Text(
            state.spoken,
            color = Color(0xFF35E0F0),
            fontSize = 17.sp,
            textAlign = TextAlign.Center,
            lineHeight = 25.sp,
            // Bounded because this block sits above the Speak button and has
            // no weight, so an unusually long reply would push the button off
            // the bottom of the screen. The reply is spoken; this is a
            // transcript of it, and three lines is enough of one.
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        )
        Spacer(Modifier.height(14.dp))
    }

    if (state.actions.isNotEmpty()) {
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(horizontal = 4.dp),
        ) {
            items(state.actions) { name -> ActionChip(name) }
        }
    }
}

/**
 * What it is about to write, and a way to stop it.
 *
 * The only action that waits is `calendar.add`, and it waits because it is the
 * only one that writes something durable into a place you will not look until
 * the day it matters -- from a time phrase that had to be *interpreted*. The
 * torch is undone by saying the opposite; a wrong appointment is found weeks
 * later by missing it.
 *
 * The question names the **weekday** rather than only the date, because that is
 * the part that catches «αύριο στις πέντε» landing on the wrong day. Both the
 * question and the write come from one parser, so this cannot show you one event
 * and file another.
 */
@Composable
private fun Approval(model: AssistantViewModel, state: AssistantState) {
    Surface(
        color = Color(0xFF35E0F0).copy(alpha = 0.07f),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            state.pending.forEach { waiting ->
                Text(
                    waiting.question,
                    color = Color(0xFF9BE9F2),
                    fontSize = 16.sp,
                    textAlign = TextAlign.Center,
                    lineHeight = 23.sp,
                )
                if (waiting.detail.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        waiting.detail,
                        color = Color(0xFF4A5D6E),
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                Spacer(Modifier.height(10.dp))
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // Declining is the wider, plainer button and comes first. The
                // whole point of the step is that it is easy not to write.
                Button(
                    onClick = { model.cancel() },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF16202A),
                        contentColor = Color(0xFF8A9AAA),
                    ),
                    shape = RoundedCornerShape(24.dp),
                    modifier = Modifier.weight(1f).height(46.dp),
                ) { Text("No", fontSize = 14.sp) }

                Button(
                    onClick = { model.confirm() },
                    enabled = !state.busy,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF35E0F0).copy(alpha = 0.22f),
                        contentColor = Color(0xFF9BE9F2),
                        disabledContainerColor = Color(0xFF16202A),
                        disabledContentColor = Color(0xFF3A4A5A),
                    ),
                    shape = RoundedCornerShape(24.dp),
                    modifier = Modifier.weight(1f).height(46.dp),
                ) { Text("Add", fontSize = 14.sp, letterSpacing = 1.sp) }
            }
        }
    }
}

/**
 * One action, named.
 *
 * These exist because a turn can now do two things, and "it did both" is not
 * something a spoken reply proves -- you hear one sentence either way. Two
 * chips is the proof.
 */
@Composable
private fun ActionChip(name: String) {
    Surface(
        color = Color(0xFF35E0F0).copy(alpha = 0.10f),
        shape = RoundedCornerShape(4.dp),
    ) {
        Text(
            name,
            color = Color(0xFF6FD3E0),
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
        )
    }
}

@Composable
private fun Controls(model: AssistantViewModel, state: AssistantState) {
    var typed by remember { mutableStateOf("") }
    var typing by remember { mutableStateOf(false) }

    Button(
        onClick = { model.listen() },
        enabled = !state.busy,
        colors = ButtonDefaults.buttonColors(
            containerColor = Color(0xFF35E0F0).copy(alpha = 0.16f),
            contentColor = Color(0xFF9BE9F2),
            disabledContainerColor = Color(0xFF16202A),
            disabledContentColor = Color(0xFF3A4A5A),
        ),
        shape = RoundedCornerShape(28.dp),
        modifier = Modifier.fillMaxWidth().height(56.dp),
    ) {
        Text(
            if (state.busy) state.busyLabel else "Speak",
            fontSize = 15.sp,
            letterSpacing = 2.sp,
        )
    }

    Spacer(Modifier.height(6.dp))

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Typing is not a fallback, it is how you develop this thing. Every
        // route and every reply can be exercised without saying a word out
        // loud on a bus, which is exactly what `--text` was for.
        TextButton(onClick = { typing = !typing }) {
            Text(
                if (typing) "hide" else "type",
                color = Color(0xFF3A4A5A),
                fontSize = 12.sp,
            )
        }
        Spacer(Modifier.width(8.dp))
        TextButton(onClick = { model.toggleLanguage() }) {
            Text(
                if (state.language == "el") "ελληνικά" else "english",
                color = Color(0xFF3A4A5A),
                fontSize = 12.sp,
            )
        }
        // The key used to be a third button here. It moved into settings: it is
        // configuration rather than an operation, the header already announces
        // when it is missing, and this row was one of the things crowding the
        // bottom of the screen.
    }

    if (typing) {
        OutlinedTextField(
            value = typed,
            onValueChange = { typed = it },
            placeholder = {
                Text("άναψε τον φακό και τι ώρα είναι", color = Color(0xFF2A3A4A))
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                focusedTextColor = Color(0xFFDCE8F0),
                unfocusedTextColor = Color(0xFFDCE8F0),
                focusedBorderColor = Color(0xFF35E0F0).copy(alpha = 0.4f),
                unfocusedBorderColor = Color(0xFF1E2A36),
                cursorColor = Color(0xFF35E0F0),
            ),
        )
        Button(
            onClick = {
                model.handleText(typed)
                typed = ""
            },
            enabled = typed.isNotBlank() && !state.busy,
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFF16202A),
                contentColor = Color(0xFF9BE9F2),
            ),
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        ) { Text("Run") }
    }
}

@Composable
fun NisosTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = androidx.compose.material3.darkColorScheme(
            primary = Color(0xFF35E0F0),
            background = Color(0xFF05070C),
            surface = Color(0xFF05070C),
        ),
        content = content,
    )
}
