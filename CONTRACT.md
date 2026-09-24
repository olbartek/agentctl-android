Contract version: 1

# The AgentCtl contract

AgentCtl lets a small script drive an app's state machine — headlessly, with no UI, or against a
running app through an in-app bridge — and prints back what changed after every command. This
document specifies the part of that behaviour that any implementation, in any host language, must
reproduce so that the same script produces the same steps everywhere. A Swift implementation and a
Kotlin implementation are two ports of this contract, not two designs.

**Scope.** This contract defines the *engine*: how a script is parsed, how a line is dispatched to
whatever commands the host app declares, how the three runtime commands (`expect`, `advance`,
`mock`) behave, how one step is rendered as text, and the exit codes and determinism guarantees
that make a script's output reproducible. For an implementation that can also run scripts inside
the real app, §8 specifies the in-app bridge's wire protocol. The contract does **not** define
which screens, commands, summary keys or mock methods any particular app has — that vocabulary
belongs to the app that embeds AgentCtl (declared however the host language expresses it:
`AgentScreen`/`AgentCommand` in Swift, a Kotlin equivalent in a port).

**The examples** use the vocabulary of TinyApp, the example app in this repository
([`Examples/TinyApp`](Examples/TinyApp)): a list screen, `items`, with the commands `open <id>`,
`refresh` and `retry`; a detail screen, `items/<id>`, with `save` (which starts a three-second
cooldown) and its container's `back`; and one mockable method, `items.fetch`. They show the
mechanism, never something a port must literally have. Every output shown is real: it is what the
reference implementation — the Swift package in this repository — prints for the command shown,
run from the repository root, with the exit code appended by `echo "exit=$?"` where it matters.
Where this text and the reference implementation disagree, one of them has a bug.

## 1. Script language

### 1.1 Lines, separators, comments

- A script is plain text. Commands are separated by `;` **or** a newline — either is equivalent as a
  separator.
- `#` starts a comment that runs to the end of the physical line. A comment resets at each newline,
  so a line that starts with more script after a `;` is not "still commented."
- Blank lines, and segments that are empty or all whitespace after trimming, produce no command —
  they are silently skipped, not an error.
- A command's name is the leading run of non-whitespace characters; its argument, if any, is
  whatever follows, trimmed of leading/trailing whitespace. A command with nothing after its name
  has no argument (not an empty string).

  ```
  open 2; save; back
  ```
  parses to three commands: `open` (argument `2`), `save` (no argument) and `back` (no argument).

### 1.2 Quoting and escapes

- A double-quoted span protects `;` and `#` from being treated as a separator or a comment start,
  so an argument can itself contain them: `expect title="a;b#c"` is one command, not two commands
  and a comment.
- Inside a quoted span, `\` escapes the character that follows it (at minimum, implementations must
  support `\"` for a literal quote and `\\` for a literal backslash — the reference implementation
  escapes *any* following character generically; see Open Questions).
- **Parsing is quote-preserving, not quote-stripping.** The line-splitting pass keeps the quote
  characters (and any backslash escapes) in the argument text verbatim; it only uses them to decide
  where a `;` or `#` is literal versus a separator. A *separate* step resolves quotes depending on
  how the argument is consumed:
  - A command that takes its argument as one opaque value (`open <id>`, or a free-text field)
    unquotes the *whole* argument only when it is wrapped end-to-end in a single pair of quotes;
    otherwise it is used as-is. `open "2"` and `open 2` are the same command, and a free-text field
    accepts `Second item` and `"Second item"` as the same value; quoting is only required when the
    value would otherwise be mistaken for something else (see `expect`, next).
  - A command whose argument is itself a list of tokens (`expect k=v [k=v …]`, `mock <method>
    <error>`) is split on *unquoted* whitespace first, and quotes/escapes are then resolved inside
    each token. This is why `expect` needs quoting for a value with a space —
    `expect title="Second item"` — while a free-text field does not.

  ```
  $ swift run tinyctl run 'open 2; expect title="Second item"; expect title="a;b#c"'; echo "exit=$?"
  > (launch)
    screen=items items=3 loading=false calls=items.fetch
  > open 2
    screen=items/2 title="Second item" saved=false cooldown=0
  > expect title="Second item"
    screen=items/2 title="Second item" saved=false cooldown=0
  > expect title="a;b#c"
    screen=items/2 title="Second item" saved=false cooldown=0
    FAIL expected title=a;b#c, got title=Second item
  exit=1
  ```

  The last `expect` reached the engine whole, `;` and `#` included, and failed only because the
  title is not `a;b#c`. (A failure message prints both values unquoted.)

  This two-phase design exists so that a single parser can serve every command shape without each
  command re-implementing tokenizing: the line splitter only needs to know where statements end,
  and only the command that receives the argument needs to know its own shape.

