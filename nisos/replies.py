"""What the assistant says back, in both languages.

This module is where the fast path gets its quality. Routed actions never ask
the model for words -- they look up a string *you* wrote. Perfect Greek,
perfect English, no risk of a 4B model producing something almost-right. Only
the reasoned path, where the model composes an answer itself, can produce
awkward phrasing, and that path is the minority of what you say.

Extending
---------
Every action in :mod:`nisos.actions` needs an entry here with an ``en`` and an
``el`` string. Placeholders in ``{braces}`` are filled from whatever dict the
action returned, so an action that returns ``{"percent": 78}`` can be phrased
as ``"{percent} percent left."``

A missing entry is not fatal -- :func:`say` falls back to the action name so
you notice in testing rather than in a silent failure -- but do add one.
"""

from __future__ import annotations

import logging

__all__ = ["SAY", "say", "missing_replies"]

log = logging.getLogger(__name__)


SAY: dict[str, dict[str, str]] = {
    "torch.on": {
        "en": "Torch on.",
        "el": "Άναψα τον φακό.",
    },
    "torch.off": {
        "en": "Torch off.",
        "el": "Έσβησα τον φακό.",
    },
    "timer.set": {
        "en": "{minutes} minutes, counting.",
        "el": "{minutes} λεπτά, ξεκίνησα.",
    },
    "battery.read": {
        "en": "{percent} percent, {status}.",
        "el": "{percent} τοις εκατό, {status}.",
    },
    "sms.send": {
        "en": "Sent to {to}.",
        "el": "Το έστειλα στον/στην {to}.",
    },
    "whatsapp.send": {
        # Says "ready" rather than "sent" on purpose -- it stops one tap short,
        # and claiming otherwise would be a lie you'd only discover later.
        "en": "WhatsApp is open for {to} — tap send.",
        "el": "Άνοιξα το WhatsApp για {to} — πάτα αποστολή.",
    },
    "memory.remember": {
        "en": "Noted — {key}.",
        "el": "Το θυμάμαι — {key}.",
    },
    "memory.recall": {
        "en": "{value}",
        "el": "{value}",
    },
    "memory.forget": {
        "en": "Forgotten.",
        "el": "Το ξέχασα.",
    },
    "memory.list": {
        "en": "{facts} things and {contacts} numbers.",
        "el": "{facts} πράγματα και {contacts} τηλέφωνα.",
    },
    "clipboard.set": {
        "en": "Copied.",
        "el": "Το αντέγραψα.",
    },
    "dnd.on": {
        "en": "Silenced.",
        "el": "Σε σίγαση.",
    },
    "volume.set": {
        "en": "Volume {level}.",
        "el": "Ένταση {level}.",
    },
    "calendar.next": {
        "en": "{summary}, in {minutes} minutes.",
        "el": "{summary}, σε {minutes} λεπτά.",
    },
    "time.read": {
        "en": "It's {time}.",
        "el": "Η ώρα είναι {time}.",
    },
    # Spoken by the model itself -- the template just passes its text through.
    "answer": {
        "en": "{text}",
        "el": "{text}",
    },
    # Failure modes. Worth phrasing well; you will hear these more than you'd like.
    "unclear": {
        "en": "Didn't catch that.",
        "el": "Δεν το έπιασα.",
    },
    # Said when the phrase needed a model and the network is what failed --
    # which, with the online brain, is now the literal truth.
    "unavailable": {
        "en": "Can't do that offline.",
        "el": "Αυτό δεν γίνεται χωρίς σύνδεση.",
    },
    # The online brain, with no key to use it. Actionable: menu key 'k'.
    "no_key": {
        "en": "There's no API key, so I can only do the quick commands.",
        "el": "Λείπει το κλειδί, οπότε κάνω μόνο τις γρήγορες εντολές.",
    },
    # A successful call that declined to answer. Rare, and worth saying plainly
    # rather than dressing up as a fault -- nothing is broken.
    "refused": {
        "en": "The model wouldn't answer that one.",
        "el": "Το μοντέλο αρνήθηκε να απαντήσει.",
    },
    # Distinct from "unavailable" on purpose. This one is not about the
    # network at all -- the phrase missed the router and llama-server is not
    # running to think about it. Actionable, because there is something you
    # can do: start the model, or say it a way the router knows.
    "no_model": {
        "en": "The model isn't running, so I can only do the quick commands.",
        "el": "Το μοντέλο δεν τρέχει, οπότε κάνω μόνο τις γρήγορες εντολές.",
    },
    "failed": {
        "en": "That didn't work.",
        "el": "Κάτι πήγε στραβά.",
    },
}


def say(action: str, language: str, **fields) -> str:
    """Render the spoken reply for an action in the given language.

    Args:
        action: Action name, e.g. ``"timer.set"``.
        language: ``"el"`` or ``"en"``. Anything unknown falls back to English.
        **fields: Values for the template placeholders, normally the dict the
            action handler returned merged with the args it was called with.

    Returns:
        A finished sentence, ready for text-to-speech.

    Never raises. A missing template or a missing placeholder degrades to
    something speakable and logs a warning, because an assistant that crashes
    while telling you it succeeded is worse than one that phrases it clumsily.

    >>> say("torch.on", "el")
    'Άναψα τον φακό.'
    >>> say("timer.set", "en", minutes=12)
    '12 minutes, counting.'
    """
    templates = SAY.get(action)
    if templates is None:
        log.warning("No reply template for action %r -- add one to SAY", action)
        return action.replace(".", " ")

    template = templates.get(language) or templates.get("en", action)

    try:
        return template.format(**fields)
    except (KeyError, IndexError) as exc:
        log.warning("Reply template %r for %r is missing field %s",
                    template, action, exc)
        # Strip the unfilled placeholders rather than speaking literal braces.
        import re
        return re.sub(r"\s*\{[^}]*\}", "", template).strip() or action


def missing_replies(actions: list[str], languages: tuple[str, ...] = ("en", "el")
                    ) -> list[tuple[str, str]]:
    """List (action, language) pairs that have no reply template.

    Used by the test suite to keep :data:`SAY` in step with the action registry,
    so adding an action without a Greek phrase fails a test rather than
    surfacing months later as an English sentence in a Greek conversation.
    """
    gaps = []
    for action in actions:
        for language in languages:
            if language not in SAY.get(action, {}):
                gaps.append((action, language))
    return gaps
