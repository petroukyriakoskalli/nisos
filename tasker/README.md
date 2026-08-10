# Tasker

Two directions across one bridge.

| Direction | What crosses | Why it has to |
|---|---|---|
| nisos → Tasker | `dnd.on`, `calendar.next`, `calendar.add` | Android will not let a Termux process do any of them |
| Tasker → nisos | a hardware button | Termux has no way to watch the volume keys |

Start here:

```bash
bash ~/nisos/scripts/tasker-setup.sh    # prepares the Termux side, prints the taps
bash ~/nisos/scripts/tasker-test.sh     # proves each link, one at a time
```

---

## What no longer needs Tasker

`timer.set` and `volume.set` used to go through this bridge. They don't any more:

- **Timer** — `SET_TIMER` is a standard Android intent. Any clock app answers it,
  `SKIP_UI` starts the countdown without coming to the foreground, and it needs no
  permission at all.
- **Volume** — `termux-volume` reports each stream's maximum, so nisos reads it and
  scales the percentage itself. Doing it this way means «βάλε την ένταση στα 30»
  lands on the same loudness whether the stream has 15 steps or 30.

Both now work with Tasker uninstalled. That was worth doing on its own: it halves the
part of this integration that can only be tested on a phone.

**What is left really is stuck here.** `cmd notification set_dnd` needs the shell UID,
which Termux does not have. Calendars need `READ_CALENDAR` and `WRITE_CALENDAR`, which
Termux does not declare and therefore cannot be granted. Tasker holds all three.

---

## Reading and writing the calendar

Both halves go the same way — the calendar provider, through Tasker's shell, which is
where the permission lives:

| Action | What it does | Answer |
|---|---|---|
| `calendar.next` | `content query` for the next 7 days | `{"summary": …, "minutes": …}` |
| `calendar.add` | `content insert` into `events` | `{"ok": true}` |

`calendar.add` needs `ok` in its answer, and that is not ceremony. An older
`NisosAction` — one that has never heard of `calendar.add` — falls into its own
else-branch and writes a perfectly well-formed answer saying it did nothing. Requiring
a field the old branch cannot produce is what turns *"it silently didn't happen"* into
*"that didn't work"* out loud. **If you have imported this task before, import it
again**, or appointments will fail with exactly that message.

Which calendar it writes to: the task picks the first one with an access level of 500
or more (`CAL_ACCESS_CONTRIBUTOR`; anything below can be read but not written). On a
phone with several accounts that is a coin toss, so pin it with `calendar_id` under
`[tasker]` in `config.toml`. To find the number:

```bash
content query --uri content://com.android.calendar/calendars \
  --projection _id:account_name:calendar_displayName
```

### If your device refuses the insert

Tasker has a **Calendar Insert** action, and it is the belt-and-braces route — it does
its own permission handling rather than going through a shell. A JavaScriptlet cannot
invoke one and hand it three separate fields, so it needs a second task, the way
`NisosDnd` works. Two minutes:

```
Tasks → + → NisosCalendarAdd
  + → Misc → Calendar Insert
      Title        %par1
      Start Date   %par2      ← "Start Time" too, if your Tasker splits them
      Duration/End 1 hour
```

Then change the one line in `NisosAction` that does the insert to:

```js
performTask("NisosCalendarAdd", 10, title, formatted);
answer({ ok: true });
```

where `formatted` is the start as a date string rather than the milliseconds nisos
sends. You lose the exact duration this way, which is why it is the fallback and not
the default.

---

## The outbound task

nisos sends a broadcast and, when it needs an answer back, waits for a file:

```
am broadcast --user 0 \
  -a net.dinglisch.android.tasker.ACTION_TASK \
  -e task_name NisosAction \
  -e par1 calendar.next \
  -e par2 '{}'
```

`%par1` is the action name, `%par2` is a JSON payload. A broadcast cannot return a
value, so anything that has one is written to **`/sdcard/nisos/calendar.json`**.

> ⚠️ **That path must be on `/sdcard`.** Termux's home is private app data —
> Tasker cannot write into it without root. The old code pointed at
> `/data/data/com.termux/files/home/.nisos/calendar.json`, which looks perfectly
> reasonable and could never have worked.

### Importing

`Tasks` tab → ⋮ → **Import Task** → pick the file.

| File | Task | Contents |
|---|---|---|
| `NisosAction.tsk.xml` | `NisosAction` | one JavaScriptlet — the dispatcher |
| `NisosDnd.tsk.xml` | `NisosDnd` | one Do Not Disturb action |

**Why one JavaScriptlet instead of a tree of Tasker actions.** Hand-written Tasker XML
is easy to get subtly wrong — argument order, the numeric code behind an `If`
condition — and a task that imports cleanly but branches wrongly is worse than no task
at all. One action means there is one thing to verify, and the logic is JavaScript you
can read in the file.

> ⚠️ These two files were written off-device and **have never been imported**. The
> JavaScript is under my control and should be fine. The one value that could not be
> checked is the mode spinner in `NisosDnd` — if it lands on the wrong entry, open the
> task and pick the one you want from the dropdown. That is the whole fix.

### Building `NisosAction` by hand instead

Under two minutes, and it cannot import wrongly:

