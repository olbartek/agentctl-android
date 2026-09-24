# TinyApp

The worked example for [AgentCtl](../../README.md), and the fixture the repository's own tests run against: a
list, a detail screen with a cooldown, and one mocked client. It is the same app as agentctl-ios's TinyApp,
screen for screen and command for command, so the two ports' outputs can be compared byte for byte.

TinyApp is a plain JVM module, built on `agentctl-core`'s `Store`. Its CLI is [`../tinyctl`](../tinyctl), run
through the [`./tinyctl`](../../tinyctl) wrapper at the repository root. [`../tinyapp-android`](../tinyapp-android)
is the same app with Android views and the agent bridge, for the `app` commands and `check --ui`.

## Run it

```bash
./tinyctl run "open 2; save"               # one step per command
./tinyctl run "open 2; save; advance 3s"   # let the cooldown run out
./tinyctl run --diff "open 2"              # with a state diff per step
./tinyctl test                             # the three scenarios below
./tinyctl screens                          # every screen, command and summary key
./tinyctl state                            # the full root state
./tinyctl docs                             # regenerate agent-commands.md (--check verifies it)
./tinyctl --help
```

```
$ ./tinyctl test
PASS browse (10 steps, 75 ms)
PASS refresh-error (7 steps, 1 ms)
PASS save-cooldown (13 steps, 4 ms)
3 passed, 0 failed
```

The step counts are fixed, because scenario output is deterministic. The millisecond timings are not.

With an emulator (an AVD name, or any running device):

```bash
./tinyctl app launch --device <avd> --seed "open 2"
./tinyctl app run "save; expect saved=true pending=1; back"
./tinyctl check --ui --device <avd>
```

## What each file demonstrates

The sources are in [`src/main/kotlin/…/tinyapp`](src/main/kotlin/io/github/olbartek/agentctl/examples/tinyapp).

| File | What to look at |
|---|---|
| `Items.kt` | An ordinary reducer, untouched by AgentCtl. A failed load keeps its rows. `OpenTapped` for an id the list lacks sets an error instead of doing nothing, so a step never reports silent success. |
| `ItemsAgent.kt` | The agent surface: paths, `summaryKeys` and `summary`, `errorCode`, and `onAppear` (there is no view to send it headlessly). Its commands include a `parsing` command with an argument, and two `gate`s that mirror a disabled button. |
| `ItemDetail.kt` | The cooldown: an effect suspended on the app's `AgentClock`, which a step reports as `pending=1` and `advance 3s` releases at once. |
| `ItemDetailAgent.kt` | The smallest useful agent surface, with a path that carries the item's id. That id is how `expect screen=items/2` tells one pushed screen from another. |
| `TinyRoot.kt` | The root reducer AgentCtl drives: the list plus a stack of pushed screens. Each element's effects are scoped to its id, and cancelled when it is popped. |
| `TinyRootAgent.kt` | `AgentContainer`, which does three things: resolves the active screen, lifts its commands (stack element id included), and adds `back`. It also holds the `registry` the docs are rendered from. |
| `ItemsClient.kt` | A client whose mock goes through `MockBackend.call`. That is what makes `calls=items.fetch` appear in a step, and what lets `mock items.fetch network` fail it. `mockMethods` lists only the codes the *client* can throw, so `mock items.fetch notFound` is rightly rejected. |
| `TinyAppConfig.kt` | The whole integration: one `AppCtlConfig`, the docs prose, the store on any `AgentEnvironment`, and the headless and live hosts. |

## The scenarios

These are executable specifications. `./tinyctl test` runs them, and so does the repository's
[`ScenarioTest`](../../tests/src/test/kotlin/io/github/olbartek/agentctl/tests/ScenarioTest.kt), ten times over
for determinism. They are copied unchanged from agentctl-ios.

| File | What it pins down |
|---|---|
| [`scenarios/browse.appctl`](scenarios/browse.appctl) | The happy path, in four steps: the list loads when it appears, an item opens, `advance` runs the save cooldown out, and `back` returns. |
| [`scenarios/refresh-error.appctl`](scenarios/refresh-error.appctl) | A forced failure: `mock` makes the next fetch throw, and the rows already loaded stay. The fault is one-shot, so the gated `retry` then succeeds. `check --ui` sends this one through the bridge. |
| [`scenarios/save-cooldown.appctl`](scenarios/save-cooldown.appctl) | The cooldown in detail: a second save is refused with `error=cooldown`, the countdown keeps running, and `advance` releases it a second at a time. |

## The generated command reference

[`agent-commands.md`](agent-commands.md) is written by `./tinyctl docs` from the agent declarations. It lists every
screen, every command, every summary key, and the mockable methods. It is committed, and `./tinyctl docs --check`
(run by CI) fails when it goes stale. Never edit it by hand.
