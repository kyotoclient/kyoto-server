package dev.kyoto.mixin;

import dev.kyoto.module.Modules;
import dev.kyoto.ui.TitleTheme;
import dev.kyoto.util.Anim;
import dev.kyoto.util.Ui;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Kyoto title screen: replaces the panorama/logo/splash drawing with Kyoto's themed scene,
 * then draws every button (vanilla + other mods + Kyoto) on top. Turn off in Settings > Title.
 */
@Mixin(TitleScreen.class)
public abstract class TitleScreenMixin extends Screen {
	protected TitleScreenMixin(Component title) {
		super(title);
	}

	@Inject(method = "init", at = @At("TAIL"), require = 0)
	private void kyoto$opened(CallbackInfo ci) {
		TitleTheme.opened();
	}

	@Inject(method = "extractRenderState", at = @At("HEAD"), cancellable = true, require = 0)
	private void kyoto$render(GuiGraphicsExtractor g, int mouseX, int mouseY, float partial, CallbackInfo ci) {
		if (!Modules.on("title_screen")) return;
		TitleTheme.background(g, this.width, this.height);
		TitleTheme.foreground(g, this.width, this.height);
		float in = dev.kyoto.util.Anim.easeOutCubic(dev.kyoto.util.Anim.clamp01((System.currentTimeMillis() - TitleTheme.openedAt()) / 900f - 0.35f));
		float bloom = dev.kyoto.util.Anim.clamp01((System.currentTimeMillis() - TitleTheme.openedAt()) / 1100f);
		if (!dev.kyoto.ui.TitleLayout.draw(Screens.getWidgets((Screen) (Object) this), g, this.width, this.height, mouseX, mouseY, partial, bloom))
			dev.kyoto.ui.Buttons.draw(Screens.getWidgets((Screen) (Object) this), g, mouseX, mouseY, partial, in);
		Ui.small(g, "Kyoto Client " + net.fabricmc.loader.api.FabricLoader.getInstance().getModContainer("kyoto").map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("") + "  ·  Fabric 26.3" + (TitleTheme.current().holiday() ? "  ·  " + TitleTheme.current().title : ""), 8, this.height - 11, Anim.argb(Ui.MUTED, 0.8f));
		ci.cancel();
	}
}
