package net.kroet.timberella.compat;

import org.bukkit.block.Block;

/**
 * Tells whether a block belongs to another plugin that disguises its own blocks
 * as vanilla ones (mushroom block states, logs). Timberella never fells, decays
 * or replaces such blocks.
 */
@FunctionalInterface
public interface CustomBlockGuard {

    CustomBlockGuard NONE = block -> false;

    boolean isCustomBlock(Block block);
}
