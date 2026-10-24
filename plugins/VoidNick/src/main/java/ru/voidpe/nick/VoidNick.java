package ru.voidpe.nick;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.ScoreboardManager;
import org.bukkit.scoreboard.Team;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * VoidNick — «смена ника с нуля» для VoidPE.
 *
 * <p>Что делает при переименовании:</p>
 * <ol>
 *   <li>подменяет ник везде, что видит сервер: чат, таб-лист, скорборд
 *       (команды и очки), сообщения входа/выхода, /list своего формата;</li>
 *   <li>снимает всё, что делало игрока «старым»: OP, группы LuckPerms,
 *       баланс Vault, инвентарь/опыт/статистику/достижения (файлы
 *       переносятся в архив плагина);</li>
 *   <li>пишет каждое действие в журнал.</li>
 * </ol>
 *
 * <p>Откат одной командой возвращает ник, права, деньги и файлы данных.</p>
 *
 * <p><b>Ограничение, о котором надо помнить:</b> настоящее имя аккаунта
 * складывается на клиенте и в UUID, сервер его не меняет. Плагин подменяет
 * ник во всём, что рендерит сервер; в логах ядра и в F3 останется исходное
 * имя. Команды (например, {@code /tp}) по-прежнему принимают настоящее имя —
 * потому что Bukkit ищет игрока по нему.</p>
 */
public final class VoidNick extends JavaPlugin implements CommandExecutor, TabCompleter, Listener {

