#!/usr/bin/env python3
"""
Портирование меню DeluxeMenus под Bedrock (Geyser + Floodgate).

Меняет ТОЛЬКО оформление, функционал не трогает:
  * иконки предметов -> ванильный материал + model_data_component (Geyser подменит
    текстуру из funtime.mcpack через geyser_mappings.json);
  * hex-цвета (&#RRGGBB и &x&R&R&G&G&B&B) -> ближайший из 16 ванильных цветов,
    потому что Bedrock hex не поддерживает;
  * size: 45 -> 54 (Bedrock рисует только 3 и 6 рядов, 5 рядов даёт пустые слоты)
    и добор декоративной рамки в освободившийся 6-й ряд;
  * ничего из click_commands / requirements / open_command / placeholders не меняется.

Использование:
    python3 tools/port_deluxemenus.py --src menus --out dist/deluxemenus
"""

from __future__ import annotations

import argparse
import json
import os
import re
import sys
from typing import Dict, List, Optional, Tuple

# --------------------------------------------------------------------------------------
# Палитра: hex -> ближайший legacy-цвет Minecraft
# --------------------------------------------------------------------------------------

LEGACY_PALETTE: List[Tuple[str, Tuple[int, int, int]]] = [
    ("0", (0, 0, 0)),
    ("1", (0, 0, 170)),
    ("2", (0, 170, 0)),
    ("3", (0, 170, 170)),
    ("4", (170, 0, 0)),
    ("5", (170, 0, 170)),
    ("6", (255, 170, 0)),
    ("7", (170, 170, 170)),
    ("8", (85, 85, 85)),
    ("9", (85, 85, 255)),
    ("a", (85, 255, 85)),
    ("b", (85, 255, 255)),
    ("c", (255, 85, 85)),
    ("d", (255, 85, 255)),
    ("e", (255, 255, 85)),
    ("f", (255, 255, 255)),
]


def nearest_legacy(r: int, g: int, b: int) -> str:
    best_code, best_dist = "f", None
    for code, (pr, pg, pb) in LEGACY_PALETTE:
        dist = (r - pr) ** 2 + (g - pg) ** 2 + (b - pb) ** 2
        if best_dist is None or dist < best_dist:
            best_code, best_dist = code, dist
    return best_code


HEX_AMP = re.compile(r"&x((?:&[0-9a-fA-F]){6})")
HEX_SECTION = re.compile(r"§x((?:§[0-9a-fA-F]){6})")
HEX_HASH_AMP = re.compile(r"&#([0-9a-fA-F]{6})")
HEX_HASH_SECTION = re.compile(r"§#([0-9a-fA-F]{6})")


def _codes_to_rgb(codes: str, sep: str) -> Tuple[int, int, int]:
    nibbles = [c for c in codes.replace(sep, "")]
    return (
        int("".join(nibbles[0:2]), 16),
        int("".join(nibbles[2:4]), 16),
        int("".join(nibbles[4:6]), 16),
    )


def normalize_colors(text: str) -> Tuple[str, int]:
    """hex -> legacy. Возвращает (текст, сколько замен)."""
    count = 0

    def repl_amp_x(match: "re.Match") -> str:
        nonlocal count
        count += 1
        return "&" + nearest_legacy(*_codes_to_rgb(match.group(1), "&"))

    def repl_section_x(match: "re.Match") -> str:
        nonlocal count
        count += 1
        return "§" + nearest_legacy(*_codes_to_rgb(match.group(1), "§"))

    def repl_hash_amp(match: "re.Match") -> str:
        nonlocal count
        count += 1
        return "&" + nearest_legacy(*_hex(match.group(1)))

    def repl_hash_section(match: "re.Match") -> str:
        nonlocal count
        count += 1
        return "§" + nearest_legacy(*_hex(match.group(1)))

    text = HEX_AMP.sub(repl_amp_x, text)
    text = HEX_SECTION.sub(repl_section_x, text)
    text = HEX_HASH_AMP.sub(repl_hash_amp, text)
    text = HEX_HASH_SECTION.sub(repl_hash_section, text)
    return text, count


