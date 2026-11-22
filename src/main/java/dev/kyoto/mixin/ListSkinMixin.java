package dev.kyoto.mixin;

import dev.kyoto.ui.VanillaSkin;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSelectionList;
import net.minecraft.client.gui.components.AbstractWidget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Server list, world list and option lists: soft dark panel + hairlines instead of Minecraft's textures. Rows (and server icons) stay Minecraft's. */
@Mixin(AbstractSelectionList.class)
public abstract class ListSkinMixin {
	private boolean kyoto$on() {
		Object sc = dev.kyoto.util.Compat.currentScreen();
		return sc != null && VanillaSkin.styled(sc);
	}

	@Inject(method = {"extractListBackground", "renderListBackground"}, at = @At("HEAD"), cancellable = true, require = 0)
	private void kyoto$bg(GuiGraphicsExtractor g, CallbackInfo ci) {
		try {
			if (!kyoto$on()) return;
			VanillaSkin.listBackground((AbstractWidget) (Object) this, g);
			ci.cancel();
		} catch (Throwable ignored) {
		}
	}

	@Inject(method = {"extractListSeparators", "renderListSeparators"}, at = @At("HEAD"), cancellable = true, require = 0)
	private void kyoto$sep(GuiGraphicsExtractor g, CallbackInfo ci) {
		try {
			if (!kyoto$on()) return;
			VanillaSkin.listSeparators((AbstractWidget) (Object) this, g);
			ci.cancel();
		} catch (Throwable ignored) {
		}
	}
}