    /** Латиница, цифры, подчёркивание и точка (Floodgate-ники бывают с точкой). */
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_.]{3,16}");

    private NickStore store;
    private LuckPermsHook luckPerms;
    private EconomyHook economy;
    private BukkitTask refreshTask;

    // ------------------------------------------------------------------ жизненный цикл

    @Override
    public void onEnable() {
        saveDefaultConfig();
        store = new NickStore(this);
        store.load();
        luckPerms = new LuckPermsHook(getLogger());
        economy = new EconomyHook(getLogger());

        getServer().getPluginManager().registerEvents(this, this);
        PluginCommand command = getCommand("voidnick");
        if (command != null) {
            command.setExecutor(this);
            command.setTabCompleter(this);
        }

        long ticks = Math.max(20L, getConfig().getLong("rename.refresh-ticks", 40L));
        refreshTask = Bukkit.getScheduler().runTaskTimer(this, this::refreshNames, ticks, ticks);

        getLogger().info("VoidNick включён: записей=" + store.all().size()
                + ", LuckPerms=" + luckPerms.available()
                + ", экономика=" + economy.available());
        getLogger().info("Команды только из консоли: /voidnick set|revert|info|list|history|reload");
    }

    @Override
    public void onDisable() {
        if (refreshTask != null) {
            refreshTask.cancel();
        }
        if (store != null) {
            store.save();
        }
    }

    // ------------------------------------------------------------------ команды (только консоль)

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (sender instanceof Player) {
            sender.sendMessage(text("messages.no-permission"));
            return true;
        }
        if (args.length == 0) {
            sender.sendMessage(text("messages.usage"));
            return true;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "set" -> {
                if (args.length < 3) {
                    sender.sendMessage(text("messages.usage"));
                    return true;
                }
                rename(sender, args[1], args[2]);
            }
            case "revert" -> {
                if (args.length < 2) {
                    sender.sendMessage(text("messages.usage"));
                    return true;
                }
                revert(sender, args[1]);
            }
            case "info" -> {
                if (args.length < 2) {
                    sender.sendMessage(text("messages.usage"));
                    return true;
                }
                info(sender, args[1]);
            }
            case "list" -> list(sender);
            case "history" -> history(sender, args.length > 1 ? args[1] : "");
            case "reload" -> {
                reloadConfig();
                sender.sendMessage(text("messages.reloaded"));
            }
            default -> sender.sendMessage(text("messages.usage"));
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (sender instanceof Player) {
            return List.of();
        }
        if (args.length == 1) {
            List<String> out = new ArrayList<>();
            String typed = args[0].toLowerCase(Locale.ROOT);
            for (String option : List.of("set", "revert", "info", "list", "history", "reload")) {
                if (option.startsWith(typed)) {
                    out.add(option);
                }
            }
            return out;
        }
        if (args.length == 2 && List.of("revert", "info", "history").contains(args[0].toLowerCase(Locale.ROOT))) {
            List<String> names = new ArrayList<>();
            String typed = args[1].toLowerCase(Locale.ROOT);
            for (NickStore.Entry entry : store.all()) {
                for (String name : List.of(entry.current, entry.original)) {
                    if (name.toLowerCase(Locale.ROOT).startsWith(typed)) {
                        names.add(name);
                    }
                }
            }
            return names;
        }
        return List.of();
    }

    // ------------------------------------------------------------------ переименование

    private void rename(CommandSender sender, String oldName, String newName) {
        if (!NAME.matcher(newName).matches()) {
            sender.sendMessage(text("messages.invalid-name"));
            return;
        }
        NickStore.Entry byNew = store.byName(newName);
        if (byNew != null) {
            sender.sendMessage(text("messages.already-renamed", "%player%", byNew.current));
            return;
        }
        OfflinePlayer target = resolve(oldName);
        if (target == null) {
            sender.sendMessage(text("messages.unknown-player", "%player%", oldName));
            return;
        }
        UUID uuid = target.getUniqueId();
        if (Bukkit.getPlayer(uuid) != null) {
            sender.sendMessage(text("messages.player-online", "%player%", oldName));
            return;
        }
        NickStore.Entry existing = store.byUuid(uuid);
        if (existing != null) {
            sender.sendMessage(text("messages.already-renamed", "%player%", existing.original));
            return;
        }
        OfflinePlayer collision = Bukkit.getOfflinePlayer(newName);
        if (collision != null && collision.hasPlayedBefore() && !collision.getUniqueId().equals(uuid)) {
            sender.sendMessage(text("messages.name-taken", "%player%", newName));
            return;
        }
        String realName = target.getName() != null ? target.getName() : oldName;

        NickStore.Entry entry = new NickStore.Entry();
        entry.uuid = uuid;
        entry.original = realName;
        entry.current = newName;
        entry.changedAt = System.currentTimeMillis();

        // 1. Снимок того, что сейчас есть.
        if (getConfig().getBoolean("reset.luckperms", true)) {
            LuckPermsHook.Snapshot snapshot = luckPerms.read(uuid);
            if (snapshot != null) {
                entry.groupsSaved = true;
                entry.groups = snapshot.groups;
                entry.primaryGroup = snapshot.primary;
            } else {
                sender.sendMessage(text("messages.luckperms-unavailable"));
            }
        }
        if (getConfig().getBoolean("reset.economy", true)) {
            double balance = economy.balance(realName);
            if (!Double.isNaN(balance)) {
                entry.balanceSaved = true;
                entry.balance = balance;
            } else {
                sender.sendMessage(text("messages.economy-unavailable"));
            }
        }

        // 2. Архив данных игрока (файлы переносим, а не копируем).
        File directory = store.archiveDirectory(realName);
        entry.archive = store.relativeArchive(directory);
        archivePlayerFiles(uuid, directory, entry);

        // 3. Сброс «старого игрока».
        if (getConfig().getBoolean("reset.op", true) && target.isOp()) {
            entry.wasOp = true;
            target.setOp(false);
            entry.applied.add("OP снят");
        }
        String defaultGroup = getConfig().getString("reset.default-group", "default");
        if (entry.groupsSaved) {
            console("lp user " + realName + " clear");
            if (defaultGroup != null && !defaultGroup.isEmpty()) {
                console("lp user " + realName + " parent add " + defaultGroup);
            }
            entry.applied.add("группы LuckPerms: " + entry.groups + " → " + defaultGroup);
        }
        if (entry.balanceSaved && entry.balance > 0 && economy.withdrawAll(realName, entry.balance)) {
            entry.applied.add("баланс снят: " + entry.balance);
        }
        for (String command : getConfig().getStringList("reset.commands")) {
            if (command == null || command.isBlank()) {
                continue;
            }
            console(command.replace("%player%", realName));
            entry.applied.add("команда: " + command.replace("%player%", realName));
        }

        // 4. Записать и сохранить.
        store.put(entry);
        store.save();
        store.journal("SET " + realName + " → " + newName + " | архив: " + entry.archive
                + " | " + String.join("; ", entry.applied));

        sender.sendMessage(text("messages.renamed", "%old%", realName, "%new%", newName));
    }

    private void revert(CommandSender sender, String name) {
        NickStore.Entry entry = store.byName(name);
        if (entry == null) {
            sender.sendMessage(text("messages.not-renamed", "%player%", name));
            return;
        }
        OfflinePlayer target = Bukkit.getOfflinePlayer(entry.uuid);
        String realName = target.getName() != null ? target.getName() : entry.original;
        if (Bukkit.getPlayer(entry.uuid) != null) {
            sender.sendMessage(text("messages.player-online", "%player%", realName));
            return;
        }

        // 1. Файлы данных обратно.
        restorePlayerFiles(entry, realName);

        // 2. Права, оп, деньги.
        if (entry.groupsSaved) {
            for (String group : entry.groups) {
                console("lp user " + realName + " parent add " + group);
            }
            if (entry.primaryGroup != null && !entry.primaryGroup.isEmpty()) {
                console("lp user " + realName + " parent set " + entry.primaryGroup);
            }
        }
        if (entry.wasOp) {
            target.setOp(true);
        }
        if (entry.balanceSaved) {
            economy.deposit(realName, entry.balance);
        }
        for (String command : getConfig().getStringList("reset.restore-commands")) {
            if (command == null || command.isBlank()) {
                continue;
            }
            console(command.replace("%player%", realName));
        }

        // 3. Запись убираем, историю оставляем в журнале.
        store.remove(entry.uuid);
        store.save();
        store.tidyArchive(entry.archive);
        store.journal("REVERT " + entry.current + " → " + realName + " | архив возвращён: " + entry.archive);

        sender.sendMessage(text("messages.reverted", "%player%", realName, "%original%", entry.original));
    }

    // ------------------------------------------------------------------ данные игрока

    private void archivePlayerFiles(UUID uuid, File directory, NickStore.Entry entry) {
        File worldFolder = worldFolder();
        if (worldFolder == null) {
            getLogger().warning("Папка мира не найдена — данные игрока не архивированы");
            return;
        }
        if (getConfig().getBoolean("reset.playerdata", true)) {
            if (store.archiveFile(new File(worldFolder, "playerdata/" + uuid + ".dat"), directory, "playerdata")) {
                entry.applied.add("playerdata → архив");
            }
            store.archiveFile(new File(worldFolder, "playerdata/" + uuid + ".dat_old"), directory, "playerdata");
        }
        if (getConfig().getBoolean("reset.stats", true)
                && store.archiveFile(new File(worldFolder, "stats/" + uuid + ".json"), directory, "stats")) {
            entry.applied.add("статистика → архив");
        }
        if (getConfig().getBoolean("reset.advancements", true)
                && store.archiveFile(new File(worldFolder, "advancements/" + uuid + ".json"), directory, "advancements")) {
            entry.applied.add("достижения → архив");
        }
    }

    private void restorePlayerFiles(NickStore.Entry entry, String realName) {
        File worldFolder = worldFolder();
        if (worldFolder == null) {
            getLogger().warning("Папка мира не найдена — данные игрока не восстановлены");
            return;
        }
        UUID uuid = entry.uuid;
        if (store.restoreFile(entry.archive, "playerdata", uuid + ".dat",
                new File(worldFolder, "playerdata/" + uuid + ".dat"))) {
            getLogger().info("Возвращён playerdata игрока " + realName);
        }
        store.restoreFile(entry.archive, "playerdata", uuid + ".dat_old",
                new File(worldFolder, "playerdata/" + uuid + ".dat_old"));
        store.restoreFile(entry.archive, "stats", uuid + ".json", new File(worldFolder, "stats/" + uuid + ".json"));
        store.restoreFile(entry.archive, "advancements", uuid + ".json",
                new File(worldFolder, "advancements/" + uuid + ".json"));
    }

    private File worldFolder() {
        World world = null;
        List<World> worlds = Bukkit.getWorlds();
        if (worlds.isEmpty()) {
            return null;
        }
        String name = getConfig().getString("world", "");
        if (name != null && !name.isEmpty()) {
            world = Bukkit.getWorld(name);
        }
        if (world == null) {
            world = worlds.get(0);
        }
        return world.getWorldFolder();
    }

    // ------------------------------------------------------------------ отображение ника

    private void refreshNames() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            applyDisplay(player);
        }
    }

    private void applyDisplay(Player player) {
        NickStore.Entry entry = store.byUuid(player.getUniqueId());
        String nick = entry != null ? entry.current : player.getName();
        if (!nick.equals(player.getDisplayName())) {
            player.setDisplayName(nick);
        }
        if (!nick.equals(player.getPlayerListName())) {
            player.setPlayerListName(nick);
        }
    }

    /**
     * Переносит ник в скорборде: команды (тег над головой) и очки
     * (таблички лидеров, сайдбар, где ник записан строкой).
     */
    private void rewriteScoreboard(String realName, String nick) {
        if (realName == null || nick == null || realName.equals(nick)) {
            return;
        }
        ScoreboardManager manager = Bukkit.getScoreboardManager();
        if (manager == null) {
            return;
        }
        Scoreboard board = manager.getMainScoreboard();
        for (Team team : board.getTeams()) {
            if (team.getEntries().contains(realName)) {
                team.removeEntry(realName);
                team.addEntry(nick);
            }
        }
        for (Objective objective : board.getObjectives()) {
            if (!objective.getScore(realName).isScoreSet()) {
                continue;
            }
            int score = objective.getScore(realName).getScore();
            board.resetScores(realName);
            objective.getScore(nick).setScore(score);
        }
    }

    // ------------------------------------------------------------------ события

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        NickStore.Entry entry = store.byUuid(player.getUniqueId());
        applyDisplay(player);
        if (entry == null) {
            return;
        }
        // Скорборд трогаем через тик: на входе он ещё пересобирается ядром.
        Bukkit.getScheduler().runTask(this, () -> rewriteScoreboard(player.getName(), entry.current));
        if (getConfig().getBoolean("join-quit.enabled", false)) {
            event.setJoinMessage(null);
            broadcast("join-quit.join", entry.current);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onQuit(PlayerQuitEvent event) {
        NickStore.Entry entry = store.byUuid(event.getPlayer().getUniqueId());
        if (entry == null || !getConfig().getBoolean("join-quit.enabled", false)) {
            return;
        }
        event.setQuitMessage(null);
        broadcast("join-quit.quit", entry.current);
    }

    /** Современный чат Paper: подменяем ник в рендерере сообщения. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        NickStore.Entry entry = store.byUuid(event.getPlayer().getUniqueId());
        if (entry == null) {
            return;
        }
        Component prefix = LegacyComponentSerializer.legacySection()
                .deserialize(TextUtil.color(chatFormat().replace("%player%", entry.current)));
        event.renderer((source, sourceDisplayName, message, viewer) -> prefix.append(message));
    }

    /** Резерв для серверов/плагинов, которые ещё слушают старый чат-ивент. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    @SuppressWarnings("deprecation")
    public void onLegacyChat(AsyncPlayerChatEvent event) {
        NickStore.Entry entry = store.byUuid(event.getPlayer().getUniqueId());
        if (entry == null) {
            return;
        }
        String format = TextUtil.color(chatFormat().replace("%player%", entry.current));
        event.setFormat(format.replace("%", "%%") + "%2$s");
    }

    // ------------------------------------------------------------------ информация

    private void info(CommandSender sender, String name) {
        NickStore.Entry entry = store.byName(name);
        if (entry == null) {
            sender.sendMessage(text("messages.not-renamed", "%player%", name));
            return;
        }
        sender.sendMessage(text("messages.info-header", "%player%", entry.original));
        line(sender, "uuid", entry.uuid.toString());
        line(sender, "сейчас", entry.current);
        line(sender, "было", entry.original);
        line(sender, "когда", entry.date());
        line(sender, "архив", entry.archive);
        line(sender, "OP до смены", entry.wasOp ? "да" : "нет");
        line(sender, "группы до смены", entry.groupsSaved ? String.join(", ", entry.groups) + " (основная: "
                + (entry.primaryGroup.isEmpty() ? "-" : entry.primaryGroup) + ")" : "не записаны");
        line(sender, "баланс до смены", entry.balanceSaved ? String.valueOf(entry.balance) : "не записан");
        line(sender, "что сделано", entry.applied.isEmpty() ? "-" : String.join("; ", entry.applied));
    }

    private void list(CommandSender sender) {
        List<NickStore.Entry> entries = new ArrayList<>(store.all());
        if (entries.isEmpty()) {
            sender.sendMessage(text("messages.list-empty"));
            return;
        }
        sender.sendMessage(text("messages.list-header", "%count%", String.valueOf(entries.size())));
        for (NickStore.Entry entry : entries) {
            sender.sendMessage(text("messages.list-line",
                    "%original%", entry.original, "%new%", entry.current, "%date%", entry.date()));
        }
    }

    private void history(CommandSender sender, String filter) {
        List<String> lines = store.journalLines(filter, 40);
        if (lines.isEmpty()) {
            sender.sendMessage(text("messages.list-empty"));
            return;
        }
        sender.sendMessage(text("messages.history-header", "%count%", String.valueOf(lines.size())));
        for (String line : lines) {
            sender.sendMessage(TextUtil.color("&8• &7" + line));
        }
    }

    private void line(CommandSender sender, String key, String value) {
        sender.sendMessage(text("messages.info-line", "%key%", key, "%value%", value));
    }

    // ------------------------------------------------------------------ мелочи

    private OfflinePlayer resolve(String name) {
        NickStore.Entry entry = store.byName(name);
        if (entry != null) {
            return Bukkit.getOfflinePlayer(entry.uuid);
        }
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            return online;
        }
        OfflinePlayer offline = Bukkit.getOfflinePlayer(name);
        if (offline != null && (offline.hasPlayedBefore() || offline.isOnline())) {
            return offline;
        }
        return null;
    }

    private String chatFormat() {
        String format = getConfig().getString("chat.format", "&f%player% &8» &7");
        return format == null ? "&f%player% &8» &7" : format;
    }

    private void console(String command) {
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
    }

    private void broadcast(String key, String playerName) {
        String format = getConfig().getString(key, "");
        if (format == null || format.isEmpty()) {
            return;
        }
        Bukkit.broadcastMessage(TextUtil.color(format.replace("%player%", playerName)));
    }

    private String text(String key, String... replacements) {
        String prefix = getConfig().getString("messages.prefix", "");
        String value = getConfig().getString(key, key);
        String result = TextUtil.color((prefix == null ? "" : prefix) + (value == null ? "" : value));
        for (int i = 0; i + 1 < replacements.length; i += 2) {
            result = result.replace(replacements[i], replacements[i + 1]);
        }
        return result;
    }
}