### 1.3 Command resolution

- A command name is looked up on the screen that is currently active, then on the containers around
  it (outer navigation/tab structure), then on the app root — **leaf first**. The first match by
  name wins; a screen's own command shadows a container's or root's command of the same name (e.g.
  a screen can offer its own `back` with a screen-specific failure reason instead of the root's
  generic one).
- A screen may expose different commands depending on which of its own paths is currently active
  (e.g. one reducer backing several screen paths, each with a different subset of the reducer's
  commands available); resolution still starts at whatever the current path currently offers.
- A command may be **gated**: disabled in some states, mirroring a disabled button. A gated command
  that is currently disabled is looked up successfully (it exists) but fails when invoked, with a
  hint describing the condition (see below).
- Unrecognized command names, and disabled or malformed invocations, are not script syntax errors —
  they are resolved and reported at the same point the app itself would refuse them (see §5 for
  which exit code each case gets):
  - **Unknown name** — not offered by the active screen or any of its containers:
    ```
    $ swift run tinyctl run 'open 2; frobnicate'
    > (launch)
      screen=items items=3 loading=false calls=items.fetch
    > open 2
      screen=items/2 title="Second item" saved=false cooldown=0
    > frobnicate
      screen=items/2 title="Second item" saved=false cooldown=0
      FAIL unknown command 'frobnicate' on items/2. Valid here: save, back, expect, advance, mock
    ```
    The listed commands are exactly what would currently resolve on that screen, in the same
    leaf-first order — here the detail screen's own `save`, then its container's `back` — with the
    three runtime commands (`expect`, `advance`, `mock`) always appended last.
  - **Disabled (gated)** — `retry` is offered only while the list reports an error:
    ```
    $ swift run tinyctl run retry
    > (launch)
      screen=items items=3 loading=false calls=items.fetch
    > retry
      screen=items items=3 loading=false
      FAIL retry is disabled here (error=none)
    ```
  - **Bad argument** — the command exists and is enabled, but its argument is missing, unexpected,
    invalid, or the command doesn't apply right now. The message is the command's usage followed by
    a colon and a reason:
    ```
    $ swift run tinyctl run 'open x'
    > (launch)
      screen=items items=3 loading=false calls=items.fetch
    > open x
      screen=items items=3 loading=false
      FAIL open <id>: invalid argument: expected an item id such as 2
    ```
    The reason is one of: `missing argument: expected <placeholder>` (a required argument was
    omitted: `open` alone fails with `open <id>: missing argument: expected <id>`),
    `unexpected argument '<value>': this command takes none` (`save now` fails with
    `save: unexpected argument 'now': this command takes none`), `invalid argument: <reason>` (a
    value was supplied but rejected, as above), or a sentence the app supplies for "this command
    doesn't apply right now", which follows the usage and its colon with no prefix of its own
    (`<usage>: <sentence>`; TinyApp has no such command).

### 1.4 Parse errors

The only failure that the line-splitting pass itself raises is an **unterminated quote**: a quoted
span still open at the end of a line (when the separator is a newline) or at the end of the whole
script. It is reported with the line and column where the quote *opened*, and maps to exit code 2
(§5). The whole script is parsed before any of it runs, so a parse error runs **no** line of it —
not even the lines before the bad quote. The reference CLI prints the `(launch)` step (§3.4) and
the error on stderr:

```
$ swift run tinyctl run 'open 2; expect title="Second'; echo "exit=$?"
> (launch)
  screen=items items=3 loading=false calls=items.fetch
error: parse error: line 1, column 22: unterminated quote
exit=2
```

Every other problem — an unknown command, a bad argument, an out-of-range value — is a
resolution-time failure once a well-formed line has been handed to the app, not a parse error: the
lines before it have run, and it is reported on its own step.

## 2. Runtime commands

