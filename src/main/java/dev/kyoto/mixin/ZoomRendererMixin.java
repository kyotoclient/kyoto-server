package dev.kyoto.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dev.kyoto.feature.Zoom;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Zoom hook #1 (preferred): the FOV the renderer uses for the world, every frame. */
@Mixin(GameRenderer.class)
public abstract class ZoomRendererMixin {
	@ModifyReturnValue(method = "getFov", at = @At("RETURN"), require = 0)
	private float kyoto$zoomRendererFov(float original) {
		return Zoom.hook(Zoom.HOOK_RENDERER, original);
	}
}
