#!/usr/bin/env python3
"""
Талисманы: перекраска иконок FunTime и (не)модель в руке.

ПОЧЕМУ ТАК.

* Иконки. В Java-паке FunTime 11 талисманов нарисованы одной золотистой фигуркой
  с мелкими отличиями — в слоте меню они неразличимы. Полностью перерисовывать
  их не нужно (пользователь просил «как на FunTime, только немного изменить»),
  поэтому берётся ОРИГИНАЛЬНЫЙ спрайт FunTime и перекрашивается: у каждого
  талисмана свой оттенок, насыщенность чуть выше, добавлены обводка и объём.
  Силуэт, корона, глаза и все детали остаются ровно как в FunTime.

* Рука. У Bedrock кастомный предмет из Geyser — это обычная плоская иконка
  (`minecraft:icon`); Geyser НЕ создаёт для него attachable и не делает
  3D-модель. Наши attachables были единственной причиной, почему фигурка
  висела в руке криво: своя геометрия + своя анимация не совпадают с тем, как
  игра держит предмет. Поэтому attachables больше не генерируются, и в руке
  предмет выглядит ровно так же, как в Java-версии FunTime — плоской иконкой
  в ладони.
"""

from __future__ import annotations

import colorsys
import os
import struct
import zlib
from typing import Dict, List, Optional, Tuple

# --------------------------------------------------------------------------------------
# Цветовая схема: slug -> (оттенок 0..1, насыщенность x, яркость x)
# --------------------------------------------------------------------------------------

SCHEMES: Dict[str, Tuple[int, int, int]] = {
    "harmony": (74, 200, 110),     # Талисман жизни     — зелёный
    "daedalus": (222, 62, 66),     # Талисман ярости    — красный
    "triton": (72, 132, 232),      # Талисман стража    — синий
    "discord": (148, 92, 226),     # Талисман странника — фиолетовый
    "echidna": (138, 148, 166),    # Талисман танка     — стальной
    "crusher": (208, 62, 74),      # Талисман воина     — багряный
    "punisher": (60, 132, 92),     # Талисман убийцы    — тёмно-зелёный
    "tyrant": (238, 128, 46),      # Талисман берсерка  — оранжевый
    "edge": (162, 110, 62),        # Талисман охотника  — коричневый
    "phoenix": (86, 196, 232),     # Талисман бегуна    — голубой
    "grinch": (150, 34, 54),       # Талисман демона    — тёмно-красный
    "hero": (238, 196, 78),        # Талисман героя     — золотой
}

HERO_SOURCE = "crusher"        # из какой фигурки FunTime делается «герой»
HERO_ICON_NAME = "minecraft_item_funtime_items_talisman_hero"

# Пиксели с насыщенностью ниже порога (белки глаз, стальные блики) не перекрашиваем.
DESATURATED_KEEP = 0.18


# --------------------------------------------------------------------------------------
# PNG: чтение (палитровые спрайты FunTime) и запись
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
        ftype = raw[pos]
        pos += 1
        line = bytearray(raw[pos:pos + stride])
        pos += stride
        if ftype == 1:
            for i in range(channels, stride):
                line[i] = (line[i] + line[i - channels]) & 0xFF
        elif ftype == 2:
            for i in range(stride):
                line[i] = (line[i] + prev[i]) & 0xFF
        elif ftype == 3:
            for i in range(stride):
                left = line[i - channels] if i >= channels else 0
                line[i] = (line[i] + ((left + prev[i]) >> 1)) & 0xFF
        elif ftype == 4:
            for i in range(stride):
                left = line[i - channels] if i >= channels else 0
                upleft = prev[i - channels] if i >= channels else 0
                line[i] = (line[i] + _paeth(left, prev[i], upleft)) & 0xFF
        elif ftype != 0:
            raise ValueError(f"неизвестный PNG-фильтр: {ftype}")
        out[y * stride:(y + 1) * stride] = line
        prev = line
    return out


def decode_png(data: bytes) -> Tuple[int, int, bytearray]:
    if data[:8] != b"\x89PNG\r\n\x1a\n":
        raise ValueError("не PNG")
    pos = 8
    palette = b""
    trns = b""
    idat = b""
    width = height = 0
    color_type = 0
    while pos < len(data):
        (length,) = struct.unpack(">I", data[pos:pos + 4])
        tag = data[pos + 4:pos + 8]
        payload = data[pos + 8:pos + 8 + length]
        pos += 12 + length
        if tag == b"IHDR":
            width, height, depth, color_type, _c, _f, interlace = struct.unpack(
                ">IIBBBBB", payload
            )
            if depth != 8 or interlace != 0:
                raise ValueError("только 8-битные PNG без интерлейса")
        elif tag == b"PLTE":
            palette = payload
        elif tag == b"tRNS":
            trns = payload
        elif tag == b"IDAT":
            idat += payload
        elif tag == b"IEND":
            break
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
            r, g, b = palette[idx * 3:idx * 3 + 3] if idx * 3 + 2 < len(palette) else (0, 0, 0)
            a = trns[idx] if idx < len(trns) else 255
            rgba[i * 4:i * 4 + 4] = bytes((r, g, b, a))
        elif color_type == 4:
            g, a = pixels[i * 2:i * 2 + 2]
            rgba[i * 4:i * 4 + 4] = bytes((g, g, g, a))
        else:
            g = pixels[i]
            rgba[i * 4:i * 4 + 4] = bytes((g, g, g, 255))
    return width, height, rgba


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


