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
contract, [CONTRACT.md](CONTRACT.md) (version 2): the same script prints the same bytes in both. This repository's
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
- **The app is built on a store, or as MVVM.** The engine talks to an `AgentDriver`, never to your app's types, and
  both drivers run the same scripts with the same output:
  - `AgentStoreDriver` drives a store. `agentctl-core` ships a small unidirectional one (`Store`, `Reducer`, `Effect`:
    run, cancellable, send, cancel, map, scoped), which plays the part TCA plays in the Swift version; TinyApp is
    built on it. Any other store implements `AgentStore`: the states, `send`, and the effects in flight.
  - `AgentViewModelDriver` drives MVVM: Jetpack `ViewModel`s under a coordinator that owns the back stack (see
    [MVVM](docs/guides/integration.md#mvvm-apps-without-a-store)).
- **Screens are driven through state, not through the UI.** Nothing taps and nothing reads pixels; a headless run
  has no views at all. Rendering is verified separately, by screenshot tests (see
  [the ladder](docs/guides/verification-ladder.md)).
- **The CLI cannot ship as a prebuilt binary.** It builds your app's store in its own process and sends your
  actions to it, so `agentctl-cli` is a *library*. Each host compiles a small `main` into its own CLI: an import,
  its `AppCtlConfig` and one call.

## Get started

The whole integration, in five steps — each is a few lines of real code in
[the integration guide](docs/guides/integration.md):

1. **Add the dependency** — `com.github.olbartek.agentctl-android:<module>:0.7.0` from JitPack, and only the modules
   each Gradle module needs (`agentctl-core` for screens, `agentctl-runtime` for the config, `agentctl-cli` for your
   CLI, `agentctl-bridge` for the debug app, `agentctl-test-support` for tests).
2. **Describe your screens** — beside each reducer (or view model), an `AgentScreen` object: its path, a `key=value`
   summary, an error code and its commands; a container (`AgentContainer`, or a coordinator) resolves which screen is
   active.
3. **Write your CLI** — one `AppCtlConfig` describing your app, and a `main` that runs it.
4. **Copy the wrapper** — [`Templates/appctl`](Templates/appctl) rebuilds the CLI quietly before every call.
5. **Run something** — `./appctl screens`, `./appctl run "…"`, `./appctl test`.

## Guides

| Guide | What it covers |
|---|---|
| [Integration](docs/guides/integration.md) | The five steps above in full: screens, commands (`action`, `parsing`, `text`, `onOff`, `choice`), gates, containers and inherited commands, mocked clients and `MockBackend.call`, the config, debug-only builds, the wrapper; MVVM apps, and moving a host to 0.7. |
| [Commands](docs/guides/commands.md) | Every command your CLI gets (`run`, `test`, `docs`, `check`, `app …`), the exit codes, the runtime commands, and what a headless run pins to stay deterministic. |
| [The device environment](docs/guides/device-environment.md) | `device …`: permissions and system prompts, links, pushes, background, foreground and relaunch, the network, location and biometry — one row per capability, with what your app wires and the real-OS `app` wrapper. |
| [The verification ladder](docs/guides/verification-ladder.md) | `check`, from the Gradle build to the real app on an emulator, and verifying at the cheapest rung that proves a change. |
| [Coverage and scenario checks](docs/guides/coverage-and-checks.md) | The guards that fail your build when the agent surface drifts, and the scenario checks every host's tests need. |
| [The in-app bridge](docs/guides/bridge.md) | Driving the real app on a device: `AgentLaunch`, launch extras and seeds, `advance` on real time, `app test`. |
| [Differences from agentctl-ios](docs/guides/platform-differences.md) | Where this port adapts the reference rather than copying it, one item each. |
| [CONTRACT.md](CONTRACT.md) | The engine specified independently of Swift: what a port implements. |

## The contract

[`CONTRACT.md`](CONTRACT.md) is copied unchanged from agentctl-ios; the Swift package is its reference
implementation. It specifies:

- the script language and command resolution;
- the runtime commands (`expect`, `advance`, `mock`, and `device` when the app configures one);
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

It also counts script columns in grapheme clusters, and compares a script's text by canonical equivalence
(`AgentText`), as Swift does. Two public helpers have no Swift counterpart, because the modules that need them are
separate here: `AgentText` (Swift's `String ==` is canonical already) and `ScriptParser.read` (strict UTF-8, as
`String(contentsOf:encoding: .utf8)` reads).

## Differences from agentctl-ios

Where Android differs from iOS, this port adapts the reference rather than copying it: L3's Roborazzi or Paparazzi
tasks, `adb` for the device commands, Compose's idleness for live settling, `AgentEnvironment.device` and
`AgentAppFactory` for the simulated device, and a few Kotlin-forced API shapes.
[The list, one difference per item](docs/guides/platform-differences.md).

## The example apps

[`examples/tinyapp`](examples/tinyapp) is the smallest complete integration and this repository's fixture: two
screens, one mocked client, five scenarios, its own `tinyctl` CLI and a committed
[generated command reference](examples/tinyapp/agent-commands.md). Most snippets in
[the integration guide](docs/guides/integration.md) are from it, and
[`examples/tinyapp-android`](examples/tinyapp-android) runs it as an Android app with the bridge.

[`examples/tinymvvm`](examples/tinymvvm) is TinyApp rebuilt as MVVM: Jetpack `ViewModel`s under a coordinator, no
store. It runs TinyApp's scenario files unchanged and prints the same output byte for byte (`./tinymvvmctl test`).
See [its README](examples/tinymvvm/README.md).

[`examples/agentshop`](examples/agentshop) is the showcase: a real Compose app (sign-in, onboarding, a shop with a
cart and checkout, and the simulated device: permissions, links, pushes, relaunch, offline, Face ID), the port of the
iOS one, with 123 scenarios that print the same bytes on both ports, UI tests generated from them, and the benchmark behind [the numbers above](#how-much-faster). See
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

Version 0.7.0, released together with agentctl-ios 0.7.0: the engine drives any architecture through `AgentDriver`,
a store (`AgentStoreDriver`) or view models under a coordinator (`AgentViewModelDriver`, shown by TinyMVVM), and every
new public name starts with `Agent`. Scripts, scenarios and step output are unchanged (CONTRACT.md version 2). Like
0.6.0, it was checked on the two NZOZ apps the two ports were extracted for, run side by side, before it was tagged.
The example apps in this repository are the integrations CI exercises, and the API may still change between minor
versions before 1.0. MIT licensed.

This port and [agentctl-ios](https://github.com/olbartek/agentctl-ios) move in lockstep: the same features, the same
example apps with the same scenario files, and the same MAJOR.MINOR version, so 0.7.x here and 0.7.x there implement the
same [`CONTRACT.md`](CONTRACT.md). A patch release is one repository's own fix, so the two patch numbers can differ.
(Before 0.4.1 this port had its own numbers: v0.1.0–v0.2.0.)
