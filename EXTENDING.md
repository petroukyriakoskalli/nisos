# Extending nisos

The action catalogue is the product. A brilliant model with three actions is a
chatbot; a mediocre model with sixty actions is an assistant. This is where to
spend your time.

## Adding a command

Three small edits, in three obvious places. The test suite checks all three
stay in step, so a forgotten one fails locally rather than silently on the
phone.

### 1. Write the handler — `nisos/actions.py`

```python
@action("wifi.off")
def wifi_off(args, ctx):
    """Turn wi-fi off."""
    ctx.tasker("wifi.off")
    return {}
```

A handler takes `(args, ctx)` and returns a dict of fields for the reply
template. Reach the outside world only through `ctx`:

| Call | Use for |
|---|---|
| `ctx.termux("termux-torch", "on")` | Anything termux-api exposes directly |
| `ctx.termux("am", "start", "-a", …)` | Anything with a standard Android intent |
| `ctx.tasker("wifi.off", {...})` | Everything else — every Tasker task you own |
| `ctx.resolve_contact("αννα")` | Mapping a heard name to a real contact |

Going through `ctx` is what makes the action testable off-phone: the tests
substitute a context that records commands instead of running them.

**Prefer the first two.** Tasker is the escape hatch, not the default — an
action that goes through it can only ever be verified on a phone, with the
right task imported and four separate permissions granted. `timer.set` and
`volume.set` both started life as Tasker calls and are now a platform intent
and a `termux-volume` call; they gained unit tests and lost a dependency.
Reach for `ctx.tasker` when Android genuinely refuses, which in practice means
a permission Termux cannot hold — see [tasker/README.md](tasker/README.md).

Raise `ActionError` for an expected failure — no duration heard, a Tasker task
that isn't installed. The assistant then says so politely instead of falling
over. Anything else you raise is caught, logged with a traceback, and turned
into "that didn't work".

### 2. Add the patterns — `nisos/router.py`

One line per language, in `ROUTES`:

```python
"en": [
    Route(r"\bwi-?fi off\b", "wifi.off"),
],
"el": [
    Route(r"\b(κλεισ|σβησ)\w*\b.*\bwifi", "wifi.off"),
],
```

Patterns are matched against `normalise()` output, so write them **lowercase,
unaccented, with final sigma as plain sigma**. Run a candidate phrase through
`normalise()` before writing the pattern for it.

Match Greek **stems**, never whole words:

```python
r"\b(αναψ|ανοιξ)\w*"    # άναψε / ανάψτε / να ανάψεις / άνοιξε — all four
r"\bαναψε\b"            # only ever the first one
```

Order matters within a table: the first hit wins, so put specific patterns
above general ones. `calendar.add` sits above `calendar.next` for exactly that
reason — «κλείσε ραντεβού αύριο στις πέντε» would otherwise be answered by
reading you your next meeting.

If the command takes a number, pass an argument builder:

```python
Route(r"\bbrightness\b", "screen.set", _level("en"))
```

An argument builder receives `(text, match)`. `text` is a `Normalised` — a
string in every way that matters, plus `.original(word_indices)`, which hands
back the user's own spelling. Use it for anything that gets **written down**
rather than spoken: `calendar.add` builds its title that way, because
«οδοντιατρο» sitting in your diary is the plumbing showing. A spoken reply
never needs it, since those are strings you wrote.

### 2b. If the command splits, check it still doesn't

A sentence is cut into several actions on «και» / "and" / a comma, and the cut
is only kept when **every** piece routes on its own. That rule is what stops
«στείλε στη Μαρία ότι άργησα και θα φάμε αργότερα» becoming half a message.

The trap is a route that matches a *fragment*. If your new pattern is loose
enough to fire on "θα φάμε αργότερα", it doesn't just add a wrong action — it
makes previously-safe sentences start splitting. Add a case to
`TestSplittingIsRefusedWhenItWouldBeWrong` when you add a broad pattern.

### 3. Add the phrases — `nisos/replies.py`

```python
"wifi.off": {
    "en": "Wi-fi off.",
    "el": "Έκλεισα το wi-fi.",
},
```

Placeholders in `{braces}` are filled from whatever the handler returned merged
with the args it was called with, so a handler returning `{"percent": 78}` can
be phrased as `"{percent} percent left."`

### 4. If the model should be able to choose it too — `grammar/action.gbnf`

Add the name to the `verb` rule. Actions missing from the grammar are still
reachable by the router, they just can't be picked by the local model — which is
sometimes exactly what you want for anything destructive.

**The online brain needs no edit here.** Its equivalent of the grammar is the
`action` enum in `nisos/cloud.py`'s tool schema, generated from the registry, so
a new action is offered to Claude the moment it is registered.

