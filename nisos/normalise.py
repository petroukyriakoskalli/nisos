"""Text normalisation shared by both language routers.

Greek breaks naive regex three ways at once:

  1. Verbs inflect      -- άναψε / ανάψτε / να ανάψεις all mean "turn it on"
  2. Accents wander     -- a recogniser may return «φακό» or «φακο» on any given day
  3. Final sigma        -- «φακός» at the end of a word, «φακοσ» anywhere else

:func:`normalise` flattens (2) and (3) so the router only has to cope with (1),
which it does by matching *stems* rather than whole words.

Everything in this module is pure text handling with no phone dependencies, so
it runs and tests fine on a desktop.

Extending
---------
Adding a language means adding its number words to :data:`NUMBER_WORDS` and,
if the script has its own quirks, extending :func:`normalise`. Nothing else in
the codebase needs to know.
"""

from __future__ import annotations

import re
import unicodedata

__all__ = [
    "normalise",
    "Normalised",
    "strip_accents",
    "script_of",
    "parse_number",
    "NUMBER_WORDS",
]


# --------------------------------------------------------------------------
# Number words
# --------------------------------------------------------------------------
# Greek speech recognisers return "δώδεκα", not "12". Without this map roughly
# half of all timer and volume commands fail silently -- the pattern matches,
# the argument comes back None, and the action quietly does nothing. English
# recognisers convert to digits far more reliably, but a few words still slip
# through ("set a timer for ten minutes"), so both languages get a table.
#
# Keys must be written in NORMALISED form: lowercase, no accents, no final
# sigma. Run a candidate through normalise() before adding it here.

NUMBER_WORDS: dict[str, dict[str, int]] = {
    "el": {
        "μηδεν": 0, "ενα": 1, "μια": 1, "δυο": 2, "τρια": 3, "τρεισ": 3,
        "τεσσερα": 4, "τεσσερισ": 4, "πεντε": 5, "εξι": 6, "εφτα": 7,
        "επτα": 7, "οκτω": 8, "οχτω": 8, "εννεα": 9, "εννια": 9, "δεκα": 10,
        "εντεκα": 11, "δωδεκα": 12, "δεκατρια": 13, "δεκατεσσερα": 14,
        "δεκαπεντε": 15, "εικοσι": 20, "εικοσιπεντε": 25, "τριαντα": 30,
        "σαραντα": 40, "πενηντα": 50, "εξηντα": 60, "ενενηντα": 90,
        "εκατο": 100,
    },
    "en": {
        "zero": 0, "one": 1, "two": 2, "three": 3, "four": 4, "five": 5,
        "six": 6, "seven": 7, "eight": 8, "nine": 9, "ten": 10,
        "eleven": 11, "twelve": 12, "thirteen": 13, "fourteen": 14,
        "fifteen": 15, "twenty": 20, "thirty": 30, "forty": 40,
        "fifty": 50, "sixty": 60, "ninety": 90, "hundred": 100,
    },
}


# --------------------------------------------------------------------------
# Normalisation
# --------------------------------------------------------------------------

# Unicode combining marks -- what NFD decomposition peels off a letter.
_COMBINING = "Mn"

# Greek block, roughly. Used only to tell the two scripts apart, which is all
# the language detection this program ever needs.
_GREEK_CHARS = re.compile(r"[Ͱ-Ͽἀ-῿]")
_LATIN_CHARS = re.compile(r"[a-zA-Z]")


def strip_accents(text: str) -> str:
    """Remove diacritics while leaving the base letters intact.

    Works by decomposing each character into base + combining marks (NFD) and
    then dropping the marks. Safe on English, where it is a no-op for ASCII and
    quietly fixes pasted text like "café".

    >>> strip_accents("άναψε")
    'αναψε'
    """
    decomposed = unicodedata.normalize("NFD", text)
    return "".join(c for c in decomposed if unicodedata.category(c) != _COMBINING)