Three commands work identically on every screen; the app never sees them as its own actions.

### 2.1 `expect k=v [k=v …]`

Asserts on the result of the step that just ran (see §4). At least one `key=value` pair is
required; `expect` with no argument, or a token without an unquoted `=` after its first character,
is a syntax error (exit 2), not a failed assertion — reported, like every resolution-time failure,
on the `expect` line's own step: `FAIL expect needs at least one key=value pair`, or
`FAIL expected key=value, got 'screen'` for `expect screen`. Quote a value that contains
whitespace: `expect title="Second item"`.

### 2.2 `advance <duration>`

Moves a virtual clock forward by the given amount, releasing every timer whose deadline falls
within the advanced window, in deadline order — then the runtime waits for the app to settle
again. TinyApp's save cooldown counts down once a second, so `advance 3s` fires all three ticks in
that one step:

```
$ swift run tinyctl run 'open 2; save; expect cooldown=3 pending=1; advance 3s; expect cooldown=0 pending=0'
> (launch)
  screen=items items=3 loading=false calls=items.fetch
> open 2
  screen=items/2 title="Second item" saved=false cooldown=0
> save
  screen=items/2 title="Second item" saved=true cooldown=3 pending=1
> expect cooldown=3 pending=1
  screen=items/2 title="Second item" saved=true cooldown=3 pending=1
> advance 3s
  screen=items/2 title="Second item" saved=true cooldown=0
> expect cooldown=0 pending=0
  screen=items/2 title="Second item" saved=true cooldown=0
```

**Accepted forms:** an unsigned integer immediately followed by one unit suffix — `ms`
(milliseconds), `s` (seconds), `m` (minutes, i.e. ×60 seconds), or `h` (hours, i.e. ×3600 seconds).
`500ms`, `30s`, `5m`, `1h` are all valid. Anything else is a usage error (exit 2), not a failed
assertion: a missing or malformed duration (no digits, no or an unknown suffix, a compound value
like `1h30m`, a negative or fractional number), and a number too large to represent — as a 64-bit
integer, or once converted to seconds (`advance 9999999999999999h`). All of them fail with
`advance needs a duration such as 500ms, 30s, 5m or 1h`.

The reference implementation also refuses, as a usage error, an `advance` that would take the
virtual clock past 2^63−1 seconds in total over one run
(`advance would take the clock past 9223372036854775807 seconds`), because its clock's instants
overflow not far beyond that. A port has its own limit; that a script can never crash the engine
through `advance` is the requirement.

**Headless only.** Issuing `advance` against a running app with no virtual clock — i.e. one driven
by a real, wall-clock timer — is rejected with a usage error (exit 2):
`advance is only available headlessly, not in the running app`. This is deliberate rather than a
missing feature: a live run's timers are real, so "advancing" them can't be done deterministically
or instantaneously, and silently ignoring the command (or turning it into a real sleep) would make
the same script behave differently in the two modes without warning. A conforming implementation
must reject `advance` outright wherever it has no virtual clock to move. (The duration is checked
first, so a malformed or oversized one is the same usage error in both modes.)

### 2.3 `mock <client.method> <error>`

Arms a **one-shot** failure: the *next* call the app makes to `<client.method>` throws the given
error instead of running normally; the registration is then cleared, so a second call to the same
method succeeds unless `mock` is issued again. The argument must be exactly two whitespace-separated
tokens (`mock items.fetch network`); any other count is a usage error (exit 2):
`usage: mock <client.method> <error> — exactly two words; mockable: items.fetch`. `<client.method>`
must name a method the app has actually declared as mockable, and `<error>` must be one of that
method's declared error codes — both are app vocabulary, not part of this contract, but an unknown
name (`unknown mock method 'items.nope'; mockable: items.fetch`) or an error code invalid for that
name (`unknown error 'notFound' for items.fetch; valid: network, timeout`) is a failure (exit 1),
because the *shape* of the command was fine and only the referenced identifier was wrong (the same
class of error as an unknown screen command, §1.3).

`mock` is available in every mode, headless or live — the fault registry it writes to lives
alongside whatever mock backends the app itself runs in that process, so there is no clock
dependency to fail on.

## 3. Step output format

After every command the runtime waits for the app to settle (§6) and prints exactly one step: an
echo of the command as run, then one line describing the resulting state.

