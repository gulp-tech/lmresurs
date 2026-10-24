package ru.voidpe.clans;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.io.File;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Кланы: данные, банк (Vault), роли и права, приглашения, меню. */
public final class ClanService implements Listener {

    public static final List<String> ALL_PERMS = List.of("invite", "kick", "withdraw", "promote", "settings", "sethome");
    private static final Map<String, String> PERM_NAMES = Map.of(
            "invite", "Приглашать игроков",
            "kick", "Кикать игроков",
            "withdraw", "Снимать монеты из банка",
            "promote", "Выдавать роли (ниже своей)",
            "settings", "Настраивать права ролей",
            "sethome", "Ставить дом клана");

    public record RoleTemplate(String id, String name, int priority, List<String> defaultPerms) {}

    public record LevelDef(int level, double xp, int members, double bank, int homes) {}

    static final class Clan {
        String name;
        UUID leader;
        double bank;
        String home = "";
        String home2 = "";
        int level = 1;
        double xp = 0;
        final Map<UUID, String> members = new LinkedHashMap<>();
        final Map<String, Set<String>> rolePerms = new HashMap<>();
    }

    private record Invite(String clanKey, long expires) {}

    static final class ClanHolder implements InventoryHolder {
        final String view;
        final String clanKey;
        UUID target;
        String roleId;
        List<UUID> players = new ArrayList<>();
        List<String> clanKeys = new ArrayList<>();
        final Map<Integer, String> actions = new HashMap<>(); // слот -> роль/право (для меню ролей)
        ClanHolder(String view, String clanKey) { this.view = view; this.clanKey = clanKey; }
        @Override public Inventory getInventory() { return null; }
    }

    private final VoidClans plugin;
    private final VaultHook vault;
    private final File dataFile;
    private final Map<String, Clan> clans = new LinkedHashMap<>(); // ключ — имя в нижнем регистре
    private final Map<String, RoleTemplate> roleTemplates = new LinkedHashMap<>();
    private final Map<UUID, Invite> invites = new HashMap<>();
    private final Map<UUID, String> nameCache = new HashMap<>();
    private final Map<Integer, LevelDef> levels = new LinkedHashMap<>();
    private final Map<UUID, BukkitRunnable> homeWarmups = new HashMap<>();

    public ClanService(VoidClans plugin) {
        this.plugin = plugin;
        this.vault = new VaultHook(plugin.getLogger());
        this.dataFile = new File(plugin.getDataFolder(), "clans.yml");
        loadTemplates();
        loadData();
    }

    public VaultHook vault() { return vault; }

    // ------------------------------------------------------------------ конфиг ролей
    public void loadTemplates() {
        roleTemplates.clear();
        ConfigurationSection section = plugin.getConfig().getConfigurationSection("roles");
        if (section != null) {
            for (String id : section.getKeys(false)) {
                roleTemplates.put(id.toLowerCase(Locale.ROOT), new RoleTemplate(
                        id.toLowerCase(Locale.ROOT),
                        section.getString(id + ".name", id),
                        section.getInt(id + ".priority", 10),
                        section.getStringList(id + ".permissions")));
            }
        }
        if (!roleTemplates.containsKey("leader")) {
            roleTemplates.put("leader", new RoleTemplate("leader", "Лидер", 100, ALL_PERMS));
        }
        if (!roleTemplates.containsKey("member")) {
            roleTemplates.put("member", new RoleTemplate("member", "Боец", 10, List.of()));
        }
        levels.clear();
        ConfigurationSection levelsSection = plugin.getConfig().getConfigurationSection("levels");
        if (levelsSection != null) {
            for (String key : levelsSection.getKeys(false)) {
                try {
                    int lvl = Integer.parseInt(key);
                    levels.put(lvl, new LevelDef(lvl,
                            levelsSection.getDouble(key + ".xp", 0),
                            levelsSection.getInt(key + ".members", 20),
                            levelsSection.getDouble(key + ".bank", -1),
                            levelsSection.getInt(key + ".homes", 1)));
                } catch (NumberFormatException ignored) {
                }
            }
        }
        if (!levels.containsKey(1)) {
            levels.put(1, new LevelDef(1, 0, plugin.getConfig().getInt("settings.max-members", 20), -1, 1));
        }
    }

    public LevelDef levelDef(int level) {
        LevelDef def = levels.get(level);
        if (def != null) {
            return def;
        }
        int best = 1;
        for (int key : levels.keySet()) {
            if (key <= level && key > best) {
                best = key;
            }
        }
        return levels.get(best);
    }

    public int maxMembers(Clan clan) {
        return levelDef(clan.level).members();
    }

    /** Лимит казны уровня; -1 — без лимита. */
    public double bankCap(Clan clan) {
        return levelDef(clan.level).bank();
    }

    public int homesAllowed(Clan clan) {
        return Math.max(1, levelDef(clan.level).homes());
    }

    /** Опыт до следующего уровня или -1, если максимум. */
    public double xpToNext(Clan clan) {
        LevelDef next = levels.get(clan.level + 1);
        return next == null ? -1 : Math.max(0, next.xp() - clan.xp);
    }

    public List<RoleTemplate> rolesSorted() {
        List<RoleTemplate> list = new ArrayList<>(roleTemplates.values());
        list.sort(Comparator.comparingInt(RoleTemplate::priority).reversed());
        return list;
    }

    public RoleTemplate roleTemplate(String roleId) {
        return roleId == null ? null : roleTemplates.get(roleId.toLowerCase(Locale.ROOT));
    }

