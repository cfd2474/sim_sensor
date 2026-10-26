"""Builds the user manual's diagrams and the annotated Live page (docs/USER_MANUAL.md).

Outputs in docs/manual/:
- system-overview.png      the parts and the ports between them
- leak-kinds.png           round leak, plume, area, beacon
- exercise-lifecycle.png   Draft / Running / Paused / Ended, with what the phones show
- beacon-calibration.png   where to stand when calibrating, and the gas edge
- oxygen-scale.png         O₂ depletion and enrichment against the default alarms and OL
- live-annotated.png       docs/manual/live.png with numbered callouts

White backgrounds and dark ink, for printing. The numbers follow the app's defaults (O₂ range 0–25,
A1 19.5 falling, A2 23.5, enrichment at most +5: SensorCatalog, SourceValidator). Needs Pillow and
the Windows Segoe UI fonts (any TrueType font works: change FONT_DIR).

Usage: python tools/make-manual-diagrams.py
"""
import math
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "docs/manual"
FONT_DIR = Path("C:/Windows/Fonts")

INK = (22, 32, 48)
MUTED = (96, 110, 128)
LINE = (190, 200, 212)
PANEL = (244, 247, 250)
NAVY = (2, 26, 73)
TEAL = (0, 128, 128)
BLUE = (25, 118, 210)
GREEN = (46, 125, 50)
AMBER = (230, 150, 0)
RED = (211, 47, 47)
GAS = (239, 108, 0)
WHITE = (255, 255, 255)


def font(size, bold=False):
    return ImageFont.truetype(str(FONT_DIR / ("segoeuib.ttf" if bold else "segoeui.ttf")), size)


def canvas(w, h):
    img = Image.new("RGB", (w, h), WHITE)
    return img, ImageDraw.Draw(img)


def box(d, xy, title, lines=(), fill=PANEL, outline=LINE, title_fill=INK, size=34):
    d.rounded_rectangle(xy, radius=22, fill=fill, outline=outline, width=4)
    x0, y0, x1, _ = xy
    cx = (x0 + x1) / 2
    d.text((cx, y0 + 40), title, font=font(size, True), fill=title_fill, anchor="mm")
    for i, line in enumerate(lines):
        d.text((cx, y0 + 92 + i * 38), line, font=font(26), fill=MUTED, anchor="mm")


def arrow(d, a, b, fill=INK, width=6, head=22, both=False):
    d.line([a, b], fill=fill, width=width)
    for p, q in [(a, b)] + ([(b, a)] if both else []):
        ang = math.atan2(q[1] - p[1], q[0] - p[0])
        pts = [q, (q[0] - head * math.cos(ang - 0.45), q[1] - head * math.sin(ang - 0.45)),
               (q[0] - head * math.cos(ang + 0.45), q[1] - head * math.sin(ang + 0.45))]
        d.polygon(pts, fill=fill)


def dashed(d, a, b, fill=MUTED, width=4, dash=18):
    n = max(1, int(math.dist(a, b) / dash))
    for k in range(0, n, 2):
        p = (a[0] + (b[0] - a[0]) * k / n, a[1] + (b[1] - a[1]) * k / n)
        q = (a[0] + (b[0] - a[0]) * (k + 1) / n, a[1] + (b[1] - a[1]) * (k + 1) / n)
        d.line([p, q], fill=fill, width=width)


def label(d, xy, text, size=26, fill=INK, bold=False, anchor="mm", pad=10, bg=WHITE):
    f = font(size, bold)
    l, t, r, b = d.textbbox(xy, text, font=f, anchor=anchor)
    d.rounded_rectangle([l - pad, t - pad / 2, r + pad, b + pad / 2], radius=8, fill=bg)
    d.text(xy, text, font=f, fill=fill, anchor=anchor)


def phone_icon(d, cx, cy, s=1.0, screen=GREEN):
    w, h = 46 * s, 84 * s
    d.rounded_rectangle([cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2], radius=9 * s, fill=INK)
    d.rectangle([cx - w / 2 + 6 * s, cy - h / 2 + 12 * s, cx + w / 2 - 6 * s, cy + h / 2 - 14 * s], fill=screen)


