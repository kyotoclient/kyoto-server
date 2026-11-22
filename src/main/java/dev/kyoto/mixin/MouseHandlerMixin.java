package dev.kyoto.mixin;

import dev.kyoto.feature.Zoom;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * While zooming, the scroll wheel changes the zoom level instead of your hotbar slot.
 * Targets the 2nd double argument (vertical scroll) so it works whether or not the method
 * also takes a window handle.
 */
@Mixin(MouseHandler.class)
public abstract class MouseHandlerMixin {
	@ModifyVariable(method = "onScroll", at = @At("HEAD"), argsOnly = true, ordinal = 1, require = 0)
	private double kyoto$zoomScroll(double vertical) {
		if (dev.kyoto.screens.Screens.consumeScroll(vertical)) return 0.0;
		if (Zoom.consumeScroll(vertical)) return 0.0;
		return vertical;
	}
}
