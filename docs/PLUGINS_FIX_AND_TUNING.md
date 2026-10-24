# Плагины VoidPE: разбор падения и настройка под нагрузку

## 1. Откуда спам в логе

```
java.lang.NoSuchMethodError: 'net.kyori.adventure.text.Component
    net.kyori.adventure.text.Component.hoverEvent(
        net.kyori.adventure.text.event.HoverEvent)'
    at VoidAntiCheat.maybeAlert(VoidAntiCheat.java:678)
```

Причина не в античите и не в конфиге: на Paper 26.2 пришла новая **Adventure**,
в которой у `Component` больше нет метода `hoverEvent(HoverEvent)` (hover стал
частью `HoverEvent<?>`, старый метод удалён). Jar `VoidAntiCheat-2.0.4.jar`
собран против старой Adventure, поэтому вызов не находится **в момент
выполнения** — компилятор такое не ловит, а JVM падает на первом же алерте.

Почему «на каждый блок»: `VoidEnchants.expandDrill()` вызывает
`BlockBreakEvent` для каждого блока бура, тот попадает в античит, античит
пытается отправить алерт персоналу — и падает. Один сломанный блок = один
стектрейс в лог.

**Это и была главная проблема производительности**: построение исключения,
его логирование и запись в файл на каждый сломанный блок. Античит при этом
не работал (исключение вылетало до отправки алерта), но сервер платил за него.

## 2. Что сделано

Патч исходников (`plugins/VoidAntiCheat/`):

| Место | Было | Стало |
| --- | --- | --- |
| `maybeAlert` (стр. 678) | `.hoverEvent(HoverEvent.showText(...))` | подсказка выводится второй строкой через `Component.append` — работает на любой версии Adventure |
| `registryLookup` (стр. 217) | `<T> T registryLookup(Registry<T> ...)` | `Registry<?>` — на 26.x у `Registry` появился bound |

Версия поднята до **2.0.5**, сборка идёт в CI (`.github/workflows/build-plugins.yml`)
и кладётся в релиз `voidplugins`.

## 3. Настройки, которые снижают шум и нагрузку

Файл `plugins/configs/VoidAntiCheat-config.yml` — готовый образец для
`plugins/VoidAntiCheat/config.yml`:

```yaml
settings:
  alert-cooldown-ms: 5000   # было 2500 — алерт по игроку+проверке не чаще раза в 5 с
  alert-min-vl: 2.0         # было 1.0 — не шуметь на одиночных подозрениях
  staff-alerts: false       # в 2.0.4 этот путь падал; в 2.0.5 можно включить обратно
```

Остальное в конфиге уже разумно: `enforcement: true`, `auto-punish: false`,
`vl-reset-clean-seconds: 90`, грейсы после телепорта/урона/поршней.

## 4. Чек-лист по остальным плагинам

1. **Убрать дубли jar.** `VoidVersionsExtension-1.5.0.jar` и `-1.5.1.jar`
   рядом — оставить только 1.5.1 (два одинаковых расширения дают двойную
   обработку рукопожатия Geyser).
2. **Один источник талисманов.** `VoidTalismans` и старый `VoidItems`
   не должны одновременно выдавать предметы с одинаковым CMD.
3. **Периоды обновлений.** Все они в конфигах и по умолчанию 40 тиков (2 с):
   `VoidTab.update-interval-ticks`, `VoidPvP.refresh-ticks`,
   `VoidTalismans.refresh-interval-ticks`. На 100+ онлайне имеет смысл 60–80.
4. **VoidWardenCity.** `update-seconds: 10` — поднимать до 20–30, если
   голограммы не нужны часто; скан сундуков — самый дорогой таймер.
5. **Флаги запуска.** Для Paper + Geyser:
   ```
   java -Xms4G -Xmx4G -XX:+UseG1GC -XX:+ParallelRefProcEnabled \
        -XX:MaxGCPauseMillis=200 -XX:+UnlockExperimentalVMOptions \
        -XX:+DisableExplicitGC -XX:+AlwaysPreTouch \
        -XX:G1NewSizePercent=30 -XX:G1MaxNewSizePercent=40 \
        -XX:G1HeapRegionSize=8M -XX:G1ReservePercent=20 \
        -XX:InitiatingHeapOccupancyPercent=15 -jar paper.jar --nogui
   ```
6. **Логи.** После установки 2.0.5 проверить, что в `logs/latest.log`
   больше нет `NoSuchMethodError` и `Could not pass event BlockBreakEvent`.
