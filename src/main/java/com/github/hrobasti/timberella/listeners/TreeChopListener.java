package com.github.hrobasti.timberella.listeners;

import com.github.hrobasti.timberella.TimberellaPlugin;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.data.Waterlogged;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.Particle;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.inventory.meta.Damageable;

import java.io.File;
import java.util.*;

public class TreeChopListener implements Listener {
    private final TimberellaPlugin plugin;
    private final NamespacedKey activeFellingKey;
    private final Set<UUID> activeFellingPlayers = new HashSet<>();
    private final Map<UUID, Long> lastFellingActionbarAt = new HashMap<>();
    private static final long FELLING_ACTIONBAR_COOLDOWN_MS = 900L;

    private record LeafEntry(Block block, int depth, Block origin) {}

    private static final Set<String> AXE_MATERIALS = new HashSet<>(Arrays.asList(
            "WOODEN_AXE", "STONE_AXE", "IRON_AXE", "GOLDEN_AXE", "DIAMOND_AXE", "NETHERITE_AXE"
    ));
    private static final Set<Material> OVERWORLD_SOILS = EnumSet.of(
            Material.DIRT,
            Material.GRASS_BLOCK,
            Material.PODZOL,
            Material.COARSE_DIRT,
            Material.ROOTED_DIRT,
            Material.MYCELIUM,
            Material.MOSS_BLOCK,
            Material.FARMLAND,
            Material.MUD,
            Material.MUDDY_MANGROVE_ROOTS
    );
        private static final Set<Material> MANGROVE_SOILS = EnumSet.of(
            Material.MUD,
            Material.MUDDY_MANGROVE_ROOTS,
            Material.ROOTED_DIRT,
            Material.DIRT,
            Material.GRASS_BLOCK,
            Material.PODZOL,
            Material.MANGROVE_ROOTS
        );
    private static final Set<Material> FUNGUS_SOILS = EnumSet.of(
            Material.CRIMSON_NYLIUM,
            Material.WARPED_NYLIUM,
            Material.NETHERRACK
    );

    private enum Species {
        MANGROVE("mangrove", true, 128, 9, 32),
        JUNGLE("jungle", true, -1, 8, 32),
        SPRUCE("spruce", true, -1, 5, 30),
        OAK("oak", true, -1, 6, 24),
        PALE_OAK("pale_oak", true, -1, 5, 16),
        DARK_OAK("dark_oak", true, -1, 6, 12),
        BIRCH("birch", true, -1, 2, 12),
        ACACIA("acacia", true, -1, 8, 12),
        CHERRY("cherry", true, -1, 9, 12),
        MUSHROOM_BROWN("mushroom_brown", true, -1, 4, 12),
        MUSHROOM_RED("mushroom_red", true, -1, 2, 12),
        WARPED("warped", true, -1, 6, 32),
        CRIMSON("crimson", true, -1, 6, 32);

        private final String configKey;
        private final boolean defaultEnabled;
        private final int defaultMaxBlocks;
        private final int defaultHorizontalRadius;
        private final int defaultVerticalRadius;

        Species(String configKey, boolean defaultEnabled, int defaultMaxBlocks,
                int defaultHorizontalRadius, int defaultVerticalRadius) {
            this.configKey = configKey;
            this.defaultEnabled = defaultEnabled;
            this.defaultMaxBlocks = defaultMaxBlocks;
            this.defaultHorizontalRadius = defaultHorizontalRadius;
            this.defaultVerticalRadius = defaultVerticalRadius;
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
            if (name == null) continue;
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
    private final Set<Material> normalLogs = new HashSet<>();
    private final Set<Material> strippedLogs = new HashSet<>();
    private final Set<Material> woods = new HashSet<>();
    private final Set<Material> strippedWoods = new HashSet<>();
    private final Set<Material> fences = new HashSet<>();
    private final Set<Material> additions = new HashSet<>();
    private final Set<Material> allowedAxes = new HashSet<>();
    private final Map<Material, Material> saplingMappings = new EnumMap<>(Material.class);
    private final Set<Material> allowedSaplings = EnumSet.noneOf(Material.class);
    private final Map<Material, Set<Material>> leafMappings = new EnumMap<>(Material.class);
    private boolean timberEnabled = true;
    private boolean leavesDecayEnabled = true;
    private long leavesDecayIntervalTicks = 2L;
    private int leavesDecayRadius = 5;
    private int leavesDecayBatchSize = 20;
    private int leavesDecayMaxDistance = 4;
    private int leavesDecayMaxDistanceSquared = 16;
    private boolean replantEnabled = false;
    private boolean includeDiagonals = true;
    private int maxBlocks = 1024;
    private int sneakMode = 0;
    private int minRemainingDurability = 10;
    private boolean durabilityModeAll = false;
    private double durabilityMultiplier = 0.5;
    private long breakIntervalTicks = 2L;

    public TreeChopListener(TimberellaPlugin plugin) {
        this.plugin = plugin;
        this.activeFellingKey = new NamespacedKey(plugin, "active_felling_id");
        loadCategoryMaps();
    }

    public void refresh() {
        loadCategoryMaps();
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
                plugin.getLogger().fine("Ignoring unknown sapling in replant.saplings: " + name);
            }
        }
        loadMap("categories.logs", normalLogs);
        ensureDefaultMaterial("categories.logs.MANGROVE_ROOTS", normalLogs, Material.MANGROVE_ROOTS);
        normalLogs.remove(Material.MUDDY_MANGROVE_ROOTS);
        loadMap("categories.stripped-logs", strippedLogs);
        loadMap("categories.woods", woods);
        loadMap("categories.stripped-woods", strippedWoods);
        loadMap("categories.fences", fences);
        loadMap("categories.additions", additions);
        loadLeafMappings();
        // modules
        timberEnabled = plugin.getConfig().getBoolean("enable-timber", true);
        leavesDecayEnabled = plugin.getConfig().getBoolean("enable-leaves-decay", true);
        leavesDecayRadius = Math.max(0, plugin.getConfig().getInt("leaves-decay.decay-radius", 5));
        leavesDecayIntervalTicks = Math.max(1L, plugin.getConfig().getLong("leaves-decay.batch-interval-ticks", 2L));
        leavesDecayBatchSize = Math.max(1, plugin.getConfig().getInt("leaves-decay.batch-size", 20));
        int configuredMaxDistance = plugin.getConfig().getInt("leaves-decay.max-distance", 4);
        leavesDecayMaxDistance = Math.max(1, configuredMaxDistance);
        leavesDecayMaxDistanceSquared = leavesDecayMaxDistance * leavesDecayMaxDistance;
        replantEnabled = plugin.getConfig().getBoolean("enable-replant", true);
        maxBlocks = Math.max(1, plugin.getConfig().getInt("max-blocks", 1024));
        loadSpeciesLimits();
        sneakMode = plugin.getConfig().getInt("sneak-mode", 0);
        includeDiagonals = plugin.getConfig().getBoolean("include-diagonals",
            plugin.getConfig().getBoolean("include_diagonals",
                plugin.getConfig().getInt("adjacency_faces", 26) > 6));

