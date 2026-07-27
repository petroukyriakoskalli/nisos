"""Things nisos remembers between conversations.

Two stores, because they're used completely differently:

* **facts** -- free-form things you told it. "Marilena's birthday is in March."
  These are surfaced to the model on reasoned turns, but only the ones that look
  relevant to what you just said.
* **contacts** -- name to phone number. Looked up directly by ``sms.send`` and
  ``whatsapp.send``, never sent to the model. This is also the real fix for
  code-switching: a Greek-locked recogniser renders "Marilena" as «μαριλενα»,
  and once you've told nisos that maps to a number, it stops mattering.

Keys are stored **normalised** (lowercase, no accents, final sigma folded), so
«Μαριλένα», «μαριλενα» and «ΜΑΡΙΛΕΝΑ» are all the same key. Values are kept
verbatim, because you want them read back the way you said them.

Why not just put everything in the prompt
-----------------------------------------
Because memory grows and the context window doesn't. Injecting the lot would
push a 4B model past the point where it reasons well, and Greek costs two to
three times more tokens per word than English, so it degrades twice as fast.
:func:`relevant` only surfaces facts whose key actually appears in what you
said, capped.

Extending
---------
Add a store by adding a top-level key in :data:`_EMPTY` and a pair of
accessors. Everything is written atomically -- a phone can lose power
mid-write, and a truncated memory file that fails to parse would silently
lose everything.
"""

from __future__ import annotations

import json
import logging
import os
import re
import tempfile
from pathlib import Path

from .normalise import normalise

__all__ = [
    "Memory", "default_path", "normalise_phone",
]

log = logging.getLogger(__name__)

#: Cap on stored facts. Old, never-recalled ones are dropped first.
MAX_FACTS = 200

_EMPTY: dict = {"facts": {}, "contacts": {}}


def default_path() -> Path:
    """Where memory lives. Inside Termux's private storage, like everything else."""
    return Path.home() / ".nisos" / "memory.json"


def normalise_phone(raw: str, country_code: str = "") -> str | None:
    """Reduce a spoken or typed number to digits WhatsApp will accept.

    ``wa.me`` wants an international number with no ``+``, spaces or dashes.
    Local numbers get the configured country code prefixed.

    Args:
        raw: Whatever the user said or typed.
        country_code: Digits, no ``+``, e.g. ``"357"``. Empty means don't guess.

    Returns:
        Digits only, or None if there aren't enough of them to be a number.

    >>> normalise_phone("+357 99 123456")
    '35799123456'
    >>> normalise_phone("99123456", "357")
    '35799123456'
    """
    if not raw:
        return None
    digits = re.sub(r"\D", "", raw)
    if raw.strip().startswith("+"):
        pass                                  # already international
    elif digits.startswith("00"):
        digits = digits[2:]                   # 00 prefix is the same thing
    elif country_code and not digits.startswith(country_code):
        digits = country_code + digits
    return digits if len(digits) >= 7 else None