    // ------------------------------------------------------------------ данные
    private void loadData() {
        clans.clear();
        if (!dataFile.isFile()) {
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(dataFile);
        ConfigurationSection all = yaml.getConfigurationSection("clans");
        if (all != null) {
            for (String key : all.getKeys(false)) {
                ConfigurationSection cs = all.getConfigurationSection(key);
                if (cs == null) {
                    continue;
                }
                Clan clan = new Clan();
                clan.name = cs.getString("name", key);
                try {
                    clan.leader = UUID.fromString(cs.getString("leader", ""));
                } catch (Throwable ignored) {
                }
                clan.bank = cs.getDouble("bank", 0);
                clan.home = cs.getString("home", "");
                clan.home2 = cs.getString("home2", "");
                clan.level = Math.max(1, cs.getInt("level", 1));
                clan.xp = cs.getDouble("xp", 0);
                ConfigurationSection members = cs.getConfigurationSection("members");
                if (members != null) {
                    for (String uuid : members.getKeys(false)) {
                        try {
                            clan.members.put(UUID.fromString(uuid), members.getString(uuid, "member"));
                        } catch (Throwable ignored) {
                        }
                    }
                }
                ConfigurationSection perms = cs.getConfigurationSection("role-perms");
                for (RoleTemplate role : roleTemplates.values()) {
                    Set<String> set = new HashSet<>();
                    if (perms != null && perms.isSet(role.id())) {
                        set.addAll(perms.getStringList(role.id()));
                    } else {
                        set.addAll(role.defaultPerms());
                    }
                    clan.rolePerms.put(role.id(), set);
                }
                if (clan.leader != null && !clan.members.isEmpty()) {
                    clans.put(key, clan);
                }
            }
        }
        ConfigurationSection names = yaml.getConfigurationSection("names");
        if (names != null) {
            for (String uuid : names.getKeys(false)) {
                try {
                    nameCache.put(UUID.fromString(uuid), names.getString(uuid, ""));
                } catch (Throwable ignored) {
                }
            }
        }
        plugin.getLogger().info("Загружено кланов: " + clans.size());
    }

    public void saveData() {
        try {
            YamlConfiguration yaml = new YamlConfiguration();
            for (Map.Entry<String, Clan> e : clans.entrySet()) {
                Clan clan = e.getValue();
                String path = "clans." + e.getKey();
                yaml.set(path + ".name", clan.name);
                yaml.set(path + ".leader", String.valueOf(clan.leader));
                yaml.set(path + ".bank", clan.bank);
                yaml.set(path + ".home", clan.home);
                yaml.set(path + ".home2", clan.home2);
                yaml.set(path + ".level", clan.level);
                yaml.set(path + ".xp", clan.xp);
                for (Map.Entry<UUID, String> m : clan.members.entrySet()) {
                    yaml.set(path + ".members." + m.getKey(), m.getValue());
                }
                for (Map.Entry<String, Set<String>> rp : clan.rolePerms.entrySet()) {
                    yaml.set(path + ".role-perms." + rp.getKey(), new ArrayList<>(rp.getValue()));
                }
            }
            for (Map.Entry<UUID, String> n : nameCache.entrySet()) {
                yaml.set("names." + n.getKey(), n.getValue());
            }
            yaml.save(dataFile);
        } catch (Throwable t) {
            plugin.getLogger().warning("Не удалось сохранить кланы: " + t.getMessage());
        }
    }

    // ------------------------------------------------------------------ помощники
    public Clan clanOf(UUID uuid) {
        for (Clan clan : clans.values()) {
            if (clan.members.containsKey(uuid)) {
                return clan;
            }
        }
        return null;
    }

    public String clanKeyOf(UUID uuid) {
        for (Map.Entry<String, Clan> e : clans.entrySet()) {
            if (e.getValue().members.containsKey(uuid)) {
                return e.getKey();
            }
        }
        return null;
    }

    public Clan byName(String name) {
        return name == null ? null : clans.get(name.toLowerCase(Locale.ROOT));
    }

    public String roleIdOf(Clan clan, UUID uuid) {
        return clan.members.getOrDefault(uuid, "member");
    }

    public int priorityOf(String roleId) {
        RoleTemplate template = roleTemplate(roleId);
        return template == null ? 0 : template.priority();
    }

    public String roleName(String roleId) {
        RoleTemplate template = roleTemplate(roleId);
        return template == null ? roleId : template.name();
    }

    public boolean hasPerm(Clan clan, UUID uuid, String perm) {
        String roleId = roleIdOf(clan, uuid);
        if (roleId.equals("leader")) {
            return true;
        }
        Set<String> perms = clan.rolePerms.get(roleId);
        return perms != null && perms.contains(perm);
    }

    public String nameOf(UUID uuid) {
        String cached = nameCache.get(uuid);
        if (cached != null && !cached.isEmpty()) {
            return cached;
        }
        OfflinePlayer offline = Bukkit.getOfflinePlayer(uuid);
        return offline.getName() != null ? offline.getName() : uuid.toString().substring(0, 8);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        nameCache.put(event.getPlayer().getUniqueId(), event.getPlayer().getName());
        saveData();
    }

    static String fmt(double amount) {
        DecimalFormatSymbols symbols = new DecimalFormatSymbols(Locale.US);
        symbols.setGroupingSeparator(' ');
        DecimalFormat format = new DecimalFormat("#,###", symbols);
        return format.format(Math.floor(amount));
    }

    private String cfg(String path, String def) {
        return plugin.getConfig().getString("messages." + path, def);
    }

    void msg(Player player, String key, String... pairs) {
        String text = cfg(key, "");
        if (text == null || text.isEmpty()) {
            return;
        }
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            text = text.replace(pairs[i], pairs[i + 1]);
        }
        String prefix = TextUtil.color(plugin.getConfig().getString("messages.prefix", "&#C86BFFVoidPE &8» &f"));
        player.sendMessage(comp(prefix + TextUtil.color(text)));
    }

    private void clanBroadcast(Clan clan, String key, UUID except, String... pairs) {
        for (UUID member : clan.members.keySet()) {
            if (member.equals(except)) {
                continue;
            }
            Player online = Bukkit.getPlayer(member);
            if (online != null) {
                msg(online, key, pairs);
            }
        }
    }

    static Component comp(String legacy) {
        return LegacyComponentSerializer.legacySection().deserialize(legacy == null ? "" : legacy);
    }

    // ------------------------------------------------------------------ создание и членство
    public void createClan(Player player, String name) {
        if (clanOf(player.getUniqueId()) != null) {
            msg(player, "already-in-clan");
            return;
        }
        int min = plugin.getConfig().getInt("settings.name-min", 3);
        int max = plugin.getConfig().getInt("settings.name-max", 16);
        if (name.length() < min || name.length() > max || !name.matches("[A-Za-zА-Яа-яЁё0-9_]+")) {
            msg(player, "name-invalid");
            return;
        }
        if (byName(name) != null) {
            msg(player, "name-taken");
            return;
        }
        double cost = plugin.getConfig().getDouble("settings.create-cost", 0);
        if (cost > 0) {
            if (!vault.available()) {
                msg(player, "economy-off");
                return;
            }
            if (vault.balance(player) < cost) {
                msg(player, "no-money", "{amount}", fmt(cost));
                return;
            }
            if (!vault.withdraw(player, cost)) {
                msg(player, "no-money", "{amount}", fmt(cost));
                return;
            }
        }
        Clan clan = new Clan();
        clan.name = name;
        clan.leader = player.getUniqueId();
        clan.members.put(player.getUniqueId(), "leader");
        for (RoleTemplate role : roleTemplates.values()) {
            clan.rolePerms.put(role.id(), new HashSet<>(role.defaultPerms()));
        }
        clans.put(name.toLowerCase(Locale.ROOT), clan);
        nameCache.put(player.getUniqueId(), player.getName());
        saveData();
        msg(player, "created", "{clan}", name);
    }

    public void invite(Player player, String targetName) {
        Clan clan = clanOf(player.getUniqueId());
        if (clan == null) {
            msg(player, "not-in-clan");
            return;
        }
        if (!hasPerm(clan, player.getUniqueId(), "invite")) {
            msg(player, "no-permission");
            return;
        }
        Player target = Bukkit.getPlayerExact(targetName);
        if (target == null) {
            msg(player, "target-offline");
            return;
        }
        if (clanOf(target.getUniqueId()) != null) {
            msg(player, "target-in-clan");
            return;
        }
        if (clan.members.size() >= maxMembers(clan)) {
            msg(player, "full");
            return;
        }
        long expire = System.currentTimeMillis()
                + plugin.getConfig().getLong("settings.invite-expire-seconds", 120) * 1000L;
        invites.put(target.getUniqueId(), new Invite(clanKeyOf(player.getUniqueId()), expire));
        msg(player, "invited", "{player}", target.getName());
        msg(target, "invite-received", "{clan}", clan.name);
    }

