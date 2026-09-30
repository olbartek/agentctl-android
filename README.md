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

## How much faster

[`examples/agentshop`](examples/agentshop) (sign-in, onboarding, a shop) has 99 scenarios that run unchanged in
three modes: headlessly, through the bridge in the real app on an emulator, and as Compose UI tests generated from
the same files. On an Apple M4 Max, with a Pixel 7 emulator (API 36):

| | Headless | Emulator, via the bridge | Compose UI tests |
|---|---|---|---|
| All 99 scenarios (1,104 steps) | **291 ms** | 5 min 48 s | 5 min 12 s |
| A typical 12-step scenario | **32 ms** | 3.7 s | 3.3 s |
| Change a line of a reducer, then check it | **1.1 s** | — | 5.0 s |

Headless has nothing to wait for: no emulator, no app to launch, no views, and a virtual clock instead of real time.
Starting its JVM (143 ms) is about half of that total. The bridge pays for a real app, a launch per scenario and a
250 ms quiet window per command. The Compose UI tests run inside the app's process and wait only until the app is
idle, so here they cost about what the bridge does, far less than the XCUITests of
[the iOS port](https://github.com/olbartek/agentctl-ios#how-much-faster) (30 minutes for the same scenarios). They
also share one process per group instead of relaunching the app for every test.
[The full report](docs/benchmarks/2026-09-29-agentshop.md) explains where the time goes, and
[this video](docs/benchmarks/2026-09-29-agentshop.mp4) runs three scenarios side by side.

The modes check different things, so this is not a case for deleting UI tests. It is a case for which one an agent
runs hundreds of times a day.

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
    implementation("com.github.olbartek.agentctl-android:agentctl-core:0.4.2")
}
```

| Artifact | Who depends on it | What for |
|---|---|---|
| `agentctl-core` | the modules holding your screens | `AgentScreen`, `AgentCommand`, `SummaryItem`, `Store`, `AgentEnvironment`, `MockBackend` |
| `agentctl-runtime` | your config module, which only your debug app (`debugImplementation`), your CLI and your tests depend on | `AppCtlConfig`, `ScriptRunner`, the deterministic headless host, the bridge's server |
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
release build with `AgentEnvironment.system(scope)`. TinyApp's
([`TinyApp.kt`](examples/tinyapp/src/main/kotlin/io/github/olbartek/agentctl/examples/tinyapp/TinyApp.kt)) needs
nothing but `agentctl-core`, so it ships:

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
[`TinyAppConfig.kt`](examples/tinyapp-config/src/main/kotlin/io/github/olbartek/agentctl/examples/tinyapp/TinyAppConfig.kt):

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

fun headless() = HeadlessHost(TinyRootAgent, mockMethods, TinyApp::store)
```

**Debug builds only.** The config needs `agentctl-runtime`, so keep it in a module of its own, apart from your
screens, and let only your debug app (`debugImplementation`), your CLI and your tests depend on it. Your screens'
`…Agent.kt` files and your store use only `agentctl-core`, and ship. A release build then carries none of AgentCtl's
runtime, CLI or test support. TinyApp does exactly this:
[`examples/tinyapp`](examples/tinyapp/build.gradle.kts) is the app, on `agentctl-core`;
[`examples/tinyapp-config`](examples/tinyapp-config/build.gradle.kts) is its config; and
[`examples/tinyapp-android`](examples/tinyapp-android/build.gradle.kts) takes the config with
`debugImplementation` and checks, as part of `check`, that its release APK holds no class of
`agentctl-runtime`, `agentctl-cli`, `agentctl-test-support` or `agentctl-bridge` (`verifyReleaseLeavesOutAgentCtl`,
which you can copy).

