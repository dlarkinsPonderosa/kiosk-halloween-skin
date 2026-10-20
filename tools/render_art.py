#!/usr/bin/env python3
"""Draw the plugin icon and the Skin picker thumbnail.

Uses the face geometry of src/ca/ponderosa/jackolantern/Pumpkin.java, frozen
mid-answer with the mouth part open, in the In the dark style. Plain Python,
no imaging library: shapes are filled with anti-aliased scanlines and the
candle glow is a blurred copy of them.

    python3 tools/render_art.py
"""
import math
import struct
import zlib
from pathlib import Path

ASSETS = Path(__file__).resolve().parents[1] / 'assets'
DOCS = Path(__file__).resolve().parents[1] / 'docs'

# Pumpkin.java
TEETH = [-0.7, -0.56, -0.42, -0.28, -0.14, 0.0, 0.14, 0.28, 0.42, 0.56, 0.7]
CORNER_LEFT = [-0.94, -0.86, -0.78]
BAND = 0.06
CANDLE = (255, 212, 92)
RIM = (255, 120, 20)
SPILL = (255, 140, 30)


def face(cx, cy, r, open_):
    """The eyes, nose and mouth as closed polygons."""
    shapes = []
    for side in (-1, 1):
        ex, ey = cx + side * 0.36 * r, cy - 0.2 * r
        e = r * 0.21
        ox, oy = ex + side * 1.05 * e, ey - 0.62 * e
        ix, iy = ex - side * 1.0 * e, ey + 0.22 * e
        qx, qy = ex + side * 0.15 * e, ey + 1.0 * e
        eye = [(ox, oy), (ix, iy)]
        for k in range(1, 24):
            t = k / 24
            eye.append(((1 - t) ** 2 * ix + 2 * (1 - t) * t * qx + t * t * ox,
                        (1 - t) ** 2 * iy + 2 * (1 - t) * t * qy + t * t * oy))
        shapes.append(eye)
    shapes.append([(cx, cy), (cx - 0.08 * r, cy + 0.12 * r), (cx + 0.08 * r, cy + 0.12 * r)])

    half, base = 0.72 * r, cy + 0.3 * r
    upper = lambda u: base + 0.1 * r * (1 - u * u) - 0.16 * r * u ** 4
    gap = lambda u: (0.16 * r + 0.32 * r * open_) * math.sqrt(max(0.0, 1 - u * u))
    tooth = lambda u: min(0.14 * r * (0.6 + 0.4 * math.sqrt(max(0.0, 1 - u * u))), max(0.0, gap(u) - BAND * r))
    m = [(cx - half, upper(-1))]
    m += [(cx + u * half, upper(u)) for u in CORNER_LEFT]
    m += [(cx + u * half, upper(u) + (tooth(u) if i % 2 == 0 else 0)) for i, u in enumerate(TEETH)]
    m += [(cx - u * half, upper(-u)) for u in reversed(CORNER_LEFT)]
    m.append((cx + half, upper(1)))
    m += [(cx - u * half, upper(-u) + gap(-u)) for u in CORNER_LEFT]
    m += [(cx + u * half, upper(u) + gap(u) - (tooth(u) if i % 2 == 1 else 0)) for i, u in reversed(list(enumerate(TEETH)))]
    m += [(cx + u * half, upper(u) + gap(u)) for u in reversed(CORNER_LEFT)]
    shapes.append(m)
    return shapes


def coverage(shapes, w, h, ss=5):
    """Fraction of each pixel inside any shape, by even-odd scanlines with ss rows per pixel."""
    cov = [0.0] * (w * h)
    for y in range(h):
        row = y * w
        for k in range(ss):
            sy = y + (k + 0.5) / ss
            for poly in shapes:
                xs = []
                n = len(poly)
                for i in range(n):
                    x0, y0 = poly[i]
                    x1, y1 = poly[(i + 1) % n]
                    if (y0 <= sy < y1) or (y1 <= sy < y0):
                        xs.append(x0 + (sy - y0) * (x1 - x0) / (y1 - y0))
                xs.sort()
                for a, b in zip(xs[0::2], xs[1::2]):
                    a, b = max(0.0, a), min(float(w), b)
                    for px in range(int(a), min(w, int(math.ceil(b)))):
                        cov[row + px] += (min(b, px + 1) - max(a, px)) / ss
    return [min(1.0, c) for c in cov]


