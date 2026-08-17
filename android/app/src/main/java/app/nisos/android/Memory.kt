package app.nisos.android

import android.content.Context
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * What it has been told, and the API key.
 *
 * Two stores in one file because they share a lifetime and nothing else needs
 * them. Facts and contacts are ordinary preferences; the key is deliberately
 * kept apart, in its own file, for the same reason the Python kept it out of
 * `config.toml` -- settings are a thing people screenshot and paste into
 * messages when something breaks.
 *
 * ⚠️ **This is not encrypted at rest.** On Android that is a smaller claim
 * than it sounds: app-private storage is already unreadable by other apps
 * under a per-app UID, which is exactly the threat this needs to hold off. It
 * is not protection against someone with your unlocked phone or a rooted
 * device. `EncryptedSharedPreferences` would add that, at the cost of a
 * dependency and a keystore that can lock you out after a restore -- worth
 * revisiting when the key stops being the only secret here.
 */
class Memory(context: Context) {

    private val facts = context.getSharedPreferences("nisos-memory", Context.MODE_PRIVATE)
    private val secrets = context.getSharedPreferences("nisos-secrets", Context.MODE_PRIVATE)

    // -- the key -----------------------------------------------------------
    var apiKey: String?
        get() = secrets.getString(KEY, null)?.takeIf { it.isNotBlank() }
        set(value) = secrets.edit().apply {
            if (value.isNullOrBlank()) remove(KEY) else putString(KEY, value.trim())
        }.apply()

    val hasKey: Boolean get() = apiKey != null

    // -- facts -------------------------------------------------------------
    /** Store a fact, or a phone number if the value looks like one. */
    fun remember(key: String, value: String) {
        val normalised = key.lowercase().trim()
        if (looksLikeNumber(value)) {
            facts.edit().putString("$CONTACT$normalised", digitsOf(value)).apply()
        } else {
            facts.edit().putString("$FACT$normalised", value).apply()
        }
    }

    fun recall(key: String): String? {
        val normalised = key.lowercase().trim()
        return facts.getString("$FACT$normalised", null)
            ?: facts.getString("$CONTACT$normalised", null)
    }

    fun contact(name: String): String? =
        facts.getString("$CONTACT${name.lowercase().trim()}", null)

    /** A heard name mapped to a real contact name, for code-switching. */
    fun alias(name: String): String? =
        facts.getString("$ALIAS${name.lowercase().trim()}", null)

    fun setAlias(heard: String, real: String) {
        facts.edit().putString("$ALIAS${heard.lowercase().trim()}", real).apply()
    }

    fun forget(key: String): Boolean {
        val normalised = key.lowercase().trim()
        val existed = facts.contains("$FACT$normalised") || facts.contains("$CONTACT$normalised")
        facts.edit().remove("$FACT$normalised").remove("$CONTACT$normalised").apply()
        return existed
    }

    fun counts(): Pair<Int, Int> {
        val keys = facts.all.keys
        return keys.count { it.startsWith(FACT) } to keys.count { it.startsWith(CONTACT) }
    }

    fun everything(): Map<String, String> = facts.all
        .filterKeys { it.startsWith(FACT) }
        .mapKeys { it.key.removePrefix(FACT) }
        .mapValues { it.value.toString() }

    /**
     * The stored facts that look relevant to this utterance.
     *
     * Only a few, and only the ones whose key actually appears in what was
     * said. Sending the whole store would be paid for on every reasoned turn,
     * and Greek costs two to three times more tokens per word than English.
     */
    fun relevant(text: String, limit: Int = 5): Map<String, String> {
        val haystack = text.lowercase()
        return everything()
            .filterKeys { haystack.contains(it) }
            .entries.take(limit)
            .associate { it.key to it.value }
    }

    // -- money -------------------------------------------------------------
    /**
     * A figure you told it, with when you told it.
     *
     * The timestamp is the point. A manual balance is exactly as accurate as
     * the last time you looked, and the spoken total says "oldest 3/8" once it
     * is stale enough to matter -- which is the difference between a number
     * you can act on and one you merely hear.
     */
    fun setBalance(account: String, amount: Double) {
        val key = account.lowercase().trim()
        facts.edit()
            .putString("$MONEY$key", amount.toString())
            .putLong("$MONEY_AT$key", System.currentTimeMillis())
            .apply()
    }

    fun balance(account: String): Pair<Double, LocalDateTime?>? {
        val key = account.lowercase().trim()
        val amount = facts.getString("$MONEY$key", null)?.toDoubleOrNull() ?: return null
        val at = facts.getLong("$MONEY_AT$key", 0L).takeIf { it > 0 }?.let {
            Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDateTime()
        }
        return amount to at
    }

    fun balanceAccounts(): List<String> =
        facts.all.keys.filter { it.startsWith(MONEY) }.map { it.removePrefix(MONEY) }.sorted()

