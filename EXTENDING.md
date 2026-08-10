# Extending nisos

The action catalogue is the product. A brilliant model with three actions is a
chatbot; a mediocre model with sixty actions is an assistant. This is where to
spend your time.

## Adding a command

Three small edits, in three obvious places. The test suite checks all three
stay in step, so a forgotten one fails in CI rather than silently on the phone.

### 1. Write the handler — `core/Actions.kt`

```kotlin
"wifi.off" to { _, phone ->
    phone.wifi(false)
    emptyMap()
},
```

A handler takes `(args, phone)` and returns a map of fields for the reply
template. **Reach the outside world only through `Phone`.** That interface is
what makes the action testable off-phone: the tests substitute a recorder that
remembers what it was asked to do instead of doing it.

If your action needs something the platform can do, add a method to `Phone`
and implement it in `android/AndroidPhone.kt`. Never import `android.*` into
`core/` — that would take the whole test suite onto an emulator.

Throw `ActionError` for an expected failure: no duration heard, a permission
that isn't granted. The assistant then says so politely instead of falling
over. Anything else you throw is caught and turned into "that didn't work".

### 2. Add the patterns — `core/Router.kt`

One line per language, in `buildRoutes`:

```kotlin
"en" to listOf(
    Route("\\bwi-?fi off\\b", "wifi.off"),
),
"el" to listOf(
    Route("\\b(κλεισ|σβησ)\\w*\\b.*\\bwifi", "wifi.off"),
),
```

Patterns are matched against `normalise()` output, so write them **lowercase,
unaccented, with final sigma as plain sigma**. Match Greek **stems**, never
whole words:

```kotlin
"\\b(αναψ|ανοιξ)\\w*"    // άναψε / ανάψτε / να ανάψεις / άνοιξε — all four
"\\bαναψε\\b"            // only ever the first one
```

Order matters within a table: the first hit wins, so put specific patterns
above general ones. `calendar.add` sits above `calendar.next` for exactly that
reason — «κλείσε ραντεβού αύριο στις πέντε» would otherwise be answered by
reading you your next meeting.

> ⚠️ **`Route` takes the pattern as a `String`, not a `Regex`.** That is not
> stylistic. Java's `\w` is ASCII-only without `UNICODE_CHARACTER_CLASS`, so
> `"\\b(αναψ)\\w*\\b.*\\bφακ"` silently matches nothing in Greek — `\w*`
> matches zero characters and the `\b` after it is then asked for a word
> boundary in the middle of «αναψε». `\b` on its own *is* Unicode-aware, which
> is what makes this so easy to miss. `Route` compiles its own pattern through
> `unicodePattern()`, which prefixes `(?U)`, so there is nowhere to put a bare
> `Regex` and get it wrong.

If the command takes a number, pass an argument builder:

```kotlin
Route("\\bbrightness\\b", "screen.set", levelIn("en"))
```

A builder receives `(text, match)`. `text` is a `Normalised` — the flattened
string, plus `.original(wordIndices)`, which hands back the user's own
spelling. Use it for anything that gets **written down** rather than spoken:
`calendar.add` builds its title that way, because «οδοντιατρο» sitting in your
diary is the plumbing showing.

### 2b. If the command splits, check it still doesn't

A sentence is cut into several actions on «και» / "and" / a comma, and the cut
is only kept when **every** piece routes on its own. That rule is what stops
«στείλε στη Μαρία ότι άργησα και θα φάμε αργότερα» becoming half a message.

The trap is a route that matches a *fragment*. If your new pattern is loose
enough to fire on "θα φάμε αργότερα", it doesn't just add a wrong action — it
makes previously-safe sentences start splitting. Add a case to
`RouterTest.and inside a message body is left alone` when you add a broad
pattern.

### 3. Add the phrases — `core/Replies.kt`

```kotlin
"wifi.off" to mapOf("en" to "Wi-fi off.", "el" to "Έκλεισα το wi-fi."),
```

Placeholders in `{braces}` are filled from whatever the handler returned merged
with the arguments it was called with, so a handler returning
`mapOf("percent" to 78)` can be phrased as `"{percent} percent left."`

