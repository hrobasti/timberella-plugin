package net.kroet.timberella.compat;

import net.momirealms.craftengine.bukkit.api.CraftEngineBlocks;
import org.bukkit.block.Block;

/**
 * CraftEngine blocks show a configurable vanilla material, which may be a log.
 */
final class CraftEngineGuard implements CustomBlockGuard {

    @Override
    public boolean isCustomBlock(Block block) {
        return CraftEngineBlocks.isCustomBlock(block);
    }
}
