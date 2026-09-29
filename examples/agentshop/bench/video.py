#!/usr/bin/env python3
"""Records the same scenarios as a Compose UI test run and through the emulator bridge, times them headlessly, and
composes the three side by side into one video with running timers.

The port of agentctl-ios's bench/video.py (`adb shell screenrecord` instead of `simctl io recordVideo`, and
`bench/compose.py` instead of compose.swift).

    python3 bench/video.py
    python3 bench/video.py --scenarios auth-login-happy-path,shop-checkout-happy-path
    python3 bench/video.py --caption "…"      # the line under the panels (default: the latest bench.py totals)

Writes .bench/video/agentshop-three-modes.mp4 (and the two raw recordings next to it), and copies it next to the
latest report as docs/benchmarks/<date>-agentshop.mp4 (`--no-publish` leaves it in .bench). Builds and installs the
app and its tests if needed.
"""

from __future__ import annotations

import argparse
import json
import shutil
import subprocess
import sys
import threading
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import bench  # noqa: E402

ROOT = bench.ROOT
VIDEO = bench.OUT / "video"
DEFAULT_SCENARIOS = ["auth-login-happy-path", "onboarding-address-prefills-checkout", "shop-checkout-happy-path"]
DEVICE_FILE = "/sdcard/agentshop-bench.mp4"


class Recorder:
    """`adb shell screenrecord` in the background. `started` is when it reported that it was capturing."""

    def __init__(self, path: Path) -> None:
        self.path = path
        subprocess.run([bench.ADB, "-s", bench.SERIAL, "shell", "rm", "-f", DEVICE_FILE], env=bench.ENV)
        self.process = subprocess.Popen(
            [bench.ADB, "-s", bench.SERIAL, "shell", "screenrecord", "--verbose", "--bit-rate", "8000000", DEVICE_FILE],
            stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, env=bench.ENV,
        )
        self.ready = threading.Event()
        self.started = 0.0
        self.log: list[str] = []
        threading.Thread(target=self._watch, daemon=True).start()
        if not self.ready.wait(20):
            self.process.kill()
            sys.exit("video: screenrecord did not start:\n" + "".join(self.log))

    def _watch(self) -> None:
        for line in self.process.stdout:  # type: ignore[union-attr]
            self.log.append(line)
            if "Content area is" in line and not self.ready.is_set():
                self.started = time.perf_counter()
                self.ready.set()

    def stop(self) -> None:
        subprocess.run([bench.ADB, "-s", bench.SERIAL, "shell", "pkill", "-INT", "screenrecord"], env=bench.ENV)
        self.process.wait(30)
        time.sleep(1)  # the muxer finishes the file
        subprocess.run([bench.ADB, "-s", bench.SERIAL, "pull", DEVICE_FILE, str(self.path)], env=bench.ENV,
                       capture_output=True, check=True)
        subprocess.run([bench.ADB, "-s", bench.SERIAL, "shell", "rm", "-f", DEVICE_FILE], env=bench.ENV)


def launch(port: int) -> None:
    bench.cli("app", "launch", "--no-build", "--clear-session", "--latency", "0", "--device", bench.SERIAL, "--port", str(port))


def headless(scenarios: list[str]) -> tuple[float, str]:
    files = [f"scenarios/{name}.appctl" for name in scenarios]
    seconds, output, code = bench.cli("test", *files, check=False)
    if code != 0:
        sys.exit(f"video: the scenarios failed headlessly\n{output}")
    text = "$ ./appctl test " + " ".join(f"{name}.appctl" for name in scenarios) + "\n" + output
    return seconds, text


def bridge(scenarios: list[str], port: int) -> tuple[Path, float, float, float]:
    path = VIDEO / "bridge.mp4"
    # The app on screen before recording, so the recording starts on it rather than on the launcher.
    launch(port)
    time.sleep(1)
    recorder = Recorder(path)
    time.sleep(0.5)
    start = time.perf_counter()
    for name in scenarios:
        script = (ROOT / f"scenarios/{name}.appctl").read_text()
        launch(port)
        _, output, code = bench.cli("app", "run", "--port", str(port), script, check=False)
        if code != 0:
            print(output[-2000:])
            sys.exit(f"video: {name} failed through the bridge")
    seconds = time.perf_counter() - start
    time.sleep(1.5)
    recorder.stop()
    # Hold a frame from just after the last step, once its animation has finished.
    return path, start - recorder.started, seconds, start - recorder.started + seconds + 0.8


