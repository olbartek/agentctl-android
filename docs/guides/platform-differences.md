# Differences from agentctl-ios

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
- The device commands (`app screenshot`, `app record`, `app statusbar`, `app info`) take `--device` where the reference
  takes `--sim`. `app record` runs `adb shell screenrecord` in back-to-back chunks under its three-minute limit,
  joined with `ffmpeg`, with the last frame held until the stop, since `screenrecord` writes frames only when the
  screen changes. The clean status bar is SystemUI's demo mode, whose mobile icon Android 16 does not draw.
  `app record stop` on a recorder that has died still saves what it recorded (its chunks stay on the device, where
  the reference's simctl has already written the file), and says so after the shared text: `the recording of
  <file> is no longer running; saved what it recorded to <file>` (exit 3).
- Live settling's UI signal (§8.5) is Compose's idleness (no recomposition pending, no frame awaited), where the
  reference asks UIKit whether a view controller has a transition, presentation or dismissal under way. Compose's
  signal also sees endless animations; the shared rule that a UI busy for more than a second stops holding a step
  covers them.
- `choice`'s enum form takes the entries and how each is spelled (`of = Tab.entries, word = { it.code }`), where the
  reference takes a `String`-backed enum type and its raw values: a Kotlin enum has no raw value.
- The wrapper has no stale-cache recovery. The reference's plans a failed SwiftPM build afresh, because SwiftPM's
  incremental build can trip over its own cache after a pull or a branch switch; Gradle's does not need it.
- A container's `containerName` defaults to its class name without an `Agent` suffix, as a screen's `screenName`
  does, so `AppFeatureAgent` shows its commands as `AppFeature`'s, where the reference's default is the type name.
- `AgentScenarioChecks` finds the repo root from the test's working directory, where the reference starts from the
  test's source file (`#filePath`). Its checks block (`runBlocking`) where the reference's are `async`, need no
  serialized suite (the reference turns a process-wide executor on for each run), and compare the final state dumps
  as they are, since pushed screens take their ids from the state, not from a per-process counter.
- The simulated device (§2.4) is `AgentEnvironment.device`, where the reference's is the `\.agentDevice` dependency;
  its configuration is `AppCtlConfig.deviceConfig`, since `device` there already names the `adb` device. Its
  requests are `suspend` functions and its updates `Flow`s, where the reference's are `async` and `AsyncStream`s; a
  cancelled request throws `CancellationException` rather than returning. A link reaches `openUrl` as a
  `java.net.URI`, where the reference's `openURL` gets a `URL`; both parse it as strictly (CONTRACT.md §2.4).
  `device relaunch` builds the next store on a
  fresh `scope` (cancelling the old one ends the old store's effects), where the reference rebuilds the store over
  the same dependencies. A host built with an `AgentAppFactory` keeps what `prepare` built (the clients, and what they
  stored) as the reference does; one built with a plain `makeStore` runs all of it again. In the app it is the same
  process, and `AgentLaunch.apps` gives the root the new app.
- The driver seam (0.7): `AgentDriver<A, App>` takes the action and app types as parameters, where the reference's
  protocol has associated types, so the hosts and the config name both
  (`HeadlessHost<AgentStoreDriver<State, Action>, Action>`) and `ScriptRunner` only the action. The MVVM driver is
  `AgentViewModelDriver`, for Jetpack `ViewModel`s or any class, where the reference's is `AgentObservationDriver` for
  `@Observable` view models. `AgentAppFactory` (clients built once per host, a store per launch) has no counterpart
  there: the reference's store driver takes the state and reducer, and its host's `configure` builds the clients
  once. `AgentTasks` is `environment.tasks`, on each launch's scope, where the reference's `\.agentTasks` runs work
  in its owner's dependencies: Kotlin view models are given their environment, so there is no owner to name. A
  relaunch also cancels that scope, after `cancelAllWork()`. Hosts for each architecture are functions named like
  the class (`HeadlessHost(container, …)`, `HeadlessHost(mockMethods, …) { AppCoordinator(it) }`), where the
  reference has constrained initializers. Since a `ScriptRunner` names only the action, it has no `store` or `state`
  (the reference's has them when its driver is an `AgentStoreDriver`); a test reads them from the host (`host.store`).
- The real-OS `app` commands use `adb`: `app permission` takes an Android permission (`CAMERA` is
  `android.permission.CAMERA`) and its `reset` resets every permission of the app (`pm reset-permissions -p`); `app
  location` needs an emulator (exit 3 on a phone) and `app location clear` is a usage error (exit 2: an emulator
  keeps its last location); `app push` is always a usage error (exit 2), since nothing delivers a push to one app;
  `app openurl` fails when no activity takes the link, which `am start` reports only in its output.
- A `mock` method and error code, a permission and a push that a script names by canonical equivalence (`e` +
  U+0301 for `é`) reach the app in the app's own declared spelling, where the reference passes them as typed: a Swift
  `switch` on them is canonical too, a Kotlin `when` compares code units, so only the declared spelling matches the
  same way.
- A release build leaves AgentCtl's runtime out because the config module is a `debugImplementation` dependency,
  which Gradle can drop per build type; the reference, whose SwiftPM cannot, compiles its runtime, CLI and test
  support to nothing unless `DEBUG` or `AGENTCTL_RELEASE` is set. The same gate here is the release APK check.
