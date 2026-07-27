"""The language model -- called only when the router misses.

Talks to a ``llama-server`` running on localhost over plain HTTP, using nothing
but the standard library. That is deliberate: this program has no pip
dependencies at all, so installing it in Termux never involves compiling a
wheel against a missing header at eleven at night.

The important part is the **grammar**. Small models improvise; ask a 4B for
JSON a hundred times and you will get several replies wrapped in a friendly
sentence. llama.cpp can constrain generation token by token against a GBNF
grammar, which makes malformed output impossible rather than unlikely. That
single flag takes tool-calling from roughly 70% reliable to essentially
perfect, and it is why ``grammar/action.gbnf`` is worth keeping in step with
the action registry.

Extending
---------
:func:`build_prompt` is where you teach the model new tricks. Keep it short --
every token in the system prompt is paid for on every reasoned request, and
Greek costs two to three times more tokens per word than English.
"""

from __future__ import annotations

import json
import logging
import time
import urllib.error
import urllib.request
from dataclasses import dataclass
from pathlib import Path

__all__ = ["Decision", "BrainError", "think", "build_prompt", "load_grammar",
           "available"]

log = logging.getLogger(__name__)


class BrainError(Exception):
    """The model was unreachable or unintelligible."""


@dataclass
class Decision:
    """What the model decided to do.

    Attributes:
        action: An action name from the registry, or ``"answer"`` when it just
            wants to say something, or ``"unclear"`` when it could not tell.
        args: Arguments for the action.
        seconds: How long the round trip took.
    """

    action: str
    args: dict
    seconds: float = 0.0


LANGUAGE_NAMES = {"el": "Greek", "en": "English"}


def build_prompt(text: str, language: str, actions: list[str]) -> str:
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

    return (
        "<|im_start|>system\n"
        "You convert a phone user's spoken request into exactly one action.\n"
        f"Available actions: {catalogue}\n"
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


def think(text: str, language: str, actions: list[str], config) -> Decision:
    """Ask the model what to do.

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
        "prompt": build_prompt(text, language, actions),
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
            f"Android suspend it? (termux-wake-lock)"
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
