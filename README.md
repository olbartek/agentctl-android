# AgentCtl for Android

AgentCtl makes an app **agent-addressable**. Each screen describes itself:

- a path, such as `items/<id>`;
- a compact `key=value` summary;
- an error code;
- the commands it offers.

A CLI drives those commands from the terminal, *headlessly on the JVM with no emulator*, and prints one line of
state per step. A coding agent (or you) can open a screen, run a command and assert on the result in milliseconds,
instead of building the app, booting an emulator and guessing whether a tap landed. The same script also drives
the real app on a device, through a debug-only in-app bridge that answers only requests carrying the token of the
launch that started it (a fresh token per launch), so what you assert headlessly is what the app does.

This is the Kotlin port of [agentctl-ios](https://github.com/olbartek/agentctl-ios). Both implement the same
contract, [CONTRACT.md](CONTRACT.md) (version 4): the same script prints the same bytes in both. This repository's
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

## Install

The modules are published through [JitPack](https://jitpack.io/#olbartek/agentctl-android); the current version is
0.11.0 ([the releases](docs/guides/releases.md)). Add `maven("https://jitpack.io")` to your repositories, then:

```kotlin
implementation("com.github.olbartek.agentctl-android:agentctl-core:0.11.0")         // your screens
debugImplementation("com.github.olbartek.agentctl-android:agentctl-bridge:0.11.0")  // the debug app
```

[The overview](docs/guides/overview.md#packages) lists all five modules and who depends on each.

## Get started

The whole integration, in five steps — each is a few lines of real code in
[the integration guide](docs/guides/integration.md):

1. **Add the dependency** — `com.github.olbartek.agentctl-android:<module>:0.11.0` from JitPack, and only the modules
   each Gradle module needs (`agentctl-core` for screens, `agentctl-runtime` for the config, `agentctl-cli` for your
   CLI, `agentctl-bridge` for the debug app, `agentctl-test-support` for tests).
2. **Describe your screens** — beside each reducer (or view model), an `AgentScreen` object: its path, a `key=value`
   summary, an error code and its commands; a container (`AgentContainer`, or a coordinator) resolves which screen is
   active.
3. **Write your CLI** — one `AppCtlConfig` describing your app, and a `main` that runs it.
4. **Copy the wrapper** — [`Templates/appctl`](Templates/appctl) rebuilds the CLI quietly before every call.
5. **Run something** — `./appctl screens`, `./appctl run "…"`, `./appctl test`.

## Documentation

The documentation is on [the wiki](https://github.com/olbartek/agentctl-android/wiki), generated from the guides
below, which are versioned with the code and read the same on GitHub:

| Guide | What it covers |
|---|---|
| [Overview](docs/guides/overview.md) | How much faster headless is, the requirements, the five modules, the example apps, building this repository. |
| [Integration](docs/guides/integration.md) | The five steps above in full: screens, commands (`action`, `parsing`, `text`, `onOff`, `choice`), gates, containers and inherited commands, mocked clients and `MockBackend.call`, the config, debug-only builds, the wrapper; MVVM apps. |
| [Commands](docs/guides/commands.md) | Every command your CLI gets (`run`, `test`, `docs`, `check`, `app …`), the exit codes, the runtime commands, and what a headless run pins to stay deterministic. |
| [The in-app bridge](docs/guides/bridge.md) | Driving the real app on a device: `AgentLaunch`, launch extras and seeds, the launch token, `advance` on real time, `app test`. |
| [The device environment](docs/guides/device-environment.md) | `device …`: permissions and system prompts, links, pushes, background, foreground and relaunch, the network, location and biometry — one row per capability, with what your app wires and the real-OS `app` wrapper. |
| [The verification ladder](docs/guides/verification-ladder.md) | `check`, from the Gradle build to the real app on an emulator, and verifying at the cheapest rung that proves a change. |
| [Coverage and scenario checks](docs/guides/coverage-and-checks.md) | The guards that fail your build when the agent surface drifts, and the scenario checks every host's tests need. |
| [Differences from agentctl-ios](docs/guides/platform-differences.md) | Where this port adapts the reference rather than copying it, one item each. |
| [Contract](docs/guides/contract.md) | The CONTRACT.md version this port implements, and its answers to the contract's open questions. |
| [Releases](docs/guides/releases.md) | Every release, newest first, with what a host changes to move to it; how the two ports share versions. |
| [Before 1.0](docs/guides/before-1.0.md) | What is still to be settled before 1.0. |
| [CONTRACT.md](CONTRACT.md) | The engine specified independently of Swift: what a port implements. |

## License

MIT licensed; see [LICENSE](LICENSE).
