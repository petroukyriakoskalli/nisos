package app.nisos.core

import java.time.LocalDateTime

/**
 * Spoken dates and times, turned into a real [LocalDateTime].
 *
 * A port of `nisos/when.py`. «αύριο στις πέντε» and "tomorrow at five" both
 * have to become a specific moment before anything can go in a calendar. Pure
 * by design: text in, a datetime out, no Android anywhere.
 *
 * Three decisions worth knowing before changing anything here
 * ----------------------------------------------------------
 * **A bare hour between one and seven means the afternoon.** «στις πέντε» is
 * 17:00, not 05:00, because nobody arranges a dentist for five in the morning
 * and says it that casually. Eight through twelve stay as spoken. «το πρωί» /
 * "in the morning" forces AM either way, and «το βράδυ» / "pm" forces PM. One
 * rule, always the same, easy to say out loud when it gets it wrong.
 *
 * **A time with no day is the next time that time happens.** Today if it is
 * still ahead, tomorrow if it has passed. A day with no time is [DEFAULT_HOUR],
 * because "put the dentist in for Thursday" is a real sentence.
 *
 * **Word positions, not character offsets.** [parse] reports which *words* it
 * consumed, so the caller can take what is left as the title.
 */

/** What "Thursday", with no time attached, means. */
const val DEFAULT_HOUR = 9

/** How long an appointment lasts when nobody says. */
const val DEFAULT_MINUTES = 60

/** A bare hour at or below this is read as afternoon: «στις πέντε» is 17:00. */
const val AFTERNOON_UNTIL = 7

private val DAY_WORDS = mapOf(
    "el" to mapOf("σημερα" to 0, "αποψε" to 0, "αυριο" to 1, "μεθαυριο" to 2),
    "en" to mapOf("today" to 0, "tonight" to 0, "tomorrow" to 1),
)

/** Day words that also carry a time of day. «απόψε στις οκτώ» is 20:00. */
private val EVENING_DAYS = setOf("αποψε", "tonight")

/** Weekday **stems**, matched with startsWith, so «της Δευτέρας» still lands. */
private val WEEKDAYS = mapOf(
    "el" to mapOf(
        "δευτερα" to 1, "τριτη" to 2, "τεταρτη" to 3, "πεμπτη" to 4,
        "παρασκευη" to 5, "σαββατο" to 6, "κυριακη" to 7,
    ),
    "en" to mapOf(
        "monday" to 1, "tuesday" to 2, "wednesday" to 3, "thursday" to 4,
        "friday" to 5, "saturday" to 6, "sunday" to 7,
    ),
)

private val AM_WORDS = mapOf(
    "el" to setOf("πρωι", "πρωινο", "πρωια"),
    "en" to setOf("am", "morning"),
)
private val PM_WORDS = mapOf(
    "el" to setOf("απογευμα", "απογευματινο", "βραδυ", "βραδι", "μεσημερι", "αποψε"),
    "en" to setOf("pm", "afternoon", "evening", "night", "tonight", "noon"),
)

/** Words that introduce a clock time, so the number after one is an hour. */
private val AT_WORDS = mapOf(
    "el" to setOf("στισ", "στη", "στην", "στο", "στον", "ωρα"),
    "en" to setOf("at"),
)
private val FOR_WORDS = mapOf("el" to setOf("για"), "en" to setOf("for"))

/** Duration unit **stems**: «λεπτά», «λεπτό», "minutes", "min". */
private val UNIT_MINUTES = mapOf(
    "el" to listOf("ωρ" to 60, "λεπτ" to 1),
    "en" to listOf("hour" to 60, "hr" to 60, "minute" to 1, "min" to 1),
)

private val HALF_WORDS = mapOf("el" to setOf("μιση", "μισι"), "en" to setOf("half"))
private val QUARTER_WORDS = mapOf("el" to setOf("τεταρτο"), "en" to setOf("quarter"))
private val TO_WORDS = mapOf("el" to setOf("παρα"), "en" to setOf("to"))

/**
 * «και μισή» -- the joiner between an hour and its minutes.
 *
 * Note this is the same word the router splits multi-action commands on. That
 * is safe: a split is only accepted when *every* piece routes on its own, and
 * «μισή» routes to nothing.
 */
private val AND_WORDS = mapOf("el" to setOf("και", "κι"), "en" to setOf("and"))

private const val PUNCTUATION = " ,.;:!?«»\"'()"

private fun bare(word: String) = word.trim { it in PUNCTUATION }

/** The language to check first, then the other. People code-switch. */
private fun order(language: String): List<String> {
    val first = if (DAY_WORDS.containsKey(language)) language else "en"
    return listOf(first) + DAY_WORDS.keys.filter { it != first }
}

