package ru.voidpe.items;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.List;
import java.util.logging.Logger;

/**
 * Мягкая привязка к WorldGuard через рефлексию: плагин работает и без WG
 * (тогда запретные регионы просто не действуют, работают только запретные миры).
 */
public final class WGHook {

    private final Logger log;
    private Object regionContainer;   // RegionContainer
    private Method containerGet;      // get(WorldEdit World) -> RegionManager
    private Method adaptWorld;        // BukkitAdapter.adapt(World)
    private Method adaptLocation;     // BukkitAdapter.adapt(Location)
    private Method vectorAt;          // BlockVector3.at(int,int,int)
    private boolean available;

    public WGHook(Logger log) {
        this.log = log;
        init();
    }

    private void init() {
        Plugin wg = Bukkit.getPluginManager().getPlugin("WorldGuard");
        if (wg == null) {
            log.warning("WorldGuard не найден: запретные регионы работать не будут (только запретные миры).");
            return;
        }
        try {
            ClassLoader cl = wg.getClass().getClassLoader();
            Class<?> wgClass = Class.forName("com.sk89q.worldguard.WorldGuard", true, cl);
            Object instance = wgClass.getMethod("getInstance").invoke(null);
            Object platform = wgClass.getMethod("getPlatform").invoke(instance);
            regionContainer = platform.getClass().getMethod("getRegionContainer").invoke(platform);
            Class<?> adapter = Class.forName("com.sk89q.worldedit.bukkit.BukkitAdapter", true, cl);
            Class<?> weWorld = Class.forName("com.sk89q.worldedit.world.World", true, cl);
            adaptWorld = adapter.getMethod("adapt", World.class);
            adaptLocation = adapter.getMethod("adapt", Location.class);
            containerGet = regionContainer.getClass().getMethod("get", weWorld);
            Class<?> bv3 = Class.forName("com.sk89q.worldedit.math.BlockVector3", true, cl);
            vectorAt = bv3.getMethod("at", int.class, int.class, int.class);
            available = true;
            log.info("WorldGuard подключён (запретные регионы активны)");
        } catch (Throwable t) {
            log.warning("WorldGuard hook не удался: " + t.getMessage());
            available = false;
        }
    }

    public boolean available() {
        return available;
    }

    /** Игрок находится в одном из запретных регионов WorldGuard (по именам из конфига). */
    public boolean inBannedRegion(Location loc, List<String> regionNames) {
        return inRegion(loc, regionNames);
    }

    /** Игрок находится в одном из регионов WorldGuard с указанными именами. */
    public boolean inRegion(Location loc, List<String> regionNames) {
        if (!available || loc == null || loc.getWorld() == null || regionNames == null || regionNames.isEmpty()) {
            return false;
        }
        try {
            Object weWorld = adaptWorld.invoke(null, loc.getWorld());
            Object manager = containerGet.invoke(regionContainer, weWorld);
            if (manager == null) {
                return false;
            }
            Method getRegion = manager.getClass().getMethod("getRegion", String.class);
            Object point = vectorAt.invoke(null, loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
            for (String name : regionNames) {
                Object region = getRegion.invoke(manager, name);
                if (region == null) {
                    continue;
                }
                Method contains = containsMethod(region.getClass(), point.getClass());
                if (contains == null) {
                    continue;
                }
                Object inside = contains.invoke(region, point);
                if (Boolean.TRUE.equals(inside)) {
                    return true;
                }
            }
            return false;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Ищет contains(...) по совместимому типу параметра. Раньше здесь был
     * getMethod("contains", point.getClass()) — если параметр объявлен как
     * BlockVector3/интерфейс, поиск падал, ошибка глоталась, и регионы
     * WorldGuard молча не работали (ни запрет, ни исключение pvp).
     */
    private static Method containsMethod(Class<?> regionClass, Class<?> pointClass) {
        Method fallback = null;
        for (Method method : regionClass.getMethods()) {
            if (!"contains".equals(method.getName()) || method.getParameterCount() != 1) {
                continue;
            }
            if (method.getParameterTypes()[0].isAssignableFrom(pointClass)) {
                return method;
            }
            if (fallback == null) {
                fallback = method;
            }
        }
        return fallback;
    }

    /** Для отладки: адаптер локации не нужен наружу, оставлен на будущее. */
    public Object adapt(Location loc) {
        try {
            return adaptLocation == null ? null : adaptLocation.invoke(null, loc);
        } catch (Throwable t) {
            return null;
        }
    }
}
