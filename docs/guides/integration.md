# The five-minute integration

Every snippet below is real code from [`examples/tinyapp`](../../examples/tinyapp), a two-screen app with one mocked
client. It is also this repository's test fixture.

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
    implementation("com.github.olbartek.agentctl-android:agentctl-core:0.5.1")
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

Abridged from
[`TinyAppConfig.kt`](../../examples/tinyapp-config/src/main/kotlin/io/github/olbartek/agentctl/examples/tinyapp/TinyAppConfig.kt):

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
    debugImplementation("com.github.olbartek.agentctl-android:agentctl-bridge:0.5.1")
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