        // tools
        List<String> axes = plugin.getConfig().getStringList("tools.allowed-axes");
        for (String name : axes) {
            Material m = Material.matchMaterial(name.toUpperCase(Locale.ROOT));
            if (m != null) allowedAxes.add(m);
        }
        minRemainingDurability = Math.max(0, plugin.getConfig().getInt("tools.min-remaining-durability", 10));
        String mode = plugin.getConfig().getString("tools.durability-mode", "first");
        durabilityModeAll = mode != null && mode.equalsIgnoreCase("all");
        durabilityMultiplier = plugin.getConfig().getDouble("tools.durability-multiplier", 0.5);
        breakIntervalTicks = Math.max(1L, plugin.getConfig().getLong("break-interval-ticks", 2L));
    }

    private void loadMap(String path, Set<Material> target) {
        var section = plugin.getConfig().getConfigurationSection(path);
        if (section == null) return;
        for (String key : section.getKeys(false)) {
            boolean enabled = section.getBoolean(key, true);
            if (!enabled) continue;
            Material m = Material.matchMaterial(key.toUpperCase(Locale.ROOT));
            if (m != null) target.add(m);
            else plugin.getLogger().fine("Ignoring unknown material in section " + path + ": " + key);
        }
    }

    private void ensureDefaultMaterial(String path, Set<Material> target, Material material) {
        if (material == null) return;
        if (plugin.getConfig().isSet(path)) {
            if (plugin.getConfig().getBoolean(path, true)) {
                target.add(material);
            }
            return;
        }
        target.add(material);
    }

