package app.nisos.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    // -- Appointments, which now wait to be approved -------------------------

    /**
     * The load-bearing half of a confirmation: **nothing is written**. If this
     * test ever passes with an event in `phone.calls`, the approval step has
     * become decoration.
     */
    @Test fun `an appointment waits and writes nothing`() {
        val phone = Recorder()
        val result = turnWith(phone, "βάλε στο ημερολόγιο οδοντίατρο αύριο στις πέντε")

        assertEquals("calendar.add", result.action)
        assertTrue(result.awaitingApproval)
        assertEquals(emptyList<String>(), phone.calls)
    }

    /**
     * The question reads the whole event back, **weekday included**.
     *
     * 10 Aug 2026 is a Monday, so "tomorrow" is Tuesday the 11th. The weekday is
     * the point of the exercise: "11/08" does not tell you which day that is, and
     * working it out is exactly the work nobody does.
     */
    @Test fun `the question names the day it landed on`() {
        val result = turnWith(Recorder(), "put dentist in my calendar tomorrow at 5")
        val waiting = result.pending.single()

        assertTrue(waiting.question, waiting.question.startsWith("Add "))
        assertTrue(waiting.question, waiting.question.contains("Tuesday 11/08 at 17:00"))
        assertTrue(waiting.question, waiting.question.endsWith("?"))
        // DEFAULT_MINUTES, which is an hour. Worth pinning: the duration is the
        // one field nothing else in the app ever shows you.
        assertEquals("60 minutes", waiting.detail)
        assertEquals(waiting.question, result.spoken)
    }

    @Test fun `the weekday is named in the language being spoken`() {
        val waiting = turnWith(Recorder(), "βάλε στο ημερολόγιο οδοντίατρο αύριο στις πέντε")
            .pending.single()

        assertEquals("οδοντίατρο", waiting.title)
        assertEquals("60 λεπτά", waiting.detail)
        assertTrue(waiting.question, waiting.question.startsWith("Να βάλω οδοντίατρο"))
        assertTrue(waiting.question, waiting.question.endsWith(";"))   // Greek question mark
        assertTrue(waiting.question, waiting.question.contains("11/08 στις 17:00"))
        assertFalse(waiting.question, waiting.question.contains("Tuesday"))
    }

    /** Approving runs the stored step, so what was shown is what gets written. */
    @Test fun `approving writes exactly what was offered`() {
        val phone = Recorder()
        val asked = turnWith(phone, "βάλε στο ημερολόγιο οδοντίατρο αύριο στις πέντε")

        val done = approve(asked.pending, phone, asked.language, asked.heard)

        assertEquals("approved", done.path)
        assertEquals("calendar.add", done.action)
        assertEquals(listOf("event:οδοντίατρο:2026-08-11T17:00:60"), phone.calls)
        assertEquals("οδοντίατρο, 11/08 στις 17:00.", done.spoken)
        assertTrue(done.pending.isEmpty())
    }

    @Test fun `declining writes nothing and does not sound like a failure`() {
        val phone = Recorder()
        val asked = turnWith(phone, "βάλε στο ημερολόγιο οδοντίατρο αύριο στις πέντε")

        val done = decline(asked.language, asked.heard)

        assertEquals(emptyList<String>(), phone.calls)
        assertEquals("cancelled", done.action)
        assertEquals("Δεν το έβαλα.", done.spoken)
    }

    /**
     * Holding the appointment back must not hold the torch back. They were two
     * separate requests, and making the reversible one wait on a tap is friction
     * bought for nothing.
     */
    @Test fun `the harmless half of a two-part turn still happens`() {
        val phone = Recorder()
        val result = turnWith(phone, "torch on and put dentist in my calendar tomorrow at 5")

        assertTrue(phone.torchOn)
        assertEquals(listOf("torch:true"), phone.calls)
        assertEquals(1, result.pending.size)
        assertTrue(result.spoken, result.spoken.startsWith("Torch on."))
        assertTrue(result.spoken, result.spoken.contains("Tuesday 11/08"))
    }

    /**
     * An unreadable time fails now, not after a tap. Approving something that
     * was never understood would make the failure look like the approval caused
     * it -- and would put a card on screen describing nothing.
     */
    @Test fun `an appointment with no readable time fails before it is offered`() {
        val phone = Recorder()
        val brain = Brain { _, _, _ ->
            Decision.fromSteps(listOf(Step("calendar.add", mapOf("summary" to "dentist"))))
        }
        val result = handle("summarise this note", phone, brain, "en", emptyMap(), routes)

        assertTrue(result.pending.isEmpty())
        assertEquals(emptyList<String>(), phone.calls)
        assertEquals("That didn't work.", result.spoken)
    }

    // -- Replies stay in step ------------------------------------------------

    @Test fun `every action has both languages`() {
        assertEquals(emptyList<Pair<String, String>>(), missingReplies())
    }

    /** Every action that asks for approval has the words to ask with. */
    @Test fun `every previewed action has confirmation wording`() {
        assertEquals(emptyList<Pair<String, String>>(), missingConfirmations())
    }
}
