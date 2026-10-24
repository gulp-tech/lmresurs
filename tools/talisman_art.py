#!/usr/bin/env python3
"""
Талисманы для Bedrock: перекраска иконок и 3D-модель в руке.

Зачем нужен этот модуль:

  1. Иконки талисманов FunTime — обычные 16×16 спрайты-фигурки. На тёмном фоне
     магазина они «проваливаются», а по краям нет объёма. Здесь каждая иконка
     аккуратно дорабатывается: золотой кант по силуэту, лёгкий блик сверху,
     чуть больше насыщенности. Силуэт и цвета остаются узнаваемыми — это
     именно «немного изменить», а не перерисовать.

  2. В руке ванильный тотем выглядит как тотем. Чтобы талисман читался объёмно,
     для каждого из 12 предметов генерируется Bedrock-attachable: плоская
     фигурка с золотым торцом, привязанная к руке. Геометрия и анимации взяты
     по образцу проверенных 3D-сфер (те же pivot/rotation), меняется только
     толщина куба и UV — поэтому модель встаёт в руку так же, как сфера.

Зависимости: только стандартная библиотека Python 3 (свой PNG-декодер,
потому что иконки FunTime — палитровые PNG, color type 3).

Использование из build_bedrock_pack.py:

    import talisman_art
    talisman_art.restyle_icons(icons)                       # 16×16 иконки
    icons[hero] = talisman_art.make_hero(icons[crusher])    # 12-й талисман
    talisman_art.build_attachables(build_dir, names, icons, namespace)
"""

from __future__ import annotations

import json
import os
import struct
import zlib
from typing import Dict, Iterable, List, Tuple

# Слаги талисманов, которые есть в Java-паке FunTime (11 штук; 12-й — «герой»,
# генерируется перекраской crusher).
TALISMAN_SLUGS = (
    "harmony",
    "daedalus",
    "triton",
    "discord",
    "echidna",
    "crusher",
    "punisher",
    "tyrant",
    "edge",
    "phoenix",
    "grinch",
)

HERO_SLUG = "hero"
GOLD = (236, 190, 78)

# Имена файлов 3D-части внутри пака.
GEO_FILE = os.path.join("models", "entity", "talisman.geo.json")
ANIM_FILE = os.path.join("animations", "talisman.json")
RC_FILE = os.path.join("render_controllers", "talisman.render_controllers.json")
ATTACHABLE_DIR = "attachables"
TEXTURE_3D_DIR = os.path.join("textures", "item")


# --------------------------------------------------------------------------------------
# PNG: чтение и запись (палитра / RGB / RGBA, 8 бит, без интерлейса)
# --------------------------------------------------------------------------------------

def _paeth(a: int, b: int, c: int) -> int:
    p = a + b - c
    pa, pb, pc = abs(p - a), abs(p - b), abs(p - c)
    if pa <= pb and pa <= pc:
        return a
    if pb <= pc:
        return b
    return c


def _unfilter(raw: bytes, width: int, height: int, channels: int) -> bytearray:
    out = bytearray(height * width * channels)
    stride = width * channels
    prev = bytearray(stride)
    pos = 0
    for y in range(height):
        filter_type = raw[pos]
        pos += 1
        line = bytearray(raw[pos:pos + stride])
        pos += stride
        if filter_type == 1:
            for i in range(channels, stride):
                line[i] = (line[i] + line[i - channels]) & 0xFF
        elif filter_type == 2:
            for i in range(stride):
                line[i] = (line[i] + prev[i]) & 0xFF
        elif filter_type == 3:
            for i in range(stride):
                left = line[i - channels] if i >= channels else 0
                line[i] = (line[i] + ((left + prev[i]) >> 1)) & 0xFF
        elif filter_type == 4:
            for i in range(stride):
                left = line[i - channels] if i >= channels else 0
                upleft = prev[i - channels] if i >= channels else 0
                line[i] = (line[i] + _paeth(left, prev[i], upleft)) & 0xFF
        elif filter_type != 0:
            raise ValueError(f"неизвестный PNG-фильтр: {filter_type}")
        out[y * stride:(y + 1) * stride] = line
        prev = line
    return out


