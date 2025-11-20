package com.hro_basti.timberella.commands;

import com.hro_basti.timberella.TimberellaPlugin;
import org.bukkit.entity.Player;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.Map;

public class TimberellaCommand implements CommandExecutor {
    private final TimberellaPlugin plugin;

    public TimberellaCommand(TimberellaPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (args.length == 0) {
            sender.sendMessage(plugin.messages().component("usage_admin"));
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "reload":
                if (!sender.hasPermission("timberella.admin")) {
                    sender.sendMessage(plugin.messages().component("no_permission"));
                    return true;
                }
                plugin.reloadAndMergeConfig();
                sender.sendMessage(plugin.messages().component("reloaded"));
                return true;
            case "toggle":
                if (args.length >= 2) {
                    // Admin toggle for another player
                    if (!sender.hasPermission("timberella.admin")) {
                        sender.sendMessage(plugin.messages().component("no_permission"));
                        return true;
                    }
                    String targetName = args[1];
                    Player target = plugin.getServer().getPlayer(targetName);
                    if (target == null) {
                        sender.sendMessage(plugin.messages().component("player_not_found"));
                        return true;
                    }
                    boolean enabledNow = plugin.toggleEnabled(target.getUniqueId());
                    // Inform admin
                    Map<String, String> rep = new HashMap<>();
                    rep.put("player", target.getName());
                    sender.sendMessage(plugin.messages().format(enabledNow ? "toggled_other_on" : "toggled_other_off", rep));
                    // Inform target
                    target.sendMessage(plugin.messages().component(enabledNow ? "toggled_on" : "toggled_off"));
                    return true;
                } else {
                    // Self toggle
                    if (!(sender instanceof Player)) {
                        sender.sendMessage(plugin.messages().component("player_only"));
                        return true;
                    }
                    if (!sender.hasPermission("timberella.toggle")) {
                        sender.sendMessage(plugin.messages().component("no_permission"));
                        return true;
                    }
                    Player p = (Player) sender;
                    boolean targetState = plugin.toggleEnabled(p.getUniqueId());
                    sender.sendMessage(plugin.messages().component(targetState ? "toggled_on" : "toggled_off"));
                    return true;
                }
            case "version":
                Map<String, String> rep = new HashMap<>();
                rep.put("current_ver", plugin.getPluginMeta().getVersion());
                sender.sendMessage(plugin.messages().format("version", rep));
                return true;
            default:
                sender.sendMessage(plugin.messages().component("usage_admin"));
                return true;
        }
    }
}
