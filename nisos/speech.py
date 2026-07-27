"""Text to speech, in whichever language was spoken.

Two engines:

* **android** (default) -- ``termux-tts-speak -l el-GR``. Uses the system voice,
  which is already on the phone, works offline once the voice data is
  downloaded, and covers both languages from one binary. Costs zero extra
  storage.
* **piper** -- better English, but Greek is limited to a single low-quality
  voice (``el_GR-rapunzelina-low``), and it adds about 150 MB. Worth it only if
  the system English voice grates on you.

Android's engine is the default precisely because it makes the second language
free: dropping Piper pays for the multilingual Whisper weights almost exactly,
so bilingual costs roughly nothing on disk.

Extending
---------
Add an engine by writing a ``_speak_<name>`` function and listing it in
:func:`speak`. The contract is: take text and a language, block until spoken.
"""

from __future__ import annotations

import logging
import shutil
import subprocess

__all__ = ["speak", "available"]

log = logging.getLogger(__name__)


def available(engine: str = "android") -> bool:
    """True if the named engine's binary is installed."""
    binaries = {"android": "termux-tts-speak", "piper": "piper"}
    return shutil.which(binaries.get(engine, "")) is not None


def speak(text: str, language: str, config) -> bool:
    """Say `text` out loud in `language`.

    Args:
        text: The finished sentence, already rendered by
            :func:`nisos.replies.say`.
        language: ``"el"`` or ``"en"``.
        config: A :class:`nisos.config.Config`.

    Returns:
        True if it spoke, False if it could not. Never raises -- an assistant
        that crashes because the speaker is busy is worse than a silent one,
        and the action has already happened by this point.
    """
    if not text.strip():
        return False

    engine = config.get_path("speech.engine", "android")

    try:
        if engine == "piper":
            return _speak_piper(text, language, config)
        return _speak_android(text, language, config)
    except Exception:  # noqa: BLE001 -- never let TTS kill the loop
        log.exception("Text-to-speech failed; the action still ran")
        return False


def _speak_android(text: str, language: str, config) -> bool:
    """Speak via Android's system TTS engine.

    The voice data for each language must be downloaded once, in
    Settings -> General management -> Text-to-speech. Without the Greek pack
    installed it will either read Greek with an English voice (comically bad)
    or say nothing.
    """
    if not available("android"):
        log.warning("termux-tts-speak not found; install termux-api")
        return False

    locale = config.get_path("speech.voices", {}).get(language, "en-GB")
    subprocess.run(["termux-tts-speak", "-l", locale, text],
                   capture_output=True, timeout=30, check=False)
    return True


def _speak_piper(text: str, language: str, config) -> bool:
    """Speak via Piper, piping raw audio straight into the ALSA player.

    Piper writes 22.05 kHz signed 16-bit mono, hence the aplay flags. Keeping
    it a pipe rather than a temp file avoids yet another thing that grows in
    your storage forever.
    """
    binary = config.expanded("speech.piper_bin")
    model = config.get_path("speech.piper_models", {}).get(language)
    if not model:
        log.warning("No Piper voice configured for %r; falling back to Android",
                    language)
        return _speak_android(text, language, config)

    import os
    model = os.path.expanduser(model)

    piper = subprocess.Popen([binary, "-m", model, "--output-raw"],
                             stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                             stderr=subprocess.DEVNULL)
    player = subprocess.Popen(["aplay", "-r", "22050", "-f", "S16_LE", "-t", "raw", "-"],
                              stdin=piper.stdout, stderr=subprocess.DEVNULL)
    if piper.stdout:
        piper.stdout.close()
    piper.communicate(text.encode("utf-8"), timeout=30)
    player.wait(timeout=30)
    return True
