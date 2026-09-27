package net.kroet.timberella.listeners;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * Splits the blocks collected from a hit block into the tree and the builds
 * touching it, on plain block positions. The tree stands on its trunk base,
 * found from the hit block downwards; other logs on the ground are a build.
 */
final class TreeShape {
    // How far up or down the columns of one thick fungus stem may start apart.
    private static final int FUNGUS_STEM_REACH = 3;
    // Vanilla branches resting on the ground are at most five logs high (a cherry
    // branch's end); a taller stack is a trunk of its own.
    private static final int MAX_BRANCH_STACK = 5;

    private static final int[][] FACES = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
    private static final int[][] ALL_NEIGHBORS = allNeighbors();
    private static final Base NO_BASE = new Base(Set.of(), List.of());

    /** What a collected block is to the tree. */
    enum Part {
        /** Logs, wood and fences: the tree's trunk and branches, or a build. */
        LOG,
        /** Mangrove roots: grounded by nature and felled with the trunk they carry. */
        ROOT,
        /** Bee nests, creaking hearts and the like: never a build on their own. */
        ATTACHMENT
    }

    /** What lies directly under a column of collected blocks. */
    enum Support {
        /** Dirt, podzol, moss, nylium and the like: what a grown trunk stands on. */
        SOIL,
        /** Anything else solid that is neither a leaf nor a log of this tree. */
        GROUND,
        /** Mangrove roots, which carry a trunk like ground. */
        ROOT,
        /** A log of this tree that wasn't collected, e.g. beyond the species limits. */
        TREE,
        /** Air, water, leaves or plants: the column hangs. */
        NONE
    }

    /** Trunk base shapes: one column, a 2x2 square, or a fungus stem up to 3x3. */
    enum Footprint {
        SINGLE, SQUARE, FUNGUS
    }

    /**
     * What the analysis needs to know about the world around the collected blocks.
     */
    interface Surroundings {
        /** What a collected block is to the tree. */
        Part part(Pos collected);

        /** What the block at pos is to a column of collected blocks standing on it. */
        Support below(Pos pos);

        /** Whether a naturally grown (non-persistent) leaf is at pos. */
        boolean isNaturalLeaf(Pos pos);

        /** The trunk base shape of the tree the collected log belongs to. */
        Footprint footprint(Pos log);

        /** Whether these blocks would be felled as a tree, the others standing. */
        boolean isTree(List<Pos> tree, Set<Pos> standing);
    }

    record Pos(int x, int y, int z) {
    }

    /**
     * The tree in collection order with the hit block first, the collected blocks
     * that stay, those of them that are builds rather than neighboring trees, and
     * the lowest block of each trunk base column for replanting.
     */
    record Result(List<Pos> kept, Set<Pos> excluded, Set<Pos> builds, List<Pos> plantingSpots) {
    }

    private record Column(int x, int z) {
    }

    // A vertical stretch of collected non-root blocks and what it stands on.
    private record Run(int x, int z, int bottom, int top, Support support) {
        boolean grounded() {
            return support == Support.SOIL || support == Support.GROUND || support == Support.ROOT;
        }

        boolean supported() {
            return support != Support.NONE;
        }

        int height() {
            return top - bottom + 1;
        }

        Column column() {
            return new Column(x, z);
        }
    }

    private record Base(Set<Column> columns, List<Run> planting) {
    }

    private final List<Pos> collected;
    private final Surroundings world;
    private final boolean diagonals;
    private final Map<Pos, Part> partOf = new HashMap<>();
    private final Map<Pos, Run> runs = new HashMap<>();
    private final Map<Run, Boolean> holdsBranch = new HashMap<>();
    private LeafReach leafReach;

    private TreeShape(List<Pos> collected, Surroundings world, boolean diagonals) {
        this.collected = collected;
        this.world = world;
        this.diagonals = diagonals;
        for (Pos pos : collected) {
            partOf.put(pos, world.part(pos));
        }
        findRuns();
    }

