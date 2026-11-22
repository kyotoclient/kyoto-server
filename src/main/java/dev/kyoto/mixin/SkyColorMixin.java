package dev.kyoto.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dev.kyoto.feature.SkyTint;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Custom Sky colours: tints the sky dome and the horizon fog (only you see it).
 * Method names differ between versions, so every hook is optional (require = 0).
 */
@Pseudo
@Mixin(targets = {"net.minecraft.client.multiplayer.ClientLevel"})
public abstract class SkyColorMixin {
	@ModifyReturnValue(method = "getSkyColor", at = @At("RETURN"), require = 0)
	private int kyoto$sky(int original) {
		return SkyTint.sky(original);
	}
}

