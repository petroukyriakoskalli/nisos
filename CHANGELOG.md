# Changelog

## v0.7.0 — 2026-08-10

### Fixed

- **The controls were drawn on top of the ring.** Found on a Samsung S25 Ultra,
  and the second layout bug that only a phone could show.

  Two things caused it together. `Reactor` was a hard-coded **260 dp**, so it
  could not give way; and the screen's outer `Column` used
  `Arrangement.SpaceBetween`, which divides the *leftover* space between its
  children. When there is no leftover the share it hands out goes **negative**,
  so the three blocks were laid on top of one another — and because a `Column`
  draws in order, the controls were painted over the ring and over the text.

  It needed a trigger, which is why the arithmetic looks fine on paper: opening
  `type` or `key` added ~118 dp each, and the soft keyboard takes ~300 dp more
  (`safeDrawing` covers the IME as well as the system bars). Any of those turns
  spare space negative.

  The middle block now carries the weight, so it absorbs whatever is spare and
  shrinks when there is none — there is never a negative gap to distribute. The
  ring is measured against the room that actually exists and passed a diameter;
  below 120 dp it is not drawn at all, on the grounds that a ring over the top
  of the text is worse than no ring. Anything still too tall scrolls, which is
  the one outcome that cannot hide a control behind a graphic. The spoken reply
  is capped at three lines for the same reason: it sits above the Speak button
  with no weight of its own, so a long one used to push the button off-screen.

### Added

- **The app can require a fingerprint, PIN or password to open.** Off until you
  turn it on, in **Settings → Opening the app**.

  This closes a gap the README has always admitted to. App-private storage keeps
  the API key, your configured balances and your calendar away from *other apps*;
  it does nothing at all about somebody holding your phone while it is unlocked.

  **Turning it on authenticates first**, and that ordering is the whole safety
  argument rather than a nicety: you cannot enable a lock you are unable to open,
  so there is no path to being shut out of a sideloaded app that has no recovery
  short of clearing its data and losing the key. Turning it off asks too —
  otherwise one moment with an unlocked phone removes the lock permanently.

  It re-asks after **ten seconds** away, not immediately. Zero would re-lock
  every time a system dialog took the foreground, which is irritating enough to
  get the feature switched off altogether; the phone's own lock screen is what
  covers the first ten seconds after you put it down. The decision is taken on
  the way back **in** rather than on the way out, which also keeps the lock
  screen from being what lands in the recents preview.

  Two honest limits. **Removing your phone's screen lock removes this**, because
  there is then nothing to prove who you are with, and an app you can never open
  again is not a security feature — the settings screen says so in place. And the
  gate is a screen that is not composed, not encryption: the threat it addresses
  is a person, not a forensic image of the device.

  One new dependency, `androidx.biometric` — still the platform rather than a
  convenience library. It is Android's own fingerprint/PIN dialog, and it turns
  what would be three branches over deprecated `FingerprintManager` and
  `KeyguardManager` into one call that asks the device which authenticators it
  will actually accept. `MainActivity` is a `FragmentActivity` now because the
  prompt is a real dialog fragment.

- **An appointment is shown to you before it is written.** `calendar.add` no
  longer happens when you say it. The turn reads the event back as a question —
  *"Add dentist — Tuesday 11/08 at 17:00?"* — and waits for **Add** or **No**.
  Nothing has touched the calendar until you tap.

  It is the only action that waits, and the reasoning is specific rather than
  general caution. The torch is undone by saying the opposite. A message is gone,
  but you dictated its text. An appointment is a **silent edit to something you
  will not open until the day it matters**, made from a time phrase that had to
  be *interpreted* — and if «αύριο στις πέντε» lands on the wrong day, you find
  out by missing it.

  The question names the **weekday**, which is the whole reason it is worth
  reading: "11/08" does not tell you which day that is, and working it out is
  exactly the work nobody does. "Tuesday" is checked at a glance. It is named in
  whichever language is being spoken, so a Greek reply says «Τρίτη».

  **The preview and the write go through one parser.** `proposeEvent` is called
  by both, so the card cannot show one event while the action files another. A
  confirmation built from a second parser is theatre — it would agree with you
  right up until it mattered, and you would have stopped checking by then. There
  is a test whose entire job is to assert that the phone was **not** called.

  Two smaller decisions. Arguments that do not parse fail *immediately* rather
  than after the tap, because approving something that was never understood makes
  the failure look like the approval caused it. And a turn that does two things
  only holds back the half that needs holding: «άναψε τον φακό και βάλε ραντεβού
  αύριο στις πέντε» lights the torch now and asks about the appointment, since
  making the reversible half wait is friction bought for nothing.

  How it generalises: membership of `PREVIEW` in `Actions.kt` is what makes an
  action need approval, and that same map supplies the fields it is described
  with — so an action cannot be marked as needing confirmation without also
  providing the words to confirm it with. `missingConfirmations()` fails a test
  if the phrasing is missing in either language, the same way `missingReplies()`
  already did for actions.

