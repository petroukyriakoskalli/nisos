"""The online brain -- Anthropic's Claude API, called when the router misses.

This is the counterpart to :mod:`nisos.brain`'s llama-server path. Same job,
same :class:`~nisos.brain.Decision` out, opposite trade-off: nothing to install
and a far better model, in exchange for needing a network and sending the
transcript off the phone. Which one runs is decided by ``brain.backend`` --
see :func:`nisos.brain.backend_for`.

Raw HTTP, not the ``anthropic`` SDK, for the same reason the rest of this
program has no dependencies: the SDK pulls in pydantic, whose core is Rust, and
Termux has no prebuilt wheel for it. Installing this on a phone should never
turn into a cross-compile. The wire format below is stable and versioned
(``anthropic-version``), so the cost of not using the SDK is a few dozen lines.

The important part -- the online mirror of the GBNF grammar
--------------------------------------------------------
llama.cpp is forced into valid JSON by a grammar. The API's equivalent is a
**tool with a forced ``tool_choice``**: declare one tool whose input schema is
the action object, then require it. The model cannot reply with prose, cannot
wrap the JSON in a friendly sentence, and cannot invent a verb outside the
enum. ``block["input"]`` arrives already parsed, so unlike the llama path there
is no JSON to salvage.

Extending
---------
:func:`build_system` is where you teach the model new tricks; it is generated
from the action registry, so a new action shows up in the enum automatically.
Keep it short -- every token is paid on every reasoned turn, and Greek costs
two to three times more tokens per word than English.
"""

from __future__ import annotations

import json
import logging
import os
import time
import urllib.error
import urllib.request
from pathlib import Path

from .brain import BrainError, Decision

__all__ = ["ENDPOINT", "TOOL_NAME", "load_key", "store_key", "available",
           "reachable", "think", "build_system", "build_user"]

log = logging.getLogger(__name__)


#: Messages API. Versioned by header, so this URL does not move.
ENDPOINT = "https://api.anthropic.com/v1/messages"

#: Sent on every request. Not optional, and not the model version.
API_VERSION = "2023-06-01"

#: The single tool the model is forced to call. The name is arbitrary but it
#: has to match between the declaration and ``tool_choice``.
TOOL_NAME = "act"

LANGUAGE_NAMES = {"el": "Greek", "en": "English"}


# --------------------------------------------------------------------------
# The key
# --------------------------------------------------------------------------

def load_key(config) -> str | None:
    """Find the API key, or None if there isn't one.

    Two places, in order:

    1. ``ANTHROPIC_API_KEY`` in the environment -- wins, so a shell can
       override the stored key for one run without editing anything.
    2. The file at ``brain.cloud.key_file`` (default ``~/.nisos/anthropic-key``).

    Deliberately **not** config.toml. A key in the config is a secret in a file
    you paste into chats when something breaks, and it would end up in a bug
    report sooner or later. Same reasoning as the web UI token, which moved to
    its own 0600 file for exactly this reason.
    """
    from_env = os.environ.get("ANTHROPIC_API_KEY", "").strip()
    if from_env:
        return from_env

    path = config.expanded("brain.cloud.key_file")
    if not path:
        return None
    try:
        key = Path(path).read_text(encoding="utf-8").strip()
    except OSError:
        return None
    return key or None


def store_key(key: str, path: str) -> Path:
    """Write `key` to `path` with 0600 permissions.

    Args:
        key: The API key. Whitespace is stripped.
        path: Destination, ``~`` allowed.

    Returns:
        The path written.

    Raises:
        ValueError: If `key` is empty.

    The chmod is the point of this function existing rather than being an
    ``echo`` in the shell: on Android every app runs as its own user, so mode
    0600 genuinely keeps other apps out.
    """
    key = key.strip()
    if not key:
        raise ValueError("empty key")

    target = Path(path).expanduser()
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(key + "\n", encoding="utf-8")
    try:
        target.chmod(0o600)
    except OSError:  # pragma: no cover -- some filesystems don't do modes
        log.warning("Could not chmod %s to 0600", target)
    return target


