package app.nisos.ui

import android.content.Context
import android.content.ContextWrapper
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import app.nisos.android.Lock

/**
 * The door.
 *
 * Shown instead of the assistant while the app is locked, and it asks
 * immediately rather than waiting for a tap -- one gesture to open the app
 * should not become two. The button is there for after a cancellation, which is
 * the only time you would want to ask again by hand.
 *
 * Nothing behind this is loaded lazily; the assistant screen is simply not
 * composed. That is not a security boundary and is not claimed as one -- the
 * data is protected by app-private storage either way. What this stops is
 * somebody with your unlocked phone reading your balances and your calendar,
 * which is exactly the gap `README` has always admitted to.
 */
@Composable
fun LockScreen(onUnlocked: () -> Unit) {
    val context = LocalContext.current
    val activity = remember(context) { context.hostActivity() }
    val strength = remember(context) { Lock.strength(context) }

    var refused by remember { mutableStateOf<String?>(null) }
    var asking by remember { mutableStateOf(false) }

    val ask: () -> Unit = {
        if (activity == null || strength == null) {
            // Should be unreachable: the caller only locks when strength is
            // non-null. Opening rather than trapping, because an app you cannot
            // get into is worse than one that did not lock.
            onUnlocked()
        } else if (!asking) {
            asking = true
            authenticate(
                activity = activity,
                strength = strength,
                title = "nisos",
                subtitle = "Unlock to continue",
            ) { ok, message ->
                asking = false
                if (ok) onUnlocked() else refused = message
            }
        }
    }

    // Ask on arrival. Keyed to Unit so returning from the system prompt does not
    // re-trigger it -- `asking` guards a double call in any case.
    LaunchedEffect(Unit) { ask() }

    Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF05070C)) {
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    listOf(Color(0xFF05070C), Color(0xFF0A1018), Color(0xFF05070C))
                )
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .safeDrawingPadding()
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    "nisos",
                    color = Color(0xFF35E0F0),
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Light,
                    letterSpacing = 8.sp,
                )
                Spacer(Modifier.height(18.dp))
                Text(
                    "locked",
                    color = Color(0xFF4A5D6E),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 2.sp,
                )

                if (refused != null) {
                    Spacer(Modifier.height(24.dp))
                    Text(
                        refused.orEmpty(),
                        color = Color(0xFFC79A3A),
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center,
                        lineHeight = 18.sp,
                    )
                }

                Spacer(Modifier.height(32.dp))
                Button(
                    onClick = ask,
                    enabled = !asking,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF35E0F0).copy(alpha = 0.16f),
                        contentColor = Color(0xFF9BE9F2),
                        disabledContainerColor = Color(0xFF16202A),
                        disabledContentColor = Color(0xFF3A4A5A),
                    ),
                    shape = RoundedCornerShape(28.dp),
                    modifier = Modifier.fillMaxWidth().height(54.dp),
                ) { Text("Unlock", fontSize = 15.sp, letterSpacing = 2.sp) }
            }
        }
    }
}

/**
 * Ask who you are, once.
 *
 * @param strength from [Lock.strength]. Handed in rather than looked up so the
 *   settings screen and the lock screen provably ask the same question.
 * @param onResult true on success. On failure the message is the system's own
 *   wording, which names the real reason better than anything invented here.
 */
internal fun authenticate(
    activity: FragmentActivity,
    strength: Int,
    title: String,
    subtitle: String,
    onResult: (Boolean, String?) -> Unit,
) {
    val prompt = BiometricPrompt(
        activity,
        ContextCompat.getMainExecutor(activity),
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                onResult(true, null)
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                onResult(false, errString.toString())
            }
            // onAuthenticationFailed is deliberately not overridden. One
            // unrecognised finger is not a result -- the system prompt stays up
            // and lets you try again, and reporting it here would tear the
            // prompt down on a smudge.
        },
    )

    val info = BiometricPrompt.PromptInfo.Builder()
        .setTitle(title)
        .setSubtitle(subtitle)
        .setAllowedAuthenticators(strength)

    // A gotcha with teeth: the builder *throws* if a negative button is set
    // alongside DEVICE_CREDENTIAL, and equally throws if one is missing when
    // only a biometric is allowed. It has to be conditional.
    if (!Lock.includesCredential(strength)) info.setNegativeButtonText("Cancel")

    prompt.authenticate(info.build())
}

/**
 * Walk out to the Activity hosting this composable.
 *
 * `BiometricPrompt` needs a `FragmentActivity` -- it shows a real dialog
 * fragment -- and `LocalContext` is not guaranteed to be one directly, since
 * Compose may hand back a themed wrapper.
 */
internal fun Context.hostActivity(): FragmentActivity? {
    var context: Context? = this
    while (context is ContextWrapper) {
        if (context is FragmentActivity) return context
        context = context.baseContext
    }
    return null
}
