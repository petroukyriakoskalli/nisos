package app.nisos.android

import android.content.Context
import org.json.JSONObject

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
    }
}

/** Read a JSON string field without throwing when it isn't there. */
fun JSONObject.stringOrNull(name: String): String? =
    if (has(name) && !isNull(name)) optString(name).takeIf { it.isNotBlank() } else null
