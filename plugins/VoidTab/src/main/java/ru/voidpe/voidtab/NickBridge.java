package ru.voidpe.voidtab;

import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Мост к VoidNick: если игрок переименован, VoidTab должен показывать новый
 * ник, а не настоящий. Иначе voidtab каждые 40 тиков перезаписывает
 * playerListName настоящим именем, и ник «отваливается» через 2 секунды.
 *
 * Читаем plugins/VoidNick/data.yml напрямую: у VoidNick нет публичного API,
 * а файл — его же штатное хранилище. Файл перечитывается только когда
 * меняется время модификации, поэтому чтения с диска в тике нет.
 */
public final class NickBridge {

    private final JavaPlugin plugin;
    private final File dataFile;
    private long lastModified = -1L;
    private Map<UUID, String> names = Map.of();

    public NickBridge(JavaPlugin plugin) {
        this.plugin = plugin;
        this.dataFile = new File(plugin.getDataFolder().getParentFile(), "VoidNick/data.yml");
    }

    /** Включён ли мост и существует ли файл VoidNick. */
    public boolean available() {
        return plugin.getConfig().getBoolean("nick-bridge.enabled", true) && dataFile.isFile();
    }

    /** Ник игрока: из VoidNick, если он переименован, иначе настоящий. */
    public String name(Player player) {
        if (player == null) {
            return "";
        }
        if (!available()) {
            return player.getName();
        }
        refreshIfChanged();
        String nick = names.get(player.getUniqueId());
        return nick == null || nick.isBlank() ? player.getName() : nick;
    }

    private void refreshIfChanged() {
        long modified = dataFile.lastModified();
        if (modified == lastModified) {
            return;
        }
        lastModified = modified;
        Map<UUID, String> loaded = new HashMap<>();
        try {
            YamlConfiguration data = YamlConfiguration.loadConfiguration(dataFile);
            var section = data.getConfigurationSection("players");
            if (section != null) {
                for (String key : section.getKeys(false)) {
                    try {
                        UUID uuid = UUID.fromString(key);
                        String current = section.getString(key + ".current");
                        if (current != null && !current.isBlank()) {
                            loaded.put(uuid, current);
                        }
                    } catch (IllegalArgumentException ignored) {
                        // битый UUID в файле — пропускаем
                    }
                }
            }
        } catch (Throwable t) {
            plugin.getLogger().warning("VoidTab: не удалось прочитать ники из VoidNick: " + t.getMessage());
        }
        names = loaded;
    }

    /** Сбросить кэш (например, после /voidnick set). */
    public void invalidate() {
        lastModified = -1L;
    }
}
