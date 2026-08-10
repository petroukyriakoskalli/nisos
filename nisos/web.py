"""A local web interface, so nisos has a front end instead of a terminal.

Serves a single page on ``127.0.0.1``. Open it in Chrome, use **Add to Home
Screen**, and Android gives it its own icon and launches it fullscreen with no
browser chrome -- it looks and behaves like an app, without anyone having to
build and sign an APK.

Standard library only. The whole project has no pip dependencies and this
doesn't break that: :class:`~http.server.ThreadingHTTPServer` is more than
enough for one user on loopback.

Lifecycle
---------
Closing the page shuts down ``llama-server`` too, which is the whole point of
the request that prompted this. Getting that right needs two mechanisms,
because either alone is wrong:

* **A beacon on close.** ``pagehide`` fires when you swipe the app away, and
  ``navigator.sendBeacon`` gets a request out even as the page dies. Fast, but
  *not reliable* -- a force-kill, a crash or a battery-out fires nothing.
* **A heartbeat watchdog.** The page pings every few seconds; if the pings stop
  for longer than the grace period, the watchdog shuts the model down anyway.
  Slower, but it cannot be dodged.

So: the beacon makes shutdown instant in the normal case, and the watchdog
guarantees it in every other case.

Security
--------
⚠️ Binding to loopback is *not* sufficient isolation on Android. Every other
app on the phone can reach ``127.0.0.1`` too, and this API can send SMS. So a
random token is generated at startup, handed to the browser in the launch URL,
and required on every API call. Without it the page is inert.

Extending
---------
Add an endpoint by writing a ``_api_<name>`` method on the handler; it is
dispatched automatically from ``/api/<name>``. The page itself is
``nisos/ui/index.html`` -- plain HTML with no build step, edit it directly.
"""

from __future__ import annotations

import json
import logging
import mimetypes
import secrets
import subprocess
import threading
import time
from collections import deque
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlparse

from . import actions as actions_module
from . import brain, loop, replies

__all__ = ["serve", "AppState"]

log = logging.getLogger(__name__)

UI_DIR = Path(__file__).resolve().parent / "ui"


def _load_token(path: str = "~/.nisos/ui-token") -> str:
    """Read the UI secret, creating it on first use.

    Stored rather than regenerated per launch, for two reasons that pull the
    same way:

    * **Add to Home Screen has to keep working.** The installed shortcut bakes
      in whatever ``start_url`` said at install time. A per-launch token makes
      that shortcut stale the moment the server restarts.
    * **The page itself is now behind the token** (see :meth:`Handler.do_GET`),
      so there has to be a value the browser can still present tomorrow.

    Lives in Termux's private app data with 0600 permissions, which is the
    part that makes it a secret at all -- no other app can read that directory
    without root.

    Returns:
        The token, 32 URL-safe characters.
    """
    target = Path(path).expanduser()
    try:
        existing = target.read_text(encoding="utf-8").strip()
        if existing:
            return existing
    except OSError:
        pass

    token = secrets.token_urlsafe(24)
    try:
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(token, encoding="utf-8")
        target.chmod(0o600)
    except OSError:
        log.warning("couldn't persist the UI token to %s -- the home screen "
                    "shortcut will need reinstalling after a restart", target)
    return token


