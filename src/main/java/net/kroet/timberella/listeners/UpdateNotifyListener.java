package net.kroet.timberella.listeners;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.kroet.timberella.TimberellaPlugin;
import net.kroet.turtlelib.helper.UpdateChecker;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

public class UpdateNotifyListener implements Listener {

    private final TimberellaPlugin plugin;

    public UpdateNotifyListener(TimberellaPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (!plugin.isOpJoinUpdateNotifyEnabled()) {
            return;
        }
        UpdateChecker.UpdateInfo info = plugin.getPendingUpdateInfo();
        if (info == null) {
            return;
        }
        if (!(event.getPlayer().isOp() || event.getPlayer().hasPermission("timberella.update.notify"))) {
            return;
        }
        Map<String, String> placeholders = new HashMap<>();
        placeholders.put("latest_ver", info.latestVersion());
        placeholders.put("current_ver", info.currentVersion());
        for (String key : joinMessageKeys(plugin.isConsoleUpdateNotifyEnabled())) {
            event.getPlayer().sendMessage(plugin.messages().format(key, placeholders));
        }
    }

    // The pointer to the server log only where the log has the details.
    // Package-private
    // for UpdateNotifyListenerTest.
    static List<String> joinMessageKeys(boolean consoleNotified) {
        return consoleNotified ? List.of("update.available", "update.details") : List.of("update.available");
    }
}