def beacon_icon(d, cx, cy, r=26):
    d.ellipse([cx - r, cy - r, cx + r, cy + r], fill=NAVY, outline=TEAL, width=5)
    d.text((cx, cy), "B", font=font(int(r * 1.1), True), fill=WHITE, anchor="mm")


def gas_blob(img, shape_fn, steps=24, clip=None):
    """Gas shading: nested shapes from the edge (faint) to the source (strong), optionally clipped
    to a mask (a room's walls)."""
    for k in range(steps):
        t = 1 - k / steps  # 1 = outer edge
        layer = Image.new("L", img.size, 0)
        shape_fn(ImageDraw.Draw(layer), t, 18)
        if clip is not None:
            layer = Image.composite(layer, Image.new("L", img.size, 0), clip)
        img.paste(Image.new("RGB", img.size, GAS), (0, 0), layer)


# The model's plume constants (SourceModel: UPWIND_FRACTION, MIN_WIDTH_FRACTION).
PLUME_UPWIND_FRACTION = 0.25
PLUME_MIN_WIDTH = 0.15


def smooth(d):
    """The default fall-off (SourceModel.falloff SMOOTH): 1 at the source, 0 at the edge."""
    return 0.0 if d >= 1 else (1 - d * d) ** 2


def shade(img, reading, bbox, strength=0.85):
    """Gas shading pixel by pixel: [reading](x, y) is the fraction of the peak there (0..1)."""
    x0, y0, x1, y1 = bbox
    mask = Image.new("L", img.size, 0)
    px = mask.load()
    for y in range(max(0, y0), min(img.height, y1)):
        for x in range(max(0, x0), min(img.width, x1)):
            v = reading(x, y)
            if v > 0:
                px[x, y] = int(255 * strength * v ** 0.7)
    img.paste(Image.new("RGB", img.size, GAS), (0, 0), mask)


# ---------------------------------------------------------------------------------------------

def system_overview():
    img, d = canvas(2000, 1060)
    d.text((60, 50), "How the parts connect", font=font(46, True), fill=INK)
    d.text((60, 112), "Everything goes through the server over HTTPS. Phones and instructor computers only need outbound TCP 443.",
           font=font(28), fill=MUTED)

    sx0, sy0, sx1, sy1 = 720, 330, 1280, 690
    box(d, (sx0, sy0, sx1, sy1), "Sim Sensor server",
        ["Linux + Docker, a DNS name", "e.g. https://sim.agency.org", "", "Console website · database", "Updater · backups"],
        fill=(232, 241, 252), outline=BLUE, size=38)

    # Instructors (left).
    lx0, ly0, lx1, ly1 = 80, 380, 520, 640
    box(d, (lx0, ly0, lx1, ly1), "Instructor console", ["Any web browser", "Admins and instructors", "Build, run, debrief"])
    arrow(d, (lx1 + 10, 510), (sx0 - 10, 510), fill=BLUE, both=True)
    label(d, ((lx1 + sx0) / 2, 470), "HTTPS · TCP 443", bold=True, fill=BLUE)

    # Phones (right).
    rx0, ry0, rx1, ry1 = 1480, 380, 1920, 640
    box(d, (rx0, ry0, rx1, ry1), "Phone app", ["Android 8.0 or newer", "One per trainee", "Simulated meter"])
    for i, c in enumerate([GREEN, AMBER, RED]):
        phone_icon(d, rx0 + 150 + i * 70, ry1 + 90, 0.9, c)
    arrow(d, (sx1 + 10, 510), (rx0 - 10, 510), fill=BLUE, both=True)
    label(d, ((sx1 + rx0) / 2, 455), "Secure WebSocket", bold=True, fill=BLUE)
    label(d, ((sx1 + rx0) / 2, 560), "TCP 443", bold=True, fill=BLUE)

    # Beacons and GPS feed the phones directly.
    beacon_icon(d, 1560, 920)
    beacon_icon(d, 1660, 960)
    d.text((1720, 940), "Bluetooth beacons\n(BLE, no network)", font=font(26), fill=INK, anchor="lm")
    dashed(d, (1600, 880), (1650, 790), fill=TEAL, width=5)
    d.ellipse([1785, 210, 1835, 260], outline=INK, width=4)
    d.text((1860, 235), "GPS", font=font(28, True), fill=INK, anchor="lm")
    dashed(d, (1790, 265), (1740, 370), fill=MUTED)

    # Internet services, top.
    box(d, (560, 170, 1000, 270), "Let's Encrypt", [], size=30)
    box(d, (1040, 170, 1440, 270), "GitHub", [], size=30)
    d.text((780, 248), "certificate", font=font(22), fill=MUTED, anchor="mm")
    d.text((1240, 248), "updates · app download", font=font(22), fill=MUTED, anchor="mm")
    arrow(d, (850, 280), (850, sy0 - 8), fill=MUTED, width=4)
    label(d, (850, 302), "TCP 80 inbound", size=22, fill=MUTED)
    arrow(d, (1150, sy0 - 8), (1150, 280), fill=MUTED, width=4)
    label(d, (1150, 302), "outbound 443", size=22, fill=MUTED)

    # Ports summary.
    d.rounded_rectangle([80, 760, 1380, 1010], radius=18, fill=PANEL, outline=LINE, width=3)
    d.text((110, 790), "Open on the server (inbound)", font=font(30, True), fill=INK)
    rows = [("TCP 443", "required", "console, enrollment, phones' live connection"),
            ("TCP 80", "required", "HTTPS certificate, http → https redirect"),
            ("UDP 443", "optional", "HTTP/3 for browsers that support it")]
    for i, (p, need, why) in enumerate(rows):
        y = 860 + i * 48
        d.text((110, y), p, font=font(28, True), fill=BLUE)
        d.text((290, y), need, font=font(28), fill=INK)
        d.text((460, y), why, font=font(28), fill=MUTED)
    img.save(OUT / "system-overview.png")