def uitests(scenarios: list[str], manifest: dict) -> tuple[Path, float, float, float | None]:
    path = VIDEO / "uitest.mp4"
    tests = [manifest[name]["uitest"] for name in scenarios]
    recorder = Recorder(path)
    time.sleep(0.5)
    start = time.perf_counter()
    # Streamed, to note when the last test finished: after that the activity closes and the recording shows the
    # launcher until `am instrument` exits.
    process = subprocess.Popen(
        [bench.ADB, "-s", bench.SERIAL, "shell", "am", "instrument", "-w", "-r", "-e", "class", ",".join(tests), bench.RUNNER],
        stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, env=bench.ENV,
    )
    output, last_passed = [], None
    for line in process.stdout:  # type: ignore[union-attr]
        output.append(line)
        if line.startswith("INSTRUMENTATION_STATUS_CODE: 0"):
            last_passed = time.perf_counter()
    process.wait()
    seconds = time.perf_counter() - start
    time.sleep(1)
    recorder.stop()
    outcomes = bench.parse_instrument("".join(output))
    if len(outcomes) != len(tests) or not all(outcomes.values()):
        print("".join(output)[-3000:])
        sys.exit("video: the UI tests failed")
    freeze = last_passed - recorder.started - 0.3 if last_passed else None
    return path, start - recorder.started, seconds, freeze


def default_caption() -> str:
    raw = bench.latest_raw()
    if not raw:
        return "The same scenario files in all three modes."
    data = json.loads(raw.read_text())
    t = bench.totals(data)["total"]
    if not (t["headless_all"] and t["bridge"] and t["ui_wall"]):
        return "The same scenario files in all three modes."
    return (f"All {t['n']} scenarios: Compose UI tests {bench.fmt_s(t['ui_wall'])} · emulator bridge"
            f" {bench.fmt_s(t['bridge'])} · headless {bench.fmt_s(t['headless_all'])}")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--scenarios", default=",".join(DEFAULT_SCENARIOS))
    parser.add_argument("--device", help="adb serial or running AVD name (default: the only device connected)")
    parser.add_argument("--port", type=int, default=8799)
    parser.add_argument("--caption")
    parser.add_argument("--no-publish", action="store_true", help="do not copy the video next to the latest report")
    args = parser.parse_args()
    scenarios = [s for s in args.scenarios.split(",") if s]
    manifest = json.loads(bench.MANIFEST.read_text())["scenarios"]
    for name in scenarios:
        if not manifest.get(name, {}).get("comparable"):
            sys.exit(f"video: {name} is not a scenario that runs in all three modes")

    VIDEO.mkdir(parents=True, exist_ok=True)
    bench.SERIAL = bench.resolve_device(args.device)
    print(f"video: {', '.join(scenarios)} on {bench.device_info()}", flush=True)
    bench.gradle(bench.SHOPCTL)
    bench.build_app()
    bench.install_apks()

    headless_s, headless_text = headless(scenarios)
    (VIDEO / "headless.txt").write_text(headless_text)
    print(f"  headless: {bench.fmt_s(headless_s)}", flush=True)
    bridge_path, bridge_start, bridge_s, bridge_freeze = bridge(scenarios, args.port)
    print(f"  bridge: {bench.fmt_s(bridge_s)}", flush=True)
    ui_path, ui_start, ui_s, ui_freeze = uitests(scenarios, manifest)
    print(f"  Compose UI tests: {bench.fmt_s(ui_s)}", flush=True)
    (VIDEO / "timings.json").write_text(json.dumps({
        "scenarios": scenarios, "headless_s": headless_s, "bridge_s": bridge_s, "uitest_s": ui_s,
        "bridge_start": bridge_start, "uitest_start": ui_start, "bridge_freeze": bridge_freeze, "uitest_freeze": ui_freeze,
    }, indent=2))

    out = VIDEO / "agentshop-three-modes.mp4"
    subprocess.run([
        sys.executable, str(ROOT / "bench/compose.py"),
        "--uitest", str(ui_path), "--uitest-start", f"{ui_start:.3f}", "--uitest-seconds", f"{ui_s:.3f}",
        "--bridge", str(bridge_path), "--bridge-start", f"{bridge_start:.3f}", "--bridge-seconds", f"{bridge_s:.3f}",
        "--bridge-freeze", f"{bridge_freeze:.3f}",
    ] + (["--uitest-freeze", f"{ui_freeze:.3f}"] if ui_freeze else []) + [
        "--headless", str(VIDEO / "headless.txt"), "--headless-seconds", f"{headless_s:.3f}",
        "--caption", args.caption or default_caption(),
        "--out", str(out),
    ], check=True, cwd=ROOT)
    print(f"video: {out.relative_to(ROOT)} ({out.stat().st_size / 1e6:.1f} MB)")
    raw = bench.latest_raw()
    if not args.no_publish and raw:
        date = json.loads(raw.read_text()).get("date")
        published = bench.REPORT_DIR / f"{date}-agentshop.mp4"
        shutil.copyfile(out, published)
        print(f"video: {published.relative_to(bench.REPO)}")


if __name__ == "__main__":
    main()
