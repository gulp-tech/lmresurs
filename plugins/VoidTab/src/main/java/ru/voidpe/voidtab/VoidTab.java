package ru.voidpe.voidtab;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * VoidTab — компактный скорборд (таб) для Geyser/Floodgate сервера VoidPE.
 * Purpur 26.2 / Java 25.
 */
public final class VoidTab extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {

    private Hooks hooks;
    private SidebarManager sidebar;
    private NametagManager nametag;
    private BukkitTask task;
    private boolean warnedNoFloodgate;

    @Override
    public void onEnable() {
        migrateFromFaralTab();
        saveDefaultConfig();
        migrateConfig();
        hooks = new Hooks(getLogger());
        sidebar = new SidebarManager(this);
        nametag = new NametagManager(this);

        getServer().getPluginManager().registerEvents(this, this);
        if (getCommand("voidtab") != null) {
            getCommand("voidtab").setExecutor(this);
            getCommand("voidtab").setTabCompleter(this);
        }

        startTask();
        // игроки, уже онлайн при /reload
        hooks.refreshLuckPermsMeta();
        Bukkit.getOnlinePlayers().forEach(p -> {
            sidebar.update(p, true);
            nametag.apply(p, true);
        });
        getLogger().info("VoidTab включён (Bedrock-only). Floodgate: " + (hooks.hasFloodgate() ? "найден, Java-игроки будут кикаться" : "НЕ найден — bedrock-only не сможет отличать Java, поставьте Floodgate!"));
    }

    @Override
    public void onDisable() {
        if (task != null) {
            task.cancel();
        }
    }

    /** Переезд с FaralTab: подхватить конфиг и hidden.yml из plugins/FaralTab. */
    private void migrateFromFaralTab() {
        try {
            java.io.File pluginsDir = getDataFolder().getParentFile();
            if (pluginsDir == null) {
                return;
            }
            java.io.File oldDir = new java.io.File(pluginsDir, "FaralTab");
            if (!oldDir.isDirectory() || oldDir.equals(getDataFolder())) {
                return;
            }
            getDataFolder().mkdirs();
            for (String name : new String[]{"config.yml", "hidden.yml"}) {
                java.io.File from = new java.io.File(oldDir, name);
                java.io.File to = new java.io.File(getDataFolder(), name);
                if (from.isFile() && !to.exists()) {
                    java.nio.file.Files.copy(from.toPath(), to.toPath());
                    getLogger().info("Перенёс " + name + " из старой папки FaralTab.");
                }
            }
        } catch (Throwable t) {
            getLogger().warning("Не удалось перенести данные FaralTab: " + t.getMessage());
        }
    }

    /** Старые конфиги: значок устройства в ТАБЕ больше не нужен — он только в нике над головой. */
    private void migrateConfig() {
        java.util.List<String> lines = getConfig().getStringList("scoreboard.lines");
        boolean changed = false;
        java.util.List<String> cleaned = new java.util.ArrayList<>();
        java.util.Map<String, String> oldDefaults = java.util.Map.of(
                "&fМонеты: &#FFD93D{coins} {icon:spark}", "&fМонеты: &#FFD93D{coins} {icon:coin}",
                "&fВ игре: &#FFD93D{playtime_hours}ч {icon:coin}", "&fВ игре: &#FFD93D{playtime_hours}ч {icon:clock}");
        for (String line : lines) {
            String c = oldDefaults.getOrDefault(line, line)
                    .replace("{device_icon} ", "").replace("{device_icon}", "");
            if (!c.equals(line)) {
                changed = true;
            }
            cleaned.add(c);
        }
        if (changed) {
            getConfig().set("scoreboard.lines", cleaned);
            saveConfig();
            getLogger().info("Миграция: {device_icon} убран из строк скорборда (значок остался только в нике).");
        }
        // Заголовок скорборда: простой &b&lVoidPE (градиент жирность в Bedrock не даёт).
        if (!getConfig().getBoolean("scoreboard.title-simple-migrated", false)) {
            String title = getConfig().getString("scoreboard.title", "");
            if (title != null && (title.contains("FaralMC") || title.contains("VoidPE"))) {
                getConfig().set("scoreboard.title", "&b&lVoidPE");
                getLogger().info("Миграция: заголовок скорборда — &b&lVoidPE (синий, жирный).");
            }
            getConfig().set("scoreboard.title-simple-migrated", true);
            getConfig().set("scoreboard.title-bold-migrated", true);
            saveConfig();
        }
        // Заголовок скорборда: пиксельная полоса -> обычный читаемый текст VoidPE (один раз).
        if (!getConfig().getBoolean("scoreboard.title-text-migrated", false)) {
            getConfig().set("scoreboard.title-logo.enabled", false);
            getConfig().set("scoreboard.title-text-migrated", true);
            saveConfig();
            getLogger().info("Миграция: заголовок скорборда — текстовый VoidPE (полоса-картинка выключена).");
        }
    }

