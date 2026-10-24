# Bedrock-порт меню FunTime для Geyser + Floodgate

Готовый результат лежит в `dist/` — ничего компилировать не нужно, только скопировать два файла в Geyser.

## Что собрано

| Файл | Что это |
| --- | --- |
| `dist/funtime.mcpack` | Bedrock-ресурспак: 135 иконок предметов, `manifest.json`, `textures/item_texture.json`, `pack_icon.png` |
| `dist/geyser_mappings.json` | Geyser custom item mappings (`format_version: 2`, определения типа `legacy` по `custom_model_data`) |
| `dist/deluxemenus_items.yml` | 136 готовых заготовок предметов для DeluxeMenus (`material` + `model_data_component`) |
| `dist/deluxemenus/` | **Портированные меню** (14 файлов) — готовы к замене в `plugins/DeluxeMenus/gui_menus/` |
| `dist/deluxemenus/PORT_REPORT.md` | Что именно изменено в каждом меню |
| `dist/preview/` | PNG-превью всех меню так, как их увидит Bedrock-игрок (не нужны на сервере) |
| `dist/CONVERSION_REPORT.md` | Отчёт: что сконвертировано, покрытие, конфликты, пропуски |
| `dist/conversion_report.json` | То же в машинном виде |
| `examples/deluxemenus_bedrock_menu.yml` | Пример меню на 54 слота, которое сразу работает на Bedrock |
| `tools/build_bedrock_pack.py` | Генератор (пересобрать после изменений в паке) |

Покрытие текущего пака:

- **136** пар «Java-предмет + `custom_model_data`»;
- **28** Java-предметов (`paper`, `trial_key`, `totem_of_undying`, `bow`, `potion`, `netherite_*`, `leather_*`, сезонные предметы и т.д.);
- **135** уникальных иконок, **0** потерянных текстур;
- самопроверка: `bedrock_identifier` уникальны, все иконки на месте, `manifest.json` валиден.

## Установка (Paper + Geyser-Spigot + Floodgate)

1. **Включить кастомный контент** в `plugins/Geyser-Spigot/config.yml`:

   ```yaml
   gameplay:
     enable-custom-content: true
   ```

2. **Ресурспак** — скопировать `dist/funtime.mcpack` в:

   ```
   plugins/Geyser-Spigot/packs/funtime.mcpack
   ```

3. **Mappings** — скопировать `dist/geyser_mappings.json` в:

   ```
   plugins/Geyser-Spigot/custom_mappings/funtime.json
   ```

4. Перезапустить сервер (mappings читаются на старте Geyser).

5. Зайти с Bedrock-клиента и принять пак — после этого предметы в меню будут с нужными иконками.

Floodgate отдельно настраивать не нужно: mappings и пак работают для всех Bedrock-подключений, Java-игроки получают обычный Java-пак из `server.properties` и ничего не замечают.

Требование по версии: Geyser **2.9.3+** (build 1062+) для формата mappings v2. Более старые сборки понимают только v1 — обнови Geyser.

## Как это работает

- DeluxeMenus на Java-стороне отдаёт обычные предметы: `material: PAPER` + `custom-model-data: 2`.
- Geyser видит `custom_model_data` и подменяет предмет на Bedrock-иконку из пака (`textures/items/<icon>.png`) по mappings.
- Контейнер остаётся ванильным сундуком Bedrock: клики по слотам, `left_click_commands`/`right_click_commands` DeluxeMenus работают как обычно.

## DeluxeMenus: что важно для Bedrock