def blur(src, w, h, radius, passes=3):
    """Three box blurs, close to a gaussian."""
    radius = max(1, int(radius))
    cur = src
    for _ in range(passes):
        tmp = [0.0] * (w * h)
        span = 2 * radius + 1
        for y in range(h):
            row = y * w
            acc = sum(cur[row + min(max(i, 0), w - 1)] for i in range(-radius, radius + 1))
            for x in range(w):
                tmp[row + x] = acc / span
                acc += cur[row + min(x + radius + 1, w - 1)] - cur[row + max(x - radius, 0)]
        out = [0.0] * (w * h)
        for x in range(w):
            acc = sum(tmp[min(max(i, 0), h - 1) * w + x] for i in range(-radius, radius + 1))
            for y in range(h):
                out[y * w + x] = acc / span
                acc += tmp[min(y + radius + 1, h - 1) * w + x] - tmp[max(y - radius, 0) * w + x]
        cur = out
    return cur


def render(w, h, r, cy, open_=0.35):
    shapes = face(w / 2, cy, r, open_)
    core = coverage(shapes, w, h)
    rim = blur(core, w, h, r * 0.035)
    glow = blur(core, w, h, r * 0.1)
    pixels = bytearray()
    for y in range(h):
        pixels.append(0)
        for x in range(w):
            i = y * w + x
            g = min(1.0, glow[i] * 1.4) * 0.5
            col = [c * g for c in SPILL]
            a = min(1.0, rim[i] * 2.2) * 0.8
            col = [c * (1 - a) + d * a for c, d in zip(col, RIM)]
            # The candlelit inside, deeper orange where it meets the cut edge.
            depth = max(0.0, min(1.0, (rim[i] - 0.45) * 2.5))
            inside = [d + (c - d) * depth for c, d in zip(CANDLE, RIM)]
            a = core[i]
            col = [c * (1 - a) + d * a for c, d in zip(col, inside)]
            pixels += bytes(max(0, min(255, round(c))) for c in col)
    return pixels


def ellipse(cx, cy, rx, ry, n=96):
    return [(cx + rx * math.cos(2 * math.pi * i / n), cy + ry * math.sin(2 * math.pi * i / n)) for i in range(n)]


def body_color(x, y, gx, gy, gr):
    """Pumpkin.java's radial gradient: lit from the upper left, darker toward the edges."""
    stops = [(0, (255, 179, 71)), (0.45, (242, 128, 28)), (0.8, (194, 87, 11)), (1, (142, 59, 6))]
    d = min(1.0, math.hypot(x - gx, y - gy) / gr)
    for (p0, c0), (p1, c1) in zip(stops, stops[1:]):
        if d <= p1:
            f = (d - p0) / (p1 - p0)
            return [a + (b - a) * f for a, b in zip(c0, c1)]
    return list(stops[-1][1])


