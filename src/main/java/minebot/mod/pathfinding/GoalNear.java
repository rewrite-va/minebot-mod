package minebot.mod.pathfinding;

/**
 * A block position the bot should get within `range` blocks of -- ported
 * from minebot's earlier Python pathfinding port (pure-protocol-backend
 * branch)'s GoalNear, itself from mineflayer-pathfinder's goals.js.
 * distanceXZ is the octile-distance heuristic (diagonal moves cost
 * sqrt(2), cheaper per unit progress than two cardinal moves at cost 2
 * each, so the heuristic must reflect that to stay admissible given
 * Movements' diagonal moves).
 */
public final class GoalNear {
    private final int x;
    private final int y;
    private final int z;
    private final double rangeSq;

    public GoalNear(final double x, final double y, final double z, final double range) {
        this.x = (int) Math.floor(x);
        this.y = (int) Math.floor(y);
        this.z = (int) Math.floor(z);
        this.rangeSq = range * range;
    }

    public double heuristic(final Move node) {
        double dx = x - node.x;
        double dz = z - node.z;
        double dy = y - node.y;
        return distanceXZ(dx, dz) + Math.abs(dy);
    }

    public boolean isEnd(final Move node) {
        double dx = x - node.x;
        double dy = y - node.y;
        double dz = z - node.z;
        return (dx * dx + dy * dy + dz * dz) <= rangeSq;
    }

    private static double distanceXZ(double dx, double dz) {
        dx = Math.abs(dx);
        dz = Math.abs(dz);
        return Math.abs(dx - dz) + Math.min(dx, dz) * Math.sqrt(2);
    }
}