- **A settings screen.** Three of the four money sources were unreachable:
  Wise needs a token and the bank sources need sender names, and both had
  setters in `Memory` with no UI in front of them — so «πόσα λεφτά έχω» could
  only ever count figures dictated by hand.

  It also does the thing a settings screen is usually bad at, which is letting
  you **check** rather than only set. **Check sources now** reads every source
  and reports each one separately, because the spoken total says "3 of 4"
  without saying *which* four, and a reply you have to trigger by talking is the
  wrong place to debug a token — especially the Wise one, which has still never
  been tried against the real API. A dead token, a revoked permission and a bank
  that simply has not texted you lately are now three visibly different
  outcomes instead of one silence.

  `READ_SMS` is still requested only when you name your first bank sender, never
  at launch, and the screen says plainly when senders are configured but the
  permission is not granted — previously that combination failed silently.

- **The voice is adjustable, and audible before you commit to it.** The name,
  pitch and speed were fields on `Voice` with sensible defaults and nothing able
  to reach them; they are now persisted in `Memory` and survive a restart, which
  they did not before. **Say something** speaks a sample, because
  `en-gb-x-rjs-local` tells you nothing about how a voice sounds, and tuning two
  interacting sliders by waiting for the next real reply is no way to do it.
  Every value has a `reset` back to the recommended one, the range is narrow
  enough that a slider cannot make it unlistenable, and the list puts en-GB
  first — a phone carries a dozen English voices and sorting by name buries the
  two this is arguing for under Australian.

### Changed

- **The API key moved out of the bottom row and into settings.** It is
  configuration rather than an operation, the header already announces when it
  is missing, and that row was one of the things crowding the bottom of the
  screen. The way in to settings is the header's own state label — the door next
  to the thing that makes you want it.

- **`SmsBalanceSource.addSender` is in `core/`, and tested.** The rule is that a
  bank cannot be added twice in a different case: Android's SMS query is
  `ADDRESS LIKE '%name%'` and therefore case-blind, so "boc" alongside "BOC"
  would be two sources reading the same message and the bank counted **twice**
  in the total. Two sources answering reads as better-attributed rather than
  worse, so nobody would ever question the number. It went into `core/` rather
  than the screen for the usual reason: a rule that cannot be tested is a rule
  you find out about later.

- `Memory.forgetBalance`, so a manual figure can be taken back out. A stale one
  is worse than a missing one — it still counts, so the total keeps quoting a
  policy you cashed in months ago.

## v0.6.0 — 2026-08-10

### Added

- **`money.total`.** Balances, from sources that cannot move money — a
  read-only token, a message the bank already sent you, a figure you typed in.
  That constraint is the design and not a caveat on it: a credential that can
  move money, sitting on a phone behind a screen lock, is a different risk
  category from a token that can only read a number, and the assistant gains
  nothing from the difference. It only ever needed to read.

  Cyprus banks are covered by parsing their balance SMS (no personal API, but
  they text you after every card transaction and the message is already on the
  phone). Wise by a read-only personal token. myEurolife by a figure you tell
  it, because insurance and pension portals have no public API and it is that
  or nothing. Revolut needs open banking and is not built.

  Two refusals worth knowing. **One source cannot take the total down** — a
  source that can't answer is skipped, and one that throws is caught, because
  one unreachable bank turning "you have €12,340" into "that didn't work" for
  three reachable accounts is the worst outcome available. And **a partial
  total never sounds complete**: three of four says "(3 of 4)" out loud, a
  stale reading gets dated, and another currency is mentioned rather than
  converted, since converting needs a live rate this app must not guess.

  The SMS parser requires a balance *word* within 40 characters of the amount,
  so "You spent EUR 45.20 at LIDL" yields nothing on purpose — a transaction
  text names two numbers and taking the wrong one is a confidently wrong
  balance. `READ_SMS` is requested only when you first name a bank sender,
  never at launch.

