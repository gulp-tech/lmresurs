# Плагины VoidPE: сборка под Paper 26.2, исправления и оптимизация

Все 14 плагинов пересобраны под **Paper 26.2 (Java 25)** и оптимизированы.
Сборка автоматическая: `.github/workflows/build-plugins.yml`, результат — в релизе
`voidplugins` (`VoidAntiCheat-2.0.5.jar` … `VoidWardenCity-1.4.2.jar` + `voidplugins-all.zip`).

## 1. Почему плагины вообще пересобирались

Три jar-файла из архива не работали на текущем ядре:

| Плагин | Ошибка | Причина |
| --- | --- | --- |
| VoidAntiCheat 2.0.4 | `NoSuchMethodError: Component.hoverEvent(HoverEvent)` | в новой Adventure метод удалён |
| VoidItems 1.8.6 | не компилируется | `Registry<T>` получил bound |
| VoidPvP 1.0.7 | не компилируется | то же самое |

Первое — это и есть спам в логе. Цепочка: `VoidEnchants` ломает блоки бура →
каждый блок кидает `BlockBreakEvent` → античит пытается отправить алерт персоналу →
падает на `hoverEvent`. Один блок = один стектрейс, и античит при этом не работал.

Исправления: подсказка алерта выводится второй строкой через `Component.append`;
`Registry<?>` в служебных методах поиска.

## 2. Что оптимизировано в коде

| Плагин | Было | Стало |
| --- | --- | --- |
| VoidAntiCheat | `getConfig().getX("a.b.c")` на каждый пакет движения | кэш значений, сброс при `/vac reload` |
| VoidEnchants | `getStringList("drill.allowed-blocks")` внутри тройного цикла (до 18 копий списка на блок) + снимок `TileState` на каждый блок | список один раз на вызов, сравнение по `Set`, `getState(false)` |
| VoidTalismans | полный обход реестра атрибутов (`stripLegacyModifiers`) на каждый refresh × каждого игрока; `Registry.get` на каждый атрибут | чистка один раз за сессию, кэш `Attribute`, эффекты не перевыдаются, пока живут |
| VoidTab | конфиг читался на каждую из 15 строк на каждого игрока; заголовок пересобирался каждый тик | кэш иконок и логотипа, сброс при `/voidtab reload` |
| VoidCombat | `valueOf` цветов боссбара каждый тик; текст пересобирался каждые 0.5 с; `HashSet` безопасных миров на каждый вызов | кэш enum'ов, текст только при смене секунды, кэш миров и регионов WG |
| VoidClans | `saveData()` (запись файла) на каждое начисление опыта; `clanOf` перебирал все кланы | запись раз в 30 с по флагу, индекс игрок→клан |
| VoidWardenCity | `saveChestData()` на каждый сломанный сундук в цикле; имя голограммы отправлялось каждый тик | одна запись на проход, пакет только при смене текста |
| VoidItems | копия `keySet()` 5 раз в секунду даже при пустой карте | ранний выход |

Версии подняты, чтобы отличать от архивных:
`VoidAntiCheat 2.0.5`, `VoidClans 1.2.1`, `VoidCombat 1.2.4`, `VoidEnchants 1.2.2`,
`VoidItems 1.8.7`, `VoidPvP 1.0.8`, `VoidTab 1.16.4`, `VoidTalismans 1.7.6`,
`VoidWardenCity 1.4.2`. Остальные (AuthSpawn, Cases, Drops, Nick, Quiet) не менялись
по коду — только пересобраны.

## 3. Конфиги

В архиве `voidplugins-configs.zip` лежат `Void<Имя>-config.yml` для всех 14 плагинов —
это дефолтные конфиги с уже применёнными настройками под нагрузку:

| Файл | Ключ | Было | Стало |
| --- | --- | --- | --- |
| VoidAntiCheat | `alert-cooldown-ms` | 2500 | 5000 |
| VoidAntiCheat | `alert-min-vl` | 1.0 | 2.0 |
| VoidAntiCheat | `staff-alerts` | true | false |
| VoidTab | `update-interval-ticks` | 40 | 60 |
| VoidTalismans | `settings.refresh-interval-ticks` | 40 | 60 |
| VoidWardenCity | `chests.scan-seconds` | 60 | 120 |

Ставить их не обязательно: jar-ы работают и со старыми конфигами, значения просто
останутся прежними. Менять конфиг можно и вручную — ключи те же.

## 4. Порядок установки

1. Остановить сервер (не `/reload`: у плагинов висят таймеры и кэши игроков).
2. Удалить старые jar-файлы, положить новые из `voidplugins-all.zip` (папка `jars/`).
3. По желанию заменить конфиги из папки `configs/`.
4. Запустить, проверить `logs/latest.log`:
   - нет `NoSuchMethodError` и `Could not pass event`;
   - нет `Не удалось сохранить кланы` каждые пару секунд.

## 5. Что ещё стоит сделать руками

1. **Убрать дубли jar.** `VoidVersionsExtension-1.5.0.jar` и `-1.5.1.jar` рядом —
   оставить только 1.5.1.
2. **Один источник талисманов.** `VoidTalismans` и старый `VoidItems` не должны
   одновременно выдавать предметы с одинаковым `custom-model-data`.
3. **VoidWardenCity.** Если голограммы не нужны часто, поднять
   `hologram.update-seconds` до 20–30 и `warden.check-seconds` до 60.
4. **Флаги запуска Paper** под 8+ ГБ памяти:
   ```
   java -Xms6G -Xmx6G -XX:+UseG1GC -XX:+ParallelRefProcEnabled \
        -XX:MaxGCPauseMillis=200 -XX:+UnlockExperimentalVMOptions \
        -XX:+DisableExplicitGC -XX:+AlwaysPreTouch \
        -XX:G1NewSizePercent=30 -XX:G1MaxNewSizePercent=40 \
        -XX:G1HeapRegionSize=8M -XX:G1ReservePercent=20 \
        -XX:InitiatingHeapOccupancyPercent=15 -jar paper.jar --nogui
   ```
5. **Профилировка.** Если после установки TPS всё ещё проседает, снять
   `/spark profiler start --timeout 60` и посмотреть, какой плагин в топе —
   по этому отчёту можно точечно доработать.
