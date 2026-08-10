"""Tests for the online brain.

Nothing here touches the network. The Claude API is a documented shape, so what
is worth testing is that we *send* that shape (forced tool call, no sampling
parameters, the beta header only when it is meant to be there) and that every
way it can fail turns into something the assistant can say out loud.

The failure paths are the reason this file is long. A wrong payload fails loudly
on the first run; a mishandled 401 fails as "Can't do that offline" on a phone
with four bars, which is the kind of bug that costs an evening.
"""

import copy
import json
import os
import urllib.error

import pytest

from nisos import brain, cloud
from nisos import config as config_module
from nisos.actions import action_names
from nisos.brain import BrainError


@pytest.fixture
def cfg(tmp_path, monkeypatch):
    """A default config whose key file is a real, empty tmp path."""
    monkeypatch.delenv("ANTHROPIC_API_KEY", raising=False)
    config = config_module.Config(copy.deepcopy(config_module.DEFAULTS))
    config["brain"]["cloud"]["key_file"] = str(tmp_path / "anthropic-key")
    return config


@pytest.fixture
def keyed(cfg):
    """The same config, with a key stored."""
    cloud.store_key("sk-ant-test", cfg.expanded("brain.cloud.key_file"))
    return cfg


class FakeResponse:
    """Minimal stand-in for what urlopen returns."""

    def __init__(self, body: dict, status: int = 200):
        self._body = json.dumps(body).encode("utf-8")
        self.status = status

    def read(self):
        return self._body

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        return False


def tool_use(action="answer", args=None, stop="tool_use"):
    """A well-formed Messages API response containing one tool call."""
    return {
        "stop_reason": stop,
        "content": [
            {"type": "thinking", "thinking": ""},
            {"type": "tool_use", "id": "toolu_1", "name": cloud.TOOL_NAME,
             "input": {"action": action, "args": args if args is not None else {}}},
        ],
    }


def capture(monkeypatch, response):
    """Stub urlopen; return a list that receives each Request it was given."""
    seen = []

    def fake_urlopen(request, timeout=None):
        seen.append(request)
        if isinstance(response, Exception):
            raise response
        return response

    monkeypatch.setattr(cloud.urllib.request, "urlopen", fake_urlopen)
    return seen


def body_of(request) -> dict:
    return json.loads(request.data.decode("utf-8"))


# --------------------------------------------------------------------------
# The key
# --------------------------------------------------------------------------

class TestKey:
    def test_no_key_anywhere(self, cfg):
        assert cloud.load_key(cfg) is None
        assert cloud.available(cfg) is False

    def test_stored_key_is_found_and_stripped(self, cfg):
        path = cfg.expanded("brain.cloud.key_file")
        cloud.store_key("  sk-ant-spaces  ", path)
        assert cloud.load_key(cfg) == "sk-ant-spaces"

    def test_environment_wins_over_the_file(self, cfg, monkeypatch):
        cloud.store_key("sk-ant-file", cfg.expanded("brain.cloud.key_file"))
        monkeypatch.setenv("ANTHROPIC_API_KEY", "sk-ant-env")
        assert cloud.load_key(cfg) == "sk-ant-env"

    def test_empty_key_is_refused(self, cfg):
        with pytest.raises(ValueError):
            cloud.store_key("   ", cfg.expanded("brain.cloud.key_file"))

    @pytest.mark.skipif(os.name == "nt", reason="POSIX modes only")
    def test_stored_key_is_not_world_readable(self, cfg):
        """The whole reason store_key exists rather than being an `echo`."""
        target = cloud.store_key("sk-ant-test", cfg.expanded("brain.cloud.key_file"))
        assert oct(target.stat().st_mode)[-3:] == "600"


# --------------------------------------------------------------------------
# The prompt and the tool
# --------------------------------------------------------------------------