    /**
     * Splits collected (hit block first) into the tree and what stays; with
     * protectBuilds off, all of it is the tree. Null if the hit block itself
     * belongs to a build.
     */
    static Result analyze(List<Pos> collected, Surroundings world, boolean diagonals, boolean protectBuilds) {
        TreeShape shape = new TreeShape(collected, world, diagonals);
        Pos hit = collected.get(0);
        Pos trunk = shape.settle(shape.findTrunk(hit));
        Base base = trunk == null ? NO_BASE : shape.base(trunk);
        List<Pos> spots = base.planting().stream().map(shape::plantingSpot).toList();
        if (!protectBuilds) {
            return new Result(List.copyOf(collected), Set.of(), Set.of(), spots);
        }
        Set<Pos> tree = shape.fell(trunk, base, hit);
        if (!tree.contains(hit)) {
            return null;
        }
        Set<Pos> left = shape.without(tree);
        return new Result(shape.inOrder(tree), left, shape.builds(left, tree), spots);
    }

    /**
     * The collected blocks that belong to a tree of their own, one a hit would fell
     * as a tree; the others are builds.
     */
    static Set<Pos> standingTrees(List<Pos> collected, Surroundings world, boolean diagonals) {
        TreeShape shape = new TreeShape(collected, world, diagonals);
        Set<Pos> all = new HashSet<>(collected);
        all.removeAll(shape.builds(all, Set.of()));
        return all;
    }

    private void findRuns() {
        for (Pos pos : partOf.keySet()) {
            if (runs.containsKey(pos) || partOf.get(pos) == Part.ROOT) {
                continue;
            }
            int bottom = pos.y();
            int top = pos.y();
            while (inRun(new Pos(pos.x(), bottom - 1, pos.z()))) {
                bottom--;
            }
            while (inRun(new Pos(pos.x(), top + 1, pos.z()))) {
                top++;
            }
            Pos under = new Pos(pos.x(), bottom - 1, pos.z());
            Support support = partOf.get(under) == Part.ROOT ? Support.ROOT : world.below(under);
            Run run = new Run(pos.x(), pos.z(), bottom, top, support);
            for (int y = bottom; y <= top; y++) {
                runs.put(new Pos(pos.x(), y, pos.z()), run);
            }
        }
    }

    private boolean inRun(Pos pos) {
        Part part = partOf.get(pos);
        return part != null && part != Part.ROOT;
    }

    // The log the tree stands on: straight down from the hit block if that reaches
    // the ground, else the lowest one reachable, since branches lead down to it. A
    // single log resting on the ground is only taken if no taller trunk is found.
    private Pos findTrunk(Pos hit) {
        record Step(Pos pos, int order) {
        }
        PriorityQueue<Step> queue = new PriorityQueue<>(
                Comparator.comparingInt((Step step) -> step.pos().y()).thenComparingInt(Step::order));
        Set<Pos> seen = new HashSet<>();
        seen.add(hit);
        queue.add(new Step(hit, 0));
        int order = 1;
        Pos resting = null;
        while (!queue.isEmpty()) {
            Pos pos = queue.poll().pos();
            Run run = runs.get(pos);
            if (partOf.get(pos) == Part.LOG && run.supported()) {
                if (run.height() > 1 || run.support() == Support.TREE) {
                    return pos;
                }
                if (resting == null) {
                    resting = pos;
                }
            }
            for (int[] offset : offsets()) {
                Pos next = offset(pos, offset);
                if (partOf.containsKey(next) && seen.add(next)) {
                    queue.add(new Step(next, order++));
                }
            }
        }
        return resting;
    }

