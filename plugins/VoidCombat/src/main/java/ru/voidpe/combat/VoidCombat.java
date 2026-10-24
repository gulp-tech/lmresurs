package ru.voidpe.combat;

import com.destroystokyo.paper.event.player.PlayerElytraBoostEvent;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.ThrownPotion;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.entity.EntityToggleGlideEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.potion.PotionType;
import org.bukkit.projectiles.ProjectileSource;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * VoidCombat — режим боя для Bedrock-сервера VoidPE.
 *
 * <p>Игрок получает метку боя, когда наносит или получает PvP-урон. Пока
 * метка активна, плагин ограничивает предметы, которыми обычно спасаются
 * в бою: золотые яблоки, зелья исцеления и кастомные предметы VoidItems.
 * Фейерверки в бою запрещены всеми способами: рукой, элитрами и арбалетом.</p>
 *
 * <p>Кастомные предметы узнаются по скрытой PDC-метке VoidItems
 * ({@code voiditems:item_id}), поэтому переименованием их не подделать,
 * а жёсткой зависимости от VoidItems у плагина нет.</p>
 */
public final class VoidCombat extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {

    private static final NamespacedKey VOID_ITEMS_ID = NamespacedKey.fromString("voiditems:item_id");

    private final Map<UUID, Long> combatUntil = new HashMap<>();
    private final Map<UUID, BossBar> combatBars = new HashMap<>();
    private final Map<UUID, Map<String, Long>> cooldownUntil = new HashMap<>();
    private final Map<UUID, Map<String, Long>> lastBlockMessage = new HashMap<>();
    private BukkitTask timerTask;
    private WGHook worldGuard;

    // Настройки из команд (/combat on|off|settime|world|zone) — state.yml.
    // null/пусто = значение берётся из config.yml.
    private Boolean enabledOverride;
    private Integer durationOverride;
    private final Set<String> stateSafeWorlds = new HashSet<>();
    private final List<SafeZone> safeZones = new ArrayList<>();

    private record CooldownItem(String key, String label, int seconds) {
    }

    /** Безопасная зона-круг, созданная командой /combat zone add. */
    private record SafeZone(String name, String world, double x, double y, double z, int radius) {
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        worldGuard = new WGHook(getLogger());
        loadState();
        getServer().getPluginManager().registerEvents(this, this);
        if (getCommand("combat") != null) {
            getCommand("combat").setExecutor(this);
            getCommand("combat").setTabCompleter(this);
        }
        timerTask = Bukkit.getScheduler().runTaskTimer(this, this::tickCombat, 10L, 10L);
        getLogger().info("VoidCombat включён: режим боя, кулдауны предметов и запрет фейерверков в бою.");
    }

    @Override
    public void onDisable() {
        if (timerTask != null) {
            timerTask.cancel();
        }
        hideAllCombatBars();
        combatUntil.clear();
        cooldownUntil.clear();
        lastBlockMessage.clear();
    }

    private Component comp(String legacy) {
        return LegacyComponentSerializer.legacySection().deserialize(TextUtil.color(legacy))
                .decoration(TextDecoration.ITALIC, false);
    }

