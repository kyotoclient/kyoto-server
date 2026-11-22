package dev.kyoto.screen;

import dev.kyoto.config.KyotoConfig;
import dev.kyoto.hud.Crosshair;
import dev.kyoto.module.Modules;
import dev.kyoto.util.Anim;
import dev.kyoto.util.Ui;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;

/**
 * Crosshair editor with two pages:
 *  Settings - style, size, gap, colour, scale, rotation, target colour, hit pulse...
 *  Draw     - paint your own 15x15 crosshair pixel by pixel (left click paints, right click erases).
 */
public class CrosshairScreen extends PanelScreen {
	private boolean drawPage = false;
	private int brush = 1;

	public CrosshairScreen(Screen parent) {
		super("Crosshair Editor", parent);
	}

	@Override
	protected String subtitle() {
		return drawPage ? "Left click paints, right click erases. Mirror copies to all 4 sides." : "Changes save instantly.";
	}

	@Override
	protected String icon() {
		return "crosshair";
	}

	private KyotoConfig.Crosshair c() {
		KyotoConfig.Crosshair c = KyotoConfig.get().crosshair;
		if (c.pixels == null || c.pixels.length != 225) c.pixels = new int[225];
		return c;
	}

	private void save() {
		KyotoConfig.save();
	}

	@Override
	protected void build() {
		btn(px + 14, py + 34, 112, (drawPage ? "" : "▶ ") + "Settings", () -> drawPage = false);
		btn(px + 132, py + 34, 112, (drawPage ? "▶ " : "") + "Draw your own", () -> {
			drawPage = true;
			c().style = Crosshair.PAINTED;
			save();
		});
		if (drawPage) buildDraw();
		else buildSettings();
	}

	private void buildSettings() {
		int y = py + 58;
		int step = 20;
		btn(px + 14, y, 230, "Custom crosshair: " + (Modules.on("custom_crosshair") ? "ON" : "OFF"), () -> Modules.get("custom_crosshair").toggle());
		y += step + 2;
		row(y, "Style", () -> Crosshair.STYLES[c().style], () -> { c().style = Math.floorMod(c().style - 1, Crosshair.STYLES.length); save(); },
				() -> { c().style = (c().style + 1) % Crosshair.STYLES.length; save(); });
		y += step;
		row(y, "Size", () -> String.valueOf(c().size), () -> { c().size = clamp(c().size - 1, 1, 20); save(); }, () -> { c().size = clamp(c().size + 1, 1, 20); save(); });
		y += step;
		row(y, "Gap", () -> String.valueOf(c().gap), () -> { c().gap = clamp(c().gap - 1, 0, 15); save(); }, () -> { c().gap = clamp(c().gap + 1, 0, 15); save(); });
		y += step;
		row(y, "Thickness", () -> String.valueOf(c().thickness), () -> { c().thickness = clamp(c().thickness - 1, 1, 5); save(); }, () -> { c().thickness = clamp(c().thickness + 1, 1, 5); save(); });
		y += step;
		row(y, "Colour", () -> Crosshair.COLORS[c().colorMode], () -> { c().colorMode = Math.floorMod(c().colorMode - 1, 4); save(); }, () -> { c().colorMode = (c().colorMode + 1) % 4; save(); });
		y += step;
		row(y, "Hue", () -> Math.round(c().hue) + "°", () -> { c().hue = (c().hue + 345) % 360; save(); }, () -> { c().hue = (c().hue + 15) % 360; save(); });
		y += step;
		row(y, "Opacity", () -> Math.round(c().opacity * 100) + "%", () -> { c().opacity = clampF(c().opacity - 0.1f, 0.1f, 1f); save(); }, () -> { c().opacity = clampF(c().opacity + 0.1f, 0.1f, 1f); save(); });
		y += step;
		row(y, "Scale", () -> Math.round(c().scale * 100) + "%", () -> { c().scale = clampF(c().scale - 0.25f, 0.5f, 3f); save(); }, () -> { c().scale = clampF(c().scale + 0.25f, 0.5f, 3f); save(); });
		y += step + 2;
		btn(px + 14, y, 56, "Outline " + onOff(c().outline), () -> { c().outline = !c().outline; save(); });
		btn(px + 72, y, 56, "Dot " + onOff(c().dot), () -> { c().dot = !c().dot; save(); });
		btn(px + 130, y, 56, "Moving " + onOff(c().dynamic), () -> { c().dynamic = !c().dynamic; save(); });
		btn(px + 188, y, 56, "45° " + onOff(c().rotation != 0), () -> { c().rotation = c().rotation == 0 ? 45 : 0; save(); });
		y += 22;
		btn(px + 14, y, 76, "Red on target " + onOff(c().targetColor), () -> { c().targetColor = !c().targetColor; save(); });
		btn(px + 92, y, 76, "Hit pulse " + onOff(c().hitPulse), () -> { c().hitPulse = !c().hitPulse; save(); });
		btn(px + 170, y, 74, "3rd person " + onOff(c().thirdPerson), () -> { c().thirdPerson = !c().thirdPerson; save(); });
		btn(px + 14, py + ph - 26, 80, "Reset", () -> { KyotoConfig.get().crosshair = new KyotoConfig.Crosshair(); save(); });
	}

