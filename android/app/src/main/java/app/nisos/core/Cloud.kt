package app.nisos.core

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDateTime
import java.time.format.TextStyle
import java.util.Locale

/**
 * The online brain -- Anthropic's Claude API.
 *
 * A port of `nisos/cloud.py`, and the important design decision came across
 * unchanged: **the model is forced to call one tool.** Declare a tool whose
 * input schema is the plan, then require it with `tool_choice`. The model
 * cannot reply with prose, cannot wrap the JSON in a friendly sentence, and
 * cannot invent a verb outside the enum -- which is generated from [REGISTRY],
 * so a new action is offered the moment it is registered.
 *
 * `HttpURLConnection` and `org.json`, both in the platform, rather than a
 * client library. The Python avoided the official SDK because Termux has no
 * pydantic wheel; the reason here is simpler and the same in spirit -- the
 * wire format is stable and versioned, and this is sixty lines.
 */
private const val ENDPOINT = "https://api.anthropic.com/v1/messages"

/** Sent on every request. Not optional, and not the model version. */
private const val API_VERSION = "2023-06-01"

/** The single tool the model is forced to call. */
const val TOOL_NAME = "act"

private val LANGUAGE_NAMES = mapOf("el" to "Greek", "en" to "English")

/** Settings the UI can change without touching this file. */
data class CloudSettings(
    val model: String = "claude-opus-5",
    val maxTokens: Int = 2048,
    /** Classifying one spoken sentence, with somebody waiting. */
    val effort: String = "low",
    /**
     * Stays on even at low effort. Turning it off is the tempting saving and
     * the wrong one: with thinking disabled the model occasionally writes its
     * tool call as ordinary text, which the forced tool_choice mostly prevents
     * but not provably.
     */
    val thinking: String = "adaptive",
    val timeoutMillis: Int = 30_000,
)

class ClaudeBrain(
    private val keyProvider: () -> String?,
    private val settings: () -> CloudSettings = { CloudSettings() },
    private val clock: () -> LocalDateTime = { LocalDateTime.now() },
    private val open: (String) -> HttpURLConnection = { URL(it).openConnection() as HttpURLConnection },
) : Brain {

    override fun think(text: String, language: String, memories: Map<String, String>): Decision {
        val key = keyProvider()?.takeIf { it.isNotBlank() }
            ?: throw BrainError("No Anthropic API key stored.", replyKey = "no_key")

        val config = settings()
        val body = payload(text, language, memories, config).toString()

        val started = System.nanoTime()
        val response: String
        val connection = open(ENDPOINT)
        try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.connectTimeout = config.timeoutMillis
            connection.readTimeout = config.timeoutMillis
            connection.setRequestProperty("x-api-key", key)
            connection.setRequestProperty("anthropic-version", API_VERSION)
            connection.setRequestProperty("content-type", "application/json")
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

            val status = connection.responseCode
            if (status !in 200..299) throw httpError(status, errorText(connection))
            response = connection.inputStream.bufferedReader().use(BufferedReader::readText)
        } catch (error: BrainError) {
            throw error
        } catch (error: Exception) {
            // No network, DNS down, aeroplane mode. Distinct from an API error.
            throw BrainError("Could not reach the Claude API: ${error.message}", "unavailable")
        } finally {
            connection.disconnect()
        }

        val elapsed = (System.nanoTime() - started) / 1_000_000_000.0
        return parse(JSONObject(response), elapsed)
    }

    // ----------------------------------------------------------------------

    internal fun payload(
        text: String,
        language: String,
        memories: Map<String, String>,
        config: CloudSettings,
    ): JSONObject {
        val message = JSONObject()
            .put("role", "user")
            .put("content", buildUser(text, memories, clock()))

        val body = JSONObject()
            .put("model", config.model)
            // Caps thinking *and* reply together, so this is not as generous
            // as it looks. A cap is not a charge -- only real output is billed.
            .put("max_tokens", config.maxTokens)
            .put("system", buildSystem(language, actionNames()))
            .put("messages", JSONArray().put(message))
            .put("tools", JSONArray().put(buildTool(actionNames())))
            // The grammar equivalent: it must call the tool, so it cannot ramble.
            .put("tool_choice", JSONObject().put("type", "tool").put("name", TOOL_NAME))

        // No temperature, top_p or top_k anywhere. Current models reject
        // sampling parameters outright, and leaking one here would 400 every
        // reasoned turn.
        when (config.thinking) {
            "adaptive" -> body.put("thinking", JSONObject().put("type", "adaptive"))
            "off", "disabled" -> body.put("thinking", JSONObject().put("type", "disabled"))
        }
        if (config.effort.isNotBlank()) {
            body.put("output_config", JSONObject().put("effort", config.effort))
        }
        return body
    }

    private fun parse(body: JSONObject, elapsed: Double): Decision {
        val stop = body.optString("stop_reason")

        // Checked before reading content: on a refusal there may be none.
        if (stop == "refusal") {
            throw BrainError("The request was declined by the API's safety filters.", "refused")
        }

        val content = body.optJSONArray("content") ?: JSONArray()
        for (index in 0 until content.length()) {
            val block = content.optJSONObject(index) ?: continue
            if (block.optString("type") == "tool_use" && block.optString("name") == TOOL_NAME) {
                return Decision.fromSteps(
                    stepsFrom(block.optJSONObject("input") ?: JSONObject()),
                    elapsed,
                    BACKEND_CLAUDE,
                )
            }
        }

        if (stop == "max_tokens") {
            throw BrainError("Claude ran out of room before it finished -- raise maxTokens.")
        }
        throw BrainError("Claude returned no action (stop_reason=$stop).")
    }
}

