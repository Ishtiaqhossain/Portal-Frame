#!/usr/bin/env python3
"""Generate sample slide PNGs (vertical gradient + big white slide number).
Pure stdlib (zlib + struct), no PIL required. Output: app/assets/slides/."""
import zlib, struct, os, sys

W, H = 1280, 800  # landscape, matching Portal Go's natural orientation
OUT = sys.argv[1] if len(sys.argv) > 1 else os.path.join(
    os.path.dirname(__file__), "..", "app", "assets", "slides")
OUT = os.path.abspath(OUT)
os.makedirs(OUT, exist_ok=True)

# 3x5 block font, just the digits we need.
FONT = {
    '1': ["010", "110", "010", "010", "111"],
    '2': ["111", "001", "111", "100", "111"],
    '3': ["111", "001", "111", "001", "111"],
    '4': ["101", "101", "111", "001", "001"],
    '5': ["111", "100", "111", "001", "111"],
}

SLIDES = [
    ("01_sunrise", (255, 94, 77),  (255, 206, 84)),
    ("02_ocean",   (0, 121, 150),  (0, 210, 180)),
    ("03_forest",  (34, 99, 53),   (160, 220, 90)),
    ("04_violet",  (90, 40, 140),  (220, 120, 200)),
    ("05_slate",   (40, 44, 60),   (120, 130, 160)),
]


def chunk(typ, data):
    return (struct.pack(">I", len(data)) + typ + data +
            struct.pack(">I", zlib.crc32(typ + data) & 0xffffffff))


def make_png(name, c0, c1, digit):
    # Vertical gradient => each row is a single uniform color (fast path).
    rows = []
    for y in range(H):
        t = y / (H - 1)
        r = int(c0[0] + (c1[0] - c0[0]) * t)
        g = int(c0[1] + (c1[1] - c0[1]) * t)
        b = int(c0[2] + (c1[2] - c0[2]) * t)
        rows.append(bytearray(bytes((r, g, b)) * W))

    # Overlay the big white digit, centered-ish.
    glyph = FONT[digit]
    scale = 70
    gw, gh = 3 * scale, 5 * scale
    x0 = (W - gw) // 2
    y0 = (H - gh) // 2
    for ry, line in enumerate(glyph):
        for cx, bit in enumerate(line):
            if bit != '1':
                continue
            for dy in range(scale):
                Y = y0 + ry * scale + dy
                row = rows[Y]
                for dx in range(scale):
                    X = x0 + cx * scale + dx
                    row[X * 3:X * 3 + 3] = b"\xff\xff\xff"

    raw = bytearray()
    for row in rows:
        raw.append(0)        # filter type 0 (None) per scanline
        raw.extend(row)
    comp = zlib.compress(bytes(raw), 6)

    sig = b"\x89PNG\r\n\x1a\n"
    ihdr = struct.pack(">IIBBBBB", W, H, 8, 2, 0, 0, 0)  # 8-bit, truecolor RGB
    png = sig + chunk(b"IHDR", ihdr) + chunk(b"IDAT", comp) + chunk(b"IEND", b"")
    path = os.path.join(OUT, name + ".png")
    with open(path, "wb") as f:
        f.write(png)
    return path


for i, (name, c0, c1) in enumerate(SLIDES, start=1):
    p = make_png(name, c0, c1, str(i))
    print("wrote", p)

print("done:", len(SLIDES), "slides ->", OUT)
