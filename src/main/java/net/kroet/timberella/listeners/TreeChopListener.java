package net.kroet.timberella.listeners;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.function.ToLongFunction;
import java.util.logging.Level;
import net.kroet.timberella.TimberellaPlugin;
import net.kroet.timberella.compat.CustomBlockGuard;
import net.kroet.turtlelib.helper.ConfigWatcher;
import org.bukkit.Bukkit;
import org.bukkit.Effect;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Waterlogged;
import org.bukkit.block.data.type.Leaves;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.ExperienceOrb;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.CraftingInventory;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitRunnable;

public class TreeChopListener implements Listener {
    private final TimberellaPlugin plugin;
    private final NamespacedKey activeFellingKey;
    private final Map<UUID, Felling> activeFellings = new HashMap<>();
    // Blocks of other plugins disguised as logs or mushroom blocks; never felled,
    // decayed, counted as foliage or replaced by a sapling.
    private CustomBlockGuard customBlocks = CustomBlockGuard.NONE;
    private final Map<UUID, Long> lastFellingActionbarAt = new HashMap<>();
    private static final long FELLING_ACTIONBAR_COOLDOWN_MS = 900L;

    private record LeafEntry(Block block, int depth) {
    }

    private record ReplantPlan(Material sapling, List<Block> targets) {
    }

    private static final Set<Material> AZALEAS = EnumSet.of(Material.AZALEA, Material.FLOWERING_AZALEA);

    // The blocks to fell, hit block first, where the trunk base stood, all blocks
    // collected, and the builds among them, which hold none of the tree's leaves.
    private record TreeSelection(List<Block> blocks, List<Block> plantingSpots, Set<Long> builds,
            Set<Long> collected) {
    }

    /**
     * Saplings kept back from leaf drops for a replant, at most one per planting
     * spot. Package-private for SaplingPoolTest.
     */
    static final class SaplingPool {
        private final Set<Material> saplings;
        private final int needed;
        private int collected;
        private Material kept;

        SaplingPool(Material sapling, int needed) {
            this(Set.of(sapling), needed);
        }

        // Takes any of the saplings, but only the kind it kept first.
        SaplingPool(Set<Material> saplings, int needed) {
            this.saplings = saplings;
            this.needed = needed;
        }

        /** Keeps what the replant still needs; returns how many were kept. */
        int reserve(Material type, int amount) {
            if (!saplings.contains(type) || (kept != null && type != kept) || amount <= 0 || collected >= needed)
                return 0;
            int taken = Math.min(amount, needed - collected);
            collected += taken;
            kept = type;
            return taken;
        }

        int collected() {
            return collected;
        }

        Material kept() {
            return kept;
        }
    }

    // Vanilla's leaf distance at which non-persistent leaves count as unsupported.
    private static final int VANILLA_LEAF_DECAY_DISTANCE = 7;
    private static final BlockFace[] LEAF_DISTANCE_FACES = {BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST,
            BlockFace.WEST, BlockFace.UP, BlockFace.DOWN};
    private static final int NATURAL_FOLIAGE_MIN_COUNT = 4;
    // Leaf cleanup always reaches this far around felled logs, enough for a small
    // crown like a birch's; wider species reach their own horizontal radius.
    private static final int MIN_LEAF_DISTANCE = 4;
    private static final Set<Material> GIANT_MUSHROOM_BLOCKS = EnumSet.of(Material.MUSHROOM_STEM,
            Material.BROWN_MUSHROOM_BLOCK, Material.RED_MUSHROOM_BLOCK);
    private static final Set<Material> MANGROVE_ROOT_BLOCKS = EnumSet.of(Material.MANGROVE_ROOTS,
            Material.MUDDY_MANGROVE_ROOTS);
    // A tall mangrove's trunk stands on roots up to six blocks above the mud its
    // propagule grew on.
    private static final int MAX_REPLANT_DROP = 6;
    private static final int MAX_WATER_WAIT_TICKS = 60;
    private static final Map<Material, Material> FUNGUS_CAPS = Map.of(
            Material.WARPED_STEM, Material.WARPED_WART_BLOCK,
            Material.STRIPPED_WARPED_STEM, Material.WARPED_WART_BLOCK,
            Material.CRIMSON_STEM, Material.NETHER_WART_BLOCK,
            Material.STRIPPED_CRIMSON_STEM, Material.NETHER_WART_BLOCK);

    // Marks the break events this plugin fires itself, so onBreak can skip them
    // while protection plugins still receive them via BlockBreakEvent's handlers.
    private static final class TimberBlockBreakEvent extends BlockBreakEvent {
        TimberBlockBreakEvent(Block block, Player player) {
            super(block, player);
        }
    }

    private static final Set<Material> TRUNK_SOILS = EnumSet.of(
            Material.DIRT,
            Material.COARSE_DIRT,
            Material.PODZOL,
            Material.ROOTED_DIRT,
            Material.MOSS_BLOCK,
            Material.PALE_MOSS_BLOCK,
            Material.MUD,
            Material.MUDDY_MANGROVE_ROOTS,
            Material.CRIMSON_NYLIUM,
            Material.WARPED_NYLIUM);

    // spotless:off - one constant per line reads better than Eclipse's wrap here.
    // Package-private so SpeciesConfigConsistencyTest can check every species'
    // defaults against the bundled config.yml.
    enum Species {
        MANGROVE("mangrove", true, 128, 9, 36, Material.MANGROVE_PROPAGULE),
        JUNGLE("jungle", true, -1, 8, 32, Material.JUNGLE_SAPLING),
        SPRUCE("spruce", true, -1, 6, 32, Material.SPRUCE_SAPLING),
        OAK("oak", true, -1, 6, 24, Material.OAK_SAPLING),
        PALE_OAK("pale_oak", true, -1, 5, 16, Material.PALE_OAK_SAPLING),
        DARK_OAK("dark_oak", true, -1, 6, 12, Material.DARK_OAK_SAPLING),
        BIRCH("birch", true, -1, 2, 12, Material.BIRCH_SAPLING),
        ACACIA("acacia", true, -1, 8, 12, Material.ACACIA_SAPLING),
        CHERRY("cherry", true, -1, 9, 12, Material.CHERRY_SAPLING),
        POPLAR("poplar", true, -1, 9, 24, Material.POPLAR_SAPLING),
        MUSHROOM_BROWN("mushroom_brown", true, -1, 4, 12, null),
        MUSHROOM_RED("mushroom_red", true, -1, 2, 12, null),
        WARPED("warped", true, -1, 6, 32, Material.WARPED_FUNGUS),
        CRIMSON("crimson", true, -1, 6, 32, Material.CRIMSON_FUNGUS);
        // spotless:on

        private final String configKey;
        private final boolean defaultEnabled;
        private final int defaultMaxBlocks;
        private final int defaultHorizontalRadius;
        private final int defaultVerticalRadius;
        private final Material sapling;

        Species(String configKey, boolean defaultEnabled, int defaultMaxBlocks,
                int defaultHorizontalRadius, int defaultVerticalRadius, Material sapling) {
            this.configKey = configKey;
            this.defaultEnabled = defaultEnabled;
            this.defaultMaxBlocks = defaultMaxBlocks;
            this.defaultHorizontalRadius = defaultHorizontalRadius;
            this.defaultVerticalRadius = defaultVerticalRadius;
            this.sapling = sapling;
        }

        String configKey() {
            return configKey;
        }

        boolean defaultEnabled() {
            return defaultEnabled;
        }

        int defaultMaxBlocks() {
            return defaultMaxBlocks;
        }

        int defaultHorizontalRadius() {
            return defaultHorizontalRadius;
        }

        int defaultVerticalRadius() {
            return defaultVerticalRadius;
        }

        /** Sapling to replant after felling; null for species that don't replant. */
        Material sapling() {
            return sapling;
        }
    }

    private static final Map<Material, Species> MATERIAL_TO_SPECIES = new EnumMap<>(Material.class);

    static {
        registerSpeciesMaterials(Species.MANGROVE,
                Material.MANGROVE_LOG,
                Material.STRIPPED_MANGROVE_LOG,
                Material.MANGROVE_WOOD,
                Material.STRIPPED_MANGROVE_WOOD,
                Material.MANGROVE_ROOTS,
                Material.MUDDY_MANGROVE_ROOTS);
        registerSpeciesMaterials(Species.JUNGLE,
                Material.JUNGLE_LOG,
                Material.STRIPPED_JUNGLE_LOG,
                Material.JUNGLE_WOOD,
                Material.STRIPPED_JUNGLE_WOOD);
        registerSpeciesMaterials(Species.SPRUCE,
                Material.SPRUCE_LOG,
                Material.STRIPPED_SPRUCE_LOG,
                Material.SPRUCE_WOOD,
                Material.STRIPPED_SPRUCE_WOOD);
        registerSpeciesMaterials(Species.OAK,
                Material.OAK_LOG,
                Material.STRIPPED_OAK_LOG,
                Material.OAK_WOOD,
                Material.STRIPPED_OAK_WOOD);
        registerSpeciesMaterialsByName(Species.PALE_OAK,
                "PALE_OAK_LOG",
                "STRIPPED_PALE_OAK_LOG",
                "PALE_OAK_WOOD",
                "STRIPPED_PALE_OAK_WOOD");
        registerSpeciesMaterials(Species.DARK_OAK,
                Material.DARK_OAK_LOG,
                Material.STRIPPED_DARK_OAK_LOG,
                Material.DARK_OAK_WOOD,
                Material.STRIPPED_DARK_OAK_WOOD);
        registerSpeciesMaterials(Species.BIRCH,
                Material.BIRCH_LOG,
                Material.STRIPPED_BIRCH_LOG,
                Material.BIRCH_WOOD,
                Material.STRIPPED_BIRCH_WOOD);
        registerSpeciesMaterials(Species.ACACIA,
                Material.ACACIA_LOG,
                Material.STRIPPED_ACACIA_LOG,
                Material.ACACIA_WOOD,
                Material.STRIPPED_ACACIA_WOOD);
        registerSpeciesMaterials(Species.CHERRY,
                Material.CHERRY_LOG,
                Material.STRIPPED_CHERRY_LOG,
                Material.CHERRY_WOOD,
                Material.STRIPPED_CHERRY_WOOD);
        registerSpeciesMaterials(Species.POPLAR,
                Material.POPLAR_LOG,
                Material.STRIPPED_POPLAR_LOG,
                Material.POPLAR_WOOD,
                Material.STRIPPED_POPLAR_WOOD);
        registerSpeciesMaterials(Species.MUSHROOM_BROWN,
                Material.BROWN_MUSHROOM_BLOCK);
        registerSpeciesMaterials(Species.MUSHROOM_RED,
                Material.RED_MUSHROOM_BLOCK);
        registerSpeciesMaterials(Species.WARPED,
                Material.WARPED_STEM,
                Material.STRIPPED_WARPED_STEM,
                Material.WARPED_HYPHAE,
                Material.STRIPPED_WARPED_HYPHAE);
        registerSpeciesMaterials(Species.CRIMSON,
                Material.CRIMSON_STEM,
                Material.STRIPPED_CRIMSON_STEM,
                Material.CRIMSON_HYPHAE,
                Material.STRIPPED_CRIMSON_HYPHAE);
    }

    private static void registerSpeciesMaterials(Species species, Material... materials) {
        for (Material material : materials) {
            if (material != null) {
                MATERIAL_TO_SPECIES.put(material, species);
            }
        }
    }

    private static void registerSpeciesMaterialsByName(Species species, String... materialNames) {
        for (String name : materialNames) {
            if (name == null)
                continue;
            Material material = Material.matchMaterial(name);
            if (material != null) {
                MATERIAL_TO_SPECIES.put(material, species);
            }
        }
    }

    private static final class SpeciesLimit {
        boolean enabled;
        int maxBlocks;
        int maxHorizontalRadius;
        int maxVerticalRadius;
    }

