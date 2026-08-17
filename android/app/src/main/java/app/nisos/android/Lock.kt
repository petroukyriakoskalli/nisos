package app.nisos.android

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL

/**
 * Whether this phone can ask who you are, and how.
 *
 * The app holds an API key, every balance you have configured, and the contents
 * of your calendar. `README` has always been honest that app-private storage
 * protects those from *other apps* and not from someone holding your unlocked
 * phone. This is the part that addresses the second half.
 *
 * ## Why a ladder rather than one constant
 *
 * `BIOMETRIC_STRONG or DEVICE_CREDENTIAL` is the combination you want -- a
 * fingerprint, falling back to the PIN or password in the same prompt -- and it
 * is unsupported on a stretch of older API levels, where `canAuthenticate`
 * simply refuses. Rather than branch on `Build.VERSION`, ask the device what it
 * will accept and take the first answer. It gets the right thing on a modern
 * phone and degrades in a defined order on anything else.
 *
 * ## Why null matters
 *
 * Null means this phone has no fingerprint enrolled and no screen lock, so it
 * cannot be asked. Every caller treats that as **do not lock** — see
 * [Memory.lockEnabled]. That is deliberate: this app is sideloaded, and an app
 * you can never open again is not a security feature. The consequence, stated
 * plainly rather than buried: turning your screen lock off turns this off too.
 */
object Lock {

    /**
     * Preferred first. A fingerprint with a PIN fallback, then the same with the
     * weaker biometric class, then the PIN alone, then a biometric alone.
     */
    private val LADDER = listOf(
        BIOMETRIC_STRONG or DEVICE_CREDENTIAL,
        BIOMETRIC_WEAK or DEVICE_CREDENTIAL,
        DEVICE_CREDENTIAL,
        BIOMETRIC_STRONG,
        BIOMETRIC_WEAK,
    )

    /**
     * The best set of authenticators this phone will accept, or null for none.
     *
     * @return a value to hand to `setAllowedAuthenticators`.
     */
    fun strength(context: Context): Int? = try {
        val manager = BiometricManager.from(context)
        LADDER.firstOrNull {
            manager.canAuthenticate(it) == BiometricManager.BIOMETRIC_SUCCESS
        }
    } catch (_: Throwable) {
        // Throwable, not Exception. This runs during `onCreate` on whatever
        // biometric stack the vendor shipped, and the failures that matter there
        // are Errors -- a missing class, a linkage problem -- which an
        // `Exception` catch would let through and turn into a crash on launch.
        // A device that throws when asked whether it can authenticate is a device
        // that cannot; failing to null keeps the app openable.
        null
    }

    /** True when this prompt will include the PIN, pattern or password. */
    fun includesCredential(strength: Int): Boolean = strength and DEVICE_CREDENTIAL != 0
}
