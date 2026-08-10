package app.nisos.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

/**
 * The router, ported test for ported test.
 *
 * These are the same assertions the Python suite makes, and they are here for
 * a reason beyond diligence: the tables were retyped into another language,
 * and a regex that silently stopped matching would be invisible until you were
 * standing in a dark room asking for the torch.
 *
 * The clock is fixed so appointment tests are not a lottery at midnight.
 */
class RouterTest {

    private val monday = LocalDateTime.of(2026, 8, 10, 12, 0)
    private val routes = buildRoutes { monday }

    private fun hit(phrase: String) = route(phrase, routes)

    // -- English -----------------------------------------------------------

    @Test fun `english phrases route`() {
        val expected = mapOf(
            "torch on" to "torch.on",
            "torch off" to "torch.off",
            "turn on the torch" to "torch.on",
            "turn off the flashlight" to "torch.off",
            "open the light" to "torch.on",
            "close the light" to "torch.off",
            "set a timer for 12 minutes" to "timer.set",
            "remind me in 5 minutes" to "timer.set",
            "what's my battery" to "battery.read",
            "silence everything" to "dnd.on",
            "what time is it" to "time.read",
            "next meeting" to "calendar.next",
        )
        expected.forEach { (phrase, action) ->
            val match = hit(phrase)
            assertNotNull("$phrase routed nowhere", match)
            assertEquals(phrase, action, match!!.action)
            assertEquals(phrase, "en", match.language)
        }
    }

    // -- Greek -------------------------------------------------------------

    @Test fun `greek phrases route`() {
        val expected = mapOf(
            "άναψε τον φακό" to "torch.on",
            "ανάψτε τον φακό" to "torch.on",
            "να ανάψεις τον φακό" to "torch.on",
            "σβήσε τον φακό" to "torch.off",
            "κλείσε τον φακό" to "torch.off",
            "βάλε χρονόμετρο δώδεκα λεπτά" to "timer.set",
            "θύμισέ μου σε δέκα λεπτά" to "timer.set",
            "πόση μπαταρία έχω" to "battery.read",
            "σίγαση" to "dnd.on",
            "τι ώρα είναι" to "time.read",
            "πότε είναι το ραντεβού μου" to "calendar.next",
        )
        expected.forEach { (phrase, action) ->
            val match = hit(phrase)
            assertNotNull("$phrase routed nowhere", match)
            assertEquals(phrase, action, match!!.action)
            assertEquals(phrase, "el", match.language)
        }
    }

    @Test fun `inflection variants agree`() {
        val forms = listOf("άναψε τον φακό", "ανάψτε τον φακό", "να ανάψεις τον φακό")
        assertEquals(setOf("torch.on"), forms.map { hit(it)!!.action }.toSet())
    }

    @Test fun `works without accents`() {
        assertEquals("torch.on", hit("αναψε τον φακο")!!.action)
        assertEquals("torch.on", hit("άναψε τον φακό")!!.action)
    }

    /**
     * The load-bearing property: the alphabets do not overlap. If this ever
     * fails, the 'router doubles as language detector' design is unsound.
     */
    @Test fun `the two alphabets never cross-match`() {
        listOf("άναψε τον φακό", "πόση μπαταρία έχω", "σίγαση").forEach {
            assertEquals("el", hit(it)!!.language)
        }
        listOf("torch on", "battery level", "silence everything").forEach {
            assertEquals("en", hit(it)!!.language)
        }
    }

    @Test fun `timer arguments parse per language`() {
        assertEquals(12, hit("βάλε χρονόμετρο δώδεκα λεπτά")!!.args["minutes"])
        assertEquals(12, hit("set a timer for 12 minutes")!!.args["minutes"])
    }

    @Test fun `misses return null`() {
        listOf("ποια είναι η πρωτεύουσα της Ιαπωνίας", "summarise the note", "", "   ")
            .forEach { assertNull(it, hit(it)) }
    }

    // -- More than one thing ------------------------------------------------

    @Test fun `two greek commands become two steps`() {
        val match = hit("βάλε χρονόμετρο δώδεκα λεπτά και άναψε τον φακό")!!
        assertEquals(listOf("timer.set", "torch.on"), match.plan.map { it.action })
        assertEquals(12, match.plan[0].args["minutes"])
    }

    @Test fun `two english commands become two steps`() {
        val match = hit("torch on and set a timer for 5 minutes")!!
        assertEquals(listOf("torch.on", "timer.set"), match.plan.map { it.action })
    }

    @Test fun `then and also split too`() {
        assertEquals(2, hit("torch off then what time is it")!!.plan.size)
        assertEquals(2, hit("what's my battery also what time is it")!!.plan.size)
    }

    @Test fun `a comma is a boundary`() {
        assertEquals(2, hit("torch on, what time is it")!!.plan.size)
    }

    @Test fun `three things still work`() {
        val match = hit("άναψε τον φακό και τι ώρα είναι και πόση μπαταρία έχω")!!
        assertEquals(
            listOf("torch.on", "time.read", "battery.read"),
            match.plan.map { it.action },
        )
    }

    @Test fun `one command is exactly one step`() {
        assertEquals(1, hit("άναψε τον φακό")!!.plan.size)
    }

    // -- ...and when splitting would be wrong -------------------------------