class Normalised(str):
    """Flattened text that still remembers how the user actually spelled it.

    A plain string everywhere it matters -- it compares, slices, formats and
    gets matched by a regex exactly like one -- with a single addition:
    :meth:`original`, which hands back the user's own spelling of a range of
    words.

    Why it has to exist. Patterns are written against flattened text, so that
    is what the router matches and what the argument builders are handed. That
    is right for *matching* and wrong for anything that ends up **written
    down**: a calendar entry titled «οδοντιατρο» rather than «οδοντίατρος» is
    the assistant's internal plumbing leaking into your diary. Speech does not
    have this problem, because a reply is composed from a template you wrote.

    Word positions rather than character offsets, deliberately. Flattening is
    not length-preserving -- an accent that arrives as a separate combining
    character costs a character and no words -- but it never splits or joins a
    word, so the two word lists always line up. When they somehow don't, the
    flattened words are used and the worst case is the old behaviour.
    """

    def __new__(cls, flattened: str, raw: str | None = None) -> "Normalised":
        self = super().__new__(cls, flattened)
        self.raw = flattened if raw is None else raw
        candidate = self.raw.split()
        self.raw_words = (candidate if len(candidate) == len(flattened.split())
                          else flattened.split())
        return self

    def original(self, indices) -> str:
        """The user's own spelling of the words at `indices`, in order.

        >>> Normalised("αναψε τον φακο", "Άναψε τον φακό").original([2])
        'φακό'
        """
        return " ".join(self.raw_words[i] for i in sorted(indices)
                        if 0 <= i < len(self.raw_words))


def normalise(text: str) -> Normalised:
    """Flatten a transcript into the form the router patterns are written against.

    Lowercases, strips accents, unifies final sigma, and collapses runs of
    whitespace. Harmless on English input, so the pipeline can call it once and
    hand the result to both language tables.

    Returns a :class:`Normalised`, which is a string in every way that matters
    and additionally knows what it was flattened from.

    >>> normalise("Άναψε τον Φακό!")
    'αναψε τον φακο!'
    >>> normalise("  Torch   ON ")
    'torch on'
    """
    lowered = text.lower()
    unaccented = strip_accents(lowered)
    unified = unaccented.replace("ς", "σ")
    return Normalised(re.sub(r"\s+", " ", unified).strip(), text)


def script_of(text: str) -> str | None:
    """Report which alphabet a string is written in: ``"el"``, ``"en"``, or None.

    This is a cheap sanity check, *not* the program's language detection --
    that falls out of the router for free, because the two alphabets share no
    characters. Use this only to spot an obviously-wrong transcript (Greek
    audio handed to an English-locked recogniser comes back pure Latin).

    Returns None when the string contains neither alphabet, or a roughly equal
    mix of both, which usually means code-switching.

    >>> script_of("άναψε τον φακό")
    'el'
    >>> script_of("torch on")
    'en'
    """
    greek = len(_GREEK_CHARS.findall(text))
    latin = len(_LATIN_CHARS.findall(text))
    if greek == 0 and latin == 0:
        return None
    if greek > latin * 2:
        return "el"
    if latin > greek * 2:
        return "en"
    return None  # too mixed to call


def parse_number(text: str, language: str | None = None) -> int | None:
    """Pull the first number out of a normalised transcript.

    Digits always win -- "timer 12 minutes" and «χρονόμετρο 12 λεπτά» both
    resolve immediately. Only when there are no digits does it fall back to
    spelled-out words, checking the given language first and then the other
    one, so a code-switched sentence still resolves.

    Args:
        text: A transcript that has already been through :func:`normalise`.
        language: ``"el"`` or ``"en"`` to check first. None checks both.

    Returns:
        The number, or None if the text contains none.

    >>> parse_number("χρονομετρο δωδεκα λεπτα", "el")
    12
    >>> parse_number("timer for 25 minutes", "en")
    25
    """
    digits = re.search(r"\d+", text)
    if digits:
        return int(digits.group())

    # Longest-first so "δεκαπεντε" (15) is not shadowed by "δεκα" (10).
    order = [language] if language else []
    order += [lang for lang in NUMBER_WORDS if lang not in order]

    for lang in order:
        words = NUMBER_WORDS.get(lang, {})
        for word in sorted(words, key=len, reverse=True):
            if re.search(rf"\b{re.escape(word)}\b", text):
                return words[word]
    return None
