package ru.voidpe.quiet;

import org.bukkit.Bukkit;
import org.bukkit.GameRule;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * VoidQuiet — тишина в чате сервера VoidPE.
 *
 * <p>Скрывает три вида сообщений, каждое отдельно в config.yml:</p>
 * <ul>
 *   <li><b>вход</b> — «X зашёл на сервер»;</li>
 *   <li><b>выход</b> — «X покинул игру»;</li>
 *   <li><b>смерти и убийства</b> — «X погиб», «X убит Y».</li>
 * </ul>
 *
 * <p>Работает на уровне событий Bukkit ({@code setJoinMessage(null)} и т.д.), поэтому
 * не зависит от настроек Essentials и от порядка загрузки плагинов. Дополнительно
 * выключает ванильный геймрул {@code showDeathMessages} — часть сообщений о смерти
 * отправляет само ядро, мимо плагинов.</p>
 *
 * <p>Вместо скрытия можно задать своё сообщение — в конфиге у каждого пункта есть
 * {@code message}; пустая строка означает «скрыть совсем».</p>
 */
public final class VoidQuiet extends JavaPlugin implements CommandExecutor, TabCompleter, QuietHost {

    private static final GameRule<Boolean> SHOW_DEATH_MESSAGES = GameRule.SHOW_DEATH_MESSAGES;

