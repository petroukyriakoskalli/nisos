package app.nisos.core

import java.time.Duration
import java.time.LocalDateTime

/**
 * "How much money do I have."
 *
 * The whole design is in one line: **every source here is incapable of moving
 * money.** A read-only token, an open-banking consent scoped to account
 * information, a message the bank already sent you, a figure you typed in.
 * Nothing that can make a payment, and above all no stored banking password.
 *
 * That is not caution for its own sake. Termux could not drive another app's
 * UI, banking apps block accessibility services, and a plaintext credential
 * folder next to a public repository is unacceptable -- but the real argument
 * is simpler than any of those. A credential that can move money, sitting on a
 * phone behind a screen lock, is a different risk category from a token that
 * can only read a number, and the assistant gains **nothing** from the
 * difference. It only ever needed to read.
 *
 * ## What a source is
 *
 * One class with one method, returning null when it has nothing to say. A
 * source that cannot answer is never an error -- no token, no network, no
 * recent message are all ordinary -- it is simply left out of the sum. The
 * reply says how many sources answered out of how many exist, so an account
 * that has quietly gone missing is visible rather than silent, which is the
 * only failure mode that actually matters when the number is the answer.
 *
 * ## Why the total is not one number
 *
 * Balances arrive in whatever currency the account holds, and converting them
 * needs a live rate this app does not have and should not guess. So the sum is
 * **per currency**: EUR is spoken, anything else is mentioned as a count. A
 * wrong single number would be worse than an honest two-part answer.
 */

/** An amount in one currency, at a point in time. */
data class Balance(
    val amount: Double,
    val currency: String = "EUR",
    /** When the number was true. Used to say "as of" for stale sources. */
    val asOf: LocalDateTime? = null,
)

/**
 * Somewhere money sits.
 *
 * @property id short, stable, lowercase -- it names the source in the reply
 *   and in whatever stores its settings.
 * @property label what you would call it out loud.
 */
interface MoneySource {
    val id: String
    val label: String

    /**
     * The balance, or null when this source cannot answer right now.
     *
     * Must not throw. A source that raises takes the whole total down with it,
     * which turns one unreachable bank into "that didn't work" for four
     * accounts that were perfectly reachable.
     */
    fun read(): Balance?
}

/**
 * What [total] worked out.
 *
 * @property answered how many sources produced a number.
 * @property asked how many exist. The gap is the point: three of four is a
 *   different answer from three of three and must not sound the same.
 * @property stalest the oldest reading that went into the sum, if any source
 *   reported one.
 */
data class Wealth(
    val byCurrency: Map<String, Double>,
    val answered: Int,
    val asked: Int,
    val stalest: LocalDateTime? = null,
) {
    val primary: String get() = if (byCurrency.containsKey("EUR")) "EUR" else
        byCurrency.maxByOrNull { it.value }?.key ?: "EUR"

    val primaryAmount: Double get() = byCurrency[primary] ?: 0.0

    /** Currencies other than [primary], for the "and some dollars" clause. */
    val others: List<String> get() = byCurrency.keys.filter { it != primary }.sorted()
}

/**
 * Add up every source that answers.
 *
 * Sources are read in order and one that misbehaves is skipped rather than
 * allowed to take the total with it.
 */
fun total(sources: List<MoneySource>, now: LocalDateTime? = null): Wealth {
    val sums = mutableMapOf<String, Double>()
    var answered = 0
    var stalest: LocalDateTime? = null

    for (source in sources) {
        val balance = try {
            source.read()
        } catch (_: Exception) {
            null
        } ?: continue

        answered++
        sums[balance.currency] = (sums[balance.currency] ?: 0.0) + balance.amount
        val at = balance.asOf
        if (at != null && (stalest == null || at.isBefore(stalest))) stalest = at
    }

    return Wealth(sums, answered, sources.size, stalest)
}

/**
 * How old a reading has to be before the reply admits it.
 *
 * Six hours, because a balance from this morning is a fine answer to "how much
 * money do I have" and one from last Tuesday is not.
 */
val STALE_AFTER: Duration = Duration.ofHours(6)

// --------------------------------------------------------------------------
// Sources
// --------------------------------------------------------------------------

/**
 * A figure you told it.
 *
 * The unglamorous one, and the one that covers the accounts nothing else can
 * reach. **myEurolife has no public API** -- like most insurance and pension
 * portals -- so a policy value gets in here or it does not get in at all.
 *
 * «θυμήσου ότι το eurolife είναι 12000» stores it; the number then counts
 * towards the total until you say a different one. It is exactly as accurate
 * as the last time you looked, which is why [Balance.asOf] is set and why the
 * reply says "as of" once it is old enough to matter.
 */
