#!/usr/bin/env python3
"""
Талисманы: свои иконки 32×32 и объёмная фигурка в руке.

Две вещи, которые здесь важны.

1. ИКОНКИ РИСУЮТСЯ С НУЛЯ (32×32, с бликами, тенями и обводкой). У каждого
   талисмана свой силуэт: цвет, головной убор (корона, рога, шлем, капюшон,
   шляпа, нимб) и снаряжение в руках (меч, топор, кинжалы, лук, посох, щит,
   крылья, плащ). Это не перекраска FunTime — рисунок свой.

2. В РУКЕ — НАСТОЯЩАЯ 3D-ФИГУРКА. Плоская иконка в руке выглядит наклейкой,
   поэтому собирается attachable из кубов (голова, торс, руки, ноги).
   Схема взята из пака, который Geyser генерирует сам для голов игроков
   (`skull_resource_pack`), — там есть главное, чего не хватало раньше:

       "binding": "c.item_slot == 'head' ? 'head' : q.item_slot_to_bone_name(c.item_slot)"

   Без `binding` модель не привязана к кости руки и висит мимо ладони —
   именно поэтому прошлые попытки вставали криво. Дальше идёт цепочка
   root → root_x → root_y → root_z, а кубы лежат на дочерней кости.

Зависимости: только стандартная библиотека Python 3.
"""

from __future__ import annotations

import json
import os
import struct
import zlib
from typing import Dict, List, Optional, Tuple

S = 32            # размер иконки
TEX = 64          # атлас для 3D: 0..31 — спрайт, 32..63 — служебные заливки

Color = Tuple[int, int, int, int]
Grid = List[List[Optional[Color]]]

GOLD = (246, 200, 80, 255)
GOLD_D = (196, 150, 46, 255)
STEEL = (214, 220, 232, 255)
STEEL_D = (158, 166, 182, 255)
WOOD = (124, 84, 48, 255)
BONE = (240, 230, 208, 255)
BONE_D = (212, 200, 180, 255)
WHITE = (250, 248, 240, 255)
INK = (20, 14, 26, 240)

# slug -> (основной цвет, верх, снаряжение)
TALISMANS: Dict[str, dict] = {
    "life":     dict(color=(72, 186, 108), top="halo",  gear="none"),
    "fury":     dict(color=(206, 62, 62),  top="horns", gear="none"),
    "guard":    dict(color=(66, 122, 214), top="helm",  gear="shield"),
    "wanderer": dict(color=(140, 92, 214), top="hood",  gear="staff"),
    "tank":     dict(color=(132, 142, 158), top="helm", gear="pauldrons"),
    "warrior":  dict(color=(196, 58, 74),  top="crown", gear="sword"),
    "assassin": dict(color=(44, 108, 82),  top="hood",  gear="daggers"),
    "berserk":  dict(color=(226, 120, 44), top="horns", gear="axe"),
    "hunter":   dict(color=(146, 102, 60), top="hat",   gear="bow"),
    "runner":   dict(color=(66, 176, 216), top="none",  gear="wings"),
    "demon":    dict(color=(126, 30, 48),  top="horns", gear="wings"),
    "hero":     dict(color=(230, 184, 66), top="crown", gear="cape"),
}

# имена моделей FunTime -> slug. В Java-паке талисманы названы по-своему
# (harmony, daedalus, crusher…), а в меню у них другие названия — вот соответствие.
# CMD 1..12 совпадает с model_data_component в shop_talismans.yml.
FUNTIME_SLUGS: Dict[str, str] = {
    "harmony": "life",        # 1  Талисман жизни
    "daedalus": "fury",       # 2  Талисман ярости
    "triton": "guard",        # 3  Талисман стража
    "discord": "wanderer",    # 4  Талисман странника
    "echidna": "tank",        # 5  Талисман танка
    "crusher": "warrior",     # 6  Талисман воина
    "punisher": "assassin",   # 7  Талисман убийцы
    "tyrant": "berserk",      # 8  Талисман берсерка
    "edge": "hunter",         # 9  Талисман охотника
    "phoenix": "runner",      # 10 Талисман бегуна
    "grinch": "demon",        # 11 Талисман демона
    "hero": "hero",           # 12 Талисман героя
}

