# nisos

A bilingual voice assistant for Android. Greek and English, built for a Galaxy
S25 Ultra, but nothing here is Samsung-specific.

```
you:    «βάλε χρονόμετρο δώδεκα λεπτά και άναψε τον φακό»
nisos:  timer.set {"minutes": 12}   torch.on {}
nisos:  «12 λεπτά, ξεκίνησα. Άναψα τον φακό.»
        ~0.2 s   ·   net bytes: 0
```

Most of what you say never leaves the phone and never touches a model. A
keyword router handles it in about five milliseconds; only a phrase the router
doesn't recognise goes to the Claude API.

## How it works

```
Speak ──► Android recogniser ──► router ──┬─ hit ──► act ──► speak    ~0.2 s
                                          │
                                          └─ miss ─► Claude ─► act ─► speak
```

Three ideas carry the whole design.

**The router is the product.** Roughly 80% of what anyone says to a phone is
one of a few dozen phrases. Matching those with regex costs milliseconds
against a network round trip. That gap is the difference between an assistant
you use daily and one you try twice — and it is also the only thing standing
between you and a per-turn API bill.

**The router is also the language detector.** Greek and English share no
characters, so a pattern written against Greek stems physically cannot fire on
English text. You don't detect the language and then route — you route against
both tables and whichever one hits tells you the language for free. Zero cost,
zero error rate. The same trick would fall apart on Spanish and English; it
works here because the alphabets are disjoint.

**Action names are always English.** You speak Greek, the system thinks in
`torch.on`, and only the spoken reply comes back Greek. Anything the router
catches is answered with a string *you* wrote, so the fast path has no model
involvement and perfect grammar in both languages.

### One sentence, more than one thing

A turn is a **list** of actions, in the router, in the model's output, and in
the log line. It runs them in order and answers once.

The rule that makes this safe is that a sentence is only split when **every**
piece routes on its own:

```
«άναψε τον φακό και βάλε χρονόμετρο 12 λεπτά»       → two actions
«στείλε στη Μαρία ότι άργησα και θα φάμε αργότερα»  → one message, «και» and all
```

The second splits into a command and a fragment, the fragment routes nowhere,
so the split is thrown away and it stays one message. Cutting a message in half
would be a far worse bug than the one multi-action fixes, so the conservative
direction is the default. A step that fails doesn't cancel the ones after it —
they were separate requests — and the reply says which half worked.

## What it can do

| | |
|---|---|
| Torch | «άναψε τον φακό» · "turn on the torch" |
| Timer | «βάλε χρονόμετρο δώδεκα λεπτά» · "remind me in 5 minutes" |
| Calendar, read and write | «βάλε στο ημερολόγιο οδοντίατρο αύριο στις πέντε» · "next meeting" |
| Money | «πόσα λεφτά έχω» · "how much money do I have" |
| Messages | «στείλε στη Μαριλένα ότι άργησα» · "text Anna on whatsapp …" |
| Battery, time, volume, clipboard, do not disturb | «πόση μπαταρία έχω» · "volume 30" |
| Memory | «θυμήσου ότι η Μαριλένα είναι 99123456» |
| Anything else | goes to Claude, and comes back spoken |

## Installing it

There is no Play Store listing. Every push builds an APK in CI:

```
Actions → "Android app" → newest green run → Artifacts → nisos-apk
```

Unzip, install, grant the permissions it asks for, and paste an Anthropic API
key under `key` if you want the reasoning half. Full walkthrough and a set of
numbered commissioning tests: [android/README.md](android/README.md).

## Layout

```
android/app/src/main/java/app/nisos/
  core/      no Android imports anywhere — runs in a JVM test in a second
  android/   everything that touches the platform
  ui/        Compose: the reactor ring, and one screen
```

| File | What lives there |
|---|---|
| `core/Normalise.kt` | Accents, final sigma, number words |
| `core/When.kt` | «αύριο στις πέντε» → a real datetime |
| `core/Router.kt` | The two regex tables — the fast path |
| `core/Replies.kt` | What it says back, in both languages |
| `core/Actions.kt` | The catalogue, and the `Phone` interface |
| `core/Money.kt` | Balances, from sources that can't move money |
| `core/Cloud.kt` | The Claude client — one forced tool call |
| `core/Loop.kt` | Orchestration — the only file that knows the order |
| `android/AndroidPhone.kt` | Every platform call |
| `ui/Reactor.kt` | The ring, drawn on a Canvas |

**The split is the point.** `core/` has no Android imports, which is what lets
the router, the time parser and the reply tables be tested with no emulator and
no phone. If you find yourself importing `android.*` into `core/`, put it
behind the `Phone` interface instead.

See [EXTENDING.md](EXTENDING.md) for how to add a command. It's three small
edits and the test suite tells you if you've missed one.

## What it won't do

- **Reason offline.** There is no local model. The router answers most
  commands entirely on the phone, but a phrase it doesn't recognise needs a
  network and a key. Up to v0.4.0 this ran in Termux with a local 4B model;
  that version is in the history, on the `feature/multi-action` branch.
- **Look anything up live.** No weather, no news, no scores. Claude answers
  from what it already knows, which is a different thing from being current.
- **Detect the language by itself.** There is a toggle. It matters less than
  it sounds: the router decides the language from which table matched, not
  from what the recogniser assumed, so the toggle corrects itself after one
  Greek sentence.
- **Move any money.** The balance sources are read-only by construction — see
  [the note on that](EXTENDING.md#money).
- **Sound like JARVIS in Greek.** English gets an en-GB male voice pitched
  slightly low. Greek gets the best `el-GR` voice the phone has and sounds
  like a different person, because it is one.

## Tests

```bash
cd android && gradle testDebugUnitTest
```

No phone, no emulator and no network — the API is stubbed and everything in
`core/` is pure.

## Licence

MIT.
