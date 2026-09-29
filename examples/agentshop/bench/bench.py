#!/usr/bin/env python3
"""Times AgentShop's scenarios in three modes and writes a report.

The port of agentctl-ios's Examples/AgentShop/bench/bench.py: the same measurements and the same report, on an
Android emulator instead of an iOS simulator.

    python3 bench/bench.py                       # everything, 3 headless runs per scenario
    python3 bench/bench.py --quick               # 1 run each, for a smoke test of the script itself
    python3 bench/bench.py --groups auth --modes headless,bridge
    python3 bench/bench.py --dry-run             # print what would run
    python3 bench/bench.py --report .bench/<timestamp>.json   # only rewrite the report from saved numbers

The three modes run the *same* scenario files (`scenarios/<group>-*.appctl`):

- headless: `shopctl test <file>` on the Mac: no emulator, no views, a virtual clock.
- bridge:   the real app on an emulator, driven through its debug-only agent bridge: `shopctl app launch --no-build`
            (a fresh app, no session, no mock latency, until the bridge answers), then `shopctl app run "<the file>"`.
- uitest:   the Compose UI tests `bench/gen_uitests.py` generates from the same files, run by `adb shell am instrument`,
            one class per group, one test at a time.

Only scenarios that can run in all three modes are compared (see `bench/uitests.json`); the others, which move
time with `advance` or restart with `reset`, are timed headlessly and listed separately.

A fourth measurement is the loop an agent actually runs: change one line of a reducer, then verify it — headlessly
(Gradle's `installDist` of the CLI + one scenario) versus through the UI (`assembleDebug assembleDebugAndroidTest`,
installing both APKs, and that scenario's UI test).

Writes docs/benchmarks/<date>-agentshop.md in the repository, and the raw numbers to .bench/<timestamp>.json.
"""

from __future__ import annotations

import argparse
import datetime as dt
import json
import os
import platform
import re
import shlex
import statistics
import subprocess
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
REPO = ROOT.parent.parent
OUT = ROOT / ".bench"
CLI = ROOT / "shopctl/build/install/shopctl/bin/shopctl"
MANIFEST = ROOT / "bench/uitests.json"
REPORT_DIR = REPO / "docs/benchmarks"
APKS = ROOT / "app/build/outputs/apk"
APP_APK = APKS / "debug/app-debug.apk"
TEST_APK = APKS / "androidTest/debug/app-debug-androidTest.apk"
APP_ID = "io.github.olbartek.agentctl.examples.agentshop"
RUNNER = f"{APP_ID}.test/androidx.test.runner.AndroidJUnitRunner"
TEST_PACKAGE = "io.github.olbartek.agentctl.examples.agentshop.app.generated"
GROUPS = {"auth": "AuthUiTests", "onboarding": "OnboardingUiTests", "shop": "ShopUiTests"}
GROUP_TITLES = {"auth": "Authentication", "onboarding": "Onboarding", "shop": "Shop"}
SHOPCTL = ":examples:agentshop:shopctl:installDist"
APP_TASKS = [":examples:agentshop:app:assembleDebug", ":examples:agentshop:app:assembleDebugAndroidTest"]
#: The reducer the change loop edits, and a line in it that the edit appends a comment to.
CHANGE_FILE = ROOT / "shop/src/main/kotlin/io/github/olbartek/agentctl/examples/agentshop/shop/ShopFeed.kt"
CHANGE_ANCHOR = "                is Action.FilterTapped -> next(state.copy(filter = action.filter))\n"
CHANGE_SCENARIO = "shop-filter-category"
#: The log tag ShopUiTestCase writes each test's duration under: `<class>#<test> <ms> ms <passed|failed>`.
TIMING_TAG = "ShopUiTiming"

DRY_RUN = False


def log(message: str) -> None:
    print(message, flush=True)


def environment() -> dict:
    """The environment every command runs in: a JDK (JAVA_HOME) and the Android SDK (ANDROID_HOME)."""
    env = dict(os.environ)
    if not (Path(env.get("JAVA_HOME", "/nonexistent")) / "bin/java").exists():
        prefix = subprocess.run(["brew", "--prefix", "openjdk@21"], capture_output=True, text=True).stdout.strip()
        if prefix:
            env["JAVA_HOME"] = f"{prefix}/libexec/openjdk.jdk/Contents/Home"
    if not env.get("ANDROID_HOME"):
        properties = REPO / "local.properties"
        if properties.exists():
            for line in properties.read_text().splitlines():
                if line.startswith("sdk.dir="):
                    env["ANDROID_HOME"] = line.removeprefix("sdk.dir=").strip()
    env["APPCTL_ROOT"] = str(ROOT)
    return env


ENV = environment()
ADB = str(Path(ENV.get("ANDROID_HOME", "")) / "platform-tools/adb") if ENV.get("ANDROID_HOME") else "adb"
SERIAL = ""  # set by main (or by the scripts that import this one) from --device


def run(command: list[str], *, check: bool = True, timeout: float | None = None) -> tuple[float, str, int]:
    """Runs a command in the example's root; returns (seconds, stdout+stderr, exit code)."""
    if DRY_RUN:
        log("  $ " + shlex.join(command))
        return 0.0, "", 0
    start = time.perf_counter()
    proc = subprocess.run(command, cwd=ROOT, capture_output=True, text=True, env=ENV, timeout=timeout)
    seconds = time.perf_counter() - start
    output = proc.stdout + proc.stderr
    if check and proc.returncode != 0:
        sys.exit(f"bench: command failed ({proc.returncode}): {shlex.join(command)}\n{output[-4000:]}")
    return seconds, output, proc.returncode


def cli(*arguments: str, check: bool = True) -> tuple[float, str, int]:
    """The installed `shopctl` (what `./appctl` execs once Gradle is done), with this directory as its root."""
    return run([str(CLI), *arguments], check=check)


def gradle(*tasks: str) -> tuple[float, str, int]:
    return run([str(REPO / "gradlew"), "-p", str(REPO), "-q", "--console=plain", *tasks])


def adb(*arguments: str, check: bool = True, timeout: float | None = None) -> tuple[float, str, int]:
    return run([ADB, "-s", SERIAL, *arguments], check=check, timeout=timeout)


# MARK: Setup


