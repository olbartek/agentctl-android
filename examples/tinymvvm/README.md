# TinyMVVM

[TinyApp](../tinyapp) rebuilt as MVVM: Jetpack `ViewModel`s under a coordinator that owns the back stack, no store.
It runs TinyApp's own scenario files, unchanged, and prints what TinyApp prints byte for byte. That is the proof that
the engine — the script language, `expect`, `advance`, `mock`, `device`, the step output — does not depend on how an
app is built.

```sh
./tinymvvmctl run "open 2; save"   # screen=items/2 title="Second item" saved=true cooldown=3 pending=1
./tinymvvmctl test                 # examples/tinyapp/scenarios/*.appctl, on the MVVM app
./tinymvvmctl docs --check         # agent-commands.md is current
```

## What is where

| File | What it shows |
|---|---|
| [`ItemsViewModel.kt`](src/main/kotlin/io/github/olbartek/agentctl/examples/tinymvvm/ItemsViewModel.kt) | A screen as an `AgentViewModel`: a `ViewModel` with a `StateFlow`, `agentState`, summary, error, gated commands as `AgentScreenIntent`s, `onAppear`, work through `environment.tasks` |
| [`ItemDetailViewModel.kt`](src/main/kotlin/io/github/olbartek/agentctl/examples/tinymvvm/ItemDetailViewModel.kt) | A cooldown on the injected clock, and work cancelled on pop |
| [`AppCoordinator.kt`](src/main/kotlin/io/github/olbartek/agentctl/examples/tinymvvm/AppCoordinator.kt) | The `AgentCoordinator`: the back stack, the active screen, `back`, the registry, `cancelAllWork` |
| [`TinyMVVMConfig.kt`](src/main/kotlin/io/github/olbartek/agentctl/examples/tinymvvm/TinyMVVMConfig.kt) | `AppCtlConfig<AgentViewModelDriver<AppCoordinator>, AgentIntent<AppCoordinator>>`, its headless and live hosts, device links and pushes |

TinyMVVM has no Android app: it runs headlessly only. An app would render `coordinator.backStack` (Navigation 3's
`NavDisplay`, or a `NavHost`) and collect each view model's `state`. The rules a view model follows to stay
deterministic are in [the integration guide](../../docs/guides/integration.md#mvvm-apps-without-a-store).