/**
 * Compose the system prompt.
 *
 * Byte-identical for a given language, and that matters: prompt caching is a
 * prefix match, so keeping this stable means the two prefixes (one per
 * language) are reused rather than re-billed. Anything that changes per
 * utterance -- the transcript, the memories, the clock -- goes in the user
 * turn instead, by [buildUser].
 */
fun buildSystem(language: String, actions: List<String>): String {
    val spoken = LANGUAGE_NAMES[language] ?: "English"
    val catalogue = actions.joinToString("\n") { "- $it" }
    return """
        You turn a phone user's spoken request into actions.

        The request was transcribed from speech, so expect the odd wrong word and pick the action the person clearly meant.

        Usually that is one action. When they clearly asked for two things, give two steps in the order they said them -- do not drop one, and do not invent a second.

        Actions:
        $catalogue

        calendar.add takes summary, start as "YYYY-MM-DDTHH:MM" in local time, and minutes. The current date and time is given with each request, so "tomorrow at five" is something you can work out.

        Use "answer" with an args.text field to reply conversationally, written in $spoken, in one or two spoken sentences -- it is read aloud, so no lists, no markdown, no emoji.
        Use "unclear" if the request makes no sense.
        Action names are always English and never translated. Only args.text is in $spoken.
    """.trimIndent()
}

/**
 * Compose the user turn: when it is, what was said, and what it was told.
 *
 * The clock is the most tempting thing to put in the system block, where it
 * would break the prompt cache on every single request. Without it "tomorrow
 * at five" is unanswerable -- a model has no clock.
 */
fun buildUser(text: String, memories: Map<String, String>, now: LocalDateTime): String {
    val day = now.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH)
    val stamp = String.format(
        Locale.ROOT, "%s %04d-%02d-%02d %02d:%02d",
        day, now.year, now.monthValue, now.dayOfMonth, now.hour, now.minute,
    )

    val parts = mutableListOf("Now: $stamp")
    if (memories.isNotEmpty()) {
        val known = memories.entries.joinToString("\n") { "- ${it.key}: ${it.value}" }
        parts += "Things you have been told:\n$known"
    }
    parts += "Request: $text"
    return parts.joinToString("\n\n")
}

