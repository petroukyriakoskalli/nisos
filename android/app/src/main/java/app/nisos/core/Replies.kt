package app.nisos.core

/**
 * What the assistant says back, in both languages.
 *
 * This is where the fast path gets its quality. Routed actions never ask a
 * model for words -- they look up a string *you* wrote. Perfect Greek, perfect
 * English, no risk of a model producing something almost-right, and no tokens
 * spent. Only the reasoned path can produce awkward phrasing, and that is the
 * minority of what anyone says.
 *
 * Extending
 * ---------
 * Every action in [REGISTRY] needs an entry here with an `en` and an `el`
 * string. Placeholders in `{braces}` are filled from whatever the action
 * returned merged with the arguments it was called with.
 */
val SAY: Map<String, Map<String, String>> = mapOf(
    "torch.on" to mapOf("en" to "Torch on.", "el" to "Άναψα τον φακό."),
    "torch.off" to mapOf("en" to "Torch off.", "el" to "Έσβησα τον φακό."),
    "timer.set" to mapOf(
        "en" to "{minutes} minutes, counting.",
        "el" to "{minutes} λεπτά, ξεκίνησα.",
    ),
    "battery.read" to mapOf(
        "en" to "{percent} percent, {status}.",
        "el" to "{percent} τοις εκατό, {status}.",
    ),
    "sms.send" to mapOf("en" to "Sent to {to}.", "el" to "Το έστειλα στον/στην {to}."),
    // Says "ready" rather than "sent" on purpose -- it stops one tap short,
    // and claiming otherwise is a lie you would only discover later.
    "whatsapp.send" to mapOf(
        "en" to "WhatsApp is open for {to} — tap send.",
        "el" to "Άνοιξα το WhatsApp για {to} — πάτα αποστολή.",
    ),
    "memory.remember" to mapOf("en" to "Noted — {key}.", "el" to "Το θυμάμαι — {key}."),
    "memory.recall" to mapOf("en" to "{value}", "el" to "{value}"),
    "memory.forget" to mapOf("en" to "Forgotten.", "el" to "Το ξέχασα."),
    "memory.list" to mapOf(
        "en" to "{facts} things and {contacts} numbers.",
        "el" to "{facts} πράγματα και {contacts} τηλέφωνα.",
    ),
    "clipboard.set" to mapOf("en" to "Copied.", "el" to "Το αντέγραψα."),
    "dnd.on" to mapOf("en" to "Silenced.", "el" to "Σε σίγαση."),
    "volume.set" to mapOf("en" to "Volume {level}.", "el" to "Ένταση {level}."),
    "calendar.next" to mapOf(
        "en" to "{summary}, in {minutes} minutes.",
        "el" to "{summary}, σε {minutes} λεπτά.",
    ),
    // Reads the appointment back rather than just confirming: the day it
    // landed on and the hour it picked are the two things most likely to be
    // wrong, and invisible until you open the calendar.
    "calendar.add" to mapOf(
        "en" to "{summary}, {date} at {time}.",
        "el" to "{summary}, {date} στις {time}.",
    ),
    "time.read" to mapOf("en" to "It's {time}.", "el" to "Η ώρα είναι {time}."),
    // {note} carries the honesty: how many sources answered when not all did,
    // other currencies, and how old the oldest reading is. Empty on the happy
    // path, so the common case is just the number.
    "money.total" to mapOf(
        "en" to "{amount} {currency}. {note}",
        "el" to "{amount} {currency}. {note}",
    ),
    "money.set" to mapOf(
        "en" to "{account}, {amount}. Noted.",
        "el" to "{account}, {amount}. Το σημείωσα.",
    ),
    // Spoken by the model itself -- the template passes its text through.
    "answer" to mapOf("en" to "{text}", "el" to "{text}"),

    // Failure modes. Worth phrasing well; you will hear these more than you
    // would like, and each one has to name the thing you can actually fix.
    "unclear" to mapOf("en" to "Didn't catch that.", "el" to "Δεν το έπιασα."),
    "unavailable" to mapOf(
        "en" to "Can't do that offline.",
        "el" to "Αυτό δεν γίνεται χωρίς σύνδεση.",
    ),
    "no_key" to mapOf(
        "en" to "There's no API key, so I can only do the quick commands.",
        "el" to "Λείπει το κλειδί, οπότε κάνω μόνο τις γρήγορες εντολές.",
    ),
    "refused" to mapOf(
        "en" to "The model wouldn't answer that one.",
        "el" to "Το μοντέλο αρνήθηκε να απαντήσει.",
    ),
    "no_permission" to mapOf(
        "en" to "I don't have permission for that yet.",
        "el" to "Δεν έχω ακόμη άδεια γι' αυτό.",
    ),
    "failed" to mapOf("en" to "That didn't work.", "el" to "Κάτι πήγε στραβά."),
)

private val PLACEHOLDER = Regex("\\{([a-z_]+)}")

/**
 * Render the spoken reply for an action in the given language.
 *
 * Never throws. A missing template or a missing placeholder degrades to
 * something speakable, because an assistant that crashes while telling you it
 * succeeded is worse than one that phrases it clumsily.
 */
fun say(action: String, language: String, fields: Map<String, Any> = emptyMap()): String {
    val templates = SAY[action] ?: return action.replace(".", " ")
    val template = templates[language] ?: templates["en"] ?: action

    return PLACEHOLDER.replace(template) { found ->
        fields[found.groupValues[1]]?.toString() ?: ""
    }.replace(Regex("\\s{2,}"), " ").trim()
}

/** What counts as an already-finished sentence, so [stitch] adds no second stop. */
private const val TERMINATORS = ".!?…:"

/**
 * Join the replies from several actions into one thing to say.
 *
 * A turn that did two things has to answer once, not twice: two calls to the
 * speech engine means two utterances, and on Android the second routinely
 * arrives on top of the first.
 *
 * **A single part is returned untouched.** That is the whole reason this is
 * not just a join: the common turn is one action and it must sound precisely
 * as it always has.
 */
fun stitch(parts: List<String>): String {
    val said = parts.map { it.trim() }.filter { it.isNotEmpty() }
    if (said.isEmpty()) return ""
    if (said.size == 1) return said[0]
    return said.joinToString(" ") { if (it.last() in TERMINATORS) it else "$it." }
}

/**
 * (action, language) pairs with no reply template.
 *
 * Used by the unit tests to keep [SAY] in step with [REGISTRY], so adding an
 * action without a Greek phrase fails a test rather than surfacing months
 * later as an English sentence in a Greek conversation.
 */
fun missingReplies(
    actions: List<String> = actionNames(),
    languages: List<String> = listOf("en", "el"),
): List<Pair<String, String>> = actions.flatMap { action ->
    languages.filter { SAY[action]?.containsKey(it) != true }.map { action to it }
}
