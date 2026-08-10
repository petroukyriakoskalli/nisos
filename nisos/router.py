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
the first hit wins. ``calendar.add`` sits above ``calendar.next`` for exactly
that reason -- «κλείσε ραντεβού αύριο στις πέντε» would otherwise be answered
by reading you your next meeting.

More than one thing at a time
-----------------------------
«άναψε τον φακό **και** βάλε χρονόμετρο δώδεκα λεπτά» is two commands, and for
a long time this returned the first and dropped the second in silence. It is
now split on conjunctions and each piece is routed on its own -- but only
accepted as a split **when every piece routes**. That one rule is what keeps
«στείλε στη Μαρία ότι άργησα και θα φάμε αργότερα» in one piece: the tail is
not a command, so the whole sentence falls back to being one message with the
word «και» inside it, exactly as before.
"""

from __future__ import annotations

import re
from dataclasses import dataclass, field
from typing import Callable

from . import when
from .actions import Step
from .normalise import Normalised, normalise, parse_number

__all__ = ["Route", "Match", "ROUTES", "route", "compile_routes", "split",
           "MAX_STEPS"]


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


# Greek names almost always arrive with an article attached -- «θυμήσου ότι
# **η** Μαριλένα είναι…» -- and storing "η μαριλενα" means a later lookup for
# "μαριλενα" finds nothing. Strip a single leading article, in both languages.
_ARTICLE = re.compile(
    r"^(?:ο|η|το|οι|τα|τον|την|τη|του|τησ|των|ενασ|μια|ενα|the|a|an)\s+")


def strip_article(text: str) -> str:
    """Drop one leading article from a memory key.

    >>> strip_article("η μαριλενα")
    'μαριλενα'
    >>> strip_article("marilena")
    'marilena'
    """
    return _ARTICLE.sub("", text.strip(), count=1).strip()


def _key(group: int) -> ArgBuilder:
    """Argument builder for memory routes that take a single key."""
    def build(text: str, match: "re.Match[str]") -> dict:
        return {"key": strip_article(match.group(group))}
    return build


def _level(language: str) -> ArgBuilder:
    """Build an argument builder that extracts a 0-100 level in `language`."""

    def build(text: str, match: "re.Match[str]") -> dict:
        return {"level": parse_number(text, language)}

    return build


# --------------------------------------------------------------------------
# Appointments
# --------------------------------------------------------------------------
# Making an appointment is the one command where the words the user did *not*
# say a command with are the payload: everything that is neither the
# instruction nor the time is the title. So rather than reading the title out
# of a capture group -- which would need the words in a fixed order, and «βάλε
# ραντεβού με τον γιατρό αύριο» and "put the dentist in my calendar tomorrow"
# do not agree on one -- the words are subtracted.

#: Removed wherever they appear: the verb that asked for an appointment, and
#: the noun for the thing it goes in. Note «ραντεβού» and "meeting" are *not*
#: here -- «ραντεβού με τον γιατρό» is a perfectly good title.
_CHROME = {
    "en": re.compile(r"^(put|add|schedule|book|create|make|new|calendar|diary"
                     r"|agenda|please)$"),
    "el": re.compile(r"^(βαλ\w*|βαζ\w*|προσθεσ\w*|προσθετ\w*|γραψ\w*|κλεισ\w*"
                     r"|οριζ\w*|ορισ\w*|ημερολογ\w*|ατζεντα|παρακαλω)$"),
}

#: Trimmed from the *ends* of the title only. In the middle they are content:
#: dropping «με τον» everywhere would turn «ραντεβού με τον γιατρό» into
#: «ραντεβού γιατρό».
_EDGE = {
    "en": re.compile(r"^(in|into|to|on|at|for|with|of|my|the|a|an"
                     r"|it|this|that)$"),
    "el": re.compile(r"^(στο|στη|στην|στον|στουσ|σε|μου|μασ|το|τη|την|τον|τα"
                     r"|οι|ο|η|ενα|μια|ενασ|αυτο)$"),
}

#: When every word turns out to be chrome or a time. «βάλε ραντεβού αύριο στις
#: πέντε» is a complete instruction with no title in it, and an appointment
#: called "" is worse than one called what it obviously is.
_UNTITLED = {"en": "Appointment", "el": "Ραντεβού"}

_TRIM = " ,.;:!?«»\"'"


def _appointment(language: str) -> ArgBuilder:
    """Build an argument builder for ``calendar.add`` in `language`.

    Produces ``summary``, ``start`` (local ``YYYY-MM-DDTHH:MM``) and
    ``minutes``. ``start`` is left out when the sentence contained no time at
    all, which the action turns into a spoken failure -- guessing a time for
    something that goes in a diary is worse than admitting you missed it.
    """

    def build(text: str, match: "re.Match[str]") -> dict:
        words = text.split()
        chrome = _CHROME[language]
        spent = {i for i, word in enumerate(words)
                 if chrome.match(word.strip(_TRIM))}

        moment = when.parse(words, language)
        if moment:
            spent |= moment.words

        args: dict = {
            "summary": _title(text, [i for i in range(len(words))
                                     if i not in spent], language),
            "minutes": moment.minutes if moment else when.DEFAULT_MINUTES,
        }
        if moment:
            args["start"] = moment.iso()
        return args

    return build


def _title(text: str, indices: list[int], language: str) -> str:
    """Assemble the appointment title from the words nothing else claimed.

    Args:
        text: The utterance. A :class:`~nisos.normalise.Normalised` here is
            what gets the accents and capitals back; a plain string still
            works and just reads flatter.
        indices: Word positions left over, in order.
        language: Which edge table to trim with.
    """
    words = text.split()
    edge = _EDGE[language]
    while indices and edge.match(words[indices[0]].strip(_TRIM)):
        indices = indices[1:]
    while indices and edge.match(words[indices[-1]].strip(_TRIM)):
        indices = indices[:-1]

    if not indices:
        return _UNTITLED[language]

    if isinstance(text, Normalised):
        title = text.original(indices)
    else:
        title = " ".join(words[i] for i in indices)
    return title.strip(_TRIM) or _UNTITLED[language]


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
        action: The first action to execute. Kept as a plain field, rather
            than a property over ``steps``, because a one-action turn is still
            the overwhelmingly common case and reading ``match.action`` should
            not require knowing that a turn can be a list.
        args: Keyword arguments for it.
        steps: Every action, in the order they were said. Defaults to a single
            step built from ``action`` and ``args``, so a Match constructed
            the old way behaves exactly as it always did.
    """

    language: str
    action: str
    args: dict
    steps: tuple[Step, ...] = ()

    def __post_init__(self) -> None:
        if not self.steps:
            object.__setattr__(self, "steps", (Step(self.action, self.args),))