# --------------------------------------------------------------------------------------
# Перекраска
# --------------------------------------------------------------------------------------

def _clamp8(value: float) -> int:
    return 0 if value < 0 else 255 if value > 255 else int(round(value))


MIX = 0.78          # сила перекраски: 0 — оригинал FunTime, 1 — чистый цвет


def recolor(width: int, height: int, rgba: bytearray, base: Tuple[int, int, int]) -> bytearray:
    """
    Перекрашивает спрайт FunTime в цвет талисмана, СМЕШИВАЯ его с оригиналом.

    * тонированный вариант = цвет талисмана, разложенный по яркости исходника;
    * результат = 78 % тона + 22 % оригинала: рисунок, корона, складки и лицо
      FunTime остаются читаемыми, но у каждого талисмана свой цвет;
    * золотые детали и белки глаз не тонируются совсем — иначе лицо
      «заливается» цветом и фигурка превращается в пятно.
    """
    out = bytearray(rgba)
    br, bg, bb = base
    for i in range(0, len(rgba), 4):
        r, g, b, a = rgba[i:i + 4]
        if a < 32:
            continue
        lum = (0.299 * r + 0.587 * g + 0.114 * b) / 255.0
        h, l, sat = colorsys.rgb_to_hls(r / 255, g / 255, b / 255)
        if (lum > 0.84 and sat < 0.25) or (sat > 0.30 and 0.07 <= h <= 0.17 and lum > 0.35):
            continue                                  # белки глаз / золото FunTime
        tint = (br * (0.30 + 1.45 * lum), bg * (0.30 + 1.45 * lum), bb * (0.30 + 1.45 * lum))
        out[i:i + 4] = bytes((
            _clamp8(r * (1 - MIX) + tint[0] * MIX),
            _clamp8(g * (1 - MIX) + tint[1] * MIX),
            _clamp8(b * (1 - MIX) + tint[2] * MIX),
            a,
        ))
    return out


def outline(width: int, height: int, rgba: bytearray) -> bytearray:
    """
    Тёмная обводка по внешнему контуру: на сером фоне сундука фигурка
    перестаёт сливаться и читается как предмет, а не как пятно.
    """
    alpha = lambda x, y: rgba[(y * width + x) * 4 + 3]
    out = bytearray(rgba)
    for y in range(height):
        for x in range(width):
            if alpha(x, y) >= 32:
                continue
            near = ((x - 1, y), (x + 1, y), (x, y - 1), (x, y + 1))
            if any(
                0 <= nx < width and 0 <= ny < height and alpha(nx, ny) >= 128
                for nx, ny in near
            ):
                i = (y * width + x) * 4
                out[i:i + 4] = bytes((26, 18, 32, 150))
    return out


def slug_of(icon_name: str) -> Optional[str]:
    for slug in SCHEMES:
        if icon_name.endswith(f"talisman_{slug}"):
            return slug
    return None


def restyle_icons(icons: Dict[str, bytes]) -> List[str]:
    """Перекрашивает иконки талисманов на месте. Возвращает список имён."""
    touched: List[str] = []
    for name in sorted(icons):
        slug = slug_of(name)
        if not slug or slug == "hero":
            continue
        try:
            width, height, rgba = decode_png(icons[name])
        except ValueError:
            continue
        rgba = recolor(width, height, rgba, SCHEMES[slug])
        icons[name] = encode_png(width, height, outline(width, height, rgba))
        touched.append(name)
    return touched


def hero_icon(icons: Dict[str, bytes]) -> Optional[Tuple[str, bytes]]:
    """12-й талисман: золотая версия фигурки воина (в FunTime её не было)."""
    source = next((n for n in icons if n.endswith(f"talisman_{HERO_SOURCE}")), None)
    if source is None:
        return None
    width, height, rgba = decode_png(icons[source])
    rgba = recolor(width, height, rgba, SCHEMES["hero"])
    return HERO_ICON_NAME, encode_png(width, height, outline(width, height, rgba))


# --------------------------------------------------------------------------------------
# Самопроверка: python3 tools/talisman_art.py <funtime.zip> --out /tmp/talismans
# --------------------------------------------------------------------------------------

def _selfcheck() -> int:
    import sys
    import zipfile

    if len(sys.argv) < 2:
        print("нужен путь к funtime.zip")
        return 2
    out_dir = "/tmp/talismans"
    if "--out" in sys.argv:
        out_dir = sys.argv[sys.argv.index("--out") + 1]
    os.makedirs(out_dir, exist_ok=True)

    icons: Dict[str, bytes] = {}
    with zipfile.ZipFile(sys.argv[1]) as zf:
        for info in zf.namelist():
            if "talisman_" in info and info.endswith(".png") and "/cases/" not in info:
                icons[os.path.basename(info)[:-4]] = zf.read(info)

    for name in restyle_icons(icons):
        with open(os.path.join(out_dir, f"{name}.png"), "wb") as fh:
            fh.write(icons[name])
    hero = hero_icon(icons)
    if hero:
        with open(os.path.join(out_dir, f"{hero[0]}.png"), "wb") as fh:
            fh.write(hero[1])
    print(f"OK: {len(icons)} иконок -> {out_dir}")
    return 0


if __name__ == "__main__":
    raise SystemExit(_selfcheck())
