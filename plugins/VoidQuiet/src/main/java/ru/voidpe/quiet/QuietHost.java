package ru.voidpe.quiet;

import java.util.List;

/**
 * То, что нужно обработчику событий от плагина. Отдельный интерфейс —
 * чтобы логику скрытия сообщений можно было проверять тестами без сервера.
 */
public interface QuietHost {

    /** Настройки на момент последней загрузки config.yml. */
    VoidQuiet.Settings settings();

    /** Выключить ванильные сообщения о смерти во всех мирах; вернуть список миров. */
    List<String> applyDeathGamerule();
}
