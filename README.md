# nisos

An offline, bilingual voice assistant for Android. Greek and English, no
network, no API keys, no account. Built for a Galaxy S25 Ultra running Termux,
but nothing here is Samsung-specific beyond the back-tap trigger.

```
you:    «βάλε χρονόμετρο δώδεκα λεπτά και άναψε τον φακό»
nisos:  timer.set {"minutes": 12}   torch.on {}
nisos:  «Δώδεκα λεπτά, και ο φακός άναψε.»
        1.02 s   ·   net bytes: 0
```

## How it works

```
back-tap ──► record ──► race two recognisers ──► router ──┬─ hit ──► act ──► speak   ~1.0 s
                                                          │
                                                          └─ miss ─► model ─► act ─► speak   ~3.2 s
```

Three ideas carry the whole design.

**The router is the product.** Roughly 80% of what anyone says to a phone is
one of a few dozen phrases. Matching those with regex takes about five
milliseconds against the second and a half it costs to wake a language model.
That gap is the difference between an assistant you use daily and one you try
twice.

**The router is also the language detector.** Greek and English share no
characters, so a pattern written against Greek stems physically cannot fire on
English text. You don't detect the language and then route — you route against
both tables and whichever one hits tells you the language for free. Zero cost,
zero error rate. The same trick would fall apart on Spanish and English; it
works here because the alphabets are disjoint.

**Action names are always English.** You speak Greek, the system thinks in
`torch.on`, and only the spoken reply comes back Greek. Asking a 4B model to
*classify* Greek is a far easier job than asking it to *write* it — and for
anything the router catches, the Greek reply is a string you wrote yourself,
so the fast path has no model involvement at all.

## Requirements

| Part | Where from | Notes |
|---|---|---|
| Termux | **F-Droid only** | The Play Store copy is a dead fork whose packages no longer resolve |
| Termux:API | F-Droid, plus `pkg install termux-api` | Torch, SMS, clipboard, battery, speech |
| Python 3.11+ | `pkg install python` | Uses `tomllib`, so 3.11 is the floor |
| llama.cpp | Built locally | `llama-server` on `localhost:8080` |
| whisper.cpp | Built locally | **Multilingual** weights, not `.en` |
| Tasker | Play Store, ~£3 | **Optional.** Only do-not-disturb, the calendar and button triggers need it — [tasker/](tasker/README.md) |
| Greek offline packs | Settings → Google → Voice | Recognition *and* text-to-speech |

There are **no pip dependencies**. The whole program runs on the standard
library, so installing it in Termux never involves compiling a wheel against a
missing header.

## Install

Two taps and one paste. Everything after that is automatic.

