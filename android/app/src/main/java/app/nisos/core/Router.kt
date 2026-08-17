package app.nisos.core

import java.time.LocalDateTime

/**
 * The keyword router -- the fast path, and the language detector.
 *
 * Two jobs, one pass:
 *
 * **Speed.** Roughly 80% of what anyone says to a phone is one of a few dozen
 * patterns. Matching those with regex takes about five milliseconds, against
 * the second or more it costs to reach a language model. The router is the
 * single biggest reason this feels like an assistant rather than a demo -- and
 * on this side of the port it is the only thing standing between you and a
 * per-turn API bill.
 *
 * **Language.** Greek and English share no characters, so a pattern written
 * against Greek stems physically cannot fire on English text, and vice versa.
 * You do not detect the language and then route -- you route against both
 * tables, and whichever one hits tells you the language for free.
 *
 * Extending
 * ---------
 * Adding a command is one line per language in [ROUTES]. Patterns are matched
 * against [normalise] output, so write them lowercase, unaccented, with final
 * sigma as plain sigma. Match Greek **stems**, never whole words.
 *
 * Order matters within a table: the first hit wins, so specific patterns go
 * above general ones. `calendar.add` sits above `calendar.next` for exactly
 * that reason -- «κλείσε ραντεβού αύριο στις πέντε» would otherwise be
 * answered by reading you your next meeting.
 *
 * More than one thing at a time
 * -----------------------------
 * «άναψε τον φακό **και** βάλε χρονόμετρο» is two commands. It is split on
 * conjunctions and each piece routed on its own -- but only accepted as a
 * split **when every piece routes**. That one rule keeps «στείλε στη Μαρία ότι
 * άργησα και θα φάμε αργότερα» in one piece: the tail is not a command, so the
 * whole sentence stays one message with «και» inside it.
 */

/** An argument builder turns a match into the action's arguments. */
typealias ArgBuilder = (Normalised, MatchResult) -> Map<String, Any?>

private val NO_ARGS: ArgBuilder = { _, _ -> emptyMap() }

/** One pattern in one language, and what to do when it fires. */
data class Route(
    val source: String,
    val action: String,
    val args: ArgBuilder = NO_ARGS,
) {
    /**
     * Compiled once, through [unicodePattern] -- which is not optional. A
     * route written with a bare `Regex(...)` would silently stop matching
     * Greek. Taking the source rather than a compiled pattern is what makes
     * that impossible to get wrong on a new route.
     */
    val pattern: Regex = unicodePattern(source)
}

/**
 * What the router returns on a hit.
 *
 * @property action the first action. A plain field rather than a lookup into
 *   [steps], because a one-action turn is still the common case and reading
 *   `match.action` should not require knowing a turn can be a list.
 * @property steps every action, in the order they were said.
 */
data class Match(
    val language: String,
    val action: String,
    val args: Map<String, Any?>,
    val steps: List<Step> = emptyList(),
) {
    val plan: List<Step> get() = steps.ifEmpty { listOf(Step(action, args)) }
}

// --------------------------------------------------------------------------
// Argument builders
// --------------------------------------------------------------------------

private fun minutesIn(language: String): ArgBuilder =
    { text, _ -> mapOf("minutes" to parseNumber(text.text, language)) }

private fun levelIn(language: String): ArgBuilder =
    { text, _ -> mapOf("level" to parseNumber(text.text, language)) }

/**
 * Greek names almost always arrive with an article attached -- «θυμήσου ότι
 * **η** Μαριλένα είναι…» -- and storing "η μαριλενα" means a later lookup for
 * "μαριλενα" finds nothing.
 */
private val ARTICLE = unicodePattern(
    "^(?:ο|η|το|οι|τα|τον|την|τη|του|τησ|των|ενασ|μια|ενα|the|a|an)\\s+"
)

fun stripArticle(text: String): String = ARTICLE.replace(text.trim(), "").trim()

