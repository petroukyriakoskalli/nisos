"""Spoken dates and times, turned into a real ``datetime``.

«αύριο στις πέντε» and "tomorrow at five" both have to become a specific
moment before anything can be written into a calendar. This module is the only
place that knows how, and it is deliberately pure: text in, a datetime out, no
phone, no clock app, no Android. It runs and tests on a laptop.

Three decisions worth knowing before you change anything here
------------------------------------------------------------
**A bare hour between one and seven means the afternoon.** «στις πέντε» is
17:00, not 05:00, because nobody arranges a dentist for five in the morning
and says it that casually. Eight through twelve stay as spoken. Saying «το
πρωί» / "in the morning" forces AM either way, and «το βράδυ» / "pm" forces
PM. One rule, always the same, easy to say out loud when it gets it wrong.

**A time with no day is the next time that time happens.** Today if it is
still ahead, tomorrow if it has passed. A day with no time is
:data:`DEFAULT_HOUR`, because "put the dentist in for Thursday" is a real
sentence and refusing it would be pedantic.

**Word positions, not character offsets.** :func:`parse` reports which *words*
it consumed, so the caller can take what is left as the title. Character
offsets would be wrong: flattening a transcript is not length-preserving (an
accent that arrives as a separate combining character costs a character and no
words), but it never splits or joins a word.

Extending
---------
A new phrasing is usually one entry in one of the tables below. Everything is
written in **normalised** form -- lowercase, unaccented, final sigma as plain
sigma -- so run a candidate through :func:`nisos.normalise.normalise` before
adding it.
"""

from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime, timedelta

from .normalise import NUMBER_WORDS

__all__ = ["When", "parse", "DEFAULT_HOUR", "DEFAULT_MINUTES", "AFTERNOON_UNTIL"]


#: What "Thursday", with no time attached, means.
DEFAULT_HOUR = 9

#: How long an appointment lasts when nobody says.
DEFAULT_MINUTES = 60

#: A bare hour at or below this is read as afternoon: «στις πέντε» is 17:00.
#: Above it, the hour is taken at face value -- «στις δέκα» stays 10:00.
AFTERNOON_UNTIL = 7


# --------------------------------------------------------------------------
# The tables
# --------------------------------------------------------------------------

#: Whole words meaning a day, relative to today.
DAY_WORDS: dict[str, dict[str, int]] = {
    "el": {"σημερα": 0, "αποψε": 0, "αυριο": 1, "μεθαυριο": 2},
    "en": {"today": 0, "tonight": 0, "tomorrow": 1},
}

#: Day words that also carry a time of day. «απόψε στις οκτώ» is 20:00.
EVENING_DAYS = {"αποψε", "tonight"}

#: Weekday **stems**, matched with startswith, so «τη Δευτέρα», «της Δευτέρας»
#: and "on Mondays" all land on the same day.
WEEKDAYS: dict[str, dict[str, int]] = {
    "el": {"δευτερα": 0, "τριτη": 1, "τεταρτη": 2, "πεμπτη": 3,
           "παρασκευη": 4, "σαββατο": 5, "κυριακη": 6},
    "en": {"monday": 0, "tuesday": 1, "wednesday": 2, "thursday": 3,
           "friday": 4, "saturday": 5, "sunday": 6},
}

#: Words that force the morning, and words that force the afternoon or later.
AM_WORDS = {"el": ("πρωι", "πρωινο", "πρωια"), "en": ("am", "morning")}
PM_WORDS = {"el": ("απογευμα", "απογευματινο", "βραδυ", "βραδι", "μεσημερι",
                   "αποψε"),
            "en": ("pm", "afternoon", "evening", "night", "tonight", "noon")}

#: Words that introduce a clock time, so the number after them is an hour and
#: not a duration, a date or a house number.
AT_WORDS = {"el": ("στισ", "στη", "στην", "στο", "στον", "ωρα"),
            "en": ("at",)}

