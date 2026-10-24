package ru.voidpe.voidtab;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.entity.Player;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Все внешние плагины подключаются МЯГКО, через reflection:
 * Vault (монеты + запасной источник группы), PlayerPoints (осколки),
 * LuckPerms (привилегия), Floodgate (Bedrock ли игрок), PlaceholderAPI.
 * Ни одной жёсткой зависимости в pom.xml — плагин стартует и без них,
 * просто соответствующие плейсхолдеры покажут значения по умолчанию из конфига.
 */
public final class Hooks {

    private final Logger log;

    private Object economy;          // Vault Economy provider
    private Method ecoGetBalance;
    private Method ecoFormat;

    private Object vaultPermission;  // Vault Permission provider
    private Method permGetPrimaryGroup;

    private Object luckPerms;        // LuckPerms api
    private Method lpGetUserManager;
    private Method lpGetGroupManager;

    private Object playerPointsApi;  // PlayerPointsAPI
    private Method ppLook;

    private Object floodgateApi;     // FloodgateApi
    private Method fgIsFloodgatePlayer;
    private Method fgGetPlayer;

    private Object geyserApi;        // GeyserApi (запасной источник устройства)
    private Method geyserConnectionByUuid;

    private Method papiSetPlaceholders; // PlaceholderAPI.setPlaceholders (static)
    private boolean papiTried;

    /** Кэш данных об устройстве: рефлексия недешёвая, а ник и скорборд обновляются каждый тик. */
    private final Map<UUID, Device> deviceCache = new ConcurrentHashMap<>();
    private final Map<UUID, Long> deviceCacheAt = new ConcurrentHashMap<>();
    private static final long DEVICE_CACHE_MS = 15_000L;

    public Hooks(Logger log) {
        this.log = log;
        initVault();
        initLuckPerms();
        initPlayerPoints();
        initFloodgate();
        initGeyser();
    }

    // ---------------------------------------------------------------- Vault
    private void initVault() {
        for (Class<?> svc : Bukkit.getServicesManager().getKnownServices()) {
            try {
                if (svc.getName().equals("net.milkbowl.vault.economy.Economy")) {
                    RegisteredServiceProvider<?> reg = Bukkit.getServicesManager().getRegistration(svc);
                    if (reg != null) {
                        economy = reg.getProvider();
                        ecoGetBalance = findMethod(economy.getClass(), "getBalance", OfflinePlayer.class);
                        ecoFormat = findMethod(economy.getClass(), "format", double.class);
                        log.info("Vault: экономика подключена (" + economy.getClass().getSimpleName() + ")");
                    }
                }
                if (svc.getName().equals("net.milkbowl.vault.permission.Permission")) {
                    RegisteredServiceProvider<?> reg = Bukkit.getServicesManager().getRegistration(svc);
                    if (reg != null) {
                        vaultPermission = reg.getProvider();
                        permGetPrimaryGroup = findMethod(vaultPermission.getClass(), "getPrimaryGroup", OfflinePlayer.class);
                        if (permGetPrimaryGroup == null) {
                            permGetPrimaryGroup = findMethod(vaultPermission.getClass(), "getPrimaryGroup", String.class, OfflinePlayer.class);
                        }
                        log.info("Vault: права подключены");
                    }
                }
            } catch (Throwable t) {
                log.warning("Vault hook: " + t.getMessage());
            }
        }
    }

    public boolean hasEconomy() {
        return economy != null && ecoGetBalance != null;
    }

    public double balance(OfflinePlayer player) {
        if (!hasEconomy()) {
            return -1;
        }
        try {
            Object v = ecoGetBalance.invoke(economy, player);
            return v instanceof Number ? ((Number) v).doubleValue() : -1;
        } catch (Throwable t) {
            return -1;
        }
    }

    public String economyFormat(double amount) {
        if (economy == null || ecoFormat == null) {
            return null;
        }
        try {
            Object v = ecoFormat.invoke(economy, amount);
            return v == null ? null : String.valueOf(v);
        } catch (Throwable t) {
            return null;
        }
    }

