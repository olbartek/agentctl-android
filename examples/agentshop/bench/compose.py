#!/usr/bin/env python3
"""Composes the three-mode demo video: the UI test recording, the bridge recording and the headless run, side by
side, each with a title and a running timer that stops when that mode finishes. `bench/video.py` records the inputs
and calls this; run it by hand as

    python3 bench/compose.py --uitest ui.mp4 --uitest-start 1.2 --uitest-seconds 20.0 \\
      --bridge bridge.mp4 --bridge-start 0.8 --bridge-seconds 12.1 \\
      [--uitest-freeze 19.0] [--bridge-freeze 13.7] \\
      --headless headless.txt --headless-seconds 0.9 --caption "…" --out out.mp4

The port of agentctl-ios's bench/compose.swift. `--*-start` is where the run begins in its recording, `--*-seconds`
how long it ran. ffmpeg decodes the recordings and encodes the result; Pillow draws each frame (this machine's
ffmpeg has no `drawtext`).
"""

from __future__ import annotations

import argparse
import json
import subprocess
import sys
import textwrap

from PIL import Image, ImageDraw, ImageFont, ImageStat

WIDTH, HEIGHT = 1600, 1000
FPS = 15
HEADER, FOOTER, MARGIN = 128, 60, 20

BACKGROUND = (18, 20, 26)
PANEL = (31, 33, 41)
TERMINAL = (8, 10, 13)
WHITE = (255, 255, 255)
GREY = (158, 166, 179)
GREEN = (89, 217, 140)
AMBER = (255, 191, 77)
RED = (255, 102, 102)
BADGE = (20, 77, 46)

HELVETICA = "/System/Library/Fonts/HelveticaNeue.ttc"
MENLO = "/System/Library/Fonts/Menlo.ttc"


def font(size: int, bold: bool = False, mono: bool = False) -> ImageFont.FreeTypeFont:
    return ImageFont.truetype(MENLO if mono else HELVETICA, size, index=1 if bold else 0)


def probe(path: str) -> tuple[int, int]:
    out = subprocess.run(
        ["ffprobe", "-v", "error", "-select_streams", "v:0", "-show_entries", "stream=width,height", "-of", "json", path],
        capture_output=True, text=True, check=True,
    ).stdout
    stream = json.loads(out)["streams"][0]
    return stream["width"], stream["height"]


