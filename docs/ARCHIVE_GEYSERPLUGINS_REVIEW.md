# Разбор архива `GeyserPlugins-main.zip`

> Дата разбора: 24.10.2026. Источник: `origin/main` репозитория `gulp-tech/lmresurs`
> (коммит `c84af6f Add files via upload`), т.е. архив лежит в **этом же** репозитории, в `main`.
> Распакован в `geyserplugins/GeyserPlugins-main/` — локально, в git не добавляется
> (`/geyserplugins/` в `.gitignore`: сам zip уже есть в `origin/main`).

---

## 1. Коротко: что это

Это **не сторонний плагин и не набор готовых решений**. Это снапшот второго проекта того же
пользователя — репозиторий `gulp-tech/GeyserPlugins`, сервер **VoidPE** (бывш. FaralMC):
Purpur/Paper 26.2 (Java 25) + Geyser 2.12 + Floodgate, **заход только с Bedrock**
(Java-игроков кикает VoidTab).

| Параметр | Значение |
| --- | --- |
| Файлов | 389 |
| Размер после распаковки | 8,9 МБ (zip — 6,47 МБ) |
| Модулей-плагинов | 17 каталогов (15 Maven-плагинов + 2 Geyser-расширения) |
| Готовых jar в корне | 9 (из них 2 — две версии одного расширения) |
| Готовых ресурспаков | `VoidResourcePack.mcpack` (1.22.0), `VoidSpheres.mcpack` (3.2.0) |
| Конфигов сервера | `configs/` — 42 файла (11 из них — меню DeluxeMenus) |
| Инструментов | `tools/` — 50 файлов |
| Превью | `preview/` — 137 файлов (18 PNG сверху + листы иконок) |
| Документация | `AGENT_HANDOFF.md` 90 КБ + `README.md` 15,7 КБ |

**Самое важное для нас:** в архиве лежит **параллельный порт тех же 14 меню DeluxeMenus**,
сделанный другим агентом в другой сессии. Команды меню совпадают один в один
(`menu`, `donate`, `shop`, `shopitems`, `shoptalismans`, …), предметы — те же
(«Рамка», «Баланс», «Капкан», «Пыль обнаружения», «Око дезориентации», «Лом для спавнеров», «Назад»).
То есть это **конкурирующая реализация нашей задачи**, а не независимый проект.

---

## 2. Карта архива

```
GeyserPlugins-main/
├── 17 каталогов плагинов      исходники (Maven / Gradle для расширений)
├── *.jar (9)                  собранные плагины (тот же сервер)
├── VoidResourcePack.mcpack    глифы E1–E4 + логотип (Bedrock-пак)
├── VoidSpheres.mcpack         4 сферы: 3D-attachable + иконки в атласе
├── configs/                   готовые конфиги сервера + 11 меню DeluxeMenus
├── tools/                     генераторы глифов, превью, локальная сборка без Maven
├── preview/                   PNG-превью (меню, скорборд, баннеры, листы иконок)
├── AGENT_HANDOFF.md           полный журнал работ: блокеры, грабли, состояние сервера
└── вложенные архивы
    ├── Geyser-Spigot.zip      СНИМОК папки Geyser пользователя (19.10.2026)
    ├── HeadDatabase.zip       конфиг + register-geyser.txt (головы)
    ├── Spheres-Geyser-ready.zip   пак сфер 3.0.3 (3 шт.) + mapping, «положить и забыть»
    ├── funtime.zip            тот же Java-пак FunTime, что у нас в корне
    └── «сферы».zip ×2         WastoPE Spheres: Java-пак + mapping по CMD 9001–9007 (7 сфер)
```

Имена двух архивов отображаются как `#U0441#U0444#U0435#U0440#U044b.zip` — это
cp437-испорченная кириллица («сферы»). Оба файла побайтово одинаковые (49 924 Б).

### 2.1. Плагины (каталоги = модули)

Версии в `pom.xml`, `api-version: '26.2'`, Java 25 — то же ядро, что у пользователя.