# slug -> id талисмана в конфиге плагина VoidTalismans
PLUGIN_IDS = {
    "life": "phoenix", "fury": "echidna", "guard": "triton", "wanderer": "harmony",
    "tank": "sources", "warrior": "crusher", "assassin": "cobra", "berserk": "punisher",
    "hunter": "vepr", "runner": "grani", "demon": "satan", "hero": "absolut",
}

# CMD каждого талисмана (совпадает с shop_talismans.yml и VoidTalismans-config.yml)
CMD_BY_SLUG = {
    "life": 1, "fury": 2, "guard": 3, "wanderer": 4, "tank": 5, "warrior": 6,
    "assassin": 7, "berserk": 8, "hunter": 9, "runner": 10, "demon": 11, "hero": 12,
}


# --------------------------------------------------------------------------------------
# Палитра-градиент
# --------------------------------------------------------------------------------------

def _clamp8(v: float) -> int:
    return 0 if v < 0 else 255 if v > 255 else int(round(v))


def _ramp(base: Tuple[int, int, int]) -> dict:
    r, g, b = base
    f = lambda k: (_clamp8(r * k), _clamp8(g * k), _clamp8(b * k), 255)
    return dict(hi=f(1.40), lt=f(1.16), base=(r, g, b, 255), dk=f(0.72), dd=f(0.48))


# --------------------------------------------------------------------------------------
# Рисование
# --------------------------------------------------------------------------------------

def _blank() -> Grid:
    return [[None] * S for _ in range(S)]


def _box(grid: Grid, x0: int, y0: int, x1: int, y1: int, color: Color) -> None:
    for y in range(min(y0, y1), max(y0, y1) + 1):
        for x in range(min(x0, x1), max(x0, x1) + 1):
            if 0 <= x < S and 0 <= y < S:
                grid[y][x] = color


def _part(grid: Grid, x0: int, y0: int, x1: int, y1: int, p: dict) -> None:
    """
    Деталь фигурки с объёмом: сверху светлее, снизу темнее, правый край в тени,
    левый — на свету. Без этого фигурка выглядит плоской заливкой.
    """
    height = max(1, y1 - y0)
    for y in range(y0, y1 + 1):
        t = (y - y0) / height
        if t < 0.28:
            c = p["lt"]
        elif t > 0.72:
            c = p["dk"]
        else:
            c = p["base"]
        for x in range(x0, x1 + 1):
            if x == x1 and x1 > x0:
                grid[y][x] = p["dk"]
            elif x == x0 and x1 > x0 + 1:
                grid[y][x] = p["lt"]
            else:
                grid[y][x] = c


def _body(grid: Grid, p: dict) -> None:
    """Фигурка: ноги, торс, руки, голова, лицо."""
    _part(grid, 11, 23, 14, 29, p)          # левая нога
    _part(grid, 17, 23, 20, 29, p)          # правая нога
    _box(grid, 11, 29, 14, 30, p["dd"])     # ботинки
    _box(grid, 17, 29, 20, 30, p["dd"])

    _part(grid, 11, 13, 20, 22, p)          # торс
    _box(grid, 11, 13, 20, 13, GOLD_D)      # воротник
    _box(grid, 14, 15, 17, 15, GOLD)        # застёжка
    _box(grid, 15, 16, 16, 20, GOLD_D)      # планка
    _box(grid, 15, 16, 16, 16, GOLD)

    _part(grid, 8, 14, 10, 22, p)           # левая рука
    _part(grid, 21, 14, 23, 22, p)          # правая рука
    _box(grid, 8, 21, 10, 22, p["dd"])      # перчатки
    _box(grid, 21, 21, 23, 22, p["dd"])

    _part(grid, 10, 4, 21, 13, p)           # голова
    _box(grid, 10, 4, 21, 4, p["hi"])       # блик на макушке
    _box(grid, 12, 5, 19, 5, p["hi"])       # свет на лбу

    # лицо
    _box(grid, 12, 8, 14, 10, WHITE)        # белки
    _box(grid, 17, 8, 19, 10, WHITE)
    _box(grid, 13, 8, 14, 10, (58, 42, 66, 255))
    _box(grid, 17, 8, 18, 10, (58, 42, 66, 255))
    _box(grid, 12, 8, 12, 8, (255, 255, 255, 255))   # блик в глазу
    _box(grid, 17, 8, 17, 8, (255, 255, 255, 255))
    _box(grid, 15, 9, 16, 11, p["dd"])      # нос
    _box(grid, 14, 12, 17, 12, p["dd"])     # рот
    _box(grid, 10, 7, 10, 12, p["dk"])      # тень сбоку лица
    _box(grid, 21, 7, 21, 12, p["dk"])


