# lmresurs

Bedrock-порт ресурспака и меню **FunTime** для Geyser + Floodgate.

## Что здесь есть

| Путь | Описание |
| --- | --- |
| `funtime.zip` | Исходный Java-ресурспак FunTime (723 файла, pack_format 34, оверлеи 1.21.4–1.21.6+) |
| `funtime/` | Распакованный Java-пак (локально, в git не нужен — генератор умеет читать `.zip`) |
| `tools/build_bedrock_pack.py` | Генератор Bedrock-пака и Geyser mappings из Java-пака |
| `tools/port_deluxemenus.py` | Портирование меню DeluxeMenus (оформление, без изменения функционала) |
| `tools/talisman_art.py` | Талисманы: иконки 32×32 и 12 объёмных фигурок в руке (attachable) |
| `tools/render_talisman_hand.py` | Оффлайн-рендер фигурок «в руке» для превью (нужен Pillow) |
| `tools/preview_menu.py` | PNG-превью меню |
| `tools/build_all.sh` | Пересборка пака, mappings и меню одной командой |
| `dist/funtime.mcpack` | Готовый Bedrock-ресурспак (136 иконок + 12 талисманов в руке) |
| `dist/geyser_mappings.json` | Geyser custom item mappings (v2, `legacy` по `custom_model_data`) |
| `dist/deluxemenus_items.yml` | 137 заготовок предметов для DeluxeMenus |
| `dist/VoidTalismans-config.yml` | Конфиг плагина талисманов: `custom-model-data` 1–12 под иконки |
| `dist/deluxemenus/` | Портированные меню DeluxeMenus (14 файлов) под Bedrock |
| `dist/preview/` | PNG-превью меню (как их видит Bedrock) и фигурок в руке |
| `menus/` | Исходные меню DeluxeMenus |
| `dist/CONVERSION_REPORT.md` | Отчёт конвертации |
| `examples/deluxemenus_bedrock_menu.yml` | Пример меню, которое сразу работает на Bedrock |
| `docs/GEYSER_BEDROCK.md` | Установка, ограничения, troubleshooting |

## Быстрый старт

```bash
bash tools/build_all.sh          # пак + mappings + все меню
```

или по отдельности:

```bash
python3 tools/port_deluxemenus.py --src menus --out dist/deluxemenus
python3 tools/build_bedrock_pack.py --pack funtime.zip --out dist --deluxemenus dist/deluxemenus
```

Затем скопировать в Geyser:

```
dist/funtime.mcpack          -> plugins/Geyser-Spigot/packs/
dist/geyser_mappings.json    -> plugins/Geyser-Spigot/custom_mappings/funtime.json
dist/deluxemenus/*.yml       -> plugins/DeluxeMenus/gui_menus/
```

и включить `gameplay.enable-custom-content: true` в `plugins/Geyser-Spigot/config.yml`.

Подробности — в [docs/GEYSER_BEDROCK.md](docs/GEYSER_BEDROCK.md).
