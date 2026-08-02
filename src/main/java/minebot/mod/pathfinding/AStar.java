package minebot.mod.pathfinding;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.ToDoubleFunction;

/**
 * A* search -- direct port of minebot's earlier Python pathfinding port
 * (pure-protocol-backend branch), itself ported from
 * mineflayer-pathfinder@2.4.5's lib/astar.js. Same g/h/f scoring, closed
 * set, and open-set update semantics; uses Java's built-in PriorityQueue
 * instead of porting astar.js's own hand-rolled binary heap (lib/heap.js)
 * -- PriorityQueue already gives an equivalent priority queue, so
 * reimplementing a custom one would just be extra code with the same
 * behavior (same reasoning as the earlier Python port's use of heapq).
 */
public final class AStar {
    public enum Status { SUCCESS, PARTIAL, TIMEOUT, NO_PATH }

    public record Result(Status status, double cost, long timeMillis, int visitedNodes, int generatedNodes, List<Move> path) {
    }

    private static final class PathNode {
        Move data;
        double g;
        double h;
        double f;
        PathNode parent;

        PathNode(final Move data, final double g, final double h, final PathNode parent) {
            set(data, g, h, parent);
        }

        void set(final Move data, final double g, final double h, final PathNode parent) {
            this.data = data;
            this.g = g;
            this.h = h;
            this.f = g + h;
            this.parent = parent;
        }
    }

    private final Function<Move, List<Move>> getNeighbors;
    private final ToDoubleFunction<Move> heuristic;
    private final Predicate<Move> isEnd;
    private final long timeoutMillis;
    private final long tickTimeoutMillis;
    private final double maxCost;

    private final long startTimeNanos = System.nanoTime();
    private final Set<Long> closedDataSet = new HashSet<>();
    private final PriorityQueue<PathNode> openHeap = new PriorityQueue<>((a, b) -> Double.compare(a.f, b.f));
    private final Map<Long, PathNode> openDataMap = new HashMap<>();
    private PathNode bestNode;

    public AStar(
        final Move start, final Function<Move, List<Move>> getNeighbors, final ToDoubleFunction<Move> heuristic,
        final Predicate<Move> isEnd, final long timeoutMillis
    ) {
        this(start, getNeighbors, heuristic, isEnd, timeoutMillis, 40, -1);
    }

    public AStar(
        final Move start, final Function<Move, List<Move>> getNeighbors, final ToDoubleFunction<Move> heuristic,
        final Predicate<Move> isEnd, final long timeoutMillis, final long tickTimeoutMillis, final double searchRadius
    ) {
        this.getNeighbors = getNeighbors;
        this.heuristic = heuristic;
        this.isEnd = isEnd;
        this.timeoutMillis = timeoutMillis;
        this.tickTimeoutMillis = tickTimeoutMillis;

        PathNode startNode = new PathNode(start, 0.0, heuristic.applyAsDouble(start), null);
        openHeap.add(startNode);
        openDataMap.put(start.hash(), startNode);
        bestNode = startNode;

        this.maxCost = searchRadius < 0 ? -1.0 : startNode.h + searchRadius;
    }

    private static List<Move> reconstructPath(final PathNode node) {
        List<Move> path = new ArrayList<>();
        PathNode current = node;
        while (current.parent != null) {
            path.add(current.data);
            current = current.parent;
        }
        java.util.Collections.reverse(path);
        return path;
    }

    private Result makeResult(final Status status, final PathNode node) {
        return new Result(
            status, node.g, (System.nanoTime() - startTimeNanos) / 1_000_000,
            closedDataSet.size(), closedDataSet.size() + openHeap.size(), reconstructPath(node)
        );
    }

    public Result compute() {
        long computeStartNanos = System.nanoTime();
        while (!openHeap.isEmpty()) {
            if ((System.nanoTime() - computeStartNanos) / 1_000_000 > tickTimeoutMillis) {
                return makeResult(Status.PARTIAL, bestNode);
            }
            if ((System.nanoTime() - startTimeNanos) / 1_000_000 > timeoutMillis) {
                return makeResult(Status.TIMEOUT, bestNode);
            }

            PathNode node = openHeap.poll();
            if (openDataMap.get(node.data.hash()) != node) {
                continue; // stale entry from an update -- superseded by a better node for the same position
            }
            if (isEnd.test(node.data)) {
                return makeResult(Status.SUCCESS, node);
            }

            openDataMap.remove(node.data.hash());
            closedDataSet.add(node.data.hash());

            for (Move neighborData : getNeighbors.apply(node.data)) {
                if (closedDataSet.contains(neighborData.hash())) {
                    continue;
                }

                double gFromThisNode = node.g + neighborData.cost;
                PathNode neighborNode = openDataMap.get(neighborData.hash());

                double h = heuristic.applyAsDouble(neighborData);
                if (maxCost > 0 && gFromThisNode + h > maxCost) {
                    continue;
                }

                if (neighborNode == null) {
                    neighborNode = new PathNode(neighborData, gFromThisNode, h, node);
                    openDataMap.put(neighborData.hash(), neighborNode);
                    if (neighborNode.h < bestNode.h) {
                        bestNode = neighborNode;
                    }
                    openHeap.add(neighborNode);
                } else {
                    if (neighborNode.g < gFromThisNode) {
                        continue; // another route is already faster
                    }
                    neighborNode.set(neighborData, gFromThisNode, h, node);
                    if (neighborNode.h < bestNode.h) {
                        bestNode = neighborNode;
                    }
                    // No decrease-key on PriorityQueue: push a fresh entry
                    // and let the stale one be skipped on poll (identity
                    // check against openDataMap, which always points at
                    // the current best node for a given position).
                    openHeap.add(neighborNode);
                }
            }
        }
        return makeResult(Status.NO_PATH, bestNode);
    }
}
