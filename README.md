# lmresurs

Bedrock-порт ресурспака и меню **FunTime** для Geyser + Floodgate.

## Что здесь есть

| Путь | Описание |
| --- | --- |
| `funtime.zip` | Исходный Java-ресурспак FunTime (723 файла, pack_format 34, оверлеи 1.21.4–1.21.6+) |
| `funtime/` | Распакованный Java-пак (локально, в git не нужен — генератор умеет читать `.zip`) |
| `tools/build_bedrock_pack.py` | Генератор Bedrock-пака и Geyser mappings из Java-пака |
| `dist/funtime.mcpack` | Готовый Bedrock-ресурспак (135 иконок предметов) |
| `dist/geyser_mappings.json` | Geyser custom item mappings (v2, `legacy` по `custom_model_data`) |
| `dist/deluxemenus_items.yml` | 136 заготовок предметов для DeluxeMenus |
| `dist/CONVERSION_REPORT.md` | Отчёт конвертации |
| `examples/deluxemenus_bedrock_menu.yml` | Пример меню, которое сразу работает на Bedrock |
| `docs/GEYSER_BEDROCK.md` | Установка, ограничения, troubleshooting |

## Быстрый старт

```bash
python3 tools/build_bedrock_pack.py --pack funtime.zip --out dist
```

Затем скопировать в Geyser:

```
dist/funtime.mcpack          -> plugins/Geyser-Spigot/packs/
dist/geyser_mappings.json    -> plugins/Geyser-Spigot/custom_mappings/funtime.json
```

и включить `gameplay.enable-custom-content: true` в `plugins/Geyser-Spigot/config.yml`.

Подробности — в [docs/GEYSER_BEDROCK.md](docs/GEYSER_BEDROCK.md).