def _top(grid: Grid, kind: str, p: dict) -> None:
    if kind == "crown":
        _box(grid, 10, 1, 21, 3, GOLD)
        _box(grid, 10, 3, 21, 3, GOLD_D)
        for x in (10, 13, 17, 20):
            _box(grid, x, 0, x, 1, GOLD)
    elif kind == "horns":
        _box(grid, 8, 2, 9, 4, BONE)
        _box(grid, 22, 2, 23, 4, BONE)
        _box(grid, 7, 1, 8, 2, BONE_D)
        _box(grid, 23, 1, 24, 2, BONE_D)
    elif kind == "helm":
        # закрытый шлем: металл по куполу, открытое лицо и нащёчники
        _box(grid, 10, 2, 21, 5, STEEL)
        _box(grid, 10, 2, 21, 3, (240, 244, 252, 255))
        _box(grid, 9, 6, 10, 6, STEEL_D)       # козырёк
        _box(grid, 21, 6, 22, 6, STEEL_D)
        _box(grid, 10, 6, 10, 11, STEEL_D)     # нащёчники
        _box(grid, 21, 6, 21, 11, STEEL_D)
        _box(grid, 15, 3, 16, 4, GOLD)         # гребень
    elif kind == "hood":
        _box(grid, 9, 2, 22, 6, p["dd"])
        _box(grid, 9, 2, 22, 3, p["dk"])
        _box(grid, 9, 7, 10, 13, p["dd"])
        _box(grid, 21, 7, 22, 13, p["dd"])
    elif kind == "halo":
        _box(grid, 11, 0, 20, 1, (255, 246, 170, 255))
        _box(grid, 11, 0, 11, 1, (206, 255, 212, 255))
        _box(grid, 20, 0, 20, 1, (206, 255, 212, 255))
    elif kind == "hat":
        _box(grid, 6, 1, 25, 3, (114, 80, 46, 255))
        _box(grid, 10, 3, 21, 4, (96, 68, 38, 255))
        _box(grid, 6, 1, 7, 3, GOLD_D)


