# The five-minute integration

Most snippets below are real code from [`examples/tinyapp`](../../examples/tinyapp), a two-screen app with one mocked
client and this repository's test fixture. The others name their source (AgentShop, TinyMVVM), except the `choice`
forms, which are illustrations.

## 1. Add the dependency

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
    implementation("com.github.olbartek.agentctl-android:agentctl-core:0.7.0")
}
```

| Artifact | Who depends on it | What for |
|---|---|---|
| `agentctl-core` | the modules holding your screens | `AgentScreen`, `AgentCommand`, `SummaryItem`, `Store`, `AgentEnvironment`, `MockBackend`, `AgentViewModel`, `AgentCoordinator`, `AgentTasks` |
| `agentctl-runtime` | your config module, which only your debug app (`debugImplementation`), your CLI and your tests depend on | `AgentDriver` with `AgentStoreDriver` and `AgentViewModelDriver`, `AppCtlConfig`, `ScriptRunner`, the deterministic headless host, the bridge's server |
| `agentctl-cli` | your CLI's module | `AgentCtl.main(config, args)` |
| `agentctl-bridge` | your app, as `debugImplementation` | `AgentLaunch`, the debug-only in-app bridge |
| `agentctl-test-support` | your tests | the coverage guards |

Until 1.0, a minor version may change the API.

## 2. Describe one screen

Put the agent surface beside the screen's state and reducer, in `<Screen>Agent.kt`. This is the complete agent
surface of TinyApp's detail screen
([`ItemDetailAgent.kt`](../../examples/tinyapp/src/main/kotlin/io/github/olbartek/agentctl/examples/tinyapp/ItemDetailAgent.kt),
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
- `summaryKeys` is checked against what `summary` really emits (see [the coverage guards](coverage-and-checks.md)).
- **Never put a secret in a summary.** It is printed on every step.

A command can do more than send an action. The list screen's commands
([`ItemsAgent.kt`](../../examples/tinyapp/src/main/kotlin/io/github/olbartek/agentctl/examples/tinyapp/ItemsAgent.kt))
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
[`TinyRootAgent.kt`](../../examples/tinyapp/src/main/kotlin/io/github/olbartek/agentctl/examples/tinyapp/TinyRootAgent.kt):

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

The other command kinds: `text` takes the rest of the line as it is typed, `onOff` a switch (`terms on`), and
`choice` one of a fixed set of words, from which it generates both the documented argument and the error, so the two
cannot drift apart:

```kotlin
// an enum's entries in their order, each spelled by `word`
AgentCommand.choice("tab", of = Tab.entries, word = { it.code }, help = "Switch tab.") { TabSelected(it) }
// words standing for values
AgentCommand.choice("sort", listOf("new" to Sort.NEWEST, "cheap" to Sort.PRICE_ASCENDING), help = "Sort.") { SortTapped(it) }
// the word itself
AgentCommand.choice("login-as", listOf("alice", "bob"), help = "Sign in.") { LoginAs(it) }
```

`tab <shop|cart|orders|profile>` is how the first is documented, and `tab home` fails with
`tab <shop|cart|orders|profile>: invalid argument: expected shop|cart|orders|profile`.

A container that offers commands on *every* screen beneath it — a root's `login-as`, a tab bar's `tab` — declares
them once as `inheritedCommands`, and `inheritingCommands` adds them both to the active screen and to every screen of
the registry, so the docs cannot list a command the app does not offer. They go after the commands of the screens
beneath, and before the container's own `back`, whichever you apply first; a command with `paths` is offered on those
paths only. A root can also offer a `back` for when nothing is pushed, which fails with
`back: nothing to go back to on <path>` rather than as an unknown command. From AgentShop's
[`AppFeature.kt`](../../examples/agentshop/shop/src/main/kotlin/io/github/olbartek/agentctl/examples/agentshop/app/AppFeature.kt):

```kotlin
object AppFeatureAgent : AgentContainer<AppFeature.State, AppFeature.Action> {
    override val inheritedCommands: List<AgentCommand<AppFeature.State, AppFeature.Action>> = listOf(
        AgentCommand.choice("login-as", listOf("alice" to MockAccounts.alice.user, "bob" to MockAccounts.bob.user), help = LOGIN_AS_HELP) {
            AppFeature.Action.LoginAs(MockAccounts.session(it))
        },
        AgentCommand.action("reset", help = RESET_HELP, action = AppFeature.Action.Reset),
    )