def available(config) -> bool:
    """True if there is a key to try. Says nothing about the network."""
    return load_key(config) is not None


def reachable(config, timeout: float = 6.0) -> bool:
    """True if the key, the network and the model name all check out.

    Used by ``--check``. Deliberately hits ``GET /v1/models/<model>`` rather
    than sending a message: it is free, it validates the model id (a typo there
    is otherwise a 404 in the middle of a turn), and it proves the key works
    without generating a single token.
    """
    key = load_key(config)
    if not key:
        return False

    model = config.get_path("brain.cloud.model", "claude-opus-5")
    request = urllib.request.Request(
        f"https://api.anthropic.com/v1/models/{model}",
        headers={"x-api-key": key, "anthropic-version": API_VERSION},
        method="GET",
    )
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            return response.status == 200
    except urllib.error.HTTPError as exc:
        log.warning("Claude API check failed: HTTP %s -- %s",
                    exc.code, _error_text(exc))
        return False
    except (urllib.error.URLError, OSError) as exc:
        log.warning("Claude API check failed: %s", exc)
        return False


# --------------------------------------------------------------------------
# The prompt
# --------------------------------------------------------------------------

def build_system(language: str, actions: list[str]) -> str:
    """Compose the system prompt.

    Args:
        language: ``"el"`` or ``"en"``. Only affects the language of a spoken
            answer -- action names stay English regardless, which is the whole
            reason a small model coped with this and the reason a large one is
            never confused by it.
        actions: Registered action names, so the prompt and the registry cannot
            drift apart.

    Returns:
        The system prompt, byte-identical for a given language. That matters:
        prompt caching is a prefix match, so keeping this stable means the two
        prefixes (one per language) are reused rather than re-billed. Anything
        that changes per utterance -- the transcript, the memories -- goes in
        the user turn instead, by :func:`build_user`.
    """
    spoken = LANGUAGE_NAMES.get(language, "English")
    catalogue = "\n".join(f"- {name}" for name in actions)

    return (
        "You turn a phone user's spoken request into exactly one action.\n"
        "\n"
        "The request was transcribed from speech, so expect the odd wrong word "
        "and pick the action the person clearly meant.\n"
        "\n"
        f"Actions:\n{catalogue}\n"
        "\n"
        f'Use "answer" with an args.text field to reply conversationally, '
        f"written in {spoken}, in one or two spoken sentences -- it is read "
        "aloud, so no lists, no markdown, no emoji.\n"
        'Use "unclear" if the request makes no sense.\n'
        "Action names are always English and never translated. Only args.text "
        f"is in {spoken}."
    )


def build_user(text: str, memories: dict[str, str] | None = None) -> str:
    """Compose the user turn: what was said, plus anything relevant it was told.

    Memories live here rather than in the system prompt so the system prefix
    stays cacheable -- see :func:`build_system`.
    """
    if not memories:
        return text

    known = "\n".join(f"- {key}: {value}" for key, value in memories.items())
    return f"Things you have been told:\n{known}\n\nRequest: {text}"


def build_tool(actions: list[str]) -> dict:
    """The one tool the model is forced to call.

    ``args`` is deliberately a free-form object, exactly as the GBNF grammar
    leaves it: the arguments differ per action (``minutes``, ``to``,
    ``message``, ``level``, ``text``...) and enumerating every combination here
    would be a second registry to keep in step. The ``action`` enum is the part
    that has to be exact, and it is generated.
    """
    return {
        "name": TOOL_NAME,
        "description": "Perform one action on the user's phone, or answer them.",
        "input_schema": {
            "type": "object",
            "properties": {
                "action": {
                    "type": "string",
                    "enum": list(actions),
                    "description": "Which action to perform.",
                },
                "args": {
                    "type": "object",
                    "description": (
                        "Arguments for the action, e.g. {\"minutes\": 10} for "
                        "timer.set, {\"to\": \"Anna\", \"message\": \"...\"} for "
                        "sms.send, {\"text\": \"...\"} for answer. Empty for "
                        "actions that take none."
                    ),
                },
            },
            "required": ["action"],
        },
    }


# --------------------------------------------------------------------------
# The call
# --------------------------------------------------------------------------

