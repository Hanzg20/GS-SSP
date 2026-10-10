"""App launcher icon: GoldSky logo with the app's word mark overlaid at its
bottom-right, on black (= splash).

    python make_app_icon.py <goldsky_logo.png> <RobotoFlex-Regular.ttf> <out_dir> <WORD> <#RRGGBB>

Adaptive icon foreground is 108dp; launchers mask it to the centre 72dp and
only the centre 66dp circle is guaranteed visible, so logo + word mark are
kept inside that circle (checked below).
"""
import math, sys
from PIL import Image, ImageDraw, ImageFont

logo_path, font_path, out_dir, word, hexcol = sys.argv[1:6]
S = 768
SAFE_R = S * 33 / 108          # radius of the guaranteed-visible circle
COLOR = tuple(int(hexcol.lstrip('#')[i:i + 2], 16) for i in (0, 2, 4)) + (255,)
OUTLINE = (0, 0, 0, 255)

def bold_font(size):
    font = ImageFont.truetype(font_path, size)
    # Roboto Flex is variable with many axes; set weight by name, keep the others at default.
    axes = font.get_variation_axes()
    values = [a['default'] for a in axes]
    for i, a in enumerate(axes):
        name = a.get('name')
        name = name.decode() if isinstance(name, bytes) else name
        if name and name.lower().startswith('weight'):
            values[i] = 900
    font.set_variation_by_axes(values)
    return font

def foreground():
    fg = Image.new('RGBA', (S, S), (0, 0, 0, 0))
    logo = Image.open(logo_path).convert('RGBA')
    logo = logo.crop(logo.getbbox())
    lw = 330                                   # was 286 with the word below the logo: ~15% larger
    lh = round(logo.height * lw / logo.width)
    logo = logo.resize((lw, lh), Image.LANCZOS)
    lx, ly = (S - lw) // 2 - 14, (S - lh) // 2 - 22   # nudged up-left to leave room for the mark
    fg.alpha_composite(logo, (lx, ly))

    # Word mark: right-aligned to the logo's right edge, sitting on its lower part.
    size = 84 if len(word) <= 4 else 70
    font = bold_font(size)
    tracking = 6
    d = ImageDraw.Draw(fg)
    widths = [d.textlength(ch, font=font) for ch in word]
    total = sum(widths) + tracking * (len(word) - 1)
    stroke = 8
    right = lx + lw + 12
    x0 = right - total
    top = ly + lh - size * 0.80
    x = x0
    for ch, w in zip(word, widths):
        d.text((x, top), ch, font=font, fill=COLOR, stroke_width=stroke, stroke_fill=OUTLINE)
        x += w + tracking

    # every opaque pixel must sit inside the safe circle
    a = fg.split()[-1]
    c = S / 2
    worst = max((math.hypot(px - c, py - c) for py in range(0, S, 2) for px in range(0, S, 2) if a.getpixel((px, py)) > 40), default=0)
    print(f'{word}: furthest content {worst:.0f}px, safe radius {SAFE_R:.0f}px', 'OK' if worst <= SAFE_R else 'OUTSIDE SAFE ZONE')
    return fg

fg = foreground()
fg.save(f'{out_dir}/ic_launcher_foreground.png')

def legacy(round_):
    img = Image.new('RGBA', (S, S), (0, 0, 0, 0))
    mask = Image.new('L', (S, S), 0)
    md = ImageDraw.Draw(mask)
    if round_:
        md.ellipse((0, 0, S - 1, S - 1), fill=255)
    else:
        md.rounded_rectangle((0, 0, S - 1, S - 1), radius=S // 6, fill=255)
    img.paste(Image.new('RGBA', (S, S), (0, 0, 0, 255)), (0, 0), mask)
    # Legacy icons show the whole canvas: show the same centre 72dp the adaptive one does.
    crop = round(S * 18 / 108)
    view = fg.crop((crop, crop, S - crop, S - crop)).resize((S, S), Image.LANCZOS)
    img.alpha_composite(Image.composite(view, Image.new('RGBA', (S, S)), mask))
    return img

legacy(False).save(f'{out_dir}/ic_launcher.png')
legacy(True).save(f'{out_dir}/ic_launcher_round.png')

# preview: adaptive masked as a circle and a squircle, plus the legacy square
prev = Image.new('RGBA', (S * 3 + 40, S), (60, 60, 60, 255))
crop = round(S * 18 / 108)
tile = Image.new('RGBA', (S, S), (0, 0, 0, 255))
tile.alpha_composite(fg)
view = tile.crop((crop, crop, S - crop, S - crop)).resize((S, S), Image.LANCZOS)
for i, shape in enumerate(['circle', 'squircle']):
    m = Image.new('L', (S, S), 0)
    md = ImageDraw.Draw(m)
    if shape == 'circle':
        md.ellipse((0, 0, S - 1, S - 1), fill=255)
    else:
        md.rounded_rectangle((0, 0, S - 1, S - 1), radius=S // 4, fill=255)
    prev.paste(view, (i * (S + 20), 0), m)
leg = Image.open(f'{out_dir}/ic_launcher.png')
prev.paste(leg, (2 * (S + 20), 0), leg)
prev.resize((prev.width // 2, prev.height // 2)).save(f'{out_dir}/preview.png')