    private final Map<Species, SpeciesLimit> speciesLimits = new EnumMap<>(Species.class);
    private final Set<Material> normalLogs = EnumSet.noneOf(Material.class);
    private final Set<Material> strippedLogs = EnumSet.noneOf(Material.class);
    private final Set<Material> woods = EnumSet.noneOf(Material.class);
    private final Set<Material> strippedWoods = EnumSet.noneOf(Material.class);
    private final Set<Material> fences = EnumSet.noneOf(Material.class);
    private final Set<Material> additions = EnumSet.noneOf(Material.class);
    private final Set<Material> allTreeMaterials = EnumSet.noneOf(Material.class);
    private final Set<Material> allowedAxes = new HashSet<>();
    private final Map<Material, Material> saplingMappings = new EnumMap<>(Material.class);
    private final Set<Material> allowedSaplings = EnumSet.noneOf(Material.class);
    private final Map<Material, Set<Material>> leafMappings = new EnumMap<>(Material.class);
    private final Set<Material> mappedFoliage = EnumSet.noneOf(Material.class);
    private boolean leafMappingsLoaded;
    private boolean timberEnabled = true;
    private boolean leavesDecayEnabled = true;
    private long leavesDecayIntervalTicks = 2L;
    private int leavesDecayRadius = 5;
    private int leavesDecayBatchSize = 20;
    private int leavesDecayMaxDistance = 9;
    private boolean replantEnabled = false;
    private boolean replantFromDrops = true;
    // Set only while a leaf breaks for a drops-based replant, so onItemSpawn can
    // keep back the saplings it drops.
    private SaplingPool capturingPool;
    private boolean includeDiagonals = true;
    private boolean requireNaturalLeaves = true;
    private int maxBlocks = 1024;
    private int sneakMode = 0;
    private int minRemainingDurability = 10;
    private boolean durabilityModeAll = false;
    private double durabilityMultiplier = 0.5;
    private long breakIntervalTicks = 2L;
    private final InternalEventGuard breakEventGuard;
    private final InternalEventGuard placeEventGuard;
    private final InternalEventGuard dropEventGuard;

    public TreeChopListener(TimberellaPlugin plugin) {
        this.plugin = plugin;
        this.activeFellingKey = new NamespacedKey(plugin, "active_felling_id");
        this.breakEventGuard = new InternalEventGuard(plugin.getLogger(), "BlockBreakEvent",
                "Breaking further logs and leaves");
        this.placeEventGuard = new InternalEventGuard(plugin.getLogger(), "BlockPlaceEvent", "Replanting");
        this.dropEventGuard = new InternalEventGuard(plugin.getLogger(), "BlockDropItemEvent",
                "The drop event for further logs");
        loadCategoryMaps();
    }

    public void refresh() {
        loadCategoryMaps();
    }

    public void setCustomBlockGuard(CustomBlockGuard guard) {
        this.customBlocks = guard != null ? guard : CustomBlockGuard.NONE;
    }

    // Resolved materials per config category, for the log_detail console listing.
    public Map<String, List<String>> activeCategoryMaterials() {
        Map<String, List<String>> result = new LinkedHashMap<>();
        result.put("logs", sortedNames(normalLogs));
        result.put("stripped_logs", sortedNames(strippedLogs));
        result.put("woods", sortedNames(woods));
        result.put("stripped_woods", sortedNames(strippedWoods));
        result.put("fences", sortedNames(fences));
        result.put("additions", sortedNames(additions));
        return result;
    }

