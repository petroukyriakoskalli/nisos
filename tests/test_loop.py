"""End-to-end tests of one turn, with the phone stubbed out.

These prove the thing that actually matters: a Greek sentence in produces a
Greek sentence out, with an English action name in between. If that invariant
breaks, the design has broken.
"""

import pytest

from nisos import config as config_module
from nisos import loop, speech
from tests.test_actions import RecordingContext


@pytest.fixture
def cfg():
    return config_module.Config(config_module.DEFAULTS.copy())


@pytest.fixture(autouse=True)
def silence(monkeypatch):
    """Stop tests from trying to talk. Records what would have been said."""
    said: list[tuple[str, str]] = []
    monkeypatch.setattr(speech, "speak",
                        lambda text, language, config: said.append((language, text)) or True)
    return said


class TestRoutedTurns:
    def test_greek_in_greek_out(self, cfg, silence):
        turn = loop.handle("άναψε τον φακό", cfg, RecordingContext())
        assert turn.path == "routed"
        assert turn.language == "el"
        assert turn.action == "torch.on"
        assert turn.spoken == "Άναψα τον φακό."
        assert silence[-1][0] == "el"

    def test_english_in_english_out(self, cfg, silence):
        turn = loop.handle("torch on", cfg, RecordingContext())
        assert turn.language == "en"
        assert turn.action == "torch.on"
        assert turn.spoken == "Torch on."

    def test_action_names_stay_english_in_greek(self, cfg, silence):
        """The load-bearing design decision, asserted."""
        turn = loop.handle("βάλε χρονόμετρο δώδεκα λεπτά", cfg, RecordingContext())
        assert turn.action == "timer.set"       # English
        assert "λεπτά" in turn.spoken           # Greek

    def test_routed_turns_never_call_the_model(self, cfg, monkeypatch, silence):
        def fail(*args, **kwargs):
            raise AssertionError("the model was woken for a routed phrase")

        monkeypatch.setattr(loop.brain, "think", fail)
        loop.handle("άναψε τον φακό", cfg, RecordingContext())


class TestTurnsThatDoTwoThings:
    """The README's own headline demo, which used to do half of what it says.

    «βάλε χρονόμετρο δώδεκα λεπτά και άναψε τον φακό» lit the torch and
    dropped the timer in silence.
    """

    def test_both_actions_actually_run(self, cfg, silence):
        ctx = RecordingContext()
        turn = loop.handle("βάλε χρονόμετρο δώδεκα λεπτά και άναψε τον φακό",
                           cfg, ctx)
        assert [s.action for s in turn.steps] == ["timer.set", "torch.on"]
        assert ("termux-torch", "on") in ctx.commands
        assert any("SET_TIMER" in " ".join(c) for c in ctx.commands)

    def test_one_reply_covers_both(self, cfg, silence):
        """Two calls to the speech engine means two utterances, and on Android
        the second routinely lands on top of the first."""
        turn = loop.handle("βάλε χρονόμετρο δώδεκα λεπτά και άναψε τον φακό",
                           cfg, RecordingContext())
        assert turn.spoken == "12 λεπτά, ξεκίνησα. Άναψα τον φακό."
        assert len(silence) == 1

    def test_a_failing_step_does_not_cancel_the_others(self, cfg, silence):
        """They were separate requests. The reply says which half worked."""
        ctx = RecordingContext()
        turn = loop.handle("set a timer and torch on", cfg, ctx)
        assert [s.action for s in turn.steps] == ["timer.set", "torch.on"]
        assert ("termux-torch", "on") in ctx.commands   # ran despite the timer
        assert "That didn't work." in turn.spoken
        assert "Torch on." in turn.spoken

    def test_the_log_line_names_every_action(self, cfg, silence):
        """A log showing only the first is how a dropped second stays
        invisible -- which is exactly how this went unnoticed."""
        turn = loop.handle("torch on and what time is it", cfg,
                           RecordingContext())
        assert "torch.on + time.read" in turn.summary()

    def test_a_reasoned_plan_runs_in_order(self, cfg, monkeypatch, silence):
        from nisos.brain import Decision, Step

        monkeypatch.setattr(
            loop.brain, "think",
            lambda text, language, actions, config, **kw:
                Decision.from_steps([Step("torch.on", {}),
                                     Step("answer", {"text": "Έγινε."})], 1.4),
        )
        turn = loop.handle("κάνε τα δύο πράγματα", cfg, RecordingContext(),
                           language_hint="el")
        assert [s.action for s in turn.steps] == ["torch.on", "answer"]
        assert turn.spoken == "Άναψα τον φακό. Έγινε."

    def test_a_one_action_turn_sounds_exactly_as_it_always_did(self, cfg,
                                                               silence):
        """The stitching must be invisible on the common path."""
        assert loop.handle("torch on", cfg, RecordingContext()).spoken == "Torch on."