def leak_kinds():
    W, H = 2000, 700
    img, d = canvas(W, H)
    pw, gap, top = 455, 26, 40
    titles = [("Round leak", "Click its centre. Size: how far it reaches."),
              ("Plume", "Click its source. It drifts downwind, and reaches a little upwind of the source."),
              ("Area", "Click the corners, then Finish. It spreads past its edge."),
              ("Beacon", "A leak around a Bluetooth beacon: the reading follows distance.")]
    for i, (t, sub) in enumerate(titles):
        x0 = 40 + i * (pw + gap)
        d.rounded_rectangle([x0, top, x0 + pw, H - 40], radius=20, fill=(248, 250, 252), outline=LINE, width=3)
        for gx in range(x0 + 30, x0 + pw, 60):
            d.line([(gx, top + 100), (gx, H - 170)], fill=(232, 236, 240), width=2)
        for gy in range(top + 100, H - 170, 60):
            d.line([(x0 + 10, gy), (x0 + pw - 10, gy)], fill=(232, 236, 240), width=2)
        d.text((x0 + 28, top + 28), t, font=font(36, True), fill=INK)
        # Wrap the caption over two lines.
        words, line, lines = sub.split(), "", []
        for w in words:
            if d.textlength(line + " " + w, font=font(25)) > pw - 56:
                lines.append(line)
                line = w
            else:
                line = (line + " " + w).strip()
        lines.append(line)
        for k, ln in enumerate(lines):
            d.text((x0 + 28, H - 150 + k * 36), ln, font=font(25), fill=MUTED)

    cy = 330
    # Round.
    cx, R = 40 + pw / 2, 150
    shade(img, lambda px, py: smooth(math.hypot(px - cx, py - cy) / R), (int(cx - R), int(cy - R), int(cx + R) + 1, int(cy + R) + 1))
    d = ImageDraw.Draw(img)
    d.ellipse([cx - R, cy - R, cx + R, cy + R], outline=GAS, width=4)
    d.ellipse([cx - 9, cy - 9, cx + 9, cy + 9], fill=INK)
    arrow(d, (cx, cy), (cx + R - 6, cy), fill=INK, width=4, head=16)
    label(d, (cx + R / 2, cy - 26), "Reaches", size=24, bold=True, bg=(255, 240, 225))

    # Plume: the model's own shape and readings (SourceModel.plume, console leaks/geometry.ts), wind
    # from the west. The width grows like the square root of the distance downwind (never under 15 %
    # of it), and the source also reaches a little upwind: a round spread a quarter of the width.
    x0 = 40 + (pw + gap)
    sx, L, Wd = x0 + 100, 280, 180
    back = Wd * PLUME_UPWIND_FRACTION

    def plume_reading(px, py):
        along, across = px - sx, abs(py - cy)
        if along < 0:
            return smooth(math.hypot(along, across) / back)
        x = along / L
        if x >= 1:
            return 0.0
        return smooth(x) * smooth(across / (Wd / 2 * max(PLUME_MIN_WIDTH, math.sqrt(x))))
    shade(img, plume_reading, (int(sx - back) - 2, int(cy - Wd / 2) - 2, sx + L + 2, int(cy + Wd / 2) + 2))
    d = ImageDraw.Draw(img)
    right = [(sx + i / 24 * L, cy + Wd / 2 * max(PLUME_MIN_WIDTH, math.sqrt(i / 24))) for i in range(25)]
    left = [(px, 2 * cy - py) for px, py in reversed(right)]
    behind = [(sx + back * math.cos(-math.pi / 2 - math.pi * i / 12), cy - back * math.sin(-math.pi / 2 - math.pi * i / 12))
              for i in range(1, 12)]
    d.polygon(right + left + behind, outline=GAS, width=4)
    d.ellipse([sx - 9, cy - 9, sx + 9, cy + 9], fill=INK)
    d.text((sx, cy + 36), "source", font=font(22, True), fill=INK, anchor="mm")
    d.line([(sx - back * 0.75, cy - back * 0.6), (sx - 30, top + 185)], fill=INK, width=2)
    d.text((sx - 50, top + 172), "reaches upwind too", font=font(22, True), fill=INK, anchor="lm")
    arrow(d, (x0 + 60, top + 120), (x0 + 220, top + 120), fill=BLUE, width=6)
    d.text((x0 + 235, top + 120), "wind from W", font=font(24, True), fill=BLUE, anchor="lm")
    d.line([(sx, cy + Wd / 2 + 34), (sx + L, cy + Wd / 2 + 34)], fill=INK, width=3)
    d.text((sx + L / 2, cy + Wd / 2 + 56), "Length", font=font(24, True), fill=INK, anchor="mm")
    d.line([(sx + L + 22, cy - Wd / 2), (sx + L + 22, cy + Wd / 2)], fill=INK, width=3)
    d.text((sx + L + 10, cy - Wd / 2 - 20), "Width", font=font(24, True), fill=INK, anchor="mm")

    # Area.
    x0 = 40 + 2 * (pw + gap)
    pts = [(x0 + 120, 210), (x0 + 330, 190), (x0 + 380, 330), (x0 + 250, 450), (x0 + 100, 400)]
    mx = sum(p[0] for p in pts) / len(pts)
    my = sum(p[1] for p in pts) / len(pts)
    grow = lambda k: [(mx + (p[0] - mx) * k, my + (p[1] - my) * k) for p in pts]
    gas_blob(img, lambda ld, t, c: ld.polygon(grow(0.4 + 0.75 * t), fill=c))
    d = ImageDraw.Draw(img)
    d.polygon(pts, outline=GAS, width=5)
    outer = grow(1.15)
    for a, b in zip(outer, outer[1:] + outer[:1]):
        dashed(d, a, b, fill=GAS, width=3, dash=14)
    for p in pts:
        d.ellipse([p[0] - 9, p[1] - 9, p[0] + 9, p[1] + 9], fill=INK)
    label(d, (x0 + 390, 440), "spreads", size=24, bold=True, fill=GAS)

    # Beacon in a room with a doorway.
    x0 = 40 + 3 * (pw + gap)
    rx0, ry0, rx1, ry1 = x0 + 40, 170, x0 + 330, 480
    bx, by = x0 + 140, cy
    room = Image.new("L", img.size, 0)
    ImageDraw.Draw(room).rectangle([rx0, ry0, rx1, ry1], fill=255)  # the walls stop it; the doorway is the gas edge
    gas_blob(img, lambda ld, t, c: ld.ellipse([bx - 230 * t, by - 230 * t, bx + 230 * t, by + 230 * t], fill=c), clip=room)
    d = ImageDraw.Draw(img)
    for seg in [((rx0, ry0), (rx1, ry0)), ((rx0, ry1), (rx1, ry1)), ((rx0, ry0), (rx0, ry1)),
                ((rx1, ry0), (rx1, ry0 + 100)), ((rx1, ry1 - 100), (rx1, ry1))]:
        d.line(seg, fill=MUTED, width=10)
    for r in (60, 100, 140):
        d.arc([bx - r, by - r, bx + r, by + r], -60, 60, fill=TEAL, width=4)
    beacon_icon(d, bx, by)
    phone_icon(d, rx1 - 50, by + 10, 0.8, RED)
    d.text((rx1 + 14, cy - 34), "doorway:", font=font(22, True), fill=TEAL, anchor="lm")
    d.text((rx1 + 14, cy - 6), "gas edge", font=font(22, True), fill=TEAL, anchor="lm")
    img.save(OUT / "leak-kinds.png")


