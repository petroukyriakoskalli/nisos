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


class AppState:
    """Everything the server keeps between requests.

    Attributes:
        config: The loaded :class:`nisos.config.Config`.
        token: Random per-launch secret required on every API call.
        history: Recent turns, newest last, capped so it can't grow forever.
        last_seen: Monotonic timestamp of the most recent heartbeat.
        turn_lock: Serialises turns -- two simultaneous recordings would fight
            over the microphone and produce nonsense.
        shutting_down: Set once shutdown has begun, so it only happens once.
    """

    def __init__(self, config):
        self.config = config
        self.token = secrets.token_urlsafe(24)
        self.history: deque = deque(maxlen=40)
        self.last_seen = time.monotonic()
        self.turn_lock = threading.Lock()
        self.shutting_down = threading.Event()
        self.context = loop.build_context(config)

    # -- model process ----------------------------------------------------
    def model_running(self) -> bool:
        """True if llama-server is answering."""
        return brain.available(self.config.get_path("brain.url"), timeout=1.0)

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
        """Record that the page is still open."""
        self.last_seen = time.monotonic()

    def idle_seconds(self) -> float:
        """How long since the page last said anything."""
        return time.monotonic() - self.last_seen


def _watchdog(state: AppState, server: ThreadingHTTPServer) -> None:
    """Shut the model down once the page has stopped checking in.

    Runs in a daemon thread. The grace period wants to be comfortably longer
    than the heartbeat interval, or switching apps for a moment would kill the
    model you are about to use again.
    """
    grace = float(state.config.get_path("ui.idle_shutdown_seconds", 45))
    stop_model = bool(state.config.get_path("ui.stop_model_on_exit", True))
    quit_server = bool(state.config.get_path("ui.quit_on_exit", True))

    while not state.shutting_down.is_set():
        time.sleep(2)
        if state.idle_seconds() < grace:
            continue

        log.info("no heartbeat for %.0fs -- shutting down", state.idle_seconds())
        state.shutting_down.set()
        if stop_model:
            state.stop_model()
        if quit_server:
            threading.Thread(target=server.shutdown, daemon=True).start()
        return


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

    def _send(self, code: int, body: bytes, ctype: str = "application/json") -> None:
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        # This page must never be embedded or reachable from a real website.
        self.send_header("X-Frame-Options", "DENY")
        self.send_header("Content-Security-Policy", "default-src 'self' 'unsafe-inline'")
        self.end_headers()
        self.wfile.write(body)

    def _json(self, code: int, payload: dict) -> None:
        self._send(code, json.dumps(payload, ensure_ascii=False).encode("utf-8"))

    def _authorised(self, query: dict) -> bool:
        """Check the per-launch token.

        Accepted from the ``X-Nisos-Token`` header or a ``t`` query parameter,
        the latter so the initial page load can carry it in the URL.
        """
        supplied = self.headers.get("X-Nisos-Token") or (query.get("t", [""])[0])
        return secrets.compare_digest(supplied or "", self.state.token)

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

        if path in ("/", "/index.html"):
            return self._serve_file("index.html", "text/html; charset=utf-8")
        if path == "/manifest.webmanifest":
            return self._serve_manifest()
        if path == "/icon.svg":
            return self._serve_file("icon.svg", "image/svg+xml")

        if path.startswith("/api/"):
            if not self._authorised(query):
                return self._json(403, {"error": "bad token"})
            return self._dispatch(path[5:], {})

        self._json(404, {"error": "not found"})

    def do_POST(self) -> None:  # noqa: N802
        parsed = urlparse(self.path)
        query = parse_qs(parsed.query)
        if not parsed.path.startswith("/api/"):
            return self._json(404, {"error": "not found"})
        if not self._authorised(query):
            return self._json(403, {"error": "bad token"})
        self._dispatch(parsed.path[5:], self._body())

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
        self._json(200, {
            "version": __version__,
            "model_running": self.state.model_running(),
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
        """Shut everything down: the model, the wake lock, and this server.

        Called both by the Quit button and by the page's ``pagehide`` beacon,
        so swiping the app away really does stop the model.
        """
        self._json(200, {"ok": True})
        self.state.shutting_down.set()
        if self.state.config.get_path("ui.stop_model_on_exit", True):
            self.state.stop_model()
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