def decode_png(data: bytes) -> Tuple[int, int, bytearray]:
    """PNG -> (ширина, высота, RGBA-байты)."""
    if data[:8] != b"\x89PNG\r\n\x1a\n":
        raise ValueError("не PNG")
    pos = 8
    palette: bytes = b""
    trns: bytes = b""
    idat = b""
    width = height = depth = color_type = 0
    while pos < len(data):
        (length,) = struct.unpack(">I", data[pos:pos + 4])
        tag = data[pos + 4:pos + 8]
        payload = data[pos + 8:pos + 8 + length]
        pos += 12 + length
        if tag == b"IHDR":
            width, height, depth, color_type, _comp, _filt, interlace = struct.unpack(
                ">IIBBBBB", payload
            )
            if depth != 8 or interlace != 0:
                raise ValueError("поддерживаются только 8-битные PNG без интерлейса")
        elif tag == b"PLTE":
            palette = payload
        elif tag == b"tRNS":
            trns = payload
        elif tag == b"IDAT":
            idat += payload
        elif tag == b"IEND":
            break
    if not width or not height:
        raise ValueError("в PNG нет IHDR")

    channels = {0: 1, 2: 3, 3: 1, 4: 2, 6: 4}[color_type]
    pixels = _unfilter(zlib.decompress(idat), width, height, channels)

    rgba = bytearray(width * height * 4)
    for i in range(width * height):
        if color_type == 6:
            rgba[i * 4:i * 4 + 4] = pixels[i * 4:i * 4 + 4]
        elif color_type == 2:
            r, g, b = pixels[i * 3:i * 3 + 3]
            rgba[i * 4:i * 4 + 4] = bytes((r, g, b, 255))
        elif color_type == 3:
            idx = pixels[i]
            if idx * 3 + 2 < len(palette):
                r, g, b = palette[idx * 3:idx * 3 + 3]
            else:
                r = g = b = 0
            a = trns[idx] if idx < len(trns) else 255
            rgba[i * 4:i * 4 + 4] = bytes((r, g, b, a))
        elif color_type == 4:
            g, a = pixels[i * 2:i * 2 + 2]
            rgba[i * 4:i * 4 + 4] = bytes((g, g, g, a))
        else:  # 0 — градации серого
            g = pixels[i]
            rgba[i * 4:i * 4 + 4] = bytes((g, g, g, 255))
    return width, height, rgba


def encode_png(width: int, height: int, rgba: bytes) -> bytes:
    """RGBA-байты -> PNG (color type 6), фильтр 0 по всем строкам."""
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


# --------------------------------------------------------------------------------------
# Перекраска иконок
# --------------------------------------------------------------------------------------

def _clamp(value: float) -> int:
    return 0 if value < 0 else 255 if value > 255 else int(round(value))


def _saturate(r: int, g: int, b: int, factor: float) -> Tuple[int, int, int]:
    luma = 0.299 * r + 0.587 * g + 0.114 * b
    return (
        _clamp(luma + (r - luma) * factor),
        _clamp(luma + (g - luma) * factor),
        _clamp(luma + (b - luma) * factor),
    )


def restyle_pixels(width: int, height: int, rgba: bytearray) -> bytearray:
    """
    «Немного другая» иконка талисмана:
      * золотой кант по внешнему силуэту (внутри силуэта, чтобы не расширять его —
        в 16×16 лишних пикселей нет),
      * блик сверху и мягкое затемнение снизу — появляется объём,
      * +10% насыщенности, чтобы фигурка читалась на тёмном фоне меню.
    """
    alpha_at = lambda x, y: rgba[(y * width + x) * 4 + 3]
    out = bytearray(rgba)
    for y in range(height):
        for x in range(width):
            i = (y * width + x) * 4
            r, g, b, a = rgba[i:i + 4]
            if a < 40:
                continue
            neighbours = ((x - 1, y), (x + 1, y), (x, y - 1), (x, y + 1))
            edge = any(
                nx < 0 or ny < 0 or nx >= width or ny >= height or alpha_at(nx, ny) < 40
                for nx, ny in neighbours
            )
            if edge:
                # кант: 20% золота + 80% исходного цвета — тёплый контур, цвета не сдвигаются
                r = _clamp(0.20 * GOLD[0] + 0.80 * r)
                g = _clamp(0.20 * GOLD[1] + 0.80 * g)
                b = _clamp(0.20 * GOLD[2] + 0.80 * b)
            else:
                r, g, b = _saturate(r, g, b, 1.06)
                shade = 1.04 - 0.10 * (y / max(1, height - 1))   # лёгкий объём
                r = _clamp(r * shade)
                g = _clamp(g * shade)
                b = _clamp(b * shade)
            out[i:i + 4] = bytes((r, g, b, 255))
    return out


