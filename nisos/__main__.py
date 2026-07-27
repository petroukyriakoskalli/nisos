"""Command line entry point: ``python -m nisos``.

Three ways to run it:

* ``python -m nisos`` -- record once, act, exit. This is what Tasker calls.
* ``python -m nisos --listen`` -- stay resident, act on every Enter press.
* ``python -m nisos --text "άναψε τον φακό"`` -- skip the microphone entirely.

That last one is the important one. It means the whole pipeline -- routing,
language detection, actions, replies -- can be developed and tested on a
laptop, and only the audio ends up being phone-specific. Pair it with
``--dry-run`` to see what would happen without anything actually happening.
"""

from __future__ import annotations

import argparse
import logging
import sys
from pathlib import Path

from . import actions as actions_module
from . import brain, config as config_module, loop, replies, speech, stt


def build_parser() -> argparse.ArgumentParser:
    """Define the command line. Kept separate so tests can introspect it."""
    parser = argparse.ArgumentParser(
        prog="nisos",
        description="Offline bilingual voice assistant (Greek + English).",
    )
    parser.add_argument("--text", metavar="PHRASE",
                        help="skip the microphone and process this instead")
    parser.add_argument("--listen", action="store_true",
                        help="stay resident and listen repeatedly")
    parser.add_argument("--dry-run", action="store_true",
                        help="log actions instead of performing them")
    parser.add_argument("--config", metavar="PATH",
                        help="path to config.toml")
    parser.add_argument("--check", action="store_true",
                        help="report what is installed and reachable, then exit")
    parser.add_argument("--actions", action="store_true",
                        help="list every registered action, then exit")
    parser.add_argument("-v", "--verbose", action="store_true",
                        help="debug logging")
    return parser


def setup_logging(config, verbose: bool) -> None:
    """Log to stderr and, if configured, to a file.

    The file is what you read after the fact, since a Tasker-launched run has
    no terminal to print to. Keep it truncated -- see ``scripts/postbuild.sh``
    for the nightly trim, because an unbounded log is one of the three things
    that quietly fills a phone.
    """
    level = logging.DEBUG if verbose else logging.INFO
    handlers: list[logging.Handler] = [logging.StreamHandler(sys.stderr)]

    log_path = config.expanded("general.log_file")
    if log_path:
        try:
            Path(log_path).parent.mkdir(parents=True, exist_ok=True)
            handlers.append(logging.FileHandler(log_path, encoding="utf-8"))
        except OSError:
            pass  # a missing log file must never stop the assistant

    logging.basicConfig(
        level=level,
        format="%(asctime)s  %(levelname).1s  %(name)-14s  %(message)s",
        datefmt="%H:%M:%S",
        handlers=handlers,
        force=True,
    )


def report_check(config) -> int:
    """Print what is present and what is missing. Returns a shell exit code.

    Written to be the first thing you run after installing, and the first thing
    you run when something has stopped working.
    """
    rows: list[tuple[str, bool, str]] = [
        ("Termux:API (microphone)", __import__("nisos.audio", fromlist=["x"]).available(),
         "pkg install termux-api + the F-Droid app"),
        ("Android recogniser", _binary("termux-speech-to-text"),
         "pkg install termux-api"),
        ("Android TTS", speech.available("android"),
         "pkg install termux-api"),
        ("whisper-cli", _path_or_binary(config.expanded("stt.whisper_bin")),
         "build whisper.cpp -- see scripts/install.sh"),
        ("Whisper weights (multilingual)",
         _file(config.expanded("stt.whisper_model")),
         "download-ggml-model.sh small-q5_1  (NOT small.en)"),
        ("llama-server", brain.available(config.get_path("brain.url")),
         "start it -- see README"),
        ("Grammar file",
         brain.load_grammar(config.get_path("brain.grammar")) is not None,
         "grammar/action.gbnf is missing from the checkout"),
    ]

    width = max(len(name) for name, _, _ in rows)
    print()
    for name, ok, remedy in rows:
        mark = "OK  " if ok else "MISS"
        line = f"  [{mark}] {name.ljust(width)}"
        print(line if ok else f"{line}   -> {remedy}")

    gaps = replies.missing_replies(actions_module.action_names())
    if gaps:
        print("\n  Actions with no reply template:")
        for action_name, language in gaps:
            print(f"    {action_name} ({language})")

    print()
    return 0 if all(ok for _, ok, _ in rows) else 1


def _binary(name: str) -> bool:
    """True if `name` is on PATH."""
    import shutil
    return shutil.which(name) is not None


def _file(path: str) -> bool:
    """True if `path` is an existing file."""
    return bool(path) and Path(path).expanduser().is_file()


def _path_or_binary(path: str) -> bool:
    """True if `path` is an executable file or a name on PATH."""
    return _file(path) or _binary(path)


def main(argv: list[str] | None = None) -> int:
    """Run the CLI. Returns a shell exit code."""
    args = build_parser().parse_args(argv)
    config = config_module.load(args.config)

    if args.dry_run:
        config.setdefault("general", {})["dry_run"] = True

    setup_logging(config, args.verbose)

    if args.actions:
        for name in actions_module.action_names():
            handler = actions_module.REGISTRY[name]
            summary = (handler.__doc__ or "").strip().split("\n")[0]
            print(f"  {name.ljust(16)} {summary}")
        return 0

    if args.check:
        return report_check(config)

    context = loop.build_context(config)

    if args.text:
        turn = loop.handle(args.text, config, context)
        print(turn.spoken)
        return 0

    if args.listen:
        print("Listening. Enter to speak, Ctrl-C to stop.")
        try:
            while True:
                input()
                turn = loop.listen_and_handle(config, context)
                print(f"  {turn.spoken}")
        except KeyboardInterrupt:
            return 0

    turn = loop.listen_and_handle(config, context)
    print(turn.spoken)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
