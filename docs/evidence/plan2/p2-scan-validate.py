#!/usr/bin/env python3
"""Plan 2 Task 6 WAV 头校验器：exit code 就是结论（0 绿 / 非 0 红）。

判据（三条全过才算绿）：
  1. offset 4..8  == len(file) - 8    且 != 0xFFFFFFFF
  2. offset 40..44 == len(file) - 44   且 != 0xFFFFFFFF
  3. `ffmpeg -v warning -i x.wav -f null -` 的 stderr 为空
     （占位头会打 `[wav @ 0x...] Ignoring maximum wav data size`，非空即红）
  4. ffprobe duration 与 (len-44)/32 ms 差 < 100ms

用法：
  python p2-scan-validate.py <file.wav> [--label before]
  echo $?    # 0 = 绿，1 = 红
"""

import argparse
import json
import os
import re
import struct
import subprocess
import sys

# ffmpeg 9.0.1 对**任何**合法的 mono PCM WAV 都会在 warning 级别打这一行
# （WAV 头里没有 channel layout）。它不是文件缺陷，必须放行，否则所有绿样本都红。
# 其余任何 stderr 行仍然判红——占位头的 `Ignoring maximum wav data size` 在 before 上照红。
BENIGN_STDERR = re.compile(r"^\[.*\] Guessed Channel Layout: mono$")

HEADER = 44
BYTES_PER_MS = 32
PLACEHOLDER = 0xFFFFFFFF


def u32(data: bytes, offset: int) -> int:
    return struct.unpack_from("<I", data, offset)[0]


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("wav")
    ap.add_argument("--label", default="")
    ap.add_argument("--ffmpeg", default="ffmpeg")
    ap.add_argument("--ffprobe", default="ffprobe")
    args = ap.parse_args()

    if not os.path.isfile(args.wav):
        print(f"FAIL missing file: {args.wav}")
        return 1

    data = open(args.wav, "rb").read()
    size = len(data)
    print(f"file: {args.wav}")
    print(f"bytes: {size}")
    print(f"hex head: {data[:48].hex(' ')}")

    failures = []

    if size < HEADER:
        print(f"FAIL size {size} < {HEADER} bytes: no usable WAV header")
        return 1

    riff = u32(data, 4)
    data_size = u32(data, 40)
    print(f"riff_size@4: 0x{riff:08x} ({'PLACEHOLDER' if riff == PLACEHOLDER else riff})")
    print(f"data_size@40: 0x{data_size:08x} ({'PLACEHOLDER' if data_size == PLACEHOLDER else data_size})")
    print(f"expect riff: {size - 8}  expect data: {size - HEADER}")

    if riff == PLACEHOLDER:
        failures.append("riff_size is 0xFFFFFFFF (placeholder header)")
    elif riff != size - 8:
        failures.append(f"riff_size {riff} != len-8 {size - 8}")

    if data_size == PLACEHOLDER:
        failures.append("data_size is 0xFFFFFFFF (placeholder header)")
    elif data_size != size - HEADER:
        failures.append(f"data_size {data_size} != len-44 {size - HEADER}")

    ff = subprocess.run(
        [args.ffmpeg, "-v", "warning", "-i", args.wav, "-f", "null", "-"],
        capture_output=True,
        text=True,
    )
    stderr = ff.stderr.strip()
    noise = [ln for ln in stderr.splitlines() if ln.strip() and not BENIGN_STDERR.match(ln.strip())]
    print(f"ffmpeg stderr: {'<empty>' if not stderr else ''}")
    if stderr:
        for line in stderr.splitlines():
            allowed = " (allowed: benign mono layout notice)" if BENIGN_STDERR.match(line.strip()) else ""
            print(f"  | {line}{allowed}")
    if noise:
        failures.append("ffmpeg stderr contains non-benign lines")
    if ff.returncode != 0:
        failures.append(f"ffmpeg exit {ff.returncode}")

    probe = subprocess.run(
        [
            args.ffprobe,
            "-v",
            "error",
            "-show_entries",
            "format=duration,size",
            "-of",
            "json",
            args.wav,
        ],
        capture_output=True,
        text=True,
    )
    duration_ms = None
    if probe.returncode == 0 and probe.stdout.strip():
        try:
            fmt = json.loads(probe.stdout)["format"]
            duration_ms = round(float(fmt["duration"]) * 1000)
        except (KeyError, ValueError, json.JSONDecodeError):
            duration_ms = None
    expect_ms = (size - HEADER) // BYTES_PER_MS
    print(f"ffprobe duration: {duration_ms} ms  expected: {expect_ms} ms")
    if duration_ms is None:
        failures.append("ffprobe could not report duration")
    elif abs(duration_ms - expect_ms) >= 100:
        failures.append(f"duration {duration_ms}ms differs from {expect_ms}ms by >=100ms")
    print(f"cross-check: ({size}-44)//32 = {expect_ms}")

    if failures:
        print(f"RESULT[{args.label}]: RED (exit 1)")
        for f in failures:
            print(f"  - {f}")
        return 1
    print(f"RESULT[{args.label}]: GREEN (exit 0)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