- **Размер меню.** Bedrock рисует только 27 (3 ряда) и 54 (6 рядов) слота. Меню `size: 27` и `size: 54` выглядят идеально; `9/18/36/45` покажут пустые слоты. Для Bedrock-игроков лучше держать 27 или 54.
- **Никаких Java-глифов в тексте.** Символы из private-use области (`offset.*`, `space.*`, `pixel_split` из `lang/en_us.json`) и префиксы `funtime:font/*` в Bedrock отображаются квадратами. Названия и лор пиши обычным текстом + цветовые коды `&`.
- **`custom-model-data` — целое число**, ровно как в `dist/deluxemenus_items.yml`. Это единственный ключ, который Geyser сопоставляет.
- **CMD 1 у PAPER — прозрачная заглушка** (`ui/empty`): удобно для фона/рамок меню.
- **Иконки плоские**, как и в Java-GUI: это 2D-спрайты 16×16/32×32, в слоте выглядят идентично.
- `display_handheld` выставлен автоматически для моделей с `item/handheld` — в руке такие предметы держатся как инструменты.

## Что физически не переносится (ограничения Bedrock)

| Java-фича | Почему нет на Bedrock | Что сделано |
| --- | --- | --- |
| Фон меню 256×256, подвешенный на один символ шрифта | В Bedrock глиф имеет фиксированную высоту ~16 px и сетку 16×16 в `font/glyph_XX.png` — «экранных» глифов не существует | Фон = ванильный сундук, контент = иконки предметов (по твоему выбору) |
| Сдвиги `offset.*` / `space.*`, пиксельный шрифт | Приватные юникод-кодпоинты и отрицательные ascent в Bedrock не поддерживаются | Не используются, в тексте их быть не должно |
| 3D-модели предметов (Blockbench `.json`) | Bedrock-иконка кастомного предмета — только текстура (3D требует attachables/geometry) | В инвентаре неотличимо от Java, в руке/на земле будет плоская иконка |
| `oversized_in_gui` (иконка больше слота) | Bedrock не масштабирует иконку сверх слота | — |
| Внешний вид надетой брони (наборы `grinch`, `moon`) | Нужны Bedrock-attachables + `entity/equipment` в bedrock-формате | Иконка в инвентаре есть; вид на теле — отдельная задача |
| Анимированные текстуры, OptiFine emissive, пластинка `creator.ogg` | Другая система в Bedrock | Не относятся к меню, не переносились |

## Обновление и пересборка

Когда поменяется Java-пак или появятся конфиги меню:

```bash
python3 tools/build_bedrock_pack.py \
    --pack funtime.zip \
    --out dist \
    --deluxemenus /path/to/plugins/DeluxeMenus/gui_menus
```

- `--pack` принимает и каталог, и `.zip` (оверлеи `overlay_*` учитываются автоматически, приоритет — у старшего).
- Генератор сам находит все `custom_model_data` в двух форматах: legacy `overrides` и новые `items/*.json` (`range_dispatch`).
- `--deluxemenus <каталог>` — сверяет `material` + `custom-model-data` из твоих меню с mappings и пишет в отчёт, какие CMD не покрыты (то есть таких моделей нет в паке).
- После пересборки скопируй `dist/funtime.mcpack` и `dist/geyser_mappings.json` в Geyser и перезапусти сервер.

## Если иконки не появились

1. `gameplay.enable-custom-content` точно `true` (и сервер перезапущен).
2. Файл лежит в `custom_mappings/`, расширение `.json`, содержимое начинается с `{"format_version": 2`.
3. В логе Geyser при старте нет ошибок про `custom_mappings` / `geyser_mappings`.
4. Пак принят клиентом: в Bedrock «Настройки → Хранилище → Пакеты ресурсов» виден «FunTime (Geyser)».
5. Bedrock кэширует паки: перезайти на сервер (иногда нужно удалить кэш паков в клиенте).
6. Конфликт: если другой пак на сервере тоже маппит `paper` с теми же CMD — Geyser возьмёт одно из определений, проверь `dist/CONVERSION_REPORT.md` на конфликты.
7. Предметы показываются как обычная бумага → mappings не загрузились, смотри консоль Geyser.


---

# Меню DeluxeMenus: что сделано

