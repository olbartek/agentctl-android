# The commands your CLI gets

| Command | What it does |
|---|---|
| `run "<script>"` | Run a script against a fresh headless app, one step per command. `--diff` adds a state diff per step. `--json` prints a JSON array of steps instead of text. `--session <file>` replays a saved file first, then appends the commands that succeed, so state survives across calls. |
| `state` | The full root state, after replaying an optional session file. |
| `screens` | Every screen path with its commands, arguments, help and summary keys, as above. |
| `docs` | Write the generated command reference (`docsPath`) from the registry. `--check` exits 1 when it is stale. |
| `test [files…]` | Run `*.appctl` scenario files (by default all of `scenariosPath`), one PASS/FAIL line each. Finding no scenario files to run is a failure, not "0 passed". |
| `snapshots` | The screenshot tests (Roborazzi's or Paparazzi's, found in the config's `gradle.snapshotModules`); `--record` re-records the references. |
| `check` | The verification ladder below; `--ui` adds its last two rungs. |
| `app launch` / `app run` / `app state` / `app screens` | The same commands, against the real app on a device or emulator, through the in-app bridge. `app launch` picks a free port and records it in `<outputPath>/bridge.json`, which the others read. |
| `app screenshot <png>` / `app record start <mp4>` / `app record stop` / `app statusbar clean\|reset` / `app info` | The device itself, for demos: a screenshot; a recording that runs across other commands until `stop` (only the recorder `start` began is stopped; a still ending is held until the stop); a 9:41 status bar with full Wi-Fi and battery (SystemUI's demo mode), or the device's own; the installed app's ID, version and build as one JSON line. Each acts on `--device`, else the last launch's device (`bridge.json`), else the config's, and never boots an emulator. |
| `app permission grant\|revoke\|reset <service>` / `app openurl <url>` / `app location <lat>,<lon>` / `app appearance light\|dark` | The real OS, beside the script's simulated `device`: `pm grant\|revoke` (a revoke kills the app; `reset` resets all of its permissions), a link through `am start -a android.intent.action.VIEW`, the emulator's location (`adb emu geo fix`; a phone has none to set), dark mode (`cmd uimode night`). `app push` exists for parity and always fails: nothing delivers a push to one app on Android. Same device resolution as above. |
| `app test [files…]` | The scenario files, in the real app on a device: one fresh launch each, one PASS/FAIL/SKIP line each. `--record <mp4>` records the run, `--step-delay <s>` (0 to 3600) sends a line at a time so the recording can be followed. |
| `help [command…]` | That command's help, as `<command…> --help` prints it (stdout, exit 0). `help` alone is the CLI's own; a name that is not a command ends the path. |

Exit codes are part of the contract:

| Code | Meaning |
|---|---|
| `0` | Everything ran and every `expect` passed. |
| `1` | A command or an `expect` failed, or a step did not settle. This includes `(launch)`. |
| `2` | A usage or parse error, the CLI's own command line included. |
| `3` | An internal or environment error: a scenario file that does not exist, no scenario files to run, no repo root, a build that failed. |

Four runtime commands work on every screen, and a fifth when the app configures a device:

- `expect k=v [k=v …]`;
- `advance <duration>`: the virtual clock headlessly; in the running app, the app's real-time clock jumped forward
  (see [the bridge](bridge.md));
- `mock <client.method> <error>`;
- `wait [<duration>] [k=v …]`: waits (10 s unless told otherwise) until the pairs match or, with none, until the
  screen's line changes; headlessly by moving the clock to each timer due, in the running app in real time
  (CONTRACT.md §2.5). A runtime command takes precedence over a screen's, so an app does not name a command `wait`;
- `device <subcommand>`: the simulated device (permissions and their prompts, links, pushes, background, relaunch,
  network, location, biometry), when the app configures one; see [the device environment](device-environment.md).

Headless runs are deterministic by construction, so the same script always prints the same bytes. That makes step
output usable as a committed fixture. `HeadlessHost` hands your store exactly this environment:

| Field | Headless value |
|---|---|
| `scope` | a `VirtualTimeDispatcher`: every effect runs on one thread, in a fixed order, and `delay` waits on virtual time that only `advance` moves |
| `clock` | `now()` is 2026-01-01T09:00:00Z plus whatever `advance` has added; `sleep` is virtual and counted for `pending=` |
| `uuids` | `00000000-0000-0000-0000-000000000000`, then `…0001`, … |
| `random` | SplitMix64, seed 0 |
| `zone`, `locale` | UTC, `en_US_POSIX` |
| `mocks` | a fresh call log and fault registry, zero latency |
| `device` | a driven `AgentDevice`: online, no location, biometry enrolled, every permission not determined |

**Nothing else is pinned.** An app that reads `System.currentTimeMillis()`, `Locale.getDefault()` or switches to
`Dispatchers.IO` around the environment is not deterministic headlessly. If you need more, build it into your store
from the environment inside the `makeStore` function you give `HeadlessHost`.