def resolve_device(name: str | None) -> str:
    """An adb serial or an AVD name among the connected devices; with no name, the only one connected."""
    output = subprocess.run([ADB, "devices"], capture_output=True, text=True, env=ENV).stdout
    serials = [line.split()[0] for line in output.splitlines()[1:] if line.strip().endswith("device")]
    if name is None:
        if len(serials) != 1:
            sys.exit(f"bench: {len(serials)} devices connected ({', '.join(serials)}); pass --device")
        return serials[0]
    for serial in serials:
        if serial == name:
            return serial
        avd = subprocess.run([ADB, "-s", serial, "emu", "avd", "name"], capture_output=True, text=True, env=ENV).stdout
        if avd.splitlines() and avd.splitlines()[0].strip() == name:
            return serial
    sys.exit(f"bench: no connected device or running AVD named {name} (boot it first)")


def getprop(prop: str) -> str:
    return subprocess.run([ADB, "-s", SERIAL, "shell", "getprop", prop], capture_output=True, text=True, env=ENV).stdout.strip()


def device_info() -> str:
    avd = subprocess.run([ADB, "-s", SERIAL, "emu", "avd", "name"], capture_output=True, text=True, env=ENV).stdout
    avd = avd.splitlines()[0].strip() if avd.strip() else getprop("ro.product.model")
    size = subprocess.run([ADB, "-s", SERIAL, "shell", "wm", "size"], capture_output=True, text=True, env=ENV).stdout
    size = size.strip().split(":")[-1].strip()
    return (f"{avd} ({getprop('ro.product.model')}, Android {getprop('ro.build.version.release')}, API"
            f" {getprop('ro.build.version.sdk')}, {getprop('ro.product.cpu.abi')}, {size}, {SERIAL})")


def build_app() -> float:
    """The debug app and its test APK, as the UI tests need them. Returns the build's seconds."""
    return gradle(*APP_TASKS)[0]


def install_apks() -> float:
    start = time.perf_counter()
    adb("install", "-r", "-t", str(APP_APK))
    adb("install", "-r", "-t", str(TEST_APK))
    return time.perf_counter() - start


# MARK: Measurements


def median(values: list[float]) -> float:
    return statistics.median(values) if values else 0.0


STEPS = re.compile(r"\((\d+) steps, (\d+) ms\)")


def measure_headless(scenarios: list[str], runs: int) -> dict:
    """Per scenario: wall time of `shopctl test <file>` (JVM start included) and the in-process time it prints."""
    results = {}
    for name in scenarios:
        walls, inside = [], []
        steps = 0
        for _ in range(runs):
            seconds, output, code = cli("test", f"scenarios/{name}.appctl", check=False)
            if code != 0:
                results[name] = {"passed": False, "output": output[-2000:]}
                break
            walls.append(seconds)
            match = STEPS.search(output)
            if match:
                steps = int(match.group(1))
                inside.append(int(match.group(2)) / 1000)
        else:
            results[name] = {"passed": True, "wall_s": median(walls), "in_process_s": median(inside), "steps": steps,
                             "walls": walls, "in_process": inside}
        log(f"  headless {name}: {results[name].get('wall_s', 0) * 1000:.0f} ms"
            + ("" if results[name]["passed"] else "  FAIL"))
    return results


def measure_headless_group(files: list[str], runs: int) -> dict:
    """One `shopctl test` process running a set of scenarios: what an agent runs to check everything at once."""
    walls, passed = [], True
    for _ in range(runs):
        seconds, output, code = cli("test", *[f"scenarios/{f}.appctl" for f in files], check=False)
        walls.append(seconds)
        passed = passed and code == 0
    return {"wall_s": median(walls), "walls": walls, "passed": passed}


def measure_bridge(scenarios: list[str], port: int) -> dict:
    """Per scenario: a fresh launch of the installed app (until its bridge answers), then the scenario through it."""
    results = {}
    for name in scenarios:
        script = (ROOT / f"scenarios/{name}.appctl").read_text()
        launch_s, launch_out, launch_code = cli(
            "app", "launch", "--no-build", "--clear-session", "--latency", "0", "--device", SERIAL, "--port", str(port),
            check=False,
        )
        if launch_code != 0:
            results[name] = {"passed": False, "launch_s": launch_s, "run_s": 0.0, "output": launch_out[-2000:]}
            log(f"  bridge {name}: launch FAILED")
            continue
        run_s, run_out, run_code = cli("app", "run", "--port", str(port), script, check=False)
        results[name] = {"passed": run_code == 0, "launch_s": launch_s, "run_s": run_s}
        if run_code != 0:
            results[name]["output"] = run_out[-2000:]
        log(f"  bridge {name}: launch {launch_s:.1f} s + run {run_s:.2f} s" + ("" if run_code == 0 else "  FAIL"))
    return results


TIMING = re.compile(r"^" + re.escape(TEST_PACKAGE) + r"\.(\w+)#(\w+) (\d+) ms (passed|failed)\s*$", re.M)


def parse_instrument(output: str) -> dict:
    """`am instrument -r`'s status blocks: the outcome of every test (code 0 passed, -2 failed, -1 error)."""
    outcomes, block = {}, {}
    for line in output.splitlines():
        if line.startswith("INSTRUMENTATION_STATUS: "):
            key, _, value = line.removeprefix("INSTRUMENTATION_STATUS: ").partition("=")
            block[key] = value
        elif line.startswith("INSTRUMENTATION_STATUS_CODE: "):
            code = int(line.split(":")[1])
            if code != 1 and "class" in block and "test" in block:
                outcomes[block["class"].rsplit(".", 1)[-1] + "#" + block["test"]] = code == 0
            block = {}
    return outcomes


