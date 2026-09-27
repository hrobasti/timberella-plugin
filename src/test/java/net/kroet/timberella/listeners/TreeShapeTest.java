package net.kroet.timberella.listeners;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.IntBinaryOperator;
import net.kroet.timberella.listeners.TreeShape.Footprint;
import net.kroet.timberella.listeners.TreeShape.Part;
import net.kroet.timberella.listeners.TreeShape.Pos;
import net.kroet.timberella.listeners.TreeShape.Result;
import net.kroet.timberella.listeners.TreeShape.Support;
import org.junit.jupiter.api.Test;

/**
 * Trees are shaped like the ones vanilla's trunk placers grow, on flat or
 * uneven ground; builds are log walls and huts placed against them.
 */
class TreeShapeTest implements TreeShape.Surroundings {
    private final Map<Pos, Part> blocks = new HashMap<>();
    private final Set<Pos> leaves = new HashSet<>();
    // Blocks of a tree felled just before; leaves touching them are its crown.
    private final Set<Pos> felled = new HashSet<>();
    private final Set<Pos> beyondLimits = new HashSet<>();
    // Top ground block per column: flat at y = -1 unless a test says otherwise.
    private IntBinaryOperator groundTop = (x, z) -> -1;
    // Ground no trunk grows from, like stone or grass; the rest is dirt.
    private final Set<Pos> stone = new HashSet<>();
    private Footprint footprint = Footprint.SINGLE;
    private boolean diagonals = true;
    private boolean protectBuilds = true;

    @Test
    void wallTouchingTheTrunkStaysStanding() {
        Set<Pos> trunk = fill(0, 0, 0, 0, 5, 0);
        Set<Pos> wall = fill(-5, 0, 0, -1, 2, 0);

        for (Pos hit : List.of(new Pos(0, 0, 0), new Pos(0, 2, 0))) {
            Result result = analyze(hit);
            assertEquals(trunk, Set.copyOf(result.kept()), "hit " + hit);
            assertEquals(wall, result.excluded());
            assertEquals(List.of(new Pos(0, 0, 0)), result.plantingSpots());
        }
    }

    @Test
    void wallTouchingTheTrunkFaceToFaceStaysStandingToo() {
        diagonals = false;
        Set<Pos> trunk = fill(0, 0, 0, 0, 5, 0);
        Set<Pos> wall = fill(-5, 0, 0, -1, 2, 0);

        Result result = analyze(new Pos(0, 1, 0));

        assertEquals(trunk, Set.copyOf(result.kept()));
        assertEquals(wall, result.excluded());
    }

    @Test
    void hittingTheWallTakesNothingOfTheTree() {
        Set<Pos> trunk = fill(0, 0, 0, 0, 5, 0);
        fill(-5, 0, 0, -1, 2, 0);

        Result nextToTrunk = analyze(new Pos(-1, 1, 0));
        Result farEnd = analyze(new Pos(-5, 2, 0));

        assertEquals(Set.of(new Pos(-1, 0, 0), new Pos(-1, 1, 0), new Pos(-1, 2, 0)), Set.copyOf(nextToTrunk.kept()));
        assertTrue(nextToTrunk.excluded().containsAll(trunk));
        assertEquals(Set.of(new Pos(-5, 0, 0), new Pos(-5, 1, 0), new Pos(-5, 2, 0)), Set.copyOf(farEnd.kept()));
    }

    // The roof reaches over to the trunk, but rests on the hut's walls.
    @Test
    void hutTouchingTheTrunkStaysWithItsRoof() {
        Set<Pos> trunk = fill(0, 0, 0, 0, 7, 0);
        Set<Pos> hut = hollowFill(2, 0, -3, 6, 3, 3);
        hut.addAll(fill(1, 4, -3, 6, 4, 3));

        Result result = analyze(new Pos(0, 1, 0));

        assertEquals(trunk, Set.copyOf(result.kept()));
        assertEquals(hut, result.excluded());
        assertNull(analyze(new Pos(1, 4, 0)));
    }

    @Test
    void fancyOakFallsWholeFromTrunkOrBranchOnUnevenGround() {
        groundTop = (x, z) -> x >= 1 ? 0 : -1;
        Set<Pos> tree = fill(0, 0, 0, 0, 9, 0);
        tree.addAll(logs(new Pos(1, 5, 0), new Pos(2, 5, 0), new Pos(3, 6, 0), new Pos(4, 7, 1)));
        tree.addAll(logs(new Pos(-1, 6, -1), new Pos(-2, 7, -2), new Pos(-3, 7, -2)));

        for (Pos hit : List.of(new Pos(0, 1, 0), new Pos(4, 7, 1), new Pos(-3, 7, -2))) {
            Result result = analyze(hit);
            assertEquals(tree, Set.copyOf(result.kept()), "hit " + hit);
            assertEquals(List.of(new Pos(0, 0, 0)), result.plantingSpots());
        }
    }