```kotlin
// your app's build.gradle.kts
dependencies {
    implementation(project(":feature:items"))           // screens and store: agentctl-core
    debugImplementation(project(":appctl-config"))      // the AppCtlConfig: agentctl-runtime
    debugImplementation("com.github.olbartek.agentctl-android:agentctl-bridge:0.4.2")
}
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
  advance <duration>            Move the app's clock forward, e.g. 500ms, 30s, 5m, 1h, firing the timers due.
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
| `snapshots` | The screenshot tests (Roborazzi's or Paparazzi's, found in the config's `gradle.snapshotModules`); `--record` re-records the references. |
| `check` | The verification ladder below; `--ui` adds its last two rungs. |
| `app launch` / `app run` / `app state` / `app screens` | The same commands, against the real app on a device or emulator, through the in-app bridge. `app launch` picks a free port and records it in `<outputPath>/bridge.json`, which the others read. |
| `app test [files…]` | The scenario files, in the real app on a device: one fresh launch each, one PASS/FAIL/SKIP line each. `--record <mp4>` records the run, `--step-delay <s>` sends a line at a time so the recording can be followed. |

Exit codes are part of the contract:

| Code | Meaning |
|---|---|
| `0` | Everything ran and every `expect` passed. |
| `1` | A command or an `expect` failed, or a step did not settle. This includes `(launch)`. |
| `2` | A usage or parse error, the CLI's own command line included. |
| `3` | An internal or environment error: a scenario file that does not exist, no scenario files to run, no repo root, a build that failed. |

Three runtime commands work on every screen:

- `expect k=v [k=v …]`;
- `advance <duration>`: the virtual clock headlessly; in the running app, the app's real-time clock jumped forward
  (see [the bridge](#the-in-app-bridge));
- `mock <client.method> <error>`.

Headless runs are deterministic by construction, so the same script always prints the same bytes. That makes step
output usable as a committed fixture. `HeadlessHost` hands your store exactly this environment:

| Field | Headless value |
|---|---|
| `scope` | a `VirtualTimeDispatcher`: every effect runs on one thread, in a fixed order, and `delay` waits on virtual time that only `advance` moves |
| `clock` | `now()` is 2026-01-01T09:00:00Z plus whatever `advance` has added; `sleep` is virtual and counted for `pending=` |
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
| L3 (`--ui`) | the screenshot tests of each module in `gradle.snapshotModules` (see below), plus any `gradle.snapshotsVerify` tasks; none configured passes, as TinyApp has none |
| L4 (`--ui`) | the real app, in four steps: installed (`gradle.install`), launched on a device (seeded with `appCheck.seed`, zero mock latency), `appCheck.scenario` sent through the bridge, one screenshot |

L3 assumes no task names. For each module in `gradle.snapshotModules` (a Gradle path such as `:feature:items`) it
runs `gradlew <module>:tasks --all` and takes Roborazzi's `verifyRoborazzi<Variant>` tasks, or else Paparazzi's
`verifyPaparazzi<Variant>`: the `Debug` variant's, every `…Debug` variant's in a module with product flavors, or the
only variant there is. `snapshots --record` takes the matching `record…` tasks, and the review hint it prints
names the modules' directories unless `gradle.snapshotReferences` says where the references live. A task of any
other tool goes in `gradle.snapshotsVerify` and `gradle.snapshotsRecord`, which run as named.

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

Add `agentctl-bridge` with **`debugImplementation`** only, as your config module, and keep the code that names them
in `src/debug`. A release build then carries neither the bridge, nor AgentCtl's runtime, nor the `INTERNET`
permission the bridge's manifest adds. The server listens on
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
| `agent-port` | int | the port; `app launch` passes the one it chose (see below). Without it the app uses 8765, and `0` means any free port |
| `appctl-seed` | string | commands applied before the first real frame, so the app opens already in that state |
| `mock-latency` | int, ms | a fixed latency for every mocked call |
| `clear-session` | boolean | calls the config's `clearSession()` before the store is built |

```
$ ./tinyctl app launch --seed "open 2"
launched on agentctl_pixel7_api36 (Android 16) [emulator-5556] at 127.0.0.1:8765 in 3.8s: screen=items/2 title="Second item" saved=false cooldown=0
$ ./tinyctl app run "save; expect saved=true pending=1; back"
> save
  screen=items/2 title="Second item" saved=true cooldown=3 pending=1
> expect saved=true pending=1
  screen=items/2 title="Second item" saved=true cooldown=3 pending=1
