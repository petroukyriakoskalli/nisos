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
| Tasker | Play Store, ~£3 | Alarms, calendar, SmartThings, the overlay |
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

**2.** Get the code onto the phone. Either clone it:

```bash
pkg install -y git && git clone https://github.com/petroukyriakoskalli/nisos ~/nisos
```

…or, if the repo is private and you'd rather not deal with a token, open it in
the phone's browser and use **Code → Download ZIP**.

**3.** Run the installer:

```bash
bash ~/nisos/scripts/bootstrap.sh
```

That's the whole install. It checks you have the disk space, installs the
toolchain, builds llama.cpp and whisper.cpp statically, fetches the Whisper
weights, finds and downloads a model, writes your config, creates home-screen
shortcuts, starts the server and self-tests the result.

It is **resumable** — a 40-minute compile on a phone gets interrupted, so every
step records itself and re-running picks up exactly where it stopped.

Four things Android will not let a script do — installing APKs, granting
permissions, and downloading Google's offline voice packs. The installer opens
the right Settings screen for each and waits, so they're one tap rather than a
hunt through menus.

### A button, not a command line

```
Install Termux:Widget from F-Droid
  → long-press the home screen → Widgets → Termux → "nisos"
```

That icon is now your assistant. `nisos-listen` and `nisos-check` are there
too. If you also install Termux:Boot, the model server comes back by itself
after a reboot.

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