COLOR_CODES = set("0123456789abcdef")
FORMAT_CODES = set("klmnor")


def collapse_colors(text: str) -> Tuple[str, int]:
    """
    Убирает повторные одинаковые цветовые коды, которые появляются после
    схлопывания градиента (&x&R&R&G&G&B&B) в один legacy-цвет.
    """
    out: List[str] = []
    active: Optional[str] = None
    removed = 0
    i = 0
    while i < len(text):
        char = text[i]
        if char in "&§" and i + 1 < len(text):
            code = text[i + 1].lower()
            if code in COLOR_CODES:
                if code == active:
                    removed += 1
                    i += 2
                    continue
                active = code
                out.append(text[i : i + 2])
                i += 2
                continue
            if code in FORMAT_CODES:
                if code == "r":
                    active = None
                out.append(text[i : i + 2])
                i += 2
                continue
        out.append(char)
        i += 1
    return "".join(out), removed


def _hex(value: str) -> Tuple[int, int, int]:
    return int(value[0:2], 16), int(value[2:4], 16), int(value[4:6], 16)


# --------------------------------------------------------------------------------------
# Оформление: что на что менять (только косметика)
# --------------------------------------------------------------------------------------
# (материал, custom model data из FunTime-пака) либо None = оставить как есть
ICON_OVERRIDES: Dict[str, Dict[str, Tuple[str, int]]] = {
    "menu.yml": {
        # player-head в Bedrock не отображается как кастомная голова -> иконка «инфо»
        "Информация": ("PAPER", 22),
        "Донат-магазин /shop": ("PAPER", 29),
    },
    "keys.yml": {
        "Ключ Талисманов": ("TRIAL_KEY", 14),
        "Ключ Ресурсов": ("TRIAL_KEY", 3),
        "Ключ Сфер": ("TRIAL_KEY", 13),
    },
    "shop.yml": {
        "Раздел талисманы": ("PAPER", 42),
    },
    "shop_items.yml": {
        "Капкан": ("PAPER", 40),
    },
    "shop_potions.yml": {
        "Зелье ярости": ("POTION", 1),
        "Боевое зелье": ("POTION", 2),
        "Зелье скорости": ("POTION", 3),
        "Зелье защиты": ("POTION", 4),
    },
    # 11 иконок талисманов в паке на 12 талисманов меню -> один повтор
    "shop_talismans.yml": {
        "Талисман воина": ("TOTEM_OF_UNDYING", 6),
        "Талисман берсерка": ("TOTEM_OF_UNDYING", 8),
        "Талисман убийцы": ("TOTEM_OF_UNDYING", 7),
        "Талисман ярости": ("TOTEM_OF_UNDYING", 2),
        "Талисман бегуна": ("TOTEM_OF_UNDYING", 10),
        "Талисман охотника": ("TOTEM_OF_UNDYING", 9),
        "Талисман странника": ("TOTEM_OF_UNDYING", 4),
        "Талисман жизни": ("TOTEM_OF_UNDYING", 1),
        "Талисман танка": ("TOTEM_OF_UNDYING", 5),
        "Талисман стража": ("TOTEM_OF_UNDYING", 3),
        "Талисман демона": ("TOTEM_OF_UNDYING", 11),
        "Талисман героя": ("TOTEM_OF_UNDYING", 2),
    },
}

# Рамки, которым нужно добрать 6-й ряд после перехода 45 -> 54
FRAME_ITEMS = {"Рамка", "border_black", "border_purple", "filler_gray"}

# Меню, которым нужна декоративная рамка: слотов много, предметов мало
BORDER_FILES = {"menu.yml"}
BORDER_MATERIAL = "BLACK_STAINED_GLASS_PANE"
BORDER_KEY = "Рамка"

MODEL_KEYS = ("model_data", "custom-model-data", "model_data_component", "item_model", "nbt_int")

ITEM_KEY_RE = re.compile(r"^  ([^\s#][^:]*):\s*$")


def unquote(key: str) -> str:
    key = key.strip()
    if len(key) >= 2 and key[0] == key[-1] and key[0] in "'\"":
        return key[1:-1]
    return key


