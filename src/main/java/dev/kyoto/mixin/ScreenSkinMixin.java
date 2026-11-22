package dev.kyoto.mixin;

import dev.kyoto.ui.VanillaSkin;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Kyoto look for Minecraft's own menus (see VanillaSkin). */
@Mixin(Screen.class)
public abstract class ScreenSkinMixin {
	@Inject(method = "extractBackground", at = @At("HEAD"), cancellable = true, require = 0)
	private void kyoto$bg(GuiGraphicsExtractor g, int mouseX, int mouseY, float partial, CallbackInfo ci) {
		try {
			if (VanillaSkin.styled(this) && VanillaSkin.background((Screen) (Object) this, g)) ci.cancel();
		} catch (Throwable ignored) {
		}
	}

	@Inject(method = "extractBackground", at = @At("TAIL"), require = 0)
	private void kyoto$tint(GuiGraphicsExtractor g, int mouseX, int mouseY, float partial, CallbackInfo ci) {
		try {
			if (VanillaSkin.styled(this)) VanillaSkin.tint((Screen) (Object) this, g);
		} catch (Throwable ignored) {
		}
	}

	@Inject(method = "extractRenderState", at = @At("TAIL"), require = 0)
	private void kyoto$paint(GuiGraphicsExtractor g, int mouseX, int mouseY, float partial, CallbackInfo ci) {
		try {
			if (VanillaSkin.styled(this)) VanillaSkin.paint((Screen) (Object) this, g, mouseX, mouseY);
		} catch (Throwable ignored) {
		}
	}
}
