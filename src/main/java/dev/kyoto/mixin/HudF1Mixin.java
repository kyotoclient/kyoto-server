package dev.kyoto.mixin;

import dev.kyoto.hud.KyotoHud;
import dev.kyoto.module.Modules;
import dev.kyoto.music.LyricsHud;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Lyrics stay visible with F1 (hidden HUD) - perfect for screenshots and videos. */
@Pseudo
@Mixin(targets = {"net.minecraft.client.gui.Gui", "net.minecraft.client.gui.Hud"})
public abstract class HudF1Mixin {
	@Inject(method = {"extractRenderState", "render"}, at = @At("TAIL"), require = 0)
	private void kyoto$lyricsInF1(GuiGraphicsExtractor g, DeltaTracker delta, CallbackInfo ci) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || Modules.opt("lyrics.f1") != 0 || !hudHidden(mc)) return;
		// the normal HUD didn't run this frame (F1) - draw just the lyrics
		if (System.nanoTime() - KyotoHud.lastExtractNanos < 30_000_000L) return;
		int w = mc.getWindow().getGuiScaledWidth(), h = mc.getWindow().getGuiScaledHeight();
		LyricsHud.render(g, mc, w, h, delta.getGameTimeDeltaPartialTick(false));
	}

	/** F1 state. Mojang moved it in 26.x, so we look for it by name instead of hard-coding it. */
	private static boolean hudHidden(Minecraft mc) {
		try {
			java.lang.reflect.Field f = mc.options.getClass().getField("hideGui");
			return f.getBoolean(mc.options);
		} catch (Throwable ignored) {
		}
		try {
			Object gui = mc.gui;
			Object hud = null;
			try {
				hud = gui.getClass().getField("hud").get(gui);
			} catch (Throwable ignored) {
			}
			for (Object t : new Object[]{gui, hud}) {
				if (t == null) continue;
				for (String name : new String[]{"isHidden", "isHudHidden", "hidden", "hideGui"}) {
					try {
						java.lang.reflect.Method m = t.getClass().getMethod(name);
						if (m.getReturnType() == boolean.class) return (boolean) m.invoke(t);
					} catch (NoSuchMethodException ignored) {
					}
					try {
						java.lang.reflect.Field f = t.getClass().getField(name);
						if (f.getType() == boolean.class) return f.getBoolean(t);
					} catch (NoSuchFieldException ignored) {
					}
				}
			}
		} catch (Throwable ignored) {
		}
		// last resort: the HUD hasn't drawn for a moment = it's hidden
		return true;
	}
}