def lifecycle():
    img, d = canvas(2000, 760)
    d.text((60, 40), "An exercise's life, and what the phones show", font=font(44, True), fill=INK)
    states = [("Draft", "EXERCISE NOT STARTED", "readings at baseline", AMBER, 120),
              ("Running", "EXERCISE ACTIVE", "readings follow the scenario", GREEN, 600),
              ("Paused", "EXERCISE PAUSED", "leaks held as they are", AMBER, 1080),
              ("Ended", "EXERCISE ENDED", "readings at baseline", MUTED, 1560)]
    y0, y1, bw = 210, 330, 340
    for name, head, sub, col, x in states:
        d.rounded_rectangle([x, y0, x + bw, y1], radius=24, fill=PANEL, outline=col, width=6)
        d.text((x + bw / 2, (y0 + y1) / 2), name, font=font(40, True), fill=INK, anchor="mm")
        # The phone header, as the trainee sees it.
        d.rounded_rectangle([x - 10, 420, x + bw + 10, 510], radius=12, fill=(14, 20, 18), outline=col, width=4)
        d.ellipse([x + 12, 455, x + 30, 473], fill=col)
        d.text((x + 42, 448), head, font=font(24, True), fill=col if col != MUTED else (190, 200, 210), anchor="lm")
        d.text((x + 42, 482), sub, font=font(21), fill=(170, 180, 175), anchor="lm")
    d.text((110, 392), "On the phone:", font=font(24, True), fill=MUTED, anchor="lm")

    def h_arrow(xa, xb, y, text, up=True):
        arrow(d, (xa, y), (xb, y), fill=INK, width=5)
        label(d, ((xa + xb) / 2, y - 30 if up else y + 30), text, size=26, bold=True)
    h_arrow(120 + bw + 10, 600 - 10, 250, "Start")
    h_arrow(600 + bw + 10, 1080 - 10, 240, "Pause")
    h_arrow(1080 - 10, 600 + bw + 10, 300, "Resume", up=False)
    # End: from Running or Paused, over the top.
    d.line([(770, y0 - 6), (770, 160), (1730, 160)], fill=INK, width=5)
    arrow(d, (1730, 160), (1730, y0 - 8), fill=INK, width=5)
    d.line([(1250, y0 - 6), (1250, 160)], fill=INK, width=5)
    label(d, (1500, 160), "End (from Running or Paused)", size=26, bold=True)
    # Run again: Ended -> Running, below.
    d.line([(1730, 520), (1730, 620), (770, 620)], fill=INK, width=5)
    arrow(d, (770, 620), (770, 518), fill=INK, width=5)
    label(d, (1250, 620), "Run again: a fresh run of the same exercise", size=26, bold=True)
    # Reset.
    d.line([(1900, 270), (1950, 270), (1950, 690), (60, 690), (60, 270)], fill=MUTED, width=4)
    arrow(d, (60, 270), (112, 270), fill=MUTED, width=4)
    label(d, (1000, 690), "Reset (from any state): back to Draft, every override released", size=26, fill=MUTED, bold=True)
    img.save(OUT / "exercise-lifecycle.png")


