package app.nisos.core

/**
 * The main loop: hear, decide, act, answer.
 *
 * One turn, start to finish:
 * ```
 *   transcript ──► router ──┬─ hit ──► every step ──┬─ ordinary ─► do it ──┐
 *                           │                       │                      ├─► one reply
 *                           └─ miss ─► Claude ──────┴─ in PREVIEW ─► ask ──┘
 *                                                                     │
 *                                            approve() ◄──────────────┘ (or decline())
 * ```
 *
 * Everything here is orchestration. The interesting decisions live in
 * [route], [REGISTRY] and [SAY]. This is deliberately the only file that knows
 * the order of operations -- which is what made the confirmation step below a
 * change in one place, and is what would make a conversation history the same.
 */

/**
 * Something held back until you say yes, and the words to ask with.
 *
 * @property step exactly what will run on approval -- the same [Step], not a
 *   re-derived one, so nothing can drift between the question and the answer.
 * @property question what to say and show. Already in the turn's language.
 * @property detail one line naming what would be written.
 * @property title what the thing is called.
 */
data class Pending(
    val step: Step,
    val question: String,
    val detail: String,
    val title: String,
)

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
    /**
     * Steps that were **not** carried out, awaiting approval.
     *
     * Non-empty means the turn is unfinished: it asked a question and is
     * waiting. Nothing in here has touched the phone.
     */
    val pending: List<Pending> = emptyList(),
) {
    val awaitingApproval: Boolean get() = pending.isNotEmpty()

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
    //
    // A step in [PREVIEW] is the exception: it is described rather than done,
    // and waits. Order is still preserved, so «άναψε τον φακό και βάλε
    // ραντεβού αύριο στις πέντε» lights the torch now and asks about the
    // appointment -- holding back the harmless half as well would be friction
    // for nothing.
    val held = mutableListOf<Pending>()
    val sentences = mutableListOf<String>()

    for (step in steps) {
        if (step.action in PREVIEW) {
            val proposal = propose(step, language)
            if (proposal == null) {
                // The arguments do not parse -- most often a time phrase it
                // could not read. Say so now rather than after a tap, because
                // the tap would be approving something that was never
                // understood, and the failure would arrive looking like the
                // approval had caused it.
                sentences += say("failed", language)
            } else {
                held += proposal
                sentences += proposal.question
            }
            continue
        }
        val (key, fields) = execute(step.action, step.args, phone)
        sentences += say(key, language, fields)
    }

    return Turn(
        heard = text,
        language = language,
        action = steps.firstOrNull()?.action ?: "unclear",
        steps = steps,
        spoken = stitch(sentences),
        path = path,
        backend = backend,
        millis = System.currentTimeMillis() - started,
        pending = held,
    )
}

/** Describe a step, or null when its arguments cannot be read. */
private fun propose(step: Step, language: String): Pending? {
    val preview = PREVIEW[step.action] ?: return null
    val fields = try {
        preview(step.args, language)
    } catch (_: Exception) {
        return null
    }
    return Pending(
        step = step,
        question = say("${step.action}.confirm", language, fields),
        detail = say("${step.action}.detail", language, fields),
        title = fields["summary"]?.toString().orEmpty(),
    )
}

/**
 * Carry out what was held back, now that it has been approved.
 *
 * Runs the stored [Step] verbatim rather than re-deriving anything from the
 * original sentence. What you approved is what runs.
 */
fun approve(
    pending: List<Pending>,
    phone: Phone,
    language: String,
    heard: String = "",
): Turn {
    val started = System.currentTimeMillis()
    val spoken = stitch(
        pending.map { waiting ->
            val (key, fields) = execute(waiting.step.action, waiting.step.args, phone)
            say(key, language, fields)
        }
    )
    return Turn(
        heard = heard,
        language = language,
        action = pending.firstOrNull()?.step?.action ?: "unclear",
        steps = pending.map { it.step },
        spoken = spoken,
        path = "approved",
        millis = System.currentTimeMillis() - started,
    )
}

/**
 * Say that nothing was done.
 *
 * Takes no [Phone] on purpose -- declining cannot touch anything, and a
 * signature that could not reach the phone even by accident is the cheapest
 * possible proof of that.
 */
fun decline(language: String, heard: String = ""): Turn = Turn(
    heard = heard,
    language = language,
    action = "cancelled",
    spoken = say("cancelled", language),
    path = "declined",
)

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