def _gear(grid: Grid, kind: str, p: dict) -> None:
    if kind == "sword":
        _box(grid, 25, 4, 26, 17, STEEL)
        _box(grid, 25, 4, 25, 17, STEEL_D)
        _box(grid, 23, 17, 28, 18, GOLD)
        _box(grid, 25, 19, 26, 23, WOOD)
    elif kind == "axe":
        _box(grid, 24, 6, 28, 11, STEEL)
        _box(grid, 24, 6, 28, 7, (240, 244, 252, 255))
        _box(grid, 25, 12, 26, 26, WOOD)
    elif kind == "daggers":
        _box(grid, 25, 6, 26, 14, STEEL)
        _box(grid, 24, 14, 27, 15, GOLD_D)
        _box(grid, 5, 6, 6, 14, STEEL)
        _box(grid, 4, 14, 7, 15, GOLD_D)
    elif kind == "bow":
        _box(grid, 4, 5, 5, 25, WOOD)
        _box(grid, 3, 4, 4, 6, WOOD)
        _box(grid, 3, 24, 4, 26, WOOD)
        _box(grid, 6, 5, 6, 25, (226, 226, 226, 255))
    elif kind == "staff":
        _box(grid, 25, 4, 26, 26, WOOD)
        _box(grid, 23, 1, 28, 4, (198, 150, 255, 255))
        _box(grid, 25, 0, 26, 1, (236, 214, 255, 255))
    elif kind == "shield":
        _box(grid, 3, 12, 7, 24, STEEL)
        _box(grid, 3, 12, 7, 13, GOLD_D)
        _box(grid, 3, 23, 7, 24, GOLD_D)
        _box(grid, 5, 16, 5, 21, STEEL_D)
    elif kind == "pauldrons":
        _box(grid, 6, 13, 9, 16, STEEL)
        _box(grid, 22, 13, 25, 16, STEEL)
        _box(grid, 6, 13, 9, 13, GOLD_D)
        _box(grid, 22, 13, 25, 13, GOLD_D)
    elif kind == "wings":
        _box(grid, 2, 12, 7, 22, p["lt"])
        _box(grid, 24, 12, 29, 22, p["lt"])
        _box(grid, 2, 12, 2, 22, p["dk"])
        _box(grid, 29, 12, 29, 22, p["dk"])
        _box(grid, 3, 13, 6, 14, p["hi"])
        _box(grid, 25, 13, 28, 14, p["hi"])
    elif kind == "cape":
        _box(grid, 3, 13, 8, 27, GOLD_D)
        _box(grid, 23, 13, 28, 27, GOLD_D)
        _box(grid, 3, 13, 8, 14, (255, 238, 150, 255))
        _box(grid, 23, 13, 28, 14, (255, 238, 150, 255))


def _outline(grid: Grid) -> Grid:
    """Тёмный контур по силуэту — фигурка читается на любом фоне."""
    def solid(x: int, y: int) -> bool:
        return 0 <= x < S and 0 <= y < S and grid[y][x] is not None

    out = [row[:] for row in grid]
    for y in range(S):
        for x in range(S):
            if solid(x, y):
                continue
            if solid(x - 1, y) or solid(x + 1, y) or solid(x, y - 1) or solid(x, y + 1):
                out[y][x] = INK
    return out


def render_icon(slug: str) -> Grid:
    spec = TALISMANS[slug]
    p = _ramp(spec["color"])
    grid = _blank()
    if spec["gear"] in ("wings", "cape"):
        _gear(grid, spec["gear"], p)      # за спиной
    _body(grid, p)
    if spec["gear"] not in ("wings", "cape"):
        _gear(grid, spec["gear"], p)
    _top(grid, spec["top"], p)
    return _outline(grid)


# --------------------------------------------------------------------------------------
# PNG
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


def _grid_to_bytes(grid: Grid, size: int) -> bytearray:
    out = bytearray(size * size * 4)
    for y in range(size):
        for x in range(size):
            c = grid[y][x]
            if c is None:
                continue
            i = (y * size + x) * 4
            out[i:i + 4] = bytes(c)
    return out


def icon_png(slug: str) -> bytes:
    return encode_png(S, S, _grid_to_bytes(render_icon(slug), S))


# служебные плашки атласа: (x, y, w, h) — однотонные заливки для орнамента
SWATCH = {
    "light": (32, 0, 16, 16),
    "dark": (32, 16, 16, 16),
    "gold": (48, 0, 8, 16),
    "bone": (56, 0, 8, 16),
    "steel": (48, 16, 8, 16),
    "wood": (56, 16, 8, 16),
}
MATERIAL = {
    "gold": (232, 186, 70, 255),
    "bone": (238, 228, 204, 255),
    "steel": (172, 180, 198, 255),
    "wood": (122, 82, 46, 255),
}


def atlas_png(slug: str) -> bytes:
    """
    Атлас 64×64: слева сверху спрайт (лицо и одежда), справа — однотонные
    плашки: светлая и тёмная грани фигурки, золото, кость, сталь, дерево.
    Из них собирается орнамент 3D-модели (корона, рога, шлем, крылья…).
    """
    p = _ramp(TALISMANS[slug]["color"])
    grid = render_icon(slug)

    canvas: Grid = [[None] * TEX for _ in range(TEX)]
    fills = {k: v for k, v in SWATCH.items()}
    for y in range(TEX):
        for x in range(TEX):
            canvas[y][x] = grid[y][x] if (x < S and y < S) else None

    for key, color in (
        ("light", p["lt"]),
        ("dark", p["dd"]),
        ("gold", MATERIAL["gold"]),
        ("bone", MATERIAL["bone"]),
        ("steel", MATERIAL["steel"]),
        ("wood", MATERIAL["wood"]),
    ):
        x0, y0, w, h = fills[key]
        for y in range(y0, y0 + h):
            for x in range(x0, x0 + w):
                canvas[y][x] = color
    return encode_png(TEX, TEX, _grid_to_bytes(canvas, TEX))


