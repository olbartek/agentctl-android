# The in-app bridge

Add `agentctl-bridge` with **`debugImplementation`** only, as your config module, and keep the code that names them
in `src/debug`. A release build then carries neither the bridge, nor AgentCtl's runtime, nor the `INTERNET`
permission the bridge's manifest adds. The server listens on
`127.0.0.1` only; the CLI reaches it with `adb forward`.

[`examples/tinyapp-android`](../../examples/tinyapp-android) is TinyApp as a real app: plain views that render the store
and send it the same actions the commands do. Its debug build's store, the whole integration
([`src/debug/…/AppStore.kt`](../../examples/tinyapp-android/src/debug/kotlin/io/github/olbartek/agentctl/examples/tinyapp/android/AppStore.kt)):

```kotlin
class AppStore private constructor(activity: Activity) {
    private val launch = AgentLaunch(TinyAppConfig.appCtl, activity.intent)

    init {
        MainScope().launch { launch.start() }   // applies the seed, then starts the bridge
    }

    val store: AgentStore<TinyRoot.State, TinyRoot.Action> get() = launch.app   // a coordinator, in an MVVM app
    val isReady: StateFlow<Boolean> get() = launch.isReady   // false while a seed is applied: show a splash

    companion object {
        private var instance: AppStore? = null
        fun get(activity: Activity): AppStore = instance ?: AppStore(activity).also { instance = it }
    }
}
```

Its release twin
([`src/release/…/AppStore.kt`](../../examples/tinyapp-android/src/release/kotlin/io/github/olbartek/agentctl/examples/tinyapp/android/AppStore.kt))
builds the same store on `AgentEnvironment.system(MainScope())`.

To run the plain app over its live dependencies unless an agent launched it, check
`AgentLaunch.isRequested(activity.intent)` before creating the `AgentLaunch`: it is true when the intent carries the
`agent-port` extra, which the CLI's `app` subcommands always send and a launch from the home screen or Android
Studio's Run does not. A JVM host reads its command line with `AgentLaunchOptions.isRequested(arguments)`.

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
one up to 8864. A port counts as taken if anything on the Mac holds it on either loopback, `127.0.0.1` or `::1`: a
connection there is answered or not refused within a second, or a bind there fails (§8.6). That covers another
device's `adb forward` and an iOS simulator's bridge, which shares the Mac's loopback. It also counts as taken if
anything on the device listens on it, such as another app's bridge. After `adb forward`, the launch checks that the
forward holds `127.0.0.1`, where the CLI connects, and fails otherwise. `app launch` forwards the port it chose and records the launch in
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
first, and the forward its last launch on that device left (as `bridge.json` records it) is removed, whatever port
the new launch takes, so forwards do not pile up per device; no other forward is touched. When the recorded app has quit,
`app run` says the file is stale and to relaunch. The file is written exactly as agentctl-ios writes it (CONTRACT.md
§8.6).

**Who answers.** The bridge names its app and its platform on every response (`X-Appctl-App: <application id>` and
`X-Appctl-Platform: android`, §8.4), so the CLI never drives the wrong app, not even the same app's iOS build, which
often has the same ID. `app launch` checks that its own app on Android answered, and `app test` checks every response
of every scenario the same way (a mismatch fails the scenario as not run, exit 3: `another app took the port during the run`). A different app or platform, or an
answer without either header, means the port was taken. A scanned port then gets one more try on the next free port, and a port you named
fails with exit 3. When the port came from `bridge.json`, `app run` first asks `GET /snapshot` who answers, and posts
the script only to the recorded app and platform; `app state` and `app screens` check their own answer. An answer
without either header is not the recorded app either. An error names both sides:
`answers as <app> (<platform>), not <app> (<platform>)`, with a missing header named in its place
(`an app without X-Appctl-App (<platform>)`, `<app> (no X-Appctl-Platform)`).

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
starts), `--no-build` uses the installed app on a device that is already running (a named AVD that is not is not
booted: `app launch` and `app test` then print `<avd> [AVD] is not booted; …` once and exit 3, where without
`--no-build` they boot it first), `--record <mp4>` records the device
for the whole run (`adb shell screenrecord`, in back-to-back chunks under its three-minute limit, joined with
`ffmpeg` when it is installed) and writes `<mp4>.chapters.txt` with the time each scenario started (a video that is
missing or empty is not reported as recorded: `warning: nothing was recorded; see <log>` on stderr, and the exit code
is the scenarios'), and
`--step-delay <s>` sends one line at a time so the video can be followed. A few scenarios are true headlessly but
not in a running app: a first `expect` on the launch's own calls (`app launch` has made them before the script
starts), a countdown's exact value (it also ticks in real time), or a date that is in the future only against the
headless fixed date. Such a file says so on a comment line, and `app test` prints it as skipped:

```
# app-test: skip the countdown also ticks in real time
```

The wire protocol (routes, the `X-Appctl-Exit` header, the JSON form) is
[CONTRACT.md §8](../../CONTRACT.md#8-the-in-app-bridge), so either port's CLI can drive either port's app.
