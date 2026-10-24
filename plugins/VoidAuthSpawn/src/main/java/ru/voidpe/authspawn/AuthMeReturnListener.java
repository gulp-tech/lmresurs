package ru.voidpe.authspawn;

import fr.xephi.authme.events.LoginEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

/**
 * Слушатель успешного входа AuthMe. Отдельным классом, потому что
 * {@link LoginEvent} существует только при установленном AuthMe:
 * VoidAuthSpawn регистрирует этот слушатель, только если AuthMe включён.
 */
final class AuthMeReturnListener implements Listener {

    private final VoidAuthSpawn plugin;

    AuthMeReturnListener(VoidAuthSpawn plugin) {
        this.plugin = plugin;
    }

    /** AuthMe вызывает LoginEvent после успешного /login (и после регистрации с автологином). */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onLogin(LoginEvent event) {
        plugin.handleSuccessfulAuth(event.getPlayer());
    }
}