/**
 * The one tool the model is forced to call.
 *
 * The input is a **list** of steps, not a single action. "Set a timer for
 * twelve minutes and turn on the torch" is one sentence and two things, and a
 * schema that can only express one of them guarantees the second is lost --
 * the model has nowhere to put it.
 *
 * `args` is deliberately free-form: the arguments differ per action and
 * enumerating every combination would be a second registry to keep in step.
 * The `action` enum is the part that has to be exact, and it is generated.
 */
fun buildTool(actions: List<String>): JSONObject {
    val enum = JSONArray().also { array -> actions.forEach { array.put(it) } }

    val step = JSONObject()
        .put("type", "object")
        .put(
            "properties",
            JSONObject()
                .put(
                    "action",
                    JSONObject().put("type", "string").put("enum", enum)
                        .put("description", "Which action to perform."),
                )
                .put(
                    "args",
                    JSONObject().put("type", "object").put(
                        "description",
                        "Arguments for the action, e.g. {\"minutes\": 10} for timer.set, " +
                            "{\"to\": \"Anna\", \"body\": \"...\"} for sms.send, " +
                            "{\"summary\": \"Dentist\", \"start\": \"2026-08-11T17:00\", " +
                            "\"minutes\": 60} for calendar.add, {\"text\": \"...\"} for " +
                            "answer. Empty for actions that take none.",
                    ),
                ),
        )
        .put("required", JSONArray().put("action"))

    val schema = JSONObject()
        .put("type", "object")
        .put(
            "properties",
            JSONObject().put(
                "steps",
                JSONObject().put("type", "array").put("minItems", 1)
                    .put(
                        "description",
                        "The actions to perform, in the order the user said them. One " +
                            "entry unless they genuinely asked for more than one thing.",
                    )
                    .put("items", step),
            ),
        )
        .put("required", JSONArray().put("steps"))

    return JSONObject()
        .put("name", TOOL_NAME)
        .put("description", "Do what the user asked on their phone, or answer them.")
        .put("input_schema", schema)
}

/**
 * Read a plan out of the model's tool input, in either shape it can arrive in.
 *
 * The schema pins it to `{"steps": [...]}`, but the single-action
 * `{"action": ..., "args": ...}` shape is still accepted. It costs three lines
 * and the alternative is answering "didn't catch that" to a perfectly clear
 * instruction when a constraint has slipped.
 */
fun stepsFrom(payload: JSONObject): List<Step> {
    val listed = payload.optJSONArray("steps")
    if (listed != null) {
        val found = (0 until listed.length())
            .mapNotNull { listed.optJSONObject(it) }
            .mapNotNull { stepOf(it) }
        return found
    }
    return listOfNotNull(stepOf(payload))
}

private fun stepOf(item: JSONObject): Step? {
    val action = item.optString("action").takeIf { it.isNotBlank() } ?: return null
    val args = item.optJSONObject("args") ?: return Step(action)
    return Step(action, args.keys().asSequence().associateWith { args.get(it) })
}

private fun errorText(connection: HttpURLConnection): String = try {
    val raw = connection.errorStream?.bufferedReader()?.use(BufferedReader::readText).orEmpty()
    JSONObject(raw).optJSONObject("error")?.optString("message")?.takeIf { it.isNotBlank() }
        ?: raw.take(400)
} catch (_: Exception) {
    connection.responseMessage ?: "no detail"
}

/**
 * Turn an HTTP status into something worth hearing and worth reading.
 *
 * The response body is always included. Suppressing the output of a call to
 * something external cost the Python project a diagnosis three separate times;
 * an API error with the reason stripped out is the same mistake in a new place.
 */
private fun httpError(status: Int, detail: String): BrainError = when {
    status == 401 || status == 403 ->
        BrainError("The Claude API rejected the key (HTTP $status): $detail", "no_key")
    status == 429 -> BrainError("Rate limited by the Claude API: $detail", "unavailable")
    status >= 500 -> BrainError("The Claude API is having trouble (HTTP $status): $detail", "unavailable")
    else -> BrainError("Claude API error (HTTP $status): $detail")
}
