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


class TestMoreThanOneThing:
    """The headline demo in the README used to do half of what it says.

    «βάλε χρονόμετρο δώδεκα λεπτά και άναψε τον φακό» lit the torch and
    dropped the timer without a word, which is worse than refusing it.
    """

    def test_two_greek_commands_become_two_steps(self):
        match = route("βάλε χρονόμετρο δώδεκα λεπτά και άναψε τον φακό")
        assert [s.action for s in match.steps] == ["timer.set", "torch.on"]
        assert match.steps[0].args["minutes"] == 12
        assert match.language == "el"

    def test_two_english_commands_become_two_steps(self):
        match = route("torch on and set a timer for 5 minutes")
        assert [s.action for s in match.steps] == ["torch.on", "timer.set"]

    def test_then_and_also_split_too(self):
        assert len(route("torch off then what time is it").steps) == 2
        assert len(route("what's my battery also what time is it").steps) == 2

    def test_a_comma_is_a_boundary(self):
        assert len(route("torch on, what time is it").steps) == 2

    def test_three_things_still_work(self):
        match = route("άναψε τον φακό και τι ώρα είναι και πόση μπαταρία έχω")
        assert [s.action for s in match.steps] == ["torch.on", "time.read",
                                                   "battery.read"]

    def test_the_first_step_is_still_the_plain_action(self):
        """Everything that reads match.action keeps working unchanged."""
        match = route("torch on and what time is it")
        assert match.action == "torch.on"
        assert match.args == route("torch on").args

    def test_one_command_is_exactly_one_step(self):
        match = route("άναψε τον φακό")
        assert len(match.steps) == 1
        assert match.steps[0].action == "torch.on"


class TestSplittingIsRefusedWhenItWouldBeWrong:
    """The safety rule: a split is only taken when *every* piece routes.

    Without it, the word «και» inside a message would cut the message in half
    -- a far worse bug than the one multi-action fixes.
    """

    def test_and_inside_a_message_body_is_left_alone(self):
        match = route("text Marilena I'm late and we'll eat later")
        assert match.action == "sms.send"
        assert len(match.steps) == 1
        assert "later" in match.args["body"]

    def test_and_inside_a_greek_message_body_is_left_alone(self):
        match = route("στείλε στη Μαριλένα ότι άργησα και θα φάμε αργότερα")
        assert match.action == "sms.send"
        assert len(match.steps) == 1

    def test_a_trailing_conjunction_does_not_produce_an_empty_step(self):
        match = route("torch on and")
        assert len(match.steps) == 1

    def test_too_many_pieces_is_treated_as_one_sentence(self):
        """Five orders in one breath is far likelier to be a sentence with
        five «και»s in it, so a longer split is not accepted at all."""
        wordy = " and ".join(["torch on"] * 6)
        assert len(route(wordy).steps) == 1

    def test_a_miss_is_still_a_miss(self):
        assert route("what is the capital of Japan and why") is None


class TestAppointments:
    @pytest.mark.parametrize("phrase", [
        "put dentist in my calendar tomorrow at 5",
        "add dentist to my diary tomorrow at 5",
        "book a meeting tomorrow at 5",
        "schedule an appointment tomorrow at 5",
    ])
    def test_english_hits(self, phrase):
        assert route(phrase).action == "calendar.add"

    @pytest.mark.parametrize("phrase", [
        "βάλε στο ημερολόγιο οδοντίατρο αύριο στις πέντε",
        "γράψε στο ημερολόγιο ραντεβού αύριο",
        "κλείσε ραντεβού με τον γιατρό αύριο στις πέντε",
        "βάλε ραντεβού αύριο στις πέντε",
    ])
    def test_greek_hits(self, phrase):
        match = route(phrase)
        assert match.action == "calendar.add"
        assert match.language == "el"

    def test_asking_about_the_next_one_is_not_making_one(self):
        """calendar.add sits above calendar.next, so this is the ordering
        test: a question must not book anything."""
        assert route("next meeting").action == "calendar.next"
        assert route("πότε είναι το ραντεβού μου").action == "calendar.next"

    def test_writing_to_the_calendar_is_not_a_text_message(self):
        """«γράψε στο ημερολόγιο» matches the sms pattern too, and the sms
        route would send a message to somebody called «ημερολόγιο»."""
        assert route("γράψε στο ημερολόγιο γιατρό αύριο").action == "calendar.add"

    def test_the_title_keeps_the_user_s_own_spelling(self):
        """A calendar entry is written down, not spoken. «οδοντιατρο» in your
        diary is the plumbing showing."""
        match = route("βάλε στο ημερολόγιο οδοντίατρο αύριο στις πέντε")
        assert match.args["summary"] == "οδοντίατρο"

    def test_the_title_survives_words_in_the_middle(self):
        match = route("κλείσε ραντεβού με τον γιατρό αύριο στις πέντε")
        assert match.args["summary"] == "ραντεβού με τον γιατρό"

    def test_english_titles_lose_the_chrome_and_keep_the_rest(self):
        match = route("book a meeting with Nikos tomorrow at 5")
        assert match.args["summary"] == "meeting with Nikos"

    def test_the_word_appointment_is_a_perfectly_good_title(self):
        """«ραντεβού» is not chrome -- it is what the entry is called."""
        match = route("βάλε ραντεβού αύριο στις πέντε")
        assert match.args["summary"] == "ραντεβού"

    def test_a_title_less_appointment_gets_a_name_rather_than_none(self):
        """Every word was an instruction or a time. An entry called "" is
        worse than one called what it obviously is."""
        match = route("βάλε στο ημερολόγιο αύριο στις πέντε")
        assert match.args["summary"] == "Ραντεβού"
        assert route("put it in my calendar tomorrow at 5") \
            .args["summary"] == "Appointment"

    def test_the_time_comes_through_as_a_date(self):
        match = route("put dentist in my calendar tomorrow at 5")
        assert "start" in match.args
        assert match.args["start"].endswith("T17:00")   # bare 5 means evening

    def test_no_time_means_no_start_rather_than_a_guess(self):
        """Guessing an hour for something that goes in a diary is worse than
        admitting you missed it -- the action then says so out loud."""
        match = route("book a meeting with Nikos")
        assert "start" not in match.args


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
