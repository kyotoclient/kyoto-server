package dev.kyoto.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dev.kyoto.feature.SkyTint;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

@Pseudo
@Mixin(targets = {"net.minecraft.client.renderer.fog.FogRenderer", "net.minecraft.client.renderer.FogRenderer"})
public abstract class FogColorMixin {
	@ModifyReturnValue(method = "computeFogColor", at = @At("RETURN"), require = 0)
	private Vector4f kyoto$fog(Vector4f original) {
		if (original == null) return null;
		float[] c = SkyTint.horizon(original.x(), original.y(), original.z());
		return new Vector4f(c[0], c[1], c[2], original.w());
	}
}
