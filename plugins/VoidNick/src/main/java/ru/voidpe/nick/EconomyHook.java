package ru.voidpe.nick;

import org.bukkit.Bukkit;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.lang.reflect.Method;
import java.util.logging.Logger;

/**
 * Баланс через Vault (рефлексией — Vault тоже мягкая зависимость).
 *
 * <p>Зачем вообще трогать деньги: «стал новым игроком» без обнуления баланса
 * не работает — старый прогресс остаётся на счету и сразу же возвращает статус.
 * Если Vault нет, плагин просто пишет в лог, что баланс не записан.</p>
 */
final class EconomyHook {

    private final Logger logger;
    private Object economy;
    private Method getBalance;
    private Method withdraw;
    private Method deposit;

    EconomyHook(Logger logger) {
        this.logger = logger;
        try {
            Class<?> economyClass = Class.forName("net.milkbowl.vault.economy.Economy");
            RegisteredServiceProvider<?> provider = Bukkit.getServicesManager().getRegistration(economyClass);
            if (provider == null) {
                return;
            }
            economy = provider.getProvider();
            getBalance = economyClass.getMethod("getBalance", String.class);
            withdraw = economyClass.getMethod("withdrawPlayer", String.class, double.class);
            deposit = economyClass.getMethod("depositPlayer", String.class, double.class);
        } catch (Throwable missing) {
            economy = null;
        }
    }

    boolean available() {
        return economy != null && getBalance != null;
    }

    /** Текущий баланс; Double.NaN, если экономики нет. */
    double balance(String player) {
        if (!available()) {
            return Double.NaN;
        }
        try {
            Object value = getBalance.invoke(economy, player);
            return value instanceof Number number ? number.doubleValue() : Double.NaN;
        } catch (Throwable failure) {
            logger.warning("Не удалось прочитать баланс " + player + ": " + failure);
            return Double.NaN;
        }
    }

    /** Снять всё. Возвращает true, если экономика ответила успехом. */
    boolean withdrawAll(String player, double amount) {
        if (!available() || amount <= 0) {
            return false;
        }
        try {
            Object response = withdraw.invoke(economy, player, amount);
            return success(response);
        } catch (Throwable failure) {
            logger.warning("Не удалось снять баланс " + player + ": " + failure);
            return false;
        }
    }

    boolean deposit(String player, double amount) {
        if (!available() || amount <= 0) {
            return false;
        }
        try {
            Object response = deposit.invoke(economy, player, amount);
            return success(response);
        } catch (Throwable failure) {
            logger.warning("Не удалось вернуть баланс " + player + ": " + failure);
            return false;
        }
    }

    private boolean success(Object response) {
        if (response == null) {
            return false;
        }
        try {
            Object value = response.getClass().getMethod("transactionSuccess").invoke(response);
            return value instanceof Boolean ok && ok;
        } catch (Throwable ignored) {
            return false;
        }
    }
}
