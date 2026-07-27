"""The keyword router -- the fast path, and the language detector.

Two jobs, one pass:

**Speed.** Roughly 80% of what anyone says to a phone is one of a few dozen
patterns. Matching those with regex takes about five milliseconds, against the
one-and-a-half seconds it costs to wake the language model. The router is the
single biggest reason this feels like an assistant rather than a demo.

**Language.** Greek and English share no characters, so a pattern written
against Greek stems physically cannot fire on English text, and vice versa.
That means you do not detect the language and then route -- you route against
both tables, and whichever one hits tells you the language for free. Zero cost,
zero error rate. (The same trick would fall apart on Spanish and English. It
works here because the alphabets are disjoint.)

Extending
---------
Adding a command is one line per language in :data:`ROUTES`. Patterns are
matched against :func:`nisos.normalise.normalise` output, so write them
lowercase, unaccented, with final sigma as plain sigma.

Match Greek **stems**, never whole words::

    r"\\b(αναψ|ανοιξ)\\w*"     # άναψε / ανάψτε / να ανάψεις / άνοιξε
    r"\\bαναψε\\b"              # only ever matches one of those four

Order matters within a table: put specific patterns above general ones, because
the first hit wins.
"""

from __future__ import annotations

import re
from dataclasses import dataclass, field
from typing import Callable

from .normalise import normalise, parse_number

__all__ = ["Route", "Match", "ROUTES", "route", "compile_routes"]


# --------------------------------------------------------------------------
# Types
# --------------------------------------------------------------------------

# An argument builder receives the normalised transcript and the regex match,
# and returns the kwargs for the action. Most actions take none.
ArgBuilder = Callable[[str, "re.Match[str]"], dict]


def _no_args(text: str, match: "re.Match[str]") -> dict:
    """Argument builder for actions that take none. The common case."""
    return {}


def _minutes(language: str) -> ArgBuilder:
    """Build an argument builder that extracts a minute count in `language`."""

    def build(text: str, match: "re.Match[str]") -> dict:
        return {"minutes": parse_number(text, language)}

    return build


def _level(language: str) -> ArgBuilder:
    """Build an argument builder that extracts a 0-100 level in `language`."""

    def build(text: str, match: "re.Match[str]") -> dict:
        return {"level": parse_number(text, language)}

    return build


@dataclass(frozen=True)
class Route:
    """One pattern in one language, and what to do when it fires.

    Attributes:
        pattern: Regex, matched against normalised text.
        action: Action name from the registry, e.g. ``"torch.on"``. Always
            English -- see the note in :mod:`nisos.actions`.
        args: Callable turning the match into action kwargs.
    """

    pattern: str
    action: str
    args: ArgBuilder = field(default=_no_args)


@dataclass(frozen=True)
class Match:
    """What the router returns on a hit.

    Attributes:
        language: ``"el"`` or ``"en"`` -- inferred from which table matched.
        action: The action name to execute.
        args: Keyword arguments for it.
    """

    language: str
    action: str
    args: dict


# --------------------------------------------------------------------------
# The tables
# --------------------------------------------------------------------------
# Keep the two tables in step: every action that appears in one should appear
# in the other, or the assistant will be cleverer in one language than the
# other and you will never remember which.

