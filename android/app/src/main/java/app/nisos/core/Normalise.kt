package app.nisos.core

import java.text.Normalizer

/**
 * Text normalisation shared by both language routers.
 *
 * A port of `nisos/normalise.py`, and the reasoning is unchanged. Greek breaks
 * naive regex three ways at once:
 *
 *  1. Verbs inflect      -- άναψε / ανάψτε / να ανάψεις all mean "turn it on"
 *  2. Accents wander     -- a recogniser may return «φακό» or «φακο» on any day
 *  3. Final sigma        -- «φακός» at the end of a word, «φακοσ» anywhere else
 *
 * [normalise] flattens (2) and (3) so the router only has to cope with (1),
 * which it does by matching *stems* rather than whole words.
 *
 * Nothing in this file touches Android, which is the point: it runs in a plain
 * JVM unit test in milliseconds.
 */

/**
 * Compile a pattern that can actually see Greek.
 *
 * ⚠️ **Every regex in this program must go through here.** Java's `\w` is
 * ASCII-only unless `UNICODE_CHARACTER_CLASS` is on, and the failure is not
 * the obvious one:
 *
 * ```
 * "\\bφακ"                          matches   -- \b alone IS Unicode-aware
 * "αναψ\\w*"                        matches   -- \w* happily matches zero
 * "\\b(αναψ|ανοιξ)\\w*\\b.*\\bφακ"  FAILS     -- and this is a torch route
 * ```
 *
 * The two harmless-looking pieces combine into a broken one: `\w*` matches
 * nothing, so `\b` is then asked for a boundary in the middle of «αναψε», and
 * there isn't one. Every Greek stem route in the table is written that way,
 * so without the flag the assistant is silently English-only -- which is
 * exactly what the first CI run showed, and exactly what nobody would notice
 * until standing in a dark room asking for the torch.
 *
 * Python has no equivalent trap: `\w` there is Unicode-aware by default for
 * str patterns, which is why the tables worked as written for months before
 * being retyped into Kotlin.
 */
fun unicodePattern(source: String): Regex = Regex("(?U)$source")

/** Greek block, roughly. Only ever used to tell the two scripts apart. */
private val GREEK = Regex("[\\u0370-\\u03FF\\u1F00-\\u1FFF]")
private val LATIN = Regex("[a-zA-Z]")
private val WHITESPACE = Regex("\\s+")

/** Remove diacritics while leaving the base letters intact. */
fun stripAccents(text: String): String =
    Normalizer.normalize(text, Normalizer.Form.NFD)
        .filter { Character.getType(it) != Character.NON_SPACING_MARK.toInt() }

/**
 * Flattened text that still remembers how the user actually spelled it.
 *
 * Kotlin will not let you subclass String, so where the Python has a `str`
 * subclass this is a small value type with the flattened form in [text]. The
 * addition is [original], which hands back the user's own spelling of a range
 * of words.
 *
 * Why it has to exist: patterns are written against flattened text, so that is
 * what the router matches. That is right for *matching* and wrong for anything
 * that ends up **written down** -- a calendar entry titled «οδοντιατρο» rather
 * than «οδοντίατρος» is the assistant's plumbing leaking into your diary.
 *
 * Word positions rather than character offsets, deliberately: flattening is
 * not length-preserving, but it never splits or joins a word, so the two word
 * lists always line up. When they somehow don't, the flattened words are used
 * and the worst case is the old behaviour.
 */
class Normalised(val text: String, raw: String = text) {
    val words: List<String> = if (text.isBlank()) emptyList() else text.split(" ")

    private val rawWords: List<String> = run {
        val candidate = raw.trim().split(WHITESPACE).filter { it.isNotEmpty() }
        if (candidate.size == words.size) candidate else words
    }

    /** The user's own spelling of the words at [indices], in order. */
    fun original(indices: Collection<Int>): String =
        indices.sorted()
            .filter { it >= 0 && it < rawWords.size }
            .joinToString(" ") { rawWords[it] }

    override fun toString(): String = text
}

