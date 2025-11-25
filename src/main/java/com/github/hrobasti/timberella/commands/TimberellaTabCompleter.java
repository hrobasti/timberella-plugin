package com.github.hrobasti.timberella.commands;

import com.github.hrobasti.timberella.TimberellaPlugin;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

public class TimberellaTabCompleter implements TabCompleter {
    private final TimberellaPlugin plugin;

    public TimberellaTabCompleter(TimberellaPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 0) return out;
        String last = args[args.length - 1].toLowerCase(Locale.ROOT);

        if (args.length == 1) {
            boolean canAdmin = sender.hasPermission("timberella.admin");
            boolean canToggle = sender.hasPermission("timberella.toggle") || canAdmin;
            List<String> subs = new ArrayList<>(Arrays.asList("version"));
            if (canAdmin) subs.add("reload");
            if (canToggle) subs.add("toggle");
            for (String s : subs) if (s.startsWith(last)) out.add(s);
            return out;
        }

        if (args.length == 2 && args[0].equalsIgnoreCase("toggle")) {
            if (sender.hasPermission("timberella.admin")) {
                for (Player p : plugin.getServer().getOnlinePlayers()) {
                    String name = p.getName();
                    if (name.toLowerCase(Locale.ROOT).startsWith(last)) out.add(name);
                }
            }
            return out;
        }

        return out;
    }
}