ROUTES: dict[str, list[Route]] = {
    "en": [
        Route(r"\btorch (on|off)\b", "torch.on"),  # direction fixed up below
        Route(r"\b(turn |switch )?(on|off) the (torch|light|flashlight)\b", "torch.on"),
        Route(r"\b(set (a |the )?)?timer\b", "timer.set", _minutes("en")),
        Route(r"\bremind me in\b", "timer.set", _minutes("en")),
        # Deliberately just the noun: "battery", "my battery", "battery level"
        # and "how's the battery" all have to work, and enumerating the
        # trailing words is how you end up with a route that only fires half
        # the time.
        Route(r"\b(battery|charge level)\b", "battery.read"),
        Route(r"\btext (\w+)\b", "sms.send",
              lambda t, m: {"to": m.group(1), "body": t[m.end():].strip()}),
        Route(r"\b(copy|clipboard)\b", "clipboard.set",
              lambda t, m: {"text": t[m.end():].strip()}),
        Route(r"\b(silence|silent|do not disturb|dnd)\b", "dnd.on"),
        Route(r"\b(volume|sound)\b", "volume.set", _level("en")),
        Route(r"\b(next |upcoming )?(meeting|appointment|calendar)\b", "calendar.next"),
        Route(r"\bwhat time is it\b|\bthe time\b", "time.read"),
    ],
    "el": [
        # Stems, always. αναψ- covers άναψε / ανάψτε / να ανάψεις.
        Route(r"\b(αναψ|ανοιξ)\w*\b.*\bφακ", "torch.on"),
        Route(r"\b(σβησ|κλεισ)\w*\b.*\bφακ", "torch.off"),
        Route(r"\bφακ\w*\b.*\b(αναψ|ανοιξ)", "torch.on"),
        Route(r"\bφακ\w*\b.*\b(σβησ|κλεισ)", "torch.off"),
        Route(r"\b(χρονομετρ|ταιμερ|αντιστροφ)", "timer.set", _minutes("el")),
        Route(r"\bθυμισε μου σε\b", "timer.set", _minutes("el")),
        Route(r"\bμπαταρι", "battery.read"),
        Route(r"\b(στειλ|γραψ)\w*\b (?:μηνυμα )?(?:στ(?:ον|ην|ο) )?(\w+)", "sms.send",
              lambda t, m: {"to": m.group(2), "body": t[m.end():].strip()}),
        Route(r"\b(αντιγραψ|κοπι)", "clipboard.set",
              lambda t, m: {"text": t[m.end():].strip()}),
        Route(r"\b(σιγαση|ησυχι|μην ενοχλ)", "dnd.on"),
        Route(r"\b(ενταση|ηχ)\w*", "volume.set", _level("el")),
        Route(r"\b(ραντεβου|συναντηση|ημερολογ)", "calendar.next"),
        Route(r"\bτι ωρα ειναι\b|\bη ωρα\b", "time.read"),
    ],
}


# The torch patterns above capture a direction word. Rather than duplicating
# every torch route, the router rewrites the action name when the match tells
# it the command was "off". Kept here so the tables stay one line per phrase.
_DIRECTION_OFF = re.compile(r"\b(off|σβησ|κλεισ)")


def compile_routes(tables: dict[str, list[Route]] | None = None
                   ) -> dict[str, list[tuple[re.Pattern[str], Route]]]:
    """Pre-compile every pattern once, at import time rather than per utterance.

    Args:
        tables: Route tables to compile. Defaults to :data:`ROUTES`.

    Returns:
        The same structure with each pattern replaced by a compiled regex
        paired with its Route.
    """
    source = tables if tables is not None else ROUTES
    return {
        language: [(re.compile(r.pattern), r) for r in routes]
        for language, routes in source.items()
    }


_COMPILED = compile_routes()


def route(raw_text: str, tables: dict | None = None) -> Match | None:
    """Try every pattern in both languages and return the first hit.

    Args:
        raw_text: The transcript straight from the recogniser. Normalisation
            happens here so callers never have to remember to do it.
        tables: Optional pre-compiled tables, for tests. Defaults to the
            module-level compiled copy of :data:`ROUTES`.

    Returns:
        A :class:`Match` on a hit -- carrying the language, which is the whole
        point -- or None, meaning wake the model.

    >>> route("άναψε τον φακό").action
    'torch.on'
    >>> route("άναψε τον φακό").language
    'el'
    >>> route("torch off").action
    'torch.off'
    >>> route("ποια είναι η πρωτεύουσα της Ιαπωνίας") is None
    True
    """
    text = normalise(raw_text)
    compiled = tables if tables is not None else _COMPILED

    for language, routes in compiled.items():
        for pattern, entry in routes:
            match = pattern.search(text)
            if not match:
                continue

            action = entry.action
            # Single-pattern torch routes carry their direction in the match.
            if action == "torch.on" and _DIRECTION_OFF.search(match.group(0)):
                action = "torch.off"

            return Match(language=language, action=action,
                         args=entry.args(text, match))
    return None