| Модуль | Версия в архиве | Jar в архиве | Что делает |
| --- | --- | --- | --- |
| VoidTab | 1.16.3 | ✅ | Скорборд, TAB, ник над головой, иконки устройств; Bedrock-only (кик Java) |
| VoidItems | 1.8.6 | ✅ | Кастомные предметы: капкан, пыль, дезориентация, лом спавнеров, зелья |
| VoidAntiCheat | 2.0.4 | ✅ | Античит только для Bedrock/Floodgate, 19 проверок, по умолчанию без наказаний |
| VoidCombat | 1.2.3 | ✅ | Режим боя, кулдауны, боссбар, запрет фейерверков в бою |
| VoidPvP | 1.0.7 | ✅ | Настройка PvP-атрибутов под Bedrock |
| VoidNick | 1.0.2 | ✅ | Смена ника (командой из консоли) |
| VoidQuiet | 1.0.2 | ✅ | Глушит сообщения входа/выхода/смертей |
| VoidVersionsExtension | 1.5.0 **и 1.5.1** | ✅✅ | Geyser-расширение: старые версии Bedrock (1.20+) на новом Geyser |
| VoidTalismans | 1.7.5 | ❌ | Талисманы + сферы (левая рука), `/talisman`, `/sphere` |
| VoidEnchants | 1.2.1 | ❌ | Кастомные чары (Автоплавка, Магнит, Бур) + серверная наковальня |
| VoidCases | 1.4.2 | ❌ | Кейсы + титулы, `/cases`, `/titles` |
| VoidClans | 1.2.0 | ❌ | Кланы, `/clan` |
| VoidDrops | 1.1.0 | ❌ | Монеты с мобов, бусты по привилегиям |
| VoidWardenCity | 1.4.1 | ❌ | Древний город Вардена: лут, вардены, вход за осколки |
| VoidAuthSpawn | 1.2.0 | ❌ | Точка регистрации AuthMe |
| VoidAnvilExtension | 1.5.x (Gradle) | ❌ | Нативная Bedrock-форма наковальни (Cumulus) |
| VoidResourcePack | 1.22.0 | mcpack ✅ | Ресурспак (не плагин): глифы, логотип |

### 2.2. `AGENT_HANDOFF.md` — что в нём полезного

Это рабочий журнал и одновременно «память» проекта. Самые ценные разделы:

- **§2 блокер:** GitHub Actions заблокирован биллингом — джобы не стартуют, поэтому jar'ы
  лежат в корне репозитория (обход). Значит часть jar'ов в архиве — **ручная локальная сборка**.
- **§6.2–6.3.14** — 14 разборов «почему падало»: `IncompatibleClassChangeError` (класс vs
  интерфейс в заглушках), `NoSuchMethodError deserialize`, `Registry#get` (разные сигнатуры
  в версиях), определение устройства в VoidTab, элитры/фейерверки в VoidCombat и т.д.
- **§6.3.13–6.3.14 — самое близкое к нам:** перенос графики FunTime в Bedrock и меню
  DeluxeMenus. Там же собраны грабли глифов: ширина символа в Bedrock = ширина непрозрачных
  пикселей, заголовок сундука = 9 клеток по 16 px (поэтому 144 px, а не 176), `int("E140", 16)`
  и т.п.
- **§7 грабли** — совпадают с нашими выводами: массовые правки YAML скриптами портят файлы,
  PyYAML не парсит flow-стиль, DeluxeMenus не поддерживает `<gradient:...>`.
- **§8 состояние сервера** — список чужих плагинов на сервере (LMAuction, ZNPCsPlus, HeadDatabase,
  AuthMe, DeluxeMenus, …) и параметры Geyser.

---

## 3. Главное открытие: два разных подхода к иконкам

И мы, и авторы архива взяли **один и тот же `funtime.zip`**, но решили задачу по-разному.

| | **Наш подход (lmresurs)** | **Подход архива (GeyserPlugins)** |
| --- | --- | --- |
| Основа | `custom_model_data` → Geyser custom items (`legacy` + `definition`) | кастомные глифы шрифта `font/glyph_E2..E4.png` |
| Иконка в слоте | **настоящий спрайт предмета** (атлас `textures/items` + `item_texture.json`) | `material: PAPER` + глиф `\uE4xx` **в первой строке названия** |
| Заголовок меню | обычный текст (`menu_title: '• Магазин: сферы'`) | **картинка-баннер** из 9 глифов `\uE2xx` (144×16) |
| Где «картинка» реально видна | слот меню, инвентарь, рука | только в **тексте**: заголовок сундука, название предмета, скорборд |
| Что нужно на сервере | `funtime.mcpack` + `custom_mappings/funtime.json` | `VoidResourcePack.mcpack` (глифы) |
| Покрытие | 135 иконок → 136 mappings, 14 меню | 124 иконки + 23 заголовка, 11 меню |

### 3.1. Важный нюанс, который видно только на клиенте

В их меню предметы — `material: PAPER`, а иконка стоит первым символом `display_name`.
Их превью (`preview/menu-funtime-preview.png`) рисует эту иконку **вместо спрайта предмета** —
но в реальном Bedrock в слоте будет **бумага**, а глиф окажется в названии (видно при наведении).
Проверяется это одной строкой в их же `tools/make_funtime_preview.py`: для не-GLASS предметов
рисуется серая плашка слота, поверх — глиф, а материал не учитывается.

