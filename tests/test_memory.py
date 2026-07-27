"""Tests for memory, WhatsApp routing, and the two working together.

The interesting case is the loop the whole feature exists for: teach it a
number once, then send to that person by name in either language.
"""

import json

import pytest

from nisos import replies
from nisos.actions import ExecutionContext, action_names, execute
from nisos.memory import Memory, normalise_phone
from nisos.router import route
from tests.test_actions import RecordingContext


@pytest.fixture
def mem(tmp_path):
    return Memory(tmp_path / "memory.json")


class TestNormalisePhone:
    def test_strips_punctuation(self):
        assert normalise_phone("+357 99 12-34 56") == "3579912 3456".replace(" ", "")

    def test_adds_country_code_to_a_local_number(self):
        assert normalise_phone("99123456", "357") == "35799123456"

    def test_leaves_international_alone(self):
        assert normalise_phone("+35799123456", "357") == "35799123456"

    def test_handles_00_prefix(self):
        assert normalise_phone("0035799123456", "357") == "35799123456"

    def test_rejects_something_too_short(self):
        assert normalise_phone("12") is None

    def test_rejects_empty(self):
        assert normalise_phone("") is None


class TestFacts:
    def test_remember_and_recall(self, mem):
        mem.remember("Marilena's birthday", "March")
        assert mem.recall("Marilena's birthday") == "March"

    def test_keys_ignore_case_and_accents(self, mem):
        """«Μαριλένα» and «μαριλενα» must be the same key."""
        mem.remember("Μαριλένα", "η αδερφή μου")
        assert mem.recall("μαριλενα") == "η αδερφή μου"
        assert mem.recall("ΜΑΡΙΛΕΝΑ") == "η αδερφή μου"

    def test_values_are_kept_verbatim(self, mem):
        mem.remember("code", "Ω123-ΑΒΓ")
        assert mem.recall("code") == "Ω123-ΑΒΓ"

    def test_forget(self, mem):
        mem.remember("x", "y")
        assert mem.forget("x") is True
        assert mem.recall("x") is None
        assert mem.forget("x") is False

    def test_survives_a_reload(self, mem, tmp_path):
        mem.remember("wifi", "hunter2")
        assert Memory(tmp_path / "memory.json").recall("wifi") == "hunter2"

    def test_corrupt_file_does_not_crash(self, tmp_path):
        """Losing the torch because a JSON file truncated would be absurd."""
        path = tmp_path / "memory.json"
        path.write_text("{not json at all", encoding="utf-8")
        m = Memory(path)
        assert m.facts() == {}
        m.remember("a", "b")
        assert m.recall("a") == "b"


class TestRelevance:
    def test_only_surfaces_matching_facts(self, mem):
        mem.remember("marilena", "my sister")
        mem.remember("kostas", "my accountant")
        picked = mem.relevant("when is marilena's birthday")
        assert list(picked.values()) == ["my sister"]

    def test_matches_greek_regardless_of_accents(self, mem):
        mem.remember("Μαριλένα", "η αδερφή μου")
        assert mem.relevant("πότε έχει γενέθλια η Μαριλένα")

    def test_is_capped(self, mem):
        for i in range(20):
            mem.remember(f"thing{i}", str(i))
        text = " ".join(f"thing{i}" for i in range(20))
        assert len(mem.relevant(text, limit=5)) == 5

    def test_no_partial_word_matches(self, mem):
        """'ann' must not fire on 'announcement'."""
        mem.remember("ann", "a person")
        assert mem.relevant("make an announcement") == {}


class TestContacts:
    def test_store_and_look_up(self, mem):
        mem.remember_contact("Marilena", "99123456", "357")
        assert mem.contact("marilena") == "35799123456"

    def test_greek_spelling_finds_it(self, mem):
        """The actual fix for code-switching."""
        mem.remember_contact("Μαριλένα", "+35799123456")
        assert mem.contact("μαριλενα") == "35799123456"

    def test_rejects_a_non_number(self, mem):
        assert mem.remember_contact("x", "not a phone") is None


