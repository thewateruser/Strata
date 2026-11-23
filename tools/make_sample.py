"""Generates app/src/main/assets/sample.psd — a layered demo poster (requires: pip install psd-tools)."""
import sys
import numpy as np
from PIL import Image, ImageDraw, ImageFilter, ImageFont
from psd_tools import PSDImage
from psd_tools.api.layers import PixelLayer, Group
from psd_tools.constants import BlendMode, Compression

out = sys.argv[1] if len(sys.argv) > 1 else "sample.psd"
W, H = 1440, 1080
rng = np.random.default_rng(7)
yy, xx = np.mgrid[0:H, 0:W].astype(np.float32)

def rgba(arr):
    return Image.fromarray(np.clip(arr, 0, 255).astype(np.uint8), "RGBA")

def lerp(a, b, t):
    return a + (b - a) * t[..., None]

# Sky gradient: deep violet -> magenta -> warm peach at the horizon
t = (yy / H) ** 1.1
sky = lerp(np.array([38, 20, 82], np.float32), np.array([196, 84, 140], np.float32), np.clip(t * 1.4, 0, 1))
sky = lerp(sky, np.array([255, 176, 128], np.float32), np.clip((t - 0.5) * 2.2, 0, 1))
bg = np.dstack([sky, np.full((H, W), 255, np.float32)])

def radial(cx, cy, r, color, power=2.0, size=None):
    d = np.sqrt((xx - cx) ** 2 + (yy - cy) ** 2) / r
    a = np.clip(1 - d, 0, 1) ** power * 255
    return np.dstack([np.full((H, W, 3), color, np.float32), a])

glow = radial(980, 560, 620, (255, 214, 170), 1.6)
sun_d = np.sqrt((xx - 980) ** 2 + (yy - 560) ** 2)
sun_a = np.clip((150 - sun_d) * 1.5, 0, 255)
sun_col = lerp(np.array([255, 236, 170], np.float32), np.array([255, 120, 90], np.float32), np.clip((yy - 410) / 300, 0, 1))
sun = np.dstack([sun_col, sun_a])

def ridge(base, amp, freqs, seed):
    r = np.random.default_rng(seed)
    x = np.arange(W, dtype=np.float32)
    y = np.full(W, base, np.float32)
    for f, a in freqs:
        y += np.sin(x / W * np.pi * 2 * f + r.uniform(0, 6)) * amp * a
    y += np.cumsum(r.normal(0, 1.2, W)).astype(np.float32) * 0.6
    return y

def mountains(base, amp, freqs, seed, top, bottom):
    edge = ridge(base, amp, freqs, seed)
    a = np.clip((yy - edge[None, :]) * 2.5, 0, 1) * 255
    col = lerp(np.array(top, np.float32), np.array(bottom, np.float32), np.clip((yy - base + amp) / (H - base + amp), 0, 1))
    return np.dstack([col, a])

back = mountains(700, 90, [(1.3, 1), (3.1, 0.4), (7, 0.15)], 3, (122, 62, 140), (70, 36, 96))
front = mountains(820, 70, [(0.9, 1), (2.6, 0.5), (9, 0.12)], 11, (58, 26, 74), (24, 10, 36))
mist = lerp(np.array([255, 190, 220], np.float32), np.array([120, 60, 160], np.float32), np.clip((yy - 760) / 320, 0, 1))
mist = np.dstack([mist, np.full((H, W), 255, np.float32)])

stars = np.zeros((H, W, 4), np.float32)
for _ in range(420):
    x, y = rng.integers(0, W), rng.integers(0, int(H * 0.6))
    r = rng.choice([1, 1, 1, 2, 2, 3])
    stars[max(0, y - r):y + r, max(0, x - r):x + r] = (255, 248, 235, rng.integers(120, 255))
stars_img = Image.fromarray(stars.astype(np.uint8), "RGBA").filter(ImageFilter.GaussianBlur(0.6))
star_mask = Image.fromarray(np.clip(255 - (yy / (H * 0.62)) * 255, 0, 255).astype(np.uint8), "L")

title = Image.new("RGBA", (W, 300), (0, 0, 0, 0))
d = ImageDraw.Draw(title)
font = ImageFont.truetype("/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf", 190)
d.text((W // 2, 150), "STRATA", font=font, fill=(255, 245, 250, 255), anchor="mm")
sub = ImageFont.truetype("/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf", 34)
d.text((W // 2, 272), "L A Y E R   B Y   L A Y E R", font=sub, fill=(255, 220, 235, 200), anchor="mm")
shadow = Image.new("RGBA", title.size, (0, 0, 0, 0))
shadow.putalpha(title.getchannel("A").filter(ImageFilter.GaussianBlur(14)).point(lambda v: v * 0.55))
shadow_px = np.array(shadow); shadow_px[..., :3] = (40, 10, 60); shadow = Image.fromarray(shadow_px, "RGBA")

sketch = Image.new("RGBA", (W, H), (0, 0, 0, 0))
ds = ImageDraw.Draw(sketch)
for i in range(0, W, 60):
    ds.line([(i, 0), (i, H)], fill=(255, 255, 255, 60), width=1)
for j in range(0, H, 60):
    ds.line([(0, j), (W, j)], fill=(255, 255, 255, 60), width=1)

C = Compression.ZIP
psd = PSDImage.new(mode="RGB", size=(W, H), depth=8)
psd.append(PixelLayer.frompil(rgba(bg), psd, "Sky", 0, 0, compression=C))
st = PixelLayer.frompil(stars_img, psd, "Stars", 0, 0, compression=C)
psd.append(st)
st.remove_mask(); st.create_mask(star_mask, top=0, left=0)
st.blend_mode = BlendMode.SCREEN  # stars are light on black; screen drops the black

g = PixelLayer.frompil(rgba(glow), psd, "Glow", 0, 0, compression=C); g.blend_mode = BlendMode.SCREEN; g.opacity = 200
s = PixelLayer.frompil(rgba(sun), psd, "Sun disc", 0, 0, compression=C)
psd.append(g); psd.append(s)
sun_group = Group.group_layers(psd, [g, s], name="Sun")

b = PixelLayer.frompil(rgba(back), psd, "Far range", 0, 0, compression=C); b.opacity = 230
f = PixelLayer.frompil(rgba(front), psd, "Near range", 0, 0, compression=C)
m = PixelLayer.frompil(rgba(mist), psd, "Mist (clipped)", 0, 0, compression=C)
m.blend_mode = BlendMode.SOFT_LIGHT; m.opacity = 190
psd.append(b); psd.append(f); psd.append(m); m.clipping = True
Group.group_layers(psd, [b, f, m], name="Mountains")

sh = PixelLayer.frompil(shadow, psd, "Title shadow", 120, 0, compression=C); sh.blend_mode = BlendMode.MULTIPLY
ti = PixelLayer.frompil(title, psd, "Title", 120, 0, compression=C)
psd.append(sh); psd.append(ti)
Group.group_layers(psd, [sh, ti], name="Type")

sk = PixelLayer.frompil(sketch, psd, "Layout grid (hidden)", 0, 0, compression=C)
psd.append(sk); sk.visible = False
psd.save(out)
print("wrote", out)
