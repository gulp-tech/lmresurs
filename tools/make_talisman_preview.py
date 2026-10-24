#!/usr/bin/env python3
"""
Мокап «талисман в руке» для проверки глазами.

Настоящий 3D-рендер в руке делает клиент Bedrock (attachable из funtime.mcpack),
поэтому здесь — приблизительный 2.5D-вид: фигурка из пака + её золотой торец
(тот же, что в textures/item/*.png). Нужен только чтобы сверить силуэты и цвета
до захода в игру.

Требует Pillow. Не обязателен для сервера.

    python3 tools/make_talisman_preview.py --pack dist/bedrock_pack --out dist/preview
"""

from __future__ import annotations

import argparse
import os

try:
    from PIL import Image, ImageDraw, ImageFont
except ImportError:  # pragma: no cover
    raise SystemExit("Нужен Pillow: pip install pillow")

# порядок как в меню shop_talismans.yml (по CMD)
ORDER = [
    ("life", 1, "talisman_harmony"),
    ("fury", 2, "talisman_daedalus"),
    ("guard", 3, "talisman_triton"),
    ("wanderer", 4, "talisman_discord"),
    ("tank", 5, "talisman_echidna"),
    ("warrior", 6, "talisman_crusher"),
    ("assassin", 7, "talisman_punisher"),
    ("berserk", 8, "talisman_tyrant"),
    ("hunter", 9, "talisman_edge"),
    ("runner", 10, "talisman_phoenix"),
    ("demon", 11, "new_year_talisman_grinch"),
    ("hero", 12, "talisman_hero"),
]
SLUG = {
    "talisman_harmony": "minecraft_item_funtime_items_talisman_harmony",
    "talisman_daedalus": "minecraft_item_funtime_items_talisman_daedalus",
    "talisman_triton": "minecraft_item_funtime_items_talisman_triton",
    "talisman_discord": "minecraft_item_funtime_items_talisman_discord",
    "talisman_echidna": "minecraft_item_funtime_items_talisman_echidna",
    "talisman_crusher": "minecraft_item_funtime_items_talisman_crusher",
    "talisman_punisher": "minecraft_item_funtime_items_talisman_punisher",
    "talisman_tyrant": "minecraft_item_funtime_items_talisman_tyrant",
    "talisman_edge": "minecraft_item_funtime_items_talisman_edge",
    "talisman_phoenix": "minecraft_item_funtime_items_talisman_phoenix",
    "new_year_talisman_grinch": "minecraft_item_funtime_new_year_talisman_grinch",
    "talisman_hero": "minecraft_item_funtime_items_talisman_hero",
}


def main() -> int:
    parser = argparse.ArgumentParser(description="Мокап талисманов «в руке»")
    parser.add_argument("--pack", default="dist/bedrock_pack")
    parser.add_argument("--out", default="dist/preview")
    args = parser.parse_args()

    scale = 7
    cell = 16 * scale
    pad = 26
    cols = 6
    rows = (len(ORDER) + cols - 1) // cols
    width = cols * (cell + pad * 2)
    height = rows * (cell + pad * 2) + 16
    sheet = Image.new("RGBA", (width, height), (22, 20, 26, 255))
    draw = ImageDraw.Draw(sheet)
    try:
        font = ImageFont.truetype("/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf", 14)
    except OSError:
        font = ImageFont.load_default()

    for index, (title, cmd, key) in enumerate(ORDER):
        path = os.path.join(args.pack, "textures", "items", f"{SLUG[key]}.png")
        if not os.path.isfile(path):
            continue
        art = Image.open(path).convert("RGBA").resize((cell, cell), Image.NEAREST)

        col, row = index % cols, index // cols
        x = col * (cell + pad * 2) + pad
        y = row * (cell + pad * 2) + pad

        # золотое ребро = то же, что торец модели в руке (сдвиг вправо-вниз)
        edge = Image.new("RGBA", (cell, cell), (0, 0, 0, 0))
        edge.paste((214, 172, 72, 255), (0, 0, cell, cell), art)
        shadow = Image.new("RGBA", (cell, cell), (0, 0, 0, 0))
        shadow.paste((0, 0, 0, 120), (0, 0, cell, cell), art)
        sheet.alpha_composite(shadow, (x + 7, y + 9))
        sheet.alpha_composite(edge, (x + 6, y + 7))
        sheet.alpha_composite(edge, (x + 3, y + 3))
        sheet.alpha_composite(art, (x, y))
        draw.text((x, y + cell + 6), f"{title} · CMD {cmd}", font=font, fill=(220, 220, 228, 255))

    os.makedirs(args.out, exist_ok=True)
    out_path = os.path.join(args.out, "talismans-hand-mock.png")
    sheet.save(out_path)
    print("OK:", out_path)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