private fun inTable(word: String, table: Map<String, Set<String>>, language: String) =
    order(language).any { table[it]?.contains(word) == true }

private fun weekdayOf(word: String, language: String): Int? {
    for (lang in order(language)) {
        WEEKDAYS[lang]?.forEach { (stem, index) ->
            if (word.startsWith(stem)) return index
        }
    }
    return null
}

private fun numberOf(word: String, language: String): Int? {
    word.toIntOrNull()?.let { return it }
    for (lang in order(language)) {
        NUMBER_WORDS[lang]?.get(word)?.let { return it }
    }
    return null
}

/**
 * Read a word as a clock time: `17:30`, `17.30`, `5`, `5pm`, or «πέντε».
 *
 * Returns hour to minute, or null if the word is not a time at all.
 */
private fun clockOf(raw: String, language: String): Pair<Int, Int>? {
    var word = raw
    var suffix: String? = null
    if (word.length > 2 && (word.endsWith("am") || word.endsWith("pm"))) {
        suffix = word.takeLast(2)
        word = word.dropLast(2)
    }

    var hour: Int
    var minute = 0
    val separator = listOf(":", ".", "h").firstOrNull { word.contains(it) }
    if (separator != null) {
        val (left, right) = word.split(separator, limit = 2).let { it[0] to it.getOrElse(1) { "" } }
        val h = left.toIntOrNull() ?: return null
        val m = right.toIntOrNull() ?: return null
        hour = h
        minute = m
    } else {
        hour = numberOf(word, language) ?: return null
    }

    if (suffix == "pm" && hour < 12) hour += 12
    if (suffix == "am" && hour == 12) hour = 0

    return if (hour in 0..23 && minute in 0..59) hour to minute else null
}

/**
 * A moment, how long it lasts, and which words paid for it.
 *
 * @property words indices of the words [parse] consumed. Whatever is left over
 *   is the caller's -- for `calendar.add` it is the title.
 */
data class When(
    val start: LocalDateTime,
    val minutes: Int = DEFAULT_MINUTES,
    val words: Set<Int> = emptySet(),
) {
    /**
     * `YYYY-MM-DDTHH:MM` in local time.
     *
     * Deliberately no timezone and no seconds. This string is the contract
     * between the router, the model and `calendar.add`, and a model asked for
     * "tomorrow at five" knows what local wall-clock time means and does not
     * know the phone's offset.
     */
    fun iso(): String = String.format(
        java.util.Locale.ROOT, "%04d-%02d-%02dT%02d:%02d",
        start.year, start.monthValue, start.dayOfMonth, start.hour, start.minute,
    )
}

/**
 * Find a date and time in an already-normalised list of words.
 *
 * @param now injected so the tests are not a lottery at midnight.
 * @return null when the words contain no time at all, which is a normal answer
 *   and not a failure.
 */
fun parseWhen(words: List<String>, language: String = "en", now: LocalDateTime): When? {
    var dayOffset: Int? = null
    var weekday: Int? = null
    var hour: Int? = null
    var minute = 0
    var meridiem: String? = null
    var minutes: Int? = null
    val used = mutableSetOf<Int>()

    var index = 0
    while (index < words.size) {
        val word = bare(words[index])
        if (word.isEmpty()) { index++; continue }

        // -- "for 30 minutes", «για μία ώρα» ---------------------------------
        if (inTable(word, FOR_WORDS, language)) {
            val span = duration(words, index + 1, language)
            if (span != null) {
                minutes = span.first
                used.add(index)
                used.addAll(span.second)
                index = span.second.max() + 1
                continue
            }
        }

        // -- today / tomorrow / tonight --------------------------------------
        val offset = order(language).firstNotNullOfOrNull { DAY_WORDS[it]?.get(word) }
        if (offset != null) {
            dayOffset = offset
            if (word in EVENING_DAYS && meridiem == null) meridiem = "pm"
            used.add(index); index++; continue
        }

        // -- Monday, «τη Δευτέρα» --------------------------------------------
        val named = weekdayOf(word, language)
        if (named != null) {
            weekday = named
            used.add(index); index++; continue
        }

        // -- morning / afternoon / pm ----------------------------------------
        if (inTable(word, AM_WORDS, language)) {
            meridiem = "am"; used.add(index); index++; continue
        }
        if (inTable(word, PM_WORDS, language)) {
            meridiem = "pm"; used.add(index); index++; continue
        }

        // -- "half past five" (English puts it in front) ----------------------
        if (hour == null && inTable(word, HALF_WORDS, language) &&
            index + 2 < words.size && bare(words[index + 1]) == "past"
        ) {
            val clock = clockOf(bare(words[index + 2]), language)
            if (clock != null) {
                hour = clock.first
                minute = 30
                used.addAll(listOf(index, index + 1, index + 2))
                index += 3
                continue
            }
        }

        // -- «στις πέντε», "at 5", "at 17:30" ---------------------------------
        if (inTable(word, AT_WORDS, language) && index + 1 < words.size) {
            val clock = clockOf(bare(words[index + 1]), language)
            if (clock != null) {
                hour = clock.first
                minute = clock.second
                used.addAll(listOf(index, index + 1))
                index += 2
                val adjusted = adjust(words, index, language, hour!!, minute, used)
                hour = adjusted.first
                minute = adjusted.second
                while (index in used) index++
                continue
            }
        }

        // -- a bare 17:30, which needs no introduction ------------------------
        if (hour == null && (word.contains(":") || word.contains("."))) {
            val clock = clockOf(word, language)
            if (clock != null) {
                hour = clock.first
                minute = clock.second
                used.add(index)
                index++
                val adjusted = adjust(words, index, language, hour!!, minute, used)
                hour = adjusted.first
                minute = adjusted.second
                while (index in used) index++
                continue
            }
        }

        index++
    }

    if (hour == null && dayOffset == null && weekday == null) return null

    return When(
        start = resolve(now, dayOffset, weekday, hour, minute, meridiem),
        minutes = minutes ?: DEFAULT_MINUTES,
        words = used,
    )
}

