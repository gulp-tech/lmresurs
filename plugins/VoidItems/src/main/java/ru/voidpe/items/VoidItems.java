package ru.voidpe.items;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * VoidItems — кастомные предметы как на FunTime (Bedrock-only сервер VoidPE):
 * трапка, явная пыль, дезориентация, лом спавнеров, особые зелья.
 */
public final class VoidItems extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {

    private ItemsService service;

    /** Маркер нашего меню, чтобы отличать его от обычных инвентарей. */
    static final class MenuHolder implements InventoryHolder {
        @Override
        public Inventory getInventory() {
            return null;
        }
    }

    @Override
    public void onEnable() {
        migrateFromFaralItems();
        saveDefaultConfig();
        migrateConfig();
        service = new ItemsService(this);
        getServer().getPluginManager().registerEvents(this, this);
        getServer().getPluginManager().registerEvents(service, this);
        if (getCommand("items") != null) {
            getCommand("items").setExecutor(this);
            getCommand("items").setTabCompleter(this);
        }
        getLogger().info("VoidItems включён, предметов: " + service.all().size());
    }

    /** Переезд с FaralItems: подхватить старый конфиг и схематики из plugins/FaralItems. */
    private void migrateFromFaralItems() {
        try {
            java.io.File pluginsDir = getDataFolder().getParentFile();
            if (pluginsDir == null) {
                return;
            }
            java.io.File oldDir = new java.io.File(pluginsDir, "FaralItems");
            if (!oldDir.isDirectory() || oldDir.equals(getDataFolder())) {
                return;
            }
            getDataFolder().mkdirs();
            java.io.File oldConfig = new java.io.File(oldDir, "config.yml");
            java.io.File newConfig = new java.io.File(getDataFolder(), "config.yml");
            if (oldConfig.isFile() && !newConfig.exists()) {
                java.nio.file.Files.copy(oldConfig.toPath(), newConfig.toPath());
                getLogger().info("Перенёс config.yml из старой папки FaralItems.");
            }
            java.io.File oldSchem = new java.io.File(oldDir, "schematics");
            java.io.File newSchem = new java.io.File(getDataFolder(), "schematics");
            newSchem.mkdirs();
            java.io.File[] files = oldSchem.listFiles();
            if (files != null) {
                for (java.io.File f : files) {
                    java.io.File target = new java.io.File(newSchem, f.getName());
                    if (f.isFile() && !target.exists()) {
                        java.nio.file.Files.copy(f.toPath(), target.toPath());
                        getLogger().info("Перенёс схематику " + f.getName() + " из старой папки FaralItems.");
                    }
                }
            }
        } catch (Throwable t) {
            getLogger().warning("Не удалось перенести данные FaralItems: " + t.getMessage());
        }
    }

    /**
     * Обновление старого config.yml на диске. Без этого новые ключи и материалы
     * не появятся: saveDefaultConfig() не перезаписывает существующий файл.
     * Меняем только те значения, что остались прежними по умолчанию.
     */
    private void migrateConfig() {
        int version = getConfig().getInt("config-version", 1);
        if (version >= 2) {
            return;
        }
        boolean changed = false;
        if ("GLOWSTONE_DUST".equalsIgnoreCase(getConfig().getString("items.dust.material", ""))) {
            // Bedrock не отправляет клик по воздуху для светопыли — снежок отправляет.
            getConfig().set("items.dust.material", "SNOWBALL");
            changed = true;
        }
        if ("NETHERITE_SCRAP".equalsIgnoreCase(getConfig().getString("items.trapka.material", ""))) {
            getConfig().set("items.trapka.material", "FIRE_CHARGE");
            changed = true;
        }
        if (!getConfig().isSet("banned.allow-potions")) {
            getConfig().set("banned.allow-potions", true);
            changed = true;
        }
        if (!getConfig().isSet("identity.migrate-legacy")) {
            getConfig().set("identity.migrate-legacy", true);
            changed = true;
        }
        if (!getConfig().isSet("identity.match-by-name")) {
            getConfig().set("identity.match-by-name", true);
            changed = true;
        }
        getConfig().set("config-version", 2);
        if (changed) {
            saveConfig();
            getLogger().info("Конфиг обновлён до версии 2: клик по воздуху, зелья на спавне, узнавание старых предметов.");
        }
    }

    @Override
    public void onDisable() {
        if (service != null) {
            service.shutdown(); // вернуть блоки всех активных трапок
        }
    }

    public ItemsService service() {
        return service;
    }

    // ------------------------------------------------------------------ меню
    public void openMenu(Player player) {
        List<CustomItem> all = new ArrayList<>(service.all());
        // Слотов должно хватать на все предметы, даже если в конфиге rows меньше.
        int needed = Math.max(1, (all.size() + 8) / 9);
        int rows = Math.max(1, Math.min(6, Math.max(getConfig().getInt("menu.rows", 2), needed)));
        Inventory inv = Bukkit.createInventory(new MenuHolder(), rows * 9,
                ItemsService.component(TextUtil.color(getConfig().getString("menu.title", "&#C86BFFКастомные предметы"))));
        int slot = 0;
        for (CustomItem item : all) {
            if (slot >= rows * 9) {
                break;
            }
            inv.setItem(slot++, service.createItem(item, 1));
        }
        player.openInventory(inv);
    }