    override fun activeScreen(state: AppFeature.State): ActiveScreen<AppFeature.Action> {
        val screen: ActiveScreen<AppFeature.Action> = …   // the active child, as above
        return inheritingCommands(screen, state).appendingBackFallback(containerName)
    }

    override val registry: List<ScreenDoc>
        get() {
            val screens = AuthFlowAgent.registry + OnboardingFlowAgent.registry + HomeTabsAgent.registry   // abridged
            return inheritingCommands(screens).map { it.inheriting(listOf(CommandDoc.backFallback(containerName))) }
        }
}
```

A container's `containerName`, the source its commands are shown with, defaults to its class name without an `Agent`
suffix, as a screen's `screenName` does.

Command lookup is **leaf first**: the active screen's own commands, then its containers', then the root's. A screen
can shadow a container's command of the same name.

Finally, route every mock through `MockBackend.call`
([`ItemsClient.kt`](../../examples/tinyapp/src/main/kotlin/io/github/olbartek/agentctl/examples/tinyapp/ItemsClient.kt)).
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

Each client lists the codes `mock` may force on its methods as `MockMethod`s. A failure every call can have, whatever
its client's own errors, is added once rather than per method: `(AuthClient.mockMethods +
OrdersClient.mockMethods).accepting(listOf("network"))` gives each method `network` after its own codes, unless it
already lists it, so `mock`'s `valid:` list and the docs read as if every client had written it out.

The app's store and clients take everything outside themselves from an `AgentEnvironment`:

- the scope its effects run in;
- the clock (now and sleep);
- the mock backend;
- identifiers, randomness, the zone and the locale.

Each host fills it differently. The headless host fills it deterministically, the live host with real values, and a
release build with `AgentEnvironment.system(scope)`. TinyApp's
([`TinyApp.kt`](../../examples/tinyapp/src/main/kotlin/io/github/olbartek/agentctl/examples/tinyapp/TinyApp.kt)) needs
nothing but `agentctl-core`, so it ships:

```kotlin
fun store(environment: AgentEnvironment): Store<TinyRoot.State, TinyRoot.Action> = Store(
    initialState = TinyRoot.State(),
    reducer = TinyRoot.reducer(ItemsClient.mock(environment.mocks), environment.clock),
    scope = environment.scope,
)
```

## 3. Write your CLI

One value describes your app to AgentCtl:

- the facts the CLI would otherwise have to hard-code;
- the prose the generated command reference is rendered from;
- the functions that build your store.

Its type names the *driver* — how the engine reaches your app — and the action type: `AgentStoreDriver<State, Action>` for
a store. Abridged from
[`TinyAppConfig.kt`](../../examples/tinyapp-config/src/main/kotlin/io/github/olbartek/agentctl/examples/tinyapp/TinyAppConfig.kt):

```kotlin
val appCtl: AppCtlConfig<AgentStoreDriver<TinyRoot.State, TinyRoot.Action>, TinyRoot.Action>
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
[`examples/tinyapp`](../../examples/tinyapp/build.gradle.kts) is the app, on `agentctl-core`;
[`examples/tinyapp-config`](../../examples/tinyapp-config/build.gradle.kts) is its config; and
[`examples/tinyapp-android`](../../examples/tinyapp-android/build.gradle.kts) takes the config with
`debugImplementation` and checks, as part of `check`, that its release APK holds no class of
`agentctl-runtime`, `agentctl-cli`, `agentctl-test-support` or `agentctl-bridge` (`verifyReleaseLeavesOutAgentCtl`,
which you can copy).

```kotlin
// your app's build.gradle.kts
dependencies {
    implementation(project(":feature:items"))           // screens and store: agentctl-core
    debugImplementation(project(":appctl-config"))      // the AppCtlConfig: agentctl-runtime
    debugImplementation("com.github.olbartek.agentctl-android:agentctl-bridge:0.7.0")
}
```

Your executable is then the whole of
[`Main.kt`](../../examples/tinyctl/src/main/kotlin/io/github/olbartek/agentctl/examples/tinyctl/Main.kt), in a module
with Gradle's `application` plugin
([`build.gradle.kts`](../../examples/tinyctl/build.gradle.kts)):

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