That asymmetry matters if you ever want an action the model can't choose.
Leaving it out of `action.gbnf` hides it from llama-server (and fails
`test_grammar_matches_registry`, which is the point — the two are meant to stay
in step), but it would *not* hide it from the API, because that enum is derived
rather than written. The clean way to keep something out of both brains is to
keep it out of the registry entirely and reach it from the router alone.

### Then

```bash
python -m pytest -q
python -m nisos --text "wifi off" --dry-run
```

`--dry-run` logs what would have happened without doing it, and `--text` skips
the microphone, so you can develop the whole thing on a laptop.

## Adding a language

More work than adding a command, but not much:

1. **`nisos/normalise.py`** — add the language's number words to
   `NUMBER_WORDS`, written in normalised form. If its script has quirks of its
   own (Turkish dotless ı, German ß) extend `normalise()`.
2. **`nisos/router.py`** — add a table to `ROUTES` keyed by the language code.
3. **`nisos/replies.py`** — add the language to every entry in `SAY`.
4. **`config.toml`** — add a voice under `[speech.voices]`.

⚠️ **Check the alphabets first.** The free language detection only works
because Greek and English share no characters. Adding a third language that
shares an alphabet with one of them — Spanish, French, anything Latin — breaks
the guarantee: patterns will cross-match and the assistant will answer in the
wrong language. If you need a same-script language, you'll have to add real
language ID and stop trusting the router's verdict.

## Adding a recogniser

Write a function returning a `Transcript` and add it to
`nisos.stt.transcribe`. The contract is just the probe callback: whatever can
satisfy the router, wins.

## Adding a text-to-speech engine

Write `_speak_<name>` in `nisos/speech.py` and list it in `speak()`. Take text
and a language, block until spoken.

## Adding a brain

`nisos/brain.py` is the dispatcher; `think()` is the only function the loop
knows about. A third backend is a module with one function of the same shape:

```python
def think(text, language, actions, config, memories=None) -> Decision
```

Return a `Decision`, and set its `backend` so the log line tells the truth.
Build a multi-action one with `Decision.from_steps([Step(...), Step(...)])`;
the plain constructor still takes a single action and fills `steps` in for you,
so a backend that only ever returns one thing needs to know none of this.

Raise `BrainError` on anything else, with a `reply_key` when you know which
entry in `nisos.replies.SAY` describes the failure — that is what turns "it
didn't work" into "there's no key". Then add the name to `BACKENDS` and one
branch in `backend_for()`.

Three things the existing backends both do, and a new one should:

- **Constrain the output rather than parsing it.** llama-server gets a GBNF
  grammar, the API gets a forced `tool_choice`. Both make a malformed action
  impossible instead of unlikely, which is worth more than any amount of
  defensive parsing on the way back.
- **Let the constraint express a list.** A schema that can only hold one action
  guarantees the second one is lost — the model has nowhere to put it. Read the
  result with `brain.steps_from`, which both existing backends share so they
  cannot come to different conclusions about the same JSON.
- **Never let a diagnostic get swallowed.** Include what the other side actually
  said — HTTP status, error body, all of it. Suppressing that has cost this
  project a diagnosis three separate times.

## Things worth knowing before you change them

**Keep action names English.** In both languages, always. This is the single
load-bearing decision in the design — it means a 4B model only ever has to
*classify* Greek, never write it, and the grammar makes English names the only
legal output. There's a test asserting it, on both brains.

**The key is not a config setting.** It lives in its own 0600 file because
`config.toml` is a thing people paste into bug reports. If you add another
credential, do the same: `cloud.store_key` is the pattern, and the chmod is the
reason it is Python and not an `echo`.

**Keep the two route tables symmetric.** If an action is reachable in English
but not Greek, the assistant is cleverer in one language than the other and you
will never remember which. `test_both_languages_cover_the_same_actions` fails
if they drift.

**The Tasker XML cannot contain `<` or `&`.** That JavaScript lives inside an
XML element, so a counted loop and a logical *and* both make the task fail to
import — silently, looking like ordinary JavaScript. Escaping them doesn't work
either, because you are also told to paste the block into Tasker by hand.
`tests/test_tasker.py` fails if either character appears.

**Never write a timestamped temp file.** The recording always goes to the same
path and overwrites. Writing `input-<timestamp>.wav` is the classic way to fill
a phone's storage over a few months without noticing.

**Don't use llama-server's `--prompt-cache`.** It writes KV state to disk and
grows without limit.

**Log rotation is not automatic.** `scripts/postbuild.sh` installs a nightly
trim; if you change the log path, change it there too.
