package app.nisos.core

import java.time.Duration
import java.time.LocalDateTime
import java.time.format.TextStyle
import java.util.Locale

/**
 * The action catalogue -- what the assistant can actually do.
 *
 * A brilliant model with three actions is a chatbot. A mediocre model with
 * sixty actions is an assistant. This is the file worth spending time on.
 *
 * The Python version had two execution routes: `termux-api` shell commands,
 * and Tasker for everything Android would not let a Termux process do. **This
 * one has neither.** An app holds the permissions itself, so every action is a
 * direct call -- which is the whole reason the app exists, and why the
 * `tasker/` directory has no counterpart here.
 *
 * Action names are **always English**, in both languages. You speak Greek, the
 * system thinks in `torch.on`, and only the spoken reply comes back Greek.
 * Never translate the schema.
 *
 * Extending
 * ---------
 * 1. Here -- a lambda in [REGISTRY], and a method on [Phone] if it needs one.
 * 2. [ROUTES] in Router.kt -- one pattern per language.
 * 3. [SAY] in Replies.kt -- an `en` and an `el` phrase.
 *
 * The unit tests check 1-3 stay in step.
 */

/** One action and its arguments -- the unit a turn is built out of. */
data class Step(val action: String, val args: Map<String, Any?> = emptyMap())

/** An action failed in a way the user should hear about, not a crash. */
class ActionError(message: String) : Exception(message)

/** What one calendar entry looks like coming back off the phone. */
data class CalendarEntry(val summary: String, val minutesAway: Int)

/** Exactly the event a `calendar.add` would write. */
data class Proposal(val summary: String, val start: LocalDateTime, val minutes: Int) {
    val date: String
        get() = String.format(Locale.ROOT, "%02d/%02d", start.dayOfMonth, start.monthValue)
    val time: String
        get() = String.format(Locale.ROOT, "%02d:%02d", start.hour, start.minute)

    /**
     * The day of the week, named, in the language being spoken.
     *
     * The single most useful field in a confirmation. «αύριο στις πέντε» landing
     * on the wrong day is the failure this whole step exists to catch, and
     * "11/08" does not catch it -- you have to work out what day that is, which
     * is exactly the work nobody does. "Tuesday" catches it instantly.
     */
    fun weekday(language: String): String = start.dayOfWeek.getDisplayName(
        TextStyle.FULL,
        if (language == "el") Locale.forLanguageTag("el") else Locale.UK,
    )
}

/**
 * Read a `calendar.add`'s arguments into the event it would create.
 *
 * Pulled out of the action so that the confirmation and the write go through
 * **one** parser. That is the whole load-bearing property: a preview built from
 * a second parser is theatre -- it would show you the event you meant while the
 * action wrote a different one, and a confirmation that can disagree with what
 * it confirms is worse than no confirmation, because you would stop checking.
 *
 * The day it lands on and the hour it picks are the two things most likely to be
 * wrong, and both are invisible until you open the calendar.
 */
fun proposeEvent(args: Map<String, Any?>): Proposal {
    val summary = args.string("summary")
    if (summary.isEmpty()) throw ActionError("no title heard")
    val start = momentOf(args["start"])
        ?: throw ActionError("couldn't read the time ${args["start"]}")
    val minutes = (args.int("minutes") ?: DEFAULT_MINUTES).coerceAtLeast(1)
    return Proposal(summary, start, minutes)
}

/**
 * Actions that describe themselves before they happen, and the fields they
 * describe themselves with.
 *
 * Membership here is what makes an action need approval -- see [handle]. The two
 * are deliberately the same set: you cannot mark something as needing a
 * confirmation without also supplying the words to confirm it with, so there is
 * no way to end up with a dialogue that asks "are you sure?" about nothing in
 * particular.
 *
 * `calendar.add` is here because it is the only action that writes something
 * durable to a place you did not look. The torch is reversible by saying the
 * opposite; a message is gone but you dictated its text; an appointment is a
 * silent edit to a calendar you will not open until the day it matters, made
 * from a time phrase that had to be *interpreted*.
 *
 * To add one: put it here with a lambda returning its fields, then add
 * `<action>.confirm` and `<action>.detail` to [SAY] in both languages. A test
 * fails if you forget either.
 *
 * The lambda takes the language because a field can be a *word* rather than a
 * number -- the weekday is, and a confirmation that says "Tuesday" to someone
 * being answered in Greek is not a confirmation they will read carefully.
 */
val PREVIEW: Map<String, (Map<String, Any?>, String) -> Map<String, Any>> = mapOf(
    "calendar.add" to { args, language ->
        val event = proposeEvent(args)
        mapOf(
            "summary" to event.summary,
            "date" to event.date,
            "time" to event.time,
            "minutes" to event.minutes,
            "weekday" to event.weekday(language),
        )
    },
)

/**
 * Everything an action needs to reach the outside world.
 *
 * An interface rather than direct calls for exactly the reason the Python had
 * an `ExecutionContext`: it is what makes the action layer testable without a
 * phone. The unit tests hand in a recorder; the app hands in `AndroidPhone`.
 */