    // Moves from a column built against a trunk to that trunk, and from a branch
    // resting on the ground down to the trunk it belongs to.
    private Pos settle(Pos trunk) {
        Set<Run> visited = new HashSet<>();
        while (trunk != null && visited.add(runs.get(trunk))) {
            Run run = runs.get(trunk);
            Pos beside = treeBeside(run);
            if (beside != null) {
                trunk = beside;
            } else if (isRestingStack(run)) {
                Pos below = trunkBelow(trunk);
                if (below == null) {
                    break;
                }
                trunk = below;
            } else {
                break;
            }
        }
        return trunk;
    }

    // A trunk reaching higher with grown leaves on top beside a bare column, which
    // is then a post built against it unless it holds that trunk up; a column of a
    // 2x2 or thick fungus trunk stays with its own trunk.
    private Pos treeBeside(Run run) {
        if (run.support() == Support.TREE || hasLeafOnTop(run) || isPartOfWideTrunk(run)) {
            return null;
        }
        for (int y = run.bottom(); y <= run.top(); y++) {
            for (int[] offset : offsets()) {
                Pos next = offset(new Pos(run.x(), y, run.z()), offset);
                Run other = runs.get(next);
                if (partOf.get(next) == Part.LOG && !other.equals(run) && isTrunkLike(other)
                        && other.top() > run.top() && hasLeafOnTop(other) && !carries(run, other)) {
                    return next;
                }
            }
        }
        return null;
    }

    private boolean isPartOfWideTrunk(Run run) {
        for (int y = run.bottom(); y <= run.top(); y++) {
            Pos pos = new Pos(run.x(), y, run.z());
            if (partOf.get(pos) == Part.LOG) {
                return run.supported() && base(pos).columns().size() > 1;
            }
        }
        return false;
    }

    private boolean isRestingStack(Run run) {
        return (run.support() == Support.SOIL || run.support() == Support.GROUND) && run.height() <= MAX_BRANCH_STACK
                && hasLeafOnTop(run);
    }

    // Whether a column holds up another run: one starting at its top log or right
    // above, touching that log, and no trunk of its own, as it hangs or rests on
    // ground no trunk grows from (a growing tree turns grass under it into dirt).
    private boolean carries(Run run, Run other) {
        if (!isNoTrunk(other) || other.bottom() < run.top() || other.bottom() > run.top() + 1) {
            return false;
        }
        return touches(new Pos(other.x(), other.bottom(), other.z()), Set.of(new Pos(run.x(), run.top(), run.z())));
    }

    private static boolean isNoTrunk(Run run) {
        return run.support() == Support.NONE || run.support() == Support.GROUND;
    }

    // A bare stack that holds up more of a branch, one reaching grown leaves, is a
    // segment of that branch rather than a build.
    private boolean holdsBranch(Run run) {
        return holdsBranch.computeIfAbsent(run, stack -> {
            for (int[] offset : offsets()) {
                Pos next = offset(new Pos(stack.x(), stack.top(), stack.z()), offset);
                Run other = runs.get(next);
                if (partOf.get(next) == Part.LOG && !other.equals(stack) && carries(stack, other)
                        && reachesLeaves(other, stack)) {
                    return true;
                }
            }
            return false;
        });
    }

    // Whether the run, or what hangs or rests on from it, has grown leaves on top,
    // without going through the holder.
    private boolean reachesLeaves(Run start, Run holder) {
        if (leafReach == null) {
            leafReach = new LeafReach();
        }
        return leafReach.reaches(start, holder);
    }

    /**
     * Which runs without a trunk of their own reach grown leaves through such runs,
     * from one pass over the shape. Reversed, all leafy runs hang off one root, and
     * a holder cuts a run off from them exactly when it dominates that run there.
     */
    private final class LeafReach {
        private static final int LEAVES = 0;
        private final Map<Run, Integer> index = new HashMap<>();
        private final int[] enter;
        private final int[] exit;

