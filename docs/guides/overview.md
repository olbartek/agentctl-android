# Overview

What AgentCtl is for, what it needs, what it ships and its example apps.

## How much faster

[`examples/agentshop`](../../examples/agentshop) (sign-in, onboarding, a shop) has 99 scenarios that run unchanged in
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
[the iOS port](https://github.com/olbartek/agentctl-ios/wiki/Home#how-much-faster) (30 minutes for the same scenarios). They
also share one process per group instead of relaunching the app for every test.
[The full report](../../docs/benchmarks/2026-09-29-agentshop.md) explains where the time goes, and
[this video](../../docs/benchmarks/2026-09-29-agentshop.mp4) runs three scenarios side by side.

The modes check different things, so this is not a case for deleting UI tests. It is a case for which one an agent
runs hundreds of times a day.

## Requirements, and what this is not

- **JDK 17+ to run, Kotlin 2.4, kotlinx.coroutines 1.11.** The engine is plain Kotlin on the JVM. The bridge is an
  Android library: minSdk 28, compiled against SDK 36.
- **The app is built on a store, or as MVVM.** The engine talks to an `AgentDriver`, never to your app's types, and
  both drivers run the same scripts with the same output:
  - `AgentStoreDriver` drives a store. `agentctl-core` ships a small unidirectional one (`Store`, `Reducer`, `Effect`:
    run, cancellable, send, cancel, map, scoped), which plays the part TCA plays in the Swift version; TinyApp is
    built on it. Any other store implements `AgentStore`: the states, `send`, and the effects in flight.
  - `AgentViewModelDriver` drives MVVM: Jetpack `ViewModel`s under a coordinator that owns the back stack (see
    [MVVM](integration.md#mvvm-apps-without-a-store)).
- **Screens are driven through state, not through the UI.** Nothing taps and nothing reads pixels; a headless run
  has no views at all. Rendering is verified separately, by screenshot tests (see
  [the ladder](verification-ladder.md)).
- **The CLI cannot ship as a prebuilt binary.** It builds your app's store in its own process and sends your
  actions to it, so `agentctl-cli` is a *library*. Each host compiles a small `main` into its own CLI: an import,
  its `AppCtlConfig` and one call.

## Packages

### What this repository provides

Five modules, published through [JitPack](https://jitpack.io/#olbartek/agentctl-android) from this repository's tags
as `com.github.olbartek.agentctl-android:<module>:<version>`. Take only the modules each Gradle module needs. Only
`agentctl-core` ships in a release build: the rest are `debugImplementation`, CLI or test dependencies, so the app's
release APK carries none of them.

| Module | What it is for | What it depends on | Who depends on it | In a release build |
|---|---|---|---|---|
| `agentctl-core` | The vocabulary: `AgentScreen`, commands and their gates, `SummaryItem`, the script parser, `expect`, the step format and its JSON, mocking (`MockBackend`), a small `Store`/`Reducer`/`Effect`, `AgentViewModel`, `AgentCoordinator`, `AgentTasks`, `AgentEnvironment` with the simulated device | kotlinx.coroutines (`core`) | the modules holding your screens and store (`implementation`) | ships |
| `agentctl-runtime` | The engine: `AgentDriver` with `AgentStoreDriver` and `AgentViewModelDriver`, `ScriptRunner`, settling, the virtual-time dispatcher, the headless and live hosts, scenarios, `AppCtlConfig`, the bridge's router and token-checked HTTP server, `AgentLaunchSession` | `agentctl-core` | your config module, which only your debug app (`debugImplementation`), your CLI and your tests depend on | left out |
| `agentctl-cli` | The CLI as a library: `AgentCtl.main(config, args)`, with `run`, `test`, `docs`, `check`, `snapshots` and `app …`, driving Gradle and `adb` | `agentctl-runtime`, Clikt (`clikt-core`) | your CLI's module, a small JVM application | not linked by the app |
| `agentctl-bridge` | An Android library (minSdk 28): `AgentLaunch`, which reads the launch extras and starts the debug-only in-app bridge, and Compose idleness for live settling | `agentctl-runtime`, kotlinx.coroutines (`android`); compiles against Compose's runtime only, never bringing Compose in | your app, as `debugImplementation` | left out |
| `agentctl-test-support` | `AgentCoverage` and `AgentScenarioChecks`: the guards that fail a build when the agent surface drifts | `agentctl-runtime` | your tests (`testImplementation`) | not linked by the app |

### How a host takes them

```kotlin
// the app module
implementation(project(":feature-screens"))               // your screens, on agentctl-core
debugImplementation(project(":app-agentctl-config"))      // your AppCtlConfig, on agentctl-runtime
debugImplementation("com.github.olbartek.agentctl-android:agentctl-bridge:0.12.1")

// the CLI module (application plugin)
implementation("com.github.olbartek.agentctl-android:agentctl-cli:0.12.1")

// a test source set
testImplementation("com.github.olbartek.agentctl-android:agentctl-test-support:0.12.1")
```

The project names are placeholders; [TinyApp's Android app](../../examples/tinyapp-android/build.gradle.kts) takes
its screens, its config and the bridge exactly this way. [The integration guide](integration.md#1-add-the-dependency)
has the repository setup, and its "Debug builds only" part shows why the config is a module of its own, and how the
example apps check that a release APK holds no class of `agentctl-runtime`, `agentctl-cli`, `agentctl-test-support`
or `agentctl-bridge`.

## The example apps

[`examples/tinyapp`](../../examples/tinyapp) is the smallest complete integration and this repository's fixture: two
screens, one mocked client, five scenarios, its own `tinyctl` CLI and a committed
[generated command reference](../../examples/tinyapp/agent-commands.md). Most snippets in
[the integration guide](integration.md) are from it, and
[`examples/tinyapp-android`](../../examples/tinyapp-android) runs it as an Android app with the bridge.

[`examples/tinymvvm`](../../examples/tinymvvm) is TinyApp rebuilt as MVVM: Jetpack `ViewModel`s under a coordinator, no
store. It runs TinyApp's scenario files unchanged and prints the same output byte for byte (`./tinymvvmctl test`).
See [its README](../../examples/tinymvvm/README.md).

[`examples/agentshop`](../../examples/agentshop) is the showcase: a real Compose app (sign-in, onboarding, a shop with a
cart and checkout, and the simulated device: permissions, links, pushes, relaunch, offline, Face ID), the port of the
iOS one, with 123 scenarios that print the same bytes on both ports, UI tests generated from them, and the benchmark
behind [the numbers above](#how-much-faster). See [its README](../../examples/agentshop/README.md) and
[the app, screen by screen](../../examples/agentshop/docs/APP.md).

## Building this repository

```bash
./gradlew build          # every module, the Android bridge and sample app included, and the whole suite
./tinyctl test           # the example's scenarios
./tinyctl check          # the ladder: L0 build, L1 tests, L2 scenarios, docs (--ui adds the emulator)
```

You need JDK 17+ (`JAVA_HOME`) and an Android SDK (`local.properties` with `sdk.dir`, or `ANDROID_HOME`) for the
Android modules.