// --------------------------------------------------------------------------
// Appointments
// --------------------------------------------------------------------------
// Making an appointment is the one command where the words you did *not* say a
// command with are the payload: everything that is neither the instruction nor
// the time is the title. So rather than reading it out of a capture group --
// which would need a fixed word order, and «βάλε ραντεβού με τον γιατρό
// αύριο» and "put the dentist in my calendar tomorrow" do not agree on one --
// the words are subtracted.

/** Removed wherever it appears: the verb, and the noun for what it goes in. */
private val CHROME = mapOf(
    "en" to unicodePattern("^(put|add|schedule|book|create|make|new|calendar|diary|agenda|please)$"),
    "el" to unicodePattern("^(βαλ\\w*|βαζ\\w*|προσθεσ\\w*|προσθετ\\w*|γραψ\\w*|κλεισ\\w*" +
        "|οριζ\\w*|ορισ\\w*|ημερολογ\\w*|ατζεντα|παρακαλω)$"),
)

/**
 * Trimmed from the *ends* of the title only. In the middle they are content:
 * dropping «με τον» everywhere turns «ραντεβού με τον γιατρό» into «ραντεβού
 * γιατρό».
 */
private val EDGE = mapOf(
    "en" to unicodePattern("^(in|into|to|on|at|for|with|of|my|the|a|an|it|this|that)$"),
    "el" to unicodePattern("^(στο|στη|στην|στον|στουσ|σε|μου|μασ|το|τη|την|τον|τα|οι|ο|η" +
        "|ενα|μια|ενασ|αυτο)$"),
)

private val UNTITLED = mapOf("en" to "Appointment", "el" to "Ραντεβού")

private const val TRIM = " ,.;:!?«»\"'"

private fun appointmentIn(language: String, clock: () -> LocalDateTime): ArgBuilder =
    builder@{ text, _ ->
        val words = text.words
        val chrome = CHROME.getValue(language)
        val spent = words.indices
            .filter { chrome.matches(words[it].trim { c -> c in TRIM }) }
            .toMutableSet()

        val moment = parseWhen(words, language, clock())
        if (moment != null) spent.addAll(moment.words)

        val left = words.indices.filter { it !in spent }
        val args = mutableMapOf<String, Any?>(
            "summary" to titleOf(text, left, language),
            "minutes" to (moment?.minutes ?: DEFAULT_MINUTES),
        )
        // No start rather than a guess: inventing an hour for something that
        // goes in a diary is worse than admitting you missed it, and the
        // action then says so out loud.
        if (moment != null) args["start"] = moment.iso()
        args
    }

private fun titleOf(text: Normalised, indices: List<Int>, language: String): String {
    val words = text.words
    val edge = EDGE.getValue(language)
    var range = indices
    while (range.isNotEmpty() && edge.matches(words[range.first()].trim { it in TRIM })) {
        range = range.drop(1)
    }
    while (range.isNotEmpty() && edge.matches(words[range.last()].trim { it in TRIM })) {
        range = range.dropLast(1)
    }
    if (range.isEmpty()) return UNTITLED.getValue(language)
    val title = text.original(range).trim { it in TRIM }
    return title.ifEmpty { UNTITLED.getValue(language) }
}

// --------------------------------------------------------------------------
// The tables
// --------------------------------------------------------------------------
// Keep the two in step: every action in one should be in the other, or the
// assistant is cleverer in one language than the other and you will never
// remember which.