    public void accept(Player player) {
        Invite invite = invites.remove(player.getUniqueId());
        if (invite == null || invite.expires() < System.currentTimeMillis()) {
            msg(player, "invite-expired");
            return;
        }
        if (clanOf(player.getUniqueId()) != null) {
            msg(player, "already-in-clan");
            return;
        }
        Clan clan = clans.get(invite.clanKey());
        if (clan == null) {
            msg(player, "clan-not-found");
            return;
        }
        if (clan.members.size() >= maxMembers(clan)) {
            msg(player, "full");
            return;
        }
        clan.members.put(player.getUniqueId(),
                plugin.getConfig().getString("default-role", "member").toLowerCase(Locale.ROOT));
        nameCache.put(player.getUniqueId(), player.getName());
        saveData();
        msg(player, "joined", "{clan}", clan.name);
        clanBroadcast(clan, "member-joined", player.getUniqueId(), "{player}", player.getName());
    }

    public void deny(Player player) {
        if (invites.remove(player.getUniqueId()) != null) {
            msg(player, "declined");
        } else {
            msg(player, "invite-expired");
        }
    }

    public void leave(Player player) {
        Clan clan = clanOf(player.getUniqueId());
        if (clan == null) {
            msg(player, "not-in-clan");
            return;
        }
        if (clan.leader.equals(player.getUniqueId())) {
            msg(player, "leader-cant-leave");
            return;
        }
        clan.members.remove(player.getUniqueId());
        saveData();
        msg(player, "left", "{clan}", clan.name);
        clanBroadcast(clan, "member-left", null, "{player}", player.getName());
    }

    public void kick(Player player, String targetName) {
        Clan clan = clanOf(player.getUniqueId());
        if (clan == null) {
            msg(player, "not-in-clan");
            return;
        }
        if (!hasPerm(clan, player.getUniqueId(), "kick")) {
            msg(player, "no-permission");
            return;
        }
        UUID target = findMember(clan, targetName);
        if (target == null) {
            msg(player, "not-member");
            return;
        }
        if (target.equals(player.getUniqueId())
                || priorityOf(roleIdOf(clan, target)) >= priorityOf(roleIdOf(clan, player.getUniqueId()))) {
            msg(player, "cant-manage-higher");
            return;
        }
        clan.members.remove(target);
        saveData();
        msg(player, "kick-done", "{player}", nameOf(target));
        Player online = Bukkit.getPlayer(target);
        if (online != null) {
            msg(online, "kicked", "{clan}", clan.name);
        }
        clanBroadcast(clan, "member-kicked", player.getUniqueId(), "{player}", nameOf(target));
    }

