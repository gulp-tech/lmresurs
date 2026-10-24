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

S = 64            # размер иконки: на 64×64 помещаются детали
TEX = 128         # атлас для 3D: 0..63 — спрайт, 64..127 — плашки материалов

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


def _tint(base: Tuple[int, int, int], k: float) -> Color:
    return (_clamp8(base[0] * k), _clamp8(base[1] * k), _clamp8(base[2] * k), 255)


def _ramp(base: Tuple[int, int, int]) -> dict:
    r, g, b = base
    return dict(
        hi=_tint(base, 1.42), lt=_tint(base, 1.18), base=(r, g, b, 255),
        dk=_tint(base, 0.72), dd=_tint(base, 0.48),
        ink=_tint(base, 0.30),
    )


# --------------------------------------------------------------------------------------
# Рисование
# --------------------------------------------------------------------------------------

WHITE = (250, 248, 242, 255)
PUPIL = (46, 30, 54, 255)
GOLD_LT = (255, 234, 150, 255)
STEEL_LT = (240, 245, 255, 255)
INK = (18, 12, 22, 255)

BODY_HEAD = (21, 10, 42, 29)     # x0, y0, x1, y1 — по этим рамкам режутся UV для 3D
BODY_TORSO = (19, 31, 44, 46)
BODY_ARM_L = (14, 33, 18, 48)
BODY_ARM_R = (45, 33, 49, 48)
BODY_LEG_L = (20, 47, 31, 61)
BODY_LEG_R = (33, 47, 44, 61)


def _blank() -> Grid:
    return [[None] * S for _ in range(S)]


def _box(grid: Grid, x0: int, y0: int, x1: int, y1: int, color: Optional[Color]) -> None:
    for y in range(min(y0, y1), max(y0, y1) + 1):
        for x in range(min(x0, x1), max(x0, x1) + 1):
            if 0 <= x < S and 0 <= y < S and color is not None:
                grid[y][x] = color


def _outl(grid: Grid, x0: int, y0: int, x1: int, y1: int, color: Color) -> None:
    """Рамка в один пиксель внутри указанной области."""
    _box(grid, x0, y0, x1, y0, color)
    _box(grid, x0, y1, x1, y1, color)
    _box(grid, x0, y0, x0, y1, color)
    _box(grid, x1, y0, x1, y1, color)


def _part(grid: Grid, box: Tuple[int, int, int, int], p: dict, round_top: bool = False) -> None:
    """
    Деталь фигурки с объёмом: сверху светлее, снизу темнее, левый край на свету,
    правый в тени. Даёт «круглость» вместо плоской заливки.
    """
    x0, y0, x1, y1 = box
    h = max(1, y1 - y0)
    w = x1 - x0
    for y in range(y0, y1 + 1):
        t = (y - y0) / h
        if t < 0.14:
            k = 1.40
        elif t < 0.40:
            k = 1.16
        elif t < 0.70:
            k = 1.0
        elif t < 0.88:
            k = 0.78
        else:
            k = 0.58
        for x in range(x0, x1 + 1):
            kk = k
            if w > 2 and x == x0:
                kk = max(kk, 1.20)
            elif w > 2 and x == x1:
                kk = min(kk, 0.76)
            if round_top and y == y0 and x in (x0, x1):
                continue
            grid[y][x] = _tint(p["base"][:3], kk)


def _metal(grid: Grid, box: Tuple[int, int, int, int], base: Tuple[int, int, int] = (198, 206, 224)) -> None:
    """Сталь: вертикальные блики, тёмный низ."""
    x0, y0, x1, y1 = box
    h = max(1, y1 - y0)
    for y in range(y0, y1 + 1):
        t = (y - y0) / h
        k = 1.14 if t < 0.22 else 1.02 if t < 0.62 else 0.82 if t < 0.86 else 0.64
        for x in range(x0, x1 + 1):
            kk = k
            if x == x0:
                kk = min(1.24, k + 0.10)
            elif x == x1:
                kk = max(0.55, k - 0.12)
            grid[y][x] = _tint(base, kk)


def _gold(grid: Grid, box: Tuple[int, int, int, int]) -> None:
    _metal(grid, box, (232, 186, 74))


