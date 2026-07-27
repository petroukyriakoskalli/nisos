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
        action: The action that ran.
        args: Its arguments.
        spoken: What was said back.
        path: ``"routed"`` or ``"reasoned"`` -- which branch was taken.
        source: Which recogniser won.
        timings: Milliseconds per stage, for the log line.
    """

    heard: str = ""
    language: str = "en"
    action: str = "unclear"
    args: dict = field(default_factory=dict)
    spoken: str = ""
    path: str = "routed"
    source: str = "typed"
    timings: dict = field(default_factory=dict)

    def summary(self) -> str:
        """One line describing the turn, in the style of the Termux log."""
        total = sum(self.timings.values())
        stages = "  ".join(f"{k} {v:.0f}ms" for k, v in self.timings.items())
        return (f"[{self.path}/{self.language}] {self.heard!r} -> {self.action} "
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
        action_name, args = match.action, match.args
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
            action_name, args = decision.action, decision.args
        except brain.BrainError as exc:
            log.error("%s", exc)
            turn.action = "unavailable"
            turn.spoken = replies.say("unavailable", turn.language)
            speech.speak(turn.spoken, turn.language, config)
            return turn

    # --- Execute ------------------------------------------------------------
    started = time.perf_counter()
    reply_key, fields = actions_module.execute(action_name, args, ctx)
    turn.timings["exec"] = (time.perf_counter() - started) * 1000
    turn.action = action_name
    turn.args = args

    # --- Answer -------------------------------------------------------------
    started = time.perf_counter()
    turn.spoken = replies.say(reply_key, turn.language, **fields)
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
        memory=Memory(config.expanded("memory.path") or None),
        country_code=str(config.get_path("memory.country_code", "") or ""),
    )