    private UUID findMember(Clan clan, String name) {
        for (UUID member : clan.members.keySet()) {
            if (nameOf(member).equalsIgnoreCase(name)) {
                return member;
            }
        }
        // по началу ника
        for (UUID member : clan.members.keySet()) {
            if (nameOf(member).toLowerCase(Locale.ROOT).startsWith(name.toLowerCase(Locale.ROOT))) {
                return member;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ роли и права
    public void setRole(Player player, String targetName, String roleId) {
        Clan clan = clanOf(player.getUniqueId());
        if (clan == null) {
            msg(player, "not-in-clan");
            return;
        }
        RoleTemplate template = roleTemplate(roleId);
        if (template == null || template.id().equals("leader")) {
            sendRaw(player, "&cТакой роли нет (лидера назначают через /clan transfer).");
            return;
        }
        if (!hasPerm(clan, player.getUniqueId(), "promote")) {
            msg(player, "no-permission");
            return;
        }
        UUID target = findMember(clan, targetName);
        if (target == null) {
            msg(player, "not-member");
            return;
        }
        int actorPriority = priorityOf(roleIdOf(clan, player.getUniqueId()));
        if (!clan.leader.equals(player.getUniqueId())
                && (priorityOf(roleIdOf(clan, target)) >= actorPriority || template.priority() >= actorPriority)) {
            msg(player, "cant-manage-higher");
            return;
        }
        clan.members.put(target, template.id());
        saveData();
        msg(player, "role-set", "{player}", nameOf(target), "{role}", template.name());
        Player online = Bukkit.getPlayer(target);
        if (online != null && !online.equals(player)) {
            msg(online, "role-received", "{role}", template.name());
        }
    }

    /** Переключить право роли в клане игрока. @return false, если нельзя. */
    public boolean togglePerm(Player player, String roleId, String perm) {
        Clan clan = clanOf(player.getUniqueId());
        if (clan == null) {
            msg(player, "not-in-clan");
            return false;
        }
        RoleTemplate template = roleTemplate(roleId);
        if (template == null) {
            return false;
        }
        if (template.id().equals("leader")) {
            msg(player, "leader-perms-locked");
            return false;
        }
        if (!hasPerm(clan, player.getUniqueId(), "settings")) {
            msg(player, "no-permission");
            return false;
        }
        Set<String> perms = clan.rolePerms.computeIfAbsent(template.id(), k -> new HashSet<>());
        boolean now;
        if (perms.contains(perm)) {
            perms.remove(perm);
            now = false;
        } else {
            perms.add(perm);
            now = true;
        }
        saveData();
        msg(player, "perm-toggled", "{perm}", PERM_NAMES.getOrDefault(perm, perm),
                "{role}", template.name(), "{state}", now ? "&aвключено" : "&cвыключено");
        return true;
    }

    public void transfer(Player player, String targetName) {
        Clan clan = clanOf(player.getUniqueId());
        if (clan == null) {
            msg(player, "not-in-clan");
            return;
        }
        if (!clan.leader.equals(player.getUniqueId())) {
            msg(player, "no-permission");
            return;
        }
        UUID target = findMember(clan, targetName);
        if (target == null || target.equals(player.getUniqueId())) {
            msg(player, "not-member");
            return;
        }
        clan.members.put(player.getUniqueId(), "deputy");
        clan.members.put(target, "leader");
        clan.leader = target;
        saveData();
        msg(player, "transferred", "{player}", nameOf(target));
        Player online = Bukkit.getPlayer(target);
        if (online != null) {
            msg(online, "transfer-received", "{clan}", clan.name);
        }
    }

    public void disband(Player player, boolean confirm) {
        Clan clan = clanOf(player.getUniqueId());
        if (clan == null) {
            msg(player, "not-in-clan");
            return;
        }
        if (!clan.leader.equals(player.getUniqueId())) {
            msg(player, "no-permission");
            return;
        }
        if (!confirm) {
            msg(player, "disband-confirm");
            return;
        }
        String key = clanKeyOf(player.getUniqueId());
        double bank = clan.bank;
        String clanName = clan.name;
        List<UUID> members = new ArrayList<>(clan.members.keySet());
        clans.remove(key);
        saveData();
        if (bank > 0 && vault.available()) {
            vault.deposit(player, bank);
        }
        for (UUID member : members) {
            Player online = Bukkit.getPlayer(member);
            if (online != null && !online.equals(player)) {
                msg(online, "disbanded", "{clan}", clanName);
            }
        }
        msg(player, "disbanded", "{clan}", clanName);
    }

    // ------------------------------------------------------------------ банк
    public void deposit(Player player, long amount) {
        Clan clan = clanOf(player.getUniqueId());
        if (clan == null) {
            msg(player, "not-in-clan");
            return;
        }
        if (amount <= 0) {
            msg(player, "amount-invalid");
            return;
        }
        if (!vault.available()) {
            msg(player, "economy-off");
            return;
        }
        if (vault.balance(player) < amount) {
            msg(player, "no-money", "{amount}", fmt(amount));
            return;
        }
        double cap = bankCap(clan);
        if (cap >= 0 && clan.bank + amount > cap) {
            msg(player, "bank-full", "{bank-cap}", fmt(cap), "{level}", String.valueOf(clan.level));
            return;
        }
        if (!vault.withdraw(player, amount)) {
            msg(player, "no-money", "{amount}", fmt(amount));
            return;
        }
        clan.bank += amount;
        saveData();
        msg(player, "deposited", "{amount}", fmt(amount), "{bank}", fmt(clan.bank));
    }

    public void withdraw(Player player, long amount) {
        Clan clan = clanOf(player.getUniqueId());
        if (clan == null) {
            msg(player, "not-in-clan");
            return;
        }
        if (!hasPerm(clan, player.getUniqueId(), "withdraw")) {
            msg(player, "no-permission");
            return;
        }
        if (amount <= 0) {
            msg(player, "amount-invalid");
            return;
        }
        if (!vault.available()) {
            msg(player, "economy-off");
            return;
        }
        if (clan.bank < amount) {
            msg(player, "bank-not-enough");
            return;
        }
        if (!vault.deposit(player, amount)) {
            msg(player, "economy-off");
            return;
        }
        clan.bank -= amount;
        saveData();
        msg(player, "withdrawn", "{amount}", fmt(amount), "{bank}", fmt(clan.bank));
    }

    // ------------------------------------------------------------------ дом и чат
    public void setHome(Player player) {
        setHome(player, 1);
    }

    public void setHome(Player player, int index) {
        Clan clan = clanOf(player.getUniqueId());
        if (clan == null) {
            msg(player, "not-in-clan");
            return;
        }
        if (!hasPerm(clan, player.getUniqueId(), "sethome")) {
            msg(player, "no-permission");
            return;
        }
        if (index == 2 && homesAllowed(clan) < 2) {
            msg(player, "home-locked", "{level}", String.valueOf(clan.level));
            return;
        }
        Location loc = player.getLocation();
        String value = loc.getWorld().getName() + "," + loc.getX() + "," + loc.getY() + "," + loc.getZ()
                + "," + loc.getYaw() + "," + loc.getPitch();
        if (index == 2) {
            clan.home2 = value;
        } else {
            clan.home = value;
        }
        saveData();
        msg(player, "home-set", "{index}", String.valueOf(index));
    }

    public void home(Player player) {
        home(player, 1);
    }

    /** Телепорт домой с прогревом 3 секунды (нельзя двигаться). */
    public void home(Player player, int index) {
        Clan clan = clanOf(player.getUniqueId());
        if (clan == null) {
            msg(player, "not-in-clan");
            return;
        }
        if (index == 2 && homesAllowed(clan) < 2) {
            msg(player, "home-locked", "{level}", String.valueOf(clan.level));
            return;
        }
        String stored = index == 2 ? clan.home2 : clan.home;
        if (stored == null || stored.isEmpty()) {
            msg(player, "home-none");
            return;
        }
        final Location target;
        try {
            String[] parts = stored.split(",");
            World world = Bukkit.getWorld(parts[0]);
            if (world == null) {
                msg(player, "home-none");
                return;
            }
            target = new Location(world,
                    Double.parseDouble(parts[1]), Double.parseDouble(parts[2]), Double.parseDouble(parts[3]),
                    Float.parseFloat(parts[4]), Float.parseFloat(parts[5]));
        } catch (Throwable t) {
            msg(player, "home-none");
            return;
        }
        if (homeWarmups.containsKey(player.getUniqueId())) {
            return;
        }
        final Location start = player.getLocation().clone();
        int seconds = Math.max(0, plugin.getConfig().getInt("settings.home-warmup-seconds", 3));
        msg(player, "home-warmup", "{seconds}", String.valueOf(seconds));
        BukkitRunnable task = new BukkitRunnable() {
            int left = seconds;

            @Override
            public void run() {
                if (!player.isOnline() || player.isDead()
                        || player.getLocation().getBlockX() != start.getBlockX()
                        || player.getLocation().getBlockY() != start.getBlockY()
                        || player.getLocation().getBlockZ() != start.getBlockZ()
                        || !player.getWorld().equals(start.getWorld())) {
                    homeWarmups.remove(player.getUniqueId());
                    if (player.isOnline()) {
                        msg(player, "home-cancelled");
                    }
                    cancel();
                    return;
                }
                if (left <= 0) {
                    homeWarmups.remove(player.getUniqueId());
                    player.teleport(target);
                    msg(player, "home-teleport");
                    cancel();
                    return;
                }
                player.sendActionBar(comp(TextUtil.color("&#C86BFFТелепорт домой: &f" + left + " сек")));
                left--;
            }
        };
        homeWarmups.put(player.getUniqueId(), task);
        task.runTaskTimer(plugin, 0L, 20L);
    }

    // ------------------------------------------------------------------ прокачка клана
    /** Начислить клану опыт за действие (ключ из leveling.xp в конфиге) и проверить повышение. */
    public void addXp(Clan clan, String action) {
        double amount = plugin.getConfig().getDouble("leveling.xp." + action, 0);
        if (amount <= 0 || clan == null) {
            return;
        }
        clan.xp += amount;
        boolean leveled = false;
        boolean homesUnlocked = false;
        while (true) {
            LevelDef next = levels.get(clan.level + 1);
            if (next == null || clan.xp < next.xp()) {
                break;
            }
            int beforeHomes = homesAllowed(clan);
            clan.level++;
            leveled = true;
            if (homesAllowed(clan) > beforeHomes) {
                homesUnlocked = true;
            }
        }
        saveData();
        if (leveled) {
            for (UUID member : clan.members.keySet()) {
                Player online = Bukkit.getPlayer(member);
                if (online != null) {
                    msg(online, "levelup", "{clan}", clan.name, "{level}", String.valueOf(clan.level));
                    if (homesUnlocked) {
                        msg(online, "levelup-homes");
                    }
                }
            }
        }
    }

    @EventHandler
    public void onBlockBreakXp(BlockBreakEvent event) {
        Player player = event.getPlayer();
        if (player.getGameMode() == org.bukkit.GameMode.CREATIVE) {
            return;
        }
        Clan clan = clanOf(player.getUniqueId());
        if (clan != null) {
            addXp(clan, "block-break");
        }
    }

    @EventHandler
    public void onMobKillXp(EntityDeathEvent event) {
        if (event.getEntity() instanceof Player) {
            return;
        }
        Player killer = event.getEntity().getKiller();
        if (killer == null) {
            return;
        }
        Clan clan = clanOf(killer.getUniqueId());
        if (clan != null) {
            addXp(clan, "mob-kill");
        }
    }

    @EventHandler
    public void onPlayerKillXp(PlayerDeathEvent event) {
        Player killer = event.getEntity().getKiller();
        if (killer == null || killer.equals(event.getEntity())) {
            return;
        }
        Clan clan = clanOf(killer.getUniqueId());
        if (clan != null) {
            addXp(clan, "player-kill");
        }
    }

    @EventHandler
    public void onFishXp(PlayerFishEvent event) {
        if (event.getState() != PlayerFishEvent.State.CAUGHT_FISH) {
            return;
        }
        Clan clan = clanOf(event.getPlayer().getUniqueId());
        if (clan != null) {
            addXp(clan, "fish");
        }
    }

    public void chat(Player player, String text) {
        Clan clan = clanOf(player.getUniqueId());
        if (clan == null) {
            msg(player, "not-in-clan");
            return;
        }
        String format = plugin.getConfig().getString("messages.chat-format", "[Клан] {player}: {message}");
        Component message = comp(TextUtil.color(format
                .replace("{player}", player.getName())
                .replace("{message}", text)));
        for (UUID member : clan.members.keySet()) {
            Player online = Bukkit.getPlayer(member);
            if (online != null) {
                online.sendMessage(message);
            }
        }
    }

    // ------------------------------------------------------------------ информация
    public void info(Player player, String clanName) {
        Clan clan = clanName == null ? clanOf(player.getUniqueId()) : byName(clanName);
        if (clan == null) {
            msg(player, clanName == null ? "not-in-clan" : "clan-not-found");
            return;
        }
        sendRaw(player, "&#FFD93DКлан &f" + clan.name);
        sendRaw(player, "&7Лидер: &f" + nameOf(clan.leader));
        sendRaw(player, "&7Участников: &f" + clan.members.size()
                + "&7/&f" + maxMembers(clan));
        double toNext = xpToNext(clan);
        sendRaw(player, "&7Уровень клана: &f" + clan.level
                + (toNext < 0 ? " &8(максимум)" : "&7, опыт: &f" + fmt(clan.xp) + "&7/&f" + fmt(levelDef(clan.level + 1).xp())));
        sendRaw(player, "&7Банк: &f" + fmt(clan.bank) + " монет");
        StringBuilder members = new StringBuilder();
        for (Map.Entry<UUID, String> m : clan.members.entrySet()) {
            if (members.length() > 0) {
                members.append("&7, ");
            }
            members.append("&f").append(nameOf(m.getKey())).append(" &8[").append(stripColor(roleName(m.getValue()))).append("]");
        }
        sendRaw(player, "&7Состав: " + members);
    }

    public void top(Player player) {
        if (clans.isEmpty()) {
            sendRaw(player, "&7Кланов пока нет.");
            return;
        }
        List<Clan> sorted = new ArrayList<>(clans.values());
        sorted.sort(Comparator.comparingDouble((Clan c) -> c.bank).reversed());
        sendRaw(player, "&#FFD93DТоп кланов по банку:");
        int i = 1;
        for (Clan clan : sorted) {
            if (i > 10) {
                break;
            }
            sendRaw(player, "&7" + i + ". &f" + clan.name + " &7— банк: &f" + fmt(clan.bank)
                    + "&7, участников: &f" + clan.members.size());
            i++;
        }
    }

    void sendRaw(Player player, String legacy) {
        String prefix = TextUtil.color(plugin.getConfig().getString("messages.prefix", "&#C86BFFVoidPE &8» &f"));
        player.sendMessage(comp(prefix + TextUtil.color(legacy)));
    }

    private static String stripColor(String text) {
        return text == null ? "" : text.replaceAll("&#[0-9A-Fa-f]{6}", "").replaceAll("&[0-9a-fk-or]", "");
    }

    // ================================================================== МЕНЮ
    private ItemStack btn(Material material, String name, String... lore) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.displayName(comp(TextUtil.color(name)).decoration(TextDecoration.ITALIC, false));
            List<Component> lines = new ArrayList<>();
            for (String line : lore) {
                lines.add(comp(TextUtil.color(line)).decoration(TextDecoration.ITALIC, false));
            }
            meta.lore(lines);
            stack.setItemMeta(meta);
        }
        return stack;
    }

    /** Обычное ванильное чёрное стекло-заполнитель для меню. */
    private ItemStack filler() {
        return btn(Material.BLACK_STAINED_GLASS_PANE, " ");
    }

    private void fill(Inventory inv) {
        ItemStack filler = filler();
        for (int i = 0; i < inv.getSize(); i++) {
            inv.setItem(i, filler);
        }
    }

    private ItemStack head(UUID uuid, String title, String... lore) {
        ItemStack stack = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) stack.getItemMeta();
        if (meta != null) {
            meta.setOwningPlayer(Bukkit.getOfflinePlayer(uuid));
            meta.displayName(comp(TextUtil.color(title)).decoration(TextDecoration.ITALIC, false));
            List<Component> lines = new ArrayList<>();
            for (String line : lore) {
                lines.add(comp(TextUtil.color(line)).decoration(TextDecoration.ITALIC, false));
            }
            meta.lore(lines);
            stack.setItemMeta(meta);
        }
        return stack;
    }