Исходники лежат в `menus/`, результат — в `dist/deluxemenus/`. Портированы все 14 меню:
`menu`, `shop`, `shop_items`, `shop_potions`, `shop_pickaxes`, `shop_talismans`, `shop_spheres`,
`shop_enchants`, `keys`, `donate`, `titles`, `events`, `podryvnik`, `privat_shop`.

**Функционал не тронут**: `open_command`, `register_command`, `click_commands`,
`click_requirement`, `requirements`, `deny_commands`, плейсхолдеры (`%playerpoints_points%`,
`%vault_eco_balance_formatted%`, `%statistic_*%`), слоты предметов и суммы — байт в байт как в исходнике.

Изменено только оформление:

| Что | Было | Стало | Зачем |
| --- | --- | --- | --- |
| Иконки | ванильные `AMETHYST_SHARD`, `DIAMOND`, `TOTEM_OF_UNDYING`… | `material` + `model_data_component.floats: [N]` из FunTime-пака | Геизер подменяет текстуру на Bedrock-иконку |
| 22 иконки | — | кейсы, ключи, талисманы, зелья, капкан, кнопки | см. `dist/deluxemenus/PORT_REPORT.md` |
| Hex-цвета | `&#00F15E`, `&#54C8FF`, `&x&R&R&G&G&B&B` | ближайший из 16 ванильных (`&a`, `&b`, `&c`…) | Bedrock **не поддерживает hex** — иначе текст был бы белым |
| Градиенты | 30–50 hex-кодов на слово | один цвет на слово | после схлопывания оставались повторные коды |
| `size: 45` | 5 рядов | `size: 54` + добор рамки в 6-й ряд | Bedrock рисует только 3 и 6 рядов, 5 рядов = пустые слоты |
| `menu.yml` | 6 предметов в пустом меню | добавлена рамка по периметру | чтобы меню не выглядело пустым |
| `head-%player_name%` | кастомная голова | `PAPER` + иконка «инфо» (CMD 22) | кастомные головы в Bedrock не рисуются |

## Установка меню

1. Скопировать `dist/deluxemenus/*.yml` в `plugins/DeluxeMenus/gui_menus/` (заменив старые).
2. Нужен **DeluxeMenus 1.14+** на Paper 1.21.4+ — только там есть `model_data_component`
   (старый `custom-model-data` в 1.21.4+ не работает).
3. Ресурспак и mappings из `dist/` уже должны быть установлены (см. раздел выше).
4. `/dm reload` и проверка с Bedrock-клиента.

## Что останется ванильным

| Место | Почему |
| --- | --- |
| `shop_spheres.yml`, `shop.yml` — сферы | в них `item_model: voidspheres:sphereN` из другого пака (VoidSpheres). Модели нет в FunTime-паке, Geyser её не знает → на Bedrock будет обычная бумага. Нужен отдельный Bedrock-пак для этого плагина |
| Слоты с `head-%player_name%` | кастомные головы в Bedrock не отображаются (ограничение Geyser) |
| Иконки, которых нет в FunTime-паке (сферы, часть кнопок) | в паке 135 иконок; для остального оставлен ванильный материал |

## Превью

`dist/preview/*.png` — рендер меню с реальными Bedrock-иконками (сетка 9×N как в игре).
Пересобрать:

```bash
python3 tools/preview_menu.py --menus dist/deluxemenus --out dist/preview
```

Это только для проверки глазами: на сервере эти PNG не нужны.

## Пересборка всего

```bash
bash tools/build_all.sh
```

Скрипт делает: порт меню (`port_deluxemenus.py`) → сборку пака и mappings с проверкой
покрытия CMD (`build_bedrock_pack.py --deluxemenus dist/deluxemenus`).
Проверка сейчас: **23** предмета с `model_data_component` покрыты mappings, **0** не покрыто,
**5** используют `item_model` из чужого пака (сферы — см. выше).
