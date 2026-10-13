from PIL import Image, ImageDraw
import os

SRC = "/root/.claude/uploads/9a77a6d2-a31d-5036-b429-1a8c2dd2f2fe/f789fbe0-image.jpg"
RES = "/home/claude/SchedaCliente/app/src/main/res"

# --- 1. Remove black background via flood fill from corners, keep internal black linework ---
img = Image.open(SRC).convert("RGBA")
w, h = img.size
seeds = [(0, 0), (w - 1, 0), (0, h - 1), (w - 1, h - 1)]
for seed in seeds:
    ImageDraw.floodfill(img, seed, (0, 0, 0, 0), thresh=40)

# Trim to the actual content bounding box (alpha > 0)
bbox = img.getbbox()
if bbox:
    img = img.crop(bbox)

def centered_canvas(source: Image.Image, canvas_size: int, content_ratio: float) -> Image.Image:
    """Paste `source` centered on a transparent square canvas, scaled so its
    largest dimension equals canvas_size * content_ratio."""
    canvas = Image.new("RGBA", (canvas_size, canvas_size), (0, 0, 0, 0))
    target = int(canvas_size * content_ratio)
    s = source.copy()
    s.thumbnail((target, target), Image.LANCZOS)
    x = (canvas_size - s.width) // 2
    y = (canvas_size - s.height) // 2
    canvas.paste(s, (x, y), s)
    return canvas

# --- 2. Legacy launcher icons (square + round), content ~78% to avoid round-crop clipping ---
legacy_sizes = {
    "mipmap-mdpi": 48,
    "mipmap-hdpi": 72,
    "mipmap-xhdpi": 96,
    "mipmap-xxhdpi": 144,
    "mipmap-xxxhdpi": 192,
}

for folder, size in legacy_sizes.items():
    square = centered_canvas(img, size, 0.90)
    out_dir = os.path.join(RES, folder)
    os.makedirs(out_dir, exist_ok=True)
    square.save(os.path.join(out_dir, "ic_launcher.png"))

    # round version: same content but masked to a circle, content kept smaller (0.78) so nothing is clipped
    round_src = centered_canvas(img, size, 0.78)
    mask = Image.new("L", (size, size), 0)
    mdraw = ImageDraw.Draw(mask)
    mdraw.ellipse([0, 0, size - 1, size - 1], fill=255)
    round_img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    round_img.paste(round_src, (0, 0), round_src)
    round_img.putalpha(Image.composite(round_img.split()[3], Image.new("L", (size, size), 0), mask))
    round_img.save(os.path.join(out_dir, "ic_launcher_round.png"))

# --- 3. Adaptive icon foreground layers (API 26+), safe zone ~66% of 108dp canvas ---
adaptive_sizes = {
    "mipmap-mdpi": 108,
    "mipmap-hdpi": 162,
    "mipmap-xhdpi": 216,
    "mipmap-xxhdpi": 324,
    "mipmap-xxxhdpi": 432,
}

for folder, size in adaptive_sizes.items():
    fg = centered_canvas(img, size, 0.62)
    out_dir = os.path.join(RES, folder)
    os.makedirs(out_dir, exist_ok=True)
    fg.save(os.path.join(out_dir, "ic_launcher_foreground.png"))

print("Icon generation complete.")
