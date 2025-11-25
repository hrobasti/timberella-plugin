package com.github.hrobasti.timberella.listeners;

import com.github.hrobasti.turtlelib.helper.UpdateChecker;
import com.github.hrobasti.timberella.TimberellaPlugin;
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
        event.getPlayer().sendMessage(plugin.messages().format("update.available", placeholders));
        event.getPlayer().sendMessage(plugin.messages().component("update.details"));
    }
}

