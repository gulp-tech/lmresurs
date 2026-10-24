#!/usr/bin/env python3
"""
Талисманы для Bedrock: собственные текстуры и объёмная фигурка в руке.

Две задачи, которые решает модуль:

  1. ИКОНКИ. В Java-паке FunTime 11 иконок талисманов, и они почти не отличаются
     друг от друга при взгляде на 16×16 в слоте меню. Здесь каждая фигурка
     рисуется заново по общей базе (голова, плечи, руки, пояс, ноги) и получает
     свой цвет, свой головной убор, эмблему на груди и снаряжение — корону,
     рога, капюшон, шлем, шляпу, щит, посох, меч, топор, кинжалы, лук, крылья,
     плащ, наплечники. 12-й талисман («герой») есть только здесь.

  2. РУКА. Плоская картинка, привязанная к руке «своей» геометрией, в Bedrock
     встаёт криво — это и было видно на скриншоте. Поэтому используется
     ванильный приём: кость `rightitem` + `texture_meshes` (так сделан лук) и
     ванильная же анимация `animation.bow.wield`, которая правильно держит
     плоский предмет в руке. Две плоскости (лицом и «спиной») — чтобы фигурку
     было видно с любой стороны.

Зависимости: только стандартная библиотека Python 3.
"""

from __future__ import annotations

import json
import os
import struct
import zlib
from typing import Dict, List, Optional, Tuple

W = H = 16

# --------------------------------------------------------------------------------------
# Палитры и снаряжение: slug -> описание
# --------------------------------------------------------------------------------------

TALISMANS: Dict[str, dict] = {
    # id в Java-паке : (смысл в меню)
    "harmony": dict(base=(70, 175, 95), accent=(245, 200, 80), eye=(235, 255, 240),
                    top="halo", emblem="cross", gear=None),          # Талисман жизни
    "daedalus": dict(base=(205, 50, 55), accent=(255, 190, 70), eye=(255, 240, 130),
                     top="horns", emblem="bolt", gear=None),         # Талисман ярости
    "triton": dict(base=(70, 120, 205), accent=(235, 240, 250), eye=(220, 245, 255),
                   top="helm", emblem="eye", gear="shield"),         # Талисман стража
    "discord": dict(base=(130, 80, 195), accent=(245, 200, 80), eye=(250, 240, 255),
                    top="hood", emblem="gem", gear="staff"),         # Талисман странника
    "echidna": dict(base=(125, 132, 145), accent=(240, 190, 80), eye=(255, 250, 230),
                    top="helm", emblem="plate", gear="pauldrons"),   # Талисман танка
    "crusher": dict(base=(195, 55, 65), accent=(245, 200, 80), eye=(255, 240, 210),
                    top="crown", emblem="sword", gear="sword"),      # Талисман воина
    "punisher": dict(base=(48, 95, 70), accent=(205, 210, 215), eye=(140, 255, 180),
                     top="hood", emblem="dagger", gear="daggers"),   # Талисман убийцы
    "tyrant": dict(base=(215, 110, 45), accent=(255, 225, 120), eye=(255, 240, 150),
                   top="horns", emblem="flame", gear="axe"),         # Талисман берсерка
    "edge": dict(base=(140, 95, 55), accent=(235, 205, 120), eye=(190, 255, 170),
                 top="hat", emblem="moon", gear="bow"),              # Талисман охотника
    "phoenix": dict(base=(60, 165, 205), accent=(245, 250, 255), eye=(255, 255, 255),
                    top="none", emblem="wing", gear="wings"),        # Талисман бегуна
    "grinch": dict(base=(120, 25, 45), accent=(255, 60, 60), eye=(255, 60, 60),
                   top="horns", emblem="skull", gear="wings"),       # Талисман демона
    "hero": dict(base=(228, 180, 60), accent=(255, 255, 240), eye=(255, 255, 255),
                 top="crown", emblem="star", gear="cape"),           # Талисман героя
}

# база фигурки: h — голова, e — глаз, b — тело, a — руки, x — пояс, l — ноги
SKELETON = [
    "................",
    ".....hhhhhh.....",
    "....hhhhhhhh....",
    "....hheehhee....",
    "....hhhhhhhh....",
    "....hhhhhhhh....",
    ".....hhhhhh.....",
    "...aabbbbbbaa...",
    "...aabbbbbbaa...",
    "...aabbbbbbaa...",
    "....bxxxxxxb....",
    "....bbbbbbbb....",
    "....bbbbbbbb....",
    "....llll.llll...",
    "....lll...lll...",
    "....ll.....ll...",
]

