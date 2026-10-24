#!/usr/bin/env bash
# Полная пересборка: Bedrock-пак + mappings + порт меню DeluxeMenus.
set -euo pipefail
cd "$(dirname "$0")/.."

echo "== 1/3 порт меню DeluxeMenus =="
python3 tools/port_deluxemenus.py --src menus --out dist/deluxemenus

echo
echo "== 2/3 Bedrock-пак + Geyser mappings + проверка меню =="
python3 tools/build_bedrock_pack.py --pack funtime.zip --out dist --deluxemenus dist/deluxemenus

echo
echo "== 3/3 готово =="
ls -la dist/funtime.mcpack dist/geyser_mappings.json
echo "Меню: $(ls dist/deluxemenus/*.yml | wc -l) файлов в dist/deluxemenus/"
