package net.kroet.timberella.listeners;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.function.ToIntFunction;

/**
 * Shape rules for giant mushrooms on plain block positions, modeled on
 * vanilla's huge mushroom features. Mushroom blocks that vanilla can't grow
 * that way are treated as a build.
 */
final class GiantMushroomShape {
    // Vanilla stems are 4 to 6 blocks tall, doubled for some mushrooms.
    static final int MIN_HEIGHT = 4;
    // Upper bound for the connected cap blocks inspected around one mushroom.
    static final int CLUSTER_LIMIT = 1024;

    /**
     * Vanilla cap kinds: brown is one flat layer, red a dome whose sides reach
     * three layers below its top.
     */
    enum Cap {
        BROWN(3, 0), RED(2, 3);

        final int radius;
        final int depth;

        Cap(int radius, int depth) {
            this.radius = radius;
            this.depth = depth;
        }

        /** Whether vanilla places a cap block of this kind at pos above the column. */
        boolean covers(Column column, Pos pos) {
            int dx = Math.abs(pos.x() - column.x());
            int dz = Math.abs(pos.z() - column.z());
            int ring = Math.max(dx, dz);
            if (this == BROWN) {
                return pos.y() == column.top() + 1 && ring <= 3 && (dx != 3 || dz != 3);
            }
            if (pos.y() == column.top() + 1) {
                return ring <= 1;
            }
            return pos.y() >= column.top() - 2 && pos.y() <= column.top() && ring == 2 && (dx != 2 || dz != 2);
        }

        // The space vanilla needs free to grow this mushroom: brown keeps a 7x7 area
        // clear from the fifth stem block up, red only its stem and the block above.
        boolean needsFree(Column column, Pos pos) {
            int dy = pos.y() - column.bottom();
            if (dy < 0 || dy > column.top() - column.bottom() + 1) {
                return false;
            }
            int radius = this == BROWN && dy > 3 ? 3 : 0;
            return Math.abs(pos.x() - column.x()) <= radius && Math.abs(pos.z() - column.z()) <= radius;
        }
    }

    @FunctionalInterface
    interface BlockTest {
        boolean test(int x, int y, int z);
    }

    /** The cap kind of the block at a position, null if it is no cap block. */
    @FunctionalInterface
    interface CapLookup {
        Cap at(int x, int y, int z);
    }

    record Pos(int x, int y, int z) {
    }

    /** Stem blocks from bottom to top at one x/z. */
    record Column(int x, int z, int bottom, int top) {
        int height() {
            return top - bottom + 1;
        }
    }

    record Mushroom(Cap kind, Column column, List<Pos> cap) {
    }

    private GiantMushroomShape() {
    }

    /**
     * The giant mushroom the stem or cap block at (x, y, z) belongs to, with the
     * cap blocks that are its own; null if the blocks around it aren't
     * vanilla-shaped.
     */
    static Mushroom find(int x, int y, int z, BlockTest isStem, CapLookup caps, ToIntFunction<Cap> reach,
            int maxBlocks) {
        Cap hit = caps.at(x, y, z);
        Pos stem = new Pos(x, y, z);
        if (hit != null) {
            stem = stemUnderCap(x, y, z, kind(caps, hit), isStem, reach.applyAsInt(hit), hit.depth, maxBlocks);
            if (stem == null) {
                return null;
            }
        }
        Column column = naturalColumn(stem.x(), stem.y(), stem.z(), isStem, maxBlocks);
        if (column == null) {
            return null;
        }
        Pos anchor = capAbove(column, (cx, cy, cz) -> caps.at(cx, cy, cz) != null);
        if (anchor == null) {
            return null;
        }
        Cap kind = caps.at(anchor.x(), anchor.y(), anchor.z());
        if (hit != null && hit != kind) {
            return null;
        }
        BlockTest isCap = kind(caps, kind);
        List<Pos> cluster = connected(capSeeds(column, isCap), isCap, CLUSTER_LIMIT + 1);
        if (cluster.size() > CLUSTER_LIMIT) {
            return null;
        }
        List<Column> neighbors = neighbors(column, cluster, isStem, maxBlocks);
        if (neighbors == null || !isVanillaGroup(kind, column, neighbors, cluster)) {
            return null;
        }
        Pos hitPos = new Pos(x, y, z);
        if (hit != null && !kind.covers(column, hitPos)) {
            return null;
        }
        // A block a neighbor's cap covers too stays with the neighbor.
        int ownReach = reach.applyAsInt(kind);
        List<Pos> own = cluster.stream()
                .filter(pos -> kind.covers(column, pos) && ring(column, pos) <= ownReach)
                .filter(pos -> neighbors.stream().noneMatch(other -> kind.covers(other, pos)))
                .toList();
        return new Mushroom(kind, column, own);
    }

