package app.nisos.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDateTime

/**
 * Spoken dates and times.
 *
 * Every rule in When.kt is a judgement call -- what a bare "five" means, what
 * a day with no time means, what happens when the hour has already passed.
 * Judgement calls are exactly what needs pinning down, because the failure
 * mode is not a crash: it is an appointment quietly landing twelve hours or
 * seven days from where you meant it.
 */
class WhenTest {

    /** A Monday, lunchtime -- so "today at five" is ahead and "at nine" gone. */
    private val monday = LocalDateTime.of(2026, 8, 10, 12, 0)

    private fun at(phrase: String, language: String = "el") =
        parseWhen(normalise(phrase).words, language, monday)

    @Test fun `greek phrases resolve`() {
        val expected = mapOf(
            "αύριο στις πέντε" to "2026-08-11T17:00",
            "σήμερα στις πέντε" to "2026-08-10T17:00",
            "μεθαύριο στις δέκα" to "2026-08-12T10:00",
            "αύριο στις 17:30" to "2026-08-11T17:30",
            "αύριο στις οκτώ το πρωί" to "2026-08-11T08:00",
            "αύριο στις οκτώ το βράδυ" to "2026-08-11T20:00",
            "την Πέμπτη στις τρεις" to "2026-08-13T15:00",
            "αύριο στις πέντε και μισή" to "2026-08-11T17:30",
            "αύριο στις πέντε και τέταρτο" to "2026-08-11T17:15",
            "αύριο στις πέντε παρά τέταρτο" to "2026-08-11T16:45",
            "απόψε στις οκτώ" to "2026-08-10T20:00",
        )
        expected.forEach { (phrase, iso) -> assertEquals(phrase, iso, at(phrase)!!.iso()) }
    }

    @Test fun `english phrases resolve`() {
        val expected = mapOf(
            "tomorrow at 5" to "2026-08-11T17:00",
            "tomorrow at 9 am" to "2026-08-11T09:00",
            "tomorrow at 9 pm" to "2026-08-11T21:00",
            "tomorrow at 17:30" to "2026-08-11T17:30",
            "on thursday at 3" to "2026-08-13T15:00",
            "tomorrow at half past five" to "2026-08-11T17:30",
            "tonight at 8" to "2026-08-10T20:00",
            "tomorrow at 5pm" to "2026-08-11T17:00",
        )
        expected.forEach { (phrase, iso) ->
            assertEquals(phrase, iso, at(phrase, "en")!!.iso())
        }
    }

    /**
     * Nobody arranges a dentist for five in the morning and says it that
     * casually. One rule, always the same, easy to say out loud.
     */
    @Test fun `a bare small hour means the afternoon`() {
        assertEquals(17, at("αύριο στις πέντε")!!.start.hour)
        assertEquals(15, at("tomorrow at 3", "en")!!.start.hour)
    }

    @Test fun `a bare large hour is taken at face value`() {
        assertEquals(10, at("αύριο στις δέκα")!!.start.hour)
        assertEquals(11, at("tomorrow at 11", "en")!!.start.hour)
    }

    @Test fun `the morning can always be asked for`() {
        assertEquals(5, at("αύριο στις πέντε το πρωί")!!.start.hour)
        assertEquals(5, at("tomorrow at 5 in the morning", "en")!!.start.hour)
    }

    /** "Put the dentist in for Thursday" is a real sentence. */
    @Test fun `a day with no time gets the default hour`() {
        val moment = at("αύριο")!!
        assertEquals(DEFAULT_HOUR, moment.start.hour)
        assertEquals(11, moment.start.dayOfMonth)
    }

    @Test fun `a time with no day is the next time it happens`() {
        assertEquals("2026-08-10T17:00", at("στις πέντε")!!.iso())          // ahead
        assertEquals("2026-08-11T09:00", at("στις εννέα το πρωί")!!.iso())  // gone
    }

    @Test fun `a weekday that is today means next week`() {
        assertEquals("2026-08-17T09:00", at("τη Δευτέρα στις εννέα το πρωί")!!.iso())
    }

    @Test fun `a weekday still ahead today is today`() {
        assertEquals("2026-08-10T17:00", at("τη Δευτέρα στις πέντε")!!.iso())
    }

    @Test fun `durations`() {
        assertEquals(DEFAULT_MINUTES, at("αύριο στις πέντε")!!.minutes)
        assertEquals(30, at("αύριο στις πέντε για μισή ώρα")!!.minutes)
        assertEquals(120, at("αύριο στις πέντε για δύο ώρες")!!.minutes)
        assertEquals(20, at("αύριο στις πέντε για 20 λεπτά")!!.minutes)
        assertEquals(30, at("tomorrow at 5 for 30 minutes", "en")!!.minutes)
        assertEquals(120, at("tomorrow at 5 for 2 hours", "en")!!.minutes)
    }

    /** "for Anna" must not become an appointment length. */
    @Test fun `for a person is not a duration`() {
        assertEquals(DEFAULT_MINUTES, at("tomorrow at 5 for Anna", "en")!!.minutes)
    }

    @Test fun `no time at all is null not a guess`() {
        listOf("οδοντίατρο", "dentist", "", "με τον γιατρό").forEach {
            assertNull(it, at(it))
        }
    }

    /** Otherwise every house number and phone number becomes an hour. */
    @Test fun `a number on its own is not a time`() {
        assertNull(at("dentist 5", "en"))
    }

    /** The leftovers are the appointment title, so this has to be exact. */
    @Test fun `time words are reported as used`() {
        val words = normalise("οδοντίατρο αύριο στις πέντε").words
        val moment = parseWhen(words, "el", monday)!!
        assertEquals(setOf(1, 2, 3), moment.words)
        assertEquals(
            listOf("οδοντιατρο"),
            words.filterIndexed { index, _ -> index !in moment.words },
        )
    }
}
