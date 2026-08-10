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
    def test_sends_the_standard_android_intent(self, ctx):
        """No Tasker task: SET_TIMER is a platform intent any clock app answers."""
        key, fields = execute("timer.set", {"minutes": 12}, ctx)
        assert key == "timer.set"
        assert fields["minutes"] == 12

        command = ctx.commands[0]
        assert command[:4] == ("am", "start", "-a",
                               "android.intent.action.SET_TIMER")
        assert command[command.index("android.intent.extra.alarm.LENGTH") + 1] == "720"
        assert command[command.index("android.intent.extra.alarm.SKIP_UI") + 1] == "true"

    def test_no_duration_is_a_polite_failure(self, ctx):
        """A missing number usually means a Greek number word is unmapped."""
        key, _ = execute("timer.set", {"minutes": None}, ctx)
        assert key == "failed"
        assert ctx.commands == []


class TestVolume:
    """Percent in, stream steps out -- scaled to whatever the device reports."""

    STREAMS = json.dumps([
        {"stream": "alarm", "volume": 5, "max_volume": 7},
        {"stream": "music", "volume": 8, "max_volume": 15},
    ])

    def _ctx(self):
        return RecordingContext(responses={"termux-volume": self.STREAMS})

    def test_scales_percent_to_device_steps(self):
        ctx = self._ctx()
        key, fields = execute("volume.set", {"level": 40}, ctx)
        assert key == "volume.set"
        assert fields["level"] == 40
        assert ctx.commands[-1] == ("termux-volume", "music", "6")  # 40% of 15

    def test_reads_the_maximum_rather_than_assuming_it(self):
        """Step counts differ per device; 100% must mean the real maximum."""
        ctx = RecordingContext(responses={
            "termux-volume": json.dumps(
                [{"stream": "music", "volume": 1, "max_volume": 30}])
        })
        execute("volume.set", {"level": 100}, ctx)
        assert ctx.commands[-1] == ("termux-volume", "music", "30")

    def test_quiet_but_audible_never_rounds_to_silence(self):
        ctx = self._ctx()
        execute("volume.set", {"level": 2}, ctx)
        assert ctx.commands[-1] == ("termux-volume", "music", "1")

    def test_zero_really_is_zero(self):
        ctx = self._ctx()
        execute("volume.set", {"level": 0}, ctx)
        assert ctx.commands[-1] == ("termux-volume", "music", "0")

    def test_unreadable_output_still_sets_something(self):
        """A wrong-ish volume beats an error the user has to debug by voice."""
        ctx = RecordingContext(responses={"termux-volume": "not json"})
        key, _ = execute("volume.set", {"level": 100}, ctx)
        assert key == "volume.set"
        assert ctx.commands[-1] == ("termux-volume", "music", "15")

    def test_no_level_is_a_polite_failure(self, ctx):
        key, _ = execute("volume.set", {"level": None}, ctx)
        assert key == "failed"


class TestCalendar:
    """The one action that genuinely needs Tasker, plus the stale-answer trap."""

    def test_broadcasts_to_the_tasker_task(self, tmp_path, ctx):
        ctx.calendar_answer = str(tmp_path / "calendar.json")
        ctx.answer_timeout = 0.2
        execute("calendar.next", {}, ctx)

        command = ctx.commands[0]
        assert command[0] == "am"
        assert command[command.index("task_name") + 1] == "NisosAction"
        assert command[command.index("par1") + 1] == "calendar.next"

    def test_reads_the_answer_tasker_writes(self, tmp_path):
        """The answer has to appear *after* the broadcast, as Tasker does it."""
        answer = tmp_path / "calendar.json"

        class AnsweringContext(RecordingContext):
            def termux(self, *command):
                answer.write_text('{"summary": "Standup", "minutes": 25}',
                                  encoding="utf-8")
                return super().termux(*command)

        ctx = AnsweringContext()
        ctx.calendar_answer = str(answer)
        key, fields = execute("calendar.next", {}, ctx)
        assert key == "calendar.next"
        assert fields == {"summary": "Standup", "minutes": 25}

    def test_stale_answer_is_deleted_before_asking(self, tmp_path, ctx):
        """Yesterday's meeting reported as today's is worse than an error.

        A Tasker task that fails silently leaves the previous answer on disk;
        without deleting it first this returns instantly with stale data.
        """
        answer = tmp_path / "calendar.json"
        answer.write_text('{"summary": "Yesterday", "minutes": 5}',
                          encoding="utf-8")
        ctx.calendar_answer = str(answer)
        ctx.answer_timeout = 0.2

        key, _ = execute("calendar.next", {}, ctx)
        assert key == "failed"
        assert not answer.exists()

    def test_missing_tasker_task_says_so(self, tmp_path):
        ctx = RecordingContext()
        ctx.calendar_answer = str(tmp_path / "nope.json")
        ctx.answer_timeout = 0.2
        key, _ = execute("calendar.next", {}, ctx)
        assert key == "failed"

    def test_answer_path_is_on_shared_storage(self):
        """Tasker cannot write into Termux's private app data. See the constant."""
        from nisos.actions import CALENDAR_ANSWER
        assert not CALENDAR_ANSWER.startswith("~")
        assert "com.termux" not in CALENDAR_ANSWER


