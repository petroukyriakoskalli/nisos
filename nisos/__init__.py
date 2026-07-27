"""nisos -- an offline, bilingual (Greek + English) voice assistant for Android.

Runs entirely on the phone: no network, no API keys, no account. Built for a
Galaxy S25 Ultra in Termux, but nothing here is Samsung-specific beyond the
back-tap trigger.

The design in one paragraph: a keyword router handles the ~80% of commands that
are one of a few dozen phrases, in about five milliseconds, and a 4B language
model handles the rest in about a second and a half. Because Greek and English
share no characters, the router doubles as the language detector for free --
whichever table matches tells you which language you just spoke.

Layout
------
============================  =====================================================
:mod:`nisos.normalise`        Accent stripping, final sigma, number words
:mod:`nisos.router`           The two regex tables; the fast path
:mod:`nisos.actions`          What it can do, and how it reaches Android
:mod:`nisos.replies`          What it says back, in both languages
:mod:`nisos.audio`            Recording
:mod:`nisos.stt`              Racing Android's recogniser against Whisper
:mod:`nisos.brain`            llama-server client, grammar-constrained
:mod:`nisos.speech`           Text to speech
:mod:`nisos.loop`             Orchestration -- the only file that knows the order
:mod:`nisos.config`           TOML settings
============================  =====================================================

See EXTENDING.md for how to add a command.
"""

__version__ = "0.2.5"
__all__ = ["__version__"]
