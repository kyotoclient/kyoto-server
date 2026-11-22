package dev.kyoto.screen;

import dev.kyoto.config.KyotoConfig;
import dev.kyoto.module.Modules;
import dev.kyoto.util.Anim;
import dev.kyoto.util.Ui;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;

/** Pick ANY accent color, or RGB mode. */
public class ThemeScreen extends PanelScreen {
	private static final String[] MODES = {"Lavender", "Ice", "Mint", "Rose", "Gold", "Crimson", "Custom", "RGB"};

	public ThemeScreen(Screen parent) {
		super("Theme", parent);
	}

	@Override
	protected String subtitle() {
		return "Colors used by every Kyoto menu, HUD and effect.";
	}

	@Override
	protected String icon() {
		return "theme";
	}

	@Override
	protected void build() {
		KyotoConfig c = KyotoConfig.get();
		int y = py + 38, step = 22;
		btn(px + 14, y, 230, "Accent colors: " + (Modules.on("accent") ? "ON" : "OFF (white glass)"), () -> Modules.get("accent").toggle());
		y += step + 2;
		row(y, "Mode", () -> MODES[Modules.opt("accent.color")], () -> Modules.setting("accent.color").cycle(-1), () -> Modules.setting("accent.color").cycle(1));
		y += step;
		row(y, "Hue", () -> Math.round(c.accentHue) + "°", () -> { c.accentHue = (c.accentHue + 350) % 360; pickCustom(); }, () -> { c.accentHue = (c.accentHue + 10) % 360; pickCustom(); });
		y += step;
		row(y, "Saturation", () -> Math.round(c.accentSat * 100) + "%", () -> { c.accentSat = clampF(c.accentSat - 0.05f, 0, 1); pickCustom(); }, () -> { c.accentSat = clampF(c.accentSat + 0.05f, 0, 1); pickCustom(); });
		y += step;
		row(y, "Brightness", () -> Math.round(c.accentBright * 100) + "%", () -> { c.accentBright = clampF(c.accentBright - 0.05f, 0.2f, 1); pickCustom(); }, () -> { c.accentBright = clampF(c.accentBright + 0.05f, 0.2f, 1); pickCustom(); });
		y += step;
		row(y, "RGB speed", () -> String.format("%.2fx", c.rgbSpeed), () -> { c.rgbSpeed = clampF(c.rgbSpeed - 0.25f, 0.25f, 4); KyotoConfig.save(); }, () -> { c.rgbSpeed = clampF(c.rgbSpeed + 0.25f, 0.25f, 4); KyotoConfig.save(); });
		y += step + 2;
		btn(px + 14, y, 112, "Font: " + (Modules.on("custom_font") ? "Kyoto" : "Minecraft"), () -> Modules.get("custom_font").toggle());
		btn(px + 132, y, 112, "Animations: " + (Modules.on("ui_animations") ? "On" : "Off"), () -> Modules.get("ui_animations").toggle());
		y += step;
		btn(px + 14, y, 230, "Use RGB mode", () -> {
			Modules.get("accent").set(true);
			while (Modules.opt("accent.color") != 7) Modules.setting("accent.color").cycle(1);
		});
	}

	private void pickCustom() {
		KyotoConfig.save();
		Modules.get("accent").set(true);
		while (Modules.opt("accent.color") != 6) Modules.setting("accent.color").cycle(1);
	}

	@Override
	protected void drawContent(GuiGraphicsExtractor g, int mx, int my, float a) {
		int x = px + 262, y = py + 40, x2 = px + pw - 14;
		if (x + 120 > x2) return;
		int acc = Ui.accent();
		Ui.round(g, x, y, x2, y + 70, 8, Anim.argb(acc, 0.85f * a));
		Ui.bold(g, "Accent", x + 10, y + 8, Anim.argb(Ui.GLASS, a));
		String hex = String.format("#%06X", acc);
		Ui.text(g, hex, x + 10, y + 20, Anim.argb(Ui.GLASS, a));
		// mini card preview
		int cy = y + 82;
		Ui.round(g, x, cy, x2, cy + 44, 7, Anim.argb(Ui.INK, 0.06f * a));
		Ui.round(g, x, cy + 8, x + 2, cy + 36, 1, Anim.argb(acc, a));
		Ui.bold(g, "Module", x + 9, cy + 7, Anim.argb(Ui.TEXT, a));
		Ui.text(g, "This is how cards look.", x + 9, cy + 20, Anim.argb(Ui.MUTED, a));
		Ui.round(g, x2 - 30, cy + 15, x2 - 8, cy + 27, 6, Anim.argb(acc, a));
		Ui.round(g, x2 - 19, cy + 16, x2 - 9, cy + 26, 5, Anim.argb(Ui.INK, a));
		// rainbow strip for RGB
		for (int i = 0; i < x2 - x; i += 2) {
			g.fill(x + i, cy + 54, x + i + 2, cy + 58, Anim.argb(Ui.hsv(i * 360f / (x2 - x), 0.55f, 1f), a));
		}
	}
}
