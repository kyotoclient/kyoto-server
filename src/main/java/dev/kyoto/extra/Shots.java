package dev.kyoto.extra;

import dev.kyoto.hud.Notifications;
import dev.kyoto.util.Compat;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.function.Consumer;

/**
 * Clean screenshots: one key hides the HUD and your hand, takes the screenshot, then puts everything back.
 * The Screenshot Studio (Tools) shows all your screenshots.
 */
public final class Shots {
	private Shots() {}

	private static int step = -1;
	private static boolean wasHidden;

	public static void cleanShot() {
		if (step >= 0) return;
		Minecraft mc = Minecraft.getInstance();
		wasHidden = hidden(mc);
		setHidden(mc, true);
		step = 0;
	}

	/** Every client tick. */
	public static void tick(Minecraft mc) {
		if (step < 0) return;
		step++;
		if (step == 2) {
			try {
				grab(mc);
			} catch (Throwable t) {
				Notifications.push("Couldn't take the screenshot", "Press F1 then F2 instead", Notifications.WARN, 3000, null, "image");
			}
		}
		if (step >= 4) {
			setHidden(mc, wasHidden);
			step = -1;
		}
	}

	private static void grab(Minecraft mc) throws Exception {
		Class<?> sc = Class.forName("net.minecraft.client.Screenshot");
		Object target = mc.getClass().getMethod("getMainRenderTarget").invoke(mc);
		File dir = net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir().toFile();
		Consumer<Component> done = msg -> Compat.runOnClient(() -> Notifications.push("Screenshot saved", "Tools > Screenshot Studio to see it", Notifications.GOOD, 3500, null, "image"));
		for (Method m : sc.getMethods()) {
			if (!m.getName().equals("grab") || !java.lang.reflect.Modifier.isStatic(m.getModifiers())) continue;
			Class<?>[] p = m.getParameterTypes();
			if (p.length == 3 && p[0] == File.class && p[2] == Consumer.class) {
				m.invoke(null, dir, target, done);
				return;
			}
			if (p.length == 4 && p[0] == File.class && p[1] == String.class && p[3] == Consumer.class) {
				m.invoke(null, dir, null, target, done);
				return;
			}
		}
		throw new IllegalStateException("no grab");
	}

	/* F1 moved around in 26.x, so look for it by name. */
	static boolean hidden(Minecraft mc) {
		for (Object t : targets(mc)) {
			if (t == null) continue;
			for (String n : new String[]{"hideGui", "hidden", "hudHidden"}) {
				try {
					Field f = t.getClass().getField(n);
					if (f.getType() == boolean.class) return f.getBoolean(t);
				} catch (Throwable ignored) {
				}
			}
			for (String n : new String[]{"isHidden", "isHudHidden"}) {
				try {
					Method m = t.getClass().getMethod(n);
					if (m.getReturnType() == boolean.class) return (boolean) m.invoke(t);
				} catch (Throwable ignored) {
				}
			}
		}
		return false;
	}

	static void setHidden(Minecraft mc, boolean on) {
		for (Object t : targets(mc)) {
			if (t == null) continue;
			for (String n : new String[]{"hideGui", "hidden", "hudHidden"}) {
				try {
					Field f = t.getClass().getField(n);
					if (f.getType() == boolean.class) { f.setBoolean(t, on); return; }
				} catch (Throwable ignored) {
				}
			}
			for (String n : new String[]{"setHidden", "setHudHidden"}) {
				try {
					Method m = t.getClass().getMethod(n, boolean.class);
					m.invoke(t, on);
					return;
				} catch (Throwable ignored) {
				}
			}
		}
	}

	private static Object[] targets(Minecraft mc) {
		Object hud = null;
		try {
			hud = mc.gui.getClass().getField("hud").get(mc.gui);
		} catch (Throwable ignored) {
		}
		return new Object[]{mc.options, mc.gui, hud};
	}
}