def measure_uitests(group: str, only: list[str] | None = None) -> tuple[float, dict, str]:
    """One group's UI tests (or a few of them) in one `am instrument`, one at a time.

    Returns (wall seconds, {"<Class>#<test>": {"passed", "seconds"}}, the instrumentation's output). A test's seconds
    are what ShopUiTestCase logged under ShopUiTiming, read from logcat while the tests run.
    """
    target = ",".join(only) if only else f"{TEST_PACKAGE}.{GROUPS[group]}"
    if DRY_RUN:
        log(f"  $ adb shell am instrument -w -r -e class {target} {RUNNER}")
        return 0.0, {}, ""
    subprocess.run([ADB, "-s", SERIAL, "logcat", "-c"], env=ENV)
    logcat = subprocess.Popen(
        [ADB, "-s", SERIAL, "logcat", "-v", "raw", "-s", f"{TIMING_TAG}:I"],
        stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True, env=ENV,
    )
    seconds, output, _ = adb("shell", "am", "instrument", "-w", "-r", "-e", "class", target, RUNNER, check=False, timeout=7200)
    time.sleep(1)  # the last test's line reaches logcat's reader
    logcat.terminate()
    timings = logcat.communicate()[0]
    outcomes = parse_instrument(output)
    tests = {}
    for class_name, test, ms, status in TIMING.findall(timings):
        key = f"{class_name}#{test}"
        tests[key] = {"passed": status == "passed" and outcomes.get(key, False), "seconds": int(ms) / 1000}
    for key, passed in outcomes.items():  # a test that crashed the process logs no timing
        tests.setdefault(key, {"passed": False, "seconds": 0.0})
    for key, result in tests.items():
        if not result["passed"]:
            result["output"] = failure_of(output, key)
    return seconds, tests, output


def failure_of(output: str, key: str) -> str:
    """The stack trace `am instrument -r` printed for one failed test, shortened."""
    test = key.split("#")[1]
    match = re.search(r"INSTRUMENTATION_STATUS: stack=(.*?)INSTRUMENTATION_STATUS: (?:stream|test)=" + test, output, re.S)
    return match.group(1)[:1500] if match else ""


# MARK: The change loop


class Change:
    """Changes one line of a reducer (differently every time), and restores the file.

    A comment would not do: Kotlin recompiles the file, but its bytecode is the same, so Gradle skips everything
    downstream (the jar, the dex, the APK). The edit appends a check of a new constant, which changes the bytecode
    and nothing else.
    """

    def __init__(self) -> None:
        self.original = CHANGE_FILE.read_text()
        if CHANGE_ANCHOR not in self.original:
            sys.exit(f"bench: {CHANGE_FILE.name} no longer contains the change anchor; update CHANGE_ANCHOR")
        self.count = 0

    def apply(self) -> None:
        self.count += 1
        changed = CHANGE_ANCHOR.rstrip("\n") + f'.also {{ require("bench {self.count}".isNotEmpty()) }}\n'
        CHANGE_FILE.write_text(self.original.replace(CHANGE_ANCHOR, changed, 1))

    def restore(self) -> None:
        CHANGE_FILE.write_text(self.original)


def measure_change_loop(runs: int, uitest: str) -> dict:
    change = Change()
    if DRY_RUN:
        log(f"  (edit {CHANGE_FILE.name}, then {SHOPCTL} + {CHANGE_SCENARIO}; {' '.join(APP_TASKS)} + install + {uitest})")
        return {}
    headless, ui, parts = [], [], []
    try:
        for _ in range(runs):
            change.apply()
            build_s = gradle(SHOPCTL)[0]
            test_s, output, code = cli("test", f"scenarios/{CHANGE_SCENARIO}.appctl", check=False)
            if code != 0:
                sys.exit(f"bench: {CHANGE_SCENARIO} failed headlessly after the change\n{output[-2000:]}")
            headless.append(build_s + test_s)
            log(f"  change → headless: installDist {build_s:.1f} s + scenario {test_s * 1000:.0f} ms")

            change.apply()
            assemble_s = build_app()
            install_s = install_apks()
            test_ui_s, tests, output = measure_uitests("shop", only=[uitest])
            if not all(t["passed"] for t in tests.values()) or not tests:
                sys.exit(f"bench: {uitest} failed after the change\n{output[-2000:]}")
            ui.append(assemble_s + install_s + test_ui_s)
            parts.append({"headless_build_s": build_s, "headless_test_s": test_s, "assemble_s": assemble_s,
                          "install_s": install_s, "uitest_s": test_ui_s})
            log(f"  change → UI test: assemble {assemble_s:.1f} s + install {install_s:.1f} s + test {test_ui_s:.1f} s")
    finally:
        change.restore()
    return {"headless_s": median(headless), "uitest_s": median(ui), "runs": runs, "headless": headless, "ui": ui,
            "parts": parts,
            "median_parts": {k: median([p[k] for p in parts]) for k in (parts[0] if parts else {})}}


# MARK: Report


def machine() -> dict:
    def out(command: list[str]) -> str:
        return subprocess.run(command, capture_output=True, text=True, env=ENV).stdout.strip()

    catalog = (REPO / "gradle/libs.versions.toml").read_text()
    version = lambda key: (re.search(rf'^{key} = "([^"]+)"', catalog, re.M) or [None, "?"])[1]
    wrapper = (REPO / "gradle/wrapper/gradle-wrapper.properties").read_text()
    java = subprocess.run([f"{ENV.get('JAVA_HOME', '')}/bin/java", "-version"], capture_output=True, text=True).stderr
    return {
        "cpu": out(["sysctl", "-n", "machdep.cpu.brand_string"]),
        "cores": out(["sysctl", "-n", "hw.ncpu"]),
        "memory_gb": round(int(out(["sysctl", "-n", "hw.memsize"]) or 0) / 2**30),
        "macos": platform.mac_ver()[0],
        "gradle": (re.search(r"gradle-([\d.]+)-", wrapper) or [None, "?"])[1],
        "agp": version("agp"),
        "kotlin": version("kotlin"),
        "compose_bom": version("compose-bom"),
        "jdk": java.splitlines()[0] if java else "",
        "emulator": out([str(Path(ENV.get("ANDROID_HOME", "")) / "emulator/emulator"), "-version"]).splitlines()[0]
        if ENV.get("ANDROID_HOME") else "",
    }


def fmt_s(seconds: float) -> str:
    if 0 < seconds < 0.0005:
        return "<1 ms"
    if seconds < 1:
        return f"{seconds * 1000:.0f} ms"
    if seconds < 120:
        return f"{seconds:.1f} s"
    whole = round(seconds)
    return f"{whole // 60} min {whole % 60} s"


def ratio(slow: float, fast: float) -> str:
    return f"{slow / fast:,.0f}×" if fast > 0 and slow > 0 and slow / fast >= 1.5 else (
        f"{slow / fast:,.1f}×" if fast > 0 and slow > 0 else "—")