    private void loadSpeciesLimits() {
        speciesLimits.clear();
        ConfigurationSection section = plugin.getConfig().getConfigurationSection("species-limits");
        for (Species species : Species.values()) {
            SpeciesLimit limit = new SpeciesLimit();
            limit.enabled = species.defaultEnabled();
            limit.maxBlocks = species.defaultMaxBlocks();
            limit.maxHorizontalRadius = Math.max(0, species.defaultHorizontalRadius());
            limit.maxVerticalRadius = Math.max(0, species.defaultVerticalRadius());

            ConfigurationSection source = section != null ? section.getConfigurationSection(species.configKey()) : null;
            if (source != null) {
                limit.enabled = source.getBoolean("enabled", limit.enabled);
                if (source.isSet("max-blocks") || source.isSet("max_blocks")) {
                    limit.maxBlocks = source.contains("max-blocks")
                        ? source.getInt("max-blocks", limit.maxBlocks)
                        : source.getInt("max_blocks", limit.maxBlocks);
                }
                if (source.isSet("max-horizontal-radius") || source.isSet("max_horizontal_radius")) {
                    limit.maxHorizontalRadius = Math.max(0,
                        source.contains("max-horizontal-radius")
                            ? source.getInt("max-horizontal-radius", limit.maxHorizontalRadius)
                            : source.getInt("max_horizontal_radius", limit.maxHorizontalRadius));
                }
                if (source.isSet("max-vertical-radius") || source.isSet("max_vertical_radius")) {
                    limit.maxVerticalRadius = Math.max(0,
                        source.contains("max-vertical-radius")
                            ? source.getInt("max-vertical-radius", limit.maxVerticalRadius)
                            : source.getInt("max_vertical_radius", limit.maxVerticalRadius));
                }
            }

            limit.maxBlocks = limit.maxBlocks < 1 ? -1 : limit.maxBlocks;
            speciesLimits.put(species, limit);
        }
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        Block start = event.getBlock();
        ItemStack tool = player.getInventory().getItemInMainHand();

        if (!plugin.isEnabledFor(player.getUniqueId())) return;
        if (!isTreeMaterial(start.getType())) return;
        if (!isAxe(tool)) return;
        if (!hasMinDurability(tool)) return;
        if (!sneakModeAllows(player.isSneaking())) return;

        boolean hasTimberPermission = player.hasPermission("timberella.use");

        int treeMaxBlocks = maxBlocks;
        int horizontalRadiusLimit = -1;
        int verticalRadiusLimit = -1;
        Species species = detectSpecies(start);
        SpeciesLimit limit = species != null ? speciesLimits.get(species) : null;
        if (limit != null && limit.enabled) {
            int speciesCap = limit.maxBlocks;
            if (speciesCap > 0 && speciesCap < maxBlocks) {
                treeMaxBlocks = Math.min(treeMaxBlocks, speciesCap);
            }
            if (limit.maxHorizontalRadius > 0) {
                horizontalRadiusLimit = limit.maxHorizontalRadius;
            }
            if (limit.maxVerticalRadius > 0) {
                verticalRadiusLimit = limit.maxVerticalRadius;
            }
        }
        List<Block> sequence = collectConnectedLogs(start, treeMaxBlocks, includeDiagonals,
                horizontalRadiusLimit, verticalRadiusLimit);
        if (sequence.isEmpty()) {
            sequence = Collections.singletonList(start);
        }
        final Map<Long, Material> originalMaterials = captureOriginalMaterials(sequence);

        if (hasTimberPermission && timberEnabled) {
            if (activeFellingPlayers.contains(player.getUniqueId())) {
                // Prevent overlapping felling tasks for the same player.
                // Let vanilla breaking happen for this block; we just don't start a second timber task.
                sendFellingAlreadyRunningActionbar(player);
                return;
            }
            try {
                var loc = start.getLocation().add(0.5, 0.5, 0.5);
                start.getWorld().spawnParticle(Particle.SWEEP_ATTACK, loc, 1, 0, 0, 0, 0);
            } catch (Throwable ignored) {}
            if (sequence.size() <= 1) {
                handlePostActions(player, tool, sequence, false, originalMaterials);
                return;
            }

            UUID fellingId = markToolForFelling(tool);
            if (fellingId == null) {
                // Tool couldn't be tagged; fall back to safe behavior (no extra durability, no overwrites)
                handlePostActions(player, tool, sequence, false, originalMaterials);
                return;
            }

            activeFellingPlayers.add(player.getUniqueId());
            final List<Block> allLogs = new ArrayList<>(sequence);
            final List<Block> toBreak = new ArrayList<>(sequence.subList(1, sequence.size()));
            final ItemStack usedTool = tool;
            final Player p = player;
            final long interval = breakIntervalTicks;
            final Map<Long, Material> capturedMaterials = originalMaterials;
            new BukkitRunnable() {
                int idx = 0;
                @Override
                public void run() {
                    if (!p.isOnline()) {
                        clearToolFellingTag(p, fellingId);
                        activeFellingPlayers.remove(p.getUniqueId());
                        cancel();
                        return;
                    }

                    if (idx >= toBreak.size()) {
                        applyDurabilityCostForTaggedTool(p, fellingId, allLogs.size());
                        clearToolFellingTag(p, fellingId);
                        handlePostActions(p, usedTool, allLogs, true, capturedMaterials);
                        activeFellingPlayers.remove(p.getUniqueId());
                        cancel();
                        return;
                    }
                    Block b = toBreak.get(idx++);
                    if (isTreeMaterial(b.getType())) {
                        b.breakNaturally(usedTool, true);
                    }
                }
            }.runTaskTimer(plugin, interval, interval);
            return;
        }

        // Timber disabled: still allow optional post-actions using the initial block
        List<Block> single = new ArrayList<>(Collections.singletonList(start));
        handlePostActions(player, tool, single, false, originalMaterials);
    }

    private boolean sneakModeAllows(boolean sneaking) {
        switch (sneakMode) {
            case 0: return sneaking;          // only when sneaking
            case 1: return !sneaking;         // only when not sneaking
            case 2: return true;              // always
            default: return sneaking;         // fallback to 0
        }
    }

    private boolean isAxe(ItemStack stack) {
        if (stack == null) return false;
        Material type = stack.getType();
        // If config provides allowed axes, use it; fallback to built-in list for safety
        if (!allowedAxes.isEmpty()) return allowedAxes.contains(type);
        return AXE_MATERIALS.contains(type.name());
    }

    private boolean hasMinDurability(ItemStack stack) {
        if (stack == null) return false;
        Material type = stack.getType();
        int max = type.getMaxDurability();
        if (max <= 0) return true; // not damageable
        var meta = stack.getItemMeta();
        if (!(meta instanceof Damageable)) return true;
        int damage = ((Damageable) meta).getDamage();
        int remaining = max - damage;
        return remaining >= minRemainingDurability;
    }

    private boolean isTreeMaterial(Material m) {
        if (m == null) return false;
        // Species extraction not currently required with boolean material maps.
        // Custom blocks always allowed
        // Legacy global customMaterials removed in favor of per-category sets

        if (normalLogs.contains(m)) return true;
        if (strippedLogs.contains(m)) return true;
        if (woods.contains(m)) return true;
        if (strippedWoods.contains(m)) return true;
        if (fences.contains(m)) return true;
        if (additions.contains(m)) return true;
        return false;
    }

