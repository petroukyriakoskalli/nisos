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

## Installing it

One permanent link, on the phone:

**<https://github.com/petroukyriakoskalli/nisos/releases/latest/download/nisos.apk>**

Every green build replaces what that URL serves, so installing and updating are
the same two taps. It goes on over the top: permissions and the stored API key
survive and nothing needs uninstalling.

⚠️ **That was untrue until v0.7.0.** The claim was there from the start, and it
was wrong. CI runners are ephemeral and carry no `~/.android/debug.keystore`, so
the Android Gradle Plugin generated a fresh one on every run — same alias and
password, **new key pair** — and Android refuses an APK as an update when the
signature differs. It went unnoticed because the first phone install had nothing
to replace. Builds now restore one keystore from the `NISOS_KEYSTORE_B64`
repository secret, so the signature is stable.

**Coming from a build before v0.7.0, you have to uninstall once.** The old
install is signed by a key that no longer exists anywhere, and nothing can update
it. That one uninstall wipes app-private storage, so the API key and settings go
with it; every update after it is two taps as advertised.

There is no way to skip the install itself; Android requires one for any code
change. What the rolling release removes is the five steps around it — the zip,
the GitHub sign-in, the hunt through workflow runs, the thirty-day expiry.

## Building it

Nothing needs building locally. **Every push builds and publishes** — see
[.github/workflows/android-app.yml](../.github/workflows/android-app.yml).

Locally, with Android Studio or a command-line SDK:

```bash
cd android
gradle wrapper          # optional; no wrapper jar is committed on purpose
gradle testDebugUnitTest
gradle assembleDebug
gradle installDebug     # onto a connected phone
```

The APK is debug-signed. It is sideloaded rather than shipped through a store, so
a release key would be ceremony.

The key is **not committed** though, which is the part that took a phone to work
out. It lives in the `NISOS_KEYSTORE_B64` secret and is written to
`android/nisos.jks` at build time. A signing key in a public repository is not
merely untidy: it lets anyone build an APK that Android accepts as a silent update
to yours, which matters now the app holds an API key and can send messages.

When the secret is absent — a fork, or a clean clone — the build falls back to the
plugin's generated debug key and still produces a working APK. It just cannot
update an install signed by the real one.

## What has actually been proven on a phone

Honest ledger, because everything else here is an argument rather than
evidence:

| | |
|---|---|
| ✅ It installs, launches and renders | first run, 2026-08-10 |
| ✅ The reactor ring draws correctly | arc, glow, inner rings, 72 ticks |
| ✅ `router only · no key` reports the real state | header |
| ❌ The controls were behind the navigation bar | fixed in build 5 — Android 15 draws edge to edge and the layout was not subtracting the system bars |
| ❌ The controls were drawn **on top of** the ring | fixed in v0.7.0, on a Samsung S25 Ultra — a fixed-size ring plus `Arrangement.SpaceBetween`, whose gap goes negative when nothing is spare |
| ⬜ Everything else | the eleven commissioning tests, not yet run |

No spoken turn has happened yet. The microphone, the recogniser, the voice, the
calendar and every action are unverified on hardware.

Both bugs found so far were **layout**, and neither was visible in the source.
That is worth saying plainly: the parts of this that a laptop can check — the
router, the parser, the reply tables — are the parts that were already tested.
What a phone tests is everything else.

## Layout

```
core/     no Android imports anywhere. Runs in a JVM unit test in a second.
android/  everything that touches the platform, in four files.
ui/       Compose. The reactor, and one screen.
```

| File | What lives there | Was |
|---|---|---|
| `core/Normalise.kt` | Accents, final sigma, number words | `nisos/normalise.py`, in Termux |
| `core/When.kt` | «αύριο στις πέντε» → a real datetime | `nisos/when.py` |
| `core/Router.kt` | The two regex tables — the fast path | `nisos/router.py` |
| `core/Replies.kt` | What it says back, in both languages | `nisos/replies.py` |
| `core/Actions.kt` | The catalogue, and the `Phone` interface | `nisos/actions.py` |
| `core/Money.kt` | Balances, from sources that cannot move money | new |
| `core/Cloud.kt` | The Claude client — one forced tool call | `nisos/cloud.py` |
| `core/Loop.kt` | Orchestration — the only file that knows the order | `nisos/loop.py` |
| `android/AndroidPhone.kt` | Every platform call | replaces `tasker/` entirely |
| `android/Voice.kt` | Text to speech, and the voice choice | `nisos/speech.py` |
| `android/Ears.kt` | The microphone | `nisos/stt.py` |
| `ui/Reactor.kt` | The ring, drawn on a Canvas | `nisos/ui/index.html` |
| `ui/SettingsScreen.kt` | The key, the money sources, the voice | `config.toml`, in Termux |
| `ui/LockScreen.kt` | Fingerprint or PIN on open | new — Termux could not ask |
| `android/Lock.kt` | What this phone can be asked for | new |

**The split is the point.** `core/` has no Android imports, which is what lets
the router, the time parser and the reply tables be tested the way they were in
Python — no emulator, no phone, about a second. If you find yourself importing
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
  other apps cannot read — that is the threat model this holds off. Against
  someone holding your unlocked phone, the answer is the app lock in
  **Settings → Opening the app**: a fingerprint, PIN or password on open, off
  until you turn it on. That is a screen that is not composed rather than
  encryption — it stops a person, not a forensic image of the device — and if you
  remove your phone's screen lock it stops applying, because there is then nothing
  to prove who you are with.

## What still needs doing

- Revolut, which has no personal API and needs an open-banking consent flow.
- An assist-gesture handoff that starts listening immediately rather than
  waiting for the button.
- The Wise request has still never been made against the real API. **Settings →
  Money → Check sources now** is how you find out; until someone taps it, that
  source is shipped unverified.
