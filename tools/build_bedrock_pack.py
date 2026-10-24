#!/usr/bin/env python3
"""
Генератор Bedrock-ресурспака и Geyser custom item mappings из Java-пака FunTime.

Что делает:
  1. Читает Java-пак (assets/, overlays) и собирает все пары
     (java_item, custom_model_data) -> модель -> текстура.
     Поддерживаются оба формата:
       - legacy overrides  (pack_format <= 45 / 1.21.3-)      models/item/*.json
       - item definitions  (1.21.4+, overlays)                items/*.json (range_dispatch)
  2. Раскладывает иконки в Bedrock-пак (textures/items/... + item_texture.json).
  3. Генерирует manifest.json / pack_icon.png -> dist/funtime_bedrock.mcpack
  4. Генерирует Geyser mappings (format_version 2, legacy-определения)
     -> dist/geyser_mappings.json
  5. Пишет отчёт dist/CONVERSION_REPORT.md (+ .json) о том, что сконвертировано,
     что пропущено и почему.

Использование:
    python3 tools/build_bedrock_pack.py \
        --pack funtime \
        --out dist \
        --namespace funtime \
        --pack-name "FunTime Bedrock"

Зависимости: только стандартная библиотека Python 3.9+.
"""

from __future__ import annotations

import argparse
import json
import os
import shutil
import sys
import uuid
import zipfile
from collections import defaultdict
from dataclasses import dataclass, field
from typing import Any, Dict, Iterable, List, Optional, Tuple

# --------------------------------------------------------------------------------------
# Утилиты
# --------------------------------------------------------------------------------------

DEFAULT_NS = "minecraft"
UUID_NAMESPACE = uuid.UUID("6f0b8f4e-1c2f-4a4a-9f3e-2d7f8c1b5a90")

TEXTURE_KEY_PRIORITY = ("layer0", "layer1", "texture", "0", "1", "all", "particle", "side")

# CMD 12-го талисмана («герой»): в Java-паке его нет, иконка генерируется
# из talisman_crusher. Значение обязано совпадать с custom-model-data
# в config.yml плагина VoidTalismans.
TALISMAN_HERO_CMD = 12


def log(msg: str) -> None:
    print(msg, flush=True)


def read_json(path: str) -> Optional[dict]:
    try:
        with open(path, "r", encoding="utf-8-sig") as fh:
            return json.load(fh)
    except FileNotFoundError:
        return None
    except json.JSONDecodeError as exc:
        log(f"  ! битый JSON: {path}: {exc}")
        return None


def split_id(value: str) -> Tuple[str, str]:
    """'funtime:font/x.png' -> ('funtime', 'font/x.png'); 'item/paper' -> ('minecraft', 'item/paper')."""
    if ":" in value:
        ns, path = value.split(":", 1)
        return ns, path
    return DEFAULT_NS, value


def sanitize(name: str) -> str:
    out = []
    for ch in name.lower():
        if ch.isalnum():
            out.append(ch)
        elif ch in "_-.":
            out.append(ch)
        else:
            out.append("_")
    res = "".join(out).strip("_")
    while "__" in res:
        res = res.replace("__", "_")
    return res or "icon"


# --------------------------------------------------------------------------------------
# Индекс пака (база + оверлеи)
# --------------------------------------------------------------------------------------


class PackIndex:
    """Позволяет искать файлы пака с учётом оверлеев (оверлей приоритетнее базы)."""

    def __init__(self, pack_root: str) -> None:
        self.root = pack_root
        self.overlays: List[str] = []
        meta = read_json(os.path.join(pack_root, "pack.mcmeta")) or {}
        for entry in (meta.get("overlays") or {}).get("entries", []):
            directory = entry.get("directory")
            if directory and os.path.isdir(os.path.join(pack_root, directory)):
                self.overlays.append(directory)
        # от самого приоритетного к базе
        self.layers: List[str] = [
            os.path.join(pack_root, d) for d in reversed(self.overlays)
        ] + [pack_root]

    def find(self, rel_path: str) -> Optional[str]:
        for layer in self.layers:
            candidate = os.path.join(layer, rel_path)
            if os.path.isfile(candidate):
                return candidate
        return None

    def find_all(self, rel_path: str) -> List[str]:
        found = []
        for layer in self.layers:
            candidate = os.path.join(layer, rel_path)
            if os.path.isfile(candidate):
                found.append(candidate)
        return found

    def model_file(self, model_id: str) -> Optional[str]:
        ns, path = split_id(model_id)
        return self.find(os.path.join("assets", ns, "models", path + ".json"))

    def texture_file(self, texture_id: str) -> Optional[str]:
        ns, path = split_id(texture_id)
        if path.endswith(".png"):
            rel = os.path.join("assets", ns, "textures", path)
        else:
            rel = os.path.join("assets", ns, "textures", path + ".png")
        return self.find(rel)


