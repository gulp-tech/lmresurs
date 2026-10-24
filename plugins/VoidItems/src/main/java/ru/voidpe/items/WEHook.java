package ru.voidpe.items;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * Мягкая привязка к WorldEdit/FAWE через рефлексию: загрузка схематики трапки
 * (plugins/VoidItems/schematics/*.schem).
 *
 * Схематика при загрузке сразу превращается в список блоков Bukkit
 * (смещение от центра + строка BlockData), поэтому сама вставка идёт
 * обычными средствами Bukkit — без EditSession и прочих капризных API.
 * Центр схематики — где стоял игрок, когда делал //copy в WorldEdit.
 */
public final class WEHook {

    /** Один блок схематики: смещение от центра и данные блока Bukkit. */
    public static final class SchematicCell {
        public final int x, y, z;
        public final String blockData;
        public final boolean air;

        SchematicCell(int x, int y, int z, String blockData, boolean air) {
            this.x = x; this.y = y; this.z = z;
            this.blockData = blockData;
            this.air = air;
        }
    }

    private final Logger log;
    private ClassLoader cl;
    private Object worldEdit;
    private Method adaptBlock; // BukkitAdapter.adapt(BlockState...) -> org.bukkit.block.data.BlockData

    private boolean loaded;
    private int relMinX, relMinY, relMinZ, relMaxX, relMaxY, relMaxZ;
    private final List<SchematicCell> cells = new ArrayList<>();

    public WEHook(Logger log) {
        this.log = log;
        init();
    }

    private void init() {
        Plugin we = Bukkit.getPluginManager().getPlugin("WorldEdit");
        if (we == null) {
            we = Bukkit.getPluginManager().getPlugin("FastAsyncWorldEdit");
        }
        if (we == null) {
            log.info("WorldEdit не найден — схематика трапки будет читаться встроенным парсером .schem.");
            return;
        }
        try {
            cl = we.getClass().getClassLoader();
            Class<?> weClass = Class.forName("com.sk89q.worldedit.WorldEdit", true, cl);
            worldEdit = weClass.getMethod("getInstance").invoke(null);
            Class<?> adapter = Class.forName("com.sk89q.worldedit.bukkit.BukkitAdapter", true, cl);
            Class<?> bukkitBlockData = Class.forName("org.bukkit.block.data.BlockData");
            for (Method m : adapter.getMethods()) {
                if (m.getName().equals("adapt") && m.getParameterCount() == 1
                        && bukkitBlockData.isAssignableFrom(m.getReturnType())) {
                    adaptBlock = m;
                    break;
                }
            }
            if (adaptBlock == null) {
                log.warning("WorldEdit: не нашёл BukkitAdapter.adapt(BlockState) — схематика трапки недоступна.");
                worldEdit = null;
                return;
            }
            log.info("WorldEdit подключён (своя схематика трапки)");
        } catch (Throwable t) {
            log.warning("WorldEdit hook не удался: " + t.getMessage());
            worldEdit = null;
        }
    }

    public boolean available() {
        return worldEdit != null;
    }

    public boolean hasSchematic() {
        return loaded;
    }

    private int coord(Object vector, String getter) throws Exception {
        return (Integer) vector.getClass().getMethod(getter).invoke(vector);
    }

    /** Загрузить .schem файл и превратить его в блоки Bukkit. @return true, если готова. */
    public boolean loadSchematic(File file) {
        if (file == null || !file.exists()) {
            return false;
        }
        // 1) Встроенный парсер .schem — WorldEdit для загрузки не нужен.
        try {
            SchemParser.Parsed parsed = SchemParser.parse(file);
            cells.clear();
            cells.addAll(parsed.cells);
            relMinX = parsed.relMinX; relMinY = parsed.relMinY; relMinZ = parsed.relMinZ;
            relMaxX = parsed.relMaxX; relMaxY = parsed.relMaxY; relMaxZ = parsed.relMaxZ;
            loaded = true;
            log.info("Схематика трапки загружена: " + file.getName()
                    + " (размер " + parsed.width + "x" + parsed.height + "x" + parsed.length
                    + ", блоков: " + parsed.cells.size()
                    + (parsed.skippedBlocks > 0 ? ", пропущено неизвестных серверу: " + parsed.skippedBlocks : "") + ")");
            return true;
        } catch (Throwable t) {
            log.warning("Встроенный парсер не прочитал " + file.getName() + ": " + t.getMessage()
                    + (available() ? " — пробую через WorldEdit..." : ""));
        }
        // 2) Запасной путь — через WorldEdit/FAWE, если он стоит.
        if (!available()) {
            return false;
        }
        try {
            Class<?> formats = Class.forName("com.sk89q.worldedit.extent.clipboard.io.ClipboardFormats", true, cl);
            Object format = formats.getMethod("findByFile", File.class).invoke(null, file);
            if (format == null) {
                log.warning("Не понял формат схематики: " + file.getName());
                return false;
            }
            Object clipboard;
            try (InputStream in = new FileInputStream(file)) {
                Object reader = format.getClass().getMethod("getReader", InputStream.class).invoke(format, in);
                clipboard = reader.getClass().getMethod("read").invoke(reader);
            }
            Object min = clipboard.getClass().getMethod("getMinimumPoint").invoke(clipboard);
            Object max = clipboard.getClass().getMethod("getMaximumPoint").invoke(clipboard);
            Object origin = clipboard.getClass().getMethod("getOrigin").invoke(clipboard);
            int ox = coord(origin, "getX"), oy = coord(origin, "getY"), oz = coord(origin, "getZ");
            int minX = coord(min, "getX"), minY = coord(min, "getY"), minZ = coord(min, "getZ");
            int maxX = coord(max, "getX"), maxY = coord(max, "getY"), maxZ = coord(max, "getZ");
            relMinX = minX - ox; relMinY = minY - oy; relMinZ = minZ - oz;
            relMaxX = maxX - ox; relMaxY = maxY - oy; relMaxZ = maxZ - oz;

            Class<?> bv3 = Class.forName("com.sk89q.worldedit.math.BlockVector3", true, cl);
            Method vectorAt = bv3.getMethod("at", int.class, int.class, int.class);
            Method getBlock = null;
            for (Method m : clipboard.getClass().getMethods()) {
                if (m.getName().equals("getBlock") && m.getParameterCount() == 1) {
                    getBlock = m;
                    break;
                }
            }
            if (getBlock == null) {
                log.warning("WorldEdit: у схематики нет getBlock — не могу прочитать блоки.");
                return false;
            }

            cells.clear();
            int skipped = 0;
            for (int x = minX; x <= maxX; x++) {
                for (int y = minY; y <= maxY; y++) {
                    for (int z = minZ; z <= maxZ; z++) {
                        Object point = vectorAt.invoke(null, x, y, z);
                        Object block = getBlock.invoke(clipboard, point);
                        Object type = block.getClass().getMethod("getBlockType").invoke(block);
                        String id = String.valueOf(type.getClass().getMethod("getId").invoke(type));
                        boolean air = id.endsWith(":air") || id.endsWith(":cave_air") || id.endsWith(":void_air");
                        String data;
                        try {
                            Object bukkitData = adaptBlock.invoke(null, block);
                            data = ((org.bukkit.block.data.BlockData) bukkitData).getAsString(true);
                        } catch (Throwable t) {
                            skipped++;
                            continue;
                        }
                        cells.add(new SchematicCell(x - ox, y - oy, z - oz, data, air));
                    }
                }
            }
            if (cells.isEmpty()) {
                log.warning("Схематика " + file.getName() + " прочиталась пустой — трапка будет встроенной клеткой.");
                return false;
            }
            loaded = true;
            log.info("Схематика трапки загружена: " + file.getName()
                    + " (размер " + (relMaxX - relMinX + 1) + "x" + (relMaxY - relMinY + 1) + "x" + (relMaxZ - relMinZ + 1)
                    + ", блоков: " + cells.size() + (skipped > 0 ? ", пропущено неподдерживаемых: " + skipped : "") + ")");
            return true;
        } catch (Throwable t) {
            log.warning("Схематика трапки не загрузилась: " + t.getMessage());
            loaded = false;
            cells.clear();
            return false;
        }
    }

    public int relMinX() { return relMinX; }
    public int relMinY() { return relMinY; }
    public int relMinZ() { return relMinZ; }
    public int relMaxX() { return relMaxX; }
    public int relMaxY() { return relMaxY; }
    public int relMaxZ() { return relMaxZ; }
    public List<SchematicCell> cells() { return cells; }
}
