#!/usr/bin/env python3
"""Takes the screenshots in docs/APP.md: drives the real app on an emulator to each screen through the agent bridge,
then saves what the emulator shows.

The port of agentctl-ios's bench/screenshots.py (`adb exec-out screencap` instead of `simctl io screenshot`).

    python3 bench/screenshots.py
    python3 bench/screenshots.py --only shop-feed,cart

Writes docs/screenshots/<name>.png, scaled down to 600 px high. Builds and installs the debug app if needed.
"""

from __future__ import annotations

import argparse
import io
import subprocess
import sys
import time
from pathlib import Path

from PIL import Image

sys.path.insert(0, str(Path(__file__).resolve().parent))
import bench  # noqa: E402

SHOTS = bench.ROOT / "docs/screenshots"

ALICE = "email alice@example.com\npassword Passw0rd!\nsubmit\n"
NINA = "email nina@example.com\npassword Passw0rd!\nsubmit\n"

# Each screenshot is the screen a script ends on, from a fresh launch with no saved session.
SCREENS: list[tuple[str, str]] = [
    ("login", "email alice@example.com\npassword Passw0rd!\n"),
    ("otp-code", "email alice@example.com\nuse-otp\nsend\n"),
    ("register", "register\nname Nina Park\nemail new@example.com\n"),
    ("onboarding-welcome", NINA),
    ("onboarding-interests", NINA + "skip\ntoggle bags\ntoggle home\n"),
    ("onboarding-address", NINA + "skip\ntoggle bags\ntoggle home\ncontinue\nname Nina Park\nstreet 2 Elm St\ncity Portland\n"),
    ("shop-feed", ALICE),
    ("shop-feed-filtered", ALICE + "filter shoes\nsort price-desc\n"),
    ("product", ALICE + "open 101\nsize 42\nqty-up\n"),
    ("cart", ALICE + "open 101\nsize 42\nadd-to-cart\nview-cart\npromo-code SAVE10\napply-promo\n"),
    ("checkout", ALICE + "open 101\nsize 42\nadd-to-cart\nview-cart\ncheckout\nshipping express\ncard 4242 4242 4242 4242\n"),
    ("confirmation", ALICE + "open 101\nsize 42\nadd-to-cart\nview-cart\ncheckout\ncard 4242 4242 4242 4242\nplace-order\n"),
    ("orders", ALICE + "tab orders\n"),
    ("profile", ALICE + "tab profile\n"),
]


def shell(*arguments: str) -> None:
    subprocess.run([bench.ADB, "-s", bench.SERIAL, "shell", *arguments], env=bench.ENV, capture_output=True)


def demo_mode(on: bool) -> None:
    """A clean status bar (SystemUI's demo mode): 9:41, full battery and signal, no notifications."""
    demo = ["am", "broadcast", "-a", "com.android.systemui.demo", "-e", "command"]
    if not on:
        shell(*demo, "exit")
        return
    shell("settings", "put", "global", "sysui_demo_allowed", "1")
    shell(*demo, "enter")
    shell(*demo, "clock", "-e", "hhmm", "0941")
    shell(*demo, "battery", "-e", "level", "100", "-e", "plugged", "false")
    shell(*demo, "network", "-e", "wifi", "show", "-e", "level", "4")
    shell(*demo, "network", "-e", "mobile", "hide")
    shell(*demo, "notifications", "-e", "visible", "false")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--only", default="")
    parser.add_argument("--device", help="adb serial or running AVD name (default: the only device connected)")
    parser.add_argument("--port", type=int, default=8799)
    args = parser.parse_args()
    only = {s for s in args.only.split(",") if s}

    bench.SERIAL = bench.resolve_device(args.device)
    print(f"screenshots on {bench.device_info()}", flush=True)
    bench.gradle(bench.SHOPCTL)
    bench.gradle(":examples:agentshop:app:assembleDebug")
    bench.adb("install", "-r", "-t", str(bench.APP_APK))
    SHOTS.mkdir(parents=True, exist_ok=True)
    demo_mode(True)
    try:
        for name, script in SCREENS:
            if only and name not in only:
                continue
            bench.cli("app", "launch", "--no-build", "--clear-session", "--latency", "0", "--device", bench.SERIAL,
                      "--port", str(args.port))
            _, output, code = bench.cli("app", "run", "--port", str(args.port), script, check=False)
            if code != 0:
                print(output[-1500:])
                sys.exit(f"screenshots: {name} did not get to its screen")
            time.sleep(1.0)  # animations, and the keyboard going away
            png = subprocess.run([bench.ADB, "-s", bench.SERIAL, "exec-out", "screencap", "-p"], capture_output=True,
                                 env=bench.ENV, check=True).stdout
            image = Image.open(io.BytesIO(png)).convert("RGB")
            image = image.resize((round(image.width * 600 / image.height), 600), Image.LANCZOS)
            path = SHOTS / f"{name}.png"
            image.save(path, optimize=True)
            print(f"  {path.relative_to(bench.ROOT)}", flush=True)
    finally:
        demo_mode(False)


if __name__ == "__main__":
    main()