# --------------------------------------------------------------------------
# The tables
# --------------------------------------------------------------------------
# Keep the two tables in step: every action that appears in one should appear
# in the other, or the assistant will be cleverer in one language than the
# other and you will never remember which.

ROUTES: dict[str, list[Route]] = {
    "en": [
        Route(r"\b(torch|flashlight) (on|off)\b", "torch.on"),  # direction fixed below
        Route(r"\b(turn |switch )?(on|off) (the )?(torch|light|flashlight)\b", "torch.on"),
        # "open the light" is how a Greek speaker says this in English, and it
        # is exactly what got typed the first time this ran on a phone. The
        # Greek table has had ανοιξ-/κλεισ- from the start and the English
        # table never mirrored them. Article optional throughout -- nobody
        # types "the" into a voice box.
        Route(r"\b(open|close) (the )?(torch|light|flashlight)\b", "torch.on"),
        Route(r"\b(set (a |the )?)?timer\b", "timer.set", _minutes("en")),
        Route(r"\bremind me in\b", "timer.set", _minutes("en")),
        # Deliberately just the noun: "battery", "my battery", "battery level"
        # and "how's the battery" all have to work, and enumerating the
        # trailing words is how you end up with a route that only fires half
        # the time.
        Route(r"\b(battery|charge level)\b", "battery.read"),
        # Above calendar.next, and above the message routes: "book a meeting"
        # is not a request to be told about your next one, and "put X in my
        # diary" is not a text message.
        Route(r"\b(put|add|schedule|book|create|make)\b.*\b(calendar|diary|agenda)\b",
              "calendar.add", _appointment("en")),
        Route(r"\b(schedule|book)\b.*\b(meeting|appointment)\b",
              "calendar.add", _appointment("en")),
        Route(r"\btext (\w+)\b", "sms.send",
              lambda t, m: {"to": m.group(1), "body": t[m.end():].strip()}),
        Route(r"\b(copy|clipboard)\b", "clipboard.set",
              lambda t, m: {"text": t[m.end():].strip()}),
        Route(r"\b(silence|silent|do not disturb|dnd)\b", "dnd.on"),
        Route(r"\b(volume|sound)\b", "volume.set", _level("en")),
        Route(r"\b(next |upcoming )?(meeting|appointment|calendar)\b", "calendar.next"),
        Route(r"\bwhat time is it\b|\bthe time\b", "time.read"),
        # Memory. Specific shapes only -- a broad "what is X" would swallow
        # every general-knowledge question and answer "nothing stored".
        Route(r"\bremember (?:that )?(.+?) (?:is|are|'s) (.+)", "memory.remember",
              lambda t, m: {"key": strip_article(m.group(1)), "value": m.group(2)}),
        Route(r"\bforget (?:about )?(.+)", "memory.forget",
              _key(1)),
        Route(r"\bwhat do you remember\b|\bhow much do you remember\b", "memory.list"),
        Route(r"\bwhat(?:'s| is) (.+?)(?:'s)? (?:number|phone)\b", "memory.recall",
              _key(1)),
        Route(r"\bwhat do you know about (.+)", "memory.recall",
              _key(1)),
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
        # Above both the message routes and calendar.next. «γράψε στο
        # ημερολόγιο» matches the sms pattern otherwise, and sends a text to
        # somebody called «ημερολόγιο».
        Route(r"\b(βαλ|βαζ|προσθεσ|προσθετ|γραψ)\w*\b.*\bημερολογ",
              "calendar.add", _appointment("el")),
        Route(r"\b(κλεισ|βαλ|βαζ|οριζ|ορισ)\w*\b.*\b(ραντεβου|συναντηση)",
              "calendar.add", _appointment("el")),
        # The article list has to be generous. Spoken Greek drops the final nu
        # constantly -- «στη Μαριλένα» is at least as common as «στην» -- and
        # missing one means the ARTICLE gets captured as the recipient's name.
        # Longest alternatives first, or «στο» shadows «στον».
        Route(r"\b(στειλ|γραψ)\w*\b\s+(?:μηνυμα\s+)?(?:στ(?:ον|ην|ουσ|ισ|η|ο|α)\s+)?(\w+)",
              "sms.send",
              lambda t, m: {"to": m.group(2), "body": t[m.end():].strip()}),
        Route(r"\b(αντιγραψ|κοπι)", "clipboard.set",
              lambda t, m: {"text": t[m.end():].strip()}),
        Route(r"\b(σιγαση|ησυχι|μην ενοχλ)", "dnd.on"),
        Route(r"\b(ενταση|ηχ)\w*", "volume.set", _level("el")),
        Route(r"\b(ραντεβου|συναντηση|ημερολογ)", "calendar.next"),
        Route(r"\bτι ωρα ειναι\b|\bη ωρα\b", "time.read"),
        # θυμησου / θυμηθειτε / να θυμασαι all share the θυμ- stem, but so does
        # «τι θυμασαι», so the list route has to come first.
        Route(r"\bτι θυμασαι\b|\bποσα θυμασαι\b", "memory.list"),
        Route(r"\bθυμ\w*\s+(?:οτι\s+)?(.+?)\s+ειναι\s+(.+)", "memory.remember",
              lambda t, m: {"key": strip_article(m.group(1)), "value": m.group(2)}),
        Route(r"\b(?:ξεχνα|ξεχασε)\s+(.+)", "memory.forget",
              _key(1)),
        Route(r"\bποιο ειναι το (?:τηλεφωνο|νουμερο)\s+(?:τη[σ]?|του|των)?\s*(.+)",
              "memory.recall", _key(1)),
        Route(r"\bτι ξερεισ για\s+(.+)", "memory.recall",
              _key(1)),
    ],
}