# --------------------------------------------------------------------------------------
# Разбор моделей
# --------------------------------------------------------------------------------------


@dataclass
class ModelInfo:
    model_id: str
    texture_id: Optional[str]
    handheld: bool
    unresolved: List[str] = field(default_factory=list)


class ModelResolver:
    def __init__(self, index: PackIndex) -> None:
        self.index = index
        self._cache: Dict[str, ModelInfo] = {}

    def resolve(self, model_id: str, _seen: Optional[set] = None) -> ModelInfo:
        if model_id in self._cache:
            return self._cache[model_id]
        seen = _seen or set()
        if model_id in seen:
            return ModelInfo(model_id, None, False, [f"циклический parent: {model_id}"])
        seen.add(model_id)

        data = read_json(self.index.model_file(model_id) or "")
        if data is None:
            # Ванильные паренты (item/handheld и т.п.) в паке не лежат — выводим handheld по имени.
            looks_handheld = "handheld" in model_id.lower()
            info = ModelInfo(model_id, None, looks_handheld, [f"модель не найдена: {model_id}"])
            self._cache[model_id] = info
            return info

        textures: Dict[str, str] = {}
        handheld = False
        unresolved: List[str] = []

        parent = data.get("parent")
        if isinstance(parent, str) and parent:
            parent_info = self.resolve(parent, seen)
            handheld = handheld or parent_info.handheld
            unresolved.extend(parent_info.unresolved)

        if data.get("display"):
            # модели, у которых явно задан handheld-хват, тоже считаем handheld
            third = data["display"].get("thirdperson_righthand") or {}
            if third.get("rotation"):
                handheld = True

        for key, value in (data.get("textures") or {}).items():
            if isinstance(value, str) and not value.startswith("#"):
                textures[key] = value

        # наследованные текстуры подтягиваем из parent отдельно
        if isinstance(parent, str) and parent:
            parent_data = read_json(self.index.model_file(parent) or "") or {}
            for key, value in (parent_data.get("textures") or {}).items():
                if isinstance(value, str) and not value.startswith("#"):
                    textures.setdefault(key, value)

        texture_id = None
        if textures:
            for key in TEXTURE_KEY_PRIORITY:
                if key in textures:
                    texture_id = textures[key]
                    break
            if texture_id is None:
                texture_id = sorted(textures.values())[0]

        info = ModelInfo(model_id, texture_id, handheld, unresolved)
        self._cache[model_id] = info
        return info


# --------------------------------------------------------------------------------------
# Сбор custom_model_data -> модель
# --------------------------------------------------------------------------------------


@dataclass
class CmdEntry:
    java_item: str            # например minecraft:paper
    cmd: int                  # значение custom_model_data (floats[0])
    model: str                # например ui/cases/usual
    source: str               # legacy | definition
    overlay: str              # base | overlay_1_21_6_plus | ...


def java_item_id_from_model_path(path: str) -> Optional[str]:
    """assets/minecraft/models/item/paper.json -> minecraft:paper (только простые пути)."""
    norm = path.replace(os.sep, "/")
    marker = "/models/"
    if marker not in norm:
        return None
    ns_and_path = norm.split(marker, 1)[1]
    rel = ns_and_path[:-5] if ns_and_path.endswith(".json") else ns_and_path
    if not rel.startswith("item/"):
        return None
    ns = norm.split(marker, 1)[0].split("/")[-1]
    return f"{ns}:{rel[len('item/'):]}"


def parse_legacy_overrides(file_path: str, overlay: str) -> Iterable[CmdEntry]:
    data = read_json(file_path) or {}
    item_id = java_item_id_from_model_path(file_path)
    if not item_id:
        return
    for override in data.get("overrides") or []:
        predicate = override.get("predicate") or {}
        if "custom_model_data" not in predicate:
            continue
        model = override.get("model")
        if not isinstance(model, str):
            continue
        try:
            cmd = int(float(predicate["custom_model_data"]))
        except (TypeError, ValueError):
            continue
        yield CmdEntry(item_id, cmd, model, "legacy", overlay)