def slug_of(icon_name: str) -> Optional[str]:
    """Имя иконки из mappings -> slug (harmony -> life, grinch -> demon и т.д.)."""
    for funtime, slug in FUNTIME_SLUGS.items():
        if icon_name.endswith(f"talisman_{funtime}"):
            return slug
    return None


HERO_ICON_NAME = "minecraft_item_funtime_items_talisman_hero"


def redraw_icons(icons: Dict[str, bytes]) -> List[str]:
    """Подменяет иконки талисманов на нарисованные (CMD и id не меняются)."""
    touched: List[str] = []
    for name in sorted(icons):
        slug = slug_of(name)
        if not slug:
            continue
        icons[name] = icon_png(slug)
        touched.append(name)
    return touched


def hero_icon() -> Tuple[str, bytes]:
    return HERO_ICON_NAME, icon_png("hero")


# --------------------------------------------------------------------------------------
# 3D-модель в руке
# --------------------------------------------------------------------------------------

GEO_FILE = os.path.join("models", "entity", "talisman.geo.json")
ANIM_FILE = os.path.join("animations", "talisman.json")
ATTACHABLE_DIR = "attachables"
HAND_SCALE = 0.5                # как у черепов в паке Geyser
HAND_POS_THIRD = [0, 14.5, -4]  # подошвы в кулаке, фигурка растёт вверх
HAND_POS_FIRST = [2, 14, 4]

# области спрайта, которые натягиваются на грани кубов: (x, y, w, h)
REGIONS = {
    "head":  (10, 4, 12, 10),
    "torso": (11, 13, 10, 10),
    "arm":   (8, 14, 3, 8),
    "leg":   (11, 23, 4, 7),
}


def _faces(region: Tuple[int, int, int, int], depth: int) -> dict:
    """
    Передняя грань — вырез из спрайта (лицо, одежда), сзади и с боков —
    тёмная плашка, сверху — светлая.
    """
    px, py, w, h = region
    lx, ly = SWATCH["light"][:2]
    dx, dy = SWATCH["dark"][:2]
    return {
        "north": {"uv": [px, py], "uv_size": [w, h]},
        "south": {"uv": [dx, dy], "uv_size": [w, h]},
        "east": {"uv": [dx, dy], "uv_size": [depth, h]},
        "west": {"uv": [dx, dy], "uv_size": [depth, h]},
        "up": {"uv": [lx, ly], "uv_size": [w, depth]},
        "down": {"uv": [dx, dy], "uv_size": [w, depth]},
    }


def _solid(key: str) -> dict:
    """Все шесть граней — одна однотонная плашка материала."""
    x, y = SWATCH[key][:2]
    return {f: {"uv": [x, y], "uv_size": [1, 1]}
            for f in ("north", "south", "east", "west", "up", "down")}


def _cube(origin, size, material: str) -> dict:
    return {"origin": list(origin), "size": list(size), "uv": _solid(material)}


