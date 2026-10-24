# Bedrock-порт меню FunTime для Geyser + Floodgate

Готовый результат лежит в `dist/` — ничего компилировать не нужно, только скопировать два файла в Geyser.

## Что собрано

| Файл | Что это |
| --- | --- |
| `dist/funtime.mcpack` | Bedrock-ресурспак: 136 иконок предметов + 12 attachable-моделей талисманов, `manifest.json`, `textures/item_texture.json`, `pack_icon.png` |
| `dist/geyser_mappings.json` | Geyser custom item mappings (`format_version: 2`, определения типа `legacy` по `custom_model_data`) |
| `dist/deluxemenus_items.yml` | 137 готовых заготовок предметов для DeluxeMenus (`material` + `model_data_component`) |
| `dist/VoidTalismans-config.yml` | Конфиг плагина VoidTalismans с `custom-model-data` у всех 12 талисманов — чтобы выданный предмет выглядел как в меню (необязателен, если плагина нет) |
| `dist/deluxemenus/` | **Портированные меню** (14 файлов) — готовы к замене в `plugins/DeluxeMenus/gui_menus/` |
| `dist/deluxemenus/PORT_REPORT.md` | Что именно изменено в каждом меню |
| `dist/preview/` | PNG-превью всех меню так, как их увидит Bedrock-игрок (не нужны на сервере) |
| `dist/CONVERSION_REPORT.md` | Отчёт: что сконвертировано, покрытие, конфликты, пропуски |
| `dist/conversion_report.json` | То же в машинном виде |
| `examples/deluxemenus_bedrock_menu.yml` | Пример меню на 54 слота, которое сразу работает на Bedrock |
| `tools/build_bedrock_pack.py` | Генератор (пересобрать после изменений в паке) |

Покрытие текущего пака:

- **137** пар «Java-предмет + `custom_model_data`»;
- **28** Java-предметов (`paper`, `trial_key`, `totem_of_undying`, `bow`, `potion`, `netherite_*`, `leather_*`, сезонные предметы и т.д.);
- **136** уникальных иконок, **0** потерянных текстур;
- **12** attachable-моделей талисманов (плоская фигурка с золотым торцом в руке);
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

## Талисманы: новые текстуры и 3D-фигурка в руке

Талисманы — единственные предметы, которые игрок постоянно видит **в руке** (левая рука —
основное место ношения), поэтому для них сделано больше, чем для остальных иконок.

**Текстуры.** 11 иконок FunTime доработаны в `tools/talisman_art.py`: по силуэту добавлен
тёплый золотой контур, сверху лёгкий блик, снизу мягкая тень, насыщенность +6 %. Силуэт и
цвета не менялись — это «немного другая» версия тех же фигурок. Плюс появился **12-й
талисман «герой»** (CMD `12`): золотая версия фигурки с короной — раньше у него была иконка
«ярости» (оба стояли на CMD `2`), теперь у всех 12 талисманов своя иконка.

**В руке.** Для каждого талисмана в паке есть `attachable`: плоская фигурка с золотым
торцом, привязанная к руке (правая — `main_hand`, левая — `off_hand`). Геометрия и анимации
повторяют проверенные 3D-сферы VoidPE (те же `pivot`/`rotation`), меняется только толщина
куба и UV, поэтому модель встаёт в руку так же, как сфера, и не улетает из кадра.

```
models/entity/talisman.geo.json              две геометрии: rightitem / leftitem
animations/talisman.json                     first_person / third_person
render_controllers/talisman.render_controllers.json
attachables/<icon_name>.json                 ×12, identifier = bedrock_identifier из mappings
textures/item/<icon_name>.png                ×12, 32×32: лицо, спина, золотые торцы
```

**Что нужно на сервере.** Иконка в меню работает сразу после замены пака. Чтобы талисман
выглядел так же **в инвентаре и в руке после выдачи плагином**, плагин должен ставить тот же
`custom-model-data`:

1. Положить `dist/VoidTalismans-config.yml` в `plugins/VoidTalismans/config.yml`
   (или, если в вашем конфиге есть свои правки, перенести только строки
   `custom-model-data: N` из секций `talismans.<id>`).
2. Полный рестарт сервера (или `/talisman reload`, если плагин это умеет).
3. Проверка: `/talisman give <ник> crusher 1` — в инвентаре фигурка воина,
   в руке объёмная фигурка с золотым ребром.

Соответствие CMD (одинаково в меню и в конфиге плагина):

| CMD | Меню | id плагина | CMD | Меню | id плагина |
| --- | --- | --- | --- | --- | --- |
| 1 | Талисман жизни | `phoenix` | 7 | Талисман убийцы | `cobra` |
| 2 | Талисман ярости | `echidna` | 8 | Талисман берсерка | `punisher` |
| 3 | Талисман стража | `triton` | 9 | Талисман охотника | `vepr` |
| 4 | Талисман странника | `harmony` | 10 | Талисман бегуна | `grani` |
| 5 | Талисман танка | `sources` | 11 | Талисман демона | `satan` |
| 6 | Талисман воина | `crusher` | 12 | Талисман героя | `absolut` |

**Версия пака поднята до `1.1.0`.** Bedrock кеширует паки по `uuid` + `version`, поэтому
после замены файла игроки получат новые текстуры автоматически (без поднятия версии пак бы
не перекачался).

