package ru.voidpe.clans;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;

/**
 * VoidClans — кланы для Bedrock-сервера VoidPE:
 * создание, банк на монетах Vault, приглашения, роли с настройкой прав,
 * кик/передача лидерства, дом клана, клан-чат и удобное меню (/clan).
 */
public final class VoidClans extends JavaPlugin implements CommandExecutor, TabCompleter {

    private ClanService service;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        service = new ClanService(this);
        getServer().getPluginManager().registerEvents(service, this);
        if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            if (new ClanPlaceholders(this).register()) {
                getLogger().info("PlaceholderAPI: плейсхолдеры %voidclans_% зарегистрированы");
            }
        }
        if (getCommand("clan") != null) {
            getCommand("clan").setExecutor(this);
            getCommand("clan").setTabCompleter(this);
        }
        getLogger().info("VoidClans включён");
    }

    @Override
    public void onDisable() {
        if (service != null) {
            service.saveData();
        }
    }

    public ClanService service() {
        return service;
    }

    private void send(CommandSender sender, String legacy) {
        String prefix = TextUtil.color(getConfig().getString("messages.prefix", "&#C86BFFVoidPE &8» &f"));
        sender.sendMessage(ClanService.comp(prefix + TextUtil.color(legacy)));
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("Команда только для игроков.");
            return true;
        }
        Player player = (Player) sender;
        if (args.length == 0) {
            service.openMain(player);
            return true;
        }
        String sub = args[0].toLowerCase();
        try {
            switch (sub) {
                case "help" -> help(player);
                case "create" -> {
                    if (args.length < 2) {
                        send(player, "&7Использование: &f/clan create <название>");
                    } else {
                        service.createClan(player, args[1]);
                    }
                }
                case "invite" -> {
                    if (args.length < 2) {
                        send(player, "&7Использование: &f/clan invite <игрок>");
                    } else {
                        service.invite(player, args[1]);
                    }
                }
                case "accept" -> service.accept(player);
                case "deny" -> service.deny(player);
                case "leave" -> service.leave(player);
                case "kick" -> {
                    if (args.length < 2) {
                        send(player, "&7Использование: &f/clan kick <игрок>");
                    } else {
                        service.kick(player, args[1]);
                    }
                }
                case "role" -> {
                    if (args.length < 3) {
                        send(player, "&7Использование: &f/clan role <игрок> <роль>");
                        rolesLine(player);
                    } else {
                        service.setRole(player, args[1], args[2]);
                    }
                }
                case "setperm" -> {
                    if (args.length < 3) {
                        send(player, "&7Использование: &f/clan setperm <роль> <право>");
                        rolesLine(player);
                        send(player, "&7Права: &f" + String.join(", ", ClanService.ALL_PERMS));
                    } else {
                        service.togglePerm(player, args[1].toLowerCase(), args[2].toLowerCase());
                    }
                }
                case "transfer" -> {
                    if (args.length < 2) {
                        send(player, "&7Использование: &f/clan transfer <игрок>");
                    } else {
                        service.transfer(player, args[1]);
                    }
                }
                case "disband" -> service.disband(player, args.length > 1 && args[1].equalsIgnoreCase("confirm"));
                case "deposit" -> {
                    Long amount = parseAmount(player, args);
                    if (amount != null) {
                        service.deposit(player, amount);
                    }
                }
                case "withdraw" -> {
                    Long amount = parseAmount(player, args);
                    if (amount != null) {
                        service.withdraw(player, amount);
                    }
                }
                case "bank" -> {
                    ClanService.Clan clan = service.clanOf(player.getUniqueId());
                    if (clan == null) {
                        service.msg(player, "not-in-clan");
                    } else {
                        send(player, "&7Банк клана &f" + clan.name + "&7: &f" + ClanService.fmt(clan.bank) + " монет");
                    }
                }
                case "sethome" -> service.setHome(player, args.length > 1 && args[1].equals("2") ? 2 : 1);
                case "level" -> {
                    ClanService.Clan clan = service.clanOf(player.getUniqueId());
                    if (clan == null) {
                        service.msg(player, "not-in-clan");
                    } else {
                        double toNext = service.xpToNext(clan);
                        send(player, "&7Клан &f" + clan.name + "&7: уровень &f" + clan.level
                                + "&7, опыт &f" + ClanService.fmt(clan.xp)
                                + (toNext < 0 ? " &7(максимум)" : "&7, до след. уровня: &f" + ClanService.fmt(toNext)));
                        send(player, "&7Опыт даётся за: блоки, мобов, убийства игроков, рыбалку.");
                    }
                }
                case "home" -> service.home(player, args.length > 1 && args[1].equals("2") ? 2 : 1);
                case "info" -> service.info(player, args.length > 1 ? args[1] : null);
                case "list", "top" -> service.top(player);
                case "c", "chat" -> {
                    if (args.length < 2) {
                        send(player, "&7Использование: &f/clan c <текст>");
                    } else {
                        service.chat(player, String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length)));
                    }
                }
                case "reload" -> {
                    if (!player.hasPermission("voidclans.admin")) {
                        send(player, getConfig().getString("messages.plugin-admin-only", "&cТолько для админов."));
                    } else {
                        reloadConfig();
                        service.loadTemplates();
                        send(player, "&aVoidClans перезагружен.");
                    }
                }
                default -> help(player);
            }
        } catch (Throwable t) {
            getLogger().warning("Ошибка /clan " + sub + ": " + t.getMessage());
            send(player, "&cЧто-то пошло не так, сообщи администрации.");
        }
        return true;
    }

    private Long parseAmount(Player player, String[] args) {
        if (args.length < 2) {
            send(player, "&7Использование: &f/clan " + args[0].toLowerCase() + " <сумма>");
            return null;
        }
        try {
            long amount = Long.parseLong(args[1]);
            if (amount <= 0) {
                throw new NumberFormatException();
            }
            return amount;
        } catch (NumberFormatException e) {
            service.msg(player, "amount-invalid");
            return null;
        }
    }

    private void rolesLine(Player player) {
        StringBuilder sb = new StringBuilder("&7Роли: ");
        boolean first = true;
        for (ClanService.RoleTemplate role : service.rolesSorted()) {
            if (!first) {
                sb.append("&7, ");
            }
            sb.append("&f").append(role.id());
            first = false;
        }
        send(player, sb.toString());
    }

    private void help(Player player) {
        send(player, "&#FFD93DКланы — команды:");
        send(player, "&f/clan &7— меню клана");
        send(player, "&f/clan create <название> &7— создать клан");
        send(player, "&f/clan invite <игрок> &7— пригласить; &f/clan accept&7/&fdeny &7— принять/отклонить");
        send(player, "&f/clan deposit <сумма> &7— внести монеты в банк; &f/clan withdraw <сумма> &7— снять (нужно право)");
        send(player, "&f/clan bank &7— баланс банка");
        send(player, "&f/clan role <игрок> <роль> &7— выдать роль; &f/clan setperm <роль> <право> &7— настроить права роли");
        send(player, "&f/clan kick <игрок> &7— кикнуть; &f/clan transfer <игрок> &7— передать лидерство");
        send(player, "&f/clan home [1|2] &7/ &f/clan sethome [1|2] &7— дом клана (2-й дом — за прокачку)");
        send(player, "&f/clan level &7— уровень и опыт клана");
        send(player, "&f/clan c <текст> &7— клан-чат");
        send(player, "&f/clan info [клан] &7— информация; &f/clan top &7— топ кланов");
        send(player, "&f/clan leave &7— выйти; &f/clan disband &7— распустить (лидер)");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> result = new ArrayList<>();
        if (!(sender instanceof Player)) {
            return result;
        }
        Player player = (Player) sender;
        if (args.length == 1) {
            for (String sub : List.of("help", "create", "invite", "accept", "deny", "leave", "kick", "role",
                    "setperm", "transfer", "disband", "deposit", "withdraw", "bank", "sethome", "home", "level",
                    "info", "top", "c", "reload")) {
                if (sub.startsWith(args[0].toLowerCase())) {
                    result.add(sub);
                }
            }
        } else if (args.length == 2) {
            String sub = args[0].toLowerCase();
            if (sub.equals("invite")) {
                for (Player online : Bukkit.getOnlinePlayers()) {
                    if (online.getName().toLowerCase().startsWith(args[1].toLowerCase())) {
                        result.add(online.getName());
                    }
                }
            } else if (sub.equals("kick") || sub.equals("role") || sub.equals("transfer")) {
                ClanService.Clan clan = service.clanOf(player.getUniqueId());
                if (clan != null) {
                    for (java.util.UUID member : clan.members.keySet()) {
                        String name = service.nameOf(member);
                        if (name.toLowerCase().startsWith(args[1].toLowerCase())) {
                            result.add(name);
                        }
                    }
                }
            } else if (sub.equals("setperm")) {
                for (ClanService.RoleTemplate role : service.rolesSorted()) {
                    if (role.id().startsWith(args[1].toLowerCase())) {
                        result.add(role.id());
                    }
                }
            } else if (sub.equals("info")) {
                // названия кланов подсказать сложно без API — пусто
            }
        } else if (args.length == 3) {
            if (args[0].equalsIgnoreCase("role")) {
                for (ClanService.RoleTemplate role : service.rolesSorted()) {
                    if (role.id().startsWith(args[2].toLowerCase())) {
                        result.add(role.id());
                    }
                }
            } else if (args[0].equalsIgnoreCase("setperm")) {
                for (String perm : ClanService.ALL_PERMS) {
                    if (perm.startsWith(args[2].toLowerCase())) {
                        result.add(perm);
                    }
                }
            }
        }
        return result;
    }
}