## 4. Copy the wrapper

Agents should never call `./gradlew run`: it prints its build log to stdout, mixed into the step output they are
supposed to read. [`Templates/appctl`](../../Templates/appctl) does this instead:

1. finds a JDK 17 or newer: `JAVA_HOME`'s, else the `java` on PATH, else Homebrew's `openjdk@21` (a Mac without a
   system JDK), and exits 3 saying so when there is none;
2. rebuilds your executable (`installDist`), sending the build log to stderr;
3. exits 3 if the build fails;
4. otherwise `exec`s the binary.

```bash
cp Templates/appctl ./appctl     # then set MODULE and NAME at the top of the file
chmod +x ./appctl
./appctl run "open 2; save"
```

Name the file whatever your agents should type, and give `HelpExamples.invocation` the same spelling, so the help
pages name a command that exists in your repo. The wrapper also exports `APPCTL_ROOT`, which `scenariosPath` and
`docsPath` are resolved against. Without it, the CLI walks up from the working directory looking for `rootMarker`.
This repository's own copy is [`./tinyctl`](../../tinyctl).

## 5. Run something

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
  device open-url <url>         Open a link in the app, as a tapped deep link or universal link.
  device push <name> [tap]      Deliver a push notification while the app is in front; with tap, open the app from it. Pushes: item-updated (Item 2 was updated.).
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

## MVVM apps, without a store

The engine never sees your app's types: it talks to an `AgentDriver`, which answers a few questions — which screen is
active, deliver this action, has anything changed (settling's fingerprint), relaunch, and dump the state. A store app
uses `AgentStoreDriver`; an app of view models under a coordinator uses `AgentViewModelDriver`. Scripts, scenarios, the step
output and the generated docs are the same either way: [`examples/tinymvvm`](../../examples/tinymvvm) is TinyApp
rebuilt as MVVM, and it runs TinyApp's scenario files byte for byte.

A screen is an `AgentViewModel`: a view model (a Jetpack `ViewModel` here) whose agent surface is the `AgentScreen` you
already know, computed from a value, `agentState`. Its commands are `AgentScreenIntent`s, functions of the view model on
screen, and the screen is usually its companion object. Abridged from
[`ItemsViewModel.kt`](../../examples/tinymvvm/src/main/kotlin/io/github/olbartek/agentctl/examples/tinymvvm/ItemsViewModel.kt):

```kotlin
class ItemsViewModel(private val environment: AgentEnvironment, private val client: ItemsClient) :
    ViewModel(), AgentViewModel<ItemsViewModel.UiState, ItemsViewModel> {
    data class UiState(val items: List<Item> = emptyList(), val isLoading: Boolean = false, val error: ItemsError? = null)

    private val mutableState = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = mutableState.asStateFlow()     // what the Compose screen collects
    private var loadJob: Job? = null

    fun refresh() {
        mutableState.update { it.copy(isLoading = true, error = null) }
        loadJob?.cancel()
        loadJob = environment.tasks.run {                             // not viewModelScope: see the rules below
            try {
                val items = client.fetch()
                if (isActive) mutableState.update { it.copy(isLoading = false, items = items) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {                                // `mock items.fetch network`: state, not a crash
                if (isActive) mutableState.update { it.copy(isLoading = false, error = ItemsError.of(error)) }
            }
        }
    }

    override val agentState: UiState get() = state.value
    override val agentScreen get() = Companion

    companion object : AgentScreen<UiState, AgentScreenIntent<ItemsViewModel>> {
        override val screenName = "Items"
        override val screenPaths = listOf("items")
        override fun screenPath(state: UiState) = "items"
        // summaryKeys, summary(state), errorCode(state) exactly as for a store screen
        override val onAppear = AgentScreenIntent<ItemsViewModel> { it.onAppear() }
        override val commands = listOf(
            AgentCommand.action<UiState, AgentScreenIntent<ItemsViewModel>>("refresh", help = "Load the list again.", action = AgentScreenIntent { it.refresh() }),
        )
    }
}
```

The coordinator is an `AgentCoordinator`: it owns the back stack (a list, as Navigation 3's `NavDisplay(backStack)`
renders it), creates each pushed view model, and returns the screen on top — `items.activeScreen()`, or a pushed view
model's with its own `back` appended as an `AgentIntent` — and `cancelAllWork()`. The config names
`AgentViewModelDriver<AppCoordinator>`, and its hosts take the coordinator to build, on the environment of each launch:

```kotlin
fun headless(): HeadlessHost<AgentViewModelDriver<AppCoordinator>, AgentIntent<AppCoordinator>> =
    HeadlessHost(mockMethods, device) { AppCoordinator(it) }
```

A store keeps a reducer deterministic for free; a view model keeps itself deterministic by following these rules,
and settling relies on every one of them:

- **View models run on the app's thread** (the main thread in the app, the host's dispatcher headlessly), and the
  coordinator creates them.
