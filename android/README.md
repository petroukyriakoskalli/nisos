# nisos, as an Android app

The same assistant, without Termux.

```
you:    «βάλε χρονόμετρο δώδεκα λεπτά και άναψε τον φακό»
nisos:  timer.set {"minutes": 12}   torch.on {}
nisos:  «12 λεπτά, ξεκίνησα. Άναψα τον φακό.»
```

## Why this exists

Termux was never the design. It was the only way to run llama.cpp and
whisper.cpp on a phone, and everything awkward about the old install traces
back to those two binaries: a 2.5 GB model download, a cross-compile in CI, a
fifty-minute setup, and a bridge made of Android broadcasts because a Termux
process cannot hold the permissions an assistant needs.

Sending reasoning to the Claude API removed the model. Once the model is gone
there is no native code left, and once there is no native code the whole thing
is an ordinary app — which can just *ask* for the permissions.

| | Termux | Here |
|---|---|---|
| Install | ~50 min, or ~15 online-only | install an APK |
| Download | 2.5 GB model + 2 binaries | a few MB |
| Torch, battery, volume, clipboard | shell out to `termux-api` | direct API calls |
| Do not disturb | broadcast → Tasker → a second task | one intent |
| Calendar, read **and** write | broadcast → Tasker → `content query` → a JSON file on `/sdcard` → poll for it | a cursor and an insert |
| Trigger from the lock screen | back-tap recipes, none of which really work | the phone's own assist gesture |
| Reasoning offline | yes, a local 4B model | **no** — this is the trade |

That last row is the honest cost, and it is a real one. See
[What it gives up](#what-it-gives-up).

## Building it

There is nothing to build locally unless you want to. **Every push builds an
APK in CI** and attaches it as an artifact — see
[.github/workflows/android-app.yml](../.github/workflows/android-app.yml).

```
Actions → "Android app" → the latest run → Artifacts → nisos-apk
```

Locally, with Android Studio or a command-line SDK:

```bash
cd android
gradle wrapper          # optional; no wrapper jar is committed on purpose
gradle testDebugUnitTest
gradle assembleDebug
gradle installDebug     # onto a connected phone
```

The APK is debug-signed. It is sideloaded from a CI artifact rather than
shipped through a store, so a release key would be ceremony — and a signing
secret in a public repository is a real cost with no benefit.

## Layout

```
core/     no Android imports anywhere. Runs in a JVM unit test in a second.
android/  everything that touches the platform, in four files.
ui/       Compose. The reactor, and one screen.
```

| File | What lives there | Ported from |
|---|---|---|
| `core/Normalise.kt` | Accents, final sigma, number words | `nisos/normalise.py` |
| `core/When.kt` | «αύριο στις πέντε» → a real datetime | `nisos/when.py` |
| `core/Router.kt` | The two regex tables — the fast path | `nisos/router.py` |
| `core/Replies.kt` | What it says back, in both languages | `nisos/replies.py` |
| `core/Actions.kt` | The catalogue, and the `Phone` interface | `nisos/actions.py` |
| `core/Cloud.kt` | The Claude client — one forced tool call | `nisos/cloud.py` |
| `core/Loop.kt` | Orchestration — the only file that knows the order | `nisos/loop.py` |
| `android/AndroidPhone.kt` | Every platform call | replaces `tasker/` entirely |
| `android/Voice.kt` | Text to speech, and the voice choice | `nisos/speech.py` |
| `android/Ears.kt` | The microphone | `nisos/stt.py` |
| `ui/Reactor.kt` | The ring, drawn on a Canvas | `nisos/ui/index.html` |

**The split is the point.** `core/` has no Android imports, which is what lets
the router, the time parser and the reply tables be tested the way the Python
was — no emulator, no phone, about a second. If you find yourself importing
`android.*` into `core/`, put it behind `Phone` instead.

## The voice

Not a clone of Paul Bettany. That needs a model trained on a named actor's
recordings, which is both a large download and somebody's likeness — not
something to put in a public repository.

What it does instead is the *register*. Google's TTS ships en-GB male voices
(`en-gb-x-rjs`, `en-gb-x-gbb`); pitched to 0.90 and slowed to 0.96 that is
most of what makes the delivery recognisable, and it is free, instant and
offline once downloaded. `Voice.ENGLISH_PREFERENCES` lists them closest-first
and takes whichever the phone has, falling back to any en-GB.

`-local` beats `-network` on purpose: the network voices sound marginally
better and add a round trip to every reply, which is the wrong trade for an
assistant whose whole argument is that it answers in a second.

⚠️ **Greek gets a different voice.** JARVIS is an English voice. «Άναψα τον
φακό» comes out of the best available `el-GR` voice and sounds like another
person, because it is one. Nothing free fixes that.

## The visuals

One screen, and deliberately not a chat transcript — an assistant you talk to
is not a messaging app, and a scrolling history makes the useful thing (what it
just did) the smallest element on screen.

The ring is drawn with Canvas primitives: arcs, a radial gradient, and 72
ticks. No image assets, no animation library. It scales to any screen,
recolours per state for free, and — the part that matters — its inner radius is
driven by the **actual microphone amplitude**, so it is reacting to your voice
rather than playing a canned animation at you.

| State | Colour | Says |
|---|---|---|
| Idle | deep blue, still | nothing is happening |
| Listening | cyan, breathing with your voice | it can hear you |
| Thinking | amber | it went to the network |
| Speaking | cyan | that is the reply |
| Failed | red | it did not do what you asked |

Under the reply, **one chip per action**. That exists because a turn can do two
things, and "it did both" is not something a single spoken sentence proves —
two chips is the proof.

## What it gives up

- **Reasoning needs a network.** There is no local model. A phrase the router
  misses, with no key or no signal, gets a spoken apology naming which of the
  two it was. The router still answers the great majority of commands entirely
  on the phone, and «άναψε τον φακό» sends nothing anywhere — but the honest
  summary is that this app is *less* offline than the Termux version was.
- **Automatic language detection.** The Python raced Android's recogniser
  against Whisper, because Android's has to be told which language to expect.
  Whisper is gone with the rest of the native code, so there is a language
  toggle. It matters less than it sounds: the router decides the language from
  which table matched, not from what the recogniser assumed, so the toggle
  corrects itself after one Greek sentence.
- **The key is not encrypted at rest.** It sits in app-private storage, which
  other apps cannot read — that is the threat model this holds off. It is not
  protection against someone with your unlocked phone.

## What still needs doing

- `money.total` — the reason the app exists, and not written yet.
- An assist-gesture handoff that starts listening immediately rather than
  waiting for the button.
- A settings screen. Today the key is set in code paths only; the voice
  preference is a field with no UI in front of it.
- Nothing here has run on a phone yet.