        LeafReach() {
            List<Run> nodes = new ArrayList<>();
            nodes.add(null);
            for (Run run : new LinkedHashSet<>(runs.values())) {
                if (isNoTrunk(run)) {
                    index.put(run, nodes.size());
                    nodes.add(run);
                }
            }
            int size = nodes.size();
            List<List<Integer>> reversed = new ArrayList<>(size);
            List<List<Integer>> reachedFrom = new ArrayList<>(size);
            for (int i = 0; i < size; i++) {
                reversed.add(new ArrayList<>());
                reachedFrom.add(new ArrayList<>());
            }
            for (int i = 1; i < size; i++) {
                Run run = nodes.get(i);
                if (hasLeafOnTop(run)) {
                    reversed.get(LEAVES).add(i);
                    reachedFrom.get(i).add(LEAVES);
                }
                for (int next : linksOf(run)) {
                    reversed.get(next).add(i);
                    reachedFrom.get(i).add(next);
                }
            }
            int[] idom = dominators(reversed, reachedFrom);
            enter = new int[size];
            exit = new int[size];
            number(idom);
        }

        // Runs without a trunk of their own that a log beside this run belongs to.
        private Set<Integer> linksOf(Run run) {
            Set<Integer> links = new LinkedHashSet<>();
            for (int y = run.bottom(); y <= run.top(); y++) {
                for (int[] offset : offsets()) {
                    Pos next = offset(new Pos(run.x(), y, run.z()), offset);
                    Run other = runs.get(next);
                    if (partOf.get(next) == Part.LOG && !other.equals(run) && isNoTrunk(other)) {
                        links.add(index.get(other));
                    }
                }
            }
            return links;
        }

        boolean reaches(Run start, Run holder) {
            Integer run = index.get(start);
            if (run == null || enter[run] < 0) {
                return false;
            }
            Integer cut = index.get(holder);
            return cut == null || !(enter[cut] >= 0 && enter[cut] <= enter[run] && exit[run] <= exit[cut]);
        }

        // Pre- and post-order numbers in the dominator tree, -1 where unreachable.
        private void number(int[] idom) {
            Arrays.fill(enter, -1);
            Arrays.fill(exit, -1);
            List<List<Integer>> children = new ArrayList<>(idom.length);
            for (int i = 0; i < idom.length; i++) {
                children.add(new ArrayList<>());
            }
            for (int v = 1; v < idom.length; v++) {
                if (idom[v] >= 0) {
                    children.get(idom[v]).add(v);
                }
            }
            int clock = 0;
            Deque<int[]> stack = new ArrayDeque<>();
            enter[LEAVES] = clock++;
            stack.push(new int[]{LEAVES, 0});
            while (!stack.isEmpty()) {
                int[] top = stack.peek();
                List<Integer> kids = children.get(top[0]);
                if (top[1] < kids.size()) {
                    int child = kids.get(top[1]++);
                    enter[child] = clock++;
                    stack.push(new int[]{child, 0});
                } else {
                    exit[top[0]] = clock++;
                    stack.pop();
                }
            }
        }
    }