def make_hero_pixels(width: int, height: int, rgba: bytearray) -> bytearray:
    """12-й талисман («герой»): золотая версия фигурки с короной (crusher)."""
    out = bytearray(rgba)
    for i in range(0, len(rgba), 4):
        r, g, b, a = rgba[i:i + 4]
        if a < 40:
            continue
        luma = 0.299 * r + 0.587 * g + 0.114 * b
        gold_r = _clamp(luma * 1.18 + 62)
        gold_g = _clamp(luma * 1.02 + 30)
        gold_b = _clamp(luma * 0.36 + 6)
        out[i:i + 4] = bytes(
            (
                _clamp(0.62 * gold_r + 0.38 * r),
                _clamp(0.62 * gold_g + 0.38 * g),
                _clamp(0.62 * gold_b + 0.38 * b),
                255,
            )
        )
    return out


def is_talisman(icon_name: str) -> bool:
    """Любой из 12 талисманов (11 из Java-пака + сгенерированный «герой»)."""
    return icon_name.endswith(tuple(f"talisman_{slug}" for slug in TALISMAN_SLUGS + (HERO_SLUG,)))


def restyle_icons(icons: Dict[str, bytes]) -> List[str]:
    """Перекрашивает все иконки талисманов на месте. Возвращает их имена."""
    touched: List[str] = []
    for name in sorted(icons):
        if not is_talisman(name) or name.endswith(f"talisman_{HERO_SLUG}"):
            continue
        try:
            width, height, rgba = decode_png(icons[name])
        except ValueError:
            continue
        icons[name] = encode_png(width, height, restyle_pixels(width, height, rgba))
        touched.append(name)
    return touched


def make_hero(source_png: bytes) -> bytes:
    """Иконка «Талисмана героя» из иконки воина (та же фигурка с короной)."""
    width, height, rgba = decode_png(source_png)
    return encode_png(width, height, restyle_pixels(width, height, make_hero_pixels(width, height, rgba)))


# --------------------------------------------------------------------------------------
# 3D: attachable «фигурка в руке»
# --------------------------------------------------------------------------------------

def _geometry(identifier: str, bone: str) -> dict:
    """
    Плоская фигурка. Геометрия повторяет проверенную 3D-сферу VoidPE:
    тот же origin/pivot/rotation, но куб тоньше (2 вместо 8 по глубине),
    поэтому модель встаёт в руку точно так же, как сфера, и не «улетает».
    """
    if bone == "rightitem":
        cube = {
            "origin": [-4, 6, -1],
            "size": [8, 8, 2],
            "uv": {
                "north": {"uv": [0, 0], "uv_size": [16, 16]},     # лицо
                "south": {"uv": [16, 0], "uv_size": [16, 16]},    # спина
                "east": {"uv": [0, 16], "uv_size": [2, 16]},      # торец
                "west": {"uv": [2, 16], "uv_size": [2, 16]},
                "up": {"uv": [16, 16], "uv_size": [16, 2]},
                "down": {"uv": [16, 18], "uv_size": [16, 2]},
            },
        }
    else:
        cube = {
            "origin": [7, 15, -1],
            "size": [8, 8, 2],
            "pivot": [11, 9, 0],
            "rotation": [0.43564, -0.01423, -90.00478],
            "uv": {
                "north": {"uv": [0, 0], "uv_size": [16, 16]},
                "south": {"uv": [16, 0], "uv_size": [16, 16]},
                "east": {"uv": [0, 16], "uv_size": [2, 16]},
                "west": {"uv": [2, 16], "uv_size": [2, 16]},
                "up": {"uv": [16, 16], "uv_size": [16, 2]},
                "down": {"uv": [16, 18], "uv_size": [16, 2]},
            },
        }
    return {
        "description": {
            "identifier": identifier,
            "texture_width": 32,
            "texture_height": 32,
            "visible_bounds_width": 4,
            "visible_bounds_height": 3,
            "visible_bounds_offset": [0, 1.5, 0],
        },
        "bones": [
            {"name": bone, "pivot": [0, 15, 0]},
            {"name": "figurine", "parent": bone, "pivot": [0, 18, 0], "cubes": [cube]},
        ],
    }


