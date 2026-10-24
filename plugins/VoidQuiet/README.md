# VoidQuiet — тишина в чате

Мини-плагин для VoidPE: убирает сообщения **входа**, **выхода** и **смертей/убийств**.
Работает на уровне событий Bukkit, поэтому не зависит от настроек Essentials и от
порядка загрузки плагинов.

## Что делает

| Сообщение | Как глушится | Ключ конфига |
|---|---|---|
| `X зашёл на сервер` | `PlayerJoinEvent.setJoinMessage(null)` | `join.hide` |
| `X покинул игру` | `PlayerQuitEvent.setQuitMessage(null)` | `quit.hide` |
| `X погиб` / `X убит Y` | `PlayerDeathEvent.setDeathMessage(null)` **+** геймрул `showDeathMessages=false` | `death.hide`, `death.disable-gamerule` |

Геймрул нужен потому, что часть строк о смерти отправляет само ядро, мимо плагинов.
Плагин выставляет его при старте, при загрузке нового мира и после `/voidquiet reload`.

Вместо скрытия можно задать **своё** сообщение — у каждого блока есть `message`
(пустая строка = скрыть совсем):

```yaml
join:
  hide: true
  message: ""                       # скрыть
  # message: '&8» &a{username} &7зашёл на сервер'   # своё
```

Плейсхолдеры: `{player}` — ник со значками и префиксом, `{username}` — чистый ник,
`{online}` — игроков онлайн, `{killer}`, `{weapon}`, `{world}` — для смертей.

## Установка

1. `VoidQuiet-1.0.0.jar` → в `plugins/`
2. Полный перезапуск сервера
3. Настройки: `plugins/VoidQuiet/config.yml`, после правок — `/voidquiet reload`

Совместим с Essentials: даже если там `death-messages: true` и
`hide-join-quit-messages-above: -1`, плагин скроет сообщения (приоритет HIGHEST —
работает после Essentials).

## Команда

```
/voidquiet              — статус (алиас: /vquiet)
/voidquiet reload       — перечитать конфиг и применить геймрул
```

## Права

| Право | По умолчанию | Что даёт |
|---|---|---|
| `voidquiet.use` | всем | просмотр статуса |
| `voidquiet.admin` | оператор | `/voidquiet reload` |

## Исключения

```yaml
worlds:
  disabled: [world_lobby]   # здесь сообщения останутся как есть
```

## Сборка

```bash
cd VoidQuiet
mvn -B -DskipTests package     # → target/VoidQuiet-1.0.0.jar
```
