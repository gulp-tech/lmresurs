package ru.voidpe.items;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.block.Block;
import org.bukkit.block.CreatureSpawner;
import org.bukkit.block.data.BlockData;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.meta.ArmorMeta;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.inventory.meta.trim.ArmorTrim;
import org.bukkit.inventory.meta.trim.TrimMaterial;
import org.bukkit.inventory.meta.trim.TrimPattern;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/** Загрузка предметов из конфига, выдача, рецепты, вся механика (трапка, пыль, дезориентация, лом). */
public final class ItemsService implements Listener {

    private final VoidItems plugin;
    private final WGHook worldGuard;
    private final WEHook worldEdit;
    private final NamespacedKey idKey;
    /** Метки старых версий (FaralItems) — чтобы старые предметы в инвентарях продолжали работать. */
    private final List<NamespacedKey> legacyIdKeys = new ArrayList<>();
    private final NamespacedKey spawnerMobKey; // моб внутри выпавшего из лома спавнера (страховка к BlockStateMeta)
    private final Map<String, CustomItem> items = new LinkedHashMap<>();
    private final Map<UUID, Map<String, Long>> cooldowns = new HashMap<>();
    private final List<Trap> traps = new ArrayList<>();
    private final Map<String, Trap> trapBlocks = new HashMap<>(); // "world:x,y,z" -> трапка
    private final Map<UUID, Long> revealed = new HashMap<>(); // подсвеченные явной пылью: uuid -> конец (мс)

    private static final class Trap {
        UUID owner;
        UUID target;
        final Map<Location, BlockData> changed = new LinkedHashMap<>();
        int minX, maxX, minY, maxY, minZ, maxZ;
        String world;
        BukkitTask task;
    }

