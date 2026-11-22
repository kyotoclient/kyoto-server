package dev.kyoto.mixin;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Server packs: draws the AH deal glow on top of chest menus. Only draws, never clicks. */
@Mixin(AbstractContainerScreen.class)
public abstract class ContainerScreenMixin {
	@Inject(method = "extractRenderState", at = @At("TAIL"), require = 0)
	private void kyoto$packOverlay(GuiGraphicsExtractor g, int mouseX, int mouseY, float partial, CallbackInfo ci) {
		try {
			dev.kyoto.servers.Market.drawOverlay((AbstractContainerScreen<?>) (Object) this, g);
		} catch (Throwable ignored) {
		}
		try {
			dev.kyoto.extra.InvSearch.draw((AbstractContainerScreen<?>) (Object) this, g);
		} catch (Throwable ignored) {
		}
	}
}