def parse_range_dispatch(node: Any, overlay: str) -> List[Tuple[int, str]]:
    """Собирает (threshold, model) из range_dispatch по custom_model_data."""
    out: List[Tuple[int, str]] = []
    if not isinstance(node, dict):
        return out
    node_type = node.get("type", "")
    if node_type == "range_dispatch" and node.get("property") == "custom_model_data":
        for entry in node.get("entries") or []:
            threshold = entry.get("threshold")
            model = entry.get("model")
            if isinstance(model, dict):
                model = model.get("model")
            if threshold is None or not isinstance(model, str):
                continue
            try:
                out.append((int(float(threshold)), model))
            except (TypeError, ValueError):
                continue
    return out


def parse_item_definition(file_path: str, overlay: str) -> Iterable[CmdEntry]:
    data = read_json(file_path) or {}
    norm = file_path.replace(os.sep, "/")
    ns = norm.split("/assets/", 1)[-1].split("/", 1)[0]
    rel = norm.split("/items/", 1)[-1]
    if not rel.endswith(".json"):
        return
    item_id = f"{ns}:{rel[:-5]}"
    for cmd, model in parse_range_dispatch(data.get("model"), overlay):
        yield CmdEntry(item_id, cmd, model, "definition", overlay)


def collect_entries(index: PackIndex) -> List[CmdEntry]:
    entries: List[CmdEntry] = []
    for layer in index.layers:
        overlay = os.path.basename(layer) if layer != index.root else "base"
        # legacy
        legacy_dir = os.path.join(layer, "assets")
        for dirpath, _dirnames, filenames in os.walk(legacy_dir):
            if f"{os.sep}models{os.sep}" not in dirpath + os.sep:
                continue
            for name in filenames:
                if name.endswith(".json"):
                    entries.extend(parse_legacy_overrides(os.path.join(dirpath, name), overlay))
        # item definitions (1.21.4+)
        defs_dir = os.path.join(layer, "assets")
        for ns_dir in (
            d for d in os.listdir(defs_dir) if os.path.isdir(os.path.join(defs_dir, d))
        ) if os.path.isdir(defs_dir) else []:
            items_dir = os.path.join(defs_dir, ns_dir, "items")
            if not os.path.isdir(items_dir):
                continue
            for name in sorted(os.listdir(items_dir)):
                if name.endswith(".json"):
                    entries.extend(
                        parse_item_definition(os.path.join(items_dir, name), overlay)
                    )
    return entries


def cross_check(entries: List[CmdEntry]) -> List[dict]:
    """Сверяет legacy-overrides и item definitions (1.21.4+): где CMD расходятся."""
    legacy: Dict[str, Dict[int, str]] = defaultdict(dict)
    modern: Dict[str, Dict[int, str]] = defaultdict(dict)
    for entry in entries:
        bucket = legacy if entry.source == "legacy" else modern
        bucket[entry.java_item].setdefault(entry.cmd, entry.model)
    diffs: List[dict] = []
    for item_id in sorted(set(legacy) | set(modern)):
        l_map = legacy.get(item_id, {})
        m_map = modern.get(item_id, {})
        for cmd in sorted(set(l_map) | set(m_map)):
            l_model = l_map.get(cmd)
            m_model = m_map.get(cmd)
            if l_model == m_model:
                continue
            if l_model and m_model:
                status = "conflict"
            elif l_model:
                status = "only_legacy"
            else:
                status = "only_definition"
            diffs.append(
                {
                    "java_item": item_id,
                    "custom_model_data": cmd,
                    "legacy": l_model,
                    "definition": m_model,
                    "status": status,
                }
            )
    return diffs


def bukkit_material(item_id: str) -> str:
    ns, path = split_id(item_id)
    return path.upper()


def pretty_name(model: str) -> str:
    tail = model.split("/")[-1]
    words = [w for w in tail.replace("-", "_").split("_") if w]
    return " ".join(w.capitalize() for w in words) or tail


def emit_deluxemenus(
    path: str,
    by_key: Dict[Tuple[str, int], "CmdEntry"],
    entry_icon: Dict[Tuple[str, int], str],
) -> int:
    """Пишет YAML-заготовки предметов для DeluxeMenus (без внешних зависимостей)."""
    used_keys: Dict[str, int] = defaultdict(int)
    lines = [
        "# Автоматически сгенерировано tools/build_bedrock_pack.py",
        "# Заготовки предметов для DeluxeMenus. Скопируй нужный блок в конфиг меню.",
        "# material + model_data_component менять не нужно: Geyser сам подменит иконку",
        "# на Bedrock-версию из funtime.mcpack через geyser_mappings.json.",
        "# model_data_component.floats[0] == custom_model_data из geyser_mappings.json.",
        "#",
        "# ВАЖНО (Bedrock): не используй в name/lore символы из private-use области",
        "# (сдвиги/offset-глифы Java-пака) — в Bedrock они отображаются квадратами.",
        "",
        "items:",
    ]
    count = 0
    for (item_id, cmd), entry in sorted(by_key.items(), key=lambda kv: (kv[0][0], kv[0][1])):
        icon = entry_icon[(item_id, cmd)]
        key = icon
        used_keys[key] += 1
        if used_keys[key] > 1:
            key = f"{icon}_{cmd}"
        label = pretty_name(entry.model)
        lines += [
            f"  # {item_id} | custom_model_data: {cmd} | модель: {entry.model}",
            f"  {key}:",
            f"    material: {bukkit_material(item_id)}",
            "    model_data_component:",
            f"      floats: [{cmd}]",
            f"    name: '&f{label}'",
            "    lore:",
            "      - '&7'",
            "",
        ]
        count += 1
    with open(path, "w", encoding="utf-8") as fh:
        fh.write("\n".join(lines) + "\n")
    return count


