package minebot.mod.mixin;

import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Exposes AbstractArrow.isInGround() (protected, no public vanilla
 * equivalent -- confirmed via decompiled source: IN_GROUND is a private
 * SynchedEntityData accessor with no public getter anywhere on the
 * class) to minebot.mod so LegsPickupItemsNode can tell a still-flying
 * arrow apart from one actually embedded in a block before treating it
 * as a pickup candidate. IN_GROUND itself IS synced to clients (unlike
 * `pickup`/`firedFromWeapon`, which are genuinely server-only plain
 * fields -- see LegsPickupItemsNode's own docstring), so this is purely
 * an access-modifier workaround, not a sync workaround.
 */
@Mixin(AbstractArrow.class)
public interface AbstractArrowAccessor {
    @Invoker("isInGround")
    boolean invokeIsInGround();
}