# Saying "on WhatsApp" anywhere in a message command switches the channel.
# Handled as a rewrite rather than duplicate routes, exactly like the torch
# direction above -- otherwise every phrasing needs writing twice per language.
# Greek recognisers render the brand phonetically about as often as they get it
# right, hence the variants.
_WHATSAPP = re.compile(r"whats\s?app|ουατσαπ|βοτσαπ|γουατσαπ")


# The torch patterns above capture a direction word. Rather than duplicating
# every torch route, the router rewrites the action name when the match tells
# it the command was "off". Kept here so the tables stay one line per phrase.
# Only consulted for torch.on, so "close" here cannot affect any other action.
_DIRECTION_OFF = re.compile(r"\b(off|close|σβησ|κλεισ)")


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


#: Words that end one command and start the next. Both languages in one set,
#: because the alphabets are disjoint and a Greek word can never be mistaken
#: for an English one.
CONJUNCTIONS = frozenset({
    "and", "then", "also", "plus",
    "και", "κι", "μετα", "επειτα", "υστερα", "επισησ",
})

#: The most actions one utterance may carry. Beyond this it is far likelier to
#: be a sentence with several «και»s in it than a person issuing five orders in
#: one breath, so a longer split is not accepted at all and the whole thing is
#: routed as one -- which is what used to happen to everything.
MAX_STEPS = 4

