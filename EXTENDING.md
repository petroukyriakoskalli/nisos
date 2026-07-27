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
| `ctx.tasker("wifi.off", {...})` | Everything else — every Tasker task you own |
| `ctx.resolve_contact("αννα")` | Mapping a heard name to a real contact |

Going through `ctx` is what makes the action testable off-phone: the tests
substitute a context that records commands instead of running them.

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
above general ones.

If the command takes a number, pass an argument builder:

```python
Route(r"\bbrightness\b", "screen.set", _level("en"))
```

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
reachable by the router, they just can't be picked by the model — which is
sometimes exactly what you want for anything destructive.

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

## Things worth knowing before you change them

**Keep action names English.** In both languages, always. This is the single
load-bearing decision in the design — it means a 4B model only ever has to
*classify* Greek, never write it, and the grammar makes English names the only
legal output. There's a test asserting it.

**Keep the two route tables symmetric.** If an action is reachable in English
but not Greek, the assistant is cleverer in one language than the other and you
will never remember which. `test_both_languages_cover_the_same_actions` fails
if they drift.

**Never write a timestamped temp file.** The recording always goes to the same
path and overwrites. Writing `input-<timestamp>.wav` is the classic way to fill
a phone's storage over a few months without noticing.

**Don't use llama-server's `--prompt-cache`.** It writes KV state to disk and
grows without limit.

**Log rotation is not automatic.** `scripts/postbuild.sh` installs a nightly
trim; if you change the log path, change it there too.
