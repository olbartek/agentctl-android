# The verification ladder

`check` runs the cheapest checks first and stops at the first failure, printing one line per rung:

```
$ ./tinyctl check --ui
L0 build      ok    1 task                       0.5s
L1 test       ok    1 task, 100 tests            11.9s
L2 scenarios  ok    5/5 scenarios                0.0s
docs          ok    up to date                   0.0s
L3 snapshots  ok    no snapshot tasks configured 0.0s
L4 app        ok    refresh-error via the agent bridge 2.9s
  agentctl_pixel7_api36 (Android 16), screenshot: …/.appctl/screenshots/check-ui-1790264952.png
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

`--device` (or the config's `device`) names an `adb` serial, an AVD or a phone's model. An AVD that is not running is
booted. With neither, the only connected device is used. The boot passes `-no-metrics -crash-report-mode never`: after
an emulator crash, a plain boot stops at the crash-report consent prompt and never opens its adb ports. A name is never
resolved by picking one of several: when two running devices share it (two emulators of one AVD started with
`-read-only`, two phones of one model), the command lists their serials, exits 2 and asks for `--device <serial>`.

The rule that makes this pay off: **verify at the cheapest rung that proves the change.**

- Logic and flows are L1/L2, and take milliseconds.
- Only a view change needs L3.
- Only the app shell, the bridge or navigation needs L4.

Everything the CLI writes goes under the config's `outputPath`, `.appctl/` by default (`logs/`, `screenshots/`).
Keep it out of version control.