GOLD = (245, 200, 80, 255)
STEEL = (212, 218, 228, 255)
WOOD = (120, 80, 45, 255)
BONE = (238, 228, 205, 255)


def _rgba(color: Tuple[int, ...]) -> Tuple[int, int, int, int]:
    """Палитры заданы тремя числами — здесь добавляем альфу."""
    if len(color) == 4:
        return color  # type: ignore[return-value]
    return (color[0], color[1], color[2], 255)


def _tint(color: Tuple[int, int, int], k: float) -> Tuple[int, int, int, int]:
    return (
        max(0, min(255, int(color[0] * k))),
        max(0, min(255, int(color[1] * k))),
        max(0, min(255, int(color[2] * k))),
        255,
    )


# --------------------------------------------------------------------------------------
# Рисование иконки
# --------------------------------------------------------------------------------------

Grid = List[List[Optional[Tuple[int, int, int, int]]]]


def _blank() -> Grid:
    return [[None] * W for _ in range(H)]


def _put(grid: Grid, x: int, y: int, color: Optional[Tuple[int, int, int, int]]) -> None:
    if 0 <= x < W and 0 <= y < H:
        grid[y][x] = color


def _gear(grid: Grid, kind: Optional[str], base: Tuple[int, int, int]) -> None:
    """Снаряжение рисуется до объёма, чтобы тени легли и на него."""
    if kind == "shield":
        for y in range(6, 11):
            _put(grid, 0, y, _tint(STEEL, 0.7))
            _put(grid, 1, y, STEEL)
            _put(grid, 2, y, _tint(STEEL, 0.7))
        for x in (0, 1, 2):
            _put(grid, x, 6, GOLD)
            _put(grid, x, 10, GOLD)
    elif kind == "staff":
        for y in range(3, 12):
            _put(grid, 14, y, WOOD)
        _put(grid, 14, 2, (190, 140, 255, 255))
        _put(grid, 13, 2, (150, 100, 220, 255))
        _put(grid, 15, 2, (150, 100, 220, 255))
    elif kind == "sword":
        for y in range(3, 9):
            _put(grid, 14, y, STEEL)
            _put(grid, 13, y, _tint(STEEL, 0.7))
        for x in (13, 14, 15):
            _put(grid, x, 9, GOLD)
        _put(grid, 14, 10, WOOD)
        _put(grid, 14, 11, WOOD)
    elif kind == "axe":
        for x in (13, 14):
            _put(grid, x, 4, STEEL)
            _put(grid, x, 5, STEEL)
        _put(grid, 14, 6, _tint(STEEL, 0.8))
        for y in range(6, 12):
            _put(grid, 14, y, WOOD)
    elif kind == "daggers":
        for y in range(5, 8):
            _put(grid, 14, y, STEEL)
            _put(grid, 15, y, _tint(STEEL, 0.7))
        _put(grid, 14, 8, GOLD)
        for y in range(5, 8):
            _put(grid, 1, y, STEEL)
            _put(grid, 0, y, _tint(STEEL, 0.7))
        _put(grid, 1, 8, GOLD)
    elif kind == "bow":
        for y in range(4, 11):
            _put(grid, 1, y, WOOD)
        _put(grid, 2, 3, WOOD)
        _put(grid, 2, 11, WOOD)
        _put(grid, 1, 3, _tint(WOOD, 1.3))
        _put(grid, 1, 11, _tint(WOOD, 1.3))
        for y in range(4, 11):
            _put(grid, 2, y, (225, 225, 225, 255))
    elif kind == "wings":
        for y in range(5, 11):
            _put(grid, 1, y, _tint(base, 1.15))
            _put(grid, 2, y, _tint(base, 0.88))
            _put(grid, 14, y, _tint(base, 1.15))
            _put(grid, 13, y, _tint(base, 0.88))
    elif kind == "cape":
        for y in range(6, 13):
            _put(grid, 2, y, GOLD if y % 2 else _tint(base, 1.2))
            _put(grid, 13, y, GOLD if y % 2 else _tint(base, 1.2))
    elif kind == "pauldrons":
        for x in (2, 3):
            _put(grid, x, 7, STEEL)
            _put(grid, x, 8, _tint(STEEL, 0.8))
        for x in (12, 13):
            _put(grid, x, 7, STEEL)
            _put(grid, x, 8, _tint(STEEL, 0.8))