def split_items(lines: List[str]) -> Tuple[int, List[Tuple[str, int, int]]]:
    """Возвращает индекс строки 'items:' и список (ключ, начало, конец) блоков."""
    items_idx = next((i for i, l in enumerate(lines) if l.rstrip() == "items:"), -1)
    if items_idx < 0:
        return -1, []
    blocks: List[Tuple[str, int]] = []
    for i in range(items_idx + 1, len(lines)):
        line = lines[i]
        if line and not line.startswith(" ") and not line.startswith("#"):
            break
        match = ITEM_KEY_RE.match(line)
        if match:
            blocks.append((unquote(match.group(1)), i))
    result: List[Tuple[str, int, int]] = []
    for idx, (key, start) in enumerate(blocks):
        end = blocks[idx + 1][1] if idx + 1 < len(blocks) else len(lines)
        # хвост файла обрезаем по первой строке без отступа
        for j in range(start + 1, end):
            if lines[j] and not lines[j].startswith(" ") and not lines[j].startswith("#"):
                end = j
                break
        result.append((key, start, end))
    return items_idx, result


def set_icon(block: List[str], material: str, cmd: int) -> List[str]:
    """Заменяет material и model_data_component внутри блока предмета."""
    out: List[str] = []
    i = 0
    replaced_material = False
    while i < len(block):
        line = block[i]
        stripped = line.strip()
        key = stripped.split(":", 1)[0] if ":" in stripped else ""

        # удаляем старые варианты указания модели (включая многострочный component)
        if key in MODEL_KEYS and line.startswith("    "):
            i += 1
            # пропускаем вложенные строки (model_data_component может быть словарём)
            while i < len(block) and (block[i].startswith("      ") or not block[i].strip()):
                if block[i].strip() and not block[i].startswith("      "):
                    break
                i += 1
            continue

        if key == "material" and line.startswith("    ") and not replaced_material:
            indent = line[: len(line) - len(line.lstrip())]
            out.append(f"{indent}material: {material}")
            out.append(f"{indent}model_data_component:")
            out.append(f"{indent}  floats: [{cmd}]")
            replaced_material = True
            i += 1
            continue
        out.append(line)
        i += 1
    return out


def add_frame_slots(block: List[str], new_slots: List[int]) -> List[str]:
    """Добавляет слоты в список slots: (сохраняя отступ)."""
    out: List[str] = []
    list_indent: Optional[str] = None
    inserted = False
    for i, line in enumerate(block):
        out.append(line)
        stripped = line.strip()
        if stripped.startswith(("slots:", "slot:")) and not inserted:
            indent = line[: len(line) - len(line.lstrip())]
            list_indent = indent
        elif list_indent and stripped.startswith("- ") and not inserted:
            continue
        elif list_indent and not stripped.startswith("- ") and not inserted and stripped:
            # конец списка — вставляем свои слоты перед этой строкой
            back = out.pop()
            for slot in new_slots:
                out.append(f"{list_indent}  - {slot}")
            out.append(back)
            inserted = True
    if not inserted and list_indent:
        for slot in new_slots:
            out.append(f"{list_indent}  - {slot}")
        inserted = True
    return out


def used_slots(lines: List[str]) -> set:
    """Собирает все занятые слоты (slot: N и slots: [...])."""
    _, blocks = split_items(lines)
    used = set()
    for _key, start, end in blocks:
        block = lines[start:end]
        in_list = False
        for line in block:
            stripped = line.strip()
            if stripped.startswith("slots:"):
                in_list = True
                inline = stripped[len("slots:"):].strip()
                if inline.startswith("["):
                    for part in inline.strip("[]").split(","):
                        if part.strip().isdigit():
                            used.add(int(part.strip()))
                continue
            if stripped.startswith("slot:"):
                value = stripped[len("slot:"):].strip()
                if value.isdigit():
                    used.add(int(value))
                in_list = False
                continue
            if in_list and stripped.startswith("- "):
                value = stripped[2:].strip()
                if value.isdigit():
                    used.add(int(value))
                continue
            if stripped and not stripped.startswith("- "):
                in_list = False
    return used


