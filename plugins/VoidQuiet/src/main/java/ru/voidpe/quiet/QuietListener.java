package ru.voidpe.quiet;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.inventory.ItemStack;

/**
 * Глушит сообщения входа, выхода и смерти.
 *
 * <p>Приоритет HIGHEST: работает после обычных плагинов (Essentials, AuthMe и т.п.),
 * которые тоже правят эти сообщения, поэтому перебивает их результат. На MONITOR
 * намеренно не поднимаемся — там события только читают, менять их нельзя.</p>
 */
public final class QuietListener implements Listener {

    private final QuietHost plugin;

    public QuietListener(QuietHost plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onJoin(PlayerJoinEvent event) {
        VoidQuiet.Settings settings = plugin.settings();
        if (!active(settings) || !settings.hideJoin) {
            return;
        }
        Player player = event.getPlayer();
        if (settings.isWorldDisabled(player.getWorld().getName())) {
            return;
        }
        event.setJoinMessage(custom(settings.joinMessage, player, null, null));
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onQuit(PlayerQuitEvent event) {
        VoidQuiet.Settings settings = plugin.settings();
        if (!active(settings) || !settings.hideQuit) {
            return;
        }
        Player player = event.getPlayer();
        if (settings.isWorldDisabled(player.getWorld().getName())) {
            return;
        }
        event.setQuitMessage(custom(settings.quitMessage, player, null, null));
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDeath(PlayerDeathEvent event) {
        VoidQuiet.Settings settings = plugin.settings();
        if (!active(settings) || !settings.hideDeath) {
            return;
        }
        Player player = event.getEntity();
        if (settings.isWorldDisabled(player.getWorld().getName())) {
            return;
        }
        Player killer = player.getKiller();
        String weapon = "";
        if (killer != null) {
            try {
                ItemStack hand = killer.getInventory().getItemInMainHand();
                if (hand != null && !hand.getType().isAir()) {
                    weapon = prettyName(hand.getType().name());
                }
            } catch (Throwable ignored) {
                // без оружия — не критично
            }
        }
        event.setDeathMessage(custom(settings.deathMessage, player, killer, weapon));
    }

    /** Новый мир загрузился — сразу выключаем в нём ванильные сообщения о смерти. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldLoad(WorldLoadEvent event) {
        plugin.applyDeathGamerule();
    }

    private static boolean active(VoidQuiet.Settings settings) {
        return settings != null && settings.enabled;
    }

    /**
     * Готовое сообщение: пустой шаблон — скрыть совсем (null),
     * иначе подставленные плейсхолдеры.
     */
    private static String custom(String template, Player player, Player killer, String weapon) {
        if (template == null || template.isEmpty()) {
            return null;
        }
        int online = Bukkit.getOnlinePlayers().size();
        String text = VoidQuiet.format(
                template,
                player.getDisplayName(),
                player.getName(),
                online,
                killer == null ? "" : killer.getDisplayName(),
                weapon,
                player.getWorld().getName());
        return VoidQuiet.color(text);
    }

    /** DIAMOND_SWORD -> Diamond Sword. */
    static String prettyName(String materialName) {
        String[] parts = materialName.toLowerCase(java.util.Locale.ROOT).split("_");
        StringBuilder out = new StringBuilder();
        for (String part : parts) {
            if (part.isEmpty()) {
                continue;
            }
            if (out.length() > 0) {
                out.append(' ');
            }
            out.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return out.toString();
    }
}