    /**
     * Immediate dominators from node 0 (Lengauer-Tarjan with path compression), -1
     * for nodes it doesn't reach. Package-private for TreeShapeTest.
     */
    static int[] dominators(List<List<Integer>> successors, List<List<Integer>> predecessors) {
        int size = successors.size();
        int[] order = new int[size];
        int[] vertex = new int[size];
        int[] parent = new int[size];
        int[] semi = new int[size];
        int[] ancestor = new int[size];
        int[] label = new int[size];
        int[] idom = new int[size];
        Arrays.fill(order, -1);
        Arrays.fill(idom, -1);
        int count = 0;
        Deque<int[]> stack = new ArrayDeque<>();
        order[0] = count;
        vertex[count++] = 0;
        stack.push(new int[]{0, 0});
        while (!stack.isEmpty()) {
            int[] top = stack.peek();
            List<Integer> next = successors.get(top[0]);
            if (top[1] < next.size()) {
                int w = next.get(top[1]++);
                if (order[w] < 0) {
                    order[w] = count;
                    vertex[count++] = w;
                    parent[w] = top[0];
                    stack.push(new int[]{w, 0});
                }
            } else {
                stack.pop();
            }
        }
        for (int v = 0; v < size; v++) {
            semi[v] = order[v];
            ancestor[v] = -1;
            label[v] = v;
        }
        List<List<Integer>> bucket = new ArrayList<>(size);
        for (int v = 0; v < size; v++) {
            bucket.add(new ArrayList<>());
        }
        for (int i = count - 1; i > 0; i--) {
            int w = vertex[i];
            for (int v : predecessors.get(w)) {
                if (order[v] < 0) {
                    continue;
                }
                int u = eval(v, ancestor, label, semi);
                semi[w] = Math.min(semi[w], semi[u]);
            }
            bucket.get(vertex[semi[w]]).add(w);
            ancestor[w] = parent[w];
            for (int v : bucket.get(parent[w])) {
                int u = eval(v, ancestor, label, semi);
                idom[v] = semi[u] < semi[v] ? u : parent[w];
            }
            bucket.get(parent[w]).clear();
        }
        for (int i = 1; i < count; i++) {
            int w = vertex[i];
            if (idom[w] != vertex[semi[w]]) {
                idom[w] = idom[idom[w]];
            }
        }
        idom[0] = 0;
        return idom;
    }

    private static int eval(int v, int[] ancestor, int[] label, int[] semi) {
        if (ancestor[v] < 0) {
            return v;
        }
        // Path compression without recursion: the chain is shortened top first.
        Deque<Integer> chain = new ArrayDeque<>();
        for (int x = v; ancestor[ancestor[x]] >= 0; x = ancestor[x]) {
            chain.push(x);
        }
        while (!chain.isEmpty()) {
            int x = chain.pop();
            int a = ancestor[x];
            if (semi[label[a]] < semi[label[x]]) {
                label[x] = label[a];
            }
            ancestor[x] = ancestor[a];
        }
        return label[v];
    }

    // The nearest trunk standing lower than the resting branch at pos that would
    // fell that branch with it.
    private Pos trunkBelow(Pos pos) {
        record Step(Pos pos, int order) {
        }
        Run resting = runs.get(pos);
        PriorityQueue<Step> queue = new PriorityQueue<>(
                Comparator.comparingInt((Step step) -> step.pos().y()).thenComparingInt(Step::order));
        Set<Pos> seen = new HashSet<>();
        seen.add(pos);
        queue.add(new Step(pos, 0));
        int order = 1;
        while (!queue.isEmpty()) {
            Pos next = queue.poll().pos();
            Run run = runs.get(next);
            if (partOf.get(next) == Part.LOG && !run.equals(resting) && isTrunkLike(run)
                    && run.bottom() < resting.bottom() && treeBeside(run) == null
                    && fell(next, base(next), next).contains(pos)) {
                return next;
            }
            for (int[] offset : offsets()) {
                Pos neighbor = offset(next, offset);
                if (partOf.containsKey(neighbor) && seen.add(neighbor)) {
                    queue.add(new Step(neighbor, order++));
                }
            }
        }
        return null;
    }

    private static boolean isTrunkLike(Run run) {
        return run != null && run.supported() && (run.height() > 1 || run.support() == Support.TREE);
    }

    private boolean hasLeafOnTop(Run run) {
        return world.isNaturalLeaf(new Pos(run.x(), run.top() + 1, run.z()));
    }