def render_whole(w, h, r, cy, open_=0.35):
    """The Whole pumpkin style: shell, stem and the lit face, on the skin's dark backdrop."""
    cx = w / 2
    hh = r * 0.8
    lobes = [(-0.5, 0.5, 0.86), (0.5, 0.5, 0.86), (-0.26, 0.52, 0.95), (0.26, 0.52, 0.95), (0.0, 0.46, 1.0)]
    lobe_cov = []
    for lx, lw, lh in lobes:
        outer = coverage([ellipse(cx + lx * r, cy, lw * r, lh * hh)], w, h)
        inner = coverage([ellipse(cx + lx * r, cy, lw * r - r * 0.02, lh * hh - r * 0.02)], w, h)
        lobe_cov.append((outer, [max(0.0, a - b) for a, b in zip(outer, inner)]))
    top = cy - hh
    stem_pts = [(cx - 0.08 * r, top + 0.14 * r)]
    for k in range(1, 13):
        t = k / 12
        stem_pts.append(((1 - t) ** 2 * (cx - 0.08 * r) + 2 * (1 - t) * t * (cx - 0.07 * r) + t * t * (cx + 0.03 * r),
                         (1 - t) ** 2 * (top + 0.14 * r) + 2 * (1 - t) * t * (top - 0.16 * r) + t * t * (top - 0.33 * r)))
    stem_pts.append((cx + 0.15 * r, top - 0.27 * r))
    for k in range(1, 13):
        t = k / 12
        stem_pts.append(((1 - t) ** 2 * (cx + 0.15 * r) + 2 * (1 - t) * t * (cx + 0.06 * r) + t * t * (cx + 0.08 * r),
                         (1 - t) ** 2 * (top - 0.27 * r) + 2 * (1 - t) * t * (top - 0.13 * r) + t * t * (top + 0.14 * r)))
    stem = coverage([stem_pts], w, h)
    core = coverage(face(cx, cy, r, open_), w, h)
    cut = blur(core, w, h, r * 0.02, passes=1)
    glow = blur(core, w, h, r * 0.09)
    gx, gy, gr = cx - 0.25 * r, cy - 0.35 * r, 1.5 * r
    pixels = bytearray()
    for y in range(h):
        pixels.append(0)
        for x in range(w):
            i = y * w + x
            col = [15.0, 10.0, 20.0]
            # A warm halo behind the pumpkin.
            a = max(0.0, 1 - math.hypot(x - cx, y - cy) / (r * 1.6)) * 0.35
            col = [c * (1 - a) + d * a for c, d in zip(col, (255, 150, 40))]
            a = stem[i]
            col = [c * (1 - a) + d * a for c, d in zip(col, (90, 107, 44))]
            if any(o[i] for o, _ in lobe_cov):
                shade = body_color(x, y, gx, gy, gr)
                for outer, ring in lobe_cov:
                    a = outer[i]
                    col = [c * (1 - a) + d * a for c, d in zip(col, shade)]
                    a = ring[i] * 0.33
                    col = [c * (1 - a) + d * a for c, d in zip(col, (138, 58, 5))]
            # Candlelight spilling onto the skin, the dark cut edge, then the lit inside.
            a = min(1.0, glow[i] * 1.6) * 0.55
            col = [c * (1 - a) + d * a for c, d in zip(col, (255, 170, 60))]
            a = min(1.0, cut[i] * 3)
            col = [c * (1 - a) + d * a for c, d in zip(col, (122, 50, 5))]
            depth = max(0.0, min(1.0, (cut[i] - 0.5) * 2.5))
            inside = [d + (c - d) * depth for c, d in zip(CANDLE, RIM)]
            a = core[i]
            col = [c * (1 - a) + d * a for c, d in zip(col, inside)]
            pixels += bytes(max(0, min(255, round(c))) for c in col)
    return pixels


def png(path, w, h, raw):
    def chunk(kind, data):
        return struct.pack('>I', len(data)) + kind + data + struct.pack('>I', zlib.crc32(kind + data) & 0xFFFFFFFF)
    data = b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', w, h, 8, 2, 0, 0, 0))
    data += chunk(b'IDAT', zlib.compress(bytes(raw), 9)) + chunk(b'IEND', b'')
    path.write_bytes(data)
    print(f'{path.name}: {w}x{h}, {len(data) // 1024} KB')


if __name__ == '__main__':
    ASSETS.mkdir(exist_ok=True)
    # The face spans about 0.35 r above its centre and 0.65 r below, so lift it to sit in the middle.
    png(ASSETS / 'voice-skin.png', 640, 400, render(640, 400, 200, 200 - 0.15 * 200))
    png(ASSETS / 'icon.png', 256, 256, render(256, 256, 140, 128 - 0.15 * 140))
    # README only, so it stays out of the plugin package.
    DOCS.mkdir(exist_ok=True)
    png(DOCS / 'whole-pumpkin.png', 640, 400, render_whole(640, 400, 150, 215))
