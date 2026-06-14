#!/usr/bin/env python3
"""Generate the launcher icon (app/res/drawable-nodpi/ic_launcher.png).
Pure stdlib (zlib + struct), no PIL. A simple "photo" motif: teal field with a
sun and two mountains — so the sideloaded app shows a real icon on the Portal
home (the launcher hides icon-less apps). Output dir overridable as argv[1]."""
import zlib, struct, os, sys

S = 192  # square icon, downscaled by the launcher as needed
OUT = sys.argv[1] if len(sys.argv) > 1 else os.path.join(
    os.path.dirname(__file__), "..", "app", "res", "drawable-nodpi")
OUT = os.path.abspath(OUT)
os.makedirs(OUT, exist_ok=True)

BG = (30, 136, 168)       # teal field
SUN = (255, 206, 84)      # warm yellow
HILL = (245, 245, 245)    # near-white mountains


def chunk(typ, data):
    return (struct.pack(">I", len(data)) + typ + data +
            struct.pack(">I", zlib.crc32(typ + data) & 0xffffffff))


def make_icon(path):
    px = [bytearray(bytes(BG) * S) for _ in range(S)]

    def put(x, y, c):
        if 0 <= x < S and 0 <= y < S:
            px[y][x * 3:x * 3 + 3] = bytes(c)

    # Sun (filled circle), upper-left.
    cx, cy, r = int(S * 0.34), int(S * 0.34), int(S * 0.13)
    for y in range(cy - r, cy + r + 1):
        for x in range(cx - r, cx + r + 1):
            if (x - cx) ** 2 + (y - cy) ** 2 <= r * r:
                put(x, y, SUN)

    # Two mountains: triangles rising to peaks, filling the lower half.
    peaks = [(int(S * 0.40), int(S * 0.40), int(S * 0.30)),   # (peak_x, peak_y, half_base)
             (int(S * 0.70), int(S * 0.52), int(S * 0.34))]
    base_y = int(S * 0.86)
    for (pxk, pyk, hb) in peaks:
        for y in range(pyk, base_y):
            t = (y - pyk) / float(base_y - pyk)
            half = int(hb * t)
            for x in range(pxk - half, pxk + half + 1):
                put(x, y, HILL)

    raw = bytearray()
    for row in px:
        raw.append(0)
        raw.extend(row)
    comp = zlib.compress(bytes(raw), 9)

    sig = b"\x89PNG\r\n\x1a\n"
    ihdr = struct.pack(">IIBBBBB", S, S, 8, 2, 0, 0, 0)  # 8-bit truecolor RGB
    png = sig + chunk(b"IHDR", ihdr) + chunk(b"IDAT", comp) + chunk(b"IEND", b"")
    with open(path, "wb") as f:
        f.write(png)
    return path


p = make_icon(os.path.join(OUT, "ic_launcher.png"))
print("wrote", p)