class TestCalendarAdd:
    """Writing an appointment -- the other half of the calendar bridge."""

    @staticmethod
    def _answering(tmp_path, reply):
        """A context whose Tasker broadcast writes `reply`, as Tasker does."""
        answer = tmp_path / "calendar.json"

        class AnsweringContext(RecordingContext):
            def termux(self, *command):
                answer.write_text(json.dumps(reply), encoding="utf-8")
                return super().termux(*command)

        ctx = AnsweringContext()
        ctx.calendar_answer = str(answer)
        ctx.answer_timeout = 0.3
        return ctx

    def test_sends_the_appointment_to_tasker(self, tmp_path):
        ctx = self._answering(tmp_path, {"ok": True})
        key, fields = execute("calendar.add", {
            "summary": "Οδοντίατρος",
            "start": "2026-08-11T17:00",
            "minutes": 30,
        }, ctx)

        assert key == "calendar.add"
        command = ctx.commands[0]
        assert command[command.index("par1") + 1] == "calendar.add"
        payload = json.loads(command[command.index("par2") + 1])
        assert payload["summary"] == "Οδοντίατρος"
        assert payload["minutes"] == 30
        # Milliseconds since the epoch, so no timezone can be argued about on
        # the far side of the bridge.
        assert payload["start_ms"] == int(
            __import__("datetime").datetime(2026, 8, 11, 17, 0).timestamp() * 1000)

    def test_reads_the_appointment_back(self, tmp_path):
        """The day it landed on and the hour it picked are the two things most
        likely to be wrong, and invisible until you open the calendar."""
        ctx = self._answering(tmp_path, {"ok": True})
        _, fields = execute("calendar.add", {
            "summary": "Dentist", "start": "2026-08-11T17:00"}, ctx)
        assert fields["date"] == "11/08"
        assert fields["time"] == "17:00"
        assert replies.say("calendar.add", "en", **fields) == \
            "Dentist, 11/08 at 17:00."

    def test_an_older_tasker_task_is_not_mistaken_for_success(self, tmp_path):
        """A NisosAction that has never heard of calendar.add falls into its
        own else-branch and writes a perfectly well-formed answer saying it
        did nothing. Requiring `ok` is what makes that audible."""
        ctx = self._answering(tmp_path, {"summary": "nothing", "minutes": 0})
        key, _ = execute("calendar.add", {
            "summary": "Dentist", "start": "2026-08-11T17:00"}, ctx)
        assert key == "failed"

    def test_no_time_is_a_polite_failure_rather_than_a_guess(self, ctx):
        """Guessing an hour for something that goes in a diary is worse than
        admitting you missed it."""
        key, _ = execute("calendar.add", {"summary": "Dentist"}, ctx)
        assert key == "failed"
        assert ctx.commands == []

    def test_an_unreadable_time_never_reaches_tasker(self, ctx):
        key, _ = execute("calendar.add", {
            "summary": "Dentist", "start": "sometime next week"}, ctx)
        assert key == "failed"
        assert ctx.commands == []

    def test_a_model_written_time_with_a_space_is_accepted(self, tmp_path):
        """What a model reaches for when nobody is watching."""
        ctx = self._answering(tmp_path, {"ok": True})
        key, fields = execute("calendar.add", {
            "summary": "Dentist", "start": "2026-08-11 17:00:00"}, ctx)
        assert key == "calendar.add"
        assert fields["time"] == "17:00"

    def test_stale_answers_cannot_confirm_a_write_that_never_happened(
            self, tmp_path, ctx):
        answer = tmp_path / "calendar.json"
        answer.write_text('{"ok": true}', encoding="utf-8")
        ctx.calendar_answer = str(answer)
        ctx.answer_timeout = 0.2

        key, _ = execute("calendar.add", {
            "summary": "Dentist", "start": "2026-08-11T17:00"}, ctx)
        assert key == "failed"
        assert not answer.exists()


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
