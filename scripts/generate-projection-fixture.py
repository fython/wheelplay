#!/usr/bin/env python3
"""Generate our own deterministic H.264 diagnostic fixture. Requires ffmpeg with libx264."""
import pathlib
import re
import struct
import subprocess

raw = subprocess.check_output([
    "ffmpeg", "-v", "error", "-f", "lavfi", "-i", "testsrc2=size=320x180:rate=15",
    "-frames:v", "30", "-c:v", "libx264", "-profile:v", "baseline", "-preset", "veryfast",
    "-tune", "zerolatency", "-x264-params", "aud=1:keyint=15:min-keyint=15:scenecut=0",
    "-f", "h264", "pipe:1",
])
nals = [n for n in re.split(b"\x00\x00\x00?\x01", raw) if n]
sps = next(n for n in nals if n[0] & 31 == 7)
pps = next(n for n in nals if n[0] & 31 == 8)
config = b"\x01" + sps[1:4] + b"\xff\xe1" + struct.pack(">H", len(sps)) + sps + b"\x01" + struct.pack(">H", len(pps)) + pps
frames = []
for nal in nals:
    if nal[0] & 31 == 9:
        frames.append(bytearray())
    frames[-1].extend(b"\0\0\0\1" + nal)
target = pathlib.Path(__file__).resolve().parents[1] / "common/src/main/assets/projection/demo.h264clip"
target.parent.mkdir(parents=True, exist_ok=True)
with target.open("wb") as output:
    output.write(struct.pack(">I", len(config)) + config + struct.pack(">I", len(frames)))
    for frame in frames:
        output.write(struct.pack(">I", len(frame)) + frame)
print(f"{len(frames)} access units, {target.stat().st_size} bytes")