1. `Tasks` → **+** → name it `NisosAction`
2. **+** → `Code` → `JavaScriptlet`
3. Paste the contents of the `<Str sr="arg0">` block from `NisosAction.tsk.xml`
4. Set **Timeout** to 45 seconds
5. Back out to save

Then `NisosDnd`: new task, **+** → `Audio` → `Do Not Disturb`, pick your mode, save.

### Adding an action

Same shape as adding one to nisos itself — one branch here, matching the
`ctx.tasker("your.action", {...})` call in `nisos/actions.py`:

```js
} else if (action === "your.action") {
    // payload is already parsed
    flash("doing " + payload.thing);
    answer({ summary: "done", minutes: 0 });   // only if nisos waits for a reply
}
```

> ⚠️ **Two characters cannot appear in that file**: a bare `<`, which XML reads as the
> start of a tag, and `&`, which starts an entity. Either one makes the task fail to
> import, and it looks like perfectly ordinary JavaScript. Escaping them is not the fix
> — you are also told above to paste this block in by hand, where `&lt;` arrives as
> literal broken code. Write `for (var i in rows)` instead of a counted loop and nest
> your `if`s instead of using `&&`. `tests/test_tasker.py` fails if either slips in.

---

## The button trigger

> **Read this first.** With the screen **off and locked**, Android hands volume keys
> to the audio system and Tasker generally never sees them. No Tasker recipe changes
> that. If "works with the phone in my pocket" is the point, use **option A** — it is
> the only one that genuinely does.

All three options end at the same place: a Tasker task that runs
`nisos-turn.sh` through the **Termux:Tasker** plugin, which takes one turn — record,
act, speak the reply — with no UI at all. Install
[Termux:Tasker](https://f-droid.org/packages/com.termux.tasker/) from F-Droid first,
then run `scripts/tasker-setup.sh`.

The shared task, once:

```
Tasks → + → NisosTurn
  + → Plugin → Termux:Tasker → pencil
      Executable   nisos-turn.sh
      Arguments    (empty)
      Terminal     unchecked        ← or a terminal window pops up every time
      Stdout       unchecked
```

### A. Side key, double press — works locked, no Tasker

Samsung builds this in, and because the system itself handles it, it fires with the
screen off:

```
Settings → Advanced features → Side button → Double press → Open app
  → pick the Termux:Widget shortcut for nisos
```

Unglamorous, but it is the one that works when the phone is in your pocket.

### B. Volume long-press — one profile, screen on

```
Profiles → + → Event → Hardware → Volume Long Press
  → link it to NisosTurn
```

One event, no counting, no timing window, and it does not change the volume as a side
effect. If you only want one Tasker trigger, make it this one.

### C. Volume-up ×3 — what you asked for

Tasker has no triple-press event, so it is built out of a counter and a window. Watch
the media volume variable rather than the key itself, which avoids needing Tasker's
accessibility service:

```
Profiles → + → Event → Variables → Variable Set
  Variable   %VOLM
  → link to a new task, NisosVolumeCount:

    1  Variable Add          %nisos_taps   Value 1   Wrap Around 0
    2  If                    %nisos_taps  eq  3
    3    Variable Set        %nisos_taps  to  0
    4    Perform Task        NisosTurn
    5    Stop
    6  End If
    7  Wait                  1 second 200 ms
    8  If                    %nisos_taps  neq  0
    9    Variable Set        %nisos_taps  to  0
   10  End If
```

Tap three times inside about a second and a quarter and it fires; pause, and the
counter resets itself.

Two honest costs. The volume genuinely moves three steps each time you do it — you are
detecting the volume changing, not intercepting the key. And at maximum or minimum
volume `%VOLM` stops changing, so the trigger goes dead exactly when the volume is
pegged. Sitting the media volume mid-range avoids that; option B has neither problem.

---

## When it doesn't work

`scripts/tasker-test.sh` walks the links in order and stops at the first failure. The
order matters — these fail in a way that looks identical from nisos' side:

| Symptom | Almost always |
|---|---|
| No flash, no file | **Allow External Access** is off (Tasker → Preferences → Misc) |
| Flash, but no file | Tasker lacks **All files access** (Settings → Apps → Tasker → Permissions) |
| Works, then stops after a while | Tasker is being killed — turn off battery optimisation for it |
| `calendar.next` says "nothing" | Tasker lacks the **Calendar** permission, or there genuinely is nothing in 7 days |
| `calendar.add` says "that didn't work" | An older `NisosAction` with no `calendar.add` branch — re-import it |
| `calendar.add` flashes "no writable calendar" | Tasker lacks the Calendar permission, or every calendar on the phone is read-only |
| DND does nothing | Tasker needs **Do Not Disturb access** (Settings → Notifications) |
| Everything works from the terminal, nothing from a button | **Terminal** is still checked in the Termux:Tasker action |

The two calendar branches are the pieces of this that could not be verified without a
device. Both reach the calendar provider through Tasker's shell — the only route
available for reading, since Tasker has an insert action but no read action, and the
route chosen for writing too so that there is one mechanism to check rather than two.
If `calendar.next` comes back empty with entries clearly in your calendar, or
`calendar.add` fails while Tasker plainly holds the permission, those are the things to
report — and the hand-built **Calendar Insert** task above is the fallback for the
second one.
