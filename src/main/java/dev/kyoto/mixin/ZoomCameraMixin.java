package dev.kyoto.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dev.kyoto.feature.Zoom;
import net.minecraft.client.Camera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Zoom hooks #2/#3: newer versions compute the FOV inside Camera. Only one hook ever applies the zoom. */
@Mixin(Camera.class)
public abstract class ZoomCameraMixin {
	@ModifyReturnValue(method = "calculateFov", at = @At("RETURN"), require = 0)
	private float kyoto$zoomCalcFov(float original) {
		return Zoom.hook(Zoom.HOOK_CAMERA_CALC, original);
	}

	@ModifyReturnValue(method = "getFov", at = @At("RETURN"), require = 0)
	private float kyoto$zoomGetFov(float original) {
		return Zoom.hook(Zoom.HOOK_CAMERA, original);
	}
}