    private String title(String path, String def) {
        return TextUtil.color(plugin.getConfig().getString("menu." + path, def));
    }

    public void openMain(Player player) {
        Clan clan = clanOf(player.getUniqueId());
        if (clan == null) {
            openNone(player);
            return;
        }
        String key = clanKeyOf(player.getUniqueId());
        ClanHolder holder = new ClanHolder("clan", key);
        Inventory inv = Bukkit.createInventory(holder, 27, comp(title("title", "Клан") + TextUtil.color(" &8• &f") + clan.name));
        fill(inv);
        inv.setItem(0, btn(Material.BOOK, "&#FFD93D" + clan.name,
                "&7Лидер: &f" + nameOf(clan.leader),
                "&7Участников: &f" + clan.members.size(),
                "&7Банк: &f" + fmt(clan.bank) + " монет",
                "&7Уровень: &f" + clan.level + " &7(опыт: &f" + fmt(clan.xp) + "&7)",
                "&7Твоя роль: &f" + roleName(roleIdOf(clan, player.getUniqueId()))));
        inv.setItem(2, btn(Material.GOLD_INGOT, "&#FFD93DБанк клана",
                "&7Баланс: &f" + fmt(clan.bank), "&7Клик — внести или снять монеты"));
        inv.setItem(4, btn(Material.PLAYER_HEAD, "&#8CE8FFУчастники",
                "&7Список, роли и кик"));
        inv.setItem(6, hasPerm(clan, player.getUniqueId(), "invite")
                ? btn(Material.WRITABLE_BOOK, "&#7CFC00Пригласить", "&7Выбрать игрока из сети")
                : btn(Material.GRAY_DYE, "&8Пригласить", "&7Нет права invite"));
        inv.setItem(8, btn(Material.ENDER_PEARL, "&#C86BFFДом клана", "&7Телепорт домой"));
        inv.setItem(10, hasPerm(clan, player.getUniqueId(), "sethome")
                ? btn(Material.COMPASS, "&#C86BFFПоставить дом", "&7Дом встанет там, где ты стоишь")
                : btn(Material.GRAY_DYE, "&8Поставить дом", "&7Нет права sethome"));
        inv.setItem(12, btn(Material.NAME_TAG, "&#FF9A3DРоли и права",
                "&7Кто что может делать в клане"));
        inv.setItem(14, btn(Material.PAPER, "&#8CE8FFКлан-чат", "&7Команда: &f/clan c <текст>"));
        double toNext = xpToNext(clan);
        inv.setItem(16, btn(Material.EXPERIENCE_BOTTLE, "&#FFD93DПрокачка клана",
                "&7Уровень: &f" + clan.level,
                toNext < 0 ? "&7Максимальный уровень" : "&7До след. уровня: &f" + fmt(toNext) + " опыта",
                "&7Опыт: блоки, мобы, убийства, рыбалка"));
        if (clan.leader.equals(player.getUniqueId())) {
            inv.setItem(26, btn(Material.TNT, "&#FF5555Распустить клан", "&7Команда: &f/clan disband"));
        } else {
            inv.setItem(22, btn(Material.IRON_DOOR, "&#FF5555Выйти из клана"));
        }
        player.openInventory(inv);
    }

