package dev.kyoto.mixin;

import dev.kyoto.module.Modules;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Hides the vanilla crosshair when Kyoto's custom crosshair is on. Safe if the method moved. */
@Pseudo
@Mixin(targets = {"net.minecraft.client.gui.Gui", "net.minecraft.client.gui.Hud", "net.minecraft.client.gui.hud.Hud"})
public abstract class CrosshairMixin {
	@Inject(method = "extractCrosshair", at = @At("HEAD"), cancellable = true, require = 0)
	private void kyoto$hideCrosshair(CallbackInfo ci) {
		if (Modules.on("custom_crosshair")) ci.cancel();
	}
}