/**
 * Flatten a transcript into the form the router patterns are written against.
 *
 * Lowercases, strips accents, unifies final sigma, and collapses runs of
 * whitespace. Harmless on English, so the pipeline calls it once and hands the
 * result to both language tables.
 */
fun normalise(text: String): Normalised {
    val flattened = stripAccents(text.lowercase())
        .replace('ς', 'σ')   // final sigma -> plain sigma
        .replace(WHITESPACE, " ")
        .trim()
    return Normalised(flattened, text)
}

/**
 * Which alphabet a string is written in: "el", "en", or null.
 *
 * A cheap sanity check, *not* the language detection -- that falls out of the
 * router for free, because the alphabets are disjoint. Null means neither, or
 * a roughly equal mix, which usually means code-switching.
 */
fun scriptOf(text: String): String? {
    val greek = GREEK.findAll(text).count()
    val latin = LATIN.findAll(text).count()
    return when {
        greek == 0 && latin == 0 -> null
        greek > latin * 2 -> "el"
        latin > greek * 2 -> "en"
        else -> null
    }
}

/**
 * Number words, because Greek recognisers return «δώδεκα», not "12".
 *
 * Without this roughly half of all timer and volume commands fail silently:
 * the pattern matches, the argument comes back null, and the action quietly
 * does nothing. English recognisers convert to digits far more reliably, but
 * "set a timer for ten minutes" still slips through, so both get a table.
 *
 * Keys are written in NORMALISED form. Run a candidate through [normalise]
 * before adding it here.
 */
val NUMBER_WORDS: Map<String, Map<String, Int>> = mapOf(
    "el" to mapOf(
        "μηδεν" to 0, "ενα" to 1, "μια" to 1, "δυο" to 2, "τρια" to 3,
        "τρεισ" to 3, "τεσσερα" to 4, "τεσσερισ" to 4, "πεντε" to 5,
        "εξι" to 6, "εφτα" to 7, "επτα" to 7, "οκτω" to 8, "οχτω" to 8,
        "εννεα" to 9, "εννια" to 9, "δεκα" to 10, "εντεκα" to 11,
        "δωδεκα" to 12, "δεκατρια" to 13, "δεκατεσσερα" to 14,
        "δεκαπεντε" to 15, "εικοσι" to 20, "εικοσιπεντε" to 25,
        "τριαντα" to 30, "σαραντα" to 40, "πενηντα" to 50, "εξηντα" to 60,
        "ενενηντα" to 90, "εκατο" to 100,
    ),
    "en" to mapOf(
        "zero" to 0, "one" to 1, "two" to 2, "three" to 3, "four" to 4,
        "five" to 5, "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9,
        "ten" to 10, "eleven" to 11, "twelve" to 12, "thirteen" to 13,
        "fourteen" to 14, "fifteen" to 15, "twenty" to 20, "thirty" to 30,
        "forty" to 40, "fifty" to 50, "sixty" to 60, "ninety" to 90,
        "hundred" to 100,
    ),
)

private val DIGITS = Regex("\\d+")

/**
 * Pull the first number out of a normalised transcript.
 *
 * Digits always win. Only when there are none does it fall back to spelled-out
 * words, checking [language] first and then the other, so a code-switched
 * sentence still resolves. Longest word first, or «δεκαπεντε» (15) is shadowed
 * by «δεκα» (10).
 */
fun parseNumber(text: String, language: String? = null): Int? {
    DIGITS.find(text)?.let { return it.value.toIntOrNull() }

    val order = buildList {
        if (language != null && NUMBER_WORDS.containsKey(language)) add(language)
        addAll(NUMBER_WORDS.keys.filter { it != language })
    }
    for (lang in order) {
        val table = NUMBER_WORDS[lang] ?: continue
        for (word in table.keys.sortedByDescending { it.length }) {
            // «δωδεκα» would in fact survive a bare Regex here -- `\b` alone
            // is Unicode-aware in Java; it is `\w` that is not. Going through
            // the helper anyway, so nobody has to know which of the two this
            // line happens to use.
            if (unicodePattern("\\b${Regex.escape(word)}\\b").containsMatchIn(text)) {
                return table[word]
            }
        }
    }
    return null
}
