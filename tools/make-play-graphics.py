"""Builds the Google Play feature graphic and phone screenshots.

Inputs (all in the repo):
- docs/brand/play-icon-512.png (from tools/make-icons.py)
- docs/brand/play/source/*.png: real captures of the app (emulator, 1080 × 2400)
Outputs in docs/brand/play/:
- feature-graphic.png  1024 × 500, no transparency (Play requirement)
- screenshot-1.png, screenshot-2.png  1080 × 1920 (9:16; Play allows at most 2:1)

No manufacturer names or logos (PROJECT_STATE chunk 2.8). Needs Pillow and the Windows Segoe UI
fonts (any TrueType font works: change FONT_DIR).

Usage: python tools/make-play-graphics.py
"""
from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter, ImageFont

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "docs/brand/play"
SOURCE = OUT / "source"
FONT_DIR = Path("C:/Windows/Fonts")

NAVY = (2, 26, 73)
NAVY_DEEP = (1, 16, 46)
CYAN = (30, 246, 221)
ORANGE = (254, 182, 51)
WHITE = (255, 255, 255)
MUTED = (178, 196, 222)

# Captures: drop the status bar and the gesture bar (identical dark rows top and bottom).
CROP = (0, 96, 1080, 2340)

SCREENSHOTS = [
    ("tiles-high-gas.png", "Readings rise as trainees walk into a virtual leak", "A1 and A2 alarms flash, beep and vibrate"),
    ("lcd-a2.png", "Meter screens styled like the agency's own monitors", "Each monitor's sensors and alarm levels"),
]


def font(name: str, size: int) -> ImageFont.FreeTypeFont:
    return ImageFont.truetype(str(FONT_DIR / name), size)


def gradient(size, top, bottom) -> Image.Image:
    w, h = size
    g = Image.new("RGB", (1, h))
    for y in range(h):
        t = y / max(1, h - 1)
        g.putpixel((0, y), tuple(round(a + (b - a) * t) for a, b in zip(top, bottom)))
    return g.resize((w, h))


def rounded(img: Image.Image, radius: int) -> Image.Image:
    mask = Image.new("L", img.size, 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, img.width - 1, img.height - 1), radius=radius, fill=255)
    out = img.convert("RGBA")
    out.putalpha(mask)
    return out


def phone(capture: Image.Image, height: int) -> Image.Image:
    """The capture in a simple dark phone frame (no device brand), with a soft shadow."""
    screen = capture.crop(CROP)
    scale = height / screen.height
    screen = screen.resize((round(screen.width * scale), height), Image.LANCZOS)
    bezel = max(8, height // 70)
    frame = Image.new("RGBA", (screen.width + 2 * bezel, screen.height + 2 * bezel), (18, 20, 24, 255))
    frame = rounded(frame, bezel * 4)
    frame.alpha_composite(rounded(screen, bezel * 3), (bezel, bezel))
    shadow = Image.new("RGBA", (frame.width + 80, frame.height + 80), (0, 0, 0, 0))
    ImageDraw.Draw(shadow).rounded_rectangle((40, 50, frame.width + 40, frame.height + 50), radius=bezel * 4, fill=(0, 0, 0, 140))
    shadow = shadow.filter(ImageFilter.GaussianBlur(22))
    shadow.alpha_composite(frame, (40, 40))
    return shadow


def wrap(draw: ImageDraw.ImageDraw, text: str, f: ImageFont.FreeTypeFont, width: int) -> list[str]:
    lines, line = [], ""
    for word in text.split():
        trial = f"{line} {word}".strip()
        if draw.textlength(trial, font=f) <= width:
            line = trial
        else:
            lines.append(line)
            line = word
    return lines + [line]


def screenshot(source: str, title: str, subtitle: str, out: Path):
    W, H = 1080, 1920
    canvas = gradient((W, H), NAVY, NAVY_DEEP).convert("RGBA")
    d = ImageDraw.Draw(canvas)
    y = 90
    for line in wrap(d, title, font("segoeuib.ttf", 64), W - 140):
        d.text((W // 2, y), line, font=font("segoeuib.ttf", 64), fill=WHITE, anchor="mt")
        y += 80
    y += 10
    for line in wrap(d, subtitle, font("segoeui.ttf", 38), W - 180):
        d.text((W // 2, y), line, font=font("segoeui.ttf", 38), fill=MUTED, anchor="mt")
        y += 50
    shot = phone(Image.open(SOURCE / source).convert("RGB"), H - y - 110)
    canvas.alpha_composite(shot, ((W - shot.width) // 2, y + 20))
    canvas.convert("RGB").save(out, optimize=True)


def feature_graphic(out: Path):
    W, H = 1024, 500
    canvas = gradient((W, H), NAVY, NAVY_DEEP).convert("RGBA")
    d = ImageDraw.Draw(canvas)
    # Faint signal arcs behind the phones, echoing the icon (own layer, so they blend).
    arcs = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    ad = ImageDraw.Draw(arcs)
    for r in (420, 340, 260):
        ad.arc((W - 300 - r, -60 - r, W - 300 + r, -60 + r), start=20, end=160, fill=(*CYAN, 38), width=9)
    canvas.alpha_composite(arcs)
    icon = Image.open(ROOT / "docs/brand/play-icon-512.png").convert("RGBA").resize((132, 132), Image.LANCZOS)
    canvas.alpha_composite(rounded(icon, 30), (60, 70))
    d.text((56, 222), "Sim Sensor", font=font("segoeuib.ttf", 74), fill=WHITE, anchor="lt")
    d.text((60, 322), "Gas detector simulator", font=font("seguisb.ttf", 32), fill=CYAN, anchor="lt")
    d.text((60, 362), "for hazmat training", font=font("seguisb.ttf", 32), fill=CYAN, anchor="lt")
    d.text((60, 420), "Virtual leaks on the map  ·  A1 / A2 alarms", font=font("segoeui.ttf", 21), fill=MUTED, anchor="lt")
    d.text((60, 450), "Live instructor console", font=font("segoeui.ttf", 21), fill=MUTED, anchor="lt")
    # Two phones on the right, slightly overlapping.
    left = phone(Image.open(SOURCE / "lcd-a2.png").convert("RGB"), 360)
    right = phone(Image.open(SOURCE / "tiles-high-gas.png").convert("RGB"), 410)
    canvas.alpha_composite(left, (W - right.width - left.width + 130, 75))
    canvas.alpha_composite(right, (W - right.width + 20, 20))
    canvas.convert("RGB").save(out, optimize=True)


def main():
    feature_graphic(OUT / "feature-graphic.png")
    for i, (source, title, subtitle) in enumerate(SCREENSHOTS, start=1):
        screenshot(source, title, subtitle, OUT / f"screenshot-{i}.png")
    for p in sorted(OUT.glob("*.png")):
        with Image.open(p) as im:
            print(p.name, im.size, im.mode)


if __name__ == "__main__":
    main()
