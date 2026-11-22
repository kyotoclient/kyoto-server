package dev.kyoto.mixin;

import dev.kyoto.hud.KyotoF3;
import dev.kyoto.module.Modules;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Kyoto F3: only takes over when the F3 screen is really open.
 * In 26.x the debug overlay draws every frame (even with F3 closed), so we ask it whether
 * the full screen is showing. If we can't tell, we leave vanilla alone.
 */
@Pseudo
@Mixin(targets = "net.minecraft.client.gui.components.DebugScreenOverlay")
public abstract class DebugOverlayMixin {
	@Inject(method = {"extractRenderState", "render"}, at = @At("HEAD"), cancellable = true, require = 0)
	private void kyoto$replaceF3(CallbackInfo ci) {
		if (!Modules.on("kyoto_f3")) return;
		Boolean open = f3Open(this);
		if (open == null || !open) return; // closed, or unknown -> vanilla does its thing
		KyotoF3.vanillaWantedAt = System.currentTimeMillis();
		ci.cancel();
	}

	private static final String[] NAMES = {"showDebugScreen", "isOverlayVisible", "isShowingDebugScreen", "isShowing", "isVisible", "showOverlay"};

	/** true = F3 open, false = closed, null = couldn't find out. */
	private static Boolean f3Open(Object overlay) {
		Boolean b = ask(overlay);
		if (b != null) return b;
		try {
			Minecraft mc = Minecraft.getInstance();
			for (Field f : Minecraft.class.getFields()) {
				if (!f.getName().toLowerCase().contains("debug")) continue;
				Object o = f.get(mc);
				if (o == null || o == overlay) continue;
				b = ask(o);
				if (b != null) return b;
			}
		} catch (Throwable ignored) {
		}
		return null;
	}

	private static Boolean ask(Object o) {
		for (String name : NAMES) {
			try {
				Method m = o.getClass().getMethod(name);
				if (m.getReturnType() == boolean.class) return (boolean) m.invoke(o);
			} catch (Throwable ignored) {
			}
		}
		return null;
	}
}