    // Everything connected to the trunk base, other than builds and what only
    // connects through them; without a base, what connects to the seed.
    private Set<Pos> fell(Pos trunk, Base base, Pos seed) {
        Set<Pos> excluded = trunk == null ? Set.of() : excluded(runs.get(trunk), base.columns());
        Set<Pos> tree = new HashSet<>();
        Deque<Pos> queue = new ArrayDeque<>();
        for (Pos pos : collected) {
            if (base.columns().contains(column(pos)) && tree.add(pos)) {
                queue.add(pos);
            }
        }
        if (queue.isEmpty()) {
            tree.add(seed);
            queue.add(seed);
        }
        while (!queue.isEmpty()) {
            Pos pos = queue.poll();
            for (int[] offset : offsets()) {
                Pos next = offset(pos, offset);
                if (partOf.containsKey(next) && !excluded.contains(next) && tree.add(next)) {
                    queue.add(next);
                }
            }
        }
        return tree;
    }

    // The blocks left standing that hold no leaves: all but those a hit would fell
    // as a tree of their own, with grown leaves on top of one of its logs and
    // leaves of its own apart from the felled tree's crown.
    private Set<Pos> builds(Set<Pos> left, Set<Pos> felled) {
        Set<Pos> builds = new HashSet<>(left);
        Set<Pos> checked = new HashSet<>();
        for (Pos pos : collected) {
            if (!builds.contains(pos) || checked.contains(pos) || partOf.get(pos) != Part.LOG
                    || !runs.get(pos).supported() || !hasLeafBeside(runs.get(pos))) {
                continue;
            }
            Set<Pos> other = fell(pos, base(pos), pos);
            checked.addAll(other);
            Set<Pos> standing = without(other);
            standing.addAll(felled);
            if (other.size() > 1 && hasLeafOnTopOfAny(other) && world.isTree(inOrder(other), standing)) {
                builds.removeAll(other);
            }
        }
        return builds;
    }