class FrameSource:
    """A recording's frames at FPS, scaled to fit a box, read in order; `frame(t)` is the one showing at t seconds.

    Emulator recordings have a variable frame rate (a frame only when the screen changes); ffmpeg's fps filter,
    anchored at 0, turns that into one frame per tick, repeating the last one.
    """

    def __init__(self, path: str, box: tuple[int, int]) -> None:
        w, h = probe(path)
        scale = min(box[0] / w, box[1] / h)
        self.size = (int(w * scale) // 2 * 2, int(h * scale) // 2 * 2)
        self.process = subprocess.Popen(
            ["ffmpeg", "-v", "error", "-i", path, "-vf", f"fps={FPS}:start_time=0,scale={self.size[0]}:{self.size[1]}",
             "-f", "rawvideo", "-pix_fmt", "rgb24", "-"],
            stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
        )
        self.index = -1
        self.current: Image.Image | None = None

    def close(self) -> None:
        self.process.kill()
        self.process.wait()

    def frame(self, time: float) -> Image.Image | None:
        target = int(time * FPS)
        frame_bytes = self.size[0] * self.size[1] * 3
        while self.index < target:
            data = self.process.stdout.read(frame_bytes)  # type: ignore[union-attr]
            if len(data) < frame_bytes:
                break
            self.current = Image.frombytes("RGB", self.size, data)
            self.index += 1
        return self.current

    def last_app_frame(self, up_to: float) -> Image.Image | None:
        """The last frame at or before `up_to` that shows the app rather than the launcher. AgentShop's screens are
        mostly white and the launcher is a dark wallpaper, so the frame's average brightness tells them apart. The
        test closes the activity as it ends, before `am instrument` reports it."""
        found = None
        step = max(int(FPS * 0.2), 1)
        t = 0.0
        while t <= up_to:
            image = self.frame(t)
            if image is not None and brightness(image) > 0.7:
                found = image
            t += step / FPS
        return found


def brightness(image: Image.Image) -> float:
    r, g, b = ImageStat.Stat(image.resize((32, 64))).mean
    return (0.299 * r + 0.587 * g + 0.114 * b) / 255


def clock(seconds: float) -> str:
    minutes = int(seconds) // 60
    return f"{minutes:02d}:{seconds - minutes * 60:04.1f}"


def centered(draw: ImageDraw.ImageDraw, text: str, x: float, width: float, y: float, f: ImageFont.FreeTypeFont, color) -> None:
    w = draw.textlength(text, font=f)
    draw.text((x + (width - w) / 2, y), text, font=f, fill=color)


def chrome(draw: ImageDraw.ImageDraw, panel: dict, time: float) -> None:
    x, y, w, h = panel["rect"]
    finished = time >= panel["seconds"]
    centered(draw, panel["title"], x, w, 14, font(28, bold=True), WHITE)
    centered(draw, panel["subtitle"], x, w, 50, font(17), GREY)
    centered(draw, clock(min(time, panel["seconds"])), x, w, 76, font(36, bold=True, mono=True), GREEN if finished else AMBER)
    if finished:
        badge = (x + 14, y + h - 14 - 50, x + w - 14, y + h - 14)
        draw.rectangle(badge, fill=BADGE)
        centered(draw, f"✓ done in {clock(panel['seconds'])}", x, w, badge[1] + 12, font(22, bold=True, mono=True), GREEN)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    for mode in ("uitest", "bridge"):
        parser.add_argument(f"--{mode}", required=True)
        parser.add_argument(f"--{mode}-start", type=float, default=0.0)
        parser.add_argument(f"--{mode}-seconds", type=float, required=True)
        parser.add_argument(f"--{mode}-freeze", type=float)
    parser.add_argument("--headless", required=True)
    parser.add_argument("--headless-seconds", type=float, required=True)
    parser.add_argument("--caption", default="")
    parser.add_argument("--out", required=True)
    parser.add_argument("--crf", type=int, default=26)
    args = parser.parse_args()

    panel_h = HEIGHT - HEADER - FOOTER
    uw, uh = probe(args.uitest)
    phone_w = round(panel_h * uw / uh)
    box = (phone_w - 8, panel_h - 8)
    panels = [
        {"title": "Compose UI tests", "subtitle": "click and type through the UI",
         "rect": (MARGIN, HEADER, phone_w, panel_h), "seconds": args.uitest_seconds},
        {"title": "AgentCtl · emulator", "subtitle": "the real app, driven via its bridge",
         "rect": (MARGIN * 2 + phone_w, HEADER, phone_w, panel_h), "seconds": args.bridge_seconds},
        {"title": "AgentCtl · headless", "subtitle": "the same scenarios on the JVM, no emulator",
         "rect": (MARGIN * 3 + phone_w * 2, HEADER, WIDTH - MARGIN * 4 - phone_w * 2, panel_h),
         "seconds": args.headless_seconds},
    ]
    # What each emulator panel holds once its run is done: a settled frame, not the launcher after the app closed.
    source = FrameSource(args.uitest, box)
    ui_hold = source.last_app_frame(args.uitest_freeze or args.uitest_start + args.uitest_seconds)
    source.close()
    source = FrameSource(args.bridge, box)
    bridge_hold = source.frame(args.bridge_freeze or args.bridge_start + args.bridge_seconds)
    source.close()
    sources = [(panels[0], FrameSource(args.uitest, box), args.uitest_start, ui_hold),
               (panels[1], FrameSource(args.bridge, box), args.bridge_start, bridge_hold)]
    mono = font(15, mono=True)
    columns = int((panels[2]["rect"][2] - 32) // mono.getlength("m"))
    lines = [part for line in open(args.headless).read().split("\n")
             for part in (textwrap.wrap(line, columns, subsequent_indent="    ") or [""])]
    duration = max(args.uitest_seconds, args.bridge_seconds, args.headless_seconds) + 3

    encoder = subprocess.Popen(
        ["ffmpeg", "-v", "error", "-y", "-f", "rawvideo", "-pix_fmt", "rgb24", "-s", f"{WIDTH}x{HEIGHT}", "-r", str(FPS),
         "-i", "-", "-c:v", "libx264", "-preset", "slow", "-crf", str(args.crf), "-pix_fmt", "yuv420p",
         "-movflags", "+faststart", args.out],
        stdin=subprocess.PIPE,
    )
    for index in range(int(duration * FPS)):
        time = index / FPS
        canvas = Image.new("RGB", (WIDTH, HEIGHT), BACKGROUND)
        draw = ImageDraw.Draw(canvas)
        for panel, source, start, hold in sources:
            x, y, w, h = panel["rect"]
            draw.rectangle((x, y, x + w, y + h), fill=PANEL)
            live = source.frame(start + time) if time < panel["seconds"] else None
            image = live or hold or source.frame(start + panel["seconds"])
            if image is not None:
                canvas.paste(image, (x + (w - image.width) // 2, y + (h - image.height) // 2))
            chrome(draw, panel, time)

        # The headless panel: the terminal output, all of it once the run is over (it takes about a second).
        terminal = panels[2]
        x, y, w, h = terminal["rect"]
        draw.rectangle((x, y, x + w, y + h), fill=TERMINAL)
        shown = len(lines) if time >= terminal["seconds"] else int(len(lines) * time / max(terminal["seconds"], 0.001))
        ty = y + 20
        for line in lines[:shown]:
            if ty > y + h - 90:
                break
            color = GREEN if line.startswith("PASS") else RED if line.startswith("FAIL") else WHITE if line.startswith("$") else GREY
            draw.text((x + 16, ty), line, font=mono, fill=color)
            ty += 22
        chrome(draw, terminal, time)
        if args.caption:
            centered(draw, args.caption, 0, WIDTH, HEIGHT - FOOTER + 18, font(20), GREY)
        encoder.stdin.write(canvas.tobytes())  # type: ignore[union-attr]

    encoder.stdin.close()  # type: ignore[union-attr]
    for _, source, _, _ in sources:
        source.close()
    if encoder.wait() != 0:
        sys.exit("compose: ffmpeg failed")
    print(f"wrote {args.out} ({duration:.1f} s)")


if __name__ == "__main__":
    main()