def check_deluxemenus(directory: str, by_key: Dict[Tuple[str, int], "CmdEntry"]) -> List[dict]:
    """
    Проверка конфигов DeluxeMenus (*.yml) без PyYAML:
    находит material + model_data_component/model_data/custom-model-data/item_model
    и проверяет, покрыты ли такие пары (предмет, CMD) в mappings.
    """
    import re

    material_re = re.compile(r"^\s*-?\s*material\s*:\s*['\"]?([A-Za-z0-9_\-:%]+)")
    cmd_re = re.compile(
        r"^\s*-?\s*(?:custom-model-data|model_data)\s*:\s*['\"]?(-?\d+)"
    )
    floats_re = re.compile(r"floats\s*:\s*\[\s*(-?\d+(?:\.\d+)?)")
    component_re = re.compile(r"^\s*-?\s*model_data_component\s*:\s*$")
    item_model_re = re.compile(r"^\s*-?\s*item_model\s*:\s*['\"]?([^'\"\s]+)")

    results: List[dict] = []

    for root, _dirs, files in os.walk(directory):
        for name in sorted(files):
            if not name.endswith((".yml", ".yaml")):
                continue
            path = os.path.join(root, name)
            current: Optional[dict] = None
            pending_component = False
            with open(path, "r", encoding="utf-8-sig", errors="replace") as fh:
                for lineno, line in enumerate(fh, 1):
                    m = material_re.match(line)
                    if m:
                        if current:
                            results.append(current)
                        current = {
                            "file": os.path.relpath(path, directory),
                            "line": lineno,
                            "material": m.group(1).upper(),
                            "custom_model_data": None,
                            "item_model": None,
                        }
                        pending_component = False
                        continue
                    if current is None:
                        continue
                    if component_re.match(line):
                        pending_component = True
                        continue
                    c = cmd_re.match(line)
                    if c:
                        current["custom_model_data"] = int(float(c.group(1)))
                        pending_component = False
                        continue
                    if pending_component:
                        f = floats_re.search(line)
                        if f:
                            current["custom_model_data"] = int(float(f.group(1)))
                    im = item_model_re.match(line)
                    if im:
                        current["item_model"] = im.group(1)
            if current:
                results.append(current)

    for entry in results:
        item_id = f"minecraft:{entry['material'].lower()}"
        entry["java_item"] = item_id
        if entry["item_model"]:
            entry["status"] = "item-model"
        elif entry["custom_model_data"] is None:
            entry["status"] = "no-cmd"
        else:
            entry["status"] = "ok" if (item_id, entry["custom_model_data"]) in by_key else "missing"
    return results


# --------------------------------------------------------------------------------------
# Иконки
# --------------------------------------------------------------------------------------

def ensure_transparent_png() -> bytes:
    """1x1 полностью прозрачный PNG (для моделей без текстуры, напр. ui/empty)."""
    import io
    import struct
    import zlib

    def chunk(tag: bytes, payload: bytes) -> bytes:
        return (
            struct.pack(">I", len(payload))
            + tag
            + payload
            + struct.pack(">I", zlib.crc32(tag + payload) & 0xFFFFFFFF)
        )

    ihdr = struct.pack(">IIBBBBB", 1, 1, 8, 6, 0, 0, 0)
    raw = b"\x00" + b"\x00\x00\x00\x00"
    return (
        b"\x89PNG\r\n\x1a\n"
        + chunk(b"IHDR", ihdr)
        + chunk(b"IDAT", zlib.compress(raw, 9))
        + chunk(b"IEND", b"")
    )


