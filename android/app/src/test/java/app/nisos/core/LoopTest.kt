package app.nisos.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

/**
 * One turn, end to end, with the phone replaced by a recorder.
 *
 * These prove the thing that actually matters: a Greek sentence in produces a
 * Greek sentence out, with an English action name in between, and a sentence
 * containing two commands does two things. If either breaks, the design has.
 */
class LoopTest {

    private val monday = LocalDateTime.of(2026, 8, 10, 12, 0)
    private val routes = buildRoutes { monday }

    /** Records what it was asked to do instead of doing it. */
    private class Recorder : Phone {
        val calls = mutableListOf<String>()
        val facts = mutableMapOf<String, String>()
        var torchOn = false

        override fun torch(on: Boolean) { torchOn = on; calls += "torch:$on" }
        override fun battery() = 78 to "charging"
        override fun startTimer(minutes: Int) { calls += "timer:$minutes" }
        override fun sendSms(to: String, body: String) { calls += "sms:$to:$body" }
        override fun copyToClipboard(text: String) { calls += "copy:$text" }
        override fun doNotDisturb(on: Boolean) { calls += "dnd:$on" }
        override fun setVolume(level: Int) { calls += "volume:$level" }
        override fun nextEvent() = CalendarEntry("Standup", 25)
        override fun addEvent(summary: String, start: LocalDateTime, minutes: Int) {
            calls += "event:$summary:$start:$minutes"
        }
        override fun openWhatsApp(number: String, body: String) { calls += "wa:$number" }
        override fun lookupNumber(name: String) = facts["contact:$name"]
        override fun now(): LocalDateTime = LocalDateTime.of(2026, 8, 10, 16, 32)
        override fun remember(key: String, value: String) { facts[key] = value }
        override fun recall(key: String) = facts[key]
        override fun forget(key: String) = facts.remove(key) != null
        override fun memoryCounts() = facts.size to 0
    }

    private fun turn(text: String, brain: Brain? = null, hint: String = "en") =
        handle(text, Recorder(), brain, hint, emptyMap(), routes)

    private fun turnWith(phone: Recorder, text: String) =
        handle(text, phone, null, "en", emptyMap(), routes)

    // -- The load-bearing invariant ----------------------------------------

    @Test fun `greek in greek out with an english action in between`() {
        val result = turn("άναψε τον φακό")
        assertEquals("routed", result.path)
        assertEquals("el", result.language)
        assertEquals("torch.on", result.action)          // English
        assertEquals("Άναψα τον φακό.", result.spoken)   // Greek
    }

    @Test fun `english in english out`() {
        val result = turn("torch on")
        assertEquals("en", result.language)
        assertEquals("Torch on.", result.spoken)
    }

    @Test fun `action names stay english in greek`() {
        val result = turn("βάλε χρονόμετρο δώδεκα λεπτά")
        assertEquals("timer.set", result.action)
        assertTrue(result.spoken.contains("λεπτά"))
    }

    @Test fun `a routed turn never wakes the brain`() {
        val explode = Brain { _, _, _ -> throw AssertionError("woke the brain") }
        handle("άναψε τον φακό", Recorder(), explode, "el", emptyMap(), routes)
    }

    // -- More than one thing ------------------------------------------------

    @Test fun `both actions actually run`() {
        val phone = Recorder()
        val result = turnWith(phone, "βάλε χρονόμετρο δώδεκα λεπτά και άναψε τον φακό")
        assertEquals(listOf("timer.set", "torch.on"), result.steps.map { it.action })
        assertTrue(phone.torchOn)
        assertTrue(phone.calls.contains("timer:12"))
    }

    /**
     * Two calls to the speech engine means two utterances, and on Android the
     * second routinely lands on top of the first.
     */
    @Test fun `one reply covers both`() {
        val result = turn("βάλε χρονόμετρο δώδεκα λεπτά και άναψε τον φακό")
        assertEquals("12 λεπτά, ξεκίνησα. Άναψα τον φακό.", result.spoken)
    }

    /** They were separate requests. The reply says which half worked. */
    @Test fun `a failing step does not cancel the others`() {
        val phone = Recorder()
        val result = turnWith(phone, "set a timer and torch on")
        assertEquals(listOf("timer.set", "torch.on"), result.steps.map { it.action })
        assertTrue(phone.torchOn)
        assertTrue(result.spoken.contains("That didn't work."))
        assertTrue(result.spoken.contains("Torch on."))
    }

    @Test fun `the log line names every action`() {
        assertTrue(turn("torch on and what time is it").summary().contains("torch.on + time.read"))
    }

    /** The stitching must be invisible on the common path. */
    @Test fun `a one-action turn sounds exactly as it always did`() {
        assertEquals("Torch on.", turn("torch on").spoken)
    }

    // -- Reasoned turns ------------------------------------------------------

    @Test fun `a miss falls through to the brain`() {
        val brain = Brain { _, _, _ ->
            Decision("answer", mapOf("text" to "Το Τόκιο."), 1.4)
        }
        val result = turn("ποια είναι η πρωτεύουσα της Ιαπωνίας", brain, "el")
        assertEquals("reasoned", result.path)
        assertEquals("Το Τόκιο.", result.spoken)
    }

    @Test fun `a reasoned plan runs in order`() {
        val brain = Brain { _, _, _ ->
            Decision.fromSteps(listOf(Step("torch.on"), Step("answer", mapOf("text" to "Έγινε."))))
        }
        val result = turn("κάνε τα δύο πράγματα", brain, "el")
        assertEquals(listOf("torch.on", "answer"), result.steps.map { it.action })
        assertEquals("Άναψα τον φακό. Έγινε.", result.spoken)
    }

    /**
     * Four failures used to share one apology. Each has to name the thing you
     * can actually do something about.
     */
    @Test fun `a missing key blames the key not the network`() {
        val brain = Brain { _, _, _ -> throw BrainError("nope", "no_key") }
        val result = turn("summarise this", brain, "el")
        assertEquals("no_key", result.action)
        assertTrue(result.spoken.contains("κλειδί"))
    }

    @Test fun `a refusal is not dressed up as a fault`() {
        val brain = Brain { _, _, _ -> throw BrainError("nope", "refused") }
        assertEquals("Το μοντέλο αρνήθηκε να απαντήσει.", turn("summarise", brain, "el").spoken)
    }

    /**
     * No brain configured at all is a legitimate way to run this: the router
     * still answers the great majority of commands and nothing ever leaves the
     * phone. It must say so rather than fall silent.
     */
    @Test fun `no brain at all still answers`() {
        val result = turn("summarise this note", null, "en")
        assertEquals("no_key", result.action)
        assertTrue(result.spoken.isNotEmpty())
    }

    // -- Appointments --------------------------------------------------------

    @Test fun `an appointment reaches the calendar and is read back`() {
        val phone = Recorder()
        val result = turnWith(phone, "βάλε στο ημερολόγιο οδοντίατρο αύριο στις πέντε")
        assertEquals("calendar.add", result.action)
        assertTrue(phone.calls.any { it.startsWith("event:οδοντίατρο:2026-08-11T17:00") })
        assertEquals("οδοντίατρο, 11/08 στις 17:00.", result.spoken)
    }

    // -- Replies stay in step ------------------------------------------------

    @Test fun `every action has both languages`() {
        assertEquals(emptyList<Pair<String, String>>(), missingReplies())
    }
}
