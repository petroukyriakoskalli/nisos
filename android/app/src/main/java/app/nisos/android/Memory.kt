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

    private companion object {
        const val KEY = "anthropic-key"
        const val FACT = "fact:"
        const val CONTACT = "contact:"
        const val ALIAS = "alias:"
        const val MONEY = "money:"
        const val MONEY_AT = "money-at:"
        const val SMS_SENDERS = "sms-senders"
        const val WISE = "wise-token"
    }
}

/** Read a JSON string field without throwing when it isn't there. */
fun JSONObject.stringOrNull(name: String): String? =
    if (has(name) && !isNull(name)) optString(name).takeIf { it.isNotBlank() } else null
