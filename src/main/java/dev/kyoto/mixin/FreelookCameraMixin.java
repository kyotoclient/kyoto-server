package dev.kyoto.mixin;

import net.minecraft.client.Camera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

/** Freelook: the camera uses the freelook angles instead of the player's. */
@Mixin(Camera.class)
public abstract class FreelookCameraMixin {
	@ModifyArgs(method = {"setup", "update", "alignWithEntity"}, at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Camera;setRotation(FF)V"), require = 0)
	private void kyoto$freelookRotation(Args args) {
		if (!dev.kyoto.extra.Freelook.active) return;
		args.set(0, dev.kyoto.extra.Freelook.yaw());
		args.set(1, dev.kyoto.extra.Freelook.pitch());
	}
}
