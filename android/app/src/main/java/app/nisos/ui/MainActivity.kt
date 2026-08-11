package app.nisos.ui

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
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

                Assistant(model)
            }
        }
    }
}

@Composable
private fun Assistant(model: AssistantViewModel) {
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
                    // language, key -- sits underneath the navigation bar,
                    // half legible and completely untappable, which is exactly
                    // how it shipped and exactly what the first run showed.
                    .safeDrawingPadding()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Header(state)

                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Reactor(mood = state.mood, level = state.level)
                    Spacer(Modifier.height(28.dp))
                    Heard(state)
                }

                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Did(state)
                    Spacer(Modifier.height(20.dp))
                    Controls(model, state)
                }
            }
        }
    }
}

@Composable
private fun Header(state: AssistantState) {
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
        Text(
            state.brainLabel,
            color = Color(0xFF4A5D6E),
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            letterSpacing = 1.sp,
        )
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
    var key by remember { mutableStateOf("") }
    var keying by remember { mutableStateOf(false) }

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
        Spacer(Modifier.width(8.dp))
        TextButton(onClick = { keying = !keying }) {
            Text(
                if (model.hasKey) "key ✓" else "key",
                color = if (model.hasKey) Color(0xFF3A4A5A) else Color(0xFF8A6A2A),
                fontSize = 12.sp,
            )
        }
    }

    if (keying) {
        // Typed in rather than pasted into a settings file, for the reason the
        // Python read it from stdin and never from an argument: a secret that
        // lands anywhere quotable ends up in a bug report eventually.
        OutlinedTextField(
            value = key,
            onValueChange = { key = it },
            placeholder = { Text("sk-ant-…", color = Color(0xFF2A3A4A)) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
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
                model.saveKey(key)
                key = ""
                keying = false
            },
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFF16202A),
                contentColor = Color(0xFF9BE9F2),
            ),
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        ) { Text(if (key.isBlank()) "Clear key" else "Save key") }
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
