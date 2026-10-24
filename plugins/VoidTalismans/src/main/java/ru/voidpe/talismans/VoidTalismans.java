package ru.voidpe.talismans;

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
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.List;

/**
 * VoidTalismans — талисманы как на FunTime: носишь в инвентаре — получаешь эффекты.
 * Bedrock-only сервер VoidPE (Geyser/Floodgate), Purpur 26.2.
 */
public final class VoidTalismans extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {

    private TalismanService service;
    private BukkitTask task;

    /** Маркер нашего меню, чтобы отличать его от обычных инвентарей. */
    static final class MenuHolder implements InventoryHolder {
        @Override
        public Inventory getInventory() {
            return null;
        }
    }

    @Override
    public void onEnable() {
        migrateConfig();
        saveDefaultConfig();
        service = new TalismanService(this);
        getServer().getPluginManager().registerEvents(this, this);
        if (getCommand("talisman") != null) {
            getCommand("talisman").setExecutor(this);
            getCommand("talisman").setTabCompleter(this);
        }
        if (getCommand("sphere") != null) {
            getCommand("sphere").setExecutor(this);
            getCommand("sphere").setTabCompleter(this);
        }
        startTask();
        getLogger().info("VoidTalismans включён, талисманов: " + service.all().size());
    }

    @Override
    public void onDisable() {
        if (task != null) {
            task.cancel();
        }
        Bukkit.getOnlinePlayers().forEach(p -> service.clearAttributes(p));
    }

    public TalismanService service() {
        return service;
    }

    /** Переименование FaralTalismans -> VoidTalismans: подхватить старый config.yml один раз. */
    private void migrateConfig() {
        java.io.File newConfig = new java.io.File(getDataFolder(), "config.yml");
        if (newConfig.exists()) {
            return;
        }
        java.io.File oldConfig = new java.io.File(getDataFolder().getParentFile(), "FaralTalismans/config.yml");
        if (!oldConfig.exists()) {
            return;
        }
        try {
            getDataFolder().mkdirs();
            java.nio.file.Files.copy(oldConfig.toPath(), newConfig.toPath());
            getLogger().info("Конфиг перенесён из FaralTalismans в VoidTalismans.");
        } catch (Exception e) {
            getLogger().warning("Не удалось перенести старый конфиг: " + e.getMessage());
        }
    }

    static boolean isAdmin(CommandSender sender) {
        return sender.hasPermission("voidtalismans.admin") || sender.hasPermission("faraltalismans.admin");
    }

    private void startTask() {
        if (task != null) {
            task.cancel();
        }
        long interval = Math.max(10L, getConfig().getLong("settings.refresh-interval-ticks", 40L));
        task = Bukkit.getScheduler().runTaskTimer(this,
                () -> Bukkit.getOnlinePlayers().forEach(service::refresh), interval, interval);
    }

    // ------------------------------------------------------------------ меню
    public void openMenu(Player player) {
        int rows = Math.max(1, Math.min(6, getConfig().getInt("menu.rows", 3)));
        Inventory inv = Bukkit.createInventory(new MenuHolder(), rows * 9,
                TalismanService.component(TextUtil.color(getConfig().getString("menu.title", "&#C86BFFТалисманы"))));
        int slot = 0;
        for (Talisman t : service.all()) {
            if (slot >= rows * 9) {
                break;
            }
            ItemStack item = service.createItem(t, 1);
            inv.setItem(slot++, item);
        }
        player.openInventory(inv);
    }

