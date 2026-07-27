"""Tests for the action layer, the reply templates, and the grammar.

Nothing here touches a phone. :class:`RecordingContext` substitutes for
:class:`nisos.actions.ExecutionContext` and simply remembers what it was asked
to run, which is enough to check that the right command would have been fired
with the right arguments.
"""

import json
from pathlib import Path

import pytest

from nisos import replies
from nisos.actions import (REGISTRY, ActionError, ExecutionContext,
                           action_names, execute)


class RecordingContext(ExecutionContext):
    """An execution context that records commands instead of running them.

    Extend this rather than mocking subprocess: it keeps the tests readable and
    means a handler that starts shelling out directly, bypassing the context,
    fails loudly.
    """

    def __init__(self, responses=None, **kwargs):
        super().__init__(**kwargs)
        self.commands: list[tuple[str, ...]] = []
        self.responses = responses or {}

    def termux(self, *command: str) -> str:
        self.commands.append(command)
        return self.responses.get(command[0], "")


@pytest.fixture
def ctx():
    return RecordingContext(contacts={"αννα": "Anna"})


class TestTorch:
    def test_on(self, ctx):
        key, fields = execute("torch.on", {}, ctx)
        assert key == "torch.on"
        assert ctx.commands == [("termux-torch", "on")]

    def test_off(self, ctx):
        execute("torch.off", {}, ctx)
        assert ctx.commands == [("termux-torch", "off")]


class TestTimer:
    def test_sets_via_tasker(self, ctx):
        key, fields = execute("timer.set", {"minutes": 12}, ctx)
        assert key == "timer.set"
        assert fields["minutes"] == 12
        assert ctx.commands[0][0] == "am"
        payload = json.loads(ctx.commands[0][ctx.commands[0].index("par2") + 1])
        assert payload == {"minutes": 12}

    def test_no_duration_is_a_polite_failure(self, ctx):
        """A missing number usually means a Greek number word is unmapped."""
        key, _ = execute("timer.set", {"minutes": None}, ctx)
        assert key == "failed"
        assert ctx.commands == []


class TestBattery:
    def test_parses_termux_json(self):
        ctx = RecordingContext(responses={
            "termux-battery-status":
                '{"percentage": 78.4, "status": "CHARGING"}'
        })
        key, fields = execute("battery.read", {}, ctx)
        assert key == "battery.read"
        assert fields["percent"] == 78
        assert fields["status"] == "charging"


class TestSms:
    def test_resolves_contact_alias(self, ctx):
        """The fix for code-switching: «αννα» must reach the real contact."""
        key, fields = execute("sms.send", {"to": "αννα", "body": "άργησα"}, ctx)
        assert key == "sms.send"
        assert fields["to"] == "Anna"
        assert ctx.commands == [("termux-sms-send", "-n", "Anna", "άργησα")]

    def test_passes_through_unknown_names(self, ctx):
        _, fields = execute("sms.send", {"to": "Kostas", "body": "hi"}, ctx)
        assert fields["to"] == "Kostas"

    def test_empty_body_refuses(self, ctx):
        key, _ = execute("sms.send", {"to": "Anna", "body": ""}, ctx)
        assert key == "failed"


class TestDispatch:
    def test_unknown_action_is_unclear_not_a_crash(self, ctx):
        key, _ = execute("teleport.me", {}, ctx)
        assert key == "unclear"

    def test_handler_exception_becomes_failed(self, ctx):
        def explode(args, context):
            raise RuntimeError("boom")

        REGISTRY["test.explode"] = explode
        try:
            key, _ = execute("test.explode", {}, ctx)
            assert key == "failed"
        finally:
            del REGISTRY["test.explode"]

    def test_dry_run_performs_nothing(self):
        ctx = ExecutionContext(dry_run=True)
        key, _ = execute("torch.on", {}, ctx)
        assert key == "torch.on"  # reports success without touching the torch


class TestReplies:
    def test_greek_and_english_differ(self):
        assert replies.say("torch.on", "el") != replies.say("torch.on", "en")

    def test_fills_placeholders(self):
        assert replies.say("timer.set", "en", minutes=12) == "12 minutes, counting."
        assert "12" in replies.say("timer.set", "el", minutes=12)

    def test_unknown_language_falls_back_to_english(self):
        assert replies.say("torch.on", "fr") == replies.say("torch.on", "en")

    def test_missing_placeholder_does_not_raise(self):
        """Speaking a clumsy sentence beats crashing after the action ran."""
        spoken = replies.say("timer.set", "en")
        assert "{" not in spoken

    def test_unknown_action_does_not_raise(self):
        assert replies.say("nope.nope", "en")

    def test_every_action_has_both_languages(self):
        """Adding an action without a Greek phrase should fail here, not on the phone."""
        gaps = replies.missing_replies(action_names())
        assert gaps == [], f"Missing reply templates: {gaps}"


class TestGrammar:
    """The grammar and the registry must list the same actions.

    If the grammar allows an action the registry does not have, the model can
    pick something that then falls through to "unclear". If the registry has
    one the grammar forbids, the model can never choose it and you will wonder
    why it is ignoring you.
    """

    @staticmethod
    def _grammar_verbs() -> set[str]:
        """Pull the alternatives out of the `verb` rule.

        Anchored to the start of a line so it finds the rule definition rather
        than the first mention of "verb" inside the `root` rule above it.
        """
        import re
        path = Path(__file__).resolve().parent.parent / "grammar" / "action.gbnf"
        text = path.read_text(encoding="utf-8")
        block = re.search(r"^verb\s*::=(.*?)(?=^\S+\s*::=)", text,
                          re.MULTILINE | re.DOTALL)
        assert block, "no `verb` rule found in action.gbnf"
        return set(re.findall(r'\\"([a-z.]+)\\"', block.group(1)))

    def test_grammar_file_exists(self):
        path = Path(__file__).resolve().parent.parent / "grammar" / "action.gbnf"
        assert path.is_file()

    def test_grammar_matches_registry(self):
        assert self._grammar_verbs() == set(action_names())
