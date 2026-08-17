package app.nisos.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * What it died of, on screen, copyable.
 *
 * Deliberately the plainest thing in the app: a `Text`, a scroll, and two
 * buttons. It builds no ViewModel, starts no speech engine and asks the
 * biometric service nothing — because it has to survive whatever killed the app,
 * and every one of those is a candidate. A crash screen that needs the same
 * machinery as the app would die of the same fault.
 *
 * The Copy button is the real feature. This app is developed with no `adb` and
 * no debugger, so a stack trace on a phone screen is unreachable unless it can
 * be put on the clipboard and pasted somewhere.
 */
@Composable
fun CrashScreen(trace: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var copied by remember { mutableStateOf(false) }

    Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF0C0607)) {
        Box(Modifier.fillMaxSize().background(Color(0xFF0C0607))) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .safeDrawingPadding()
                    .padding(20.dp),
            ) {
                Text(
                    "nisos stopped",
                    color = Color(0xFFE0483C),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Light,
                    letterSpacing = 3.sp,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "This is the last crash. Copy it and send it on — there is no " +
                        "other way to get it off the phone.",
                    color = Color(0xFF8A9AAA),
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                )
                Spacer(Modifier.height(14.dp))

                Surface(
                    color = Color(0xFF16202A).copy(alpha = 0.7f),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth().weight(1f),
                ) {
                    // Scrolls both ways: a stack trace has long lines and many
                    // of them, and wrapping them makes it unreadable.
                    Text(
                        trace,
                        color = Color(0xFFDCE8F0),
                        fontSize = 10.sp,
                        lineHeight = 14.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier
                            .verticalScroll(rememberScrollState())
                            .horizontalScroll(rememberScrollState())
                            .padding(10.dp),
                    )
                }

                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Button(
                        onClick = {
                            copyToClipboard(context, trace)
                            copied = true
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF35E0F0).copy(alpha = 0.18f),
                            contentColor = Color(0xFF9BE9F2),
                        ),
                        shape = RoundedCornerShape(24.dp),
                        modifier = Modifier.weight(1f).height(48.dp),
                    ) { Text(if (copied) "Copied" else "Copy", fontSize = 14.sp) }

                    Button(
                        onClick = onDismiss,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF16202A),
                            contentColor = Color(0xFF8A9AAA),
                        ),
                        shape = RoundedCornerShape(24.dp),
                        modifier = Modifier.weight(1f).height(48.dp),
                    ) { Text("Try again", fontSize = 14.sp) }
                }
            }
        }
    }
}

private fun copyToClipboard(context: Context, text: String) {
    try {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("nisos crash", text))
    } catch (_: Throwable) {
        // Nothing useful to do about it, and this screen must not itself crash.
    }
}
