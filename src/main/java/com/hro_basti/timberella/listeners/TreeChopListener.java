package com.hro_basti.timberella.listeners;

import com.hro_basti.timberella.TimberellaPlugin;
import org.bukkit.Location;
import org.bukkit.Material;
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
import org.bukkit.inventory.meta.Damageable;

import java.io.File;
import java.util.*;

public class TreeChopListener implements Listener {
    private final TimberellaPlugin plugin;

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
            Material.PODZOL
    );
    private static final Set<Material> FUNGUS_SOILS = EnumSet.of(
            Material.CRIMSON_NYLIUM,
            Material.WARPED_NYLIUM,
            Material.NETHERRACK
    );

    private final Set<Material> normalLogs = new HashSet<>();
    private final Set<Material> strippedLogs = new HashSet<>();
    private final Set<Material> woods = new HashSet<>();
    private final Set<Material> strippedWoods = new HashSet<>();
    private final Set<Material> fences = new HashSet<>();
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
        loadMap("categories.stripped_logs", strippedLogs);
        loadMap("categories.woods", woods);
        loadMap("categories.stripped_woods", strippedWoods);
        loadMap("categories.fences", fences);
        loadLeafMappings();
        // modules
        timberEnabled = plugin.getConfig().getBoolean("enable_timber", true);
        leavesDecayEnabled = plugin.getConfig().getBoolean("enable_leaves_decay", true);
        leavesDecayRadius = Math.max(0, plugin.getConfig().getInt("leaves_decay.decay_radius", 5));
        leavesDecayIntervalTicks = Math.max(1L, plugin.getConfig().getLong("leaves_decay.batch_interval_ticks", 2L));
        leavesDecayBatchSize = Math.max(1, plugin.getConfig().getInt("leaves_decay.batch_size", 20));
        int configuredMaxDistance = plugin.getConfig().getInt("leaves_decay.max_distance", 4);
        leavesDecayMaxDistance = Math.max(1, configuredMaxDistance);
        leavesDecayMaxDistanceSquared = leavesDecayMaxDistance * leavesDecayMaxDistance;
        boolean replantGlobal = plugin.getConfig().getBoolean("enable_replant", true);
        boolean replantSection = plugin.getConfig().getBoolean("replant.enabled", true);
        replantEnabled = replantGlobal && replantSection;
        maxBlocks = Math.max(1, plugin.getConfig().getInt("max_blocks", 1024));
        sneakMode = plugin.getConfig().getInt("sneak_mode", 0);
        includeDiagonals = plugin.getConfig().getBoolean("include_diagonals",
            plugin.getConfig().getInt("adjacency_faces", 26) > 6);

        // tools
        List<String> axes = plugin.getConfig().getStringList("tools.allowed_axes");
        for (String name : axes) {
            Material m = Material.matchMaterial(name.toUpperCase(Locale.ROOT));
            if (m != null) allowedAxes.add(m);
        }
        minRemainingDurability = Math.max(0, plugin.getConfig().getInt("tools.min_remaining_durability", 10));
        String mode = plugin.getConfig().getString("tools.durability_mode", "first");
        durabilityModeAll = mode != null && mode.equalsIgnoreCase("all");
        durabilityMultiplier = plugin.getConfig().getDouble("tools.durability_multiplier", 0.5);
        breakIntervalTicks = Math.max(1L, plugin.getConfig().getLong("break_interval_ticks", 2L));
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

        if (hasTimberPermission && timberEnabled) {
            List<Block> sequence = collectConnectedLogs(start, maxBlocks, includeDiagonals);
            if (sequence.isEmpty()) {
                sequence = Collections.singletonList(start);
            }
            try {
                var loc = start.getLocation().add(0.5, 0.5, 0.5);
                start.getWorld().spawnParticle(Particle.SWEEP_ATTACK, loc, 1, 0, 0, 0, 0);
            } catch (Throwable ignored) {}
            if (sequence.size() <= 1) {
                applyDurabilityCost(player, tool, sequence.size());
                handlePostActions(player, tool, sequence, false);
                return;
            }
            final List<Block> allLogs = new ArrayList<>(sequence);
            final List<Block> toBreak = new ArrayList<>(sequence.subList(1, sequence.size()));
            final ItemStack usedTool = tool;
            final Player p = player;
            final long interval = breakIntervalTicks;
            new BukkitRunnable() {
                int idx = 0;
                @Override
                public void run() {
                    if (idx >= toBreak.size()) {
                        applyDurabilityCost(p, usedTool, allLogs.size());
                        handlePostActions(p, usedTool, allLogs, true);
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
        handlePostActions(player, tool, single, false);
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
        return false;
    }

    private List<Block> collectConnectedLogs(Block start, int maxBlocks, boolean includeDiagonals) {
        Queue<Block> queue = new ArrayDeque<>();
        Set<Long> visited = new HashSet<>();
        List<Block> result = new ArrayList<>();
        queue.add(start);
        visited.add(key(start));
        while (!queue.isEmpty() && result.size() < maxBlocks) {
            Block b = queue.poll();
            if (!isTreeMaterial(b.getType())) continue;
            result.add(b);
            if (!includeDiagonals) {
                int[][] dirs = {
                        {1,0,0},{-1,0,0},{0,1,0},{0,-1,0},{0,0,1},{0,0,-1}
                };
                for (int[] d : dirs) {
                    Block n = b.getRelative(d[0], d[1], d[2]);
                    if (!isTreeMaterial(n.getType())) continue;
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
                            long k = key(n);
                            if (visited.add(k)) queue.add(n);
                        }
                    }
                }
            }
        }
        return result;
    }

    private void handlePostActions(Player player, ItemStack tool, List<Block> logs, boolean performedTimber) {
        if (logs == null || logs.isEmpty()) return;
        if (leavesDecayEnabled) {
            Block origin = logs.get(0);
            scheduleLeavesDecay(player, logs, origin);
        }
        if (performedTimber && replantEnabled) {
            tryReplant(logs);
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

    private void tryReplant(List<Block> logs) {
        if (!replantEnabled) return;
        if (logs == null || logs.isEmpty()) return;

        Block best = null;
        Material sapling = null;
        for (Block log : logs) {
            Material mapped = saplingMappings.get(log.getType());
            if (mapped == null) continue;
            if (!allowedSaplings.isEmpty() && !allowedSaplings.contains(mapped)) continue;
            Block soil = log.getRelative(0, -1, 0);
            if (!isSuitableSoil(soil.getType(), mapped)) continue;
            if (best == null || log.getY() < best.getY()) {
                best = log;
                sapling = mapped;
            }
        }
        if (best == null || sapling == null) return;

        Location targetLoc = best.getLocation();
        Material finalSapling = sapling;
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            Block target = targetLoc.getBlock();
            Block soil = target.getRelative(0, -1, 0);
            if (!isSuitableSoil(soil.getType(), finalSapling)) return;
            Material current = target.getType();
            boolean targetIsWater = current == Material.WATER;
            if (!current.isAir() && !targetIsWater && !target.isPassable()) return;
            target.setType(finalSapling);
            if (finalSapling == Material.MANGROVE_PROPAGULE) {
                var data = target.getBlockData();
                if (data instanceof Waterlogged waterlogged) {
                    waterlogged.setWaterlogged(targetIsWater);
                    target.setBlockData(waterlogged);
                }
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

    private void applyDurabilityCost(Player player, ItemStack tool, int brokenBlocks) {
        if (!durabilityModeAll) return; // vanilla first-only
        if (tool == null) return;
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
        // ensure inventory updates
        player.getInventory().setItemInMainHand(tool);
    }

    private long key(Block b) {
        long x = b.getX();
        long y = b.getY();
        long z = b.getZ();
        // world hash (mix both UUID parts for better spread) truncated to 16 bits
        long worldHash = (b.getWorld().getUID().getMostSignificantBits() ^ b.getWorld().getUID().getLeastSignificantBits()) & 0xFFFFL;
        long coordPack = (x & 0x3FFFFFFL) << 38 | (z & 0x3FFFFFFL) << 12 | (y & 0xFFFL);
        return (worldHash << 48) ^ coordPack; // combine ensuring difference across worlds
    }
}
