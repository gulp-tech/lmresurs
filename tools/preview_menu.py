#!/usr/bin/env python3
"""
Превью меню DeluxeMenus так, как их увидит Bedrock-игрок.

Не обязателен для работы сервера — только для проверки «как это выглядит».
Требует Pillow (pip install pillow) и DejaVu-шрифт для кириллицы.

Использование:
    python3 tools/preview_menu.py --menus dist/deluxemenus --only menu,keys --out dist/preview
"""

from __future__ import annotations

import argparse
import json
import os
import re
import sys
from typing import Dict, Optional, Tuple

try:
    from PIL import Image, ImageDraw, ImageFont
except ImportError:  # pragma: no cover
    print("Нужен Pillow: pip install pillow")
    raise SystemExit(2)

CELL = 64
PAD = 12
HEADER = 44
BG = (71, 71, 71, 255)
SLOT = (139, 139, 139, 255)
SLOT_EDGE = (55, 55, 55, 255)
FONT_PATHS = [
    "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
    "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf",
]


def load_font(size: int):
    for path in FONT_PATHS:
        if os.path.isfile(path):
            return ImageFont.truetype(path, size)
    return ImageFont.load_default()


def parse_switch(codes: str) -> Optional[Tuple[int, int, int]]:
    palette = {
        "0": (0, 0, 0), "1": (0, 0, 170), "2": (0, 170, 0), "3": (0, 170, 170),
        "4": (170, 0, 0), "5": (170, 0, 170), "6": (255, 170, 0), "7": (170, 170, 170),
        "8": (85, 85, 85), "9": (85, 85, 255), "a": (85, 255, 85), "b": (85, 255, 255),
        "c": (255, 85, 85), "d": (255, 85, 255), "e": (255, 255, 85), "f": (255, 255, 255),
    }
    color = None
    bold = False
    for i in range(0, len(codes) - 1):
        if codes[i] in "&§":
            code = codes[i + 1].lower()
            if code in palette:
                color = palette[code]
            elif code == "l":
                bold = True
            elif code == "r":
                color, bold = None, False
    return color, bold  # type: ignore[return-value]


def apply_colors(text: str, default=(255, 255, 255)) -> Tuple[str, Tuple[int, int, int], bool]:
    """Снимает коды, возвращает (текст, цвет последнего сегмента, жирный)."""
    clean = []
    color = default
    bold = False
    i = 0
    while i < len(text):
        if text[i] in "&§" and i + 1 < len(text):
            code = text[i + 1].lower()
            palette = {
                "0": (0, 0, 0), "1": (0, 0, 170), "2": (0, 170, 0), "3": (0, 170, 170),
                "4": (170, 0, 0), "5": (170, 0, 170), "6": (255, 170, 0), "7": (170, 170, 170),
                "8": (85, 85, 85), "9": (85, 85, 255), "a": (85, 255, 85), "b": (85, 255, 255),
                "c": (255, 85, 85), "d": (255, 85, 255), "e": (255, 255, 85), "f": (255, 255, 255),
            }
            if code in palette:
                color = palette[code]
            elif code == "l":
                bold = True
            elif code == "r":
                color, bold = default, False
            i += 2
            continue
        clean.append(text[i])
        i += 1
    return "".join(clean), color, bold


def load_mini_yaml(path: str) -> dict:
    """
    Минимальный YAML-парсер под структуру DeluxeMenus (только то, что нужно превью):
    верхний уровень, items: -> ключ -> поля/списки/вложенный model_data_component.
    Без внешних зависимостей.
    """
    data: dict = {"items": {}}
    lines = open(path, encoding="utf-8-sig").read().replace("\r\n", "\n").split("\n")
    section = None
    current = None
    list_key = None
    nested = None

    def as_value(value: str):
        if re.fullmatch(r"-?\d+", value):
            return int(value)
        return value

    for raw in lines:
        if not raw.strip() or raw.lstrip().startswith("#"):
            continue
        indent = len(raw) - len(raw.lstrip())
        text = raw.strip()

        if indent == 0:
            key, _, value = text.partition(":")
            section = key.strip()
            current = None
            list_key = None
            nested = None
            if section != "items":
                data[section] = value.strip().strip("'\"")
            continue

        if section != "items":
            continue

        if indent == 2 and text.endswith(":"):
            key = text[:-1].strip().strip("'\"")
            current = data["items"].setdefault(key, {})
            list_key = None
            nested = None
            continue

        if current is None:
            continue

        # элементы списка
        if text.startswith("- "):
            value = text[2:].strip().strip("'\"")
            if nested and isinstance(current.get(nested), dict):
                continue
            if list_key is not None and isinstance(current.get(list_key), list):
                current[list_key].append(as_value(value))
            continue

        # вложенные поля model_data_component
        if nested and indent >= 6:
            if "floats" in text:
                match = re.search(r"floats\s*:\s*\[\s*(-?\d+)", text)
                if match:
                    current[nested]["floats"] = [int(match.group(1))]
            continue

        if indent >= 4:
            key, _, value = text.partition(":")
            key = key.strip()
            value = value.strip().strip("'\"")
            nested = None
            if not value:
                if key == "model_data_component":
                    current[key] = {}
                    nested = key
                    list_key = None
                else:
                    list_key = key
                    current[key] = []
            else:
                list_key = None
                current[key] = as_value(value)
            continue
    return data