def _animation(first_person: bool) -> dict:
    """Те же числа, что у проверенных 3D-сфер: в руке модель уже в кадре."""
    if first_person:
        return {
            "loop": True,
            "bones": {
                "rightitem": {"position": [0, 5, 4.0], "scale": [-0.85, -0.85, -0.85],
                              "rotation": [0.0, 35.0, 145.0]},
                "leftitem": {"position": [-100.0, -124.0, 120.0], "scale": [-6.8, -6.8, -6.8],
                             "rotation": [5.0, -30.0, 5.0]},
            },
        }
    return {
        "loop": True,
        "bones": {
            "rightitem": {"position": [0, -6, -4], "scale": [-0.55, -0.55, -0.55],
                          "rotation": [35.0, 0.0, 35.0]},
            "leftitem": {"position": [0, -6, -4], "scale": [-0.55, -0.55, -0.55],
                         "rotation": [35.0, 0.0, -35.0]},
        },
    }


def _texture_3d(icon_png: bytes) -> bytes:
    """
    Текстура 32×32 для 3D-фигурки:
      0..15 × 0..15   — лицо (сама иконка),
      16..31 × 0..15  — спина (то же, затемнённое),
      низ 16..31      — золото для торцов и кромок.
    Прозрачный фон сохраняется: материал entity_alphatest вырежет силуэт.
    """
    width, height, rgba = decode_png(icon_png)
    canvas = bytearray(32 * 32 * 4)

    def put(x: int, y: int, pixel: Tuple[int, int, int, int]) -> None:
        i = (y * 32 + x) * 4
        canvas[i:i + 4] = bytes(pixel)

    for y in range(16):
        for x in range(16):
            sx, sy = min(x, width - 1), min(y, height - 1)
            i = (sy * width + sx) * 4
            r, g, b, a = rgba[i:i + 4]
            put(x, y, (r, g, b, a))
            put(16 + x, y, (_clamp(r * 0.72), _clamp(g * 0.72), _clamp(b * 0.72), a))

    for y in range(16, 32):
        for x in range(32):
            # золото с лёгкими полосами, чтобы торец не выглядел плоской заливкой
            k = 1.0 if (x + y) % 4 else 0.86
            put(x, y, (_clamp(GOLD[0] * k), _clamp(GOLD[1] * k), _clamp(GOLD[2] * k), 255))
    return encode_png(32, 32, canvas)


def _write_json(path: str, payload: dict) -> None:
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as fh:
        json.dump(payload, fh, ensure_ascii=False, indent=2)


