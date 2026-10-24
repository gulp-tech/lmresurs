package ru.voidpe.pvp;

import org.bukkit.entity.Player;

/**
 * То, что нужно обработчику ударов от плагина: настройки и проверка права.
 * Отдельный интерфейс — чтобы логику критов можно было проверять тестами
 * без запуска сервера.
 */
public interface PvPConfig {

    /** Настройки на момент последней загрузки config.yml. */
    VoidPvP.Settings settings();

    /** Разрешены ли fast crits этому игроку (права из конфига). */
    boolean allowed(Player player);
}