def _top(grid: Grid, kind: str, base: Tuple[int, int, int]) -> None:
    if kind == "crown":
        for x in range(4, 12):
            _put(grid, x, 0, GOLD)
        for x in (4, 7, 8, 11):
            _put(grid, x, 1, _tint(GOLD, 0.7))
    elif kind == "horns":
        _put(grid, 3, 1, _tint(BONE, 0.75))
        _put(grid, 3, 0, BONE)
        _put(grid, 2, 0, _tint(BONE, 0.9))
        _put(grid, 12, 1, _tint(BONE, 0.75))
        _put(grid, 12, 0, BONE)
        _put(grid, 13, 0, _tint(BONE, 0.9))
    elif kind == "hood":
        for x in range(3, 13):
            _put(grid, x, 0, _tint(base, 0.55))
        for y in (1, 2):
            _put(grid, 3, y, _tint(base, 0.6))
            _put(grid, 12, y, _tint(base, 0.6))
    elif kind == "helm":
        for x in range(4, 12):
            _put(grid, x, 0, _tint(STEEL, 0.95))
        _put(grid, 7, 0, GOLD)
        _put(grid, 8, 0, GOLD)
    elif kind == "halo":
        for x in range(5, 11):
            _put(grid, x, 0, (255, 245, 170, 255))
        _put(grid, 5, 0, (205, 255, 210, 255))
        _put(grid, 10, 0, (205, 255, 210, 255))
    elif kind == "hat":
        for x in range(2, 14):
            _put(grid, x, 0, _tint(base, 0.7))
        for x in range(4, 12):
            _put(grid, x, 1, _tint(base, 0.55))
        _put(grid, 2, 0, GOLD)
        _put(grid, 13, 0, GOLD)


def _emblem(grid: Grid, kind: str) -> None:
    white = (250, 250, 250, 255)
    if kind == "cross":
        for x in (7, 8):
            _put(grid, x, 8, white)
            _put(grid, x, 9, white)
        _put(grid, 7, 10, white)
        _put(grid, 8, 10, white)
    elif kind == "bolt":
        _put(grid, 8, 8, GOLD)
        _put(grid, 7, 9, GOLD)
        _put(grid, 8, 9, GOLD)
        _put(grid, 9, 10, _tint(GOLD, 0.8))
    elif kind == "eye":
        _put(grid, 7, 9, white)
        _put(grid, 8, 9, white)
        _put(grid, 8, 9, (60, 90, 140, 255))
    elif kind == "gem":
        _put(grid, 7, 8, GOLD)
        _put(grid, 8, 8, GOLD)
        _put(grid, 7, 9, _tint(GOLD, 0.75))
        _put(grid, 8, 9, _tint(GOLD, 0.75))
    elif kind == "plate":
        for x in (6, 7, 8, 9):
            _put(grid, x, 9, _tint(STEEL, 0.9))
    elif kind == "sword":
        for y in (8, 9, 10):
            _put(grid, 8, y, STEEL)
        for x in (7, 8, 9):
            _put(grid, x, 11, GOLD)
    elif kind == "dagger":
        _put(grid, 7, 8, STEEL)
        _put(grid, 7, 9, STEEL)
        _put(grid, 7, 10, GOLD)
        _put(grid, 9, 8, STEEL)
        _put(grid, 9, 9, STEEL)
        _put(grid, 9, 10, GOLD)
    elif kind == "flame":
        _put(grid, 8, 8, (255, 170, 60, 255))
        _put(grid, 7, 9, (255, 170, 60, 255))
        _put(grid, 8, 9, (255, 120, 40, 255))
        _put(grid, 8, 10, (255, 90, 40, 255))
    elif kind == "moon":
        _put(grid, 7, 8, (245, 240, 200, 255))
        _put(grid, 7, 9, (245, 240, 200, 255))
        _put(grid, 8, 8, (245, 240, 200, 255))
    elif kind == "wing":
        for x in (6, 7, 8, 9):
            _put(grid, x, 8, white)
        for x in (7, 8):
            _put(grid, x, 9, _tint(white, 0.85))
    elif kind == "skull":
        _put(grid, 7, 8, (240, 240, 235, 255))
        _put(grid, 8, 8, (240, 240, 235, 255))
        _put(grid, 7, 9, (70, 70, 70, 255))
        _put(grid, 8, 9, (70, 70, 70, 255))
    elif kind == "star":
        _put(grid, 8, 8, GOLD)
        for x in (7, 8, 9):
            _put(grid, x, 9, GOLD)
        _put(grid, 8, 10, _tint(GOLD, 0.8))