### Removed

- **Termux, and everything that existed only to work around being a terminal
  program**: the Python package and its 288 tests, the Tasker bridge, twelve
  shell scripts, the GBNF grammar, the llama.cpp/whisper.cpp cross-compile
  workflow, and the config file that configured all of it.

  None of it was bad code. It was the right design for a constraint that no
  longer exists — reasoning had to run on the phone, so a 4B model had to run
  on the phone, so it had to be Termux, so every permission Android reserves
  for apps had to be borrowed from Tasker through a broadcast that cannot
  return a value. Take away the local model and the whole chain unwinds.

  ⚠️ This is ahead of the evidence: the gate was an APK on the phone with the
  Greek, multi-action and calendar tests passing, and that has not happened.
  Recovery is `git checkout feature/multi-action`, which is pushed.

## v0.5.0 — 2026-08-10 — an Android app

### Added

- **`android/` — nisos as an APK.** Same assistant, no Termux, no Tasker.

  The online brain is what made this possible, and it is worth being precise
  about why. Termux was never the design; it was the only way to run
  llama.cpp and whisper.cpp on a phone. Every awkward thing about the install
  traced back to those two binaries — the 2.5 GB download, the cross-compile
  in CI, the fifty-minute setup. Send reasoning to the API and there is no
  native code left; with no native code this is an ordinary app, and an
  ordinary app can simply **ask for the permissions**.

  That deletes the whole `tasker/` bridge in one go. `READ_CALENDAR` and
  `WRITE_CALENDAR` are two lines in a manifest, and they replace: a broadcast,
  a JavaScriptlet, an answer file on `/sdcard`, a polling loop, the
  stale-answer trap, the `ok`-field handshake, and an entire class of failure
  where Tasker silently was not running. `calendar.next` is a cursor.
  `calendar.add` is an insert.

- **`core/` has no Android imports anywhere.** That is the load-bearing rule of
  the port: the router, the time parser, the reply tables and the action
  registry all run in a plain JVM unit test in about a second, exactly as the
  Python does. The platform lives behind one `Phone` interface, which the
  tests replace with a recorder. Every assertion the Python suite makes about
  routing, splitting, appointments and stitched replies is asserted again in
  Kotlin — the tables were retyped into another language, and a regex that
  quietly stopped matching would be invisible until you were standing in a
  dark room asking for the torch.

- **A voice with the right register.** Not a clone of a named actor — that
  needs a model trained on their recordings, which is a large download and
  somebody's likeness, and does not belong in a public repository. What it
  does is pick Google's en-GB male voices (`en-gb-x-rjs`, `en-gb-x-gbb`),
  pitch to 0.90 and slow to 0.96, which is most of what makes that delivery
  recognisable. Local voices are preferred over network ones on purpose: the
  network voices sound marginally better and add a round trip to every reply.
  ⚠️ Greek gets the best `el-GR` voice available and sounds like a different
  person, because it is one.

- **A reactor HUD, drawn rather than shipped.** Arcs, a radial gradient and 72
  ticks on a Canvas — no image assets, no animation library. The inner ring is
  driven by the **actual microphone amplitude**, so it reacts to your voice
  instead of playing a canned animation. Five states with distinct colours,
  because "is it hearing me", "did it freeze" and "did that fail" are
  different questions and a spinner answers none of them. Under the reply,
  one chip per action — a turn can now do two things and a single spoken
  sentence does not prove it did both.

- **CI is the build machine.** `.github/workflows/android-app.yml` runs the
  unit tests, assembles a debug APK and uploads it as an artifact. Debug-signed
  deliberately: this is sideloaded, not shipped through a store, so a release
  key would be ceremony and a signing secret in a public repository is a real
  cost. No Gradle wrapper jar is committed for the same reason — an unreadable
  binary in a public repo, replaced by one line in the workflow.

