"""The action catalogue -- what the assistant can actually do.

A brilliant model with three actions is a chatbot. A mediocre model with sixty
actions is an assistant. This is the file worth spending your time on.

Two execution routes:

* **termux-api** -- direct shell commands for things Android exposes to apps:
  torch, SMS, clipboard, battery, volume, notifications, sensors.
* **Tasker** -- everything else. Termux fires an intent, Tasker does the work.
  This means every Tasker task you have ever written is already callable by
  voice; you just need a route and a reply for it.

Action names are **always English**, in both languages. You speak Greek, the
system thinks in ``torch.on``, and only the spoken reply comes back Greek.
This is deliberate: asking a 4B model to *classify* Greek is a far easier job
than asking it to *write* Greek, and the GBNF grammar makes English action
names the only legal model output. Never translate the schema.

Extending
---------
Adding an action is three small edits, in three obvious places:

1. Here -- a function with an ``@action("name")`` decorator.
2. :mod:`nisos.router` -- one pattern per language.
3. :mod:`nisos.replies` -- an ``en`` and an ``el`` phrase.

Then add the name to ``grammar/action.gbnf`` if you want the model to be able
to choose it too. The test suite checks 1-3 stay in step.

A handler receives ``(args, ctx)`` and returns a dict of fields for the reply
template. Raise :class:`ActionError` for an expected failure -- a missing
contact, a Tasker task that isn't installed -- and the assistant says so
politely instead of falling over.
"""

from __future__ import annotations

import json
import logging
import shutil
import subprocess
import time
from dataclasses import dataclass
from typing import Callable

__all__ = [
    "action", "REGISTRY", "ActionError", "ExecutionContext",
    "execute", "action_names",
]

log = logging.getLogger(__name__)


class ActionError(Exception):
    """An action failed in a way the user should hear about, not a crash."""


Handler = Callable[[dict, "ExecutionContext"], dict]

#: Action name -> handler. Populated by the :func:`action` decorator at import.
REGISTRY: dict[str, Handler] = {}


def action(name: str) -> Callable[[Handler], Handler]:
    """Register a function as the handler for an action name.

    Args:
        name: The action name, e.g. ``"torch.on"``. Must match what the router
            emits and what ``action.gbnf`` allows.

    Raises:
        ValueError: If the name is already registered. Catching duplicates at
            import time is much kinder than debugging why one of two handlers
            silently won.

    Example::

        @action("torch.on")
        def torch_on(args, ctx):
            '''Turn the camera flash on.'''
            ctx.termux("termux-torch", "on")
            return {}
    """

    def register(handler: Handler) -> Handler:
        if name in REGISTRY:
            raise ValueError(f"Action {name!r} is already registered")
        REGISTRY[name] = handler
        return handler

    return register


def action_names() -> list[str]:
    """Every registered action name, sorted. Used by the tests and the CLI."""
    return sorted(REGISTRY)


# --------------------------------------------------------------------------
# Execution context
# --------------------------------------------------------------------------

