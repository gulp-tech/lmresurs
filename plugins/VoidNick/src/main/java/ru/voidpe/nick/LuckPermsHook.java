package ru.voidpe.nick;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Чтение групп LuckPerms через рефлексию.
 *
 * <p>Зачем рефлексия: LuckPerms — мягкая зависимость. Если его нет, плагин
 * должен работать дальше, а не падать с NoClassDefFoundError. Снимать группы
 * (для отката) обязательно: иначе игрок после «стать новым» вернётся в VIP
 * одной командой restore.</p>
 *
 * <p>Меняем права консольными командами LuckPerms — так надёжнее, чем дергать
 * API из потока сервера; а вот читаем через API, потому что вывод консольных
 * команд прочитать нельзя.</p>
 */
final class LuckPermsHook {

    /** Что было у игрока до сброса. */
    static final class Snapshot {
        final List<String> groups;
        final String primary;

        Snapshot(List<String> groups, String primary) {
            this.groups = groups;
            this.primary = primary;
        }
    }

    private final Logger logger;
    private boolean available;
    private Method getUser;
    private Object userManager;

    LuckPermsHook(Logger logger) {
        this.logger = logger;
        try {
            Class<?> providerClass = Class.forName("net.luckperms.api.LuckPermsProvider");
            Object luckPerms = providerClass.getMethod("get").invoke(null);
            userManager = luckPerms.getClass().getMethod("getUserManager").invoke(luckPerms);
            getUser = userManager.getClass().getMethod("getUser", UUID.class);
            available = true;
        } catch (Throwable missing) {
            available = false;
        }
    }

    boolean available() {
        return available;
    }

    /** Группы игрока или null, если LuckPerms нет / игрок не загружен. */
    Snapshot read(UUID uuid) {
        if (!available) {
            return null;
        }
        try {
            Object user = getUser.invoke(userManager, uuid);
            if (user == null) {
                return null;
            }
            // В LuckPerms 5 getUser(UUID) возвращает CompletableFuture<User>.
            if (user instanceof java.util.concurrent.CompletableFuture<?> future) {
                user = future.get();
            }
            if (user == null) {
                return null;
            }
            Object groupsRaw = user.getClass().getMethod("getParentGroups").invoke(user);
            List<String> groups = new ArrayList<>();
            if (groupsRaw instanceof Collection<?> collection) {
                for (Object group : collection) {
                    groups.add(String.valueOf(group));
                }
            }
            Object primaryRaw = user.getClass().getMethod("getPrimaryGroup").invoke(user);
            return new Snapshot(groups, primaryRaw == null ? "" : String.valueOf(primaryRaw));
        } catch (Throwable failure) {
            logger.warning("Не удалось прочитать группы LuckPerms: " + failure);
            return null;
        }
    }
}