class TestRouting:
    @pytest.mark.parametrize("phrase,action", [
        ("remember that Marilena is 99123456", "memory.remember"),
        ("forget Marilena", "memory.forget"),
        ("what do you remember", "memory.list"),
        ("what's Marilena's number", "memory.recall"),
        ("θυμήσου ότι η Μαριλένα είναι 99123456", "memory.remember"),
        ("τι θυμάσαι", "memory.list"),
        ("ξέχνα τη Μαριλένα", "memory.forget"),
    ])
    def test_memory_phrases(self, phrase, action):
        m = route(phrase)
        assert m is not None, f"{phrase!r} routed nowhere"
        assert m.action == action

    def test_general_questions_still_reach_the_model(self):
        """The recall patterns must not swallow everything."""
        assert route("what is the capital of Japan") is None
        assert route("ποια είναι η πρωτεύουσα της Ιαπωνίας") is None

    def test_messaging_defaults_to_sms(self):
        m = route("text Marilena I'm running late")
        assert m.action == "sms.send"

    def test_saying_whatsapp_switches_channel(self):
        m = route("text Marilena on whatsapp I'm running late")
        assert m.action == "whatsapp.send"

    def test_greek_whatsapp(self):
        m = route("στείλε στη Μαριλένα στο whatsapp ότι άργησα")
        assert m.action == "whatsapp.send"
        assert m.language == "el"

    def test_brand_name_is_stripped_from_the_message(self):
        """Otherwise the word 'whatsapp' ends up inside the text you send."""
        m = route("text Marilena on whatsapp running late")
        assert "whatsapp" not in m.args.get("body", "").lower()


class TestActions:
    def test_remembering_a_number_stores_a_contact(self, tmp_path):
        ctx = RecordingContext(memory=Memory(tmp_path / "m.json"), country_code="357")
        key, fields = execute("memory.remember",
                              {"key": "Marilena", "value": "99123456"}, ctx)
        assert key == "memory.remember"
        assert ctx.memory.contact("marilena") == "35799123456"

    def test_remembering_a_sentence_stores_a_fact(self, tmp_path):
        ctx = RecordingContext(memory=Memory(tmp_path / "m.json"))
        execute("memory.remember", {"key": "wifi", "value": "the password is hunter2"}, ctx)
        assert ctx.memory.recall("wifi") == "the password is hunter2"

    def test_whatsapp_opens_a_prefilled_chat(self, tmp_path):
        ctx = RecordingContext(memory=Memory(tmp_path / "m.json"), country_code="357")
        ctx.memory.remember_contact("Marilena", "99123456", "357")
        key, fields = execute("whatsapp.send",
                              {"to": "Marilena", "body": "άργησα"}, ctx)
        assert key == "whatsapp.send"
        url = ctx.commands[0][-1]
        assert url.startswith("https://wa.me/35799123456?text=")

    def test_whatsapp_without_a_number_says_so(self, tmp_path):
        ctx = RecordingContext(memory=Memory(tmp_path / "m.json"))
        key, _ = execute("whatsapp.send", {"to": "Nobody", "body": "hi"}, ctx)
        assert key == "failed"
        assert ctx.commands == []

    def test_the_whole_loop(self, tmp_path):
        """Teach a number, then message that person by name in Greek."""
        ctx = RecordingContext(memory=Memory(tmp_path / "m.json"), country_code="357")

        taught = route("θυμήσου ότι η Μαριλένα είναι 99123456")
        execute(taught.action, taught.args, ctx)

        send = route("στείλε στη Μαριλένα στο whatsapp ότι άργησα")
        key, fields = execute(send.action, send.args, ctx)

        assert key == "whatsapp.send"
        assert "35799123456" in ctx.commands[-1][-1]


class TestRepliesStayInStep:
    def test_every_action_still_has_both_languages(self):
        assert replies.missing_replies(action_names()) == []
