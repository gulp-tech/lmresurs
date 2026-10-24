package ru.voidpe.voidtab;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Statistic;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.io.File;
import java.io.IOException;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Компактный Bedrock-скорборд (сервер VoidPE — только Bedrock: Geyser + Floodgate).
 * Иконки — всегда глифы из VoidResourcePack, никаких Java-заглушек.
 * Строки скорборда — это префиксы команд (entries невидимы), поэтому:
 *  - нет мерцания при обновлении (меняем только изменившиеся строки);
 *  - иконка из ресурспака занимает ровно 1 символ и не раздувает таб.
 */
public final class SidebarManager {

    private static final String OBJECTIVE = "voidtab";
    private static final int MAX_LINES = 15;
    private static final char[] ENTRY_CODES = "0123456789abcde".toCharArray();

    private final VoidTab plugin;
    private final Map<UUID, List<String>> cache = new HashMap<>();
    private final Set<UUID> hidden = new HashSet<>();
    private File hiddenFile;

    public SidebarManager(VoidTab plugin) {
        this.plugin = plugin;
        loadHidden();
    }

    // ------------------------------------------------------------ жизненный цикл
    public void show(Player player) {
        hidden.remove(player.getUniqueId());
        saveHidden();
        update(player, true);
    }

    public void hide(Player player) {
        hidden.add(player.getUniqueId());
        saveHidden();
        cache.remove(player.getUniqueId());
        player.setScoreboard(Bukkit.getScoreboardManager().getNewScoreboard());
    }

    public boolean toggle(Player player) {
        if (hidden.contains(player.getUniqueId())) {
            show(player);
            return true;
        }
        hide(player);
        return false;
    }

    public boolean isHidden(UUID uuid) {
        return hidden.contains(uuid);
    }

    public void remove(UUID uuid) {
        cache.remove(uuid);
    }