    private void msg(Player player, String key, String... pairs) {
        String text = getConfig().getString("messages." + key, defaultMessage(key));
        if (text == null || text.isEmpty()) {
            return;
        }
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            text = text.replace(pairs[i], pairs[i + 1]);
        }
        String prefix = getConfig().getString("messages.prefix", "");
        player.sendMessage(comp((prefix == null ? "" : prefix) + text));
    }

    /** Тексты новых запретов — на случай, если конфиг ещё не обновлён. */
    private String defaultMessage(String key) {
        switch (key) {
            case "elytra-blocked":
                return "&cЭлитры в этом мире запрещены.";
            case "firework-blocked-world":
                return "&cФейерверки в этом мире запрещены.";
            default:
                return "";
        }
    }

    private void msg(CommandSender sender, String key, String... pairs) {
        if (sender instanceof Player) {
            msg((Player) sender, key, pairs);
            return;
        }
        String text = getConfig().getString("messages." + key, defaultMessage(key));
        if (text == null) {
            return;
        }
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            text = text.replace(pairs[i], pairs[i + 1]);
        }
        sender.sendMessage(comp(text));
    }

    // ------------------------------------------------------------ режим боя

    public boolean isInCombat(Player player) {
        return player != null && combatLeftMillis(player.getUniqueId()) > 0;
    }

    private long combatLeftMillis(UUID uuid) {
        Long until = combatUntil.get(uuid);
        if (until == null) {
            return 0;
        }
        long left = until - System.currentTimeMillis();
        if (left <= 0) {
            combatUntil.remove(uuid);
            return 0;
        }
        return left;
    }

    private int combatLeftSeconds(UUID uuid) {
        return (int) Math.ceil(combatLeftMillis(uuid) / 1000.0);
    }

    // ------------------------------------------------------------ настройки и state.yml

    /** Режим боя включён: выбор командой (/combat on|off) главнее конфига. */
    private boolean combatEnabled() {
        return enabledOverride != null
                ? enabledOverride
                : getConfig().getBoolean("combat.enabled", true);
    }

    /** Длительность режима боя в секундах: команда главнее конфига. */
    private int combatDuration() {
        int seconds = durationOverride != null
                ? durationOverride
                : getConfig().getInt("combat.duration-seconds", 30);
        return Math.max(1, seconds);
    }

    private File stateFile() {
        return new File(getDataFolder(), "state.yml");
    }

    private void loadState() {
        enabledOverride = null;
        durationOverride = null;
        stateSafeWorlds.clear();
        safeZones.clear();
        File file = stateFile();
        if (!file.exists()) {
            return;
        }
        YamlConfiguration data = YamlConfiguration.loadConfiguration(file);
        if (data.contains("enabled")) {
            enabledOverride = data.getBoolean("enabled");
        }
        if (data.contains("duration-seconds")) {
            durationOverride = Math.max(1, Math.min(3600, data.getInt("duration-seconds")));
        }
        for (String world : data.getStringList("safe-worlds")) {
            stateSafeWorlds.add(world.toLowerCase(Locale.ROOT));
        }
        for (Map<?, ?> raw : data.getMapList("zones")) {
            Object name = raw.get("name");
            Object world = raw.get("world");
            if (name == null || world == null) {
                continue;
            }
            safeZones.add(new SafeZone(
                    String.valueOf(name).toLowerCase(Locale.ROOT),
                    String.valueOf(world),
                    raw.get("x") instanceof Number n ? n.doubleValue() : 0,
                    raw.get("y") instanceof Number n ? n.doubleValue() : 0,
                    raw.get("z") instanceof Number n ? n.doubleValue() : 0,
                    raw.get("radius") instanceof Number n ? Math.max(1, n.intValue()) : 30));
        }
        getLogger().info("VoidCombat state: включён=" + combatEnabled()
                + ", длительность=" + combatDuration() + " сек, безопасных миров="
                + stateSafeWorlds.size() + ", зон=" + safeZones.size());
    }

    private void saveState() {
        YamlConfiguration data = new YamlConfiguration();
        if (enabledOverride != null) {
            data.set("enabled", enabledOverride);
        }
        if (durationOverride != null) {
            data.set("duration-seconds", durationOverride);
        }
        data.set("safe-worlds", new ArrayList<>(stateSafeWorlds));
        List<Map<String, Object>> zones = new ArrayList<>();
        for (SafeZone zone : safeZones) {
            Map<String, Object> row = new HashMap<>();
            row.put("name", zone.name());
            row.put("world", zone.world());
            row.put("x", zone.x());
            row.put("y", zone.y());
            row.put("z", zone.z());
            row.put("radius", zone.radius());
            zones.add(row);
        }
        data.set("zones", zones);
        try {
            data.save(stateFile());
        } catch (IOException e) {
            getLogger().warning("Не удалось сохранить state.yml: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------ безопасные места

    /** Все безопасные миры: из конфига + добавленные командой (нижний регистр). */
    private Set<String> safeWorldNames() {
        Set<String> out = new HashSet<>(stateSafeWorlds);
        for (String world : getConfig().getStringList("combat.safe.worlds")) {
            out.add(world.toLowerCase(Locale.ROOT));
        }
        return out;
    }

    /** Безопасное место: режим боя не ставится, метка снимается. */
    private boolean isSafe(Location loc) {
        if (loc == null || loc.getWorld() == null) {
            return false;
        }
        if (safeWorldNames().contains(loc.getWorld().getName().toLowerCase(Locale.ROOT))) {
            return true;
        }
        if (worldGuard != null && worldGuard.inRegion(loc,
                getConfig().getStringList("combat.safe.worldguard-regions"))) {
            return true;
        }
        boolean ignoreY = getConfig().getBoolean("combat.safe.ignore-y", true);
        for (SafeZone zone : safeZones) {
            if (!zone.world().equalsIgnoreCase(loc.getWorld().getName())) {
                continue;
            }
            double dx = loc.getX() - zone.x();
            double dz = loc.getZ() - zone.z();
            double distSq = ignoreY
                    ? dx * dx + dz * dz
                    : dx * dx + dz * dz + Math.pow(loc.getY() - zone.y(), 2);
            if (distSq <= (double) zone.radius() * zone.radius()) {
                return true;
            }
        }
        return false;
    }

    private void tagCombat(Player player, Player opponent) {
        if (!combatEnabled()) {
            return;
        }
        // Безопасное место (мир, регион WG, зона): боя нет ни у кого из пары.
        if (isSafe(player.getLocation()) || isSafe(opponent.getLocation())) {
            return;
        }
        if (getConfig().getBoolean("combat.ignore-creative-and-spectator", true)
                && (player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR
                || opponent.getGameMode() == GameMode.CREATIVE || opponent.getGameMode() == GameMode.SPECTATOR)) {
            return;
        }
        boolean wasInCombat = isInCombat(player);
        combatUntil.put(player.getUniqueId(),
                System.currentTimeMillis() + combatDuration() * 1000L);
        if (!wasInCombat) {
            msg(player, "combat-started", "{player}", opponent.getName());
        }
        // Боссбар показываем сразу, не дожидаясь следующего тика таймера.
        updateCombatBar(player, combatDuration() * 1000L);
    }

    private void tickCombat() {
        enforceElytraBan();
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<UUID, Long>> it = combatUntil.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Long> entry = it.next();
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player == null || !player.isOnline()) {
                it.remove();
                combatBars.remove(entry.getKey());
                continue;
            }
            if (getConfig().getBoolean("combat.safe.clear-on-enter", true)
                    && isSafe(player.getLocation())) {
                it.remove();
                hideCombatBar(player);
                msg(player, "combat-ended-safe");
                continue;
            }
            long leftMillis = entry.getValue() - now;
            if (leftMillis <= 0) {
                it.remove();
                hideCombatBar(player);
                msg(player, "combat-ended");
                continue;
            }
            updateCombatBar(player, leftMillis);
            if (getConfig().getBoolean("combat.display.actionbar", true)) {
                String format = getConfig().getString("combat.display.actionbar-format",
                        "&#FF5555Режим боя: &f{seconds} сек. &8[{bar}&8]");
                int seconds = (int) Math.ceil(leftMillis / 1000.0);
                player.sendActionBar(comp(format
                        .replace("{seconds}", String.valueOf(seconds))
                        .replace("{bar}", progressBar(leftMillis))));
            }
        }
    }

    // ------------------------------------------------------------ боссбар боя

    private BossBar.Color bossBarColor() {
        String raw = getConfig().getString("combat.display.bossbar-color", "RED");
        try {
            return BossBar.Color.valueOf(raw == null ? "RED" : raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return BossBar.Color.RED;
        }
    }

    private BossBar.Overlay bossBarOverlay() {
        String raw = getConfig().getString("combat.display.bossbar-overlay", "PROGRESS");
        try {
            return BossBar.Overlay.valueOf(raw == null ? "PROGRESS" : raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return BossBar.Overlay.PROGRESS;
        }
    }

    /** Создаёт/обновляет боссбар боя: заголовок с секундами + тающая полоса. */
    private void updateCombatBar(Player player, long leftMillis) {
        if (!getConfig().getBoolean("combat.display.bossbar", true)) {
            hideCombatBar(player);
            return;
        }
        int seconds = (int) Math.ceil(leftMillis / 1000.0);
        String format = getConfig().getString("combat.display.bossbar-format",
                "&#FF5555Режим боя: &f{seconds} сек.");
        Component title = comp(format.replace("{seconds}", String.valueOf(seconds)));
        double total = combatDuration() * 1000.0;
        float progress = total <= 0 ? 0.0f
                : (float) Math.max(0.0, Math.min(1.0, leftMillis / total));
        BossBar bar = combatBars.get(player.getUniqueId());
        if (bar == null) {
            bar = BossBar.bossBar(title, progress, bossBarColor(), bossBarOverlay());
            combatBars.put(player.getUniqueId(), bar);
        } else {
            bar.name(title);
            bar.progress(progress);
            bar.color(bossBarColor());
            bar.overlay(bossBarOverlay());
        }
        player.showBossBar(bar);
    }

    private void hideCombatBar(Player player) {
        if (player == null) {
            return;
        }
        BossBar bar = combatBars.remove(player.getUniqueId());
        if (bar != null) {
            player.hideBossBar(bar);
        }
    }

    private void hideAllCombatBars() {
        for (Map.Entry<UUID, BossBar> entry : combatBars.entrySet()) {
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player != null && player.isOnline()) {
                player.hideBossBar(entry.getValue());
            }
        }
        combatBars.clear();
    }

    /** Полоса прогресса режима боя для {bar} в actionbar. */
    private String progressBar(long leftMillis) {
        int length = Math.max(1, Math.min(40, getConfig().getInt("combat.display.bar-length", 10)));
        double total = combatDuration() * 1000.0;
        double fraction = total <= 0 ? 0 : Math.max(0.0, Math.min(1.0, leftMillis / total));
        int filled = (int) Math.round(fraction * length);
        String filledChar = getConfig().getString("combat.display.bar-filled", "█");
        String emptyChar = getConfig().getString("combat.display.bar-empty", "░");
        return filledChar.repeat(filled) + emptyChar.repeat(length - filled);
    }

    private Player resolveAttacker(Entity damager) {
        if (damager instanceof Player) {
            return (Player) damager;
        }
        if (damager instanceof Projectile) {
            ProjectileSource shooter = ((Projectile) damager).getShooter();
            if (shooter instanceof Player) {
                return (Player) shooter;
            }
        }
        return null;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPvpDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player) || event.getFinalDamage() <= 0) {
            return;
        }
        Player victim = (Player) event.getEntity();
        Player attacker = resolveAttacker(event.getDamager());
        if (attacker == null || attacker.equals(victim)) {
            return;
        }
        // Метка ставится после всех защит: если WorldGuard или другой плагин
        // отменил урон, событие сюда не попадёт (ignoreCancelled).
        tagCombat(victim, attacker);
        tagCombat(attacker, victim);
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        combatUntil.remove(event.getEntity().getUniqueId());
        hideCombatBar(event.getEntity());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        combatUntil.remove(uuid);
        hideCombatBar(event.getPlayer());
        cooldownUntil.remove(uuid);
        lastBlockMessage.remove(uuid);
    }

    // ------------------------------------------------------------ кулдауны

    private boolean cooldownsApply(Player player) {
        if (!getConfig().getBoolean("cooldowns.enabled", true)) {
            return false;
        }
        return !getConfig().getBoolean("cooldowns.only-in-combat", true) || isInCombat(player);
    }

    private String customItemId(ItemStack stack) {
        if (stack == null || stack.getType() == Material.AIR || VOID_ITEMS_ID == null || !stack.hasItemMeta()) {
            return null;
        }
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return null;
        }
        String id = meta.getPersistentDataContainer().get(VOID_ITEMS_ID, PersistentDataType.STRING);
        return id == null || id.isBlank() ? null : id.toLowerCase(Locale.ROOT);
    }

    private String itemLabel(ItemStack stack, String fallback) {
        if (stack != null && stack.hasItemMeta()) {
            ItemMeta meta = stack.getItemMeta();
            if (meta != null && meta.displayName() != null) {
                String plain = PlainTextComponentSerializer.plainText().serialize(meta.displayName()).trim();
                if (!plain.isEmpty()) {
                    return plain;
                }
            }
        }
        return fallback;
    }

    private int customCooldownSeconds(String id) {
        int seconds = getConfig().getInt("cooldowns.custom-items.items." + id,
                getConfig().getInt("cooldowns.custom-items.default-seconds", 60));
        if (seconds <= 0) {
            return 0;
        }
        int minimum = Math.max(0, getConfig().getInt("cooldowns.custom-items.minimum-seconds", 60));
        return Math.max(minimum, seconds);
    }

    private boolean isHealingPotion(ItemStack stack) {
        if (stack == null || !(stack.getItemMeta() instanceof PotionMeta)) {
            return false;
        }
        PotionMeta meta = (PotionMeta) stack.getItemMeta();
        PotionType base = meta.getBasePotionType();
        if (base == PotionType.HEALING || base == PotionType.STRONG_HEALING) {
            return true;
        }
        return PotionEffectType.INSTANT_HEALTH != null && meta.hasCustomEffect(PotionEffectType.INSTANT_HEALTH);
    }

    private CooldownItem resolveCooldownItem(ItemStack stack) {
        if (stack == null || stack.getType() == Material.AIR) {
            return null;
        }
        String customId = customItemId(stack);
        if (customId != null && getConfig().getBoolean("cooldowns.custom-items.enabled", true)) {
            // Кастомное зелье VoidItems может не быть в tracked-ids: при питье
            // оно всё равно получает кастомный кулдаун, а не зелья исцеления.
            boolean tracked = getConfig().getStringList("cooldowns.custom-items.tracked-ids")
                    .stream().anyMatch(id -> id.equalsIgnoreCase(customId));
            if (tracked || stack.getType() == Material.POTION) {
                int seconds = customCooldownSeconds(customId);
                if (seconds > 0) {
                    return new CooldownItem("custom:" + customId, itemLabel(stack, customId), seconds);
                }
            }
        }
        Material type = stack.getType();
        if (type == Material.ENCHANTED_GOLDEN_APPLE) {
            int seconds = getConfig().getInt("cooldowns.vanilla.enchanted-golden-apple", 180);
            return seconds > 0 ? new CooldownItem("enchanted-golden-apple",
                    getConfig().getString("item-names.enchanted-golden-apple", "Зачарованное золотое яблоко"), seconds) : null;
        }
        if (type == Material.GOLDEN_APPLE) {
            int seconds = getConfig().getInt("cooldowns.vanilla.golden-apple", 30);
            return seconds > 0 ? new CooldownItem("golden-apple",
                    getConfig().getString("item-names.golden-apple", "Золотое яблоко"), seconds) : null;
        }
        if ((type == Material.POTION || type == Material.SPLASH_POTION || type == Material.LINGERING_POTION)
                && isHealingPotion(stack)) {
            int seconds = getConfig().getInt("cooldowns.vanilla.healing-potion", 15);
            return seconds > 0 ? new CooldownItem("healing-potion",
                    getConfig().getString("item-names.healing-potion", "Зелье исцеления"), seconds) : null;
        }
        return null;
    }

    private long cooldownLeftMillis(UUID uuid, String key) {
        Map<String, Long> byItem = cooldownUntil.get(uuid);
        if (byItem == null) {
            return 0;
        }
        Long until = byItem.get(key);
        if (until == null) {
            return 0;
        }
        long left = until - System.currentTimeMillis();
        if (left <= 0) {
            byItem.remove(key);
            if (byItem.isEmpty()) {
                cooldownUntil.remove(uuid);
            }
            return 0;
        }
        return left;
    }

    private boolean shouldSendBlockMessage(Player player, String key) {
        long now = System.currentTimeMillis();
        Map<String, Long> byKey = lastBlockMessage.computeIfAbsent(player.getUniqueId(), k -> new HashMap<>());
        Long last = byKey.get(key);
        if (last != null && now - last < 1000L) {
            return false;
        }
        byKey.put(key, now);
        return true;
    }

    /** true — предмет на кулдауне, использование надо отменить. */
    private boolean checkCooldown(Player player, CooldownItem item) {
        if (item == null || item.seconds() <= 0 || !cooldownsApply(player)) {
            return false;
        }
        long left = cooldownLeftMillis(player.getUniqueId(), item.key());
        if (left <= 0) {
            return false;
        }
        if (shouldSendBlockMessage(player, "cooldown:" + item.key())) {
            msg(player, "cooldown", "{item}", item.label(),
                    "{seconds}", String.valueOf((int) Math.ceil(left / 1000.0)));
        }
        return true;
    }

    private void startCooldown(Player player, CooldownItem item) {
        if (item == null || item.seconds() <= 0 || !cooldownsApply(player)) {
            return;
        }
        cooldownUntil.computeIfAbsent(player.getUniqueId(), k -> new HashMap<>())
                .put(item.key(), System.currentTimeMillis() + item.seconds() * 1000L);
    }

    // ------------------------------------------------------------ использование предметов

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onConsumeCheck(PlayerItemConsumeEvent event) {
        CooldownItem item = resolveCooldownItem(event.getItem());
        if (item != null && checkCooldown(event.getPlayer(), item)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onConsumeDone(PlayerItemConsumeEvent event) {
        startCooldown(event.getPlayer(), resolveCooldownItem(event.getItem()));
    }

    /** Взрывные и оседающие зелья исцеления не пьют, а бросают. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPotionThrowCheck(ProjectileLaunchEvent event) {
        if (!(event.getEntity() instanceof ThrownPotion)) {
            return;
        }
        ProjectileSource shooter = event.getEntity().getShooter();
        if (!(shooter instanceof Player)) {
            return;
        }
        ItemStack stack = ((ThrownPotion) event.getEntity()).getItem();
        CooldownItem item = resolveCooldownItem(stack);
        if (item != null && item.key().equals("healing-potion")
                && checkCooldown((Player) shooter, item)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPotionThrowDone(ProjectileLaunchEvent event) {
        if (!(event.getEntity() instanceof ThrownPotion)) {
            return;
        }
        ProjectileSource shooter = event.getEntity().getShooter();
        if (!(shooter instanceof Player)) {
            return;
        }
        CooldownItem item = resolveCooldownItem(((ThrownPotion) event.getEntity()).getItem());
        if (item != null && item.key().equals("healing-potion")) {
            startCooldown((Player) shooter, item);
        }
    }

    /**
     * Активные кастомные предметы (трапка, пыль, дезориентация): если предмет
     * на боевом кулдауне — клик отменяется до VoidItems (он уважает отмену).
     * Если кулдауна нет — после клика проверяем, правда ли предмет потратился:
     * кулдаун включается только за успешное использование.
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCustomInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        Player player = event.getPlayer();
        ItemStack hand = player.getInventory().getItemInMainHand();
        String customId = customItemId(hand);
        if (customId == null) {
            return;
        }
        CooldownItem item = resolveCooldownItem(hand);
        if (item == null || !item.key().startsWith("custom:")) {
            return;
        }
        if (checkCooldown(player, item)) {
            event.setCancelled(true);
            return;
        }
        if (!cooldownsApply(player)) {
            return;
        }
        int beforeAmount = hand.getAmount();
        Bukkit.getScheduler().runTask(this, () -> {
            if (!player.isOnline()) {
                return;
            }
            ItemStack now = player.getInventory().getItemInMainHand();
            String nowId = customItemId(now);
            if (nowId == null || !nowId.equals(customId) || now.getAmount() < beforeAmount) {
                startCooldown(player, item);
            }
        });
    }

    /** Лом спавнеров срабатывает не кликом, а ломанием спавнера. */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCustomLomCheck(BlockBreakEvent event) {
        if (event.getBlock().getType() != Material.SPAWNER) {
            return;
        }
        Player player = event.getPlayer();
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (!"spawner_lom".equals(customItemId(hand))) {
            return;
        }
        CooldownItem item = resolveCooldownItem(hand);
        if (item == null) {
            return;
        }
        if (checkCooldown(player, item)) {
            event.setCancelled(true);
            return;
        }
        if (!cooldownsApply(player)) {
            return;
        }
        int beforeAmount = hand.getAmount();
        Bukkit.getScheduler().runTask(this, () -> {
            if (!player.isOnline()) {
                return;
            }
            ItemStack now = player.getInventory().getItemInMainHand();
            if (!"spawner_lom".equals(customItemId(now)) || now.getAmount() < beforeAmount) {
                startCooldown(player, item);
            }
        });
    }

    // ------------------------------------------------------------ фейерверки в бою

    private boolean blockFirework(Player player) {
        // Мир из чёрного списка: фейерверки нельзя вообще, даже вне боя.
        if (isWorldListed("fireworks.banned-worlds", player.getWorld())) {
            if (shouldSendBlockMessage(player, "firework-world")) {
                msg(player, "firework-blocked-world");
            }
            return true;
        }
        if (!getConfig().getBoolean("fireworks.block-in-combat", true) || !isInCombat(player)) {
            return false;
        }
        if (shouldSendBlockMessage(player, "firework")) {
            msg(player, "firework-blocked");
        }
        return true;
    }

    /**
     * Список миров из конфига. Если ключа нет (старый config.yml, обновлённый
     * плагином), запрет всё равно работает для {@code defaults} — иначе после
     * обновления настройка молча не включилась бы.
     */
    private java.util.List<String> worldList(String path, String... defaults) {
        if (!getConfig().isSet(path)) {
            return java.util.Arrays.asList(defaults);
        }
        return getConfig().getStringList(path);
    }

    /** Мир перечислен в списке конфига (имена сравниваются без учёта регистра)? */
    private boolean isWorldListed(String path, org.bukkit.World world) {
        if (world == null) {
            return false;
        }
        for (String name : worldList(path, "world")) {
            if (name != null && name.trim().equalsIgnoreCase(world.getName())) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------ элитры в запрещённых мирах

    private boolean elytraBanned(Player player) {
        return isWorldListed("elytra.banned-worlds", player.getWorld());
    }

    /** Взлететь на элитре в запрещённом мире нельзя (приземление не блокируем). */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onGlideToggle(EntityToggleGlideEvent event) {
        if (!event.isGliding()) {
            return;
        }
        if (!(event.getEntity() instanceof Player)) {
            return;
        }
        Player player = (Player) event.getEntity();
        if (!elytraBanned(player)) {
            return;
        }
        event.setCancelled(true);
        // Клиент Bedrock может кратковременно включить полёт локально до ресинка —
        // через тик гасим его ещё раз, чтобы элитра не «срабатывала».
        Bukkit.getScheduler().runTask(this, () -> {
            if (player.isOnline() && player.isGliding() && elytraBanned(player)) {
                player.setGliding(false);
            }
        });
        if (shouldSendBlockMessage(player, "elytra")) {
            msg(player, "elytra-blocked");
        }
    }

    /** Влетел в запрещённый мир уже на элитре — сразу опускаем на землю. */
    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent event) {
        Player player = event.getPlayer();
        if (player.isGliding() && elytraBanned(player)) {
            player.setGliding(false);
        }
    }

    /**
     * Страховка: если событие взлёта пропущено (например, игрок уже летел при
     * включении плагина или мир определился позже), гасим полёт в тике режима боя.
     */
    private void enforceElytraBan() {
        if (worldList("elytra.banned-worlds", "world").isEmpty()) {
            return;
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!player.isGliding() || !elytraBanned(player)) {
                continue;
            }
            player.setGliding(false);
            if (shouldSendBlockMessage(player, "elytra")) {
                msg(player, "elytra-blocked");
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onFireworkInteract(PlayerInteractEvent event) {
        ItemStack item = event.getItem();
        if (item == null || item.getType() != Material.FIREWORK_ROCKET) {
            return;
        }
        if (blockFirework(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onElytraBoost(PlayerElytraBoostEvent event) {
        if (blockFirework(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    /** Раздатчик в запрещённом мире тоже не запускает фейерверки. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDispenseFirework(org.bukkit.event.block.BlockDispenseEvent event) {
        ItemStack item = event.getItem();
        if (item == null || item.getType() != Material.FIREWORK_ROCKET) {
            return;
        }
        if (isWorldListed("fireworks.banned-worlds", event.getBlock().getWorld())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCrossbowFirework(EntityShootBowEvent event) {
        if (!(event.getEntity() instanceof Player)) {
            return;
        }
        ItemStack consumable = event.getConsumable();
        if (consumable == null || consumable.getType() != Material.FIREWORK_ROCKET) {
            return;
        }
        if (blockFirework((Player) event.getEntity())) {
            event.setCancelled(true);
        }
    }

    // ------------------------------------------------------------ команда

    private boolean admin(CommandSender sender) {
        return sender.hasPermission("voidcombat.admin");
    }

    private void sendHelp(CommandSender sender) {
        String prefix = getConfig().getString("messages.prefix", "");
        for (String line : getConfig().getStringList("messages.help")) {
            sender.sendMessage(comp((prefix == null ? "" : prefix) + line));
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "reload" -> {
                if (!admin(sender)) {
                    msg(sender, "no-permission");
                    return true;
                }
                reloadConfig();
                loadState();
                hideAllCombatBars();
                msg(sender, "reloaded");
                return true;
            }
            case "on", "off" -> {
                if (!admin(sender)) {
                    msg(sender, "no-permission");
                    return true;
                }
                boolean on = sub.equals("on");
                enabledOverride = on;
                if (!on) {
                    combatUntil.clear();
                    hideAllCombatBars();
                }
                saveState();
                msg(sender, on ? "mode-enabled" : "mode-disabled",
                        "{seconds}", String.valueOf(combatDuration()));
                return true;
            }
            case "settime", "time", "duration" -> {
                if (!admin(sender)) {
                    msg(sender, "no-permission");
                    return true;
                }
                Integer seconds = args.length >= 2 ? parseInt(args[1]) : null;
                if (seconds == null || seconds < 1 || seconds > 3600) {
                    msg(sender, "invalid-seconds");
                    return true;
                }
                durationOverride = seconds;
                saveState();
                msg(sender, "time-set", "{seconds}", String.valueOf(seconds));
                return true;
            }
            case "info" -> {
                if (!admin(sender)) {
                    msg(sender, "no-permission");
                    return true;
                }
                msg(sender, "info",
                        "{state}", getConfig().getString(combatEnabled()
                                ? "messages.state-on" : "messages.state-off", ""),
                        "{seconds}", String.valueOf(combatDuration()),
                        "{worlds}", String.valueOf(safeWorldNames().size()),
                        "{zones}", String.valueOf(safeZones.size()),
                        "{fighting}", String.valueOf(combatUntil.size()));
                return true;
            }
            case "world" -> {
                if (!admin(sender)) {
                    msg(sender, "no-permission");
                    return true;
                }
                handleWorld(sender, args);
                return true;
            }
            case "zone" -> {
                if (!admin(sender)) {
                    msg(sender, "no-permission");
                    return true;
                }
                handleZone(sender, args);
                return true;
            }
            case "help" -> {
                sendHelp(sender);
                return true;
            }
            default -> {
                if (!(sender instanceof Player player)) {
                    sendHelp(sender);
                    return true;
                }
                if (!sub.isEmpty() && admin(sender)) {
                    sendHelp(sender);
                    return true;
                }
                if (!combatEnabled()) {
                    msg(player, "combat-status-off");
                } else if (isInCombat(player)) {
                    msg(player, "combat-status-active",
                            "{seconds}", String.valueOf(combatLeftSeconds(player.getUniqueId())));
                } else {
                    msg(player, "combat-status-none");
                }
                return true;
            }
        }
    }

    private Integer parseInt(String text) {
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** /combat world add|remove|list [мир] — безопасные миры без режима боя. */
    private void handleWorld(CommandSender sender, String[] args) {
        String action = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "";
        switch (action) {
            case "add", "remove" -> {
                String worldName;
                if (args.length >= 3) {
                    worldName = args[2];
                } else if (sender instanceof Player player) {
                    worldName = player.getWorld().getName();
                } else {
                    msg(sender, "world-usage");
                    return;
                }
                if (action.equals("add")) {
                    if (Bukkit.getWorld(worldName) == null) {
                        msg(sender, "world-not-found", "{world}", worldName);
                        return;
                    }
                    stateSafeWorlds.add(worldName.toLowerCase(Locale.ROOT));
                    saveState();
                    msg(sender, "world-added", "{world}", worldName);
                } else {
                    boolean inState = stateSafeWorlds.remove(worldName.toLowerCase(Locale.ROOT));
                    boolean inConfig = getConfig().getStringList("combat.safe.worlds").stream()
                            .anyMatch(w -> w.equalsIgnoreCase(worldName));
                    if (!inState && !inConfig) {
                        msg(sender, "world-not-safe", "{world}", worldName);
                        return;
                    }
                    saveState();
                    // Мир из config.yml командой не убрать — честно предупреждаем.
                    msg(sender, inConfig && !inState ? "world-in-config" : "world-removed",
                            "{world}", worldName);
                }
            }
            case "list" -> {
                Set<String> worlds = safeWorldNames();
                if (worlds.isEmpty()) {
                    msg(sender, "worlds-empty");
                } else {
                    msg(sender, "worlds-list", "{worlds}", String.join(", ", worlds));
                }
            }
            default -> msg(sender, "world-usage");
        }
    }

    /** /combat zone add <имя> [радиус] | remove <имя> | list — зоны-круги. */
    private void handleZone(CommandSender sender, String[] args) {
        String action = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "";
        switch (action) {
            case "add" -> {
                if (!(sender instanceof Player player)) {
                    msg(sender, "players-only");
                    return;
                }
                if (args.length < 3) {
                    msg(sender, "zone-usage");
                    return;
                }
                String name = args[2].toLowerCase(Locale.ROOT);
                if (safeZones.stream().anyMatch(z -> z.name().equals(name))) {
                    msg(sender, "zone-exists", "{name}", name);
                    return;
                }
                int radius = getConfig().getInt("combat.safe.default-zone-radius", 30);
                if (args.length >= 4) {
                    Integer parsed = parseInt(args[3]);
                    if (parsed == null || parsed < 1 || parsed > 1000) {
                        msg(sender, "zone-usage");
                        return;
                    }
                    radius = parsed;
                }
                Location loc = player.getLocation();
                safeZones.add(new SafeZone(name, loc.getWorld().getName(),
                        loc.getX(), loc.getY(), loc.getZ(), radius));
                saveState();
                msg(sender, "zone-added", "{name}", name,
                        "{radius}", String.valueOf(radius));
            }
            case "remove" -> {
                if (args.length < 3) {
                    msg(sender, "zone-usage");
                    return;
                }
                String name = args[2].toLowerCase(Locale.ROOT);
                if (!safeZones.removeIf(z -> z.name().equals(name))) {
                    msg(sender, "zone-not-found", "{name}", name);
                    return;
                }
                saveState();
                msg(sender, "zone-removed", "{name}", name);
            }
            case "list" -> {
                if (safeZones.isEmpty()) {
                    msg(sender, "zones-empty");
                } else {
                    List<String> names = new ArrayList<>();
                    for (SafeZone zone : safeZones) {
                        names.add(zone.name() + " (" + zone.world() + ", r=" + zone.radius() + ")");
                    }
                    msg(sender, "zones-list", "{zones}", String.join(", ", names));
                }
            }
            default -> msg(sender, "zone-usage");
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> result = new ArrayList<>();
        if (!admin(sender)) {
            return result;
        }
        String prefix = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        List<String> options = List.of();
        if (args.length == 1) {
            options = List.of("on", "off", "settime", "world", "zone", "info", "reload", "help");
        } else if (args.length == 2 && args[0].equalsIgnoreCase("world")) {
            options = List.of("add", "remove", "list");
        } else if (args.length == 2 && args[0].equalsIgnoreCase("zone")) {
            options = List.of("add", "remove", "list");
        } else if (args.length == 2 && args[0].equalsIgnoreCase("settime")) {
            options = List.of("15", "30", "45", "60", "90");
        } else if (args.length == 3 && args[0].equalsIgnoreCase("world")
                && (args[1].equalsIgnoreCase("add") || args[1].equalsIgnoreCase("remove"))) {
            List<String> worlds = new ArrayList<>();
            for (org.bukkit.World world : Bukkit.getWorlds()) {
                worlds.add(world.getName());
            }
            options = worlds;
        } else if (args.length == 3 && args[0].equalsIgnoreCase("zone")
                && args[1].equalsIgnoreCase("remove")) {
            List<String> names = new ArrayList<>();
            for (SafeZone zone : safeZones) {
                names.add(zone.name());
            }
            options = names;
        }
        for (String option : options) {
            if (option.toLowerCase(Locale.ROOT).startsWith(prefix)) {
                result.add(option);
            }
        }
        return result;
    }
}