Мокап «как это будет в руке» (приблизительный 2.5D, настоящий вид — только в клиенте):
`dist/preview/talismans-hand-mock.png`, пересобрать — `tools/make_talisman_preview.py`.

## Что физически не переносится (ограничения Bedrock)

| Java-фича | Почему нет на Bedrock | Что сделано |
| --- | --- | --- |
| Фон меню 256×256, подвешенный на один символ шрифта | В Bedrock глиф имеет фиксированную высоту ~16 px и сетку 16×16 в `font/glyph_XX.png` — «экранных» глифов не существует | Фон = ванильный сундук, контент = иконки предметов (по твоему выбору) |
| Сдвиги `offset.*` / `space.*`, пиксельный шрифт | Приватные юникод-кодпоинты и отрицательные ascent в Bedrock не поддерживаются | Не используются, в тексте их быть не должно |
| 3D-модели предметов (Blockbench `.json`) | Bedrock-иконка кастомного предмета — только текстура; объём требует отдельного `attachable` на каждый предмет | Для 12 талисманов attachable сделан (см. раздел про талисманы). Остальные предметы — плоская иконка, как в Java-GUI |
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


---

# Troubleshooting: ошибки DeluxeMenus

## `NumberFormatException: For input string: "44 "` → потом `zip file closed`

Это **одна** проблема, а не две. Цепочка:

1. DeluxeMenus читает меню и падает на разборе `slots`:
   `java.lang.NumberFormatException: For input string: "44 "` (`DeluxeMenusConfig.loadMenuItems`).
2. `onEnable` выбрасывает исключение → плагин **отключается** (`Disabling DeluxeMenus`).
3. Класслоадер отключённого плагина закрывает свой JAR.
4. Любая команда `/menu` после этого падает уже с
   `IllegalStateException: zip file closed` — это следствие, а не причина.

Поэтому «zip file closed» **не значит**, что JAR битый: чинить надо YAML.
Причина `"44 "` — сломанный отступ в списке слотов, из-за которого SnakeYAML
склеивает элементы в одну строку:

```yaml
    slots:
    - 43
    - 44
      - 45          # <- отступ на 2 больше
      - 46
```

YAML видит это как `['43', '44 - 45 - 46 - 47 ...']`, а DeluxeMenus делает `Integer.parseInt`
и падает. Правильно:

```yaml
    slots:
    - 43
    - 44
    - 45
    - 46
```

В этом репозитории такое отлавливается автоматически при каждой сборке
(`normalize_slot_lists` + `validate_strict` + `validate_slots_indent`),
проверка идёт по всем 14 файлам.

Проверить свои файлы можно так (нужен python3 + pyyaml):

```bash
python3 - <<'EOF'
import yaml, glob
for f in sorted(glob.glob('plugins/DeluxeMenus/gui_menus/*.yml')):
    d = yaml.safe_load(open(f, encoding='utf-8'))
    for k, v in d.get('items', {}).items():
        for s in (v.get('slots') or ([v['slot']] if 'slot' in v else [])):
            if not isinstance(s, int):
                print('СЛОМАНО:', f, k, repr(s))
print('проверка завершена')
EOF
```

## Меню не загрузилось: `NumberFormatException` / слоты `44 - 45 - 46`

Признак того, что список `slots:` в YAML склеился в одну строку из-за разного отступа.
В этом репозитории такие файлы отлавливает `tools/port_deluxemenus.py`
(функция `validate_slots_indent`), проверка идёт при каждой сборке.
Проверить свои файлы можно так:

```bash
python3 -c "
import yaml,glob
for f in sorted(glob.glob('dist/deluxemenus/*.yml')):
    d=yaml.safe_load(open(f,encoding='utf-8'))
    for k,v in d['items'].items():
        for s in (v.get('slots') or ([v['slot']] if 'slot' in v else [])):
            assert isinstance(s,int), (f,k,s)
    print('OK', f)
"
```

## Меню открывается, но иконки ванильные

1. `dist/funtime.mcpack` лежит в `plugins/Geyser-Spigot/packs/`.
2. `gameplay.enable-custom-content: true`.
3. `dist/geyser_mappings.json` → `plugins/Geyser-Spigot/custom_mappings/funtime.json`.
4. Сервер перезапущен **после** копирования (mappings читаются на старте).
5. Bedrock-клиент принял пак (Настройки → Хранилище → Пакеты ресурсов).


## Быстрая замена меню (одним архивом)

```
dist/deluxemenus-bedrock.zip      <- распаковать в plugins/DeluxeMenus/gui_menus/ (с заменой)
dist/deluxemenus-SHA256SUMS.txt   <- контрольные суммы для проверки
```

Порядок:

1. Остановить сервер.
2. Распаковать архив в `plugins/DeluxeMenus/gui_menus/` с заменой файлов.
3. Запустить сервер.
4. В консоли должно быть `[DeluxeMenus] Enabling DeluxeMenus v1.14.1-Release` **без**
   `Error occurred while enabling`. Если есть ошибка — файл не тот.

## Гигиена консоли DeluxeMenus 1.14.1

`hide_attributes: true` объявлен устаревшим, портер сам переписывает его в
`item_flags: [HIDE_ATTRIBUTES]`, поэтому предупреждений в логе больше не будет.