def beacon_calibration():
    img, d = canvas(2000, 980)
    d.text((60, 40), "Calibrating a beacon on site", font=font(44, True), fill=INK)
    d.text((60, 100), "Phone at chest height, clear line of sight. The calibration is stored on the beacon and used by every phone.",
           font=font(27), fill=MUTED)
    rx0, ry0, rx1, ry1 = 120, 200, 1180, 880
    door = (470, 610)
    d.rectangle([rx0, ry0, rx1, ry1], fill=(255, 246, 236))
    for seg in [((rx0, ry0), (rx1, ry0)), ((rx0, ry1), (rx1, ry1)), ((rx0, ry0), (rx0, ry1)),
                ((rx1, ry0), (rx1, door[0])), ((rx1, door[1]), (rx1, ry1))]:
        d.line(seg, fill=MUTED, width=14)
    d.text((rx0 + 30, ry0 + 24), "ROOM WITH THE LEAK", font=font(26, True), fill=MUTED)
    bx, by = 260, 540
    for r in (52, 80, 108):
        d.arc([bx - r, by - r, bx + r, by + r], -50, 50, fill=TEAL, width=4)
    beacon_icon(d, bx, by, 36)
    d.text((bx - 10, by + 70), "beacon, low down", font=font(24), fill=INK, anchor="mm")
    m = 130  # pixels per metre
    steps = [(1, "1  Record"), (3, "2  Record"), (5, "3  Record (if room)")]
    for metres, text in steps:
        px = bx + metres * m
        phone_icon(d, px, by, 0.9, BLUE)
        d.text((px, by - 75), text, font=font(25, True), fill=BLUE, anchor="mm")
        d.line([(bx, by + 120), (px, by + 120)], fill=LINE, width=3)
        d.line([(px, by + 108), (px, by + 132)], fill=MUTED, width=3)
        d.text((px, by + 150), f"{metres} m", font=font(24), fill=MUTED, anchor="mm")
    # Gas edge at the doorway.
    gx = rx1 - 60
    phone_icon(d, gx, (door[0] + door[1]) / 2, 0.9, AMBER)
    label(d, (gx - 20, door[0] - 60), "4  Record edge (inside the doorway)", size=25, bold=True, fill=AMBER, anchor="rm",
          bg=(255, 246, 236))
    # Outside: reads nothing.
    for (ox, oy) in [(1330, 300), (1330, 800)]:
        phone_icon(d, ox, oy, 0.9, (150, 160, 170))
        d.text((ox + 45, oy), "OUTSIDE: reads nothing", font=font(25, True), fill=MUTED, anchor="lm")
    d.text((1300, 920), "5  Check: walk outside and along the walls", font=font(25, True), fill=INK, anchor="lm")

    # Signal scale.
    sx0, sx1, sy = 1290, 1930, 520
    d.text((sx0, sy - 120), "Signal the phone hears", font=font(28, True), fill=INK)
    for k in range(sx1 - sx0):
        t = k / (sx1 - sx0)
        c = tuple(int(TEAL[i] * (1 - t) + 225 * t) for i in range(3))
        d.line([(sx0 + k, sy - 40), (sx0 + k, sy + 10)], fill=c)
    d.text((sx0, sy + 40), "strong", font=font(22), fill=MUTED)
    d.text((sx1, sy + 40), "weak", font=font(22), fill=MUTED, anchor="ra")
    ex = sx0 + int((sx1 - sx0) * 0.62)
    d.line([(ex, sy - 60), (ex, sy + 30)], fill=AMBER, width=6)
    d.text((ex, sy - 75), "gas edge", font=font(24, True), fill=AMBER, anchor="mm")
    d.text(((sx0 + ex) / 2, sy + 90), "reads (stronger = closer)", font=font(24, True), fill=GAS, anchor="mm")
    d.text(((ex + sx1) / 2, sy + 90), "reads 0", font=font(24, True), fill=MUTED, anchor="mm")
    img.save(OUT / "beacon-calibration.png")