def _wood(grid: Grid, box: Tuple[int, int, int, int]) -> None:
    x0, y0, x1, y1 = box
    for y in range(y0, y1 + 1):
        for x in range(x0, x1 + 1):
            k = 1.0 if (x - x0) % 3 else 0.82
            if x == x0:
                k = 1.16
            if x == x1:
                k = 0.7
            grid[y][x] = _tint((128, 86, 48), k)


def _bevel(grid: Grid, box: Tuple[int, int, int, int], color: Color, corners: int = 1) -> None:
    """Срезать углы — деталь перестаёт быть прямоугольником."""
    x0, y0, x1, y1 = box
    for i in range(corners):
        for dx in range(i + 1):
            for (x, y) in ((x0 + dx, y0 + i), (x1 - dx, y0 + i), (x0 + dx, y1 - i), (x1 - dx, y1 - i)):
                if 0 <= x < S and 0 <= y < S:
                    grid[y][x] = color


def _body(grid: Grid, p: dict) -> None:
    """Фигурка: ноги, торс, руки, голова, лицо. Свет падает сверху-слева."""
    # ноги
    for box in (BODY_LEG_L, BODY_LEG_R):
        _part(grid, box, p)
        x0, y0, x1, y1 = box
        _box(grid, x0, y0, x0, y1, p["dk"])
        _box(grid, x1, y0, x1, y1, p["dd"])
        # штанины заканчиваются, начинаются сапоги
        _box(grid, x0, y1 - 5, x1, y1 - 5, p["dd"])
        _box(grid, x0, y1 - 4, x1, y1, (78, 54, 40, 255))
        _box(grid, x0, y1 - 4, x0, y1, (52, 36, 28, 255))
        _box(grid, x0, y1, x1, y1, (40, 28, 22, 255))
        _box(grid, x0 + 1, y1 - 3, x0 + 2, y1 - 3, GOLD_LT)

    # торс: сверху шире, к поясу уже
    _part(grid, BODY_TORSO, p)
    x0, y0, x1, y1 = BODY_TORSO
    _bevel(grid, BODY_TORSO, None, 1)
    _box(grid, x0, y0, x0, y1, p["dk"])
    _box(grid, x1, y0, x1, y1, p["dd"])
    # ворот
    _box(grid, x0 + 4, y0, x1 - 4, y0, p["hi"])
    _box(grid, x0 + 3, y0 + 1, x1 - 3, y0 + 1, p["lt"])
    # застёжка и планка
    _gold(grid, (31, y0 + 3, 32, y1 - 3))
    _gold(grid, (29, y0 + 3, 34, y0 + 4))
    _gold(grid, (30, y0 + 8, 33, y0 + 9))
    # пояс
    _box(grid, x0 + 1, y1 - 2, x1 - 1, y1 - 1, p["dd"])
    _gold(grid, (30, y1 - 2, 33, y1 - 1))

    # руки и перчатки
    for box in (BODY_ARM_L, BODY_ARM_R):
        _part(grid, box, p)
        ax0, ay0, ax1, ay1 = box
        _box(grid, ax0, ay0, ax0, ay1, p["lt"])
        _box(grid, ax1, ay0, ax1, ay1, p["dd"])
        _box(grid, ax0, ay1 - 4, ax1, ay1, p["dd"])       # перчатка
        _box(grid, ax0, ay1 - 4, ax1, ay1 - 4, p["dk"])

    # шея
    _box(grid, 29, 29, 34, 31, p["dd"])

    # голова со срезанными углами
    _part(grid, BODY_HEAD, p)
    hx0, hy0, hx1, hy1 = BODY_HEAD
    _bevel(grid, BODY_HEAD, None, 2)
    _box(grid, hx0 + 2, hy0, hx1 - 2, hy0, p["hi"])
    _box(grid, hx0 + 1, hy0 + 1, hx1 - 1, hy0 + 1, p["hi"])
    _box(grid, hx0 + 1, hy0 + 2, hx1 - 1, hy0 + 2, p["lt"])
    _box(grid, hx0, hy0 + 3, hx0, hy1 - 2, p["dk"])
    _box(grid, hx1, hy0 + 3, hx1, hy1 - 2, p["dd"])

    # волосы: чёлка и виски
    _box(grid, hx0 + 1, hy0 + 2, hx1 - 1, hy0 + 4, p["dd"])
    _box(grid, hx0 + 1, hy0 + 2, hx0 + 4, hy0 + 9, p["dd"])
    _box(grid, hx1 - 4, hy0 + 2, hx1 - 1, hy0 + 9, p["dd"])
    _box(grid, hx0 + 6, hy0 + 4, hx1 - 6, hy0 + 5, p["dk"])
    # уши
    _box(grid, hx0 - 1, hy0 + 9, hx0 - 1, hy0 + 12, p["dk"])
    _box(grid, hx1 + 1, hy0 + 9, hx1 + 1, hy0 + 12, p["dd"])

    # брови
    _box(grid, 23, 15, 28, 15, p["dd"])
    _box(grid, 35, 15, 40, 15, p["dd"])
    # глаза: тёмные, с бликом — так читаются и в меню, и в руке
    for ex in (24, 35):
        _box(grid, ex, 17, ex + 4, 21, (44, 30, 52, 255))
        _box(grid, ex + 1, 17, ex + 3, 17, (62, 44, 72, 255))
        _box(grid, ex, 22, ex + 4, 22, (32, 22, 40, 255))
        _box(grid, ex + 1, 18, ex + 1, 18, (255, 255, 255, 255))
        _box(grid, ex + 2, 18, ex + 2, 19, (226, 220, 236, 255))
    _box(grid, 30, 17, 30, 17, p["dk"])
    _box(grid, 40, 17, 40, 17, p["dk"])
    # нос
    _box(grid, 31, 20, 32, 25, p["dd"])
    _box(grid, 30, 25, 32, 25, p["dk"])
    # рот
    _box(grid, 28, 26, 35, 26, (112, 52, 68, 255))
    _box(grid, 29, 27, 34, 27, p["dd"])
    # подбородок и щёки
    _box(grid, 25, 28, 38, 28, p["dk"])
    _box(grid, hx0 + 2, 24, hx0 + 3, 26, p["dk"])
    _box(grid, hx1 - 3, 24, hx1 - 2, 26, p["dd"])


