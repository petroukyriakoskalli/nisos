"""The language model -- called only when the router misses.

Two backends, one :class:`Decision` out. :func:`think` picks between them and
everything upstream is none the wiser:

* **llama** -- a ``llama-server`` on localhost. No network, no account, no
  per-turn cost; a 4B model and 2.5 GB of storage.
* **claude** -- the Anthropic API, in :mod:`nisos.cloud`. Nothing to install
  and a far better model; needs a network, a key, and sends the transcript off
  the phone.

``brain.backend`` chooses: ``claude``, ``llama``, or ``auto`` (the default --
online when a key is present, dropping to llama-server if the network is gone
and it happens to be running). Either way the router still answers ~80% of
commands on the phone with no model involved at all, so what leaves the device
is the minority of phrases that miss it.

Both talk plain HTTP with nothing but the standard library. That is deliberate:
this program has no pip dependencies at all, so installing it in Termux never
involves compiling a wheel against a missing header at eleven at night.

The important part is that neither backend is allowed to improvise
--------------------------------------------------------------
Small models improvise; ask a 4B for JSON a hundred times and you will get
several replies wrapped in a friendly sentence. llama.cpp can constrain
generation token by token against a GBNF grammar, which makes malformed output
impossible rather than unlikely -- that single flag takes tool-calling from
roughly 70% reliable to essentially perfect, and it is why
``grammar/action.gbnf`` is worth keeping in step with the action registry. The
API's equivalent is a forced ``tool_choice``; see :mod:`nisos.cloud`.

Extending
---------
:func:`build_prompt` is where you teach the local model new tricks (its online
counterpart is :func:`nisos.cloud.build_system`). Keep it short -- every token
in the system prompt is paid for on every reasoned request, and Greek costs two
to three times more tokens per word than English.
"""

from __future__ import annotations

import json
import logging
import time
import urllib.error
import urllib.request
from dataclasses import dataclass
from pathlib import Path

__all__ = ["Decision", "BrainError", "think", "think_llama", "backend_for",
           "build_prompt", "load_grammar", "available"]

log = logging.getLogger(__name__)

#: Everything ``brain.backend`` accepts. Anything else is treated as ``auto``
#: with a warning, because a typo here should not take the assistant down.
BACKENDS = ("auto", "claude", "llama")


class BrainError(Exception):
    """The model was unreachable or unintelligible.

    Attributes:
        reply_key: Which entry in :data:`nisos.replies.SAY` describes this
            failure, when the raiser knows. "No API key" and "rate limited"
            and "the local model isn't running" are three different things to
            be told, and one apology for all of them sends you looking in the
            wrong place -- which is exactly the bug that put ``no_model`` in
            the reply table. None means "caller, work it out".
    """

    def __init__(self, message: str, reply_key: str | None = None):
        super().__init__(message)
        self.reply_key = reply_key


@dataclass
class Decision:
    """What the model decided to do.

    Attributes:
        action: An action name from the registry, or ``"answer"`` when it just
            wants to say something, or ``"unclear"`` when it could not tell.
        args: Arguments for the action.
        seconds: How long the round trip took.
        backend: Which brain answered -- ``"llama"`` or ``"claude"``. Ends up
            in the log line, so a slow turn can be blamed on the right thing.
    """

    action: str
    args: dict
    seconds: float = 0.0
    backend: str = "llama"


LANGUAGE_NAMES = {"el": "Greek", "en": "English"}


def build_prompt(text: str, language: str, actions: list[str],
                 memories: dict[str, str] | None = None) -> str:
    """Compose the instruction sent to the model.

    Args:
        text: What the user said, as transcribed.
        language: ``"el"`` or ``"en"``. Only affects the language of any spoken
            answer -- action names stay English regardless, which is the whole
            reason a 4B model copes with this at all.
        actions: Registered action names, so the prompt and the registry cannot
            drift apart.

    Returns:
        A complete prompt string, in llama.cpp's plain-completion format.
    """
    spoken = LANGUAGE_NAMES.get(language, "English")
    catalogue = ", ".join(actions)

    # Only the memories that look relevant to THIS utterance, and only a few.
    # Dumping the whole store would push a 4B model past the point where it
    # reasons well -- and Greek costs 2-3x more tokens per word, so it degrades
    # twice as fast in the language you most need it to work in.
    known = ""
    if memories:
        lines = "\n".join(f"- {k}: {v}" for k, v in memories.items())
        known = f"Things you have been told:\n{lines}\n"

    return (
        "<|im_start|>system\n"
        "You convert a phone user's spoken request into exactly one action.\n"
        f"Available actions: {catalogue}\n"
        f"{known}"
        "Reply with JSON only: {\"action\": \"<name>\", \"args\": {...}}\n"
        "Action names are always English, never translated.\n"
        "Use \"answer\" with an args.text field to reply conversationally, "
        f"written in {spoken}.\n"
        "Use \"unclear\" if the request makes no sense.\n"
        "<|im_end|>\n"
        f"<|im_start|>user\n{text}<|im_end|>\n"
        "<|im_start|>assistant\n"
    )


def load_grammar(path: str) -> str | None:
    """Read a GBNF grammar file.

    Args:
        path: Path to ``action.gbnf``, absolute or relative to the repo root.

    Returns:
        The grammar text, or None if the file is missing -- in which case the
        model still works, just less reliably. A warning is logged because
        running without the grammar is almost never intended.
    """
    candidate = Path(path).expanduser()
    if not candidate.is_absolute():
        candidate = Path(__file__).resolve().parent.parent / path

    if not candidate.is_file():
        log.warning("Grammar not found at %s -- the model's JSON will be "
                    "best-effort rather than guaranteed", candidate)
        return None
    return candidate.read_text(encoding="utf-8")


