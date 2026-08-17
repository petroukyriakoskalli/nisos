package app.nisos.core

/**
 * The brain contract, and the one shared reader for whatever it returns.
 *
 * The Python had two backends -- a local llama-server and the Claude API. This
 * app has one: **there is no local model here.** That is not a regression, it
 * is the trade that made an APK possible at all. The 2.5 GB download and the
 * two cross-compiled binaries were the entire reason nisos lived in Termux;
 * without them the app is a few megabytes and installs with a tap.
 *
 * What did not change is that the router answers ~80% of commands with no
 * model involved, so what leaves the phone is the minority of phrases that
 * miss it -- and «άναψε τον φακό» still sends nothing anywhere.
 */

/** Which brain answered. Kept as a string so a third one needs no new type. */
const val BACKEND_CLAUDE = "claude"

/**
 * The model was unreachable or unusable.
 *
 * @property replyKey which entry in [SAY] describes this failure, when the
 *   raiser knows. "No API key", "rate limited" and "no network" are three
 *   different things to be told, and one apology for all of them sends you
 *   looking in the wrong place.
 */
class BrainError(message: String, val replyKey: String? = null) : Exception(message)

/**
 * What the model decided to do -- one action, or several in order.
 *
 * @property action the first action. Kept as a plain field for the same reason
 *   [Match.action] is: a one-action turn is the common case.
 */
data class Decision(
    val action: String,
    val args: Map<String, Any?> = emptyMap(),
    val seconds: Double = 0.0,
    val backend: String = BACKEND_CLAUDE,
    val steps: List<Step> = emptyList(),
) {
    val plan: List<Step> get() = steps.ifEmpty { listOf(Step(action, args)) }

    companion object {
        /**
         * Build a Decision from a plan, keeping [action] on the first step.
         *
         * An empty plan becomes `unclear` rather than an empty turn: a model
         * answering with nothing at all can happen, and silence is the one
         * response this program is not allowed to give.
         */
        fun fromSteps(
            steps: List<Step>,
            seconds: Double = 0.0,
            backend: String = BACKEND_CLAUDE,
        ): Decision {
            val plan = steps.ifEmpty { listOf(Step("unclear")) }
            return Decision(plan[0].action, plan[0].args, seconds, backend, plan)
        }
    }
}

/** Anything that can turn a transcript into a plan. */
fun interface Brain {
    fun think(text: String, language: String, memories: Map<String, String>): Decision
}