```
> refresh
  screen=items items=3 loading=false calls=items.fetch
```

### 3.1 Field order

The state line's fields appear in this exact order, space-separated:

1. `screen=<path>` — always present, always first. The active screen's path (an app-defined string
   such as `items` or `items/2`).
2. The screen's own summary pairs, **in the screen's declaration order** — the fixed order in which
   that screen lists its own keys, not alphabetical and not insertion order of some other kind. All
   of a screen's summary keys are printed every time it is the active screen; a key is never omitted
   to signal something — an app that needs to say "no issues" prints an empty or sentinel value for
   that key instead of leaving it out. This is what makes the set of keys for a given screen
   path documentable once and relied on afterwards.
3. `calls=<c1>,<c2>,…` — present **only if** at least one call to a mocked method happened during
   this step; a comma-joined list of `<client>.<method>` names in the order they were made
   (duplicates included if a method was called twice). **Omitted entirely** when no call was made —
   there is no `calls=` with an empty value.
4. `error=<code>` — present **only if** the screen currently reports an error. **Omitted entirely**
   when there is none (there is no printed `error=none`; that spelling exists only as something you
   can `expect`, see §4 — do not treat a missing `error=` field as ambiguous with an explicit "no
   error" you can pattern-match against in the text output).
5. `pending=<n>` — present **only if** `n` is nonzero: the number of timers currently suspended
   (e.g. the save cooldown's countdown), not a general count of in-flight work. Counting only clock
   waits (and not, say, an in-progress navigation effect) is what lets `pending` mean "advance
   would still do something here."
6. `settled=false` — present **only if** the runtime hit its real-time ceiling waiting for the app
   to go quiet before the fields above were read (§6). A step carrying `settled=false` is also a
   failed step (§5): the printed state may not be final, so it must not be trusted or asserted on.
   This holds for every step, the `(launch)` step included (§3.4).

### 3.2 Quoting of summary values

A summary value is printed bare (`items=3`) unless it contains whitespace or is the empty string,
in which case it is wrapped in a plain pair of double quotes with no internal escaping
(`title="Second item"`, `<key>=""`). A value that itself contains a `"` character is not specified
by the reference implementation (see Open Questions) — do not rely on round-tripping such a value
through the text format.

### 3.3 Failure lines

When a step fails, the state line is still printed in full (the screen's current state is always
knowable, even when the command that produced it was rejected), followed by one `  FAIL <text>`
line per line of the failure message — for `expect`, one per unmet pair:

```
> frobnicate
  screen=items items=3 loading=false
  FAIL unknown command 'frobnicate' on items. Valid here: open <id>, refresh, retry, expect, advance, mock
```

```
> expect screen=nope items=0 loading=false
  screen=items items=3 loading=false calls=items.fetch
  FAIL expected screen=nope, got screen=items
  FAIL expected items=0, got items=3
```

### 3.4 `(launch)`

Before any command in the script runs, the runtime performs its own initial step: it settles the
app from a cold start into its first screen (for TinyApp, the list, which loads its items when it
appears) and prints it exactly like any other step, except the command echo is the literal token
`(launch)` rather than anything the script author wrote:

```
> (launch)
  screen=items items=3 loading=false calls=items.fetch
```

This step is not something a script can invoke or skip; it is how a script's own first line is
always evaluated against a known starting state. It follows the rule of §3.1 like any step: if the
app does not settle, `(launch)` is printed with `settled=false` and a `FAIL` line, **no line of the
script runs**, and the exit code is 1.

### 3.5 Other machine-readable forms

A tool built on this contract may offer other serializations of the same steps (the reference CLI
has a JSON array form, which §8.4 describes where the bridge uses it). Only the text form specified
above is normative byte for byte; see §7.

## 4. `expect` semantics

`expect k=v [k=v …]` checks each pair against the snapshot produced by the step that just ran: the
active screen's path, its full summary, the calls made during that step, its error code, and its
pending count. Every pair must match for the `expect` to pass; on failure, one message per unmet
pair is printed (as `FAIL` lines, §3.3), and the pairs that *did* match produce no output of their
own.

| Key | What it checks | Match rule |
|---|---|---|
| `screen` | the active screen's path | exact string equality |
| any summary key | that key's current value | exact string equality against the printed (unquoted) value; asserting a key the current screen doesn't have is itself a failure, not a "no match": `unknown key 'foo' on items; available: screen, call, error, pending, items, loading` |
| `call` | whether a method was called during the step just run | membership, not equality — `expect call=items.fetch` passes if that name appears anywhere in the step's call list; repeat the key to require several, each of which must appear (`expect call=items.fetch call=items.save` fails with `expected call=items.save, got calls=items.fetch`) |
| `error` | the screen's current error code | exact string equality; the literal value `none` means "no error is set" — this is the only place `none` is meaningful; it is never printed on the state line itself (§3.1) |
| `pending` | the number of timers currently suspended | exact equality against the integer, compared as text, e.g. `pending=0` |

A failed match prints `expected <key>=<value>, got <key>=<actual>`, except for `call`, which prints
`expected call=<value>, got calls=<c1>,<c2>,…` (or `got calls=none` when nothing was called).

**What "the previous step" means for `call` and the summary.** `expect` does not run the command
again or advance anything; it reads the same call list and summary that would have been printed
had a state line been shown at that point. Consecutive `expect` lines (or an `expect` right after a
`mock`, which clears the call list without making a call) all see the same, unadvanced snapshot —
only an actual command, or `advance`, updates it.

```
$ swift run tinyctl run 'mock items.fetch network; refresh; expect error=network items=3 loading=false call=items.fetch; retry; expect error=none items=3 call=items.fetch'
> (launch)
  screen=items items=3 loading=false calls=items.fetch
> mock items.fetch network
  screen=items items=3 loading=false
> refresh
  screen=items items=3 loading=false calls=items.fetch error=network
> expect error=network items=3 loading=false call=items.fetch
  screen=items items=3 loading=false calls=items.fetch error=network
> retry
  screen=items items=3 loading=false calls=items.fetch
> expect error=none items=3 call=items.fetch
  screen=items items=3 loading=false calls=items.fetch
```

## 5. Exit codes

| Code | Meaning |
|---|---|
| 0 | Every line in the script ran and every `expect` passed. |
| 1 | A command or an `expect` failed. This covers: an unmet `expect` pair (including an unknown key); an unresolvable command name; a gated command that is currently disabled; a command argument that is missing, unexpected, invalid, or inapplicable right now; a `mock` naming an unknown method or an error code not valid for that method; and any step that did not settle within its time limit (`settled=false`), the `(launch)` step included. |
| 2 | A usage or parse error: an unterminated quote (§1.4); `expect` with no argument or a malformed `key=value` token; `advance` with a missing, unparseable or unrepresentable duration, one that would take the virtual clock past its limit (§2.2), or one issued where there is no virtual clock to move; `mock` with a wrong number of tokens. In every case here, the *shape* of the line itself was wrong, before anything was resolved against the app's current state. The same code covers a tool's own malformed command line — a missing argument, an unknown option or subcommand — whatever code its argument parser would use by default. |
| 3 | An internal or environment error: a failure outside the script engine itself. A script or scenario file that does not exist or cannot be read; no scenario files where a tool was told to look for them; no repository root to resolve paths against; a host process that could not be built or started; and similar environment problems. Not something a script's own lines can trigger. |

A script stops at its first failing line; exit code 0 requires every line to have run.

**Several scripts.** A tool that runs several scripts in one invocation (the reference CLI's
`test`) runs each against a fresh app. It exits 3 if any script could not be read, or if it was
asked to run every script in a directory that holds none — zero scripts run is not a pass;
otherwise 1 if any script failed, whatever that script's own code would have been; otherwise 0.

```
$ swift run tinyctl test /nonexistent.appctl; echo "exit=$?"
FAIL nonexistent
  cannot read /nonexistent.appctl
0 passed, 1 failed
exit=3
```

The in-app bridge reports these same codes for what it ran, in a response header (§8.4).

## 6. Determinism requirements

A conforming headless implementation must guarantee: **the same script, run from the same fresh
starting state, always produces byte-identical step output.** Concretely:

- **A virtual clock**, not a real one. Nothing that measures time (a resend cooldown, a code expiry)
  progresses except through `advance`; the implementation must be able to enumerate and fire every
  pending timer up to a given point, in deadline order.
- **A fixed notion of "now."** Whatever the app reads as the current date/time returns one constant
  value for the whole run (the reference implementation uses 2026-01-01 09:00 UTC). The exact value
  is an application choice; that it never varies between runs is the requirement.
- **Deterministic identifiers and randomness.** Anything the app generates that would otherwise be
  random (record IDs, session identifiers, a shuffled order) must instead come from a deterministic
  source — an incrementing sequence, a seeded generator — so two runs of the same script generate
  the same values in the same order.
- **A fixed time zone, locale and calendar**, so that formatting a date or a number, or doing
  calendar arithmetic, reads the same on every machine regardless of its settings.
- **Zero artificial latency** in headless/test runs: a mocked call may still be recorded and may
  still be faulted (§2.3), but it must not itself wait on real time. (A live run driving a real,
  running app is explicitly exempt from this — it is allowed, even expected, to have real latency;
  determinism is a headless-mode guarantee, not a live-mode one.)
- **Deterministic scheduling.** Concurrent work (effects, coroutines, whatever the host language
  calls its concurrency unit) must resolve in a fixed order for a given script, so that interleaving
  differences never change which call happens before which state update.
- **A clean starting state per run.** Any storage the app's mocks use starts empty (or at a fixed
  seed) at the beginning of a headless run; nothing carries over from a previous run in the same
  process unless the script itself asks for it.
- **Settling never reports a half-finished state.** The runtime must wait until the app has gone
  quiet — by whatever definition of "quiet" fits the host (e.g. several consecutive checks seeing no
  change) — before reading the fields for a step, rather than printing state mid-effect. A run that
  cannot reach quiet within a bounded ceiling reports `settled=false` (§3.1) and fails the step,
  rather than guessing.

**What the reference implementation pins.** The Swift headless host (`HeadlessHost`) meets these
requirements by overriding exactly these dependencies of the app's store, and then calling the host
app's own `configure` hook:

| Dependency | Headless value |
|---|---|
| the continuous clock | a test clock that only `advance` moves, wrapped to count its sleeps for `pending` |
| UUID generation | incrementing: `00000000-0000-0000-0000-000000000000`, then `…0001`, … |
| the current date | 2026-01-01T09:00:00Z, on every read |
| the random number generator | SplitMix64, seeded with 0 |
| the time zone | UTC |
| the locale | `en_US_POSIX` |
| the calendar | Gregorian, in UTC |
| mock latency | zero |
| the mock call log and fault registry | fresh for each run |

and it runs everything on one serial executor, in order. **Nothing else is pinned.** A dispatch-queue
scheduler (TCA's `mainQueue`), a suspending clock or any other source of time keeps its real
default, and whatever the app reads around its dependencies — the process's current locale, say —
is not pinned at all. An app that uses one of those must have its host pin it — in the reference,
in the `configure` hook, which runs last and so can also override anything in the table.
A port pins the equivalents in its own language (for a Kotlin port: the coroutine dispatcher, the
clock, `Random`, `Locale`, `TimeZone`, …).

**Why this matters for a port.** These guarantees are what let a script's output be captured once
as a fixture and compared byte-for-byte later — both to catch an accidental behaviour change while
refactoring the same implementation (the reference's own test suite runs every example scenario ten
times and requires identical output), and, prospectively, to check a from-scratch port in another
language against the same fixtures. A nondeterministic engine cannot be verified this way at all:
every comparison would need a human to judge whether a difference is "just timing" or a real
regression, which defeats the purpose of having a shared, scriptable contract in the first place.

## 7. What is NOT part of the contract

- **The host language and its idioms.** How screens and commands are declared (Swift protocols and
  builder functions in the reference implementation, whatever reads naturally in Kotlin for a port)
  is free to differ completely. Only the *runtime behaviour* they produce — resolution order,
  argument handling, gating, the exact failure text shapes in §1.3 — is specified here.
- **The UI framework**, if any. This contract describes driving an app's state and reading it back;
  it has nothing to say about how (or whether) that state is rendered.
- **Whether an implementation has an in-app bridge at all.** A bridge is one way to deliver a script
  to a running app. An implementation that has one speaks the protocol in §8, so that a CLI can
  drive either port's app; one that has none is still conforming.
- **The CLI's own command names, subcommands and flags** (e.g. `run`, `test`, `--json`, `--diff`,
  `--session`). These are one tool's presentation choices; a port may organize its own tool however
  it likes as long as running a script produces the output this document specifies, with the exit
  codes of §5.
- **The exact bytes of any non-text serialization** a tool happens to also offer (e.g. a JSON array
  of steps): whitespace, key order and number formatting. Only the text format in §3 is normative
  byte for byte; the JSON forms of §8.4 are specified by their keys and values. See also the Open
  Questions note on the reference implementation's own JSON form.
- **Any application's specific vocabulary** — its screen paths, summary keys, command names and
  argument shapes, and mockable methods/error codes. These are declared by whatever app embeds
  AgentCtl and are exactly as arbitrary as that app's own design; this contract only specifies how
  scripts address *whatever* vocabulary an app declares.
- **State diffing, snapshot/UI testing, simulator or device concerns.** These are verification
  layers built on top of a driven app, not part of driving it.

## 8. The in-app bridge

An implementation may also run the engine inside the real app, in its debug builds, and accept
scripts over HTTP from a CLI on the same machine — the reference CLI's `app launch`, `app run`,
`app state` and `app screens`. If it does, it speaks this protocol, so that one CLI can drive
either port's app. **The names below — routes, header, query parameter and launch arguments — are
frozen as they are.** Some of them keep the tool's older spelling (`appctl`); existing CLIs, tests
and documentation send exactly these.

### 8.1 Where it runs

- **Debug builds only.** A release build of the app contains no bridge (the reference compiles it
  out: `#if DEBUG` from end to end).
- **Loopback only.** It listens on `127.0.0.1` and never on another interface.
- **Port 8765** unless the app is launched with `-agent-port <n>`; `-agent-port 0` asks the system
  for a free port. The CLI connects to 8765 unless given another (`--port` in the reference).
- **HTTP/1.1, one request per connection.** A request is a request line, headers, and a body of
  exactly `Content-Length` bytes (no chunked encoding); a request whose headers exceed 64 KiB is
  malformed. The bridge answers and closes the connection. Requests are handled one at a time, in
  the order they arrive.

### 8.2 Launch arguments

The app reads these from its own command line, after the executable's name. Anything else is
ignored.

| Argument | Meaning |
|---|---|
| `-agent-port <n>` | The port to listen on, 0–65535 (`0`: any free port). Default 8765; a value that is not a port is ignored. |
| `-appctl-seed "<script>"` | A script to run before the app shows its first real frame (§8.3). |
| `-mock-latency <ms>` | A fixed latency for every mocked call, in milliseconds, instead of the app's own live latency. |
| `-clear-session` | Forget the app's saved session before it launches. What a session is, is the app's own business. |

### 8.3 Launch seeding

With `-appctl-seed`, before its first real frame the app runs the `(launch)` step (§3.4), then the
seed script — exactly as a headless run does, and failing the same way: the seed stops at its first
failing step, and a `(launch)` step that did not settle fails it before its first line. No view
exists yet, so during the seed the runtime sends each screen's appearance itself. The outcome is
logged, not returned: the reference prints `AgentCtlBridge: seed applied` or
`AgentCtlBridge: seed FAILED (exit <code>)`, then the steps, and the parse error if there was one.

Only then does the bridge start listening, so its first answer means the app is ready: a CLI waits
for the app by polling `GET /snapshot` until it answers.

### 8.4 Routes and responses

| Request | Response |
|---|---|
| `POST /run` | Runs the body, a UTF-8 script, on the app's live store, from whatever state the app is in — there is no `(launch)` step. The body is the steps in the text form of §3, ending in a newline; for a parse error, `error: <message>` and a newline instead. With `?format=json`, the JSON form below. Status 200 whatever the outcome: the outcome is the exit code. |
| `GET /state` | A dump of the app's root state (the reference uses `customDump`), exit 0. Informational: its format is not specified. |
| `GET /screens` | Every screen and its commands, as the CLI's `screens` prints them, exit 0. Informational. |
| `GET /snapshot` | The active screen as one step (§3) whose command echo is `(snapshot)`, exit 0. It runs nothing; its `calls=` are those of the last step that ran. |
| another method on one of those paths | 405, body `method not allowed`, exit 2 |
| any other path | 404, body `not found; endpoints: POST /run, GET /state, GET /screens, GET /snapshot`, exit 2 |
| a request that cannot be parsed | 400, body `bad request` (or `incomplete request` when the connection closed mid-request), exit 2 |

Every response carries these headers:

```
Content-Type: text/plain; charset=utf-8
Content-Length: <bytes in the body>
X-Appctl-Exit: <code>
Connection: close
```

`Content-Type` is `application/json` for `?format=json`. **`X-Appctl-Exit`** is the §5 exit code
of what the request did; the CLI's `app run` prints the body and exits with it (with 3 if the
header is missing).

