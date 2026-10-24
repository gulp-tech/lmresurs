package ru.voidpe.nick;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import java.util.stream.Stream;

/**
 * Хранилище переименований и журнал.
 *
 * <p>Всё лежит в папке плагина: {@code data.yml} — кто на ком стоит,
 * {@code journal.yml} — что и когда делали, {@code archives/} — снятые данные
 * игроков (playerdata, статистика, достижения). Никаких внешних БД: сервер
 * один, файлов достаточно, и их видно глазами, если что-то пошло не так.</p>
 */
final class NickStore {

    /** Одна запись: кого переименовали и что при этом сняли. */
    static final class Entry {
        UUID uuid;
        String original;
        String current;
        long changedAt;
        String archive = "";
        boolean wasOp;
        boolean groupsSaved;
        List<String> groups = new ArrayList<>();
        String primaryGroup = "";
        boolean balanceSaved;
        double balance;
        List<String> applied = new ArrayList<>();

        String date() {
            SimpleDateFormat format = new SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.ROOT);
            return format.format(new Date(changedAt));
        }
    }

    private final JavaPlugin plugin;
    private final File dataFile;
    private final File journalFile;
    private final File archiveRoot;
    private final Map<UUID, Entry> entries = new HashMap<>();

    NickStore(JavaPlugin plugin) {
        this.plugin = plugin;
        this.dataFile = new File(plugin.getDataFolder(), "data.yml");
        this.journalFile = new File(plugin.getDataFolder(), "journal.yml");
        this.archiveRoot = new File(plugin.getDataFolder(), "archives");
    }

    // ------------------------------------------------------------ загрузка/сохранение

    void load() {
        entries.clear();
        if (!dataFile.exists()) {
            return;
        }
        // YamlConfiguration.loadConfiguration(File) не бросает IOException:
        // при ошибке Bukkit сам пишет её в лог и возвращает пустой конфиг.
        YamlConfiguration data = YamlConfiguration.loadConfiguration(dataFile);
        ConfigurationSection players = data.getConfigurationSection("players");
        if (players == null) {
            return;
        }
        for (String key : players.getKeys(false)) {
            ConfigurationSection section = players.getConfigurationSection(key);
            if (section == null) {
                continue;
            }
            Entry entry = new Entry();
            try {
                entry.uuid = UUID.fromString(key);
            } catch (IllegalArgumentException broken) {
                plugin.getLogger().warning("В data.yml битый UUID: " + key + " — запись пропущена");
                continue;
            }
            entry.original = section.getString("original", "");
            entry.current = section.getString("current", "");
            entry.changedAt = section.getLong("changed-at", System.currentTimeMillis());
            entry.archive = section.getString("archive", "");
            entry.wasOp = section.getBoolean("was-op", false);
            entry.groupsSaved = section.getBoolean("groups-saved", false);
            entry.groups = new ArrayList<>(section.getStringList("groups"));
            entry.primaryGroup = section.getString("primary-group", "");
            entry.balanceSaved = section.getBoolean("balance-saved", false);
            entry.balance = section.getDouble("balance", 0.0);
            entry.applied = new ArrayList<>(section.getStringList("applied"));
            entries.put(entry.uuid, entry);
        }
        plugin.getLogger().info("VoidNick: загружено записей — " + entries.size());
    }

    void save() {
        YamlConfiguration data = new YamlConfiguration();
        for (Entry entry : entries.values()) {
            String base = "players." + entry.uuid;
            data.set(base + ".original", entry.original);
            data.set(base + ".current", entry.current);
            data.set(base + ".changed-at", entry.changedAt);
            data.set(base + ".archive", entry.archive);
            data.set(base + ".was-op", entry.wasOp);
            data.set(base + ".groups-saved", entry.groupsSaved);
            data.set(base + ".groups", entry.groups);
            data.set(base + ".primary-group", entry.primaryGroup);
            data.set(base + ".balance-saved", entry.balanceSaved);
            data.set(base + ".balance", entry.balance);
            data.set(base + ".applied", entry.applied);
        }
        write(data, dataFile);
    }

    /** Журнал — обычный список строк, дописывается снизу; старые записи не теряются. */
    void journal(String line) {
        YamlConfiguration journal = new YamlConfiguration();
        if (journalFile.exists()) {
            journal = YamlConfiguration.loadConfiguration(journalFile);
        }
        List<String> lines = new ArrayList<>(journal.getStringList("lines"));
        lines.add(new SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.ROOT).format(new Date()) + " | " + line);
        if (lines.size() > 5000) {
            lines = lines.subList(lines.size() - 5000, lines.size());
        }
        journal.set("lines", lines);
        write(journal, journalFile);
        plugin.getLogger().info("VoidNick: " + line);
    }

    List<String> journalLines(String filter, int limit) {
        if (!journalFile.exists()) {
            return List.of();
        }
        List<String> lines = YamlConfiguration.loadConfiguration(journalFile).getStringList("lines");
        List<String> out = new ArrayList<>();
        for (String line : lines) {
            if (filter == null || filter.isEmpty() || line.toLowerCase(Locale.ROOT).contains(filter.toLowerCase(Locale.ROOT))) {
                out.add(line);
            }
        }
        if (out.size() > limit) {
            out = out.subList(out.size() - limit, out.size());
        }
        return out;
    }

    private void write(YamlConfiguration config, File file) {
        try {
            if (!file.getParentFile().exists() && !file.getParentFile().mkdirs()) {
                plugin.getLogger().warning("Не удалось создать папку " + file.getParentFile());
            }
            config.save(file);
        } catch (IOException failure) {
            plugin.getLogger().log(Level.SEVERE, "Не удалось сохранить " + file.getName(), failure);
        }
    }

    // ------------------------------------------------------------ поиск

    Entry byUuid(UUID uuid) {
        return entries.get(uuid);
    }

    /** Ищет запись по текущему или исходному нику (без учёта регистра). */
    Entry byName(String name) {
        if (name == null || name.isEmpty()) {
            return null;
        }
        for (Entry entry : entries.values()) {
            if (entry.current.equalsIgnoreCase(name) || entry.original.equalsIgnoreCase(name)) {
                return entry;
            }
        }
        return null;
    }

    Collection<Entry> all() {
        List<Entry> list = new ArrayList<>(entries.values());
        list.sort(Comparator.comparingLong((Entry entry) -> entry.changedAt).reversed());
        return list;
    }

    void put(Entry entry) {
        entries.put(entry.uuid, entry);
    }

    void remove(UUID uuid) {
        entries.remove(uuid);
    }

    // ------------------------------------------------------------ архив данных

    /** Каталог архива для игрока: plugins/VoidNick/archives/<дата>-<ник>. */
    File archiveDirectory(String playerName) {
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(new Date());
        File directory = new File(archiveRoot, stamp + "-" + playerName);
        if (!directory.exists() && !directory.mkdirs()) {
            plugin.getLogger().warning("Не удалось создать архив " + directory);
        }
        return directory;
    }

    String relativeArchive(File directory) {
        return plugin.getDataFolder().toPath().relativize(directory.toPath()).toString().replace('\\', '/');
    }

    File absoluteArchive(String relative) {
        return relative == null || relative.isEmpty() ? null : new File(plugin.getDataFolder(), relative);
    }

    /**
     * Переносит файл в архив. Возвращает true, если файл реально перемещён.
     * Никогда не бросает: отсутствие файла — это норма (новый игрок).
     */
    boolean archiveFile(File source, File directory, String subfolder) {
        if (source == null || !source.exists()) {
            return false;
        }
        File target = new File(directory, subfolder + "/" + source.getName());
        return move(source, target);
    }

    /** Возвращает файл из архива на место. */
    boolean restoreFile(String relativeArchive, String subfolder, String fileName, File target) {
        File directory = absoluteArchive(relativeArchive);
        if (directory == null) {
            return false;
        }
        File source = new File(directory, subfolder + "/" + fileName);
        return move(source, target);
    }

    private boolean move(File source, File target) {
        try {
            Path parent = target.toPath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            return true;
        } catch (IOException failure) {
            plugin.getLogger().log(Level.WARNING, "Не удалось перенести " + source + " → " + target, failure);
            return false;
        }
    }

    /** Очистка пустых каталогов архива (после отката). */
    void tidyArchive(String relative) {
        File directory = absoluteArchive(relative);
        if (directory == null || !directory.exists()) {
            return;
        }
        try (Stream<Path> paths = Files.walk(directory.toPath())) {
            paths.sorted(Comparator.reverseOrder())
                    .filter(path -> !path.equals(directory.toPath()))
                    .map(Path::toFile)
                    .filter(File::isDirectory)
                    .filter(dir -> {
                        String[] children = dir.list();
                        return children != null && children.length == 0;
                    })
                    .forEach(File::delete);
        } catch (IOException ignored) {
            // не критично: пустая папка в архиве никому не мешает
        }
    }
}