def _crown(grid: Grid) -> None:
    _gold(grid, (20, 6, 43, 9))
    _box(grid, 20, 9, 43, 9, (170, 128, 40, 255))
    for sx in (20, 26, 32, 38, 42):
        _gold(grid, (sx, 3, sx + 1, 6))
        _box(grid, sx, 3, sx + 1, 3, GOLD_LT)
    for gx in (23, 29, 35):
        _box(grid, gx, 7, gx + 1, 8, (150, 60, 90, 255))
        _box(grid, gx, 7, gx, 7, (255, 190, 210, 255))


def _horns(grid: Grid, p: dict) -> None:
    for side in (-1, 1):
        x = 18 if side < 0 else 41
        step = 1 if side > 0 else -1
        for i in range(5):
            _box(grid, x + step * i, 8 - i, x + step * i + 2, 10 - i,
                 (238, 228, 204, 255) if i < 3 else (250, 244, 226, 255))
        _box(grid, x + step * 4, 3, x + step * 4 + 2, 4, (255, 250, 236, 255))


def _helm(grid: Grid, p: dict) -> None:
    _metal(grid, (19, 4, 44, 14))
    _box(grid, 19, 4, 44, 4, STEEL_LT)
    _box(grid, 19, 13, 44, 13, (120, 128, 148, 255))
    _gold(grid, (30, 2, 33, 8))                       # гребень
    _metal(grid, (18, 14, 21, 28))                    # нащёчники
    _metal(grid, (42, 14, 45, 28))
    _box(grid, 18, 14, 18, 28, (110, 118, 138, 255))
    _box(grid, 45, 14, 45, 28, (110, 118, 138, 255))
    _box(grid, 22, 12, 41, 13, (86, 94, 112, 255))    # тень козырька на лбу
    for ry in (20, 25):
        _box(grid, 19, ry, 20, ry, GOLD_LT)
        _box(grid, 43, ry, 44, ry, GOLD_LT)


