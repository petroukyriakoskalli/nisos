"""Tests for spoken dates and times.

Every rule in :mod:`nisos.when` is a judgement call -- what a bare "five"
means, what a day with no time means, what happens when the hour has already
passed today. Judgement calls are exactly what needs pinning down, because the
failure mode is not a crash: it is an appointment quietly landing twelve hours
or seven days from where you meant it.

``now`` is injected everywhere. A test that reads the wall clock passes all
day and fails at eleven at night.
"""

from datetime import datetime

import pytest

from nisos.normalise import normalise
from nisos.when import DEFAULT_HOUR, DEFAULT_MINUTES, parse

# A Monday, lunchtime -- so "today at five" is still ahead and "today at nine"
# has gone, and both branches are reachable from one fixture.
MONDAY_NOON = datetime(2026, 8, 10, 12, 0)


def when(phrase, language="el", now=MONDAY_NOON):
    """Parse a phrase the way the router does: normalise, then split."""
    return parse(normalise(phrase).split(), language, now)


class TestGreek:
    @pytest.mark.parametrize("phrase,expected", [
        ("αύριο στις πέντε", "2026-08-11T17:00"),
        ("σήμερα στις πέντε", "2026-08-10T17:00"),
        ("μεθαύριο στις δέκα", "2026-08-12T10:00"),
        ("αύριο στις 17:30", "2026-08-11T17:30"),
        ("αύριο στις οκτώ το πρωί", "2026-08-11T08:00"),
        ("αύριο στις οκτώ το βράδυ", "2026-08-11T20:00"),
        ("την Πέμπτη στις τρεις", "2026-08-13T15:00"),
    ])
    def test_hits(self, phrase, expected):
        assert when(phrase).iso() == expected

    def test_half_past(self):
        assert when("αύριο στις πέντε και μισή").iso() == "2026-08-11T17:30"

    def test_quarter_past_and_to(self):
        assert when("αύριο στις πέντε και τέταρτο").iso() == "2026-08-11T17:15"
        assert when("αύριο στις πέντε παρά τέταρτο").iso() == "2026-08-11T16:45"

    def test_tonight_is_the_evening(self):
        """«απόψε στις οκτώ» is 20:00 -- the day word carries the meridiem."""
        assert when("απόψε στις οκτώ").iso() == "2026-08-10T20:00"


class TestEnglish:
    @pytest.mark.parametrize("phrase,expected", [
        ("tomorrow at 5", "2026-08-11T17:00"),
        ("tomorrow at 9 am", "2026-08-11T09:00"),
        ("tomorrow at 9 pm", "2026-08-11T21:00"),
        ("tomorrow at 17:30", "2026-08-11T17:30"),
        ("on thursday at 3", "2026-08-13T15:00"),
        ("tomorrow at half past five", "2026-08-11T17:30"),
        ("tonight at 8", "2026-08-10T20:00"),
    ])
    def test_hits(self, phrase, expected):
        assert when(phrase, "en").iso() == expected

    def test_a_glued_meridiem(self):
        """"5pm" arrives from the recogniser as one token."""
        assert when("tomorrow at 5pm", "en").iso() == "2026-08-11T17:00"


class TestTheJudgementCalls:
    def test_a_bare_small_hour_means_the_afternoon(self):
        """Nobody arranges a dentist for five in the morning and says it that
        casually. One rule, always the same, easy to say out loud."""
        assert when("αύριο στις πέντε").start.hour == 17
        assert when("tomorrow at 3", "en").start.hour == 15

    def test_a_bare_large_hour_is_taken_at_face_value(self):
        assert when("αύριο στις δέκα").start.hour == 10
        assert when("tomorrow at 11", "en").start.hour == 11

    def test_the_morning_can_always_be_asked_for(self):
        assert when("αύριο στις πέντε το πρωί").start.hour == 5
        assert when("tomorrow at 5 in the morning", "en").start.hour == 5

    def test_a_day_with_no_time_gets_the_default_hour(self):
        """"Put the dentist in for Thursday" is a real sentence."""
        moment = when("αύριο")
        assert moment.start.hour == DEFAULT_HOUR
        assert moment.start.day == 11

    def test_a_time_with_no_day_is_the_next_time_it_happens(self):
        assert when("στις πέντε").iso() == "2026-08-10T17:00"      # still ahead
        assert when("στις εννέα το πρωί").iso() == "2026-08-11T09:00"  # gone

    def test_a_weekday_that_is_today_means_next_week(self):
        """Monday lunchtime, asked for "Monday at nine": that has passed."""
        assert when("τη Δευτέρα στις εννέα το πρωί").iso() == "2026-08-17T09:00"

    def test_a_weekday_still_ahead_today_is_today(self):
        assert when("τη Δευτέρα στις πέντε").iso() == "2026-08-10T17:00"


class TestDuration:
    def test_defaults_to_an_hour(self):
        assert when("αύριο στις πέντε").minutes == DEFAULT_MINUTES

    @pytest.mark.parametrize("phrase,language,expected", [
        ("αύριο στις πέντε για μισή ώρα", "el", 30),
        ("αύριο στις πέντε για δύο ώρες", "el", 120),
        ("αύριο στις πέντε για 20 λεπτά", "el", 20),
        ("tomorrow at 5 for 30 minutes", "en", 30),
        ("tomorrow at 5 for 2 hours", "en", 120),
    ])
    def test_spoken_durations(self, phrase, language, expected):
        assert when(phrase, language).minutes == expected

    def test_for_a_person_is_not_a_duration(self):
        """"for Anna" must not become an appointment length."""
        assert when("tomorrow at 5 for Anna", "en").minutes == DEFAULT_MINUTES


class TestMisses:
    @pytest.mark.parametrize("phrase", ["οδοντίατρο", "dentist", "", "με τον γιατρό"])
    def test_no_time_at_all_is_none_not_a_guess(self, phrase):
        assert when(phrase) is None

    def test_a_number_on_its_own_is_not_a_time(self):
        """Otherwise every house number and phone number becomes an hour."""
        assert when("dentist 5", "en") is None


class TestWhatWasConsumed:
    """The leftovers are the appointment's title, so this has to be exact."""

    def test_time_words_are_reported_as_used(self):
        words = normalise("οδοντίατρο αύριο στις πέντε").split()
        moment = parse(words, "el", MONDAY_NOON)
        assert moment.words == {1, 2, 3}
        left = [w for i, w in enumerate(words) if i not in moment.words]
        assert left == ["οδοντιατρο"]

    def test_a_duration_is_used_up_too(self):
        words = normalise("tomorrow at 5 for 30 minutes").split()
        moment = parse(words, "en", MONDAY_NOON)
        assert sorted(moment.words) == [0, 1, 2, 3, 4, 5]
