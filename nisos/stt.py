"""Speech to text -- two recognisers, raced against each other.

Neither recogniser is good enough alone:

* **Android's built-in recogniser** (``termux-speech-to-text``) returns in about
  half a second and is more accurate on Greek than any Whisper model that fits
  comfortably on a phone. But it transcribes in the **device locale** and
  Termux cannot change that at runtime, so it reliably handles exactly one of
  your two languages.
* **whisper.cpp small** detects the language itself, so it covers the other
  one -- but it takes around 1.2 seconds, which is too slow to pay on every
  "torch on".

So run both on the same recording and let the router decide. If Android's
transcript routes, you are finished in about a second and Whisper is killed
mid-sentence. If it does not -- wrong language, or a genuinely complex request
-- Whisper is already halfway done, and you were about to spend 1.4 seconds on
the model anyway. The fallback hides inside latency you had already accepted.

Extending
---------
Adding a third recogniser means writing a function that returns a
:class:`Transcript` and adding it to :func:`transcribe`. The probe callback is
the only contract: whatever can satisfy the router, wins.
"""

from __future__ import annotations

import logging
import re
import shutil
import subprocess
import time
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass
from typing import Callable

__all__ = ["Transcript", "transcribe", "android", "whisper", "SttError"]

log = logging.getLogger(__name__)


class SttError(Exception):
    """No recogniser could produce a transcript."""


@dataclass
class Transcript:
    """One recogniser's answer.

    Attributes:
        text: What it heard. Raw -- normalisation happens in the router.
        source: ``"android"``, ``"whisper"`` or ``"typed"``.
        language: ``"el"``/``"en"`` when the recogniser reports one (Whisper
            does, Android does not), otherwise None. Advisory only: the router
            has the final say, because a matched pattern is stronger evidence
            than a recogniser's guess.
        seconds: How long it took, for the log line.
    """

    text: str
    source: str
    language: str | None = None
    seconds: float = 0.0

    def __bool__(self) -> bool:
        """A transcript is falsy when it is empty, so callers can `if not t:`."""
        return bool(self.text.strip())


# Whisper prints its language guess to stderr, e.g.
#   whisper_full_with_state: auto-detected language: el (p = 0.98)
_WHISPER_LANG = re.compile(r"auto-detected language:\s*([a-z]{2})")


def android(timeout: float = 8.0) -> Transcript:
    """Transcribe using Android's on-device recogniser.

    Requires the Greek (or English) offline pack to be installed, or this
    silently becomes an online call -- which defeats the entire point. Verify
    once with the phone in airplane mode; it fails quietly rather than telling
    you the pack is missing.

    Note this reads the microphone itself rather than a file: the Android API
    does not accept pre-recorded audio. That means in ``race`` mode the two
    recognisers are not literally listening to the same bytes, only to the same
    utterance. In practice this is fine and saves a recording round-trip.

    Args:
        timeout: Seconds before giving up.

    Returns:
        A :class:`Transcript`. Empty text if nothing was heard.

    Raises:
        SttError: If termux-api is not installed.
    """
    if shutil.which("termux-speech-to-text") is None:
        raise SttError("termux-speech-to-text not found (install termux-api)")

    started = time.perf_counter()
    try:
        done = subprocess.run(["termux-speech-to-text"], capture_output=True,
                              text=True, timeout=timeout, check=False)
    except subprocess.TimeoutExpired:
        return Transcript("", "android", None, timeout)

    return Transcript(
        text=done.stdout.strip(),
        source="android",
        language=None,  # it never tells us; the router works it out
        seconds=time.perf_counter() - started,
    )