interface Phone {
    fun torch(on: Boolean)
    fun battery(): Pair<Int, String>
    fun startTimer(minutes: Int)
    fun sendSms(to: String, body: String)
    fun copyToClipboard(text: String)
    fun doNotDisturb(on: Boolean)
    fun setVolume(level: Int)
    fun nextEvent(): CalendarEntry?
    fun addEvent(summary: String, start: LocalDateTime, minutes: Int)
    fun openWhatsApp(number: String, body: String)

    /** A phone number for a spoken name, from contacts or from memory. */
    fun lookupNumber(name: String): String?

    /**
     * Every configured balance source. Empty is a legitimate answer and means
     * nothing has been set up yet, which the reply says out loud rather than
     * reporting a confident zero.
     */
    fun moneySources(): List<MoneySource> = emptyList()

    /** Store a figure you told it, for [ManualSource] to read back. */
    fun setBalance(account: String, amount: Double) = Unit

    /** Resolve a heard name to the real contact name. */
    fun resolveContact(name: String): String = name

    fun now(): LocalDateTime = LocalDateTime.now()

    // -- memory ----------------------------------------------------------
    fun remember(key: String, value: String)
    fun recall(key: String): String?
    fun forget(key: String): Boolean
    fun memoryCounts(): Pair<Int, Int>
}

/** A handler takes arguments and a phone, and returns fields for the reply. */
typealias Handler = (Map<String, Any?>, Phone) -> Map<String, Any>

private fun Map<String, Any?>.string(key: String): String =
    (this[key] as? String)?.trim().orEmpty()

private fun Map<String, Any?>.int(key: String): Int? = when (val value = this[key]) {
    is Int -> value
    is Long -> value.toInt()
    is Double -> value.toInt()
    is Number -> value.toInt()
    is String -> value.trim().toIntOrNull()
    else -> null
}

/**
 * Read `YYYY-MM-DDTHH:MM` -- the one date format that crosses layers.
 *
 * Tolerant of the shapes a model reaches for on its own: a space instead of
 * the T, and trailing seconds. Anything else is null, which the caller turns
 * into something spoken rather than a crash.
 */
fun momentOf(value: Any?): LocalDateTime? {
    val text = (value as? String)?.trim()?.replace(" ", "T") ?: return null
    if (text.isEmpty()) return null
    return try {
        LocalDateTime.parse(text)
    } catch (_: Exception) {
        null
    }
}

