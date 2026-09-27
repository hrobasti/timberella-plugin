package net.kroet.timberella.compat;

import net.Indyuce.mmoitems.MMOItems;
import org.bukkit.block.Block;

/** MMOItems custom blocks are mushroom block states. */
final class MmoItemsGuard implements CustomBlockGuard {

    @Override
    public boolean isCustomBlock(Block block) {
        var blocks = MMOItems.plugin.getCustomBlocks();
        return blocks.isMushroomBlock(block.getType()) && blocks.getFromBlock(block.getBlockData()).isPresent();
    }
}
