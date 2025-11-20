package com.hro_basti.timberella.listeners;

import com.hro_basti.timberella.TimberellaPlugin;
import com.hro_basti.timberella.update.UpdateChecker;
import java.util.HashMap;
import java.util.Map;
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
        UpdateChecker.UpdateInfo info = plugin.getPendingUpdateInfo();
        if (info == null) {
            return;
        }
        if (!event.getPlayer().hasPermission("timberella.update.notify")) {
            return;
        }
        Map<String, String> placeholders = new HashMap<>();
        placeholders.put("latest_ver", info.latestVersion());
        placeholders.put("current_ver", info.currentVersion());
        event.getPlayer().sendMessage(plugin.messages().format("update_available", placeholders));
    }
}
