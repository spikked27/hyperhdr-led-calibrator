#!/usr/bin/env python3
"""Generate the Beta 9.3 calibration video with robust machine-readable step markers.

Requires ffmpeg in PATH. Output is 1920x1080 H.264 MP4 by default.
The first 15 seconds are unmarked BLACK so the user can start playback, finish the app's framing
countdown, and let the app acquire the TV border while HyperHDR backlights are WHITE. Once the
border locks, the app turns the LEDs off before the first marked WHITE patch appears.

Beta 9.3 extends final BLACK to four minutes so the app can characterize raw LED output and then
physically validate both the currently installed ICE calibration and the newly solved candidate.
The complete video is six minutes long.

The center ~80% of every marked screen remains a uniform calibration field; the marker is confined
to the top-left edge and is outside the app's inset RAW measurement ROI.
"""

from __future__ import annotations

import argparse
import subprocess
import tempfile
from pathlib import Path

WIDTH = 1920
HEIGHT = 1080
FPS = 30
LEAD_IN = 15
PATCH_SECONDS = 15
FINAL_BLACK = 240

PATCHES = [
    ("white", (255, 255, 255)),
    ("red", (255, 0, 0)),
    ("green", (0, 255, 0)),
    ("blue", (0, 0, 255)),
    ("cyan", (0, 255, 255)),
    ("magenta", (255, 0, 255)),
    ("yellow", (255, 255, 0)),
    ("black", (0, 0, 0)),
]


def ppm_frame(path: Path, rgb: tuple[int, int, int], step: int | None) -> None:
    data = bytearray(rgb * (WIDTH * HEIGHT))
    if step is not None:
        # Must match VideoSyncAnalyzer.decodeMarker(). Four bit-pairs occupy x=5.5..55.5% of
        # the detected TV width and y=5.5..18% of TV height. Pair 0 is fixed 1; pairs 1..3
        # encode the step index LSB first. 1 = WHITE|BLACK, 0 = BLACK|WHITE.
        marker_left = int(WIDTH * 0.055)
        marker_top = int(HEIGHT * 0.055)
        marker_width = int(WIDTH * 0.50)
        marker_height = int(HEIGHT * 0.125)
        bits = [1, step & 1, (step >> 1) & 1, (step >> 2) & 1]
        for pair, bit in enumerate(bits):
            x0 = int(marker_left + pair * marker_width / 4)
            x1 = int(marker_left + (pair + 0.5) * marker_width / 4)
            x2 = int(marker_left + (pair + 1.0) * marker_width / 4)
            first = (255, 255, 255) if bit else (0, 0, 0)
            second = (0, 0, 0) if bit else (255, 255, 255)
            paint_rect(data, x0, marker_top, x1, marker_top + marker_height, first)
            paint_rect(data, x1, marker_top, x2, marker_top + marker_height, second)

    header = f"P6\n{WIDTH} {HEIGHT}\n255\n".encode("ascii")
    path.write_bytes(header + data)


def paint_rect(data: bytearray, left: int, top: int, right: int, bottom: int, rgb: tuple[int, int, int]) -> None:
    row_pixel = bytes(rgb)
    row = row_pixel * max(0, right - left)
    for y in range(max(0, top), min(HEIGHT, bottom)):
        start = (y * WIDTH + max(0, left)) * 3
        data[start : start + len(row)] = row


def run(output: Path) -> None:
    with tempfile.TemporaryDirectory(prefix="beta93-video-") as td:
        root = Path(td)
        segments: list[tuple[Path, int]] = []
        lead = root / "lead.ppm"
        ppm_frame(lead, (0, 0, 0), None)
        segments.append((lead, LEAD_IN))

        for step, (_, rgb) in enumerate(PATCHES):
            frame = root / f"patch-{step}.ppm"
            ppm_frame(frame, rgb, step)
            seconds = FINAL_BLACK if step == len(PATCHES) - 1 else PATCH_SECONDS
            segments.append((frame, seconds))

        inputs: list[str] = []
        filters: list[str] = []
        labels: list[str] = []
        for i, (frame, seconds) in enumerate(segments):
            inputs += ["-loop", "1", "-framerate", str(FPS), "-t", str(seconds), "-i", str(frame)]
            filters.append(f"[{i}:v]format=yuv420p,setsar=1[v{i}]")
            labels.append(f"[v{i}]")
        filters.append("".join(labels) + f"concat=n={len(segments)}:v=1:a=0[outv]")

        cmd = [
            "ffmpeg", "-y", *inputs,
            "-filter_complex", ";".join(filters),
            "-map", "[outv]",
            "-c:v", "libx264",
            "-preset", "medium",
            "-crf", "12",
            "-pix_fmt", "yuv420p",
            "-movflags", "+faststart",
            str(output),
        ]
        subprocess.run(cmd, check=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("output", nargs="?", type=Path, default=Path("HyperHDR_LED_Calibration_Beta9.3.mp4"))
    args = parser.parse_args()
    run(args.output)
    print(args.output)
