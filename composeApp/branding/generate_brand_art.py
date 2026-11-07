#!/usr/bin/env python3
"""Generate the Boomio brand artwork: app mark, packaging icons, wordmark.

The mark shipped here is a PLACEHOLDER -- a rounded gradient tile with a
lowercase 'b'. To replace it with real artwork, change make_mark() (or paste a
1024x1024 transparent PNG over what it returns) and re-run:

    python3 composeApp/branding/generate_brand_art.py

Palettes are per theme and match the icon options the settings screen offers,
so a new theme is one entry in PALETTES. Requires Pillow and Quicksand Bold
(override the font path with BOOMIO_FONT).
"""
import os
from PIL import Image, ImageDraw, ImageFont, ImageChops

ROOT = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", ".."))
ICONS = os.path.join(ROOT, "composeApp/src/desktopMain/resources/icons")
DRAW = os.path.join(ROOT, "composeApp/src/commonMain/composeResources/drawable")
FONT = os.environ.get("BOOMIO_FONT", "/usr/share/fonts/truetype/quicksand/Quicksand-Bold.ttf")

SS = 4                       # supersample factor
SIZE = 1024 * SS
FONT = "/usr/share/fonts/truetype/quicksand/Quicksand-Bold.ttf"

PALETTES = {
    "original":    ((35, 171, 210), (192, 94, 254)),
    "arctic_blue": ((56, 202, 242), (83, 113, 255)),
    "emerald":     ((93, 222, 115), (31, 195, 133)),
    "rose_gold":   ((207, 79, 241), (255, 152, 133)),
    "copper":      ((193, 82, 57), (255, 188, 103)),
    "graphite":    ((224, 224, 224), (165, 165, 165)),
    "gold":        ((236, 181, 36), (255, 213, 76)),
}


def lerp(a, b, t):
    return tuple(int(round(a[i] + (b[i] - a[i]) * t)) for i in range(3))


def brighten(c, k):
    return tuple(min(255, int(round(v + (255 - v) * k))) for v in c)


def diag_gradient(size, top, bot):
    """135deg linear gradient: top-left -> bottom-right.

    Computed on a small grid and resampled up -- the ramp is linear, so
    upscaling it costs nothing visually and keeps this fast without numpy."""
    n = 384
    mid = brighten(lerp(top, bot, 0.5), 0.06)
    stops = [(0.0, top), (0.55, mid), (1.0, bot)]
    small = Image.new("RGB", (n, n))
    p = small.load()
    cache = {}
    d = 2 * (n - 1)
    for y in range(n):
        for x in range(n):
            key = int(round((x + y) / d * 255))
            c = cache.get(key)
            if c is None:
                t = key / 255.0
                for i in range(len(stops) - 1):
                    t0, c0 = stops[i]
                    t1, c1 = stops[i + 1]
                    if t0 <= t <= t1:
                        c = lerp(c0, c1, (t - t0) / (t1 - t0))
                        break
                else:
                    c = bot
                cache[key] = c
            p[x, y] = c
    return small.resize((size, size), Image.BICUBIC)


def tile_mask(size):
    """Rounded square with a comfortable macOS/Windows margin."""
    m = Image.new("L", (size, size), 0)
    d = ImageDraw.Draw(m)
    pad = int(size * 0.055)
    r = int((size - 2 * pad) * 0.235)
    d.rounded_rectangle([pad, pad, size - pad - 1, size - pad - 1], radius=r, fill=255)
    return m


def sheen(size):
    """Soft white highlight from the top-left corner, to stop the tile
    reading as flat -- the Nuvio mark it replaces is lit from there too."""
    n = 256
    g = Image.new("L", (n, n))
    p = g.load()
    d = 2 * (n - 1)
    for y in range(n):
        for x in range(n):
            p[x, y] = int(round((1 - (x + y) / d) * 255))  # bright at top-left
    return g.resize((size, size), Image.BICUBIC).point(lambda v: int(v * 0.16))


def glyph_layer(size):
    """White lowercase 'b', optically centred on its ink box."""
    layer = Image.new("L", (size, size), 0)
    d = ImageDraw.Draw(layer)
    fs = int(size * 0.48)
    font = ImageFont.truetype(FONT, fs)
    box = d.textbbox((0, 0), "b", font=font)
    tw, th = box[2] - box[0], box[3] - box[1]
    # lowercase b sits high in its em box; lift it slightly so the ink,
    # not the metrics, is what the eye centres on
    x = (size - tw) / 2 - box[0]
    y = (size - th) / 2 - box[1] + th * 0.045
    d.text((x, y), "b", font=font, fill=255)
    return layer