- **Every clock, id and client comes from the `AgentEnvironment` they are given**, never `System.currentTimeMillis()`,
  `delay` on another dispatcher, `UUID.randomUUID()` or a singleton. The headless host pins them as it does for a store.
- **Async work starts through `environment.tasks`** (`AgentTasks.run { … }`). That is how settling knows work is in
  flight, and it runs on the host's dispatcher, virtual time headlessly. `viewModelScope` is neither: settling
  cannot see its work, and on the JVM it runs on real threads. A failure the work does not catch is uncaught (the
  scope is a supervisor's) and crashes the app, so catch it and make it state, as above.
- **A child is created with its parent's environment**, so its clock and clients are the app's.
- **A pop cancels the popped screen's work**, as a store cancels a stack element's effects: the coordinator calls the
  view model's `cancelAllWork()`. (Its `onCleared()` calls it too, for a view model a `ViewModelStore` owns; the
  coordinator creates its own directly, so for them the pop is what counts.)
- **Stack ids are a counter, not random**: they appear in screen identity (`#3`), which must be the same every run.
- **Appearing is an intent**: the screen's `onAppear` is what the runner sends headlessly, and what the screen's
  `LaunchedEffect` calls in the app.

In the app, `AgentLaunch(config, intent)` works as it does for a store; `launch.app` is the coordinator, and a
`device relaunch` replaces it (`launch.apps`; see [the bridge guide](bridge.md)).

### Moving a host to 0.7

0.7 put the engine behind `AgentDriver`. A store host's scripts, scenarios and output are unchanged; its Kotlin
changes as follows:

- Every public name 0.7 adds starts with `Agent`, so none clashes with an app's own.
- `AppCtlConfig<State, Action>`, `HeadlessHost<State, Action>` and `LiveHost<State, Action>` become
  `…<AgentStoreDriver<State, Action>, Action>`, and `ScriptRunner<State, Action>` becomes `ScriptRunner<Action>`. The
  `HeadlessHost(container, mockMethods, …)` and `LiveHost(container, …)` calls are unchanged.
- The test helpers: `AgentCoverage<Action>` and `AgentScenarioChecks<AgentStoreDriver<State, Action>, Action>`. A
  test that reads `host.store` imports `io.github.olbartek.agentctl.runtime.store`.
- `AgentLaunch.store` and `stores` are now `launch.app` and `apps`, and `AgentLaunch.isRequested(intent)` is also
  `AgentLaunchArguments.isRequested(intent)`. That includes the host's own call sites, such as the debug-only class
  that hands `launch.store` to the app's root: it reads `launch.app`. The old names still compile, with a deprecation
  warning, so a build that treats warnings as errors fails until they move.
- A host that built a `ScriptRunner` itself passes `driver = host.driver` instead of the store and the container.
- The store lives on the driver: `host.store` (the import above), `host.driver.state`, and `host.container` is
  `host.driver.container`. `ScriptRunner.store` and `ScriptRunner.state` are gone, since a runner names only the
  action type; keep the host and read `host.store`.
- `host.environment` is now the host's own, which outlives relaunches; the current launch's (whose scope a relaunch
  cancels, the old meaning) is `host.driver.environment`. Work started on `host.environment.scope` is no longer ended
  by a relaunch.
- `LiveHost.onRelaunch: (AgentStore) -> Unit` is `host.driver.onRelaunch: () -> Unit`; read the new store from
  `host.store`.
- Other type parameters: `AgentLaunch<State, Action>` is `AgentLaunch<AgentStoreDriver<State, Action>, Action,
  AgentStore<State, Action>>`, `AgentLaunchSession<Driver, Action>`, `BridgeRouter<Action>`, and `applySeed` and
  `ScenarioRunner.run` are generic over the action only.