#: Words that introduce a duration.
FOR_WORDS = {"el": ("για",), "en": ("for",)}

#: Duration unit **stems**: «λεπτά», «λεπτό», "minutes", "min".
UNIT_MINUTES = {"el": (("ωρ", 60), ("λεπτ", 1)),
                "en": (("hour", 60), ("hr", 60), ("minute", 1), ("min", 1))}

#: Half and quarter, in the two shapes Greek actually says them.
HALF_WORDS = {"el": ("μιση", "μισι"), "en": ("half",)}
QUARTER_WORDS = {"el": ("τεταρτο",), "en": ("quarter",)}

#: «πέντε παρά τέταρτο» -- a quarter *to* rather than *past*.
TO_WORDS = {"el": ("παρα",), "en": ("to",)}

#: «και μισή» -- the joiner between an hour and its minutes. Note this is the
#: same word the router splits multi-action commands on; that is safe, because
#: a split is only accepted when *every* piece routes on its own, and «μισή»
#: does not route to anything.
AND_WORDS = {"el": ("και", "κι"), "en": ("and",)}

_PUNCTUATION = " ,.;:!?«»\"'()"


def _bare(word: str) -> str:
    """A word with the punctuation a recogniser sprinkles on it removed."""
    return word.strip(_PUNCTUATION)


def _order(language: str) -> list[str]:
    """The language to check first, then the other one.

    Code-switching is normal here -- "meeting με τον Νίκο tomorrow" is a
    sentence people say -- so nothing is ever checked in one language only.
    """
    first = language if language in DAY_WORDS else "en"
    return [first] + [other for other in DAY_WORDS if other != first]


def _lookup(word: str, table: dict[str, dict[str, int]], language: str):
    """Find `word` in a per-language table of whole words."""
    for lang in _order(language):
        if word in table.get(lang, {}):
            return table[lang][word]
    return None


def _weekday(word: str, language: str) -> int | None:
    """Find `word` in the weekday table, matching on the stem."""
    for lang in _order(language):
        for stem, index in WEEKDAYS.get(lang, {}).items():
            if word.startswith(stem):
                return index
    return None


def _in(word: str, table: dict[str, tuple[str, ...]], language: str) -> bool:
    """True if `word` appears in a per-language tuple of words."""
    return any(word in table.get(lang, ()) for lang in _order(language))


def _number(word: str, language: str) -> int | None:
    """Read a word as a number: digits first, then spelled out."""
    if word.isdigit():
        return int(word)
    for lang in _order(language):
        value = NUMBER_WORDS.get(lang, {}).get(word)
        if value is not None:
            return value
    return None


def _clock(word: str, language: str) -> tuple[int, int] | None:
    """Read a word as a clock time.

    Handles ``17:30``, ``17.30`` and ``5``, plus the spelled-out hours, and
    the English ``5pm`` that arrives as one token.

    Returns:
        ``(hour, minute)``, or None if the word is not a time at all.
    """
    suffix = None
    if word.endswith(("am", "pm")) and len(word) > 2:
        suffix, word = word[-2:], word[:-2]

    for separator in (":", ".", "h"):
        if separator in word:
            left, _, right = word.partition(separator)
            if left.isdigit() and right.isdigit():
                hour, minute = int(left), int(right)
                break
    else:
        value = _number(word, language)
        if value is None:
            return None
        hour, minute = value, 0

    if suffix == "pm" and hour < 12:
        hour += 12
    elif suffix == "am" and hour == 12:
        hour = 0

    if 0 <= hour <= 23 and 0 <= minute <= 59:
        return hour, minute
    return None


# --------------------------------------------------------------------------
# The result
# --------------------------------------------------------------------------