def add_border(lines: List[str], size: int) -> int:
    """Добавляет декоративную рамку по периметру в свободные слоты."""
    used = used_slots(lines)
    perimeter = []
    rows = size // 9
    for slot in range(size):
        row, col = divmod(slot, 9)
        if row in (0, rows - 1) or col in (0, 8):
            perimeter.append(slot)
    free = [slot for slot in perimeter if slot not in used]
    if not free:
        return 0

    block = [f"  '{BORDER_KEY}':", "    slots:"]
    block += [f"    - {slot}" for slot in free]
    block += [
        f"    material: {BORDER_MATERIAL}",
        "    display_name: '&8'",
    ]

    items_idx = next((i for i, l in enumerate(lines) if l.rstrip() == "items:"), -1)
    if items_idx < 0:
        return 0
    # вставляем в конец списка items (последняя непустая строка блока items)
    insert_at = len(lines)
    for i in range(items_idx + 1, len(lines)):
        if lines[i] and not lines[i].startswith(" ") and not lines[i].startswith("#"):
            insert_at = i
            break
    while insert_at - 1 > items_idx and not lines[insert_at - 1].strip():
        insert_at -= 1
    lines[insert_at:insert_at] = ["", *block]
    return len(free)


def port_file(path: str) -> Tuple[List[str], dict]:
    raw = open(path, "r", encoding="utf-8-sig").read().replace("\r\n", "\n")
    lines = raw.split("\n")
    if lines and lines[-1] == "":
        lines = lines[:-1]

    name = os.path.basename(path)
    report = {
        "file": name,
        "size_before": None,
        "size_after": None,
        "icons": [],
        "color_replacements": 0,
        "color_codes_collapsed": 0,
        "frame_slots_added": 0,
        "border_added": 0,
        "warnings": [],
    }

    # --- размер меню ---------------------------------------------------------------
    for i, line in enumerate(lines):
        match = re.match(r"^size:\s*(\d+)\s*$", line)
        if match:
            size = int(match.group(1))
            report["size_before"] = size
            if size == 45:
                lines[i] = "size: 54"
                report["size_after"] = 54
            else:
                report["size_after"] = size
            break

    # --- иконки -------------------------------------------------------------------
    overrides = ICON_OVERRIDES.get(name, {})
    items_idx, blocks = split_items(lines)
    if overrides and items_idx < 0:
        report["warnings"].append("не найден блок items:")
    if overrides:
        new_lines: List[str] = []
        cursor = 0
        for key, start, end in blocks:
            new_lines.extend(lines[cursor:start])
            block = lines[start:end]
            if key in overrides:
                material, cmd = overrides[key]
                block = set_icon(block, material, cmd)
                report["icons"].append(
                    {"item": key, "material": material, "custom_model_data": cmd}
                )
            new_lines.extend(block)
            cursor = end
        new_lines.extend(lines[cursor:])
        lines = new_lines
        items_idx, blocks = split_items(lines)

    # --- добор рамки под 6 рядов ---------------------------------------------------
    if report["size_before"] == 45 and report["size_after"] == 54:
        new_lines = []
        cursor = 0
        for key, start, end in blocks:
            new_lines.extend(lines[cursor:start])
            block = lines[start:end]
            if key in FRAME_ITEMS:
                if key == "border_purple":
                    extra = [45, 53]
                elif key == "border_black":
                    extra = [46, 47, 48, 49, 50, 51, 52]
                elif key == "filler_gray":
                    extra = []
                else:  # 'Рамка' — сплошная рамка по периметру
                    extra = [45, 46, 47, 48, 49, 50, 51, 52, 53]
                if extra:
                    block = add_frame_slots(block, extra)
                    report["frame_slots_added"] += len(extra)
            new_lines.extend(block)
            cursor = end
        new_lines.extend(lines[cursor:])
        lines = new_lines

    # --- декоративная рамка для «пустых» меню ---------------------------------------
    if name in BORDER_FILES and report["size_after"]:
        added = add_border(lines, report["size_after"])
        report["border_added"] = added

    # --- hex -> legacy -------------------------------------------------------------
    converted: List[str] = []
    total = 0
    collapsed = 0
    for line in lines:
        new_line, count = normalize_colors(line)
        total += count
        new_line, removed = collapse_colors(new_line)
        collapsed += removed
        converted.append(new_line)
    report["color_replacements"] = total
    report["color_codes_collapsed"] = collapsed

    text = "\n".join(converted) + "\n"

    # --- предупреждения ------------------------------------------------------------
    for model in set(re.findall(r"item_model:\s*(\S+)", text)):
        if model.startswith("voidspheres:"):
            report["warnings"].append(
                f"item_model {model} — модели нет в FunTime-паке, на Bedrock будет бумага"
            )
    if "head-%" in text:
        report["warnings"].append("материал head-%player_name% — кастомные головы в Bedrock не рисуются")
    if re.search(r"&x(?:&[0-9a-fA-F]){6}", text):
        report["warnings"].append("остались необработанные hex-коды")

    return text.split("\n")[:-1], report


