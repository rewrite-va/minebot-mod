package minebot.mod.mixin;

import minebot.mod.MinebotMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * LivingEntity.onEquippedItemBroken(Item, EquipmentSlot) is vanilla's own
 * per-slot break-notification hook (26.1.2's renamed equivalent of the
 * older sendEquipmentBreakStatus method RuinedEquipment -- github.com/
 * pepjebs/RuinedEquipment -- mixins into server-side): it's called exactly
 * once, right when a stack's damage reaches its max, both client- and
 * server-side (client-side for the local player's own prediction/
 * rendering). It hands the broken Item directly as a parameter, so there's
 * no need to read the stack itself (already cleared by the time this
 * fires). This gives an unambiguous "this item just broke" signal instead
 * of inferring breakage from disappearing inventory slots (which can't
 * tell a broken tool apart from one that was simply dropped).
 */
@Mixin(LivingEntity.class)
public abstract class ItemBreakMixin {
    @Inject(method = "onEquippedItemBroken", at = @At("HEAD"))
    private void onEquippedItemBroken(Item item, EquipmentSlot slot, CallbackInfo ci) {
        LivingEntity self = (LivingEntity) (Object) this;
        LocalPlayer player = Minecraft.getInstance().player;
        if (self != player) {
            return;
        }
        MinebotMod.getInstance().broadcastItemBrokenEvent(item, slot);
    }
}