### What it gives up

Worth stating plainly, because the README could easily imply otherwise:

- **Reasoning now needs a network.** There is no local model in the app. The
  router still answers the great majority of commands entirely on the phone
  and «άναψε τον φακό» sends nothing anywhere — but this app is *less* offline
  than the Termux version. `backend = "llama"` over there remains the fully
  private option.
- **Automatic language detection.** Whisper went with the rest of the native
  code, so there is a language toggle. It matters less than it sounds: the
  router decides the language from which table matched, not from what the
  recogniser assumed, so the toggle corrects itself after one Greek sentence.
- **The key is not encrypted at rest.** App-private storage keeps other apps
  out, which is the threat this needs to hold off. It is not protection
  against someone holding your unlocked phone.

### Not verified

- **Nothing here has run on a phone**, and nothing has been compiled on this
  laptop either — there is no Android SDK on it, which is why CI exists. The
  first green build is the first time any of this Kotlin has been checked by a
  compiler rather than by reading.
- The Python program is unchanged and remains the reference implementation.

## v0.4.0 — 2026-08-10

### Added

- **A turn can do more than one thing.** The demo at the top of the README —
  «βάλε χρονόμετρο δώδεκα λεπτά και άναψε τον φακό» — lit the torch and dropped
  the timer without a word. It had done that since the first commit, in the one
  example everybody reads first.

  A turn is now a **list of steps**, all the way through: `Match.steps` out of
  the router, `Decision.steps` out of either brain, `Turn.steps` in the log
  line, run in order and answered in one sentence. `action` and `args` stay
  exactly where they were on all three, pointing at the first step, so nothing
  that reads them had to change and a one-action turn is byte-for-byte what it
  always was — including the spoken reply, which is why `replies.stitch`
  returns a single part untouched rather than joining it with itself.

  The safety of the whole thing rests on one rule: **a sentence is split only
  when every piece routes on its own.** «στείλε στη Μαρία ότι άργησα και θα
  φάμε αργότερα» splits into a command and a fragment, the fragment routes
  nowhere, the split is thrown away, and it stays one message with «και» inside
  it. Cutting a message in half would be a far worse bug than the one this
  fixes, so the conservative direction is the default one. Beyond four pieces
  it does not split at all — five orders in one breath is far likelier to be a
  sentence with five «και»s in it.

  A step that fails does not cancel the ones after it. They were separate
  requests, and there is no reason for the torch to stay off because a message
  failed; the reply says which half worked.

  Both brains had to be able to *express* a second action, or the model has
  nowhere to put one and the ceiling is the schema rather than the model. The
  GBNF root is now a list (with the single-object form still legal, because a
  4B model that has seen the old shape a thousand times will reach for it), and
  the tool schema takes `steps`. One reader, `brain.steps_from`, is shared by
  both, so the two brains cannot disagree about the same JSON.

- **`calendar.add`** — «βάλε στο ημερολόγιο οδοντίατρο αύριο στις πέντε»,
  "book a meeting with Nikos tomorrow at half past five". The write half of a
  bridge that could only read.

  The interesting part is not the Tasker call, it is **`nisos/when.py`**:
  spoken time into a real datetime, pure and testable on a laptop, which is
  where the judgement calls live. A bare hour of one to seven means the
  afternoon — «στις πέντε» is 17:00, because nobody arranges a dentist for five
  in the morning and says it that casually. A day with no time gets 09:00. A
  time with no day is the next time that time happens. Each of those is one
  sentence you can say out loud when it gets something wrong, which is worth
  more than being clever.

  The title is subtracted rather than captured: everything that is neither the
  instruction nor the time is the name of the appointment. That is what makes
  «βάλε ραντεβού με τον γιατρό αύριο» and "put the dentist in my calendar
  tomorrow" both work without agreeing on word order.

  It reads the appointment back — the day it landed on and the hour it picked
  are exactly the two things most likely to be wrong and the two you cannot see
  until you open the calendar.

- **`Normalised`**, a string that remembers its own spelling. Patterns are
  matched against flattened text, which is right for *matching* and wrong for
  anything that gets **written down**: a calendar entry titled «οδοντιατρο»
  instead of «οδοντίατρος» is the assistant's plumbing leaking into your diary.
  Argument builders can now ask for the user's own words back. Word positions
  rather than character offsets, because flattening is not length-preserving
  but never splits or joins a word.