    public ItemsService(VoidItems plugin) {
        this.plugin = plugin;
        this.worldGuard = new WGHook(plugin.getLogger());
        this.worldEdit = new WEHook(plugin.getLogger());
        this.idKey = new NamespacedKey(plugin, "item_id");
        for (String namespace : new String[]{"faralitems", "faral_items", "faral", "voidpe"}) {
            try {
                legacyIdKeys.add(new NamespacedKey(namespace, "item_id"));
            } catch (IllegalArgumentException ignored) {
            }
        }
        this.spawnerMobKey = new NamespacedKey(plugin, "spawner_mob");
        load();
        registerRecipes();
        // Своя схематика трапки: plugins/VoidItems/schematics/<файл из конфига trapka.schematic>
        java.io.File schemDir = new java.io.File(plugin.getDataFolder(), "schematics");
        schemDir.mkdirs();
        CustomItem trapItem = items.get("trapka");
        if (trapItem != null && trapItem.num("use-schematic", 1) > 0) {
            String fileName = trapItem.str("schematic", "trap.schem");
            java.io.File schemFile = new java.io.File(schemDir, fileName);
            if (!schemFile.isFile()) {
                // Имя не совпало — берём любой .schem из папки, чтобы не падало из-за опечатки.
                java.io.File[] found = schemDir.listFiles((dir, name) -> {
                    String n = name.toLowerCase(java.util.Locale.ROOT);
                    return n.endsWith(".schem") || n.endsWith(".schematic");
                });
                if (found != null && found.length > 0) {
                    java.util.Arrays.sort(found);
                    schemFile = found[0];
                    plugin.getLogger().info("Файла " + fileName + " нет, использую схематику из папки: " + schemFile.getName());
                }
            }
            if (schemFile.isFile()) {
                if (!worldEdit.loadSchematic(schemFile)) {
                    plugin.getLogger().warning("Схематика " + schemFile.getName() + " не загрузилась (см. выше) — трапка будет встроенной клеткой 3x3.");
                }
            } else {
                plugin.getLogger().info("Схематики в " + schemDir.getPath() + " нет — трапка будет встроенной клеткой 3x3. "
                        + "Положи туда .schem от //schem save, если нужна своя.");
            }
        }
        // пока пыль действует: повторно выпитый инвиз не прячет, белая обводка (свечение) не гаснет
        Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            long now = System.currentTimeMillis();
            for (UUID uuid : new ArrayList<>(revealed.keySet())) {
                Long until = revealed.get(uuid);
                Player p = Bukkit.getPlayer(uuid);
                if (until == null || until <= now) {
                    revealed.remove(uuid);
                    if (p != null) {
                        p.setGlowing(false);
                        p.removePotionEffect(PotionEffectType.GLOWING);
                    }
                    continue;
                }
                if (p == null || !p.isOnline() || p.isDead()) {
                    continue;
                }
                p.removePotionEffect(PotionEffectType.INVISIBILITY);
                if (!p.isGlowing()) {
                    p.setGlowing(true);
                }
                if (!p.hasPotionEffect(PotionEffectType.GLOWING)) {
                    int leftTicks = (int) Math.max(20L, (until - now) / 50L);
                    p.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, leftTicks, 0, true, false, true));
                }
            }
        }, 4L, 4L);
    }

    // ------------------------------------------------------------------ загрузка
    public void load() {
        items.clear();
        ConfigurationSection section = plugin.getConfig().getConfigurationSection("items");
        if (section == null) {
            plugin.getLogger().warning("В config.yml нет секции items!");
            return;
        }
        for (String id : section.getKeys(false)) {
            String path = "items." + id;
            ConfigurationSection itemSection = section.getConfigurationSection(id);
            CustomItem.Kind kind;
            try {
                kind = CustomItem.Kind.valueOf(plugin.getConfig().getString(path + ".kind", "DUST").toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("Предмет " + id + ": неизвестный kind, пропускаю");
                continue;
            }
            Material material = Material.matchMaterial(plugin.getConfig().getString(path + ".material", "SUGAR"));
            if (material == null) {
                plugin.getLogger().warning("Предмет " + id + ": неизвестный материал, пропускаю");
                continue;
            }
            Map<Character, Material> ingredients = new HashMap<>();
            ConfigurationSection ing = plugin.getConfig().getConfigurationSection(path + ".recipe.ingredients");
            if (ing != null) {
                for (String k : ing.getKeys(false)) {
                    Material m = Material.matchMaterial(ing.getString(k, ""));
                    if (m != null && !k.isEmpty()) {
                        ingredients.put(k.charAt(0), m);
                    }
                }
            }
            CustomItem.RecipeDef recipe = new CustomItem.RecipeDef(
                    plugin.getConfig().getBoolean(path + ".recipe.enabled", false),
                    plugin.getConfig().getStringList(path + ".recipe.shape"),
                    ingredients);
            List<CustomItem.EnchantDef> enchants = new ArrayList<>();
            if (itemSection != null) {
                for (Map<?, ?> raw : itemSection.getMapList("enchants")) {
                    String key = String.valueOf(raw.get("type"));
                    Object levelRaw = raw.get("level");
                    int level = levelRaw instanceof Number ? ((Number) levelRaw).intValue() : 1;
                    if (key != null && !"null".equals(key) && !key.isBlank()) {
                        enchants.add(new CustomItem.EnchantDef(key, Math.max(1, level)));
                    }
                }
            }
            items.put(id, new CustomItem(
                    id, kind,
                    plugin.getConfig().getBoolean(path + ".enabled", true),
                    plugin.getConfig().getString(path + ".display-name", id),
                    material,
                    plugin.getConfig().getStringList(path + ".lore"),
                    plugin.getConfig().getBoolean(path + ".glow", true),
                    recipe, enchants,
                    plugin.getConfig().getString(path + ".trim-material", ""),
                    plugin.getConfig().getString(path + ".trim-pattern", ""),
                    itemSection));
        }
        plugin.getLogger().info("Загружено кастомных предметов: " + items.size());
    }

    public void registerRecipes() {
        for (CustomItem item : items.values()) {
            NamespacedKey key = new NamespacedKey(plugin, "item_" + item.id());
            Bukkit.removeRecipe(key);
            if (!item.enabled() || !item.recipe().enabled() || item.recipe().shape().isEmpty()) {
                continue;
            }
            ShapedRecipe recipe = new ShapedRecipe(key, createItem(item, 1));
            recipe.shape(item.recipe().shape().toArray(new String[0]));
            item.recipe().ingredients().forEach(recipe::setIngredient);
            Bukkit.addRecipe(recipe);
            plugin.getLogger().info("Рецепт предмета: " + item.id());
        }
    }

    public static PotionEffectType potionType(String name) {
        // getByName есть во всех версиях, реестр — только запасной путь
        // (RegistryAccess сам разбирается с сигнатурой Registry#get).
        PotionEffectType type = PotionEffectType.getByName(name.toUpperCase(Locale.ROOT));
        if (type == null) {
            type = RegistryAccess.find(Registry.EFFECT, name);
        }
        return type;
    }

    private static Enchantment enchantment(String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        String normalized = key.toLowerCase(Locale.ROOT).replace("minecraft:", "");
        Enchantment enchantment = Enchantment.getByKey(NamespacedKey.minecraft(normalized));
        if (enchantment == null) {
            enchantment = RegistryAccess.find(Registry.ENCHANTMENT, normalized);
        }
        if (enchantment == null) {
            enchantment = Enchantment.getByName(key.toUpperCase(Locale.ROOT));
        }
        return enchantment;
    }

    private static TrimMaterial trimMaterial(String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        return RegistryAccess.find(Registry.TRIM_MATERIAL,
                key.toLowerCase(Locale.ROOT).replace("minecraft:", ""));
    }

    private static TrimPattern trimPattern(String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        return RegistryAccess.find(Registry.TRIM_PATTERN,
                key.toLowerCase(Locale.ROOT).replace("minecraft:", ""));
    }

    // ------------------------------------------------------------------ предметы
    public ItemStack createItem(CustomItem item, int amount) {
        ItemStack stack = new ItemStack(item.material());
        // У зелий максимальный стак — 1: без ограничения клиент показывал «пустой» стак.
        stack.setAmount(Math.max(1, Math.min(amount, stack.getMaxStackSize())));
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(component(TextUtil.color(item.displayName())));
        List<Component> lore = new ArrayList<>();
        for (String line : item.lore()) {
            lore.add(component(TextUtil.color(line)));
        }
        meta.lore(lore);
        if (item.kind() == CustomItem.Kind.ARMOR || item.kind() == CustomItem.Kind.GEAR) {
            Component name = meta.displayName();
            if (name != null) {
                meta.displayName(name.decoration(TextDecoration.ITALIC, false));
            }
            List<Component> fixedLore = new ArrayList<>();
            for (Component line : lore) {
                fixedLore.add(line.decoration(TextDecoration.ITALIC, false));
            }
            meta.lore(fixedLore);
        }
        if (item.glow()) {
            meta.addEnchant(Enchantment.UNBREAKING, 1, true);
            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
        }
        for (CustomItem.EnchantDef enchantDef : item.enchants()) {
            Enchantment enchantment = enchantment(enchantDef.key());
            if (enchantment != null) {
                meta.addEnchant(enchantment, enchantDef.level(), true);
            }
        }
        if (meta instanceof ArmorMeta) {
            ArmorMeta armorMeta = (ArmorMeta) meta;
            TrimMaterial trimMaterial = trimMaterial(item.trimMaterial());
            TrimPattern trimPattern = trimPattern(item.trimPattern());
            if (trimMaterial != null && trimPattern != null) {
                armorMeta.setTrim(new ArmorTrim(trimMaterial, trimPattern));
            }
        }
        if (item.kind() == CustomItem.Kind.POTION && meta instanceof PotionMeta) {
            PotionMeta potion = (PotionMeta) meta;
            for (CustomItem.EffectDef e : item.effects("effects")) {
                potion.addCustomEffect(new PotionEffect(e.type(), e.durationSeconds() * 20, e.amplifier()), true);
            }
            String colorHex = item.str("color", "#FF5555").replace("#", "");
            try {
                potion.setColor(org.bukkit.Color.fromRGB(Integer.parseInt(colorHex, 16)));
            } catch (NumberFormatException ignored) {
            }
        }
        meta.getPersistentDataContainer().set(idKey, PersistentDataType.STRING, item.id());
        stack.setItemMeta(meta);
        return stack;
    }

    /**
     * Узнаёт предмет по скрытой метке. Поддерживает предметы, выданные старыми
     * версиями (FaralItems): у них другая метка, и раньше они вообще не узнавались —
     * из-за этого око эндера улетало как ванильное, а пыль и трапка не работали.
     * Старая метка при первом использовании переписывается на текущую.
     */
    public String identify(ItemStack stack) {
        if (stack == null || stack.getType() == Material.AIR || !stack.hasItemMeta()) {
            return null;
        }
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return null;
        }
        PersistentDataContainer data = meta.getPersistentDataContainer();
        String current = data.get(idKey, PersistentDataType.STRING);
        if (current != null && items.containsKey(current)) {
            return current;
        }
        if (plugin.getConfig().getBoolean("identity.migrate-legacy", true)) {
            for (NamespacedKey legacy : legacyIdKeys) {
                String old = data.get(legacy, PersistentDataType.STRING);
                if (old != null && items.containsKey(old)) {
                    return migrateTag(stack, meta, old);
                }
            }
            if (plugin.getConfig().getBoolean("identity.match-by-name", true)) {
                String byName = byDisplayName(meta);
                if (byName != null) {
                    return migrateTag(stack, meta, byName);
                }
            }
        }
        return null;
    }

    /** Переписывает метку предмета на текущую (voiditems:item_id) и возвращает id. */
    private String migrateTag(ItemStack stack, ItemMeta meta, String id) {
        meta.getPersistentDataContainer().set(idKey, PersistentDataType.STRING, id);
        stack.setItemMeta(meta);
        return id;
    }

    private String byDisplayName(ItemMeta meta) {
        if (!meta.hasDisplayName()) {
            return null;
        }
        Component name = meta.displayName();
        if (name == null) {
            return null;
        }
        String plain = LegacyComponentSerializer.legacySection().serialize(name);
        for (CustomItem item : items.values()) {
            if (item.enabled() && plain.equals(TextUtil.color(item.displayName()))) {
                return item.id();
            }
        }
        return null;
    }

    public CustomItem byId(String id) {
        CustomItem item = items.get(id);
        return item != null && item.enabled() ? item : null;
    }

    public Collection<CustomItem> all() {
        List<CustomItem> result = new ArrayList<>();
        for (CustomItem item : items.values()) {
            if (item.enabled()) {
                result.add(item);
            }
        }
        return result;
    }

    // ------------------------------------------------------------------ запретные зоны
    /**
     * В этой точке кастомные предметы запрещены: мир целиком или регион
     * WorldGuard. Исключение — разрешённые регионы: например, в мире world
     * предметы запрещены везде, кроме PvP-региона pvp.
     */
    public boolean isBanned(Location loc) {
        if (loc == null || loc.getWorld() == null) {
            return false;
        }
        if (worldGuard.inRegion(loc, plugin.getConfig().getStringList("banned.allowed-worldguard-regions"))) {
            return false;
        }
        if (plugin.getConfig().getStringList("banned.worlds").contains(loc.getWorld().getName())) {
            return true;
        }
        return worldGuard.inBannedRegion(loc, plugin.getConfig().getStringList("banned.worldguard-regions"));
    }

    // ------------------------------------------------------------------ кулдауны
    private long cooldownLeft(UUID uuid, CustomItem item) {
        Map<String, Long> byItem = cooldowns.get(uuid);
        if (byItem == null) {
            return 0;
        }
        Long until = byItem.get(item.id());
        if (until == null) {
            return 0;
        }
        long left = (until - System.currentTimeMillis()) / 1000L;
        return Math.max(0, left);
    }

    private void startCooldown(UUID uuid, CustomItem item) {
        long seconds = (long) item.num("cooldown-seconds", 30);
        if (seconds <= 0) {
            return;
        }
        cooldowns.computeIfAbsent(uuid, k -> new HashMap<>())
                .put(item.id(), System.currentTimeMillis() + seconds * 1000L);
    }

    /** Явно записывает уменьшённый стак обратно в слот: на Bedrock иначе предмет «не тратится». */
    private void consume(Player player, EquipmentSlot slot) {
        boolean main = slot != EquipmentSlot.OFF_HAND;
        ItemStack hand = handItem(player, slot);
        if (hand == null || hand.getType() == Material.AIR) {
            return;
        }
        int left = hand.getAmount() - 1;
        ItemStack updated = left <= 0 ? null : hand.clone();
        if (updated != null) {
            updated.setAmount(left);
        }
        if (main) {
            player.getInventory().setItemInMainHand(updated);
        } else {
            player.getInventory().setItemInOffHand(updated);
        }
        player.updateInventory(); // клиенту Bedrock нужен явный синк слота
    }

    private static ItemStack handItem(Player player, EquipmentSlot slot) {
        return slot == EquipmentSlot.OFF_HAND
                ? player.getInventory().getItemInOffHand()
                : player.getInventory().getItemInMainHand();
    }

    private void msg(Player player, String key, CustomItem item, String... pairs) {
        String text = plugin.getConfig().getString("messages." + key, "");
        if (text == null || text.isEmpty()) {
            return;
        }
        if (item != null) {
            text = text.replace("{item}", item.displayName());
        }
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            text = text.replace(pairs[i], pairs[i + 1]);
        }
        String prefix = TextUtil.color(plugin.getConfig().getString("messages.prefix", "&#C86BFFVoidPE &8» &f"));
        player.sendMessage(component(prefix + TextUtil.color(text)));
    }

    // ------------------------------------------------------------------ использование (ПКМ)
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onUse(PlayerInteractEvent event) {
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        Player player = event.getPlayer();
        // Рука берётся из события: на Bedrock "использование в воздухе" иногда приходит
        // как OFF_HAND, и раньше такие нажатия молча терялись.
        EquipmentSlot hand = event.getHand() == null ? EquipmentSlot.HAND : event.getHand();
        if (action == Action.RIGHT_CLICK_BLOCK && event.getClickedBlock() != null
                && event.getClickedBlock().getType().isInteractable()) {
            return; // сундук, дверь, кнопка: пусть работает ванильное действие, а не предмет
        }
        String id = identify(handItem(player, hand));
        if (id == null) {
            return;
        }
        CustomItem item = byId(id);
        if (item == null) {
            return;
        }
        if (item.kind() == CustomItem.Kind.LOM || item.kind() == CustomItem.Kind.ARMOR
                || item.kind() == CustomItem.Kind.GEAR) {
            return; // лом работает при ломании блока, броня и инструменты — как обычные
        }
        if (item.kind() == CustomItem.Kind.POTION) {
            // Зелье пьётся ванильным способом: отменять событие нельзя, иначе питьё не начнётся.
            // Запрет по зонам проверяется в onConsumeCustomPotion.
            return;
        }
        // Свой предмет: ванильное действие (например, бросок ока эндера) не должно срабатывать никогда.
        event.setCancelled(true);
        if (isBanned(player.getLocation())) {
            msg(player, "banned", item);
            return;
        }
        long left = cooldownLeft(player.getUniqueId(), item);
        if (left > 0) {
            msg(player, "cooldown", item, "{seconds}", String.valueOf(left));
            return;
        }
        boolean used = false;
        if (item.kind() == CustomItem.Kind.TRAP) {
            used = useTrap(player, item);
        } else if (item.kind() == CustomItem.Kind.DUST) {
            used = useDust(player, item);
        } else if (item.kind() == CustomItem.Kind.DISORIENT) {
            used = useDisorient(player, item);
        }
        if (used) {
            consume(player, hand);
            startCooldown(player.getUniqueId(), item);
        }
    }

    /**
     * Кастомные зелья пьются везде, включая спавн: это расходник, а не боевой предмет.
     * Если нужно запрещать их в зонах — banned.allow-potions: false.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onConsumeCustomPotion(PlayerItemConsumeEvent event) {
        if (plugin.getConfig().getBoolean("banned.allow-potions", true)) {
            return;
        }
        String id = identify(event.getItem());
        CustomItem item = id == null ? null : byId(id);
        if (item == null || item.kind() != CustomItem.Kind.POTION) {
            return;
        }
        if (isBanned(event.getPlayer().getLocation())) {
            event.setCancelled(true);
            msg(event.getPlayer(), "banned", item);
        }
    }

    // ------------------------------------------------------------------ трапка
    private boolean useTrap(Player user, CustomItem item) {
        // Трапка появляется там, где стоит игрок (как на FunTime): клетка 3x3 вокруг него.
        buildTrap(user, item);
        msg(user, "trap-used", item);
        playSound(user, item.str("sound", "block.crying_obsidian.place"));
        return true;
    }

    private boolean trappedPlayer(UUID uuid) {
        for (Trap trap : traps) {
            if (trap.target.equals(uuid)) {
                return true;
            }
        }
        return false;
    }

    private void buildTrap(Player owner, CustomItem item) {
        // Своя схематика WorldEdit в приоритете; нет файла — встроенная клетка 3x3.
        if (worldEdit.hasSchematic()) {
            buildSchematicTrap(owner, item);
        } else {
            buildCageTrap(owner, item);
        }
    }

    private void buildCageTrap(Player owner, CustomItem item) {
        Location base = owner.getLocation();
        int bx = base.getBlockX();
        int by = base.getBlockY();
        int bz = base.getBlockZ();
        Material floor = mat(item.str("blocks.floor", "POLISHED_BLACKSTONE"), Material.POLISHED_BLACKSTONE);
        Material walls = mat(item.str("blocks.walls", "CRYING_OBSIDIAN"), Material.CRYING_OBSIDIAN);
        Material corners = mat(item.str("blocks.corners", "GILDED_BLACKSTONE"), Material.GILDED_BLACKSTONE);
        Material ceiling = mat(item.str("blocks.ceiling", "POLISHED_BLACKSTONE"), Material.POLISHED_BLACKSTONE);
        Material light = mat(item.str("blocks.light", "SEA_LANTERN"), Material.SEA_LANTERN);

        Trap trap = new Trap();
        trap.owner = owner.getUniqueId();
        trap.target = owner.getUniqueId();
        trap.world = base.getWorld().getName();
        int floorDepth = Math.max(1, (int) item.num("floor-depth", 2)); // пол в 2 блока вниз — не прокопаться
        trap.minX = bx - 1; trap.maxX = bx + 1;
        trap.minZ = bz - 1; trap.maxZ = bz + 1;
        trap.minY = by - floorDepth; trap.maxY = by + 3;

        for (int x = bx - 1; x <= bx + 1; x++) {
            for (int z = bz - 1; z <= bz + 1; z++) {
                boolean corner = Math.abs(x - bx) == 1 && Math.abs(z - bz) == 1;
                setTrapBlock(trap, base.getWorld().getBlockAt(x, by - 1, z), corner ? corners : floor);
                // дополнительные слои пола вниз (ломать их тоже нельзя — они в trapBlocks)
                for (int d = 2; d <= floorDepth; d++) {
                    setTrapBlock(trap, base.getWorld().getBlockAt(x, by - d, z), corner ? corners : floor);
                }
                setTrapBlock(trap, base.getWorld().getBlockAt(x, by + 3, z), corner ? corners : ceiling);
                for (int y = by; y <= by + 2; y++) {
                    if (Math.abs(x - bx) == 1 || Math.abs(z - bz) == 1) {
                        setTrapBlock(trap, base.getWorld().getBlockAt(x, y, z), corner ? corners : walls);
                    }
                }
            }
        }
        // внутри пусто: центральный столб очищаем (блоки вернутся, когда трапка рассыплется)
        for (int y = by; y <= by + 2; y++) {
            setTrapBlock(trap, base.getWorld().getBlockAt(bx, y, bz), Material.AIR);
        }
        setTrapBlock(trap, base.getWorld().getBlockAt(bx, by + 3, bz), light);
        // Если игрок стоит на краю блока, хитбокс попадает в стену и его зажимает.
        // Ставим его в центр клетки — на 0.5 блока, без рывков.
        Location center = new Location(base.getWorld(), bx + 0.5, by, bz + 0.5, base.getYaw(), base.getPitch());
        if (owner.getLocation().distanceSquared(center) > 0.09) {
            owner.teleport(center);
        }
        traps.add(trap);

        // кто оказался внутри вместе с хозяином — тот тоже в трапке
        for (Player other : base.getWorld().getPlayers()) {
            if (other.equals(owner) || other.isDead()) {
                continue;
            }
            Location loc = other.getLocation();
            if (loc.getBlockX() >= trap.minX && loc.getBlockX() <= trap.maxX
                    && loc.getBlockY() >= trap.minY && loc.getBlockY() <= trap.maxY
                    && loc.getBlockZ() >= trap.minZ && loc.getBlockZ() <= trap.maxZ) {
                msg(other, "trapped-you", item, "{player}", owner.getName());
            }
        }

        long durationTicks = (long) item.num("duration-seconds", 20) * 20L;
        trap.task = Bukkit.getScheduler().runTaskLater(plugin, () -> endTrap(trap, true), durationTicks);
    }

    private void notifyTrappedInside(Trap trap, Player owner, CustomItem item) {
        for (Player other : owner.getWorld().getPlayers()) {
            if (other.equals(owner) || other.isDead()) {
                continue;
            }
            Location loc = other.getLocation();
            if (loc.getBlockX() >= trap.minX && loc.getBlockX() <= trap.maxX
                    && loc.getBlockY() >= trap.minY && loc.getBlockY() <= trap.maxY
                    && loc.getBlockZ() >= trap.minZ && loc.getBlockZ() <= trap.maxZ) {
                msg(other, "trapped-you", item, "{player}", owner.getName());
            }
        }
    }

    /**
     * Трапка из своей схематики: ставится МГНОВЕННО и ровно по игроку в момент
     * нажатия — центр постройки по горизонтали совмещается с блоком, где стоит
     * игрок (даже на бегу), куда смотрит игрок — не важно. По вертикали низ
     * схематики от точки //copy сохраняется (схема не прыгает вверх/вниз).
     */
    private void buildSchematicTrap(Player owner, CustomItem item) {
        Location base = owner.getLocation();
        int bx = base.getBlockX();
        int by = base.getBlockY();
        int bz = base.getBlockZ();
        // Сдвиг, чтобы геометрический центр схематики встал точно на игрока,
        // даже если при //copy игрок стоял не по центру своей постройки.
        int shiftX = (worldEdit.relMinX() + worldEdit.relMaxX()) / 2;
        int shiftZ = (worldEdit.relMinZ() + worldEdit.relMaxZ()) / 2;

        Trap trap = new Trap();
        trap.owner = owner.getUniqueId();
        trap.target = owner.getUniqueId();
        trap.world = base.getWorld().getName();
        trap.minX = bx + worldEdit.relMinX() - shiftX; trap.maxX = bx + worldEdit.relMaxX() - shiftX;
        trap.minZ = bz + worldEdit.relMinZ() - shiftZ; trap.maxZ = bz + worldEdit.relMaxZ() - shiftZ;
        trap.minY = by + worldEdit.relMinY(); trap.maxY = by + worldEdit.relMaxY();

        // Снапшот всей территории под схематику: после конца восстановим блок в блок.
        for (int x = trap.minX; x <= trap.maxX; x++) {
            for (int y = trap.minY; y <= trap.maxY; y++) {
                for (int z = trap.minZ; z <= trap.maxZ; z++) {
                    Block block = base.getWorld().getBlockAt(x, y, z);
                    if (trapBlocks.containsKey(key(block.getLocation()))) {
                        continue;
                    }
                    trap.changed.put(block.getLocation(), block.getBlockData().clone());
                }
            }
        }
        // Ставим блоки схематики обычным Bukkit: снапшот выше уже снят,
        // после конца трапки территория вернётся блок в блок.
        boolean placedAny = false;
        for (WEHook.SchematicCell cell : worldEdit.cells()) {
            Block block = base.getWorld().getBlockAt(bx + cell.x - shiftX, by + cell.y, bz + cell.z - shiftZ);
            if (block.getType() == Material.BEDROCK) {
                continue; // бедрок не трогаем
            }
            try {
                block.setBlockData(Bukkit.createBlockData(cell.blockData), false);
            } catch (IllegalArgumentException e) {
                continue; // блок из схематики не поддерживается этой версией сервера
            }
            placedAny = true;
            // Защита: не-воздушные блоки схематики нельзя ломать, пока трапка жива.
            if (!cell.air) {
                trapBlocks.put(key(block.getLocation()), trap);
            }
        }
        if (!placedAny) {
            // ничего не поставилось (чужая версия блоков) — откатываемся на встроенную клетку
            trap.changed.clear();
            buildCageTrap(owner, item);
            return;
        }
        // Пол нельзя прокопать: дорисовываем под низом схематики ещё слои пола вниз.
        int floorDepth = Math.max(0, (int) item.num("floor-depth", 2));
        if (floorDepth > 0) {
            thickenFloorDown(trap, base.getWorld(), floorDepth);
        }
        traps.add(trap);
        notifyTrappedInside(trap, owner, item);
        long durationTicks = (long) item.num("duration-seconds", 20) * 20L;
        trap.task = Bukkit.getScheduler().runTaskLater(plugin, () -> endTrap(trap, true), durationTicks);
    }

    /**
     * Под низом трапки дорисовывает depth слоёв пола (тем же блоком, что и низ
     * колонки) и защищает их от ломания. Территория снимется в trap.changed —
     * после конца трапки всё вернётся как было.
     */
    private void thickenFloorDown(Trap trap, org.bukkit.World world, int depth) {
        int bottom = trap.minY;
        for (int x = trap.minX; x <= trap.maxX; x++) {
            for (int z = trap.minZ; z <= trap.maxZ; z++) {
                Block floorBlock = world.getBlockAt(x, bottom, z);
                if (floorBlock.getType() == Material.AIR || floorBlock.getType() == Material.BEDROCK) {
                    continue; // в этой колонке пола нет (пролёт схематики) или бедрок
                }
                BlockData floorData = floorBlock.getBlockData().clone();
                for (int d = 1; d <= depth; d++) {
                    Block block = world.getBlockAt(x, bottom - d, z);
                    if (block.getType() == Material.BEDROCK) {
                        continue;
                    }
                    if (trapBlocks.containsKey(key(block.getLocation()))) {
                        continue; // блок другой трапки
                    }
                    trap.changed.putIfAbsent(block.getLocation(), block.getBlockData().clone());
                    block.setBlockData(floorData.clone(), false);
                    trapBlocks.put(key(block.getLocation()), trap);
                }
            }
        }
        trap.minY = bottom - depth;
    }

    private void setTrapBlock(Trap trap, Block block, Material material) {
        if (block.getType() == Material.BEDROCK) {
            return; // бедрок не трогаем
        }
        if (trapBlocks.containsKey(key(block.getLocation()))) {
            return; // блок другой трапки
        }
        // Запоминаем ВСЁ, что меняем (включая очистку внутренности), —
        // когда трапка рассыплется, территория восстановится блок в блок.
        trap.changed.put(block.getLocation(), block.getBlockData().clone());
        trapBlocks.put(key(block.getLocation()), trap);
        block.setType(material, false);
    }

    private void endTrap(Trap trap, boolean restore) {
        traps.remove(trap);
        if (trap.task != null) {
            trap.task.cancel();
        }
        for (Map.Entry<Location, BlockData> e : trap.changed.entrySet()) {
            trapBlocks.remove(key(e.getKey()));
            if (restore) {
                e.getKey().getBlock().setBlockData(e.getValue(), false);
            }
        }
        trap.changed.clear();
        Player target = Bukkit.getPlayer(trap.target);
        if (target != null) {
            msg(target, "trap-ended", null);
        }
    }

    /** Вызывается при выключении плагина: вернуть все блоки трапок. */
    public void shutdown() {
        for (Trap trap : new ArrayList<>(traps)) {
            endTrap(trap, true);
        }
        for (UUID uuid : new ArrayList<>(revealed.keySet())) {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null) {
                p.setGlowing(false);
                p.removePotionEffect(PotionEffectType.GLOWING);
            }
        }
        revealed.clear();
    }

    private static String key(Location loc) {
        return loc.getWorld().getName() + ":" + loc.getBlockX() + "," + loc.getBlockY() + "," + loc.getBlockZ();
    }

    private Material mat(String name, Material def) {
        Material m = Material.matchMaterial(name);
        return m == null ? def : m;
    }

    // трапку нельзя сломать и внутри неё нельзя ставить блоки
    @EventHandler
    public void onBreakTrap(BlockBreakEvent event) {
        if (trapBlocks.containsKey(key(event.getBlock().getLocation()))) {
            event.setCancelled(true);
            msg(event.getPlayer(), "trap-unbreakable", null);
        }
    }

    @EventHandler
    public void onPlaceInsideTrap(BlockPlaceEvent event) {
        Location loc = event.getBlockPlaced().getLocation();
        for (Trap trap : traps) {
            if (trap.world.equals(loc.getWorld().getName())
                    && loc.getBlockX() >= trap.minX && loc.getBlockX() <= trap.maxX
                    && loc.getBlockY() >= trap.minY && loc.getBlockY() <= trap.maxY
                    && loc.getBlockZ() >= trap.minZ && loc.getBlockZ() <= trap.maxZ) {
                event.setCancelled(true);
                msg(event.getPlayer(), "trap-no-place", null);
                return;
            }
        }
    }

    @EventHandler
    public void onExplodeBlocks(EntityExplodeEvent event) {
        event.blockList().removeIf(b -> trapBlocks.containsKey(key(b.getLocation())));
    }

    @EventHandler
    public void onExplodeBlocks(BlockExplodeEvent event) {
        event.blockList().removeIf(b -> trapBlocks.containsKey(key(b.getLocation())));
    }

    // ------------------------------------------------------------------ явная пыль
    private boolean useDust(Player user, CustomItem item) {
        double radius = item.num("radius", 15);
        int glowingSeconds = (int) item.num("glowing-seconds", 10);
        int found = 0;
        for (Player other : user.getWorld().getPlayers()) {
            if (other.equals(user) || other.isDead() || isBanned(other.getLocation())) {
                continue;
            }
            if (other.getLocation().distanceSquared(user.getLocation()) > radius * radius) {
                continue;
            }
            other.removePotionEffect(PotionEffectType.INVISIBILITY);
            other.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, glowingSeconds * 20, 0, true, false, true));
            other.setGlowing(true); // белая обводка вокруг игрока (эффект свечения)
            revealed.put(other.getUniqueId(), System.currentTimeMillis() + glowingSeconds * 1000L);
            msg(other, "revealed-you", item);
            found++;
        }
        playSound(user, item.str("sound", "entity.firework_rocket.launch"));
        boolean requireTarget = item.bool("require-target", false);
        if (found == 0 && requireTarget) {
            msg(user, "no-target", item);
            return false;
        }
        msg(user, found == 0 ? "no-target" : "dust-used", item);
        return true;
    }

    // ------------------------------------------------------------------ дезориентация
    private boolean useDisorient(Player user, CustomItem item) {
        double radius = item.num("radius", 9);
        List<CustomItem.EffectDef> effects = item.effects("effects");
        int hit = 0;
        for (Player other : user.getWorld().getPlayers()) {
            if (other.equals(user) || other.isDead() || isBanned(other.getLocation())) {
                continue;
            }
            if (other.getLocation().distanceSquared(user.getLocation()) > radius * radius) {
                continue;
            }
            for (CustomItem.EffectDef e : effects) {
                other.addPotionEffect(new PotionEffect(e.type(), e.durationSeconds() * 20, e.amplifier(), true, false, true));
            }
            if (item.num("rotate-view", 1) > 0) {
                // Разворот без телепорта: setRotation не двигает игрока, поэтому
                // античит не видит "moved too quickly" и ложных флагов не будет.
                float turn = 120f + ThreadLocalRandom.current().nextFloat() * 120f;
                if (ThreadLocalRandom.current().nextBoolean()) {
                    turn = -turn;
                }
                float yaw = other.getLocation().getYaw() + turn;
                float pitch = Math.max(-60f, Math.min(60f,
                        other.getLocation().getPitch() + (ThreadLocalRandom.current().nextFloat() * 50f - 25f)));
                other.setRotation(yaw, pitch);
            }
            hit++;
        }
        // Раньше без игроков рядом предмет «не срабатывал» и не тратился.
        // Теперь по умолчанию (require-target: false) он используется всегда:
        // эффект просто не на кого применить, но звук/сообщение/кулдаун — как обычно.
        boolean requireTarget = item.bool("require-target", false);
        if (hit == 0 && requireTarget) {
            msg(user, "no-target", item);
            return false;
        }
        playSound(user, item.str("sound", "entity.wither.ambient"));
        msg(user, hit == 0 ? "no-target" : "disorient-used", item);
        return true;
    }

    // ------------------------------------------------------------------ лом спавнеров
    @EventHandler(ignoreCancelled = true)
    public void onBreakSpawner(BlockBreakEvent event) {
        Block block = event.getBlock();
        // блоки трапок не ломаются вообще (отдельный обработчик выше), тут — спавнеры
        if (block.getType() != Material.SPAWNER) {
            return;
        }
        Player player = event.getPlayer();
        String id = identify(player.getInventory().getItemInMainHand());
        CustomItem item = id == null ? null : byId(id);
        if (item == null || item.kind() != CustomItem.Kind.LOM) {
            return;
        }
        if (isBanned(block.getLocation())) {
            msg(player, "banned", item);
            event.setCancelled(true); // в запретной зоне лом не работает и спавнер не теряется
            return;
        }
        // Моб сохраняется: в выпавшем спавнере будет тот же моб.
        org.bukkit.entity.EntityType mob = null;
        if (block.getState() instanceof CreatureSpawner) {
            mob = ((CreatureSpawner) block.getState()).getSpawnedType();
        }
        event.setDropItems(false);
        event.setExpToDrop(0);
        ItemStack spawnerItem = new ItemStack(Material.SPAWNER);
        ItemMeta spawnerMeta = spawnerItem.getItemMeta();
        String mobName = mob == null ? "" : mob.name();
        spawnerMeta.displayName(component(TextUtil.color(item.str("spawner-item-name", "&#FFB340Спавнер")
                .replace("{mob}", mobName))));
        if (mob != null) {
            // Способ 1 (ванильный): моб в состоянии блока внутри предмета.
            // ВАЖНО: getBlockState() звать ровно один раз и править именно этот объект.
            if (spawnerMeta instanceof BlockStateMeta) {
                BlockStateMeta bsm = (BlockStateMeta) spawnerMeta;
                org.bukkit.block.BlockState state = bsm.getBlockState();
                if (state instanceof CreatureSpawner) {
                    ((CreatureSpawner) state).setSpawnedType(mob);
                    bsm.setBlockState(state);
                }
            }
            // Способ 2 (страховка): моб в скрытой метке предмета.
            // При постановке onPlaceSpawner вернёт его в блок, даже если
            // сервер при выдаче предмета потерял ванильное состояние блока.
            spawnerMeta.getPersistentDataContainer().set(spawnerMobKey, PersistentDataType.STRING, mob.name());
        }
        spawnerItem.setItemMeta(spawnerMeta);
        consume(player, EquipmentSlot.HAND); // лом одноразовый
        Bukkit.getScheduler().runTask(plugin, () -> {
            block.setType(Material.AIR, false);
            java.util.Map<Integer, ItemStack> rest = player.getInventory().addItem(spawnerItem);
            for (ItemStack drop : rest.values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), drop);
            }
        });
        msg(player, "lom-used", item, "{mob}", mobName);
        event.setCancelled(true);
    }

    /** Спавнер из лома поставили: вернуть моба из скрытой метки предмета в блок. */
    @EventHandler
    public void onPlaceSpawner(BlockPlaceEvent event) {
        if (event.getBlockPlaced().getType() != Material.SPAWNER) {
            return;
        }
        ItemStack hand = event.getItemInHand();
        if (hand == null || !hand.hasItemMeta()) {
            return;
        }
        String mobName = hand.getItemMeta().getPersistentDataContainer()
                .get(spawnerMobKey, PersistentDataType.STRING);
        if (mobName == null || mobName.isEmpty()) {
            return;
        }
        org.bukkit.entity.EntityType mob;
        try {
            mob = org.bukkit.entity.EntityType.valueOf(mobName);
        } catch (IllegalArgumentException e) {
            return;
        }
        org.bukkit.block.BlockState state = event.getBlockPlaced().getState();
        if (state instanceof CreatureSpawner) {
            CreatureSpawner spawner = (CreatureSpawner) state;
            if (spawner.getSpawnedType() != mob) {
                spawner.setSpawnedType(mob);
                spawner.update(true, false);
            }
        }
    }

    private void playSound(Player player, String sound) {
        if (sound == null || sound.isEmpty()) {
            return;
        }
        try {
            player.playSound(player.getLocation(), sound.toLowerCase(Locale.ROOT), 0.8f, 1.0f);
        } catch (Throwable ignored) {
        }
    }

    static Component component(String legacy) {
        return LegacyComponentSerializer.legacySection().deserialize(legacy == null ? "" : legacy);
    }
}