def ink_for(theme):
    """White on the saturated palettes, near-black where the tile itself is
    light (graphite, gold) -- white-on-light-grey is unreadable at 16px."""
    top, bot = PALETTES[theme]
    mid = lerp(top, bot, 0.5)
    lum = 0.299 * mid[0] + 0.587 * mid[1] + 0.114 * mid[2]
    return (255, 255, 255) if lum <= 175 else (31, 31, 36)


def make_mark(theme):
    top, bot = PALETTES[theme]
    grad = diag_gradient(SIZE, top, bot)
    mask = tile_mask(SIZE)

    ink = Image.new("RGB", (SIZE, SIZE), ink_for(theme))
    mark = Image.composite(ink, grad, glyph_layer(SIZE))

    lit = Image.new("RGB", (SIZE, SIZE), (255, 255, 255))
    mark = Image.composite(lit, mark, ImageChops.multiply(sheen(SIZE), mask))

    out = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
    out.paste(mark, (0, 0), mask)
    return out.resize((1024, 1024), Image.LANCZOS)


def make_wordmark(theme):
    W, H = 1085, 344
    canvas = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    mark = make_mark(theme)

    th = 300
    m = mark.resize((th, th), Image.LANCZOS)
    canvas.alpha_composite(m, (6, (H - th) // 2))

    d = ImageDraw.Draw(canvas)
    text = "boomio"
    left = 6 + th + 36
    avail = W - left - 40

    # Size on ink height first (the wordmark should sit inside the mark's
    # height, not tower over it), then shrink if six lowercase letters would
    # run past the right edge.
    target_h = 134
    fs = 40
    font = ImageFont.truetype(FONT, fs)
    while True:
        box = d.textbbox((0, 0), text, font=font)
        h = box[3] - box[1]
        if h >= target_h or fs > 400:
            break
        fs += 2
        font = ImageFont.truetype(FONT, fs)
    box = d.textbbox((0, 0), text, font=font)
    tw = box[2] - box[0]
    if tw > avail:
        fs = int(fs * avail / tw)
        font = ImageFont.truetype(FONT, fs)
        box = d.textbbox((0, 0), text, font=font)
        tw = box[2] - box[0]
    tht = box[3] - box[1]
    x = left + (avail - tw) / 2 - box[0]
    y = (H - tht) / 2 - box[1]
    d.text((x, y), text, font=font, fill=(255, 255, 255, 255))
    return canvas

ICO_SIZES = [(16, 16), (24, 24), (32, 32), (48, 48), (64, 64), (128, 128), (256, 256)]
ICNS_SIZES = [(16, 16), (32, 32), (64, 64), (128, 128), (256, 256), (512, 512), (1024, 1024)]

written = []


def save_all(mark, stem_dir, stem):
    png = os.path.join(stem_dir, stem + ".png")
    mark.resize((256, 256), Image.LANCZOS).save(png)
    written.append(png)

    ico = os.path.join(stem_dir, stem + ".ico")
    mark.save(ico, format="ICO", sizes=ICO_SIZES)
    written.append(ico)

    icns = os.path.join(stem_dir, stem + ".icns")
    mark.save(icns, format="ICNS", sizes=ICNS_SIZES)
    written.append(icns)


for theme in PALETTES:
    mark = make_mark(theme)
    word = make_wordmark(theme)

    # gold is a wordmark-only theme (AppTheme.GOLD has no AppIconOption)
    if theme != "gold":
        # settings picker previews: opaque tile on black, and transparent
        prep = os.path.join(DRAW, f"app_icon_{theme}.png")
        small = mark.resize((256, 256), Image.LANCZOS)
        bg = Image.new("RGB", (256, 256), (0, 0, 0))
        bg.paste(small, (0, 0), small)
        bg.save(prep)
        written.append(prep)

        tr = os.path.join(DRAW, f"app_icon_{theme}_transparent.png")
        small.save(tr)
        written.append(tr)

        save_all(mark, ICONS, f"app-icon-{theme}-transparent")

    wm = os.path.join(DRAW, f"app_logo_wordmark_{theme}.png")
    word.save(wm)
    written.append(wm)

    if theme == "original":
        base = os.path.join(DRAW, "app_logo_wordmark.png")
        word.save(base)
        written.append(base)

# the icon jpackage plants on the installer, Start menu and Dock
save_all(make_mark("original"), ICONS, "boomio-app-icon-transparent")

# retire the upstream files
for name in ("nuvio-app-icon-transparent.png", "nuvio-app-icon-transparent.ico",
             "nuvio-app-icon-transparent.icns"):
    p = os.path.join(ICONS, name)
    if os.path.exists(p):
        os.remove(p)

print("wrote", len(written), "files")