def main(argv: Optional[List[str]] = None) -> int:
    parser = argparse.ArgumentParser(description="Порт DeluxeMenus-меню под Bedrock")
    parser.add_argument("--src", default="menus", help="каталог с исходными меню")
    parser.add_argument("--out", default="dist/deluxemenus", help="каталог результата")
    args = parser.parse_args(argv)

    src = os.path.abspath(args.src)
    out = os.path.abspath(args.out)
    if not os.path.isdir(src):
        print(f"Нет каталога {src}")
        return 2
    os.makedirs(out, exist_ok=True)

    reports = []
    for name in sorted(os.listdir(src)):
        if not name.endswith((".yml", ".yaml")):
            continue
        text_lines, report = port_file(os.path.join(src, name))
        with open(os.path.join(out, name), "w", encoding="utf-8") as fh:
            fh.write("\n".join(text_lines) + "\n")
        reports.append(report)
        print(
            f"{name}: size {report['size_before']}->{report['size_after']}, "
            f"иконок {len(report['icons'])}, hex→legacy {report['color_replacements']}, "
            f"схлопнуто {report.get('color_codes_collapsed', 0)}, "
            f"рамка +{report['frame_slots_added']}"
        )
        for warning in report["warnings"]:
            print(f"   ! {warning}")

    with open(os.path.join(out, "PORT_REPORT.json"), "w", encoding="utf-8") as fh:
        json.dump(reports, fh, ensure_ascii=False, indent=2)

    lines = ["# Порт меню DeluxeMenus под Bedrock", ""]
    lines.append("| Меню | size | Иконок заменено | hex→legacy | Повторов цвета убрано | Рамка +слотов |")
    lines.append("| --- | --- | --- | --- | --- | --- |")
    for report in reports:
        lines.append(
            f"| `{report['file']}` | {report['size_before']} → {report['size_after']} "
            f"| {len(report['icons'])} | {report['color_replacements']} "
            f"| {report.get('color_codes_collapsed', 0)} | {report['frame_slots_added']} |"
        )
    lines += ["", "## Заменённые иконки", ""]
    for report in reports:
        if not report["icons"]:
            continue
        lines.append(f"### `{report['file']}`")
        lines.append("")
        lines.append("| Предмет | Материал | model_data_component |")
        lines.append("| --- | --- | --- |")
        for icon in report["icons"]:
            lines.append(
                f"| {icon['item']} | `{icon['material']}` | `floats: [{icon['custom_model_data']}]` |"
            )
        lines.append("")
    warnings = [(r["file"], w) for r in reports for w in r["warnings"]]
    if warnings:
        lines += ["## Предупреждения", ""]
        for file, warning in warnings:
            lines.append(f"- `{file}`: {warning}")
    with open(os.path.join(out, "PORT_REPORT.md"), "w", encoding="utf-8") as fh:
        fh.write("\n".join(lines) + "\n")

    print(f"\nГотово: {out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
