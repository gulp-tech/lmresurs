#!/usr/bin/env python3
"""
Мокап «талисман в руке» — проверка глазами до захода в игру.

Geyser рисует кастомный предмет плоской иконкой (attachables для талисманов
намеренно не создаются), поэтому в руке фигурка выглядит ровно так же, как в
слоте меню. Здесь она кладётся на «ладонь» без наклона — так и будет в игре.

Требует Pillow. Для сервера не нужен.

    python3 tools/make_talisman_preview.py --pack dist/bedrock_pack --out dist/preview
"""

from __future__ import annotations

import argparse
import os

try:
    from PIL import Image, ImageDraw, ImageFont
except ImportError:  # pragma: no cover
    raise SystemExit("Нужен Pillow: pip install pillow")

# CMD -> (подпись, имя иконки в паке)
ITEMS = [
    (1, "жизни", "minecraft_item_funtime_items_talisman_harmony"),
    (2, "ярости", "minecraft_item_funtime_items_talisman_daedalus"),
    (3, "стража", "minecraft_item_funtime_items_talisman_triton"),
    (4, "странника", "minecraft_item_funtime_items_talisman_discord"),
    (5, "танка", "minecraft_item_funtime_items_talisman_echidna"),
    (6, "воина", "minecraft_item_funtime_items_talisman_crusher"),
    (7, "убийцы", "minecraft_item_funtime_items_talisman_punisher"),
    (8, "берсерка", "minecraft_item_funtime_items_talisman_tyrant"),
    (9, "охотника", "minecraft_item_funtime_items_talisman_edge"),
    (10, "бегуна", "minecraft_item_funtime_items_talisman_phoenix"),
    (11, "демона", "minecraft_item_funtime_new_year_talisman_grinch"),
    (12, "героя", "minecraft_item_funtime_items_talisman_hero"),
]


def main() -> int:
    parser = argparse.ArgumentParser(description="Мокап талисманов «в руке»")
    parser.add_argument("--pack", default="dist/bedrock_pack")
    parser.add_argument("--out", default="dist/preview")
    args = parser.parse_args()

    scale = 6
    cell = 16 * scale
    pad_x, pad_y, label = 16, 26, 22
    cols = 6
    rows = (len(ITEMS) + cols - 1) // cols
    width = cols * (cell + pad_x * 2 + 34)
    height = rows * (cell + pad_y + label) + 24
    sheet = Image.new("RGBA", (width, height), (22, 20, 26, 255))
    draw = ImageDraw.Draw(sheet)
    try:
        font = ImageFont.truetype("/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf", 13)
    except OSError:
        font = ImageFont.load_default()

    missing = 0
    for index, (cmd, title, icon) in enumerate(ITEMS):
        path = os.path.join(args.pack, "textures", "items", f"{icon}.png")
        if not os.path.isfile(path):
            missing += 1
            continue
        art = Image.open(path).convert("RGBA").resize((cell, cell), Image.NEAREST)

        col, row = index % cols, index // cols
        x = col * (cell + pad_x * 2 + 34) + pad_x
        y = row * (cell + pad_y + label) + pad_y

        # «ладонь» — тёмная плашка, чтобы видеть реальный размер фигурки в руке
        draw.rounded_rectangle(
            [x - 8, y - 6, x + cell + 8, y + cell + 10], radius=6, fill=(46, 40, 52, 255)
        )
        sheet.alpha_composite(art, (x, y))
        draw.text((x, y + cell + 8), f"{title} · CMD {cmd}", font=font, fill=(220, 220, 228, 255))

    os.makedirs(args.out, exist_ok=True)
    out_path = os.path.join(args.out, "talismans-hand-mock.png")
    sheet.save(out_path)
    print(f"OK: {out_path}" + (f" (нет иконок: {missing})" if missing else ""))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
