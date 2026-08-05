package minebot.mod;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Raycasts from a real entity's own eyes along their own actual look
 * direction -- not the bot's crosshair. Any Entity (including another
 * tracked player, not just the bot's own LocalPlayer) exposes real,
 * currently-synced getEyePosition()/getViewVector(), so this works for
 * "what block is that OTHER player looking at right now" just as well
 * as it would for the bot's own aim, via the same real primitive vanilla
 * itself uses for the crosshair pick (Level.clip(ClipContext) --
 * confirmed via decompiled source, e.g. Entity.pick's own use of this
 * exact shape; also the same one BlockBreaker.hasLineOfSight already
 * uses for the bot's own mining aim).
 *
 * Pulled out as a standalone, reusable utility rather than left inline
 * in whichever feature needed it first -- an earlier one-off version of
 * this exact raycast lived inline in a since-removed !debug command
 * (raycasting from the chat sender's eyes to test block-breaking against
 * a real player's aim, not the bot's own unfocused window's crosshair)
 * and was deleted once that debugging need was served; this is the same
 * mechanism kept around properly instead of re-deriving it from scratch
 * for every future feature that needs "what is this player looking at"
 * (e.g. !save chest, and likely PENDING.md's own planned !look/!use).
 */
public final class LookingAt {
    private LookingAt() {
    }

    /**
     * Returns the position of the first solid block `looker` is looking
     * directly at, within `maxDistance` blocks along their current view
     * vector, or null if nothing solid is hit that close.
     */
    public static BlockPos blockPos(final Entity looker, final ClientLevel level, final double maxDistance) {
        Vec3 from = looker.getEyePosition();
        Vec3 to = from.add(looker.getViewVector(1.0f).scale(maxDistance));
        BlockHitResult hit = level.clip(new ClipContext(from, to, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, looker));
        return hit.getType() == HitResult.Type.BLOCK ? hit.getBlockPos() : null;
    }
}