/** Action name to handler. The catalogue. */
val REGISTRY: Map<String, Handler> = mapOf(

    "torch.on" to { _, phone -> phone.torch(true); emptyMap() },

    "torch.off" to { _, phone -> phone.torch(false); emptyMap() },

    "timer.set" to { args, phone ->
        val minutes = args.int("minutes") ?: throw ActionError("no duration heard")
        phone.startTimer(minutes)
        mapOf("minutes" to minutes)
    },

    "battery.read" to { _, phone ->
        val (percent, status) = phone.battery()
        mapOf("percent" to percent, "status" to status)
    },

    "sms.send" to { args, phone ->
        val to = args.string("to")
        val body = args.string("body")
        if (to.isEmpty()) throw ActionError("no recipient heard")
        if (body.isEmpty()) throw ActionError("no message heard")
        val recipient = phone.resolveContact(to)
        phone.sendSms(recipient, body)
        mapOf("to" to recipient)
    },

    // Stops one tap short on purpose. WhatsApp has no API for sending without
    // confirmation; doing it automatically means driving an accessibility
    // service, which breaks whenever their UI changes and is against their
    // terms. A prefilled chat is reliable and honest.
    "whatsapp.send" to { args, phone ->
        val name = args.string("to")
        val body = args.string("body")
        if (name.isEmpty()) throw ActionError("no recipient heard")
        if (body.isEmpty()) throw ActionError("no message heard")
        val number = phone.lookupNumber(name) ?: throw ActionError("no number stored for $name")
        phone.openWhatsApp(number, body)
        mapOf("to" to name)
    },

    "clipboard.set" to { args, phone ->
        val text = args.string("text")
        if (text.isEmpty()) throw ActionError("nothing to copy")
        phone.copyToClipboard(text)
        emptyMap()
    },

    "dnd.on" to { _, phone -> phone.doNotDisturb(true); emptyMap() },

    "volume.set" to { args, phone ->
        val level = args.int("level") ?: throw ActionError("no level heard")
        val clamped = level.coerceIn(0, 100)
        phone.setVolume(clamped)
        mapOf("level" to clamped)
    },

    "calendar.next" to { _, phone ->
        val entry = phone.nextEvent()
        if (entry == null) mapOf("summary" to "nothing", "minutes" to 0)
        else mapOf("summary" to entry.summary, "minutes" to entry.minutesAway)
    },

    // The action that used to need a Tasker task, an answer file on shared
    // storage, and a permission Termux could not hold. It is now four lines.
    // Reached only after approval -- [handle] holds this action back and asks
    // first. The parse is shared with the confirmation so the event written here
    // is provably the one you were shown; see [proposeEvent].
    "calendar.add" to { args, phone ->
        val event = proposeEvent(args)
        phone.addEvent(event.summary, event.start, event.minutes)
        mapOf(
            "summary" to event.summary,
            "date" to event.date,
            "time" to event.time,
            "minutes" to event.minutes,
        )
    },

    "time.read" to { _, phone ->
        val now = phone.now()
        mapOf("time" to String.format(Locale.ROOT, "%02d:%02d", now.hour, now.minute))
    },

    "memory.remember" to { args, phone ->
        val key = args.string("key")
        val value = args.string("value")
        if (key.isEmpty() || value.isEmpty()) throw ActionError("didn't catch what to remember")
        phone.remember(key, value)
        mapOf("key" to key, "value" to value)
    },

    "memory.recall" to { args, phone ->
        val key = args.string("key")
        if (key.isEmpty()) throw ActionError("didn't catch what to recall")
        val value = phone.recall(key) ?: throw ActionError("nothing stored for $key")
        mapOf("key" to key, "value" to value)
    },

    "memory.forget" to { args, phone ->
        val key = args.string("key")
        if (key.isEmpty()) throw ActionError("didn't catch what to forget")
        if (!phone.forget(key)) throw ActionError("nothing stored for $key")
        mapOf("key" to key)
    },

    // Deliberately a count, not a recital. Reading forty facts aloud is
    // useless; the screen is where you browse them.
    "memory.list" to { _, phone ->
        val (facts, contacts) = phone.memoryCounts()
        mapOf("facts" to facts, "contacts" to contacts)
    },

    // "How much money do I have." Every source behind this is read-only by
    // construction -- see the note at the top of Money.kt.
    "money.total" to { _, phone ->
        val sources = phone.moneySources()
        if (sources.isEmpty()) throw ActionError("no money sources configured")

        val wealth = total(sources, phone.now())
        if (wealth.answered == 0) throw ActionError("no source could answer")

        // "Three of four" and "three of three" are different answers and must
        // not sound the same: an account that has quietly gone missing is the
        // one failure that matters when the number *is* the answer.
        val partial = wealth.answered < wealth.asked
        val stale = wealth.stalest?.let {
            Duration.between(it, phone.now()) > STALE_AFTER
        } ?: false

        mapOf(
            "amount" to formatMoney(wealth.primaryAmount),
            "currency" to wealth.primary,
            "answered" to wealth.answered,
            "asked" to wealth.asked,
            "note" to buildString {
                if (partial) append(" (${wealth.answered} of ${wealth.asked})")
                if (wealth.others.isNotEmpty()) {
                    append(" + ${wealth.others.joinToString(", ")}")
                }
                if (stale && wealth.stalest != null) {
                    append(" — oldest ${wealth.stalest!!.dayOfMonth}/${wealth.stalest!!.monthValue}")
                }
            }.trim(),
        )
    },

    // «θυμήσου ότι το eurolife είναι 12000» -- the source of last resort, and
    // the only one that reaches an insurance or pension portal.
    "money.set" to { args, phone ->
        val account = args.string("account").lowercase()
        if (account.isEmpty()) throw ActionError("no account heard")
        val amount = when (val raw = args["amount"]) {
            is Number -> raw.toDouble()
            is String -> SmsBalanceSource.europeanNumber(raw.trim())
            else -> null
        } ?: throw ActionError("no amount heard")

        phone.setBalance(account, amount)
        mapOf("account" to account, "amount" to formatMoney(amount))
    },

    "answer" to { args, _ -> mapOf("text" to args.string("text")) },

    "unclear" to { _, _ -> emptyMap() },
)

/**
 * A money amount as it should be *spoken*.
 *
 * Whole euros, grouped. Reading the cents of a five-figure balance aloud is
 * noise -- nobody asking "how much money do I have" wants "and forty-three
 * cents" -- and the grouping is what makes 12,340 land as twelve thousand
 * rather than a string of digits.
 */
fun formatMoney(amount: Double): String =
    String.format(Locale.UK, "%,.0f", amount)

/** Every registered action name, sorted. */
fun actionNames(): List<String> = REGISTRY.keys.sorted()

/**
 * Run an action and return the reply key and the fields to speak.
 *
 * On failure the key becomes `failed` or `unclear`, so the caller never has to
 * handle errors separately: it always has something to say.
 */
fun execute(name: String, args: Map<String, Any?>, phone: Phone): Pair<String, Map<String, Any>> {
    val handler = REGISTRY[name] ?: return "unclear" to emptyMap()
    return try {
        val fields = handler(args, phone)
        // Args first, so handler output wins on a key collision.
        name to (args.filterValues { it != null }.mapValues { it.value!! } + fields)
    } catch (_: ActionError) {
        "failed" to emptyMap()
    } catch (_: Exception) {
        // A crash here must not kill the turn.
        "failed" to emptyMap()
    }
}