def _ornaments(top: str, gear: str) -> List[dict]:
    """
    Головной убор и снаряжение — то, что отличает талисманы в руке так же,
    как на иконке. Всё из тех же плашек, что и в атласе.
    """
    cubes: List[dict] = []
    if top == "crown":
        cubes.append(_cube([-2.6, 11.4, -1.6], [5.2, 1.0, 3.2], "gold"))
        cubes.append(_cube([-2.6, 12.4, -1.1], [1.0, 1.2, 2.2], "gold"))
        cubes.append(_cube([1.6, 12.4, -1.1], [1.0, 1.2, 2.2], "gold"))
        cubes.append(_cube([-0.5, 12.4, -1.1], [1.0, 0.9, 2.2], "gold"))
    elif top == "horns":
        cubes.append(_cube([-3.4, 11.0, -1.0], [1.0, 2.0, 1.0], "bone"))
        cubes.append(_cube([2.4, 11.0, -1.0], [1.0, 2.0, 1.0], "bone"))
        cubes.append(_cube([-3.9, 12.4, -0.7], [0.9, 0.9, 0.9], "bone"))
        cubes.append(_cube([3.0, 12.4, -0.7], [0.9, 0.9, 0.9], "bone"))
    elif top == "halo":
        cubes.append(_cube([-2.6, 12.3, -1.7], [5.2, 0.5, 0.6], "gold"))   # перед
        cubes.append(_cube([-2.6, 12.3, 1.1], [5.2, 0.5, 0.6], "gold"))    # зад
        cubes.append(_cube([-2.6, 12.3, -1.1], [0.6, 0.5, 2.2], "gold"))   # лево
        cubes.append(_cube([2.0, 12.3, -1.1], [0.6, 0.5, 2.2], "gold"))    # право
    elif top == "helm":
        cubes.append(_cube([-3.3, 11.0, -1.8], [6.6, 1.1, 3.6], "steel"))
        cubes.append(_cube([-3.3, 8.6, -1.8], [0.7, 2.5, 2.4], "steel"))
        cubes.append(_cube([2.6, 8.6, -1.8], [0.7, 2.5, 2.4], "steel"))
        cubes.append(_cube([-1.0, 8.8, -2.0], [2.0, 0.5, 0.5], "steel"))
        cubes.append(_cube([-0.4, 9.4, -2.1], [0.8, 1.6, 0.5], "steel"))
    elif top == "hood":
        cubes.append(_cube([-3.3, 8.4, 1.2], [6.6, 3.4, 0.8], "dark"))
        cubes.append(_cube([-3.3, 11.6, -1.7], [6.6, 0.9, 3.7], "dark"))
        cubes.append(_cube([-3.3, 8.4, -1.7], [0.7, 3.2, 3.0], "dark"))
        cubes.append(_cube([2.6, 8.4, -1.7], [0.7, 3.2, 3.0], "dark"))
    elif top == "hat":
        cubes.append(_cube([-4.2, 11.2, -2.2], [8.4, 0.6, 4.4], "wood"))
        cubes.append(_cube([-2.2, 11.8, -1.7], [4.4, 1.4, 3.4], "wood"))
        cubes.append(_cube([-2.2, 10.9, -1.8], [4.4, 0.4, 3.6], "gold"))

    if gear == "wings":
        cubes.append(_cube([-4.8, 5.4, 1.3], [2.2, 5.0, 0.6], "dark"))
        cubes.append(_cube([2.6, 5.4, 1.3], [2.2, 5.0, 0.6], "dark"))
        cubes.append(_cube([-5.6, 7.6, 1.3], [1.4, 2.4, 0.6], "dark"))
        cubes.append(_cube([4.2, 7.6, 1.3], [1.4, 2.4, 0.6], "dark"))
    elif gear == "cape":
        cubes.append(_cube([-2.8, 2.6, 1.3], [5.6, 6.0, 0.6], "dark"))
        cubes.append(_cube([-3.4, 2.2, 1.3], [6.8, 0.8, 0.6], "gold"))
    elif gear == "shield":
        cubes.append(_cube([-5.2, 4.0, -1.3], [1.0, 4.6, 2.6], "steel"))
        cubes.append(_cube([-5.3, 5.8, -0.9], [1.2, 1.2, 1.8], "gold"))
    elif gear == "staff":
        cubes.append(_cube([4.0, 1.2, -0.9], [0.8, 10.0, 0.8], "wood"))
        cubes.append(_cube([3.6, 10.6, -1.3], [1.6, 1.6, 1.6], "gold"))
    elif gear == "sword":
        cubes.append(_cube([3.9, 3.4, -0.8], [0.7, 6.4, 0.7], "steel"))
        cubes.append(_cube([3.4, 2.8, -1.2], [1.7, 0.6, 1.5], "gold"))
        cubes.append(_cube([3.9, 1.6, -0.8], [0.7, 1.2, 0.7], "wood"))
    elif gear == "daggers":
        cubes.append(_cube([3.7, 5.0, -0.7], [0.6, 3.2, 0.6], "steel"))
        cubes.append(_cube([-4.3, 5.0, -0.7], [0.6, 3.2, 0.6], "steel"))
    elif gear == "axe":
        cubes.append(_cube([3.9, 1.4, -0.7], [0.7, 7.2, 0.7], "wood"))
        cubes.append(_cube([2.7, 6.6, -1.1], [2.6, 2.2, 1.6], "steel"))
        cubes.append(_cube([2.4, 6.9, -1.3], [2.2, 1.6, 0.4], "gold"))
    elif gear == "bow":
        cubes.append(_cube([-4.6, 3.0, -0.6], [0.7, 7.4, 0.7], "wood"))
        cubes.append(_cube([-4.9, 9.4, -0.6], [1.3, 1.0, 1.0], "wood"))
        cubes.append(_cube([-4.9, 2.4, -0.6], [1.3, 1.0, 1.0], "wood"))
    elif gear == "pauldrons":
        cubes.append(_cube([-4.2, 7.4, -1.4], [1.6, 2.2, 3.0], "steel"))
        cubes.append(_cube([2.6, 7.4, -1.4], [1.6, 2.2, 3.0], "steel"))
    return cubes


