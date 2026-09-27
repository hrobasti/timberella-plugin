package net.kroet.timberella.commands;

import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.kroet.timberella.TimberellaPlugin;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

public class TimberellaCommand implements BasicCommand {

    private final TimberellaPlugin plugin;
    private final String label;

    // BasicCommand gets no label from Paper, so each registration passes its own.
    public TimberellaCommand(TimberellaPlugin plugin, String label) {
        this.plugin = plugin;
        this.label = label;
    }

    @Override
    public void execute(CommandSourceStack source, String[] args) {
        if (args.length == 0) {
            sendUsage(source);
            return;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "reload" -> handleReload(source);
            case "toggle" -> handleToggle(source, args);
            case "version" -> handleVersion(source);
            default -> sendUsage(source);
        }
    }

    // Lists only the subcommands this sender may use.
    private void sendUsage(CommandSourceStack source) {
        Map<String, String> rep = new HashMap<>();
        rep.put("label", label);
        rep.put("subcommands", String.join("|", availableSubcommands(source.getSender())));
        source.getSender().sendMessage(plugin.messages().format("command.usage", rep));
    }

    static List<String> availableSubcommands(CommandSender sender) {
        boolean canAdmin = sender.hasPermission("timberella.admin");
        boolean canToggle = sender.hasPermission("timberella.toggle") || canAdmin;
        List<String> options = new ArrayList<>();
        if (canAdmin)
            options.add("reload");
        options.add("version");
        if (canToggle)
            options.add("toggle");
        return options;
    }

    private void handleReload(CommandSourceStack source) {
        if (!source.getSender().hasPermission("timberella.admin")) {
            source.getSender().sendMessage(plugin.messages().component("command.no_permission"));
            return;
        }
        String configError = plugin.reloadAndMergeConfig();
        if (configError != null) {
            source.getSender().sendMessage(plugin.messages().format("command.reload_failed", Map.of("error", configError)));
            return;
        }
        source.getSender().sendMessage(plugin.messages().component("command.reloaded"));
    }

    private void handleToggle(CommandSourceStack source, String[] args) {
        if (args.length >= 2) {
            if (!source.getSender().hasPermission("timberella.admin")) {
                source.getSender().sendMessage(plugin.messages().component("command.no_permission"));
                return;
            }
            String targetName = args[1];
            OfflinePlayer target = plugin.getServer().getOfflinePlayerIfCached(targetName);
            if (target == null) {
                source.getSender().sendMessage(plugin.messages().component("command.player_not_found"));
                return;
            }
            boolean enabledNow = plugin.toggleEnabled(target.getUniqueId());
            Map<String, String> rep = new HashMap<>();
            rep.put("player", target.getName() != null ? target.getName() : targetName);
            source.getSender().sendMessage(plugin.messages().format(enabledNow ? "toggle.other_enabled" : "toggle.other_disabled", rep));
            Player onlineTarget = target.getPlayer();
            if (onlineTarget != null) {
                onlineTarget.sendMessage(plugin.messages().component(enabledNow ? "toggle.self_enabled" : "toggle.self_disabled"));
            }
            return;
        }

        if (!(source.getSender() instanceof Player player)) {
            source.getSender().sendMessage(plugin.messages().component("command.player_only"));
            return;
        }
        if (!source.getSender().hasPermission("timberella.toggle")) {
            source.getSender().sendMessage(plugin.messages().component("command.no_permission"));
            return;
        }
        boolean targetState = plugin.toggleEnabled(player.getUniqueId());
        player.sendMessage(plugin.messages().component(targetState ? "toggle.self_enabled" : "toggle.self_disabled"));
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
            return availableSubcommands(sender).stream().filter(opt -> opt.startsWith(prefix)).toList();
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