> back
  screen=items items=3 loading=false calls=items.fetch
```

**Launch state and ports.** `app launch` starts the bridge on 8765 if that port is free, or else on the next free
one up to 8864. A port counts as taken if anything on the Mac listens on it: another device's `adb forward`, or an
iOS simulator's bridge, which shares the Mac's loopback. It also counts as taken if anything on the device listens
on it, such as another app's bridge. `app launch` forwards the port it chose and records the launch in
`<outputPath>/bridge.json`:

```json
{
  "appId" : "io.github.olbartek.agentctl.examples.tinyapp",
  "device" : "emulator-5556",
  "launchedAt" : "2026-09-30T10:58:06Z",
  "platform" : "android",
  "port" : 8765
}
```

`app run`, `app state` and `app screens` then talk to that port, so two apps (or an emulator and a simulator) on one
Mac never need a port by hand. Each command's port is `--port` if given, else `APPCTL_PORT`, else the last launch's,
else 8765. `app launch` and `app test` take `--port` or `APPCTL_PORT` as the exact port to use, and otherwise scan as
above. `app test` and `check --ui` rewrite the file on every launch. A relaunch keeps its port: the app is stopped
first, and the forward its last launch on that device left (as `bridge.json` records it) is removed once nothing
on the device listens behind it; no other forward is touched. When the recorded app has quit,
`app run` says the file is stale and to relaunch. The file is written exactly as agentctl-ios writes it (CONTRACT.md
§8.6).

**Who answers.** The bridge names its app on every response (`X-Appctl-App: <application id>`, §8.4), so the CLI never
drives the wrong app. `app launch` checks that its own app answered. A different app, or an answer without the
header, means the port was taken. A scanned port then gets one more try on the next free port, and a port you named
fails with exit 3. When the port came from `bridge.json`, `app run` first asks `GET /snapshot` who answers, and posts
the script only to the recorded app; `app state` and `app screens` check their own answer. A bridge from before the
header is accepted there.

A seed is a script and fails like one: at its first failing step, or at a `(launch)` that did not settle. The app
logs `AgentCtlBridge: seed applied` or `AgentCtlBridge: seed FAILED (exit <code>)`, with its steps, under the
logcat tag `AgentCtlBridge`.

The same scripts then run against the real app (`app run`), on real time and with real mock latency. `advance`
works there too: the live host's clock is an `AdvanceableClock`, which `advance` moves forward deadline by deadline,
letting the app settle between them, so a countdown ticks once per second advanced, as it does headlessly, and its
`now()` moves with it. Only what sleeps on `environment.clock` moves; a bare `delay`, a `Handler` or a `Timer` keeps
real time. A backend of yours that reads the time or sleeps should do it on `environment.clock`, as it would
headlessly.

A step in the running app also waits for its UI (CONTRACT.md §8.5): a screen still sliding in or out is not settled,
so the next step, and a screenshot, start on the screen the user sees. The bridge asks Compose whether it is idle (no
recomposition pending and no frame awaited, which is what an animation does); an app without Compose is always idle
there. For UI of another kind, set your own signal on the `AgentLaunch`:

```kotlin
launch.isUIIdle = { !navigator.isTransitioning }   // called on the main thread
```

A UI that stays busy for more than a second (a spinner on screen) is animating without end, not in a transition, and
stops holding the step. A launch seed does not wait for the UI: it runs behind the splash, before the store's screens
are shown.

`app test` runs the scenario files this way, as `test` runs them headlessly: it builds and installs once, then for
each file launches the app with no saved session (`clear-session`) and sends the file through the bridge.
`--latency <ms>` sets the mock latency (0 unless given, as for L4, so the first screen has loaded when the script
starts), `--no-build` uses the installed app, `--record <mp4>` records the device
for the whole run (`adb shell screenrecord`, in back-to-back chunks under its three-minute limit, joined with
`ffmpeg` when it is installed) and writes `<mp4>.chapters.txt` with the time each scenario started, and
`--step-delay <s>` sends one line at a time so the video can be followed. A few scenarios are true headlessly but
not in a running app: a first `expect` on the launch's own calls (`app launch` has made them before the script
starts), a countdown's exact value (it also ticks in real time), or a date that is in the future only against the
headless fixed date. Such a file says so on a comment line, and `app test` prints it as skipped:

```
# app-test: skip the countdown also ticks in real time
```

The wire protocol (routes, the `X-Appctl-Exit` header, the JSON form) is
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

It also counts script columns in grapheme clusters, as Swift does.

Where Android differs from iOS, the port adapts the reference rather than copying it:

- L3 finds each snapshot module's Roborazzi or Paparazzi tasks (`gradle.snapshotModules`) where the reference finds
  a package's Xcode scheme and its `*SnapshotTests` targets (`snapshotPackages`), and runs them on the JVM, with no
  device.
- The live host's `AdvanceableClock` is itself the `AgentClock` that counts sleeps for `pending=`, where the
  reference wraps it in a `CountingClock`; and a backend reads the moved time from `environment.clock.now()`, where
  the reference adds `LiveEnvironment.now`.
- `app test` runs on a device through `adb` (`screenrecord` for `--record`), and fixes the mock latency at 0 unless
  `--latency` is given, as L4 does, where the reference uses the app's own latency.
- A screen's summary, its commands' disabled checks and their argument parsing need no "store's dependency
  context" (§6): an `AgentScreen` sees only the state, never a clock, so it cannot read another "now" than the
  reducers. A date rule (a code's expiry, say) lives in the reducer, which reads `environment.clock`, and the state
  carries its result. The reference computes screens inside the store's `withDependencies` instead, because a SwiftUI
  feature's summary can read `@Dependency(\.date)` directly.
- Live settling's UI signal (§8.5) is Compose's idleness (no recomposition pending, no frame awaited), where the
  reference asks UIKit whether a view controller has a transition, presentation or dismissal under way. Compose's
  signal also sees endless animations; the shared rule that a UI busy for more than a second stops holding a step
  covers them.
- A release build leaves AgentCtl's runtime out because the config module is a `debugImplementation` dependency,
  which Gradle can drop per build type; the reference, whose SwiftPM cannot, compiles its runtime, CLI and test
  support to nothing unless `DEBUG` or `AGENTCTL_RELEASE` is set. The same gate here is the release APK check.

## The example apps

[`examples/tinyapp`](examples/tinyapp) is the smallest complete integration and this repository's fixture: two
screens, one mocked client, three scenarios, its own `tinyctl` CLI and a committed
[generated command reference](examples/tinyapp/agent-commands.md). Every snippet above is from it, and
[`examples/tinyapp-android`](examples/tinyapp-android) runs it as an Android app with the bridge.

[`examples/agentshop`](examples/agentshop) is the showcase: a real Compose app (sign-in, onboarding, a shop with a
cart and checkout), the port of the iOS one, with 105 scenarios that print the same bytes on both ports, UI tests
generated from them, and the benchmark behind [the numbers above](#how-much-faster). See
[its README](examples/agentshop/README.md) and [the app, screen by screen](examples/agentshop/docs/APP.md).

## Building this repository

```bash
./gradlew build          # every module, the Android bridge and sample app included, and the whole suite
./tinyctl test           # the example's scenarios
./tinyctl check          # the ladder: L0 build, L1 tests, L2 scenarios, docs (--ui adds the emulator)
```

You need JDK 17+ (`JAVA_HOME`) and an Android SDK (`local.properties` with `sdk.dir`, or `ANDROID_HOME`) for the
Android modules.

## Status

Version 0.4.2. The two example apps in this repository are the integrations CI exercises, and the API may still
change between minor versions before 1.0. MIT licensed.

This port and [agentctl-ios](https://github.com/olbartek/agentctl-ios) move in lockstep: the same features, the same
example apps with the same scenario files, and the same MAJOR.MINOR version, so 0.4.x here and 0.4.x there implement the
same [`CONTRACT.md`](CONTRACT.md). A patch release is one repository's own fix, so the two patch numbers can differ:
0.4.2 here fixes the running app's clock, which the Swift package did not need. (Before 0.4.1 this port had its own
numbers: v0.1.0–v0.2.0.)