/** Apply «και μισή», «και τέταρτο» and «παρά τέταρτο» to an hour. */
private fun adjust(
    words: List<String>, index: Int, language: String,
    hour: Int, minute: Int, used: MutableSet<Int>,
): Pair<Int, Int> {
    if (index >= words.size) return hour to minute

    val word = bare(words[index])
    val following = if (index + 1 < words.size) bare(words[index + 1]) else ""

    if (inTable(word, AND_WORDS, language) && following.isNotEmpty()) {
        if (inTable(following, HALF_WORDS, language)) {
            used.addAll(listOf(index, index + 1)); return hour to 30
        }
        if (inTable(following, QUARTER_WORDS, language)) {
            used.addAll(listOf(index, index + 1)); return hour to 15
        }
    }
    if (inTable(word, TO_WORDS, language) && inTable(following, QUARTER_WORDS, language)) {
        used.addAll(listOf(index, index + 1))
        return ((hour - 1 + 24) % 24) to 45
    }
    return hour to minute
}

/**
 * Read "30 minutes" / «μισή ώρα» / «μία ώρα» starting at [index].
 *
 * Returns null if what follows is not a duration -- "for Anna" must not become
 * an appointment length.
 */
private fun duration(words: List<String>, index: Int, language: String): Pair<Int, Set<Int>>? {
    if (index >= words.size) return null
    val consumed = mutableSetOf(index)
    val word = bare(words[index])

    val count: Double = if (inTable(word, HALF_WORDS, language)) {
        0.5
    } else {
        (numberOf(word, language) ?: return null).toDouble()
    }

    if (index + 1 >= words.size) return null
    val unit = bare(words[index + 1])
    for (lang in order(language)) {
        UNIT_MINUTES[lang]?.forEach { (stem, size) ->
            if (unit.startsWith(stem)) {
                consumed.add(index + 1)
                return maxOf(1, Math.round(count * size).toInt()) to consumed
            }
        }
    }
    return null
}

/**
 * Turn the pieces into one moment.
 *
 * 1. A bare hour of one to seven means the afternoon -- see [AFTERNOON_UNTIL].
 * 2. No hour at all means [DEFAULT_HOUR].
 * 3. A named weekday means its next occurrence, today included only if the
 *    time has not passed.
 * 4. No day at all means today, rolled to tomorrow if the time has passed.
 */
private fun resolve(
    now: LocalDateTime, dayOffset: Int?, weekday: Int?,
    hour: Int?, minute: Int, meridiem: String?,
): LocalDateTime {
    var h = hour
    var m = minute
    when {
        h == null -> { h = DEFAULT_HOUR; m = 0 }
        meridiem == "pm" && h < 12 -> h += 12
        meridiem == "am" && h == 12 -> h = 0
        meridiem == null && h in 1..AFTERNOON_UNTIL -> h += 12
    }

    val start = now.withHour(h!!).withMinute(m).withSecond(0).withNano(0)

    if (weekday != null) {
        var ahead = (weekday - start.dayOfWeek.value + 7) % 7
        if (ahead == 0 && !start.isAfter(now)) ahead = 7
        return start.plusDays(ahead.toLong())
    }
    if (dayOffset != null) return start.plusDays(dayOffset.toLong())
    return if (!start.isAfter(now)) start.plusDays(1) else start
}