**1.** Install [Termux](https://f-droid.org/packages/com.termux/) and
[Termux:API](https://f-droid.org/packages/com.termux.api/) — **from F-Droid, not
the Play Store.** The Play build is a dead fork whose packages no longer
resolve, and it's the most common way this fails on step one.

**2.** Open Termux and paste one line:

```bash
curl -sL https://raw.githubusercontent.com/petroukyriakoskalli/nisos/main/scripts/bootstrap.sh | bash
```

That's the whole install — full step-by-step in **[INSTALL.md](INSTALL.md)**.
No account, no token, no ZIP.

It runs unattended and nothing asks you a question until it's finished. Paste
it, put the phone down, come back to four taps.

The two native engines come prebuilt — 15 MB, cross-compiled for Android arm64
by [a workflow](.github/workflows/android-binaries.yml) and attached to each
release, checksum-verified and test-run before install. No compiler is needed
and there's nothing to clean up afterwards. If the download isn't usable for any
reason it falls back to compiling from source, which is the old ~50 minute path.

Piping a script into your shell deserves a look first — it's a public repo, so
read it before you run it if you'd rather:
[scripts/bootstrap.sh](scripts/bootstrap.sh). It checks you have the disk space, installs the
toolchain, builds llama.cpp and whisper.cpp statically, fetches the Whisper
weights, finds and downloads a model, writes your config, creates home-screen
shortcuts, starts the server and self-tests the result.

It is **resumable** — a 40-minute compile on a phone gets interrupted, so every
step records itself and re-running picks up exactly where it stopped.

Four things Android will not let a script do — installing APKs, granting
permissions, and downloading Google's offline voice packs. The installer opens
the right Settings screen for each and waits, so they're one tap rather than a
hunt through menus.

### The app

```bash
bash ~/nisos/scripts/nisos-ui.sh
```

Opens a local web UI in your browser. Use **Add to Home Screen** once and
Android gives it its own icon, launching it fullscreen with no browser chrome —
it looks and behaves like an app, without anyone having to build and sign an
APK.

A big Speak button, a typed-command box, and a scrolling history showing what
it heard, what it did and how long it took.

**Closing it stops the model.** That takes two mechanisms, because neither works
alone: a `pagehide` beacon handles the normal swipe-away instantly, and a
heartbeat watchdog catches everything the beacon can't — force-kills, crashes, a
flat battery. The wake lock is released with it.

⚠️ The UI binds to loopback, but that is *not* isolation on Android: every other
app on the phone can reach `127.0.0.1`, and this API can send SMS. So a random
token is generated per launch, handed to the browser in the URL, and required on
every call.

### A console, when the UI is what's broken

Install [Termux:Widget](https://f-droid.org/packages/com.termux.widget/), then
long-press the home screen → **Widgets → Termux → "nisos-console"**.

A text control panel — everything one keypress, no UI to go wrong:

```
  nisos   offline · ελληνικά + english
  ─────────────────────────────────────────

   ●  model      ready    Qwen3-4B-Q4_K_M.gguf
   ●  ears       android + whisper
   ●  voice      el-GR + en-GB
   ●  disk       3.1 GB used  · 212 GB free

   1  Speak a command          5  Start / restart the model
   2  Listen continuously      6  Stop the model
   3  Type a command           7  Diagnostics
   4  What can it do?          8  View log

   9  Install or repair        u  Check for updates
   c  Free up space            r  Roll back last update
   q  Quit
```

Everything from there is a single keypress — no commands to remember, no
typing on a phone keyboard. `nisos-speak` and `nisos-listen` are separate
shortcuts if you want to skip straight to voice.

If you also install [Termux:Boot](https://f-droid.org/packages/com.termux.boot/),
the model server comes back by itself after a reboot.

### Updates

Opt in during install and nisos checks once a day for a new tagged release,
then puts a normal Android notification on your phone with an **Install**
button. Or press **u** in the control panel whenever you feel like it.

Four rules it follows, because updating an offline tool deserves care:

- **Opt-in.** The check is the only thing in nisos that touches the network.
  Leave it off and nothing ever phones home.
- **Releases, not `main`.** You get tagged versions, not half-finished work.
- **Git, not a downloaded script.** Everything goes through git over HTTPS
  against a known public remote, so nothing runs that isn't already a
  reviewable commit.
- **Never silent, always reversible.** Nothing installs without you tapping
  Install, local edits block an update rather than being overwritten, and the
  previous version stays put — **r** rolls back.

### Afterwards

```bash
bash ~/nisos/scripts/postbuild.sh
```

Deletes the compiler and the build trees, installs a nightly log trim, and
prints before/after. Takes the install from about 7 GB down to roughly 3 GB,
most of which is the model itself.

## Using it

```bash
python -m nisos                       # record once, act, exit — what Tasker calls
python -m nisos --listen              # stay resident, act on every Enter
python -m nisos --text "torch on"     # skip the microphone entirely
python -m nisos --text "άναψε τον φακό" --dry-run
python -m nisos --check               # diagnose a broken install
python -m nisos --actions             # list everything it can do
```

`--text` is the important one: the whole pipeline — routing, language
detection, actions, replies — runs on a laptop. Only the audio is
phone-specific, which is why the test suite covers 81 cases without a phone
anywhere near it.

## Speed

Measured expectations on a Snapdragon 8 Elite, CPU inference, 6 threads.

| Path | English | Greek |
|---|---|---|
| Routed — "torch on", «άναψε τον φακό» | ~1.0 s | ~1.0 s |
| Reasoned — anything the router misses | ~3.2 s | ~3.5 s |

Greek costs nothing on the fast path, because those replies are strings you
wrote. It only shows up on the reasoned path, where Qwen's tokeniser needs two
to three times more tokens per Greek word.

## What it won't do

- **It can't look anything up.** No weather, no news, no scores. Offline means
  offline — the model is a language engine, not a knowledge base.
- **A 4B model is a capable intern with no memory.** It will nail "remind me at
  six" and summarise a note. It will not reliably hold a three-step plan
  together.
- **Its Greek is worse than its English on the reasoned path.** Routed replies
  are flawless because you wrote them; anything the model composes reads as
  competent-foreigner Greek. Add routes rather than accepting that.
- **Half-Greek half-English sentences trip it**, names especially. See the
  `[contacts]` alias table in `config.example.toml`.
- **The NPU sits idle.** Qualcomm's Hexagon is only reachable through the QNN
  SDK inside a native Android app; Termux can't touch it. Everything here is
  CPU.
- **The S Pen can't be your trigger.** Samsung dropped Bluetooth from it with
  the S25 generation, so Air Actions are gone. Back-tap or a cheap BLE button.

## Layout

| Module | What lives there |
|---|---|
| `nisos/normalise.py` | Accent stripping, final sigma, number words |
| `nisos/router.py` | The two regex tables — the fast path |
| `nisos/actions.py` | What it can do, and how it reaches Android |
| `nisos/replies.py` | What it says back, in both languages |
| `nisos/stt.py` | Racing Android's recogniser against Whisper |
| `nisos/brain.py` | llama-server client, grammar-constrained |
| `nisos/loop.py` | Orchestration — the only file that knows the order |
| `grammar/action.gbnf` | Makes malformed model output impossible |

See [EXTENDING.md](EXTENDING.md) for how to add a command. It's three small
edits and the test suite tells you if you've missed one.

## Tests

```bash
python -m pytest -q
```

81 tests, no phone required.

## Licence

MIT.
