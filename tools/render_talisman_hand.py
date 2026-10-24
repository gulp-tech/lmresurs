#!/usr/bin/env python3
"""
Оффлайн-рендер 3D-модели талисмана «в руке» — чтобы видеть результат, не
заходя в игру.

Читает настоящие файлы из пака (models/entity/talisman.geo.json,
animations/talisman.json, textures/items/*_3d.png), применяет анимацию
третьего лица и рисует фигурку на схематичной ладони. Рендер приблизительный
(без сглаживания и освещения игры), но масштаб, посадка и силуэт видно.

Требует Pillow. Для сервера не нужен.

    python3 tools/render_talisman_hand.py --pack dist/bedrock_pack --out dist/preview
"""

from __future__ import annotations

import argparse
import json
import math
import os

try:
    from PIL import Image, ImageDraw, ImageFont
except ImportError:  # pragma: no cover
    raise SystemExit("Нужен Pillow: pip install pillow")

ANIM = "animation.talisman.thirdperson_main_hand"
# CMD, подпись, слаг модели
ITEMS = [
    (1, "жизни", "life"), (2, "ярости", "fury"), (3, "стража", "guard"),
    (4, "странника", "wanderer"), (5, "танка", "tank"), (6, "воина", "warrior"),
    (7, "убийцы", "assassin"), (8, "берсерка", "berserk"), (9, "охотника", "hunter"),
    (10, "бегуна", "runner"), (11, "демона", "demon"), (12, "героя", "hero"),
]
SKIN = (198, 140, 106, 255)

FACES = {
    "north": lambda x0, y0, z0, x1, y1, z1: [((x0, y1, z0), (0, 0)), ((x1, y1, z0), (1, 0)), ((x1, y0, z0), (1, 1)), ((x0, y0, z0), (0, 1))],
    "south": lambda x0, y0, z0, x1, y1, z1: [((x1, y1, z1), (0, 0)), ((x0, y1, z1), (1, 0)), ((x0, y0, z1), (1, 1)), ((x1, y0, z1), (0, 1))],
    "east":  lambda x0, y0, z0, x1, y1, z1: [((x1, y1, z1), (0, 0)), ((x1, y1, z0), (1, 0)), ((x1, y0, z0), (1, 1)), ((x1, y0, z1), (0, 1))],
    "west":  lambda x0, y0, z0, x1, y1, z1: [((x0, y1, z0), (0, 0)), ((x0, y1, z1), (1, 0)), ((x0, y0, z1), (1, 1)), ((x0, y0, z0), (0, 1))],
    "up":    lambda x0, y0, z0, x1, y1, z1: [((x0, y1, z1), (0, 0)), ((x1, y1, z1), (1, 0)), ((x1, y1, z0), (1, 1)), ((x0, y1, z0), (0, 1))],
    "down":  lambda x0, y0, z0, x1, y1, z1: [((x0, y0, z0), (0, 0)), ((x1, y0, z0), (1, 0)), ((x1, y0, z1), (1, 1)), ((x0, y0, z1), (0, 1))],
}
SHADE = {"north": 1.0, "south": 0.72, "east": 0.85, "west": 0.78, "up": 1.0, "down": 0.62}


def _ident():
    return [[1.0 if i == j else 0.0 for j in range(4)] for i in range(4)]


def _mul(a, b):
    return [[sum(a[i][k] * b[k][j] for k in range(4)) for j in range(4)] for i in range(4)]


def _trans(t):
    m = _ident()
    m[0][3], m[1][3], m[2][3] = t
    return m


def _scale(s):
    m = _ident()
    m[0][0], m[1][1], m[2][2] = s
    return m


def _rot(axis, deg):
    r = math.radians(deg)
    c, s = math.cos(r), math.sin(r)
    m = _ident()
    if axis == "x":
        m[1][1], m[1][2], m[2][1], m[2][2] = c, -s, s, c
    elif axis == "y":
        m[0][0], m[0][2], m[2][0], m[2][2] = c, s, -s, c
    else:
        m[0][0], m[0][1], m[1][0], m[1][1] = c, -s, s, c
    return m


def _apply(m, p):
    x, y, z = p
    return (m[0][0] * x + m[0][1] * y + m[0][2] * z + m[0][3],
            m[1][0] * x + m[1][1] * y + m[1][2] * z + m[1][3],
            m[2][0] * x + m[2][1] * y + m[2][2] * z + m[2][3])