_SPLIT_TRIM = " ,.;:!?«»\"'"


def split(text: str) -> list[Normalised]:
    """Cut an utterance into the separate commands it contains.

    Splits on the conjunctions above, and after any word ending in a comma.
    The pieces keep their own slice of the *raw* text, so a title or a message
    body taken out of one still has its accents and capitals.

    Args:
        text: Normalised text, ideally a :class:`~nisos.normalise.Normalised`.

    Returns:
        One entry per piece. A single-element list means there was nothing to
        split, and :func:`route` then works on the original text rather than a
        rebuilt copy of it -- so the common path is byte-for-byte unchanged.

    >>> [str(p) for p in split("αναψε τον φακο και βαλε χρονομετρο")]
    ['αναψε τον φακο', 'βαλε χρονομετρο']
    >>> [str(p) for p in split("torch on")]
    ['torch on']
    """
    words = text.split()
    raw_words = (text.raw_words if isinstance(text, Normalised)
                 else words)

    pieces: list[list[int]] = []
    current: list[int] = []
    for index, word in enumerate(words):
        if word.strip(_SPLIT_TRIM) in CONJUNCTIONS:
            if current:
                pieces.append(current)
                current = []
            continue
        current.append(index)
        if word.endswith(","):
            pieces.append(current)
            current = []
    if current:
        pieces.append(current)

    return [Normalised(" ".join(words[i].strip(",") for i in piece),
                       " ".join(raw_words[i].strip(",") for i in piece))
            for piece in pieces]


def route(raw_text: str, tables: dict | None = None) -> Match | None:
    """Work out what was asked for, in one language, possibly several times.

    Args:
        raw_text: The transcript straight from the recogniser. Normalisation
            happens here so callers never have to remember to do it.
        tables: Optional pre-compiled tables, for tests. Defaults to the
            module-level compiled copy of :data:`ROUTES`.

    Returns:
        A :class:`Match` on a hit -- carrying the language, which is the whole
        point, and one or more steps -- or None, meaning wake the model.

    A multi-command split is only taken when **every** piece routes. That is
    the whole safety argument: the pieces of a sentence that merely contains
    the word «και» do not all route, so it stays in one piece and behaves
    exactly as it did before this existed.

    >>> route("άναψε τον φακό").action
    'torch.on'
    >>> route("άναψε τον φακό").language
    'el'
    >>> route("torch off").action
    'torch.off'
    >>> len(route("άναψε τον φακό και βάλε χρονόμετρο 12 λεπτά").steps)
    2
    >>> route("ποια είναι η πρωτεύουσα της Ιαπωνίας") is None
    True
    """
    text = normalise(raw_text)
    compiled = tables if tables is not None else _COMPILED

    pieces = split(text)
    if 1 < len(pieces) <= MAX_STEPS:
        hits = [_route_one(piece, compiled) for piece in pieces]
        if all(hits):
            steps = tuple(step for hit in hits for step in hit.steps)
            return Match(language=hits[0].language, action=steps[0].action,
                         args=steps[0].args, steps=steps)

    return _route_one(text, compiled)


def _route_one(text: str, compiled: dict) -> Match | None:
    """Try every pattern in both languages against one command.

    Whichever table hits tells you the language for free -- see the note at
    the top of this module. Returns a single-step :class:`Match`, or None.
    """
    for language, routes in compiled.items():
        for pattern, entry in routes:
            match = pattern.search(text)
            if not match:
                continue

            action = entry.action
            # Single-pattern torch routes carry their direction in the match.
            if action == "torch.on" and _DIRECTION_OFF.search(match.group(0)):
                action = "torch.off"

            args = entry.args(text, match)

            # Messaging defaults to SMS, because that sends with no taps at
            # all. Saying "on WhatsApp" switches channel; the brand name is
            # then stripped out so it doesn't end up inside the message.
            if action == "sms.send" and _WHATSAPP.search(text):
                action = "whatsapp.send"
                if args.get("body"):
                    args["body"] = _WHATSAPP.sub("", args["body"])
                    args["body"] = re.sub(r"\s+(on|στο|με)\s*$", "", args["body"].strip())
                    args["body"] = re.sub(r"\s{2,}", " ", args["body"]).strip(" ,")

            return Match(language=language, action=action, args=args)
    return None
