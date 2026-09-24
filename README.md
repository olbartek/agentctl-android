# AgentCtl for Android

AgentCtl makes an app **agent-addressable**. Each screen describes itself:

- a path, such as `items/<id>`;
- a compact `key=value` summary;
- an error code;
- the commands it offers.

A CLI drives those commands from the terminal, *headlessly on the JVM with no emulator*, and prints one line of
state per step. A coding agent (or you) can open a screen, run a command and assert on the result in milliseconds,
instead of building the app, booting an emulator and guessing whether a tap landed. The same script also drives
the real app on a device, through a debug-only in-app bridge, so what you assert headlessly is what the app does.

This is the Kotlin port of [agentctl-ios](https://github.com/olbartek/agentctl-ios). Both implement the same
contract, [CONTRACT.md](CONTRACT.md) (version 1): the same script prints the same bytes in both. This repository's
suite runs every example in the contract and compares the output byte for byte.

```
$ ./tinyctl run "open 2; save"
> (launch)
  screen=items items=3 loading=false calls=items.fetch
> open 2
  screen=items/2 title="Second item" saved=false cooldown=0
> save
  screen=items/2 title="Second item" saved=true cooldown=3 pending=1
```

That is the whole idea:

- `screen=` is where you are.
- The pairs after it are what the screen says about itself.
- `calls=` lists the mocked client methods called during the step.
- `pending=1` means one effect is suspended on the clock: here the three-second save cooldown, which `advance 3s`
  runs out at once instead of waiting.

Scripts can assert, so they can be committed as executable specifications:

```
open 2
expect screen=items/2 cooldown=0 error=none
save
expect saved=true cooldown=3 pending=1 error=none
save
expect error=cooldown saved=true cooldown=3 pending=1
advance 1s
expect cooldown=2 pending=1 error=cooldown
```

This one is from [`examples/tinyapp/scenarios/save-cooldown.appctl`](examples/tinyapp/scenarios/save-cooldown.appctl).
`./tinyctl test` runs it, and so does this repository's own test suite.

## Requirements, and what this is not

- **JDK 17+ to run, Kotlin 2.4, kotlinx.coroutines 1.11.** The engine is plain Kotlin on the JVM. The bridge is an
  Android library: minSdk 28, compiled against SDK 36.
- **An architecture AgentCtl can drive.**
  - `agentctl-core` ships a small unidirectional store: `Store`, `Reducer`, `Effect` (run, cancellable, send,
    cancel, map, scoped). It plays the part TCA plays in the Swift version, and TinyApp is built on it.
  - An app on another architecture (a `ViewModel` exposing a `StateFlow`, say) implements `AgentStore` instead:
    the states, `send`, and the number of effects in flight.
- **Screens are driven through state, not through the UI.** Nothing taps and nothing reads pixels; a headless run
  has no views at all. Rendering is verified separately, by screenshot tests (see
  [the ladder](#the-verification-ladder)).
- **The CLI cannot ship as a prebuilt binary.** It builds your app's store in its own process and sends your
  actions to it, so `agentctl-cli` is a *library*. Each host compiles a small `main` into its own CLI: an import,
  its `AppCtlConfig` and one call.

## The five-minute integration

Every snippet below is real code from [`examples/tinyapp`](examples/tinyapp), a two-screen app with one mocked
client. It is also this repository's test fixture.

### 1. Add the dependency

The artifacts are published through [JitPack](https://jitpack.io/#olbartek/agentctl-android) from this
repository's tags:

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}

// a module's build.gradle.kts
dependencies {
    implementation("com.github.olbartek.agentctl-android:agentctl-core:v0.1.0")
}
```

| Artifact | Who depends on it | What for |
|---|---|---|
| `agentctl-core` | the modules holding your screens | `AgentScreen`, `AgentCommand`, `SummaryItem`, `Store`, `AgentEnvironment`, `MockBackend` |
| `agentctl-runtime` | the module holding your config | `AppCtlConfig`, `ScriptRunner`, the deterministic headless host, the bridge's server |
| `agentctl-cli` | your CLI's module | `AgentCtl.main(config, args)` |
| `agentctl-bridge` | your app, as `debugImplementation` | `AgentLaunch`, the debug-only in-app bridge |
| `agentctl-test-support` | your tests | the coverage guards |

Until 1.0, a minor version may change the API.

### 2. Describe one screen

Put the agent surface beside the screen's state and reducer, in `<Screen>Agent.kt`. This is the complete agent
surface of TinyApp's detail screen
([`ItemDetailAgent.kt`](examples/tinyapp/src/main/kotlin/io/github/olbartek/agentctl/examples/tinyapp/ItemDetailAgent.kt),
doc comments trimmed):

```kotlin
object ItemDetailAgent : AgentScreen<ItemDetail.State, ItemDetail.Action> {
    override val screenPaths = listOf("items/<id>")

    override fun screenPath(state: ItemDetail.State) = "items/${state.item.id}"

    override val summaryKeys = listOf("title", "saved", "cooldown")

    override fun summary(state: ItemDetail.State) = listOf(
        SummaryItem("title", state.item.title),
        SummaryItem("saved", state.saved),
        SummaryItem("cooldown", state.cooldown),
    )

    override fun errorCode(state: ItemDetail.State) = state.error?.code

    override val commands: List<AgentCommand<ItemDetail.State, ItemDetail.Action>> = listOf(
        AgentCommand.action(
            "save",
            help = "Save this item. A save starts a ${ItemDetail.COOLDOWN_SECONDS}-second cooldown; saving again " +
                "before it runs out reports error=cooldown.",
            action = ItemDetail.Action.SaveTapped,
        ),
    )
}
```

- `screenPaths` is the path as the generated docs list it, with `<id>` for the variable part.
- `screenPath` is the concrete path an agent sees and asserts on.
- `summaryKeys` is checked against what `summary` really emits (see [the coverage guards](#the-coverage-guards)).
- **Never put a secret in a summary.** It is printed on every step.

A command can do more than send an action. The list screen's commands
([`ItemsAgent.kt`](examples/tinyapp/src/main/kotlin/io/github/olbartek/agentctl/examples/tinyapp/ItemsAgent.kt))
parse an argument, and are *gated*. A gate mirrors a disabled button: the runner refuses the command and names the
condition that closed it, instead of sending an action that could only do nothing.

```kotlin
    /** Headlessly there is no view to send this, so the runtime sends it when the screen becomes active. */
    override val onAppear: Items.Action = Items.Action.OnAppear

    override val commands: List<AgentCommand<Items.State, Items.Action>> = listOf(
        AgentCommand.parsing(
            "open",
            argument = "<id>",
            help = "Open an item, e.g. open 2.",
            gate = CommandGate("items=0") { it.items.isNotEmpty() },
        ) { text ->
            val id = text.toIntOrNull() ?: invalidArgument("expected an item id such as 2")
            Items.Action.OpenTapped(id)
        },
        AgentCommand.action("refresh", help = "Load the list again.", action = Items.Action.Refresh),
        AgentCommand.action(
            "retry",
            help = "Load the list again after a failure.",
            action = Items.Action.Retry,
            gate = CommandGate("error=none") { it.error != null },
        ),
    )
```

Then a *container* decides which screen is active. That is your navigation stack, tab bar or root, and it:

1. resolves the active screen;
2. lifts that screen's commands to the container's own action type;
3. appends the commands the container owns.

All of
[`TinyRootAgent.kt`](examples/tinyapp/src/main/kotlin/io/github/olbartek/agentctl/examples/tinyapp/TinyRootAgent.kt):

```kotlin
object TinyRootAgent : AgentContainer<TinyRoot.State, TinyRoot.Action> {
    private const val BACK_HELP = "Go back to the list."

    override fun activeScreen(state: TinyRoot.State): ActiveScreen<TinyRoot.Action> {
        val top = state.path.lastOrNull() ?: return ItemsAgent.activeScreen(state.items).map { TinyRoot.Action.Items(it) }
        val child: ActiveScreen<TinyRoot.Action> = when (val screen = top.screen) {
            is TinyRoot.Path.Detail -> ItemDetailAgent.activeScreen(screen.state).map { TinyRoot.Action.Detail(top.id, it) }
        }
        val back = AgentCommand.action<TinyRoot.State, TinyRoot.Action>("back", help = BACK_HELP, action = TinyRoot.Action.PopFrom(top.id))
            .resolve(state, source = "TinyRoot")
        return child.identified("#${top.id}").appending(listOf(back))
    }

    override val registry: List<ScreenDoc>
        get() {
            val back = CommandDoc("back", null, BACK_HELP, "TinyRoot")
            return ItemsAgent.screenDocs + ItemDetailAgent.screenDocs.map { it.inheriting(listOf(back)) }
        }
}
```

Command lookup is **leaf first**: the active screen's own commands, then its containers', then the root's. A screen
can shadow a container's command of the same name.

Finally, route every mock through `MockBackend.call`
([`ItemsClient.kt`](examples/tinyapp/src/main/kotlin/io/github/olbartek/agentctl/examples/tinyapp/ItemsClient.kt)).
That makes the call show up as `calls=items.fetch`, and lets `mock items.fetch network` force it to fail:

```kotlin
fun mock(backend: MockBackend): ItemsClient = ItemsClient(
    fetch = {
        backend.call("items.fetch", { code -> ItemsException(ItemsError.ofCode(code) ?: ItemsError.NETWORK) }) {
            Item.seed
        }
    },
)
```

The app's store and clients take everything outside themselves from an `AgentEnvironment`:

- the scope its effects run in;
- the clock (now and sleep);
- the mock backend;
- identifiers, randomness, the zone and the locale.

Each host fills it differently. The headless host fills it deterministically, the live host with real values, and a
release build with `AgentEnvironment.system(scope)`:

```kotlin
fun store(environment: AgentEnvironment): Store<TinyRoot.State, TinyRoot.Action> = Store(
    initialState = TinyRoot.State(),
    reducer = TinyRoot.reducer(ItemsClient.mock(environment.mocks), environment.clock),
    scope = environment.scope,
)
```

### 3. Write your CLI

One value describes your app to AgentCtl:

- the facts the CLI would otherwise have to hard-code;
- the prose the generated command reference is rendered from;
- the functions that build your store.

Abridged from
[`TinyAppConfig.kt`](examples/tinyapp/src/main/kotlin/io/github/olbartek/agentctl/examples/tinyapp/TinyAppConfig.kt):

```kotlin
val appCtl: AppCtlConfig<TinyRoot.State, TinyRoot.Action>
    get() = AppCtlConfig(
        name = "TinyApp",
        rootMarker = "settings.gradle.kts",   // what the CLI walks up the tree for to find the repo root
        applicationId = "io.github.olbartek.agentctl.examples.tinyapp",   // the debug app, for adb
        launchActivity = ".android.MainActivity",
        gradle = GradleTasks(build = listOf("assemble"), test = listOf("test"), install = ":examples:tinyapp-android:installDebug"),
        scenariosPath = "examples/tinyapp/scenarios",
        docsPath = "examples/tinyapp/agent-commands.md",
        appCheck = AppCheck(scenario = "refresh-error", expectScreen = "items"),
        help = HelpExamples(invocation = "./tinyctl" /* … the examples every help page prints */),
        mockMethods = mockMethods,            // what `mock <client.method> <error>` accepts
        docsText = docsText,                  // the prose around the generated command reference
        screens = screens,                    // TinyRootAgent.registry
        makeHeadless = { headless() },
        makeLive = { latency, dispatcher -> live(latency, dispatcher) },
    )

fun headless() = HeadlessHost(TinyRootAgent, mockMethods, ::store)
```

Your executable is then the whole of
[`Main.kt`](examples/tinyctl/src/main/kotlin/io/github/olbartek/agentctl/examples/tinyctl/Main.kt), in a module
with Gradle's `application` plugin
([`build.gradle.kts`](examples/tinyctl/build.gradle.kts)):

```kotlin
fun main(args: Array<String>): Unit = AgentCtl.main(TinyAppConfig.appCtl, args)
```

```kotlin
plugins { application }
application {
    mainClass.set("io.github.olbartek.agentctl.examples.tinyctl.MainKt")
    applicationName = "tinyctl"
    applicationDefaultJvmArgs = listOf("-Dagentctl.cli.name=tinyctl")   // how the CLI names itself in its help
}
```

### 4. Copy the wrapper

Agents should never call `./gradlew run`: it prints its build log to stdout, mixed into the step output they are
supposed to read. [`Templates/appctl`](Templates/appctl) does this instead:

1. rebuilds your executable (`installDist`), sending the build log to stderr;
2. exits 3 if the build fails;
3. otherwise `exec`s the binary.

```bash
cp Templates/appctl ./appctl     # then set MODULE and NAME at the top of the file
chmod +x ./appctl
./appctl run "open 2; save"
```

Name the file whatever your agents should type, and give `HelpExamples.invocation` the same spelling, so the help
pages name a command that exists in your repo. The wrapper also exports `APPCTL_ROOT`, which `scenariosPath` and
`docsPath` are resolved against. Without it, the CLI walks up from the working directory looking for `rootMarker`.
This repository's own copy is [`./tinyctl`](tinyctl).

### 5. Run something

```
$ ./tinyctl screens
items  [Items]
  open <id>               Open an item, e.g. open 2. [disabled when items=0]
  refresh                 Load the list again.
  retry                   Load the list again after a failure. [disabled when error=none]
  summary: items loading
items/<id>  [ItemDetail]
  save                    Save this item. A save starts a 3-second cooldown; saving again before it runs out reports error=cooldown.
  back                    Go back to the list.  (TinyRoot)
  summary: title saved cooldown
every screen  [runtime]
  expect k=v [k=v …]            Assert on screen, any summary key, call=<client.method> (called during the previous step), error=<code|none> or pending=<n>. A failed assertion fails the script.
  advance <duration>            Advance the test clock, e.g. 500ms, 30s, 5m, 1h. Headless only.
  mock <client.method> <error>  Make the next call to that method fail, e.g. mock items.fetch network.
```

Force a client failure, watch the screen report it, then recover. The fault is one-shot:

```
$ ./tinyctl run "mock items.fetch network; refresh; expect error=network items=3"
> (launch)
  screen=items items=3 loading=false calls=items.fetch
> mock items.fetch network
  screen=items items=3 loading=false
> refresh
  screen=items items=3 loading=false calls=items.fetch error=network
> expect error=network items=3
  screen=items items=3 loading=false calls=items.fetch error=network
```

A failed `expect` still prints the state, says which pair was unmet, and exits 1. A command that does not exist
here is answered with the ones that do, and a gated one names the condition that closed it:

```
$ ./tinyctl run "retry"; echo "exit=$?"
> (launch)
  screen=items items=3 loading=false calls=items.fetch
> retry
  screen=items items=3 loading=false
  FAIL retry is disabled here (error=none)
exit=1
```

## The commands your CLI gets

| Command | What it does |
|---|---|
| `run "<script>"` | Run a script against a fresh headless app, one step per command. `--diff` adds a state diff per step. `--json` prints a JSON array of steps instead of text. `--session <file>` replays a saved file first, then appends the commands that succeed, so state survives across calls. |
| `state` | The full root state, after replaying an optional session file. |
| `screens` | Every screen path with its commands, arguments, help and summary keys, as above. |
| `docs` | Write the generated command reference (`docsPath`) from the registry. `--check` exits 1 when it is stale. |
| `test [files…]` | Run `*.appctl` scenario files (by default all of `scenariosPath`), one PASS/FAIL line each. Finding no scenario files to run is a failure, not "0 passed". |
| `snapshots` | The screenshot tests (the config's Gradle tasks, e.g. Roborazzi's); `--record` re-records the references. |
| `check` | The verification ladder below; `--ui` adds its last two rungs. |
| `app launch` / `app run` / `app state` / `app screens` | The same commands, against the real app on a device or emulator, through the in-app bridge. |

Exit codes are part of the contract:

| Code | Meaning |
|---|---|
| `0` | Everything ran and every `expect` passed. |
| `1` | A command or an `expect` failed, or a step did not settle. This includes `(launch)`. |
| `2` | A usage or parse error, the CLI's own command line included. |
| `3` | An internal or environment error: a scenario file that does not exist, no scenario files to run, no repo root, a build that failed. |

Three runtime commands work on every screen:

- `expect k=v [k=v …]`;
- `advance <duration>`, headless only: a running app's timers are real, so `advance` is rejected there rather than
  silently slept;
- `mock <client.method> <error>`.

Headless runs are deterministic by construction, so the same script always prints the same bytes. That makes step
output usable as a committed fixture. `HeadlessHost` hands your store exactly this environment:

| Field | Headless value |
|---|---|
| `scope` | a `VirtualTimeDispatcher`: every effect runs on one thread, in a fixed order, and `delay` waits on virtual time that only `advance` moves |
| `clock` | `now()` is 2026-01-01T09:00:00Z on every read; `sleep` is virtual and counted for `pending=` |
| `uuids` | `00000000-0000-0000-0000-000000000000`, then `…0001`, … |
| `random` | SplitMix64, seed 0 |
| `zone`, `locale` | UTC, `en_US_POSIX` |
| `mocks` | a fresh call log and fault registry, zero latency |

**Nothing else is pinned.** An app that reads `System.currentTimeMillis()`, `Locale.getDefault()` or switches to
`Dispatchers.IO` around the environment is not deterministic headlessly. If you need more, build it into your store
from the environment inside the `makeStore` function you give `HeadlessHost`.

## The verification ladder

`check` runs the cheapest checks first and stops at the first failure, printing one line per rung:

```
$ ./tinyctl check --ui
L0 build      ok    1 task                       0.5s
L1 test       ok    1 task, 100 tests            11.9s
L2 scenarios  ok    3/3 scenarios                0.0s
docs          ok    up to date                   0.0s
L3 snapshots  ok    no snapshot tasks configured 0.0s
L4 app        ok    refresh-error via the agent bridge 2.9s
  nzoz_pixel7_api36 (Android 16), screenshot: …/.appctl/screenshots/check-ui-1790264952.png
```

| Rung | What runs |
|---|---|
| L0 | the config's `gradle.build` tasks |
| L1 | its `gradle.test` tasks, with the number of tests from the JUnit reports |
| L2 | every scenario file, in-process |
| docs | `docs --check`: the generated command reference is not stale |
| L3 (`--ui`) | the `gradle.snapshotsVerify` tasks (e.g. `verifyRoborazziDebug`); none configured passes, as TinyApp has none |
| L4 (`--ui`) | the real app, in four steps: installed (`gradle.install`), launched on a device (seeded with `appCheck.seed`, zero mock latency), `appCheck.scenario` sent through the bridge, one screenshot |

`--device` (or the config's `device`) names an `adb` serial or an AVD. An AVD that is not running is booted. With
neither, the only connected device is used.

The rule that makes this pay off: **verify at the cheapest rung that proves the change.**

- Logic and flows are L1/L2, and take milliseconds.
- Only a view change needs L3.
- Only the app shell, the bridge or navigation needs L4.

Everything the CLI writes goes under the config's `outputPath`, `.appctl/` by default (`logs/`, `screenshots/`).
Keep it out of version control.

## The coverage guards

`agentctl-test-support` fails your build when the agent surface drifts from what the app actually does. This is the
suite's own use of it
([`CoverageTest.kt`](tests/src/test/kotlin/io/github/olbartek/agentctl/tests/CoverageTest.kt)):

```kotlin
private fun coverage() = AgentCoverage(TinyRootAgent.registry, scenariosDirectory) { TinyAppConfig.headless().makeRunner() }

@Test
fun everyCommandIsUsedByAScenario() {
    assertEquals(emptyList(), coverage().unusedCommands())
}
```

- `unusedCommands()`: a command no scenario ever sends.
- `undocumentedSummaryKeys()`: a key a run emits that the screen's `summaryKeys` does not list.
- `unvisitedScreens()`: a documented screen path no scenario reaches. An `<id>` segment matches any concrete one.

The constructor throws `NoScenariosFound` when the directory holds no `*.appctl` files; otherwise all three guards
would pass having examined nothing.

The guards are deliberately shallow: they check that a command *appears* in some script, not that its result was
asserted.

## The in-app bridge

Add `agentctl-bridge` with **`debugImplementation`** only, and keep the code that names it in `src/debug`. A release
build then carries neither the bridge nor the `INTERNET` permission its manifest adds. The server listens on
`127.0.0.1` only; the CLI reaches it with `adb forward`.

[`examples/tinyapp-android`](examples/tinyapp-android) is TinyApp as a real app: plain views that render the store
and send it the same actions the commands do. Its debug build's store, the whole integration
([`src/debug/…/AppStore.kt`](examples/tinyapp-android/src/debug/kotlin/io/github/olbartek/agentctl/examples/tinyapp/android/AppStore.kt)):

```kotlin
class AppStore private constructor(activity: Activity) {
    private val launch = AgentLaunch(TinyAppConfig.appCtl, activity.intent)

    init {
        MainScope().launch { launch.start() }   // applies the seed, then starts the bridge
    }

    val store: AgentStore<TinyRoot.State, TinyRoot.Action> get() = launch.store
    val isReady: StateFlow<Boolean> get() = launch.isReady   // false while a seed is applied: show a splash

    companion object {
        private var instance: AppStore? = null
        fun get(activity: Activity): AppStore = instance ?: AppStore(activity).also { instance = it }
    }
}
```

Its release twin
([`src/release/…/AppStore.kt`](examples/tinyapp-android/src/release/kotlin/io/github/olbartek/agentctl/examples/tinyapp/android/AppStore.kt))
builds the same store on `AgentEnvironment.system(MainScope())`.

Keep `AgentLaunch` one per process, and start it from a scope that outlives the activity. Right after a cold boot
the system recreates activities as its overlays settle, and a start tied to the first activity would be cancelled
half-way through its seed.

The views send their own appearance actions. The list sends `OnAppear` each time it comes on screen, which is what
the headless runner stands in for, so the same script reads the same in both.

**Launch arguments.** An Android app has no command line, so the arguments of CONTRACT.md §8.2 arrive as intent
extras with the same names, minus the dash. `app launch` sends them with `am start`:

| Extra | Type | Meaning |
|---|---|---|
| `agent-port` | int | the port; 8765 by default, `0` for any free one |
| `appctl-seed` | string | commands applied before the first real frame, so the app opens already in that state |
| `mock-latency` | int, ms | a fixed latency for every mocked call |
| `clear-session` | boolean | calls the config's `clearSession()` before the store is built |

```
$ ./tinyctl app launch --seed "open 2"
launched on nzoz_pixel7_api36 (Android 16) [emulator-5554] in 3.8s: screen=items/2 title="Second item" saved=false cooldown=0
$ ./tinyctl app run "save; expect saved=true pending=1; back"
> save
  screen=items/2 title="Second item" saved=true cooldown=3 pending=1
> expect saved=true pending=1
  screen=items/2 title="Second item" saved=true cooldown=3 pending=1
> back
  screen=items items=3 loading=false calls=items.fetch
```

A seed is a script and fails like one: at its first failing step, or at a `(launch)` that did not settle. The app
logs `AgentCtlBridge: seed applied` or `AgentCtlBridge: seed FAILED (exit <code>)`, with its steps, under the
logcat tag `AgentCtlBridge`.

The same scripts then run against the real app (`app run`), on the live clock and with real mock latency. That is
why `advance` is rejected there. The wire protocol (routes, the `X-Appctl-Exit` header, the JSON form) is
[CONTRACT.md §8](CONTRACT.md#8-the-in-app-bridge), so either port's CLI can drive either port's app.

## The contract

[`CONTRACT.md`](CONTRACT.md) is copied unchanged from agentctl-ios; the Swift package is its reference
implementation. It specifies:

- the script language and command resolution;
- the three runtime commands;
- the exact step output and the exit codes;
- the determinism requirements;
- the bridge protocol.

On the contract's open questions, this port follows the reference in every case, so fixtures are shared:

- escapes are generic;
- a duration takes a single unit;
- summary values are not escaped;
- settling uses the same thresholds;
- the JSON uses sorted keys laid out as Foundation's encoder lays them out;
- the "Valid here" list ends in the same tail.

It also counts script columns in grapheme clusters, as Swift does. The design notes of the port are in
[`.agent/thoughts/plans/2026-09-24-kotlin-port.md`](.agent/thoughts/plans/2026-09-24-kotlin-port.md).

## Building this repository

```bash
./gradlew build          # every module, the Android bridge and sample app included, and the whole suite
./tinyctl test           # the example's scenarios
./tinyctl check          # the ladder: L0 build, L1 tests, L2 scenarios, docs (--ui adds the emulator)
```

You need JDK 17+ (`JAVA_HOME`) and an Android SDK (`local.properties` with `sdk.dir`, or `ANDROID_HOME`) for the
Android modules.

## Status

Version 0.1, a port of agentctl-ios 0.3. The example app is the only integration CI exercises. The API may still
change between minor versions before 1.0. MIT licensed.
