package dev.kyoto.extra;

import dev.kyoto.module.Modules;
import dev.kyoto.util.Ui;
import net.minecraft.client.Minecraft;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Hit colour: changes the red flash mobs and players get when they're hit. Minecraft keeps that flash in a tiny
 * 16x16 "overlay" texture; we repaint its top half in your colour. Purely visual, only on your screen.
 */
public final class HitColor {
	private HitColor() {}

	public static final String[] NAMES = {"Red (normal)", "Pink", "Purple", "Blue", "Mint", "Gold", "White", "Accent"};
	private static final int[] COLS = {0xFF0000, 0xFF5FB0, 0xA35CFF, 0x3D8BFF, 0x3DFFB0, 0xFFC229, 0xFFFFFF, -1};
	private static int applied = -2;
	private static long lastTry = 0;

	public static void tick(Minecraft mc) {
		int want = Modules.on("hit_color") ? Modules.opt("hitcolor.color") : 0;
		int rgb = COLS[Math.max(0, Math.min(COLS.length - 1, want))];
		if (rgb == -1) rgb = Ui.accent() & Ui.INK;
		int key = want == 7 ? rgb : want;
		if (key == applied || System.currentTimeMillis() - lastTry < 2000) return;
		lastTry = System.currentTimeMillis();
		if (paint(mc, rgb, Modules.opt("hitcolor.strength"))) applied = key;
	}

	private static boolean paint(Minecraft mc, int rgb, int strength) {
		try {
			Object ov = mc.gameRenderer.getClass().getMethod("overlayTexture").invoke(mc.gameRenderer);
			Object dyn = null;
			for (Field f : ov.getClass().getDeclaredFields()) {
				if (f.getType().getSimpleName().contains("DynamicTexture")) {
					f.setAccessible(true);
					dyn = f.get(ov);
					break;
				}
			}
			if (dyn == null) return false;
			Object img = dyn.getClass().getMethod("getPixels").invoke(dyn);
			int alpha = new int[]{0x60, 0xB2, 0xE0}[Math.max(0, Math.min(2, strength))];
			Method set = null;
			boolean abgr = false;
			try {
				set = img.getClass().getMethod("setPixel", int.class, int.class, int.class);
			} catch (NoSuchMethodException e) {
				set = img.getClass().getMethod("setPixelRGBA", int.class, int.class, int.class);
				abgr = true;
			}
			int argb = (alpha << 24) | rgb;
			int c = abgr ? (argb & 0xFF00FF00) | ((argb & 0xFF) << 16) | ((argb >> 16) & 0xFF) : argb;
			for (int y = 0; y < 8; y++) for (int x = 0; x < 16; x++) set.invoke(img, x, y, c);
			dyn.getClass().getMethod("upload").invoke(dyn);
			return true;
		} catch (Throwable t) {
			return false;
		}
	}
}