class AppState:
    """Everything the server keeps between requests.

    Attributes:
        config: The loaded :class:`nisos.config.Config`.
        token: Secret required on every request, including the page itself.
        history: Recent turns, newest last, capped so it can't grow forever.
        last_seen: Monotonic timestamp of the most recent heartbeat.
        turn_lock: Serialises turns -- two simultaneous recordings would fight
            over the microphone and produce nonsense.
        shutting_down: Set once shutdown has begun, so it only happens once.
        idle_handled: True once the watchdog has dealt with the current idle
            spell. Reset by :meth:`touch`, so in app mode -- where the server
            outlives the page -- the model gets stopped again after the *next*
            session too, not only the first.
    """

    def __init__(self, config):
        self.config = config
        self.token = _load_token()
        self.history: deque = deque(maxlen=40)
        self.last_seen = time.monotonic()
        self.turn_lock = threading.Lock()
        self.shutting_down = threading.Event()
        self.idle_handled = False
        self.context = loop.build_context(config)

    # -- model process ----------------------------------------------------
    def model_running(self) -> bool:
        """True if llama-server is answering."""
        return brain.available(self.config.get_path("brain.url"), timeout=1.0)

    def brain_status(self) -> dict:
        """Which brain a turn would use, and whether it can answer right now.

        The wording lives here rather than in the page so there is one place
        that decides what "ready" is called. The online check is deliberately
        only "is there a key" -- the page polls this every few seconds, and a
        network round trip per poll would spend battery to tell you something
        the next turn is about to find out anyway.
        """
        from . import cloud

        backend = brain.backend_for(self.config)
        if backend == "claude":
            ready = cloud.available(self.config)
            return {"backend": "claude", "ready": ready,
                    "label": "online" if ready else "no key"}
        ready = self.model_running()
        return {"backend": "llama", "ready": ready,
                "label": "model ready" if ready else "model stopped"}

    def stop_model(self) -> bool:
        """Kill llama-server and release the wake lock.

        Returns:
            True if something was killed.

        This is what "closing the app closes llama too" actually does. The wake
        lock goes with it -- leaving that held is the part that costs battery,
        and it is easy to forget.
        """
        killed = subprocess.run(["pkill", "-f", "llama-server"],
                                capture_output=True).returncode == 0
        subprocess.run(["termux-wake-unlock"], capture_output=True)
        log.info("model stopped (killed=%s), wake lock released", killed)
        return killed

    def touch(self) -> None:
        """Record that the page is still open, and re-arm the watchdog."""
        self.last_seen = time.monotonic()
        self.idle_handled = False

    def idle_seconds(self) -> float:
        """How long since the page last said anything."""
        return time.monotonic() - self.last_seen


def _watchdog(state: AppState, server: ThreadingHTTPServer) -> None:
    """Free what the page was using once it stops checking in.

    Runs in a daemon thread. The grace period wants to be comfortably longer
    than the heartbeat interval, or switching apps for a moment would kill the
    model you are about to use again.

    Two modes, chosen by ``ui.quit_on_exit``:

    * **Session mode** (default) -- stop the model *and* the server. Nothing is
      left running; you launch it again from Termux next time.
    * **App mode** (``quit_on_exit = false``) -- stop the model, keep serving.
      The 2.5 GB goes back, an idle HTTP server stays, and the home-screen
      icon works instantly instead of landing on "site can't be reached".

    App mode is why this loops instead of returning after it fires: the server
    outlives many page sessions, and each one has to be cleaned up after.
    """
    grace = float(state.config.get_path("ui.idle_shutdown_seconds", 45))
    stop_model = bool(state.config.get_path("ui.stop_model_on_exit", True))
    quit_server = bool(state.config.get_path("ui.quit_on_exit", True))

    while True:
        time.sleep(2)
        if state.shutting_down.is_set():
            return
        if state.idle_handled or state.idle_seconds() < grace:
            continue

        log.info("no heartbeat for %.0fs -- releasing", state.idle_seconds())
        if stop_model:
            state.stop_model()

        if quit_server:
            state.shutting_down.set()
            threading.Thread(target=server.shutdown, daemon=True).start()
            return

        # App mode: stay up, but don't stop the model again until the page
        # has come back and gone away once more.
        state.idle_handled = True