def whisper(audio_path: str, binary: str, model: str,
            timeout: float = 20.0,
            process_box: list | None = None) -> Transcript:
    """Transcribe a WAV file with whisper.cpp, detecting the language.

    Args:
        audio_path: 16 kHz mono WAV.
        binary: Path to ``whisper-cli``.
        model: Path to the **multilingual** GGML weights. Using a ``.en`` model
            here is the single easiest way to build this whole project and
            discover it only speaks English.
        timeout: Seconds before giving up.
        process_box: If given, the running Popen is appended to it so a caller
            can kill this transcription early. That is how the race cancels the
            loser instead of leaving it burning CPU.

    Returns:
        A :class:`Transcript` with ``language`` filled in. Empty text if
        Whisper was killed or found nothing.
    """
    if not shutil.which(binary) and not _is_file(binary):
        raise SttError(f"whisper-cli not found at {binary}")

    command = [
        binary,
        "-m", model,
        "-f", audio_path,
        "-l", "auto",     # the whole reason Whisper is here
        "-nt",            # no timestamps, just the words
        "-np",            # no progress spam in the log
        "-t", "4",        # leave cores free for llama-server
    ]

    started = time.perf_counter()
    try:
        proc = subprocess.Popen(command, stdout=subprocess.PIPE,
                                stderr=subprocess.PIPE, text=True)
        if process_box is not None:
            process_box.append(proc)
        out, err = proc.communicate(timeout=timeout)
    except subprocess.TimeoutExpired:
        proc.kill()
        return Transcript("", "whisper", None, timeout)

    if proc.returncode != 0 and not out.strip():
        # Killed by the race, almost always. Not worth a warning.
        return Transcript("", "whisper", None, time.perf_counter() - started)

    guess = _WHISPER_LANG.search(err or "")
    return Transcript(
        text=out.strip(),
        source="whisper",
        language=guess.group(1) if guess else None,
        seconds=time.perf_counter() - started,
    )


def transcribe(config, audio_path: str,
               probe: Callable[[str], bool] | None = None) -> Transcript:
    """Get a transcript using the configured strategy.

    Args:
        config: A :class:`nisos.config.Config`.
        audio_path: The recording, for Whisper. Android ignores it.
        probe: Callable that answers "would the router accept this text?".
            In ``race`` mode this decides whether Android's fast answer is good
            enough to cancel Whisper. Without a probe, race mode degrades to
            "trust Android if it said anything at all".

    Returns:
        The winning :class:`Transcript`.

    Raises:
        SttError: If every recogniser failed.
    """
    strategy = config.get_path("stt.strategy", "race")

    if strategy == "android":
        return android(config.get_path("stt.android_timeout", 8.0))

    if strategy == "whisper":
        return whisper(audio_path,
                       config.expanded("stt.whisper_bin"),
                       config.expanded("stt.whisper_model"),
                       config.get_path("stt.whisper_timeout", 20.0))

    return _race(config, audio_path, probe)


def _race(config, audio_path: str,
          probe: Callable[[str], bool] | None) -> Transcript:
    """Run both recognisers, take Android's if the router likes it.

    See the module docstring for why this is the default. The losing process is
    killed rather than left to finish, because on a phone a stray Whisper run
    is a second of CPU you are paying for in battery.
    """
    whisper_procs: list = []

    def run_android() -> Transcript:
        try:
            return android(config.get_path("stt.android_timeout", 8.0))
        except SttError as exc:
            log.debug("Android recogniser unavailable: %s", exc)
            return Transcript("", "android")

    def run_whisper() -> Transcript:
        try:
            return whisper(audio_path,
                           config.expanded("stt.whisper_bin"),
                           config.expanded("stt.whisper_model"),
                           config.get_path("stt.whisper_timeout", 20.0),
                           process_box=whisper_procs)
        except SttError as exc:
            log.debug("Whisper unavailable: %s", exc)
            return Transcript("", "whisper")

    with ThreadPoolExecutor(max_workers=2) as pool:
        fast = pool.submit(run_android)
        slow = pool.submit(run_whisper)

        first = fast.result()
        accepted = bool(first) and (probe(first.text) if probe else True)

        if accepted:
            for proc in whisper_procs:
                if proc.poll() is None:
                    proc.kill()
            log.debug("Android won (%.2fs), Whisper cancelled", first.seconds)
            slow.cancel()
            return first

        second = slow.result()
        if second:
            log.debug("Router rejected Android's transcript; using Whisper "
                      "(%.2fs, language=%s)", second.seconds, second.language)
            return second

        # Whisper produced nothing either. Android's text, however unroutable,
        # is still better than silence -- the model may make sense of it.
        if first:
            return first

    raise SttError("no recogniser produced anything")


def _is_file(path: str) -> bool:
    """True if `path` points at an existing file. Kept small for testability."""
    from pathlib import Path
    return Path(path).expanduser().is_file()
