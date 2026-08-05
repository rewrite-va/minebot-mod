package minebot.mod;

import minebot.mod.pathfinding.BlockBreaker;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;

/**
 * Draws a cube outline around whichever block each BlockBreaker instance
 * is currently actively mining, same Gizmo debug-draw system PathVisualizer
 * already uses (see its own docstring for why Gizmos, not hand-rolled
 * VertexConsumer calls -- confirmed via decompiled vanilla source as the
 * real primitive for exactly this). Added specifically to make a live
 * report of "the bot is stuck, looking at a grass_block, probably the
 * stone block it targeted is below it" directly visible instead of only
 * inferable from log coordinates -- see FINDINGS.md. Pattern (Gizmos.
 * cuboid + GizmoStyle.stroke + setAlwaysOnTop) copied from
 * VillagerHelperMod's own PoiRenderer, a working reference for this same
 * built-in debug-draw API.
 *
 * Color-coded per BlockBreaker purpose (matching the "pathfinding"/
 * "digDown" labels each instance already carries for log attribution) so
 * overlapping/adjacent targets from different goals are still
 * distinguishable at a glance.
 */
public final class BlockTargetVisualizer {
    private static final int PATHFINDING_COLOR = 0xFF00FF00; // green
    private static final int DIG_DOWN_COLOR = 0xFFFF8800; // orange
    private static final float STROKE_WIDTH = 4.0f;

    private final BlockBreaker pathBlockBreaker;
    private final BlockBreaker digDownBreaker;

    public BlockTargetVisualizer(final BlockBreaker pathBlockBreaker, final BlockBreaker digDownBreaker) {
        this.pathBlockBreaker = pathBlockBreaker;
        this.digDownBreaker = digDownBreaker;
    }

    public void register() {
        LevelRenderEvents.BEFORE_GIZMOS.register(this::drawTargets);
    }

    private void drawTargets(final LevelRenderContext context) {
        BlockPos pathTarget = pathBlockBreaker.currentTarget();
        BlockPos digDownTarget = digDownBreaker.currentTarget();
        if (pathTarget == null && digDownTarget == null) {
            return;
        }

        try (var ignored = context.levelRenderer().collectPerFrameGizmos()) {
            if (pathTarget != null) {
                Gizmos.cuboid(pathTarget, GizmoStyle.stroke(PATHFINDING_COLOR, STROKE_WIDTH)).setAlwaysOnTop();
            }
            if (digDownTarget != null) {
                Gizmos.cuboid(digDownTarget, GizmoStyle.stroke(DIG_DOWN_COLOR, STROKE_WIDTH)).setAlwaysOnTop();
            }
        }
    }
}