def think(text: str, language: str, actions: list[str], config,
          memories: dict[str, str] | None = None) -> Decision:
    """Ask Claude what to do.

    Args:
        text: The transcript.
        language: Which language to answer in.
        actions: Registered action names.
        config: A :class:`nisos.config.Config`.
        memories: Anything stored that looks relevant to this utterance.

    Returns:
        A :class:`~nisos.brain.Decision` with ``backend="claude"``.

    Raises:
        BrainError: No key, no network, an API error, or a refusal. Each one
            carries a ``reply_key`` so the caller can say something true about
            which of those it was, rather than one apology for all four.
    """
    key = load_key(config)
    if not key:
        raise BrainError(
            "No Anthropic API key. Put one in "
            f"{config.expanded('brain.cloud.key_file')} (menu key 'k'), or set "
            "ANTHROPIC_API_KEY.",
            reply_key="no_key",
        )

    payload = _payload(text, language, actions, config, memories)
    headers = {
        "x-api-key": key,
        "anthropic-version": API_VERSION,
        "content-type": "application/json",
    }
    if payload.get("fallbacks"):
        # Opts into the server-side fallback: if the safety classifiers decline
        # the request, the API re-runs it on another model inside the same call
        # instead of handing back a dead end. Header and parameter form are a
        # matched pair -- "default" goes with -07-01, the array form with
        # -06-01, and mixing them is a 400.
        headers["anthropic-beta"] = "server-side-fallback-2026-07-01"

    request = urllib.request.Request(
        ENDPOINT,
        data=json.dumps(payload).encode("utf-8"),
        headers=headers,
        method="POST",
    )

    started = time.perf_counter()
    try:
        with urllib.request.urlopen(
            request, timeout=config.get_path("brain.cloud.timeout", 30.0)
        ) as response:
            body = json.loads(response.read().decode("utf-8"))
    except urllib.error.HTTPError as exc:
        raise _http_error(exc) from exc
    except (urllib.error.URLError, OSError) as exc:
        # No network, DNS down, aeroplane mode. Distinct from an API error, and
        # the only one of these where the offline model is the right answer.
        raise BrainError(
            f"Could not reach the Claude API: {exc}", reply_key="unavailable"
        ) from exc
    except json.JSONDecodeError as exc:
        raise BrainError(
            f"Claude API returned something that isn't JSON: {exc}"
        ) from exc

    elapsed = time.perf_counter() - started
    return _parse(body, elapsed, config)


def _payload(text: str, language: str, actions: list[str], config,
             memories: dict[str, str] | None) -> dict:
    """Build the request body.

    Three settings here are worth understanding before changing them:

    * **effort** defaults to ``low``. Classifying one spoken sentence is the
      textbook case for it, and this runs while somebody waits with a phone in
      their hand.
    * **thinking** stays on (``adaptive``) even at low effort. Turning it off
      is the tempting saving and the wrong one: with thinking disabled the
      model occasionally writes its tool call as ordinary text, which the
      forced ``tool_choice`` mostly prevents but not provably.
    * **no temperature.** Current models reject sampling parameters outright,
      so the llama path's ``brain.temperature`` deliberately does not apply
      here.
    """
    payload: dict = {
        "model": config.get_path("brain.cloud.model", "claude-opus-5"),
        # Caps thinking *and* reply together, so this is not as generous as it
        # looks. A cap is not a charge -- only real output tokens are billed.
        "max_tokens": config.get_path("brain.cloud.max_tokens", 2048),
        "system": build_system(language, actions),
        "messages": [{"role": "user", "content": build_user(text, memories)}],
        "tools": [build_tool(actions)],
        # The grammar equivalent: it must call the tool, so it cannot ramble.
        "tool_choice": {"type": "tool", "name": TOOL_NAME},
    }

    thinking = str(config.get_path("brain.cloud.thinking", "adaptive") or "")
    if thinking == "adaptive":
        payload["thinking"] = {"type": "adaptive"}
    elif thinking in ("off", "disabled"):
        payload["thinking"] = {"type": "disabled"}

    effort = str(config.get_path("brain.cloud.effort", "low") or "")
    if effort:
        payload["output_config"] = {"effort": effort}

    fallbacks = str(config.get_path("brain.cloud.fallbacks", "default") or "")
    if fallbacks:
        payload["fallbacks"] = fallbacks

    return payload


