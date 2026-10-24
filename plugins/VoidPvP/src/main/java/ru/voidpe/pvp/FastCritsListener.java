package ru.voidpe.pvp;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import io.papermc.paper.event.entity.EntityKnockbackEvent;
import org.bukkit.util.Vector;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;

/**
 * Обработка ударов: крит в прыжке (в том числе в спринте) и снятие
 * неуязвимости между ударами — «тап-тап» проходит полностью.
 *
 * <p>Работает только на прямые удары игрока (ENTITY_ATTACK): стрелы, взрывы,
 * огонь и размашистый удар (sweep) остаются ванильными.</p>
 */
public final class FastCritsListener implements Listener {

    private final PvPConfig plugin;
    /** UUID жертвы -> тик последнего удара, который снял неуязвимость. */
    private final java.util.Map<java.util.UUID, Integer> lastHitTick = new java.util.HashMap<>();
    /** UUID жертвы -> тик предыдущего засчитанного удара (для «тап-тап»-ослабления). */
    private final java.util.Map<java.util.UUID, Integer> lastDamageTick = new java.util.HashMap<>();
    /** UUID жертвы -> тик повторного удара (нужен обработчику отдачи). */
    private final java.util.Map<java.util.UUID, Integer> repeatHitTick = new java.util.HashMap<>();

    public FastCritsListener(PvPConfig plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        VoidPvP.Settings settings = plugin.settings();
        if (settings == null || !settings.enabled) {
            return;
        }
        // Только прямые удары игрока: стрелы и взрывы не трогаем.
        if (!(event.getDamager() instanceof Player attacker)) {
            return;
        }
        if (event.getCause() != EntityDamageEvent.DamageCause.ENTITY_ATTACK) {
            return;
        }
        if (attacker.getGameMode() == GameMode.SPECTATOR || attacker.getGameMode() == GameMode.CREATIVE) {
            return;
        }
        Entity victim = event.getEntity();
        if (victim == attacker) {
            return;
        }
        if (settings.isWorldDisabled(attacker.getWorld().getName())) {
            return;
        }
        if (!plugin.allowed(attacker)) {
            return;
        }
        int now = Bukkit.getCurrentTick();
        Integer previousHit = lastDamageTick.put(victim.getUniqueId(), now);
        boolean repeat = previousHit != null && now - previousHit <= settings.repeatWindowTicks;
        if (repeat) {
            repeatHitTick.put(victim.getUniqueId(), now);
        } else {
            repeatHitTick.remove(victim.getUniqueId());
        }
        prune(now);

        if (settings.critsEnabled) {
            applyCrit(event, attacker, victim, settings);
        }
        // Серия быстрых ударов («тап-тап») бьёт слабее: иначе урон суммируется
        // и бой заканчивается за долю секунды.
        if (repeat && settings.repeatDamageScale < 1.0) {
            event.setDamage(event.getDamage() * settings.repeatDamageScale);
        }
        if (settings.noInvulnerability) {
            clearInvulnerability(victim, settings);
        }
    }

    /**
     * Отдача (knockback) от ударов игрока по игроку. В ванили каждый удар
     * отбрасывает на ~0.4 блока, а «тап-тап» бьёт несколько раз в секунду —
     * суммарно игрока уносит через полкарты. Ослабляем: обычный удар ×0.5,
     * повторный — ×0.3 от этого (по умолчанию).
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onKnockback(EntityKnockbackEvent event) {
        VoidPvP.Settings settings = plugin.settings();
        if (settings == null || !settings.enabled) {
            return;
        }
        if (event.getCause() != EntityKnockbackEvent.Cause.ENTITY_ATTACK) {
            return;
        }
        if (!(event.getEntity() instanceof Player victim)) {
            return;
        }
        // Отдача от мобов и взрывов остаётся ванильной: трогаем только удары игроков.
        if (event instanceof com.destroystokyo.paper.event.entity.EntityKnockbackByEntityEvent byEntity
                && !(byEntity.getHitBy() instanceof Player)) {
            return;
        }
        if (settings.isWorldDisabled(victim.getWorld().getName())) {
            return;
        }
        double scale = settings.knockbackScale;
        Integer repeatTick = repeatHitTick.get(victim.getUniqueId());
        if (repeatTick != null && Bukkit.getCurrentTick() - repeatTick <= 1) {
            scale *= settings.repeatKnockbackScale;
        }
        if (scale >= 1.0) {
            return;
        }
        Vector knockback = event.getKnockback();
        if (knockback == null) {
            return;
        }
        event.setKnockback(knockback.multiply(scale));
    }

    /**
     * Крит: умножает уже посчитанный урон на множитель из конфига.
     * Если ванильный крит и так был (падение, не спринт) — не удваиваем.
     */
    private void applyCrit(EntityDamageByEntityEvent event, Player attacker, Entity victim,
                           VoidPvP.Settings settings) {
        if (settings.ignoredEntities.contains(victim.getType().name())) {
            return;
        }
        boolean airborne = !attacker.isOnGround() || attacker.getFallDistance() > 0.0F;
        boolean wantCrit = settings.always || (settings.whileAirborne && airborne);
        if (!wantCrit) {
            return;
        }
        if (vanillaCrit(attacker)) {
            return; // ванилла уже умножила урон на 1.5
        }
        double base = event.getDamage();
        if (base <= 0.0) {
            return;
        }
        event.setDamage(base * settings.multiplier);
        showCrit(victim, attacker, settings);
    }