	private static String onOff(boolean b) {
		return b ? "✔" : "✖";
	}

	private void buildDraw() {
		int y = py + 60;
		for (int i = 0; i < Crosshair.PAINT_NAMES.length; i++) {
			final int fi = i;
			btn(px + 14 + (i % 2) * 116, y + (i / 2) * 22, 112, (brush == i ? "▶ " : "") + Crosshair.PAINT_NAMES[i], () -> brush = fi);
		}
		y += 4 * 22 + 6;
		btn(px + 14, y, 112, "Mirror: " + (c().mirror ? "On" : "Off"), () -> { c().mirror = !c().mirror; save(); });
		btn(px + 130, y, 112, "Clear", () -> { c().pixels = new int[225]; save(); });
		y += 22;
		btn(px + 14, y, 112, "Start: plus", () -> { preset(0); save(); });
		btn(px + 130, y, 112, "Start: circle", () -> { preset(1); save(); });
		y += 22;
		btn(px + 14, y, 112, "Start: dot", () -> { preset(2); save(); });
		btn(px + 130, y, 112, "Start: arrow", () -> { preset(3); save(); });
		y += 22;
		btn(px + 14, y, 228, "Custom crosshair: " + (Modules.on("custom_crosshair") ? "ON" : "OFF"), () -> Modules.get("custom_crosshair").toggle());
	}

	private void preset(int which) {
		int[] p = new int[225];
		for (int y = 0; y < 15; y++)
			for (int x = 0; x < 15; x++) {
				int dx = x - 7, dy = y - 7;
				boolean on = switch (which) {
					case 0 -> (dx == 0 && Math.abs(dy) >= 2 && Math.abs(dy) <= 6) || (dy == 0 && Math.abs(dx) >= 2 && Math.abs(dx) <= 6);
					case 1 -> Math.abs(Math.sqrt(dx * dx + dy * dy) - 5) < 0.6;
					case 2 -> Math.abs(dx) <= 1 && Math.abs(dy) <= 1;
					default -> dy >= 1 && dy <= 5 && Math.abs(dx) == dy;
				};
				if (on) p[y * 15 + x] = 1;
			}
		c().pixels = p;
	}

	/* ---------------- painting ---------------- */

	private int gridX, gridY, cell;

	private void paintAt(double mx, double my, int value) {
		int gx = (int) Math.floor((mx - gridX) / cell), gy = (int) Math.floor((my - gridY) / cell);
		if (gx < 0 || gy < 0 || gx >= 15 || gy >= 15) return;
		int[] p = c().pixels;
		boolean changed = set(p, gx, gy, value);
		if (c().mirror) {
			changed |= set(p, 14 - gx, gy, value);
			changed |= set(p, gx, 14 - gy, value);
			changed |= set(p, 14 - gx, 14 - gy, value);
		}
		if (changed) save();
	}