Вывод: **для иконок в слотах наш подход визуально честнее и сильнее**. Глифы выигрывают только
там, где картинка не может быть предметом — в **заголовках** и **скорборде**.

---

## 4. Что из архива реально стоит забрать

### 4.1. Сферы — закрывает нашу дыру (высокий приоритет)

Наши меню содержат **5 вхождений** `item_model: voidspheres:sphere1..sphere4`
(`shop_spheres.yml` ×4 и `shop.yml` ×1). На Bedrock это **бумага**: маппинга на эти модели у нас нет.

В архиве — два готовых решения:

| Вариант | Файлы | Покрытие | Как выглядит |
| --- | --- | --- | --- |
| **A (рекомендую)** | `VoidSpheres.mcpack` (корень, v3.2.0) + `configs/Geyser-spheres-mapping.json` | sphere1–**sphere4** | 3D-сфера в руке (attachable + geo-модель) + иконка 73×70 в слоте |
| B | `Spheres-Geyser-ready.zip` (v3.0.3) | sphere1–**3** (sphere4 нет) | то же, но без 4-й сферы |
| C | `«сферы».zip` → `spheres/spheres.json` + `WastoPE Spheres.zip` | sphere1–**7** по CMD 9001–9007 | 3D + иконки 85×85, иной стиль |

Вариант A совпадает с нашими меню **точно**: mapping построен на `model: voidspheres:sphereN`
и ссылается на иконки `sphere1..sphere4` из того же пакета. Цвета тоже сходятся:
sphere1 — фиолетовая (сумерки), sphere2 — оранжево-красная (пламя), sphere3 — тёмно-синяя
(бездна), sphere4 — синяя (пустота).

Установка: `VoidSpheres.mcpack` → `plugins/Geyser-Spigot/packs/`,
`Geyser-spheres-mapping.json` → `plugins/Geyser-Spigot/custom_mappings/`, полный рестарт.
С нашим `funtime.mcpack` не конфликтует: другие UUID, другие ключи атласа
(`sphere1..4` против `item_funtime_*`).

### 4.2. Баннеры-заголовки `E2xx` — единственный способ «картинки» в заголовке

23 полосы 144×16 (`\uE200`–`\uE2CE`) из FunTime: «ГЛАВНОЕ МЕНЮ», «МАГАЗИН СЕРВЕРА»,
«ДОНАТ УСЛУГИ», «КЕЙСЫ», «АУКЦИОН», «ВАРПЫ», «ТЕЛЕПОРТАЦИИ» и др.
Заголовок сундука в Bedrock — это текст, поэтому глифы здесь работают идеально (9 символов = ровно 144 px).

Ограничение: FunTime поставлял только 23 заголовка, и они не покрывают наши
`shop_items / shop_potions / shop_pickaxes / shop_talismans / shop_spheres / shop_enchants /
events / podryvnik / privat_shop`. Если ставить баннеры — либо только на 5 «крупных»
меню (menu, shop, donate, keys, titles), либо дорисовать остальные тем же
`tools/import_funtime_pack.py` (он режет полосы из Java-пака по именам файлов с `title`).

Технически: в наш `funtime.mcpack` добавляется `font/glyph_E2.png` из `VoidResourcePack.mcpack`,
в меню — `menu_title: "\uE287…\uE28F"`. Функционал не меняется, только заголовок.

### 4.3. Мелкое, но полезное

- **`configs/VoidTab-icons-funtime.yml`** — 11 кодов для секции `icons:` в VoidTab
  (`kill`, `rank`, `token`, `time2`, `lock`, `rune`, `clan1..3` …). Без пересборки jar.
  Требует глифов из `VoidResourcePack`.
- **`tools/import_funtime_pack.py`** — режет Java-шрифт FunTime на глиф-страницы
  (иконки 16×16 + заголовки 144×16). Пригодится, если захотим баннеры.
- **`tools/make_funtime_preview.py`** — превью **по готовому YAML** с настоящими глифами:
  сразу видно битый код иконки. Хорошая идея для нашего `tools/preview_menu.py`.
- **`configs/VoidTab-config.yml`, `VoidAntiCheat-config.yml`, `VoidPvP-config.yml`,
  `VoidQuiet-config.yml`** — эталонные конфиги плагинов сервера (не наша задача).
- **`Geyser-Spigot.zip`** — снимок папки Geyser пользователя на 19.10.2026: внутри
  `packs/` только `VoidResourcePack.mcpack`, `custom_mappings/` **пустая**,
  `enable-custom-content: true`, `force-resource-packs: true`.
  Это подтверждает, почему сферы и меню на тот момент выглядели «как в Java».