    public List<String> activeLeafMappings() {
        return leafMappings.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(Comparator.comparing(Material::name)))
                .map(entry -> entry.getKey().name() + " -> " + String.join(", ", sortedNames(entry.getValue())))
                .toList();
    }

    public List<String> activeAxes() {
        return sortedNames(allowedAxes);
    }

    public List<String> activeSaplings() {
        return sortedNames(allowedSaplings);
    }

    private static List<String> sortedNames(Collection<Material> materials) {
        return materials.stream().map(Material::name).sorted().toList();
    }
    private void loadCategoryMaps() {
        normalLogs.clear();
        strippedLogs.clear();
        woods.clear();
        strippedWoods.clear();
        fences.clear();
        additions.clear();
        allowedAxes.clear();
        saplingMappings.clear();
        initializeSaplingMappings();
        allowedSaplings.clear();
        List<String> saplingNames = plugin.getConfig().getStringList("replant.saplings");
        for (String name : saplingNames) {
            Material m = Material.matchMaterial(name.toUpperCase(Locale.ROOT));
            if (m != null) {
                allowedSaplings.add(m);
            } else {
                plugin.getLogger().warning("Ignoring unknown sapling in replant.saplings: " + name);
            }
        }
        loadMap("categories.logs", normalLogs);
        normalLogs.remove(Material.MUDDY_MANGROVE_ROOTS);
        loadMap("categories.stripped_logs", strippedLogs);
        loadMap("categories.woods", woods);
        loadMap("categories.stripped_woods", strippedWoods);
        loadMap("categories.fences", fences);
        loadMap("categories.additions", additions);
        allTreeMaterials.clear();
        allTreeMaterials.addAll(normalLogs);
        allTreeMaterials.addAll(strippedLogs);
        allTreeMaterials.addAll(woods);
        allTreeMaterials.addAll(strippedWoods);
        allTreeMaterials.addAll(fences);
        allTreeMaterials.addAll(additions);
        loadLeafMappings();
        // modules
        timberEnabled = plugin.getConfig().getBoolean("enable_timber", true);
        leavesDecayEnabled = plugin.getConfig().getBoolean("enable_leaves_decay", true);
        leavesDecayRadius = Math.max(0, plugin.getConfig().getInt("leaves_decay.decay_radius", 5));
        leavesDecayIntervalTicks = Math.max(1L, plugin.getConfig().getLong("leaves_decay.batch_interval_ticks", 2L));
        leavesDecayBatchSize = Math.max(1, plugin.getConfig().getInt("leaves_decay.batch_size", 20));
        leavesDecayMaxDistance = Math.max(1, plugin.getConfig().getInt("leaves_decay.max_distance", 9));
        replantEnabled = plugin.getConfig().getBoolean("enable_replant", true);
        replantFromDrops = !"free".equalsIgnoreCase(plugin.getConfig().getString("replant.sapling_source", "drops"));
        maxBlocks = Math.max(1, plugin.getConfig().getInt("max_blocks", 1024));
        loadSpeciesLimits();
        sneakMode = plugin.getConfig().getInt("sneak_mode", 0);
        // adjacency_faces (6 or 26) is still honored when include_diagonals is absent.
        includeDiagonals = plugin.getConfig().getBoolean("include_diagonals",
                plugin.getConfig().getInt("adjacency_faces", 26) > 6);
        requireNaturalLeaves = plugin.getConfig().getBoolean("require_natural_leaves", true);

        // tools
        List<String> axes = plugin.getConfig().getStringList("tools.allowed_axes");
        for (String name : axes) {
            Material m = Material.matchMaterial(name.toUpperCase(Locale.ROOT));
            if (m != null)
                allowedAxes.add(m);
        }
        minRemainingDurability = Math.max(0, plugin.getConfig().getInt("tools.min_remaining_durability", 10));
        String mode = plugin.getConfig().getString("tools.durability_mode", "all");
        durabilityModeAll = mode != null && mode.equalsIgnoreCase("all");
        durabilityMultiplier = plugin.getConfig().getDouble("tools.durability_multiplier", 0.5);
        breakIntervalTicks = Math.max(1L, plugin.getConfig().getLong("break_interval_ticks", 2L));
    }

    private void loadMap(String path, Set<Material> target) {
        var section = plugin.getConfig().getConfigurationSection(path);
        if (section == null)
            return;
        for (String key : section.getKeys(false)) {
            boolean enabled = section.getBoolean(key, true);
            if (!enabled)
                continue;
            Material m = Material.matchMaterial(key.toUpperCase(Locale.ROOT));
            if (m != null)
                target.add(m);
            else
                plugin.getLogger().warning("Ignoring unknown material in section " + path + ": " + key);
        }
    }

    private void loadSpeciesLimits() {
        speciesLimits.clear();
        // The species_limits section is migrated centrally in TimberellaPlugin
        // (onEnable/reloadAndMergeConfig), so by the time this runs, any
        // kebab-case "species-limits" section has already been copied here.
        ConfigurationSection section = plugin.getConfig().getConfigurationSection("species_limits");
        for (Species species : Species.values()) {
            SpeciesLimit limit = new SpeciesLimit();
            limit.enabled = species.defaultEnabled();
            limit.maxBlocks = species.defaultMaxBlocks();
            limit.maxHorizontalRadius = Math.max(0, species.defaultHorizontalRadius());
            limit.maxVerticalRadius = Math.max(0, species.defaultVerticalRadius());

            ConfigurationSection source = section != null ? section.getConfigurationSection(species.configKey()) : null;
            if (source == null && section != null) {
                // Also accepts a kebab-case species section name (pale-oak, ...) as a
                // fallback, and warns so an admin who hits this can rename it to the
                // snake_case form.
                String legacyKey = species.configKey().replace('_', '-');
                source = section.getConfigurationSection(legacyKey);
                if (source != null) {
                    plugin.getLogger()
                            .warning("species_limits." + legacyKey + " uses a legacy key name; rename it to species_limits."
                                    + species.configKey()
                                    + " - a future release will stop recognizing the old name.");
                }
            }
            if (source != null) {
                limit.enabled = source.getBoolean("enabled", limit.enabled);
                if (source.isSet("max_blocks") || source.isSet("max-blocks")) {
                    limit.maxBlocks = source.isSet("max_blocks")
                            ? source.getInt("max_blocks", limit.maxBlocks)
                            : source.getInt("max-blocks", limit.maxBlocks);
                }
                if (source.isSet("max_horizontal_radius") || source.isSet("max-horizontal-radius")) {
                    limit.maxHorizontalRadius = Math.max(0,
                            source.isSet("max_horizontal_radius")
                                    ? source.getInt("max_horizontal_radius", limit.maxHorizontalRadius)
                                    : source.getInt("max-horizontal-radius", limit.maxHorizontalRadius));
                }
                if (source.isSet("max_vertical_radius") || source.isSet("max-vertical-radius")) {
                    limit.maxVerticalRadius = Math.max(0,
                            source.isSet("max_vertical_radius")
                                    ? source.getInt("max_vertical_radius", limit.maxVerticalRadius)
                                    : source.getInt("max-vertical-radius", limit.maxVerticalRadius));
                }
            }

            limit.maxBlocks = limit.maxBlocks < 1 ? -1 : limit.maxBlocks;
            speciesLimits.put(species, limit);
        }
    }

    // In-memory felling state never survives a restart, so a felling tag still
    // present here is stale (server stop/crash, item dropped/moved, ...).
    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        clearAnyFellingTags(event.getPlayer());
    }

    /**
     * Clears felling tags left on tools of players still mid-felling when the
     * plugin shuts down, since the scheduled BukkitRunnable that would normally do
     * this never runs again after a server stop.
     */
    public void clearActiveFellingTagsOnShutdown() {
        for (UUID uuid : new ArrayList<>(activeFellings.keySet())) {
            Player player = plugin.getServer().getPlayer(uuid);
            if (player != null) {
                clearAnyFellingTags(player);
            }
        }
    }

    // HIGHEST: runs after every plugin that may still cancel the break (protection,
    // custom blocks), and only reads the event.
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (event instanceof TimberBlockBreakEvent)
            return;
        Player player = event.getPlayer();
        // Adventure mode only lets the axe break what its can_break list allows, so
        // only the hit block breaks: no felling, leaf cleanup or replant.
        if (player.getGameMode() == GameMode.ADVENTURE)
            return;
        Block start = event.getBlock();
        ItemStack tool = player.getInventory().getItemInMainHand();

        if (!plugin.isEnabledFor(player.getUniqueId()))
            return;
        if (!isTreeMaterial(start.getType()))
            return;
        if (customBlocks.isCustomBlock(start))
            return; // the owning plugin handles its own block
        if (!isAxe(tool))
            return;
        if (!hasMinDurability(tool))
            return;
        if (!sneakModeAllows(player.isSneaking()))
            return;

        boolean hasTimberPermission = player.hasPermission("timberella.use");

        if (!hasTimberPermission || !timberEnabled) {
            // Sequential felling won't happen either way, so skip the tree search (up
            // to 1024 blocks x 26 neighbor lookups) instead of doing it and discarding
            // the result.
            List<Block> single = new ArrayList<>(Collections.singletonList(start));
            handlePostActions(player, single, singleBlock(start), false, captureOriginalMaterials(single));
            return;
        }

        if (activeFellings.containsKey(player.getUniqueId())) {
            // No second felling task per player; this block just breaks the vanilla way.
            sendFellingAlreadyRunningActionbar(player);
            return;
        }

        TreeSelection tree = requireNaturalLeaves && GIANT_MUSHROOM_BLOCKS.contains(start.getType())
                ? collectGiantMushroom(start)
                : collectTree(start);
        if (tree == null) {
            // Most likely a build: break only this block.
            List<Block> single = new ArrayList<>(Collections.singletonList(start));
            handlePostActions(player, single, singleBlock(start), false, captureOriginalMaterials(single));
            return;
        }
        List<Block> sequence = tree.blocks();
        final Map<Long, Material> originalMaterials = captureOriginalMaterials(sequence);

        try {
            var loc = start.getLocation().add(0.5, 0.5, 0.5);
            start.getWorld().spawnParticle(Particle.SWEEP_ATTACK, loc, 1, 0, 0, 0, 0);
        } catch (Exception ignored) {
            // Cosmetic effect only; not worth failing the felling action over.
        }
        if (sequence.size() <= 1) {
            handlePostActions(player, sequence, tree, false, originalMaterials);
            return;
        }

        UUID fellingId = markToolForFelling(tool);
        if (fellingId == null) {
            // Tool couldn't be tagged; fall back to safe behavior (no extra durability, no
            // overwrites)
            handlePostActions(player, sequence, tree, false, originalMaterials);
            return;
        }

        // Only the clicked block went through vanilla's own BlockBreakEvent; the
        // Felling task fires one per further log so protection plugins can cancel it.
        Felling felling = new Felling(player, fellingId, tool, tree, originalMaterials);
        activeFellings.put(player.getUniqueId(), felling);
        felling.runTaskTimer(plugin, breakIntervalTicks, breakIntervalTicks);
    }

    /**
     * Connected logs of the start block's species within its limits. With
     * require_natural_leaves on, builds touching the tree stay out. Null means no
     * species was found, the start block belongs to a build, or the logs carry no
     * grown foliage.
     */
    private TreeSelection collectTree(Block start) {
        Species species = detectSpecies(start);
        // Without a species of its own or next to it, only the hit block breaks.
        if (species == null)
            return null;
        List<Block> collected = collectConnectedLogs(start, species,
                type -> belongsToTree(type, species));
        if (collected.isEmpty()) {
            collected = Collections.singletonList(start);
        }
        World world = start.getWorld();
        Map<TreeShape.Pos, Block> blocks = positionsOf(collected);
        TreeShape.Result shape = TreeShape.analyze(List.copyOf(blocks.keySet()),
                surroundings(world, blocks, species, Set.of()), includeDiagonals, requireNaturalLeaves);
        if (shape == null)
            return null;
        List<Block> sequence = shape.kept().stream().map(blocks::get).toList();
        if (requireNaturalLeaves && sequence.size() > 1
                && !hasNaturalFoliage(sequence, keysOf(world, shape.excluded()))) {
            return null;
        }
        List<Block> plantingSpots = shape.plantingSpots().stream().map(pos -> blockAt(world, pos)).toList();
        return new TreeSelection(sequence, plantingSpots, keysOf(world, shape.builds()),
                keysOf(world, blocks.keySet()));
    }

    private static Map<TreeShape.Pos, Block> positionsOf(List<Block> blocks) {
        Map<TreeShape.Pos, Block> positions = new LinkedHashMap<>();
        for (Block block : blocks) {
            positions.put(new TreeShape.Pos(block.getX(), block.getY(), block.getZ()), block);
        }
        return positions;
    }

    // The world around collected blocks of one species, for TreeShape. Leaves that
    // touch one of the ignored blocks never count as a tree's own foliage.
    private TreeShape.Surroundings surroundings(World world, Map<TreeShape.Pos, Block> blocks, Species species,
            Set<Long> ignoredFoliage) {
        return new TreeShape.Surroundings() {
            @Override
            public TreeShape.Part part(TreeShape.Pos pos) {
                return partOf(blocks.get(pos).getType());
            }

            @Override
            public TreeShape.Support below(TreeShape.Pos pos) {
                return supportAt(blockAt(world, pos), species);
            }

            @Override
            public boolean isNaturalLeaf(TreeShape.Pos pos) {
                Block block = blockAt(world, pos);
                return isVanillaLeafName(block.getType()) && isNaturalFoliage(block, null);
            }

            @Override
            public TreeShape.Footprint footprint(TreeShape.Pos log) {
                return footprintOf(MATERIAL_TO_SPECIES.get(blocks.get(log).getType()));
            }

            @Override
            public boolean isTree(List<TreeShape.Pos> tree, Set<TreeShape.Pos> standing) {
                Set<Long> ignored = keysOf(world, standing);
                ignored.addAll(ignoredFoliage);
                return hasNaturalFoliage(tree.stream().map(blocks::get).toList(), ignored);
            }
        };
    }

    private static Block blockAt(World world, TreeShape.Pos pos) {
        return world.getBlockAt(pos.x(), pos.y(), pos.z());
    }

    private Set<Long> keysOf(World world, Set<TreeShape.Pos> positions) {
        Set<Long> keys = new HashSet<>(positions.size());
        for (TreeShape.Pos pos : positions) {
            keys.add(key(world, pos.x(), pos.y(), pos.z()));
        }
        return keys;
    }

    private TreeShape.Part partOf(Material type) {
        if (MANGROVE_ROOT_BLOCKS.contains(type))
            return TreeShape.Part.ROOT;
        return additions.contains(type) ? TreeShape.Part.ATTACHMENT : TreeShape.Part.LOG;
    }

    // What a column of collected logs stands on; the block itself wasn't collected.
    private TreeShape.Support supportAt(Block block, Species species) {
        Material type = block.getType();
        if (MANGROVE_ROOT_BLOCKS.contains(type))
            return TreeShape.Support.ROOT;
        if (belongsToTree(type, species) && !customBlocks.isCustomBlock(block))
            return TreeShape.Support.TREE;
        if (isVanillaLeafName(type) || mappedFoliage.contains(type) || !block.isSolid())
            return TreeShape.Support.NONE;
        return isTrunkSoil(type) ? TreeShape.Support.SOIL : TreeShape.Support.GROUND;
    }

    // What a grown trunk stands on: growing turns grass and mycelium under it into
    // dirt, and fungi grow on nylium. Package-private for TrunkSoilTest.
    static boolean isTrunkSoil(Material type) {
        return TRUNK_SOILS.contains(type);
    }

    private static TreeShape.Footprint footprintOf(Species species) {
        if (species == null)
            return TreeShape.Footprint.SINGLE;
        return switch (species) {
            case SPRUCE, JUNGLE, DARK_OAK, PALE_OAK -> TreeShape.Footprint.SQUARE;
            case CRIMSON, WARPED -> TreeShape.Footprint.FUNGUS;
            default -> TreeShape.Footprint.SINGLE;
        };
    }

    /**
     * Stem and cap of the giant mushroom the start block belongs to, stem first;
     * null if the mushroom blocks around it have any other shape. Only the cap on
     * top of that stem is felled, never a neighbor's.
     */
    private TreeSelection collectGiantMushroom(Block start) {
        World world = start.getWorld();
        GiantMushroomShape.Mushroom mushroom = GiantMushroomShape.find(start.getX(), start.getY(), start.getZ(),
                (x, y, z) -> {
                    Block block = world.getBlockAt(x, y, z);
                    return block.getType() == Material.MUSHROOM_STEM && !customBlocks.isCustomBlock(block);
                }, (x, y, z) -> capAt(world.getBlockAt(x, y, z)), this::capReach, maxBlocks);
        if (mushroom == null)
            return null;
        GiantMushroomShape.Column column = mushroom.column();
        List<Block> sequence = new ArrayList<>();
        sequence.add(start);
        for (int y = column.top(); y >= column.bottom(); y--) {
            addUnlessStart(sequence, world.getBlockAt(column.x(), y, column.z()), start);
        }
        for (GiantMushroomShape.Pos pos : mushroom.cap()) {
            addUnlessStart(sequence, world.getBlockAt(pos.x(), pos.y(), pos.z()), start);
        }
        SpeciesLimit limit = speciesLimits.get(capSpecies(mushroom.kind()));
        int limitBlocks = limit != null && limit.enabled && limit.maxBlocks > 0
                ? Math.min(limit.maxBlocks, maxBlocks)
                : maxBlocks;
        List<Block> felled = sequence.size() > limitBlocks ? sequence.subList(0, limitBlocks) : sequence;
        return new TreeSelection(felled, List.of(), Set.of(), keysOf(felled));
    }

    private static void addUnlessStart(List<Block> sequence, Block block, Block start) {
        if (!block.equals(start)) {
            sequence.add(block);
        }
    }

    private GiantMushroomShape.Cap capAt(Block block) {
        GiantMushroomShape.Cap cap = switch (block.getType()) {
            case BROWN_MUSHROOM_BLOCK -> GiantMushroomShape.Cap.BROWN;
            case RED_MUSHROOM_BLOCK -> GiantMushroomShape.Cap.RED;
            default -> null;
        };
        return cap != null && !customBlocks.isCustomBlock(block) ? cap : null;
    }

    // The vanilla cap size, narrowed by the species' horizontal radius if set.
    private int capReach(GiantMushroomShape.Cap cap) {
        SpeciesLimit limit = speciesLimits.get(capSpecies(cap));
        return limit != null && limit.enabled && limit.maxHorizontalRadius > 0
                ? Math.min(cap.radius, limit.maxHorizontalRadius)
                : cap.radius;
    }

    private static Species capSpecies(GiantMushroomShape.Cap cap) {
        return cap == GiantMushroomShape.Cap.RED ? Species.MUSHROOM_RED : Species.MUSHROOM_BROWN;
    }

    // Inventory changes made here still reach the player's saved data, so the
    // running felling is settled now rather than after the quit.
    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        lastFellingActionbarAt.remove(event.getPlayer().getUniqueId());
        Felling felling = activeFellings.get(event.getPlayer().getUniqueId());
        if (felling != null) {
            felling.stopOnQuit();
        }
    }

    /**
     * One running sequential felling. Stops early if the tagged axe leaves the
     * player's inventory, the player changes worlds, or the player quits.
     */
    private final class Felling extends BukkitRunnable {
        private final Player player;
        private final UUID fellingId;
        private final ItemStack usedTool;
        private final World startWorld;
        private final List<Block> toBreak;
        private final List<Block> brokenLogs;
        private final TreeSelection tree;
        private final Map<Long, Material> originalMaterials;
        private int idx = 0;
        private int chargedExtra = 0;

        Felling(Player player, UUID fellingId, ItemStack usedTool, TreeSelection tree,
                Map<Long, Material> originalMaterials) {
            List<Block> sequence = tree.blocks();
            this.player = player;
            this.fellingId = fellingId;
            this.usedTool = usedTool;
            this.startWorld = player.getWorld();
            this.toBreak = new ArrayList<>(sequence.subList(1, sequence.size()));
            this.brokenLogs = new ArrayList<>(sequence.size());
            this.brokenLogs.add(sequence.get(0));
            this.tree = tree;
            this.originalMaterials = originalMaterials;
        }

        @Override
        public void run() {
            if (!player.isOnline()) {
                stopOnQuit();
                return;
            }
            if (idx >= toBreak.size()) {
                finish(null);
                return;
            }
            if (!player.getWorld().equals(startWorld)) {
                finish("ui.felling_stopped_world_changed");
                return;
            }
            if (findToolByFellingId(player, fellingId) == null) {
                finish("ui.felling_stopped_axe_missing");
                return;
            }
            chargeDurability();
            Block b = toBreak.get(idx++);
            if (!isTreeMaterial(b.getType()))
                return;
            // Vanilla's spawn protection and world border fire no event, so they are
            // checked here; such a block stays and costs nothing, like a denied one.
            if (!mayInteract(player, b))
                return;
            BlockBreakEvent breakEvent = breakEventGuard.fire(() -> fired(new TimberBlockBreakEvent(b, player)));
            if (breakEvent == null || breakEvent.isCancelled()) {
                // A protection plugin denied this block, or Paper's event could not be
                // fired; it stays and costs nothing.
                return;
            }
            // Creative mode breaks without drops or statistics, like vanilla.
            if (player.getGameMode() == GameMode.CREATIVE) {
                BeeNests.beforeBreak(b, player, usedTool, true);
                b.setType(Material.AIR);
            } else {
                breakForPlayer(b, player, usedTool, breakEvent);
            }
            brokenLogs.add(b);
            chargeDurability();
        }

        // Completes normally (stopKey null) or stops early with an actionbar note;
        // either way the logs broken so far get leaf cleanup and replanting.
        private void finish(String stopKey) {
            chargeDurability();
            end();
            handlePostActions(player, brokenLogs, tree, true, originalMaterials);
            if (stopKey != null) {
                sendFellingStoppedActionbar(player, stopKey);
            }
        }

        // Replanting needs the player online, so only the leaves are cleaned up.
        void stopOnQuit() {
            chargeDurability();
            end();
            handlePostActions(player, brokenLogs, tree, false, originalMaterials);
        }

        private void end() {
            cancel();
            activeFellings.remove(player.getUniqueId(), this);
            clearToolFellingTag(player, fellingId);
        }

        // Books the cost of the logs broken so far onto the tagged axe, as the
        // difference to what is already booked (durability_mode: all only). Points
        // Unbreaking saved count as booked, so they are not rolled again.
        private void chargeDurability() {
            if (!durabilityModeAll)
                return;
            int due = extraDurabilityCost(brokenLogs.size(), durabilityMultiplier) - chargedExtra;
            if (due > 0) {
                chargedExtra += applyDurabilityCostForTaggedTool(player, fellingId, due);
            }
        }
    }

    private boolean sneakModeAllows(boolean sneaking) {
        switch (sneakMode) {
            case 0 :
                return sneaking; // only when sneaking
            case 1 :
                return !sneaking; // only when not sneaking
            case 2 :
                return true; // always
            default :
                return sneaking; // fallback to 0
        }
    }

    private boolean isAxe(ItemStack stack) {
        if (stack == null)
            return false;
        Material type = stack.getType();
        // If config provides allowed axes, use it; fallback to the vanilla axes tag so
        // new/modded axe materials (e.g. copper) are covered without a hardcoded list
        if (!allowedAxes.isEmpty())
            return allowedAxes.contains(type);
        return Tag.ITEMS_AXES.isTagged(type);
    }

    private boolean hasMinDurability(ItemStack stack) {
        if (stack == null)
            return false;
        var meta = stack.getItemMeta();
        if (!(meta instanceof Damageable damageable) || meta.isUnbreakable())
            return true;
        int max = maxDurability(stack.getType().getMaxDurability(), damageable);
        if (max <= 0)
            return true; // not damageable
        return max - damageable.getDamage() >= minRemainingDurability;
    }

    // An item's own max_damage component wins over its type's durability.
    // Package-private for DurabilityCostTest.
    static int maxDurability(int typeDurability, Damageable meta) {
        return meta != null && meta.hasMaxDamage() ? meta.getMaxDamage() : typeDurability;
    }

    private boolean isTreeMaterial(Material m) {
        return m != null && allTreeMaterials.contains(m);
    }

    /** Block types by position; custom blocks read as air. */
    interface TypeLookup {
        Material typeAt(int x, int y, int z);
    }

    private Species detectSpecies(Block block) {
        World world = block.getWorld();
        return detectSpecies(block.getX(), block.getY(), block.getZ(), world.getMaxHeight(), includeDiagonals,
                (x, y, z) -> {
                    Block at = world.getBlockAt(x, y, z);
                    return customBlocks.isCustomBlock(at) ? Material.AIR : at.getType();
                }, this::isTreeMaterial);
    }

    // A block's own species, a mushroom stem's from its cap, and for bee nests,
    // creaking hearts or fences that of a tree block next to them; null if none.
    // Package-private for SpeciesDetectionTest.
    static Species detectSpecies(int x, int y, int z, int maxY, boolean diagonals, TypeLookup types,
            Predicate<Material> treeMaterial) {
        Material type = types.typeAt(x, y, z);
        if (MATERIAL_TO_SPECIES.containsKey(type) || type == Material.MUSHROOM_STEM) {
            return ownSpecies(x, y, z, maxY, types);
        }
        for (int pass = 0; pass <= (diagonals ? 1 : 0); pass++) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        int axes = Math.abs(dx) + Math.abs(dy) + Math.abs(dz);
                        if (axes == 0 || (pass == 0) != (axes == 1)
                                || !treeMaterial.test(types.typeAt(x + dx, y + dy, z + dz)))
                            continue;
                        Species species = ownSpecies(x + dx, y + dy, z + dz, maxY, types);
                        if (species != null)
                            return species;
                    }
                }
            }
        }
        return null;
    }

    private static Species ownSpecies(int x, int y, int z, int maxY, TypeLookup types) {
        Material type = types.typeAt(x, y, z);
        return type == Material.MUSHROOM_STEM ? capOfStem(x, y, z, maxY, types) : MATERIAL_TO_SPECIES.get(type);
    }

    // The cap sits on the stem's top, however tall vanilla grew it (up to twelve).
    private static Species capOfStem(int x, int y, int z, int maxY, TypeLookup types) {
        int top = y;
        while (top + 1 < maxY && types.typeAt(x, top + 1, z) == Material.MUSHROOM_STEM) {
            top++;
        }
        Species above = capSpecies(types.typeAt(x, top + 1, z));
        if (above != null)
            return above;
        for (int dy = -4; dy <= 1; dy++) {
            for (int dx = -3; dx <= 3; dx++) {
                for (int dz = -3; dz <= 3; dz++) {
                    Species species = capSpecies(types.typeAt(x + dx, top + dy, z + dz));
                    if (species != null)
                        return species;
                }
            }
        }
        return null;
    }

    private static Species capSpecies(Material type) {
        Species species = MATERIAL_TO_SPECIES.get(type);
        return species == Species.MUSHROOM_BROWN || species == Species.MUSHROOM_RED ? species : null;
    }

    // Connected blocks the joins test accepts, within the species' block and radius
    // limits around the start.
    private List<Block> collectConnectedLogs(Block start, Species species, Predicate<Material> joins) {
        int treeMaxBlocks = maxBlocks;
        int horizontal = -1;
        int vertical = -1;
        SpeciesLimit limit = species != null ? speciesLimits.get(species) : null;
        if (limit != null && limit.enabled) {
            if (limit.maxBlocks > 0 && limit.maxBlocks < maxBlocks) {
                treeMaxBlocks = limit.maxBlocks;
            }
            horizontal = limit.maxHorizontalRadius > 0 ? limit.maxHorizontalRadius : -1;
            vertical = limit.maxVerticalRadius > 0 ? limit.maxVerticalRadius : -1;
        }
        final boolean limitRadius = horizontal > 0 || vertical > 0;
        final int maxHorizontalRadius = horizontal;
        final int maxVerticalRadius = vertical;
        return collectConnected(start, treeMaxBlocks, includeDiagonals,
                b -> joins.test(b.getType()) && !customBlocks.isCustomBlock(b)
                        && (!limitRadius || withinRadius(start.getX(), start.getY(), start.getZ(), b,
                                maxHorizontalRadius, maxVerticalRadius)),
                this::key);
    }

    /**
     * Breadth-first search from start over blocks accepted by joins; a rejected
     * block is neither collected nor connects anything behind it. Package-private
     * for TreeCollectionTest.
     */
    static List<Block> collectConnected(Block start, int maxBlocks, boolean includeDiagonals,
            Predicate<Block> joins, ToLongFunction<Block> key) {
        if (start == null) {
            return Collections.emptyList();
        }
        Queue<Block> queue = new ArrayDeque<>();
        Set<Long> visited = new HashSet<>();
        List<Block> result = new ArrayList<>();
        queue.add(start);
        visited.add(key.applyAsLong(start));
        while (!queue.isEmpty() && result.size() < maxBlocks) {
            Block b = queue.poll();
            if (!joins.test(b))
                continue;
            result.add(b);
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        int axes = Math.abs(dx) + Math.abs(dy) + Math.abs(dz);
                        if (axes == 0 || (!includeDiagonals && axes > 1))
                            continue;
                        Block n = b.getRelative(dx, dy, dz);
                        if (joins.test(n) && visited.add(key.applyAsLong(n)))
                            queue.add(n);
                    }
                }
            }
        }
        return result;
    }

    /**
     * Whether a block may join a felling started on the given species. Additions
     * and fences join any tree; giant mushroom stems join their cap's species.
     */
    private boolean belongsToTree(Material material, Species species) {
        if (!isTreeMaterial(material))
            return false;
        if (species == null || additions.contains(material) || fences.contains(material))
            return true;
        if (MATERIAL_TO_SPECIES.get(material) == species)
            return true;
        return material == Material.MUSHROOM_STEM
                && (species == Species.MUSHROOM_BROWN || species == Species.MUSHROOM_RED);
    }

    /**
     * Whether the logs carry enough grown foliage to look like a tree rather than a
     * build. Player-placed leaves never count, nor do leaves touching an excluded
     * block, as they may belong to the build or tree next to it.
     */
    private boolean hasNaturalFoliage(List<Block> logs, Set<Long> excluded) {
        Set<Long> counted = new HashSet<>();
        // Collection order runs outward from the hit block, so the crown comes last.
        for (int i = logs.size() - 1; i >= 0; i--) {
            Block log = logs.get(i);
            Set<Material> foliage = leafMappings.get(log.getType());
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        Block n = log.getRelative(dx, dy, dz);
                        if (!isNaturalFoliage(n, foliage) || touchesAny(n, excluded))
                            continue;
                        if (counted.add(key(n)) && counted.size() >= NATURAL_FOLIAGE_MIN_COUNT)
                            return true;
                    }
                }
            }
        }
        return false;
    }

    private boolean touchesAny(Block block, Set<Long> keys) {
        if (keys.isEmpty())
            return false;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (keys.contains(key(block.getWorld(), block.getX() + dx, block.getY() + dy, block.getZ() + dz)))
                        return true;
                }
            }
        }
        return false;
    }

    /**
     * Grown leaves of any kind count (azalea leaves on oak logs, too); foliage
     * without a persistence flag (wart blocks, shroomlights, mushroom caps) only
     * counts when leaf_mappings.yml lists it for this log.
     */
    private boolean isNaturalFoliage(Block block, Set<Material> mappedFoliage) {
        if (customBlocks.isCustomBlock(block))
            return false;
        Material type = block.getType();
        if (isVanillaLeafName(type)) {
            return block.getBlockData() instanceof Leaves leaves && !leaves.isPersistent();
        }
        return mappedFoliage != null && mappedFoliage.contains(type);
    }

    // Leaf cleanup for the broken logs, where builds hold no leaves, and if asked
    // for, a replant at the tree's planting spots.
    private void handlePostActions(Player player, List<Block> logs, TreeSelection tree, boolean replant,
            Map<Long, Material> originalMaterials) {
        if (logs == null || logs.isEmpty())
            return;
        ReplantPlan plan = replant && replantEnabled
                ? planReplant(tree.plantingSpots(), originalMaterials, UnderwaterPlanting.forPlayer(player),
                        azaleaFor(logs, originalMaterials))
                : null;
        // Nothing drops in creative mode, so the sapling is planted for free there.
        boolean fromDrops = replantFromDrops && player.getGameMode() != GameMode.CREATIVE;
        SaplingPool pool = plan != null && fromDrops
                ? new SaplingPool(AZALEAS.contains(plan.sapling()) ? AZALEAS : Set.of(plan.sapling()),
                        plan.targets().size())
                : null;
        if (leavesDecayEnabled) {
            scheduleLeavesDecay(player, logs, new LeafHolders(tree, logs), originalMaterials, plan, pool);
        }
        if (plan != null && !fromDrops) {
            UUID playerId = player.getUniqueId();
            plantOnceWaterSettled(plan, playerId, 2L, () -> plantSaplings(plan, plan.targets().size(), playerId));
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onItemSpawn(ItemSpawnEvent event) {
        SaplingPool pool = capturingPool;
        if (pool == null)
            return;
        ItemStack stack = event.getEntity().getItemStack();
        int kept = pool.reserve(stack.getType(), stack.getAmount());
        if (kept == 0)
            return;
        if (kept >= stack.getAmount()) {
            event.setCancelled(true);
            return;
        }
        stack.setAmount(stack.getAmount() - kept);
        event.getEntity().setItemStack(stack);
    }

    private void scheduleLeavesDecay(Player player, List<Block> logs, LeafHolders holders,
            Map<Long, Material> originalMaterials,
            ReplantPlan plan, SaplingPool pool) {
        if (!leavesDecayEnabled)
            return;
        if (leavesDecayRadius <= 0)
            return;
        // A giant mushroom's cap is felled with its stem; any other mushroom blocks
        // nearby belong to a neighbor or a build.
        logs = logs.stream()
                .filter(log -> !GIANT_MUSHROOM_BLOCKS.contains(getOriginalMaterial(log, originalMaterials)))
                .toList();
        if (logs.isEmpty())
            return;

        final Set<Material> allowedLeaves = computeAllowedLeaves(logs, originalMaterials);
        if (allowedLeaves != null && allowedLeaves.isEmpty()) {
            return;
        }
        final int foliageMaxDistanceSquared = resolveFoliageMaxDistanceSquared(logs, originalMaterials);
        final Deque<LeafEntry> queue = new ArrayDeque<>();
        final Set<Long> visited = new HashSet<>();
        final int maxDepth = leavesDecayRadius;
        final List<Block> felledLogs = List.copyOf(logs);
        // Bucket log positions by a grid cell sized to the search radius, so each
        // leaf candidate only checks its own cell plus the 26 neighbors instead of
        // every felled log.
        final int leafSearchCellSize = Math.max(1, (int) Math.ceil(Math.sqrt(foliageMaxDistanceSquared)));
        final FoliageOwners owners = new FoliageOwners(felledLogs, originalMaterials, holders, leafSearchCellSize);
        final Map<Long, List<Block>> felledLogsByCell = new HashMap<>();
        for (Block log : felledLogs) {
            felledLogsByCell.computeIfAbsent(leafSearchCellKey(log, leafSearchCellSize), k -> new ArrayList<>())
                    .add(log);
        }
        for (Block log : felledLogs) {
            seedLeafNeighbors(log, queue, visited, maxDepth, allowedLeaves, felledLogsByCell, leafSearchCellSize,
                    foliageMaxDistanceSquared);
        }
        // A fungus' outer cap is queued at the full depth: it is taken itself, but
        // the cleanup never spreads from it to a neighbor.
        World world = felledLogs.get(0).getWorld();
        for (TreeShape.Pos rim : capRim(felledLogs, originalMaterials)) {
            enqueueLeaf(world.getBlockAt(rim.x(), rim.y(), rim.z()), maxDepth, maxDepth, queue, visited,
                    allowedLeaves, felledLogsByCell, leafSearchCellSize, foliageMaxDistanceSquared);
        }
        if (queue.isEmpty())
            return;
        final int batchSize = leavesDecayBatchSize;
        final long interval = leavesDecayIntervalTicks;
        final Player sourcePlayer = player;

        new BukkitRunnable() {
            @Override
            public void run() {
                int processed = 0;
                // Bounded by the queue size at the start, so leaves added below wait for
                // the next run instead of growing this one.
                for (int i = 0, pending = queue.size(); i < pending && processed < batchSize; i++) {
                    LeafEntry entry = queue.poll();
                    if (entry == null)
                        break;
                    Block b = entry.block();
                    int depth = entry.depth();
                    if (!isDecayCandidate(b.getType(), allowedLeaves)
                            || !isAllowedLeaf(b.getType(), allowedLeaves)) {
                        continue;
                    }
                    if (b.getBlockData() instanceof Leaves leaves) {
                        // Only take leaves vanilla would let decay too: never player-placed
                        // ones, and none still held by a standing log (e.g. a neighbor tree),
                        // leaving out builds.
                        if (leaves.isPersistent())
                            continue;
                        if (leaves.getDistance() < VANILLA_LEAF_DECAY_DISTANCE && isHeldByStandingLog(b, holders))
                            continue;
                    } else if (owners.keepsForStandingLog(b)) {
                        // Neither taken nor spread through, so a neighbor fungus keeps its cap.
                        continue;
                    }

                    boolean allowDrops = true;
                    if (sourcePlayer != null) {
                        if (!mayInteract(sourcePlayer, b))
                            continue;
                        BlockBreakEvent leafEvent = breakEventGuard
                                .fire(() -> fired(new TimberBlockBreakEvent(b, sourcePlayer)));
                        if (leafEvent == null || leafEvent.isCancelled()) {
                            continue;
                        }
                        allowDrops = leafEvent.isDropItems() && sourcePlayer.getGameMode() != GameMode.CREATIVE;
                    }

                    if (allowDrops) {
                        // Drops spawn synchronously here, a hanging propagule below included.
                        capturingPool = pool;
                        try {
                            b.breakNaturally();
                        } finally {
                            capturingPool = null;
                        }
                    } else {
                        b.setType(Material.AIR);
                    }

                    int nextDepth = depth + 1;
                    if (nextDepth <= maxDepth) {
                        if (includeDiagonals) {
                            for (int dx = -1; dx <= 1; dx++) {
                                for (int dy = -1; dy <= 1; dy++) {
                                    for (int dz = -1; dz <= 1; dz++) {
                                        if (dx == 0 && dy == 0 && dz == 0)
                                            continue;
                                        Block n = b.getRelative(dx, dy, dz);
                                        enqueueLeaf(n, nextDepth, maxDepth, queue, visited, allowedLeaves,
                                                felledLogsByCell, leafSearchCellSize, foliageMaxDistanceSquared);
                                    }
                                }
                            }
                        } else {
                            int[][] dirs = {
                                    {1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}
                            };
                            for (int[] d : dirs) {
                                Block n = b.getRelative(d[0], d[1], d[2]);
                                enqueueLeaf(n, nextDepth, maxDepth, queue, visited, allowedLeaves,
                                        felledLogsByCell, leafSearchCellSize, foliageMaxDistanceSquared);
                            }
                        }
                    }
                    processed++;
                }
                if (queue.isEmpty()) {
                    cancel();
                    if (pool != null) {
                        UUID playerId = player.getUniqueId();
                        plantOnceWaterSettled(plan, playerId, 0L, () -> plantFromPool(plan, pool, playerId));
                    }
                }
            }
        }.runTaskTimer(plugin, 0L, interval);
    }

    // Which logs hold leaves after a felling: none of the builds left standing, the
    // other collected ones (a neighboring tree, logs a protection plugin kept), and
    // any other log only if a hit would fell it as a tree of its own.
    private final class LeafHolders {
        private final Set<Long> builds;
        private final Set<Long> collected;
        private final Set<Long> felled;
        private final Map<Long, Boolean> trees = new HashMap<>();

        LeafHolders(TreeSelection tree, List<Block> felledLogs) {
            this.builds = tree.builds();
            this.collected = tree.collected();
            this.felled = keysOf(felledLogs);
        }

        boolean isBuild(Block log) {
            return builds.contains(key(log));
        }

        boolean holds(Block log) {
            long key = key(log);
            if (builds.contains(key))
                return false;
            if (collected.contains(key))
                return true;
            if (!trees.containsKey(key))
                classify(log);
            return trees.getOrDefault(key, true);
        }

        // Classifies every log of the structure this one belongs to, within its
        // species' limits; logs of no known species hold leaves as in vanilla.
        private void classify(Block log) {
            Species species = MATERIAL_TO_SPECIES.get(log.getType());
            List<Block> structure = species == null || customBlocks.isCustomBlock(log)
                    ? List.of()
                    : collectConnectedLogs(log, species, type -> MATERIAL_TO_SPECIES.get(type) == species);
            if (structure.isEmpty()) {
                trees.put(key(log), true);
                return;
            }
            Map<TreeShape.Pos, Block> blocks = positionsOf(structure);
            Set<TreeShape.Pos> standing = TreeShape.standingTrees(List.copyOf(blocks.keySet()),
                    surroundings(log.getWorld(), blocks, species, felled), includeDiagonals);
            blocks.forEach((pos, block) -> trees.put(key(block), standing.contains(pos)));
        }
    }

    // Wart blocks, shroomlights and vines carry no marker of the stem they grew
    // from, so each belongs to the nearest log whose leaf mapping lists it; one a
    // standing log other than a build beside the felled tree is as near to stays.
    private final class FoliageOwners {
        private final List<Block> felled;
        private final Map<Long, Material> originals;
        private final LeafHolders holders;
        private final int reach;
        private final Map<Material, List<TreeShape.Pos>> felledOwners = new EnumMap<>(Material.class);
        private final Map<Material, List<TreeShape.Pos>> standingOwners = new EnumMap<>(Material.class);
        private List<Block> standingLogs;

        FoliageOwners(List<Block> felled, Map<Long, Material> originals, LeafHolders holders, int reach) {
            this.felled = felled;
            this.originals = originals;
            this.holders = holders;
            this.reach = reach;
        }

        boolean keepsForStandingLog(Block foliage) {
            Material type = foliage.getType();
            List<TreeShape.Pos> standing = standingOwners.computeIfAbsent(type, this::standingOwnersOf);
            if (standing.isEmpty())
                return false;
            return keptByStandingLog(new TreeShape.Pos(foliage.getX(), foliage.getY(), foliage.getZ()),
                    felledOwners.computeIfAbsent(type, this::felledOwnersOf), standing);
        }

        private List<TreeShape.Pos> felledOwnersOf(Material foliage) {
            List<TreeShape.Pos> owners = new ArrayList<>();
            for (Block log : felled) {
                if (maps(getOriginalMaterial(log, originals), foliage))
                    owners.add(new TreeShape.Pos(log.getX(), log.getY(), log.getZ()));
            }
            return owners;
        }

        private List<TreeShape.Pos> standingOwnersOf(Material foliage) {
            if (standingLogs == null)
                standingLogs = findStandingLogs();
            List<TreeShape.Pos> owners = new ArrayList<>();
            for (Block log : standingLogs) {
                if (maps(log.getType(), foliage) && !holders.isBuild(log))
                    owners.add(new TreeShape.Pos(log.getX(), log.getY(), log.getZ()));
            }
            return owners;
        }

        private boolean maps(Material log, Material foliage) {
            Set<Material> mapped = leafMappings.get(log);
            return mapped != null && mapped.contains(foliage);
        }

        // Foliage within reach of a felled log is only nearer to a standing log
        // within twice that reach of it.
        private List<Block> findStandingLogs() {
            World world = felled.get(0).getWorld();
            int minX = Integer.MAX_VALUE;
            int minY = Integer.MAX_VALUE;
            int minZ = Integer.MAX_VALUE;
            int maxX = Integer.MIN_VALUE;
            int maxY = Integer.MIN_VALUE;
            int maxZ = Integer.MIN_VALUE;
            for (Block log : felled) {
                minX = Math.min(minX, log.getX());
                minY = Math.min(minY, log.getY());
                minZ = Math.min(minZ, log.getZ());
                maxX = Math.max(maxX, log.getX());
                maxY = Math.max(maxY, log.getY());
                maxZ = Math.max(maxZ, log.getZ());
            }
            int span = 2 * reach;
            int bottom = Math.max(world.getMinHeight(), minY - span);
            int top = Math.min(world.getMaxHeight() - 1, maxY + span);
            List<Block> logs = new ArrayList<>();
            for (int x = minX - span; x <= maxX + span; x++) {
                for (int z = minZ - span; z <= maxZ + span; z++) {
                    for (int y = bottom; y <= top; y++) {
                        Material type = world.getType(x, y, z);
                        if (!Tag.LOGS.isTagged(type) || !leafMappings.containsKey(type))
                            continue;
                        Block log = world.getBlockAt(x, y, z);
                        if (!customBlocks.isCustomBlock(log))
                            logs.add(log);
                    }
                }
            }
            return logs;
        }
    }

    /**
     * Whether foliage stays with a standing log: when one is at least as near as
     * the nearest felled log that could have grown it. Package-private for
     * FoliageOwnerTest.
     */
    static boolean keptByStandingLog(TreeShape.Pos foliage, Collection<TreeShape.Pos> felled,
            Collection<TreeShape.Pos> standing) {
        return !standing.isEmpty() && nearestDistanceSquared(foliage, standing) <= nearestDistanceSquared(foliage,
                felled);
    }

    private static long nearestDistanceSquared(TreeShape.Pos from, Collection<TreeShape.Pos> to) {
        long nearest = Long.MAX_VALUE;
        for (TreeShape.Pos pos : to) {
            long dx = pos.x() - from.x();
            long dy = pos.y() - from.y();
            long dz = pos.z() - from.z();
            nearest = Math.min(nearest, dx * dx + dy * dy + dz * dz);
        }
        return nearest;
    }

    private TreeSelection singleBlock(Block block) {
        return new TreeSelection(List.of(block), List.of(), Set.of(), keysOf(List.of(block)));
    }

    private Set<Long> keysOf(List<Block> blocks) {
        Set<Long> keys = new HashSet<>(blocks.size());
        for (Block block : blocks) {
            keys.add(key(block));
        }
        return keys;
    }

    /** What a block is to vanilla's leaf distance, with the builds told apart. */
    enum LeafNeighbor {
        LOG, BUILD_LOG, LEAF, OTHER
    }

    @FunctionalInterface
    interface LeafNeighborLookup {
        LeafNeighbor at(int x, int y, int z);
    }

    private boolean isHeldByStandingLog(Block leaf, LeafHolders holders) {
        World world = leaf.getWorld();
        return isHeldWithoutBuilds(leaf.getX(), leaf.getY(), leaf.getZ(), (x, y, z) -> {
            Block block = world.getBlockAt(x, y, z);
            Material type = block.getType();
            if (Tag.LOGS.isTagged(type))
                return holders.holds(block) ? LeafNeighbor.LOG : LeafNeighbor.BUILD_LOG;
            return isVanillaLeafName(type) && block.getBlockData() instanceof Leaves
                    ? LeafNeighbor.LEAF
                    : LeafNeighbor.OTHER;
        });
    }

    /**
     * Vanilla's leaf distance without the builds: whether a log other than theirs
     * is at most six steps away through leaves. Package-private for
     * LeafDistanceTest.
     */
    static boolean isHeldWithoutBuilds(int x, int y, int z, LeafNeighborLookup lookup) {
        record Step(int x, int y, int z, int distance) {
        }
        Deque<Step> queue = new ArrayDeque<>(List.of(new Step(x, y, z, 1)));
        Set<List<Integer>> seen = new HashSet<>(List.of(List.of(x, y, z)));
        while (!queue.isEmpty()) {
            Step step = queue.poll();
            for (BlockFace face : LEAF_DISTANCE_FACES) {
                int nx = step.x() + face.getModX();
                int ny = step.y() + face.getModY();
                int nz = step.z() + face.getModZ();
                LeafNeighbor neighbor = lookup.at(nx, ny, nz);
                if (neighbor == LeafNeighbor.LOG)
                    return true;
                if (neighbor == LeafNeighbor.LEAF && step.distance() < VANILLA_LEAF_DECAY_DISTANCE - 1
                        && seen.add(List.of(nx, ny, nz))) {
                    queue.add(new Step(nx, ny, nz, step.distance() + 1));
                }
            }
        }
        return false;
    }

    private Set<TreeShape.Pos> capRim(List<Block> felledLogs, Map<Long, Material> originalMaterials) {
        Map<TreeShape.Pos, Material> stems = new LinkedHashMap<>();
        for (Block log : felledLogs) {
            Material material = getOriginalMaterial(log, originalMaterials);
            if (FUNGUS_CAPS.containsKey(material))
                stems.put(new TreeShape.Pos(log.getX(), log.getY(), log.getZ()), material);
        }
        if (stems.isEmpty())
            return Set.of();
        World world = felledLogs.get(0).getWorld();
        return capRim(stems, (x, y, z) -> {
            Block at = world.getBlockAt(x, y, z);
            return customBlocks.isCustomBlock(at) ? Material.AIR : at.getType();
        });
    }

    // A huge fungus' outer cap may touch nothing else of it: on a short stem its
    // edge hangs down two blocks out, a tall hat's lowest rows reach three blocks
    // out. Package-private for FungusCapRimTest.
    static Set<TreeShape.Pos> capRim(Map<TreeShape.Pos, Material> felledStems, TypeLookup types) {
        Set<TreeShape.Pos> rim = new LinkedHashSet<>();
        felledStems.forEach((stem, material) -> {
            Material cap = FUNGUS_CAPS.get(material);
            if (cap == null)
                return;
            int base = stem.y();
            while (isFelledFungusStem(felledStems, stem.x(), base - 1, stem.z()))
                base--;
            int top = stem.y();
            while (isFelledFungusStem(felledStems, stem.x(), top + 1, stem.z()))
                top++;
            int row = stem.y() - base;
            int height = top - base + 1;
            addCapRim(rim, stem.x(), stem.y(), stem.z(), cap, row, height, types);
            // An extra large fungus grows the corners of its 3x3 stem only here and
            // there; its cap goes by the middle column, so each corner counts as one.
            for (int sx = -1; sx <= 1; sx += 2) {
                for (int sz = -1; sz <= 1; sz += 2) {
                    if (isFelledFungusStem(felledStems, stem.x() + sx, stem.y(), stem.z())
                            && isFelledFungusStem(felledStems, stem.x(), stem.y(), stem.z() + sz)) {
                        addCapRim(rim, stem.x() + sx, stem.y(), stem.z() + sz, cap, row, height, types);
                    }
                }
            }
        });
        return rim;
    }

    private static void addCapRim(Set<TreeShape.Pos> rim, int stemX, int y, int stemZ, Material cap, int row,
            int height, TypeLookup types) {
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                int ring = Math.max(Math.abs(dx), Math.abs(dz));
                int x = stemX + dx;
                int z = stemZ + dz;
                if (ring >= 2 && isCapRim(types.typeAt(x, y, z), cap, ring, row, height))
                    rim.add(new TreeShape.Pos(x, y, z));
            }
        }
    }

    private static boolean isFelledFungusStem(Map<TreeShape.Pos, Material> felledStems, int x, int y, int z) {
        Material material = felledStems.get(new TreeShape.Pos(x, y, z));
        return material != null && FUNGUS_CAPS.containsKey(material);
    }

    // Rows vanilla's huge fungus can fill in that ring, counted from the stem's
    // foot: a hat of up to 5 + height / 3 rows, shroomlights above its three lowest
    // rows, and ring 3 only in the four lowest rows of a hat of nine or more.
    private static boolean isCapRim(Material type, Material cap, int ring, int row, int height) {
        int tallestHat = Math.min(5 + height / 3, height);
        int lowest = height - tallestHat;
        int highest = ring == 2 ? height - 1 : tallestHat >= 9 ? height - 6 : -1;
        if (type == cap)
            return row >= lowest && row <= highest;
        return type == Material.SHROOMLIGHT && row >= lowest + 3 && row <= highest;
    }

    private void seedLeafNeighbors(Block log, Deque<LeafEntry> queue, Set<Long> visited, int maxDepth,
            Set<Material> allowedLeaves, Map<Long, List<Block>> logsByCell, int cellSize,
            int foliageMaxDistanceSquared) {
        if (log == null || maxDepth <= 0)
            return;
        if (includeDiagonals) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dy == 0 && dz == 0)
                            continue;
                        Block candidate = log.getRelative(dx, dy, dz);
                        enqueueLeaf(candidate, 0, maxDepth, queue, visited, allowedLeaves,
                                logsByCell, cellSize, foliageMaxDistanceSquared);
                    }
                }
            }
        } else {
            int[][] dirs = {
                    {1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}
            };
            for (int[] d : dirs) {
                Block candidate = log.getRelative(d[0], d[1], d[2]);
                enqueueLeaf(candidate, 0, maxDepth, queue, visited, allowedLeaves,
                        logsByCell, cellSize, foliageMaxDistanceSquared);
            }
        }
    }

    private void enqueueLeaf(Block block, int depth, int maxDepth, Deque<LeafEntry> queue, Set<Long> visited,
            Set<Material> allowedLeaves, Map<Long, List<Block>> logsByCell, int cellSize,
            int foliageMaxDistanceSquared) {
        if (block == null)
            return;
        if (depth > maxDepth)
            return;
        if (!isDecayCandidate(block.getType(), allowedLeaves))
            return;
        if (!isAllowedLeaf(block.getType(), allowedLeaves))
            return;
        if (!isWithinLeafDistance(logsByCell, cellSize, block, foliageMaxDistanceSquared))
            return;
        if (customBlocks.isCustomBlock(block))
            return;
        long key = key(block);
        if (visited.add(key)) {
            queue.add(new LeafEntry(block, depth));
        }
    }

    private boolean isAllowedLeaf(Material material, Set<Material> allowedLeaves) {
        return allowedLeaves == null || allowedLeaves.contains(material);
    }

    /**
     * Whether a block may be removed by leaves decay. Uses
     * {@code leaf_mappings.yml} when present; otherwise falls back to vanilla leaf
     * materials ({@code *_LEAVES} / {@code *_LEAF}).
     */
    private boolean isDecayCandidate(Material material, Set<Material> allowedLeaves) {
        if (material == null)
            return false;
        if (allowedLeaves != null && !allowedLeaves.isEmpty() && allowedLeaves.contains(material)) {
            return true;
        }
        return isVanillaLeafName(material);
    }

    private static boolean isVanillaLeafName(Material material) {
        String name = material.name();
        return name.endsWith("_LEAVES") || name.endsWith("_LEAF");
    }

    private boolean isWithinLeafDistance(Block origin, Block candidate, int maxDistanceSquared) {
        if (origin == null || candidate == null)
            return true;
        int dx = origin.getX() - candidate.getX();
        int dy = origin.getY() - candidate.getY();
        int dz = origin.getZ() - candidate.getZ();
        return (dx * dx + dy * dy + dz * dz) <= maxDistanceSquared;
    }

    /**
     * Same distance test as above, but only checks felled logs in the candidate's
     * own grid cell and its 26 neighbors, not every felled log.
     */
    private boolean isWithinLeafDistance(Map<Long, List<Block>> logsByCell, int cellSize, Block candidate,
            int maxDistanceSquared) {
        if (candidate == null || logsByCell == null || logsByCell.isEmpty()) {
            return true;
        }
        long cx = Math.floorDiv(candidate.getX(), cellSize);
        long cy = Math.floorDiv(candidate.getY(), cellSize);
        long cz = Math.floorDiv(candidate.getZ(), cellSize);
        for (long dx = -1; dx <= 1; dx++) {
            for (long dy = -1; dy <= 1; dy++) {
                for (long dz = -1; dz <= 1; dz++) {
                    List<Block> bucket = logsByCell.get(key(candidate.getWorld(), cx + dx, cy + dy, cz + dz));
                    if (bucket == null)
                        continue;
                    for (Block log : bucket) {
                        if (isWithinLeafDistance(log, candidate, maxDistanceSquared)) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    private long leafSearchCellKey(Block b, int cellSize) {
        return key(b.getWorld(), Math.floorDiv((long) b.getX(), cellSize), Math.floorDiv((long) b.getY(), cellSize),
                Math.floorDiv((long) b.getZ(), cellSize));
    }

    private int resolveFoliageMaxDistanceSquared(List<Block> logs, Map<Long, Material> originalMaterials) {
        int largestRadius = 0;
        if (logs != null) {
            for (Block log : logs) {
                if (log == null)
                    continue;
                Species species = MATERIAL_TO_SPECIES.get(getOriginalMaterial(log, originalMaterials));
                SpeciesLimit limit = species != null ? speciesLimits.get(species) : null;
                if (limit == null || !limit.enabled)
                    continue;
                largestRadius = Math.max(largestRadius, limit.maxHorizontalRadius);
            }
        }
        int distance = effectiveLeafDistance(leavesDecayMaxDistance, largestRadius);
        return distance * distance;
    }

    /**
     * How far leaf cleanup reaches: the felled species' horizontal radius, at least
     * MIN_LEAF_DISTANCE, capped by leaves_decay.max_distance. Package-private for
     * LeafDistanceTest.
     */
    static int effectiveLeafDistance(int maxDistance, int speciesRadius) {
        return Math.min(maxDistance, Math.max(MIN_LEAF_DISTANCE, speciesRadius));
    }

    // Replants where the trunk base stood: all four spots of a 2x2 trunk, or else
    // the lowest one that can take a sapling.
    private ReplantPlan planReplant(List<Block> plantingSpots, Map<Long, Material> originalMaterials,
            UnderwaterPlanting underwater, Material azalea) {
        Material sapling = null;
        List<Block> targets = new ArrayList<>();
        for (Block spot : plantingSpots) {
            Material mapped = saplingFor(spot, originalMaterials);
            if (mapped == Material.OAK_SAPLING && azalea != null)
                mapped = azalea;
            if (mapped == null || (!allowedSaplings.isEmpty() && !allowedSaplings.contains(mapped)))
                continue;
            if (sapling != null && mapped != sapling)
                continue;
            Block target = plantingTarget(spot, mapped, underwater);
            if (target != null) {
                sapling = mapped;
                targets.add(target);
            }
        }
        if (targets.isEmpty())
            return null;
        if (targets.size() < plantingSpots.size()) {
            targets = List.of(targets.stream()
                    .min(Comparator.comparingInt(Block::getY).thenComparingInt(Block::getX)
                            .thenComparingInt(Block::getZ))
                    .orElseThrow());
        }
        return new ReplantPlan(sapling, List.copyOf(targets));
    }

    // The sapling for the spot's column: its lowest block that maps to one, as a
    // creaking heart at the bottom of a trunk maps to none.
    private Material saplingFor(Block spot, Map<Long, Material> originalMaterials) {
        for (Block block = spot; originalMaterials.containsKey(key(block)); block = block.getRelative(BlockFace.UP)) {
            Material mapped = saplingMappings.get(originalMaterials.get(key(block)));
            if (mapped != null)
                return mapped;
        }
        return null;
    }

    // The spot itself, or where a trunk stood on roots above water or air, the
    // first plantable block below it.
    private Block plantingTarget(Block spot, Material sapling, UnderwaterPlanting underwater) {
        Block target = spot;
        for (int drop = 0; drop <= MAX_REPLANT_DROP; drop++) {
            if (canPlantAt(target, sapling, underwater))
                return target;
            Material type = target.getType();
            if (!type.isAir() && type != Material.WATER)
                return null;
            target = target.getRelative(BlockFace.DOWN);
        }
        return null;
    }

    // UnderwaterTrees holds the flow back from a sapling only with water above
    // it, which a felled trunk's leaves may keep off for a moment; the replant
    // waits for that water, but not longer than MAX_WATER_WAIT_TICKS.
    private void plantOnceWaterSettled(ReplantPlan plan, UUID playerId, long delay, Runnable plant) {
        new BukkitRunnable() {
            private int waited;

            @Override
            public void run() {
                Player player = plugin.getServer().getPlayer(playerId);
                UnderwaterPlanting underwater = player != null ? UnderwaterPlanting.forPlayer(player) : null;
                if (underwater == null || waited++ >= MAX_WATER_WAIT_TICKS || isWaterSettled(plan, underwater)) {
                    cancel();
                    plant.run();
                }
            }
        }.runTaskTimer(plugin, delay, 1L);
    }

    private static boolean isWaterSettled(ReplantPlan plan, UnderwaterPlanting underwater) {
        for (Block target : plan.targets()) {
            List<Material> sides = new ArrayList<>(4);
            for (BlockFace face : List.of(BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST)) {
                sides.add(target.getRelative(face).getType());
            }
            if (!underwater.isSettled(plan.sapling(), target.getRelative(BlockFace.DOWN).getType(), target.getType(),
                    target.getRelative(BlockFace.UP).getType(), sides))
                return false;
        }
        return true;
    }

    /**
     * Plants up to {@code count} saplings of the plan as the felling player and
     * returns how many were planted; none while they're offline. A multi-spot plan
     * (2x2) is planted completely or not at all.
     */
    private int plantSaplings(ReplantPlan plan, int count, UUID playerId) {
        Player player = plugin.getServer().getPlayer(playerId);
        if (player == null)
            return 0;
        UnderwaterPlanting underwater = UnderwaterPlanting.forPlayer(player);
        List<BlockState> placed = new ArrayList<>();
        for (Block target : plan.targets()) {
            if (placed.size() >= count)
                break;
            BlockState previous = placeSapling(target, plan.sapling(), player, underwater);
            if (previous != null) {
                placed.add(previous);
            } else if (plan.targets().size() > 1) {
                placed.forEach(state -> state.update(true, false));
                return 0;
            }
        }
        return placed.size();
    }

    // Plants only once the leaves dropped a sapling for every spot (all four for a
    // 2x2 tree); otherwise, and for any sapling without a free spot, the kept
    // saplings drop as items instead of vanishing.
    private void plantFromPool(ReplantPlan plan, SaplingPool pool, UUID playerId) {
        int collected = pool.collected();
        if (collected <= 0)
            return;
        // An azalea tree may have dropped the other kind of azalea than planned.
        ReplantPlan kept = new ReplantPlan(pool.kept(), plan.targets());
        int planted = collected >= kept.targets().size() ? plantSaplings(kept, collected, playerId) : 0;
        int left = collected - planted;
        if (left > 0) {
            Block spot = kept.targets().get(0);
            spot.getWorld().dropItemNaturally(spot.getLocation().add(0.5, 0.5, 0.5),
                    ItemStack.of(kept.sapling(), left));
        }
    }

    // An azalea tree grows oak logs under azalea leaves: where those outnumber oak
    // leaves around the felled oak logs, an azalea goes back instead of an oak.
    private Material azaleaFor(List<Block> logs, Map<Long, Material> originalMaterials) {
        Set<Long> seen = new HashSet<>();
        int oak = 0;
        int azalea = 0;
        int flowering = 0;
        for (Block log : logs) {
            if (saplingMappings.get(getOriginalMaterial(log, originalMaterials)) != Material.OAK_SAPLING)
                continue;
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        Block leaf = log.getRelative(dx, dy, dz);
                        if (!seen.add(key(leaf)) || !(leaf.getBlockData() instanceof Leaves leaves)
                                || leaves.isPersistent())
                            continue;
                        switch (leaf.getType()) {
                            case OAK_LEAVES -> oak++;
                            case AZALEA_LEAVES -> azalea++;
                            case FLOWERING_AZALEA_LEAVES -> flowering++;
                            default -> {
                            }
                        }
                    }
                }
            }
        }
        return azaleaSapling(oak, azalea, flowering);
    }

    // A flowering azalea if most of the azalea leaves flower. Package-private for
    // SaplingPoolTest.
    static Material azaleaSapling(int oakLeaves, int azaleaLeaves, int floweringLeaves) {
        if (azaleaLeaves + floweringLeaves <= oakLeaves)
            return null;
        return floweringLeaves > azaleaLeaves ? Material.FLOWERING_AZALEA : Material.AZALEA;
    }

    private void initializeSaplingMappings() {
        saplingMappings.putAll(defaultSaplingMappings());
    }

    // Derived from the species registry, so every log material of a species
    // replants that species' sapling. Package-private for SpeciesSaplingTest.
    static Map<Material, Material> defaultSaplingMappings() {
        Map<Material, Material> mappings = new EnumMap<>(Material.class);
        MATERIAL_TO_SPECIES.forEach((material, species) -> {
            if (species.sapling() != null) {
                mappings.put(material, species.sapling());
            }
        });
        return mappings;
    }

    private Map<Long, Material> captureOriginalMaterials(List<Block> logs) {
        if (logs == null || logs.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<Long, Material> snapshot = new HashMap<>(logs.size());
        for (Block log : logs) {
            if (log == null)
                continue;
            snapshot.put(key(log), log.getType());
        }
        return snapshot;
    }

    private Material getOriginalMaterial(Block block, Map<Long, Material> originals) {
        if (block == null || originals == null || originals.isEmpty()) {
            return block != null ? block.getType() : null;
        }
        return originals.getOrDefault(key(block), block.getType());
    }

    // Into air or a replaceable block like grass, where vanilla lets the sapling
    // survive; into water only a mangrove propagule, or with UnderwaterTrees any
    // sapling it would plant there itself, on its own soils.
    private boolean canPlantAt(Block target, Material sapling, UnderwaterPlanting underwater) {
        if (target == null || sapling == null || customBlocks.isCustomBlock(target))
            return false;
        Material current = target.getType();
        BlockData data = sapling.createBlockData();
        if (current == Material.WATER || current == Material.BUBBLE_COLUMN) {
            if (sapling == Material.MANGROVE_PROPAGULE && data instanceof Waterlogged waterlogged) {
                waterlogged.setWaterlogged(true);
                return target.canPlace(waterlogged);
            }
            return underwater != null
                    && underwater.accepts(sapling, target.getRelative(0, -1, 0).getType(), current);
        }
        return (current.isAir() || target.isReplaceable()) && target.canPlace(data);
    }

    // Returns the block's state before planting, or null if nothing was planted.
    private BlockState placeSapling(Block target, Material sapling, Player player, UnderwaterPlanting underwater) {
        if (!canPlantAt(target, sapling, underwater))
            return null;
        Material current = target.getType();
        boolean targetIsWater = current == Material.WATER || current == Material.BUBBLE_COLUMN;
        BlockState previousState = target.getState();
        boolean allowed = false;
        try {
            // Into water without physics, like UnderwaterTrees' own planting; its place
            // listener then tracks the sapling and keeps the water from washing it out.
            target.setType(sapling, !targetIsWater || sapling == Material.MANGROVE_PROPAGULE);
            if (sapling == Material.MANGROVE_PROPAGULE) {
                var data = target.getBlockData();
                if (data instanceof Waterlogged waterlogged) {
                    waterlogged.setWaterlogged(targetIsWater);
                    target.setBlockData(waterlogged);
                }
            }
            // Protection plugins can veto the replant like any placement by this player,
            // and block loggers record it; being allowed to break is not permission to
            // build.
            BlockPlaceEvent placeEvent = placeEventGuard.fire(() -> fired(new BlockPlaceEvent(target, previousState,
                    target.getRelative(BlockFace.DOWN), ItemStack.of(sapling), player,
                    mayInteract(player, target), EquipmentSlot.HAND)));
            allowed = placeEvent != null && !placeEvent.isCancelled() && placeEvent.canBuild();
        } finally {
            // Any failure, even an unexpected exception, takes the sapling out again.
            if (!allowed) {
                previousState.update(true, false);
            }
        }
        return allowed ? previousState : null;
    }

    // Breaks a further log the way the player's own break would: with its effect,
    // the mined-block statistic and drops that pass the drop event first, so
    // pickup and loot plugins can take or change them.
    private void breakForPlayer(Block block, Player player, ItemStack tool, BlockBreakEvent breakEvent) {
        BlockState state = block.getState();
        Material type = block.getType();
        Collection<ItemStack> drops = breakEvent.isDropItems() ? block.getDrops(tool, player) : List.of();
        World world = block.getWorld();
        world.playEffect(block.getLocation(), Effect.DESTROY_BLOCK_WITH_SOUND, block.getBlockData());
        BeeNests.beforeBreak(block, player, tool, false);
        block.setType(Material.AIR);
        MinedStatistic.award(player, type);
        dropItems(block, state, player, drops);
        int experience = breakEvent.getExpToDrop();
        if (experience > 0) {
            world.spawn(block.getLocation().add(0.5, 0.5, 0.5), ExperienceOrb.class,
                    orb -> orb.setExperience(experience));
        }
    }

    // Only the items still listed after the drop event are spawned, where vanilla
    // pops a block's drops; if the event can't be fired, all of them drop.
    private void dropItems(Block block, BlockState state, Player player, Collection<ItemStack> drops) {
        World world = block.getWorld();
        ThreadLocalRandom random = ThreadLocalRandom.current();
        List<Item> items = new ArrayList<>();
        for (ItemStack drop : drops) {
            if (drop == null || drop.isEmpty())
                continue;
            Location at = block.getLocation().add(0.5 + random.nextDouble(-0.25, 0.25),
                    0.375 + random.nextDouble(-0.25, 0.25), 0.5 + random.nextDouble(-0.25, 0.25));
            Item item = world.createEntity(at, Item.class);
            item.setItemStack(drop);
            items.add(item);
        }
        if (items.isEmpty())
            return;
        BlockDropItemEvent event = dropEventGuard
                .fire(() -> fired(new BlockDropItemEvent(block, state, player, items)));
        if (event != null && event.isCancelled())
            return;
        for (Item item : event == null ? items : event.getItems()) {
            if (!item.isInWorld()) {
                world.addEntity(item);
            }
        }
    }

    private <E extends Event> E fired(E event) {
        plugin.getServer().getPluginManager().callEvent(event);
        return event;
    }

    private static boolean mayInteract(Player player, Block block) {
        return mayInteract(block.getWorld().getWorldBorder().isInside(block.getLocation()),
                isUnderSpawnProtection(player, block));
    }

    // The server's mayInteract rule for a block: inside the world border, which
    // holds for ops too, and not under spawn protection. Package-private for
    // SpawnProtectionTest.
    static boolean mayInteract(boolean insideWorldBorder, boolean underSpawnProtection) {
        return insideWorldBorder && !underSpawnProtection;
    }

    // Spawn protection only applies around the world spawn, in the world holding
    // the server's respawn point, with radius > 0, existing ops, and to non-ops.
    private static boolean isUnderSpawnProtection(Player player, Block block) {
        int radius = Bukkit.getSpawnRadius();
        if (radius <= 0 || Bukkit.getOperators().isEmpty() || player.isOp()) {
            return false;
        }
        World respawnWorld = Bukkit.getServer().getRespawnWorld();
        var spawn = respawnWorld.getSpawnLocation();
        return isWithinSpawnProtection(block.getWorld().getKey(), block.getX(), block.getZ(), respawnWorld.getKey(),
                spawn.getBlockX(), spawn.getBlockZ(), radius);
    }

    static boolean isWithinSpawnProtection(NamespacedKey world, int x, int z, NamespacedKey spawnWorld, int spawnX,
            int spawnZ, int radius) {
        return world.equals(spawnWorld) && Math.max(Math.abs(x - spawnX), Math.abs(z - spawnZ)) <= radius;
    }

    private void loadLeafMappings() {
        File file = new File(plugin.getDataFolder(), "leaf_mappings.yml");
        if (!file.exists()) {
            plugin.saveResource("leaf_mappings.yml", false);
        }
        String error = ConfigWatcher.describeYamlError(file);
        if (error != null) {
            plugin.messages().log(plugin.getLogger(), Level.WARNING,
                    leafMappingsLoaded ? "log.leaf_mappings_invalid_reload" : "log.leaf_mappings_invalid_startup",
                    Map.of("error", error));
        }
        YamlConfiguration yaml = leafMappingsSource(file, error, leafMappingsLoaded,
                () -> plugin.getResource("leaf_mappings.yml"));
        if (yaml == null) {
            return;
        }
        leafMappings.clear();
        mappedFoliage.clear();
        leafMappingsLoaded = true;
        ConfigurationSection section = yaml.getConfigurationSection("log_to_leaves");
        if (section == null) {
            return;
        }
        for (String key : section.getKeys(false)) {
            Material log = Material.matchMaterial(key.toUpperCase(Locale.ROOT));
            if (log == null) {
                plugin.getLogger().warning("Unknown log material in leaf_mappings.yml: " + key);
                continue;
            }
            List<String> leaves = section.getStringList(key);
            if (leaves.isEmpty())
                continue;
            Set<Material> mapped = leafMappings.computeIfAbsent(log, m -> EnumSet.noneOf(Material.class));
            for (String leafName : leaves) {
                Material leaf = Material.matchMaterial(leafName.toUpperCase(Locale.ROOT));
                if (leaf != null) {
                    mapped.add(leaf);
                    mappedFoliage.add(leaf);
                } else {
                    plugin.getLogger().warning("Unknown leaf material in leaf_mappings.yml: " + leafName);
                }
            }
        }
    }

    // The leaf mappings to load: the server's file, or while it isn't valid YAML,
    // none to keep those loaded, or the bundled ones if none are loaded yet; the
    // file stays untouched. Package-private for LeafMappingsFileTest.
    static YamlConfiguration leafMappingsSource(File file, String yamlError, boolean loaded,
            Supplier<InputStream> bundled) {
        if (yamlError == null) {
            return YamlConfiguration.loadConfiguration(file);
        }
        if (loaded) {
            return null;
        }
        try (InputStream in = bundled.get()) {
            return in == null
                    ? new YamlConfiguration()
                    : YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (IOException e) {
            return new YamlConfiguration();
        }
    }

    private Set<Material> computeAllowedLeaves(List<Block> logs, Map<Long, Material> originalMaterials) {
        if (logs == null || logs.isEmpty()) {
            return null;
        }
        Set<Material> allowed = null;
        for (Block log : logs) {
            if (log == null)
                continue;
            Material logType = getOriginalMaterial(log, originalMaterials);
            if (logType == null)
                continue;
            Set<Material> mapped = leafMappings.get(logType);
            if (mapped == null || mapped.isEmpty())
                continue;
            if (allowed == null) {
                allowed = EnumSet.copyOf(mapped);
            } else {
                allowed.addAll(mapped);
            }
        }
        return allowed;
    }

    /**
     * Extra durability a felling of {@code brokenLogs} logs costs on top of the
     * point vanilla takes for the first block. Package-private for
     * DurabilityCostTest.
     */
    static int extraDurabilityCost(int brokenLogs, double multiplier) {
        int total = (int) Math.round(Math.max(0, brokenLogs) * Math.max(0.0, multiplier));
        int vanillaApplied = brokenLogs > 0 ? 1 : 0;
        return Math.max(0, total - vanillaApplied);
    }

    // The axe always keeps at least 1 durability. Package-private for
    // DurabilityCostTest.
    static int cappedDurabilityCharge(int amount, int maxDurability, int currentDamage) {
        return Math.max(0, Math.min(amount, maxDurability - currentDamage - 1));
    }

    // Damages the tagged axe wherever it sits in the inventory the vanilla way
    // (Unbreaking, Unbreakable, PlayerItemDamageEvent, none in creative mode) and
    // returns the points that were rolled for, not the points actually taken.
    private int applyDurabilityCostForTaggedTool(Player player, UUID fellingId, int amount) {
        if (player == null || fellingId == null || amount <= 0)
            return 0;
        FoundTool found = findToolByFellingId(player, fellingId);
        if (found == null || found.stack == null)
            return 0;
        ItemStack tool = found.stack;
        if (!(tool.getItemMeta() instanceof Damageable dmg))
            return 0;
        int max = maxDurability(tool.getType().getMaxDurability(), dmg);
        if (max <= 0)
            return 0;
        if (dmg.isUnbreakable() || player.getGameMode() == GameMode.CREATIVE)
            return amount;
        int charge = cappedDurabilityCharge(amount, max, dmg.getDamage());
        if (charge <= 0)
            return 0;
        ItemStack damaged = player.damageItemStack(tool, charge);
        // Write back in case Bukkit handed out a copy of the stack.
        found.writeBack(player, damaged);
        return charge;
    }

    private UUID markToolForFelling(ItemStack tool) {
        if (tool == null || tool.getType().isAir())
            return null;
        var meta = tool.getItemMeta();
        if (meta == null)
            return null;

        UUID id = UUID.randomUUID();
        meta.getPersistentDataContainer().set(activeFellingKey, PersistentDataType.STRING, id.toString());
        tool.setItemMeta(meta);
        return id;
    }

    private void clearToolFellingTag(Player player, UUID fellingId) {
        if (player == null || fellingId == null)
            return;
        FoundTool found = findToolByFellingId(player, fellingId);
        if (found == null || found.stack == null)
            return;
        var meta = found.stack.getItemMeta();
        if (meta == null)
            return;
        meta.getPersistentDataContainer().remove(activeFellingKey);
        found.stack.setItemMeta(meta);
        found.writeBack(player, found.stack);
    }

    /**
     * Removes a possibly stale felling tag from every item in the player's
     * inventory, regardless of which felling created it.
     */
    private void clearAnyFellingTags(Player player) {
        if (player == null)
            return;
        var inv = player.getInventory();
        ItemStack off = inv.getItemInOffHand();
        if (clearFellingTagIfPresent(off)) {
            inv.setItemInOffHand(off);
        }
        ItemStack[] contents = inv.getContents();
        for (int i = 0; i < contents.length; i++) {
            if (clearFellingTagIfPresent(contents[i])) {
                inv.setItem(i, contents[i]);
            }
        }
    }

    private boolean clearFellingTagIfPresent(ItemStack stack) {
        if (stack == null || stack.getType().isAir())
            return false;
        var meta = stack.getItemMeta();
        if (meta == null || !meta.getPersistentDataContainer().has(activeFellingKey, PersistentDataType.STRING))
            return false;
        meta.getPersistentDataContainer().remove(activeFellingKey);
        stack.setItemMeta(meta);
        return true;
    }

    /** Where a tagged tool was found; index is the slot within that place. */
    enum ToolPlace {
        OFFHAND, INVENTORY, CURSOR, CRAFTING_GRID
    }

    record ToolLocation(ToolPlace place, int index) {
    }

    /**
     * Searches off hand, inventory, cursor and crafting grid in that order.
     * Package-private and generic for ToolLocationTest.
     */
    static <T> ToolLocation locateTool(T offhand, T[] contents, T cursor, T[] craftingGrid,
            Predicate<T> matches) {
        if (matches.test(offhand))
            return new ToolLocation(ToolPlace.OFFHAND, -1);
        for (int i = 0; i < contents.length; i++) {
            if (matches.test(contents[i]))
                return new ToolLocation(ToolPlace.INVENTORY, i);
        }
        if (matches.test(cursor))
            return new ToolLocation(ToolPlace.CURSOR, -1);
        for (int i = 0; i < craftingGrid.length; i++) {
            if (matches.test(craftingGrid[i]))
                return new ToolLocation(ToolPlace.CRAFTING_GRID, i);
        }
        return null;
    }

    private static final class FoundTool {
        final ToolLocation location;
        final ItemStack stack;

        FoundTool(ToolLocation location, ItemStack stack) {
            this.location = location;
            this.stack = stack;
        }

        void writeBack(Player player, ItemStack updated) {
            if (player == null)
                return;
            var inv = player.getInventory();
            switch (location.place()) {
                case OFFHAND -> inv.setItemInOffHand(updated);
                case INVENTORY -> inv.setItem(location.index(), updated);
                case CURSOR -> player.setItemOnCursor(updated);
                case CRAFTING_GRID -> {
                    CraftingInventory grid = craftingGridOf(player);
                    if (grid != null) {
                        ItemStack[] matrix = grid.getMatrix();
                        matrix[location.index()] = updated;
                        grid.setMatrix(matrix);
                    }
                }
            }
        }
    }

    // The player's own 2x2 grid or an open crafting table: the server hands their
    // items back when the view closes, so a tool there still counts as carried.
    private static CraftingInventory craftingGridOf(Player player) {
        var top = player.getOpenInventory().getTopInventory();
        if ((top.getType() == InventoryType.CRAFTING || top.getType() == InventoryType.WORKBENCH)
                && top instanceof CraftingInventory grid) {
            return grid;
        }
        return null;
    }

    private FoundTool findToolByFellingId(Player player, UUID fellingId) {
        if (player == null || fellingId == null)
            return null;
        var inv = player.getInventory();
        String target = fellingId.toString();
        CraftingInventory grid = craftingGridOf(player);
        ItemStack[] offhand = {inv.getItemInOffHand()};
        ItemStack[] contents = inv.getContents();
        ItemStack[] cursor = {player.getItemOnCursor()};
        ItemStack[] matrix = grid != null ? grid.getMatrix() : new ItemStack[0];
        ToolLocation location = locateTool(offhand[0], contents, cursor[0], matrix,
                item -> hasFellingId(item, target));
        if (location == null)
            return null;
        ItemStack stack = switch (location.place()) {
            case OFFHAND -> offhand[0];
            case INVENTORY -> contents[location.index()];
            case CURSOR -> cursor[0];
            case CRAFTING_GRID -> matrix[location.index()];
        };
        return new FoundTool(location, stack);
    }

    private boolean hasFellingId(ItemStack stack, String expectedId) {
        if (stack == null || stack.getType().isAir())
            return false;
        var meta = stack.getItemMeta();
        if (meta == null)
            return false;
        String id = meta.getPersistentDataContainer().get(activeFellingKey, PersistentDataType.STRING);
        return expectedId.equals(id);
    }

    private void sendFellingStoppedActionbar(Player player, String key) {
        if (player == null || !player.isOnline())
            return;
        try {
            player.sendActionBar(plugin.messages().component(key));
        } catch (Exception ignored) {
            // Best-effort only (compat across server APIs)
        }
    }

    private void sendFellingAlreadyRunningActionbar(Player player) {
        if (player == null || !player.isOnline())
            return;
        long now = System.currentTimeMillis();
        UUID uuid = player.getUniqueId();
        long last = lastFellingActionbarAt.getOrDefault(uuid, 0L);
        if ((now - last) < FELLING_ACTIONBAR_COOLDOWN_MS)
            return;
        lastFellingActionbarAt.put(uuid, now);
        try {
            player.sendActionBar(plugin.messages().component("ui.felling_already_running"));
        } catch (Exception ignored) {
            // Best-effort only (compat across server APIs)
        }
    }

    private long key(Block b) {
        return key(b.getWorld(), b.getX(), b.getY(), b.getZ());
    }

    private long key(World world, long x, long y, long z) {
        long worldHash = (world.getUID().getMostSignificantBits() ^ world.getUID().getLeastSignificantBits()) & 0xFFFFL;
        long coordPack = (x & 0x3FFFFFFL) << 38 | (z & 0x3FFFFFFL) << 12 | (y & 0xFFFL);
        return (worldHash << 48) ^ coordPack;
    }

    private boolean withinRadius(int originX, int originY, int originZ, Block candidate,
            int horizontalRadius, int verticalRadius) {
        if (candidate == null)
            return false;
        int dx = Math.abs(candidate.getX() - originX);
        int dy = Math.abs(candidate.getY() - originY);
        int dz = Math.abs(candidate.getZ() - originZ);
        boolean horizontalOk = horizontalRadius <= 0 || Math.max(dx, dz) <= horizontalRadius;
        boolean verticalOk = verticalRadius <= 0 || dy <= verticalRadius;
        return horizontalOk && verticalOk;
    }
}
