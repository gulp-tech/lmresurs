package ru.voidpe.items;

import org.bukkit.Bukkit;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

/**
 * Встроенный парсер схематик Sponge (.schem от WorldEdit //schem save):
 * читает файл сам, без WorldEdit. Поддерживает Sponge v1/v2 (Palette+BlockData)
 * и v3 (Blocks.Palette+Blocks.Data). Центр — Offset из файла (где стоял
 * игрок при //copy).
 */
public final class SchemParser {

    public static final class Parsed {
        public final List<WEHook.SchematicCell> cells = new ArrayList<>();
        public int relMinX, relMinY, relMinZ, relMaxX, relMaxY, relMaxZ;
        public int width, height, length;
        public int skippedBlocks;
    }

    private SchemParser() {}

    public static Parsed parse(File file) throws IOException {
        Map<String, Object> root = readNbtRoot(file);
        Map<String, Object> schem = root;
        Object nested = root.get("Schematic");
        if (nested instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> m = (Map<String, Object>) nested;
            schem = m;
        }

        int width = number(schem.get("Width"), 0);
        int height = number(schem.get("Height"), 0);
        int length = number(schem.get("Length"), 0);

        Map<String, Object> paletteTag = mapOf(schem.get("Palette"));
        byte[] blockData = bytesOf(schem.get("BlockData"));
        Object blocks = schem.get("Blocks");
        if (blocks instanceof Map) { // Sponge v3
            @SuppressWarnings("unchecked")
            Map<String, Object> b = (Map<String, Object>) blocks;
            if (paletteTag.isEmpty()) paletteTag = mapOf(b.get("Palette"));
            if (blockData == null) blockData = bytesOf(b.get("Data"));
        }
        if (width <= 0 || height <= 0 || length <= 0 || paletteTag.isEmpty() || blockData == null) {
            if (schem.containsKey("Blocks") && schem.get("Blocks") instanceof byte[]) {
                throw new IOException("это старый формат .schematic (MCEdit) — пересохрани схему через WorldEdit командой //schem save");
            }
            throw new IOException("не нашёл в файле Palette/BlockData/размеры — это точно .schem от //schem save?");
        }
        long volume = (long) width * height * length;
        if (volume > 500_000L) {
            throw new IOException("схематика слишком большая (" + volume + " блоков, лимит 500000)");
        }

        // палитра: индекс -> строка блока ("minecraft:stone", "minecraft:oak_stairs[facing=north,...]")
        String[] palette = new String[paletteTag.size()];
        for (Map.Entry<String, Object> e : paletteTag.entrySet()) {
            int idx = number(e.getValue(), -1);
            if (idx >= 0) {
                if (idx >= palette.length) {
                    String[] bigger = new String[idx + 1];
                    System.arraycopy(palette, 0, bigger, 0, palette.length);
                    palette = bigger;
                }
                palette[idx] = e.getKey();
            }
        }

        // смещение центра (точки //copy) от минимального угла
        int offX = 0, offY = 0, offZ = 0;
        Object offset = schem.get("Offset");
        if (offset instanceof int[] && ((int[]) offset).length >= 3) {
            int[] o = (int[]) offset;
            offX = o[0]; offY = o[1]; offZ = o[2];
        } else {
            Map<String, Object> meta = mapOf(schem.get("Metadata"));
            offX = number(meta.get("WEOffsetX"), 0);
            offY = number(meta.get("WEOffsetY"), 0);
            offZ = number(meta.get("WEOffsetZ"), 0);
        }

        Parsed parsed = new Parsed();
        parsed.width = width; parsed.height = height; parsed.length = length;
        parsed.relMinX = -offX; parsed.relMinY = -offY; parsed.relMinZ = -offZ;
        parsed.relMaxX = width - 1 - offX; parsed.relMaxY = height - 1 - offY; parsed.relMaxZ = length - 1 - offZ;

        // BlockData: varint'ы подряд в порядке y -> z -> x
        int[] indexes = decodeVarints(blockData, (int) volume);
        int i = 0;
        for (int y = 0; y < height; y++) {
            for (int z = 0; z < length; z++) {
                for (int x = 0; x < width; x++, i++) {
                    int palIdx = i < indexes.length ? indexes[i] : 0;
                    String blockName = palIdx >= 0 && palIdx < palette.length ? palette[palIdx] : null;
                    if (blockName == null) {
                        parsed.skippedBlocks++;
                        continue;
                    }
                    boolean air = blockName.equals("minecraft:air") || blockName.equals("minecraft:cave_air")
                            || blockName.equals("minecraft:void_air")
                            || blockName.startsWith("minecraft:air[") || blockName.startsWith("minecraft:cave_air[")
                            || blockName.startsWith("minecraft:void_air[");
                    try {
                        Bukkit.createBlockData(blockName); // проверка, что сервер знает такой блок
                    } catch (IllegalArgumentException e) {
                        parsed.skippedBlocks++;
                        continue;
                    }
                    parsed.cells.add(new WEHook.SchematicCell(x - offX, y - offY, z - offZ, blockName, air));
                }
            }
        }
        if (parsed.cells.isEmpty()) {
            throw new IOException("в схематике не оказалось ни одного понятного серверу блока");
        }
        return parsed;
    }