    private Species detectSpecies(Block block) {
        if (block == null) return null;
        Material type = block.getType();
        Species mapped = MATERIAL_TO_SPECIES.get(type);
        if (mapped != null) {
            return mapped;
        }
        if (type == Material.MUSHROOM_STEM) {
            return detectMushroomSpecies(block);
        }
        return null;
    }

    private Species detectMushroomSpecies(Block origin) {
        if (origin == null) return null;
        for (int dy = 1; dy <= 6; dy++) {
            Block candidate = origin.getRelative(0, dy, 0);
            Material mat = candidate.getType();
            Species species = MATERIAL_TO_SPECIES.get(mat);
            if (species == Species.MUSHROOM_BROWN || species == Species.MUSHROOM_RED) {
                return species;
            }
            if (mat != Material.MUSHROOM_STEM) {
                break;
            }
        }
        int radius = 3;
        for (int dy = 0; dy <= 4; dy++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (dx == 0 && dz == 0 && dy == 0) continue;
                    Block candidate = origin.getRelative(dx, dy, dz);
                    Species species = MATERIAL_TO_SPECIES.get(candidate.getType());
                    if (species == Species.MUSHROOM_BROWN || species == Species.MUSHROOM_RED) {
                        return species;
                    }
                }
            }
        }
        return null;
    }

    private List<Block> collectConnectedLogs(Block start, int maxBlocks, boolean includeDiagonals,
                                             int maxHorizontalRadius, int maxVerticalRadius) {
        if (start == null) {
            return Collections.emptyList();
        }
        Queue<Block> queue = new ArrayDeque<>();
        Set<Long> visited = new HashSet<>();
        List<Block> result = new ArrayList<>();
        queue.add(start);
        visited.add(key(start));
        final boolean limitHorizontal = maxHorizontalRadius > 0;
        final boolean limitVertical = maxVerticalRadius > 0;
        final boolean limitRadius = limitHorizontal || limitVertical;
        final int originX = start.getX();
        final int originY = start.getY();
        final int originZ = start.getZ();
        while (!queue.isEmpty() && result.size() < maxBlocks) {
            Block b = queue.poll();
            if (!isTreeMaterial(b.getType())) continue;
            if (limitRadius && !withinRadius(originX, originY, originZ, b, maxHorizontalRadius, maxVerticalRadius)) continue;
            result.add(b);
            if (!includeDiagonals) {
                int[][] dirs = {
                        {1,0,0},{-1,0,0},{0,1,0},{0,-1,0},{0,0,1},{0,0,-1}
                };
                for (int[] d : dirs) {
                    Block n = b.getRelative(d[0], d[1], d[2]);
                    if (!isTreeMaterial(n.getType())) continue;
                    if (limitRadius && !withinRadius(originX, originY, originZ, n, maxHorizontalRadius, maxVerticalRadius)) continue;
                    long k = key(n);
                    if (visited.add(k)) queue.add(n);
                }
            } else {
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dy = -1; dy <= 1; dy++) {
                        for (int dz = -1; dz <= 1; dz++) {
                            if (dx == 0 && dy == 0 && dz == 0) continue;
                            Block n = b.getRelative(dx, dy, dz);
                            if (!isTreeMaterial(n.getType())) continue;
                            if (limitRadius && !withinRadius(originX, originY, originZ, n, maxHorizontalRadius, maxVerticalRadius)) continue;
                            long k = key(n);
                            if (visited.add(k)) queue.add(n);
                        }
                    }
                }
            }
        }
        return result;
    }

    private void handlePostActions(Player player, ItemStack tool, List<Block> logs, boolean performedTimber, Map<Long, Material> originalMaterials) {
        if (logs == null || logs.isEmpty()) return;
        if (leavesDecayEnabled) {
            Block origin = logs.get(0);
            scheduleLeavesDecay(player, logs, origin);
        }
        if (performedTimber && replantEnabled) {
            tryReplant(logs, originalMaterials);
        }
    }

    private void scheduleLeavesDecay(Player player, List<Block> logs, Block origin) {
        if (!leavesDecayEnabled) return;
        if (logs.isEmpty()) return;
        if (leavesDecayRadius <= 0) return;

        final Set<Material> allowedLeaves = computeAllowedLeaves(logs, origin);
        final Deque<LeafEntry> queue = new ArrayDeque<>();
        final Set<Long> visited = new HashSet<>();
        final int maxDepth = leavesDecayRadius;
        for (Block log : logs) {
            seedLeafNeighbors(log, queue, visited, maxDepth, allowedLeaves, log);
        }
        if (queue.isEmpty()) return;
        final int batchSize = leavesDecayBatchSize;
        final long interval = leavesDecayIntervalTicks;
        final Player sourcePlayer = player;
        final var pluginManager = plugin.getServer().getPluginManager();

        new BukkitRunnable() {
            @Override
            public void run() {
                int processed = 0;
                while (!queue.isEmpty() && processed < batchSize) {
                    LeafEntry entry = queue.poll();
                    if (entry == null) break;
                    Block b = entry.block();
                    int depth = entry.depth();
                    Block originBlock = entry.origin();
                    if (!isLeafMaterial(b.getType()) || !isAllowedLeaf(b.getType(), allowedLeaves)) {
                        continue;
                    }

                    boolean allowDrops = true;
                    if (sourcePlayer != null) {
                        BlockBreakEvent leafEvent = new BlockBreakEvent(b, sourcePlayer);
                        pluginManager.callEvent(leafEvent);
                        if (leafEvent.isCancelled()) {
                            continue;
                        }
                        allowDrops = leafEvent.isDropItems();
                    }

                    if (allowDrops) {
                        b.breakNaturally();
                    } else {
                        b.setType(Material.AIR);
                    }

                    int nextDepth = depth + 1;
                    if (nextDepth <= maxDepth) {
                        if (includeDiagonals) {
                            for (int dx = -1; dx <= 1; dx++) {
                                for (int dy = -1; dy <= 1; dy++) {
                                    for (int dz = -1; dz <= 1; dz++) {
                                        if (dx == 0 && dy == 0 && dz == 0) continue;
                                        Block n = b.getRelative(dx, dy, dz);
                                        enqueueLeaf(n, nextDepth, maxDepth, queue, visited, allowedLeaves, originBlock);
                                    }
                                }
                            }
                        } else {
                            int[][] dirs = {
                                    {1,0,0},{-1,0,0},{0,1,0},{0,-1,0},{0,0,1},{0,0,-1}
                            };
                            for (int[] d : dirs) {
                                Block n = b.getRelative(d[0], d[1], d[2]);
                                enqueueLeaf(n, nextDepth, maxDepth, queue, visited, allowedLeaves, originBlock);
                            }
                        }
                    }
                    processed++;
                }
                if (queue.isEmpty()) cancel();
            }
        }.runTaskTimer(plugin, 0L, interval);
    }

    private void seedLeafNeighbors(Block log, Deque<LeafEntry> queue, Set<Long> visited, int maxDepth, Set<Material> allowedLeaves, Block origin) {
        if (log == null || maxDepth <= 0) return;
        if (includeDiagonals) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dy == 0 && dz == 0) continue;
                        Block candidate = log.getRelative(dx, dy, dz);
                        enqueueLeaf(candidate, 0, maxDepth, queue, visited, allowedLeaves, origin);
                    }
                }
            }
        } else {
            int[][] dirs = {
                    {1,0,0},{-1,0,0},{0,1,0},{0,-1,0},{0,0,1},{0,0,-1}
            };
            for (int[] d : dirs) {
                Block candidate = log.getRelative(d[0], d[1], d[2]);
                enqueueLeaf(candidate, 0, maxDepth, queue, visited, allowedLeaves, origin);
            }
        }
    }

    private void enqueueLeaf(Block block, int depth, int maxDepth, Deque<LeafEntry> queue, Set<Long> visited, Set<Material> allowedLeaves, Block origin) {
        if (block == null) return;
        if (depth > maxDepth) return;
        if (!isLeafMaterial(block.getType())) return;
        if (!isAllowedLeaf(block.getType(), allowedLeaves)) return;
        if (!isWithinLeafDistance(origin, block)) return;
        long key = key(block);
        if (visited.add(key)) {
            queue.add(new LeafEntry(block, depth, origin));
        }
    }

    private boolean isAllowedLeaf(Material material, Set<Material> allowedLeaves) {
        return allowedLeaves == null || allowedLeaves.contains(material);
    }

    private boolean isLeafMaterial(Material material) {
        if (material == null) return false;
        String name = material.name();
        return name.endsWith("_LEAVES") || name.endsWith("_LEAF");
    }

    private boolean isWithinLeafDistance(Block origin, Block candidate) {
        if (origin == null || candidate == null) return true;
        int dx = origin.getX() - candidate.getX();
        int dy = origin.getY() - candidate.getY();
        int dz = origin.getZ() - candidate.getZ();
        return (dx * dx + dy * dy + dz * dz) <= leavesDecayMaxDistanceSquared;
    }

    private void tryReplant(List<Block> logs, Map<Long, Material> originalMaterials) {
        if (!replantEnabled) return;
        if (logs == null || logs.isEmpty()) return;

        Block best = null;
        Material sapling = null;
        for (Block log : logs) {
            Material originalType = getOriginalMaterial(log, originalMaterials);
            Material mapped = saplingMappings.get(originalType);
            if (mapped == null) continue;
            if (!allowedSaplings.isEmpty() && !allowedSaplings.contains(mapped)) continue;
            if (!canPlantAt(log, mapped)) continue;
            if (best == null || log.getY() < best.getY()
                    || (log.getY() == best.getY() && compareColumns(log, best) < 0)) {
                best = log;
                sapling = mapped;
            }
        }
        if (best == null || sapling == null) return;

        List<Block> plantingSpots = computePlantingSpots(best, logs, originalMaterials, sapling);
        if (plantingSpots.isEmpty()) {
            plantingSpots = Collections.singletonList(best);
        }

        final Material finalSapling = sapling;
        final List<Block> targets = new ArrayList<>(new LinkedHashSet<>(plantingSpots));
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            for (Block target : targets) {
                placeSapling(target, finalSapling);
            }
        }, 2L);
    }

    private boolean isSuitableSoil(Material soil, Material sapling) {
        if (soil == null || sapling == null) return false;
        if (sapling == Material.CRIMSON_FUNGUS || sapling == Material.WARPED_FUNGUS) {
            return FUNGUS_SOILS.contains(soil);
        }
        if (sapling == Material.MANGROVE_PROPAGULE) {
            return MANGROVE_SOILS.contains(soil);
        }
        return OVERWORLD_SOILS.contains(soil);
    }

    private void initializeSaplingMappings() {
        mapSapling(Material.OAK_LOG, Material.OAK_SAPLING);
        mapSapling(Material.STRIPPED_OAK_LOG, Material.OAK_SAPLING);
        mapSapling(Material.OAK_WOOD, Material.OAK_SAPLING);
        mapSapling(Material.STRIPPED_OAK_WOOD, Material.OAK_SAPLING);
        mapSapling("PALE_OAK_LOG", "PALE_OAK_SAPLING");
        mapSapling("STRIPPED_PALE_OAK_LOG", "PALE_OAK_SAPLING");
        mapSapling("PALE_OAK_WOOD", "PALE_OAK_SAPLING");
        mapSapling("STRIPPED_PALE_OAK_WOOD", "PALE_OAK_SAPLING");

        mapSapling(Material.SPRUCE_LOG, Material.SPRUCE_SAPLING);
        mapSapling(Material.STRIPPED_SPRUCE_LOG, Material.SPRUCE_SAPLING);
        mapSapling(Material.SPRUCE_WOOD, Material.SPRUCE_SAPLING);
        mapSapling(Material.STRIPPED_SPRUCE_WOOD, Material.SPRUCE_SAPLING);

        mapSapling(Material.BIRCH_LOG, Material.BIRCH_SAPLING);
        mapSapling(Material.STRIPPED_BIRCH_LOG, Material.BIRCH_SAPLING);
        mapSapling(Material.BIRCH_WOOD, Material.BIRCH_SAPLING);
        mapSapling(Material.STRIPPED_BIRCH_WOOD, Material.BIRCH_SAPLING);

        mapSapling(Material.JUNGLE_LOG, Material.JUNGLE_SAPLING);
        mapSapling(Material.STRIPPED_JUNGLE_LOG, Material.JUNGLE_SAPLING);
        mapSapling(Material.JUNGLE_WOOD, Material.JUNGLE_SAPLING);
        mapSapling(Material.STRIPPED_JUNGLE_WOOD, Material.JUNGLE_SAPLING);

        mapSapling(Material.ACACIA_LOG, Material.ACACIA_SAPLING);
        mapSapling(Material.STRIPPED_ACACIA_LOG, Material.ACACIA_SAPLING);
        mapSapling(Material.ACACIA_WOOD, Material.ACACIA_SAPLING);
        mapSapling(Material.STRIPPED_ACACIA_WOOD, Material.ACACIA_SAPLING);

        mapSapling(Material.DARK_OAK_LOG, Material.DARK_OAK_SAPLING);
        mapSapling(Material.STRIPPED_DARK_OAK_LOG, Material.DARK_OAK_SAPLING);
        mapSapling(Material.DARK_OAK_WOOD, Material.DARK_OAK_SAPLING);
        mapSapling(Material.STRIPPED_DARK_OAK_WOOD, Material.DARK_OAK_SAPLING);

        mapSapling(Material.CHERRY_LOG, Material.CHERRY_SAPLING);
        mapSapling(Material.STRIPPED_CHERRY_LOG, Material.CHERRY_SAPLING);
        mapSapling(Material.CHERRY_WOOD, Material.CHERRY_SAPLING);
        mapSapling(Material.STRIPPED_CHERRY_WOOD, Material.CHERRY_SAPLING);

        mapSapling(Material.MANGROVE_LOG, Material.MANGROVE_PROPAGULE);
        mapSapling(Material.STRIPPED_MANGROVE_LOG, Material.MANGROVE_PROPAGULE);
        mapSapling(Material.MANGROVE_WOOD, Material.MANGROVE_PROPAGULE);
        mapSapling(Material.STRIPPED_MANGROVE_WOOD, Material.MANGROVE_PROPAGULE);
        mapSapling(Material.MANGROVE_ROOTS, Material.MANGROVE_PROPAGULE);
        mapSapling(Material.MUDDY_MANGROVE_ROOTS, Material.MANGROVE_PROPAGULE);

        mapSapling(Material.CRIMSON_STEM, Material.CRIMSON_FUNGUS);
        mapSapling(Material.STRIPPED_CRIMSON_STEM, Material.CRIMSON_FUNGUS);
        mapSapling(Material.CRIMSON_HYPHAE, Material.CRIMSON_FUNGUS);
        mapSapling(Material.STRIPPED_CRIMSON_HYPHAE, Material.CRIMSON_FUNGUS);

        mapSapling(Material.WARPED_STEM, Material.WARPED_FUNGUS);
        mapSapling(Material.STRIPPED_WARPED_STEM, Material.WARPED_FUNGUS);
        mapSapling(Material.WARPED_HYPHAE, Material.WARPED_FUNGUS);
        mapSapling(Material.STRIPPED_WARPED_HYPHAE, Material.WARPED_FUNGUS);
    }

    private void mapSapling(Material source, Material sapling) {
        if (source == null || sapling == null) return;
        saplingMappings.put(source, sapling);
    }

    private void mapSapling(String sourceName, String saplingName) {
        if (sourceName == null || saplingName == null) return;
        Material source = Material.matchMaterial(sourceName);
        Material sapling = Material.matchMaterial(saplingName);
        mapSapling(source, sapling);
    }

    private Map<Long, Material> captureOriginalMaterials(List<Block> logs) {
        if (logs == null || logs.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<Long, Material> snapshot = new HashMap<>(logs.size());
        for (Block log : logs) {
            if (log == null) continue;
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

    private boolean canPlantAt(Block target, Material sapling) {
        if (target == null || sapling == null) return false;
        Block soil = target.getRelative(0, -1, 0);
        if (!isSuitableSoil(soil.getType(), sapling)) return false;
        Material current = target.getType();
        if (current == Material.WATER || current == Material.BUBBLE_COLUMN) {
            return sapling == Material.MANGROVE_PROPAGULE;
        }
        return current.isAir() || target.isPassable();
    }

    private void placeSapling(Block target, Material sapling) {
        if (!canPlantAt(target, sapling)) return;
        Material current = target.getType();
        boolean targetIsWater = current == Material.WATER || current == Material.BUBBLE_COLUMN;
        target.setType(sapling);
        if (sapling == Material.MANGROVE_PROPAGULE) {
            var data = target.getBlockData();
            if (data instanceof Waterlogged waterlogged) {
                waterlogged.setWaterlogged(targetIsWater);
                target.setBlockData(waterlogged);
            }
        }
    }

    private List<Block> computePlantingSpots(Block reference, List<Block> logs, Map<Long, Material> originals, Material sapling) {
        if (reference == null || logs == null) {
            return Collections.emptyList();
        }
        Map<Long, Block> columns = new HashMap<>();
        for (Block log : logs) {
            Material mapped = saplingMappings.get(getOriginalMaterial(log, originals));
            if (!sapling.equals(mapped)) continue;
            if (!canPlantAt(log, sapling)) continue;
            long colKey = columnKey(log.getWorld(), log.getX(), log.getZ());
            Block existing = columns.get(colKey);
            if (existing == null || log.getY() < existing.getY()) {
                columns.put(colKey, log);
            }
        }
        if (columns.isEmpty()) {
            return Collections.singletonList(reference);
        }
        List<Block> footprint = findTwoByTwoFootprint(columns);
        if (!footprint.isEmpty()) {
            return footprint;
        }
        return Collections.singletonList(reference);
    }

    private List<Block> findTwoByTwoFootprint(Map<Long, Block> columns) {
        if (columns.size() < 4) {
            return Collections.emptyList();
        }
        for (Block origin : columns.values()) {
            var world = origin.getWorld();
            Block east = columns.get(columnKey(world, origin.getX() + 1, origin.getZ()));
            Block south = columns.get(columnKey(world, origin.getX(), origin.getZ() + 1));
            Block southEast = columns.get(columnKey(world, origin.getX() + 1, origin.getZ() + 1));
            if (east == null || south == null || southEast == null) continue;
            if (!isLevelClose(origin, east) || !isLevelClose(origin, south) || !isLevelClose(origin, southEast)) continue;
            return Arrays.asList(origin, east, south, southEast);
        }
        return Collections.emptyList();
    }

    private boolean isLevelClose(Block a, Block b) {
        if (a == null || b == null) return false;
        return Math.abs(a.getY() - b.getY()) <= 1;
    }

    private int compareColumns(Block a, Block b) {
        if (a == null || b == null) return 0;
        if (!a.getWorld().equals(b.getWorld())) {
            return a.getWorld().getUID().compareTo(b.getWorld().getUID());
        }
        if (a.getX() != b.getX()) {
            return Integer.compare(a.getX(), b.getX());
        }
        return Integer.compare(a.getZ(), b.getZ());
    }

    private void loadLeafMappings() {
        leafMappings.clear();
        File file = new File(plugin.getDataFolder(), "leaf_mappings.yml");
        if (!file.exists()) {
            plugin.saveResource("leaf_mappings.yml", false);
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection section = yaml.getConfigurationSection("log_to_leaves");
        if (section == null) {
            return;
        }
        for (String key : section.getKeys(false)) {
            Material log = Material.matchMaterial(key.toUpperCase(Locale.ROOT));
            if (log == null) {
                plugin.getLogger().fine("Unknown log material in leaf_mappings.yml: " + key);
                continue;
            }
            List<String> leaves = section.getStringList(key);
            if (leaves.isEmpty()) continue;
            Set<Material> mapped = leafMappings.computeIfAbsent(log, m -> EnumSet.noneOf(Material.class));
            for (String leafName : leaves) {
                Material leaf = Material.matchMaterial(leafName.toUpperCase(Locale.ROOT));
                if (leaf != null) {
                    mapped.add(leaf);
                } else {
                    plugin.getLogger().fine("Unknown leaf material in leaf_mappings.yml: " + leafName);
                }
            }
        }
    }

    private Set<Material> computeAllowedLeaves(List<Block> logs, Block origin) {
        if (logs == null || logs.isEmpty()) {
            return null;
        }
        Material originType = origin != null ? origin.getType() : null;
        Set<Material> originLeaves = originType != null ? leafMappings.get(originType) : null;
        if (originLeaves == null || originLeaves.isEmpty()) {
            return null;
        }
        Set<Material> allowed = EnumSet.copyOf(originLeaves);
        for (Block log : logs) {
            if (log == null) continue;
            Set<Material> mapped = leafMappings.get(log.getType());
            if (mapped != null && mapped.equals(originLeaves)) {
                allowed.addAll(mapped);
            }
        }
        return allowed;
    }

    private void applyDurabilityCostForTaggedTool(Player player, UUID fellingId, int brokenBlocks) {
        if (!durabilityModeAll) return; // vanilla first-only
        if (player == null || !player.isOnline()) return;
        if (fellingId == null) return;

        FoundTool found = findToolByFellingId(player, fellingId);
        if (found == null || found.stack == null) return;

        ItemStack tool = found.stack;
        Material type = tool.getType();
        int max = type.getMaxDurability();
        if (max <= 0) return;
        var meta = tool.getItemMeta();
        if (!(meta instanceof Damageable)) return;
        Damageable dmg = (Damageable) meta;
        int currentDamage = dmg.getDamage();
        // Total intended cost for the whole felling
        int targetTotal = (int) Math.round(Math.max(0, brokenBlocks) * Math.max(0.0, durabilityMultiplier));
        int vanillaApplied = brokenBlocks > 0 ? 1 : 0; // first block consumed normally
        int extra = Math.max(0, targetTotal - vanillaApplied);
        if (extra <= 0) return;
        int remaining = max - currentDamage;
        int cappedExtra = Math.max(0, Math.min(extra, remaining - 1)); // keep at least 1 durability
        if (cappedExtra <= 0) return;
        dmg.setDamage(currentDamage + cappedExtra);
        tool.setItemMeta((org.bukkit.inventory.meta.ItemMeta) dmg);

        // Ensure the modified stack is written back to the correct slot (important if Bukkit returned copies).
        found.writeBack(player, tool);
    }

    private UUID markToolForFelling(ItemStack tool) {
        if (tool == null || tool.getType().isAir()) return null;
        var meta = tool.getItemMeta();
        if (meta == null) return null;

        UUID id = UUID.randomUUID();
        meta.getPersistentDataContainer().set(activeFellingKey, PersistentDataType.STRING, id.toString());
        tool.setItemMeta(meta);
        return id;
    }

    private void clearToolFellingTag(Player player, UUID fellingId) {
        if (player == null || fellingId == null) return;
        FoundTool found = findToolByFellingId(player, fellingId);
        if (found == null || found.stack == null) return;
        var meta = found.stack.getItemMeta();
        if (meta == null) return;
        meta.getPersistentDataContainer().remove(activeFellingKey);
        found.stack.setItemMeta(meta);
        found.writeBack(player, found.stack);
    }

    private static final class FoundTool {
        final int slot;
        final boolean offhand;
        final ItemStack stack;

        FoundTool(int slot, boolean offhand, ItemStack stack) {
            this.slot = slot;
            this.offhand = offhand;
            this.stack = stack;
        }

        void writeBack(Player player, ItemStack updated) {
            if (player == null) return;
            var inv = player.getInventory();
            if (offhand) {
                inv.setItemInOffHand(updated);
            } else if (slot >= 0) {
                inv.setItem(slot, updated);
            }
        }
    }

    private FoundTool findToolByFellingId(Player player, UUID fellingId) {
        if (player == null || fellingId == null) return null;
        var inv = player.getInventory();
        String target = fellingId.toString();

        ItemStack off = inv.getItemInOffHand();
        if (hasFellingId(off, target)) {
            return new FoundTool(-1, true, off);
        }

        ItemStack[] contents = inv.getContents();
        for (int i = 0; i < contents.length; i++) {
            ItemStack item = contents[i];
            if (hasFellingId(item, target)) {
                return new FoundTool(i, false, item);
            }
        }
        return null;
    }

    private boolean hasFellingId(ItemStack stack, String expectedId) {
        if (stack == null || stack.getType().isAir()) return false;
        var meta = stack.getItemMeta();
        if (meta == null) return false;
        String id = meta.getPersistentDataContainer().get(activeFellingKey, PersistentDataType.STRING);
        return expectedId.equals(id);
    }

    private void sendFellingAlreadyRunningActionbar(Player player) {
        if (player == null || !player.isOnline()) return;
        long now = System.currentTimeMillis();
        UUID uuid = player.getUniqueId();
        long last = lastFellingActionbarAt.getOrDefault(uuid, 0L);
        if ((now - last) < FELLING_ACTIONBAR_COOLDOWN_MS) return;
        lastFellingActionbarAt.put(uuid, now);
        try {
            player.sendActionBar(plugin.messages().component("ui.felling-already-running"));
        } catch (Throwable ignored) {
            // Best-effort only (compat across server APIs)
        }
    }

    private long key(Block b) {
        return key(b.getWorld(), b.getX(), b.getY(), b.getZ());
    }

    private long key(org.bukkit.World world, long x, long y, long z) {
        long worldHash = (world.getUID().getMostSignificantBits() ^ world.getUID().getLeastSignificantBits()) & 0xFFFFL;
        long coordPack = (x & 0x3FFFFFFL) << 38 | (z & 0x3FFFFFFL) << 12 | (y & 0xFFFL);
        return (worldHash << 48) ^ coordPack;
    }

    private long columnKey(org.bukkit.World world, int x, int z) {
        return key(world, x, 0, z);
    }

    private boolean withinRadius(int originX, int originY, int originZ, Block candidate,
                                 int horizontalRadius, int verticalRadius) {
        if (candidate == null) return false;
        int dx = Math.abs(candidate.getX() - originX);
        int dy = Math.abs(candidate.getY() - originY);
        int dz = Math.abs(candidate.getZ() - originZ);
        boolean horizontalOk = horizontalRadius <= 0 || Math.max(dx, dz) <= horizontalRadius;
        boolean verticalOk = verticalRadius <= 0 || dy <= verticalRadius;
        return horizontalOk && verticalOk;
    }
}