def validate_output(
    build_dir: str, mappings: dict, texture_data: Dict[str, dict]
) -> List[str]:
    """Проверяет целостность результата перед упаковкой."""
    errors: List[str] = []

    identifiers: List[str] = []
    for java_item, defs in mappings["items"].items():
        if not java_item.startswith("minecraft:"):
            errors.append(f"подозрительный java-предмет: {java_item}")
        cmds = [d["custom_model_data"] for d in defs]
        if len(cmds) != len(set(cmds)):
            errors.append(f"дубли custom_model_data у {java_item}")
        for definition in defs:
            identifiers.append(definition["bedrock_identifier"])
            icon = definition["bedrock_options"]["icon"]
            if icon not in texture_data:
                errors.append(f"иконка {icon} отсутствует в item_texture.json")
                continue
            rel = texture_data[icon]["textures"] + ".png"
            if not os.path.isfile(os.path.join(build_dir, rel)):
                errors.append(f"файл иконки отсутствует: {rel}")
            if definition["bedrock_identifier"].startswith("minecraft:"):
                errors.append("bedrock_identifier не может быть в namespace minecraft")

    duplicates = {i for i in identifiers if identifiers.count(i) > 1}
    if duplicates:
        errors.append(f"дубли bedrock_identifier: {sorted(duplicates)}")

    for icon_name in texture_data:
        if not os.path.isfile(os.path.join(build_dir, "textures", "items", f"{icon_name}.png")):
            errors.append(f"нет PNG для иконки {icon_name}")

    manifest = read_json(os.path.join(build_dir, "manifest.json")) or {}
    for key in ("format_version", "header", "modules"):
        if key not in manifest:
            errors.append(f"в manifest.json нет ключа {key}")
    return errors


# --------------------------------------------------------------------------------------
# Основная сборка
# --------------------------------------------------------------------------------------