    private static int[] decodeVarints(byte[] data, int expected) {
        int[] out = new int[expected];
        int pos = 0, count = 0;
        while (pos < data.length && count < expected) {
            int value = 0, shift = 0;
            while (pos < data.length) {
                int b = data[pos++] & 0xFF;
                value |= (b & 0x7F) << shift;
                if ((b & 0x80) == 0) break;
                shift += 7;
            }
            out[count++] = value;
        }
        return out;
    }

    // ------------------------------------------------------------ NBT (минимум)

    @SuppressWarnings("unchecked")
    private static Map<String, Object> readNbtRoot(File file) throws IOException {
        byte[] raw;
        try (InputStream in = new FileInputStream(file)) {
            raw = in.readAllBytes();
        }
        InputStream stream = new ByteArrayInputStream(raw);
        if (raw.length >= 2 && (raw[0] & 0xFF) == 0x1F && (raw[1] & 0xFF) == 0x8B) {
            stream = new GZIPInputStream(stream);
        } else if (raw.length >= 1 && (raw[0] & 0xFF) == 0x78) {
            stream = new InflaterInputStream(stream);
        }
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(stream))) {
            int type = in.readByte();
            if (type != 10) {
                throw new IOException("файл не похож на NBT-схематику (первый тег " + type + ")");
            }
            in.readUTF(); // имя корня
            Object root = readPayload(in, 10);
            if (!(root instanceof Map)) {
                throw new IOException("повреждённая схематика: корень не compound");
            }
            return (Map<String, Object>) root;
        }
    }

    private static Object readPayload(DataInputStream in, int type) throws IOException {
        switch (type) {
            case 1: return in.readByte();
            case 2: return in.readShort();
            case 3: return in.readInt();
            case 4: return in.readLong();
            case 5: return in.readFloat();
            case 6: return in.readDouble();
            case 7: {
                int len = in.readInt();
                byte[] arr = new byte[len];
                in.readFully(arr);
                return arr;
            }
            case 8: return in.readUTF();
            case 9: {
                int elemType = in.readByte();
                int len = in.readInt();
                List<Object> list = new ArrayList<>(Math.max(0, len));
                for (int i = 0; i < len; i++) {
                    list.add(readPayload(in, elemType));
                }
                return list;
            }
            case 10: {
                Map<String, Object> map = new LinkedHashMap<>();
                while (true) {
                    int childType = in.readByte();
                    if (childType == 0) break;
                    String name = in.readUTF();
                    map.put(name, readPayload(in, childType));
                }
                return map;
            }
            case 11: {
                int len = in.readInt();
                int[] arr = new int[len];
                for (int i = 0; i < len; i++) arr[i] = in.readInt();
                return arr;
            }
            case 12: {
                int len = in.readInt();
                long[] arr = new long[len];
                for (int i = 0; i < len; i++) arr[i] = in.readLong();
                return arr;
            }
            default: throw new IOException("неизвестный NBT-тег " + type);
        }
    }

    private static int number(Object o, int def) {
        return o instanceof Number ? ((Number) o).intValue() : def;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapOf(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : Map.of();
    }

    private static byte[] bytesOf(Object o) {
        return o instanceof byte[] ? (byte[]) o : null;
    }
}
