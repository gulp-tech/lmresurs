package ru.voidpe.cases;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/** Кейсы: загрузка из конфига, предметы-кейсы, рулетка-анимация, выдача призов и объявления. */
public final class CaseService implements Listener {

    public record PrizeDef(String id, String name, Material material, int weight,
                           boolean announce, List<String> commands) {}

    public record CaseDef(String id, String displayName, Material material,
                          List<String> itemLore, List<PrizeDef> prizes) {}

    /** Маркер меню титулов. */
    static final class TitlesHolder implements InventoryHolder {
        @Override public Inventory getInventory() { return null; }
    }

    /**
     * Меню титулов: показывает, какие титулы уже есть у игрока, какой стоит
     * сейчас, позволяет переставить имеющийся и убрать титул совсем.
     * Владение титулами хранится в titles.yml; выигрыш из кейса записывается
     * автоматически. Текущий титул дополнительно сверяется с суффиксом LP.
     */
    public void openTitles(Player player) {
        CaseDef def = byId("titles");
        if (def == null) {
            msg(player, "case-not-found", null, null);
            return;
        }
        syncTitleFromLuckPerms(player, def);
        java.util.Set<String> owned = ownedTitles.getOrDefault(player.getUniqueId(), java.util.Set.of());
        String active = activeTitles.get(player.getUniqueId());
        Inventory inv = Bukkit.createInventory(new TitlesHolder(), 27,
                component(TextUtil.color("&#FFD93DТитулы &8• &fтвоя коллекция")));
        ItemStack filler = pane(false, " ");
        for (int slot = 0; slot < 27; slot++) {
            inv.setItem(slot, filler);
        }
        int total = 0;
        for (PrizeDef prize : def.prizes()) {
            total += prize.weight();
        }
        int slot = 10;
        for (PrizeDef prize : def.prizes()) {
            if (slot > 16) {
                break;
            }
            String titleId = prize.id().toLowerCase(Locale.ROOT);
            boolean has = owned.contains(titleId);
            boolean isActive = titleId.equals(active);
            ItemStack item = prizeItem(prize);
            ItemMeta meta = item.getItemMeta();
            if (meta != null) {
                double chance = total > 0 ? prize.weight() * 100.0 / total : 0;
                List<Component> lore = new ArrayList<>();
                lore.add(noItalic(component(TextUtil.color("&7Шанс из кейса: &f" + String.format(Locale.US, "%.1f", chance) + "%"))));
                if (isActive) {
                    lore.add(noItalic(component(TextUtil.color("&aСейчас установлен"))));
                    lore.add(noItalic(component(TextUtil.color("&7Чтобы снять, нажми «Убрать титул» ниже."))));
                } else if (has) {
                    lore.add(noItalic(component(TextUtil.color("&aЕсть у тебя"))));
                    lore.add(noItalic(component(TextUtil.color("&eНажми, чтобы установить"))));
                } else {
                    lore.add(noItalic(component(TextUtil.color("&cНет у тебя"))));
                    lore.add(noItalic(component(TextUtil.color("&7Выбивается только из: &f" + def.displayName()))));
                }
                lore.add(noItalic(component(TextUtil.color("&8Титул виден после ника в чате, TAB и скорборде"))));
                meta.lore(lore);
                meta.getPersistentDataContainer().set(titleKey, PersistentDataType.STRING, titleId);
                item.setItemMeta(meta);
            }
            inv.setItem(slot++, item);
        }
        ItemStack remove = new ItemStack(Material.BARRIER);
        ItemMeta removeMeta = remove.getItemMeta();
        if (removeMeta != null) {
            removeMeta.displayName(noItalic(component(TextUtil.color("&#FF5555Убрать титул"))));
            List<Component> lore = new ArrayList<>();
            if (active == null) {
                lore.add(noItalic(component(TextUtil.color("&7Сейчас титул не установлен."))));
            } else {
                PrizeDef activePrize = prizeById(def, active);
                lore.add(noItalic(component(TextUtil.color("&7Сейчас стоит: &f" + (activePrize == null ? active : activePrize.name())))));
                lore.add(noItalic(component(TextUtil.color("&eНажми, чтобы убрать титул совсем"))));
            }
            removeMeta.lore(lore);
            removeMeta.getPersistentDataContainer().set(titleKey, PersistentDataType.STRING, "__remove__");
            remove.setItemMeta(removeMeta);
        }
        inv.setItem(22, remove);
        player.openInventory(inv);
    }

    private PrizeDef prizeById(CaseDef def, String prizeId) {
        if (def == null || prizeId == null) {
            return null;
        }
        for (PrizeDef prize : def.prizes()) {
            if (prize.id().equalsIgnoreCase(prizeId)) {
                return prize;
            }
        }
        return null;
    }

