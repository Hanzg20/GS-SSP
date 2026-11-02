"""Cut the three 小海狸 scenes out of the baked-in checkerboard and save them transparent."""
import sys
import numpy as np
from PIL import Image

src, outdir = sys.argv[1], sys.argv[2]
im = np.asarray(Image.open(src).convert('RGB')).astype(np.float32)
H, W, _ = im.shape
mx, mn = im.max(2), im.min(2)
sat = np.where(mx > 0, (mx - mn) / np.maximum(mx, 1), 0)
val = mx / 255
# The light grey / white checker: no colour, bright, and never warm (the receipt paper is
# a warm white: red above blue; the checker is neutral or a little blue).
warm_ok = np.ones((H, W), bool); warm_ok[485:, 800:] = False          # only near the receipts (calculator scene)
cand = (sat < 0.075) & (val > 0.84) & (warm_ok | (im[..., 2] - im[..., 0] >= -2))

# Flood from the border through checker-like pixels (4-neighbour dilation until stable).
reached = np.zeros_like(cand)
reached[0, :] = cand[0, :]; reached[-1, :] = cand[-1, :]; reached[:, 0] = cand[:, 0]; reached[:, -1] = cand[:, -1]
while True:
    grow = reached.copy()
    grow[1:, :] |= reached[:-1, :]; grow[:-1, :] |= reached[1:, :]
    grow[:, 1:] |= reached[:, :-1]; grow[:, :-1] |= reached[:, 1:]
    grow &= cand
    if (grow == reached).all():
        break
    reached = grow

# Checker shut in behind the wave, between the paw and the shop shelves (the flood can't reach it).
enclosed = np.zeros_like(cand); enclosed[30:180, 1165:1275] = True
reached |= (sat < 0.14) & (val > 0.78) & enclosed
alpha = np.where(reached, 0.0, 1.0)

# The counter scene's shop fades out to white at its edges: fade it out to transparent
# instead (whiter = more see-through), except on 小海狸 and the card terminal (eye whites, teeth).
shop = np.zeros_like(cand); shop[:485, 560:] = True
protect = np.zeros_like(cand); protect[0:470, 800:1180] = True; protect[260:470, 1175:1360] = True; protect[30:180, 1165:1275] = True
near = reached.copy()                       # within 25 px of the outside: the fading rim, not the labels on the shelves
for _ in range(25):
    d = near.copy(); d[1:, :] |= near[:-1, :]; d[:-1, :] |= near[1:, :]; d[:, 1:] |= near[:, :-1]; d[:, :-1] |= near[:, 1:]; near = d
soft = shop & ~protect & ~reached & near
fade = np.clip((mn - 205) / (248 - 205), 0, 1) * np.clip(1 - sat / 0.16, 0, 1)
alpha = np.where(soft, np.minimum(alpha, 1 - fade), alpha)

# Soft edge: in a 2-px band next to the background, opacity follows how unlike the
# checker the pixel is; then take the white out of those pixels (no light halo).
band = np.zeros_like(reached)
r = reached
for _ in range(2):
    d = r.copy()
    d[1:, :] |= r[:-1, :]; d[:-1, :] |= r[1:, :]; d[:, 1:] |= r[:, :-1]; d[:, :-1] |= r[:, 1:]
    r = d
band = r & ~reached
whiteness = np.clip((mn - 150) / (245 - 150), 0, 1) * np.clip(1 - sat / 0.25, 0, 1)
alpha = np.where(band, np.clip(1 - whiteness, 0, 1), alpha)
a = alpha[..., None]
rgb = np.where(a > 0.02, (im - (1 - a) * 255) / np.maximum(a, 0.02), im)
rgb = np.clip(rgb, 0, 255)
rgba = np.dstack([rgb, alpha * 255]).astype(np.uint8)

# The three scenes, by where they sit on the sheet.
regions = {
    'apex_scene_counter': lambda x, y: x > 560 and y < 485,     # waving at the counter with the card terminal
    'apex_scene_clipboard': lambda x, y: x < 640 and y > 440 and not (x >= 572 and y < 484),  # logs, clipboard, a question mark (not the counter's table edge)
    'apex_scene_calculator': lambda x, y: x > 800 and y > 485,  # glasses, calculator, receipts
}
ys, xs = np.nonzero(alpha > 0.05)
for name, inside in regions.items():
    sel = np.array([inside(x, y) for x, y in zip(xs, ys)])
    x0, x1, y0, y1 = xs[sel].min(), xs[sel].max(), ys[sel].min(), ys[sel].max()
    crop = rgba[y0:y1 + 1, x0:x1 + 1].copy()
    # Clear anything of the other scenes inside this box.
    yy, xx = np.mgrid[y0:y1 + 1, x0:x1 + 1]
    mask = np.vectorize(inside)(xx, yy)
    crop[..., 3] = np.where(mask, crop[..., 3], 0)
    pad = 12
    out = np.zeros((crop.shape[0] + 2 * pad, crop.shape[1] + 2 * pad, 4), np.uint8)
    out[pad:-pad, pad:-pad] = crop
    Image.fromarray(out, 'RGBA').save(f'{outdir}/{name}.png', optimize=True)
    print(name, out.shape[1], 'x', out.shape[0])