    private void openNone(Player player) {
        ClanHolder holder = new ClanHolder("none", null);
        Inventory inv = Bukkit.createInventory(holder, 27, comp(title("title-none", "Кланы")));
        fill(inv);
        double cost = plugin.getConfig().getDouble("settings.create-cost", 0);
        inv.setItem(11, btn(Material.EMERALD, "&#7CFC00Создать клан",
                "&7Команда: &f/clan create <название>",
                cost > 0 ? "&7Цена: &f" + fmt(cost) + " монет" : "&7Бесплатно"));
        inv.setItem(15, btn(Material.BOOK, "&#FFD93DТоп кланов", "&7Кланов на сервере: &f" + clans.size()));
        player.openInventory(inv);
    }

    private void openBank(Player player) {
        Clan clan = clanOf(player.getUniqueId());
        if (clan == null) {
            openNone(player);
            return;
        }
        ClanHolder holder = new ClanHolder("bank", clanKeyOf(player.getUniqueId()));
        Inventory inv = Bukkit.createInventory(holder, 27, comp(title("title", "Клан") + TextUtil.color(" &8• &fБанк")));
        fill(inv);
        inv.setItem(4, btn(Material.GOLD_BLOCK, "&#FFD93DБанк клана", "&7Баланс: &f" + fmt(clan.bank) + " монет"));
        List<Integer> amounts = plugin.getConfig().getIntegerList("menu.amounts");
        if (amounts.isEmpty()) {
            amounts = List.of(100, 1000, 10000);
        }
        int[] depSlots = {10, 12, 14};
        for (int i = 0; i < amounts.size() && i < depSlots.length; i++) {
            inv.setItem(depSlots[i], btn(Material.LIME_DYE, "&#7CFC00Внести " + fmt(amounts.get(i)), "&7Из твоего баланса"));
        }
        inv.setItem(16, btn(Material.SUNFLOWER, "&#7CFC00Внести всё", "&7Весь твой баланс монет"));
        boolean canWithdraw = hasPerm(clan, player.getUniqueId(), "withdraw");
        int[] wdSlots = {19, 21, 23};
        for (int i = 0; i < amounts.size() && i < wdSlots.length; i++) {
            inv.setItem(wdSlots[i], canWithdraw
                    ? btn(Material.RED_DYE, "&#FF5555Снять " + fmt(amounts.get(i)), "&7Себе на баланс")
                    : btn(Material.GRAY_DYE, "&8Снять " + fmt(amounts.get(i)), "&7Нет права withdraw"));
        }
        if (canWithdraw) {
            inv.setItem(25, btn(Material.SUNFLOWER, "&#FF5555Снять всё", "&7Весь банк себе"));
        }
        inv.setItem(26, btn(Material.ARROW, "&fНазад"));
        player.openInventory(inv);
    }

    private void openMembers(Player player) {
        Clan clan = clanOf(player.getUniqueId());
        if (clan == null) {
            openNone(player);
            return;
        }
        ClanHolder holder = new ClanHolder("members", clanKeyOf(player.getUniqueId()));
        Inventory inv = Bukkit.createInventory(holder, 54, comp(title("title", "Клан") + TextUtil.color(" &8• &fУчастники")));
        fill(inv);
        int slot = 0;
        for (Map.Entry<UUID, String> m : clan.members.entrySet()) {
            if (slot >= 53) {
                break;
            }
            holder.players.add(m.getKey());
            boolean online = Bukkit.getPlayer(m.getKey()) != null;
            inv.setItem(slot++, head(m.getKey(), "&f" + nameOf(m.getKey()),
                    "&7Роль: &f" + roleName(m.getValue()),
                    online ? "&#7CFC00В сети" : "&8Не в сети",
                    "&7Клик — управление"));
        }
        inv.setItem(53, btn(Material.ARROW, "&fНазад"));
        player.openInventory(inv);
    }

    private void openMember(Player player, UUID target) {
        Clan clan = clanOf(player.getUniqueId());
        if (clan == null || !clan.members.containsKey(target)) {
            openMembers(player);
            return;
        }
        ClanHolder holder = new ClanHolder("member", clanKeyOf(player.getUniqueId()));
        holder.target = target;
        Inventory inv = Bukkit.createInventory(holder, 27, comp(title("title", "Клан") + TextUtil.color(" &8• &f") + nameOf(target)));
        fill(inv);
        inv.setItem(4, head(target, "&f" + nameOf(target), "&7Роль: &f" + roleName(roleIdOf(clan, target))));
        int slot = 10;
        for (RoleTemplate role : rolesSorted()) {
            if (role.id().equals("leader") || slot > 16) {
                continue;
            }
            ItemStack item = btn(Material.NAME_TAG, role.name(),
                    roleIdOf(clan, target).equals(role.id()) ? "&#7CFC00Текущая роль" : "&7Клик — выдать роль");
            if (roleIdOf(clan, target).equals(role.id())) {
                ItemMeta meta = item.getItemMeta();
                meta.addEnchant(Enchantment.UNBREAKING, 1, true);
                meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
                item.setItemMeta(meta);
            }
            inv.setItem(slot, item);
            holder.actions.put(slot, role.id());
            slot += 2;
        }
        boolean canManage = !target.equals(player.getUniqueId())
                && priorityOf(roleIdOf(clan, target)) < priorityOf(roleIdOf(clan, player.getUniqueId()));
        if (canManage && hasPerm(clan, player.getUniqueId(), "kick")) {
            inv.setItem(22, btn(Material.IRON_AXE, "&#FF5555Кикнуть из клана"));
        }
        if (clan.leader.equals(player.getUniqueId()) && !target.equals(player.getUniqueId())) {
            inv.setItem(24, btn(Material.GOLDEN_HELMET, "&#FFD93DПередать лидерство"));
        }
        inv.setItem(26, btn(Material.ARROW, "&fНазад"));
        player.openInventory(inv);
    }