def available(url: str, timeout: float = 1.0) -> bool:
    """True if llama-server answers on `url`.

    Used by the CLI's ``--check`` so you can tell "the model is not running"
    apart from "the model gave a bad answer" without reading a stack trace.
    """
    try:
        with urllib.request.urlopen(f"{url}/health", timeout=timeout) as response:
            return response.status == 200
    except (urllib.error.URLError, OSError):
        return False


def backend_for(config) -> str:
    """Which brain a turn would use right now: ``"claude"`` or ``"llama"``.

    Resolves ``auto`` -- the default -- to ``claude`` when a key is available
    and ``llama`` otherwise. Note this checks for a *key*, not for a network:
    probing the network before every turn would cost a round trip on the path
    where somebody is standing there waiting. The network is discovered by
    trying, and :func:`think` handles the miss.
    """
    from . import cloud

    choice = str(config.get_path("brain.backend", "auto") or "auto").lower()
    if choice not in BACKENDS:
        log.warning("Unknown brain.backend %r -- treating it as 'auto'. "
                    "Valid values: %s", choice, ", ".join(BACKENDS))
        choice = "auto"

    if choice == "auto":
        return "claude" if cloud.available(config) else "llama"
    return choice


def think(text: str, language: str, actions: list[str], config,
          memories: dict[str, str] | None = None) -> Decision:
    """Ask whichever brain is configured what to do.

    Args:
        text: The transcript.
        language: Which language to answer in.
        actions: Registered action names.
        config: A :class:`nisos.config.Config`.
        memories: Anything stored that looks relevant to this utterance.

    Returns:
        A :class:`Decision`.

    Raises:
        BrainError: When the chosen brain could not be reached or used.

    On ``auto``, an online failure falls through to llama-server *only if it is
    actually running* -- starting a 2.5 GB model from inside a turn somebody is
    waiting on would be worse than admitting the network is down. If it isn't
    running, the original online failure is what gets raised, because that is
    the one the user can do something about.
    """
    from . import cloud

    backend = backend_for(config)

    if backend == "llama":
        return think_llama(text, language, actions, config, memories=memories)

    try:
        return cloud.think(text, language, actions, config, memories=memories)
    except BrainError as exc:
        explicit = str(config.get_path("brain.backend", "auto") or "auto").lower()
        if explicit == "claude":
            raise
        if not available(config.get_path("brain.url"), timeout=1.0):
            raise
        log.warning("Online brain failed (%s) -- falling back to llama-server",
                    exc)
        return think_llama(text, language, actions, config, memories=memories)


def think_llama(text: str, language: str, actions: list[str], config,
                memories: dict[str, str] | None = None) -> Decision:
    """Ask the local llama-server what to do.

    Args:
        text: The transcript.
        language: Which language to answer in.
        actions: Registered action names.
        config: A :class:`nisos.config.Config`.

    Returns:
        A :class:`Decision`. Falls back to ``unclear`` rather than raising when
        the model returns something unusable, so the assistant always has
        something to say.

    Raises:
        BrainError: Only when llama-server itself is unreachable, which is a
            setup problem the user needs told about plainly.
    """
    url = config.get_path("brain.url", "http://127.0.0.1:8080")
    payload = {
        "prompt": build_prompt(text, language, actions, memories),
        "n_predict": config.get_path("brain.max_tokens", 128),
        "temperature": config.get_path("brain.temperature", 0.2),
        "stop": ["<|im_end|>"],
        "cache_prompt": True,  # the system prompt is identical every time
    }

    grammar = load_grammar(config.get_path("brain.grammar", "grammar/action.gbnf"))
    if grammar:
        payload["grammar"] = grammar

    request = urllib.request.Request(
        f"{url}/completion",
        data=json.dumps(payload).encode("utf-8"),
        headers={"Content-Type": "application/json"},
        method="POST",
    )

    started = time.perf_counter()
    try:
        with urllib.request.urlopen(
            request, timeout=config.get_path("brain.timeout", 40.0)
        ) as response:
            body = json.loads(response.read().decode("utf-8"))
    except urllib.error.URLError as exc:
        raise BrainError(
            f"llama-server unreachable at {url} -- is it running, and did "
            f"Android suspend it? (termux-wake-lock)",
            reply_key="no_model",
        ) from exc
    except (json.JSONDecodeError, OSError) as exc:
        raise BrainError(f"llama-server gave an unreadable response: {exc}") from exc

    elapsed = time.perf_counter() - started
    return _parse(body.get("content", ""), elapsed)


def _parse(content: str, elapsed: float) -> Decision:
    """Turn the model's raw output into a :class:`Decision`.

    With the grammar loaded this is trivial. Without it, the model may have
    wrapped the JSON in prose, so fall back to finding the first brace-balanced
    object before giving up.
    """
    content = content.strip()

    try:
        data = json.loads(content)
    except json.JSONDecodeError:
        start = content.find("{")
        end = content.rfind("}")
        if start == -1 or end <= start:
            log.warning("Model returned no JSON: %r", content[:200])
            return Decision("unclear", {}, elapsed)
        try:
            data = json.loads(content[start:end + 1])
        except json.JSONDecodeError:
            log.warning("Model returned malformed JSON: %r", content[:200])
            return Decision("unclear", {}, elapsed)

    action = data.get("action")
    if not isinstance(action, str) or not action:
        return Decision("unclear", {}, elapsed)

    args = data.get("args")
    if not isinstance(args, dict):
        args = {}

    return Decision(action, args, elapsed)