- **The clock now travels with every reasoned request.** "Tomorrow at five" is
  unanswerable otherwise — a model has no clock. It goes in the *user* turn on
  both brains, never the system block, which would break the API's prompt cache
  on every single turn and llama-server's `cache_prompt` once a minute.

- **`tests/test_tasker.py`.** The Tasker XML cannot contain a `<` or an `&` —
  one is a tag, the other is an entity, and either makes the task fail to
  import while looking like perfectly ordinary JavaScript. Escaping is not the
  fix, because the README also tells you to paste that block in by hand. There
  is now a test, and a second one asserting every action nisos broadcasts has a
  branch waiting for it.

### Changed

- The log line names every action a turn ran (`torch.on + time.read`). A log
  that shows only the first is precisely how a dropped second one stays
  invisible for eleven versions.
- `calendar.next` and `calendar.add` share the answer-file wait, and the
  stale-answer delete that goes with it.

### Not verified

- **Nothing here has run on a phone**, and `calendar.add`'s Tasker branch is
  the piece that cannot be checked anywhere else. It inserts through the
  calendar provider using Tasker's shell — the same mechanism `calendar.next`
  already used, chosen so there is one route to verify rather than two. Tasker's
  own **Calendar Insert** action is the fallback if a device refuses it, and
  `tasker/README.md` has the two-minute hand-built task and the one line to
  change.
- ⚠️ **Re-import `NisosAction`.** An older copy has no `calendar.add` branch;
  it will answer, and the answer will not say `ok`, so appointments fail with
  "that didn't work" rather than silently going nowhere.

## v0.3.0 — 2026-08-10

### Added

- **An online brain.** A phrase the router misses can now go to the Claude API
  instead of the local 4B model. `brain.backend` chooses: `claude`, `llama`, or
  `auto` — the new default, meaning online when a key is present and dropping
  back to llama-server if the network is gone *and* it happens to be running.
  Set `backend = "llama"` for exactly the old behaviour.

  What this buys is answers that are actually good and Greek that is actually
  Greek — the reasoned path's weakest point since the beginning. What it costs
  is a network, a key, tokens per turn, and the transcript of an unrecognised
  phrase leaving the phone. Routed commands are unaffected: ~80% of what you say
  still never reaches a model of any kind, and «άναψε τον φακό» sends nothing
  anywhere.

  The online equivalent of the GBNF grammar is a **forced `tool_choice`**: one
  tool whose input schema is the action object, and the model is required to
  call it. Same guarantee reached a different way — it cannot answer in prose
  and cannot invent a verb outside the enum, which is generated from the action
  registry so a new action needs no second edit here. Raw `urllib`, not the
  official SDK: the SDK needs pydantic, whose core is Rust, and Termux has no
  wheel for it — the no-dependencies rule stands.

- **`python -m nisos --set-key`** (and `--forget-key`), plus **`k`** in the
  console menu. The key is read from **stdin**, never an argument — an argument
  is visible in `ps` and lands in the history file — and stored 0600 in
  `~/.nisos/anthropic-key`, deliberately not in `config.toml`, which is a file
  you paste into bug reports. Storing it immediately checks it against
  `GET /v1/models/<model>`, which validates the key, the network *and* the model
  name at once, for free, without generating a token.

- **`NISOS_ONLINE=1 bash scripts/bootstrap.sh`** — an install with no local
  model: no 2.5 GB download, no waiting for one, and one paste for the key at
  the end. About 15 minutes rather than 50. It reuses the existing
  `NISOS_SKIP_MODEL` path rather than adding a second one, so resume behaves
  exactly as before.

- **`--print-backend`**, and `scripts/nisos.sh` now asks it before starting
  anything. An online install no longer loads a 2.5 GB model on the first turn
  after a reboot only to leave it idle. The check costs one Python start and is
  paid *only* on the branch where llama-server isn't already answering, so the
  common path is untouched.

- **`--backend claude|llama|auto`** to override the config for one run, which is
  how you compare the two on the same phrase.

### Changed

