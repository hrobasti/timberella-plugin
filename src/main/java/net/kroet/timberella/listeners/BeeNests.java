package net.kroet.timberella.listeners;

import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import io.papermc.paper.registry.keys.tags.EnchantmentTagKeys;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import org.bukkit.GameRules;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Beehive;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Directional;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Bee;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.BoundingBox;

/**
 * Bee nests and beehives felled with a tree treat their bees like vanilla's
 * BeehiveBlock does when a player breaks one.
 */
final class BeeNests {

    enum Outcome {
        /** Nothing happens to the bees, e.g. they go along in the Silk Touch drop. */
        KEEP,
        /** The bees fly out and turn on the player. */
        RELEASE,
        /** Creative mode: the nest drops as an item that holds its bees and honey. */
        DROP_NEST
    }

    private static final List<BlockFace> SIDES = List.of(BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH,
            BlockFace.WEST);

    private BeeNests() {
    }

    /**
     * Vanilla's rule: in creative mode a nest holding bees or honey drops if blocks
     * drop at all; otherwise the bees come out unless the tool keeps them (Silk
     * Touch). Package-private for BeeNestsTest.
     */
    static Outcome outcome(boolean creative, boolean toolKeepsBees, boolean hasBeesOrHoney, boolean blocksDrop) {
        if (creative) {
            return hasBeesOrHoney && blocksDrop ? Outcome.DROP_NEST : Outcome.KEEP;
        }
        return toolKeepsBees ? Outcome.KEEP : Outcome.RELEASE;
    }

    // Before the nest is removed, while its bees are still in it.
    static void beforeBreak(Block block, Player player, ItemStack tool, boolean creative) {
        if (!(block.getState() instanceof Beehive hive)) {
            return;
        }
        int honey = block.getBlockData() instanceof org.bukkit.block.data.type.Beehive data ? data.getHoneyLevel() : 0;
        boolean blocksDrop = Boolean.TRUE.equals(block.getWorld().getGameRuleValue(GameRules.BLOCK_DROPS));
        switch (outcome(creative, keepsBees(tool), hive.getEntityCount() > 0 || honey > 0, blocksDrop)) {
            case RELEASE -> release(block, player, hive.isSedated());
            case DROP_NEST -> dropNest(block);
            case KEEP -> {
            }
        }
    }

    private static boolean keepsBees(ItemStack tool) {
        if (tool == null || tool.isEmpty()) {
            return false;
        }
        Collection<Enchantment> keeping = RegistryAccess.registryAccess().getRegistry(RegistryKey.ENCHANTMENT)
                .getTagValues(EnchantmentTagKeys.PREVENTS_BEE_SPAWNS_WHEN_MINING);
        return tool.getEnchantments().keySet().stream().anyMatch(keeping::contains);
    }

    // The bees near the player go for them, unless a campfire calms the nest; then
    // every bee around without a target goes for a player nearby.
    private static void release(Block block, Player player, boolean sedated) {
        for (Bee bee : releaseAll(block)) {
            if (bee.getLocation().distanceSquared(player.getLocation()) <= 16.0) {
                if (sedated) {
                    bee.setCannotEnterHiveTicks(400);
                } else {
                    bee.setTarget(player);
                }
            }
        }
        World world = block.getWorld();
        BoundingBox area = BoundingBox.of(block).expand(8.0, 6.0, 8.0);
        Collection<Entity> bees = world.getNearbyEntities(area, entity -> entity instanceof Bee);
        List<Entity> players = new ArrayList<>(world.getNearbyEntities(area, entity -> entity instanceof Player));
        if (bees.isEmpty() || players.isEmpty()) {
            return;
        }
        for (Entity entity : bees) {
            Bee bee = (Bee) entity;
            if (bee.getTarget() == null) {
                bee.setTarget((Player) players.get(ThreadLocalRandom.current().nextInt(players.size())));
            }
        }
    }

    // The API only lets bees out of a free front, where vanilla lets them out
    // anyway; a blocked nest turns to each free side in turn first.
    private static List<Bee> releaseAll(Block block) {
        List<Bee> released = new ArrayList<>(((Beehive) block.getState()).releaseEntities());
        if (!(block.getBlockData() instanceof Directional nest)) {
            return released;
        }
        for (BlockFace side : SIDES) {
            if (((Beehive) block.getState()).getEntityCount() == 0) {
                break;
            }
            if (!block.getRelative(side).getCollisionShape().getBoundingBoxes().isEmpty()) {
                continue;
            }
            nest.setFacing(side);
            block.setBlockData(nest, false);
            released.addAll(((Beehive) block.getState()).releaseEntities());
        }
        return released;
    }

    // The item vanilla drops in creative mode is the one Silk Touch yields.
    private static void dropNest(Block block) {
        ItemStack silkTouch = ItemStack.of(Material.WOODEN_AXE);
        silkTouch.addUnsafeEnchantment(Enchantment.SILK_TOUCH, 1);
        for (ItemStack drop : block.getDrops(silkTouch)) {
            block.getWorld().dropItem(block.getLocation(), drop);
        }
    }
}
