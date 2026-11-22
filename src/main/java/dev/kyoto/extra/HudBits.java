package dev.kyoto.extra;

import dev.kyoto.feature.Privacy;
import dev.kyoto.hud.HudLayout;
import dev.kyoto.module.Modules;
import dev.kyoto.music.Lyrics;
import dev.kyoto.music.Music;
import dev.kyoto.util.Anim;
import dev.kyoto.util.Compat;
import dev.kyoto.util.Ui;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.Random;

/** Small HUD pieces: music visualizer, karaoke bar, portal linker, durability, slime chunk tag. All movable in Edit HUD. */
public final class HudBits {
	private HudBits() {}

	static {
		HudLayout.NAMES.put("visualizer", "Music Visualizer");
		HudLayout.NAMES.put("karaoke", "Karaoke");
		HudLayout.NAMES.put("portal", "Portal Linker");
		HudLayout.NAMES.put("durability", "Durability");
	}

	public static void init() {
		// loads the static block above so the pieces show in the HUD editor list
	}

	public static void render(GuiGraphicsExtractor g, Minecraft mc, int w, int h, float a) {
		if (Modules.on("music_visualizer") && Music.active()) {
			HudLayout.begin(g, "visualizer", w / 2f - 60, 30);
			visualizer(g, a);
			HudLayout.end(g, 120, 22);
		}
		if (Modules.on("karaoke") && Music.active() && !Lyrics.lines().isEmpty()) {
			HudLayout.begin(g, "karaoke", w / 2f - 160, h - 110);
			karaoke(g, a);
			HudLayout.end(g, 320, 40);
		}
		if (Modules.on("portal_linker") && !Privacy.hideCoords() && !Modules.on("coords_streamer")) {
			HudLayout.begin(g, "portal", 4, h / 2f + 20);
			portal(g, mc, a);
			HudLayout.end(g, 150, 26);
		}
		if (Modules.on("durability_hud")) {
			HudLayout.begin(g, "durability", w / 2f + 96, h - 44);
			durability(g, mc, a);
			HudLayout.end(g, 70, 40);
		}
	}

	/* ---------------- music visualizer ---------------- */
	// Spotify doesn't share the actual audio, so these bars are made to move with the beat feel of the song, not measured.

	private static final float[] BARS = new float[24];
	private static final Random RNG = new Random();
	private static long beatAt = 0;

	private static void visualizer(GuiGraphicsExtractor g, float a) {
		long now = System.currentTimeMillis();
		boolean playing = Music.playing;
		if (now - beatAt > 430 + RNG.nextInt(90)) beatAt = now;
		float beat = 1f - Math.min(1f, (now - beatAt) / 380f);
		int acc = Ui.accent();
		Ui.hud(g, 0, 0, 120, 22, 6, a);
		for (int i = 0; i < BARS.length; i++) {
			float centre = 1f - Math.abs(i - BARS.length / 2f) / (BARS.length / 2f);
			float target = playing ? (0.15f + 0.55f * beat * (0.4f + 0.6f * centre) + 0.35f * RNG.nextFloat() * (float) (0.5 + 0.5 * Math.sin(now / 160.0 + i))) : 0.08f;
			BARS[i] += (target - BARS[i]) * 0.35f;
			int bh = Math.max(1, Math.round(BARS[i] * 16));
			int x = 5 + i * 46 / 10;
			g.fill(x, 19 - bh, x + 3, 19, Anim.argb(Ui.mix(acc, Ui.INK, i / (float) BARS.length * 0.5f), (0.55f + 0.45f * BARS[i]) * a));
		}
	}

	/* ---------------- karaoke ---------------- */

