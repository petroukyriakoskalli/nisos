# Installing nisos

An offline voice assistant that answers in Greek or English with the radios off.

**Two app installs, one paste, four taps.**

| | |
|---|---|
| Time | ~10 minutes (or ~50 if it has to compile) |
| Your attention | ~2 minutes of tapping, all at the end |
| Free space needed | ~4 GB (10 GB if it compiles) |
| Android | 7 or newer |
| Wi-fi | needed once, then never again |
| Cost | free |

> **Tip** — read this page on the phone you're installing on. Every code block
> below has a copy button in the corner. Tap it, then long-press in Termux and
> choose Paste. Nothing here needs typing.

---

## 1 · Install two apps

Both from **F-Droid**:

- **[Termux](https://f-droid.org/packages/com.termux/)** — the Linux shell everything runs in
- **[Termux:API](https://f-droid.org/packages/com.termux.api/)** — torch, SMS, microphone, voice

> ⚠️ **F-Droid only — not the Play Store.**
> The Play Store version is an abandoned fork stuck on an old Android release and
> its package servers no longer respond. Installing the wrong one is the single
> most common way this fails before it starts. If you already have the Play
> version, uninstall it first.

Both apps must come from the same source, or Android won't let them talk to each
other.

---

## 2 · Paste one line

Open Termux and paste this. It is the entire install.

```bash
curl -sL https://raw.githubusercontent.com/petroukyriakoskalli/nisos/main/scripts/bootstrap.sh | bash
```

It checks your free space, downloads the speech and language engines, fetches a
2.5 GB model, writes your settings, creates a home-screen shortcut, starts
everything, and tests itself.

**Nothing asks you a question until it's finished.** Paste it, put the phone
down, come back to four taps.

> **Why it's usually fast.** The two native engines are compiled once on
> GitHub's servers and downloaded ready-made — **15 MB, a few seconds**, instead
> of half an hour of compiling on your phone. They're checksum-verified and
> test-run before being installed. If that isn't possible for any reason (no
> release, bad checksum, an unusual device), it quietly compiles from source
> instead and the install takes ~50 minutes. Either way it works.

> **Safe to interrupt.** Paste the same line again. Every step records itself,
> so it resumes rather than starting over.

> ⚠️ **Stay on wi-fi.** The model download is 2.5 GB.

---

## 3 · Four taps it can't do for you

Android won't let a script grant permissions or download Google's voice packs.
The installer pauses and opens the right screen for each — tap, then come back.

1. **Battery → Unrestricted** — find Termux and set it to Unrestricted. Skip this
   and Android kills the assistant overnight.
2. **Greek speech recognition** — Offline speech recognition → add Ελληνικά.
   *Skip if you only want English.*
3. **Greek speaking voice** — Text-to-speech → install the Greek voice.
   *Skip if you only want English.*
4. **Microphone permission** — allow it for Termux:API, or it can't hear you.

> ⚠️ **Then test with airplane mode ON.**
> If the Greek pack didn't install properly, Android quietly sends your voice to
> the internet instead of telling you — which defeats the entire point. Turn the
> radios off and check it still understands you.

---

## 4 · Try it

Type first. If that works, the whole brain works and anything left is plumbing.

```bash
cd ~/nisos
python -m nisos --text "άναψε τον φακό"
```

The torch should light and it should answer «Άναψα τον φακό.»

Now your voice:

```bash
python -m nisos
```

---

## 5 · Make it a home-screen icon

Install **[Termux:Widget](https://f-droid.org/packages/com.termux.widget/)**, then
long-press your home screen → **Widgets** → **Termux** → pick **nisos**.

Tapping it opens the control panel:

```
  nisos   offline · ελληνικά + english
  ─────────────────────────────────────────

   ●  model      ready    Qwen3-4B-Q4_K_M.gguf
   ●  ears       android + whisper
   ●  voice      el-GR + en-GB
   ●  disk       3.1 GB used  · 212 GB free

   1  Speak a command      5  Start / restart model
   2  Listen continuously  6  Stop the model
   3  Type a command       7  Diagnostics
   4  What can it do?      8  View log

   9  Install or repair    u  Check for updates
   c  Free up space        r  Roll back update
```

From here you never type a command again.

---

## 6 · Reclaim about 4 GB

The compiler and build files aren't needed once it's built. Press **c** in the
control panel, or:

```bash
bash ~/nisos/scripts/postbuild.sh
```

Takes the install from about 7 GB down to roughly 3 GB — and 2.5 GB of that is
the model itself.

---

## Optional · a one-tap way in

The fastest nisos gets is not opening anything at all — tap something, speak,
hear the answer. **Start here**, because it needs nothing installed:

```bash
bash ~/nisos/scripts/notification.sh on
```

A permanent notification with a **Speak** button, tappable from the lock
screen. No Tasker, no hardware, no battery cost. Add
[Termux:Boot](https://f-droid.org/packages/com.termux.boot/) and it survives a
reboot.

If you want a physical trigger as well, run `bash ~/nisos/scripts/tasker-setup.sh`
and pick one. Full recipes are in [tasker/README.md](tasker/README.md); the
trade-off is the only thing worth knowing up front:

| Trigger | Works with the screen off? | Needs |
|---|---|---|
| **Notification Speak button** | **Yes** | nothing |
| Side key, double press | Yes — the system handles it | Samsung, nothing else |
| Back-tap (double) | Yes | Good Lock → RegiStar |
| Volume long-press | No | Tasker |
| Volume up ×3 | No | Tasker, and it moves the volume as it counts |

> ⚠️ **Volume keys cannot wake it from a locked phone.** Android delivers input
> to the system UI, the media session, and system-assigned gestures — and to
> nothing else. An app never sees the press, so no Tasker profile can fix it.
> The notification shade *is* system UI, which is why the first option works
> and the last two don't.

> ⚠️ **The S Pen can't do this.** Samsung removed Bluetooth from the S Pen with
> the S25 generation, so Air Actions are gone.

---

## Does everyone do all of this?

| Step | Who needs it |
|---|---|
| 1 · Two apps | Everyone. No way around it. |
| 2 · The paste | Everyone. This is the install. |
| 3 · Battery + microphone | Everyone. |
| 3 · Greek packs | Only if you want Greek. English-only users skip both. |
| 4 · Testing | Everyone, honestly. |
| 5 · Home icon | Optional — but it's the difference between a tool and a terminal. |
| 6 · Cleanup | Optional. Skip it if you have space to spare. |
| Back-tap | Samsung only. Other phones: use the widget or a Bluetooth button. |

On a non-Samsung Android everything works except the back-tap. On a phone with
less than 8 GB of RAM, use a smaller model — open an issue and it'll be swapped.

---

## When something breaks

First, always:

```bash
cd ~/nisos && python -m nisos --check
```

| Symptom | Cause and fix |
|---|---|
| `pkg: command not found` | Play Store Termux. Uninstall, reinstall from F-Droid. |
| Build stops partway | Android killed it. Paste the install line again — it resumes. |
| Dies overnight | Battery optimisation. Settings → Apps → Termux → Battery → Unrestricted. |
| Speaks English at Greek | Greek voice not installed. Settings → Text-to-speech. |
| Hears nothing | Microphone permission for Termux:API, and check Termux:API is installed. |
| Only works online | Offline Greek recognition pack missing. Re-check in airplane mode. |
| Do-not-disturb or calendar do nothing | Those two need Tasker. Run `bash ~/nisos/scripts/tasker-setup.sh`, then `scripts/tasker-test.sh` — it walks the links and stops at the broken one. See [tasker/README.md](tasker/README.md). Everything else, timer and volume included, works with Tasker uninstalled. |
| Timer opens the clock app instead of just starting | Some clock apps ignore `SKIP_UI`. Harmless — the countdown still starts. |
| Slow first command | Model reloading from storage. Hold a wake lock: `termux-wake-lock`. |

> ⚠️ **Expect a few rough edges.** No part of this has run on a real phone yet —
> the logic is tested, the Android side isn't. If something misbehaves, run
> Diagnostics and
> [open an issue](https://github.com/petroukyriakoskalli/nisos/issues) with the
> output.

---

## Updating

Opt in during install and nisos checks once a day for a new release, then puts a
normal Android notification on your phone with an **Install** button. Or press
**u** in the control panel whenever you like.

The check is the only thing in nisos that touches the network, it's off unless
you say yes, nothing installs without you tapping Install, and **r** rolls back
if a release misbehaves.