def load_icons(pack_dir: str, mappings_path: str) -> Tuple[Dict[str, str], Dict[str, Dict[int, str]]]:
    icons: Dict[str, str] = {}
    textures_dir = os.path.join(pack_dir, "textures", "items")
    if os.path.isdir(textures_dir):
        for name in os.listdir(textures_dir):
            if name.endswith(".png"):
                icons[name[:-4]] = os.path.join(textures_dir, name)
    legacy: Dict[str, Dict[int, str]] = {}
    if os.path.isfile(mappings_path):
        data = json.load(open(mappings_path, encoding="utf-8"))
        for item, defs in data.get("items", {}).items():
            for definition in defs:
                cmd = definition.get("custom_model_data")
                icon = (definition.get("bedrock_options") or {}).get("icon")
                if cmd is not None and icon:
                    legacy.setdefault(item, {})[int(cmd)] = icon
    return icons, legacy


def render(menu_path: str, icons, legacy, out_path: str, scale: int = 2) -> None:
    menu = load_mini_yaml(menu_path)
    size = int(menu.get("size") or 54)
    rows = max(1, size // 9)
    cols = 9
    title_text, title_color, _ = apply_colors(str(menu.get("menu_title", "")), (255, 255, 255))

    width = cols * (CELL + 4) + PAD * 2
    height = HEADER + rows * (CELL + 4) + PAD
    sheet = Image.new("RGBA", (width, height), BG)
    draw = ImageDraw.Draw(sheet)

    title_font = load_font(20)
    name_font = load_font(11)

    draw.text((PAD, 10), title_text, font=title_font, fill=title_color)

    slots: Dict[int, dict] = {}
    for key, item in menu["items"].items():
        slot_list = item.get("slots") or ([item["slot"]] if "slot" in item else [])
        for slot in slot_list:
            if isinstance(slot, int) and 0 <= slot < size:
                slots[slot] = (key, item)

    for slot in range(size):
        row, col = divmod(slot, cols)
        x = PAD + col * (CELL + 4)
        y = HEADER + row * (CELL + 4)

        draw.rectangle([x, y, x + CELL, y + CELL], fill=SLOT, outline=SLOT_EDGE)
        entry = slots.get(slot)
        if not entry:
            continue
        key, item = entry

        material = str(item.get("material", "")).upper()
        cmd = None
        component = item.get("model_data_component")
        if isinstance(component, dict):
            floats = component.get("floats") or []
            if floats:
                cmd = int(floats[0])
        elif isinstance(item.get("custom_model_data"), str):
            cmd = int(item["custom_model_data"])

        icon_path = None
        if cmd is not None:
            icon_name = legacy.get(f"minecraft:{material.lower()}", {}).get(cmd)
            if icon_name:
                icon_path = icons.get(icon_name)
        if icon_path and os.path.isfile(icon_path):
            icon = Image.open(icon_path).convert("RGBA")
            icon = icon.resize((CELL - 12, CELL - 12), Image.NEAREST)
            sheet.alpha_composite(icon, (x + 6, y + 6))
        else:
            draw.rectangle([x + 8, y + 8, x + CELL - 8, y + CELL - 8], fill=(60, 60, 60))
            label = material.replace("_", " ")[:10]
            draw.text((x + 10, y + CELL // 2 - 6), label, font=name_font, fill=(220, 220, 220))

    sheet = sheet.resize((width * scale, height * scale), Image.NEAREST)
    os.makedirs(os.path.dirname(out_path), exist_ok=True)
    sheet.save(out_path)
    print(f"{os.path.basename(menu_path)} -> {out_path}")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--menus", default="dist/deluxemenus")
    parser.add_argument("--pack", default="dist/bedrock_pack")
    parser.add_argument("--mappings", default="dist/geyser_mappings.json")
    parser.add_argument("--out", default="dist/preview")
    parser.add_argument("--only", default="", help="список имён без .yml через запятую")
    args = parser.parse_args()

    icons, legacy = load_icons(args.pack, args.mappings)
    only = {x.strip() for x in args.only.split(",") if x.strip()}
    for name in sorted(os.listdir(args.menus)):
        if not name.endswith(".yml"):
            continue
        if only and name[:-4] not in only:
            continue
        render(
            os.path.join(args.menus, name),
            icons,
            legacy,
            os.path.join(args.out, name.replace(".yml", ".png")),
        )
    return 0


if __name__ == "__main__":
    sys.exit(main())