    // ------------------------------------------------------------ LuckPerms
    private void initLuckPerms() {
        for (Class<?> svc : Bukkit.getServicesManager().getKnownServices()) {
            try {
                if (svc.getName().equals("net.luckperms.api.LuckPerms")) {
                    RegisteredServiceProvider<?> reg = Bukkit.getServicesManager().getRegistration(svc);
                    if (reg != null) {
                        luckPerms = reg.getProvider();
                        lpGetUserManager = findMethod(luckPerms.getClass(), "getUserManager");
                        lpGetGroupManager = findMethod(luckPerms.getClass(), "getGroupManager");
                        log.info("LuckPerms подключён");
                    }
                }
            } catch (Throwable t) {
                log.warning("LuckPerms hook: " + t.getMessage());
            }
        }
    }

    public boolean hasLuckPerms() {
        return luckPerms != null;
    }

    /** @return массив [rawGroup, displayNameOrNull] или null. */
    public String[] luckPermsGroup(UUID uuid) {
        if (luckPerms == null || lpGetUserManager == null) {
            return null;
        }
        try {
            Object userManager = lpGetUserManager.invoke(luckPerms);
            if (userManager == null) {
                return null;
            }
            Method getUser = findMethod(userManager.getClass(), "getUser", UUID.class);
            Object user = getUser == null ? null : getUser.invoke(userManager, uuid);
            if (user == null) {
                return null; // пользователь ещё не загружен LuckPerms
            }
            Method getPrimaryGroup = findMethod(user.getClass(), "getPrimaryGroup");
            String raw = String.valueOf(getPrimaryGroup.invoke(user));
            String display = null;
            if (lpGetGroupManager != null) {
                Object gm = lpGetGroupManager.invoke(luckPerms);
                Method getGroup = gm == null ? null : findMethod(gm.getClass(), "getGroup", String.class);
                Object group = getGroup == null ? null : getGroup.invoke(gm, raw);
                if (group != null) {
                    Method getDisplayName = findMethod(group.getClass(), "getDisplayName");
                    Object dn = getDisplayName == null ? null : getDisplayName.invoke(group);
                    display = dn == null ? null : String.valueOf(dn);
                }
            }
            return new String[]{raw, display};
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Действующий префикс LuckPerms (CachedMetaData: свой + наследованные, с приоритетами).
     * Сначала префикс самого игрока, если нет — префикс его основной группы. null — префикса нет.
     */
    public String luckPermsPrefix(UUID uuid) {
        if (luckPerms == null || lpGetUserManager == null) {
            return null;
        }
        try {
            Object userManager = lpGetUserManager.invoke(luckPerms);
            if (userManager == null) {
                return null;
            }
            Method getUser = findMethod(userManager.getClass(), "getUser", UUID.class);
            Object user = getUser == null ? null : getUser.invoke(userManager, uuid);
            if (user == null) {
                return null;
            }
            String prefix = metaPrefix(user);
            if (prefix != null && !prefix.isBlank()) {
                return prefix;
            }
            Method getPrimaryGroup = findMethod(user.getClass(), "getPrimaryGroup");
            String raw = getPrimaryGroup == null ? null : String.valueOf(getPrimaryGroup.invoke(user));
            if (raw != null && lpGetGroupManager != null) {
                Object gm = lpGetGroupManager.invoke(luckPerms);
                Method getGroup = gm == null ? null : findMethod(gm.getClass(), "getGroup", String.class);
                Object group = getGroup == null ? null : getGroup.invoke(gm, raw);
                if (group != null) {
                    prefix = metaPrefix(group);
                    if (prefix != null && !prefix.isBlank()) {
                        return prefix;
                    }
                }
            }
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Сбросить кэш метаданных LuckPerms у всех онлайн-игроков и всех групп.
     * После внешней правки префикса/суффикса (командой LP из консоли, кейсом,
     * донатом) скорборд, TAB и ChatEx должны увидеть новое значение без
     * перезахода игрока.
     */
    public void refreshLuckPermsMeta() {
        if (luckPerms == null || lpGetUserManager == null) {
            return;
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            refreshLuckPermsMeta(player.getUniqueId());
        }
        if (lpGetGroupManager == null) {
            return;
        }
        try {
            Object groupManager = lpGetGroupManager.invoke(luckPerms);
            if (groupManager == null) {
                return;
            }
            Method getAllGroups = findMethod(groupManager.getClass(), "getAllGroups");
            Object groups = getAllGroups == null ? null : getAllGroups.invoke(groupManager);
            if (groups instanceof Iterable) {
                for (Object group : (Iterable<?>) groups) {
                    invalidateCachedData(group);
                }
            }
        } catch (Throwable ignored) {
            // мягкий хук: если API изменился, обычное чтение префиксов всё равно работает
        }
    }

    /** Сбросить кэш метаданных LuckPerms одного игрока. */
    public void refreshLuckPermsMeta(UUID uuid) {
        if (luckPerms == null || lpGetUserManager == null || uuid == null) {
            return;
        }
        try {
            Object userManager = lpGetUserManager.invoke(luckPerms);
            if (userManager == null) {
                return;
            }
            Method getUser = findMethod(userManager.getClass(), "getUser", UUID.class);
            Object user = getUser == null ? null : getUser.invoke(userManager, uuid);
            invalidateCachedData(user);
        } catch (Throwable ignored) {
            // см. выше
        }
    }

    private void invalidateCachedData(Object holder) {
        if (holder == null) {
            return;
        }
        try {
            Method getCachedData = findMethod(holder.getClass(), "getCachedData");
            if (getCachedData == null) {
                return;
            }
            Object cached = getCachedData.invoke(holder);
            if (cached == null) {
                return;
            }
            for (String name : new String[]{"invalidate", "invalidateMeta"}) {
                Method method = findMethod(cached.getClass(), name);
                if (method != null && method.getParameterCount() == 0) {
                    method.invoke(cached);
                }
            }
        } catch (Throwable ignored) {
            // LuckPerms сам обновляет кэш при своих командах; это только страховка.
        }
    }

    /**
     * Действующий суффикс LuckPerms игрока — это ТИТУЛ из кейса титулов
     * (meta setsuffix). null — титула нет.
     */
    public String luckPermsSuffix(UUID uuid) {
        if (luckPerms == null || lpGetUserManager == null) {
            return null;
        }
        try {
            Object userManager = lpGetUserManager.invoke(luckPerms);
            if (userManager == null) {
                return null;
            }
            Method getUser = findMethod(userManager.getClass(), "getUser", UUID.class);
            Object user = getUser == null ? null : getUser.invoke(userManager, uuid);
            if (user == null) {
                return null;
            }
            return metaValue(user, "getSuffix");
        } catch (Throwable t) {
            return null;
        }
    }

    /** getSuffix()/getPrefix() из CachedMetaData держателя. */
    private String metaValue(Object holder, String getter) {
        try {
            Method getCachedData = findMethod(holder.getClass(), "getCachedData");
            if (getCachedData == null) {
                return null;
            }
            Object cached = getCachedData.invoke(holder);
            if (cached == null) {
                return null;
            }
            Method getMeta = null;
            Object options = null;
            for (Method m : cached.getClass().getMethods()) {
                if (m.getName().equals("getMetaData") && m.getParameterCount() == 0) {
                    getMeta = m;
                    break;
                }
            }
            if (getMeta == null) {
                for (Method m : cached.getClass().getMethods()) {
                    if (m.getName().equals("getMetaData") && m.getParameterCount() == 1) {
                        for (Method sm : m.getParameterTypes()[0].getMethods()) {
                            if (sm.getName().equals("defaultContextualOptions") && sm.getParameterCount() == 0) {
                                sm.setAccessible(true);
                                options = sm.invoke(null);
                                getMeta = m;
                                break;
                            }
                        }
                        break;
                    }
                }
            }
            if (getMeta == null) {
                return null;
            }
            getMeta.setAccessible(true);
            Object meta = getMeta.getParameterCount() == 0 ? getMeta.invoke(cached) : getMeta.invoke(cached, options);
            if (meta == null) {
                return null;
            }
            Method get = findMethod(meta.getClass(), getter);
            Object value = get == null ? null : get.invoke(meta);
            return value == null ? null : String.valueOf(value);
        } catch (Throwable t) {
            return null;
        }
    }

    /** getPrefix() из CachedMetaData держателя (User или Group), QueryOptions — контекстные по умолчанию. */
    private String metaPrefix(Object holder) {
        return metaValue(holder, "getPrefix");
    }

    public String vaultGroup(OfflinePlayer player) {
        if (vaultPermission == null || permGetPrimaryGroup == null) {
            return null;
        }
        try {
            Object v = permGetPrimaryGroup.getParameterCount() == 1
                    ? permGetPrimaryGroup.invoke(vaultPermission, player)
                    : permGetPrimaryGroup.invoke(vaultPermission, player.getPlayer() == null ? null : player.getPlayer().getWorld().getName(), player);
            return v == null ? null : String.valueOf(v);
        } catch (Throwable t) {
            return null;
        }
    }

    // ---------------------------------------------------------- PlayerPoints
    private void initPlayerPoints() {
        Plugin pp = Bukkit.getPluginManager().getPlugin("PlayerPoints");
        if (pp == null) {
            return;
        }
        try {
            Method getApi = findMethod(pp.getClass(), "getAPI");
            if (getApi == null) {
                getApi = findMethod(pp.getClass(), "getApi");
            }
            playerPointsApi = getApi == null ? null : getApi.invoke(pp);
            if (playerPointsApi != null) {
                ppLook = findMethod(playerPointsApi.getClass(), "look", UUID.class);
                log.info("PlayerPoints подключён (осколки)");
            }
        } catch (Throwable t) {
            log.warning("PlayerPoints hook: " + t.getMessage());
        }
    }

    public boolean hasPlayerPoints() {
        return playerPointsApi != null && ppLook != null;
    }

    public int points(UUID uuid) {
        if (!hasPlayerPoints()) {
            return -1;
        }
        try {
            Object v = ppLook.invoke(playerPointsApi, uuid);
            return v instanceof Number ? ((Number) v).intValue() : -1;
        } catch (Throwable t) {
            return -1;
        }
    }

    // ------------------------------------------------------------- Floodgate
    private void initFloodgate() {
        Plugin fg = Bukkit.getPluginManager().getPlugin("floodgate");
        if (fg == null) {
            return;
        }
        try {
            Class<?> apiClass = Class.forName("org.geysermc.floodgate.api.FloodgateApi", true, fg.getClass().getClassLoader());
            Method getInstance = findMethod(apiClass, "getInstance");
            floodgateApi = getInstance == null ? null : getInstance.invoke(null);
            if (floodgateApi != null) {
                fgIsFloodgatePlayer = findMethod(floodgateApi.getClass(), "isFloodgatePlayer", UUID.class);
                fgGetPlayer = findMethod(floodgateApi.getClass(), "getPlayer", UUID.class);
                log.info("Floodgate подключён (Bedrock-игроки получат иконки пака)");
            }
        } catch (Throwable t) {
            log.warning("Floodgate hook: " + t.getMessage());
        }
    }

    public boolean hasFloodgate() {
        return floodgateApi != null && fgIsFloodgatePlayer != null;
    }

    public boolean isBedrock(UUID uuid) {
        if (!hasFloodgate()) {
            return false;
        }
        try {
            Object v = fgIsFloodgatePlayer.invoke(floodgateApi, uuid);
            return Boolean.TRUE.equals(v);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Устройство Bedrock-игрока из Floodgate: WINDOWS, GOOGLE (Android), IOS, XBOX, PS4, NX и т.д.
     * FloodgatePlayer.getDeviceOs() возвращает enum org.geysermc.geyser.configuration.DeviceOs.
     */
    public String deviceOs(UUID uuid) {
        String floodgate = floodgateDeviceOs(uuid);
        String geyser = geyserEnum(uuid, "deviceOs", "getDeviceOs");
        return pickOs(floodgate, geyser);
    }

    /**
     * Телефоны (и особенно сторонние клиенты) нередко присылают deviceOs=WINDOWS,
     * хотя Geyser/Floodgate из того же логина знают ANDROID/IOS. Если источники
     * расходятся так, что один говорит «Windows», а другой — мобильную ОС,
     * верим мобильной: на телефоне подделать UI/ввод сложнее, чем строку ОС.
     */
    static String pickOs(String floodgate, String geyser) {
        String fg = normalizeOs(floodgate);
        String gy = normalizeOs(geyser);
        boolean fgDesktop = isWindowsOs(fg);
        boolean gyMobile = isMobileOs(gy);
        if (fgDesktop && gyMobile) {
            return gy;
        }
        if (!fg.isEmpty() && !fg.equals("UNKNOWN")) {
            return fg;
        }
        if (!gy.isEmpty() && !gy.equals("UNKNOWN")) {
            return gy;
        }
        return "UNKNOWN";
    }

    private static String normalizeOs(String value) {
        return value == null ? "" : value.trim().toUpperCase(java.util.Locale.ROOT);
    }

    /** Windows-семейство, включая UWP/WIN32 и «Windows Phone» (его разбираем отдельно). */
    static boolean isWindowsOs(String os) {
        if (os == null || os.isEmpty() || os.contains("PHONE")) {
            return false;
        }
        return os.contains("WINDOWS") || os.contains("UWP") || os.contains("WIN32")
                || os.contains("WIN10");
    }

    static boolean isMobileOs(String os) {
        if (os == null || os.isEmpty() || os.equals("UNKNOWN")) {
            return false;
        }
        return os.contains("GOOGLE") || os.contains("ANDROID") || os.equals("IOS")
                || os.contains("FIREOS") || os.contains("AMAZON") || os.contains("PHONE")
                || os.contains("IPHONE") || os.contains("IPAD");
    }

    /** Сырое значение Floodgate (для /voidtab debug). */
    public String floodgateDeviceOs(UUID uuid) {
        return floodgateEnum(uuid, "getDeviceOs");
    }

    /** Сырое значение Geyser (для /voidtab debug). */
    public String geyserDeviceOs(UUID uuid) {
        return geyserEnum(uuid, "deviceOs", "getDeviceOs");
    }

    public String geyserInputMode(UUID uuid) {
        return geyserEnum(uuid, "inputMode", "getInputMode");
    }

    /** Подключение Geyser напрямую: GeyserApi.api().connectionByUuid(uuid), оттуда deviceOs/inputMode. */
    private void initGeyser() {
        Plugin geyser = Bukkit.getPluginManager().getPlugin("Geyser-Spigot");
        if (geyser == null) {
            geyser = Bukkit.getPluginManager().getPlugin("Geyser");
        }
        if (geyser == null) {
            return;
        }
        try {
            Class<?> apiClass = Class.forName("org.geysermc.geyser.api.GeyserApi", true, geyser.getClass().getClassLoader());
            Method api = findMethod(apiClass, "api");
            geyserApi = api == null ? null : api.invoke(null);
            if (geyserApi != null) {
                geyserConnectionByUuid = findMethod(geyserApi.getClass(), "connectionByUuid", UUID.class);
                if (geyserConnectionByUuid == null) {
                    geyserConnectionByUuid = findMethod(geyserApi.getClass(), "playerByUuid", UUID.class);
                }
                if (geyserConnectionByUuid != null) {
                    log.info("Geyser подключён (запасной источник устройства игрока)");
                }
            }
        } catch (Throwable t) {
            log.warning("Geyser hook: " + t.getMessage());
        }
    }

    private String geyserEnum(UUID uuid, String... getters) {
        if (geyserApi == null || geyserConnectionByUuid == null) {
            return null;
        }
        try {
            Object connection = geyserConnectionByUuid.invoke(geyserApi, uuid);
            if (connection == null) {
                return null;
            }
            for (String getter : getters) {
                Method m = findMethod(connection.getClass(), getter);
                if (m == null) {
                    continue;
                }
                Object v = m.invoke(connection);
                if (v == null) {
                    continue;
                }
                try {
                    Method nameMethod = v.getClass().getMethod("name");
                    return String.valueOf(nameMethod.invoke(v)).toUpperCase(java.util.Locale.ROOT);
                } catch (Throwable ignored) {
                    return v.toString().toUpperCase(java.util.Locale.ROOT);
                }
            }
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Режим ввода Bedrock-клиента: KEYBOARD_MOUSE, TOUCH, CONTROLLER, VR, UNKNOWN. */
    public String inputMode(UUID uuid) {
        String mode = floodgateEnum(uuid, "getInputMode");
        if (mode == null || mode.isBlank() || mode.equals("UNKNOWN")) {
            // В Geyser метод лежит в ClientData и называется inputMode(), без "get".
            mode = geyserClientDataString(uuid, "getInputMode", "inputMode");
        }
        if (mode == null || mode.isBlank() || mode.equals("UNKNOWN")) {
            mode = geyserEnum(uuid, "inputMode", "getInputMode");
        }
        return mode == null || mode.isBlank() ? "UNKNOWN" : mode;
    }

    /**
     * Модель устройства (DeviceModel из клиентских данных Bedrock).
     * ВАЖНО: Floodgate такого метода НЕ отдаёт (у FloodgatePlayer есть только
     * DeviceOs/UiProfile/InputMode/версия), поэтому модель читаем из сессии Geyser.
     * Если Geyser недоступен — пустая строка, все проверки по модели просто пропускаются.
     */
    public String deviceModel(UUID uuid) {
        // В Geyser 2.x это ClientData#deviceModel() (без "get"), в старых сборках — getDeviceModel().
        String fromGeyser = geyserClientDataString(uuid, "getDeviceModel", "deviceModel");
        if (fromGeyser != null && !fromGeyser.isBlank()) {
            return fromGeyser;
        }
        // На случай, если в будущих версиях Floodgate модель всё-таки появится.
        if (floodgateApi == null || fgGetPlayer == null) {
            return "";
        }
        try {
            Object floodgatePlayer = fgGetPlayer.invoke(floodgateApi, uuid);
            if (floodgatePlayer == null) {
                return "";
            }
            Method m = findMethod(floodgatePlayer.getClass(), "getDeviceModel");
            Object v = m == null ? null : m.invoke(floodgatePlayer);
            return v == null ? "" : String.valueOf(v);
        } catch (Throwable t) {
            return "";
        }
    }

    /** UI-профиль клиента: CLASSIC (ПК/консоль) или POCKET (мобильный интерфейс). */
    public String uiProfile(UUID uuid) {
        String ui = floodgateEnum(uuid, "getUiProfile");
        if (ui == null || ui.isBlank() || ui.equals("UNKNOWN")) {
            String fromClient = geyserClientDataString(uuid, "getUiProfile", "uiProfile");
            if (fromClient != null && !fromClient.isBlank()) {
                ui = fromClient.toUpperCase(java.util.Locale.ROOT);
            }
        }
        return ui == null || ui.isBlank() ? "UNKNOWN" : ui;
    }

    /** Любое строковое/перечислимое поле из клиентских данных сессии Geyser. */
    private String geyserClientDataString(UUID uuid, String... getters) {
        if (geyserApi == null || geyserConnectionByUuid == null) {
            return null;
        }
        try {
            Object connection = geyserConnectionByUuid.invoke(geyserApi, uuid);
            if (connection == null) {
                return null;
            }
            Method clientDataGetter = findMethod(connection.getClass(), "getClientData");
            Object clientData = clientDataGetter == null ? null : clientDataGetter.invoke(connection);
            if (clientData == null) {
                return null;
            }
            for (String getter : getters) {
                Method m = findMethod(clientData.getClass(), getter);
                Object v = m == null ? null : m.invoke(clientData);
                if (v != null && !String.valueOf(v).isBlank()) {
                    return String.valueOf(v);
                }
            }
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Снимок устройства игрока: ОС, режим ввода, UI-профиль и модель.
     * Кэшируется на 15 секунд — иначе каждый рендер ника/скорборда дёргает рефлексию.
     */
    public Device device(UUID uuid) {
        long now = System.currentTimeMillis();
        Device cached = deviceCache.get(uuid);
        Long at = deviceCacheAt.get(uuid);
        if (cached != null && at != null && now - at < DEVICE_CACHE_MS) {
            return cached;
        }
        Device fresh = new Device(deviceOs(uuid), inputMode(uuid), uiProfile(uuid), deviceModel(uuid));
        deviceCache.put(uuid, fresh);
        deviceCacheAt.put(uuid, now);
        return fresh;
    }

    /** Сбросить кэш устройства (выход игрока, /voidtab reload). */
    public void forgetDevice(UUID uuid) {
        deviceCache.remove(uuid);
        deviceCacheAt.remove(uuid);
    }

    /** Одно устройство игрока: то, что реально присылает Bedrock-клиент. */
    public static final class Device {
        public final String os;
        public final String input;
        public final String ui;
        public final String model;

        Device(String os, String input, String ui, String model) {
            this.os = os == null ? "UNKNOWN" : os;
            this.input = input == null ? "UNKNOWN" : input;
            this.ui = ui == null ? "UNKNOWN" : ui;
            this.model = model == null ? "" : model;
        }

        public boolean modelContains(String needle) {
            return !model.isEmpty() && model.toUpperCase(java.util.Locale.ROOT).contains(needle);
        }
    }

    /**
     * ВАЖНО: DeviceOs.toString() у Floodgate возвращает подпись ("Android", "Windows"),
     * а не имя enum — поэтому берём именно Enum.name() ("GOOGLE", "UWP", ...).
     */
    private String floodgateEnum(UUID uuid, String getter) {
        if (floodgateApi == null || fgGetPlayer == null) {
            return null;
        }
        try {
            Object floodgatePlayer = fgGetPlayer.invoke(floodgateApi, uuid);
            if (floodgatePlayer == null) {
                return null;
            }
            Method m = findMethod(floodgatePlayer.getClass(), getter);
            Object v = m == null ? null : m.invoke(floodgatePlayer);
            if (v == null) {
                return null;
            }
            try {
                Method nameMethod = v.getClass().getMethod("name");
                return String.valueOf(nameMethod.invoke(v)).toUpperCase(java.util.Locale.ROOT);
            } catch (Throwable ignored) {
                return v.toString().toUpperCase(java.util.Locale.ROOT);
            }
        } catch (Throwable t) {
            return null;
        }
    }

    // --------------------------------------------------------- PlaceholderAPI
    public String applyPlaceholders(OfflinePlayer player, String text) {
        if (!papiTried) {
            papiTried = true;
            Plugin papi = Bukkit.getPluginManager().getPlugin("PlaceholderAPI");
            if (papi != null) {
                try {
                    Class<?> clazz = Class.forName("me.clip.placeholderapi.PlaceholderAPI", true, papi.getClass().getClassLoader());
                    papiSetPlaceholders = findMethod(clazz, "setPlaceholders", OfflinePlayer.class, String.class);
                    if (papiSetPlaceholders != null) {
                        log.info("PlaceholderAPI подключён");
                    }
                } catch (Throwable t) {
                    log.warning("PlaceholderAPI hook: " + t.getMessage());
                }
            }
        }
        if (papiSetPlaceholders == null) {
            return text;
        }
        try {
            Object v = papiSetPlaceholders.invoke(null, player, text);
            return v == null ? text : String.valueOf(v);
        } catch (Throwable t) {
            return text;
        }
    }

    // ---------------------------------------------------------------- util
    static Method findMethod(Class<?> clazz, String name, Class<?>... params) {
        Class<?> c = clazz;
        while (c != null) {
            try {
                Method m = c.getMethod(name, params);
                m.setAccessible(true);
                return m;
            } catch (NoSuchMethodException ignored) {
                // ищем этим же именем c любым набором параметров ниже по иерархии
                for (Method m : c.getMethods()) {
                    if (m.getName().equals(name) && m.getParameterCount() == params.length) {
                        boolean ok = true;
                        for (int i = 0; i < params.length; i++) {
                            if (!m.getParameterTypes()[i].isAssignableFrom(params[i])
                                    && !params[i].isAssignableFrom(m.getParameterTypes()[i])) {
                                ok = false;
                                break;
                            }
                        }
                        if (ok) {
                            m.setAccessible(true);
                            return m;
                        }
                    }
                }
                c = c.getSuperclass();
            } catch (Throwable ignored) {
                return null;
            }
        }
        return null;
    }
}