# Failures that are differences between the modes rather than bugs, explained in the report.
KNOWN_FAILURES = {
    "bridge auth-launch": "its only line checks `call=session.current`, the session lookup at launch. Headlessly the"
    " first step reports the launch's calls; through the bridge, `app run` starts after the launch has settled and"
    " sees none. The UI test does not check calls, and passes.",
}


def fit(points: list[tuple[int, float]]) -> tuple[float, float]:
    """Least squares `seconds = fixed + per_step * steps`. Returns (fixed, per_step), neither below zero."""
    if len(points) < 2:
        return 0.0, 0.0
    n = len(points)
    mx = sum(x for x, _ in points) / n
    my = sum(y for _, y in points) / n
    sxx = sum((x - mx) ** 2 for x, _ in points)
    slope = sum((x - mx) * (y - my) for x, y in points) / sxx if sxx else 0.0
    return max(my - slope * mx, 0.0), max(slope, 0.0)


def test_key(manifest: dict, name: str) -> str:
    """`AuthUiTests#test_login_happy_path` for a scenario, the key its UI test's result is stored under."""
    return manifest[name].get("uitest", "").rsplit(".", 1)[-1]


def fits(data: dict) -> dict:
    """Each mode's fixed cost per scenario and cost per step, over every scenario that passed in this run."""
    manifest = data["manifest"]
    head, head_wall, bridge_launch, bridge_run, ui = [], [], [], [], []
    for g in data["groups"].values():
        tests = g.get("uitest", {}).get("tests", {})
        for name in g["scenarios"]:
            steps = manifest[name]["steps"]
            h = g.get("headless", {}).get(name)
            if h and h.get("passed"):
                head.append((steps, h.get("in_process_s", 0.0)))
                head_wall.append(h["wall_s"] - h.get("in_process_s", 0.0))
            b = g.get("bridge", {}).get(name)
            if b and b.get("passed"):
                bridge_launch.append(b["launch_s"])
                bridge_run.append((steps, b["run_s"]))
            t = tests.get(test_key(manifest, name))
            if t and t.get("passed"):
                ui.append((steps, t["seconds"]))
    result = {}
    if head:
        result["headless"] = fit(head)
        result["jvm_start_s"] = median(head_wall)
    if bridge_run:
        fixed, step = fit(bridge_run)
        result["bridge_launch_s"] = sum(bridge_launch) / len(bridge_launch)
        result["bridge"] = (fixed + result["bridge_launch_s"], step)
        result["bridge_run_fixed_s"] = fixed
    if ui:
        result["uitest"] = fit(ui)
    return result


def why_section(data: dict) -> list[str]:
    """Where each mode spends its time, from this run's numbers: a fixed cost per scenario and a cost per step."""
    f = fits(data)
    if not all(k in f for k in ("headless", "bridge", "uitest")):
        return []
    (h_fixed, h_step), (b_fixed, b_step), (u_fixed, u_step) = f["headless"], f["bridge"], f["uitest"]
    launch_test = next(
        (t["seconds"] for g in data["groups"].values() for k, t in g.get("uitest", {}).get("tests", {}).items()
         if k.endswith("#test_launch") and t.get("passed")),
        None,
    )
    ios = IOS_REPORT
    lines = [
        "## Why headless is the fastest, and where the other two spend their time",
        "",
        "Each mode's time per scenario, fitted as *a fixed cost per scenario + a cost per step* over every scenario"
        " in this run (a step is one line of a scenario: a command or an `expect`):",
        "",
        "| Mode | Fixed cost per scenario | Cost per step | A 12-step scenario |",
        "|---|---|---|---|",
        f"| Headless (in-process) | {fmt_s(h_fixed)} | {fmt_s(h_step)} | **{fmt_s(h_fixed + 12 * h_step)}** |",
        f"| Emulator bridge | {fmt_s(b_fixed)} | {fmt_s(b_step)} | **{fmt_s(b_fixed + 12 * b_step)}** |",
        f"| Compose UI test | {fmt_s(u_fixed)} | {fmt_s(u_step)} | **{fmt_s(u_fixed + 12 * u_step)}** |",
        "",
        "The headless row is the time the CLI reports for the scenario inside its process; starting that process (a JVM)"
        f" adds about {fmt_s(f['jvm_start_s'])} (the median difference between a one-scenario `shopctl test`'s wall time"
        " and the time it reports), once per process, however many scenarios it runs.",
        "",
        "### Headless: nothing to wait for",
        "",
        "- **No emulator, no app, no views.** The scenario runs in a JVM on the Mac that holds the app's features (their"
        " reducers and state) with the mocked clients. Nothing is installed, launched or rendered.",
        "- **A command goes straight to the screen's store.** There is no touch to inject and no node to find:"
        " `add-to-cart` is the action the button would send.",
        "- **Time is virtual.** The store runs on a virtual-time dispatcher, so a mock's latency, a debounce or"
        " `advance 30s` take no real time. A step is settled the moment its effects have finished, not after a wait.",
        f"- What is left is the Kotlin work itself: {fmt_s(h_step)} a step. Starting the JVM is by far the biggest cost"
        f" ({fmt_s(f['jvm_start_s'])}), and one `shopctl test` pays it once for every scenario. The first scenarios in a"
        " process also run slower than the rest, before the JIT has warmed up.",
        "",
        "### Emulator bridge: a real app, and real time",
        "",
        f"- **A launch per scenario** ({fmt_s(f['bridge_launch_s'])} on average, the `shopctl app launch --no-build`"
        " process included): `adb forward` for the bridge's port, `am force-stop`, then `am start -W` with the launch"
        " extras (`clear-session`, `mock-latency 0`); the app starts a new process, builds its store, and the CLI polls"
        " the bridge until it answers. (Each scenario starts fresh, as a UI test does on iOS, so the comparison is fair;"
        " one launch could run many.)",
        f"- **A settle window per step** ({fmt_s(b_step)} a step here): the command is sent as an HTTP request through"
        " `adb forward` to the bridge in the app, and applied on the main thread (`Dispatchers.Main.immediate`), like"
        " the headless one, but the app's effects run on real time. So the bridge can only call a step settled once no"
        " mocked call is in flight and the state has stayed unchanged for **250 ms**. Every command pays that quiet"
        " window; an `expect` sends nothing and costs almost nothing, which is why the average step comes out below it."
        " The window is the price of knowing a step is finished without a virtual clock.",
        f"- **The rest of a run** ({fmt_s(f['bridge_run_fixed_s'])} fixed per `app run`) is the CLI's JVM starting and its"
        " first request.",
        "- **Real rendering.** Compose lays out and draws every screen, with its animations. The bridge does not wait for"
        " them, but they share the main thread with the app.",
        "",
        "### Compose UI tests: in the app's process, synchronized with it",
        "",
    ]
    if launch_test is not None:
        lines.append(
            f"- **A launch per test** (`test_launch`, which only launches and checks one screen, takes {fmt_s(launch_test)}):"
            " `ActivityScenario` starts `MainActivity` with the test's extras and waits for it to be resumed and idle."
            " Unlike XCUITest, the app's process is **not** restarted: every test of a group runs in one instrumentation"
            " process, and the `ui-testing` extra gives each launch a fresh store. That saves a process start per test."
        )
    else:
        lines.append("- **A launch per test**: `ActivityScenario` starts the activity (in the same process) and waits for it.")
    lines += [
        f"- **Every step goes through the UI** ({fmt_s(u_step)} a step here), but cheaply. The test runs *inside* the app's"
        " process (instrumentation), so it reads the Compose semantics tree directly, with no second process and no"
        " accessibility round trips. Before every lookup and after every action, the Compose test framework waits"
        " until the app is idle: no pending recomposition or layout, the main looper empty, and animations finished"
        ". A click is a synthesized touch event dispatched on the main thread.",
        "- **Typing is one call.** `performTextReplacement` sets a field's text through its semantics action, as typing"
        " would, so there is no keyboard to open, type on, or dismiss, and nothing is covered by it.",
        "- **Navigation still renders.** A push or a tab switch recomposes, lays out and draws the next screen, and the"
        " test waits for it.",
        "- **Checks poll.** An expectation is a wait (`waitUntil`) for a node or a value to appear, re-reading the"
        " semantics tree until it does. A UI test cannot ask the app what state it is in; it can only look.",
        "",
        "That is why the gap between the UI tests and the bridge is much smaller here than on iOS. In the"
        f" [iOS report]({ios}) an XCUITest step costs 1.1 s and a test's launch 5.2 s, because XCUITest drives the app"
        " from a separate runner process through accessibility snapshots, types key by key and relaunches the app for"
        f" every test. A Compose test step costs a small fraction of that ({fmt_s(u_step)}),"
        + (" *less than a bridge step*," if u_step < b_step else " about what a bridge step costs,")
        + " because it waits for the app's own idleness rather than a fixed quiet window. The two setups are not identical"
        " (one process and no relaunch here), which favours the Android UI tests; a per-test process restart"
        " (Android Test Orchestrator) would add a process start to every test.",
        "",
        "The bridge and the UI tests run the same app on the same emulator. The difference between the bridge and headless"
        " is what a running app, a device connection and real time cost.",
        "",
        "They are not interchangeable, though. The UI tests are the only mode that checks what a user can reach: that a"
        " node exists, is displayed and can be clicked. Headless is for the hundreds of checks an agent runs while it"
        " works; the UI tests are for what only the real UI can tell you.",
        "",
    ]
    return lines