    @Test
    void acaciaFallsWholeFromTheTopOfItsBentTrunk() {
        groundTop = (x, z) -> x >= 1 ? 1 : -1;
        Set<Pos> tree = fill(0, 0, 0, 0, 3, 0);
        tree.addAll(logs(new Pos(1, 4, 0), new Pos(2, 5, 0), new Pos(3, 6, 0)));
        tree.addAll(logs(new Pos(-1, 4, -1), new Pos(-2, 5, -2)));

        Result result = analyze(new Pos(3, 6, 0));

        assertEquals(tree, Set.copyOf(result.kept()));
        assertEquals(List.of(new Pos(0, 0, 0)), result.plantingSpots());
    }

    // Terrain raised right up to the lowest branch logs, as on a slope or with a
    // pillar placed under them later.
    @Test
    void branchesRestingOnTheGroundFallWithTheTree() {
        Set<Pos> fancyOak = fill(0, 0, 0, 0, 9, 0);
        fancyOak.addAll(logs(new Pos(1, 5, 0), new Pos(2, 5, 0), new Pos(3, 6, 0), new Pos(4, 7, 1)));
        fancyOak.addAll(logs(new Pos(-1, 6, -1), new Pos(-2, 7, -2), new Pos(-3, 7, -2)));
        groundTop = (x, z) -> x == 1 && z == 0 ? 4 : x == -1 && z == -1 ? 5 : -1;

        for (Pos hit : List.of(new Pos(0, 0, 0), new Pos(1, 5, 0), new Pos(4, 7, 1))) {
            Result result = analyze(hit);
            assertEquals(fancyOak, Set.copyOf(result.kept()), "hit " + hit);
            assertEquals(List.of(new Pos(0, 0, 0)), result.plantingSpots());
        }
    }

    @Test
    void bentTrunkRestingOnTheGroundFallsWithTheTree() {
        Set<Pos> acacia = fill(0, 0, 0, 0, 3, 0);
        acacia.addAll(logs(new Pos(1, 4, 0), new Pos(2, 5, 0), new Pos(3, 6, 0)));
        acacia.addAll(logs(new Pos(-1, 4, 0), new Pos(-2, 5, 0)));
        groundTop = (x, z) -> x == 1 || x == -1 ? 3 : -1;

        assertEquals(acacia, Set.copyOf(analyze(new Pos(0, 0, 0)).kept()));
    }

    @Test
    void logsLyingBesideTheTrunkBaseOrBelowItStay() {
        groundTop = (x, z) -> x <= -4 ? -2 : -1;
        Set<Pos> trunk = fill(0, 0, 0, 0, 5, 0);
        Set<Pos> bench = fill(-3, 0, 0, -1, 0, 0);
        Set<Pos> lowerBench = fill(-6, -1, 0, -4, -1, 0);

        Result result = analyze(new Pos(0, 0, 0));

        assertEquals(trunk, Set.copyOf(result.kept()));
        assertEquals(union(bench, lowerBench), result.excluded());
    }

    // The price of letting branches rest on the ground: a single row of logs lying
    // higher than the trunk base passes for one.
    @Test
    void singleRowOfLogsHigherThanTheTrunkBaseFallsWithTheTree() {
        groundTop = (x, z) -> x != 0 ? 0 : -1;
        Set<Pos> tree = fill(0, 0, 0, 0, 5, 0);
        tree.addAll(fill(-3, 1, 0, -1, 1, 0));
        Set<Pos> wall = fill(1, 1, 0, 3, 2, 0);

        Result result = analyze(new Pos(0, 0, 0));

        assertEquals(tree, Set.copyOf(result.kept()));
        assertEquals(wall, result.excluded());
    }

    @Test
    void darkOakFallsWholeWithItsLeanAndHangingBranches() {
        Set<Pos> tree = darkOak();

        for (Pos hit : List.of(new Pos(0, 0, 0), new Pos(-1, 2, 0), new Pos(2, 6, 1))) {
            Result result = analyze(hit);
            assertEquals(tree, Set.copyOf(result.kept()), "hit " + hit);
            assertEquals(Set.of(new Pos(0, 0, 0), new Pos(1, 0, 0), new Pos(0, 0, 1), new Pos(1, 0, 1)),
                    Set.copyOf(result.plantingSpots()));
        }
    }

    // Vanilla ends each hanging branch with leaves on top; on a slope, or with a
    // pillar placed under it, the branch rests on the ground.
    @Test
    void darkOakBranchesRestingOnTheGroundFallWithTheTree() {
        groundTop = (x, z) -> x == -1 && z == 0 ? 1 : x == 2 && z == 2 ? 2 : -1;
        Set<Pos> tree = darkOak();
        leaves.addAll(List.of(new Pos(-1, 6, 0), new Pos(0, 6, -1), new Pos(2, 6, 2)));

        assertEquals(tree, Set.copyOf(analyze(new Pos(0, 0, 0)).kept()));
    }