def _hood(grid: Grid, p: dict) -> None:
    """Капюшон рамкой: лицо остаётся открытым, вокруг — тёмная ткань."""
    dark = _tint(p["base"][:3], 0.42)
    mid = _tint(p["base"][:3], 0.58)
    _box(grid, 17, 5, 46, 15, mid)
    _box(grid, 17, 5, 46, 9, dark)
    _box(grid, 17, 5, 28, 9, dark)
    _box(grid, 35, 5, 46, 9, dark)
    _box(grid, 17, 6, 20, 33, mid)
    _box(grid, 43, 6, 46, 33, mid)
    _box(grid, 17, 6, 18, 33, dark)
    _box(grid, 45, 6, 46, 33, dark)
    _box(grid, 20, 14, 43, 15, dark)          # козырёк капюшона
    _box(grid, 21, 15, 42, 15, _tint(p["base"][:3], 0.30))
    _box(grid, 17, 30, 46, 33, mid)           # плечевая накидка
    _box(grid, 17, 32, 46, 33, dark)


def _halo(grid: Grid) -> None:
    _box(grid, 22, 2, 41, 4, GOLD_LT)
    _box(grid, 24, 5, 39, 6, (255, 250, 214, 255))
    _box(grid, 22, 2, 22, 6, (255, 255, 240, 255))
    _box(grid, 41, 2, 41, 6, (255, 255, 240, 255))
    _box(grid, 25, 1, 38, 1, (255, 240, 180, 255))
    _box(grid, 26, 0, 37, 0, (255, 250, 220, 255))


def _hat(grid: Grid) -> None:
    _wood(grid, (13, 8, 50, 11))
    _box(grid, 13, 11, 50, 11, (92, 60, 34, 255))
    _box(grid, 13, 8, 14, 11, (170, 120, 72, 255))
    _wood(grid, (21, 3, 42, 9))
    _box(grid, 21, 8, 42, 9, (206, 168, 74, 255))
    _box(grid, 21, 8, 21, 9, GOLD_LT)
    _box(grid, 21, 3, 42, 3, (150, 104, 60, 255))


def _top(grid: Grid, kind: str, p: dict) -> None:
    if kind == "crown":
        _crown(grid)
    elif kind == "horns":
        _horns(grid, p)
    elif kind == "helm":
        _helm(grid, p)
    elif kind == "hood":
        _hood(grid, p)
    elif kind == "halo":
        _halo(grid)
    elif kind == "hat":
        _hat(grid)


def _sword(grid: Grid) -> None:
    _metal(grid, (49, 14, 53, 48))
    _box(grid, 49, 14, 53, 15, (255, 255, 255, 255))
    _box(grid, 53, 40, 53, 48, (150, 158, 178, 255))
    _gold(grid, (46, 49, 56, 52))
    _wood(grid, (50, 53, 52, 60))
    _gold(grid, (49, 61, 53, 62))


def _axe(grid: Grid) -> None:
    _wood(grid, (50, 12, 53, 60))
    _metal(grid, (44, 14, 56, 26))
    _box(grid, 44, 14, 56, 15, STEEL_LT)
    _box(grid, 56, 18, 58, 23, (176, 184, 202, 255))
    _gold(grid, (47, 18, 53, 21))
    _box(grid, 47, 18, 53, 18, GOLD_LT)


def _daggers(grid: Grid) -> None:
    for x, d in ((44, 1), (17, -1)):
        _metal(grid, (x, 22, x + 3, 40))
        _box(grid, x, 22, x + 3, 22, (255, 255, 255, 255))
        _gold(grid, (x - 1, 41, x + 4, 43))
        _wood(grid, (x + 1, 44, x + 2, 47))


def _bow(grid: Grid) -> None:
    _wood(grid, (10, 28, 13, 58))
    _wood(grid, (8, 26, 12, 30))
    _wood(grid, (8, 56, 12, 60))
    _box(grid, 14, 28, 14, 58, (226, 226, 226, 255))
    _box(grid, 13, 30, 13, 56, (198, 198, 198, 255))
    _gold(grid, (11, 38, 13, 40))


