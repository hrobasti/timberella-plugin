package net.kroet.timberella.compat;

import dev.lone.itemsadder.api.CustomBlock;
import dev.lone.itemsadder.api.Events.ItemsAdderLoadDataEvent;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

/**
 * ItemsAdder REAL blocks are mushroom block states. Until ItemsAdder has loaded
 * its data the API can't tell them apart, so no block counts as custom yet.
 */
final class ItemsAdderGuard implements CustomBlockGuard, Listener {

    private volatile boolean dataLoaded = blocksAlreadyRegistered();

    // Covers data loaded before this listener existed; an empty registry means no
    // custom blocks to protect either way.
    private static boolean blocksAlreadyRegistered() {
        try {
            return !CustomBlock.getNamespacedIdsInRegistry().isEmpty();
        } catch (RuntimeException e) {
            return false;
        }
    }

    @EventHandler
    public void onDataLoaded(ItemsAdderLoadDataEvent event) {
        dataLoaded = true;
    }

    @Override
    public boolean isCustomBlock(Block block) {
        return dataLoaded && CustomBlock.byAlreadyPlaced(block) != null;
    }
}
