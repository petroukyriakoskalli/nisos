package app.nisos.core

/**
 * The main loop: hear, decide, act, answer.
 *
 * One turn, start to finish:
 * ```
 *   transcript ──► router ──┬─ hit ──► execute every step ──► one reply
 *                           │
 *                           └─ miss ─► Claude ──► execute every step ──► one reply
 * ```
 *
 * Everything here is orchestration. The interesting decisions live in
 * [route], [REGISTRY] and [SAY]. This is deliberately the only file that knows
 * the order of operations, so a confirmation step before destructive actions,
 * or a conversation history, is a change in exactly one place.
 */

/** Everything that happened in one exchange, for the screen and the log. */
data class Turn(
    val heard: String = "",
    val language: String = "en",
    val action: String = "unclear",
    val steps: List<Step> = emptyList(),
    val spoken: String = "",
    /** `routed` or `reasoned` -- which branch was taken. */
    val path: String = "routed",
    /** Which brain answered. Empty on a routed turn, where none did. */
    val backend: String = "",
    val millis: Long = 0,
) {
    /**
     * One line describing the turn.
     *
     * A turn that did two things names both. A log that shows only the first
     * is how a dropped second one stays invisible.
     */
    fun summary(): String {
        val via = if (backend.isNotEmpty()) "$path:$backend" else path
        val did = steps.joinToString(" + ") { it.action }.ifEmpty { action }
        return "[$via/$language] \"$heard\" -> $did | ${millis}ms"
    }
}

/**
 * Take a transcript through routing, execution and the spoken reply.
 *
 * @param brain consulted only when the router misses. Null means there is no
 *   brain configured at all, which is a legitimate way to run this -- the
 *   router still answers the great majority of commands, and nothing leaves
 *   the phone ever.
 * @param languageHint what to assume if the router misses and cannot tell.
 */
fun handle(
    text: String,
    phone: Phone,
    brain: Brain? = null,
    languageHint: String = "en",
    memories: Map<String, String> = emptyMap(),
    routes: Map<String, List<Route>> = DEFAULT_ROUTES,
): Turn {
    val started = System.currentTimeMillis()
    val match = route(text, routes)

    val path: String
    val language: String
    val steps: List<Step>
    var backend = ""

    if (match != null) {
        path = "routed"
        language = match.language
        steps = match.plan
    } else {
        path = "reasoned"
        language = languageHint
        if (brain == null) {
            return failure(text, language, "no_key", path, started)
        }
        val decision = try {
            brain.think(text, language, memories)
        } catch (error: BrainError) {
            return failure(text, language, error.replyKey ?: "unavailable", path, started)
        }
        backend = decision.backend
        steps = decision.plan
    }

    // In order, and every one of them. A step that fails does not stop the
    // ones after it: they were separate requests, and «άναψε τον φακό και
    // στείλε στη Μαρία» has no reason to leave the torch off because the
    // message failed. Each step contributes its own sentence, so the reply
    // says which half worked.
    val spoken = stitch(steps.map { step ->
        val (key, fields) = execute(step.action, step.args, phone)
        say(key, language, fields)
    })

    return Turn(
        heard = text,
        language = language,
        action = steps.firstOrNull()?.action ?: "unclear",
        steps = steps,
        spoken = spoken,
        path = path,
        backend = backend,
        millis = System.currentTimeMillis() - started,
    )
}

private fun failure(
    text: String,
    language: String,
    replyKey: String,
    path: String,
    started: Long,
): Turn = Turn(
    heard = text,
    language = language,
    action = replyKey,
    spoken = say(replyKey, language),
    path = path,
    millis = System.currentTimeMillis() - started,
)