def _bone_matrix(bone, anim_bone):
    pivot = bone.get("pivot", [0, 0, 0])
    rot = anim_bone.get("rotation", [0, 0, 0])
    rot = [0, 0, rot] if isinstance(rot, (int, float)) else list(rot)
    sc = anim_bone.get("scale", [1, 1, 1])
    sc = [sc, sc, sc] if isinstance(sc, (int, float)) else list(sc)
    pos = anim_bone.get("position", [0, 0, 0])
    m = _mul(_trans(pos), _trans(pivot))
    m = _mul(m, _rot("z", rot[2]))
    m = _mul(m, _rot("y", rot[1]))
    m = _mul(m, _rot("x", rot[0]))
    m = _mul(m, _scale(sc))
    return _mul(m, _trans([-pivot[0], -pivot[1], -pivot[2]]))


def _quads(geo, anim, hand=True):
    """Список четырёхугольников: (4 точки в 3D с UV, затенение, материал)."""
    bones = {b["name"]: b for b in geo["bones"]}
    world = {}
    for b in geo["bones"]:
        parent = world.get(b.get("parent"), _ident())
        world[b["name"]] = _mul(parent, _bone_matrix(b, anim.get(b["name"], {})))

    tw, th = geo["description"]["texture_width"], geo["description"]["texture_height"]
    out = []

    def emit(matrix, origin, size, uvmap, material):
        x0, y0, z0 = origin
        x1, y1, z1 = origin[0] + size[0], origin[1] + size[1], origin[2] + size[2]
        for fname, fn in FACES.items():
            uv = uvmap.get(fname) if uvmap else None
            if uv is None and material is None:
                continue
            u, v = (uv["uv"] if uv else (0, 0))
            du, dv = (uv["uv_size"] if uv else (1, 1))
            quad = [(_apply(matrix, corner), (u + a * du, v + bb * dv))
                    for (corner, (a, bb)) in fn(x0, y0, z0, x1, y1, z1)]
            out.append((quad, SHADE[fname], material))

    for b in geo["bones"]:
        for cube in b.get("cubes", []):
            emit(world[b["name"]], cube["origin"], cube["size"], cube["uv"], None)

    if hand:
        # схематичная ладонь: кулак и предплечье, по габаритам черепа из
        # пака Geyser (там фигурка сидит ровно в руке)
        emit(_ident(), (-1.9, 17.6, -6.2), (3.8, 4.0, 4.4), None, SKIN)   # кулак
        emit(_ident(), (-2.7, 19.0, -5.4), (0.8, 1.7, 2.6), None, SKIN)   # большой палец
        emit(_ident(), (-1.9, 11.0, -2.0), (3.8, 6.6, 3.8), None, SKIN)   # предплечье
        for i in range(4):                                               # костяшки
            emit(_ident(), (-1.75 + i * 0.95, 18.6, -6.5), (0.75, 1.6, 0.5), None, SKIN)
    return out, (tw, th)


