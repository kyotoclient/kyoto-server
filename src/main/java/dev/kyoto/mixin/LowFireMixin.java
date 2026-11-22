package dev.kyoto.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Low Fire: moves the "you're on fire" flames down so you can see. (Static version of the method.) */
@Pseudo
@Mixin(targets = "net.minecraft.client.renderer.ScreenEffectRenderer")
public abstract class LowFireMixin {
	@ModifyVariable(method = "renderFire", at = @At("HEAD"), argsOnly = true, require = 0)
	private static PoseStack kyoto$lowFire(PoseStack pose) {
		return dev.kyoto.extra.Extras.lowFireStart(pose);
	}

	@Inject(method = "renderFire", at = @At("RETURN"), require = 0)
	private static void kyoto$lowFireEnd(CallbackInfo ci) {
		dev.kyoto.extra.Extras.lowFireEnd();
	}
}