class TestReasonedTurns:
    def test_falls_through_to_the_model(self, cfg, monkeypatch, silence):
        from nisos.brain import Decision

        monkeypatch.setattr(
            loop.brain, "think",
            lambda text, language, actions, config, **kw:
                Decision("answer", {"text": "Το Τόκιο."}, 1.4),
        )
        turn = loop.handle("ποια είναι η πρωτεύουσα της Ιαπωνίας", cfg,
                           RecordingContext(), language_hint="el")
        assert turn.path == "reasoned"
        assert turn.spoken == "Το Τόκιο."

    @staticmethod
    def _brain_raises(monkeypatch):
        from nisos.brain import BrainError

        def down(*args, **kwargs):
            raise BrainError("llama-server unreachable")

        monkeypatch.setattr(loop.brain, "think", down)

    def test_a_stopped_model_says_so_rather_than_blaming_the_network(
            self, cfg, monkeypatch, silence):
        """The normal case in app mode, and it used to lie about the reason.

        Saying "can't do that offline" when llama-server merely is not running
        sends you hunting for a network fault in a program whose entire point
        is not having one.
        """
        self._brain_raises(monkeypatch)
        monkeypatch.setattr(loop.brain, "available", lambda *a, **k: False)
        turn = loop.handle("summarise this", cfg, RecordingContext(),
                           language_hint="el")
        assert turn.action == "no_model"
        assert "μοντέλο" in turn.spoken

    def test_a_reachable_model_that_failed_still_apologises_in_greek(
            self, cfg, monkeypatch, silence):
        self._brain_raises(monkeypatch)
        monkeypatch.setattr(loop.brain, "available", lambda *a, **k: True)
        turn = loop.handle("summarise this", cfg, RecordingContext(),
                           language_hint="el")
        assert turn.action == "unavailable"
        assert turn.spoken == "Αυτό δεν γίνεται χωρίς σύνδεση."

    @staticmethod
    def _brain_raises_with(monkeypatch, reply_key):
        from nisos.brain import BrainError

        def down(*args, **kwargs):
            raise BrainError("nope", reply_key=reply_key)

        monkeypatch.setattr(loop.brain, "think", down)

    def test_a_missing_key_blames_the_key_not_the_network(self, cfg, monkeypatch,
                                                         silence):
        """Four failures used to share one apology. This is the online one:
        nothing is down, there is simply nothing to authenticate with."""
        self._brain_raises_with(monkeypatch, "no_key")
        turn = loop.handle("summarise this", cfg, RecordingContext(),
                           language_hint="el")
        assert turn.action == "no_key"
        assert "κλειδί" in turn.spoken

    def test_a_refusal_is_not_dressed_up_as_a_fault(self, cfg, monkeypatch,
                                                    silence):
        self._brain_raises_with(monkeypatch, "refused")
        turn = loop.handle("summarise this", cfg, RecordingContext(),
                           language_hint="el")
        assert turn.action == "refused"
        assert turn.spoken == "Το μοντέλο αρνήθηκε να απαντήσει."

    def test_a_keyed_failure_never_probes_llama(self, cfg, monkeypatch, silence):
        """The probe is a 1s HTTP timeout. Paying it to answer a question we
        already know the answer to would add a second of silence to a failure."""
        self._brain_raises_with(monkeypatch, "no_key")

        def fail(*args, **kwargs):
            raise AssertionError("probed llama-server for a known failure")

        monkeypatch.setattr(loop.brain, "available", fail)
        loop.handle("summarise this", cfg, RecordingContext())

    def test_the_log_line_says_which_brain_answered(self, cfg, monkeypatch,
                                                    silence):
        from nisos.brain import Decision

        monkeypatch.setattr(
            loop.brain, "think",
            lambda text, language, actions, config, **kw:
                Decision("answer", {"text": "Το Τόκιο."}, 1.4, backend="claude"),
        )
        turn = loop.handle("ποια είναι η πρωτεύουσα της Ιαπωνίας", cfg,
                           RecordingContext(), language_hint="el")
        assert turn.backend == "claude"
        assert "reasoned:claude" in turn.summary()


class TestTiming:
    def test_routed_turn_records_its_stages(self, cfg, silence):
        turn = loop.handle("torch on", cfg, RecordingContext())
        assert "route" in turn.timings
        assert "exec" in turn.timings
        assert "model" not in turn.timings

    def test_summary_is_one_line(self, cfg, silence):
        turn = loop.handle("torch on", cfg, RecordingContext())
        assert "\n" not in turn.summary()
        assert "routed" in turn.summary()