BODY_CUBES = [
    # origin, size, область спрайта: фигурка симметрична относительно x = 0
    # и стоит на y = 1 — растёт вверх от ладони
    ([-3, 8, -1.5], [6, 3.5, 3], "head"),
    ([-2.5, 4.5, -1.5], [5, 3.5, 3], "torso"),
    ([-3.5, 4.5, -1.5], [1, 3, 3], "arm"),
    ([2.5, 4.5, -1.5], [1, 3, 3], "arm"),
    ([-2.5, 1, -1.5], [2, 3.5, 3], "leg"),
    ([0.5, 1, -1.5], [2, 3.5, 3], "leg"),
]


def _geometry_for(slug: str) -> dict:
    """
    Своя модель на каждый талисман: одна и та же фигурка плюс убор и
    снаряжение. Цепочка костей как в паке Geyser для голов игроков —
    без `binding` у `root` модель не привязывается к кости руки.
    """
    p = TALISMANS[slug]
    cubes = [{"origin": o, "size": sz, "uv": _faces(REGIONS[k], 3)}
             for (o, sz, k) in BODY_CUBES]
    cubes += _ornaments(p["top"], p["gear"])
    return {
        "description": {
            "identifier": f"geometry.talisman.{slug}",
            "texture_width": TEX,
            "texture_height": TEX,
            "visible_bounds_width": 3,
            "visible_bounds_height": 3,
            "visible_bounds_offset": [0, 1, 0],
        },
        "bones": [
            {
                "name": "root",
                "binding": "c.item_slot == 'head' ? 'head' : "
                           "q.item_slot_to_bone_name(c.item_slot)",
                "pivot": [0, 8, 0],
            },
            {"name": "root_x", "parent": "root", "pivot": [0, 8, 0]},
            {"name": "root_y", "parent": "root_x", "pivot": [0, 8, 0]},
            {"name": "root_z", "parent": "root_y", "pivot": [0, 8, 0]},
            {"name": "figure", "parent": "root_z", "pivot": [0, 8, 0], "cubes": cubes},
        ],
    }


def _geometry() -> dict:
    """Все 12 моделей одним файлом — по одной на талисман."""
    return {
        "format_version": "1.16.0",
        "minecraft:geometry": [_geometry_for(slug) for slug in TALISMANS],
    }


