from PIL import Image, ImageDraw, ImageFont
import os

sizes = {
    "mipmap-mdpi": 48,
    "mipmap-hdpi": 72,
    "mipmap-xhdpi": 96,
    "mipmap-xxhdpi": 144,
    "mipmap-xxxhdpi": 192,
}

base_dir = os.path.expanduser("~/SchedaCliente/app/src/main/res")
primary = (46, 83, 57, 255)   # #2E5339
accent = (176, 134, 66, 255)  # #B08642
white = (255, 255, 255, 255)

for folder, size in sizes.items():
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)
    # rounded square background
    draw.rounded_rectangle([0, 0, size - 1, size - 1], radius=size // 5, fill=primary)
    # simple "set square / geometra" motif: a triangle + line
    pad = size * 0.2
    draw.polygon(
        [(pad, size - pad), (size - pad, size - pad), (size / 2, pad)],
        outline=white,
        width=max(2, size // 20),
    )
    # small accent dot
    r = size * 0.06
    cx, cy = size / 2, pad + r * 0.5
    draw.ellipse([cx - r, cy - r, cx + r, cy + r], fill=accent)

    out_path = os.path.join(base_dir, folder, "ic_launcher.png")
    img.save(out_path)
    round_path = os.path.join(base_dir, folder, "ic_launcher_round.png")
    img.save(round_path)

print("done")
