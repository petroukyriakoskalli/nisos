"""Tests for text normalisation and number parsing.

These run anywhere -- no phone, no model, no audio. That is deliberate: the
parts of this program most likely to be wrong are the Greek text handling and
the router, and both are pure functions precisely so they can be tested on a
laptop before anything is flashed to a phone.
"""

import pytest

from nisos.normalise import (NUMBER_WORDS, normalise, parse_number,
                             script_of, strip_accents)


class TestStripAccents:
    def test_removes_greek_tonos(self):
        assert strip_accents("άναψε") == "αναψε"
        assert strip_accents("φακό") == "φακο"

    def test_removes_dialytika(self):
        assert strip_accents("προϊόν") == "προιον"

    def test_leaves_ascii_alone(self):
        assert strip_accents("torch on") == "torch on"

    def test_handles_latin_diacritics_too(self):
        assert strip_accents("café") == "cafe"


class TestNormalise:
    def test_lowercases_and_strips_accents(self):
        assert normalise("Άναψε τον Φακό") == "αναψε τον φακο"

    def test_unifies_final_sigma(self):
        # The whole point: «φακός» and «φακοσ» must become the same string,
        # or every pattern needs writing twice.
        assert normalise("φακός") == normalise("φακοσ")

    def test_collapses_whitespace(self):
        assert normalise("  torch   ON  ") == "torch on"

    def test_is_idempotent(self):
        once = normalise("Άναψε τον Φακό!")
        assert normalise(once) == once

    def test_preserves_punctuation(self):
        # Patterns sometimes need it, and stripping it would merge words.
        assert normalise("τι ώρα είναι;") == "τι ωρα ειναι;"


class TestScriptOf:
    def test_detects_greek(self):
        assert script_of("άναψε τον φακό") == "el"

    def test_detects_english(self):
        assert script_of("turn the torch on") == "en"

    def test_mostly_greek_with_a_latin_name_is_still_greek(self):
        """Code-switching for a single name must not flip the verdict.

        «στείλε μήνυμα στην Anna» is a Greek sentence containing one English
        word, and treating it as English would send the reply in the wrong
        language.
        """
        assert script_of("στείλε μήνυμα στην Anna") == "el"

    def test_none_for_a_balanced_mix(self):
        assert script_of("torch on φακό") is None

    def test_none_for_digits_only(self):
        assert script_of("12345") is None


class TestParseNumber:
    def test_digits_win(self):
        assert parse_number("timer for 25 minutes", "en") == 25
        assert parse_number("χρονομετρο 12 λεπτα", "el") == 12

    def test_greek_words(self):
        assert parse_number("χρονομετρο δωδεκα λεπτα", "el") == 12
        assert parse_number("θυμισε μου σε δεκα λεπτα", "el") == 10

    def test_english_words(self):
        assert parse_number("set a timer for ten minutes", "en") == 10

    def test_longest_word_wins(self):
        # "δεκαπεντε" (15) must not be shadowed by "δεκα" (10).
        assert parse_number("χρονομετρο δεκαπεντε λεπτα", "el") == 15

    def test_falls_back_to_other_language(self):
        # Code-switching: Greek sentence, English number.
        assert parse_number("χρονομετρο twelve λεπτα", "el") == 12

    def test_none_when_absent(self):
        assert parse_number("αναψε τον φακο", "el") is None

    @pytest.mark.parametrize("language", list(NUMBER_WORDS))
    def test_number_word_keys_are_already_normalised(self, language):
        """Keys are matched against normalised text, so they must be normalised.

        An accented key like "δώδεκα" would never match anything, and the
        failure is silent -- the timer just quietly does nothing.
        """
        for word in NUMBER_WORDS[language]:
            assert word == normalise(word), (
                f"{language} number word {word!r} is not in normalised form"
            )
