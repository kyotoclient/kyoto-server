package dev.kyoto.screen;

import dev.kyoto.config.KyotoConfig;
import dev.kyoto.feature.TimeChanger;
import dev.kyoto.module.Modules;
import dev.kyoto.util.Anim;
import dev.kyoto.util.Ui;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;

/**
 * Tools > Sky + Time.
 *  - Custom Sky: a different sky for day and for night (galaxy, aurora, rainbow, birds...). Only you see it.
 *  - My Time of Day: make it day or night just for you (visual only).
 */
public class SkyScreen extends PanelScreen {
	public SkyScreen(Screen parent) {
		super("Sky + Time", parent);
	}

	@Override
	protected String icon() {
		return "sky";
	}

	@Override
	protected String subtitle() {
		return "Only you see these. The real time, mobs and beds don't change.";
	}

	@Override
	protected void build() {
		int y = py + 38;
		boolean nuit = dev.kyoto.feature.Skyboxes.nuit();
		btn(px + 14, y, 230, "Skybox: " + (Modules.on("skybox") ? "On" : "Off") + (nuit ? "" : " (needs Nuit)"), () -> {
			Modules.get("skybox").toggle();
			dev.kyoto.feature.Skyboxes.apply();
			if (!nuit && Modules.on("skybox")) dev.kyoto.util.Compat.openUrl(dev.kyoto.feature.Skyboxes.NUIT_URL);
		});
		y += 24;
		for (String id : new String[]{"skybox.style", "skybox.when"}) {
			var st = Modules.setting(id);
			row(y, st.name, st::label, () -> { st.cycle(-1); dev.kyoto.feature.Skyboxes.apply(); }, () -> { st.cycle(1); dev.kyoto.feature.Skyboxes.apply(); });
			y += 22;
		}
		y -= 22;
		y += 30;
		btn(px + 14, y, 230, "Custom sky: " + (Modules.on("sky_fx") ? "On" : "Off"), () -> Modules.get("sky_fx").toggle());
		y += 24;
		cycleRow(y, Modules.setting("sky.day"));
		y += 22;
		cycleRow(y, Modules.setting("sky.night"));
		y += 22;
		cycleRow(y, Modules.setting("sky.amount"));
		y += 22;
		cycleRow(y, Modules.setting("sky.tint_day"));
		y += 22;
		cycleRow(y, Modules.setting("sky.tint_night"));
		y += 30;
		btn(px + 14, y, 230, "My time of day: " + (Modules.on("custom_time") ? "On" : "Off"), () -> Modules.get("custom_time").toggle());
		y += 24;
		KyotoConfig c = KyotoConfig.get();
		row(y, "Time", () -> TimeChanger.clock(c.timeTicks), () -> TimeChanger.set(c.timeTicks - 500), () -> TimeChanger.set(c.timeTicks + 500));
		y += 22;
		cycleRow(y, Modules.setting("time.mode"));
		y += 22;
		if (Modules.opt("time.mode") == 1) {
			cycleRow(y, Modules.setting("time.speed"));
			y += 22;
		}
		// quick picks
		for (int i = 0; i < TimeChanger.PRESETS.length; i++) {
			final int fi = i;
			btn(px + 14 + (i % 4) * 58, y + (i / 4) * 22, 56, TimeChanger.PRESETS[i], () -> {
				TimeChanger.set(TimeChanger.PRESET_TICKS[fi]);
				Modules.get("custom_time").set(true);
			});
		}
	}

