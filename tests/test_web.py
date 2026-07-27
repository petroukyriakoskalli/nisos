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
def server(monkeypatch, tmp_path):
    """A live server on an ephemeral port, torn down afterwards.

    stop_model is stubbed so tests never try to pkill anything real, and the
    token is redirected into tmp_path so a test run never touches the real
    ~/.nisos/ui-token on the machine running it.
    """
    monkeypatch.setattr(web, "_load_token",
                        lambda *a, **k: _fresh_token(tmp_path / "ui-token"))
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


#: Grabbed before any fixture patches the name, so the helper below can still
#: reach the real implementation instead of recursing into the stub.
_REAL_LOAD_TOKEN = web._load_token


def _fresh_token(path):
    """The real loader, pointed somewhere disposable."""
    return _REAL_LOAD_TOKEN(str(path))


def fetch(url, cookie=None):
    """GET a page, returning (status, body, headers). No exception on 4xx."""
    req = urllib.request.Request(url)
    if cookie:
        req.add_header("Cookie", cookie)
    try:
        with urllib.request.urlopen(req, timeout=10) as r:
            return r.status, r.read().decode("utf-8"), r.headers
    except urllib.error.HTTPError as exc:
        return exc.code, exc.read().decode("utf-8"), exc.headers


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


@pytest.fixture
def app_mode_server(monkeypatch, tmp_path):
    """A server configured the way `scripts/app-mode.sh on` configures it."""
    import copy
    monkeypatch.setattr(web, "_load_token",
                        lambda *a, **k: _fresh_token(tmp_path / "ui-token"))
    cfg = config_module.Config(copy.deepcopy(config_module.DEFAULTS))
    cfg.setdefault("ui", {})["quit_on_exit"] = False
    state = web.AppState(cfg)
    state.stopped = []
    monkeypatch.setattr(state, "stop_model",
                        lambda: state.stopped.append(True) or True)
    monkeypatch.setattr(state, "model_running", lambda: False)
    web.Handler.state = state

    httpd = ThreadingHTTPServer(("127.0.0.1", 0), web.Handler)
    httpd.daemon_threads = True
    threading.Thread(target=httpd.serve_forever, daemon=True).start()
    yield f"http://127.0.0.1:{httpd.server_address[1]}", state
    httpd.shutdown()
    httpd.server_close()


class TestAppMode:
    """`quit_on_exit = false` -- the 2.5 GB goes, the idle server stays.

    This is what makes the home-screen icon work. A bookmark cannot start a
    server, so if closing the page killed the server the icon would land on
    "site can't be reached" almost every time you tapped it.
    """

    def test_closing_the_page_frees_the_model_but_keeps_serving(self, app_mode_server):
        base, state = app_mode_server
        status, body = call(base, "/api/quit", token=state.token, payload={})
        assert status == 200
        assert body["serving"] is True
        assert state.stopped == [True]              # the expensive thing went
        assert not state.shutting_down.is_set()     # the cheap thing stayed

        # and it is genuinely still answering
        status, _ = call(base, "/api/status", token=state.token)
        assert status == 200

    def test_the_quit_button_still_quits(self, app_mode_server):
        """App mode is about the beacon. Pressing Quit means what it says."""
        base, state = app_mode_server
        status, body = call(base, "/api/quit", token=state.token,
                            payload={"explicit": True})
        assert status == 200
        assert body["serving"] is False
        assert state.shutting_down.is_set()

    def test_session_mode_is_still_the_default(self, server):
        base, state = server
        _, body = call(base, "/api/quit", token=state.token, payload={})
        assert body["serving"] is False
        assert state.shutting_down.is_set()


class TestPage:
    def test_index_is_served_and_token_injected(self, server):
        base, state = server
        status, html, _ = fetch(f"{base}/?t={state.token}")
        assert status == 200
        assert "__NISOS_TOKEN__" not in html, "placeholder was never replaced"
        assert state.token in html

    def test_manifest_makes_it_installable(self, server):
        base, state = server
        status, body, _ = fetch(f"{base}/manifest.webmanifest?t={state.token}")
        assert status == 200
        m = json.loads(body)
        assert m["display"] == "standalone"   # fullscreen, no browser chrome
        assert m["icons"]

    def test_icon_exists(self, server):
        base, _ = server
        with urllib.request.urlopen(f"{base}/icon.svg", timeout=10) as r:
            assert r.status == 200


class TestTheTokenIsActuallySecret:
    """Regression tests for a hole that made the token decorative.

    `/` used to be served with no token check at all, with the live token
    embedded in the HTML. So any other app on the phone could fetch the page,
    scrape the token out of it, and then drive an API that sends SMS and reads
    the clipboard. Each test below is one way that came apart.
    """

    def test_page_is_not_served_without_the_token(self, server):
        base, _ = server
        status, body, _ = fetch(f"{base}/")
        assert status == 403
        assert "nisos" in body           # a readable refusal, not a blank 403

    def test_the_refusal_does_not_leak_the_token(self, server):
        base, state = server
        _, body, _ = fetch(f"{base}/")
        assert state.token not in body

    def test_manifest_is_gated_too(self, server):
        """start_url carries the token, so an open manifest is the same hole."""
        base, state = server
        status, body, _ = fetch(f"{base}/manifest.webmanifest")
        assert status == 403
        assert state.token not in body

    def test_a_wrong_token_is_refused(self, server):
        base, _ = server
        status, _, _ = fetch(f"{base}/?t=not-the-token")
        assert status == 403

    def test_the_icon_stays_open_because_it_holds_no_secret(self, server):
        base, state = server
        status, body, _ = fetch(f"{base}/icon.svg")
        assert status == 200
        assert state.token not in body


class TestHomeScreenShortcut:
    """What still has to work now that the page itself needs a token."""

    def test_a_valid_load_hands_back_a_cookie(self, server):
        base, state = server
        _, _, headers = fetch(f"{base}/?t={state.token}")
        cookie = headers.get("Set-Cookie", "")
        assert state.token in cookie
        assert "HttpOnly" in cookie       # page scripts must not read it back
        assert "SameSite=Strict" in cookie

    def test_the_cookie_alone_opens_the_page(self, server):
        """Exactly what a home-screen shortcut sends: no ?t=, just the cookie."""
        base, state = server
        status, html, _ = fetch(f"{base}/", cookie=f"nisos={state.token}")
        assert status == 200
        assert "__NISOS_TOKEN__" not in html

    def test_the_token_survives_a_restart(self, tmp_path):
        """A per-launch token would make every installed shortcut stale."""
        store = tmp_path / "ui-token"
        first = web._load_token(str(store))
        second = web._load_token(str(store))
        assert first == second
        assert len(first) >= 20

    def test_the_token_file_is_not_readable_by_others(self, tmp_path):
        store = tmp_path / "ui-token"
        web._load_token(str(store))
        assert store.exists()
        if hasattr(store, "stat") and hasattr(store.stat(), "st_mode"):
            mode = store.stat().st_mode & 0o777
            # Windows reports 0o666 regardless of chmod; only assert where the
            # permission bits mean something.
            if mode not in (0o666, 0o777):
                assert mode & 0o077 == 0, "another user could read the token"


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