    /**
     * The safety rule: a split is only taken when *every* piece routes.
     * Without it, «και» inside a message would cut the message in half -- a
     * far worse bug than the one multi-action fixes.
     */
    @Test fun `and inside a message body is left alone`() {
        val match = hit("text Marilena I'm late and we'll eat later")!!
        assertEquals("sms.send", match.action)
        assertEquals(1, match.plan.size)
        assertTrue(match.args["body"].toString().contains("later"))
    }

    @Test fun `and inside a greek message body is left alone`() {
        val match = hit("στείλε στη Μαριλένα ότι άργησα και θα φάμε αργότερα")!!
        assertEquals("sms.send", match.action)
        assertEquals(1, match.plan.size)
    }

    @Test fun `a trailing conjunction produces no empty step`() {
        assertEquals(1, hit("torch on and")!!.plan.size)
    }

    @Test fun `too many pieces is treated as one sentence`() {
        val wordy = List(6) { "torch on" }.joinToString(" and ")
        assertEquals(1, hit(wordy)!!.plan.size)
    }

    // -- Appointments -------------------------------------------------------

    @Test fun `english appointments route`() {
        listOf(
            "put dentist in my calendar tomorrow at 5",
            "add dentist to my diary tomorrow at 5",
            "book a meeting tomorrow at 5",
            "schedule an appointment tomorrow at 5",
        ).forEach { assertEquals(it, "calendar.add", hit(it)!!.action) }
    }

    @Test fun `greek appointments route`() {
        listOf(
            "βάλε στο ημερολόγιο οδοντίατρο αύριο στις πέντε",
            "γράψε στο ημερολόγιο ραντεβού αύριο",
            "κλείσε ραντεβού με τον γιατρό αύριο στις πέντε",
            "βάλε ραντεβού αύριο στις πέντε",
        ).forEach {
            val match = hit(it)!!
            assertEquals(it, "calendar.add", match.action)
            assertEquals(it, "el", match.language)
        }
    }

    /** calendar.add sits above calendar.next: a question must book nothing. */
    @Test fun `asking about the next one is not making one`() {
        assertEquals("calendar.next", hit("next meeting")!!.action)
        assertEquals("calendar.next", hit("πότε είναι το ραντεβού μου")!!.action)
    }

    /** «γράψε στο ημερολόγιο» matches the sms pattern too. */
    @Test fun `writing to the calendar is not a text message`() {
        assertEquals("calendar.add", hit("γράψε στο ημερολόγιο γιατρό αύριο")!!.action)
    }

    @Test fun `the title keeps the user's own spelling`() {
        val match = hit("βάλε στο ημερολόγιο οδοντίατρο αύριο στις πέντε")!!
        assertEquals("οδοντίατρο", match.args["summary"])
    }

    @Test fun `the title survives words in the middle`() {
        val match = hit("κλείσε ραντεβού με τον γιατρό αύριο στις πέντε")!!
        assertEquals("ραντεβού με τον γιατρό", match.args["summary"])
    }

    @Test fun `english titles lose the chrome and keep the rest`() {
        assertEquals(
            "meeting with Nikos",
            hit("book a meeting with Nikos tomorrow at 5")!!.args["summary"],
        )
    }

    @Test fun `a title-less appointment gets a name rather than none`() {
        assertEquals("Ραντεβού", hit("βάλε στο ημερολόγιο αύριο στις πέντε")!!.args["summary"])
        assertEquals("Appointment", hit("put it in my calendar tomorrow at 5")!!.args["summary"])
    }

    /** A bare 5 means the evening -- see the rule in When.kt. */
    @Test fun `the time comes through as a date`() {
        val start = hit("put dentist in my calendar tomorrow at 5")!!.args["start"]
        assertEquals("2026-08-11T17:00", start)
    }

    /** Guessing an hour for a diary entry is worse than admitting the miss. */
    @Test fun `no time means no start rather than a guess`() {
        assertTrue(!hit("book a meeting with Nikos")!!.args.containsKey("start"))
    }

    // -- Messaging channel --------------------------------------------------

    @Test fun `messaging defaults to sms and whatsapp switches it`() {
        assertEquals("sms.send", hit("text Marilena I'm running late")!!.action)
        assertEquals("whatsapp.send", hit("text Marilena on whatsapp I'm late")!!.action)
    }

    @Test fun `the brand name is stripped from the message`() {
        val body = hit("text Marilena on whatsapp running late")!!.args["body"].toString()
        assertTrue(body, !body.lowercase().contains("whatsapp"))
    }

    // -- Tables stay in step ------------------------------------------------

    @Test fun `every routed action exists`() {
        val registered = actionNames().toSet()
        routes.forEach { (language, list) ->
            list.forEach { entry ->
                assertTrue(
                    "$language route ${entry.pattern} points at ${entry.action}",
                    entry.action in registered,
                )
            }
        }
    }

    /**
     * Otherwise the assistant is cleverer in one language than the other and
     * you will never remember which. torch.off is reachable in English through
     * the direction rewrite rather than its own route.
     */
    @Test fun `both languages cover the same actions`() {
        val byLanguage = routes.mapValues { (_, list) ->
            list.map { it.action }.toSet() + "torch.off"
        }
        assertEquals(byLanguage["en"], byLanguage["el"])
    }
}