    @EventHandler
    public void onMenuClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof MenuHolder)) {
            return;
        }
        event.setCancelled(true); // настоящий предмет выдаём сами
        if (!(event.getWhoClicked() instanceof Player)) {
            return;
        }
        Player player = (Player) event.getWhoClicked();
        if (!player.hasPermission("voiditems.admin")) {
            return;
        }
        String id = service.identify(event.getCurrentItem());
        CustomItem item = id == null ? null : service.byId(id);
        if (item == null) {
            return;
        }
        Map<Integer, ItemStack> rest = player.getInventory().addItem(service.createItem(item, 1));
        for (ItemStack drop : rest.values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), drop);
        }
        String prefix = TextUtil.color(getConfig().getString("messages.prefix", "&#C86BFFVoidPE &8» &f"));
        String text = getConfig().getString("messages.received", "&aВыдан предмет: {item}")
                .replace("{item}", item.displayName());
        player.sendMessage(ItemsService.component(prefix + TextUtil.color(text)));
    }

    // ------------------------------------------------------------------ команда
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String prefix = TextUtil.color(getConfig().getString("messages.prefix", "&#C86BFFVoidPE &8» &f"));
        // /items give <игрок> <id> [кол-во] — выдача из консоли (нужна донат-магазину DeluxeMenus).
        if (args.length >= 1 && args[0].equalsIgnoreCase("give")) {
            if (!sender.hasPermission("voiditems.admin")) {
                sender.sendMessage(ItemsService.component(prefix + TextUtil.color(
                        getConfig().getString("messages.no-permission", "&cТолько для админов."))));
                return true;
            }
            if (args.length < 3) {
                sender.sendMessage(ItemsService.component(prefix + TextUtil.color(
                        "&fИспользование: &b/items give <игрок> <id> [кол-во]")));
                return true;
            }
            Player target = Bukkit.getPlayerExact(args[1]);
            if (target == null) {
                sender.sendMessage(ItemsService.component(prefix + TextUtil.color(
                        getConfig().getString("messages.player-not-found", "&cИгрок не найден."))));
                return true;
            }
            CustomItem item = service.byId(args[2].toLowerCase(java.util.Locale.ROOT));
            if (item == null) {
                sender.sendMessage(ItemsService.component(prefix + TextUtil.color(
                        getConfig().getString("messages.item-not-found", "&cТакого предмета нет: &f{item}")
                                .replace("{item}", args[2]))));
                return true;
            }
            int amount = 1;
            if (args.length > 3) {
                try {
                    amount = Math.max(1, Math.min(64, Integer.parseInt(args[3])));
                } catch (NumberFormatException ignored) {
                }
            }
            Map<Integer, ItemStack> rest = target.getInventory().addItem(service.createItem(item, amount));
            for (ItemStack drop : rest.values()) {
                target.getWorld().dropItemNaturally(target.getLocation(), drop);
            }
            String received = getConfig().getString("messages.received", "&aВыдан предмет: {item}")
                    .replace("{item}", item.displayName());
            target.sendMessage(ItemsService.component(prefix + TextUtil.color(received)));
            if (!sender.equals(target)) {
                String given = getConfig().getString("messages.given", "&aИгроку &f{player} &aвыдано: {item} &7x{amount}")
                        .replace("{player}", target.getName())
                        .replace("{item}", item.displayName())
                        .replace("{amount}", String.valueOf(amount));
                sender.sendMessage(ItemsService.component(prefix + TextUtil.color(given)));
            }
            return true;
        }
        if (!(sender instanceof Player)) {
            sender.sendMessage(prefix + "Команда только для игроков. Для выдачи: /items give <игрок> <id> [кол-во]");
            return true;
        }
        Player player = (Player) sender;
        if (!player.hasPermission("voiditems.admin")) {
            player.sendMessage(ItemsService.component(prefix + TextUtil.color(
                    getConfig().getString("messages.no-permission", "&cТолько для админов."))));
            return true;
        }
        openMenu(player);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> result = new ArrayList<>();
        if (!sender.hasPermission("voiditems.admin")) {
            return result;
        }
        if (args.length == 1 && "give".startsWith(args[0].toLowerCase(java.util.Locale.ROOT))) {
            result.add("give");
        } else if (args.length == 2 && args[0].equalsIgnoreCase("give")) {
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (player.getName().toLowerCase(java.util.Locale.ROOT).startsWith(args[1].toLowerCase(java.util.Locale.ROOT))) {
                    result.add(player.getName());
                }
            }
        } else if (args.length == 3 && args[0].equalsIgnoreCase("give")) {
            for (CustomItem item : service.all()) {
                if (item.id().startsWith(args[2].toLowerCase(java.util.Locale.ROOT))) {
                    result.add(item.id());
                }
            }
        }
        return result;
    }
}