class TestPrompt:
    def test_every_action_is_offered(self):
        system = cloud.build_system("el", action_names())
        for name in action_names():
            assert name in system

    def test_greek_asks_for_greek_words_but_english_action_names(self):
        """The load-bearing design decision, asserted for the online path too."""
        system = cloud.build_system("el", action_names())
        assert "Greek" in system
        assert "torch.on" in system          # English, untranslated

    def test_the_enum_is_generated_from_the_registry(self):
        """A new action must not need a second edit here to be reachable."""
        schema = cloud.build_tool(action_names())["input_schema"]
        assert schema["properties"]["action"]["enum"] == action_names()

    def test_memories_go_in_the_user_turn_not_the_system_prompt(self):
        """So the system prefix stays byte-identical and cacheable."""
        first = cloud.build_system("en", action_names())
        second = cloud.build_system("en", action_names())
        assert first == second

        user = cloud.build_user("when is her birthday", {"marilena": "3 March"})
        assert "3 March" in user
        assert "when is her birthday" in user


# --------------------------------------------------------------------------
# The request
# --------------------------------------------------------------------------

class TestPayload:
    def test_the_model_is_forced_to_call_the_tool(self, keyed, monkeypatch):
        seen = capture(monkeypatch, FakeResponse(tool_use()))
        cloud.think("summarise this", "en", action_names(), keyed)

        payload = body_of(seen[0])
        assert payload["tool_choice"] == {"type": "tool", "name": cloud.TOOL_NAME}
        assert payload["tools"][0]["name"] == cloud.TOOL_NAME

    def test_headers(self, keyed, monkeypatch):
        seen = capture(monkeypatch, FakeResponse(tool_use()))
        cloud.think("summarise this", "en", action_names(), keyed)

        headers = {k.lower(): v for k, v in seen[0].header_items()}
        assert headers["x-api-key"] == "sk-ant-test"
        assert headers["anthropic-version"] == cloud.API_VERSION
        assert headers["content-type"] == "application/json"

    def test_no_sampling_parameters(self, keyed, monkeypatch):
        """Current models reject these outright -- brain.temperature is for
        llama-server only, and leaking it here would 400 every reasoned turn."""
        seen = capture(monkeypatch, FakeResponse(tool_use()))
        cloud.think("summarise this", "en", action_names(), keyed)

        payload = body_of(seen[0])
        for forbidden in ("temperature", "top_p", "top_k"):
            assert forbidden not in payload

    def test_effort_and_thinking_defaults(self, keyed, monkeypatch):
        seen = capture(monkeypatch, FakeResponse(tool_use()))
        cloud.think("summarise this", "en", action_names(), keyed)

        payload = body_of(seen[0])
        assert payload["output_config"] == {"effort": "low"}
        assert payload["thinking"] == {"type": "adaptive"}

    def test_thinking_can_be_turned_off(self, keyed, monkeypatch):
        keyed["brain"]["cloud"]["thinking"] = "off"
        seen = capture(monkeypatch, FakeResponse(tool_use()))
        cloud.think("summarise this", "en", action_names(), keyed)

        assert body_of(seen[0])["thinking"] == {"type": "disabled"}

    def test_blank_settings_are_omitted_rather_than_sent_empty(self, keyed,
                                                               monkeypatch):
        """The escape hatch for a model that does not take these at all."""
        keyed["brain"]["cloud"]["effort"] = ""
        keyed["brain"]["cloud"]["thinking"] = ""
        keyed["brain"]["cloud"]["fallbacks"] = ""
        seen = capture(monkeypatch, FakeResponse(tool_use()))
        cloud.think("summarise this", "en", action_names(), keyed)

        payload = body_of(seen[0])
        assert "output_config" not in payload
        assert "thinking" not in payload
        assert "fallbacks" not in payload

    def test_the_beta_header_travels_with_the_parameter(self, keyed, monkeypatch):
        """Header and parameter form are a matched pair -- one without the
        other is a 400, so they must never be settable separately."""
        seen = capture(monkeypatch, FakeResponse(tool_use()))
        cloud.think("summarise this", "en", action_names(), keyed)
        headers = {k.lower(): v for k, v in seen[0].header_items()}
        assert body_of(seen[0])["fallbacks"] == "default"
        assert "anthropic-beta" in headers

        keyed["brain"]["cloud"]["fallbacks"] = ""
        seen = capture(monkeypatch, FakeResponse(tool_use()))
        cloud.think("summarise this", "en", action_names(), keyed)
        headers = {k.lower(): v for k, v in seen[0].header_items()}
        assert "anthropic-beta" not in headers


