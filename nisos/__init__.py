"""nisos -- a bilingual (Greek + English) voice assistant for Android.

Everything you say is heard and acted on **on the phone**. The one part that can
leave it is reasoning: since v0.3.0 a phrase the router misses can be sent to
the Claude API instead of a local 4B model, which answers far better and speaks
much better Greek, but is a network call with a key and a per-turn cost.
``brain.backend`` chooses, and ``llama`` keeps the original
everything-on-the-device behaviour. Either way no model of any kind is involved
in the ~80% of commands the router handles. Built for a Galaxy S25 Ultra in
Termux, but nothing here is Samsung-specific beyond the back-tap trigger.

The design in one paragraph: a keyword router handles the ~80% of commands that
are one of a few dozen phrases, in about five milliseconds, and a language model
handles the rest -- about a second and a half on the phone, or a network round
trip online. Because Greek and English share no characters, the router doubles
as the language detector for free -- whichever table matches tells you which
language you just spoke.

Layout
------
============================  =====================================================
:mod:`nisos.normalise`        Accent stripping, final sigma, number words
:mod:`nisos.router`           The two regex tables; the fast path
:mod:`nisos.actions`          What it can do, and how it reaches Android
:mod:`nisos.replies`          What it says back, in both languages
:mod:`nisos.audio`            Recording
:mod:`nisos.stt`              Racing Android's recogniser against Whisper
:mod:`nisos.brain`            Which brain answers, and the llama-server client
:mod:`nisos.cloud`            The Claude API client, forced tool call
:mod:`nisos.speech`           Text to speech
:mod:`nisos.loop`             Orchestration -- the only file that knows the order
:mod:`nisos.config`           TOML settings
============================  =====================================================

See EXTENDING.md for how to add a command.
"""

__version__ = "0.3.0"
__all__ = ["__version__"]