- **Four different failures used to share one apology.** "Can't do that
  offline" was already wrong for a stopped llama-server (fixed in 0.2.5), and
  the online brain adds two more: no key, and a request the API declined.
  `BrainError` now carries the reply it deserves, so a missing key says «Λείπει
  το κλειδί» instead of blaming a network with four bars — and the 1-second
  llama probe is skipped entirely when the reason is already known.
- **`--check` only checks what your configuration needs.** On an online install
  there is no local model to find and no grammar to load, and a report that kept
  demanding them would fail forever and teach you to ignore it. llama-server
  shows as `[opt ]` there, because it is then only the fallback. Whisper rows
  are skipped when `stt.strategy = "android"`.
- The log line names which brain answered — `[reasoned:claude/el]`. "That took
  four seconds" has different answers depending on where it went, and the log is
  the only place you can tell afterwards.
- The web UI's status pill and the console header say `online` / `on the phone`.
  The wording is decided server-side, so exactly one place knows it.

### Not verified

- **No successful API call has been made from this checkout.** The endpoint,
  headers and error handling are confirmed live — a real HTTP 401 came back from
  `api.anthropic.com` with its message intact — but nobody has run a turn with a
  valid key, so the request *body* is code-verified against the API
  documentation only. The two opt-in parts (`fallbacks`, and
  `thinking`/`effort`) are what would 400 if any of that is wrong; each clears
  in one line, and an HTTP 400 now says so explicitly rather than leaving you to
  find out.
- Nothing here has run on the phone. Online timings are left blank in the README
  rather than guessed.

## v0.2.6 — 2026-07-27

### Fixed

- **The Speak button could sit amber forever with nothing to show.** A turn's
  timeouts stack — recording 13s, then the recogniser 8s, then Whisper 20s,
  then speaking 30s — so a legitimately slow turn can exceed a minute, and a
  wedged one never returns at all. Both looked identical: bouncing bars and no
  information.

  The button now counts the seconds out loud after the third one, so "is it
  dead?" becomes "it has been 14s". And requests are capped at two minutes,
  after which the page says so and names the usual cause — Termux:API commands
  block waiting on a reply from the Termux:API app, and when that app is
  missing or wedged they never return. Same failure that hangs
  `termux-notification`.

## v0.2.5 — 2026-07-27

First fixes found by using it on a phone rather than reading it.

### Fixed

- **"open flashlight" did nothing.** The Greek table has had ανοιξ-/κλεισ-
  since the beginning; the English table never mirrored them, and it also
  required the word "the". So the phrasing a Greek speaker actually reaches
  for in English — *open the light* — missed the router entirely and fell
  through to a model that wasn't running. Now handled, article optional, and
  `close` maps to off: `open flashlight`, `flashlight on`, `close the light`
  and `switch off the torch` all route.
- **"Can't do that offline" was the wrong excuse.** A phrase that misses the
  router when llama-server is down is not a network problem — and in app mode
  the model being down is the *normal* state, because the UI deliberately
  doesn't start it. It now says the model isn't running and that the quick
  commands still work, which is both true and actionable.

## v0.2.4 — 2026-07-27

### Fixed

- **`notification.sh on` could hang forever.** `termux-notification` talks to
  the Termux:API app over a pair of FIFOs and blocks reading the reply, so if
  that app is missing, unpermitted or wedged it never returns — and the call
  was wrapped in `2>/dev/null`, so there was nothing on screen to explain it.
  That is the third time suppressing the output of a command that talks to
  Android has cost this project a diagnosis.

  It now runs under a 15-second timeout, prints its errors, and says what to
  check in order. It also refuses to install the reboot hook for a
  notification it could not post once — better to fail than to wire a broken
  thing into boot.
- **Dropped `--icon mic`.** Cosmetic, and one less argument that can be
  rejected by a Termux:API version that doesn't know it.

## v0.2.3 — 2026-07-27

### Fixed

- **`nisos-ui.sh` refused to open the UI while app mode was on.** In app mode
  the server is started at boot by `app-mode.sh`, so there is no `ui-url` file
  for the launcher to read — it saw the port taken, found nothing to reopen,
  and said "something is already on port 8765" with no way in. Now that the
  token is persistent the URL is simply rebuilt from it. This was on the exact
  path the setup instructions tell you to walk.

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
