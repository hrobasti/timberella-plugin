package net.kroet.timberella.listeners;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.junit.jupiter.api.Test;

/**
 * Collecting a tree must skip custom blocks of other plugins, and such a block
 * must not connect the logs behind it.
 */
class TreeCollectionTest {

    /** Blocks by position; everything else is air. */
    private static final class FakeWorld {
        final Map<List<Integer>, Material> types = new HashMap<>();
        final Map<List<Integer>, Boolean> custom = new HashMap<>();

        FakeWorld set(int x, int y, int z, Material type, boolean isCustom) {
            types.put(List.of(x, y, z), type);
            custom.put(List.of(x, y, z), isCustom);
            return this;
        }

        Block at(int x, int y, int z) {
            return (Block) Proxy.newProxyInstance(Block.class.getClassLoader(), new Class<?>[]{Block.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "getX" -> x;
                        case "getY" -> y;
                        case "getZ" -> z;
                        case "getType" -> types.getOrDefault(List.of(x, y, z), Material.AIR);
                        case "getRelative" -> at(x + (int) args[0], y + (int) args[1], z + (int) args[2]);
                        case "hashCode" -> List.of(x, y, z).hashCode();
                        case "equals" -> proxy == args[0];
                        default -> throw new UnsupportedOperationException(method.getName());
                    });
        }

        boolean isCustom(Block b) {
            return custom.getOrDefault(List.of(b.getX(), b.getY(), b.getZ()), false);
        }
    }

    private static long key(Block b) {
        return ((long) b.getX() & 0xFFFFF) << 40 | ((long) b.getY() & 0xFFFFF) << 20 | ((long) b.getZ() & 0xFFFFF);
    }

    private static Set<List<Integer>> collect(FakeWorld world, boolean diagonals) {
        Predicate<Block> joins = b -> b.getType() == Material.MUSHROOM_STEM && !world.isCustom(b);
        return TreeChopListener.collectConnected(world.at(0, 0, 0), 64, diagonals, joins, TreeCollectionTest::key)
                .stream()
                .map(b -> List.of(b.getX(), b.getY(), b.getZ()))
                .collect(Collectors.toSet());
    }

    @Test
    void customBlockInTheTrunkIsSkippedAndCutsTheConnection() {
        FakeWorld world = new FakeWorld();
        for (int y = 0; y <= 4; y++) {
            world.set(0, y, 0, Material.MUSHROOM_STEM, y == 2);
        }
        assertEquals(Set.of(List.of(0, 0, 0), List.of(0, 1, 0)), collect(world, false));
        assertEquals(Set.of(List.of(0, 0, 0), List.of(0, 1, 0)), collect(world, true));
    }

    @Test
    void customBlocksNextToTheTreeStayOut() {
        FakeWorld world = new FakeWorld()
                .set(0, 0, 0, Material.MUSHROOM_STEM, false)
                .set(0, 1, 0, Material.MUSHROOM_STEM, false)
                .set(1, 1, 0, Material.MUSHROOM_STEM, true)
                .set(2, 1, 0, Material.MUSHROOM_STEM, false);
        assertEquals(Set.of(List.of(0, 0, 0), List.of(0, 1, 0)), collect(world, false));
    }

    @Test
    void withoutCustomBlocksTheWholeTreeIsCollected() {
        FakeWorld world = new FakeWorld();
        for (int y = 0; y <= 4; y++) {
            world.set(0, y, 0, Material.MUSHROOM_STEM, false);
        }
        world.set(1, 5, 1, Material.MUSHROOM_STEM, false);
        assertEquals(6, collect(world, true).size());
        assertEquals(5, collect(world, false).size());
    }
}
