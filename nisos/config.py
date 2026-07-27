"""Configuration loading.

Settings live in a TOML file so you can retune the assistant from the phone
with a text editor and no Python. :func:`load` merges your file over the
defaults below, which means a half-written config still boots -- useful when
you are editing it over SSH at midnight.

Extending
---------
Add a field to :data:`DEFAULTS` and it becomes settable immediately. Paths are
expanded with ``~`` so the config reads the same on a phone and a laptop.
"""

from __future__ import annotations

import copy
import logging
import os
import tomllib
from pathlib import Path
from typing import Any

__all__ = ["DEFAULTS", "load", "Config"]

log = logging.getLogger(__name__)


#: Every setting the program understands, with a sane value. Anything absent
#: from the user's config.toml falls back to what is here.
DEFAULTS: dict[str, Any] = {
    "general": {
        # Sticky locale for the fast recogniser. The router corrects this
        # automatically whenever you speak the other language, so it is a
        # starting guess rather than a setting you need to keep accurate.
        "language": "el",
        "dry_run": False,
        "log_file": "~/.nisos/nisos.log",
    },
    "audio": {
        "seconds": 8,          # hard cap on a single recording
        "sample_rate": 16000,  # what whisper.cpp wants; do not change lightly
        "path": "~/.nisos/input.wav",  # always the SAME path, so it overwrites
    },
    "stt": {
        # race    -- fire both, take Android's if the router likes it (default)
        # android -- fast, accurate, but you must announce language switches
        # whisper -- slower, auto-detects, simplest code path
        "strategy": "race",
        "whisper_bin": "~/nisos/bin/whisper-cli",
        "whisper_model": "~/nisos/models/ggml-small-q5_1.bin",
        "android_timeout": 8.0,
        "whisper_timeout": 20.0,
    },
    "brain": {
        "url": "http://127.0.0.1:8080",
        "grammar": "grammar/action.gbnf",
        "timeout": 40.0,
        "max_tokens": 128,
        "temperature": 0.2,
    },
    "speech": {
        "engine": "android",   # android | piper
        "piper_bin": "~/nisos/bin/piper",
        "voices": {"en": "en-GB", "el": "el-GR"},
        "piper_models": {
            "en": "~/nisos/voices/en_GB-alba-medium.onnx",
            "el": "~/nisos/voices/el_GR-rapunzelina-low.onnx",
        },
    },
    "tasker": {
        "task": "NisosAction",
    },
    # Maps what the recogniser heard to the real contact name. See
    # ExecutionContext.resolve_contact for why this exists.
    "contacts": {},
}


class Config(dict):
    """A dict with dotted lookup, so ``cfg["stt.strategy"]`` works.

    Thin on purpose. Config is read in a dozen places and none of them benefit
    from a class hierarchy.
    """

    def get_path(self, dotted: str, default: Any = None) -> Any:
        """Fetch a nested value by dotted key.

        Args:
            dotted: e.g. ``"stt.whisper_model"``.
            default: Returned if any part of the path is missing.

        >>> Config({"a": {"b": 1}}).get_path("a.b")
        1
        """
        node: Any = self
        for part in dotted.split("."):
            if not isinstance(node, dict) or part not in node:
                return default
            node = node[part]
        return node

    def expanded(self, dotted: str, default: Any = None) -> str:
        """Like :meth:`get_path`, but resolves ``~`` and environment variables."""
        raw = self.get_path(dotted, default)
        if raw is None:
            return ""
        return os.path.expanduser(os.path.expandvars(str(raw)))


def _merge(base: dict, overlay: dict) -> dict:
    """Deep-merge `overlay` onto a copy of `base`, dicts recursing, scalars replacing."""
    result = copy.deepcopy(base)
    for key, value in overlay.items():
        if isinstance(value, dict) and isinstance(result.get(key), dict):
            result[key] = _merge(result[key], value)
        else:
            result[key] = value
    return result


def load(path: str | Path | None = None) -> Config:
    """Read config.toml and merge it over :data:`DEFAULTS`.

    Args:
        path: Where to look. Defaults to ``config.toml`` beside the package,
            then ``~/.nisos/config.toml``.

    Returns:
        A :class:`Config`. Always usable -- a missing or malformed file logs a
        warning and yields the defaults rather than refusing to start.
    """
    candidates = []
    if path:
        candidates.append(Path(path))
    else:
        candidates.append(Path(__file__).resolve().parent.parent / "config.toml")
        candidates.append(Path.home() / ".nisos" / "config.toml")

    for candidate in candidates:
        if not candidate.is_file():
            continue
        try:
            with candidate.open("rb") as handle:
                user = tomllib.load(handle)
            log.debug("Loaded config from %s", candidate)
            return Config(_merge(DEFAULTS, user))
        except (tomllib.TOMLDecodeError, OSError) as exc:
            log.warning("Ignoring unreadable config %s: %s", candidate, exc)

    log.debug("No config file found; using defaults")
    return Config(copy.deepcopy(DEFAULTS))