---

## 5. Чего брать НЕЛЬЗЯ (конфликты)

1. **Их набор меню вместе с нашим.** Команды совпадают: `menu`, `donate`, `shop`, `keys`,
   `titles`, `shopitems`, `shoppotions`, `shoppickaxes`, `shoptalismans`, `shopspheres`,
   `shopenchants`. Два файла с одной `open_command` в `gui_menus/` → конфликт регистрации
   команд и «какое меню откроется» непредсказуемо. Ставить **только один** набор.
2. **Их `DeluxeMenus-donate.yml`** — это **старая** версия, где привилегии покупаются за осколки
   (`Цена: 12 осколков`, `points take`, `click_requirement`). Наш `dist/deluxemenus/donate.yml`
   новее и уже без осколков — их файл не использовать.
3. **Три варианта главного меню** (`DeluxeMenus-menu.yml`, `-menu-funtime.yml`, `-menu-glyph.yml`)
   на одну команду `menu` — в архиве это альтернативы, в `gui_menus/` должна попасть одна.
4. **`VoidVersionsExtension-1.5.0.jar` и `-1.5.1.jar` рядом** — оставлять только 1.5.1.
5. **Их `DeluxeMenus-config.yml`** регистрирует 11 меню; у нас 14 (плюс `events`, `podryvnik`,
   `privat_shop`). Как есть — не подходит.
6. **Два маппинга сфер одновременно** (`spheres.json` по CMD и `Geyser-spheres-mapping.json`
   по `item_model`) — выбрать один, дубли не ставить (это же правило записано в их handoff).
7. **Глиф-иконки в `display_name` как замена спрайту** — см. §3.1: на клиенте будет бумага.

---

## 6. Что не относится к нашей задаче

Плагины (`VoidAntiCheat`, `VoidVersionsExtension`, `VoidCombat`, `VoidPvP`, `VoidItems`,
`VoidWardenCity` и др.) — это **функционал сервера**, а мы по договорённости занимаемся
только меню и графикой. Их jar'ы полезны лишь если пользователь отдельно попросит обновить
плагины; к DeluxeMenus они отношения не имеют. Аналогично `HeadDatabase.zip`,
`AuthMe/LiteBans/LiteKits/…` конфиги в `configs/`.

---

## 7. Итог и варианты следующего шага

Архив полезен, но не как «плагин», а как **второй источник решения нашей задачи**.
Из него имеет смысл взять ровно две вещи: **сферы** (закрывают реальную дыру) и, опционально,
**баннеры-заголовки**. Меню из архива — не брать.

| Вариант | Что делаем | Объём |
| --- | --- | --- |
| **1. Минимум** | Только сферы: положить `VoidSpheres.mcpack` + `Geyser-spheres-mapping.json` в `dist/` и дописать в `docs/GEYSER_BEDROCK.md` раздел установки | 2 файла, 1 раздел доков |
| **2. Средний** | + баннеры: добавить `font/glyph_E2.png` в наш `funtime.mcpack`, перевести заголовки 5 меню на глифы, отдать один архив | правка `build_bedrock_pack.py` + 5 YAML |
| **3. Полный** | + `VoidTab-icons-funtime.yml` в доки, + перенос идеи «превью по YAML» в `tools/preview_menu.py` | средний |

---

### Как я это проверял (воспроизводимо)

```bash
# откуда архив
git show origin/main:GeyserPlugins-main.zip > /tmp/GeyserPlugins-main.zip
unzip -q /tmp/GeyserPlugins-main.zip -d geyserplugins/

# сколько и чего
find geyserplugins/GeyserPlugins-main -type f | wc -l      # 389
for d in geyserplugins/GeyserPlugins-main/*/; do echo "$d $(find "$d" -type f | wc -l)"; done

# версии модулей
grep -m1 -oP '(?<=<version>)[^<]+' geyserplugins/GeyserPlugins-main/*/pom.xml

# наши непокрытые модели
grep -rn "item_model" dist/deluxemenus/*.yml                # 5 вхождений voidspheres

# совпадение команд меню
grep -m1 '^open_command' dist/deluxemenus/*.yml
grep -m1 '^open_command' geyserplugins/GeyserPlugins-main/configs/DeluxeMenus-*.yml

# сферы: содержимое пака и маппинга
unzip -l geyserplugins/GeyserPlugins-main/VoidSpheres.mcpack     # sphere1..4 + item_texture.json
cat geyserplugins/GeyserPlugins-main/configs/Geyser-spheres-mapping.json
```