@dataclass(frozen=True)
class When:
    """A moment, how long it lasts, and which words paid for it.

    Attributes:
        start: The resolved local datetime.
        minutes: Duration, defaulting to :data:`DEFAULT_MINUTES`.
        words: Indices of the words :func:`parse` consumed. Whatever is left
            over is the caller's -- for ``calendar.add`` it is the title.
    """

    start: datetime
    minutes: int = DEFAULT_MINUTES
    words: frozenset[int] = frozenset()

    def iso(self) -> str:
        """``YYYY-MM-DDTHH:MM`` in local time.

        Deliberately no timezone and no seconds. This string is the contract
        between the router, the model and :func:`nisos.actions.calendar_add`,
        and a model asked for an appointment "tomorrow at five" knows what
        local wall-clock time means and does not know the phone's offset.
        """
        return self.start.strftime("%Y-%m-%dT%H:%M")


# --------------------------------------------------------------------------
# The parse
# --------------------------------------------------------------------------

def parse(words: list[str], language: str = "en",
          now: datetime | None = None) -> When | None:
    """Find a date and time in an already-normalised list of words.

    Args:
        words: The utterance, split on whitespace, already through
            :func:`nisos.normalise.normalise`.
        language: ``"el"`` or ``"en"``. Only decides which table is checked
            first -- both are always checked, because people code-switch.
        now: The moment to resolve relative words against. Injected so the
            tests are not a lottery at midnight.

    Returns:
        A :class:`When`, or None when the words contain no time at all --
        which is a normal answer, not a failure.

    >>> from datetime import datetime
    >>> monday = datetime(2026, 8, 10, 12, 0)
    >>> parse("αυριο στισ πεντε".split(), "el", monday).iso()
    '2026-08-11T17:00'
    >>> parse("tomorrow at 9 am".split(), "en", monday).iso()
    '2026-08-11T09:00'
    >>> parse("dentist".split(), "en", monday) is None
    True
    """
    now = now or datetime.now()

    day_offset: int | None = None
    weekday: int | None = None
    hour: int | None = None
    minute = 0
    meridiem: str | None = None
    minutes: int | None = None
    used: set[int] = set()

    index = 0
    while index < len(words):
        word = _bare(words[index])
        if not word:
            index += 1
            continue

        # -- "for 30 minutes", «για μία ώρα» --------------------------------
        if _in(word, FOR_WORDS, language):
            span = _duration(words, index + 1, language)
            if span:
                minutes, consumed = span
                used.update({index, *consumed})
                index = max(consumed) + 1
                continue

        # -- today / tomorrow / tonight -------------------------------------
        offset = _lookup(word, DAY_WORDS, language)
        if offset is not None:
            day_offset = offset
            if word in EVENING_DAYS and meridiem is None:
                meridiem = "pm"
            used.add(index)
            index += 1
            continue

        # -- Monday, «τη Δευτέρα» -------------------------------------------
        found = _weekday(word, language)
        if found is not None:
            weekday = found
            used.add(index)
            index += 1
            continue

        # -- morning / afternoon / pm ---------------------------------------
        if _in(word, AM_WORDS, language):
            meridiem = "am"
            used.add(index)
            index += 1
            continue
        if _in(word, PM_WORDS, language):
            meridiem = "pm"
            used.add(index)
            index += 1
            continue

        # -- "half past five" (English puts it in front) --------------------
        if (hour is None and _in(word, HALF_WORDS, language)
                and index + 2 < len(words)
                and _bare(words[index + 1]) == "past"):
            clock = _clock(_bare(words[index + 2]), language)
            if clock:
                hour, minute = clock[0], 30
                used.update({index, index + 1, index + 2})
                index += 3
                continue

        # -- «στις πέντε», "at 5", "at 17:30" -------------------------------
        if _in(word, AT_WORDS, language) and index + 1 < len(words):
            clock = _clock(_bare(words[index + 1]), language)
            if clock:
                hour, minute = clock
                used.update({index, index + 1})
                index += 2
                hour, minute = _adjust(words, index, language, hour, minute,
                                       used)
                index = _skip_used(index, used)
                continue

        # -- a bare 17:30, which needs no introduction ----------------------
        if hour is None and (":" in word or "." in word) and _clock(word, language):
            hour, minute = _clock(word, language)  # type: ignore[misc]
            used.add(index)
            index += 1
            hour, minute = _adjust(words, index, language, hour, minute, used)
            index = _skip_used(index, used)
            continue

        index += 1

    if hour is None and day_offset is None and weekday is None:
        return None

    start = _resolve(now, day_offset, weekday, hour, minute, meridiem)
    return When(start=start,
                minutes=minutes if minutes is not None else DEFAULT_MINUTES,
                words=frozenset(used))