	private static void karaoke(GuiGraphicsExtractor g, float a) {
		List<Lyrics.Line> l = Lyrics.lines();
		long pos = Music.lyricPositionMs();
		int i = Lyrics.indexAt(pos);
		Ui.hud(g, 0, 0, 320, 40, 8, a);
		int acc = Ui.accent();
		if (i < 0) {
			String t = "get ready...";
			Ui.text(g, t, 160 - Ui.width(t) / 2, 8, Anim.argb(Ui.MUTED, a));
		} else {
			Lyrics.Line line = l.get(i);
			// current line: words fill in as they're sung
			int total = Ui.width(line.text());
			float scale = total > 300 ? 300f / total : 1f;
			g.pose().pushMatrix();
			g.pose().translate(160 - total * scale / 2f, 7);
			g.pose().scale(scale, scale);
			int x = 0;
			for (int k = 0; k < line.words().length; k++) {
				String wd = line.words()[k] + " ";
				float p = line.wordProgress(k, pos);
				int ww = Ui.width(wd);
				Ui.textShadow(g, wd, x, 0, Anim.argb(Ui.INK, 0.55f * a));
				if (p > 0) {
					int cut = Math.round(ww * p);
					g.enableScissor(x, -3, x + cut, 12);
					Ui.textShadow(g, wd, x, 0, Anim.argb(acc, a));
					g.disableScissor();
				}
				x += ww;
			}
			g.pose().popMatrix();
		}
		if (i + 1 < l.size()) {
			String n = Ui.ellipsize(l.get(i + 1).text(), 300);
			Ui.small(g, n, 160 - Ui.widthSmall(n) / 2, 25, Anim.argb(Ui.MUTED, a));
		}
	}

	/* ---------------- portal linker ---------------- */

	private static void portal(GuiGraphicsExtractor g, Minecraft mc, float a) {
		String dim = Compat.dimensionId();
		boolean nether = dim.contains("nether"), over = dim.contains("overworld");
		if (!nether && !over) return;
		int x = mc.player.getBlockX(), y = mc.player.getBlockY(), z = mc.player.getBlockZ();
		String label = nether ? "Overworld portal" : "Nether portal";
		int tx = nether ? x * 8 : Math.floorDiv(x, 8), tz = nether ? z * 8 : Math.floorDiv(z, 8);
		Ui.hud(g, 0, 0, 150, 26, 6, a);
		Ui.iconTex(g, "swap", 5, 5, 8, Anim.argb(nether ? 0x7FD0FF : 0xC77BFF, a));
		Ui.small(g, label + " goes at", 17, 4, Anim.argb(Ui.MUTED, a));
		Ui.text(g, tx + "  " + (nether ? y : Math.max(5, Math.min(120, y))) + "  " + tz, 17, 13, Anim.argb(Ui.TEXT, a));
	}

	/* ---------------- durability ---------------- */

	private static void durability(GuiGraphicsExtractor g, Minecraft mc, float a) {
		int y = 0;
		for (EquipmentSlot s : new EquipmentSlot[]{EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND}) {
			ItemStack st = mc.player.getItemBySlot(s);
			if (st.isEmpty() || !st.isDamageableItem()) continue;
			int max = st.getMaxDamage(), left = max - st.getDamageValue();
			float f = left / (float) Math.max(1, max);
			int col = f > 0.5f ? 0x6EE7A0 : f > 0.2f ? 0xFFC93D : 0xFF5A6A;
			Ui.hud(g, 0, y, 70, y + 18, 5, a);
			String pct = Math.round(f * 100) + "%";
			Ui.small(g, Ui.ellipsize(st.getHoverName().getString(), Math.round((66 - Ui.width(pct)) / 0.75f)), 4, y + 3, Anim.argb(Ui.MUTED, a));
			Ui.text(g, pct, 66 - Ui.width(pct), y + 2, Anim.argb(col, a));
			g.fill(4, y + 13, 66, y + 15, Anim.argb(Ui.INK, 0.12f * a));
			g.fill(4, y + 13, 4 + Math.round(62 * f), y + 15, Anim.argb(col, a));
			y += 20;
		}
	}
}