IOS_REPORT = "https://github.com/olbartek/agentctl-ios/blob/main/docs/benchmarks/2026-09-24-agentshop.md"


def totals(data: dict) -> dict:
    manifest = data["manifest"]
    total = {"n": 0, "steps": 0, "headless": 0.0, "headless_group": 0.0, "bridge": 0.0, "bridge_run": 0.0,
             "bridge_launch": 0.0, "ui_wall": 0.0, "ui_tests": 0.0}
    rows = []
    for group, g in data["groups"].items():
        names = g["scenarios"]
        head = g.get("headless", {})
        bridge = g.get("bridge", {})
        ui = g.get("uitest", {})
        steps = sum(manifest[n]["steps"] for n in names)
        h = sum(head[n]["wall_s"] for n in names if head.get(n, {}).get("passed"))
        b = sum(bridge[n]["launch_s"] + bridge[n]["run_s"] for n in names if n in bridge)
        br = sum(bridge[n]["run_s"] for n in names if n in bridge)
        bl = sum(bridge[n]["launch_s"] for n in names if n in bridge)
        uw = ui.get("wall_s", 0.0)
        ut = sum(t["seconds"] for t in ui.get("tests", {}).values())
        hg = g.get("headless_group", {}).get("wall_s", 0.0)
        for key, value in (("n", len(names)), ("steps", steps), ("headless", h), ("headless_group", hg),
                           ("bridge", b), ("bridge_run", br), ("bridge_launch", bl), ("ui_wall", uw), ("ui_tests", ut)):
            total[key] += value
        rows.append((GROUP_TITLES[group], len(names), steps, hg, h, b, br, uw))
    # The whole set in one process, when it was measured: one JVM start rather than one per group.
    total["headless_all"] = data.get("headless_all", {}).get("wall_s", 0.0) or total["headless_group"]
    return {"total": total, "rows": rows}


