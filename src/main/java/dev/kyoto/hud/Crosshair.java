package dev.kyoto.hud;

import dev.kyoto.config.KyotoConfig;
import dev.kyoto.util.Anim;
import dev.kyoto.util.Ui;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/** Draws the custom crosshair (also used by the editor's preview). */
public final class Crosshair {
	private Crosshair() {}

	public static final String[] STYLES = {"Cross", "Dot", "Circle", "Cross + Dot", "T-Shape", "X", "Chevron", "Square", "Brackets", "Painted (draw your own)"};
	public static final int PAINTED = 9;
	public static final int[] PAINT = {0, -1, 0xFFFFFF, 0x000000, 0xFF4D4D, 0x5CFF7A, 0x5CA8FF};
	public static final String[] PAINT_NAMES = {"Eraser", "Main colour", "White", "Black", "Red", "Green", "Blue"};
	private static long hitAt = 0;

	public static void onHit() {
		hitAt = System.currentTimeMillis();
	}
	public static final String[] COLORS = {"Theme", "White", "Custom hue", "RGB"};

	private static float spread = 0;

	public static int color(KyotoConfig.Crosshair c) {
		int rgb = switch (c.colorMode) {
			case 1 -> 0xFFFFFF;
			case 2 -> Ui.hsv(c.hue, 0.8f, 1f);
			case 3 -> Ui.rgbCycle(0);
			default -> Ui.accent();
		};
		return Anim.argb(rgb, c.opacity);
	}

	public static void drawInGame(GuiGraphicsExtractor g, int w, int h) {
		Minecraft mc = Minecraft.getInstance();
		KyotoConfig.Crosshair c = KyotoConfig.get().crosshair;
		if (mc.player == null || (!mc.options.getCameraType().isFirstPerson() && !c.thirdPerson)) return;
		float target = 0;
		if (c.dynamic) {
			double speed = mc.player.getDeltaMovement().horizontalDistance() * 20;
			target = (float) Math.min(4, speed * 0.6);
			if (mc.options.keyAttack.isDown()) target += 2;
			if (!mc.player.onGround()) target += 1.5f;
		}
		spread += (target - spread) * 0.25f;
		boolean onTarget = c.targetColor && mc.crosshairPickEntity != null;
		float pulse = c.hitPulse ? Math.max(0, 1 - (System.currentTimeMillis() - hitAt) / 180f) : 0;
		g.pose().pushMatrix();
		g.pose().translate(w / 2f, h / 2f);
		float sc = c.scale * (1 + pulse * 0.35f);
		g.pose().scale(sc, sc);
		if (c.rotation != 0) g.pose().rotate((float) Math.toRadians(45));
		targetOverride = onTarget ? Anim.argb(0xFF4D4D, c.opacity) : 0;
		draw(g, 0, 0, c, Math.round(spread + pulse * 2));
		targetOverride = 0;
		g.pose().popMatrix();
	}

	private static int targetOverride = 0;

