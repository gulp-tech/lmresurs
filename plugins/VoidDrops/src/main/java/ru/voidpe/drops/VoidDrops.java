package ru.voidpe.drops;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.concurrent.ThreadLocalRandom;

/**
 * VoidDrops — монеты (Vault) выпадают с мобов и игроков зачарованным
 * золотым слитком. Подбор слитка начисляет монеты; сумма — в actionbar.
 * С игрока при смерти падает процент баланса (по умолчанию 10%,
 * если баланс меньше минимума — ничего не падает и не списывается).
 * У привилегий — буст монет с мобов по правам voiddrops.boost.<группа>
 * (секция boosts в конфиге, берётся наибольший множитель).
 */
public final class VoidDrops extends JavaPlugin implements Listener, CommandExecutor {

    private VaultHook vault;
    private NamespacedKey coinKey;
    private long lastHookTry;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        vault = new VaultHook(getLogger());
        coinKey = new NamespacedKey(this, "coin_amount");
        getServer().getPluginManager().registerEvents(this, this);
        if (getCommand("vdrops") != null) {
            getCommand("vdrops").setExecutor(this);
        }
        // Экономика (Essentials) может зарегистрироваться в Vault ПОЗЖЕ нас:
        // повторяем подключение через 5 секунд после старта.
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (!vaultOk()) {
                getLogger().warning("Vault: экономика пока не найдена, подключусь при первом дропе/подборе");
            }
        }, 100L);
        getLogger().info("VoidDrops включён");
    }

    private boolean vaultOk() {
        return vault != null && vault.available();
    }

    /** Создаёт VaultHook заново, если экономики ещё нет (не чаще раза в 3 сек). */
    private void ensureVault() {
        if (vaultOk()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastHookTry < 3000L) {
            return;
        }
        lastHookTry = now;
        vault = new VaultHook(getLogger());
    }

    /** Другой плагин (Vault/Essentials) включился позже нас — пробуем подключиться. */
    @EventHandler
    public void onPluginEnable(PluginEnableEvent event) {
        if (!vaultOk()) {
            lastHookTry = 0L;
            ensureVault();
        }
    }

    private Component comp(String legacy) {
        return LegacyComponentSerializer.legacySection().deserialize(TextUtil.color(legacy))
                .decoration(TextDecoration.ITALIC, false);
    }

    private String fmt(double amount) {
        if (amount == Math.floor(amount)) {
            return String.format("%,d", (long) amount).replace(',', ' ');
        }
        return String.format("%,.2f", amount).replace(',', ' ');
    }

    // ------------------------------------------------------------------ буст

    /** Наибольший множитель монет с мобов по правам voiddrops.boost.<ключ>. */
    private double multiplierFor(Player player) {
        if (player == null || !getConfig().getBoolean("boosts.enabled", true)) {
            return 1.0;
        }
        var section = getConfig().getConfigurationSection("boosts.values");
        if (section == null) {
            return 1.0;
        }
        double best = 1.0;
        for (String key : section.getKeys(false)) {
            if (player.hasPermission("voiddrops.boost." + key)) {
                best = Math.max(best, section.getDouble(key, 1.0));
            }
        }
        return best;
    }

    // ------------------------------------------------------------------ убийца

    /**
     * Кто убил: сначала getKiller(), запасной путь — последний урон
     * (прямой удар игрока или его снаряд). getKiller() в ряде случаев
     * (добивание, стрелы, падение после удара) возвращает null — раньше
     * из-за этого монеты с мобов молча не выпадали.
     */
    private Player resolveKiller(LivingEntity entity) {
        Player killer = entity.getKiller();
        if (killer != null) {
            return killer;
        }
        EntityDamageEvent last = entity.getLastDamageCause();
        if (last instanceof EntityDamageByEntityEvent byEntity) {
            if (byEntity.getDamager() instanceof Player player) {
                return player;
            }
            if (byEntity.getDamager() instanceof Projectile projectile
                    && projectile.getShooter() instanceof Player player) {
                return player;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ дроп

    private void spawnCoin(Location loc, long amount) {
        if (amount <= 0 || loc == null || loc.getWorld() == null) {
            return;
        }
        ItemStack stack = new ItemStack(Material.GOLD_INGOT);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            String name = getConfig().getString("item.name", "&#FFD93DМонеты: &f{amount}");
            meta.displayName(comp(name.replace("{amount}", fmt(amount))));
            meta.addEnchant(Enchantment.UNBREAKING, 1, true);
            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
            meta.getPersistentDataContainer().set(coinKey, PersistentDataType.LONG, amount);
            stack.setItemMeta(meta);
        }
        Item dropped = loc.getWorld().dropItemNaturally(loc, stack);
        dropped.setPickupDelay(10);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMobDeath(EntityDeathEvent event) {
        if (event.getEntity() instanceof Player) {
            return;
        }
        if (!getConfig().getBoolean("mobs.enabled", true)) {
            return;
        }
        Player killer = resolveKiller(event.getEntity());
        if (getConfig().getBoolean("mobs.only-player-kill", true) && killer == null) {
            return;
        }
        double chance = getConfig().getDouble("mobs.chance", 0.35);
        if (ThreadLocalRandom.current().nextDouble() > chance) {
            return;
        }
        long min = Math.max(1, getConfig().getLong("mobs.min", 1));
        long max = Math.max(min, getConfig().getLong("mobs.max", 5));
        long amount = min == max ? min : ThreadLocalRandom.current().nextLong(min, max + 1);
        double boost = multiplierFor(killer);
        if (boost > 1.0) {
            amount = Math.max(1L, Math.round(amount * boost));
        }
        spawnCoin(event.getEntity().getLocation(), amount);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerDeath(PlayerDeathEvent event) {
        if (!getConfig().getBoolean("players.enabled", true)) {
            return;
        }
        ensureVault();
        if (!vaultOk()) {
            return;
        }
        Player dead = event.getEntity();
        double balance = vault.balance(dead);
        double minBalance = getConfig().getDouble("players.min-balance", 100);
        if (balance < minBalance) {
            return; // меньше минимума — ничего не падает и не списывается
        }
        double percent = getConfig().getDouble("players.percent", 10);
        long amount = (long) Math.floor(balance * percent / 100.0);
        if (amount <= 0) {
            return;
        }
        if (!vault.withdraw(dead, amount)) {
            return;
        }
        spawnCoin(dead.getLocation(), amount);
    }

    // ------------------------------------------------------------------ подбор

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player)) {
            return;
        }
        ItemStack stack = event.getItem().getItemStack();
        if (stack.getItemMeta() == null) {
            return;
        }
        Long amount = stack.getItemMeta().getPersistentDataContainer()
                .get(coinKey, PersistentDataType.LONG);
        if (amount == null) {
            return;
        }
        event.setCancelled(true);
        Player player = (Player) event.getEntity();
        long total = amount * stack.getAmount();
        ensureVault();
        if (!vaultOk() || !vault.deposit(player, total)) {
            // Экономики нет — НЕ съедаем монету: слиток остаётся лежать,
            // его можно будет подобрать, когда Vault подключится.
            event.getItem().setPickupDelay(40);
            return;
        }
        event.getItem().remove();
        String text = getConfig().getString("messages.picked", "&#FFD93D+{amount} монет");
        player.sendActionBar(comp(text.replace("{amount}", fmt(total))));
    }

    // ------------------------------------------------------------------ команда

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("voiddrops.admin")) {
            sender.sendMessage(comp("&cТолько для админов."));
            return true;
        }
        if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
            reloadConfig();
            lastHookTry = 0L;
            vault = new VaultHook(getLogger());
            sender.sendMessage(comp("&#7CFC00VoidDrops перезагружен. Экономика: "
                    + (vaultOk() ? "&aподключена" : "&cНЕ найдена")));
            return true;
        }
        ensureVault();
        sender.sendMessage(comp("&7VoidDrops: экономика Vault — "
                + (vaultOk() ? "&#7CFC00подключена" : "&cне найдена")
                + "&7; /vdrops reload — перезагрузка"));
        return true;
    }
}