def _skip_used(index: int, used: set[int]) -> int:
    """Move past any words a lookahead has already claimed."""
    while index in used:
        index += 1
    return index


def _adjust(words: list[str], index: int, language: str, hour: int,
            minute: int, used: set[int]) -> tuple[int, int]:
    """Apply «και μισή», «και τέταρτο» and «παρά τέταρτο» to an hour.

    Args:
        index: Where to start looking, immediately after the hour.
        used: Updated in place with anything consumed.

    Returns:
        The adjusted ``(hour, minute)``.
    """
    if index >= len(words):
        return hour, minute

    word = _bare(words[index])
    following = _bare(words[index + 1]) if index + 1 < len(words) else ""

    if _in(word, AND_WORDS, language) and following:
        if _in(following, HALF_WORDS, language):
            used.update({index, index + 1})
            return hour, 30
        if _in(following, QUARTER_WORDS, language):
            used.update({index, index + 1})
            return hour, 15

    if _in(word, TO_WORDS, language) and _in(following, QUARTER_WORDS, language):
        used.update({index, index + 1})
        return (hour - 1) % 24, 45

    return hour, minute


def _duration(words: list[str], index: int, language: str
              ) -> tuple[int, set[int]] | None:
    """Read "30 minutes" / «μισή ώρα» / «μία ώρα» starting at `index`.

    Returns:
        ``(minutes, consumed indices)``, or None if what follows is not a
        duration -- "for Anna" must not become an appointment length.
    """
    if index >= len(words):
        return None

    consumed = {index}
    word = _bare(words[index])

    if _in(word, HALF_WORDS, language):
        count: float = 0.5
    else:
        value = _number(word, language)
        if value is None:
            return None
        count = value

    if index + 1 >= len(words):
        return None
    unit = _bare(words[index + 1])
    for lang in _order(language):
        for stem, size in UNIT_MINUTES.get(lang, ()):
            if unit.startswith(stem):
                consumed.add(index + 1)
                return max(1, round(count * size)), consumed
    return None


def _resolve(now: datetime, day_offset: int | None, weekday: int | None,
             hour: int | None, minute: int, meridiem: str | None) -> datetime:
    """Turn the pieces into one moment.

    The rules, in the order they are applied:

    1. A bare hour of one to seven with nothing to say otherwise means the
       afternoon -- see :data:`AFTERNOON_UNTIL`.
    2. No hour at all means :data:`DEFAULT_HOUR`.
    3. A named weekday means its next occurrence, today included only if the
       time has not passed.
    4. No day at all means today, rolled to tomorrow if the time has passed.
    """
    if hour is None:
        hour, minute = DEFAULT_HOUR, 0
    elif meridiem == "pm" and hour < 12:
        hour += 12
    elif meridiem == "am" and hour == 12:
        hour = 0
    elif meridiem is None and 1 <= hour <= AFTERNOON_UNTIL:
        hour += 12

    start = now.replace(hour=hour, minute=minute, second=0, microsecond=0)

    if weekday is not None:
        ahead = (weekday - start.weekday()) % 7
        if ahead == 0 and start <= now:
            ahead = 7
        return start + timedelta(days=ahead)

    if day_offset is not None:
        return start + timedelta(days=day_offset)

    if start <= now:
        return start + timedelta(days=1)
    return start