    @Test
    void postStandingUphillOfTheTrunkStays() {
        groundTop = (x, z) -> x == 1 ? 0 : -1;
        Set<Pos> trunk = fill(0, 0, 0, 0, 5, 0);
        Set<Pos> post = fill(1, 1, 0, 1, 3, 0);

        Result result = analyze(new Pos(0, 0, 0));

        assertEquals(trunk, Set.copyOf(result.kept()));
        assertEquals(post, result.excluded());
    }

    // The price of letting branches rest on the ground: a post uphill that reaches
    // into the crown passes for one of the tree's branches.
    @Test
    void postUphillReachingIntoTheCrownFallsWithTheTree() {
        groundTop = (x, z) -> x == 1 ? 0 : -1;
        Set<Pos> tree = fill(0, 0, 0, 0, 5, 0);
        tree.addAll(fill(1, 1, 0, 1, 3, 0));
        leaves.add(new Pos(1, 4, 0));

        assertEquals(tree, Set.copyOf(analyze(new Pos(0, 0, 0)).kept()));
    }

    @Test
    void wallAtTheTrunkBaseStaysEvenUnderTheCrown() {
        Set<Pos> trunk = fill(0, 0, 0, 0, 5, 0);
        Set<Pos> wall = fill(-3, 0, 0, -1, 2, 0);
        crown(-3, 3, -2, 2, 6, 2);

        Result result = analyze(new Pos(0, 0, 0));

        assertEquals(trunk, Set.copyOf(result.kept()));
        assertEquals(wall, result.excluded());
    }

    // Hitting a hanging branch that rests on the ground still finds the trunk.
    @Test
    void hitOnARestingDarkOakBranchFellsTheWholeTree() {
        groundTop = (x, z) -> x == -1 && z == 0 ? 1 : -1;
        Set<Pos> tree = darkOak();
        leaves.addAll(List.of(new Pos(-1, 6, 0), new Pos(0, 6, -1), new Pos(2, 6, 2)));
        crown(0, 7, -1, 3, 7, 2);

        Result result = analyze(new Pos(-1, 3, 0));

        assertEquals(tree, Set.copyOf(result.kept()));
        assertEquals(Set.of(new Pos(0, 0, 0), new Pos(1, 0, 0), new Pos(0, 0, 1), new Pos(1, 0, 1)),
                Set.copyOf(result.plantingSpots()));
    }

    // CherryTrunkPlacer: a branch going out from the trunk and up at its end, where
    // the leaves sit; the end rests on raised ground.
    @Test
    void hitOnARestingCherryBranchFellsTheWholeTree() {
        groundTop = (x, z) -> x == 2 && z == 0 ? 3 : -1;
        Set<Pos> tree = fill(0, 0, 0, 0, 4, 0);
        tree.addAll(logs(new Pos(1, 4, 0)));
        tree.addAll(fill(2, 4, 0, 2, 6, 0));
        crown(1, 7, -1, 3, 8, 1);

        Result result = analyze(new Pos(2, 5, 0));

        assertEquals(tree, Set.copyOf(result.kept()));
        assertEquals(List.of(new Pos(0, 0, 0)), result.plantingSpots());
    }

    // A wall downhill of an oak stands lower than its trunk, which carries the
    // crown and reaches higher.
    @Test
    void wallDownhillOfATreeFellsNothingOfItWhenHit() {
        groundTop = (x, z) -> x < 0 ? -2 : -1;
        Set<Pos> trunk = fill(0, 0, 0, 0, 4, 0);
        Set<Pos> wall = fill(-3, -1, 0, -1, 1, 0);
        crown(-2, 3, -2, 2, 6, 2);

        Result tree = analyze(new Pos(0, 1, 0));

        assertNull(analyze(new Pos(-1, 0, 0)));
        assertEquals(fill(-3, -1, 0, -3, 1, 0), Set.copyOf(analyze(new Pos(-3, 1, 0)).kept()));
        assertEquals(trunk, Set.copyOf(tree.kept()));
        assertEquals(wall, tree.excluded());
    }

    // ForkingTrunkPlacer: a short bare trunk base bending over, the bent part
    // resting on a stone pillar.
    @Test
    void acaciaWithItsBentTrunkOnStoneFallsFromItsBase() {
        groundTop = (x, z) -> x == -1 && z == 0 ? 1 : -1;
        stone.add(new Pos(-1, 1, 0));
        Set<Pos> tree = fill(0, 0, 0, 0, 1, 0);
        tree.addAll(fill(-1, 2, 0, -1, 4, 0));
        crown(-3, 5, -2, 1, 6, 2);

        for (Pos hit : List.of(new Pos(0, 0, 0), new Pos(-1, 3, 0))) {
            Result result = analyze(hit);
            assertEquals(tree, Set.copyOf(result.kept()), "hit " + hit);
            assertEquals(List.of(new Pos(0, 0, 0)), result.plantingSpots());
        }
    }

