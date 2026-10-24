# lmresurs

Bedrock-порт ресурспака и меню **FunTime** для Geyser + Floodgate.

## Что здесь есть

| Путь | Описание |
| --- | --- |
| `funtime.zip` | Исходный Java-ресурспак FunTime (723 файла, pack_format 34, оверлеи 1.21.4–1.21.6+) |
| `funtime/` | Распакованный Java-пак (локально, в git не нужен — генератор умеет читать `.zip`) |
| `tools/build_bedrock_pack.py` | Генератор Bedrock-пака и Geyser mappings из Java-пака |
| `tools/port_deluxemenus.py` | Портирование меню DeluxeMenus (оформление, без изменения функционала) |
| `tools/talisman_art.py` | Талисманы: иконки 64×64 и 12 объёмных фигурок в руке (attachable) |
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

## Плагины VoidPE: сборка, исправления, оптимизация

Все 14 плагинов пересобраны под Paper 26.2 (Java 25) и оптимизированы.
Релиз: **[voidplugins](https://github.com/gulp-tech/lmresurs/releases/tag/voidplugins)**

| Файл | Версия | Что изменилось |
| --- | --- | --- |
| [voidplugins-all.zip](https://github.com/gulp-tech/lmresurs/releases/download/voidplugins/voidplugins-all.zip) | — | все 14 jar + все конфиги одним архивом |
| [voidplugins-configs.zip](https://github.com/gulp-tech/lmresurs/releases/download/voidplugins/voidplugins-configs.zip) | — | конфиги под нагрузку (`Void<Имя>-config.yml`) |
| [VoidAntiCheat-2.0.5.jar](https://github.com/gulp-tech/lmresurs/releases/download/voidplugins/VoidAntiCheat-2.0.5.jar) | 2.0.5 | фикс `NoSuchMethodError: hoverEvent` (спам в логе), кэш конфига |
| [VoidEnchants-1.2.2.jar](https://github.com/gulp-tech/lmresurs/releases/download/voidplugins/VoidEnchants-1.2.2.jar) | 1.2.2 | бур: конфиг один раз на вызов, без снимков блоков |
| [VoidTalismans-1.7.6.jar](https://github.com/gulp-tech/lmresurs/releases/download/voidplugins/VoidTalismans-1.7.6.jar) | 1.7.6 | чистка атрибутов раз за сессию, кэш атрибутов |
| [VoidTab-1.16.5.jar](https://github.com/gulp-tech/lmresurs/releases/download/voidplugins/VoidTab-1.16.5.jar) | 1.16.5 | кэш иконок, ник из VoidNick в табе и над головой |
| [VoidCombat-1.2.4.jar](https://github.com/gulp-tech/lmresurs/releases/download/voidplugins/VoidCombat-1.2.4.jar) | 1.2.4 | кэш боссбара, миров и регионов WG |
| [VoidClans-1.2.1.jar](https://github.com/gulp-tech/lmresurs/releases/download/voidplugins/VoidClans-1.2.1.jar) | 1.2.1 | запись раз в 30 с вместо каждого блока, индекс игрок→клан |
| [VoidWardenCity-1.4.2.jar](https://github.com/gulp-tech/lmresurs/releases/download/voidplugins/VoidWardenCity-1.4.2.jar) | 1.4.2 | без спама голограммами, одна запись на скан |
| [VoidItems-1.8.7.jar](https://github.com/gulp-tech/lmresurs/releases/download/voidplugins/VoidItems-1.8.7.jar) | 1.8.7 | фикс сборки под 26.2 + таймер без пустых копий |
| [VoidPvP-1.0.8.jar](https://github.com/gulp-tech/lmresurs/releases/download/voidplugins/VoidPvP-1.0.8.jar) | 1.0.8 | фикс сборки под 26.2 |
| [VoidNick-1.1.0.jar](https://github.com/gulp-tech/lmresurs/releases/download/voidplugins/VoidNick-1.1.0.jar) | 1.1.0 | смена ника без выхода из игры, поиск Floodgate-игрока |

Остальные (`VoidAuthSpawn 1.2.0`, `VoidCases 1.4.2`, `VoidDrops 1.1.0`,
`VoidNick 1.1.0`, `VoidQuiet 1.0.2`) пересобраны без изменений по коду —
файлы лежат в `voidplugins-all.zip` и в релизе отдельными jar.

Исходники — в `plugins/`, сборка — GitHub Actions
(`.github/workflows/build-plugins.yml`, матрица на 14 модулей).
Подробности и чек-лист: `docs/PLUGINS_FIX_AND_TUNING.md`.

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
