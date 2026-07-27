"""Recording, and knowing when you have stopped talking.

Uses ``termux-microphone-record`` because it needs no extra packages and
records straight to the 16 kHz mono WAV that whisper.cpp wants -- which is the
reason this project does not install ffmpeg at all, saving 130 MB.

The recording always goes to the **same path**, overwriting each time. Writing
``input-<timestamp>.wav`` instead is the classic way to fill a phone's storage
over a few months without noticing.

Extending
---------
If you want proper voice-activity detection rather than a fixed cap, replace
:func:`record` -- everything downstream only cares that a WAV file exists at
the returned path.
"""

from __future__ import annotations

import logging
import shutil
import subprocess
import time
from pathlib import Path

__all__ = ["record", "RecordingError", "available"]

log = logging.getLogger(__name__)


class RecordingError(Exception):
    """The microphone could not be read."""


def available() -> bool:
    """True if termux-microphone-record is installed and on PATH.

    Lets the CLI fall back to typed input on a desktop instead of crashing,
    which is how most of this program gets developed and tested.
    """
    return shutil.which("termux-microphone-record") is not None


def record(path: str, seconds: int = 8, sample_rate: int = 16000) -> str:
    """Capture audio to `path` and return that path.

    Records for at most `seconds`, then stops. termux-microphone-record runs in
    the background and is stopped explicitly, so the cap is a ceiling rather
    than a fixed wait -- :func:`stop` returns as soon as the file is closed.

    Args:
        path: Destination WAV. Parent directories are created.
        seconds: Maximum recording length. Eight is generous for a command; the
            model never sees audio this long because the recogniser trims it.
        sample_rate: 16000 unless you have changed the Whisper model.

    Returns:
        The path written.

    Raises:
        RecordingError: If Termux:API is missing or the recorder fails.
    """
    if not available():
        raise RecordingError(
            "termux-microphone-record not found -- install the Termux:API app "
            "from F-Droid and run: pkg install termux-api"
        )

    target = Path(path).expanduser()
    target.parent.mkdir(parents=True, exist_ok=True)
    if target.exists():
        target.unlink()  # the recorder refuses to overwrite

    command = [
        "termux-microphone-record",
        "-f", str(target),
        "-l", str(seconds),
        "-r", str(sample_rate),
        "-c", "1",              # mono
        "-e", "wav",
    ]

    try:
        subprocess.run(command, capture_output=True, text=True,
                       timeout=seconds + 5, check=True)
    except subprocess.TimeoutExpired as exc:
        raise RecordingError("recorder did not start") from exc
    except subprocess.CalledProcessError as exc:
        raise RecordingError(f"recorder failed: {exc.stderr.strip()}") from exc

    log.debug("Recording to %s (max %ss)", target, seconds)
    return str(target)


def stop() -> None:
    """Stop an in-progress recording.

    Call this when you detect end-of-speech -- from a Tasker scene button, a
    second back-tap, or your own silence detector. Safe to call when nothing is
    recording.
    """
    if not available():
        return
    try:
        subprocess.run(["termux-microphone-record", "-q"],
                       capture_output=True, timeout=5, check=False)
    except subprocess.TimeoutExpired:
        log.warning("Recorder would not stop")


def wait_for_file(path: str, timeout: float = 2.0) -> bool:
    """Block until `path` exists and has stopped growing, or `timeout` passes.

    The recorder closes its file asynchronously, so reading it immediately can
    hand Whisper a truncated WAV. Cheap insurance.

    Returns:
        True if the file settled, False on timeout.
    """
    target = Path(path).expanduser()
    deadline = time.monotonic() + timeout
    last_size = -1

    while time.monotonic() < deadline:
        if target.exists():
            size = target.stat().st_size
            if size > 0 and size == last_size:
                return True
            last_size = size
        time.sleep(0.05)

    return target.exists() and target.stat().st_size > 0