def build_attachables(
    pack_dir: str,
    icon_names: Iterable[str],
    icons: Dict[str, bytes],
    namespace: str,
) -> List[str]:
    """
    Пишет в пак geometry/animations/render_controllers, по attachable и 3D-текстуре
    на каждый талисман. Shortname предмета совпадает с именем иконки
    (bedrock_identifier = "<namespace>:<icon_name>"), как их строит build_bedrock_pack.
    """
    names = [n for n in sorted(set(icon_names)) if n in icons]
    if not names:
        return []

    _write_json(
        os.path.join(pack_dir, GEO_FILE),
        {
            "format_version": "1.12.0",
            "minecraft:geometry": [
                _geometry("geometry.talisman_right", "rightitem"),
                _geometry("geometry.talisman_left", "leftitem"),
            ],
        },
    )
    _write_json(
        os.path.join(pack_dir, ANIM_FILE),
        {
            "format_version": "1.10.0",
            "animations": {
                "animation.talisman.first_person": _animation(True),
                "animation.talisman.third_person": _animation(False),
            },
        },
    )
    _write_json(
        os.path.join(pack_dir, RC_FILE),
        {
            "format_version": "1.10.0",
            "render_controllers": {
                "controller.render.talisman_right": {
                    "geometry": "geometry.talisman_right",
                    "materials": [{"*": "material.default"}],
                    "textures": ["texture.default"],
                },
                "controller.render.talisman_left": {
                    "geometry": "geometry.talisman_left",
                    "materials": [{"*": "material.default"}],
                    "textures": ["texture.default"],
                },
            },
        },
    )

    written: List[str] = []
    for name in names:
        # в attachable ссылка без расширения, а сам файл обязан быть .png
        texture_rel = f"textures/item/{name}"
        png_path = os.path.join(pack_dir, *(texture_rel + ".png").split("/"))
        os.makedirs(os.path.dirname(png_path), exist_ok=True)
        with open(png_path, "wb") as fh:
            fh.write(_texture_3d(icons[name]))

        shortname = name
        _write_json(
            os.path.join(pack_dir, ATTACHABLE_DIR, f"{name}.json"),
            {
                "format_version": "1.10",
                "minecraft:attachable": {
                    "description": {
                        "identifier": f"{namespace}:{shortname}",
                        "materials": {
                            "default": "entity_alphatest",
                            "enchanted": "entity_alphatest_glint",
                        },
                        "textures": {
                            "default": texture_rel,
                            "enchanted": "textures/misc/enchanted_item_glint",
                        },
                        "geometry": {
                            "talisman_right": "geometry.talisman_right",
                            "talisman_left": "geometry.talisman_left",
                        },
                        "animations": {
                            "wield_first_person": "animation.talisman.first_person",
                            "wield_third_person": "animation.talisman.third_person",
                        },
                        "scripts": {
                            "animate": [
                                {"wield_first_person": "c.is_first_person"},
                                {"wield_third_person": "!c.is_first_person"},
                            ]
                        },
                        "render_controllers": [
                            {
                                "controller.render.talisman_right":
                                    f"query.get_equipped_item_name('main_hand') == '{shortname}'"
                            },
                            {
                                "controller.render.talisman_left":
                                    f"query.get_equipped_item_name('off_hand') == '{shortname}'"
                            },
                        ],
                    }
                },
            },
        )
        written.append(name)
    return written


# --------------------------------------------------------------------------------------
# Самопроверка: python3 tools/talisman_art.py <java-пак.zip> [--out /tmp/out]
# --------------------------------------------------------------------------------------

def _selfcheck() -> int:
    """Раскрашивает 11 иконок и складывает лист для визуальной проверки."""
    import sys
    import zipfile

    if len(sys.argv) < 2:
        print("Нужен путь к funtime.zip для самопроверки")
        return 2
    out_dir = "/tmp/talisman_check"
    if "--out" in sys.argv:
        out_dir = sys.argv[sys.argv.index("--out") + 1]
    os.makedirs(out_dir, exist_ok=True)

    icons: Dict[str, bytes] = {}
    with zipfile.ZipFile(sys.argv[1]) as zf:
        for info in zf.namelist():
            if "talisman_" not in info or not info.endswith(".png") or "/cases/" in info:
                continue
            icons[os.path.basename(info)[:-4]] = zf.read(info)

    for name, png in icons.items():
        width, height, rgba = decode_png(png)
        with open(os.path.join(out_dir, f"{name}.png"), "wb") as fh:
            fh.write(encode_png(width, height, restyle_pixels(width, height, rgba)))
    if "talisman_crusher" in icons:
        with open(os.path.join(out_dir, "talisman_hero.png"), "wb") as fh:
            fh.write(make_hero(icons["talisman_crusher"]))
    print(f"OK: {len(icons)} иконок -> {out_dir}")
    return 0


if __name__ == "__main__":
    raise SystemExit(_selfcheck())