class Handler(BaseHTTPRequestHandler):
    """Serves the page and a small JSON API.

    Endpoints are dispatched by name: ``/api/status`` calls ``_api_status``.
    """

    state: AppState = None  # type: ignore[assignment]
    protocol_version = "HTTP/1.1"

    # -- plumbing ---------------------------------------------------------
    def log_message(self, fmt, *args):
        """Quieten the default per-request stderr spam."""
        log.debug(fmt, *args)

    #: Set once a request has proved it knows the token, so the reply can hand
    #: the browser a cookie and later requests need nothing in the URL.
    _grant_cookie = False

    def _send(self, code: int, body: bytes, ctype: str = "application/json") -> None:
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        # This page must never be embedded or reachable from a real website.
        self.send_header("X-Frame-Options", "DENY")
        self.send_header("Content-Security-Policy", "default-src 'self' 'unsafe-inline'")
        if self._grant_cookie:
            # HttpOnly so page scripts can't read it back out, SameSite=Strict
            # so no other origin can make the browser send it. A year, because
            # the whole point is that the home-screen icon keeps working.
            self.send_header(
                "Set-Cookie",
                f"nisos={self.state.token}; Path=/; Max-Age=31536000; "
                f"HttpOnly; SameSite=Strict")
        self.end_headers()
        self.wfile.write(body)

    def _json(self, code: int, payload: dict) -> None:
        self._send(code, json.dumps(payload, ensure_ascii=False).encode("utf-8"))

    def _cookie_token(self) -> str:
        """Pull the token out of the Cookie header, if it is there."""
        raw = self.headers.get("Cookie") or ""
        for part in raw.split(";"):
            name, _, value = part.strip().partition("=")
            if name == "nisos":
                return value
        return ""

    def _authorised(self, query: dict) -> bool:
        """Check the token, from any of the three places it can arrive.

        * ``X-Nisos-Token`` header -- what the page's own fetches use.
        * ``?t=`` -- how the launcher hands it over on the very first load.
        * a ``nisos`` cookie -- set on the first successful load, so a
          home-screen shortcut needs nothing in its URL afterwards.

        ⚠️ Every route into this server goes through here, *including the page
        itself*. That is deliberate and it is the difference between a real
        secret and a decorative one: `/` used to be served unauthenticated
        with the live token embedded in it, so any other app on the phone
        could fetch the page, scrape the token, and then drive an API that
        sends SMS. Loopback is not a boundary on Android.
        """
        supplied = (self.headers.get("X-Nisos-Token")
                    or query.get("t", [""])[0]
                    or self._cookie_token())
        ok = secrets.compare_digest(supplied or "", self.state.token)
        if ok:
            self._grant_cookie = True
        return ok

    def _body(self) -> dict:
        """Read and parse a JSON request body, tolerating an empty one."""
        length = int(self.headers.get("Content-Length") or 0)
        if not length:
            return {}
        try:
            return json.loads(self.rfile.read(length).decode("utf-8"))
        except (json.JSONDecodeError, UnicodeDecodeError):
            return {}

    # -- routing ----------------------------------------------------------
    def do_GET(self) -> None:  # noqa: N802 -- name fixed by BaseHTTPRequestHandler
        parsed = urlparse(self.path)
        query = parse_qs(parsed.query)
        path = parsed.path

        # The icon carries no secret and the manifest links to it, so it is the
        # one thing served without a token. Everything else needs one.
        if path == "/icon.svg":
            return self._serve_file("icon.svg", "image/svg+xml")

        if not self._authorised(query):
            return self._forbidden(path)

        if path in ("/", "/index.html"):
            return self._serve_file("index.html", "text/html; charset=utf-8")
        if path == "/manifest.webmanifest":
            return self._serve_manifest()
        if path.startswith("/api/"):
            return self._dispatch(path[5:], {})

        self._json(404, {"error": "not found"})

    def _forbidden(self, path: str) -> None:
        """Refuse, and say something useful if a person is reading it.

        An unauthorised API call gets JSON; an unauthorised page load gets a
        sentence, because the one way a person hits this is by opening a
        home-screen shortcut after clearing their browser data.
        """
        if path.startswith("/api/"):
            return self._json(403, {"error": "bad token"})
        self._send(403, (
            "<!doctype html><meta charset=utf-8>"
            "<title>nisos</title>"
            "<body style=\"font:15px ui-monospace,monospace;background:#080B12;"
            "color:#E7EEF7;padding:2rem;line-height:1.6\">"
            "<h1 style=\"font-size:17px\">Not this way in</h1>"
            "<p style=\"color:#8FA0B6\">This page needs the launch token, and "
            "this request had none. Start it from Termux &mdash; "
            "<code>bash ~/nisos/scripts/nisos-ui.sh</code> &mdash; and use "
            "<b>Add to Home Screen</b> from the page it opens.</p>"
            "</body>").encode("utf-8"), "text/html; charset=utf-8")

    def do_POST(self) -> None:  # noqa: N802
        parsed = urlparse(self.path)
        query = parse_qs(parsed.query)

        # Read the body BEFORE deciding anything. Replying to a keep-alive POST
        # without draining its body leaves unread bytes in the socket, and the
        # client sees the connection abort rather than the 403 you sent.
        body = self._body()

        if not parsed.path.startswith("/api/"):
            return self._json(404, {"error": "not found"})
        if not self._authorised(query):
            return self._forbidden(parsed.path)
        self._dispatch(parsed.path[5:], body)

    def _dispatch(self, name: str, body: dict) -> None:
        """Call ``_api_<name>``, or 404."""
        fn = getattr(self, f"_api_{name.replace('-', '_')}", None)
        if fn is None:
            return self._json(404, {"error": f"no endpoint {name}"})
        try:
            fn(body)
        except Exception as exc:  # noqa: BLE001 -- a bad request must not kill the server
            log.exception("endpoint %s failed", name)
            self._json(500, {"error": str(exc)})

    def _serve_file(self, name: str, ctype: str) -> None:
        target = UI_DIR / name
        if not target.is_file():
            return self._json(404, {"error": f"{name} missing"})
        data = target.read_bytes()
        if name == "index.html":
            # The page needs the token to talk to the API at all.
            data = data.replace(b"__NISOS_TOKEN__", self.state.token.encode())
        self._send(200, data, ctype)

    def _serve_manifest(self) -> None:
        """Enough of a manifest that Add to Home Screen opens it fullscreen."""
        self._send(200, json.dumps({
            "name": "nisos",
            "short_name": "nisos",
            "start_url": f"/?t={self.state.token}",
            "display": "standalone",
            "orientation": "portrait",
            "background_color": "#080B12",
            "theme_color": "#080B12",
            "icons": [{"src": "/icon.svg", "sizes": "any", "type": "image/svg+xml"}],
        }).encode(), "application/manifest+json")

    # -- endpoints --------------------------------------------------------
    def _api_ping(self, body: dict) -> None:
        """Heartbeat. The page calls this every few seconds while it's open."""
        self.state.touch()
        self._json(200, {"ok": True})

    def _api_status(self, body: dict) -> None:
        """Everything the status board shows."""
        self.state.touch()
        from . import __version__
        brain_status = self.state.brain_status()
        self._json(200, {
            "version": __version__,
            "brain": brain_status,
            # Kept for a page cached from an older release, which would
            # otherwise show "model stopped" forever on an online install.
            "model_running": brain_status["ready"],
            "language": self.state.config.get_path("general.language", "en"),
            "dry_run": bool(self.state.config.get_path("general.dry_run", False)),
            "actions": actions_module.action_names(),
            "history": list(self.state.history),
        })

    def _api_text(self, body: dict) -> None:
        """Run a typed command."""
        phrase = (body.get("text") or "").strip()
        if not phrase:
            return self._json(400, {"error": "no text"})
        self._json(200, self._run(lambda: loop.handle(
            phrase, self.state.config, self.state.context)))

    def _api_speak(self, body: dict) -> None:
        """Record from the microphone and run one turn."""
        self._json(200, self._run(lambda: loop.listen_and_handle(
            self.state.config, self.state.context)))

    def _api_stop_model(self, body: dict) -> None:
        """Stop llama-server but leave the UI running."""
        self.state.touch()
        self._json(200, {"stopped": self.state.stop_model()})

    def _api_quit(self, body: dict) -> None:
        """Stop the model, and the server too unless it is meant to stay up.

        Called by two different things that want two different outcomes:

        * The **Quit button** (``explicit``) always means all of it. You asked.
        * The page's ``pagehide`` **beacon** just means the page went away. In
          app mode that must not take the server with it, or the home-screen
          icon would be dead again by the time you next tapped it.
        """
        explicit = bool(body.get("explicit"))
        keep_serving = (not explicit
                        and not self.state.config.get_path("ui.quit_on_exit", True))

        self._json(200, {"ok": True, "serving": keep_serving})

        if self.state.config.get_path("ui.stop_model_on_exit", True):
            self.state.stop_model()

        if keep_serving:
            self.state.idle_handled = True   # nothing left for the watchdog
            return

        self.state.shutting_down.set()
        threading.Thread(target=self.server.shutdown, daemon=True).start()

    # -- shared -----------------------------------------------------------
    def _run(self, fn) -> dict:
        """Run one turn under the lock and record it in history.

        The lock matters: two overlapping requests would both try to open the
        microphone, and the second would either fail or capture silence.
        """
        self.state.touch()
        if not self.state.turn_lock.acquire(blocking=False):
            return {"error": "busy", "spoken": ""}
        try:
            turn = fn()
            entry = {
                "heard": turn.heard,
                "spoken": turn.spoken,
                "action": turn.action,
                "language": turn.language,
                "path": turn.path,
                "source": turn.source,
                "ms": round(sum(turn.timings.values())),
            }
            self.state.history.append(entry)
            return entry
        finally:
            self.state.turn_lock.release()


