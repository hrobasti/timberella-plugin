package net.kroet.timberella.listeners;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import net.kroet.timberella.listeners.TreeChopListener.ToolLocation;
import net.kroet.timberella.listeners.TreeChopListener.ToolPlace;
import org.junit.jupiter.api.Test;

/**
 * The tagged axe counts as carried in the off hand, the inventory, on the
 * cursor and in a crafting grid, searched in that order.
 */
class ToolLocationTest {

    private static final String AXE = "tagged-axe";
    private static final String[] EMPTY_GRID = {};

    private static ToolLocation locate(String offhand, String[] contents, String cursor, String[] grid) {
        return TreeChopListener.locateTool(offhand, contents, cursor, grid, AXE::equals);
    }

    @Test
    void findsTheAxeInEveryPlace() {
        assertEquals(new ToolLocation(ToolPlace.OFFHAND, -1),
                locate(AXE, new String[]{"dirt"}, null, EMPTY_GRID));
        assertEquals(new ToolLocation(ToolPlace.INVENTORY, 2),
                locate(null, new String[]{"dirt", null, AXE}, null, EMPTY_GRID));
        assertEquals(new ToolLocation(ToolPlace.CURSOR, -1),
                locate(null, new String[]{"dirt"}, AXE, EMPTY_GRID));
        assertEquals(new ToolLocation(ToolPlace.CRAFTING_GRID, 3),
                locate(null, new String[]{"dirt"}, null, new String[]{null, "stick", null, AXE}));
    }

    @Test
    void searchesOffhandThenInventoryThenCursorThenGrid() {
        String[] grid = {AXE};
        assertEquals(ToolPlace.OFFHAND, locate(AXE, new String[]{AXE}, AXE, grid).place());
        assertEquals(ToolPlace.INVENTORY, locate(null, new String[]{AXE}, AXE, grid).place());
        assertEquals(ToolPlace.CURSOR, locate(null, new String[]{}, AXE, grid).place());
    }

    @Test
    void axeElsewhereIsNotFound() {
        // e.g. put into a chest, dropped or lost on death
        assertNull(locate("shield", new String[]{"dirt", null}, null, EMPTY_GRID));
    }
}