    // CherryTrunkPlacer: the trunk ends in a branch going out level with its top
    // log, then up; each step rests on a stone pillar.
    @Test
    void cherryWithItsBranchOnStoneBesideTheTrunkTopFallsFromItsBase() {
        groundTop = (x, z) -> z != 0 ? -1 : x == -1 ? 2 : x == -2 ? 5 : -1;
        stone.addAll(List.of(new Pos(-1, 2, 0), new Pos(-2, 5, 0)));
        Set<Pos> tree = fill(0, 0, 0, 0, 3, 0);
        tree.addAll(fill(-1, 3, 0, -1, 6, 0));
        tree.addAll(fill(-2, 6, 0, -2, 7, 0));
        tree.addAll(logs(new Pos(-3, 7, 0)));
        crown(-5, 7, -2, 0, 9, 2);

        Result result = analyze(new Pos(0, 0, 0));

        assertEquals(tree, Set.copyOf(result.kept()));
        assertEquals(List.of(new Pos(0, 0, 0)), result.plantingSpots());
    }

    // The lowest segment of a cherry branch, two logs on a stone pillar, holds up
    // the rest of the branch, which hangs and ends in leaves.
    @Test
    void cherryBranchWithABareSegmentOnStoneFallsWithTheTree() {
        groundTop = (x, z) -> x == 0 && (z == 1 || z == 2) ? 2 : -1;
        stone.addAll(List.of(new Pos(0, 2, 1), new Pos(0, 2, 2)));
        Set<Pos> tree = fill(0, 0, 0, 0, 5, 0);
        tree.addAll(logs(new Pos(0, 3, 1)));
        tree.addAll(fill(0, 3, 2, 0, 4, 2));
        tree.addAll(fill(0, 4, 3, 0, 6, 3));
        tree.addAll(logs(new Pos(0, 6, 4), new Pos(0, 6, 5)));
        crown(-2, 6, -2, 2, 8, 1);
        crown(-2, 7, 3, 2, 8, 6);

        Result result = analyze(new Pos(0, 0, 0));

        assertEquals(tree, Set.copyOf(result.kept()));
        assertEquals(Set.of(), result.excluded());
    }

    // A trunk on dirt may be a tree of its own: a wall downhill whose top only
    // reaches its lowest log, diagonally or level with it, is still a post.
    @Test
    void wallDownhillMeetingOnlyTheTrunkBaseStaysAPost() {
        for (int wallTop : List.of(-1, 0)) {
            blocks.clear();
            leaves.clear();
            int top = wallTop;
            groundTop = (x, z) -> x < 0 ? top - 2 : -1;
            Set<Pos> trunk = fill(0, 0, 0, 0, 4, 0);
            Set<Pos> wall = fill(-3, top - 1, 0, -1, top, 0);
            crown(-2, 3, -2, 2, 6, 2);

            assertNull(analyze(new Pos(-1, top, 0)), "wall top " + top);
            Result tree = analyze(new Pos(0, 1, 0));
            assertEquals(trunk, Set.copyOf(tree.kept()), "wall top " + top);
            assertEquals(wall, tree.excluded(), "wall top " + top);
        }
    }

    // Standing side by side with a branch that rests on stone, the post holds none
    // of it up.
    @Test
    void postBesideABranchRestingOnStoneStaysAPost() {
        groundTop = (x, z) -> x == 2 && z == 0 ? 1 : -1;
        stone.add(new Pos(2, 1, 0));
        Set<Pos> trunk = fill(0, 0, 0, 0, 6, 0);
        logs(new Pos(1, 5, 0));
        fill(2, 2, 0, 2, 4, 0);
        Set<Pos> post = fill(3, 0, 0, 3, 3, 0);
        leaves.addAll(List.of(new Pos(0, 7, 0), new Pos(1, 6, 0), new Pos(2, 5, 0), new Pos(-1, 7, 0)));

        assertNull(analyze(new Pos(3, 0, 0)));
        Result result = analyze(new Pos(0, 0, 0));
        assertTrue(result.kept().containsAll(trunk));
        assertTrue(result.excluded().containsAll(post));
    }

    // A bare stack uphill holding up hanging logs without any leaves, like a roof,
    // is still a build.
    @Test
    void postUphillHoldingUpABareBeamStays() {
        groundTop = (x, z) -> x >= 1 ? 0 : -1;
        stone.add(new Pos(1, 0, 0));
        Set<Pos> trunk = fill(0, 0, 0, 0, 5, 0);
        Set<Pos> post = fill(1, 1, 0, 1, 3, 0);
        post.addAll(fill(2, 3, 0, 4, 3, 0));
        crown(-2, 6, -2, 2, 7, 2);

        Result result = analyze(new Pos(0, 0, 0));

        assertEquals(trunk, Set.copyOf(result.kept()));
        assertEquals(post, result.excluded());
    }

    // Taller than any branch that could rest on the ground: a trunk of its own.
    @Test
    void neighbouringTreeStandingHigherStays() {
        groundTop = (x, z) -> x == 1 ? 0 : -1;
        Set<Pos> felled = fill(0, 0, 0, 0, 4, 0);
        Set<Pos> neighbour = fill(1, 1, 0, 1, 7, 0);
        leaves.addAll(List.of(new Pos(0, 5, 0), new Pos(1, 8, 0)));

        Result result = analyze(new Pos(0, 0, 0));

        assertEquals(felled, Set.copyOf(result.kept()));
        assertEquals(neighbour, result.excluded());
        assertEquals(neighbour, Set.copyOf(analyze(new Pos(1, 1, 0)).kept()));
    }