@dataclass
class ExecutionContext:
    """Everything a handler needs to reach the outside world.

    Passing this in rather than calling subprocess directly is what makes the
    action layer testable off-phone: the test suite substitutes a context that
    records commands instead of running them.

    Attributes:
        dry_run: Log commands instead of running them.
        tasker_task: Name of the Tasker task that dispatches actions.
        timeout: Seconds to allow any single shell command.
        contacts: Alias table mapping mangled recogniser output back to real
            names. A Greek-locked recogniser renders "Anna" phonetically, so
            «στείλε στην Άννα» needs «αννα» -> "Anna" to find the contact.
    """

    dry_run: bool = False
    tasker_task: str = "NisosAction"
    timeout: float = 10.0
    contacts: dict[str, str] = None  # type: ignore[assignment]

    def __post_init__(self) -> None:
        if self.contacts is None:
            self.contacts = {}

    def termux(self, *command: str) -> str:
        """Run a termux-api command and return its stdout.

        Args:
            *command: The command and its arguments, e.g. ``("termux-torch", "on")``.

        Returns:
            Captured stdout, stripped. Empty string in dry-run mode.

        Raises:
            ActionError: If the binary is missing (Termux:API not installed) or
                the command fails or times out.
        """
        printable = " ".join(command)
        if self.dry_run:
            log.info("[dry-run] %s", printable)
            return ""

        if shutil.which(command[0]) is None:
            raise ActionError(
                f"{command[0]} not found -- is the Termux:API app installed "
                f"alongside the termux-api package?"
            )

        try:
            done = subprocess.run(command, capture_output=True, text=True,
                                  timeout=self.timeout, check=True)
        except subprocess.TimeoutExpired as exc:
            raise ActionError(f"{command[0]} timed out") from exc
        except subprocess.CalledProcessError as exc:
            raise ActionError(f"{command[0]} failed: {exc.stderr.strip()}") from exc

        return done.stdout.strip()

    def tasker(self, task_action: str, payload: dict | None = None) -> str:
        """Ask Tasker to run something, via an Android broadcast intent.

        Tasker needs "Allow External Access" enabled in its preferences, and a
        task named :attr:`tasker_task` that switches on ``%par1``.

        Args:
            task_action: The action name, passed to Tasker as ``%par1``.
            payload: Arguments, passed as JSON in ``%par2``.

        Returns:
            Empty string -- broadcasts are fire-and-forget. If you need a value
            back from Tasker, have the task write it to a file and read that.
        """
        body = json.dumps(payload or {}, ensure_ascii=False)
        return self.termux(
            "am", "broadcast", "--user", "0",
            "-a", "net.dinglisch.android.tasker.ACTION_TASK",
            "-e", "task_name", self.tasker_task,
            "-e", "par1", task_action,
            "-e", "par2", body,
        )

    def resolve_contact(self, spoken: str) -> str:
        """Map a spoken name back to a real contact name.

        Code-switching is this program's weakest point. «στείλε μήνυμα στην
        Anna» spoken to a Greek-locked recogniser comes back as «αννα», which
        matches no contact. The alias table in your config fixes the handful of
        people you actually text.

        Args:
            spoken: The name as the recogniser heard it, already normalised.

        Returns:
            The mapped name, or the input unchanged if there is no alias.
        """
        return self.contacts.get(spoken.lower(), spoken)


# --------------------------------------------------------------------------
# The actions themselves
# --------------------------------------------------------------------------

@action("torch.on")
def torch_on(args: dict, ctx: ExecutionContext) -> dict:
    """Turn the camera flash on."""
    ctx.termux("termux-torch", "on")
    return {}


@action("torch.off")
def torch_off(args: dict, ctx: ExecutionContext) -> dict:
    """Turn the camera flash off."""
    ctx.termux("termux-torch", "off")
    return {}


@action("timer.set")
def timer_set(args: dict, ctx: ExecutionContext) -> dict:
    """Start a countdown timer via Tasker.

    Args:
        args: Expects ``minutes``. None means the recogniser heard a timer
            request but no number -- usually a Greek number word missing from
            :data:`nisos.normalise.NUMBER_WORDS`.
    """
    minutes = args.get("minutes")
    if not minutes:
        raise ActionError("no duration heard")
    ctx.tasker("timer.set", {"minutes": int(minutes)})
    return {"minutes": int(minutes)}


@action("battery.read")
def battery_read(args: dict, ctx: ExecutionContext) -> dict:
    """Report battery percentage and charging state."""
    raw = ctx.termux("termux-battery-status")
    if not raw:
        return {"percent": 0, "status": "unknown"}
    data = json.loads(raw)
    return {
        "percent": round(data.get("percentage", 0)),
        "status": str(data.get("status", "")).lower(),
    }


