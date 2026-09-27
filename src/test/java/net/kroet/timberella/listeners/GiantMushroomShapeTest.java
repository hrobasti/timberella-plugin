package net.kroet.timberella.listeners;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.kroet.timberella.listeners.GiantMushroomShape.Cap;
import net.kroet.timberella.listeners.GiantMushroomShape.Column;
import net.kroet.timberella.listeners.GiantMushroomShape.Mushroom;
import net.kroet.timberella.listeners.GiantMushroomShape.Pos;
import org.junit.jupiter.api.Test;

/**
 * Giant mushrooms are placed like vanilla's huge mushroom features: a stem of
 * the given height and the brown flat cap or red dome on top, skipping blocks
 * that are already taken.
 */
class GiantMushroomShapeTest {
    // Natural heights: 4 to 6, doubled for one in twelve mushrooms.
    private static final List<Integer> HEIGHTS = List.of(4, 5, 6, 8, 10, 12);

    private final Set<Pos> stems = new HashSet<>();
    private final Map<Pos, Cap> caps = new HashMap<>();

    @Test
    void naturalMushroomsOfEveryHeightAreFoundWithTheirWholeCap() {
        for (Cap kind : Cap.values()) {
            for (int height : HEIGHTS) {
                stems.clear();
                caps.clear();
                grow(kind, 0, 0, height);
                for (Pos start : List.of(new Pos(0, 0, 0), new Pos(0, height - 1, 0), new Pos(0, height, 0))) {
                    Mushroom mushroom = find(start);
                    assertNotNull(mushroom, kind + " " + height + " from " + start);
                    assertEquals(kind, mushroom.kind());
                    assertEquals(new Column(0, 0, 0, height - 1), mushroom.column());
                    assertEquals(caps.keySet(), Set.copyOf(mushroom.cap()), kind + " " + height);
                }
            }
        }
    }

    @Test
    void redDomeIsFoundFromItsLowestSide() {
        grow(Cap.RED, 0, 0, 5);

        Mushroom mushroom = find(new Pos(2, 2, 0));

        assertNotNull(mushroom);
        assertEquals(45, mushroom.cap().size());
    }

    // The domes share their side wall at x = 2; it stays with whichever still
    // stands.
    @Test
    void redDomesFourApartShareAWallAndEachKeepsIt() {
        grow(Cap.RED, 0, 0, 5);
        grow(Cap.RED, 4, 0, 5);

        Mushroom left = find(new Pos(0, 0, 0));
        Mushroom right = find(new Pos(4, 0, 0));

        assertNotNull(left);
        assertNotNull(right);
        assertEquals(36, left.cap().size());
        assertEquals(36, right.cap().size());
        assertTrue(left.cap().stream().noneMatch(pos -> pos.x() >= 2));
        assertTrue(right.cap().stream().noneMatch(pos -> pos.x() <= 2));

        left.cap().forEach(caps::remove);
        stems.removeIf(pos -> pos.x() == 0);
        assertEquals(45, find(new Pos(4, 0, 0)).cap().size());
    }

    @Test
    void brownCapsTouchingAtTheSameHeightKeepTheirOwnCap() {
        grow(Cap.BROWN, 0, 0, 5);
        grow(Cap.BROWN, 7, 0, 5);
        grow(Cap.RED, 0, 6, 6);

        Mushroom brown = find(new Pos(0, 0, 0));
        Mushroom red = find(new Pos(0, 0, 6));

        assertNotNull(brown);
        assertNotNull(red);
        assertEquals(45, brown.cap().size());
        assertTrue(brown.cap().stream().allMatch(pos -> pos.x() <= 3));
        assertEquals(45, red.cap().size());
        assertEquals(new Column(7, 0, 0, 4), find(new Pos(4, 5, 0)).column());
        assertEquals(45, find(new Pos(7, 0, 0)).cap().size());
    }

    // Vanilla lets a shorter brown mushroom grow this close to a taller one.
    @Test
    void brownCapsAtDifferentHeightsCanStandCloser() {
        for (int distance : List.of(6, 4)) {
            stems.clear();
            caps.clear();
            grow(Cap.BROWN, 0, 0, 5);
            grow(Cap.BROWN, distance, 0, 6);

            Mushroom low = find(new Pos(0, 0, 0));
            Mushroom high = find(new Pos(distance, 0, 0));

            assertNotNull(low, "distance " + distance);
            assertNotNull(high, "distance " + distance);
            assertEquals(45, low.cap().size());
            assertEquals(45, high.cap().size());
            assertTrue(low.cap().stream().allMatch(pos -> pos.y() == 5));
        }
    }

    @Test
    void brownCapsTooCloseForVanillaAreABuild() {
        grow(Cap.BROWN, 0, 0, 5);
        grow(Cap.BROWN, 6, 0, 5);

        assertNull(find(new Pos(0, 0, 0)));
    }

    @Test
    void stemWallIsABuild() {
        for (int y = 0; y < 5; y++) {
            stems.add(new Pos(0, y, 0));
            stems.add(new Pos(1, y, 0));
        }
        caps.put(new Pos(0, 5, 0), Cap.BROWN);

        assertNull(find(new Pos(0, 0, 0)));
        assertNull(find(new Pos(0, 5, 0)));
    }

