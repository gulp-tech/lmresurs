package ru.voidpe.voidtab;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Оформление НИКА над головой (не скорборд!):
 *  - перед ником значок устройства из Floodgate (Windows / Android / iPhone / другое);
 *  - под ником здоровье (красное сердечко + число от самой игры).
 *
 * Почему команды дублируются на все борды: у каждого игрока свой скорборд
 * сайдбара, и viewer видит неймтеги по СВОЕМУ борду — команд главного борда
 * он бы просто не увидел. Плюс для Bedrock через Geyser дополнительно
 * выставляются display-name и player-list-name игрока.
 * В невидимости ник и здоровье прячет сама игра.
 */
public final class NametagManager {

    private static final String HEALTH_OBJECTIVE = "faral_hp";

    private final VoidTab plugin;
    private final Map<UUID, String> appliedPrefix = new HashMap<>();

    public NametagManager(VoidTab plugin) {
        this.plugin = plugin;
    }

    /** Все борды, где должны жить неймтеги: главный + персональный борд каждого игрока. */
    private Set<Scoreboard> allBoards() {
        Set<Scoreboard> boards = new HashSet<>();
        boards.add(Bukkit.getScoreboardManager().getMainScoreboard());
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            boards.add(viewer.getScoreboard());
        }
        return boards;
    }

    private void ensureHealthObjective(Scoreboard board) {
        if (!plugin.getConfig().getBoolean("nametag.health-below-name", true)) {
            return;
        }
        Objective objective = board.getObjective(HEALTH_OBJECTIVE);
        if (objective == null) {
            objective = board.registerNewObjective(HEALTH_OBJECTIVE, Criteria.HEALTH,
                    component(TextUtil.color(plugin.getConfig().getString("nametag.health-suffix", "&c❤"))));
            objective.setDisplaySlot(DisplaySlot.BELOW_NAME);
        }
    }

    public String appliedPrefix(UUID uuid) {
        return appliedPrefix.getOrDefault(uuid, "<нет>");
    }

    private String teamName(Player player) {
        return "ft" + player.getUniqueId().toString().replace("-", "").substring(0, 14);
    }

    public void apply(Player player) {
        apply(player, false);
    }

    /** Поставить/обновить значок устройства в нике игрока на всех бордах. */
    public void apply(Player player, boolean forceNames) {
        if (!plugin.getConfig().getBoolean("nametag.enabled", true)) {
            return;
        }
        // Порядок над головой: [устройство] [привилегия] Ник
        String prefix = "";
        if (plugin.getConfig().getBoolean("nametag.device-icon", true)) {
            String icon = plugin.sidebar().deviceIconChar(player);
            if (!icon.isBlank()) {
                prefix += icon + " ";
            }
        }
        if (plugin.getConfig().getBoolean("nametag.rank-icon", true)) {
            String rankIcon = plugin.sidebar().rankIconChar(player);
            if (!rankIcon.isBlank()) {
                prefix += rankIcon + " ";
            }
        }
        if (plugin.getConfig().getBoolean("nametag.rank-name", true)) {
            prefix += TextUtil.color(plugin.sidebar().rankDisplayName(player)) + " ";
        }
        // Титул (суффикс LP) в TAB после ника; учитываем в "changed", чтобы таб обновлялся при получении титула
        String titleSuffix = "";
        if (plugin.getConfig().getBoolean("nametag.title-in-tab", true) && plugin.hooks().hasLuckPerms()) {
            String suffix = plugin.hooks().luckPermsSuffix(player.getUniqueId());
            if (suffix != null && !suffix.isBlank()) {
                titleSuffix = suffix;
            }
        }
        boolean changed = !(prefix + "|" + titleSuffix).equals(appliedPrefix.get(player.getUniqueId()));
        appliedPrefix.put(player.getUniqueId(), prefix + "|" + titleSuffix);

        Component prefixComponent = component(
                TextUtil.color(plugin.getConfig().getString("nametag.prefix-color", "&f")) + prefix);
        for (Scoreboard board : allBoards()) {
            ensureHealthObjective(board);
            Team team = board.getTeam(teamName(player));
            if (team == null) {
                team = board.registerNewTeam(teamName(player));
            }
            if (!team.hasEntry(player.getName())) {
                team.addEntry(player.getName());
            }
            // ставим всегда: у позже зашедшего игрока новый борд, и без этого
            // его команды остались бы вообще без значка устройства
            team.prefix(prefixComponent);
        }

        // Bedrock (Geyser): неймтег над головой берётся из display/list имени — дублируем туда.
        // forceNames нужен после смены префикса LuckPerms: Geyser мог закэшировать
        // старое имя, поэтому периодически пересылаем display/list name принудительно.
        if ((changed || forceNames) && plugin.getConfig().getBoolean("nametag.use-display-name", true)) {
            Component name = Component.text(prefix).append(Component.text(player.getName()));
            player.displayName(name);
            String tabName = TextUtil.color(
                    plugin.getConfig().getString("nametag.prefix-color", "&f")) + prefix + player.getName()
                    + TextUtil.color(titleSuffix);
            if (plugin.getConfig().getBoolean("nametag.device-name-in-tab", true)) {
                tabName += TextUtil.color(" &7(" + plugin.sidebar().deviceName(player) + ")");
            }
            player.setPlayerListName(tabName);
        }
    }

    /** Игрок вышел — убрать его неймтег-команды со всех бордов. */
    public void remove(Player player) {
        appliedPrefix.remove(player.getUniqueId());
        for (Scoreboard board : allBoards()) {
            Team team = board.getTeam(teamName(player));
            if (team != null) {
                team.unregister();
            }
        }
    }

    private Component component(String legacySectionText) {
        return LegacyComponentSerializer.legacySection().deserialize(
                legacySectionText == null ? "" : legacySectionText);
    }
}