@action("sms.send")
def sms_send(args: dict, ctx: ExecutionContext) -> dict:
    """Send a text message.

    Args:
        args: Expects ``to`` (a contact name, resolved through the alias table)
            and ``body``.
    """
    to = args.get("to", "").strip()
    body = args.get("body", "").strip()
    if not to:
        raise ActionError("no recipient heard")
    if not body:
        raise ActionError("no message heard")

    recipient = ctx.resolve_contact(to)
    ctx.termux("termux-sms-send", "-n", recipient, body)
    return {"to": recipient}


@action("clipboard.set")
def clipboard_set(args: dict, ctx: ExecutionContext) -> dict:
    """Put text on the clipboard."""
    text = args.get("text", "").strip()
    if not text:
        raise ActionError("nothing to copy")
    ctx.termux("termux-clipboard-set", text)
    return {}


@action("dnd.on")
def dnd_on(args: dict, ctx: ExecutionContext) -> dict:
    """Switch on Do Not Disturb, optionally until a given time."""
    ctx.tasker("dnd.on", {"until": args.get("until")})
    return {}


@action("volume.set")
def volume_set(args: dict, ctx: ExecutionContext) -> dict:
    """Set media volume, as a 0-100 level."""
    level = args.get("level")
    if level is None:
        raise ActionError("no level heard")
    level = max(0, min(100, int(level)))
    # termux-volume works in stream steps, not percent; Tasker handles percent.
    ctx.tasker("volume.set", {"level": level})
    return {"level": level}


@action("calendar.next")
def calendar_next(args: dict, ctx: ExecutionContext) -> dict:
    """Report the next calendar entry.

    Tasker writes the answer to a file because broadcasts cannot return values.
    The companion task should write JSON like
    ``{"summary": "Standup", "minutes": 25}``.
    """
    ctx.tasker("calendar.next")
    path = "/data/data/com.termux/files/home/.nisos/calendar.json"
    for _ in range(20):  # Tasker needs a moment to write it
        try:
            with open(path, encoding="utf-8") as handle:
                data = json.load(handle)
            return {"summary": data.get("summary", "nothing"),
                    "minutes": data.get("minutes", 0)}
        except (FileNotFoundError, json.JSONDecodeError):
            time.sleep(0.05)
    raise ActionError("calendar didn't answer")


@action("time.read")
def time_read(args: dict, ctx: ExecutionContext) -> dict:
    """Report the current time. The one action that needs nothing at all."""
    return {"time": time.strftime("%H:%M")}


@action("answer")
def answer(args: dict, ctx: ExecutionContext) -> dict:
    """Speak text the model composed itself. No side effects."""
    return {"text": args.get("text", "")}


@action("unclear")
def unclear(args: dict, ctx: ExecutionContext) -> dict:
    """The model could not work out what was wanted."""
    return {}


# --------------------------------------------------------------------------
# Dispatch
# --------------------------------------------------------------------------

def execute(name: str, args: dict, ctx: ExecutionContext) -> tuple[str, dict]:
    """Run an action and return the name and fields to speak.

    Args:
        name: Action name from the router or the model.
        args: Its arguments.
        ctx: Where to send the side effects.

    Returns:
        ``(reply_key, fields)`` -- the key to look up in
        :data:`nisos.replies.SAY`, and the values to fill it with. On failure
        the key becomes ``"failed"`` or ``"unavailable"``, so the caller does
        not need to handle errors separately; it always has something to say.
    """
    handler = REGISTRY.get(name)
    if handler is None:
        log.error("Unknown action %r -- the model invented it, or the grammar "
                  "and the registry have drifted apart", name)
        return "unclear", {}

    started = time.perf_counter()
    try:
        fields = handler(args, ctx)
    except ActionError as exc:
        log.warning("Action %s refused: %s", name, exc)
        return "failed", {}
    except Exception:  # noqa: BLE001 -- a crash here must not kill the loop
        log.exception("Action %s crashed", name)
        return "failed", {}

    log.info("%s ok (%.0f ms)", name, (time.perf_counter() - started) * 1000)
    # Args first so handler output wins on any key collision.
    return name, {**args, **fields}