    @Test
    void stemTouchingTheColumnDiagonallyIsABuild() {
        grow(Cap.BROWN, 0, 0, 5);
        stems.add(new Pos(1, -1, 1));

        assertNull(find(new Pos(0, 2, 0)));
    }

    @Test
    void stemWithoutCapIsABuild() {
        for (int y = 0; y < 5; y++) {
            stems.add(new Pos(0, y, 0));
        }

        assertNull(find(new Pos(0, 0, 0)));
    }

    @Test
    void stemShorterThanVanillaIsABuild() {
        grow(Cap.BROWN, 0, 0, 3);

        assertNull(find(new Pos(0, 0, 0)));
    }

    @Test
    void capBlocksWithoutStemAreABuild() {
        for (int x = 0; x < 5; x++) {
            for (int y = 0; y < 3; y++) {
                caps.put(new Pos(x, y, 0), Cap.RED);
            }
        }

        assertNull(find(new Pos(2, 1, 0)));
    }

    @Test
    void roofOnPillarsFourApartIsABuild() {
        pillarsUnderRoof(4, 4);

        assertNull(find(new Pos(0, 0, 0)));
        assertNull(find(new Pos(4, 0, 4)));
        assertNull(find(new Pos(0, 4, 0)));
    }

    // Every pillar on its own looks natural, but the middle of the roof lies
    // outside every pillar's cap.
    @Test
    void roofOnPillarsEightApartIsABuild() {
        pillarsUnderRoof(8, 4);

        assertNull(find(new Pos(0, 0, 0)));
        assertNull(find(new Pos(8, 3, 8)));
        assertNull(find(new Pos(0, 4, 0)));
        assertNull(find(new Pos(4, 4, 4)));
    }

    @Test
    void capBlocksHangingBelowTheCapMakeItABuild() {
        grow(Cap.BROWN, 0, 0, 5);
        caps.put(new Pos(3, 4, 0), Cap.BROWN);
        caps.put(new Pos(3, 3, 0), Cap.BROWN);

        assertNull(find(new Pos(0, 0, 0)));
        assertNull(find(new Pos(3, 3, 0)));
    }

    @Test
    void capBlockEquallyNearTwoStemsIsLeftAlone() {
        grow(Cap.RED, 0, 0, 5);
        grow(Cap.RED, 4, 0, 5);

        assertNull(GiantMushroomShape.stemUnderCap(2, 4, 0, this::isRed, this::isStem, 2, 3, 1024));
    }

    @Test
    void columnTallerThanTheLimitIsABuild() {
        grow(Cap.BROWN, 0, 0, 12);

        assertNull(GiantMushroomShape.column(0, 0, 0, this::isStem, 11));
        assertEquals(new Column(0, 0, 0, 11), GiantMushroomShape.column(0, 0, 0, this::isStem, 12));
    }

    private Mushroom find(Pos start) {
        return GiantMushroomShape.find(start.x(), start.y(), start.z(), this::isStem,
                (x, y, z) -> caps.get(new Pos(x, y, z)), cap -> cap.radius, 1024);
    }

    private boolean isStem(int x, int y, int z) {
        return stems.contains(new Pos(x, y, z));
    }

    private boolean isRed(int x, int y, int z) {
        return caps.get(new Pos(x, y, z)) == Cap.RED;
    }

    // Four pillars of the given height at the corners of a square, under one flat
    // roof of brown cap blocks reaching one block past them.
    private void pillarsUnderRoof(int spacing, int height) {
        for (int x = 0; x <= spacing; x += spacing) {
            for (int z = 0; z <= spacing; z += spacing) {
                for (int y = 0; y < height; y++) {
                    stems.add(new Pos(x, y, z));
                }
            }
        }
        for (int x = -1; x <= spacing + 1; x++) {
            for (int z = -1; z <= spacing + 1; z++) {
                caps.put(new Pos(x, height, z), Cap.BROWN);
            }
        }
    }

    // Mirrors vanilla's HugeBrownMushroomFeature and HugeRedMushroomFeature, which
    // don't replace blocks that are already there.
    private void grow(Cap kind, int ox, int oz, int height) {
        for (int y = 0; y < height; y++) {
            stems.add(new Pos(ox, y, oz));
        }
        if (kind == Cap.BROWN) {
            for (int x = -3; x <= 3; x++) {
                for (int z = -3; z <= 3; z++) {
                    if (Math.abs(x) != 3 || Math.abs(z) != 3) {
                        place(new Pos(ox + x, height, oz + z), Cap.BROWN);
                    }
                }
            }
            return;
        }
        for (int y = height - 3; y <= height; y++) {
            int radius = y < height ? 2 : 1;
            for (int x = -radius; x <= radius; x++) {
                for (int z = -radius; z <= radius; z++) {
                    boolean xEdge = Math.abs(x) == radius;
                    boolean zEdge = Math.abs(z) == radius;
                    if (y >= height || xEdge != zEdge) {
                        place(new Pos(ox + x, y, oz + z), Cap.RED);
                    }
                }
            }
        }
    }

    private void place(Pos pos, Cap kind) {
        if (!stems.contains(pos)) {
            caps.putIfAbsent(pos, kind);
        }
    }
}
