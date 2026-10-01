"""Generate the app's geometric icon without external dependencies."""
from pathlib import Path
import json
import struct
import zlib

root = Path(__file__).resolve().parents[1] / "App/Assets.xcassets"
directory = root / "AppIcon.appiconset"
directory.mkdir(parents=True, exist_ok=True)
(root / "Contents.json").write_text(json.dumps({"info": {"author": "xcode", "version": 1}}))
(directory / "Contents.json").write_text(json.dumps({"images": [{"filename": "Icon.png", "idiom": "universal", "platform": "ios", "size": "1024x1024"}], "info": {"author": "xcode", "version": 1}}))
bars = [(206, 520, 346, 786), (442, 372, 582, 786), (678, 252, 818, 786)]


def inside_bar(x, y, rectangle):
    left, top, right, bottom = rectangle
    if not (left <= x <= right and top <= y <= bottom):
        return False
    closest_x = min(right - 32, max(left + 32, x))
    closest_y = min(bottom - 32, max(top + 32, y))
    return (x - closest_x) ** 2 + (y - closest_y) ** 2 <= 32 ** 2


pixels = bytearray()
for y in range(1024):
    pixels.append(0)
    for x in range(1024):
        color = (55, 94, 216)
        if any(inside_bar(x, y, bar) for bar in bars):
            color = (255, 255, 255)
        if (x - 824) ** 2 + (y - 190) ** 2 <= 46 ** 2:
            color = (111, 227, 190)
        pixels.extend(color)


def chunk(kind, data):
    return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data) & 0xFFFFFFFF)


png = b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", 1024, 1024, 8, 2, 0, 0, 0))
png += chunk(b"IDAT", zlib.compress(bytes(pixels), 9)) + chunk(b"IEND", b"")
(directory / "Icon.png").write_bytes(png)
print("Generated", directory / "Icon.png")