    // ------------------------------------------------------------ титулы игрока
    private void loadTitles() {
        ownedTitles.clear();
        activeTitles.clear();
        if (!titlesFile.isFile()) {
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(titlesFile);
        ConfigurationSection root = yaml.getConfigurationSection("titles");
        if (root == null) {
            return;
        }
        for (String uuidText : root.getKeys(false)) {
            try {
                UUID uuid = UUID.fromString(uuidText);
                java.util.Set<String> owned = new java.util.LinkedHashSet<>();
                for (String id : yaml.getStringList("titles." + uuidText + ".owned")) {
                    owned.add(id.toLowerCase(Locale.ROOT));
                }
                ownedTitles.put(uuid, owned);
                String active = yaml.getString("titles." + uuidText + ".active", "");
                if (active != null && !active.isBlank()) {
                    activeTitles.put(uuid, active.toLowerCase(Locale.ROOT));
                }
            } catch (IllegalArgumentException ignored) {
            }
        }
    }

    private void saveTitles() {
        try {
            YamlConfiguration yaml = new YamlConfiguration();
            for (Map.Entry<UUID, java.util.Set<String>> e : ownedTitles.entrySet()) {
                yaml.set("titles." + e.getKey() + ".owned", new ArrayList<>(e.getValue()));
            }
            for (Map.Entry<UUID, String> e : activeTitles.entrySet()) {
                yaml.set("titles." + e.getKey() + ".active", e.getValue());
            }
            yaml.save(titlesFile);
        } catch (Throwable t) {
            plugin.getLogger().warning("Не удалось сохранить титулы: " + t.getMessage());
        }
    }

    private void addOwnedTitle(UUID uuid, String titleId) {
        ownedTitles.computeIfAbsent(uuid, k -> new java.util.LinkedHashSet<>())
                .add(titleId.toLowerCase(Locale.ROOT));
        // Право titles.<id> для меню титулов DeluxeMenus (проверка has permission).
        // Выдаётся и при выигрыше, и при синхронизации старого титула из суффикса LP.
        Player online = Bukkit.getPlayer(uuid);
        if (online != null) {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(),
                    "lp user " + online.getName() + " permission set titles."
                            + titleId.toLowerCase(Locale.ROOT) + " true");
        }
    }

    private void recordTitleWin(Player player, PrizeDef prize) {
        addOwnedTitle(player.getUniqueId(), prize.id());
        activeTitles.put(player.getUniqueId(), prize.id().toLowerCase(Locale.ROOT));
        saveTitles();
    }