    // Another mangrove's trunk on its roots stays, even standing higher and short
    // enough to pass for a resting branch.
    @Test
    void neighbouringMangroveStandingHigherStays() {
        Set<Pos> felled = mangrove(0);
        Set<Pos> neighbour = mangrove(6, 3, 4);
        leaves.add(new Pos(6, 7, 0));
        felled.add(root(3, 1, 0));

        Result result = analyze(new Pos(0, 4, 0));

        assertEquals(felled, Set.copyOf(result.kept()));
        assertEquals(neighbour, result.excluded());
    }

    @Test
    void megaSpruceStandsApartFromAWallAlongItsSide() {
        footprint = Footprint.SQUARE;
        Set<Pos> trunk = fill(0, 0, 0, 1, 14, 1);
        Set<Pos> wall = fill(2, 0, -2, 2, 3, 3);

        Result tree = analyze(new Pos(0, 0, 0));
        Result wallHit = analyze(new Pos(2, 1, 0));

        assertEquals(trunk, Set.copyOf(tree.kept()));
        assertEquals(wall, tree.excluded());
        assertEquals(Set.of(new Pos(0, 0, 0), new Pos(1, 0, 0), new Pos(0, 0, 1), new Pos(1, 0, 1)),
                Set.copyOf(tree.plantingSpots()));
        assertEquals(fill(2, 0, 0, 2, 3, 0), Set.copyOf(wallHit.kept()));
        assertEquals(List.of(new Pos(2, 0, 0)), wallHit.plantingSpots());
    }

    @Test
    void megaSprucesSideBySideFallOneByOne() {
        footprint = Footprint.SQUARE;
        Set<Pos> small = fill(0, 0, 0, 1, 11, 1);
        Set<Pos> tall = fill(2, 0, 0, 3, 15, 1);

        assertEquals(small, Set.copyOf(analyze(new Pos(1, 0, 0)).kept()));
        assertEquals(tall, Set.copyOf(analyze(new Pos(2, 0, 0)).kept()));
    }

    @Test
    void mangroveFallsWithItsRootsFromTrunkOrRoot() {
        Set<Pos> tree = mangrove(0);

        for (Pos hit : List.of(new Pos(0, 5, 0), new Pos(2, 1, 0), new Pos(0, 0, 2))) {
            Result result = analyze(hit);
            assertEquals(tree, Set.copyOf(result.kept()), "hit " + hit);
            assertEquals(List.of(new Pos(0, 1, 0)), result.plantingSpots());
        }
    }

    @Test
    void neighbouringMangroveKeepsItsTrunkAndNearerRoots() {
        Set<Pos> felled = mangrove(0);
        Set<Pos> neighbour = mangrove(6);
        // The root systems meet halfway; the middle root goes with the felled tree.
        felled.add(root(3, 0, 0));
        neighbour.add(root(4, 1, 0));

        Result result = analyze(new Pos(0, 4, 0));

        assertEquals(felled, Set.copyOf(result.kept()));
        assertEquals(neighbour, result.excluded());
    }

    // Branches growing from the trunk's foot start right above the roots beside it,
    // one log high, or two where two branches go the same way.
    @Test
    void mangroveBranchesStandingOnTheirOwnRootsFallWithTheTree() {
        Set<Pos> tree = mangrove(0);
        tree.addAll(logs(new Pos(1, 3, 0), new Pos(2, 4, 0), new Pos(3, 5, 0)));
        tree.addAll(logs(new Pos(0, 3, -1), new Pos(0, 4, -1), new Pos(0, 5, -2)));

        Result result = analyze(new Pos(0, 5, 0));

        assertEquals(tree, Set.copyOf(result.kept()));
        assertEquals(List.of(new Pos(0, 1, 0)), result.plantingSpots());
    }

    @Test
    void mangrovesFourApartWithMeetingRootsFallOneByOne() {
        Set<Pos> felled = mangrove(0);
        felled.addAll(logs(new Pos(1, 3, 0)));
        Set<Pos> neighbour = mangrove(4);
        neighbour.addAll(logs(new Pos(3, 3, 0)));
        neighbour.removeAll(felled);

        Result result = analyze(new Pos(0, 5, 0));

        assertEquals(felled, Set.copyOf(result.kept()));
        assertEquals(neighbour, result.excluded());
    }

    @Test
    void wallUnderTheCrownHoldsNoneOfItsLeaves() {
        Set<Pos> trunk = fill(0, 0, 0, 0, 5, 0);
        Set<Pos> wall = fill(-5, 0, 0, -1, 2, 0);
        crown(-2, 2, -2, 2, 6, 2);

        Result result = analyze(new Pos(0, 0, 0));

        assertEquals(trunk, Set.copyOf(result.kept()));
        assertEquals(wall, result.builds());
    }

