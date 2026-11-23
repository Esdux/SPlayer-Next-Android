# -*- coding: utf-8 -*-
from PIL import Image, ImageDraw
import os

src = "icon-src.png"
res = "android/app/src/main/res"
im = Image.open(src).convert("RGBA")
w, h = im.size
px = im.load()

minx, miny, maxx, maxy = w, h, 0, 0
for y in range(0, h, 2):
    for x in range(0, w, 2):
        r, g, b, a = px[x, y]
        if max(r, g, b) > 40:
            minx = min(minx, x); maxx = max(maxx, x)
            miny = min(miny, y); maxy = max(maxy, y)
print("bbox", minx, miny, maxx, maxy)

im = im.crop((minx, miny, maxx + 1, maxy + 1))
side = min(im.size)
im = im.resize((side, side), Image.LANCZOS)

mask = Image.new("L", (side, side), 0)
d = ImageDraw.Draw(mask)
radius = int(side * 0.225)
d.rounded_rectangle([0, 0, side - 1, side - 1], radius=radius, fill=255)
im.putalpha(mask)
im.save("icon-cutout.png")

for dens, size in [("mdpi", 48), ("hdpi", 72), ("xhdpi", 96), ("xxhdpi", 144), ("xxxhdpi", 192)]:
    out = im.resize((size, size), Image.LANCZOS)
    for name in ("ic_launcher", "ic_launcher_round", "ic_launcher_foreground"):
        out.save(os.path.join(res, "mipmap-" + dens, name + ".png"))
print("done side=", side)