def build(args: argparse.Namespace) -> int:
    pack_root = os.path.abspath(args.pack)
    out_root = os.path.abspath(args.out)
    tmp_dir = None
    if os.path.isfile(pack_root) and pack_root.lower().endswith(".zip"):
        import tempfile

        tmp_dir = tempfile.mkdtemp(prefix="java_pack_")
        log(f"Распаковываю {os.path.basename(pack_root)} во временный каталог")
        with zipfile.ZipFile(pack_root) as zf:
            zf.extractall(tmp_dir)
        # пак может лежать в подкаталоге архива
        candidates = [
            os.path.join(tmp_dir, d)
            for d in os.listdir(tmp_dir)
            if os.path.isfile(os.path.join(tmp_dir, d, "pack.mcmeta"))
        ]
        pack_root = candidates[0] if candidates else tmp_dir
    if not os.path.isdir(pack_root):
        log(f"Ошибка: пак не найден: {pack_root}")
        return 2

    index = PackIndex(pack_root)
    log(f"Пак: {pack_root}")
    log(f"Слои: {['base' if p == index.root else os.path.basename(p) for p in index.layers]}")

    resolver = ModelResolver(index)
    entries = collect_entries(index)
    log(f"Найдено определений custom_model_data: {len(entries)}")

    # дедупликация: приоритет у оверлеев (они идут первыми в index.layers)
    by_key: Dict[Tuple[str, int], CmdEntry] = {}
    for entry in entries:
        by_key.setdefault((entry.java_item, entry.cmd), entry)
    log(f"Уникальных пар (предмет, CMD): {len(by_key)}")

    # ---- иконки -------------------------------------------------------------------
    icons: Dict[str, bytes] = {}            # имя иконки -> png
    icon_sources: Dict[str, str] = {}       # имя иконки -> откуда
    entry_icon: Dict[Tuple[str, int], str] = {}
    entry_handheld: Dict[Tuple[str, int], bool] = {}
    missing: List[dict] = []

    for (item_id, cmd), entry in sorted(by_key.items()):
        info = resolver.resolve(entry.model)
        base_name = sanitize(entry.model.replace(":", "_"))
        icon_name = base_name
        if info.texture_id:
            texture_path = index.texture_file(info.texture_id)
            if texture_path:
                icon_name = sanitize(info.texture_id.replace(":", "_").replace("/", "_"))
                if icon_name not in icons:
                    with open(texture_path, "rb") as fh:
                        icons[icon_name] = fh.read()
                    icon_sources[icon_name] = os.path.relpath(texture_path, pack_root)
            else:
                # текстуры нет — используем саму модель как имя и прозрачную заглушку
                missing.append(
                    {
                        "java_item": item_id,
                        "custom_model_data": cmd,
                        "model": entry.model,
                        "reason": f"текстура не найдена: {info.texture_id}",
                    }
                )
                icon_name = base_name
        else:
            icon_name = base_name

        if icon_name not in icons:
            icons[icon_name] = ensure_transparent_png()
            icon_sources.setdefault(icon_name, "<transparent>")
        entry_icon[(item_id, cmd)] = icon_name
        entry_handheld[(item_id, cmd)] = info.handheld

    # ---- талисманы: перекраска + 12-й талисман «герой» -----------------------------
    # В Java-паке 11 иконок талисманов, а в меню их 12 (у «героя» была иконка ярости).
    # Здесь иконки дорабатываются (золотой контур, объём) и появляется отдельный «герой»;
    # CMD 12 должен совпадать с custom-model-data в конфиге плагина VoidTalismans.
    import talisman_art

    restyled = talisman_art.restyle_icons(icons)
    hero_icon: Optional[str] = None
    crusher_key = next(
        (key for key, icon in entry_icon.items() if icon.endswith("talisman_crusher")), None
    )
    if crusher_key is not None:
        hero_icon = "minecraft_item_funtime_items_talisman_hero"
        icons[hero_icon] = talisman_art.make_hero(icons[entry_icon[crusher_key]])
        icon_sources[hero_icon] = "talisman_art: перекраска talisman_crusher"
        hero_key = ("minecraft:totem_of_undying", TALISMAN_HERO_CMD)
        by_key[hero_key] = CmdEntry(
            java_item=hero_key[0],
            cmd=hero_key[1],
            model="funtime/items/talisman_hero",
            source="legacy",
            overlay="base",
        )
        entry_icon[hero_key] = hero_icon
        entry_handheld[hero_key] = True
    log(f"Талисманы: перекрашено {len(restyled)}, герой: {hero_icon or 'нет'}")

    log(f"Иконок: {len(icons)} (уникальных текстур)")

    # ---- структура Bedrock-пака ---------------------------------------------------
    build_dir = os.path.join(out_root, "bedrock_pack")
    if os.path.isdir(build_dir):
        shutil.rmtree(build_dir)
    os.makedirs(os.path.join(build_dir, "textures", "items"), exist_ok=True)

    namespace = args.namespace
    pack_name = args.pack_name
    version = [1, 1, 0]   # поднимать при любом изменении графики

    manifest = {
        "format_version": 2,
        "header": {
            "name": f"{pack_name} (Geyser)",
            "description": f"{pack_name} — Bedrock-иконки меню и предметов для Geyser/Floodgate",
            "uuid": str(uuid.uuid5(UUID_NAMESPACE, f"{pack_name}:header")),
            "version": version,
            "min_engine_version": [1, 21, 0],
        },
        "modules": [
            {
                "type": "resources",
                "uuid": str(uuid.uuid5(UUID_NAMESPACE, f"{pack_name}:resources")),
                "version": version,
            }
        ],
        "metadata": {
            "authors": ["FunTime / lmresurs", "generated by build_bedrock_pack.py"],
            "generated_with": {"build_bedrock_pack": ["1.0.0"]},
        },
    }

    texture_data: Dict[str, dict] = {}
    for icon_name, png in icons.items():
        rel_png = os.path.join("textures", "items", f"{icon_name}.png")
        with open(os.path.join(build_dir, rel_png), "wb") as fh:
            fh.write(png)
        texture_data[icon_name] = {"textures": f"textures/items/{icon_name}"}

    with open(os.path.join(build_dir, "textures", "item_texture.json"), "w", encoding="utf-8") as fh:
        json.dump(
            {
                "resource_pack_name": sanitize(pack_name),
                "texture_name": "atlas.items",
                "texture_data": dict(sorted(texture_data.items())),
            },
            fh,
            ensure_ascii=False,
            indent=2,
        )

    with open(os.path.join(build_dir, "manifest.json"), "w", encoding="utf-8") as fh:
        json.dump(manifest, fh, ensure_ascii=False, indent=2)

    pack_icon = os.path.join(pack_root, "pack.png")
    if os.path.isfile(pack_icon):
        shutil.copyfile(pack_icon, os.path.join(build_dir, "pack_icon.png"))

    # ---- 3D-талисманы в руке (attachable) -----------------------------------------
    talisman_icons = [name for name in icons if talisman_art.is_talisman(name)]
    talisman_3d = talisman_art.build_attachables(build_dir, talisman_icons, icons, namespace)
    log(f"3D-модель в руке: {len(talisman_3d)} талисманов")

    # ---- Geyser mappings ----------------------------------------------------------
    items_map: Dict[str, List[dict]] = defaultdict(list)
    used_ids: set = set()
    for (item_id, cmd), entry in sorted(by_key.items()):
        icon_name = entry_icon[(item_id, cmd)]
        # bedrock_identifier обязан быть уникальным для каждой пары (предмет, CMD):
        # одна и та же иконка может использоваться разными Java-предметами.
        bedrock_id = f"{namespace}:{icon_name}"
        suffix = 1
        while bedrock_id in used_ids:
            suffix += 1
            bedrock_id = f"{namespace}:{icon_name}_{cmd}" if suffix == 2 else f"{namespace}:{icon_name}_{cmd}_{suffix}"
        used_ids.add(bedrock_id)
        definition: Dict[str, Any] = {
            "type": "legacy",
            "custom_model_data": cmd,
            "bedrock_identifier": bedrock_id,
            "bedrock_options": {
                "icon": icon_name,
                "allow_offhand": True,
                "display_handheld": bool(entry_handheld[(item_id, cmd)]),
            },
        }
        items_map[item_id].append(definition)

    mappings = {
        "format_version": 2,
        "items": {k: sorted(v, key=lambda d: d["custom_model_data"]) for k, v in sorted(items_map.items())},
    }

    validation_errors = validate_output(build_dir, mappings, texture_data)
    if validation_errors:
        log("")
        log("! Самопроверка нашла проблемы:")
        for err in validation_errors:
            log(f"  - {err}")
    else:
        log("Самопроверка: OK (идентификаторы уникальны, все иконки на месте)")

    os.makedirs(out_root, exist_ok=True)
    mappings_path = os.path.join(out_root, "geyser_mappings.json")
    with open(mappings_path, "w", encoding="utf-8") as fh:
        json.dump(mappings, fh, ensure_ascii=False, indent=2)

    # ---- упаковка .mcpack ---------------------------------------------------------
    mcpack_path = os.path.join(out_root, f"{sanitize(pack_name)}.mcpack")
    if os.path.isfile(mcpack_path):
        os.remove(mcpack_path)
    with zipfile.ZipFile(mcpack_path, "w", zipfile.ZIP_DEFLATED) as zf:
        for dirpath, _dirnames, filenames in os.walk(build_dir):
            for name in sorted(filenames):
                full = os.path.join(dirpath, name)
                arc = os.path.relpath(full, build_dir)
                zf.write(full, arc)

    # ---- проверка DeluxeMenus ------------------------------------------------------
    dm_checks: List[dict] = []
    if args.deluxemenus:
        dm_dir = os.path.abspath(args.deluxemenus)
        if os.path.isdir(dm_dir):
            dm_checks = check_deluxemenus(dm_dir, by_key)
            missing_dm = [d for d in dm_checks if d["status"] == "missing"]
            model_dm = [d for d in dm_checks if d["status"] == "item-model"]
            log(f"DeluxeMenus: предметов с CMD: "
                f"{len([d for d in dm_checks if d['status'] == 'ok'])} покрыто, "
                f"{len(missing_dm)} не покрыто, {len(model_dm)} через item_model")
            for d in missing_dm:
                log(f"  ! {d['file']}:{d['line']} {d['java_item']} CMD {d['custom_model_data']}")
            for d in model_dm:
                log(f"  ~ {d['file']}:{d['line']} item_model {d['item_model']} (нет в Java-паке)")
        else:
            log(f"! каталог DeluxeMenus не найден: {dm_dir}")

    # ---- отчёт --------------------------------------------------------------------
    discrepancies = cross_check(entries)
    dm_path = os.path.join(out_root, "deluxemenus_items.yml")
    dm_count = emit_deluxemenus(dm_path, by_key, entry_icon)

    report = {
        "pack": pack_root,
        "entries_total": len(entries),
        "entries_unique": len(by_key),
        "icons": len(icons),
        "java_items": len(items_map),
        "missing_textures": missing,
        "validation_errors": validation_errors,
        "deluxemenus_checks": dm_checks,
        "legacy_vs_definition_mismatches": discrepancies,
        "items": {
            java_item: [
                {
                    "custom_model_data": d["custom_model_data"],
                    "bedrock_identifier": d["bedrock_identifier"],
                    "icon": d["bedrock_options"]["icon"],
                    "display_handheld": d["bedrock_options"]["display_handheld"],
                    "source_texture": icon_sources.get(d["bedrock_options"]["icon"], ""),
                }
                for d in defs
            ]
            for java_item, defs in mappings["items"].items()
        },
    }
    with open(os.path.join(out_root, "conversion_report.json"), "w", encoding="utf-8") as fh:
        json.dump(report, fh, ensure_ascii=False, indent=2)

    lines = [
        "# Отчёт конвертации Java → Bedrock",
        "",
        f"- Пар (предмет, custom_model_data): **{len(by_key)}**",
        f"- Уникальных иконок: **{len(icons)}**",
        f"- Java-предметов в mappings: **{len(items_map)}**",
        f"- Проблемных текстур: **{len(missing)}**",
        f"- Ошибок самопроверки: **{len(validation_errors)}**",
        f"- Отличий в покрытии legacy ↔ 1.21.4+: **{len(discrepancies)}** "
        f"(реальных конфликтов: {len([d for d in discrepancies if d['status'] == 'conflict'])})",
        f"- Заготовок DeluxeMenus: **{dm_count}** (`deluxemenus_items.yml`)",
        "",
        "## Предметы",
        "",
        "| Java-предмет | CMD | Bedrock identifier | Иконка | handheld |",
        "| --- | --- | --- | --- | --- |",
    ]
    for java_item, defs in mappings["items"].items():
        for d in defs:
            lines.append(
                f"| `{java_item}` | {d['custom_model_data']} | `{d['bedrock_identifier']}` "
                f"| `{d['bedrock_options']['icon']}` | "
                f"{'да' if d['bedrock_options']['display_handheld'] else '—'} |"
            )
    if dm_checks:
        dm_missing = [d for d in dm_checks if d["status"] == "missing"]
        lines += [
            "",
            "## Проверка DeluxeMenus",
            "",
            f"Предметов с `model_data_component`: "
            f"**{len([d for d in dm_checks if d['status'] in ('ok', 'missing')])}**, "
            f"покрыто mappings: **{len([d for d in dm_checks if d['status'] == 'ok'])}**, "
            f"не покрыто: **{len(dm_missing)}**",
            "",
            "| Меню | Строка | Предмет | CMD | Статус |",
            "| --- | --- | --- | --- | --- |",
        ]
        for d in dm_checks:
            if d["status"] in ("no-cmd",):
                continue
            lines.append(
                f"| `{d['file']}` | {d['line']} | `{d['java_item']}` | "
                f"{d['custom_model_data'] if d['custom_model_data'] is not None else '—'} | {d['status']} |"
            )
        for d in dm_missing:
            lines.append(
                f"- `{d['file']}:{d['line']}` — `{d['java_item']}` CMD {d['custom_model_data']} "
                f"(нет такого CMD в Java-паке)"
            )
    if missing:
        lines += ["", "## Проблемы", ""]
        for m in missing:
            lines.append(
                f"- `{m['java_item']}` CMD {m['custom_model_data']} → `{m['model']}`: {m['reason']}"
            )
    conflicts = [d for d in discrepancies if d["status"] == "conflict"]
    uncovered = [d for d in discrepancies if d["status"] != "conflict"]
    if conflicts:
        lines += ["", "## Конфликты legacy ↔ 1.21.4+ (разные модели на один CMD)", ""]
        for d in conflicts:
            lines.append(
                f"- `{d['java_item']}` CMD {d['custom_model_data']}: "
                f"legacy = `{d['legacy']}`, definition = `{d['definition']}`"
            )
    if uncovered:
        lines += [
            "",
            "## Покрытие оверлеев",
            "",
            "CMD, который есть только в одном формате (нормально: оверлеи патчат не все предметы).",
            "",
        ]
        for d in uncovered:
            lines.append(
                f"- `{d['java_item']}` CMD {d['custom_model_data']}: {d['status']} "
                f"(legacy = `{d['legacy']}`, definition = `{d['definition']}`)"
            )
    with open(os.path.join(out_root, "CONVERSION_REPORT.md"), "w", encoding="utf-8") as fh:
        fh.write("\n".join(lines) + "\n")

    log("")
    log(f"Готово:")
    log(f"  {mcpack_path}  ({os.path.getsize(mcpack_path)} байт)")
    log(f"  {mappings_path}  ({len(items_map)} предметов)")
    log(f"  {os.path.join(out_root, 'CONVERSION_REPORT.md')}")
    if missing:
        log(f"  ! проблемных текстур: {len(missing)} (см. отчёт)")
    return 0


def main(argv: Optional[List[str]] = None) -> int:
    parser = argparse.ArgumentParser(description="Java → Bedrock конвертер для Geyser (FunTime)")
    parser.add_argument("--pack", default="funtime", help="путь к распакованному Java-паку")
    parser.add_argument("--out", default="dist", help="куда писать результат")
    parser.add_argument("--namespace", default="funtime", help="namespace bedrock_identifier")
    parser.add_argument("--pack-name", default="FunTime", help="имя пака")
    parser.add_argument(
        "--deluxemenus",
        default=None,
        help="каталог с конфигами DeluxeMenus (*.yml) для проверки покрытия CMD",
    )
    return build(parser.parse_args(argv))


if __name__ == "__main__":
    sys.exit(main())
