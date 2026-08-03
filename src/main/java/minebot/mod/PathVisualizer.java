package minebot.mod;

import minebot.mod.pathfinding.Move;
import minebot.mod.pathfinding.PathTracker;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.world.phys.Vec3;

import java.util.Collection;

/**
 * Draws the currently planned A* path (PathTracker.waypoints()) as a
 * connected line through each waypoint, using MC 26.1.2's built-in Gizmo
 * debug-draw system -- confirmed via decompiled vanilla source
 * (LevelRenderer.collectPerFrameGizmos/Gizmos.line) as the real primitive
 * for exactly this ("a colored line in world space, no texture, local-
 * client-only, this frame") rather than hand-rolling VertexConsumer
 * calls. Gizmos render straight to this client's own framebuffer -- never
 * networked, so nothing here is visible to other players, matching "only
 * for the bot client".
 *
 * setAlwaysOnTop() is used deliberately: a pathfinding overlay is only
 * useful for debugging if it's visible through terrain (the whole point
 * is often to check the route goes where expected even when part of it
 * is behind a wall/underground), not occluded like a normal opaque gizmo
 * would be by default.
 */
public final class PathVisualizer {
    private static final int LINE_COLOR = 0xFF00FFFF; // opaque cyan -- distinct from vanilla's usual red/yellow debug colors
    private static final float LINE_WIDTH = 2.0f;
    private static final int MARKER_COLOR = 0xFFFF8800; // opaque orange -- distinct from the cyan line so each waypoint stands out against it
    // Gizmos.point's size is a raw gl_PointSize in *screen pixels*, not a
    // world-space size like LineGizmo's width (confirmed via decompiled
    // shader source, core/debug_point.vsh: `gl_PointSize = LineWidth;`,
    // no division by screen size the way rendertype_lines.vsh does for
    // real line geometry) -- 0.25f requested a quarter-pixel dot,
    // invisible on any real display (found live: markers simply didn't
    // render at all, while the line using the same collector/frame did).
    private static final float MARKER_SIZE = 10.0f;

    private final PathTracker pathTracker;

    public PathVisualizer(final PathTracker pathTracker) {
        this.pathTracker = pathTracker;
    }

    public void register() {
        LevelRenderEvents.BEFORE_GIZMOS.register(this::drawPath);
    }

    private void drawPath(final LevelRenderContext context) {
        Collection<Move> waypoints = pathTracker.waypoints();
        if (waypoints.isEmpty()) {
            return;
        }

        try (var ignored = context.levelRenderer().collectPerFrameGizmos()) {
            Vec3 previous = null;
            for (Move waypoint : waypoints) {
                Vec3 point = Vec3.atCenterOf(new BlockPos(waypoint.x, waypoint.y, waypoint.z));
                if (previous != null) {
                    Gizmos.line(previous, point, LINE_COLOR, LINE_WIDTH).setAlwaysOnTop();
                }
                Gizmos.point(point, MARKER_COLOR, MARKER_SIZE).setAlwaysOnTop();
                previous = point;
            }
        }
    }
}