def render(pack_dir: str, slug: str, ppu: int = 22, yaw: float = -30, pitch: float = 16,
           width_units: float = 9, height_units: float = 8.5, center=(0.0, 24.6, -5.0),
           hand: bool = False):
    geo_all = json.load(open(os.path.join(pack_dir, "models", "entity", "talisman.geo.json"),
                             encoding="utf-8"))["minecraft:geometry"]
    geo = next(g for g in geo_all
               if g["description"]["identifier"] == f"geometry.talisman.{slug}")
    anim_all = json.load(open(os.path.join(pack_dir, "animations", "talisman.json"),
                              encoding="utf-8"))["animations"]
    anim = anim_all[ANIM]["bones"]

    tex_names = {
        "life": "items_talisman_harmony", "fury": "items_talisman_daedalus",
        "guard": "items_talisman_triton", "wanderer": "items_talisman_discord",
        "tank": "items_talisman_echidna", "warrior": "items_talisman_crusher",
        "assassin": "items_talisman_punisher", "berserk": "items_talisman_tyrant",
        "hunter": "items_talisman_edge", "runner": "items_talisman_phoenix",
        "demon": "new_year_talisman_grinch", "hero": "items_talisman_hero",
    }
    tex_path = os.path.join(pack_dir, "textures", "items",
                            f"minecraft_item_funtime_{tex_names[slug]}_3d.png")
    tex = Image.open(tex_path).convert("RGBA")
    px = tex.load()

    quads, (tw, th) = _quads(geo, anim, hand)
    cy, sy = math.cos(math.radians(yaw)), math.sin(math.radians(yaw))
    cp, sp = math.cos(math.radians(pitch)), math.sin(math.radians(pitch))

    def project(p):
        x, y, z = p[0] - center[0], p[1] - center[1], p[2] - center[2]
        rx = x * cy - z * sy
        rz = x * sy + z * cy
        return (rx, y * cp - rz * sp, y * sp + rz * cp)

    W, H = int(width_units * ppu), int(height_units * ppu)
    img = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    pix = img.load()
    zbuf = [[-1e9] * W for _ in range(H)]
    cx, cyy = W / 2, H / 2

    for quad, shade, material in quads:
        pts = []
        for (p, uv) in quad:
            x, y, d = project(p)
            pts.append((cx + x * ppu, cyy - y * ppu, d, uv))

        def put(X, Y, col):
            if 0 <= X < W and 0 <= Y < H:
                pix[X, Y] = (min(255, int(col[0] * shade)), min(255, int(col[1] * shade)),
                             min(255, int(col[2] * shade)), 255)

        for tri in ((0, 1, 2), (0, 2, 3)):
            a, b, c = pts[tri[0]], pts[tri[1]], pts[tri[2]]
            den = (b[1] - c[1]) * (a[0] - c[0]) + (c[0] - b[0]) * (a[1] - c[1])
            if abs(den) < 1e-9:
                continue
            minx = max(0, int(min(a[0], b[0], c[0])))
            maxx = min(W - 1, int(max(a[0], b[0], c[0])) + 1)
            miny = max(0, int(min(a[1], b[1], c[1])))
            maxy = min(H - 1, int(max(a[1], b[1], c[1])) + 1)
            for Y in range(miny, maxy + 1):
                for X in range(minx, maxx + 1):
                    w0 = ((b[1] - c[1]) * (X + .5 - c[0]) + (c[0] - b[0]) * (Y + .5 - c[1])) / den
                    w1 = ((c[1] - a[1]) * (X + .5 - c[0]) + (a[0] - c[0]) * (Y + .5 - c[1])) / den
                    w2 = 1 - w0 - w1
                    if w0 < 0 or w1 < 0 or w2 < 0:
                        continue
                    d = w0 * a[2] + w1 * b[2] + w2 * c[2]
                    if d <= zbuf[Y][X]:
                        continue
                    if material is not None:
                        col = material
                    else:
                        uu = w0 * a[3][0] + w1 * b[3][0] + w2 * c[3][0]
                        vv = w0 * a[3][1] + w1 * b[3][1] + w2 * c[3][1]
                        col = px[int(uu) % tw, int(vv) % th]
                        if col[3] < 16:
                            continue
                    zbuf[Y][X] = d
                    put(X, Y, col)
    return img


def main() -> int:
    parser = argparse.ArgumentParser(description="3D-мокап талисманов в руке")
    parser.add_argument("--pack", default="dist/bedrock_pack")
    parser.add_argument("--out", default="dist/preview")
    args = parser.parse_args()

    try:
        font = ImageFont.truetype("/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf", 14)
    except OSError:
        font = ImageFont.load_default()

    cell_w, cell_h, cols = 224, 246, 4
    rows = (len(ITEMS) + cols - 1) // cols
    sheet = Image.new("RGBA", (cols * cell_w + 24, rows * cell_h + 56), (22, 20, 26, 255))
    draw = ImageDraw.Draw(sheet)
    draw.text((14, 14), "Талисманы в руке — третье лицо, масштаб 1:1 к модели пака", fill=(236, 230, 220, 255), font=font)

    for n, (cmd, label, slug) in enumerate(ITEMS):
        img = render(args.pack, slug)
        cell_x = 12 + (n % cols) * cell_w
        cell_y = 48 + (n // cols) * cell_h
        sheet.alpha_composite(img, (cell_x + (cell_w - img.width) // 2, cell_y + 4))
        text = f"{label} · CMD {cmd}"
        tw = draw.textlength(text, font=font)
        draw.text((cell_x + (cell_w - tw) / 2, cell_y + img.height + 10), text,
                  fill=(198, 192, 184, 255), font=font)

    os.makedirs(args.out, exist_ok=True)
    path = os.path.join(args.out, "talismans-hand-mock.png")
    sheet.save(path)
    print(f"OK: {path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
