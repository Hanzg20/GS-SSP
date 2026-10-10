"""Iris launcher icon: GoldSky logo + an "IRIS" word mark, on black (= splash).

Adaptive icon foreground is 108dp; only the centre 66dp circle is guaranteed
visible, so logo + word mark are kept inside a circle of ~0.61 x canvas.
"""
import sys
from PIL import Image, ImageDraw, ImageFont

logo_path, font_path, out_dir = sys.argv[1], sys.argv[2], sys.argv[3]
S = 768
CYAN = (0, 229, 255, 255)

def foreground():
    fg = Image.new('RGBA', (S, S), (0, 0, 0, 0))
    logo = Image.open(logo_path).convert('RGBA')
    lw = 286
    lh = round(logo.height * lw / logo.width)
    logo = logo.resize((lw, lh), Image.LANCZOS)
    fg.alpha_composite(logo, ((S - lw) // 2, 196))

    font = ImageFont.truetype(font_path, 92)
    # Roboto Flex is variable with many axes; set weight by name, keep the others at default.
    axes = font.get_variation_axes()
    values = [a['default'] for a in axes]
    for i, a in enumerate(axes):
        name = a.get('name')
        name = name.decode() if isinstance(name, bytes) else name
        if name and name.lower().startswith('weight'):
            values[i] = 800
    font.set_variation_by_axes(values)
    text = 'IRIS'
    tracking = 14
    d = ImageDraw.Draw(fg)
    widths = [d.textlength(ch, font=font) for ch in text]
    total = sum(widths) + tracking * (len(text) - 1)
    x = (S - total) / 2
    y = 196 + lh + 18
    for ch, w in zip(text, widths):
        d.text((x, y), ch, font=font, fill=CYAN)
        x += w + tracking
    return fg

fg = foreground()
fg.save(f'{out_dir}/ic_launcher_foreground.png')

def legacy(round_):
    img = Image.new('RGBA', (S, S), (0, 0, 0, 0))
    bg = Image.new('RGBA', (S, S), (0, 0, 0, 255))
    mask = Image.new('L', (S, S), 0)
    md = ImageDraw.Draw(mask)
    if round_:
        md.ellipse((0, 0, S - 1, S - 1), fill=255)
    else:
        md.rounded_rectangle((0, 0, S - 1, S - 1), radius=S // 6, fill=255)
    img.paste(bg, (0, 0), mask)
    # Legacy icons show the whole canvas: scale the foreground up a little.
    big = fg.resize((round(S * 1.25), round(S * 1.25)), Image.LANCZOS)
    off = (S - big.width) // 2
    layer = Image.new('RGBA', (S, S), (0, 0, 0, 0))
    layer.alpha_composite(big.crop((-off, -off, -off + S, -off + S)))
    img.alpha_composite(Image.composite(layer, Image.new('RGBA', (S, S)), mask))
    return img

legacy(False).save(f'{out_dir}/ic_launcher.png')
legacy(True).save(f'{out_dir}/ic_launcher_round.png')

# preview: adaptive as a circle and a rounded square, plus the legacy square
prev = Image.new('RGBA', (S * 3 + 40, S), (60, 60, 60, 255))
for i, shape in enumerate(['circle', 'squircle']):
    tile = Image.new('RGBA', (S, S), (0, 0, 0, 255))
    tile.alpha_composite(fg)
    m = Image.new('L', (S, S), 0)
    md = ImageDraw.Draw(m)
    inset = round(S * 0.0)
    if shape == 'circle':
        md.ellipse((inset, inset, S - 1 - inset, S - 1 - inset), fill=255)
    else:
        md.rounded_rectangle((inset, inset, S - 1 - inset, S - 1 - inset), radius=S // 4, fill=255)
    # launchers show the centre 72/108 of the canvas
    crop = round(S * 18 / 108)
    view = tile.crop((crop, crop, S - crop, S - crop)).resize((S, S), Image.LANCZOS)
    prev.paste(view, (i * (S + 20), 0), m)
prev.paste(Image.open(f'{out_dir}/ic_launcher.png'), (2 * (S + 20), 0), Image.open(f'{out_dir}/ic_launcher.png'))
prev.resize((prev.width // 2, prev.height // 2)).save(f'{out_dir}/preview.png')
print('ok')