fun buildRoutes(clock: () -> LocalDateTime = { LocalDateTime.now() }): Map<String, List<Route>> = mapOf(
    "en" to listOf(
        Route("\\b(torch|flashlight) (on|off)\\b", "torch.on"),
        Route("\\b(turn |switch )?(on|off) (the )?(torch|light|flashlight)\\b", "torch.on"),
        // "open the light" is how a Greek speaker says this in English, and is
        // exactly what got typed the first time this ran on a phone.
        Route("\\b(open|close) (the )?(torch|light|flashlight)\\b", "torch.on"),
        Route("\\b(set (a |the )?)?timer\\b", "timer.set", minutesIn("en")),
        Route("\\bremind me in\\b", "timer.set", minutesIn("en")),
        Route("\\b(battery|charge level)\\b", "battery.read"),
        // Money. money.set first: "set my eurolife to 12000" also contains
        // "how much"-shaped words in some phrasings, and a question must never
        // overwrite a figure.
        Route("\\b(set|update)\\b.*\\b(\\w+)\\b\\s+(?:to|at)\\s+([\\d.,]+)", "money.set") { _, m ->
            mapOf("account" to m.groupValues[2], "amount" to m.groupValues[3])
        },
        Route("\\b(how much money|how much do i have|my balance|total balance" +
            "|how much have i got)\\b", "money.total"),
        // Above calendar.next and above the message routes: "book a meeting"
        // is not a request to be told about your next one.
        Route("\\b(put|add|schedule|book|create|make)\\b.*\\b(calendar|diary|agenda)\\b",
            "calendar.add", appointmentIn("en", clock)),
        Route("\\b(schedule|book)\\b.*\\b(meeting|appointment)\\b",
            "calendar.add", appointmentIn("en", clock)),
        Route("\\btext (\\w+)\\b", "sms.send") { text, m ->
            mapOf("to" to m.groupValues[1], "body" to text.text.substring(m.range.last + 1).trim())
        },
        Route("\\b(copy|clipboard)\\b", "clipboard.set") { text, m ->
            mapOf("text" to text.text.substring(m.range.last + 1).trim())
        },
        Route("\\b(silence|silent|do not disturb|dnd)\\b", "dnd.on"),
        Route("\\b(volume|sound)\\b", "volume.set", levelIn("en")),
        Route("\\b(next |upcoming )?(meeting|appointment|calendar)\\b", "calendar.next"),
        Route("\\bwhat time is it\\b|\\bthe time\\b", "time.read"),
        // Memory. Specific shapes only -- a broad "what is X" would swallow
        // every general-knowledge question and answer "nothing stored".
        Route("\\bremember (?:that )?(.+?) (?:is|are|'s) (.+)", "memory.remember") { _, m ->
            mapOf("key" to stripArticle(m.groupValues[1]), "value" to m.groupValues[2])
        },
        Route("\\bforget (?:about )?(.+)", "memory.forget") { _, m ->
            mapOf("key" to stripArticle(m.groupValues[1]))
        },
        Route("\\bwhat do you remember\\b|\\bhow much do you remember\\b", "memory.list"),
        Route("\\bwhat(?:'s| is) (.+?)(?:'s)? (?:number|phone)\\b", "memory.recall") { _, m ->
            mapOf("key" to stripArticle(m.groupValues[1]))
        },
        Route("\\bwhat do you know about (.+)", "memory.recall") { _, m ->
            mapOf("key" to stripArticle(m.groupValues[1]))
        },
    ),
    "el" to listOf(
        // Stems, always. αναψ- covers άναψε / ανάψτε / να ανάψεις.
        Route("\\b(αναψ|ανοιξ)\\w*\\b.*\\bφακ", "torch.on"),
        Route("\\b(σβησ|κλεισ)\\w*\\b.*\\bφακ", "torch.off"),
        Route("\\bφακ\\w*\\b.*\\b(αναψ|ανοιξ)", "torch.on"),
        Route("\\bφακ\\w*\\b.*\\b(σβησ|κλεισ)", "torch.off"),
        Route("\\b(χρονομετρ|ταιμερ|αντιστροφ)", "timer.set", minutesIn("el")),
        Route("\\bθυμισε μου σε\\b", "timer.set", minutesIn("el")),
        Route("\\bμπαταρι", "battery.read"),
        // Money. «βάλε το eurolife στα 12000» before the question forms, and
        // both before the memory routes -- «θυμήσου ότι το eurolife είναι
        // 12000» is a legitimate way to say it and memory.remember would
        // otherwise swallow it into the facts store where no total can see it.
        Route("\\b(βαλ|βαζ|θυμ|ενημερωσ)\\w*\\b.*\\b(\\w+)\\s+" +
            "(?:στα|ειναι|εχει)\\s+([\\d.,]+)", "money.set") { _, m ->
            mapOf("account" to m.groupValues[2], "amount" to m.groupValues[3])
        },
        Route("\\b(ποσα (?:λεφτα|χρηματα|ευρω)|ποσα εχω|το υπολοιπο" +
            "|υπολοιπο μου)", "money.total"),
        // Above the message routes: «γράψε στο ημερολόγιο» matches the sms
        // pattern otherwise, and sends a text to somebody called «ημερολόγιο».
        Route("\\b(βαλ|βαζ|προσθεσ|προσθετ|γραψ)\\w*\\b.*\\bημερολογ",
            "calendar.add", appointmentIn("el", clock)),
        Route("\\b(κλεισ|βαλ|βαζ|οριζ|ορισ)\\w*\\b.*\\b(ραντεβου|συναντηση)",
            "calendar.add", appointmentIn("el", clock)),
        // The article list has to be generous. Spoken Greek drops the final nu
        // constantly, and missing one means the article is captured as the
        // recipient's name. Longest alternatives first, or «στο» shadows «στον».
        Route("\\b(στειλ|γραψ)\\w*\\b\\s+(?:μηνυμα\\s+)?(?:στ(?:ον|ην|ουσ|ισ|η|ο|α)\\s+)?(\\w+)",
            "sms.send") { text, m ->
            mapOf("to" to m.groupValues[2], "body" to text.text.substring(m.range.last + 1).trim())
        },
        Route("\\b(αντιγραψ|κοπι)", "clipboard.set") { text, m ->
            mapOf("text" to text.text.substring(m.range.last + 1).trim())
        },
        Route("\\b(σιγαση|ησυχι|μην ενοχλ)", "dnd.on"),
        Route("\\b(ενταση|ηχ)\\w*", "volume.set", levelIn("el")),
        Route("\\b(ραντεβου|συναντηση|ημερολογ)", "calendar.next"),
        Route("\\bτι ωρα ειναι\\b|\\bη ωρα\\b", "time.read"),
        // θυμησου / θυμηθειτε / να θυμασαι share the θυμ- stem, but so does
        // «τι θυμάσαι», so the list route has to come first.
        Route("\\bτι θυμασαι\\b|\\bποσα θυμασαι\\b", "memory.list"),
        Route("\\bθυμ\\w*\\s+(?:οτι\\s+)?(.+?)\\s+ειναι\\s+(.+)", "memory.remember") { _, m ->
            mapOf("key" to stripArticle(m.groupValues[1]), "value" to m.groupValues[2])
        },
        Route("\\b(?:ξεχνα|ξεχασε)\\s+(.+)", "memory.forget") { _, m ->
            mapOf("key" to stripArticle(m.groupValues[1]))
        },
        Route("\\bποιο ειναι το (?:τηλεφωνο|νουμερο)\\s+(?:τη[σ]?|του|των)?\\s*(.+)",
            "memory.recall") { _, m -> mapOf("key" to stripArticle(m.groupValues[1])) },
        Route("\\bτι ξερεισ για\\s+(.+)", "memory.recall") { _, m ->
            mapOf("key" to stripArticle(m.groupValues[1]))
        },
    ),
)

