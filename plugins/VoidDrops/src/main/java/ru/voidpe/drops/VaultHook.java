package ru.voidpe.drops;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.lang.reflect.Method;
import java.util.logging.Logger;

/** Vault Economy через reflection (как в VoidTab) — без жёсткой зависимости. */
public final class VaultHook {

    private final Logger log;
    private Object economy;
    private Method getBalance;
    private Method withdraw;
    private Method deposit;

    public VaultHook(Logger log) {
        this.log = log;
        for (Class<?> svc : Bukkit.getServicesManager().getKnownServices()) {
            try {
                if (svc.getName().equals("net.milkbowl.vault.economy.Economy")) {
                    RegisteredServiceProvider<?> reg = Bukkit.getServicesManager().getRegistration(svc);
                    if (reg != null) {
                        economy = reg.getProvider();
                        getBalance = find(economy.getClass(), "getBalance", OfflinePlayer.class);
                        withdraw = find(economy.getClass(), "withdrawPlayer", OfflinePlayer.class, double.class);
                        deposit = find(economy.getClass(), "depositPlayer", OfflinePlayer.class, double.class);
                        log.info("Vault: экономика подключена к дропу монет");
                    }
                }
            } catch (Throwable t) {
                log.warning("Vault hook: " + t.getMessage());
            }
        }
    }

    private static Method find(Class<?> type, String name, Class<?>... params) {
        Class<?> current = type;
        while (current != null) {
            try {
                Method m = current.getMethod(name, params);
                m.setAccessible(true);
                return m;
            } catch (NoSuchMethodException e) {
                current = current.getSuperclass();
            }
        }
        // интерфейсы (Economy — интерфейс, getMethod класса провайдера может не найти)
        for (Class<?> iface : type.getInterfaces()) {
            try {
                Method m = iface.getMethod(name, params);
                m.setAccessible(true);
                return m;
            } catch (NoSuchMethodException ignored) {
            }
        }
        return null;
    }

    public boolean available() {
        return economy != null && getBalance != null && withdraw != null && deposit != null;
    }

    public double balance(OfflinePlayer player) {
        if (!available()) {
            return -1;
        }
        try {
            Object v = getBalance.invoke(economy, player);
            return v instanceof Number ? ((Number) v).doubleValue() : -1;
        } catch (Throwable t) {
            return -1;
        }
    }

    /** @return true, если операция успешна (EconomyResponse.transactionSuccess). */
    private boolean transact(Method method, OfflinePlayer player, double amount) {
        if (!available()) {
            return false;
        }
        try {
            Object response = method.invoke(economy, player, amount);
            if (response == null) {
                return false;
            }
            Method success = response.getClass().getMethod("transactionSuccess");
            Object v = success.invoke(response);
            return Boolean.TRUE.equals(v);
        } catch (Throwable t) {
            log.warning("Vault операция не удалась: " + t.getMessage());
            return false;
        }
    }

    public boolean withdraw(OfflinePlayer player, double amount) {
        return transact(withdraw, player, amount);
    }

    public boolean deposit(OfflinePlayer player, double amount) {
        return transact(deposit, player, amount);
    }
}