    public void updateAll() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            update(player, false);
        }
    }

    // ------------------------------------------------------------ обновление
    public void update(Player player, boolean force) {
        if (!plugin.getConfig().getBoolean("scoreboard.enabled", true)) {
            return;
        }
        if (hidden.contains(player.getUniqueId())) {
            return;
        }

        List<String> lines = new ArrayList<>();
        for (String raw : plugin.getConfig().getStringList("scoreboard.lines")) {
            lines.add(render(player, raw));
            if (lines.size() >= MAX_LINES) {
                break;
            }
        }

        UUID uuid = player.getUniqueId();
        if (!force && lines.equals(cache.get(uuid))) {
            return; // ничего не изменилось — не трогаем скорборд (нет мерцания)
        }
        cache.put(uuid, lines);

        Scoreboard board = player.getScoreboard();
        if (board.getObjective(OBJECTIVE) == null) {
            board = Bukkit.getScoreboardManager().getNewScoreboard();
            player.setScoreboard(board);
        }
        Objective objective = board.getObjective(OBJECTIVE);
        if (objective == null) {
            objective = board.registerNewObjective(OBJECTIVE, Criteria.DUMMY, component(renderTitle(player)));
            objective.setDisplaySlot(DisplaySlot.SIDEBAR);
        } else {
            objective.displayName(component(renderTitle(player)));
        }

        int total = lines.size();
        for (int i = 0; i < MAX_LINES; i++) {
            String entry = "§" + ENTRY_CODES[i] + "§r";
            Team team = board.getTeam("ft" + i);
            if (team == null) {
                team = board.registerNewTeam("ft" + i);
                team.addEntry(entry);
            }
            if (i < total) {
                team.prefix(component(lines.get(i)));
                objective.getScore(entry).setScore(total - i);
            } else {
                team.prefix(Component.empty());
                board.resetScores(entry);
            }
        }
    }

    /** Титул игрока (суффикс LuckPerms из кейса титулов) без начального пробела. */
    public String titleName(Player player) {
        if (plugin.hooks().hasLuckPerms()) {
            String suffix = plugin.hooks().luckPermsSuffix(player.getUniqueId());
            if (suffix != null && !suffix.isBlank()) {
                return suffix.trim();
            }
        }
        return plugin.getConfig().getString("defaults.no-title", "&7Нет");
    }

    private String renderTitle(Player player) {
        // Заголовок — маленькая картинка VoidPE из ресурспака: полоса глифов \uE010..\uE017,
        // высота ровно одна строка, как обычный текст, только текстурой.
        if (plugin.getConfig().getBoolean("scoreboard.title-logo.enabled", true)) {
            try {
                int start = Integer.parseInt(
                        plugin.getConfig().getString("scoreboard.title-logo.start-code", "E010").trim(), 16);
                int count = Math.max(1, Math.min(12,
                        plugin.getConfig().getInt("scoreboard.title-logo.glyphs", 8)));
                StringBuilder logo = new StringBuilder();
                for (int i = 0; i < count; i++) {
                    logo.append(new String(Character.toChars(start + i)));
                }
                return logo.toString();
            } catch (IllegalArgumentException ignored) {
                // битый конфиг — упадём на текстовый заголовок ниже
            }
        }
        return render(player, plugin.getConfig().getString("scoreboard.title", "VoidPE"));
    }

    // ------------------------------------------------------------ рендер строки
    String render(Player player, String raw) {
        Hooks hooks = plugin.hooks();
        String text = raw;

        // иконки: {icon:coin} и т.д.
        for (String name : plugin.getConfig().getConfigurationSection("icons") == null
                ? Set.<String>of() : plugin.getConfig().getConfigurationSection("icons").getKeys(false)) {
            String token = "{icon:" + name + "}";
            if (text.contains(token)) {
                text = text.replace(token, iconChar(name));
            }
        }

        String groupRaw = "default";
        String groupDisplay = null;
        String[] lp = hooks.hasLuckPerms() ? hooks.luckPermsGroup(player.getUniqueId()) : null;
        if (lp != null) {
            groupRaw = lp[0];
            groupDisplay = lp[1];
        } else {
            String vg = hooks.vaultGroup(player);
            if (vg != null && !vg.isBlank()) {
                groupRaw = vg;
            }
        }
        String group = null;
        // Префикс из LuckPerms — главный источник: поменял в LP, поменялось везде.
        if (plugin.getConfig().getBoolean("groups.use-luckperms-prefix", true) && hooks.hasLuckPerms()) {
            String lpPrefix = hooks.luckPermsPrefix(player.getUniqueId());
            if (lpPrefix != null && !lpPrefix.isBlank()) {
                group = lpPrefix.trim();
            }
        }
        if (group == null) {
            group = plugin.getConfig().getString("groups." + groupRaw.toLowerCase(Locale.ROOT) + ".display");
        }
        if (group == null && plugin.getConfig().getBoolean("groups.use-luckperms-display-name", false)) {
            group = groupDisplay;
        }
        if (group == null) {
            group = groupRaw.isEmpty() ? "Игрок"
                    : Character.toUpperCase(groupRaw.charAt(0)) + groupRaw.substring(1);
        }

        double balance = hooks.hasEconomy() ? hooks.balance(player) : -1;
        String coins;
        if (balance < 0) {
            coins = plugin.getConfig().getString("defaults.coins", "0");
        } else if (plugin.getConfig().getBoolean("coins.use-vault-format", false) && hooks.economyFormat(balance) != null) {
            coins = hooks.economyFormat(balance);
        } else {
            coins = formatNumber(balance,
                    plugin.getConfig().getInt("coins.decimals", 0),
                    plugin.getConfig().getBoolean("coins.grouping", true));
        }

        int points = hooks.hasPlayerPoints() ? hooks.points(player.getUniqueId()) : -1;
        String shards = points < 0
                ? plugin.getConfig().getString("defaults.shards", "0")
                : formatNumber(points, 0, plugin.getConfig().getBoolean("shards.grouping", true));

        int kills = stat(player, Statistic.PLAYER_KILLS);
        int deaths = stat(player, Statistic.DEATHS);
        String kd = deaths == 0
                ? formatNumber(kills, 2, false)
                : formatNumber((double) kills / deaths, 2, false);

        long playTicks = stat(player, Statistic.PLAY_ONE_MINUTE);
        long totalSeconds = playTicks / 20L;
        long hours = totalSeconds / 3600L;
        long minutes = (totalSeconds % 3600L) / 60L;
        String playtime = plugin.getConfig().getString("playtime.format", "{h}ч {m}м")
                .replace("{h}", String.valueOf(hours))
                .replace("{m}", String.valueOf(minutes));

        text = text.replace("{device_icon}", deviceIconChar(player));
        text = text.replace("{device}", deviceName(player));

        Map<String, String> values = new HashMap<>();
        values.put("player", player.getName());
        values.put("group", group);
        values.put("group_icon", rankIconChar(player));
        values.put("group_raw", groupRaw);
        values.put("title", titleName(player));
        values.put("coins", coins);
        values.put("coins_raw", balance < 0 ? "0" : String.valueOf((long) balance));
        values.put("shards", shards);
        values.put("kills", String.valueOf(kills));
        values.put("deaths", String.valueOf(deaths));
        values.put("kd", kd);
        values.put("online", String.valueOf(Bukkit.getOnlinePlayers().size()));
        values.put("max", String.valueOf(Bukkit.getMaxPlayers()));
        values.put("ping", String.valueOf(player.getPing()));
        values.put("playtime_hours", String.valueOf(hours));
        values.put("playtime_minutes", String.valueOf(totalSeconds / 60L));
        values.put("playtime", playtime);
        values.put("world", player.getWorld().getName());

        for (Map.Entry<String, String> e : values.entrySet()) {
            text = text.replace("{" + e.getKey() + "}", e.getValue());
        }

        if (plugin.getConfig().getBoolean("hooks.placeholderapi", true)) {
            text = hooks.applyPlaceholders(player, text);
        }
        return TextUtil.color(text);
    }

    /**
     * Имя иконки устройства. Источники по убыванию надёжности:
     * 1) DeviceModel от клиента — самый точный признак (Xbox шлёт «Xbox», ПК — «Windows…»);
     * 2) DeviceOs из Floodgate/Geyser (GOOGLE, IOS, UWP, PS4, NX, XBOX, LINUX, DEDICATED…);
     * 3) режим ввода (TOUCH / KEYBOARD_MOUSE / CONTROLLER / VR) — уточняет картинку;
     * 4) UI-профиль (POCKET — мобильный интерфейс, CLASSIC — ПК/консоль).
     * Всё берётся одним кэшированным снимком: Hooks.device(uuid).
     */
    public String deviceIconName(Player player) {
        // Ручное переопределение в config.yml (nametag.device-overrides.<ник>) — выше всего:
        // если клиент врёт про своё устройство, админ чинит это одной строкой.
        String forced = plugin.getConfig().getString("nametag.device-overrides." + player.getName(), "");
        if (forced != null && !forced.isBlank()) {
            return forced.trim().toLowerCase(java.util.Locale.ROOT);
        }
        Hooks.Device d = plugin.hooks().device(player.getUniqueId());
        boolean touchIsMobile = plugin.getConfig().getBoolean("nametag.device-detect.touch-is-mobile", true);
        return deviceIconName(d, touchIsMobile);
    }

    /**
     * Значок устройства по снимку клиента (без Bukkit — удобно для тестов).
     * <p>Порядок важен. Сначала консоли и VR: они узнаются по модели/ОС однозначно.
     * Затем мобильные признаки: телефон нередко присылает {@code deviceOs=WINDOWS}
     * (сторонние клиенты, «Windows-режим» в эмуляторах), но TOUCH-ввод и POCKET-интерфейс
     * выдают его надёжнее строки ОС. И только потом — настольные ОС.</p>
     */
    static String deviceIconName(Hooks.Device d) {
        return deviceIconName(d, true);
    }

    static String deviceIconName(Hooks.Device d, boolean touchIsMobile) {
        String os = d.os == null ? "UNKNOWN" : d.os.toUpperCase(java.util.Locale.ROOT);
        String input = d.input == null ? "UNKNOWN" : d.input.toUpperCase(java.util.Locale.ROOT);
        String ui = d.ui == null ? "UNKNOWN" : d.ui.toUpperCase(java.util.Locale.ROOT);
        boolean touch = touchIsMobile && input.contains("TOUCH");
        boolean keyboard = input.contains("KEYBOARD");
        boolean controller = input.contains("CONTROLLER");
        boolean mobileUi = touchIsMobile && ui.contains("POCKET");

        // 1. Консоли, ТВ и VR.
        if (d.modelContains("XBOX") || os.contains("XBOX")) {
            return "xbox";
        }
        if (d.modelContains("PLAYSTATION") || d.modelContains("PS4") || d.modelContains("PS5")
                || os.contains("PLAYSTATION") || os.contains("PS4") || os.contains("PS5")
                || os.contains("SONY")) {
            return "playstation";
        }
        if (d.modelContains("SWITCH") || d.modelContains("NINTENDO")
                || os.equals("NX") || os.contains("SWITCH") || os.contains("NINTENDO")) {
            return "switch";
        }
        if (os.contains("TVOS")) {
            return "tv";
        }
        if (input.contains("VR") || os.contains("GEARVR") || os.contains("HOLOLENS")) {
            return "vr";
        }

        // 2. Мобильные устройства.
        boolean mobileOs = Hooks.isMobileOs(os);
        boolean phoneModel = isPhoneModel(d);
        boolean appleModel = d.modelContains("IPHONE") || d.modelContains("IPAD") || d.modelContains("IPOD");
        if (mobileOs || phoneModel || appleModel || touch || mobileUi) {
            if (os.equals("IOS") || os.contains("IPHONE") || os.contains("IPAD") || appleModel) {
                return "apple";
            }
            if (os.contains("AMAZON") || os.contains("FIRE")) {
                return "amazon";
            }
            // Планшет/телефон с внешней клавиатурой и мышью.
            if (keyboard && !touch && !mobileUi && (mobileOs || phoneModel)) {
                return "kbmouse";
            }
            return "android";
        }

        // 3. Настольные ОС.
        if (d.modelContains("MACBOOK") || d.modelContains("IMAC") || d.modelContains("MAC ")
                || os.contains("OSX") || os.contains("MAC")) {
            return "mac";
        }
        if (d.modelContains("WINDOWS") || Hooks.isWindowsOs(os)) {
            return "windows";
        }
        if (os.contains("LINUX")) {
            return "linux";
        }
        if (os.contains("DEDICATED")) {
            return "dedicated";
        }

        // 4. Ничего не распознали — уточняем по режиму ввода.
        if (keyboard) {
            return "kbmouse";
        }
        if (controller) {
            return "controller";
        }
        return "device";
    }

    /** Модель похожа на телефон/планшет: так ловятся клиенты, которые врут про ОС. */
    private static boolean isPhoneModel(Hooks.Device d) {
        String[] hints = {
                "IPHONE", "IPAD", "IPOD", "ANDROID", "SAMSUNG", "SM-", "GT-", "PIXEL", "NEXUS",
                "REDMI", "POCO", "XIAOMI", "HUAWEI", "HONOR", "ONEPLUS", "REALME", "VIVO",
                "OPPO", "MOTOROLA", "MOTO", "NOKIA", "XPERIA", "INFINIX", "TECNO", "ONEPLUS"
        };
        for (String hint : hints) {
            if (d.modelContains(hint)) {
                return true;
            }
        }
        return false;
    }

    /** Человекочитаемое устройство: Windows / Android / iOS / Xbox / ... (тексты — nametag.device-names). */
    public String deviceName(Player player) {
        String icon = deviceIconName(player);
        String text = plugin.getConfig().getString("nametag.device-names." + icon, "");
        if (text != null && !text.isBlank()) {
            return text;
        }
        switch (icon) {
            case "windows": return "Windows";
            case "android": return "Android";
            case "apple": return "iOS";
            case "mac": return "Mac OS";
            case "amazon": return "Amazon Fire";
            case "xbox": return "Xbox";
            case "playstation": return "PlayStation";
            case "switch": return "Nintendo Switch";
            case "vr": return "VR";
            case "tv": return "TV";
            case "controller": return "Геймпад";
            case "kbmouse": return "Клавиатура+мышь";
            case "linux": return "Linux";
            case "dedicated": return "Сервер";
            default: return "Устройство";
        }
    }

    /** Глиф устройства из ресурспака для ника/скорборда. */
    public String deviceIconChar(Player player) {
        return iconChar(deviceIconName(player));
    }

    /** Сырое имя группы игрока (LuckPerms primary group, иначе Vault, иначе default). */
    public String groupRaw(Player player) {
        Hooks hooks = plugin.hooks();
        String[] lp = hooks.hasLuckPerms() ? hooks.luckPermsGroup(player.getUniqueId()) : null;
        if (lp != null && lp[0] != null && !lp[0].isBlank()) {
            return lp[0].toLowerCase(Locale.ROOT);
        }
        String vg = hooks.vaultGroup(player);
        if (vg != null && !vg.isBlank()) {
            return vg.toLowerCase(Locale.ROOT);
        }
        return "default";
    }

    /** Кастомный значок привилегии из ресурспака (иконка названа как группа: icons.<группа>). */
    public String rankIconChar(Player player) {
        return iconChar(groupRaw(player));
    }

    /** Название привилегии для неймтега: groups.<группа>.display, иначе как в скорборде. */
    public String rankDisplayName(Player player) {
        if (plugin.getConfig().getBoolean("groups.use-luckperms-prefix", true) && plugin.hooks().hasLuckPerms()) {
            String lpPrefix = plugin.hooks().luckPermsPrefix(player.getUniqueId());
            if (lpPrefix != null && !lpPrefix.isBlank()) {
                return lpPrefix.trim();
            }
        }
        String raw = groupRaw(player);
        String display = plugin.getConfig().getString("groups." + raw + ".display");
        if (display == null && plugin.getConfig().getBoolean("groups.use-luckperms-display-name", false)) {
            String[] lp = plugin.hooks().hasLuckPerms() ? plugin.hooks().luckPermsGroup(player.getUniqueId()) : null;
            if (lp != null) display = lp[1];
        }
        if (display == null || display.isBlank()) {
            display = raw.isEmpty() ? "Игрок" : Character.toUpperCase(raw.charAt(0)) + raw.substring(1);
        }
        return display;
    }

    /** Иконка — всегда глиф из Bedrock-ресурспака: сервер только для Bedrock. */
    private String iconChar(String name) {
        String code = plugin.getConfig().getString("icons." + name, "");
        if (code == null || code.isBlank()) {
            return "";
        }
        try {
            int cp = Integer.parseInt(code.trim(), 16);
            return new String(Character.toChars(cp));
        } catch (IllegalArgumentException e) {
            return "";
        }
    }

    // ------------------------------------------------------------ утилиты
    private int stat(Player player, Statistic statistic) {
        try {
            return player.getStatistic(statistic);
        } catch (Throwable t) {
            return 0;
        }
    }

    private String formatNumber(double value, int decimals, boolean grouping) {
        DecimalFormatSymbols symbols = new DecimalFormatSymbols(Locale.ROOT);
        if (grouping) {
            symbols.setGroupingSeparator(
                    plugin.getConfig().getString("numbers.grouping-separator", " ").charAt(0));
        }
        StringBuilder pattern = new StringBuilder(grouping ? "#,##0" : "0");
        if (decimals > 0) {
            pattern.append('.');
            pattern.append("0".repeat(decimals));
        }
        return new DecimalFormat(pattern.toString(), symbols).format(value);
    }

    private Component component(String legacySectionText) {
        return LegacyComponentSerializer.legacySection().deserialize(
                legacySectionText == null ? "" : legacySectionText);
    }

    // ------------------------------------------------------------ скрытые игроки
    private void loadHidden() {
        try {
            hiddenFile = new File(plugin.getDataFolder(), "hidden.yml");
            if (hiddenFile.exists()) {
                YamlConfiguration cfg = YamlConfiguration.loadConfiguration(hiddenFile);
                for (String s : cfg.getStringList("hidden")) {
                    try {
                        hidden.add(UUID.fromString(s));
                    } catch (IllegalArgumentException ignored) {
                    }
                }
            }
        } catch (Throwable t) {
            plugin.getLogger().warning("Не удалось загрузить hidden.yml: " + t.getMessage());
        }
    }

    private void saveHidden() {
        if (!plugin.getConfig().getBoolean("scoreboard.remember-hidden", true)) {
            return;
        }
        try {
            if (hiddenFile == null) {
                hiddenFile = new File(plugin.getDataFolder(), "hidden.yml");
            }
            YamlConfiguration cfg = new YamlConfiguration();
            List<String> list = new ArrayList<>();
            for (UUID u : hidden) {
                list.add(u.toString());
            }
            cfg.set("hidden", list);
            cfg.save(hiddenFile);
        } catch (IOException e) {
            plugin.getLogger().warning("Не удалось сохранить hidden.yml: " + e.getMessage());
        }
    }
}