	private static boolean set(int[] p, int x, int y, int v) {
		int i = y * 15 + x;
		if (p[i] == v) return false;
		p[i] = v;
		return true;
	}

	@Override
	protected void drawContent(GuiGraphicsExtractor g, int mx, int my, float a) {
		int bx1 = px + 262, by1 = py + 58, bx2 = px + pw - 14, by2 = py + ph - 36;
		if (bx2 - bx1 < 80) return;
		if (drawPage) {
			cell = Math.max(4, Math.min((bx2 - bx1 - 16) / 15, (by2 - by1 - 40) / 15));
			gridX = (bx1 + bx2) / 2 - cell * 15 / 2;
			gridY = by1 + 6;
			Ui.round(g, gridX - 4, gridY - 4, gridX + cell * 15 + 4, gridY + cell * 15 + 4, 6, Anim.argb(Ui.GLASS, 0.9f * a));
			int main = Crosshair.color(c());
			int[] p = c().pixels;
			for (int i = 0; i < 225; i++) {
				int x = gridX + (i % 15) * cell, y = gridY + (i / 15) * cell;
				boolean centre = i == 112;
				int bg = ((i % 15 + i / 15) % 2 == 0) ? Ui.SOFT : Ui.GLASS;
				g.fill(x, y, x + cell - 1, y + cell - 1, Anim.argb(centre ? Ui.SOFT : bg, a));
				int v = p[i];
				if (v > 0 && v < Crosshair.PAINT.length) g.fill(x, y, x + cell - 1, y + cell - 1, v == 1 ? main : Anim.argb(Crosshair.PAINT[v], a));
			}
			// live painting while a mouse button is held
			var mh = this.minecraft == null ? null : this.minecraft.mouseHandler;
			if (mh != null && Ui.inside(mx, my, gridX, gridY, gridX + cell * 15, gridY + cell * 15)) {
				if (mh.isLeftPressed()) paintAt(mx, my, brush);
				else if (mh.isRightPressed()) paintAt(mx, my, 0);
				int gx = (mx - gridX) / cell, gy = (my - gridY) / cell;
				int hx = gridX + gx * cell, hy = gridY + gy * cell;
				Ui.outline(g, hx - 1, hy - 1, hx + cell, hy + cell, Anim.argb(Ui.INK, 0.8f * a));
			}
			// actual-size + 3x preview
			int pyv = gridY + cell * 15 + 10;
			Ui.small(g, "In game:", bx1 + 4, pyv + 4, Anim.argb(Ui.MUTED, a));
			Crosshair.drawPainted(g, bx1 + 60, pyv + 8, c(), main);
			g.pose().pushMatrix();
			g.pose().translate(bx2 - 30, pyv + 8);
			g.pose().scale(2f, 2f);
			Crosshair.drawPainted(g, 0, 0, c(), main);
			g.pose().popMatrix();
			return;
		}
		Ui.round(g, bx1, by1, bx2, by2, 8, Anim.argb(0x4E6A40, 0.55f * a));
		Ui.round(g, bx1, by1, bx2, (by1 + by2) / 2, 8, Anim.argb(0x6FA8DC, 0.45f * a));
		int cx = (bx1 + bx2) / 2, cy = (by1 + by2) / 2;
		g.pose().pushMatrix();
		g.pose().translate(cx, cy);
		g.pose().scale(3f * c().scale, 3f * c().scale);
		if (c().rotation != 0) g.pose().rotate((float) Math.toRadians(45));
		Crosshair.draw(g, 0, 0, c(), 0);
		g.pose().popMatrix();
		Ui.text(g, "Preview (3x)", bx1 + 6, by2 - 12, Anim.argb(Ui.INK, 0.8f * a));
	}
}