# --------------------------------------------------------------------------
# The response
# --------------------------------------------------------------------------

class TestResponse:
    def test_a_tool_call_becomes_a_decision(self, keyed, monkeypatch):
        capture(monkeypatch, FakeResponse(
            tool_use("timer.set", {"minutes": 10})))
        decision = cloud.think("ten minute timer", "en", action_names(), keyed)

        assert decision.action == "timer.set"
        assert decision.args == {"minutes": 10}
        assert decision.backend == "claude"
        assert decision.seconds >= 0

    def test_a_tool_call_with_no_action_is_unclear_not_a_crash(self, keyed,
                                                              monkeypatch):
        capture(monkeypatch, FakeResponse({
            "stop_reason": "tool_use",
            "content": [{"type": "tool_use", "name": cloud.TOOL_NAME,
                         "input": {"args": {"minutes": 3}}}],
        }))
        assert cloud.think("x", "en", action_names(), keyed).action == "unclear"

    def test_non_dict_args_are_dropped_rather_than_passed_on(self, keyed,
                                                             monkeypatch):
        capture(monkeypatch, FakeResponse(
            tool_use("torch.on", args="on please")))
        decision = cloud.think("x", "en", action_names(), keyed)
        assert decision.args == {}

    def test_a_refusal_says_so_and_never_reads_missing_content(self, keyed,
                                                              monkeypatch):
        capture(monkeypatch, FakeResponse({
            "stop_reason": "refusal",
            "stop_details": {"type": "refusal", "category": "cyber"},
            "content": [],
        }))
        with pytest.raises(BrainError) as caught:
            cloud.think("x", "en", action_names(), keyed)
        assert caught.value.reply_key == "refused"

    def test_running_out_of_room_names_the_setting_to_change(self, keyed,
                                                            monkeypatch):
        capture(monkeypatch, FakeResponse({"stop_reason": "max_tokens",
                                           "content": []}))
        with pytest.raises(BrainError, match="brain.cloud.max_tokens"):
            cloud.think("x", "en", action_names(), keyed)


# --------------------------------------------------------------------------
# The failures
# --------------------------------------------------------------------------