    private void openRoles(Player player) {
        Clan clan = clanOf(player.getUniqueId());
        if (clan == null) {
            openNone(player);
            return;
        }
        ClanHolder holder = new ClanHolder("roles", clanKeyOf(player.getUniqueId()));
        Inventory inv = Bukkit.createInventory(holder, 27, comp(title("title", "Клан") + TextUtil.color(" &8• &fРоли")));
        fill(inv);
        int slot = 10;
        for (RoleTemplate role : rolesSorted()) {
            if (slot > 16) {
                break;
            }
            holder.actions.put(slot, role.id());
            inv.setItem(slot, btn(Material.NAME_TAG, role.name(),
                    "&7Приоритет: &f" + role.priority(),
                    role.id().equals("leader") ? "&7У лидера всегда все права" : "&7Клик — настроить права"));
            slot += 2;
        }
        inv.setItem(26, btn(Material.ARROW, "&fНазад"));
        player.openInventory(inv);
    }

    private void openRolePerms(Player player, String roleId) {
        Clan clan = clanOf(player.getUniqueId());
        RoleTemplate template = roleTemplate(roleId);
        if (clan == null || template == null) {
            openRoles(player);
            return;
        }
        ClanHolder holder = new ClanHolder("roleperms", clanKeyOf(player.getUniqueId()));
        holder.roleId = roleId;
        Inventory inv = Bukkit.createInventory(holder, 27, comp(title("title", "Клан") + TextUtil.color(" &8• &f") + stripColor(template.name())));
        fill(inv);
        int slot = 10;
        for (String perm : ALL_PERMS) {
            boolean on = template.id().equals("leader") || hasPermRaw(clan, roleId, perm);
            inv.setItem(slot, btn(on ? Material.LIME_DYE : Material.GRAY_DYE,
                    (on ? "&#7CFC00" : "&8") + PERM_NAMES.get(perm),
                    template.id().equals("leader") ? "&7У лидера не меняется" : "&7Клик — переключить"));
            holder.actions.put(slot, perm);
            slot++;
            if (slot == 13) {
                slot = 14; // небольшой разрыв по центру
            }
        }
        inv.setItem(26, btn(Material.ARROW, "&fНазад"));
        player.openInventory(inv);
    }

    private boolean hasPermRaw(Clan clan, String roleId, String perm) {
        Set<String> perms = clan.rolePerms.get(roleId);
        return perms != null && perms.contains(perm);
    }

