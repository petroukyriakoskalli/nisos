"""Tests for the web UI's server half.

Runs a real server on a random port and talks to it over HTTP, because the
things most likely to be wrong here -- token checking, the shutdown lifecycle,
UTF-8 handling -- only misbehave through an actual socket.
"""

import json
import threading
import time
import urllib.error
import urllib.request
from http.server import ThreadingHTTPServer

import pytest

from nisos import config as config_module
from nisos import web


@pytest.fixture
def server(monkeypatch):
    """A live server on an ephemeral port, torn down afterwards.

    stop_model is stubbed so tests never try to pkill anything real.
    """
    cfg = config_module.Config(config_module.DEFAULTS.copy())
    state = web.AppState(cfg)
    state.stopped = []
    monkeypatch.setattr(state, "stop_model",
                        lambda: state.stopped.append(True) or True)
    monkeypatch.setattr(state, "model_running", lambda: False)
    web.Handler.state = state

    httpd = ThreadingHTTPServer(("127.0.0.1", 0), web.Handler)
    httpd.daemon_threads = True
    threading.Thread(target=httpd.serve_forever, daemon=True).start()
    port = httpd.server_address[1]

    yield f"http://127.0.0.1:{port}", state

    httpd.shutdown()
    httpd.server_close()


def call(base, path, token=None, payload=None, method=None):
    """Make a request and return (status, parsed body)."""
    headers = {"Content-Type": "application/json"}
    if token:
        headers["X-Nisos-Token"] = token
    data = json.dumps(payload, ensure_ascii=False).encode() if payload is not None else None
    req = urllib.request.Request(f"{base}{path}", data=data, headers=headers,
                                 method=method or ("POST" if data is not None else "GET"))
    try:
        with urllib.request.urlopen(req, timeout=10) as r:
            return r.status, json.loads(r.read().decode("utf-8"))
    except urllib.error.HTTPError as exc:
        body = exc.read().decode("utf-8")
        try:
            return exc.code, json.loads(body)
        except json.JSONDecodeError:
            return exc.code, {"raw": body}


class TestToken:
    """Loopback is not isolation on Android -- any other app can reach it."""

    def test_no_token_is_refused(self, server):
        base, _ = server
        status, _ = call(base, "/api/status")
        assert status == 403

    def test_wrong_token_is_refused(self, server):
        base, _ = server
        status, _ = call(base, "/api/status?t=nope")
        assert status == 403

    def test_correct_token_works(self, server):
        base, state = server
        status, body = call(base, "/api/status", token=state.token)
        assert status == 200
        assert "version" in body

    def test_token_is_not_guessable(self, server):
        _, state = server
        assert len(state.token) >= 20

    def test_sending_sms_needs_the_token(self, server):
        """The API can send texts. It must not be open to other apps."""
        base, _ = server
        status, _ = call(base, "/api/text", payload={"text": "text Anna hello"})
        assert status == 403


class TestPage:
    def test_index_is_served_and_token_injected(self, server):
        base, state = server
        with urllib.request.urlopen(f"{base}/", timeout=10) as r:
            html = r.read().decode("utf-8")
        assert "__NISOS_TOKEN__" not in html, "placeholder was never replaced"
        assert state.token in html

    def test_manifest_makes_it_installable(self, server):
        base, _ = server
        with urllib.request.urlopen(f"{base}/manifest.webmanifest", timeout=10) as r:
            m = json.loads(r.read().decode("utf-8"))
        assert m["display"] == "standalone"   # fullscreen, no browser chrome
        assert m["icons"]

    def test_icon_exists(self, server):
        base, _ = server
        with urllib.request.urlopen(f"{base}/icon.svg", timeout=10) as r:
            assert r.status == 200


class TestCommands:
    def test_greek_in_greek_out_over_http(self, server):
        """UTF-8 has to survive the request body, not just the router."""
        base, state = server
        status, t = call(base, "/api/text", token=state.token,
                         payload={"text": "άναψε τον φακό"})
        assert status == 200
        assert t["action"] == "torch.on"     # English action name
        assert t["language"] == "el"
        assert t["path"] == "routed"

    def test_english_routes_too(self, server):
        base, state = server
        _, t = call(base, "/api/text", token=state.token, payload={"text": "torch off"})
        assert t["action"] == "torch.off"
        assert t["language"] == "en"

    def test_empty_text_is_rejected(self, server):
        base, state = server
        status, _ = call(base, "/api/text", token=state.token, payload={"text": "  "})
        assert status == 400

    def test_turns_appear_in_history(self, server):
        base, state = server
        call(base, "/api/text", token=state.token, payload={"text": "torch on"})
        _, s = call(base, "/api/status", token=state.token)
        assert s["history"], "the turn was not recorded"
        assert s["history"][-1]["action"] == "torch.on"

    def test_unknown_endpoint_404s(self, server):
        base, state = server
        status, _ = call(base, "/api/nonsense", token=state.token)
        assert status == 404


class TestLifecycle:
    """Closing the page must stop the model. This is the load-bearing bit."""

    def test_quit_stops_the_model(self, server):
        base, state = server
        call(base, "/api/quit", token=state.token, payload={})
        time.sleep(0.3)
        assert state.stopped, "quit did not stop the model"

    def test_stop_model_endpoint(self, server):
        base, state = server
        status, _ = call(base, "/api/stop_model", token=state.token, payload={})
        assert status == 200
        assert state.stopped

    def test_any_call_counts_as_a_heartbeat(self, server):
        base, state = server
        state.last_seen = 0.0
        call(base, "/api/ping", token=state.token, payload={})
        assert state.idle_seconds() < 1.0

    def test_idle_is_measured_from_the_last_call(self, server):
        _, state = server
        state.last_seen = time.monotonic() - 100
        assert state.idle_seconds() > 99