`LoopTest.every action has both languages` fails if you add an action without a
Greek phrase — which is the point. Otherwise it surfaces months later as an
English sentence in a Greek conversation.

### 4. If the model should be able to choose it too

Nothing to do. The tool schema's `action` enum in `core/Cloud.kt` is generated
from the registry, so a new action is offered to Claude the moment it is
registered.

That asymmetry is worth knowing if you ever want an action the model *cannot*
choose: there is no list to leave it out of. The clean way is to keep it out of
`REGISTRY` entirely and reach it from the router alone.

### Then

```bash
cd android && gradle testDebugUnitTest
```

Or push and let CI do it. The unit tests need no emulator, no phone and no
network, and take about a second.

## Money

`money.total` sums whatever sources are configured. A source is one small
class:

```kotlin
class MySource : MoneySource {
    override val id = "mybank"
    override fun read(): Balance? = ...
}
```

Return `null` when a source has nothing to say right now — no token, no
network, no recent message. A source that cannot answer is never an error; it
is simply left out of the sum, and the reply says how many sources answered so
a quietly-missing account is visible rather than silent.

> 🔒 **Every source must be incapable of moving money.** Read-only API tokens,
> open-banking consent scoped to account information, parsed notifications,
> figures you typed in. **Never** store a banking password or drive another
> app's UI. Termux could not do it and this app must not either: a credential
> that can move money, sitting on a phone, protected by a screen lock, is a
> different risk category from a token that can only read a number — and the
> assistant gains nothing from the difference.

## Adding a language

More work than adding a command, but not much:

1. **`core/Normalise.kt`** — add the number words to `NUMBER_WORDS`, in
   normalised form. If the script has quirks of its own, extend `normalise()`.
2. **`core/When.kt`** — add its day, weekday, meridiem and duration words.
3. **`core/Router.kt`** — add a table to `buildRoutes` keyed by the code.
4. **`core/Replies.kt`** — add the language to every entry in `SAY`.
5. **`android/Voice.kt`** — a locale, and a voice preference if you care which.

⚠️ **Check the alphabets first.** The free language detection only works
because Greek and English share no characters. Adding a third language that
shares an alphabet with one of them — Spanish, French, anything Latin — breaks
the guarantee: patterns will cross-match and the assistant will answer in the
wrong language. If you need a same-script language, you'll have to add real
language ID and stop trusting the router's verdict.

## Adding a brain

`Brain` is a one-method interface. A second backend — a local model over HTTP,
a different provider — implements it and gets chosen in `AssistantViewModel`.

Two things the existing one does, and a new one should:

- **Constrain the output rather than parsing it.** The API gets a forced
  `tool_choice` whose schema is the plan. That makes a malformed action
  impossible rather than unlikely, which is worth more than any amount of
  defensive parsing on the way back. Read the result with `stepsFrom`, so two
  brains cannot come to different conclusions about the same JSON.
- **Let the constraint express a list.** A schema that can only hold one
  action guarantees the second one is lost — the model has nowhere to put it.
- **Never let a diagnostic get swallowed.** Include what the other side
  actually said: status, error body, all of it. Suppressing that cost this
  project a diagnosis three separate times.

Throw `BrainError` with a `replyKey` when you know which entry in `SAY`
describes the failure. That is what turns "it didn't work" into "there's no
key".

## Things worth knowing before you change them

**Keep action names English.** In both languages, always. This is the single
load-bearing decision in the design — it means the model only ever has to
*classify* Greek, never write it, and the tool schema makes English names the
only legal output. There is a test asserting it.

**Keep the two route tables symmetric.** If an action is reachable in English
but not Greek, the assistant is cleverer in one language than the other and you
will never remember which. `RouterTest.both languages cover the same actions`
fails if they drift.

**The secrets are not in a config file.** The API key and any source tokens
live in app-private storage, not in anything checked in or quotable. If you add
another credential, do the same.

**`core/` has no Android imports.** Repeated because it is the rule everything
else here depends on. It is what keeps a full test run at one second and no
emulator.
