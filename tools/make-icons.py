"""Builds the Android and Play icons from docs/brand/app-icon-source.webp.

The source is the full icon: a cyan meter glyph with an orange dot on a navy rounded square.
This script lifts the glyph off the navy (keeping anti-aliased edges as transparency) and
writes:
- android/src/main/res/mipmap-*/ic_launcher_foreground.png   adaptive icon foreground (108 dp)
- android/src/main/res/mipmap-*/ic_launcher_monochrome.png   themed icon (Android 13+)
- android/src/main/res/drawable-*/ic_notification.png        status bar icon (24 dp, white)
- docs/brand/play-icon-512.png                               Google Play store icon
The background colour lives in res/values/ic_launcher_colors.xml.

Usage: python tools/make-icons.py   (needs Pillow)
"""
from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parent.parent
SOURCE = ROOT / "docs/brand/app-icon-source.webp"
RES = ROOT / "android/src/main/res"

NAVY = (2, 26, 73)
CYAN = (30, 246, 221)
ORANGE = (254, 182, 51)
# The glyph's box inside the 1254 px source, with a little margin.
GLYPH_BOX = (350, 120, 905, 1085)

DENSITIES = {"mdpi": 1.0, "hdpi": 1.5, "xhdpi": 2.0, "xxhdpi": 3.0, "xxxhdpi": 4.0}
ADAPTIVE_DP = 108
# Glyph height inside the 108 dp canvas: keeps it within the 66 dp safe zone of round masks.
GLYPH_HEIGHT_DP = 58
NOTIFICATION_DP = 24
NOTIFICATION_GLYPH_DP = 22


def lift_glyph() -> Image.Image:
    """The glyph on transparency: each pixel is a mix of navy and either cyan or orange."""
    src = Image.open(SOURCE).convert("RGB").crop(GLYPH_BOX)
    out = Image.new("RGBA", src.size)
    px, opx = src.load(), out.load()
    for y in range(src.height):
        for x in range(src.width):
            p = px[x, y]
            best = None
            for colour in (CYAN, ORANGE):
                # Fraction of `colour` over navy, by projecting onto the navy→colour line.
                d = [c - n for c, n in zip(colour, NAVY)]
                v = [c - n for c, n in zip(p, NAVY)]
                a = sum(i * j for i, j in zip(v, d)) / sum(i * i for i in d)
                err = sum((v[i] - a * d[i]) ** 2 for i in range(3))
                if best is None or err < best[0]:
                    best = (err, max(0.0, min(1.0, a)), colour)
            _, alpha, colour = best
            opx[x, y] = (*colour, round(alpha * 255))
    return out


def place(glyph: Image.Image, canvas_px: int, glyph_height_px: float, colour=None, background=None) -> Image.Image:
    g = glyph
    if colour is not None:
        g = Image.new("RGBA", glyph.size, (*colour, 255))
        g.putalpha(glyph.getchannel("A"))
    scale = glyph_height_px / g.height
    g = g.resize((max(1, round(g.width * scale)), max(1, round(g.height * scale))), Image.LANCZOS)
    canvas = Image.new("RGBA", (canvas_px, canvas_px), (*background, 255) if background else (0, 0, 0, 0))
    canvas.alpha_composite(g, ((canvas_px - g.width) // 2, (canvas_px - g.height) // 2))
    return canvas


def main():
    glyph = lift_glyph()
    for name, factor in DENSITIES.items():
        mipmap = RES / f"mipmap-{name}"
        mipmap.mkdir(parents=True, exist_ok=True)
        size, height = round(ADAPTIVE_DP * factor), GLYPH_HEIGHT_DP * factor
        place(glyph, size, height).save(mipmap / "ic_launcher_foreground.png", optimize=True)
        place(glyph, size, height, colour=(255, 255, 255)).save(mipmap / "ic_launcher_monochrome.png", optimize=True)
        drawable = RES / f"drawable-{name}"
        drawable.mkdir(parents=True, exist_ok=True)
        place(glyph, round(NOTIFICATION_DP * factor), NOTIFICATION_GLYPH_DP * factor, colour=(255, 255, 255)).save(
            drawable / "ic_notification.png", optimize=True,
        )
    # Play: full square (Play applies its own mask), glyph at the source's proportion.
    place(glyph, 512, 512 * 0.70, background=NAVY).convert("RGB").save(ROOT / "docs/brand/play-icon-512.png", optimize=True)
    print("icons written")


if __name__ == "__main__":
    main()