This is TinyApp answering `POST /run` with the body `open 2`, and then `?format=json` with a script
that does not parse. (The reference's router, on a headless store: the live bridge answers
byte-identically for any script that starts no timer, which its test suite checks.)

```
HTTP/1.1 200 OK
Content-Type: text/plain; charset=utf-8
Content-Length: 69
X-Appctl-Exit: 0
Connection: close

> open 2
  screen=items/2 title="Second item" saved=false cooldown=0
```

```
HTTP/1.1 200 OK
Content-Type: application/json
Content-Length: 87
X-Appctl-Exit: 2
Connection: close

{
  "error" : "parse error: line 1, column 8: unterminated quote",
  "steps" : [

  ]
}
```

**The JSON form** of `POST /run` is an array with one object per step. Each has `command`
(string), `screen` (string), `summary` (an object of string values — which loses the order of
§3.1, see Open Questions), `calls` (array of strings), `error` (string, or `null`), `pending`
(integer), `settled` (boolean), `ok` (boolean), and `message` (string; present only when the step
has one, such as a `FAIL` reason). The array has no place for a failure that belongs to no step, so
when the script did not parse, the body is instead the object above: the message under `error`,
and the steps — none — under `steps`.

### 8.5 How a live run differs from a headless one

- **Time is real.** `advance` is rejected (§2.2); a mocked call takes the app's live latency (or
  `-mock-latency`); and settling waits until no mocked call is in flight and the state has been
  quiet for a moment (the reference: 250 ms quiet, a 3 s ceiling). A step that starts a timer can
  therefore read differently from its headless counterpart once a tick has fired.
