package ru.voidpe.pvp;

import java.lang.reflect.Method;
import java.util.Locale;
import org.bukkit.Keyed;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;

/**
 * Поиск в реестрах Bukkit без привязки к версии API.
 *
 * <p>Сигнатура {@code Registry#get} менялась: на 1.21.x это
 * {@code get(NamespacedKey)}, на Paper 26.x — {@code get(net.kyori.adventure.key.Key)}.
 * Прямой вызов компилируется, но на сервере с другой сигнатурой падает прямо при
 * включении плагина:</p>
 *
 * <pre>
 * NoSuchMethodError: 'java.lang.Object org.bukkit.Registry.get(org.bukkit.NamespacedKey)'
 * </pre>
 *
 * <p>Поэтому сначала пробуем оба варианта {@code get} через рефлексию, а если не
 * вышло — перебираем содержимое реестра: {@code Registry} — это
 * {@code Iterable<? extends Keyed>}, и ключ каждого элемента можно сравнить строкой.</p>
 */
public final class RegistryAccess {

    private RegistryAccess() {
    }

    /** Ищет элемент по первому найденному ключу; ключи без namespace получают minecraft:. */
    @SafeVarargs
    public static <T extends Keyed> T find(Registry<T> registry, String... keys) {
        if (registry == null || keys == null) {
            return null;
        }
        for (String key : keys) {
            String full = normalize(key);
            if (full == null) {
                continue;
            }
            T value = search(registry, full);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static String normalize(String key) {
        if (key == null) {
            return null;
        }
        String trimmed = key.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        return trimmed.indexOf(':') >= 0 ? trimmed : "minecraft:" + trimmed;
    }

    @SuppressWarnings("unchecked")
    private static <T> T search(Registry<T> registry, String full) {
        Object byBukkitKey = invokeGet(registry, NamespacedKey.class,
                NamespacedKey.fromString(full));
        if (byBukkitKey != null) {
            return (T) byBukkitKey;
        }
        Object byAdventureKey = invokeGetAdventureKey(registry, full);
        if (byAdventureKey != null) {
            return (T) byAdventureKey;
        }
        return (T) scan(registry, full);
    }

    private static Object invokeGet(Object registry, Class<?> parameter, Object argument) {
        Object value = invoke(registry, "get", parameter, argument);
        if (value != null) {
            return value;
        }
        return invoke(Registry.class, "get", registry, parameter, argument);
    }

    private static Object invokeGetAdventureKey(Object registry, String full) {
        try {
            Class<?> keyClass = Class.forName("net.kyori.adventure.key.Key");
            Object key = keyClass.getMethod("key", String.class).invoke(null, full);
            Object value = invoke(registry, "get", keyClass, key);
            if (value != null) {
                return value;
            }
            return invoke(Registry.class, "get", registry, keyClass, key);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** Вызов метода по имени; target == null означает «искать в классе-интерфейсе». */
    private static Object invoke(Object target, String name, Class<?> parameter, Object argument) {
        return invoke(target, name, target, parameter, argument);
    }

    private static Object invoke(Object owner, String name, Object target, Class<?> parameter,
                                 Object argument) {
        try {
            Method method = owner.getClass().getMethod(name, parameter);
            try {
                method.setAccessible(true);
            } catch (RuntimeException ignored) {
                // модуль закрыт — метод публичный, вызов всё равно пройдёт
            }
            return method.invoke(target, argument);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Object scan(Object registry, String full) {
        if (!(registry instanceof Iterable<?> entries)) {
            return null;
        }
        String wanted = full.toLowerCase(Locale.ROOT);
        for (Object entry : entries) {
            String key = keyOf(entry);
            if (key != null && key.equalsIgnoreCase(wanted)) {
                return entry;
            }
        }
        return null;
    }

    private static String keyOf(Object entry) {
        if (entry == null) {
            return null;
        }
        for (String getter : new String[]{"getKey", "key"}) {
            try {
                Object key = entry.getClass().getMethod(getter).invoke(entry);
                if (key != null) {
                    return key.toString();
                }
            } catch (Throwable ignored) {
                // у этого объекта ключ называется иначе — пробуем следующий вариант
            }
        }
        return null;
    }
}
