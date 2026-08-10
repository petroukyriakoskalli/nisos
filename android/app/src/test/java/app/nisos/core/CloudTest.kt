package app.nisos.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

/**
 * The online brain.
 *
 * Nothing here touches the network. The Claude API is a documented shape, so
 * what is worth testing is that we *send* that shape -- forced tool call, no
 * sampling parameters, a schema that can express more than one action -- and
 * that every way it can fail turns into something the assistant can say out
 * loud.
 *
 * A wrong payload fails loudly on the first run. A mishandled 401 fails as
 * "can't do that offline" on a phone with four bars, which is the kind of bug
 * that costs an evening.
 */
class CloudTest {

    private val monday = LocalDateTime.of(2026, 8, 10, 14, 30)

    private fun brain(key: String? = "sk-ant-test") =
        ClaudeBrain(keyProvider = { key }, clock = { monday })

    private fun payload() =
        brain().payload("summarise this", "en", emptyMap(), CloudSettings())

    // -- The request ---------------------------------------------------------

    @Test fun `the model is forced to call the tool`() {
        val body = payload()
        val choice = body.getJSONObject("tool_choice")
        assertEquals("tool", choice.getString("type"))
        assertEquals(TOOL_NAME, choice.getString("name"))
        assertEquals(TOOL_NAME, body.getJSONArray("tools").getJSONObject(0).getString("name"))
    }

    /**
     * Current models reject these outright, and leaking one here would 400
     * every reasoned turn.
     */
    @Test fun `no sampling parameters`() {
        val body = payload()
        listOf("temperature", "top_p", "top_k").forEach {
            assertFalse(it, body.has(it))
        }
    }

    @Test fun `effort and thinking defaults`() {
        val body = payload()
        assertEquals("low", body.getJSONObject("output_config").getString("effort"))
        assertEquals("adaptive", body.getJSONObject("thinking").getString("type"))
    }

    @Test fun `thinking can be turned off`() {
        val body = brain().payload("x", "en", emptyMap(), CloudSettings(thinking = "off"))
        assertEquals("disabled", body.getJSONObject("thinking").getString("type"))
    }

    @Test fun `blank settings are omitted rather than sent empty`() {
        val body = brain().payload("x", "en", emptyMap(), CloudSettings(effort = "", thinking = ""))
        assertFalse(body.has("output_config"))
        assertFalse(body.has("thinking"))
    }

    // -- The prompt ----------------------------------------------------------

    @Test fun `every action is offered`() {
        val system = buildSystem("el", actionNames())
        actionNames().forEach { assertTrue(it, system.contains(it)) }
    }

    /** The load-bearing design decision, asserted for the online path too. */
    @Test fun `greek asks for greek words but english action names`() {
        val system = buildSystem("el", actionNames())
        assertTrue(system.contains("Greek"))
        assertTrue(system.contains("torch.on"))
    }

    /** A new action must not need a second edit here to be reachable. */
    @Test fun `the enum is generated from the registry`() {
        val step = buildTool(actionNames())
            .getJSONObject("input_schema")
            .getJSONObject("properties")
            .getJSONObject("steps")
            .getJSONObject("items")
        val enum = step.getJSONObject("properties").getJSONObject("action").getJSONArray("enum")
        assertEquals(actionNames().size, enum.length())
        actionNames().forEachIndexed { index, name -> assertEquals(name, enum.getString(index)) }
    }

    /**
     * The schema is the whole ceiling: a model cannot report a second action
     * it has nowhere to put.
     */
    @Test fun `the tool can express more than one action`() {
        val schema = buildTool(actionNames()).getJSONObject("input_schema")
        assertEquals("array", schema.getJSONObject("properties").getJSONObject("steps").getString("type"))
        assertEquals("steps", schema.getJSONArray("required").getString(0))
    }

    /** So the system prefix stays byte-identical and cacheable. */
    @Test fun `the system prompt is stable`() {
        assertEquals(buildSystem("en", actionNames()), buildSystem("en", actionNames()))
    }

    /**
     * "Tomorrow at five" is unanswerable without it -- and putting it in the
     * system block would break the cache on every single turn.
     */
    @Test fun `the clock travels with the request not the prefix`() {
        val user = buildUser("dentist tomorrow at five", emptyMap(), monday)
        assertTrue(user, user.contains("2026-08-10"))
        assertTrue(user, user.contains("Monday"))
        assertFalse(buildSystem("en", actionNames()).contains("2026-08-10"))
    }

    @Test fun `memories go in the user turn`() {
        val user = buildUser("when is her birthday", mapOf("marilena" to "3 March"), monday)
        assertTrue(user.contains("3 March"))
        assertTrue(user.contains("when is her birthday"))
    }

    // -- The response --------------------------------------------------------

    @Test fun `a plan becomes several steps in order`() {
        val input = JSONObject(
            """{"steps":[{"action":"timer.set","args":{"minutes":12}},
                         {"action":"torch.on","args":{}}]}"""
        )
        val steps = stepsFrom(input)
        assertEquals(listOf("timer.set", "torch.on"), steps.map { it.action })
        assertEquals(12, steps[0].args["minutes"])
    }

    /**
     * The single-action shape has to keep working: a model that has slipped
     * the constraint reaches for it, and answering "didn't catch that" to a
     * clear instruction is the worse failure.
     */
    @Test fun `the old single-action shape is still read`() {
        val steps = stepsFrom(JSONObject("""{"action":"torch.off"}"""))
        assertEquals(listOf("torch.off"), steps.map { it.action })
    }

    @Test fun `nonsense becomes no steps`() {
        assertTrue(stepsFrom(JSONObject("""{"nonsense":true}""")).isEmpty())
        assertTrue(stepsFrom(JSONObject("""{"steps":[]}""")).isEmpty())
    }

    /** Silence is the one response this program is not allowed to give. */
    @Test fun `an empty plan is unclear rather than an empty turn`() {
        assertEquals("unclear", Decision.fromSteps(emptyList()).action)
    }

    @Test fun `a decision keeps the first step readable the old way`() {
        val decision = Decision.fromSteps(
            listOf(Step("timer.set", mapOf("minutes" to 12)), Step("torch.on"))
        )
        assertEquals("timer.set", decision.action)
        assertEquals(12, decision.args["minutes"])
        assertEquals(2, decision.plan.size)
    }

    // -- Failures ------------------------------------------------------------

    @Test fun `no key is reported before any request is made`() {
        val error = runCatching { brain(key = null).think("x", "en", emptyMap()) }
            .exceptionOrNull()
        assertTrue(error is BrainError)
        assertEquals("no_key", (error as BrainError).replyKey)
    }

    @Test fun `a blank key counts as no key`() {
        val error = runCatching { brain(key = "   ").think("x", "en", emptyMap()) }
            .exceptionOrNull()
        assertEquals("no_key", (error as BrainError).replyKey)
    }
}