def render_icon(slug: str) -> Tuple[int, int, bytearray]:
    """Рисует 16×16 иконку талисмана; возвращает (w, h, RGBA)."""
    spec = TALISMANS[slug]
    base = spec["base"]
    colors = dict(
        h=_rgba(base),
        b=_rgba(base),
        l=_tint(base, 0.78),
        a=_tint(base, 0.9),
        e=_rgba(spec["eye"]),
        x=_rgba(spec["accent"]),
    )

    grid: Grid = _blank()
    for y, row in enumerate(SKELETON):
        for x, ch in enumerate(row):
            if ch != ".":
                grid[y][x] = colors[ch]

    _gear(grid, spec["gear"], base)
    _top(grid, spec["top"], base)

    # объём: тёмная кромка справа/снизу, светлая — слева/сверху
    def solid(x: int, y: int) -> bool:
        return 0 <= x < W and 0 <= y < H and grid[y][x] is not None

    shaded: Grid = _blank()
    for y in range(H):
        for x in range(W):
            color = grid[y][x]
            if color is None:
                continue
            if not solid(x + 1, y) or not solid(x, y + 1):
                shaded[y][x] = _tint(color, 0.58)
            elif not solid(x - 1, y) or not solid(x, y - 1):
                shaded[y][x] = _tint(color, 1.22)
            else:
                shaded[y][x] = color

    # брови — лицо читается даже в 16×16
    for x in (6, 7, 9, 10):
        if shaded[2][x] is not None:
            shaded[2][x] = _tint(base, 0.55)

    _emblem(shaded, spec["emblem"])

    rgba = bytearray(W * H * 4)
    for y in range(H):
        for x in range(W):
            color = shaded[y][x]
            if color is None:
                continue
            i = (y * W + x) * 4
            rgba[i:i + 4] = bytes(_rgba(color))
    return W, H, rgba


# --------------------------------------------------------------------------------------
# PNG (только запись — исходные палитровые иконки нам больше не нужны)
# --------------------------------------------------------------------------------------

def encode_png(width: int, height: int, rgba: bytes) -> bytes:
    raw = bytearray()
    stride = width * 4
    for y in range(height):
        raw.append(0)
        raw += rgba[y * stride:(y + 1) * stride]

    def chunk(tag: bytes, payload: bytes) -> bytes:
        return (
            struct.pack(">I", len(payload))
            + tag
            + payload
            + struct.pack(">I", zlib.crc32(tag + payload) & 0xFFFFFFFF)
        )

    return (
        b"\x89PNG\r\n\x1a\n"
        + chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0))
        + chunk(b"IDAT", zlib.compress(bytes(raw), 9))
        + chunk(b"IEND", b"")
    )


def icon_png(slug: str) -> bytes:
    width, height, rgba = render_icon(slug)
    return encode_png(width, height, rgba)


# --------------------------------------------------------------------------------------
# Стыковка с build_bedrock_pack.py
# --------------------------------------------------------------------------------------

HERO_ICON_NAME = "minecraft_item_funtime_items_talisman_hero"


def slug_of(icon_name: str) -> Optional[str]:
    """`minecraft_item_funtime_new_year_talisman_grinch` -> 'grinch'."""
    for slug in TALISMANS:
        if icon_name.endswith(f"talisman_{slug}"):
            return slug
    return None


def redraw_icons(icons: Dict[str, bytes]) -> List[str]:
    """Заменяет иконки талисманов на нарисованные. Возвращает список имён."""
    touched: List[str] = []
    for name in sorted(icons):
        slug = slug_of(name)
        if not slug:
            continue
        icons[name] = icon_png(slug)
        touched.append(name)
    return touched


def hero_icon() -> Tuple[str, bytes]:
    """12-й талисман: в Java-паке его нет, рисуем сами."""
    return HERO_ICON_NAME, icon_png("hero")


# --------------------------------------------------------------------------------------
# Модель в руке
# --------------------------------------------------------------------------------------

GEO_FILE = os.path.join("models", "entity", "talisman.geo.json")
ANIM_FILE = os.path.join("animations", "talisman.json")
RC_FILE = os.path.join("render_controllers", "talisman.render_controllers.json")
ATTACHABLE_DIR = "attachables"