    private void setTitle(Player player, CaseDef def, PrizeDef prize) {
        for (String cmd : prize.commands()) {
            String prepared = cmd.replace("{player}", player.getName());
            if (prepared.startsWith("/")) {
                prepared = prepared.substring(1);
            }
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), prepared);
        }
        recordTitleWin(player, prize);
        msg(player, "title-set", def, prize);
    }

    private void removeTitle(Player player, CaseDef def) {
        String active = activeTitles.get(player.getUniqueId());
        PrizeDef activePrize = prizeById(def, active);
        if (active == null && (luckPermsSuffix(player.getUniqueId()) == null
                || luckPermsSuffix(player.getUniqueId()).isBlank())) {
            msg(player, "title-none", def, null);
            return;
        }
        int priority = activePrize == null ? 90 : titleSuffixPriority(activePrize);
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(),
                "lp user " + player.getName() + " meta removesuffix " + priority);
        activeTitles.remove(player.getUniqueId());
        saveTitles();
        msg(player, "title-removed", def, activePrize);
    }

    /** Если титул ставили вручную/до обновления, подхватываем его из суффикса LuckPerms. */
    private void syncTitleFromLuckPerms(Player player, CaseDef def) {
        String suffix = luckPermsSuffix(player.getUniqueId());
        if (suffix == null || suffix.isBlank()) {
            if (activeTitles.remove(player.getUniqueId()) != null) {
                saveTitles();
            }
            return;
        }
        for (PrizeDef prize : def.prizes()) {
            String expected = titleSuffixValue(prize);
            if (expected != null && suffix.trim().equals(expected.trim())) {
                boolean changed = !prize.id().equalsIgnoreCase(activeTitles.get(player.getUniqueId()));
                addOwnedTitle(player.getUniqueId(), prize.id());
                activeTitles.put(player.getUniqueId(), prize.id().toLowerCase(Locale.ROOT));
                if (changed || !ownedTitles.getOrDefault(player.getUniqueId(), java.util.Set.of())
                        .contains(prize.id().toLowerCase(Locale.ROOT))) {
                    saveTitles();
                } else {
                    saveTitles();
                }
                return;
            }
        }
        // Суффикс есть, но это не титул из кейса (поставили вручную) — наш активный сбрасываем.
        if (activeTitles.remove(player.getUniqueId()) != null) {
            saveTitles();
        }
    }

    private String titleSuffixValue(PrizeDef prize) {
        for (String cmd : prize.commands()) {
            java.util.regex.Matcher matcher = java.util.regex.Pattern
                    .compile("(?i)meta\\s+setsuffix\\s+\\d+\\s+(.+)$")
                    .matcher(cmd.replace("{player}", "player"));
            if (matcher.find()) {
                String value = matcher.group(1).trim();
                if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
                    value = value.substring(1, value.length() - 1);
                }
                return value;
            }
        }
        return null;
    }

    private int titleSuffixPriority(PrizeDef prize) {
        for (String cmd : prize.commands()) {
            java.util.regex.Matcher matcher = java.util.regex.Pattern
                    .compile("(?i)meta\\s+setsuffix\\s+(\\d+)\\s+")
                    .matcher(cmd.replace("{player}", "player"));
            if (matcher.find()) {
                try {
                    return Integer.parseInt(matcher.group(1));
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return 90;
    }

    /** Действующий суффикс LuckPerms через мягкий reflection-хук (плагин работает и без LP). */
    private String luckPermsSuffix(UUID uuid) {
        try {
            Object luckPerms = null;
            for (Class<?> svc : Bukkit.getServicesManager().getKnownServices()) {
                if (svc.getName().equals("net.luckperms.api.LuckPerms")) {
                    org.bukkit.plugin.RegisteredServiceProvider<?> reg =
                            Bukkit.getServicesManager().getRegistration(svc);
                    if (reg != null) {
                        luckPerms = reg.getProvider();
                    }
                }
            }
            if (luckPerms == null) {
                return null;
            }
            Object userManager = luckPerms.getClass().getMethod("getUserManager").invoke(luckPerms);
            Object user = userManager.getClass().getMethod("getUser", UUID.class).invoke(userManager, uuid);
            if (user == null) {
                return null;
            }
            Object cached = user.getClass().getMethod("getCachedData").invoke(user);
            Object meta = null;
            for (java.lang.reflect.Method method : cached.getClass().getMethods()) {
                if (method.getName().equals("getMetaData") && method.getParameterCount() == 0) {
                    meta = method.invoke(cached);
                    break;
                }
            }
            if (meta == null) {
                return null;
            }
            Object value = meta.getClass().getMethod("getSuffix").invoke(meta);
            return value == null ? null : String.valueOf(value);
        } catch (Throwable t) {
            return null;
        }
    }

    private final VoidCases plugin;
    private final NamespacedKey idKey;
    private final NamespacedKey titleKey;
    private final Map<String, CaseDef> cases = new LinkedHashMap<>();
    private final java.util.Set<UUID> opening = new java.util.HashSet<>();
    private final Map<UUID, Map<String, Integer>> keys = new HashMap<>(); // виртуальные ключи: игрок -> кейс -> кол-во
    private final java.io.File keysFile;
    private final Map<String, String> placedCases = new LinkedHashMap<>(); // "world,x,y,z" -> id кейса
    private final Map<String, Integer> placedOpeningCounts = new HashMap<>(); // открытых анимаций у поставленного кейса
    private final java.io.File placedFile;
    private final java.io.File titlesFile;
    private final Map<UUID, java.util.Set<String>> ownedTitles = new HashMap<>();
    private final Map<UUID, String> activeTitles = new HashMap<>();

    public CaseService(VoidCases plugin) {
        this.plugin = plugin;
        this.idKey = new NamespacedKey(plugin, "case_id");
        this.titleKey = new NamespacedKey(plugin, "title_id");
        this.placedFile = new java.io.File(plugin.getDataFolder(), "placed-cases.yml");
        this.keysFile = new java.io.File(plugin.getDataFolder(), "keys.yml");
        this.titlesFile = new java.io.File(plugin.getDataFolder(), "titles.yml");
        load();
        loadPlaced();
        loadKeys();
        loadTitles();
    }

    // ------------------------------------------------------------------ загрузка
    public void load() {
        cases.clear();
        ConfigurationSection section = plugin.getConfig().getConfigurationSection("cases");
        if (section == null) {
            plugin.getLogger().warning("В config.yml нет секции cases!");
            return;
        }
        for (String id : section.getKeys(false)) {
            String path = "cases." + id;
            Material material = Material.matchMaterial(plugin.getConfig().getString(path + ".material", "CHEST"));
            if (material == null) {
                plugin.getLogger().warning("Кейс " + id + ": неизвестный материал, пропускаю");
                continue;
            }
            List<PrizeDef> prizes = new ArrayList<>();
            for (Map<?, ?> raw : plugin.getConfig().getMapList(path + ".prizes")) {
                String prizeId = String.valueOf(raw.get("id"));
                Material prizeMaterial = Material.matchMaterial(String.valueOf(raw.get("material")));
                if (prizeMaterial == null) {
                    plugin.getLogger().warning("Кейс " + id + ", приз " + prizeId + ": неизвестный материал");
                    continue;
                }
                int weight = raw.get("weight") instanceof Number ? ((Number) raw.get("weight")).intValue() : 100;
                boolean announce = Boolean.TRUE.equals(raw.get("announce"));
                List<String> commands = new ArrayList<>();
                if (raw.get("commands") instanceof List) {
                    for (Object cmd : (List<?>) raw.get("commands")) {
                        commands.add(String.valueOf(cmd));
                    }
                }
                prizes.add(new PrizeDef(
                        prizeId,
                        raw.get("name") == null ? prizeId : String.valueOf(raw.get("name")),
                        prizeMaterial,
                        Math.max(1, weight),
                        announce,
                        commands));
            }
            if (prizes.isEmpty()) {
                plugin.getLogger().warning("Кейс " + id + ": нет призов, пропускаю");
                continue;
            }
            cases.put(id.toLowerCase(Locale.ROOT), new CaseDef(
                    id,
                    plugin.getConfig().getString(path + ".display-name", id),
                    material,
                    plugin.getConfig().getStringList(path + ".item-lore"),
                    prizes));
        }
        plugin.getLogger().info("Загружено кейсов: " + cases.size());
    }

    public CaseDef byId(String id) {
        return id == null ? null : cases.get(id.toLowerCase(Locale.ROOT));
    }

    public List<String> ids() {
        return new ArrayList<>(cases.keySet());
    }

    public List<CaseDef> all() {
        return new ArrayList<>(cases.values());
    }

    // ------------------------------------------------------------------ поставленные кейсы
    private static String key(Location loc) {
        return loc.getWorld().getName() + "," + loc.getBlockX() + "," + loc.getBlockY() + "," + loc.getBlockZ();
    }

    private void loadPlaced() {
        placedCases.clear();
        if (!placedFile.isFile()) {
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(placedFile);
        for (String line : yaml.getStringList("cases")) {
            String[] parts = line.split(",", -1);
            if (parts.length == 5) {
                placedCases.put(parts[0] + "," + parts[1] + "," + parts[2] + "," + parts[3], parts[4]);
            }
        }
        plugin.getLogger().info("Поставленных кейсов: " + placedCases.size());
    }

    private void savePlaced() {
        try {
            YamlConfiguration yaml = new YamlConfiguration();
            List<String> lines = new ArrayList<>();
            for (Map.Entry<String, String> e : placedCases.entrySet()) {
                lines.add(e.getKey() + "," + e.getValue());
            }
            yaml.set("cases", lines);
            yaml.save(placedFile);
        } catch (Throwable t) {
            plugin.getLogger().warning("Не удалось сохранить поставленные кейсы: " + t.getMessage());
        }
    }

    // ------------------------------------------------------------------ виртуальные ключи
    private void loadKeys() {
        keys.clear();
        if (!keysFile.isFile()) {
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(keysFile);
        ConfigurationSection root = yaml.getConfigurationSection("keys");
        if (root == null) {
            return;
        }
        for (String uuidText : root.getKeys(false)) {
            try {
                UUID uuid = UUID.fromString(uuidText);
                Map<String, Integer> owned = new HashMap<>();
                ConfigurationSection per = root.getConfigurationSection(uuidText);
                if (per != null) {
                    for (String caseId : per.getKeys(false)) {
                        owned.put(caseId.toLowerCase(Locale.ROOT), per.getInt(caseId));
                    }
                }
                keys.put(uuid, owned);
            } catch (IllegalArgumentException ignored) {
            }
        }
    }

    private void saveKeys() {
        try {
            YamlConfiguration yaml = new YamlConfiguration();
            for (Map.Entry<UUID, Map<String, Integer>> e : keys.entrySet()) {
                for (Map.Entry<String, Integer> c : e.getValue().entrySet()) {
                    if (c.getValue() > 0) {
                        yaml.set("keys." + e.getKey() + "." + c.getKey(), c.getValue());
                    }
                }
            }
            yaml.save(keysFile);
        } catch (Throwable t) {
            plugin.getLogger().warning("Не удалось сохранить ключи: " + t.getMessage());
        }
    }

    public int keyCount(UUID player, String caseId) {
        Map<String, Integer> owned = keys.get(player);
        return owned == null ? 0 : owned.getOrDefault(caseId.toLowerCase(Locale.ROOT), 0);
    }

    public void addKeys(UUID player, String caseId, int amount) {
        keys.computeIfAbsent(player, k -> new HashMap<>())
                .merge(caseId.toLowerCase(Locale.ROOT), amount, Integer::sum);
        saveKeys();
    }

    private boolean takeKey(UUID player, String caseId) {
        int have = keyCount(player, caseId);
        if (have <= 0) {
            return false;
        }
        keys.get(player).put(caseId.toLowerCase(Locale.ROOT), have - 1);
        saveKeys();
        return true;
    }

    /** Вид кейса в этой точке или null. */
    public String placedCaseAt(Location loc) {
        return loc == null || loc.getWorld() == null ? null : placedCases.get(key(loc));
    }

    /**
     * Поставить кейс на блок: сам блок НЕ меняется (якорь возрождения останется
     * якорем, сундук — сундуком). Клик по нему перехватывается и открывает кейс.
     * Над блоком появляется голограмма с названием.
     */
    public void setPlaced(Block block, CaseDef def) {
        placedCases.put(key(block.getLocation()), def.id());
        savePlaced();
        ensureHologram(block.getLocation(), def);
    }

    /** Убрать поставленный кейс в точке; вернёт id кейса или null, если там кейса нет. */
    public String removePlaced(Location loc) {
        String removed = placedCases.remove(key(loc));
        if (removed != null) {
            savePlaced();
            removeHologram(loc);
        }
        return removed;
    }

    // ------------------------------------------------------------------ голограммы
    private static final String HOLO_TAG = "voidcases_holo";

    /** Голограмма над поставленным кейсом: название + подсказка. Видна в Bedrock. */
    public void ensureHologram(Location blockLoc, CaseDef def) {
        if (blockLoc == null || blockLoc.getWorld() == null || def == null) {
            return;
        }
        removeHologram(blockLoc);
        Location base = blockLoc.clone().add(0.5, 0, 0.5);
        spawnHoloLine(base.clone().add(0, 1.75, 0), def.displayName());
        String second = plugin.getConfig().getString("hologram.second-line", "&#FFD93DНажми, чтобы открыть");
        if (second != null && !second.isEmpty()) {
            spawnHoloLine(base.clone().add(0, 1.45, 0), second);
        }
    }

    private void spawnHoloLine(Location loc, String legacyText) {
        try {
            loc.getWorld().spawn(loc, ArmorStand.class, stand -> {
                stand.setVisible(false);
                stand.setGravity(false);
                stand.setMarker(true);
                stand.setInvulnerable(true);
                stand.setPersistent(true);
                stand.setCustomNameVisible(true);
                stand.customName(noItalic(component(TextUtil.color(legacyText))));
                stand.addScoreboardTag(HOLO_TAG);
            });
        } catch (Throwable t) {
            plugin.getLogger().warning("Голограмма кейса не создалась: " + t.getMessage());
        }
    }

    public void removeHologram(Location blockLoc) {
        if (blockLoc == null || blockLoc.getWorld() == null) {
            return;
        }
        Location center = blockLoc.clone().add(0.5, 1.6, 0.5);
        for (Entity entity : blockLoc.getWorld().getNearbyEntities(center, 1.2, 1.6, 1.2)) {
            if (entity.getScoreboardTags().contains(HOLO_TAG)) {
                entity.remove();
            }
        }
    }

    /** После старта: голограммы для уже загруженных чанков (остальные — по ChunkLoadEvent). */
    public void initHolograms() {
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            for (Map.Entry<String, String> e : placedCases.entrySet()) {
                String[] parts = e.getKey().split(",");
                org.bukkit.World world = Bukkit.getWorld(parts[0]);
                if (world == null) {
                    continue;
                }
                int x = Integer.parseInt(parts[1]);
                int y = Integer.parseInt(parts[2]);
                int z = Integer.parseInt(parts[3]);
                if (world.isChunkLoaded(x >> 4, z >> 4) && !isPlacedOpening(new Location(world, x, y, z))) {
                    CaseDef def = byId(e.getValue());
                    if (def != null) {
                        ensureHologram(new Location(world, x, y, z), def);
                    }
                }
            }
        }, 40L);
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        for (Map.Entry<String, String> e : placedCases.entrySet()) {
            String[] parts = e.getKey().split(",");
            if (!parts[0].equals(event.getWorld().getName())) {
                continue;
            }
            int x = Integer.parseInt(parts[1]);
            int y = Integer.parseInt(parts[2]);
            int z = Integer.parseInt(parts[3]);
            Location loc = new Location(event.getWorld(), x, y, z);
            if ((x >> 4) == event.getChunk().getX() && (z >> 4) == event.getChunk().getZ()
                    && !isPlacedOpening(loc)) {
                CaseDef def = byId(e.getValue());
                if (def != null) {
                    ensureHologram(loc, def);
                }
            }
        }
    }

    private boolean isPlacedOpening(Location blockLoc) {
        return blockLoc != null && placedOpeningCounts.getOrDefault(key(blockLoc), 0) > 0;
    }

    private void beginPlacedOpening(Location blockLoc) {
        String locKey = key(blockLoc);
        int count = placedOpeningCounts.getOrDefault(locKey, 0) + 1;
        placedOpeningCounts.put(locKey, count);
        if (count == 1) {
            removeHologram(blockLoc); // на время анимации голограмма не мешает награде
        }
    }

    private void endPlacedOpening(Location blockLoc, CaseDef def) {
        String locKey = key(blockLoc);
        int count = placedOpeningCounts.getOrDefault(locKey, 0) - 1;
        if (count > 0) {
            placedOpeningCounts.put(locKey, count);
            return;
        }
        placedOpeningCounts.remove(locKey);
        // Возвращаем голограмму только если кейс всё ещё стоит на этом блоке.
        if (def != null && def.id().equalsIgnoreCase(placedCaseAt(blockLoc))) {
            ensureHologram(blockLoc, def);
        }
    }

    /** Открыть поставленный кейс: списывает ВИРТУАЛЬНЫЙ ключ. Один блок — одно открытие за раз. */
    public void tryOpenPlaced(Player player, CaseDef def, Location blockLoc) {
        if (opening.contains(player.getUniqueId())) {
            msg(player, "already-opening", def, null);
            return;
        }
        if (isPlacedOpening(blockLoc)) {
            // Пока другой игрок крутит этот поставленный кейс, второй не откроет
            // его и не потратит ключ впустую.
            msg(player, "case-busy", def, null);
            return;
        }
        if (!takeKey(player.getUniqueId(), def.id())) {
            msg(player, "need-keys", def, null);
            return;
        }
        beginPlacedOpening(blockLoc);
        openCase(player, def, blockLoc.clone().add(0.5, 1.15, 0.5), blockLoc);
    }

    /** Открыть кейс из любого места (/cases open): анимация крутится над игроком. */
    public void tryOpen(Player player, CaseDef def) {
        if (opening.contains(player.getUniqueId())) {
            msg(player, "already-opening", def, null);
            return;
        }
        if (!takeKey(player.getUniqueId(), def.id())) {
            msg(player, "need-keys", def, null);
            return;
        }
        openCase(player, def, player.getLocation().add(0, 1.6, 0), null);
    }

    /**
     * Клик по поставленному кейсу: клик ВСЕГДА отменяется (якорь возрождения не
     * активируется, сундук не открывается) — вместо этого запускается открытие кейса.
     */
    @EventHandler
    public void onPlacedUse(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        Block block = event.getClickedBlock();
        if (block == null) {
            return;
        }
        String caseId = placedCaseAt(block.getLocation());
        if (caseId == null) {
            return;
        }
        event.setCancelled(true);
        CaseDef def = byId(caseId);
        if (def == null) {
            return;
        }
        tryOpenPlaced(event.getPlayer(), def, block.getLocation());
    }

    // поставленный кейс нельзя сломать (админ убирает его командой /cases remove)
    @EventHandler
    public void onBreakPlaced(BlockBreakEvent event) {
        if (placedCaseAt(event.getBlock().getLocation()) == null) {
            return;
        }
        if (!event.getPlayer().hasPermission("voidcases.admin")) {
            event.setCancelled(true);
            msg(event.getPlayer(), "case-protected", null, null);
            return;
        }
        removePlaced(event.getBlock().getLocation());
        msg(event.getPlayer(), "set-removed", null, null);
    }

    @EventHandler
    public void onExplodePlaced(EntityExplodeEvent event) {
        event.blockList().removeIf(b -> placedCaseAt(b.getLocation()) != null);
    }

    @EventHandler
    public void onExplodePlaced(BlockExplodeEvent event) {
        event.blockList().removeIf(b -> placedCaseAt(b.getLocation()) != null);
    }

    // ------------------------------------------------------------------ предметы
    public ItemStack createCaseItem(CaseDef def, int amount) {
        ItemStack stack = new ItemStack(def.material(), Math.max(1, amount));
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(noItalic(component(TextUtil.color(def.displayName()))));
        List<Component> lore = new ArrayList<>();
        for (String line : def.itemLore()) {
            lore.add(noItalic(component(TextUtil.color(line))));
        }
        meta.lore(lore);
        meta.getPersistentDataContainer().set(idKey, PersistentDataType.STRING, def.id());
        stack.setItemMeta(meta);
        return stack;
    }

    private ItemStack prizeItem(PrizeDef prize) {
        ItemStack stack = new ItemStack(prize.material());
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.displayName(noItalic(component(TextUtil.color(prize.name()))));
            stack.setItemMeta(meta);
        }
        return stack;
    }

    public String identify(ItemStack stack) {
        if (stack == null || stack.getType() == Material.AIR || !stack.hasItemMeta()) {
            return null;
        }
        return stack.getItemMeta().getPersistentDataContainer().get(idKey, PersistentDataType.STRING);
    }

    // ------------------------------------------------------------------ открытие (анимация СНАРУЖИ, в мире)
    /**
     * Старые физические предметы-кейсы: ПКМ превращает их в виртуальные ключи
     * (по одному за предмет). Дальше открытие — только виртуальными ключами.
     */
    @EventHandler
    public void onUse(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        Player player = event.getPlayer();
        ItemStack hand = player.getInventory().getItemInMainHand();
        String id = identify(hand);
        if (id == null) {
            return;
        }
        CaseDef def = byId(id);
        if (def == null) {
            return;
        }
        event.setCancelled(true);
        int amount = Math.max(1, hand.getAmount());
        hand.setAmount(0);
        addKeys(player.getUniqueId(), def.id(), amount);
        String text = plugin.getConfig().getString("messages.key-added",
                        "&aПредмет превращён в виртуальные ключи: {case} &7x{amount}&a. Открыть: &f/cases open {id}")
                .replace("{case}", def.displayName())
                .replace("{amount}", String.valueOf(amount))
                .replace("{id}", def.id());
        player.sendMessage(component(prefix() + TextUtil.color(text)));
    }

    /**
     * Анимация открытия СНАРУЖИ: над кейсом (или над игроком) крутится предмет,
     * призы мелькают и замедляются, в конце — выигрыш, фейерверк и приз.
     */
    private void openCase(Player player, CaseDef def, Location anchor, Location placedBlock) {
        opening.add(player.getUniqueId());
        playSound(player, cfg("animation.open-sound", "block.chest.open"), 1.0f);

        final PrizeDef winningPrize = randomPrize(def);
        final Item display;
        final ArmorStand prizeLabel;
        try {
            PrizeDef firstPrize = randomPrize(def);
            display = anchor.getWorld().dropItem(anchor, prizeItem(firstPrize));
            display.setGravity(false);
            display.setInvulnerable(true);
            display.setPickupDelay(Integer.MAX_VALUE);
            display.setVelocity(new org.bukkit.util.Vector(0, 0, 0));
            prizeLabel = spawnPrizeLabel(anchor.clone().add(0, 0.55, 0), firstPrize);
        } catch (Throwable t) {
            opening.remove(player.getUniqueId());
            if (placedBlock != null) {
                endPlacedOpening(placedBlock, def);
            }
            plugin.getLogger().warning("Не удалось запустить анимацию кейса: " + t.getMessage());
            return;
        }

        final int duration = Math.max(40, plugin.getConfig().getInt("animation.duration-ticks", 90));
        final int finalHold = Math.max(0, plugin.getConfig().getInt("animation.final-hold-ticks", 20));
        new BukkitRunnable() {
            int ticks = 0;
            int sinceUpdate = 0;
            int holdTicks = 0;
            boolean finishing = false;
            boolean cleaned = false;
            PrizeDef shownPrize;

            private void cleanup(boolean awardPrize) {
                if (cleaned) {
                    return;
                }
                cleaned = true;
                display.remove();
                if (prizeLabel != null) {
                    prizeLabel.remove();
                }
                opening.remove(player.getUniqueId());
                if (placedBlock != null) {
                    endPlacedOpening(placedBlock, def); // голограмма возвращается после анимации
                }
                if (awardPrize) {
                    award(player, def, winningPrize, anchor);
                }
            }

            private void showPrize(PrizeDef prize) {
                shownPrize = prize;
                display.setItemStack(prizeItem(prize));
                if (prizeLabel != null && prizeLabel.isValid()) {
                    prizeLabel.customName(noItalic(component(TextUtil.color(prize.name()))));
                }
            }

            @Override
            public void run() {
                if (!player.isOnline() || !display.isValid()) {
                    // Игрок вышел до конца — ключ возвращаем, чтобы награда не сгорела из-за дисконнекта.
                    if (player != null && !finishing) {
                        addKeys(player.getUniqueId(), def.id(), 1);
                    }
                    cleanup(false);
                    cancel();
                    return;
                }

                if (finishing) {
                    // Выигрыш спокойно висит над кейсом, чтобы игрок успел прочитать название.
                    Location settled = anchor.clone().add(0, 0.22 + Math.sin(holdTicks * 0.25) * 0.02, 0);
                    display.teleport(settled);
                    if (prizeLabel != null && prizeLabel.isValid()) {
                        prizeLabel.teleport(settled.clone().add(0, 0.55, 0));
                    }
                    holdTicks++;
                    if (holdTicks >= finalHold) {
                        cleanup(true);
                        cancel();
                    }
                    return;
                }

                ticks++;
                sinceUpdate++;
                double progress = Math.min(1.0, (double) ticks / duration);
                // Плавный подъём по дуге, лёгкое кружение и постепенная остановка в центре.
                double angle = ticks * (0.42 - 0.27 * progress);
                double radius = 0.16 * (1.0 - progress);
                Location spin = anchor.clone().add(
                        Math.cos(angle) * radius,
                        Math.sin(progress * Math.PI) * 0.30 + Math.sin(ticks * 0.45) * 0.025,
                        Math.sin(angle) * radius);
                spin.setYaw((float) Math.toDegrees(angle));
                display.teleport(spin);
                if (prizeLabel != null && prizeLabel.isValid()) {
                    prizeLabel.teleport(spin.clone().add(0, 0.55, 0));
                }

                // Интервал смены наград плавно растёт: 2 -> 12 тиков (без резких ступеней).
                int interval = Math.max(2, (int) Math.round(2 + 10 * progress * progress));
                if (sinceUpdate >= interval && ticks < duration) {
                    sinceUpdate = 0;
                    showPrize(randomPrize(def));
                    playSound(player, cfg("animation.spin-sound", "block.note_block.hat"),
                            0.75f + (float) (0.55f * (1.0 - progress)));
                }
                if (ticks >= duration) {
                    showPrize(winningPrize); // финальный предмет совпадает с реально выданной наградой
                    playSound(player, cfg("animation.win-sound", "entity.player.levelup"), 1.0f);
                    finishing = true;
                }
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    /** Подпись текущей награды над крутящимся предметом. */
    private ArmorStand spawnPrizeLabel(Location loc, PrizeDef prize) {
        if (!plugin.getConfig().getBoolean("animation.show-prize-name", true)) {
            return null;
        }
        try {
            return loc.getWorld().spawn(loc, ArmorStand.class, stand -> {
                stand.setVisible(false);
                stand.setGravity(false);
                stand.setMarker(true);
                stand.setInvulnerable(true);
                stand.setPersistent(false);
                stand.setCustomNameVisible(true);
                stand.customName(noItalic(component(TextUtil.color(prize.name()))));
                stand.addScoreboardTag("voidcases_animation_label");
            });
        } catch (Throwable t) {
            plugin.getLogger().warning("Не удалось создать подпись награды: " + t.getMessage());
            return null;
        }
    }

    /** Выдать приз: команды консоли, сообщения, заголовок, объявление, фейерверк. */
    private void award(Player player, CaseDef def, PrizeDef prize, Location anchor) {
        playSound(player, cfg("animation.win-sound", "entity.player.levelup"), 1.0f);
        for (String cmd : prize.commands()) {
            String prepared = cmd.replace("{player}", player.getName());
            if (prepared.startsWith("/")) {
                prepared = prepared.substring(1);
            }
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), prepared);
        }
        if (def.id().equalsIgnoreCase("titles")) {
            recordTitleWin(player, prize); // меню /titles сразу покажет: титул есть и установлен
        }
        msg(player, "win", def, prize);
        player.showTitle(Title.title(
                noItalic(component(TextUtil.color(prize.name()))),
                noItalic(component(TextUtil.color(def.displayName())))));
        if (prize.announce()) {
            String text = plugin.getConfig().getString("messages.win-broadcast", "");
            if (text != null && !text.isEmpty()) {
                Component broadcast = component(prefix() + TextUtil.color(text
                        .replace("{player}", player.getName())
                        .replace("{case}", def.displayName())
                        .replace("{prize}", prize.name())));
                Bukkit.broadcast(broadcast);
            }
        }
        if (plugin.getConfig().getBoolean("animation.celebration", true)) {
            celebrate(player, anchor);
        }
    }

    private PrizeDef randomPrize(CaseDef def) {
        int total = 0;
        for (PrizeDef prize : def.prizes()) {
            total += prize.weight();
        }
        int roll = ThreadLocalRandom.current().nextInt(total);
        for (PrizeDef prize : def.prizes()) {
            roll -= prize.weight();
            if (roll < 0) {
                return prize;
            }
        }
        return def.prizes().get(def.prizes().size() - 1);
    }

    /**
     * Праздничный финал БЕЗ сущности фейерверка: взрыв настоящего фейерверка
     * наносил урон игрокам рядом с кейсом. Частицы и звук урона не наносят.
     */
    private void celebrate(Player player, Location anchor) {
        try {
            Location loc = anchor == null ? player.getLocation().add(0, 1, 0) : anchor.clone().add(0, 0.35, 0);
            if (loc.getWorld() == null) {
                return;
            }
            loc.getWorld().spawnParticle(Particle.END_ROD, loc, 28, 0.35, 0.45, 0.35, 0.02);
            loc.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, loc, 18, 0.3, 0.3, 0.3, 0.01);
            playSound(player, cfg("animation.celebration-sound", "entity.firework_rocket.launch"), 1.0f);
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------------ защита меню титулов
    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof TitlesHolder)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player)) {
            return;
        }
        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || !clicked.hasItemMeta()) {
            return;
        }
        String titleId = clicked.getItemMeta().getPersistentDataContainer()
                .get(titleKey, PersistentDataType.STRING);
        if (titleId == null) {
            return;
        }
        Player player = (Player) event.getWhoClicked();
        CaseDef def = byId("titles");
        if (def == null) {
            return;
        }
        if (titleId.equals("__remove__")) {
            removeTitle(player, def);
            player.closeInventory();
            openTitles(player);
            return;
        }
        PrizeDef prize = prizeById(def, titleId);
        if (prize == null) {
            return;
        }
        syncTitleFromLuckPerms(player, def);
        if (!ownedTitles.getOrDefault(player.getUniqueId(), java.util.Set.of())
                .contains(prize.id().toLowerCase(Locale.ROOT))) {
            msg(player, "title-not-owned", def, prize);
            return;
        }
        setTitle(player, def, prize);
        player.closeInventory();
        openTitles(player);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof TitlesHolder) {
            event.setCancelled(true);
        }
    }

    // ------------------------------------------------------------------ утилиты
    /** Обычное ванильное стекло для меню (как просил владелец: без кастомных иконок). */
    private ItemStack pane(boolean yellow, String name) {
        ItemStack stack = new ItemStack(yellow ? Material.YELLOW_STAINED_GLASS_PANE : Material.BLACK_STAINED_GLASS_PANE);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.displayName(noItalic(component(TextUtil.color(name))));
            stack.setItemMeta(meta);
        }
        return stack;
    }

    private String cfg(String path, String def) {
        return plugin.getConfig().getString(path, def);
    }

    private String prefix() {
        return TextUtil.color(plugin.getConfig().getString("messages.prefix", "&#C86BFFVoidPE &8» &f"));
    }

    public void msg(Player player, String key, CaseDef def, PrizeDef prize) {
        String text = plugin.getConfig().getString("messages." + key, "");
        if (text == null || text.isEmpty()) {
            return;
        }
        if (def != null) {
            text = text.replace("{case}", def.displayName());
        }
        if (prize != null) {
            text = text.replace("{prize}", prize.name());
        }
        player.sendMessage(component(prefix() + TextUtil.color(text)));
    }

    private void playSound(Player player, String sound, float pitch) {
        if (sound == null || sound.isEmpty()) {
            return;
        }
        try {
            player.playSound(player.getLocation(), sound.toLowerCase(Locale.ROOT), 0.8f, pitch);
        } catch (Throwable ignored) {
        }
    }

    static Component component(String legacy) {
        return LegacyComponentSerializer.legacySection().deserialize(legacy == null ? "" : legacy);
    }

    static Component noItalic(Component component) {
        return component.decoration(TextDecoration.ITALIC, false);
    }
}
