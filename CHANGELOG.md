# Changelog

## v0.2.2 — 2026-07-27

A QA pass before the first real run on a phone. Three of these were live
defects; two of them were the kind you don't notice until the battery is flat.

### Fixed

- **The wake lock was never released.** `scripts/nisos.sh` took one, set an
  `EXIT` trap to give it back, and then ended in `exec python -m nisos` —
  which replaces the shell, so the trap never ran. Every turn, from every
  trigger, left the lock held. That is the single largest battery cost in this
  project and the trap existed specifically to prevent it. The `exec` is gone.
- **The web UI's token was decorative.** `/` was served with no token check
  and the live token embedded in the HTML, so any other app on the phone could
  fetch the page, scrape the token, and then drive an API that sends SMS and
  reads the clipboard. Loopback is not a boundary on Android — that was in the
  docstring, and the code did not honour it.

  The page, the manifest and the API are now all behind the token. It is
  persisted to `~/.nisos/ui-token` (0600, Termux private storage) instead of
  regenerated per launch, and a valid load hands back an HttpOnly
  `SameSite=Strict` cookie — so a home-screen shortcut keeps working without
  carrying a secret in its URL. Only `/icon.svg` is still open; it holds
  nothing. Nine regression tests cover it.
- **Double-tapping the notification's Speak button** started two turns fighting
  over one microphone. Now locked, with a two-minute staleness escape so a
  killed turn cannot wedge the button.
- **`llama.log` grew forever.** Nothing trimmed it; the nightly trim only knew
  about `nisos.log` and had to be wired into a Tasker task by hand. Both are
  now capped at 5 MB on every turn.
- **"Free up space" quietly broke self-updating** by uninstalling `git`, which
  `update.sh` needs to fetch tags. It keeps git now — ~30 MB against an
  updater that fails at "fetch failed".
- **The UI failed silently.** A dead server or an expired token reset the
  button with no message, which looks exactly like "it heard you and had
  nothing to say". It now says what happened and how to fix it.
- **Long dictated text** could push a transcript card sideways.

### Added

- **App mode** — `scripts/app-mode.sh on`, or `p` in the console. Add to Home
  Screen makes a bookmark, and a bookmark cannot start a server, so the icon
  normally lands on "site can't be reached". App mode keeps the idle server
  listening while still releasing the model when you close the page, so the
  icon opens instantly. The watchdog now loops rather than firing once, so
  every session gets cleaned up, not just the first.
- **`[ui]` is documented** in `config.example.toml`, which it never was.

## v0.2.1 — 2026-07-27

### Added

- **A Speak button that works from the lock screen.**
  `scripts/notification.sh on` puts a permanent notification in the shade with
  a **Speak** button on it, and a reboot hook so it comes back.

  This is the answer to "trigger it without unlocking", and the reason no
  hardware version of that works: on a locked phone Android delivers input to
  the system UI, the media session, and gestures the system itself is assigned
  to — and to nothing else. An app never sees a volume-key combo, so no Tasker
  profile can make one fire from your pocket. The notification shade *is*
  system UI.

  It doubles as a one-line status display — the content becomes whatever nisos
  last said. Tapping the body opens the web UI; the second button stops the
  model and gives back 2.5 GB.
- **The console can open the app.** `a` in `scripts/menu.sh` launches the web
  UI, which until now you had to know the script name to reach.

## v0.2.0 — 2026-07-27

The first release that was installed on a real phone, and it shows: most of
what follows is a fix for something that only turned up once Android was
involved.

### Added

- **A front end.** `scripts/nisos-ui.sh` serves a single page on loopback. Use
  *Add to Home Screen* once and Android gives it its own icon and launches it
  fullscreen — an app, without anyone building or signing an APK. A big Speak
  button, a typed-command box, and a history showing what it heard, what it
  did and how long it took.
- **Closing the page stops the model.** Two mechanisms, because neither works
  alone: a `pagehide` beacon makes the normal swipe-away instant, and a
  heartbeat watchdog catches force-kills, crashes and a flat battery.
- **Memory.** `nisos/memory.py` — facts and learned phone numbers, injected
  into the model prompt under a relevance cap so it never crowds out the
  command. `memory.remember` / `recall` / `forget` / `list`.
- **WhatsApp.** `whatsapp.send` opens a prefilled `wa.me` chat. It stops one
  tap short deliberately: sending without confirmation means driving an
  Accessibility Service, which breaks whenever WhatsApp changes its UI.
- **Prebuilt binaries.** Every release now carries `llama-server` and
  `whisper-cli` cross-compiled for Android arm64. Install went from ~50 min to
  ~10, peak disk 10 GB to 4, and no compiler is needed at all.
  `scripts/fetch-binaries.sh` verifies SHA256 *and executes each binary once*,
  because the wrong ABI fails at exec rather than at download, then falls back
  to compiling on any failure.
- **The Tasker bridge, both directions.** `tasker/` — an importable task for
  do-not-disturb and the calendar, a hardware-button trigger that takes one
  turn with no UI at all, and `scripts/tasker-test.sh` to prove each link
  separately.

### Changed

- **The timer and the volume no longer need Tasker.** `SET_TIMER` is a
  platform intent any clock app answers, with no permission at all;
  `termux-volume` reports each stream's maximum so scaling a percentage is
  arithmetic. Both now work with Tasker uninstalled, and both gained unit
  tests. Only `dnd.on` and `calendar.next` still cross the bridge, because
  Termux cannot hold the permissions they need.
- **Released binaries are stripped.** `CMAKE_BUILD_TYPE=Release` does not
  strip under the NDK toolchain — the first build shipped a 174 MB
  `llama-server`. `llvm-strip --strip-all` takes it to 12.8 MB, whisper 19 MB
  to 2.0 MB, 15 MB in total.
- **Unattended install.** `scripts/bootstrap.sh` is one paste, resumable, and
  opens the four Settings screens Android will not let a script touch.

### Fixed

- **`pkg upgrade` before installing anything.** Termux repos are rolling, so
  an older base image plus new packages is an ABI mismatch — and the symptom
  is `curl` dying with a missing symbol, which kills both install paths at
  once. `pkg update` alone does not fix it; it only refreshes the lists.
- **The wake lock is no longer held forever.** It is taken around a turn and
  always given back. A permanently held lock was the single biggest battery
  cost here, and it only ever saved a ~10 second model reload.
- **`calendar.next` waited on a path Tasker cannot write.**
  `/data/data/com.termux/files/home` is private app data; no amount of correct
  Tasker configuration could have put a file there. It is `/sdcard` now.
- **`calendar.next` reported stale answers.** It read whatever answer file was
  lying around, so a Tasker task that failed silently reported yesterday's
  meeting as today's. The previous answer is deleted before asking, and the
  error names the likely cause.
- **Errors are no longer hidden.** Several `>/dev/null` redirections around
  `apt`, `git clone` and the NDK build turned one-line diagnoses into
  mysteries. Two separate install failures in one day traced back to this.
- **Built for 16 KB pages**, so the binaries load on current Android.

### Known gaps

- The web UI has been verified by 17 HTTP tests and code review, but has
  **never been opened on a phone**.
- The two Tasker XML files have **never been imported**. The DND mode spinner
  is the one value that could not be checked off-device.
- Volume-key triggers do not fire with the screen off — Android routes those
  keys to the audio system. Use the side key or a back-tap for that.

## v0.1.0 — 2026-07-27

First release. Router, model, actions, replies, both languages, and an
installer.