    /**
     * Drop a manual figure.
     *
     * Needed because a stale manual balance is worse than a missing one: it
     * still counts towards the total, so "you have €12,340" keeps quoting a
     * policy you cashed in months ago. Voice can add one but had no way to take
     * one back out -- the settings screen does.
     */
    fun forgetBalance(account: String): Boolean {
        val key = account.lowercase().trim()
        val existed = facts.contains("$MONEY$key")
        facts.edit().remove("$MONEY$key").remove("$MONEY_AT$key").apply()
        return existed
    }

    /** Senders whose texts get read for a balance. Empty means none, and
     *  READ_SMS is never requested. */
    fun smsSenders(): List<String> =
        facts.getStringSet(SMS_SENDERS, emptySet())?.sorted().orEmpty()

    fun setSmsSenders(senders: Collection<String>) {
        facts.edit().putStringSet(SMS_SENDERS, senders.map { it.trim() }.filter { it.isNotEmpty() }.toSet()).apply()
    }

    /** A read-only Wise token. Stored beside the API key, not in the facts. */
    var wiseToken: String?
        get() = secrets.getString(WISE, null)?.takeIf { it.isNotBlank() }
        set(value) = secrets.edit().apply {
            if (value.isNullOrBlank()) remove(WISE) else putString(WISE, value.trim())
        }.apply()

    // -- the lock ----------------------------------------------------------
    /**
     * Whether opening the app needs a fingerprint, PIN or password.
     *
     * Off until you turn it on, and the settings screen makes you authenticate
     * **before** it can be turned on. That ordering is the whole safety
     * argument: you cannot enable a lock you are unable to open, so there is no
     * path to being shut out of a sideloaded app with no recovery.
     *
     * Read together with [Lock.strength] -- a phone with nothing enrolled cannot
     * be asked, and is treated as unlocked rather than unopenable.
     */
    var lockEnabled: Boolean
        get() = facts.getBoolean(LOCK, false)
        set(value) = facts.edit().putBoolean(LOCK, value).apply()

    // -- the voice ---------------------------------------------------------
    /**
     * How it should sound, kept across restarts.
     *
     * [Voice] holds these as plain fields with sensible defaults, which was
     * fine while nothing could change them; the moment a screen can, they have
     * to outlive the process or every launch throws the choice away.
     *
     * Pitch and rate are here rather than hidden because they *are* the effect
     * -- see the argument in [Voice]: what makes that delivery recognisable is
     * an RP male voice pitched slightly down and slowed a touch, and which
     * "slightly" lands depends on the voice the phone actually has. The
     * defaults stay the recommendation; the range is deliberately narrow so a
     * slider cannot make it unlistenable.
     */
    var voiceName: String?
        get() = facts.getString(VOICE, null)?.takeIf { it.isNotBlank() }
        set(value) = facts.edit().apply {
            if (value.isNullOrBlank()) remove(VOICE) else putString(VOICE, value)
        }.apply()

    var voicePitch: Float
        get() = facts.getFloat(PITCH, Voice.DEFAULT_PITCH)
        set(value) = facts.edit().putFloat(PITCH, value.coerceIn(VOICE_RANGE)).apply()

    var voiceRate: Float
        get() = facts.getFloat(RATE, Voice.DEFAULT_RATE)
        set(value) = facts.edit().putFloat(RATE, value.coerceIn(VOICE_RANGE)).apply()

    // -- helpers -----------------------------------------------------------
    private fun digitsOf(value: String) = value.filter { it.isDigit() || it == '+' }

    /**
     * A "number" made mostly of letters is not one. Requiring the value to be
     * predominantly digits is what keeps «η Μαριλένα είναι στο σπίτι» out of
     * the contacts store.
     */
    private fun looksLikeNumber(value: String): Boolean {
        val digits = value.count { it.isDigit() }
        return digits >= maxOf(7, value.length / 2)
    }

    companion object {
        /**
         * How far the voice sliders may go.
         *
         * Narrow on purpose. Android accepts 0.5–2.0 for both, and most of that
         * range is unusable -- 2.0 pitch is a cartoon and 0.5 rate is a
         * recording played back wrong. This is the band either side of the
         * defaults where the register still sounds like a person.
         */
        val VOICE_RANGE = 0.7f..1.15f

        private const val KEY = "anthropic-key"
        private const val FACT = "fact:"
        private const val CONTACT = "contact:"
        private const val ALIAS = "alias:"
        private const val MONEY = "money:"
        private const val MONEY_AT = "money-at:"
        private const val SMS_SENDERS = "sms-senders"
        private const val WISE = "wise-token"
        private const val LOCK = "lock:enabled"
        private const val VOICE = "voice:name"
        private const val PITCH = "voice:pitch"
        private const val RATE = "voice:rate"
    }
}

/** Read a JSON string field without throwing when it isn't there. */
fun JSONObject.stringOrNull(name: String): String? =
    if (has(name) && !isNull(name)) optString(name).takeIf { it.isNotBlank() } else null