def _animations() -> dict:
    """
    Положение в ладони — по образцу пака Geyser для голов игроков, но БЕЗ
    наклонов: у черепа куб, ему поворот незаметен, а фигурка от `root_x: 45`
    заваливается набок (ровно это и было видно на скриншоте). Поэтому
    root_x и root_z = 0: фигурка стоит вертикально, root_y только разворачивает
    её лицом от игрока.
    """
    def pose(position, rot_x, rot_y, root_z=None):
        bones = {
            "root": {"scale": [HAND_SCALE, HAND_SCALE, HAND_SCALE], "position": position},
            "root_x": {"rotation": [rot_x, 0, 0]},
            "root_y": {"rotation": [0, rot_y, 0]},
        }
        if root_z is not None:
            bones["root_z"] = {"rotation": [root_z, 0, 0]}
        return {"loop": True, "bones": bones}

    return {
        "format_version": "1.8.0",
        "animations": {
            "animation.talisman.thirdperson_main_hand": pose(HAND_POS_THIRD, 0, -45),
            "animation.talisman.thirdperson_off_hand": pose(HAND_POS_THIRD, 0, 45),
            "animation.talisman.firstperson_main_hand": pose(HAND_POS_FIRST, 0, 0),
            "animation.talisman.firstperson_off_hand": pose(HAND_POS_FIRST, 0, 0),
            "animation.talisman.disable": {
                "loop": True,
                "override_previous_animation": True,
                "bones": {"root": {"scale": 0}},
            },
        },
    }


def _write_json(path: str, payload: dict) -> None:
    os.makedirs(os.path.dirname(path) or ".", exist_ok=True)
    with open(path, "w", encoding="utf-8") as fh:
        json.dump(payload, fh, ensure_ascii=False, indent=2)


def build_hand_models(pack_dir: str, icon_names: List[str], namespace: str) -> List[str]:
    """Геометрия, анимации и по attachable на каждый талисман."""
    names = [n for n in sorted(set(icon_names)) if slug_of(n)]
    if not names:
        return []

    _write_json(os.path.join(pack_dir, GEO_FILE), _geometry())
    _write_json(os.path.join(pack_dir, ANIM_FILE), _animations())

    for name in names:
        tex_path = os.path.join(pack_dir, "textures", "items", f"{name}_3d.png")
        os.makedirs(os.path.dirname(tex_path), exist_ok=True)
        with open(tex_path, "wb") as fh:
            fh.write(atlas_png(slug_of(name)))  # type: ignore[arg-type]

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
                            "default": f"textures/items/{name}_3d",
                            "enchanted": "textures/misc/enchanted_item_glint",
                        },
                        "geometry": {"default": f"geometry.talisman.{slug_of(name)}"},
                        "scripts": {
                            "pre_animation": [
                                "v.main_hand = c.item_slot == 'main_hand';",
                                "v.off_hand = c.item_slot == 'off_hand';",
                                "v.head = c.item_slot == 'head';",
                            ],
                            "animate": [
                                {"thirdperson_main_hand": "v.main_hand && !c.is_first_person"},
                                {"thirdperson_off_hand": "v.off_hand && !c.is_first_person"},
                                {"thirdperson_head": "v.head && !c.is_first_person"},
                                {"firstperson_main_hand": "v.main_hand && c.is_first_person"},
                                {"firstperson_off_hand": "v.off_hand && c.is_first_person"},
                                {"firstperson_head": "c.is_first_person && v.head"},
                            ],
                        },
                        "animations": {
                            "thirdperson_main_hand": "animation.talisman.thirdperson_main_hand",
                            "thirdperson_off_hand": "animation.talisman.thirdperson_off_hand",
                            "thirdperson_head": "animation.talisman.disable",
                            "firstperson_main_hand": "animation.talisman.firstperson_main_hand",
                            "firstperson_off_hand": "animation.talisman.firstperson_off_hand",
                            "firstperson_head": "animation.talisman.disable",
                        },
                        "render_controllers": ["controller.render.item_default"],
                    }
                },
            },
        )
    return names


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
        with open(os.path.join(out_dir, f"{slug}_3d.png"), "wb") as fh:
            fh.write(atlas_png(slug))
    print(f"OK: {len(TALISMANS)} иконок 32×32 + атласы 64×64 -> {out_dir}")
    return 0


if __name__ == "__main__":
    raise SystemExit(_selfcheck())
