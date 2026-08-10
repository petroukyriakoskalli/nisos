"""The main loop: listen, decide, act, answer.

One turn of the assistant, start to finish::

    record -> race the recognisers -> route
                                       |
                          hit ---------+--------- miss
                           |                       |
                      execute now             ask the model
                           |                       |
                           +-----------+-----------+
                                       |
                                    execute
                                       |
                                     speak

The two paths differ by about 1.4 seconds, which is the entire reason the
router exists. Everything here is orchestration -- the interesting decisions
live in :mod:`nisos.router`, :mod:`nisos.actions` and :mod:`nisos.brain`.

Extending
---------
:func:`handle` is deliberately the only place that knows the order of
operations. If you want a confirmation step before destructive actions, or a
conversation history, this is the file to change.
"""

from __future__ import annotations

import logging
import time
from dataclasses import dataclass, field

from . import actions as actions_module
from . import audio, brain, replies, speech, stt
from .router import route

__all__ = ["Turn", "handle", "listen_and_handle"]

log = logging.getLogger(__name__)


@dataclass
class Turn:
    """Everything that happened in one exchange, for logging and tests.

    Attributes:
        heard: The transcript used.
        language: Which language it was decided to be.
        action: The first action that ran -- or, when the turn failed before
            anything ran, the reply key describing why.
        args: Its arguments.
        steps: Every action the turn performed, in order. One entry for the
            common case; the point of it existing is the case that isn't.
        spoken: What was said back -- one utterance, however many actions.
        path: ``"routed"`` or ``"reasoned"`` -- which branch was taken.
        source: Which recogniser won.
        backend: Which brain answered on a reasoned turn -- ``"claude"`` or
            ``"llama"``. Empty on a routed turn, where no model ran at all.
        timings: Milliseconds per stage, for the log line.
    """

    heard: str = ""
    language: str = "en"
    action: str = "unclear"
    args: dict = field(default_factory=dict)
    steps: list = field(default_factory=list)
    spoken: str = ""
    path: str = "routed"
    source: str = "typed"
    backend: str = ""
    timings: dict = field(default_factory=dict)

    def summary(self) -> str:
        """One line describing the turn, in the style of the Termux log.

        The backend is in here because "that took four seconds" has different
        answers depending on whether it went to the phone or to the network,
        and the log is the only place you can tell after the fact. A turn that
        did two things names both, for the same reason: a log that shows the
        first one is how a dropped second one stays invisible.
        """
        total = sum(self.timings.values())
        stages = "  ".join(f"{k} {v:.0f}ms" for k, v in self.timings.items())
        via = f"{self.path}:{self.backend}" if self.backend else self.path
        did = " + ".join(step.action for step in self.steps) or self.action
        return (f"[{via}/{self.language}] {self.heard!r} -> {did} "
                f"| {stages} | total {total:.0f}ms")