def oxygen_scale():
    img, d = canvas(2000, 560)
    d.text((60, 36), "O₂ on the meter (default alarms)", font=font(44, True), fill=INK)
    x0, x1, y = 120, 1700, 260
    to_x = lambda v: x0 + (x1 - x0) * v / 25.0
    d.rectangle([x0, y - 34, x1, y + 34], fill=(232, 245, 233))
    d.rectangle([x0, y - 34, to_x(19.5), y + 34], fill=(255, 224, 224))
    d.rectangle([to_x(23.5), y - 34, x1, y + 34], fill=(255, 224, 224))
    d.rectangle([x1, y - 34, 1880, y + 34], fill=(60, 60, 60))
    d.text(((x1 + 1880) / 2, y), "OL", font=font(36, True), fill=WHITE, anchor="mm")
    d.text(((x1 + 1880) / 2, y + 62), "over 25", font=font(22), fill=MUTED, anchor="mm")
    for v in range(0, 26, 5):
        d.line([(to_x(v), y + 34), (to_x(v), y + 50)], fill=INK, width=3)
        d.text((to_x(v), y + 70), f"{v}", font=font(24), fill=INK, anchor="mm")
    d.text((x0, y + 110), "%vol", font=font(22), fill=MUTED)
    for v, text, col in [(19.5, "A1 ↓ 19.5", RED), (20.9, "baseline 20.9", GREEN), (23.5, "A2 23.5", RED)]:
        d.line([(to_x(v), y - 60), (to_x(v), y + 34)], fill=col, width=5)
    d.text((to_x(19.5) - 8, y - 78), "A1 ↓ 19.5", font=font(24, True), fill=RED, anchor="rm")
    d.text((to_x(20.9), y - 112), "baseline 20.9", font=font(24, True), fill=GREEN, anchor="mm")
    d.text((to_x(23.5) + 8, y - 78), "A2 23.5", font=font(24, True), fill=RED, anchor="lm")
    # Depletion and enrichment.
    arrow(d, (to_x(20.9), y + 170), (to_x(0.4), y + 170), fill=BLUE, width=6)
    d.text((to_x(10), y + 200), "Depletion: below baseline, down to 0", font=font(26, True), fill=BLUE, anchor="mm")
    arrow(d, (to_x(20.9), y + 170), (to_x(25.0) + 50, y + 170), fill=GAS, width=6)
    d.text((to_x(23.4), y + 200), "Enrichment: at most +5", font=font(26, True), fill=GAS, anchor="mm")
    d.text((120, y + 250), "Inert gas (N₂, Ar, He…) lowers O₂ in proportion: 20 % inert gas → 20.9 × 0.8 = 16.7.",
           font=font(26), fill=MUTED)
    img.save(OUT / "oxygen-scale.png")


def annotated_live():
    """Numbered callouts on the Live page; the legend is in the manual (§8.2)."""
    img = Image.open(OUT / "live.png").convert("RGB")
    s = img.width / 2160  # coordinates below are on the 2160-wide capture
    d = ImageDraw.Draw(img)
    marks = [(1, 1810, 132), (2, 470, 202), (3, 400, 336), (4, 1290, 292), (5, 1665, 288), (6, 1895, 812),
             (7, 1490, 1000), (8, 925, 1100)]
    r = 30 * s
    for n, x, y in marks:
        x, y = x * s, y * s
        d.ellipse([x - r - 4, y - r - 4, x + r + 4, y + r + 4], fill=WHITE)
        d.ellipse([x - r, y - r, x + r, y + r], fill=RED)
        d.text((x, y), str(n), font=font(int(34 * s), True), fill=WHITE, anchor="mm")
    img.save(OUT / "live-annotated.png")


if __name__ == "__main__":
    OUT.mkdir(parents=True, exist_ok=True)
    system_overview()
    leak_kinds()
    lifecycle()
    beacon_calibration()
    oxygen_scale()
    annotated_live()
    print("written to", OUT)
