package dicemc.money.mixin;

import dicemc.money.event.EventHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.Hopper;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Stops hoppers and hopper minecarts from pulling stock out of a sign shop. */
@Mixin(HopperBlockEntity.class)
public class HopperBlockEntityMixin {
	@Inject(method = "suckInItems", at = @At("HEAD"), cancellable = true)
	private static void dicemcmm$blockShopPull(Level level, Hopper hopper, CallbackInfoReturnable<Boolean> cir) {
		BlockPos above = BlockPos.containing(hopper.getLevelX(), hopper.getLevelY() + 1.0, hopper.getLevelZ());
		BlockEntity block = level.getBlockEntity(above);
		if (EventHandler.isShopContainer(block)) {
			cir.setReturnValue(false);
		}
	}
}
