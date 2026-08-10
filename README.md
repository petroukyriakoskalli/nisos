# nisos

A bilingual voice assistant for Android. Greek and English, built for a Galaxy
S25 Ultra running Termux, but nothing here is Samsung-specific beyond the
back-tap trigger.

```
you:    «βάλε χρονόμετρο δώδεκα λεπτά και άναψε τον φακό»
nisos:  timer.set {"minutes": 12}   torch.on {}
nisos:  «12 λεπτά, ξεκίνησα. Άναψα τον φακό.»
        1.02 s   ·   net bytes: 0
```

Every command it can name is heard, decided and acted on **on the phone** — the
turn above is the common case, and it sends nothing anywhere. Reasoning is the
one part that can leave: a phrase the router doesn't recognise goes either to a
local model or to the Claude API, and [you choose which](#two-brains).

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

### One sentence, more than one thing

The demo at the top is two commands, and for a long time it did one of them:
the torch lit and the timer was dropped without a word. A turn is now a
**list** of actions — in the router, in both brains' output, and in the log
line — run in order and answered in one sentence.

The rule that makes this safe is that a sentence is only split when **every**
piece routes on its own:

```
«άναψε τον φακό και βάλε χρονόμετρο 12 λεπτά»       → two actions
«στείλε στη Μαρία ότι άργησα και θα φάμε αργότερα»  → one message, «και» and all
```

The second one splits into a command and a fragment, the fragment routes
nowhere, so the split is thrown away and the whole thing is one message
exactly as before. A step that fails doesn't cancel the ones after it — they
were separate requests — and the reply says which half worked.

## Two brains

Only phrases the router misses reach a model at all. Which model is
`brain.backend` in `config.toml`:

| | `llama` | `claude` |
|---|---|---|
| Runs on | this phone | Anthropic's API |
| Needs | 2.5 GB and a ~40 min install | a key and a network |
| Per turn | free | costs tokens |
| Transcript | never leaves the device | sent to the API |
| Answers | a capable intern | far better, and much better Greek |
| Works on a plane | yes | no |

`auto` — the default — means online when a key is present, dropping back to
llama-server if the network is gone *and* it happens to be running. Pin either
name to remove the guessing.

**What actually leaves the phone, online:** the transcript of a phrase the
router missed, plus any stored facts that look relevant to it, plus the list of
action names. Nothing else — not the audio, not your contacts, and nothing at
all for a routed command. If that trade isn't one you want, set
`backend = "llama"` and it behaves exactly as it did before v0.3.0.

Going online:

```bash
python -m nisos --set-key      # paste it; stored 0600, never in config.toml
python -m nisos --check        # proves the key, the network and the model name
```

or press `k` in the console menu. A fresh phone can skip the local model
entirely with `NISOS_ONLINE=1 bash scripts/bootstrap.sh` — about 15 minutes
instead of 50, and no 2.5 GB download.

## Requirements

| Part | Where from | Notes |
|---|---|---|
| Termux | **F-Droid only** | The Play Store copy is a dead fork whose packages no longer resolve |
| Termux:API | F-Droid, plus `pkg install termux-api` | Torch, SMS, clipboard, battery, speech |
| Python 3.11+ | `pkg install python` | Uses `tomllib`, so 3.11 is the floor |
| An Anthropic API key | [console.anthropic.com](https://console.anthropic.com/settings/keys) | **Optional.** Only for the online brain — `python -m nisos --set-key` |
| llama.cpp | Built locally | **Optional with the online brain.** `llama-server` on `localhost:8080` |
| whisper.cpp | Built locally | **Multilingual** weights, not `.en` |
| Tasker | Play Store, ~£3 | **Optional.** Only do-not-disturb, the calendar and button triggers need it — [tasker/](tasker/README.md) |
| Greek offline packs | Settings → Google → Voice | Recognition *and* text-to-speech |

There are **no pip dependencies**. The whole program runs on the standard
library, so installing it in Termux never involves compiling a wheel against a
missing header. That is also why the Claude API is called over plain `urllib`
rather than with the official SDK: the SDK pulls in pydantic, whose core is
Rust, and Termux has no prebuilt wheel for it.

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

For the online brain, prefix it with `NISOS_ONLINE=1` — it then skips the 2.5 GB
model download and asks for an API key at the end instead. About 15 minutes
rather than 50. Everything below still applies; there is simply no local model
to build a config around.

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

**Make the icon behave like an app:**

```bash
bash ~/nisos/scripts/app-mode.sh on
```

Add to Home Screen makes a *bookmark*, and a bookmark cannot start a server —
so by default the icon lands on "site can't be reached", because nisos shuts
down forty-five seconds after you close the page. App mode splits those two
things apart: the model is still released the moment you close the page, but
a few MB of idle Python keeps listening, so the icon always opens instantly.

Turn battery optimisation off for Termux or Android will eventually kill the
background session — that's the one part the script can't do for you.

**Closing it stops the model.** That takes two mechanisms, because neither works
alone: a `pagehide` beacon handles the normal swipe-away instantly, and a
heartbeat watchdog catches everything the beacon can't — force-kills, crashes, a
flat battery. The wake lock is released with it.

⚠️ The UI binds to loopback, but that is *not* isolation on Android: every other
app on the phone can reach `127.0.0.1`, and this API can send SMS. So a random
token is generated per launch, handed to the browser in the URL, and required on
every call.

### A Speak button that is always there

```bash
bash ~/nisos/scripts/notification.sh on
```

Puts a permanent notification in the shade with a **Speak** button on it. Wake
the screen, pull down, tap, talk. Nothing to install, nothing running, no
battery cost — and it works from the lock screen.

That last part is the whole reason it exists. On a locked phone Android
delivers input to three places only: the system UI, the media session, and
gestures the system itself is assigned to. An app never sees a volume-key
combo, so no amount of Tasker configuration makes "volume up ×3" work from
your pocket — the press does not arrive. **The notification shade is system
UI**, so a button there does.

It doubles as a one-line status display: the content line becomes whatever
nisos last said, so a glance at the shade confirms it heard you correctly.
Tapping the notification body opens the web UI; the second button stops the
model and gives back 2.5 GB.

Add [Termux:Boot](https://f-droid.org/packages/com.termux.boot/) once and it
comes back after a reboot — `notification.sh on` writes the hook for you.

### A console, when the UI is what's broken

Install [Termux:Widget](https://f-droid.org/packages/com.termux.widget/), then
long-press the home screen → **Widgets → Termux → "nisos-console"**.

A text control panel — everything one keypress, no UI to go wrong:

```
  nisos   online · ελληνικά + english
  ─────────────────────────────────────────

   ●  brain      online        Claude API
   ●  model      stopped  Qwen3-4B-Q4_K_M.gguf
   ●  ears       android + whisper
   ●  voice      el-GR + en-GB
   ●  disk       3.1 GB used  · 212 GB free

   1  Speak a command          k  Online brain (API key)
   2  Listen continuously      5  Start / restart the model
   3  Type a command           6  Stop the model
   4  What can it do?          7  Diagnostics

   9  Install or repair        u  Check for updates
   c  Free up space            r  Roll back last update
   q  Quit
```

The first line and the `brain` row track `brain.backend`, so a glance tells you
whether the next hard question goes to the phone or to the network.

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
phone-specific, which is why the test suite covers 288 cases without a phone
anywhere near it.

### Appointments

```bash
python -m nisos --text "βάλε στο ημερολόγιο οδοντίατρο αύριο στις πέντε"
#  → οδοντίατρο, 11/08 στις 17:00.
python -m nisos --text "book a meeting with Nikos tomorrow at half past five"
#  → meeting with Nikos, 11/08 at 17:30.
```

Three things there are worth knowing, because each is a decision that could
have gone the other way:

- **A bare hour of one to seven means the afternoon.** «στις πέντε» is 17:00.
  Nobody arranges a dentist for five in the morning and says it that casually.
  «το πρωί» / "in the morning" overrides it, always.
- **The title keeps your spelling.** Patterns are matched against flattened
  text — lowercase, no accents — but a calendar entry is *written down*, and
  «οδοντιατρο» sitting in your diary is the plumbing showing. The title is
  recovered from the raw transcript.
- **No time means no appointment.** It says so rather than guessing an hour
  for something that goes in a diary.

It reads the appointment back — the day it landed on and the hour it picked
are the two things most likely to be wrong and the two you cannot see until
you open the calendar. Writing needs the Tasker task, and **if you imported
that before today, import it again**: an older copy has no `calendar.add`
branch. See [tasker/README.md](tasker/README.md).

## Speed

Measured expectations on a Snapdragon 8 Elite, CPU inference, 6 threads.

| Path | English | Greek |
|---|---|---|
| Routed — "torch on", «άναψε τον φακό» | ~1.0 s | ~1.0 s |
| Reasoned, `llama` — anything the router misses | ~3.2 s | ~3.5 s |
| Reasoned, `claude` | not yet measured on a phone | not yet measured |

Greek costs nothing on the fast path, because those replies are strings you
wrote. It only shows up on the reasoned path, where Qwen's tokeniser needs two
to three times more tokens per Greek word.

The online figures are deliberately blank rather than guessed. It runs at
`effort = "low"` with thinking on, which is the right setting for classifying
one spoken sentence, but the honest number is whatever your mobile connection
does — and nobody has timed it on the phone yet.

## What it won't do

- **It can't look anything up live.** No weather, no news, no scores. There is
  no action that reaches the web, on either brain — the online brain answers
  from what the model already knows, which is a different thing from being
  current.
- **A 4B model is a capable intern with no memory.** It will nail "remind me at
  six" and summarise a note. It will not reliably hold a three-step plan
  together — and now that a turn *can* be a plan, that gap shows: the router
  splits two commands perfectly whatever the brain, but a two-part instruction
  phrased in a way the router misses is where `llama` and `claude` visibly
  differ. The online brain does not have this limitation; it has a bill
  instead.
- **Its Greek is worse than its English on the reasoned path** — on `llama`.
  Routed replies are flawless because you wrote them; anything the local model
  composes reads as competent-foreigner Greek. Add routes rather than accepting
  that, or go online, where this mostly stops being true.
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
| `nisos/when.py` | «αύριο στις πέντε» → a real datetime |
| `nisos/router.py` | The two regex tables — the fast path |
| `nisos/actions.py` | What it can do, and how it reaches Android |
| `nisos/replies.py` | What it says back, in both languages |
| `nisos/stt.py` | Racing Android's recogniser against Whisper |
| `nisos/brain.py` | Which brain answers, and the llama-server client |
| `nisos/cloud.py` | The Claude API client — one forced tool call |
| `nisos/loop.py` | Orchestration — the only file that knows the order |
| `grammar/action.gbnf` | Makes malformed local-model output impossible |

See [EXTENDING.md](EXTENDING.md) for how to add a command. It's three small
edits and the test suite tells you if you've missed one.

## Tests

```bash
python -m pytest -q
```

288 tests, no phone and no network required — the API is stubbed, and the
Tasker XML is checked for the two characters that make it fail to import.

## Licence

MIT.