class TestFailures:
    def test_no_key_is_reported_before_any_request_is_made(self, cfg,
                                                           monkeypatch):
        seen = capture(monkeypatch, FakeResponse(tool_use()))
        with pytest.raises(BrainError) as caught:
            cloud.think("x", "en", action_names(), cfg)

        assert caught.value.reply_key == "no_key"
        assert seen == [], "asked the API for something with no key"

    @staticmethod
    def _http_error(code, payload):
        import io
        return urllib.error.HTTPError(
            cloud.ENDPOINT, code, "err", {},
            io.BytesIO(json.dumps(payload).encode("utf-8")))

    def test_a_rejected_key_says_key_not_network(self, keyed, monkeypatch):
        capture(monkeypatch, self._http_error(
            401, {"error": {"message": "invalid x-api-key"}}))
        with pytest.raises(BrainError) as caught:
            cloud.think("x", "en", action_names(), keyed)

        assert caught.value.reply_key == "no_key"
        # The API's own words, kept. Suppressing them has cost this project a
        # diagnosis three times already.
        assert "invalid x-api-key" in str(caught.value)

    def test_rate_limiting_is_a_network_problem_as_far_as_the_user_is_told(
            self, keyed, monkeypatch):
        capture(monkeypatch, self._http_error(
            429, {"error": {"message": "slow down"}}))
        with pytest.raises(BrainError) as caught:
            cloud.think("x", "en", action_names(), keyed)
        assert caught.value.reply_key == "unavailable"

    def test_a_server_error_keeps_the_status_code(self, keyed, monkeypatch):
        capture(monkeypatch, self._http_error(529, {"error": {"message": "busy"}}))
        with pytest.raises(BrainError, match="529"):
            cloud.think("x", "en", action_names(), keyed)

    def test_an_unparseable_error_body_still_produces_a_message(self, keyed,
                                                                monkeypatch):
        import io
        capture(monkeypatch, urllib.error.HTTPError(
            cloud.ENDPOINT, 400, "Bad Request", {}, io.BytesIO(b"<html>nope")))
        with pytest.raises(BrainError, match="nope"):
            cloud.think("x", "en", action_names(), keyed)

    def test_no_network_is_the_one_failure_that_means_offline(self, keyed,
                                                             monkeypatch):
        capture(monkeypatch, urllib.error.URLError("Name or service not known"))
        with pytest.raises(BrainError) as caught:
            cloud.think("x", "en", action_names(), keyed)
        assert caught.value.reply_key == "unavailable"


# --------------------------------------------------------------------------
# Which brain runs
# --------------------------------------------------------------------------

class TestBackendChoice:
    def test_auto_goes_online_when_there_is_a_key(self, keyed):
        assert brain.backend_for(keyed) == "claude"

    def test_auto_stays_local_without_one(self, cfg):
        assert brain.backend_for(cfg) == "llama"

    def test_pinning_llama_ignores_the_key(self, keyed):
        keyed["brain"]["backend"] = "llama"
        assert brain.backend_for(keyed) == "llama"

    def test_pinning_claude_works_without_a_key_so_the_error_can_say_why(self, cfg):
        """Otherwise a missing key would silently become "model isn't running"."""
        cfg["brain"]["backend"] = "claude"
        assert brain.backend_for(cfg) == "claude"

    def test_a_typo_falls_back_to_auto_rather_than_breaking(self, keyed):
        keyed["brain"]["backend"] = "cluade"
        assert brain.backend_for(keyed) == "claude"

    def test_auto_drops_to_llama_when_the_network_is_gone(self, keyed,
                                                          monkeypatch):
        capture(monkeypatch, urllib.error.URLError("no route to host"))
        monkeypatch.setattr(brain, "available", lambda *a, **k: True)
        monkeypatch.setattr(
            brain, "think_llama",
            lambda *a, **k: brain.Decision("answer", {"text": "local"}, 0.1))

        decision = brain.think("x", "en", action_names(), keyed)
        assert decision.action == "answer"
        assert decision.backend == "llama"

    def test_auto_reports_the_online_failure_when_llama_is_not_running(
            self, keyed, monkeypatch):
        """The alternative -- loading a 2.5 GB model inside a turn somebody is
        waiting on -- is worse than saying the network is down."""
        capture(monkeypatch, urllib.error.URLError("no route to host"))
        monkeypatch.setattr(brain, "available", lambda *a, **k: False)

        with pytest.raises(BrainError) as caught:
            brain.think("x", "en", action_names(), keyed)
        assert caught.value.reply_key == "unavailable"

    def test_pinned_claude_never_falls_back(self, keyed, monkeypatch):
        keyed["brain"]["backend"] = "claude"
        capture(monkeypatch, urllib.error.URLError("no route to host"))
        monkeypatch.setattr(brain, "available", lambda *a, **k: True)

        def fail(*args, **kwargs):
            raise AssertionError("fell back to llama despite backend = claude")

        monkeypatch.setattr(brain, "think_llama", fail)
        with pytest.raises(BrainError):
            brain.think("x", "en", action_names(), keyed)