    /**
     * The stem column through (x, y, z), or null if any other stem touches it, even
     * diagonally, or it is taller than maxHeight.
     */
    static Column column(int x, int y, int z, BlockTest isStem, int maxHeight) {
        int bottom = y;
        int top = y;
        while (top - bottom < maxHeight && isStem.test(x, bottom - 1, z)) {
            bottom--;
        }
        while (top - bottom < maxHeight && isStem.test(x, top + 1, z)) {
            top++;
        }
        if (top - bottom + 1 > maxHeight) {
            return null;
        }
        for (int cy = bottom - 1; cy <= top + 1; cy++) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if ((dx != 0 || dz != 0) && isStem.test(x + dx, cy, z + dz)) {
                        return null;
                    }
                }
            }
        }
        return new Column(x, z, bottom, top);
    }

    /**
     * The cap block the column carries: directly above its top, else anywhere in
     * the 3x3 above it; null if nothing sits on the stem.
     */
    static Pos capAbove(Column column, BlockTest isCap) {
        int y = column.top() + 1;
        if (isCap.test(column.x(), y, column.z())) {
            return new Pos(column.x(), y, column.z());
        }
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (isCap.test(column.x() + dx, y, column.z() + dz)) {
                    return new Pos(column.x() + dx, y, column.z() + dz);
                }
            }
        }
        return null;
    }

    /**
     * Top stem of the column whose cap holds the cap block at (x, y, z), found
     * through the connected cap; the nearest column wins. Null if no column within
     * reach carries the cap, or two are equally near.
     */
    static Pos stemUnderCap(int x, int y, int z, BlockTest isCap, BlockTest isStem, int reach, int depth,
            int maxBlocks) {
        BlockTest nearby = (cx, cy, cz) -> Math.abs(cy - y) <= depth
                && Math.max(Math.abs(cx - x), Math.abs(cz - z)) <= 2 * reach && isCap.test(cx, cy, cz);
        Pos best = null;
        int bestDistance = Integer.MAX_VALUE;
        boolean tie = false;
        for (Pos cap : connected(List.of(new Pos(x, y, z)), nearby, maxBlocks)) {
            if (!isStem.test(cap.x(), cap.y() - 1, cap.z())) {
                continue;
            }
            int distance = Math.max(Math.abs(cap.x() - x), Math.abs(cap.z() - z));
            if (distance < bestDistance) {
                best = new Pos(cap.x(), cap.y() - 1, cap.z());
                bestDistance = distance;
                tie = false;
            } else if (distance == bestDistance) {
                tie = true;
            }
        }
        return best == null || tie || bestDistance > reach ? null : best;
    }

    /**
     * Whether vanilla can grow these mushrooms of one kind with this connected cap:
     * every cap block lies where one of them places its cap, and each pair can grow
     * next to each other in some order.
     */
    static boolean isVanillaGroup(Cap kind, Column column, List<Column> neighbors, List<Pos> cluster) {
        List<Column> all = new ArrayList<>(neighbors);
        all.add(column);
        for (Pos pos : cluster) {
            if (all.stream().noneMatch(member -> kind.covers(member, pos))) {
                return false;
            }
        }
        for (int i = 0; i < all.size(); i++) {
            for (int j = i + 1; j < all.size(); j++) {
                Column a = all.get(i);
                Column b = all.get(j);
                if (!growsBeside(kind, a, b) && !growsBeside(kind, b, a)) {
                    return false;
                }
            }
        }
        return true;
    }

    // Whether later can grow once first stands: first's stem and cap stay out of
    // the space later needs free.
    private static boolean growsBeside(Cap kind, Column first, Column later) {
        for (int y = first.bottom(); y <= first.top(); y++) {
            if (kind.needsFree(later, new Pos(first.x(), y, first.z()))) {
                return false;
            }
        }
        int top = first.top() + 1;
        for (int y = top - kind.depth; y <= top; y++) {
            for (int dx = -kind.radius; dx <= kind.radius; dx++) {
                for (int dz = -kind.radius; dz <= kind.radius; dz++) {
                    Pos pos = new Pos(first.x() + dx, y, first.z() + dz);
                    if (kind.covers(first, pos) && kind.needsFree(later, pos)) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private static Column naturalColumn(int x, int y, int z, BlockTest isStem, int maxHeight) {
        Column column = column(x, y, z, isStem, maxHeight);
        return column != null && column.height() >= MIN_HEIGHT ? column : null;
    }

    // Other stems holding up the cluster; null if one of them isn't vanilla-shaped.
    private static List<Column> neighbors(Column column, List<Pos> cluster, BlockTest isStem, int maxHeight) {
        List<Column> neighbors = new ArrayList<>();
        for (Pos pos : cluster) {
            boolean ownTop = pos.x() == column.x() && pos.z() == column.z() && pos.y() - 1 == column.top();
            if (ownTop || !isStem.test(pos.x(), pos.y() - 1, pos.z())) {
                continue;
            }
            Column other = naturalColumn(pos.x(), pos.y() - 1, pos.z(), isStem, maxHeight);
            if (other == null) {
                return null;
            }
            neighbors.add(other);
        }
        return neighbors;
    }

    private static List<Pos> capSeeds(Column column, BlockTest isCap) {
        List<Pos> seeds = new ArrayList<>();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (isCap.test(column.x() + dx, column.top() + 1, column.z() + dz)) {
                    seeds.add(new Pos(column.x() + dx, column.top() + 1, column.z() + dz));
                }
            }
        }
        return seeds;
    }

    private static int ring(Column column, Pos pos) {
        return Math.max(Math.abs(pos.x() - column.x()), Math.abs(pos.z() - column.z()));
    }

    private static BlockTest kind(CapLookup caps, Cap kind) {
        return (x, y, z) -> caps.at(x, y, z) == kind;
    }

    // Breadth-first over the face and edge neighbors (not the corners).
    private static List<Pos> connected(List<Pos> seeds, BlockTest joins, int maxBlocks) {
        Queue<Pos> queue = new ArrayDeque<>(seeds);
        Set<Pos> seen = new HashSet<>(seeds);
        List<Pos> result = new ArrayList<>();
        while (!queue.isEmpty() && result.size() < maxBlocks) {
            Pos pos = queue.poll();
            result.add(pos);
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        int axes = Math.abs(dx) + Math.abs(dy) + Math.abs(dz);
                        Pos next = new Pos(pos.x() + dx, pos.y() + dy, pos.z() + dz);
                        if (axes > 0 && axes < 3 && joins.test(next.x(), next.y(), next.z()) && seen.add(next)) {
                            queue.add(next);
                        }
                    }
                }
            }
        }
        return result;
    }
}