def handle(text: str, config, context=None,
           language_hint: str | None = None) -> Turn:
    """Take a transcript through routing, execution and speech.

    This is the function to call if you already have text -- from the CLI's
    ``--text`` flag, from a test, or from some other trigger entirely. It never
    touches the microphone.

    Args:
        text: What the user said.
        config: A :class:`nisos.config.Config`.
        context: An :class:`nisos.actions.ExecutionContext`. Built from config
            if not supplied.
        language_hint: Language to assume if the router misses. Falls back to
            the configured sticky language.

    Returns:
        A completed :class:`Turn`.
    """
    turn = Turn(heard=text)
    ctx = context or build_context(config)

    # --- Fast path: the router, which also tells us the language ------------
    started = time.perf_counter()
    match = route(text)
    turn.timings["route"] = (time.perf_counter() - started) * 1000

    if match:
        turn.path = "routed"
        turn.language = match.language
        turn.steps = list(match.steps)
    else:
        # --- Slow path: wake the model --------------------------------------
        turn.path = "reasoned"
        turn.language = (language_hint
                         or config.get_path("general.language", "en"))
        # Surface anything remembered that looks relevant, so "when is
        # Marilena's birthday" can be answered from what you told it.
        memories = {}
        if getattr(ctx, "memory", None) is not None:
            try:
                memories = ctx.memory.relevant(text)
            except Exception:  # noqa: BLE001 -- memory must never block a turn
                log.exception("couldn't read memory")

        try:
            decision = brain.think(text, turn.language,
                                   actions_module.action_names(), config,
                                   memories=memories)
            turn.timings["model"] = decision.seconds * 1000
            turn.backend = decision.backend
            turn.steps = list(decision.steps)
        except brain.BrainError as exc:
            log.error("%s", exc)
            # Several very different failures used to say the same thing.
            # "Can't do that offline" is right for a phrase that genuinely
            # needs the network, and actively misleading when the truth is a
            # missing API key or an llama-server that simply is not up -- which
            # is the normal state in app mode, where the UI deliberately
            # doesn't start it. Saying the wrong one sends you looking for a
            # network problem on a program that may not even want one.
            #
            # A BrainError that knows which it was says so (exc.reply_key); the
            # fallback probe is for the ones that don't.
            if exc.reply_key:
                turn.action = exc.reply_key
            else:
                reachable = brain.available(config.get_path("brain.url"),
                                            timeout=1.0)
                turn.action = "unavailable" if reachable else "no_model"
            turn.spoken = replies.say(turn.action, turn.language)
            speech.speak(turn.spoken, turn.language, config)
            return turn

    # --- Execute ------------------------------------------------------------
    # In order, and every one of them. A step that fails does not stop the
    # ones after it: they were separate requests, and «άναψε τον φακό και
    # στείλε στη Μαρία» has no reason to leave the torch off because the
    # message failed. Each step contributes its own sentence, so the reply
    # says which half worked.
    started = time.perf_counter()
    results = [actions_module.execute(step.action, step.args, ctx)
               for step in turn.steps]
    turn.timings["exec"] = (time.perf_counter() - started) * 1000
    turn.action = turn.steps[0].action
    turn.args = turn.steps[0].args

    # --- Answer -------------------------------------------------------------
    started = time.perf_counter()
    turn.spoken = replies.stitch(
        [replies.say(key, turn.language, **fields) for key, fields in results])
    speech.speak(turn.spoken, turn.language, config)
    turn.timings["speak"] = (time.perf_counter() - started) * 1000

    log.info("%s", turn.summary())
    return turn


def listen_and_handle(config, context=None) -> Turn:
    """Record, transcribe, then hand off to :func:`handle`.

    The probe passed to the recogniser race is what lets Android's fast answer
    cancel Whisper: it simply asks whether the router would accept the text.

    Args:
        config: A :class:`nisos.config.Config`.
        context: Optional execution context.

    Returns:
        A completed :class:`Turn`. On a failure to hear anything, the turn is
        still returned with ``action="unclear"`` and the apology already spoken.
    """
    language = config.get_path("general.language", "en")
    path = config.expanded("audio.path")

    try:
        audio.record(path,
                     seconds=config.get_path("audio.seconds", 8),
                     sample_rate=config.get_path("audio.sample_rate", 16000))
        audio.wait_for_file(path)
    except audio.RecordingError as exc:
        log.error("%s", exc)

    try:
        transcript = stt.transcribe(config, path, probe=lambda t: route(t) is not None)
    except stt.SttError as exc:
        log.error("%s", exc)
        spoken = replies.say("unclear", language)
        speech.speak(spoken, language, config)
        return Turn(action="unclear", language=language, spoken=spoken)

    if not transcript:
        spoken = replies.say("unclear", language)
        speech.speak(spoken, language, config)
        return Turn(action="unclear", language=language, spoken=spoken)

    turn = handle(transcript.text, config, context,
                  language_hint=transcript.language)
    turn.source = transcript.source
    turn.timings["stt"] = transcript.seconds * 1000
    return turn


def build_context(config):
    """Create an :class:`nisos.actions.ExecutionContext` from config.

    Kept separate so tests and the CLI can build one without a full config, and
    so there is exactly one place that knows how config maps onto the context.
    """
    from .memory import Memory
    return actions_module.ExecutionContext(
        dry_run=config.get_path("general.dry_run", False),
        tasker_task=config.get_path("tasker.task", "NisosAction"),
        answer_timeout=float(config.get_path("tasker.answer_timeout", 4.0)),
        calendar_answer=(config.expanded("tasker.calendar_answer")
                         or actions_module.CALENDAR_ANSWER),
        contacts={k.lower(): v for k, v in
                  (config.get_path("contacts", {}) or {}).items()},
        calendar_id=str(config.get_path("tasker.calendar_id", "") or ""),
        memory=Memory(config.expanded("memory.path") or None),
        country_code=str(config.get_path("memory.country_code", "") or ""),
    )
