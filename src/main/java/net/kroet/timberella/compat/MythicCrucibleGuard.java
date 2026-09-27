package net.kroet.timberella.compat;

import io.lumine.mythiccrucible.MythicCrucible;
import org.bukkit.block.Block;

/** MythicCrucible custom blocks are mushroom block states by default. */
final class MythicCrucibleGuard implements CustomBlockGuard {

    @Override
    public boolean isCustomBlock(Block block) {
        return MythicCrucible.inst().getItemManager().getCustomBlockManager().getBlockFromBlock(block).isPresent();
    }
}