def _geometry() -> dict:
    """
    Плоская фигурка в руке — по образцу ванильного лука: кость `rightitem`
    с `texture_meshes` (две плоскости, лицом и наизнанку). Ориентацию и место
    задаёт анимация, поэтому в самой геометрии поворотов нет.
    """
    # числа ровно как в ванильном geometry.bow_pulling_*: local_pivot 6/0/6 — центр
    # картинки, вокруг него и крутится плоскость
    mesh = {"local_pivot": [6, 0, 6], "position": [2, 1, -1], "rotation": [0, -135, 90],
            "texture": "default"}
    return {
        "format_version": "1.16.0",
        "minecraft:geometry": [
            {
                "description": {
                    "identifier": "geometry.talisman",
                    "texture_width": 16,
                    "texture_height": 16,
                },
                "bones": [
                    {
                        "name": "rightitem",
                        "texture_meshes": [
                            mesh,
                            dict(mesh, position=[2.01, 1, -1], rotation=[0, 45, 90]),
                        ],
                    }
                ],
            }
        ],
    }


def _animation() -> dict:
    """
    Те же числа, что в ванильной `animation.bow.wield` (по ним Bedrock держит
    плоский предмет в руке), плюс небольшой масштаб, чтобы фигурка не занимала
    половину экрана.
    """
    return {
        "format_version": "1.10.0",
        "animations": {
            "animation.talisman.wield": {
                "loop": True,
                "bones": {
                    "rightitem": {
                        "position": [
                            "c.is_first_person ? -5.5 : 0.5",
                            "c.is_first_person ? -3.0 : -2.5",
                            "c.is_first_person ? -3.0 : 1.0",
                        ],
                        "rotation": [
                            "c.is_first_person ? 38.0 : 0.0",
                            "c.is_first_person ? -120.0 : 0.0",
                            "c.is_first_person ? -63.0 : 0.0",
                        ],
                        "scale": 0.85,
                    }
                },
            }
        },
    }


def _render_controller() -> dict:
    return {
        "format_version": "1.10.0",
        "render_controllers": {
            "controller.render.talisman": {
                "geometry": "geometry.default",
                "materials": [
                    {"*": "variable.is_enchanted ? material.enchanted : material.default"}
                ],
                "textures": ["texture.default", "texture.enchanted"],
            }
        },
    }


def _write_json(path: str, payload: dict) -> None:
    os.makedirs(os.path.dirname(path) or ".", exist_ok=True)
    with open(path, "w", encoding="utf-8") as fh:
        json.dump(payload, fh, ensure_ascii=False, indent=2)


def build_attachables(
    pack_dir: str,
    icon_names: List[str],
    icons: Dict[str, bytes],
    namespace: str,
) -> List[str]:
    """Пишет геометрию/анимацию/render controller и по attachable на талисман."""
    names = [n for n in sorted(set(icon_names)) if slug_of(n) and n in icons]
    if not names:
        return []

    _write_json(os.path.join(pack_dir, GEO_FILE), _geometry())
    _write_json(os.path.join(pack_dir, ANIM_FILE), _animation())
    _write_json(os.path.join(pack_dir, RC_FILE), _render_controller())

    written: List[str] = []
    for name in names:
        _write_json(
            os.path.join(pack_dir, ATTACHABLE_DIR, f"{name}.json"),
            {
                "format_version": "1.10.0",
                "minecraft:attachable": {
                    "description": {
                        "identifier": f"{namespace}:{name}",
                        "materials": {
                            "default": "entity_alphatest",
                            "enchanted": "entity_alphatest_glint",
                        },
                        "textures": {
                            "default": f"textures/items/{name}",
                            "enchanted": "textures/misc/enchanted_item_glint",
                        },
                        "geometry": {"default": "geometry.talisman"},
                        "animations": {"wield": "animation.talisman.wield"},
                        "scripts": {"animate": ["wield"]},
                        "render_controllers": ["controller.render.talisman"],
                    }
                },
            },
        )
        written.append(name)
    return written


# --------------------------------------------------------------------------------------
# Самопроверка: python3 tools/talisman_art.py --out /tmp/talismans
# --------------------------------------------------------------------------------------

def _selfcheck() -> int:
    import sys

    out_dir = "/tmp/talismans"
    if "--out" in sys.argv:
        out_dir = sys.argv[sys.argv.index("--out") + 1]
    os.makedirs(out_dir, exist_ok=True)
    for slug in TALISMANS:
        with open(os.path.join(out_dir, f"{slug}.png"), "wb") as fh:
            fh.write(icon_png(slug))
    print(f"OK: {len(TALISMANS)} иконок -> {out_dir}")
    return 0


if __name__ == "__main__":
    raise SystemExit(_selfcheck())