def _parse(body: dict, elapsed: float, config) -> Decision:
    """Turn the API response into a :class:`~nisos.brain.Decision`.

    Raises:
        BrainError: On a refusal, or when there is no tool call to read --
            which with a forced ``tool_choice`` means the response was cut
            short rather than that the model chose prose.
    """
    stop = body.get("stop_reason")

    if stop == "refusal":
        # A successful HTTP 200 that declined. Checking this before reading
        # content matters: on a refusal there may be no content at all.
        details = body.get("stop_details") or {}
        log.warning("Claude declined the request (category=%s)",
                    details.get("category"))
        raise BrainError("The request was declined by the API's safety filters.",
                         reply_key="refused")

    for block in body.get("content") or []:
        if block.get("type") == "tool_use" and block.get("name") == TOOL_NAME:
            return _decision(block.get("input") or {}, elapsed)

    if stop == "max_tokens":
        raise BrainError(
            "Claude ran out of room before it finished -- raise "
            f"brain.cloud.max_tokens (now "
            f"{config.get_path('brain.cloud.max_tokens', 2048)})."
        )

    log.warning("No tool call in the response: stop_reason=%s", stop)
    raise BrainError(f"Claude returned no action (stop_reason={stop}).")


def _decision(payload: dict, elapsed: float) -> Decision:
    """Validate the tool input. Never raises -- an odd shape becomes unclear.

    ``payload`` is already a parsed dict, so there is nothing to unescape here.
    Never string-match the serialised input: escaping is not guaranteed stable.
    """
    action = payload.get("action")
    if not isinstance(action, str) or not action:
        log.warning("Tool call with no action: %r", payload)
        return Decision("unclear", {}, elapsed, backend="claude")

    args = payload.get("args")
    if not isinstance(args, dict):
        args = {}

    return Decision(action, args, elapsed, backend="claude")


def _http_error(exc: urllib.error.HTTPError) -> BrainError:
    """Turn an HTTP error into something worth hearing and worth reading.

    The response body is always included. Suppressing the output of a call to
    something external has cost this project a diagnosis three separate times;
    an API error with the reason stripped out is the same mistake in a new
    place.
    """
    detail = _error_text(exc)

    if exc.code in (401, 403):
        return BrainError(
            f"The Claude API rejected the key (HTTP {exc.code}): {detail}",
            reply_key="no_key",
        )
    if exc.code == 429:
        return BrainError(
            f"Rate limited by the Claude API: {detail}", reply_key="unavailable"
        )
    if exc.code >= 500:
        return BrainError(
            f"The Claude API is having trouble (HTTP {exc.code}): {detail}",
            reply_key="unavailable",
        )
    if exc.code == 400:
        # A 400 means the body was wrong, and the two parts of it that are
        # opt-in are the likeliest culprits on a phone that cannot be debugged
        # comfortably. Name the escape hatch rather than making them find it.
        return BrainError(
            f"The Claude API rejected the request (HTTP 400): {detail}\n"
            "If that mentions 'fallbacks', a beta header, 'thinking' or "
            "'effort', set brain.cloud.fallbacks = \"\" (or thinking/effort) "
            "in config.toml -- the model in use may not take it."
        )
    return BrainError(f"Claude API error (HTTP {exc.code}): {detail}")


def _error_text(exc: urllib.error.HTTPError) -> str:
    """Best-effort read of an error body, without ever raising in a handler."""
    try:
        raw = exc.read().decode("utf-8", "replace")
    except Exception:  # noqa: BLE001 -- diagnostics must not add a second fault
        return exc.reason or "no detail"

    try:
        parsed = json.loads(raw)
    except json.JSONDecodeError:
        return raw[:400].strip() or (exc.reason or "no detail")

    error = parsed.get("error")
    if isinstance(error, dict):
        return str(error.get("message") or error)
    return raw[:400].strip()
