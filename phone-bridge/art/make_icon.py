"""Draws the Ghafoorii app icon for iOS and Android.

A yellow diamond road sign on orange, with GHAFOORII across it in heavy black letters. The A is a
turn arrow, like the U-turn letter in the "TURN" sign it is based on.

Usage: python3 art/make_icon.py <phone-bridge folder> [font.otf]
The font is Inter Display Black (SIL Open Font License), e.g. /usr/share/fonts/opentype/inter/.
"""
import math
import os
import sys

from PIL import Image, ImageDraw, ImageFont

ORANGE = (251, 178, 28)
YELLOW = (244, 230, 28)
INK = (35, 31, 32)
WORD = "GHAFOORII"
TURN_LETTER = 2  # the A
FONT = "/usr/share/fonts/opentype/inter/InterDisplay-Black.otf"


def runs(row):
    """Ink runs in one row of a mask: [(start, end), ...]."""
    out, start = [], None
    for i, v in enumerate(row + [0]):
        if v > 127 and start is None:
            start = i
        elif v <= 127 and start is not None:
            out.append((start, i))
            start = None
    return out


def turn_a(font):
    """The font's own A without its bar, with the left leg ending in a down arrow: a turn sign
    letter, like the U-turn letter of the "TURN" sign. Returns (mask, left, top) in text space."""
    l, t, r, b = font.getbbox("A", anchor="ls")
    pad = int((b - t) * 0.3)
    w, h = r - l + 2 * pad, b - t + 2 * pad
    m = Image.new("L", (w, h), 0)
    ImageDraw.Draw(m).text((pad - l, pad - t), "A", font=font, fill=255, anchor="ls")
    px = m.load()
    rows = [[px[x, y] for x in range(w)] for y in range(h)]
    kinds = [len(runs(rw)) for rw in rows]
    base = max(y for y in range(h) if kinds[y])
    # Rows from the bottom: legs (2 runs), then the bar (1 run), then the counter (2 runs).
    y = base
    while y > 0 and kinds[y] == 2:
        y -= 1
    bar_bottom = y
    while y > 0 and kinds[y] == 1:
        y -= 1
    bar_top = y + 1
    below, above = runs(rows[bar_bottom + 1]), runs(rows[bar_top - 1])
    d = ImageDraw.Draw(m)
    # Remove the bar: clear between the inner edges of the legs, following their slant.
    span = max(1, (bar_bottom + 1) - (bar_top - 1))
    for yy in range(bar_top, bar_bottom + 1):
        f = ((bar_bottom + 1) - yy) / span
        x0 = below[0][1] + (above[0][1] - below[0][1]) * f
        x1 = below[1][0] + (above[1][0] - below[1][0]) * f
        d.line([(x0, yy), (x1 - 1, yy)], fill=0)
    # Turn the left leg into an arrow pointing down (read the mask again: the bar is gone now).
    px = m.load()
    rows = [[px[x, y] for x in range(w)] for y in range(h)]
    foot = runs(rows[base])[0]
    leg = foot[1] - foot[0]
    cut = int(base - leg * 1.1)
    for yy in range(cut, base + 1):
        r0 = runs(rows[yy])[0]
        d.line([(r0[0], yy), (r0[1] - 1, yy)], fill=0)
    lr = runs(rows[cut])[0]
    mid = (lr[0] + lr[1]) / 2
    half = leg * 1.08
    d.polygon([(mid - half, cut), (mid + half, cut), (mid, base + leg * 0.18)], fill=255)
    # Drawn at 4x and scaled down later, so a hard edge here is fine; it also drops faint leftovers.
    m = m.point(lambda v: 255 if v > 127 else 0)
    return m, l - pad, t - pad


def icon(size, font_path=FONT):
    k = 4  # draw big, then scale down: smooth edges
    S = 1024 * k
    img = Image.new("RGB", (S, S), ORANGE)
    d = ImageDraw.Draw(img)
    c = S / 2

    # The sign: a yellow diamond, with its dark border showing at the top and bottom corners.
    h = 0.30 * S
    d.polygon([(c, c - h), (c + h, c), (c, c + h), (c - h, c)], fill=YELLOW)
    gap, part, line = 0.022 * S, 0.42, max(2, int(0.011 * S))
    H = h + gap * math.sqrt(2)
    for sy in (-1, 1):
        corner = (c, c + sy * H)
        for sx in (-1, 1):
            end = (c + sx * H * part, c + sy * H * (1 - part))
            d.line([corner, end], fill=INK, width=line)
        d.ellipse([c - line / 2, c + sy * H - line / 2, c + line / 2, c + sy * H + line / 2], fill=INK)

    # The word, as wide as the original "TURN": about 88 % of the icon.
    target = 0.88 * S
    size_pt = 100
    font = ImageFont.truetype(font_path, size_pt)
    tracking = 0.0
    width = sum(font.getlength(ch) for ch in WORD)
    size_pt = int(size_pt * target / width)
    font = ImageFont.truetype(font_path, size_pt)
    cap = font.getbbox("H")[3] - font.getbbox("H")[1]
    stem = font.getbbox("I")[2] - font.getbbox("I")[0]
    advances = [font.getlength(ch) for ch in WORD]
    tracking = -0.01 * size_pt
    extra = 0.07 * size_pt  # room for the arrow head next to the H
    total = sum(advances) + tracking * (len(WORD) - 1) + extra
    x = c - total / 2
    base = c + cap / 2
    for i, ch in enumerate(WORD):
        if i == TURN_LETTER:
            x += extra
            m, ox, oy = turn_a(font)  # noqa: the turn letter
            img.paste(INK, (int(round(x + ox)), int(round(base + oy))), m)
        else:
            d.text((x, base), ch, font=font, fill=INK, anchor="ls")
        x += advances[i] + tracking

    return img.resize((size, size), Image.LANCZOS)


def main():
    out = sys.argv[1]
    font = sys.argv[2] if len(sys.argv) > 2 else FONT
    big = icon(1024, font)

    ios = os.path.join(out, "ios/Bridge/Assets.xcassets/AppIcon.appiconset")
    os.makedirs(ios, exist_ok=True)
    big.save(os.path.join(ios, "icon-1024.png"))
    open(os.path.join(ios, "Contents.json"), "w").write(
        '{"images":[{"filename":"icon-1024.png","idiom":"universal","platform":"ios","size":"1024x1024"}],'
        '"info":{"author":"xcode","version":1}}')
    open(os.path.join(out, "ios/Bridge/Assets.xcassets/Contents.json"), "w").write('{"info":{"author":"xcode","version":1}}')

    mask = Image.new("L", (1024, 1024), 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, 1023, 1023], radius=230, fill=255)
    rgba = big.convert("RGBA")
    rgba.putalpha(mask)
    for name, px in {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}.items():
        d = os.path.join(out, f"android/app/src/main/res/mipmap-{name}")
        os.makedirs(d, exist_ok=True)
        rgba.resize((px, px), Image.LANCZOS).save(os.path.join(d, "ic_launcher.png"))
    print("icons written")


if __name__ == "__main__":
    main()