def write_report(data: dict) -> Path:
    date = data.get("date") or f"{dt.datetime.now():%Y-%m-%d}"
    info = data["machine"]
    manifest = data["manifest"]
    t = totals(data)
    total, rows = t["total"], t["rows"]
    rows.append(("**All**", total["n"], total["steps"], total["headless_all"], total["headless"], total["bridge"],
                 total["bridge_run"], total["ui_wall"]))
    n, hg, bw, ui = total["n"], total["headless_all"], total["bridge"], total["ui_wall"]
    steps = max(total["steps"], 1)
    lines = [
        f"# AgentShop benchmark: headless vs emulator vs Compose UI tests ({date})",
        "",
        "Generated by `python3 examples/agentshop/bench/bench.py`. Every row runs the **same scenario file** in each",
        "mode: the headless CLI, the real app on an Android emulator driven through its debug-only agent bridge, and a",
        "Compose UI test generated from the scenario (`bench/gen_uitests.py`), which clicks and types through the real UI.",
        f"It is the Android counterpart of [the iOS benchmark]({IOS_REPORT}), with the same scenarios and the same method.",
        "",
        f"- **Machine:** {info['cpu']}, {info['cores']} cores, {info['memory_gb']} GB, macOS {info['macos']}",
        f"- **Tools:** Gradle {info['gradle']}, AGP {info['agp']}, Kotlin {info['kotlin']}, Compose BOM"
        f" {info['compose_bom']}; {info['jdk']}; {info['emulator']}",
        f"- **Emulator:** {data['device']}",
        f"- **Runs:** headless {data['runs']}× per scenario (median); bridge and UI tests once each.",
        "- **Settings:** both emulator modes launch a fresh app with no saved session and no mock latency, so the"
        " numbers compare the harnesses, not the fake backend. The Gradle daemon is warm.",
        "",
    ]
    lines += [
        "## Summary",
        "",
        f"- **{n} scenarios, {total['steps']} steps, three ways.** Headless: **{fmt_s(hg)}**. The real app on an emulator,"
        f" through the bridge: **{fmt_s(bw)}**. The generated Compose UI tests: **{fmt_s(ui)}**.",
        f"- The UI tests take **{ratio(ui, hg)}** as long as the headless run, and **{ratio(ui, bw)}** as long as the"
        " same scenarios driven through the bridge on the same emulator"
        + (": unlike on iOS, the UI tests are the faster of the two emulator modes (see"
           " [why](#compose-ui-tests-in-the-apps-process-synchronized-with-it))." if ui < bw else "."),
    ]
    if "change" in data:
        c = data["change"]
        lines.append(
            f"- After a one-line change to a reducer, verifying it headlessly (build + scenario) takes **{fmt_s(c['headless_s'])}**;"
            f" through a UI test (build + install + test), **{fmt_s(c['uitest_s'])}** ({ratio(c['uitest_s'], c['headless_s'])})."
        )
    lines += [
        "- Headless runs no emulator and renders no views, so it checks the app's logic and navigation, not pixels. The"
        " bridge checks the same things in the real app on a device, and the UI tests check that the UI shows and reaches"
        " them. The three are complementary; the point is which one an agent should run hundreds of times a day.",
        "- Compared with iOS, headless pays a JVM start instead of a native process start, and the Compose UI tests are"
        " far cheaper than XCUITest (they run in the app's process and do not relaunch it per test; see"
        " [below](#why-headless-is-the-fastest-and-where-the-other-two-spend-their-time)).",
        "",
        "## The three modes",
        "",
        "| Mode | What runs | How a step is sent | What it proves | Needs |",
        "|---|---|---|---|---|",
        "| **Headless** (`./appctl test`) | The features' reducers and the mocked clients, in a JVM on the Mac | A command,"
        " straight to the screen's store | Logic, navigation, errors, calls made, values the screen would show |"
        " `installDist` of the CLI only |",
        "| **Emulator bridge** (`./appctl app run`) | The real app on an emulator, views and all | A command over the app's"
        " debug-only HTTP bridge (through `adb forward`), then a 250 ms quiet wait | The same, in the real app process,"
        " with real views rendered | An emulator and a debug APK |",
        "| **Compose UI test** (generated) | The real app on an emulator, driven by instrumentation in its own process |"
        " A click or text input on a semantics node, then waits for what the screen shows | That the UI can reach every"
        " state: nodes exist, are displayed and can be clicked | An emulator, the debug and test APKs, `am instrument` |",
        "",
        "## Results",
        "",
        "### All modes, all scenarios",
        "",
        "| Mode | Total | Per scenario | Per step | vs Compose UI tests |",
        "|---|---|---|---|---|",
    ]
    modes = [("Headless, one process for everything", hg)]
    if data.get("headless_all") and total["headless_group"]:
        modes.append(("Headless, one process per group", total["headless_group"]))
    modes += [
        ("Headless, one process per scenario", total["headless"]),
        ("Emulator bridge, relaunch + run", bw),
        ("Emulator bridge, run only", total["bridge_run"]),
        ("Compose UI tests", ui),
    ]
    for label, value in modes:
        versus = "—" if not value or value == ui else (
            ratio(ui, value) + " faster" if value < ui else ratio(value, ui) + " slower")
        lines.append(f"| {label} | **{fmt_s(value)}** | {fmt_s(value / max(n, 1))} | {fmt_s(value / steps)} | {versus} |")
    lines += [
        "",
        "### Per group",
        "",
        "| Group | Scenarios | Steps | Headless, one process | Headless, one process per scenario | Emulator bridge (launch + run) | Compose UI tests | UI tests vs headless |",
        "|---|---|---|---|---|---|---|---|",
    ]
    for title, gn, gsteps, ghg, gh, gb, gbr, guw in rows:
        lines.append(
            f"| {title} | {gn} | {gsteps} | **{fmt_s(ghg)}** | {fmt_s(gh)} | {fmt_s(gb)} ({fmt_s(gbr)} running) | **{fmt_s(guw)}** | {ratio(guw, ghg)} |"
        )
    lines += [
        "",
        "- *Headless, one process* is `shopctl test` over the whole group (for *All*, over all of them in one process):"
        " how an agent (or CI) checks everything.",
        "- *One process per scenario* adds a JVM start to every scenario.",
        "- *Emulator bridge* relaunches the app for every scenario; *running* is `app run` alone.",
        "- *Compose UI tests* is the wall time of `adb shell am instrument -w -e class <the group's class>` (already built"
        " and installed); the per-test times below are the tests' own, logged by `ShopUiTestCase`.",
        "",
    ]

    if "change" in data:
        c = data["change"]
        p = c.get("median_parts", {})
        lines += [
            "### The loop an agent runs: change a line, verify it",
            "",
            f"A one-line change to the `ShopFeed` reducer, then"
            f" `{CHANGE_SCENARIO}` verified (median of {c['runs']}).",
            "",
            "| | Time |",
            "|---|---|",
            f"| Headless: Gradle `installDist` of the CLI + the scenario | **{fmt_s(c['headless_s'])}** |",
            f"| UI test: `assembleDebug assembleDebugAndroidTest` + installing both APKs + the scenario's UI test | **{fmt_s(c['uitest_s'])}** |",
            f"| Ratio | {ratio(c['uitest_s'], c['headless_s'])} |",
            "",
        ]
        if p:
            lines += [
                f"Split (medians): headless build {fmt_s(p['headless_build_s'])} + scenario {fmt_s(p['headless_test_s'])};"
                f" UI build {fmt_s(p['assemble_s'])} + install {fmt_s(p['install_s'])} + `am instrument` {fmt_s(p['uitest_s'])}."
                " The reducer lives in the `:shop` module: Gradle recompiles it and repackages the app APK, and the test"
                " APK stays up to date. The edit appends a `require` of a new constant to the line rather than a comment,"
                " because a comment leaves the bytecode unchanged and Gradle would then skip everything after the compile.",
                "",
            ]

    if data.get("setup"):
        s = data["setup"]
        lines += ["### One-off costs", "", "| | Time |", "|---|---|"]
        for label, key in (("Gradle `installDist` of the CLI (warm, nothing changed)", "cli_build_s"),
                           ("`assembleDebug assembleDebugAndroidTest` (warm, nothing changed)", "app_build_s"),
                           ("Installing the debug and test APKs (`adb install`)", "install_s")):
            if key in s:
                lines.append(f"| {label} | {fmt_s(s[key])} |")
        lines.append("")

    lines += why_section(data)

    checked = sum(manifest[n].get("assertions_checked_in_ui", 0) for g in data["groups"].values() for n in g["scenarios"])
    headless_only_asserts = sum(manifest[n].get("assertions_headless_only", 0) for g in data["groups"].values() for n in g["scenarios"])
    lines += [
        "## What the UI tests check",
        "",
        f"The generated UI tests assert {checked} of the scenarios' {checked + headless_only_asserts} expectations through"
        " the UI (screens, errors, shown values, enabled buttons). The other"
        f" {headless_only_asserts} — `loading=`, `call=`, `pending=`, values no screen shows — are checked headlessly only.",
        "",
    ]

    only = data.get("headless_only", {})
    if only:
        lines += [
            "## Headless-only scenarios",
            "",
            "These move a virtual clock (`advance`), seed (`login-as`) or relaunch (`reset`), which a UI test can only do by"
            " really waiting or relaunching. Headlessly they take milliseconds (with the JVM start):",
            "",
            "| Scenario | Steps | Headless | Real time a UI test would wait |",
            "|---|---|---|---|",
        ]
        for name, r in only.items():
            wait = r.get("advance_s", 0)
            lines.append(f"| `{name}` | {r.get('steps', 0)} | {fmt_s(r.get('wall_s', 0))} | {fmt_s(wait) if wait else '—'} |")
        lines.append("")

    lines += ["## Every scenario", ""]
    for group, g in data["groups"].items():
        lines += [
            f"<details><summary>{GROUP_TITLES[group]} ({len(g['scenarios'])} scenarios)</summary>",
            "",
            "| Scenario | Steps | Headless (in-process) | Headless (with JVM start) | Bridge launch | Bridge run | Compose UI test |",
            "|---|---|---|---|---|---|---|",
        ]
        tests = g.get("uitest", {}).get("tests", {})
        for name in g["scenarios"]:
            h = g.get("headless", {}).get(name, {})
            b = g.get("bridge", {}).get(name, {})
            r = tests.get(test_key(manifest, name), {})
            fail = lambda x: "" if not x or x.get("passed") else " ✗"
            lines.append(
                f"| `{name}` | {manifest[name]['steps']} | {fmt_s(h.get('in_process_s', 0))}{fail(h)} | {fmt_s(h.get('wall_s', 0))} | "
                f"{fmt_s(b.get('launch_s', 0)) if b else '—'} | {fmt_s(b.get('run_s', 0)) if b else '—'}{fail(b)} | "
                f"{fmt_s(r.get('seconds', 0)) if r else '—'}{fail(r)} |"
            )
        lines += ["", "</details>", ""]

    failures = [
        f"{mode} {name}"
        for g in data["groups"].values()
        for mode in ("headless", "bridge")
        for name, r in g.get(mode, {}).items()
        if not r.get("passed")
    ] + [f"uitest {k}" for g in data["groups"].values() for k, r in g.get("uitest", {}).get("tests", {}).items() if not r["passed"]]
    lines += [
        "## Caveats",
        "",
        "- One machine, one session. The ratios are more stable than the absolute numbers.",
        "- The emulator runs on the same Mac as everything else (an arm64 system image, hardware-accelerated), so the"
        " emulator modes share its CPU with Gradle and adb. A physical device would differ.",
        "- The bridge settles each step with a 250 ms quiet window, which dominates its per-step time.",
        "- UI tests run one at a time on one emulator. They share one instrumentation process per group and do not"
        " restart the app's process between tests, where XCUITest relaunches the app for every test; with Android Test"
        " Orchestrator (a process per test) they would be slower.",
        "- The headless numbers include the JVM's start and warm-up: `shopctl` is a Gradle `installDist` start script"
        " running a JVM, not a native binary.",
        "- `auth-otp-resend-countdown` is headless-only (it uses `advance`), so it is not compared above. Through the bridge"
        " (`app test`) it fails for a reason the benchmark does not hide: the live clock also ticks in real time during"
        " the steps before `advance 10s`, so the screen then reads `resendIn=19`, not the `resendIn=20` the scenario"
        " expects (observed while building this report, as on iOS).",
    ]
    for note in data.get("notes", []):
        lines.append(f"- {note}")
    reruns = data.get("reruns", {})
    failures = [f for f in failures if f not in reruns]
    if reruns:
        lines.append(
            f"- {len(reruns)} scenario runs failed in the full run and passed when rerun alone afterwards: "
            + ", ".join(f"`{k}` ({fmt_s(v['launch_s']) + ' launch + ' + fmt_s(v['run_s']) + ' run' if 'run_s' in v else fmt_s(v['seconds'])})"
                        for k, v in reruns.items())
            + ". " + data.get("reruns_cause", "")
            + " The totals above are the full run's, failed attempts included."
        )
    for failure in failures:
        lines.append(f"- Failed: `{failure}`" + (f": {KNOWN_FAILURES[failure]}" if failure in KNOWN_FAILURES else "."))
    if not failures:
        lines.append("- No other failures.")
    lines.append("")
    video = REPORT_DIR / f"{date}-agentshop.mp4"
    if video.exists():
        # `bench/video.py`'s recording, copied (and compressed) next to the report.
        lines.insert(lines.index("## Summary"), f"**Video:** [three scenarios in all three modes, side by side]({video.name}).\n")
    REPORT_DIR.mkdir(parents=True, exist_ok=True)
    path = REPORT_DIR / f"{date}-agentshop.md"
    path.write_text("\n".join(lines))
    return path