/**
 * Saying "on WhatsApp" anywhere in a message command switches the channel.
 * Handled as a rewrite rather than duplicate routes, exactly like the torch
 * direction below. Greek recognisers render the brand phonetically about as
 * often as they get it right, hence the variants.
 */
private val WHATSAPP = unicodePattern("whats\\s?app|ουατσαπ|βοτσαπ|γουατσαπ")

/**
 * The torch patterns capture a direction word, so the router rewrites the
 * action name rather than the tables carrying every phrasing twice. Only ever
 * consulted for torch.on, so "close" here cannot affect anything else.
 */
private val DIRECTION_OFF = unicodePattern("\\b(off|close|σβησ|κλεισ)")

/**
 * Words that end one command and start the next. Both languages in one set,
 * because the alphabets are disjoint and a Greek word can never be mistaken
 * for an English one.
 */
val CONJUNCTIONS = setOf(
    "and", "then", "also", "plus",
    "και", "κι", "μετα", "επειτα", "υστερα", "επισησ",
)

/**
 * The most actions one utterance may carry. Beyond this it is far likelier to
 * be a sentence with several «και»s in it than a person issuing five orders in
 * one breath, so a longer split is not accepted at all.
 */
const val MAX_STEPS = 4

private const val SPLIT_TRIM = " ,.;:!?«»\"'"