    private boolean hasLeafOnTopOfAny(Set<Pos> blocks) {
        for (Pos pos : blocks) {
            Run run = runs.get(pos);
            if (partOf.get(pos) == Part.LOG && run.top() == pos.y() && hasLeafOnTop(run)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasLeafBeside(Run run) {
        for (int y = run.bottom(); y <= run.top(); y++) {
            for (int[] offset : ALL_NEIGHBORS) {
                if (world.isNaturalLeaf(offset(new Pos(run.x(), y, run.z()), offset))) {
                    return true;
                }
            }
        }
        return false;
    }

    private Base base(Pos trunk) {
        Run run = runs.get(trunk);
        return switch (world.footprint(trunk)) {
            case SINGLE -> single(run);
            case SQUARE -> square(run, supportedRuns());
            case FUNGUS -> fungus(run, supportedRuns());
        };
    }

    private static Base single(Run trunk) {
        return new Base(Set.of(trunk.column()), List.of(trunk));
    }

    // 2x2 trunks: four columns starting at the trunk's height. Where such squares
    // overlap, the one with the tallest columns wins, so a wall beside the trunk
    // never pairs up with half of it.
    private static Base square(Run trunk, Map<Column, List<Run>> supported) {
        List<List<Run>> squares = new ArrayList<>();
        for (int ox = trunk.x() - 3; ox <= trunk.x() + 2; ox++) {
            for (int oz = trunk.z() - 3; oz <= trunk.z() + 2; oz++) {
                List<Run> members = new ArrayList<>(4);
                for (int dx = 0; dx <= 1; dx++) {
                    for (int dz = 0; dz <= 1; dz++) {
                        Run run = runStartingAt(supported, new Column(ox + dx, oz + dz), trunk.bottom());
                        if (run != null) {
                            members.add(run);
                        }
                    }
                }
                if (members.size() == 4) {
                    squares.add(members);
                }
            }
        }
        Comparator<List<Run>> taller = Comparator.comparingInt(TreeShape::lowestTop)
                .thenComparingInt(TreeShape::summedTops);
        squares.sort(taller.reversed());
        Set<Column> taken = new HashSet<>();
        for (List<Run> square : squares) {
            if (square.stream().anyMatch(run -> taken.contains(run.column()))) {
                continue;
            }
            square.forEach(run -> taken.add(run.column()));
            if (square.contains(trunk)) {
                Set<Column> columns = new HashSet<>();
                square.forEach(run -> columns.add(run.column()));
                return new Base(columns, square);
            }
        }
        return single(trunk);
    }

    private static int lowestTop(List<Run> square) {
        return square.stream().mapToInt(Run::top).min().orElse(Integer.MIN_VALUE);
    }

    private static int summedTops(List<Run> square) {
        return square.stream().mapToInt(Run::top).sum();
    }

    // Thick fungus stems: the 3x3 around a center column whose four sides are stem
    // too. A plain stem, or one with a stem wall beside it, is a single column.
    private Base fungus(Run trunk, Map<Column, List<Run>> supported) {
        Column center = null;
        int best = 0;
        for (int ring = 0; ring <= 1; ring++) {
            for (int dx = -ring; dx <= ring; dx++) {
                for (int dz = -ring; dz <= ring; dz++) {
                    Column candidate = new Column(trunk.x() + dx, trunk.z() + dz);
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != ring || !isThickStemCenter(candidate, trunk)) {
                        continue;
                    }
                    int stems = 0;
                    for (Column column : around(candidate)) {
                        stems += hasStem(column, trunk.bottom()) ? 1 : 0;
                    }
                    if (stems > best) {
                        center = candidate;
                        best = stems;
                    }
                }
            }
        }
        if (center == null) {
            return single(trunk);
        }
        // The fungus goes back on the center column, at the stem's own height.
        Run plant = null;
        for (Run run : supported.getOrDefault(center, List.of())) {
            int offset = Math.abs(run.bottom() - trunk.bottom());
            if (plant == null || offset < Math.abs(plant.bottom() - trunk.bottom())
                    || offset == Math.abs(plant.bottom() - trunk.bottom()) && run.bottom() < plant.bottom()) {
                plant = run;
            }
        }
        return new Base(new HashSet<>(around(center)), List.of(plant != null ? plant : trunk));
    }

    private boolean isThickStemCenter(Column center, Run trunk) {
        for (int[] face : FACES) {
            if (face[1] == 0 && !hasStem(new Column(center.x() + face[0], center.z() + face[2]), trunk.bottom())) {
                return false;
            }
        }
        return hasStem(center, trunk.bottom());
    }

    private boolean hasStem(Column column, int y) {
        for (int dy = -FUNGUS_STEM_REACH; dy <= FUNGUS_STEM_REACH; dy++) {
            if (inRun(new Pos(column.x(), y + dy, column.z()))) {
                return true;
            }
        }
        return false;
    }

    private static List<Column> around(Column center) {
        List<Column> columns = new ArrayList<>(9);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                columns.add(new Column(center.x() + dx, center.z() + dz));
            }
        }
        return columns;
    }

    private Map<Column, List<Run>> supportedRuns() {
        Map<Column, List<Run>> supported = new HashMap<>();
        for (Run run : new HashSet<>(runs.values())) {
            if (run.supported()) {
                supported.computeIfAbsent(run.column(), column -> new ArrayList<>()).add(run);
            }
        }
        return supported;
    }

    private static Run runStartingAt(Map<Column, List<Run>> supported, Column column, int bottom) {
        for (Run run : supported.getOrDefault(column, List.of())) {
            if (run.bottom() == bottom) {
                return run;
            }
        }
        return null;
    }

    // Where a sapling goes back: the run's bottom, or the lowest root below it.
    private Pos plantingSpot(Run run) {
        int y = run.bottom();
        while (partOf.get(new Pos(run.x(), y - 1, run.z())) == Part.ROOT) {
            y--;
        }
        return new Pos(run.x(), y, run.z());
    }