def advance_seconds(text: str) -> float:
    total = 0.0
    for amount, unit in re.findall(r"^advance (\d+)(ms|s|m|h)\s*$", text, flags=re.M):
        total += int(amount) * {"ms": 0.001, "s": 1, "m": 60, "h": 3600}[unit]
    return total


def latest_raw() -> Path | None:
    runs = sorted(OUT.glob("2*.json"))
    return runs[-1] if runs else None


def rerun_failures(raw: Path, port: int, cause: str) -> None:
    """Reruns, one at a time, what failed in a saved run for no known reason, and records the reruns beside it.

    The run's own numbers are kept: the report lists the reruns in its caveats.
    """
    data = json.loads(raw.read_text())
    reruns = data.setdefault("reruns", {})
    for group, g in data["groups"].items():
        for name, r in g.get("bridge", {}).items():
            key = f"bridge {name}"
            if not r.get("passed") and key not in KNOWN_FAILURES:
                result = measure_bridge([name], port)[name]
                if result["passed"]:
                    reruns[key] = result
        for test, r in g.get("uitest", {}).get("tests", {}).items():
            if not r.get("passed"):
                _, tests, _ = measure_uitests(group, only=[f"{TEST_PACKAGE}.{test}"])
                if tests.get(test, {}).get("passed"):
                    reruns[f"uitest {test}"] = tests[test]
    if cause:
        data["reruns_cause"] = cause
    raw.write_text(json.dumps(data, indent=2, default=str))
    log(f"reruns: {', '.join(reruns) or 'none'}; wrote {write_report(data).relative_to(REPO)}")