	@Override
	protected void drawContent(GuiGraphicsExtractor g, int mx, int my, float a) {
		int x1 = px + 258, y1 = py + 38, x2 = px + pw - 14, y2 = py + ph - 14;
		if (x2 - x1 < 90) return;
		// little preview: a sky that matches your time, with the sun/moon on its arc
		long t = TimeChanger.active() ? TimeChanger.target() : KyotoConfig.get().timeTicks;
		double ang = (t / 24000.0) * Math.PI * 2; // 0 = sunrise
		float day = (float) Math.max(0, Math.sin(ang));
		int top = Ui.mix(Ui.GLASS, 0x5C9CFF, day), bottom = Ui.mix(Ui.GLASS, 0xBFE0FF, day);
		long tod = Math.floorMod(t, 24000L);
		if (tod > 11000 && tod < 13500 || tod > 22500 || tod < 500) bottom = Ui.mix(bottom, 0xFF9C6B, 0.6f);
		if (Modules.on("sky_fx")) {
			top = dev.kyoto.feature.SkyTint.sky(top);
			bottom = dev.kyoto.feature.SkyTint.horizon(bottom);
		}
		g.fillGradient(x1, y1, x2, y2, Anim.argb(top, a), Anim.argb(bottom, a));
		if (Modules.on("skybox")) {
			// the skybox's side faces, as a panorama
			String id = dev.kyoto.feature.Skyboxes.IDS[dev.kyoto.feature.Skyboxes.current()];
			net.minecraft.resources.Identifier tex = net.minecraft.resources.Identifier.fromNamespaceAndPath("kyoto", "skies/" + id + ".png");
			int pw2 = x2 - x1, ph2 = y2 - y1;
			int fw = 384 * 3, view = Math.round(384f * pw2 / ph2);
			int off = (int) ((System.currentTimeMillis() / 40) % fw);
			int u = 384 * 0 + off % (fw - view), vh = 384;
			dev.kyoto.util.Compat.blitRegion(g, tex, x1, y1, pw2, ph2, u, 384, Math.min(view, fw - u), vh, 1152, 768, Anim.argb(0xFFFFFF, a));
			if (!dev.kyoto.feature.Skyboxes.nuit()) {
				Ui.round(g, x1 + 6, y2 - 22, x2 - 6, y2 - 6, 6, Anim.argb(0x000000, 0.45f * a));
				Ui.small(g, "Install Nuit to see this in game", x1 + 12, y2 - 17, Anim.argb(0xFFFFFF, a));
			}
			return;
		}
		int cx = (x1 + x2) / 2, cy = y2 - 10, r = Math.min((x2 - x1) / 2 - 12, y2 - y1 - 24);
		int sx = (int) (cx - Math.cos(ang) * r), sy = (int) (cy - Math.sin(ang) * r);
		boolean night = Math.sin(ang) < 0;
		int mx2 = (int) (cx + Math.cos(ang) * r), my2 = (int) (cy + Math.sin(ang) * r);
		if (!night) {
			Ui.round(g, sx - 9, sy - 9, sx + 9, sy + 9, 9, Anim.argb(0xFFE7A0, 0.25f * a));
			Ui.round(g, sx - 5, sy - 5, sx + 5, sy + 5, 5, Anim.argb(0xFFF4D0, a));
		} else {
			for (int i = 0; i < 30; i++) {
				int px2 = x1 + (i * 37 % Math.max(1, x2 - x1)), py2 = y1 + (i * 53 % Math.max(1, y2 - y1 - 20));
				g.fill(px2, py2, px2 + 1, py2 + 1, Anim.argb(Ui.INK, 0.8f * a));
			}
			Ui.round(g, mx2 - 5, my2 - 5, mx2 + 5, my2 + 5, 5, Anim.argb(Ui.INK, a));
		}
		g.fill(x1, y2 - 10, x2, y2, Anim.argb(Ui.GLASS, a));
		Ui.text(g, TimeChanger.clock(t) + (TimeChanger.active() ? "  (yours)" : "  (off)"), x1 + 6, y1 + 6, Anim.argb(Ui.INK, a));
		String sky = night ? Modules.setting("sky.night").label() : Modules.setting("sky.day").label();
		Ui.small(g, (night ? "Night sky: " : "Day sky: ") + sky, x1 + 6, y1 + 18, Anim.argb(Ui.INK, a));
	}
}
