package dev.kyoto.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Block Outline styles: hides Minecraft's own black outline while Kyoto draws its own. */
@Pseudo
@Mixin(targets = "net.minecraft.client.renderer.LevelRenderer")
public abstract class BlockOutlineMixin {
	@Inject(method = {"renderHitOutline", "renderBlockOutline"}, at = @At("HEAD"), cancellable = true, require = 0)
	private void kyoto$hideVanillaOutline(CallbackInfo ci) {
		if (dev.kyoto.extra.WorldOverlays.outlineOn()) ci.cancel();
	}
}
