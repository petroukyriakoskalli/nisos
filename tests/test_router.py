"""Tests for the bilingual router.

Two things matter here and nothing else does:

1. Common phrases in both languages hit the right action.
2. The language that comes back is correct -- because the rest of the program
   trusts it completely, and a wrong answer means a Greek reply to an English
   question.
"""

import pytest

from nisos.actions import action_names
from nisos.router import ROUTES, route


class TestEnglishRouting:
    @pytest.mark.parametrize("phrase,expected", [
        ("torch on", "torch.on"),
        ("torch off", "torch.off"),
        ("turn on the torch", "torch.on"),
        ("turn off the flashlight", "torch.off"),
        ("set a timer for 12 minutes", "timer.set"),
        ("remind me in 5 minutes", "timer.set"),
        ("what's my battery", "battery.read"),
        ("silence everything", "dnd.on"),
        ("what time is it", "time.read"),
        ("next meeting", "calendar.next"),
    ])
    def test_hits(self, phrase, expected):
        match = route(phrase)
        assert match is not None, f"{phrase!r} routed to nothing"
        assert match.action == expected
        assert match.language == "en"


class TestGreekRouting:
    @pytest.mark.parametrize("phrase,expected", [
        ("άναψε τον φακό", "torch.on"),
        ("ανάψτε τον φακό", "torch.on"),
        ("να ανάψεις τον φακό", "torch.on"),
        ("σβήσε τον φακό", "torch.off"),
        ("κλείσε τον φακό", "torch.off"),
        ("βάλε χρονόμετρο δώδεκα λεπτά", "timer.set"),
        ("θύμισέ μου σε δέκα λεπτά", "timer.set"),
        ("πόση μπαταρία έχω", "battery.read"),
        ("σίγαση", "dnd.on"),
        ("τι ώρα είναι", "time.read"),
        ("πότε είναι το ραντεβού μου", "calendar.next"),
    ])
    def test_hits(self, phrase, expected):
        match = route(phrase)
        assert match is not None, f"{phrase!r} routed to nothing"
        assert match.action == expected
        assert match.language == "el"

    def test_inflection_variants_agree(self):
        """The three ways to say "turn it on" must all reach the same action."""
        forms = ["άναψε τον φακό", "ανάψτε τον φακό", "να ανάψεις τον φακό"]
        actions = {route(f).action for f in forms}
        assert actions == {"torch.on"}

    def test_works_without_accents(self):
        """Recognisers drop accents unpredictably; both spellings must route."""
        assert route("αναψε τον φακο").action == "torch.on"
        assert route("άναψε τον φακό").action == "torch.on"


class TestLanguageDetection:
    """The load-bearing property: the alphabets do not overlap.

    This is what makes bilingual almost free. If these tests ever fail, the
    whole 'router doubles as language detector' design is unsound and the
    program needs real language ID.
    """

    def test_greek_never_matches_english_table(self):
        for phrase in ["άναψε τον φακό", "πόση μπαταρία έχω", "σίγαση"]:
            assert route(phrase).language == "el"

    def test_english_never_matches_greek_table(self):
        for phrase in ["torch on", "battery level", "silence everything"]:
            assert route(phrase).language == "en"

    def test_timer_arguments_parse_per_language(self):
        assert route("βάλε χρονόμετρο δώδεκα λεπτά").args["minutes"] == 12
        assert route("set a timer for 12 minutes").args["minutes"] == 12


class TestMisses:
    @pytest.mark.parametrize("phrase", [
        "ποια είναι η πρωτεύουσα της Ιαπωνίας",
        "summarise the note I just wrote",
        "",
        "   ",
    ])
    def test_returns_none(self, phrase):
        assert route(phrase) is None


class TestTablesStayInStep:
    def test_every_routed_action_exists(self):
        """A route pointing at an unregistered action is a silent dead end."""
        registered = set(action_names())
        for language, routes in ROUTES.items():
            for entry in routes:
                assert entry.action in registered, (
                    f"{language} route {entry.pattern!r} points at "
                    f"{entry.action!r}, which is not registered"
                )

    def test_both_languages_cover_the_same_actions(self):
        """Otherwise the assistant is cleverer in one language than the other.

        torch.off is reachable in English through the direction rewrite rather
        than its own route, so it is excluded from the comparison.
        """
        by_language = {
            lang: {r.action for r in routes} | {"torch.off"}
            for lang, routes in ROUTES.items()
        }
        assert by_language["en"] == by_language["el"], (
            "Greek and English tables cover different actions: "
            f"{by_language['en'] ^ by_language['el']}"
        )