    @Test
    void hutWithItsRoofUnderTheCrownHoldsNoneOfItsLeaves() {
        fill(0, 0, 0, 0, 7, 0);
        Set<Pos> hut = hollowFill(2, 0, -3, 6, 3, 3);
        hut.addAll(fill(1, 4, -3, 6, 4, 3));
        crown(-2, 4, -2, 2, 8, 2);

        assertEquals(hut, analyze(new Pos(0, 1, 0)).builds());
    }

    // A bare stub right under the crown has the felled tree's leaves around it,
    // but no crown of its own.
    @Test
    void bareStubUnderTheCrownHoldsNoneOfItsLeaves() {
        groundTop = (x, z) -> x == 1 && z == 0 ? 0 : -1;
        Set<Pos> trunk = fill(0, 0, 0, 0, 5, 0);
        Set<Pos> stub = fill(1, 1, 0, 1, 3, 0);
        crown(-2, 2, -2, 2, 7, 2);
        leaves.remove(new Pos(1, 4, 0));

        Result result = analyze(new Pos(0, 0, 0));

        assertEquals(trunk, Set.copyOf(result.kept()));
        assertEquals(stub, result.builds());
    }

    // A wall of another wood under the felled crown: not collected with the tree,
    // so it gets its own look once the tree is gone.
    @Test
    void otherWoodWallUnderTheFelledCrownIsNoTree() {
        felled.addAll(List.of(new Pos(0, 0, 0), new Pos(0, 1, 0), new Pos(0, 2, 0), new Pos(0, 3, 0)));
        fill(-4, 0, 0, -1, 2, 0);
        crown(-2, 1, -2, 2, 5, 2);
        leaves.removeAll(felled);

        assertEquals(Set.of(), TreeShape.standingTrees(collect(new Pos(-1, 0, 0)), this, diagonals));
    }

    @Test
    void otherWoodNeighbourTreeKeepsHoldingItsLeaves() {
        felled.addAll(List.of(new Pos(0, 0, 0), new Pos(0, 1, 0), new Pos(0, 2, 0), new Pos(0, 3, 0)));
        Set<Pos> birch = fill(3, 0, 0, 3, 5, 0);
        crown(1, 3, -2, 5, 7, 2);
        leaves.removeAll(felled);

        assertEquals(birch, TreeShape.standingTrees(collect(new Pos(3, 0, 0)), this, diagonals));
    }

    // The neighbor carries its own crown, so it still holds its leaves.
    @Test
    void neighboringTreeOfTheSameSpeciesKeepsHoldingItsLeaves() {
        footprint = Footprint.SQUARE;
        Set<Pos> small = fill(0, 0, 0, 1, 11, 1);
        Set<Pos> tall = fill(2, 0, 0, 3, 15, 1);
        crown(-2, 8, -2, 3, 12, 3);
        crown(2, 12, -2, 6, 16, 3);

        Result result = analyze(new Pos(1, 0, 0));

        assertEquals(small, Set.copyOf(result.kept()));
        assertEquals(tall, result.excluded());
        assertEquals(Set.of(), result.builds());
    }

