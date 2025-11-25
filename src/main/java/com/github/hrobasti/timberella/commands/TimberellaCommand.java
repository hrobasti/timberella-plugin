package com.github.hrobasti.timberella.commands;

import com.github.hrobasti.timberella.TimberellaPlugin;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

public class TimberellaCommand implements BasicCommand {

    private final TimberellaPlugin plugin;

    public TimberellaCommand(TimberellaPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void execute(CommandSourceStack source, String[] args) {
        if (args.length == 0) {
            source.getSender().sendMessage(plugin.messages().component("command.usage-admin"));
            return;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "reload" -> handleReload(source);
            case "toggle" -> handleToggle(source, args);
            case "version" -> handleVersion(source);
            default -> source.getSender().sendMessage(plugin.messages().component("command.usage-admin"));
        }
    }

    private void handleReload(CommandSourceStack source) {
        if (!source.getSender().hasPermission("timberella.admin")) {
            source.getSender().sendMessage(plugin.messages().component("command.no-permission"));
            return;
        }
        plugin.reloadAndMergeConfig();
        source.getSender().sendMessage(plugin.messages().component("command.reloaded"));
    }

    private void handleToggle(CommandSourceStack source, String[] args) {
        if (args.length >= 2) {
            if (!source.getSender().hasPermission("timberella.admin")) {
                source.getSender().sendMessage(plugin.messages().component("command.no-permission"));
                return;
            }
            String targetName = args[1];
            Player target = plugin.getServer().getPlayer(targetName);
            if (target == null) {
                source.getSender().sendMessage(plugin.messages().component("command.player-not-found"));
                return;
            }
            boolean enabledNow = plugin.toggleEnabled(target.getUniqueId());
            Map<String, String> rep = new HashMap<>();
            rep.put("player", target.getName());
            source.getSender().sendMessage(plugin.messages().format(enabledNow ? "toggle.other-enabled" : "toggle.other-disabled", rep));
            target.sendMessage(plugin.messages().component(enabledNow ? "toggle.self-enabled" : "toggle.self-disabled"));
            return;
        }

        if (!(source.getSender() instanceof Player player)) {
            source.getSender().sendMessage(plugin.messages().component("command.player-only"));
            return;
        }
        if (!source.getSender().hasPermission("timberella.toggle")) {
            source.getSender().sendMessage(plugin.messages().component("command.no-permission"));
            return;
        }
        boolean targetState = plugin.toggleEnabled(player.getUniqueId());
        player.sendMessage(plugin.messages().component(targetState ? "toggle.self-enabled" : "toggle.self-disabled"));
    }

    private void handleVersion(CommandSourceStack source) {
        Map<String, String> rep = new HashMap<>();
        rep.put("current_ver", plugin.getPluginMeta().getVersion());
        source.getSender().sendMessage(plugin.messages().format("command.version", rep));
    }

    @Override
    public List<String> suggest(CommandSourceStack source, String[] args) {
        var sender = source.getSender();
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            List<String> options = new ArrayList<>();
            boolean canAdmin = sender.hasPermission("timberella.admin");
            boolean canToggle = sender.hasPermission("timberella.toggle") || canAdmin;
            options.add("version");
            if (canAdmin) options.add("reload");
            if (canToggle) options.add("toggle");
            return options.stream().filter(opt -> opt.startsWith(prefix)).toList();
        }
        if (args.length == 2 && "toggle".equalsIgnoreCase(args[0]) && sender.hasPermission("timberella.admin")) {
            String prefix = args[1].toLowerCase(Locale.ROOT);
            return plugin.getServer().getOnlinePlayers().stream()
                    .map(Player::getName)
                    .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(prefix))
                    .toList();
        }
        return Collections.emptyList();
    }

    @Override
    public @Nullable String permission() {
        return null; // handled per-subcommand
    }

}

