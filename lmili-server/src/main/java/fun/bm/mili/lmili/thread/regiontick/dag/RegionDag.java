package fun.bm.mili.lmili.thread.regiontick.dag;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;

public final class RegionDag {

    public final long regionId;
    private final List<DagNode> nodes;
    private final List<int[]> adjacencyList;
    private final int[] inDegrees;
    private int @Nullable [] topologicalOrder;

    public RegionDag(final long regionId, final List<DagNode> nodes, final boolean[][] conflictMatrix) {
        this.regionId = regionId;
        this.nodes = List.copyOf(nodes);
        int n = nodes.size();
        this.adjacencyList = new ArrayList<>(n);
        this.inDegrees = new int[n];
        for (int i = 0; i < n; i++) adjacencyList.add(new int[0]);

        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                if (conflictMatrix[i][j]) {
                    if (nodes.get(i).priority() >= nodes.get(j).priority()) addEdge(i, j);
                    else addEdge(j, i);
                }
            }
        }
    }

    private void addEdge(final int from, final int to) {
        int[] old = adjacencyList.get(from);
        int[] expanded = Arrays.copyOf(old, old.length + 1);
        expanded[old.length] = to;
        adjacencyList.set(from, expanded);
        inDegrees[to]++;
    }

    public synchronized int @NotNull [] topologicalSort() {
        if (this.topologicalOrder != null) return this.topologicalOrder;

        int n = nodes.size();
        int[] inDeg = this.inDegrees.clone();
        int[] order = new int[n];
        int orderIdx = 0;
        Deque<Integer> queue = new ArrayDeque<>();
        for (int i = 0; i < n; i++) if (inDeg[i] == 0) queue.addLast(i);

        while (!queue.isEmpty()) {
            int node = queue.pollLast();
            order[orderIdx++] = node;
            for (int succ : adjacencyList.get(node)) {
                if (--inDeg[succ] == 0) queue.addLast(succ);
            }
        }

        if (orderIdx != n) Arrays.setAll(order, i -> i);
        this.topologicalOrder = order;
        return order;
    }

    public @NotNull List<Integer> getReadyNodes() {
        List<Integer> ready = new ArrayList<>();
        for (int i = 0; i < nodes.size(); i++) if (inDegrees[i] == 0) ready.add(i);
        return ready;
    }

    public int @NotNull [] getSuccessors(final int nodeId) { return adjacencyList.get(nodeId); }
    public int getInDegree(final int nodeId) { return inDegrees[nodeId]; }
    public @NotNull DagNode getNode(final int index) { return nodes.get(index); }
    public int size() { return nodes.size(); }
    public @NotNull List<DagNode> nodes() { return nodes; }

    @Override
    public String toString() {
        return "RegionDag{regionId=" + regionId + ", nodes=" + nodes.size() + "}";
    }

    public static @NotNull RegionDag build(final long regionId,
                                            final List<Map.Entry<SystemProfile, Scope>> profileScopePairs) {
        int n = profileScopePairs.size();
        List<DagNode> nodes = new ArrayList<>(n);
        SystemProfile[] profiles = new SystemProfile[n];
        Scope[] scopes = new Scope[n];

        for (int i = 0; i < n; i++) {
            Map.Entry<SystemProfile, Scope> entry = profileScopePairs.get(i);
            profiles[i] = entry.getKey();
            scopes[i] = entry.getValue();
            nodes.add(new DagNode(i, entry.getKey(), entry.getValue()));
        }

        boolean[][] conflicts = ConflictDetector.buildConflictMatrix(profiles, scopes);
        return new RegionDag(regionId, nodes, conflicts);
    }
}