def serve(config, host: str = "127.0.0.1", port: int = 8765) -> str:
    """Start the web UI and block until it shuts down.

    Args:
        config: A :class:`nisos.config.Config`.
        host: Loopback. Do not change this -- see the security note above.
        port: Any free port.

    Returns:
        The URL that was served, once the server has stopped.
    """
    state = AppState(config)
    Handler.state = state

    server = ThreadingHTTPServer((host, port), Handler)
    server.daemon_threads = True

    threading.Thread(target=_watchdog, args=(state, server), daemon=True).start()

    url = f"http://{host}:{port}/?t={state.token}"
    log.info("nisos UI on %s", url)
    print(url, flush=True)          # the launcher reads this to open a browser

    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        state.shutting_down.set()
        if config.get_path("ui.stop_model_on_exit", True):
            state.stop_model()
        server.server_close()
        log.info("UI stopped")
    return url


def main(argv: list[str] | None = None) -> int:
    """Entry point for ``python -m nisos.web``.

    Kept here rather than in ``__main__.py`` so the UI can be launched
    independently of the CLI -- scripts/nisos-ui.sh reads the URL from the
    first line of stdout.
    """
    import argparse
    from . import config as config_module

    parser = argparse.ArgumentParser(prog="nisos.web", description="nisos web UI")
    parser.add_argument("--port", type=int, default=8765)
    parser.add_argument("--host", default="127.0.0.1",
                        help="loopback only; changing this exposes an API that can send SMS")
    parser.add_argument("--config")
    parser.add_argument("-v", "--verbose", action="store_true")
    args = parser.parse_args(argv)

    cfg = config_module.load(args.config)
    logging.basicConfig(
        level=logging.DEBUG if args.verbose else logging.INFO,
        format="%(asctime)s  %(levelname).1s  %(name)-12s  %(message)s",
        datefmt="%H:%M:%S",
        stream=__import__("sys").stderr,
    )
    serve(cfg, host=args.host, port=args.port)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