- **Views appear by themselves.** The app's views send their own appearance actions, so the bridge
  does not send them — except during a launch seed (§8.3), when there are no views yet.
- **State carries over.** Each `POST /run` continues from the app's current state; nothing is reset
  between requests.

## Open questions

These are genuinely unspecified in the source material — a port should make an explicit decision
rather than copying the reference implementation's incidental behaviour without noticing it was a
choice:

1. **Escape scope inside quotes.** The reference implementation treats `\` as escaping *any*
   following character, not just `\"` and `\\`. Should the contract require the general rule (so
   `\n` inside quotes yields a literal `n`, not a newline), or only guarantee `\"` and `\\` and
   leave other escapes undefined?
2. **Compound durations.** `advance` only accepts a single `<number><unit>` token (`30s`, `5m`); a
   form like `1h30m` is a usage error. Is single-unit-only a deliberate constraint of the contract,
   or just something never needed yet that a port is free to extend?
3. **Values containing a literal quote character.** Neither the summary-value quoting rule (§3.2)
   nor the argument-unquoting rule (§1.2) specifies what happens when a summary value itself
   contains `"`. Left undefined here; a port should pick a rule (e.g. escape it) and document it
   rather than relying on the reference implementation's un-exercised behaviour.
4. **Exact settling thresholds.** The reference implementation waits for a specific number of stable
   rounds and specific real-time ceilings (in both headless and live modes) before giving up and
   reporting `settled=false`. This document treats "wait until quiet, then give up after a bounded
   ceiling" as the contract and leaves the specific numbers as tuning, not a requirement — is that
   the right line, or should a port match the reference's exact thresholds for parity?
5. **JSON output ordering.** The reference implementation's JSON form serializes a step's summary as
   a key/value map rather than an ordered list, silently losing the declaration order that the text
   format (§3.1) treats as meaningful. Is that an intentional simplification for JSON consumers, or
   an oversight that a port's own machine-readable form should avoid repeating?
6. **Literal composition of the "valid here" list.** §1.3's unknown-command message always appends
   exactly `expect, advance, mock` after the screen's own commands. Is a port required to reproduce
   that exact tail, or is the actual requirement just "list the commands that would currently
   resolve, including the runtime ones," with the reference's specific wording being incidental?