    @Test
    void thickFungusStemFallsWholeOnUnevenGround() {
        footprint = Footprint.FUNGUS;
        groundTop = (x, z) -> x == 1 && z == 0 ? 0 : x == -1 && z == 0 ? -2 : -1;
        Set<Pos> stem = new HashSet<>();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (Math.abs(dx) + Math.abs(dz) < 2) {
                    stem.addAll(fill(dx, dx == 1 && dz == 0 ? 1 : 0, dz, dx, 7, dz));
                }
            }
        }
        stem.addAll(logs(new Pos(1, 0, 1), new Pos(1, 4, 1), new Pos(-1, 2, -1)));

        Result result = analyze(new Pos(1, 1, 0));

        assertEquals(stem, Set.copyOf(result.kept()));
        assertEquals(List.of(new Pos(0, 0, 0)), result.plantingSpots());
    }

    @Test
    void fungusStandsApartFromAStemWallBesideIt() {
        footprint = Footprint.FUNGUS;
        Set<Pos> stem = fill(0, 0, 0, 0, 6, 0);
        Set<Pos> wall = fill(1, 0, -2, 1, 2, 2);

        Result result = analyze(new Pos(0, 0, 0));

        assertEquals(stem, Set.copyOf(result.kept()));
        assertEquals(wall, result.excluded());
    }

    @Test
    void beeNestOnTheGroundBesideTheTrunkFallsWithIt() {
        groundTop = (x, z) -> x == 1 ? 0 : -1;
        Set<Pos> tree = fill(0, 0, 0, 0, 5, 0);
        Pos nest = new Pos(1, 1, 0);
        blocks.put(nest, Part.ATTACHMENT);
        tree.add(nest);

        assertEquals(tree, Set.copyOf(analyze(new Pos(0, 0, 0)).kept()));
    }

    @Test
    void trunkReachingBeyondTheCollectedLogsStillCarriesTheTree() {
        Set<Pos> tree = fill(0, 20, 0, 0, 30, 0);
        tree.addAll(logs(new Pos(1, 31, 0)));
        beyondLimits.add(new Pos(0, 19, 0));

        Result result = analyze(new Pos(1, 31, 0));

        assertEquals(tree, Set.copyOf(result.kept()));
        assertEquals(List.of(new Pos(0, 20, 0)), result.plantingSpots());
    }

    @Test
    void logsWithoutAnyGroundAreKeptWhole() {
        groundTop = (x, z) -> -100;
        Set<Pos> all = fill(0, 0, 0, 0, 3, 0);
        all.addAll(fill(-3, 0, 0, -1, 1, 0));

        Result result = analyze(new Pos(0, 0, 0));

        assertEquals(all, Set.copyOf(result.kept()));
        assertEquals(List.of(), result.plantingSpots());
    }

    // Without build protection the wall falls too, but the sapling still goes
    // where the trunk stood, not to the wall's far end.
    @Test
    void withoutBuildProtectionAllFallsAndTheTrunkBaseIsReplanted() {
        protectBuilds = false;
        Set<Pos> all = fill(0, 0, 0, 0, 5, 0);
        all.addAll(fill(-5, 0, 0, -1, 2, 0));

        Result result = analyze(new Pos(0, 3, 0));

        assertEquals(all, Set.copyOf(result.kept()));
        assertEquals(List.of(new Pos(0, 0, 0)), result.plantingSpots());
    }

    // A player's lattice of bare stacks on stone above a trunk's foot, each holding
    // up hanging logs that link them all: lookups grow with its size, not squared.
    @Test
    void largeBuildNeedsLeafLookupsInProportionToItsSize() {
        groundTop = (x, z) -> x == -1 && z == 0 ? -5 : -1;
        fill(-1, -4, 0, -1, 1, 0);
        for (int x = 0; x < 32; x++) {
            for (int z = 0; z < 32; z++) {
                stone.add(new Pos(x, -1, z));
                if ((x + z) % 2 == 0) {
                    fill(x, 0, z, x, 1, z);
                } else {
                    logs(new Pos(x, 2, z));
                }
            }
        }
        int[] lookups = {0};
        TreeShape.Surroundings counting = new TreeShape.Surroundings() {
            @Override
            public Part part(Pos collected) {
                return TreeShapeTest.this.part(collected);
            }

            @Override
            public Support below(Pos pos) {
                return TreeShapeTest.this.below(pos);
            }

            @Override
            public boolean isNaturalLeaf(Pos pos) {
                lookups[0]++;
                return TreeShapeTest.this.isNaturalLeaf(pos);
            }

            @Override
            public Footprint footprint(Pos log) {
                return TreeShapeTest.this.footprint(log);
            }

            @Override
            public boolean isTree(List<Pos> tree, Set<Pos> standing) {
                return TreeShapeTest.this.isTree(tree, standing);
            }
        };

        List<Pos> collected = collect(new Pos(-1, 0, 0));
        TreeShape.analyze(collected, counting, true, true);
        assertTrue(lookups[0] < 64 * collected.size(), lookups[0] + " leaf lookups for " + collected.size() + " logs");
    }

    @Test
    void dominatorsAreTheNodesEveryPathFromTheRootPasses() {
        // 0 -> 1, 0 -> 2, 1 -> 3, 2 -> 3, 3 -> 4, 4 -> 3, 4 -> 5; 6 is unreachable.
        List<List<Integer>> successors = List.of(List.of(1, 2), List.of(3), List.of(3), List.of(4), List.of(3, 5),
                List.of(), List.of(5));
        List<List<Integer>> predecessors = List.of(List.of(), List.of(0), List.of(0), List.of(1, 2, 4), List.of(3),
                List.of(4, 6), List.of());

        int[] idom = TreeShape.dominators(successors, predecessors);

        assertEquals(List.of(0, 0, 0, 0, 3, 4, -1), Arrays.stream(idom).boxed().toList());
    }

    private Result analyze(Pos hit) {
        return TreeShape.analyze(collect(hit), this, diagonals, protectBuilds);
    }

    // Breadth-first from the hit block, like TreeChopListener.collectConnected.
    private List<Pos> collect(Pos hit) {
        List<Pos> collected = new ArrayList<>();
        Deque<Pos> queue = new ArrayDeque<>(List.of(hit));
        Set<Pos> seen = new HashSet<>(queue);
        while (!queue.isEmpty()) {
            Pos pos = queue.poll();
            collected.add(pos);
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        int axes = Math.abs(dx) + Math.abs(dy) + Math.abs(dz);
                        Pos next = new Pos(pos.x() + dx, pos.y() + dy, pos.z() + dz);
                        if (axes > 0 && (diagonals || axes == 1) && blocks.containsKey(next) && seen.add(next)) {
                            queue.add(next);
                        }
                    }
                }
            }
        }
        return collected;
    }

    @Override
    public Part part(Pos collected) {
        return blocks.get(collected);
    }

    @Override
    public Support below(Pos pos) {
        if (beyondLimits.contains(pos)) {
            return Support.TREE;
        }
        if (pos.y() > groundTop.applyAsInt(pos.x(), pos.z())) {
            return Support.NONE;
        }
        return stone.contains(pos) ? Support.GROUND : Support.SOIL;
    }

    @Override
    public boolean isNaturalLeaf(Pos pos) {
        return leaves.contains(pos) && !blocks.containsKey(pos);
    }

    @Override
    public Footprint footprint(Pos log) {
        return footprint;
    }

    // Like TreeChopListener.hasNaturalFoliage: four grown leaves next to the tree
    // that touch nothing left standing or felled.
    @Override
    public boolean isTree(List<Pos> tree, Set<Pos> standing) {
        Set<Pos> counted = new HashSet<>();
        for (Pos log : tree) {
            for (Pos leaf : around(log)) {
                if (isNaturalLeaf(leaf)
                        && around(leaf).stream().noneMatch(pos -> standing.contains(pos) || felled.contains(pos))) {
                    counted.add(leaf);
                }
            }
        }
        return counted.size() >= 4;
    }

    private static List<Pos> around(Pos pos) {
        List<Pos> around = new ArrayList<>(26);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx != 0 || dy != 0 || dz != 0) {
                        around.add(new Pos(pos.x() + dx, pos.y() + dy, pos.z() + dz));
                    }
                }
            }
        }
        return around;
    }

    // Leaves filling a box, except where logs are.
    private void crown(int x1, int y1, int z1, int x2, int y2, int z2) {
        for (int x = x1; x <= x2; x++) {
            for (int y = y1; y <= y2; y++) {
                for (int z = z1; z <= z2; z++) {
                    leaves.add(new Pos(x, y, z));
                }
            }
        }
    }

    private Set<Pos> fill(int x1, int y1, int z1, int x2, int y2, int z2) {
        Set<Pos> placed = new LinkedHashSet<>();
        for (int x = x1; x <= x2; x++) {
            for (int y = y1; y <= y2; y++) {
                for (int z = z1; z <= z2; z++) {
                    placed.addAll(logs(new Pos(x, y, z)));
                }
            }
        }
        return placed;
    }

    // Like /fill ... hollow: only the outer shell of the box.
    private Set<Pos> hollowFill(int x1, int y1, int z1, int x2, int y2, int z2) {
        Set<Pos> placed = new LinkedHashSet<>();
        for (int x = x1; x <= x2; x++) {
            for (int y = y1; y <= y2; y++) {
                for (int z = z1; z <= z2; z++) {
                    if (x == x1 || x == x2 || y == y1 || y == y2 || z == z1 || z == z2) {
                        placed.addAll(logs(new Pos(x, y, z)));
                    }
                }
            }
        }
        return placed;
    }

    private static Set<Pos> union(Set<Pos> first, Set<Pos> second) {
        Set<Pos> all = new HashSet<>(first);
        all.addAll(second);
        return all;
    }

    private Set<Pos> logs(Pos... positions) {
        Set<Pos> placed = new LinkedHashSet<>();
        for (Pos pos : positions) {
            blocks.put(pos, Part.LOG);
            placed.add(pos);
        }
        return placed;
    }

    private Pos root(int x, int y, int z) {
        Pos pos = new Pos(x, y, z);
        blocks.put(pos, Part.ROOT);
        return pos;
    }

    // DarkOakTrunkPlacer: a 2x2 trunk leaning one block east near the top, and
    // logs hanging from the crown around it.
    private Set<Pos> darkOak() {
        footprint = Footprint.SQUARE;
        Set<Pos> tree = fill(0, 0, 0, 1, 4, 1);
        tree.addAll(fill(1, 5, 0, 2, 6, 1));
        tree.addAll(fill(-1, 2, 0, -1, 5, 0));
        tree.addAll(fill(0, 4, -1, 0, 5, -1));
        tree.addAll(fill(2, 3, 2, 2, 5, 2));
        return tree;
    }

    // MangroveRootPlacer: a trunk raised on a root, with roots spreading from its
    // foot down to the mud.
    private Set<Pos> mangrove(int x) {
        return mangrove(x, 2, 7);
    }

    private Set<Pos> mangrove(int x, int bottom, int height) {
        Set<Pos> tree = fill(x, bottom, 0, x, bottom + height - 1, 0);
        tree.add(root(x, bottom - 1, 0));
        tree.add(root(x - 1, bottom, 0));
        tree.add(root(x - 2, bottom - 1, 0));
        tree.add(root(x + 1, bottom, 0));
        tree.add(root(x + 2, bottom - 1, 0));
        tree.add(root(x, bottom, 1));
        tree.add(root(x, bottom - 1, 2));
        tree.add(root(x, bottom - 2, 2));
        tree.add(root(x, bottom, -1));
        tree.add(root(x + 1, bottom - 1, -2));
        return tree;
    }
}
