package net.kroet.timberella.listeners;

import java.util.Collection;
import java.util.EnumSet;
import java.util.Set;
import org.bukkit.Material;
import org.bukkit.configuration.Configuration;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * UnderwaterTrees' rules for planting a sapling into water, read from that
 * plugin's live config, so a replant only goes underwater where it would.
 */
final class UnderwaterPlanting {

    private static final String PLUGIN_NAME = "UnderwaterTrees";
    private static final String PLACE_PERMISSION = "underwatertrees.place";

    private final Set<Material> saplings;
    private final Set<Material> soils;
    private final boolean requireWaterAbove;

    private UnderwaterPlanting(Set<Material> saplings, Set<Material> soils, boolean requireWaterAbove) {
        this.saplings = saplings;
        this.soils = soils;
        this.requireWaterAbove = requireWaterAbove;
    }

    // The rules while UnderwaterTrees is enabled and lets the player plant
    // underwater; null otherwise.
    static UnderwaterPlanting forPlayer(Player player) {
        Plugin plugin = player.getServer().getPluginManager().getPlugin(PLUGIN_NAME);
        if (plugin == null || !plugin.isEnabled() || !player.hasPermission(PLACE_PERMISSION))
            return null;
        return fromConfig(plugin.getConfig());
    }

    // Package-private for UnderwaterPlantingTest.
    static UnderwaterPlanting fromConfig(Configuration config) {
        return new UnderwaterPlanting(enabled(config, "saplings"), enabled(config, "soils"),
                config.getBoolean("require_water_above", false));
    }

    // Like UnderwaterTrees itself, a section that enables nothing falls back to
    // the defaults of its bundled config.yml.
    private static Set<Material> enabled(Configuration config, String section) {
        Set<Material> materials = enabledIn(config.get(section, null));
        if (materials.isEmpty() && config.getDefaults() != null) {
            materials = enabledIn(config.getDefaults().get(section, null));
        }
        return materials;
    }

    private static Set<Material> enabledIn(Object section) {
        Set<Material> materials = EnumSet.noneOf(Material.class);
        if (section instanceof ConfigurationSection entries) {
            for (String key : entries.getKeys(false)) {
                Material material = entries.getBoolean(key, false) ? Material.matchMaterial(key) : null;
                if (material != null)
                    materials.add(material);
            }
        }
        return materials;
    }

    boolean accepts(Material sapling, Material soil, Material target) {
        return isWaterFor(target) && saplings.contains(sapling) && soils.contains(soil);
    }

    // Whether a sapling it would plant underwater stays if planted now: in water
    // only with water above, the only place UnderwaterTrees holds the flow back
    // from; on dry ground only once no water is about to flow in.
    boolean isSettled(Material sapling, Material soil, Material target, Material above, Collection<Material> sides) {
        if (sapling == Material.MANGROVE_PROPAGULE || !saplings.contains(sapling) || !soils.contains(soil))
            return true;
        if (isWater(target))
            return !isWaterFor(target) || isWaterFor(above);
        return !isWater(above) && sides.stream().noneMatch(UnderwaterPlanting::isWater);
    }

    // Water, or a bubble column unless water must sit above the sapling.
    private boolean isWaterFor(Material type) {
        return type == Material.WATER || (!requireWaterAbove && type == Material.BUBBLE_COLUMN);
    }

    private static boolean isWater(Material type) {
        return type == Material.WATER || type == Material.BUBBLE_COLUMN;
    }
}