    /**
     * Условия ванильного крита: падение, не на земле, не в спринте.
     * Спринт в ваниле крит отменяет — именно этот случай мы и «докручиваем».
     */
    private static boolean vanillaCrit(Player attacker) {
        return attacker.getFallDistance() > 0.0F
                && !attacker.isOnGround()
                && !attacker.isSprinting();
    }

    /** Частицы и звук крита — на Bedrock клиент сам их не рисует, шлём с сервера. */
    private static void showCrit(Entity victim, Player attacker, VoidPvP.Settings settings) {
        Location location = victim.getLocation().add(0.0, victim.getHeight() * 0.5, 0.0);
        if (settings.particles) {
            try {
                location.getWorld().spawnParticle(Particle.CRIT, location, 8, 0.25, 0.25, 0.25, 0.15);
            } catch (Throwable ignored) {
                // частица недоступна в этой версии — не критично
            }
        }
        if (settings.sound) {
            try {
                attacker.playSound(location, Sound.ENTITY_PLAYER_ATTACK_CRIT, 0.9F, 1.0F);
                if (victim instanceof Player victimPlayer) {
                    victimPlayer.playSound(location, Sound.ENTITY_PLAYER_ATTACK_CRIT, 0.5F, 1.0F);
                }
            } catch (Throwable ignored) {
                // звук недоступен в этой версии — не критично
            }
        }
    }

    /** Чистка карт, чтобы они не росли на долгом аптайме. */
    private void pruneLastHits(int now) {
        if (lastHitTick.size() < 500) {
            return;
        }
        lastHitTick.entrySet().removeIf(entry -> now - entry.getValue() > 400);
    }

    private void prune(int now) {
        if (lastDamageTick.size() > 500) {
            lastDamageTick.entrySet().removeIf(entry -> now - entry.getValue() > 400);
        }
        if (repeatHitTick.size() > 500) {
            repeatHitTick.entrySet().removeIf(entry -> now - entry.getValue() > 400);
        }
        pruneLastHits(now);
    }

    /**
     * Снимает 0.5-секундную неуязвимость жертвы после нашего удара,
     * чтобы следующий тап тоже нанёс урон. Через тик: ядро само выставит
     * noDamageTicks после события, поэтому обнуляем уже после него.
     */
    private void clearInvulnerability(Entity victim, VoidPvP.Settings settings) {
        if (!(victim instanceof LivingEntity living)) {
            return;
        }
        if (living.isDead()) {
            return;
        }
        // Ослабленный режим: слишком частые удары не «пробивают» неуязвимость,
        // поэтому серия кликов не превращается в мгновенную смерть.
        int now = Bukkit.getCurrentTick();
        Integer previous = lastHitTick.put(victim.getUniqueId(), now);
        if (previous != null && now - previous < settings.minIntervalTicks) {
            return;
        }
        pruneLastHits(now);
        if (!(plugin instanceof Plugin bukkitPlugin)) {
            return; // без сервера (тесты) задачу планировать некуда
        }
        Bukkit.getScheduler().runTask(bukkitPlugin, () -> {
            if (living.isValid() && !living.isDead() && living.getNoDamageTicks() > 0) {
                living.setNoDamageTicks(0);
            }
        });
    }
}
