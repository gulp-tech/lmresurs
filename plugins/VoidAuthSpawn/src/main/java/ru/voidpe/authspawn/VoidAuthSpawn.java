package ru.voidpe.authspawn;

import fr.xephi.authme.api.v3.AuthMeApi;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * VoidAuthSpawn — дополнение к AuthMe для Bedrock-сервера VoidPE.
 *
 * <p>Когда зашедшему игроку ещё нужно регистрироваться в AuthMe
 * (аккаунта в базе AuthMe нет), плагин телепортирует его в точку из
 * config.yml (секция {@code spawn:}). По желанию там же можно включить
 * телепорт уже зарегистрированных, но ещё не вошедших игроков
 * ({@code teleport.not-logged-in}) и удержание у точки до регистрации
 * ({@code hold.enabled}).</p>
 *
 * <p>После успешного {@code /login} (событие AuthMe {@code LoginEvent})
 * зарегистрированного игрока возвращает на прежнее место: туда, где он
 * появился при входе в этот раз, до всех телепортов к точке авторизации
 * (секция {@code return-after-login:}). Место запоминается при входе и
 * хранится в {@code return-locations.yml}, поэтому переживает рестарт.</p>
 *
 * <p>Кроме того, зарегистрированного игрока телепортирует на прежнее место
 * <b>сразу при входе</b>, не дожидаясь {@code /login}
 * (секция {@code teleport-on-join:}), а место выхода авторизованного
 * игрока запоминается при его выходе из игры.</p>
 *
 * <p>Проверка идёт через официальный AuthMe API
 * ({@code fr.xephi.authme.api.v3.AuthMeApi#isRegistered}), с задержкой
 * после входа и несколькими повторными проверками: так телепорт нашего
 * плагина срабатывает после того, как AuthMe и Floodgate закончат
 * свою обработку входа.</p>
 */
public final class VoidAuthSpawn extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {

    /** Игроки, которых мы уже отправили к точке и ждём регистрации/входа. */
    private final Set<UUID> awaitingAuth = new HashSet<>();
    /** Кому уже показали сообщение о телепорте (чтобы не спамить при повторных проверках). */
    private final Set<UUID> messaged = new HashSet<>();
    /** Прежнее место игрока (где появился при входе), куда вернуть после /login. */
    private final Map<UUID, Location> returnLocations = new HashMap<>();
    /** Кто зашёл ещё незарегистрированным (чтобы отличать /register от /login). */
    private final Set<UUID> unregisteredAtJoin = new HashSet<>();
    private boolean warnedNoAuthMe;
    private boolean warnedNoWorld;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getServer().getPluginManager().registerEvents(this, this);
        if (getCommand("authspawn") != null) {
            getCommand("authspawn").setExecutor(this);
            getCommand("authspawn").setTabCompleter(this);
        }
        if (!authMePresent()) {
            getLogger().warning("AuthMe не найден! VoidAuthSpawn включён, но телепортировать "
                    + "незарегистрированных сможет только когда AuthMe будет установлен и включён.");
        } else {
            getLogger().info("AuthMe найден: незарегистрированные игроки будут телепортироваться в точку из config.yml.");
            // LoginEvent существует только при установленном AuthMe — слушатель отдельным классом.
            getServer().getPluginManager().registerEvents(new AuthMeReturnListener(this), this);
        }
        loadReturnLocations();
    }

    // ------------------------------------------------------------- события

    /** Запоминаем, где игрок появился при входе, — это и есть «прежнее место» для возврата после /login. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoinRecord(PlayerJoinEvent event) {
        if (getConfig().getBoolean("enabled", true)
                && getConfig().getBoolean("return-after-login.enabled", true)) {
            recordReturnLocation(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        if (!getConfig().getBoolean("enabled", true)) {
            return;
        }
        Player player = event.getPlayer();
        long delay = Math.max(1L, getConfig().getLong("teleport.check-delay-ticks", 20L));
        int times = Math.max(1, getConfig().getInt("teleport.check-times", 3));
        long interval = Math.max(1L, getConfig().getLong("teleport.check-interval-ticks", 40L));
        for (int i = 0; i < times; i++) {
            final int attempt = i;
            Bukkit.getScheduler().runTaskLater(this, () -> checkPlayer(player, attempt), delay + interval * i);
        }
        // Сразу при входе вернуть зарегистрированного игрока на прежнее место,
        // не дожидаясь /login. Несколько попыток: AuthMe может телепортировать
        // игрока к своему спавну чуть позже нас — вернём обратно.
        if (getConfig().getBoolean("teleport-on-join.enabled", true)) {
            long jDelay = Math.max(1L, getConfig().getLong("teleport-on-join.delay-ticks", 1L));
            int jTimes = Math.max(1, getConfig().getInt("teleport-on-join.repeat-times", 3));
            long jInterval = Math.max(1L, getConfig().getLong("teleport-on-join.repeat-interval-ticks", 10L));
            for (int i = 0; i < jTimes; i++) {
                Bukkit.getScheduler().runTaskLater(this, () -> tryJoinRestore(player), jDelay + jInterval * i);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (!getConfig().getBoolean("hold.enabled", false)) {
            return;
        }
        if (event.getTo() == null || !awaitingAuth.contains(event.getPlayer().getUniqueId())) {
            return;
        }
        if (event.getFrom().getBlockX() == event.getTo().getBlockX()
                && event.getFrom().getBlockY() == event.getTo().getBlockY()
                && event.getFrom().getBlockZ() == event.getTo().getBlockZ()) {
            return; // повернулся/подпрыгнул на месте — не трогаем
        }
        Location point = spawnLocation();
        if (point == null || point.getWorld() == null || event.getTo().getWorld() == null
                || !point.getWorld().equals(event.getTo().getWorld())) {
            return;
        }
        double radius = Math.max(0.5, getConfig().getDouble("hold.radius", 3.0));
        if (event.getTo().distanceSquared(point) > radius * radius) {
            event.getPlayer().teleport(point);
            send(event.getPlayer(), msg("hold-back"));
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        awaitingAuth.remove(player.getUniqueId());
        messaged.remove(player.getUniqueId());
        // Запоминаем, где авторизованный игрок вышел из игры: при следующем
        // входе это и будет его «прежнее место». Если игрок вышел, так и не
        // войдя (стоял у точки авторизации), старое место не затираем.
        if (getConfig().getBoolean("enabled", true)
                && getConfig().getBoolean("return-after-login.enabled", true)
                && wasAuthenticated(player)) {
            Location quit = player.getLocation().clone();
            Location point = spawnLocation();
            if (quit.getWorld() != null && (point == null || !isNear(quit, point, 2.0))) {
                returnLocations.put(player.getUniqueId(), quit);
                saveReturnLocations();
            }
        }
    }

    /** Был ли игрок авторизован в AuthMe (если AuthMe нет/не готов — считаем, что да). */
    private boolean wasAuthenticated(Player player) {
        if (!authMePresent()) {
            return true;
        }
        try {
            AuthMeApi api = AuthMeApi.getInstance();
            return api == null || api.isAuthenticated(player);
        } catch (NoClassDefFoundError | ExceptionInInitializerError | RuntimeException e) {
            return true;
        }
    }

    // ------------------------------------------------------------- логика

    private void checkPlayer(Player player, int attempt) {
        if (!player.isOnline() || !getConfig().getBoolean("enabled", true)) {
            return;
        }
        if (player.hasPermission("voidauthspawn.bypass")) {
            awaitingAuth.remove(player.getUniqueId());
            return;
        }
        Boolean needs = needsTeleport(player);
        if (needs == null) {
            return; // AuthMe ещё не готов — подождём следующую проверку
        }
        if (!needs) {
            awaitingAuth.remove(player.getUniqueId());
            return;
        }
        Location point = spawnLocation();
        if (point == null) {
            return;
        }
        // Не дёргаем игрока, если он уже стоит у точки.
        if (point.getWorld() != null && point.getWorld().equals(player.getWorld())
                && player.getLocation().distanceSquared(point) < 1.0) {
            awaitingAuth.add(player.getUniqueId());
            return;
        }
        player.teleport(point);
        awaitingAuth.add(player.getUniqueId());
        if (getConfig().getBoolean("teleport.play-sound", true)) {
            String sound = getConfig().getString("teleport.sound", "minecraft:entity.enderman.teleport");
            if (sound != null && !sound.isBlank()) {
                player.playSound(point, sound, 0.7f, 1.0f);
            }
        }
        if (messaged.add(player.getUniqueId())) {
            send(player, msg("teleported"));
        }
    }

    /**
     * @return true — игрока надо телепортировать; false — не надо;
     *         null — AuthMe пока не готов, решение принять нельзя.
     */
    private Boolean needsTeleport(Player player) {
        if (!authMePresent()) {
            if (!warnedNoAuthMe) {
                warnedNoAuthMe = true;
                getLogger().warning("AuthMe не включён — проверка регистрации пропущена.");
            }
            return false;
        }
        AuthMeApi api;
        try {
            api = AuthMeApi.getInstance();
        } catch (NoClassDefFoundError | ExceptionInInitializerError e) {
            return false;
        }
        if (api == null) {
            return null;
        }
        boolean registered;
        try {
            registered = api.isRegistered(player.getName());
        } catch (RuntimeException e) {
            return null;
        }
        if (!registered) {
            unregisteredAtJoin.add(player.getUniqueId());
            return getConfig().getBoolean("teleport.unregistered", true);
        }
        if (getConfig().getBoolean("teleport.not-logged-in", false)) {
            if (getConfig().getBoolean("teleport-on-join.enabled", true)) {
                // Игрока сразу при входе вернули на прежнее место — не надо
                // следом уводить его к точке авторизации, телепорты будут драться.
                return false;
            }
            try {
                return !api.isAuthenticated(player);
            } catch (RuntimeException e) {
                return null;
            }
        }
        return false;
    }

    // ------------------------------------------------- возврат после /login

    private void recordReturnLocation(Player player) {
        Location join = player.getLocation().clone();
        if (join.getWorld() == null) {
            return;
        }
        UUID id = player.getUniqueId();
        Location old = returnLocations.get(id);
        Location point = spawnLocation();
        if (point != null && isNear(join, point, 2.0)) {
            // Появился прямо у точки авторизации (например, AuthMe сам ставит
            // сюда спавн невошедших). Настоящее прежнее место не затираем
            // точкой; если его ещё нет — потом подберём место выхода из AuthMe.
            if (old == null || isNear(old, point, 2.0)) {
                return;
            }
            return;
        }
        returnLocations.put(id, join);
        saveReturnLocations();
    }

    /** Вызывается из AuthMeReturnListener по событию успешного входа AuthMe (LoginEvent). */
    public void handleSuccessfulAuth(Player player) {
        UUID id = player.getUniqueId();
        boolean wasUnregistered = unregisteredAtJoin.remove(id);
        boolean wasAwaiting = awaitingAuth.remove(id);
        messaged.remove(id);
        if (!getConfig().getBoolean("enabled", true)
                || !getConfig().getBoolean("return-after-login.enabled", true)) {
            return;
        }
        if (wasUnregistered && !getConfig().getBoolean("return-after-login.on-register", false)) {
            return; // это была регистрация нового игрока, а не /login
        }
        if (player.hasPermission("voidauthspawn.bypass")) {
            return;
        }
        Location back = resolvePrevious(player);
        if (back == null || back.getWorld() == null) {
            return;
        }
        final Location target = back.clone();
        long delay = Math.max(1L, getConfig().getLong("return-after-login.delay-ticks", 10L));
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (player.isOnline()) {
                doReturn(player, target, wasAwaiting);
            }
        }, delay);
    }

    private void doReturn(Player player, Location target, boolean wasAwaiting) {
        Location point = spawnLocation();
        // Прежнее место совпадает с самой точкой авторизации — возвращать некуда.
        if (point != null && isNear(target, point, 2.0)) {
            returnLocations.remove(player.getUniqueId());
            saveReturnLocations();
            return;
        }
        // По желанию: возвращать только тех, кто сейчас стоит у точки авторизации
        // (или кого мы сами туда телепортировали).
        if (getConfig().getBoolean("return-after-login.only-if-at-auth-point", false)
                && !wasAwaiting
                && (point == null || !isNear(player.getLocation(), point,
                        getConfig().getDouble("return-after-login.auth-point-radius", 5.0)))) {
            return;
        }
        if (isNear(player.getLocation(), target, 0.5)) {
            returnLocations.remove(player.getUniqueId());
            saveReturnLocations();
            return; // игрок и так уже на прежнем месте
        }
        player.teleport(target);
        returnLocations.remove(player.getUniqueId());
        saveReturnLocations();
        if (getConfig().getBoolean("return-after-login.play-sound", true)) {
            String sound = getConfig().getString("teleport.sound", "minecraft:entity.enderman.teleport");
            if (sound != null && !sound.isBlank()) {
                player.playSound(target, sound, 0.7f, 1.0f);
            }
        }
        send(player, msg("returned"));
    }

    private boolean isNear(Location a, Location b, double radius) {
        return a.getWorld() != null && a.getWorld().equals(b.getWorld())
                && a.distanceSquared(b) <= radius * radius;
    }

    /**
     * Прежнее место игрока по приоритету: наше хранилище (место выхода/появления),
     * затем сохранённое AuthMe место выхода. Места у самой точки авторизации
     * пропускаются — возвращать туда бессмысленно.
     */
    private Location resolvePrevious(Player player) {
        Location point = spawnLocation();
        Location stored = returnLocations.get(player.getUniqueId());
        if (stored != null && stored.getWorld() != null && (point == null || !isNear(stored, point, 2.0))) {
            return stored;
        }
        Location fromAuthMe = authMeLastLocation(player);
        if (fromAuthMe != null && fromAuthMe.getWorld() != null && (point == null || !isNear(fromAuthMe, point, 2.0))) {
            returnLocations.put(player.getUniqueId(), fromAuthMe.clone());
            saveReturnLocations();
            return fromAuthMe;
        }
        return stored; // может совпадать с точкой — вызывающий код это проверит
    }

    /**
     * Сразу при входе: зарегистрированного, но ещё не вошедшего игрока
     * телепортируем на прежнее место, не дожидаясь, пока он напишет /login.
     */
    private void tryJoinRestore(Player player) {
        if (!player.isOnline() || !getConfig().getBoolean("enabled", true)
                || !getConfig().getBoolean("teleport-on-join.enabled", true)) {
            return;
        }
        if (player.hasPermission("voidauthspawn.bypass") || !authMePresent()) {
            return;
        }
        AuthMeApi api;
        try {
            api = AuthMeApi.getInstance();
        } catch (NoClassDefFoundError | ExceptionInInitializerError e) {
            return;
        }
        if (api == null) {
            return; // AuthMe ещё не готов — будет следующая попытка
        }
        boolean registered;
        boolean authenticated;
        try {
            registered = api.isRegistered(player.getName());
            authenticated = registered && api.isAuthenticated(player);
        } catch (RuntimeException e) {
            return;
        }
        if (!registered) {
            unregisteredAtJoin.add(player.getUniqueId());
            return; // незарегистрированных ведёт отдельный поток к точке регистрации
        }
        if (authenticated) {
            return; // уже вошёл (например, по сессии) — возврат после /login не нужен
        }
        Location target = resolvePrevious(player);
        if (target == null || target.getWorld() == null) {
            return;
        }
        Location point = spawnLocation();
        if (point != null && isNear(target, point, 2.0)) {
            return; // прежнее место — сама точка авторизации
        }
        if (isNear(player.getLocation(), target, 1.0)) {
            return; // игрок уже появился на прежнем месте
        }
        player.teleport(target.clone());
        if (getConfig().getBoolean("teleport-on-join.play-sound", true)) {
            String sound = getConfig().getString("teleport.sound", "minecraft:entity.enderman.teleport");
            if (sound != null && !sound.isBlank()) {
                player.playSound(target, sound, 0.7f, 1.0f);
            }
        }
        if (messaged.add(player.getUniqueId())) {
            send(player, msg("returned-on-join"));
        }
    }

    private Location authMeLastLocation(Player player) {
        try {
            AuthMeApi api = AuthMeApi.getInstance();
            return api == null ? null : api.getLastLocation(player);
        } catch (NoClassDefFoundError | ExceptionInInitializerError | RuntimeException e) {
            return null;
        }
    }

    // ------------------------------------------ хранение прежних мест (файл)

    private File returnFile() {
        return new File(getDataFolder(), "return-locations.yml");
    }

    private void loadReturnLocations() {
        if (!getConfig().getBoolean("return-after-login.save-to-file", true)) {
            return;
        }
        File file = returnFile();
        if (!file.exists()) {
            return;
        }
        YamlConfiguration data = YamlConfiguration.loadConfiguration(file);
        if (data.isConfigurationSection("locations")) {
            for (String key : data.getConfigurationSection("locations").getKeys(false)) {
                try {
                    UUID id = UUID.fromString(key);
                    String base = "locations." + key + ".";
                    World world = Bukkit.getWorld(data.getString(base + "world", ""));
                    if (world == null) {
                        continue;
                    }
                    returnLocations.put(id, new Location(world,
                            data.getDouble(base + "x"), data.getDouble(base + "y"), data.getDouble(base + "z"),
                            (float) data.getDouble(base + "yaw"), (float) data.getDouble(base + "pitch")));
                } catch (IllegalArgumentException ignored) {
                    // битая запись — пропускаем
                }
            }
        }
        getLogger().info("Загружено прежних мест игроков: " + returnLocations.size());
    }

    private void saveReturnLocations() {
        if (!getConfig().getBoolean("return-after-login.save-to-file", true)) {
            return;
        }
        YamlConfiguration data = new YamlConfiguration();
        for (Map.Entry<UUID, Location> e : returnLocations.entrySet()) {
            Location loc = e.getValue();
            if (loc.getWorld() == null) {
                continue;
            }
            String base = "locations." + e.getKey() + ".";
            data.set(base + "world", loc.getWorld().getName());
            data.set(base + "x", round(loc.getX()));
            data.set(base + "y", round(loc.getY()));
            data.set(base + "z", round(loc.getZ()));
            data.set(base + "yaw", round(loc.getYaw()));
            data.set(base + "pitch", round(loc.getPitch()));
        }
        try {
            data.save(returnFile());
        } catch (IOException e) {
            getLogger().warning("Не удалось сохранить return-locations.yml: " + e.getMessage());
        }
    }

    private boolean authMePresent() {
        return getServer().getPluginManager().isPluginEnabled("AuthMe");
    }

    /** Точка из config.yml; null — если мир не найден и запасной вариант выключен. */
    private Location spawnLocation() {
        String worldName = getConfig().getString("spawn.world", "world");
        World world = worldName == null ? null : Bukkit.getWorld(worldName);
        if (world == null) {
            if (!warnedNoWorld) {
                warnedNoWorld = true;
                getLogger().warning("Мир '" + worldName + "' из config.yml не найден!");
            }
            if (getConfig().getBoolean("spawn.fallback-to-world-spawn", true) && !Bukkit.getWorlds().isEmpty()) {
                return Bukkit.getWorlds().get(0).getSpawnLocation();
            }
            return null;
        }
        return new Location(world,
                getConfig().getDouble("spawn.x", 0.5),
                getConfig().getDouble("spawn.y", 64.0),
                getConfig().getDouble("spawn.z", 0.5),
                (float) getConfig().getDouble("spawn.yaw", 0.0),
                (float) getConfig().getDouble("spawn.pitch", 0.0));
    }

    // ------------------------------------------------------------- команда

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "set" -> {
                if (!sender.hasPermission("voidauthspawn.admin")) {
                    send(sender, msg("no-permission"));
                    return true;
                }
                if (!(sender instanceof Player player)) {
                    send(sender, msg("players-only"));
                    return true;
                }
                Location loc = player.getLocation();
                getConfig().set("spawn.world", loc.getWorld() == null ? "world" : loc.getWorld().getName());
                getConfig().set("spawn.x", round(loc.getX()));
                getConfig().set("spawn.y", round(loc.getY()));
                getConfig().set("spawn.z", round(loc.getZ()));
                getConfig().set("spawn.yaw", round(loc.getYaw()));
                getConfig().set("spawn.pitch", round(loc.getPitch()));
                saveConfig();
                send(sender, msg("point-set")
                        .replace("{world}", loc.getWorld() == null ? "?" : loc.getWorld().getName())
                        .replace("{x}", fmt(loc.getX()))
                        .replace("{y}", fmt(loc.getY()))
                        .replace("{z}", fmt(loc.getZ())));
                return true;
            }
            case "tp" -> {
                if (!sender.hasPermission("voidauthspawn.admin")) {
                    send(sender, msg("no-permission"));
                    return true;
                }
                if (!(sender instanceof Player player)) {
                    send(sender, msg("players-only"));
                    return true;
                }
                Location point = spawnLocation();
                if (point == null) {
                    send(sender, msg("point-missing"));
                    return true;
                }
                player.teleport(point);
                send(sender, msg("teleported-self"));
                return true;
            }
            case "reload" -> {
                if (!sender.hasPermission("voidauthspawn.admin")) {
                    send(sender, msg("no-permission"));
                    return true;
                }
                reloadConfig();
                warnedNoWorld = false;
                send(sender, msg("reloaded"));
                return true;
            }
            default -> {
                for (String line : getConfig().getStringList("messages.help")) {
                    send(sender, line);
                }
                return true;
            }
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1 && sender.hasPermission("voidauthspawn.admin")) {
            List<String> out = new ArrayList<>();
            for (String s : List.of("set", "tp", "reload")) {
                if (s.startsWith(args[0].toLowerCase(Locale.ROOT))) {
                    out.add(s);
                }
            }
            return out;
        }
        return List.of();
    }

    // ------------------------------------------------------------- тексты

    private String msg(String key) {
        return getConfig().getString("messages." + key, "");
    }

    private void send(CommandSender sender, String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        String prefix = getConfig().getString("messages.prefix", "");
        sender.sendMessage(comp((prefix == null ? "" : prefix) + text));
    }

    private Component comp(String legacy) {
        return LegacyComponentSerializer.legacySection().deserialize(TextUtil.color(legacy))
                .decoration(TextDecoration.ITALIC, false);
    }

    private double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private String fmt(double v) {
        return String.format(Locale.ROOT, "%.1f", v);
    }
}