/**
 * Cut an utterance into the separate commands it contains.
 *
 * Splits on the conjunctions above, and after any word ending in a comma. Each
 * piece keeps its own slice of the *raw* text, so a title or a message body
 * taken out of one still has its accents and capitals.
 */
fun split(text: Normalised): List<Normalised> {
    val words = text.words
    val pieces = mutableListOf<List<Int>>()
    var current = mutableListOf<Int>()

    words.forEachIndexed { index, word ->
        if (word.trim { it in SPLIT_TRIM } in CONJUNCTIONS) {
            if (current.isNotEmpty()) { pieces.add(current); current = mutableListOf() }
            return@forEachIndexed
        }
        current.add(index)
        if (word.endsWith(",")) { pieces.add(current); current = mutableListOf() }
    }
    if (current.isNotEmpty()) pieces.add(current)

    return pieces.map { piece ->
        Normalised(
            piece.joinToString(" ") { words[it].trimEnd(',') },
            text.original(piece).trimEnd(','),
        )
    }
}

/**
 * Work out what was asked for, in one language, possibly several times.
 *
 * A multi-command split is only taken when **every** piece routes. That is the
 * whole safety argument: the pieces of a sentence that merely contains the
 * word «και» do not all route, so it stays in one piece.
 *
 * @return null means nothing matched -- wake the model.
 */
fun route(rawText: String, tables: Map<String, List<Route>> = DEFAULT_ROUTES): Match? {
    val text = normalise(rawText)

    val pieces = split(text)
    if (pieces.size in 2..MAX_STEPS) {
        val hits = pieces.map { routeOne(it, tables) }
        if (hits.all { it != null }) {
            val steps = hits.filterNotNull().flatMap { it.plan }
            return Match(hits[0]!!.language, steps[0].action, steps[0].args, steps)
        }
    }
    return routeOne(text, tables)
}

/** Try every pattern in both languages against one command. */
private fun routeOne(text: Normalised, tables: Map<String, List<Route>>): Match? {
    for ((language, routes) in tables) {
        for (entry in routes) {
            val found = entry.pattern.find(text.text) ?: continue

            var action = entry.action
            if (action == "torch.on" && DIRECTION_OFF.containsMatchIn(found.value)) {
                action = "torch.off"
            }

            var args = entry.args(text, found)

            // Messaging defaults to SMS, because that sends with no taps at
            // all. Saying "on WhatsApp" switches channel; the brand name is
            // then stripped so it does not end up inside the message.
            if (action == "sms.send" && WHATSAPP.containsMatchIn(text.text)) {
                action = "whatsapp.send"
                val body = args["body"] as? String
                if (!body.isNullOrEmpty()) {
                    var cleaned = WHATSAPP.replace(body, "")
                    cleaned = Regex("\\s+(on|στο|με)\\s*$").replace(cleaned.trim(), "")
                    cleaned = Regex("\\s{2,}").replace(cleaned, " ").trim().trim(' ', ',')
                    args = args + ("body" to cleaned)
                }
            }

            return Match(language, action, args, listOf(Step(action, args)))
        }
    }
    return null
}

/** Compiled once. The clock is only consulted when an appointment is routed. */
val DEFAULT_ROUTES: Map<String, List<Route>> = buildRoutes()