	public static void draw(GuiGraphicsExtractor g, int cx, int cy, KyotoConfig.Crosshair c, int extraGap) {
		int col = targetOverride != 0 ? targetOverride : color(c);
		if (c.style == PAINTED) {
			drawPainted(g, cx, cy, c, col);
			return;
		}
		int out = Anim.argb(0x000000, 0.75f * c.opacity);
		int s = c.size, gap = c.gap + extraGap, t = Math.max(1, c.thickness);
		int ht = t / 2;
		switch (c.style) {
			case 1 -> square(g, cx - ht, cy - ht, t + 1, t + 1, col, out, c.outline);
			case 2 -> ring(g, cx, cy, s + gap, t, col);
			case 4 -> { // T
				bar(g, cx - s - gap, cy - ht, s, t, col, out, c.outline);
				bar(g, cx + gap + 1, cy - ht, s, t, col, out, c.outline);
				bar(g, cx - ht, cy + gap + 1, t, s, col, out, c.outline);
			}
			case 5 -> { // X
				for (int i = gap; i < gap + s; i++) {
					for (int k = 0; k < t; k++) {
						g.fill(cx + i + k, cy + i, cx + i + k + 1, cy + i + 1, col);
						g.fill(cx - i - k, cy + i, cx - i - k + 1, cy + i + 1, col);
						g.fill(cx + i + k, cy - i, cx + i + k + 1, cy - i + 1, col);
						g.fill(cx - i - k, cy - i, cx - i - k + 1, cy - i + 1, col);
					}
				}
			}
			case 6 -> { // chevron
				for (int i = 0; i < s; i++) {
					for (int k = 0; k < t; k++) {
						g.fill(cx - i - k, cy + gap + i, cx - i - k + 1, cy + gap + i + 1, col);
						g.fill(cx + i + k, cy + gap + i, cx + i + k + 1, cy + gap + i + 1, col);
					}
				}
			}
			case 7 -> { // hollow square
				int r = s + gap;
				g.outline(cx - r, cy - r, r * 2 + 1, r * 2 + 1, col);
			}
			case 8 -> { // brackets [ ]
				int r = s + gap;
				bar(g, cx - r, cy - r, t, r * 2 + 1, col, out, c.outline);
				bar(g, cx - r, cy - r, 3, t, col, out, c.outline);
				bar(g, cx - r, cy + r + 1 - t, 3, t, col, out, c.outline);
				bar(g, cx + r + 1 - t, cy - r, t, r * 2 + 1, col, out, c.outline);
				bar(g, cx + r - 2, cy - r, 3, t, col, out, c.outline);
				bar(g, cx + r - 2, cy + r + 1 - t, 3, t, col, out, c.outline);
			}
			default -> { // cross (+ dot)
				bar(g, cx - s - gap, cy - ht, s, t, col, out, c.outline);
				bar(g, cx + gap + 1, cy - ht, s, t, col, out, c.outline);
				bar(g, cx - ht, cy - s - gap, t, s, col, out, c.outline);
				bar(g, cx - ht, cy + gap + 1, t, s, col, out, c.outline);
			}
		}
		if (c.dot || c.style == 3) square(g, cx - ht, cy - ht, t, t, col, out, c.outline);
	}

	/** 15x15 hand-drawn crosshair, centred on cx,cy. */
	public static void drawPainted(GuiGraphicsExtractor g, int cx, int cy, KyotoConfig.Crosshair c, int mainCol) {
		if (c.pixels == null || c.pixels.length != 225) c.pixels = new int[225];
		for (int i = 0; i < 225; i++) {
			int v = c.pixels[i];
			if (v <= 0 || v >= PAINT.length) continue;
			int col = v == 1 ? mainCol : Anim.argb(PAINT[v], c.opacity);
			int x = cx + (i % 15) - 7, y = cy + (i / 15) - 7;
			g.fill(x, y, x + 1, y + 1, col);
		}
	}

	private static void bar(GuiGraphicsExtractor g, int x, int y, int w, int h, int col, int out, boolean outline) {
		if (outline) g.fill(x - 1, y - 1, x + w + 1, y + h + 1, out);
		g.fill(x, y, x + w, y + h, col);
	}

	private static void square(GuiGraphicsExtractor g, int x, int y, int w, int h, int col, int out, boolean outline) {
		bar(g, x, y, Math.max(1, w), Math.max(1, h), col, out, outline);
	}

	private static void ring(GuiGraphicsExtractor g, int cx, int cy, int r, int t, int col) {
		int steps = Math.max(24, r * 8);
		for (int i = 0; i < steps; i++) {
			double a = i * Math.PI * 2 / steps;
			int x = cx + (int) Math.round(Math.cos(a) * r), y = cy + (int) Math.round(Math.sin(a) * r);
			g.fill(x, y, x + t, y + t, col);
		}
	}
}