    private volatile Settings settings;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        settings = new Settings(getConfig());
        getServer().getPluginManager().registerEvents(new QuietListener(this), this);
        if (getCommand("voidquiet") != null) {
            getCommand("voidquiet").setExecutor(this);
            getCommand("voidquiet").setTabCompleter(this);
        }
        if (settings.disableDeathGamerule) {
            applyDeathGamerule();
        }
        getLogger().info("VoidQuiet включён: " + settings.summary());
    }

    @Override
    public void onDisable() {
        settings = null;
    }

    @Override
    public Settings settings() {
        return settings;
    }

    /**
     * Выключает ванильные сообщения о смерти во всех загруженных мирах.
     * Вызывается при старте, после reload и при загрузке нового мира.
     */
    @Override
    public List<String> applyDeathGamerule() {
        Settings current = settings;
        List<String> done = new ArrayList<>();
        if (current == null || !current.disableDeathGamerule || !current.enabled) {
            return done;
        }
        for (World world : Bukkit.getWorlds()) {
            if (current.isWorldDisabled(world.getName())) {
                continue;
            }
            try {
                if (!Boolean.FALSE.equals(world.getGameRuleValue(SHOW_DEATH_MESSAGES))) {
                    world.setGameRule(SHOW_DEATH_MESSAGES, Boolean.FALSE);
                }
                done.add(world.getName());
            } catch (Throwable t) {
                getLogger().warning("Не удалось выключить showDeathMessages в мире "
                        + world.getName() + ": " + t.getMessage());
            }
        }
        return done;
    }

    // ------------------------------------------------------------------
    // Команда /voidquiet
    // ------------------------------------------------------------------

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        Settings current = settings;
        if (current == null) {
            sender.sendMessage(color("&cКонфиг не загружен."));
            return true;
        }
        if (!sender.hasPermission("voidquiet.use")) {
            sender.sendMessage(color(current.prefix + current.noPermission));
            return true;
        }
        String sub = args.length == 0 ? "status" : args[0].toLowerCase(Locale.ROOT);
        if (sub.equals("reload") || sub.equals("test")) {
            if (!sender.hasPermission("voidquiet.admin")) {
                sender.sendMessage(color(current.prefix + current.noPermission));
                return true;
            }
            reloadConfig();
            settings = new Settings(getConfig());
            Settings fresh = settings;
            sender.sendMessage(color(fresh.prefix + fresh.reloaded));
            if (fresh.disableDeathGamerule) {
                List<String> worlds = applyDeathGamerule();
                sender.sendMessage(color(fresh.prefix + fresh.gameruleDone
                        .replace("%worlds%", worlds.isEmpty() ? "нет миров" : String.join(", ", worlds))));
            }
            getLogger().info("Конфиг перезагружен: " + fresh.summary());
            return true;
        }
        // status
        sender.sendMessage(color(current.prefix + "&fVoidQuiet &7v" + getDescription().getVersion()
                + (current.enabled ? " &8| &aактивен" : " &8| &cвыключен")));
        sender.sendMessage(color("&7Вход: " + state(current.enabled && current.hideJoin, current.joinMessage)
                + " &8| &7Выход: " + state(current.enabled && current.hideQuit, current.quitMessage)
                + " &8| &7Смерти: " + state(current.enabled && current.hideDeath, current.deathMessage)));
        sender.sendMessage(color("&7Геймрул showDeathMessages выключен: "
                + (current.enabled && current.disableDeathGamerule ? "&aда" : "&cнет")));
        if (!current.disabledWorlds.isEmpty()) {
            sender.sendMessage(color("&7Исключения (миры): &e" + String.join(", ", current.disabledWorlds)));
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length != 1) {
            return List.of();
        }
        String typed = args[0].toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String option : List.of("status", "reload")) {
            if (option.startsWith(typed)) {
                out.add(option);
            }
        }
        return out;
    }

    private static String state(boolean hidden, String message) {
        if (!hidden) {
            return "&cпоказывается";
        }
        return message == null || message.isEmpty() ? "&aскрыто" : "&eсвоё сообщение";
    }

    /** Перевод &-кодов и &#RRGGBB в §-формат. */
    public static String color(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == '&' && i + 7 < text.length() && text.charAt(i + 1) == '#') {
                String hex = text.substring(i + 2, i + 8);
                out.append('§').append('x');
                for (char digit : hex.toCharArray()) {
                    out.append('§').append(digit);
                }
                i += 7;
            } else if (ch == '&') {
                out.append('§');
            } else {
                out.append(ch);
            }
        }
        return out.toString();
    }

    /**
     * Подстановка плейсхолдеров. Отдельным статическим методом — чтобы формат
     * можно было проверять тестами без запуска сервера.
     */
    static String format(String template, String player, String username, int online,
                         String killer, String weapon, String world) {
        if (template == null || template.isEmpty()) {
            return "";
        }
        return template
                .replace("{player}", player == null ? "" : player)
                .replace("{username}", username == null ? "" : username)
                .replace("{online}", String.valueOf(online))
                .replace("{killer}", killer == null ? "" : killer)
                .replace("{weapon}", weapon == null ? "" : weapon)
                .replace("{world}", world == null ? "" : world);
    }

    // ------------------------------------------------------------------
    // Настройки
    // ------------------------------------------------------------------

    /** Снимок config.yml на момент загрузки. */
    public static final class Settings {
        final boolean enabled;
        final boolean hideJoin;
        final String joinMessage;
        final boolean hideQuit;
        final String quitMessage;
        final boolean hideDeath;
        final String deathMessage;
        final boolean disableDeathGamerule;
        final java.util.Set<String> disabledWorlds;
        final String prefix;
        final String reloaded;
        final String gameruleDone;
        final String noPermission;

        Settings(org.bukkit.configuration.file.FileConfiguration config) {
            this.enabled = config.getBoolean("enabled", true);
            this.hideJoin = config.getBoolean("join.hide", true);
            this.joinMessage = config.getString("join.message", "");
            this.hideQuit = config.getBoolean("quit.hide", true);
            this.quitMessage = config.getString("quit.message", "");
            this.hideDeath = config.getBoolean("death.hide", true);
            this.deathMessage = config.getString("death.message", "");
            this.disableDeathGamerule = config.getBoolean("death.disable-gamerule", true);
            java.util.Set<String> worlds = new java.util.LinkedHashSet<>();
            for (String name : config.getStringList("worlds.disabled")) {
                worlds.add(name.toLowerCase(Locale.ROOT));
            }
            this.disabledWorlds = java.util.Collections.unmodifiableSet(worlds);
            this.prefix = config.getString("messages.prefix", "");
            this.reloaded = config.getString("messages.reloaded", "&aКонфиг перезагружен.");
            this.gameruleDone = config.getString("messages.gamerule-done",
                    "&7Геймрул showDeathMessages выключен в мирах: &e%worlds%");
            this.noPermission = config.getString("messages.no-permission", "&cНедостаточно прав.");
        }

        boolean isWorldDisabled(String world) {
            return !disabledWorlds.isEmpty() && disabledWorlds.contains(world.toLowerCase(Locale.ROOT));
        }

        String summary() {
            return "вход " + (hideJoin ? "скрыт" : "виден")
                    + ", выход " + (hideQuit ? "скрыт" : "виден")
                    + ", смерти " + (hideDeath ? "скрыты" : "видны")
                    + (disableDeathGamerule ? ", геймрул выключен" : "");
        }
    }
}
