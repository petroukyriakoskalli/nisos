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

    def test_unreachable_model_apologises_in_the_right_language(
            self, cfg, monkeypatch, silence):
        from nisos.brain import BrainError

        def down(*args, **kwargs):
            raise BrainError("llama-server unreachable")

        monkeypatch.setattr(loop.brain, "think", down)
        turn = loop.handle("summarise this", cfg, RecordingContext(),
                           language_hint="el")
        assert turn.action == "unavailable"
        assert turn.spoken == "Αυτό δεν γίνεται χωρίς σύνδεση."


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