    private void openInvite(Player player) {
        Clan clan = clanOf(player.getUniqueId());
        if (clan == null) {
            openNone(player);
            return;
        }
        ClanHolder holder = new ClanHolder("invite", clanKeyOf(player.getUniqueId()));
        Inventory inv = Bukkit.createInventory(holder, 54, comp(title("title", "Клан") + TextUtil.color(" &8• &fПригласить")));
        fill(inv);
        int slot = 0;
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (slot >= 53 || online.equals(player) || clanOf(online.getUniqueId()) != null) {
                continue;
            }
            holder.players.add(online.getUniqueId());
            inv.setItem(slot++, head(online.getUniqueId(), "&f" + online.getName(), "&7Клик — пригласить"));
        }
        if (slot == 0) {
            inv.setItem(22, btn(Material.BARRIER, "&7Некого приглашать", "&7Все игроки уже в кланах или никого нет"));
        }
        inv.setItem(53, btn(Material.ARROW, "&fНазад"));
        player.openInventory(inv);
    }

    private void openTop(Player player) {
        ClanHolder holder = new ClanHolder("top", clanKeyOf(player.getUniqueId()));
        Inventory inv = Bukkit.createInventory(holder, 54, comp(title("title-none", "Кланы") + TextUtil.color(" &8• &fТоп")));
        fill(inv);
        List<Clan> sorted = new ArrayList<>(clans.values());
        sorted.sort(Comparator.comparingDouble((Clan c) -> c.bank).reversed());
        int slot = 0;
        for (Clan clan : sorted) {
            if (slot >= 53) {
                break;
            }
            holder.clanKeys.add(clanKeyByName(clan.name));
            inv.setItem(slot++, btn(Material.WHITE_BANNER, "&#FFD93D" + clan.name,
                    "&7Лидер: &f" + nameOf(clan.leader),
                    "&7Участников: &f" + clan.members.size(),
                    "&7Банк: &f" + fmt(clan.bank)));
        }
        inv.setItem(53, btn(Material.ARROW, "&fНазад"));
        player.openInventory(inv);
    }

    private String clanKeyByName(String name) {
        for (Map.Entry<String, Clan> e : clans.entrySet()) {
            if (e.getValue().name.equals(name)) {
                return e.getKey();
            }
        }
        return null;
    }

    private void openLevels(Player player) {
        Clan clan = clanOf(player.getUniqueId());
        if (clan == null) {
            openNone(player);
            return;
        }
        ClanHolder holder = new ClanHolder("levels", clanKeyOf(player.getUniqueId()));
        Inventory inv = Bukkit.createInventory(holder, 54, comp(title("title", "Клан") + TextUtil.color(" &8• &fПрокачка")));
        fill(inv);
        List<Integer> sorted = new ArrayList<>(levels.keySet());
        java.util.Collections.sort(sorted);
        int slot = 0;
        for (int lvl : sorted) {
            if (slot >= 53) {
                break;
            }
            LevelDef def = levels.get(lvl);
            boolean current = clan.level == lvl;
            boolean unlocked = clan.level >= lvl;
            inv.setItem(slot++, btn(unlocked ? Material.EXPERIENCE_BOTTLE : Material.GLASS_BOTTLE,
                    (current ? "&#7CFC00" : unlocked ? "&#FFD93D" : "&8") + "Уровень " + lvl,
                    "&7Нужно опыта: &f" + fmt(def.xp()),
                    "&7Слотов игроков: &f" + def.members(),
                    "&7Лимит казны: &f" + (def.bank() < 0 ? "без лимита" : fmt(def.bank())),
                    "&7Точек дома: &f" + def.homes(),
                    current ? "&#7CFC00Текущий уровень клана" : unlocked ? "&7Открыто" : "&8Ещё не открыто"));
        }
        inv.setItem(53, btn(Material.ARROW, "&fНазад"));
        player.openInventory(inv);
    }

    private void openHomes(Player player) {
        Clan clan = clanOf(player.getUniqueId());
        if (clan == null) {
            openNone(player);
            return;
        }
        ClanHolder holder = new ClanHolder("homes", clanKeyOf(player.getUniqueId()));
        Inventory inv = Bukkit.createInventory(holder, 27, comp(title("title", "Клан") + TextUtil.color(" &8• &fДома клана")));
        fill(inv);
        boolean two = homesAllowed(clan) >= 2;
        inv.setItem(11, btn(Material.ENDER_PEARL, "&#C86BFFДом 1",
                clan.home == null || clan.home.isEmpty() ? "&7Не установлен" : "&7Клик — телепорт (3 сек)"));
        inv.setItem(15, two
                ? btn(Material.END_CRYSTAL, "&#FF9A3DДом 2",
                        clan.home2 == null || clan.home2.isEmpty() ? "&7Не установлен" : "&7Клик — телепорт (3 сек)")
                : btn(Material.BARRIER, "&8Дом 2 закрыт",
                        "&7Откроется на уровне: &f" + firstLevelWithHomes(2)));
        if (hasPerm(clan, player.getUniqueId(), "sethome")) {
            inv.setItem(20, btn(Material.COMPASS, "&fПоставить дом 1 сюда"));
            if (two) {
                inv.setItem(24, btn(Material.COMPASS, "&fПоставить дом 2 сюда"));
            }
        }
        inv.setItem(26, btn(Material.ARROW, "&fНазад"));
        player.openInventory(inv);
    }

    private int firstLevelWithHomes(int homes) {
        int best = -1;
        for (LevelDef def : levels.values()) {
            if (def.homes() >= homes && (best < 0 || def.level() < best)) {
                best = def.level();
            }
        }
        return best;
    }

    // ------------------------------------------------------------------ клики меню
    @EventHandler
    public void onMenuClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof ClanHolder)) {
            return;
        }
        ClanHolder holder = (ClanHolder) event.getInventory().getHolder();
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player)) {
            return;
        }
        Player player = (Player) event.getWhoClicked();
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= event.getInventory().getSize()) {
            return;
        }
        Clan clan = holder.clanKey == null ? clanOf(player.getUniqueId()) : clans.get(holder.clanKey);
        List<Integer> amounts = plugin.getConfig().getIntegerList("menu.amounts");
        if (amounts.isEmpty()) {
            amounts = List.of(100, 1000, 10000);
        }

        if (holder.view.equals("none")) {
            if (slot == 15) {
                openTop(player);
            }
            return;
        }
        if (holder.view.equals("top")) {
            if (slot == 53) {
                openMain(player);
            }
            return;
        }
        if (clan == null) {
            openNone(player);
            return;
        }
        if (holder.view.equals("clan")) {
            if (slot == 2) {
                openBank(player);
            } else if (slot == 4) {
                openMembers(player);
            } else if (slot == 6 && hasPerm(clan, player.getUniqueId(), "invite")) {
                openInvite(player);
            } else if (slot == 8) {
                if (homesAllowed(clan) >= 2) {
                    openHomes(player);
                } else {
                    player.closeInventory();
                    home(player, 1);
                }
            } else if (slot == 10 && hasPerm(clan, player.getUniqueId(), "sethome")) {
                if (homesAllowed(clan) >= 2) {
                    openHomes(player);
                } else {
                    setHome(player, 1);
                    openMain(player);
                }
            } else if (slot == 16) {
                openLevels(player);
            } else if (slot == 12) {
                openRoles(player);
            } else if (slot == 22 && !clan.leader.equals(player.getUniqueId())) {
                player.closeInventory();
                leave(player);
            } else if (slot == 26 && clan.leader.equals(player.getUniqueId())) {
                player.closeInventory();
                disband(player, false);
            }
            return;
        }
        if (holder.view.equals("levels")) {
            if (slot == 53) {
                openMain(player);
            }
            return;
        }
        if (holder.view.equals("homes")) {
            if (slot == 26) {
                openMain(player);
            } else if (slot == 11) {
                player.closeInventory();
                home(player, 1);
            } else if (slot == 15 && homesAllowed(clan) >= 2) {
                player.closeInventory();
                home(player, 2);
            } else if (slot == 20 && hasPerm(clan, player.getUniqueId(), "sethome")) {
                setHome(player, 1);
                openHomes(player);
            } else if (slot == 24 && homesAllowed(clan) >= 2 && hasPerm(clan, player.getUniqueId(), "sethome")) {
                setHome(player, 2);
                openHomes(player);
            }
            return;
        }
        if (holder.view.equals("bank")) {
            int[] depSlots = {10, 12, 14};
            int[] wdSlots = {19, 21, 23};
            for (int i = 0; i < depSlots.length && i < amounts.size(); i++) {
                if (slot == depSlots[i]) {
                    deposit(player, amounts.get(i));
                    openBank(player);
                    return;
                }
                if (slot == wdSlots[i]) {
                    if (hasPerm(clan, player.getUniqueId(), "withdraw")) {
                        withdraw(player, amounts.get(i));
                        openBank(player);
                    }
                    return;
                }
            }
            if (slot == 16) {
                deposit(player, (long) Math.floor(vault.available() ? vault.balance(player) : 0));
                openBank(player);
            } else if (slot == 25 && hasPerm(clan, player.getUniqueId(), "withdraw")) {
                withdraw(player, (long) Math.floor(clan.bank));
                openBank(player);
            } else if (slot == 26) {
                openMain(player);
            }
            return;
        }
        if (holder.view.equals("members")) {
            if (slot == 53) {
                openMain(player);
                return;
            }
            if (slot < holder.players.size() && holder.players.get(slot) != null) {
                openMember(player, holder.players.get(slot));
            }
            return;
        }
        if (holder.view.equals("member")) {
            if (slot == 26) {
                openMembers(player);
                return;
            }
            if (slot == 22) {
                kick(player, nameOf(holder.target));
                openMembers(player);
                return;
            }
            if (slot == 24 && clan.leader.equals(player.getUniqueId())) {
                transfer(player, nameOf(holder.target));
                openMain(player);
                return;
            }
            if (holder.actions.containsKey(slot)) {
                setRole(player, nameOf(holder.target), holder.actions.get(slot));
                openMember(player, holder.target);
            }
            return;
        }
        if (holder.view.equals("roles")) {
            if (slot == 26) {
                openMain(player);
                return;
            }
            if (holder.actions.containsKey(slot)) {
                openRolePerms(player, holder.actions.get(slot));
            }
            return;
        }
        if (holder.view.equals("roleperms")) {
            if (slot == 26) {
                openRoles(player);
                return;
            }
            if (holder.actions.containsKey(slot) && holder.roleId != null) {
                togglePerm(player, holder.roleId, holder.actions.get(slot));
                openRolePerms(player, holder.roleId);
            }
            return;
        }
        if (holder.view.equals("invite")) {
            if (slot == 53) {
                openMain(player);
                return;
            }
            if (slot < holder.players.size() && holder.players.get(slot) != null) {
                invite(player, nameOf(holder.players.get(slot)));
                openInvite(player);
            }
        }
    }
}