    @EventHandler
    public void onMenuClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof MenuHolder)) {
            return;
        }
        event.setCancelled(true); // предмет из меню не забираем — выдаём настоящий
        if (!(event.getWhoClicked() instanceof Player)) {
            return;
        }
        Player player = (Player) event.getWhoClicked();
        if (!isAdmin(player)) {
            return;
        }
        String id = service.identify(event.getCurrentItem());
        Talisman t = id == null ? null : service.byId(id);
        if (t == null) {
            return;
        }
        java.util.Map<Integer, ItemStack> rest = player.getInventory().addItem(service.createItem(t, 1));
        for (ItemStack drop : rest.values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), drop);
        }
        String prefix = TextUtil.color(getConfig().getString("messages.prefix", "&#C86BFFVoidPE &8» &f"));
        player.sendMessage(TalismanService.component(prefix + TextUtil.color(
                getConfig().getString("messages.received", "&aВы получили талисман!")
                        .replace("{talisman}", t.displayName()))));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        service.clearAttributes(event.getPlayer());
    }

    // ------------------------------------------------------------------ команды
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String prefix = TextUtil.color(getConfig().getString("messages.prefix", "&#C86BFFVoidPE &8» &f"));
        if (command.getName().equalsIgnoreCase("sphere")) {
            return onSphereCommand(sender, prefix, args);
        }
        if (!isAdmin(sender)) {
            sender.sendMessage(TalismanService.component(prefix + TextUtil.color(
                    getConfig().getString("messages.no-permission", "&cНет прав."))));
            return true;
        }
        if (args.length == 0) {
            if (sender instanceof Player) {
                openMenu((Player) sender);
            } else {
                sendHelp(sender, prefix);
            }
            return true;
        }
        String sub = args[0].toLowerCase();
        if (sub.equals("give")) {
            // /talisman give <игрок> <id> [кол-во] - работает и из консоли (для DeluxeMenus)
            if (args.length < 3) {
                sender.sendMessage(TalismanService.component(prefix + "&cИспользование: /talisman give <игрок> <id> [кол-во]"));
                return true;
            }
            Player target = Bukkit.getPlayerExact(args[1]);
            if (target == null) {
                sender.sendMessage(TalismanService.component(prefix + TextUtil.color(
                        getConfig().getString("messages.player-not-found", "&cИгрок не найден."))));
                return true;
            }
            Talisman t = service.byId(args[2]);
            if (t == null) {
                sender.sendMessage(TalismanService.component(prefix + TextUtil.color(
                        getConfig().getString("messages.not-found", "&cТакого предмета нет."))));
                return true;
            }
            int amount = 1;
            if (args.length >= 4) {
                try {
                    amount = Math.max(1, Math.min(64, Integer.parseInt(args[3])));
                } catch (NumberFormatException ignored) {
                }
            }
            java.util.Map<Integer, ItemStack> rest = target.getInventory().addItem(service.createItem(t, amount));
            for (ItemStack drop : rest.values()) {
                target.getWorld().dropItemNaturally(target.getLocation(), drop);
            }
            target.sendMessage(TalismanService.component(prefix + TextUtil.color(
                    getConfig().getString("messages.received", "&aТы получил предмет!")
                            .replace("{talisman}", t.displayName()))));
            sender.sendMessage(TalismanService.component(prefix + TextUtil.color(
                    getConfig().getString("messages.given", "&aВыдано игроку {player}: &f{talisman} &7x{amount}")
                            .replace("{player}", target.getName())
                            .replace("{talisman}", t.displayName())
                            .replace("{amount}", String.valueOf(amount)))));
            return true;
        }
        if (sub.equals("list")) {
            StringBuilder sb = new StringBuilder();
            for (Talisman t : service.all()) {
                if (sb.length() > 0) {
                    sb.append("&7, &f");
                }
                sb.append(t.id());
            }
            sender.sendMessage(TalismanService.component(prefix + "&f" + sb));
            return true;
        }
        if (sub.equals("reload")) {
            reloadConfig();
            service.reload();
            if (task != null) {
                task.cancel();
            }
            startTask();
            sender.sendMessage(TalismanService.component(prefix + TextUtil.color(
                    getConfig().getString("messages.reloaded", "&aКонфиг перезагружен."))));
            return true;
        }
        sendHelp(sender, prefix);
        return true;
    }

    /** Отдельная команда сфер: /sphere give <игрок> <уровень 1-4> [кол-во] | /sphere exchange <игрок> | /sphere list. */
    private boolean onSphereCommand(CommandSender sender, String prefix, String[] args) {
        if (!isAdmin(sender)) {
            sender.sendMessage(TalismanService.component(prefix + TextUtil.color(
                    getConfig().getString("messages.no-permission", "&cНет прав."))));
            return true;
        }
        if (args.length >= 1 && args[0].equalsIgnoreCase("list")) {
            StringBuilder sb = new StringBuilder();
            for (Talisman t : service.all()) {
                if (!t.id().startsWith("sphere")) {
                    continue;
                }
                if (sb.length() > 0) {
                    sb.append("&7, &f");
                }
                sb.append(t.id());
            }
            sender.sendMessage(TalismanService.component(prefix + "&f" + sb));
            return true;
        }
        if (args.length >= 2 && args[0].equalsIgnoreCase("exchange")) {
            // Обмен «яйцо дракона -> Сфера пустоты» одним атомарным действием:
            // сначала проверяем и забираем яйцо, только потом выдаём сферу.
            // (Раньше это делали две консольные команды в DeluxeMenus, и clear
            // мог не сработать из-за Essentials — сфера выдавалась даром.)
            Player target = Bukkit.getPlayerExact(args[1]);
            if (target == null) {
                sender.sendMessage(TalismanService.component(prefix + TextUtil.color(
                        getConfig().getString("messages.player-not-found", "&cИгрок не найден."))));
                return true;
            }
            org.bukkit.Material mat = org.bukkit.Material.matchMaterial(
                    getConfig().getString("sphere-exchange.material", "DRAGON_EGG"));
            String giveId = getConfig().getString("sphere-exchange.give", "sphere4");
            Talisman t = giveId == null ? null : service.byId(giveId);
            if (mat == null || t == null) {
                sender.sendMessage(TalismanService.component(prefix + TextUtil.color(
                        getConfig().getString("messages.not-found", "&cТакого предмета нет."))));
                return true;
            }
            int need = Math.max(1, getConfig().getInt("sphere-exchange.amount", 1));
            // считаем предметы: основная сетка инвентаря + левая рука
            org.bukkit.inventory.PlayerInventory inv = target.getInventory();
            org.bukkit.inventory.ItemStack[] contents = inv.getStorageContents();
            org.bukkit.inventory.ItemStack off = inv.getItemInOffHand();
            int have = 0;
            for (org.bukkit.inventory.ItemStack it : contents) {
                if (it != null && it.getType() == mat) {
                    have += it.getAmount();
                }
            }
            if (off != null && off.getType() == mat) {
                have += off.getAmount();
            }
            if (have < need) {
                target.sendMessage(TalismanService.component(prefix + TextUtil.color(
                        getConfig().getString("messages.exchange-no-item",
                                "&cДля обмена нужно: &fяйцо дракона&c. Сфера не выдана."))));
                return true;
            }
            int left = need;
            for (int i = 0; i < contents.length && left > 0; i++) {
                org.bukkit.inventory.ItemStack it = contents[i];
                if (it == null || it.getType() != mat) {
                    continue;
                }
                int take = Math.min(left, it.getAmount());
                it.setAmount(it.getAmount() - take);
                inv.setItem(i, it.getAmount() <= 0 ? null : it);
                left -= take;
            }
            if (left > 0 && off != null && off.getType() == mat) {
                int take = Math.min(left, off.getAmount());
                off.setAmount(off.getAmount() - take);
                inv.setItemInOffHand(off.getAmount() <= 0 ? null : off);
            }
            java.util.Map<Integer, org.bukkit.inventory.ItemStack> rest =
                    target.getInventory().addItem(service.createItem(t, 1));
            for (org.bukkit.inventory.ItemStack drop : rest.values()) {
                target.getWorld().dropItemNaturally(target.getLocation(), drop);
            }
            target.sendMessage(TalismanService.component(prefix + TextUtil.color(
                    getConfig().getString("messages.exchange-success", "&aОбмен завершён! Ты получил: &f{talisman}&a!")
                            .replace("{talisman}", t.displayName()))));
            if (sender != target) {
                sender.sendMessage(TalismanService.component(prefix + TextUtil.color(
                        getConfig().getString("messages.given", "&aВыдано игроку {player}: &f{talisman} &7x{amount}")
                                .replace("{player}", target.getName())
                                .replace("{talisman}", t.displayName())
                                .replace("{amount}", "1"))));
            }
            return true;
        }
        if (args.length >= 3 && args[0].equalsIgnoreCase("give")) {
            Player target = Bukkit.getPlayerExact(args[1]);
            if (target == null) {
                sender.sendMessage(TalismanService.component(prefix + TextUtil.color(
                        getConfig().getString("messages.player-not-found", "&cИгрок не найден."))));
                return true;
            }
            String idArg = args[2].toLowerCase();
            String id = idArg.startsWith("sphere") ? idArg : "sphere" + idArg;
            Talisman t = service.byId(id);
            if (t == null) {
                sender.sendMessage(TalismanService.component(prefix + TextUtil.color(
                        getConfig().getString("messages.not-found", "&cТакого предмета нет."))));
                return true;
            }
            int amount = 1;
            if (args.length >= 4) {
                try {
                    amount = Math.max(1, Math.min(64, Integer.parseInt(args[3])));
                } catch (NumberFormatException ignored) {
                }
            }
            java.util.Map<Integer, ItemStack> rest = target.getInventory().addItem(service.createItem(t, amount));
            for (ItemStack drop : rest.values()) {
                target.getWorld().dropItemNaturally(target.getLocation(), drop);
            }
            target.sendMessage(TalismanService.component(prefix + TextUtil.color(
                    getConfig().getString("messages.received", "&aТы получил: &f{talisman}&a!")
                            .replace("{talisman}", t.displayName()))));
            sender.sendMessage(TalismanService.component(prefix + TextUtil.color(
                    getConfig().getString("messages.given", "&aВыдано игроку {player}: &f{talisman} &7x{amount}")
                            .replace("{player}", target.getName())
                            .replace("{talisman}", t.displayName())
                            .replace("{amount}", String.valueOf(amount)))));
            return true;
        }
        sender.sendMessage(TalismanService.component(prefix + "&f/sphere give <игрок> <1|2|3|4> [кол-во] &7| &f/sphere exchange <игрок> &7| &f/sphere list"));
        return true;
    }

    private void sendHelp(CommandSender sender, String prefix) {
        for (String line : getConfig().getStringList("messages.help")) {
            sender.sendMessage(TalismanService.component(prefix + TextUtil.color(line)));
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!isAdmin(sender)) {
            return List.of();
        }
        if (command.getName().equalsIgnoreCase("sphere")) {
            if (args.length == 1) {
                List<String> out = new ArrayList<>();
                for (String sub : new String[]{"give", "list"}) {
                    if (sub.startsWith(args[0].toLowerCase())) {
                        out.add(sub);
                    }
                }
                return out;
            }
            if (args.length == 2 && args[0].equalsIgnoreCase("give")) {
                return null; // ники игроков
            }
            if (args.length == 3 && args[0].equalsIgnoreCase("give")) {
                List<String> out = new ArrayList<>();
                for (Talisman t : service.all()) {
                    if (t.id().startsWith("sphere")) {
                        String num = t.id().substring("sphere".length());
                        if (num.startsWith(args[2])) {
                            out.add(num);
                        }
                    }
                }
                return out;
            }
            return List.of();
        }
        if (args.length == 1) {
            List<String> out = new ArrayList<>();
            for (String sub : new String[]{"give", "list", "reload", "help"}) {
                if (sub.startsWith(args[0].toLowerCase())) {
                    out.add(sub);
                }
            }
            return out;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("give")) {
            return null; // ники игроков подскажет сам сервер
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("give")) {
            List<String> out = new ArrayList<>();
            for (Talisman t : service.all()) {
                if (t.id().startsWith(args[2].toLowerCase())) {
                    out.add(t.id());
                }
            }
            return out;
        }
        return List.of();
    }
}