def _staff(grid: Grid, p: dict) -> None:
    _wood(grid, (51, 14, 53, 62))
    _box(grid, 51, 14, 51, 62, (160, 112, 66, 255))
    _box(grid, 47, 4, 57, 13, (150, 96, 232, 255))
    _box(grid, 49, 5, 55, 12, (198, 158, 255, 255))
    _box(grid, 50, 6, 52, 9, (238, 216, 255, 255))
    _box(grid, 48, 2, 56, 3, (236, 214, 255, 255))
    _box(grid, 46, 5, 47, 12, (120, 70, 200, 255))
    _box(grid, 57, 5, 58, 12, (120, 70, 200, 255))


def _shield(grid: Grid, p: dict) -> None:
    _metal(grid, (5, 30, 17, 56))
    _box(grid, 5, 30, 17, 32, GOLD_LT)
    _box(grid, 5, 54, 17, 56, (150, 158, 178, 255))
    _box(grid, 6, 55, 16, 57, (170, 178, 196, 255))
    _gold(grid, (9, 38, 13, 46))
    _box(grid, 10, 40, 12, 44, (255, 240, 180, 255))
    _box(grid, 6, 33, 6, 52, (230, 236, 250, 255))


def _pauldrons(grid: Grid, p: dict) -> None:
    for x0, x1 in ((13, 20), (43, 50)):
        _metal(grid, (x0, 29, x1, 37))
        _box(grid, x0, 29, x1, 30, STEEL_LT)
        _box(grid, x0, 37, x1, 37, (128, 136, 156, 255))
        _gold(grid, (x0 + 1, 33, x1 - 1, 34))


def _wings(grid: Grid, p: dict) -> None:
    for x0, x1 in ((2, 17), (46, 61)):
        _part(grid, (x0, 20, x1, 50), p)
        _box(grid, x0, 20, x1, 22, p["hi"])
        for i in range(4):
            y = 26 + i * 6
            _box(grid, x0 + 1, y, x1 - 1, y, p["dk"])
        _box(grid, x0 + 3, 32, x1 - 2, 48, p["lt"])
        _box(grid, x0, 20, x0, 50, p["dk"])
        if x1 < 32:
            _box(grid, x1, 22, x1, 48, p["dd"])
        else:
            _box(grid, x0, 22, x0, 48, p["dd"])


def _cape(grid: Grid, p: dict) -> None:
    for x0, x1 in ((7, 20), (43, 56)):
        _part(grid, (x0, 30, x1, 58), p)
        _box(grid, x0, 30, x1, 32, (206, 168, 74, 255))
        _box(grid, x0, 33, x1, 34, p["dk"])
        _box(grid, x0, 44, x1, 45, p["dk"])
        _box(grid, x0, 58, x1, 58, (206, 168, 74, 255))


def _gear(grid: Grid, kind: str, p: dict) -> None:
    if kind == "sword":
        _sword(grid)
    elif kind == "axe":
        _axe(grid)
    elif kind == "daggers":
        _daggers(grid)
    elif kind == "bow":
        _bow(grid)
    elif kind == "staff":
        _staff(grid, p)
    elif kind == "shield":
        _shield(grid, p)
    elif kind == "pauldrons":
        _pauldrons(grid, p)
    elif kind == "wings":
        _wings(grid, p)
    elif kind == "cape":
        _cape(grid, p)


def _outline(grid: Grid) -> Grid:
    """Тёмный контур по силуэту — фигурка читается на любом фоне."""
    def solid(x: int, y: int) -> bool:
        return 0 <= x < S and 0 <= y < S and grid[y][x] is not None

    out = [row[:] for row in grid]
    for y in range(S):
        for x in range(S):
            if solid(x, y):
                continue
            if (solid(x - 1, y) or solid(x + 1, y) or solid(x, y - 1)
                    or solid(x, y + 1) or solid(x - 1, y - 1) or solid(x + 1, y + 1)):
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
    "light": (64, 0, 32, 32),
    "dark": (64, 32, 32, 32),
    "gold": (96, 0, 16, 32),
    "bone": (112, 0, 16, 32),
    "steel": (96, 32, 16, 32),
    "wood": (112, 32, 16, 32),
}
MATERIAL = {
    "gold": (232, 186, 74, 255),
    "bone": (240, 230, 206, 255),
    "steel": (180, 188, 206, 255),
    "wood": (126, 86, 50, 255),
}


