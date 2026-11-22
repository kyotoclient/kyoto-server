package dev.kyoto.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Freelook: while it's on, mouse movement turns the camera instead of your player. */
@Mixin(Entity.class)
public abstract class FreelookEntityMixin {
	@Inject(method = "turn", at = @At("HEAD"), cancellable = true, require = 0)
	private void kyoto$freelookTurn(double yRot, double xRot, CallbackInfo ci) {
		if (dev.kyoto.extra.Freelook.active && (Object) this == Minecraft.getInstance().player && dev.kyoto.extra.Freelook.turn(yRot, xRot)) ci.cancel();
	}
}
