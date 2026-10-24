package ru.voidpe.clans;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;

import java.util.Locale;
import java.util.UUID;

/**
 * Плейсхолдеры VoidClans для PlaceholderAPI (ChatEx, DeluxeMenus, скорборды и т.д.).
 *
 * Основной для чата: %voidclans_tag% — готовый тег вида [Название] с &r на конце,
 * чтобы цвет клана не перекрашивал ник и текст сообщения.
 */
public final class ClanPlaceholders extends PlaceholderExpansion {

    private final VoidClans plugin;

    public ClanPlaceholders(VoidClans plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getIdentifier() {
        return "voidclans";
    }

    @Override
    public String getAuthor() {
        return "VoidPE";
    }

    @Override
    public String getVersion() {
        return plugin.getDescription().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onRequest(OfflinePlayer player, String params) {
        if (player == null) {
            return "";
        }
        ClanService service = plugin.service();
        if (service == null) {
            return "";
        }
        ClanService.Clan clan = service.clanOf(player.getUniqueId());
        String key = params.toLowerCase(Locale.ROOT);

        if (clan == null) {
            return switch (key) {
                case "has_clan" -> "false";
                case "is_leader" -> "false";
                case "name", "clan", "clan_name" -> plugin.getConfig().getString("placeholders.no-clan", "");
                case "level", "members", "members_online", "max_members", "homes" -> "0";
                case "xp", "bank" -> "0";
                default -> "";
            };
        }

        return switch (key) {
            case "name", "clan", "clan_name" -> clan.name;
            case "tag" -> TextUtil.color(plugin.getConfig()
                    .getString("placeholders.tag-format", "&8[&#C86BFF{clan}&8]&r")
                    .replace("{clan}", clan.name));
            case "tag_raw" -> plugin.getConfig()
                    .getString("placeholders.tag-format", "&8[&#C86BFF{clan}&8]&r")
                    .replace("{clan}", clan.name);
            case "role" -> TextUtil.color(service.roleName(service.roleIdOf(clan, player.getUniqueId())));
            case "role_id" -> service.roleIdOf(clan, player.getUniqueId());
            case "leader" -> service.nameOf(clan.leader);
            case "level" -> String.valueOf(clan.level);
            case "xp" -> ClanService.fmt(clan.xp);
            case "bank" -> ClanService.fmt(clan.bank);
            case "members" -> String.valueOf(clan.members.size());
            case "members_online" -> String.valueOf(onlineMembers(clan));
            case "max_members" -> String.valueOf(service.maxMembers(clan));
            case "homes" -> String.valueOf(service.homesAllowed(clan));
            case "has_clan" -> "true";
            case "is_leader" -> String.valueOf(clan.leader != null && clan.leader.equals(player.getUniqueId()));
            default -> null;
        };
    }

    private int onlineMembers(ClanService.Clan clan) {
        int online = 0;
        for (UUID member : clan.members.keySet()) {
            if (Bukkit.getPlayer(member) != null) {
                online++;
            }
        }
        return online;
    }
}