class ManualSource(
    override val id: String,
    override val label: String,
    private val lookup: (String) -> Pair<Double, LocalDateTime?>?,
) : MoneySource {
    override fun read(): Balance? {
        val (amount, at) = lookup(id) ?: return null
        return Balance(amount, "EUR", at)
    }
}

/**
 * The balance out of a bank's own SMS.
 *
 * Cyprus banks text you after a card transaction and most of those messages
 * carry the running balance. Reading them needs no API, no consent flow and no
 * account with anybody -- the message is already on the phone, sent by the
 * bank, addressed to you.
 *
 * ⚠️ It is a *permission* though, and a serious one: `READ_SMS` is access to
 * every message on the device. It stays off until you turn this source on, and
 * the parser below only ever looks at messages from senders you have named.
 *
 * The parsing is deliberately narrow. A number in a text message could be an
 * amount, a card's last four digits, a date or a reference; the patterns here
 * require a balance *word* next to it, in either language, and give up rather
 * than guess. A wrong balance stated confidently is worse than no balance.
 */
class SmsBalanceSource(
    override val id: String,
    override val label: String,
    /** Recent messages from this bank, newest first: (body, received). */
    private val messages: () -> List<Pair<String, LocalDateTime>>,
) : MoneySource {

    override fun read(): Balance? {
        for ((body, at) in messages()) {
            val found = parseBalance(body) ?: continue
            return found.copy(asOf = at)
        }
        return null
    }

    companion object {
        /**
         * Words a bank puts next to the number that is your balance.
         *
         * Greek and English both, because a Cyprus bank will use either
         * depending on which one it thinks you speak.
         */
        private val BALANCE_WORD = unicodePattern(
            "(?i)(?:available\\s+balance|current\\s+balance|balance|υπολοιπο|" +
                "υπόλοιπο|διαθεσιμο|διαθέσιμο)"
        )

        /** EUR 1.234,56 · €1234.56 · 1,234.56 EUR — all of them appear. */
        private val AMOUNT = unicodePattern(
            "(?:EUR|€)\\s*([0-9][0-9.,\\s]*[0-9]|[0-9])|([0-9][0-9.,]*[0-9]|[0-9])\\s*(?:EUR|€)"
        )

        /**
         * Pull a balance out of one message, or null.
         *
         * Requires the balance word and the amount to be near each other --
         * within [WINDOW] characters. A transaction text names two numbers,
         * the amount spent and the balance left, and taking the wrong one is
         * the whole failure mode this guards against.
         */
        fun parseBalance(body: String): Balance? {
            val word = BALANCE_WORD.find(body) ?: return null
            val after = body.substring(word.range.last + 1)
                .take(WINDOW)
            val amount = AMOUNT.find(after) ?: return null
            val digits = amount.groupValues[1].ifEmpty { amount.groupValues[2] }
            val value = europeanNumber(digits) ?: return null
            return Balance(value, "EUR")
        }

        private const val WINDOW = 40

        /**
         * Read a number written either way round.
         *
         * `1.234,56` is Greek and `1,234.56` is English and both turn up in
         * messages from the same bank. Whichever separator comes **last** is
         * the decimal point; if there is only one and it splits exactly three
         * trailing digits, it is a thousands separator.
         */
        fun europeanNumber(raw: String): Double? {
            val text = raw.replace(" ", "")
            if (text.isEmpty()) return null

            val lastDot = text.lastIndexOf('.')
            val lastComma = text.lastIndexOf(',')

            val cleaned = when {
                lastDot == -1 && lastComma == -1 -> text
                lastComma > lastDot -> text.replace(".", "").replace(',', '.')
                lastDot > lastComma -> text.replace(",", "")
                else -> text
            }

            // A lone separator with exactly three digits after it is thousands,
            // not decimals: "1.234" is one thousand two hundred, not 1.234.
            val single = if (lastDot == -1) lastComma else if (lastComma == -1) lastDot else -1
            if (single != -1 && text.length - single - 1 == 3) {
                return text.replace(".", "").replace(",", "").toDoubleOrNull()
            }
            return cleaned.toDoubleOrNull()
        }
    }
}

/**
 * An account reachable with a read-only token over HTTP.
 *
 * Wise is the one that fits this shape today: a personal API token created in
 * the app, scoped to read, revocable from the same screen. Revolut and the
 * Cyprus banks do not offer personal tokens and have to come through open
 * banking instead -- see the note in EXTENDING.md.
 *
 * The fetch is injected rather than performed here so this stays in `core/`
 * and stays testable: the tests hand in a function returning a fixed body.
 */
class TokenSource(
    override val id: String,
    override val label: String,
    /** Returns the balance, or null for no token / no network / a refusal. */
    private val fetch: () -> Balance?,
) : MoneySource {
    override fun read(): Balance? = fetch()
}