class Memory:
    """A tiny JSON-backed store, loaded once and written atomically.

    Args:
        path: Where to persist. Defaults to :func:`default_path`.
    """

    def __init__(self, path: str | Path | None = None):
        self.path = Path(path) if path else default_path()
        self._data = self._load()

    # -- persistence ------------------------------------------------------
    def _load(self) -> dict:
        """Read the file, tolerating absence and corruption.

        A memory file that won't parse must not stop the assistant from
        working -- losing the ability to turn the torch on because a JSON file
        got truncated would be absurd. It's backed up and replaced.
        """
        if not self.path.is_file():
            return json.loads(json.dumps(_EMPTY))
        try:
            with self.path.open(encoding="utf-8") as fh:
                data = json.load(fh)
        except (json.JSONDecodeError, OSError) as exc:
            log.warning("memory unreadable (%s); starting fresh", exc)
            try:
                self.path.rename(self.path.with_suffix(".corrupt"))
            except OSError:
                pass
            return json.loads(json.dumps(_EMPTY))
        for key, blank in _EMPTY.items():
            data.setdefault(key, type(blank)())
        return data

    def save(self) -> None:
        """Write to a temp file and rename over the original.

        Atomic, so a power loss mid-write leaves the old file intact rather
        than a half-written one.
        """
        self.path.parent.mkdir(parents=True, exist_ok=True)
        fd, tmp = tempfile.mkstemp(dir=str(self.path.parent), suffix=".tmp")
        try:
            with os.fdopen(fd, "w", encoding="utf-8") as fh:
                json.dump(self._data, fh, ensure_ascii=False, indent=1)
            os.replace(tmp, self.path)
        except OSError as exc:
            log.error("couldn't save memory: %s", exc)
            try:
                os.unlink(tmp)
            except OSError:
                pass

    # -- facts ------------------------------------------------------------
    def remember(self, key: str, value: str) -> None:
        """Store a fact. Overwrites any existing one with the same key."""
        k = normalise(key)
        if not k or not value.strip():
            return
        self._data["facts"][k] = {"value": value.strip(), "said": key.strip(), "hits": 0}
        self._prune()
        self.save()

    def recall(self, key: str) -> str | None:
        """Look up one fact, counting the hit so pruning keeps useful ones."""
        entry = self._data["facts"].get(normalise(key))
        if entry is None:
            return None
        entry["hits"] = entry.get("hits", 0) + 1
        self.save()
        return entry["value"]

    def forget(self, key: str) -> bool:
        """Drop a fact. Returns True if there was one."""
        if self._data["facts"].pop(normalise(key), None) is None:
            return False
        self.save()
        return True

    def facts(self) -> dict[str, str]:
        """Every fact as ``{as-you-said-it: value}``."""
        return {e.get("said", k): e["value"] for k, e in self._data["facts"].items()}

    def relevant(self, text: str, limit: int = 5) -> dict[str, str]:
        """Facts worth showing the model for this particular utterance.

        Matches on whole normalised keys appearing in the normalised text, so
        "when is marilena's birthday" surfaces the *marilena* fact and nothing
        else. Capped, because every injected token is paid for on a phone and
        Greek costs two to three times more of them per word.

        >>> m = Memory(":memory:") if False else None  # see tests
        """
        haystack = normalise(text)
        hits = []
        for k, entry in self._data["facts"].items():
            if k and re.search(rf"(?<!\w){re.escape(k)}(?!\w)", haystack):
                hits.append((entry.get("hits", 0), entry.get("said", k), entry["value"]))
        hits.sort(reverse=True)
        return {said: value for _, said, value in hits[:limit]}

    def _prune(self) -> None:
        """Keep the store bounded, dropping never-recalled entries first."""
        facts = self._data["facts"]
        if len(facts) <= MAX_FACTS:
            return
        ordered = sorted(facts.items(), key=lambda kv: kv[1].get("hits", 0))
        for key, _ in ordered[: len(facts) - MAX_FACTS]:
            facts.pop(key, None)

    # -- contacts ---------------------------------------------------------
    def remember_contact(self, name: str, number: str, country_code: str = "") -> str | None:
        """Store a phone number against a name.

        Returns:
            The normalised number, or None if it didn't look like one.
        """
        digits = normalise_phone(number, country_code)
        if not digits:
            return None
        self._data["contacts"][normalise(name)] = {"number": digits, "said": name.strip()}
        self.save()
        return digits

    def contact(self, name: str) -> str | None:
        """Look up a number by name. Returns digits, or None."""
        entry = self._data["contacts"].get(normalise(name))
        if isinstance(entry, dict):
            return entry.get("number")
        return entry  # tolerate a hand-edited plain string

    def contacts(self) -> dict[str, str]:
        """Every contact as ``{as-you-said-it: digits}``."""
        out = {}
        for k, e in self._data["contacts"].items():
            out[e.get("said", k) if isinstance(e, dict) else k] = (
                e.get("number") if isinstance(e, dict) else e)
        return out

    def forget_contact(self, name: str) -> bool:
        """Drop a contact. Returns True if there was one."""
        if self._data["contacts"].pop(normalise(name), None) is None:
            return False
        self.save()
        return True