    public Hooks hooks() {
        return hooks;
    }

    public SidebarManager sidebar() {
        return sidebar;
    }

    public NametagManager nametag() {
        return nametag;
    }

    private void startTask() {
        if (task != null) {
            task.cancel();
        }
        long interval = Math.max(10L, getConfig().getLong("update-interval-ticks", 40L));
        task = Bukkit.getScheduler().runTaskTimer(this, () -> {
            // Префикс LuckPerms — единый источник. Перед каждым обновлением
            // сбрасываем кэш LP, иначе после внешней смены префикса скорборд
            // и ник могли показывать старое значение до перезахода.
            if (getConfig().getBoolean("luckperms.refresh-cache", true)) {
                hooks.refreshLuckPermsMeta();
            }
            sidebar.updateAll();
            boolean forceNames = getConfig().getBoolean("luckperms.force-name-resend", true);
            Bukkit.getOnlinePlayers().forEach(p -> nametag.apply(p, forceNames));
        }, interval, interval);
    }

    // ------------------------------------------------------------- события
    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (isJavaPlayer(player)) {
            getLogger().info("Bedrock-only: кикнут Java-игрок " + player.getName() + " (" + player.getUniqueId() + ")");
            player.kick(LegacyComponentSerializer.legacySection().deserialize(
                    TextUtil.color(getConfig().getString("bedrock-only.kick-message",
                            "&cVoidPE — сервер только для Bedrock!"))));
            return;
        }
        long delay = Math.max(0L, getConfig().getLong("join-delay-ticks", 20L));
        Bukkit.getScheduler().runTaskLater(this, () -> {
            hooks.refreshLuckPermsMeta(player.getUniqueId());
            sidebar.update(player, true);
            nametag.apply(player, true);
            showJoinTitle(player);
            // в лог сервера: чем клиент представился и какой значок получил (диагностика устройств)
            getLogger().info("Устройство " + player.getName() + ": OS=" + hooks.deviceOs(player.getUniqueId())
                    + " (Floodgate=" + hooks.floodgateDeviceOs(player.getUniqueId())
                    + ", Geyser=" + hooks.geyserDeviceOs(player.getUniqueId()) + ")"
                    + ", ввод=" + hooks.inputMode(player.getUniqueId())
                    + ", UI=" + hooks.uiProfile(player.getUniqueId())
                    + ", модель=" + hooks.deviceModel(player.getUniqueId())
                    + " -> значок " + sidebar.deviceIconName(player));
        }, delay);
    }

    /** Большой синий VoidPE при входе (как логотип на экране загрузки у референса). */
    private void showJoinTitle(Player player) {
        if (!getConfig().getBoolean("join-title.enabled", true)) {
            return;
        }
        Component title = component(sidebar.render(player,
                getConfig().getString("join-title.title", "<gradient:#55C8FF:#1B6FFF>VoidPE</gradient>")));
        Component subtitle = component(sidebar.render(player,
                getConfig().getString("join-title.subtitle", "&fДобро пожаловать!")));
        long fadeIn = getConfig().getLong("join-title.fade-in-ticks", 10L);
        long stay = getConfig().getLong("join-title.stay-ticks", 70L);
        long fadeOut = getConfig().getLong("join-title.fade-out-ticks", 20L);
        player.showTitle(Title.title(title, subtitle, Title.Times.times(
                Duration.ofMillis(fadeIn * 50L), Duration.ofMillis(stay * 50L), Duration.ofMillis(fadeOut * 50L))));
    }

    private Component component(String legacySectionText) {
        return LegacyComponentSerializer.legacySection().deserialize(legacySectionText == null ? "" : legacySectionText);
    }

    /**
     * Сервер только для Bedrock. Java-игрок = зашёл напрямую на Purpur, мимо Geyser/Floodgate.
     * Если Floodgate на сервере не стоит, отличить Java от Bedrock нельзя — никого не кикаем.
     */
    private boolean isJavaPlayer(Player player) {
        if (!getConfig().getBoolean("bedrock-only.enabled", true)) {
            return false;
        }
        if (player.hasPermission("voidtab.bedrock-bypass")) {
            return false;
        }
        if (!hooks.hasFloodgate()) {
            if (!warnedNoFloodgate) {
                warnedNoFloodgate = true;
                getLogger().warning("bedrock-only включён, но Floodgate не найден: Java-игроки НЕ кикаются. Поставьте Floodgate на этот сервер.");
            }
            return false;
        }
        return !hooks.isBedrock(player.getUniqueId());
    }

    /**
     * Перед следующим сообщением в чат обновляем кэш LuckPerms: ChatEx сам
     * читает префикс из LuckPerms на каждое сообщение, а эта инвалидация не
     * даёт ему и скорборду залипнуть на старом значении.
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onChatRefreshLuckPerms(AsyncPlayerChatEvent event) {
        hooks.refreshLuckPermsMeta(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        sidebar.remove(event.getPlayer().getUniqueId());
        nametag.remove(event.getPlayer());
    }

    // ------------------------------------------------------------- команды
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String prefix = TextUtil.color(getConfig().getString("messages.prefix", "&#FFB340VoidPE &8» &f"));
        if (args.length >= 1 && args[0].equalsIgnoreCase("debug")) {
            if (!sender.hasPermission("voidtab.admin")) {
                sender.sendMessage(prefix + TextUtil.color(getConfig().getString("messages.no-permission", "&cНет прав.")));
                return true;
            }
            Player target = args.length >= 2 ? Bukkit.getPlayerExact(args[1])
                    : (sender instanceof Player ? (Player) sender : null);
            if (target == null) {
                sender.sendMessage(prefix + "Использование: /voidtab debug [игрок]");
                return true;
            }
            java.util.UUID tu = target.getUniqueId();
            hooks.refreshLuckPermsMeta(tu);
            sender.sendMessage(prefix + "VoidTab v" + getDescription().getVersion()
                    + " | Floodgate: " + (hooks.hasFloodgate() ? "&aда" : "&cНЕТ"));
            sender.sendMessage(prefix + "Игрок &f" + target.getName() + "&7: Floodgate OS=&b" + hooks.floodgateDeviceOs(tu)
                    + " &7| Geyser OS=&b" + hooks.geyserDeviceOs(tu)
                    + " &7| Ввод Floodgate=&b" + hooks.inputMode(tu)
                    + " &7| Ввод Geyser=&b" + hooks.geyserInputMode(tu)
                    + " &7| Модель=&b" + hooks.deviceModel(tu));
            sender.sendMessage(prefix + "Итог: &b" + hooks.deviceOs(tu) + " &7-> значок &b" + sidebar.deviceIconName(target)
                    + " &7(" + sidebar.deviceName(target) + ") | Префикс ника: '" + nametag.appliedPrefix(tu) + "'");
            String[] dbgLp = hooks.hasLuckPerms() ? hooks.luckPermsGroup(tu) : null;
            String dbgRaw = sidebar.groupRaw(target);
            sender.sendMessage(prefix + "Группа: LuckPerms=&b" + (dbgLp == null ? "null (не подключён/игрок не загружен)" : dbgLp[0])
                    + " &7| groupRaw=&b" + dbgRaw
                    + " &7| icons." + dbgRaw + "=&b" + getConfig().getString("icons." + dbgRaw, "<нет в конфиге>")
                    + " &7| rank-icon=&b" + getConfig().getBoolean("nametag.rank-icon", true));
            sender.sendMessage(prefix + "LuckPerms сейчас: префикс=&b'" + hooks.luckPermsPrefix(tu)
                    + "&7' | титул/суффикс=&b'" + hooks.luckPermsSuffix(tu) + "&7'");
            sender.sendMessage(prefix + "Все онлайн:");
            for (Player online : Bukkit.getOnlinePlayers()) {
                sender.sendMessage(prefix + "&7- &f" + online.getName() + " &7-> &b" + sidebar.deviceIconName(online)
                        + " &7(" + hooks.deviceOs(online.getUniqueId()) + "/" + hooks.inputMode(online.getUniqueId()) + ")");
            }
            return true;
        }
        if (args.length >= 1 && args[0].equalsIgnoreCase("refresh")) {
            Player target = null;
            if (args.length >= 2) {
                if (!sender.hasPermission("voidtab.admin")) {
                    sender.sendMessage(prefix + TextUtil.color(getConfig().getString("messages.no-permission", "&cНет прав.")));
                    return true;
                }
                target = Bukkit.getPlayerExact(args[1]);
                if (target == null) {
                    sender.sendMessage(prefix + TextUtil.color(getConfig().getString("messages.player-not-found", "&cИгрок не найден.")));
                    return true;
                }
            } else if (sender instanceof Player) {
                target = (Player) sender;
            }
            hooks.refreshLuckPermsMeta();
            if (target != null) {
                sidebar.update(target, true);
                nametag.apply(target, true);
            } else {
                Bukkit.getOnlinePlayers().forEach(online -> {
                    sidebar.update(online, true);
                    nametag.apply(online, true);
                });
            }
            sender.sendMessage(prefix + TextUtil.color(getConfig().getString("messages.refreshed", "&aПрефикс, скорборд и ник обновлены.")));
            return true;
        }
        if (args.length >= 1 && args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("voidtab.admin")) {
                sender.sendMessage(prefix + TextUtil.color(getConfig().getString("messages.no-permission", "&cНет прав.")));
                return true;
            }
            reloadConfig();
            hooks.refreshLuckPermsMeta();
            startTask();
            Bukkit.getOnlinePlayers().forEach(online -> {
                sidebar.update(online, true);
                nametag.apply(online, true);
            });
            sender.sendMessage(prefix + TextUtil.color(getConfig().getString("messages.reloaded", "&aКонфиг перезагружен.")));
            return true;
        }
        if (args.length >= 1 && (args[0].equalsIgnoreCase("toggle") || args[0].equalsIgnoreCase("on") || args[0].equalsIgnoreCase("off"))) {
            Player target;
            if (args.length >= 2) {
                if (!sender.hasPermission("voidtab.admin")) {
                    sender.sendMessage(prefix + TextUtil.color(getConfig().getString("messages.no-permission", "&cНет прав.")));
                    return true;
                }
                target = Bukkit.getPlayerExact(args[1]);
                if (target == null) {
                    sender.sendMessage(prefix + TextUtil.color(getConfig().getString("messages.player-not-found", "&cИгрок не найден.")));
                    return true;
                }
            } else if (sender instanceof Player) {
                target = (Player) sender;
                if (!sender.hasPermission("voidtab.toggle")) {
                    sender.sendMessage(prefix + TextUtil.color(getConfig().getString("messages.no-permission", "&cНет прав.")));
                    return true;
                }
            } else {
                sender.sendMessage(prefix + "Использование: /voidtab <reload|toggle [игрок]>");
                return true;
            }
            boolean visible;
            if (args[0].equalsIgnoreCase("on")) {
                sidebar.show(target);
                visible = true;
            } else if (args[0].equalsIgnoreCase("off")) {
                sidebar.hide(target);
                visible = false;
            } else {
                visible = sidebar.toggle(target);
            }
            String key = visible ? "messages.shown" : "messages.hidden";
            String def = visible ? "&aСкорборд включён." : "&eСкорборд скрыт.";
            target.sendMessage(prefix + TextUtil.color(getConfig().getString(key, def)));
            return true;
        }
        sender.sendMessage(prefix + "Использование: /voidtab <reload|refresh [игрок]|debug [игрок]|toggle [игрок]|on|off>");
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> subs = new ArrayList<>();
            for (String sub : List.of("reload", "refresh", "debug", "toggle", "on", "off")) {
                if (sub.startsWith(args[0].toLowerCase(java.util.Locale.ROOT))) {
                    subs.add(sub);
                }
            }
            return subs;
        }
        if (args.length == 2 && List.of("refresh", "debug", "toggle", "on", "off").contains(args[0].toLowerCase(java.util.Locale.ROOT))) {
            List<String> names = new ArrayList<>();
            for (Player online : Bukkit.getOnlinePlayers()) {
                if (online.getName().toLowerCase(java.util.Locale.ROOT).startsWith(args[1].toLowerCase(java.util.Locale.ROOT))) {
                    names.add(online.getName());
                }
            }
            return names;
        }
        return List.of();
    }
}