def main() -> None:
    global DRY_RUN, SERIAL
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--runs", type=int, default=3, help="headless runs per scenario, and change loops (median)")
    parser.add_argument("--quick", action="store_true", help="one run of everything")
    parser.add_argument("--groups", default="auth,onboarding,shop")
    parser.add_argument("--modes", default="headless,bridge,uitest")
    parser.add_argument("--device", help="adb serial or running AVD name (default: the only device connected)")
    parser.add_argument("--port", type=int, default=8799, help="the bridge port (8765 is often taken by adb)")
    parser.add_argument("--no-change-loop", action="store_true", help="skip the change-a-line loop")
    parser.add_argument("--dry-run", action="store_true")
    parser.add_argument("--report", metavar="RAW_JSON", help="only rewrite the report from a saved .bench/<timestamp>.json")
    parser.add_argument("--rerun", metavar="RAW_JSON",
                        help="rerun a saved run's unexpected bridge and UI test failures alone, record them, rewrite the report")
    parser.add_argument("--rerun-cause", default="", help="with --rerun: what the report says made them fail")
    args = parser.parse_args()
    if args.rerun:
        SERIAL = resolve_device(args.device)
        rerun_failures(Path(args.rerun), args.port, args.rerun_cause)
        return
    if args.report:
        log(f"wrote {write_report(json.loads(Path(args.report).read_text())).relative_to(REPO)}")
        return
    DRY_RUN = args.dry_run
    runs = 1 if args.quick else args.runs
    groups = [g for g in args.groups.split(",") if g]
    modes = set(args.modes.split(","))

    manifest = json.loads(MANIFEST.read_text())["scenarios"]
    SERIAL = resolve_device(args.device)
    data: dict = {"date": f"{dt.datetime.now():%Y-%m-%d}", "machine": machine(), "device": device_info(),
                  "runs": runs, "manifest": manifest, "groups": {}, "setup": {}}
    log(f"bench: {', '.join(groups)} in {', '.join(sorted(modes))} on {data['device']}")

    log("setup")
    gradle(SHOPCTL)
    data["setup"]["cli_build_s"] = gradle(SHOPCTL)[0]
    if modes & {"bridge", "uitest"} or not args.no_change_loop:
        build_app()
        data["setup"]["app_build_s"] = build_app()
        data["setup"]["install_s"] = install_apks()

    comparable = []
    for group in groups:
        names = sorted(n for n, m in manifest.items() if m["group"] == group and m["comparable"])
        comparable += names
        g: dict = {"scenarios": names}
        log(f"{GROUP_TITLES[group]}: {len(names)} scenarios")
        if "headless" in modes:
            g["headless"] = measure_headless(names, runs)
            g["headless_group"] = measure_headless_group(names, runs)
            log(f"  headless group: {fmt_s(g['headless_group']['wall_s'])}")
        if "bridge" in modes:
            g["bridge"] = measure_bridge(names, args.port)
        if "uitest" in modes:
            wall, tests, _ = measure_uitests(group)
            g["uitest"] = {"wall_s": wall, "tests": tests}
            log(f"  UI tests: {fmt_s(wall)}, {sum(t['passed'] for t in tests.values())}/{len(tests)} passed")
        data["groups"][group] = g

    if "headless" in modes:
        if len(groups) > 1:
            data["headless_all"] = measure_headless_group(comparable, runs)
            log(f"headless, all {len(comparable)} in one process: {fmt_s(data['headless_all']['wall_s'])}")
        only = sorted(n for n, m in manifest.items() if not m["comparable"] and m["group"] in groups)
        results = measure_headless(only, runs)
        for name in only:
            results[name]["advance_s"] = advance_seconds((ROOT / f"scenarios/{name}.appctl").read_text())
            results[name].setdefault("steps", manifest[name]["steps"])
        data["headless_only"] = results

    if not args.no_change_loop and "shop" in groups:
        log("change loop")
        uitest = manifest[CHANGE_SCENARIO]["uitest"]
        data["change"] = measure_change_loop(runs, uitest)
        # Leave the build outputs and the installed app matching the restored source.
        gradle(SHOPCTL)
        build_app()
        install_apks()

    if DRY_RUN:
        return
    OUT.mkdir(parents=True, exist_ok=True)
    raw = OUT / f"{dt.datetime.now():%Y%m%d-%H%M%S}.json"
    raw.write_text(json.dumps(data, indent=2, default=str))
    report = write_report(data)
    log(f"wrote {report.relative_to(REPO)} (raw: {raw.relative_to(ROOT)})")


if __name__ == "__main__":
    main()