    // Logs on the ground beside the trunk base are a build, and so is whatever
    // without ground of its own touches one; roots go with the nearest trunk.
    private Set<Pos> excluded(Run trunk, Set<Column> base) {
        Set<Pos> build = new HashSet<>();
        Set<Column> rootedTrunks = new HashSet<>();
        for (Pos pos : collected) {
            Run run = runs.get(pos);
            if (partOf.get(pos) == Part.LOG && isBuild(run, trunk, base)) {
                build.add(pos);
                if (run.support() == Support.ROOT && isTrunkLike(run)) {
                    rootedTrunks.add(run.column());
                }
            }
        }
        Set<Pos> excluded = new HashSet<>(build);
        Deque<Pos> queue = new ArrayDeque<>();
        for (Pos pos : collected) {
            if (canRestOnBuild(pos, build, base) && touches(pos, build)) {
                excluded.add(pos);
                queue.add(pos);
            }
        }
        while (!queue.isEmpty()) {
            Pos pos = queue.poll();
            for (int[] offset : offsets()) {
                Pos next = offset(pos, offset);
                if (partOf.containsKey(next) && canRestOnBuild(next, build, base) && excluded.add(next)) {
                    queue.add(next);
                }
            }
        }
        for (Pos pos : collected) {
            if (partOf.get(pos) == Part.ROOT && distance(pos, rootedTrunks) < distance(pos, base)) {
                excluded.add(pos);
            }
        }
        return excluded;
    }

    // Wood on the ground no higher than the trunk base is a build. Higher up, a
    // single log, or a stack of up to five with grown leaves on top or holding up
    // more of a branch, rests there; on mangrove roots only right beside the trunk.
    private boolean isBuild(Run run, Run trunk, Set<Column> base) {
        if (!run.grounded() || base.contains(run.column())) {
            return false;
        }
        if (run.support() == Support.ROOT) {
            return !isBeside(run.column(), base);
        }
        if (run.bottom() <= trunk.bottom()) {
            return true;
        }
        if (run.height() <= 1) {
            return false;
        }
        return run.height() > MAX_BRANCH_STACK || !hasLeafOnTop(run) && !holdsBranch(run);
    }

    private static boolean isBeside(Column column, Set<Column> base) {
        for (Column other : base) {
            if (Math.max(Math.abs(column.x() - other.x()), Math.abs(column.z() - other.z())) <= 1) {
                return true;
            }
        }
        return false;
    }

    private boolean canRestOnBuild(Pos pos, Set<Pos> build, Set<Column> base) {
        return partOf.get(pos) != Part.ROOT && !build.contains(pos) && !base.contains(column(pos));
    }

    private boolean touches(Pos pos, Set<Pos> blocks) {
        for (int[] offset : offsets()) {
            if (blocks.contains(offset(pos, offset))) {
                return true;
            }
        }
        return false;
    }

    private static int distance(Pos pos, Set<Column> columns) {
        int nearest = Integer.MAX_VALUE;
        for (Column column : columns) {
            nearest = Math.min(nearest, Math.abs(pos.x() - column.x()) + Math.abs(pos.z() - column.z()));
        }
        return nearest;
    }

    private List<Pos> inOrder(Set<Pos> blocks) {
        return collected.stream().filter(blocks::contains).toList();
    }

    private Set<Pos> without(Set<Pos> blocks) {
        Set<Pos> rest = new HashSet<>(collected);
        rest.removeAll(blocks);
        return rest;
    }

    private static Column column(Pos pos) {
        return new Column(pos.x(), pos.z());
    }

    private static Pos offset(Pos pos, int[] offset) {
        return new Pos(pos.x() + offset[0], pos.y() + offset[1], pos.z() + offset[2]);
    }

    private int[][] offsets() {
        return diagonals ? ALL_NEIGHBORS : FACES;
    }

    private static int[][] allNeighbors() {
        List<int[]> offsets = new ArrayList<>(26);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx != 0 || dy != 0 || dz != 0) {
                        offsets.add(new int[]{dx, dy, dz});
                    }
                }
            }
        }
        return offsets.toArray(new int[0][]);
    }
}
