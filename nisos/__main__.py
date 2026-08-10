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
import os
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
    parser.add_argument("--backend", choices=brain.BACKENDS,
                        help="override brain.backend for this run")
    parser.add_argument("--set-key", action="store_true",
                        help="read an Anthropic API key from stdin and store "
                             "it with 0600 permissions, then exit")
    parser.add_argument("--forget-key", action="store_true",
                        help="delete the stored Anthropic API key, then exit")
    parser.add_argument("--print-backend", action="store_true",
                        help="print which brain a turn would use "
                             "(claude or llama), then exit")
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

    Only checks what the current configuration actually needs. That is the
    point: on an online-only install there is no 2.5 GB model to find and no
    grammar to load, and a report that keeps demanding them would fail forever
    and teach you to ignore it. Rows marked ``opt`` are useful-if-present and
    do not affect the exit code -- llama-server is one of those when the online
    brain is in charge, because it is then only the fallback for a lost network.
    """
    from . import cloud

    backend = brain.backend_for(config)
    configured = str(config.get_path("brain.backend", "auto"))
    model = config.get_path("brain.cloud.model", "claude-opus-5")
    uses_whisper = str(config.get_path("stt.strategy", "race")) != "android"

    print()
    print(f"  brain: {backend}"
          + (f"  ({model})" if backend == "claude" else "  (llama-server)")
          + (f"   [brain.backend = {configured}]" if configured != backend else ""))

    # (name, ok, remedy, required)
    rows: list[tuple[str, bool, str, bool]] = [
        ("Termux:API (microphone)",
         __import__("nisos.audio", fromlist=["x"]).available(),
         "pkg install termux-api + the F-Droid app", True),
        ("Android recogniser", _binary("termux-speech-to-text"),
         "pkg install termux-api", True),
        ("Android TTS", speech.available("android"),
         "pkg install termux-api", True),
    ]

    if uses_whisper:
        rows += [
            ("whisper-cli", _path_or_binary(config.expanded("stt.whisper_bin")),
             "build whisper.cpp -- see scripts/install.sh", True),
            ("Whisper weights (multilingual)",
             _file(config.expanded("stt.whisper_model")),
             "download-ggml-model.sh small-q5_1  (NOT small.en)", True),
        ]

    if backend == "claude":
        rows += [
            ("Anthropic API key", cloud.available(config),
             f"put one in {config.expanded('brain.cloud.key_file')} "
             f"(python -m nisos --set-key), or set ANTHROPIC_API_KEY", True),
            (f"Claude API reachable ({model})", cloud.reachable(config),
             "checks the key, the network and the model name -- "
             "see the log line above for which of them it was", True),
            # Only the safety net now, so a miss is not a failure.
            ("llama-server (offline fallback)",
             brain.available(config.get_path("brain.url")),
             "optional while online -- start it to keep working without a "
             "network", False),
        ]
    else:
        rows += [
            ("llama-server", brain.available(config.get_path("brain.url")),
             "start it -- see README", True),
            ("Grammar file",
             brain.load_grammar(config.get_path("brain.grammar")) is not None,
             "grammar/action.gbnf is missing from the checkout", True),
            ("Anthropic API key (online brain)", cloud.available(config),
             "optional -- add one and set brain.backend = \"auto\" to use the "
             "API instead", False),
        ]

    width = max(len(name) for name, _, _, _ in rows)
    print()
    for name, ok, remedy, required in rows:
        mark = "OK  " if ok else ("MISS" if required else "opt ")
        line = f"  [{mark}] {name.ljust(width)}"
        print(line if ok else f"{line}   -> {remedy}")

    gaps = replies.missing_replies(actions_module.action_names())
    if gaps:
        print("\n  Actions with no reply template:")
        for action_name, language in gaps:
            print(f"    {action_name} ({language})")

    print()
    return 0 if all(ok for _, ok, _, required in rows if required) else 1


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


def manage_key(config, forget: bool) -> int:
    """Store or delete the API key. Returns a shell exit code.

    The key is read from **stdin**, never from an argument. Anything on a
    command line is visible in ``ps`` and lands in the shell history file, and
    a credential that leaks that easily may as well be in the config.
    """
    path = config.expanded("brain.cloud.key_file")
    if not path:
        print("  brain.cloud.key_file is empty -- nowhere to put a key.")
        return 1

    if forget:
        target = Path(path)
        if target.exists():
            target.unlink()
            print(f"  removed {target}")
        else:
            print(f"  nothing stored at {target}")
        if os.environ.get("ANTHROPIC_API_KEY"):
            print("  note: ANTHROPIC_API_KEY is still set in this environment "
                  "and wins over the file.")
        return 0

    from . import cloud

    if sys.stdin.isatty():
        print("  Paste the key, then Enter:")
    try:
        key = sys.stdin.readline()
    except KeyboardInterrupt:  # pragma: no cover -- interactive only
        return 1

    try:
        target = cloud.store_key(key, path)
    except ValueError:
        print("  no key given -- nothing written")
        return 1
    except OSError as exc:
        print(f"  could not write {path}: {exc}")
        return 1

    print(f"  stored in {target} (0600)")
    print("  checking it...")
    if cloud.reachable(config):
        print(f"  OK -- {config.get_path('brain.cloud.model')} is reachable.")
        return 0
    print("  the key was stored but the check failed -- see the warning above "
          "for whether that was the key, the network or the model name.")
    return 1


def main(argv: list[str] | None = None) -> int:
    """Run the CLI. Returns a shell exit code."""
    args = build_parser().parse_args(argv)
    config = config_module.load(args.config)

    if args.dry_run:
        config.setdefault("general", {})["dry_run"] = True
    if args.backend:
        config.setdefault("brain", {})["backend"] = args.backend

    setup_logging(config, args.verbose)

    if args.set_key or args.forget_key:
        return manage_key(config, forget=args.forget_key)

    if args.print_backend:
        # One word on stdout, for scripts/nisos.sh to read. It uses this to
        # decide whether to spend nine seconds loading a local model, so the
        # answer has to come from the real config loader rather than a grep.
        print(brain.backend_for(config))
        return 0

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
