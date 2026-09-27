package net.kroet.timberella.compat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.block.Block;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;

/**
 * Combines the adapters of all custom-block plugins that are enabled. An
 * adapter that throws (e.g. after an incompatible API update) is dropped with a
 * single warning instead of breaking felling.
 */
public final class CustomBlockGuards implements CustomBlockGuard {

    // Plugin name -> adapter factory. Lambdas (not constructor references) keep an
    // adapter class and the API it uses unloaded until that plugin is enabled.
    private static final Map<String, Supplier<CustomBlockGuard>> ADAPTERS = Map.of(
            "ItemsAdder", () -> new ItemsAdderGuard(),
            "MMOItems", () -> new MmoItemsGuard(),
            "MythicCrucible", () -> new MythicCrucibleGuard(),
            "CraftEngine", () -> new CraftEngineGuard());

    private final List<Named> adapters;
    private final Logger logger;

    private record Named(String plugin, CustomBlockGuard guard) {
    }

    CustomBlockGuards(Map<String, CustomBlockGuard> adapters, Logger logger) {
        this.adapters = new ArrayList<>();
        adapters.forEach((plugin, guard) -> this.adapters.add(new Named(plugin, guard)));
        this.logger = logger;
    }

    /**
     * Builds the guard for the custom-block plugins enabled right now; adapters
     * that wait for an event of their plugin are registered as listeners.
     */
    public static CustomBlockGuards detect(Plugin owner) {
        Logger logger = owner.getLogger();
        var plugins = owner.getServer().getPluginManager();
        Map<String, CustomBlockGuard> found = new TreeMap<>();
        ADAPTERS.forEach((name, factory) -> {
            if (!plugins.isPluginEnabled(name))
                return;
            try {
                CustomBlockGuard adapter = factory.get();
                if (adapter instanceof Listener listener) {
                    plugins.registerEvents(listener, owner);
                }
                found.put(name, adapter);
            } catch (RuntimeException | LinkageError e) {
                logger.log(Level.WARNING, "Could not hook into " + name + "; its custom blocks are not protected", e);
            }
        });
        return new CustomBlockGuards(found, logger);
    }

    /** Names of the plugins whose custom blocks are protected. */
    public List<String> hookedPlugins() {
        return adapters.stream().map(Named::plugin).toList();
    }

    @Override
    public boolean isCustomBlock(Block block) {
        if (block == null || adapters.isEmpty())
            return false;
        for (int i = 0; i < adapters.size(); i++) {
            Named adapter = adapters.get(i);
            try {
                if (adapter.guard().isCustomBlock(block))
                    return true;
            } catch (RuntimeException | LinkageError e) {
                logger.log(Level.WARNING,
                        "Custom block check for " + adapter.plugin() + " failed and is disabled until restart", e);
                adapters.remove(i--);
            }
        }
        return false;
    }
}
