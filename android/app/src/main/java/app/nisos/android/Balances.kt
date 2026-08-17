package app.nisos.android

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Telephony
import androidx.core.content.ContextCompat
import app.nisos.core.Balance
import app.nisos.core.ManualSource
import app.nisos.core.MoneySource
import app.nisos.core.SmsBalanceSource
import app.nisos.core.TokenSource
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Where the balances actually come from, on a real phone.
 *
 * The rule from `core/Money.kt` holds here without exception: **nothing in
 * this file can move money.** A revocable read-only token, and messages the
 * bank already sent you. No password is stored, no login is automated, and no
 * app's UI is driven.
 *
 * Which account needs which source, for Cyprus:
 *
 * | | Route | Why |
 * |---|---|---|
 * | Bank of Cyprus, Hellenic, Alpha | [smsSources] | No personal API. They text you a balance after every card transaction, and that message is already on the phone. |
 * | Wise | [wiseSource] | A read-only personal token you create in the app and can revoke from the same screen. |
 * | Revolut | not yet | No personal API. Needs open banking — see EXTENDING.md. |
 * | myEurolife | [manualSources] | Insurance and pension portals have no public API. A figure you tell it, or nothing. |
 */
object Balances {

    /** Everything configured, in the order it will be summed. */
    fun sources(context: Context, memory: Memory): List<MoneySource> =
        manualSources(memory) + smsSources(context, memory) + listOfNotNull(wiseSource(memory))

    // ----------------------------------------------------------------------

    /**
     * Figures you told it.
     *
     * One source per account you have named, so «πόσα λεφτά έχω» counts the
     * Eurolife policy alongside everything else rather than treating it as a
     * different kind of number.
     */
    fun manualSources(memory: Memory): List<MoneySource> =
        memory.balanceAccounts().map { account ->
            ManualSource(account, account) { memory.balance(it) }
        }

    /**
     * Banks that text you a balance.
     *
     * ⚠️ `READ_SMS` is access to every message on the phone, so it is
     * requested only when this source is switched on, never at launch, and the
     * query below is filtered to the senders you named. Nothing is uploaded:
     * the parsing is local and the number never leaves the device except as
     * part of a total you asked for out loud.
     */
    fun smsSources(context: Context, memory: Memory): List<MoneySource> {
        val senders = memory.smsSenders()
        if (senders.isEmpty()) return emptyList()
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS)
            != PackageManager.PERMISSION_GRANTED
        ) return emptyList()

        return senders.map { sender ->
            SmsBalanceSource(sender.lowercase(), sender) { recentFrom(context, sender) }
        }
    }

    /**
     * The last few messages from one sender, newest first.
     *
     * Capped at [MESSAGE_LIMIT]. A balance older than the last handful of
     * transactions is not worth reporting, and reading the whole inbox to find
     * one is both slow and far more access than the job needs.
     */
    private fun recentFrom(context: Context, sender: String): List<Pair<String, LocalDateTime>> {
        val found = mutableListOf<Pair<String, LocalDateTime>>()
        try {
            context.contentResolver.query(
                Telephony.Sms.Inbox.CONTENT_URI,
                arrayOf(Telephony.Sms.BODY, Telephony.Sms.DATE),
                "${Telephony.Sms.ADDRESS} LIKE ?",
                arrayOf("%$sender%"),
                "${Telephony.Sms.DATE} DESC LIMIT $MESSAGE_LIMIT",
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val body = cursor.getString(0) ?: continue
                    val at = Instant.ofEpochMilli(cursor.getLong(1))
                        .atZone(ZoneId.systemDefault()).toLocalDateTime()
                    found += body to at
                }
            }
        } catch (_: Exception) {
            // A provider that refuses is a source that cannot answer, not an
            // error -- see the contract on MoneySource.read.
        }
        return found
    }

    private const val MESSAGE_LIMIT = 20

    // ----------------------------------------------------------------------

    /**
     * Wise, through a read-only personal token.
     *
     * Settings → Integrations and tools → API tokens, in the Wise app. Make it
     * **read-only**; a full-access token could move money and this app has no
     * use for one that can.
     *
     * ⚠️ **This request has never been made against the real API.** The shape
     * follows Wise's documented v4 balances endpoint, and the failure mode is
     * deliberately gentle: anything unexpected returns null, the source is
     * skipped, and the spoken total says "3 of 4" instead of pretending. That
     * is why it is safe to ship unverified — it can be wrong, but it cannot be
     * wrong *quietly*.
     */
    fun wiseSource(memory: Memory): MoneySource? {
        val token = memory.wiseToken ?: return null
        return TokenSource("wise", "Wise") { readWise(token) }
    }

    private fun readWise(token: String): Balance? = try {
        val profiles = JSONArray(getJson("https://api.transferwise.com/v2/profiles", token))
        val profileId = (0 until profiles.length())
            .mapNotNull { profiles.optJSONObject(it) }
            .firstOrNull { it.optString("type") == "PERSONAL" }
            ?.optLong("id")

        if (profileId == null) null else {
            val body = getJson(
                "https://api.transferwise.com/v4/profiles/$profileId/balances?types=STANDARD",
                token,
            )
            val accounts = JSONArray(body)
            // Sum the EUR balances. Wise holds one per currency, and
            // converting the others needs a live rate this app does not have
            // and should not guess -- core/Money.kt keeps them separate.
            var euros = 0.0
            var found = false
            for (index in 0 until accounts.length()) {
                val amount = accounts.optJSONObject(index)?.optJSONObject("amount") ?: continue
                if (amount.optString("currency") != "EUR") continue
                euros += amount.optDouble("value", 0.0)
                found = true
            }
            if (found) Balance(euros, "EUR", LocalDateTime.now()) else null
        }
    } catch (_: Exception) {
        null
    }

    private fun getJson(url: String, token: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 12_000
            connection.readTimeout = 12_000
            connection.setRequestProperty("Authorization", "Bearer $token")
            connection.setRequestProperty("Accept", "application/json")
            if (connection.responseCode !in 200..299) throw IllegalStateException("HTTP ${connection.responseCode}")
            connection.inputStream.bufferedReader().use(BufferedReader::readText)
        } finally {
            connection.disconnect()
        }
    }
}

/** Kept so a future source can hand back a parsed object without importing json twice. */
internal fun JSONObject.doubleOrNull(name: String): Double? =
    if (has(name) && !isNull(name)) optDouble(name) else null