def atlas_png(slug: str) -> bytes:
    """
    Атлас 128×128: слева сверху спрайт 64×64 (лицо, одежда, оружие), справа —
    однотонные плашки: светлая и тёмная грани фигурки, золото, кость, сталь,
    дерево. Из них собирается орнамент 3D-модели (корона, рога, шлем, крылья…).
    """
    p = _ramp(TALISMANS[slug]["color"])
    grid = render_icon(slug)

    canvas: Grid = [[None] * TEX for _ in range(TEX)]
    for y in range(S):
        for x in range(S):
            canvas[y][x] = grid[y][x]

    for key, color in (
        ("light", p["lt"]),
        ("dark", p["dd"]),
        ("gold", MATERIAL["gold"]),
        ("bone", MATERIAL["bone"]),
        ("steel", MATERIAL["steel"]),
        ("wood", MATERIAL["wood"]),
    ):
        x0, y0, w, h = SWATCH[key]
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
HAND_SCALE = 0.58
HAND_POS_THIRD = [0, 16.4, -7]  # подошвы на ладони, фигурка вынесена
                               # вперёд, чтобы не тонула в бедре
HAND_POS_FIRST = [2, 14, 4]

# области спрайта, которые натягиваются на грани кубов: (x, y, w, h)
REGIONS = {
    "head":  (21, 10, 22, 20),
    "torso": (19, 31, 26, 16),
    "arm":   (14, 33, 5, 16),
    "leg":   (20, 47, 12, 15),
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
        cubes.append(_cube([-2.4, 11.9, -1.6], [4.8, 1.0, 3.2], "gold"))
        cubes.append(_cube([-2.4, 12.9, -1.1], [0.9, 1.3, 2.2], "gold"))
        cubes.append(_cube([1.5, 12.9, -1.1], [0.9, 1.3, 2.2], "gold"))
        cubes.append(_cube([-0.45, 12.9, -1.1], [0.9, 1.0, 2.2], "gold"))
    elif top == "horns":
        cubes.append(_cube([-3.1, 11.6, -1.0], [0.9, 2.0, 0.9], "bone"))
        cubes.append(_cube([2.2, 11.6, -1.0], [0.9, 2.0, 0.9], "bone"))
        cubes.append(_cube([-3.6, 13.0, -0.7], [0.8, 0.9, 0.8], "bone"))
        cubes.append(_cube([2.8, 13.0, -0.7], [0.8, 0.9, 0.8], "bone"))
    elif top == "halo":
        cubes.append(_cube([-2.4, 13.6, -1.7], [4.8, 0.5, 0.6], "gold"))
        cubes.append(_cube([-2.4, 13.6, 1.1], [4.8, 0.5, 0.6], "gold"))
        cubes.append(_cube([-2.4, 13.6, -1.1], [0.6, 0.5, 2.2], "gold"))
        cubes.append(_cube([1.8, 13.6, -1.1], [0.6, 0.5, 2.2], "gold"))
    elif top == "helm":
        cubes.append(_cube([-3.0, 11.9, -1.7], [6.0, 1.2, 3.4], "steel"))
        cubes.append(_cube([-2.9, 9.2, -1.7], [0.8, 2.7, 2.4], "steel"))
        cubes.append(_cube([2.1, 9.2, -1.7], [0.8, 2.7, 2.4], "steel"))
        cubes.append(_cube([-1.0, 9.4, -2.0], [2.0, 0.5, 0.5], "steel"))
        cubes.append(_cube([-0.4, 10.0, -2.1], [0.8, 1.7, 0.5], "steel"))
    elif top == "hood":
        cubes.append(_cube([-3.0, 9.0, 1.2], [6.0, 3.6, 0.8], "dark"))
        cubes.append(_cube([-3.0, 12.1, -1.7], [6.0, 1.0, 3.7], "dark"))
        cubes.append(_cube([-3.0, 9.0, -1.7], [0.7, 3.4, 3.0], "dark"))
        cubes.append(_cube([2.3, 9.0, -1.7], [0.7, 3.4, 3.0], "dark"))
    elif top == "hat":
        cubes.append(_cube([-4.2, 11.7, -2.2], [8.4, 0.7, 4.4], "wood"))
        cubes.append(_cube([-2.2, 12.4, -1.7], [4.4, 1.5, 3.4], "wood"))
        cubes.append(_cube([-2.2, 11.5, -1.8], [4.4, 0.4, 3.6], "gold"))

    if gear == "wings":
        cubes.append(_cube([-5.2, 5.2, 1.3], [2.4, 5.4, 0.6], "dark"))
        cubes.append(_cube([2.8, 5.2, 1.3], [2.4, 5.4, 0.6], "dark"))
        cubes.append(_cube([-6.0, 7.6, 1.3], [1.5, 2.6, 0.6], "dark"))
        cubes.append(_cube([4.6, 7.6, 1.3], [1.5, 2.6, 0.6], "dark"))
    elif gear == "cape":
        cubes.append(_cube([-3.0, 1.8, 1.3], [6.0, 6.4, 0.6], "dark"))
        cubes.append(_cube([-3.6, 1.4, 1.3], [7.2, 0.8, 0.6], "gold"))
    elif gear == "shield":
        cubes.append(_cube([-5.6, 3.4, -1.3], [1.0, 5.0, 2.6], "steel"))
        cubes.append(_cube([-5.7, 5.4, -0.9], [1.2, 1.4, 1.8], "gold"))
    elif gear == "staff":
        cubes.append(_cube([4.9, 0.6, -0.9], [0.9, 11.0, 0.9], "wood"))
        cubes.append(_cube([4.5, 11.4, -1.4], [1.9, 1.9, 1.9], "gold"))
    elif gear == "sword":
        cubes.append(_cube([5.0, 2.8, -0.8], [0.8, 7.0, 0.8], "steel"))
        cubes.append(_cube([4.4, 2.1, -1.3], [1.9, 0.6, 1.7], "gold"))
        cubes.append(_cube([5.0, 0.8, -0.8], [0.8, 1.3, 0.8], "wood"))
    elif gear == "daggers":
        cubes.append(_cube([4.7, 4.4, -0.7], [0.6, 3.6, 0.6], "steel"))
        cubes.append(_cube([-5.3, 4.4, -0.7], [0.6, 3.6, 0.6], "steel"))
    elif gear == "axe":
        cubes.append(_cube([5.2, 0.8, -0.7], [0.8, 8.0, 0.8], "wood"))
        cubes.append(_cube([3.4, 7.4, -1.1], [2.8, 2.4, 1.6], "steel"))
        cubes.append(_cube([3.1, 7.8, -1.3], [2.2, 1.6, 0.4], "gold"))
    elif gear == "bow":
        cubes.append(_cube([-4.8, 2.4, -0.6], [0.8, 8.0, 0.8], "wood"))
        cubes.append(_cube([-5.1, 9.8, -0.6], [1.4, 1.1, 1.1], "wood"))
        cubes.append(_cube([-5.1, 1.8, -0.6], [1.4, 1.1, 1.1], "wood"))
    elif gear == "pauldrons":
        cubes.append(_cube([-4.6, 7.0, -1.4], [1.8, 2.4, 3.0], "steel"))
        cubes.append(_cube([2.8, 7.0, -1.4], [1.8, 2.4, 3.0], "steel"))
    return cubes


BODY_CUBES = [
    # origin, size, область спрайта. Размеры = пиксели рамки / 4, поэтому
    # лицо и одежда ложатся на кубы без искажений. Фигурка симметрична
    # относительно x = 0 и стоит на y = 0 — растёт вверх от ладони.
    ([-2.75, 7.75, -1.5], [5.5, 5, 3], "head"),     # голова 22×20 px
    ([-3.25, 3.75, -1.5], [6.5, 4, 3], "torso"),    # торс 26×16 px
    ([-4.5, 3.75, -1.5], [1.25, 4, 3], "arm"),      # руки 5×16 px
    ([3.25, 3.75, -1.5], [1.25, 4, 3], "arm"),
    ([-3, 0, -1.5], [3, 3.75, 3], "leg"),           # ноги 12×15 px
    ([0, 0, -1.5], [3, 3.75, 3], "leg"),
]
HEIGHT = 12.75   # высота фигурки в единицах (1/16 блока)


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
    print(f"OK: {len(TALISMANS)} иконок 64×64 + атласы 128×128 -> {out_dir}")
    return 0


if __name__ == "__main__":
    raise SystemExit(_selfcheck())
