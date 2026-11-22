package dev.kyoto.music;

import dev.kyoto.config.KyotoConfig;
import dev.kyoto.module.Modules;
import dev.kyoto.util.Anim;
import dev.kyoto.util.Compat;
import dev.kyoto.util.Proj;
import dev.kyoto.util.Ui;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;

/**
 * Synced lyrics, placed like a real object ("sprite") in the world:
 *  - Floating: each new line appears a good way ahead of you at eye level and STAYS there.
 *              Walk up to it, walk past it, look back at it.
 *  - Sky:      huge lines written across the sky in front of you.
 *  - Screen:   subtitles. Lyrics fade out while paused and hide behind walls.
 * Words light up exactly when they're sung (karaoke), with glow + particle effects.
 */
public final class LyricsHud {
	private LyricsHud() {}

	/** {dim colour, lit colour, glow colour} */
	private static final int[][] STYLES = {
			{Ui.MUTED, Ui.INK, Ui.INK}, // Silver
			{0xCFF7E6, Ui.INK, 0x3DDC97}, // Mint
			{0xE6D6FF, Ui.MUTED, 0x9B72FF}, // Lavender
			{0xFFD6EE, 0x9CCBFF, Ui.MUTED}, // Dreamy
			{Ui.INK, Ui.MUTED, 0x52C8FF}, // Ice
			{0xFFE9B0, 0xC79BFF, Ui.INK}, // Royal
			{0xFFE0B0, Ui.MUTED, 0xFF9A5C}, // Sunset
			{Ui.INK, Ui.INK, Ui.INK}, // Clean
	};

	/** Added after "Accent" (index 8) so old saved choices keep working. */
	private static final int[][] STYLES2 = {
			{0xFFB3E6, 0xFF4FD8, 0x37E7FF}, // Neon
			{Ui.INK, Ui.INK, Ui.INK}, // Rainbow (hue per word, see colourFor)
			{Ui.INK, 0xFFE14D, 0xFFB800}, // Karaoke
			{0xB8FFF4, 0x2EF2D0, 0x00B3A1}, // Retro
			{0xF5ECD9, 0xE8C77A, 0xC9A24C}, // Elegant
			{0x9AA8D8, 0xD9C8FF, 0x6A7BFF}, // Midnight
			{0xFFD08A, 0xFF5A36, 0xFF8A00}, // Fire
			{0xC8F4FF, 0x3AA8FF, 0x1E6BFF}, // Ocean
			{Ui.INK, Ui.MUTED, 0xFFC2D9}, // Sakura
	};

	public static final String[] STYLE_NAMES = {"Silver", "Mint", "Lavender", "Dreamy", "Ice", "Royal", "Sunset", "Clean", "Accent",
			"Neon", "Rainbow", "Karaoke", "Retro", "Elegant", "Midnight", "Fire", "Ocean", "Sakura"};
	public static final String[] ANIM_NAMES = {"Karaoke", "Word pop", "Fade", "Wave", "Bounce", "Typewriter", "Slide up"};
	public static final String[] EFFECT_NAMES = {"Glow + particles", "Glow", "Particles", "None", "Outline", "Neon glow", "Shadow"};

	/** Everything that decides how a lyric looks. Presets and the menu preview use their own; the HUD uses the saved one. */
	public record Look(int style, int font, int anim, int effect, float size) {}

	/** Named looks shown in the Presets tab. */
	public record Preset(String name, Look look) {}

	public static final List<Preset> PRESETS = List.of(
			new Preset("Clean", new Look(7, 2, 2, 3, 1f)),
			new Preset("Neon", new Look(9, 0, 0, 5, 1f)),
			new Preset("Music Video", new Look(7, 15, 1, 4, 1.35f)),
			new Preset("Rainbow Wave", new Look(10, 0, 3, 1, 1f)),
			new Preset("Karaoke", new Look(11, 0, 0, 4, 1f)),
			new Preset("Sunset", new Look(6, 0, 0, 0, 1f)),
			new Preset("Retro", new Look(12, 7, 5, 6, 1f)),
			new Preset("Elegant", new Look(13, 4, 2, 1, 1f)),
			new Preset("Dreamy", new Look(3, 4, 6, 0, 1f)),
			new Preset("Midnight", new Look(14, 3, 6, 1, 1f)),
			new Preset("Fire", new Look(15, 0, 4, 0, 1.35f)),
			new Preset("Ocean", new Look(16, 2, 3, 1, 1f)),
			new Preset("Sakura", new Look(17, 1, 1, 0, 1f)),
			new Preset("Bold Pop", new Look(0, 15, 4, 4, 1.35f)),
			new Preset("Kyoto", new Look(8, 0, 0, 0, 1f)));

	public static Look current() {
		if (!Modules.on("lyrics_look")) return new Look(0, 0, 0, 1, 1f);
		return new Look(Modules.opt("lyrics.style"), Modules.opt("lyrics.font"), Modules.opt("lyrics.anim"), Modules.opt("lyrics.effect"),
				new float[]{0.75f, 1f, 1.35f, 1.8f}[Modules.opt("lyrics.size")]);
	}

	public static void apply(Look l) {
		KyotoConfig.get().choices.put("lyrics.style", l.style());
		KyotoConfig.get().choices.put("lyrics.font", l.font());
		KyotoConfig.get().choices.put("lyrics.anim", l.anim());
		KyotoConfig.get().choices.put("lyrics.effect", l.effect());
		int sz = l.size() >= 1.79f ? 3 : l.size() >= 1.34f ? 2 : l.size() >= 0.99f ? 1 : 0;
		KyotoConfig.get().choices.put("lyrics.size", sz);
		if (!Modules.on("lyrics_look")) Modules.get("lyrics_look").toggle();
		KyotoConfig.save();
	}

	/** Look used while drawing (lets the menu draw previews in other styles). */
	private static Look drawing = null;

	private static Look L() {
		return drawing != null ? drawing : current();
	}

	public static int[] palette(int s) {
		if (s < STYLES.length) return STYLES[s];
		if (s == STYLES.length) {
			int a = Ui.accent();
			return new int[]{Ui.mix(a, Ui.INK, 0.6f), a, a};
		}
		int i = s - STYLES.length - 1;
		return i >= 0 && i < STYLES2.length ? STYLES2[i] : STYLES[0];
	}

	private static boolean rainbow() {
		return L().style() == 10;
	}

	private static final class Shown {
		final Lyrics.Line line;
		final Vec3 anchor; // null = screen mode
		final Vec3 right, up;
		final Vec3 eyeAtSpawn;
		final double textHeight; // blocks per 9px line
		final long born = System.currentTimeMillis();
		long fadeStart = -1;
		final boolean[] burst;
		final float[] seen; // per-word visibility (walls fade words out smoothly)

		Shown(Lyrics.Line line, Vec3 anchor, Vec3 right, Vec3 eye, double textHeight) {
			this.line = line;
			this.anchor = anchor;
			this.right = right;
			this.up = new Vec3(0, 1, 0);
			this.eyeAtSpawn = eye;
			this.textHeight = textHeight;
			this.burst = new boolean[line.words().length];
			this.seen = new float[line.words().length];
			java.util.Arrays.fill(seen, 1f);
		}
	}

	private static final List<Shown> SHOWN = new ArrayList<>();
	private static final Random RAND = new Random();
	private static int lastIndex = -2;
	private static String lastTrack = null;
	private static int lastMode = -1;
	private static float pillShow = 0;
	private static float pauseFade = 1f;

	private static int mode() {
		return Modules.opt("lyrics.mode"); // 0 sky, 1 floating, 2 screen
	}

	private static boolean look() {
		return Modules.on("lyrics_look");
	}

	private static int[] colours() {
		return palette(L().style());
	}

	private static int fontIndex() {
		return L().font();
	}

	private static float sizeMul() {
		return L().size();
	}

	private static int effect() {
		return L().effect(); // 0 glow+particles, 1 glow, 2 particles, 3 none, 4 outline, 5 neon glow, 6 shadow
	}

	private static int anim() {
		return L().anim();
	}

	public static void render(GuiGraphicsExtractor g, Minecraft mc, int w, int h, float partial) {
		if (!Modules.on("lyrics") || mc.player == null || !Music.active()) {
			SHOWN.clear();
			lastIndex = -2;
			return;
		}
		List<Lyrics.Line> lines = Lyrics.lines();
		if (!Music.trackId.equals(lastTrack) || mode() != lastMode) {
			lastTrack = Music.trackId;
			lastMode = mode();
			for (Shown s : SHOWN) if (s.fadeStart < 0) s.fadeStart = System.currentTimeMillis();
			lastIndex = -2;
		}

		long pos = Music.lyricPositionMs();
		int idx = Lyrics.indexAt(pos);
		if (idx != lastIndex) {
			long now = System.currentTimeMillis();
			for (Shown s : SHOWN) if (s.fadeStart < 0) s.fadeStart = now;
			if (idx >= 0 && idx < lines.size() && lines.get(idx).words().length > 0) SHOWN.add(spawn(mc.player, lines.get(idx), partial));
			lastIndex = idx;
		}

		// fade everything out while the song is paused, back in when it plays
		boolean show = Music.playing || Modules.opt("lyrics.pause") != 0;
		pauseFade += ((show ? 1f : 0f) - pauseFade) * (show ? 0.12f : 0.06f);
		if (pauseFade < 0.02f) return;
		Iterator<Shown> it = SHOWN.iterator();
		while (it.hasNext()) {
			Shown s = it.next();
			float fade = Anim.easeOutCubic(Anim.clamp01((System.currentTimeMillis() - s.born) / 350f));
			float rise = 0f;
			if (s.fadeStart > 0) {
				float p = (System.currentTimeMillis() - s.fadeStart) / 700f;
				if (p >= 1) {
					it.remove();
					continue;
				}
				fade *= 1 - Anim.easeInCubic(p);
				rise = Anim.easeOutCubic(p);
			}
			fade *= pauseFade;
			if (s.anchor == null) drawScreen(g, s, w, h, fade, rise, pos, s.fadeStart < 0 && s == SHOWN.get(SHOWN.size() - 1) ? nextLine(lines) : null);
			else drawWorld(g, mc, s, w, h, partial, fade, rise, pos);
		}
	}

	private static Lyrics.Line nextLine(List<Lyrics.Line> lines) {
		int n = lastIndex + 1;
		while (n >= 0 && n < lines.size() && lines.get(n).words().length == 0) n++;
		return n >= 0 && n < lines.size() ? lines.get(n) : null;
	}

	private static boolean canSeeSky(Minecraft mc) {
		try {
			return mc.level.canSeeSky(BlockPos.containing(mc.player.getEyePosition()));
		} catch (Throwable t) {
			return true;
		}
	}

	/* ------------------------------------------------ placement ------------------------------------------------ */

	private static Shown spawn(LocalPlayer p, Lyrics.Line line, float partial) {
		int mode = mode();
		Vec3 eye = p.getEyePosition(partial);
		if (mode == 2) return new Shown(line, null, null, eye, 0);
		boolean followView = Modules.opt("lyrics.follow") == 0;
		int dist = Modules.opt("lyrics.distance");

		// which way to place it: where you look, or where you're walking
		float yaw = p.getYRot();
		if (!followView) {
			Vec3 mv = p.getDeltaMovement();
			if (mv.x * mv.x + mv.z * mv.z > 0.003) yaw = (float) Math.toDegrees(Math.atan2(-mv.x, mv.z));
			else yaw = p.yBodyRot;
		}
		double d;
		float pitch;
		double height;
		if (mode == 0) { // sky
			d = new double[]{38, 55, 80}[dist];
			pitch = followView ? Math.min(-12f, p.getXRot() - 18f) : -22f;
			pitch = Math.max(-55f, pitch) - RAND.nextFloat() * 6f;
			yaw += (RAND.nextFloat() - 0.5f) * 30f;
			height = d * 0.055 * sizeMul();
		} else { // floating - far-ish ahead, around eye level
			d = new double[]{9, 14, 22}[dist];
			pitch = -4f - RAND.nextFloat() * 4f;
			yaw += (RAND.nextFloat() - 0.5f) * 22f;
			height = d * 0.06 * sizeMul();
		}
		double yr = Math.toRadians(yaw), pr = Math.toRadians(pitch);
		Vec3 dir = new Vec3(-Math.sin(yr) * Math.cos(pr), -Math.sin(pr), Math.cos(yr) * Math.cos(pr));
		Vec3 anchor = eye.add(dir.scale(d));
		// the text faces the spot you were standing when it appeared
		Vec3 toEye = new Vec3(eye.x - anchor.x, 0, eye.z - anchor.z).normalize();
		Vec3 right = new Vec3(toEye.z, 0, -toEye.x);
		return new Shown(line, anchor, right, eye, height);
	}

	/* ------------------------------------------------ world drawing ------------------------------------------------ */

	private static void drawWorld(GuiGraphicsExtractor g, Minecraft mc, Shown s, int w, int h, float partial, float fade, float rise, long pos) {
		Vec3 eye = mc.player.getEyePosition(partial);
		Vec3 anchor = s.anchor;
		if (Modules.opt("lyrics.lock") != 0) anchor = anchor.add(eye.subtract(s.eyeAtSpawn)); // follows you around
		anchor = anchor.add(0, rise * s.textHeight * 1.2, 0);

		Vec3 right = s.right;
		// seen from behind? flip so it still reads left-to-right
		Vec3 toCam = eye.subtract(anchor);
		Vec3 normal = new Vec3(-right.z, 0, right.x);
		if (normal.dot(toCam) < 0) right = right.scale(-1);

		double bpp = s.textHeight / 9.0; // blocks per pixel of text
		String[] words = s.line.words();
		int[] widths = new int[words.length];
		int space = Ui.font().width(Ui.inFont(" ", fontIndex(), true));
		int total = 0;
		for (int i = 0; i < words.length; i++) {
			widths[i] = Ui.font().width(Ui.inFont(words[i], fontIndex(), true));
			total += widths[i] + (i > 0 ? space : 0);
		}
		// screen rotation of the whole line (from its two ends)
		float[] a = Proj.project(anchor.add(right.scale(-total / 2.0 * bpp)), partial, w, h);
		float[] b = Proj.project(anchor.add(right.scale(total / 2.0 * bpp)), partial, w, h);
		float angle = 0;
		if (a != null && b != null) angle = (float) Math.atan2(b[1] - a[1], b[0] - a[0]);
		if (Math.abs(angle) > 1.2f) angle = 0;
		double focal = Proj.focal(h);
		int[] cols = colours();

		int x = -total / 2;
		for (int i = 0; i < words.length; i++) {
			double cx = x + widths[i] / 2.0;
			Vec3 wp = anchor.add(right.scale(cx * bpp));
			x += widths[i] + space;
			float[] sp = Proj.project(wp, partial, w, h);
			if (sp == null || sp[2] < 0.6f) continue; // you walked past this word
			// hide behind walls: a word fades out while a block is between you and it
			if (Modules.opt("lyrics.walls") == 0) {
				boolean blocked = blocked(mc, eye, wp);
				s.seen[i] += ((blocked ? 0f : 1f) - s.seen[i]) * 0.25f;
			} else s.seen[i] = 1f;
			if (s.seen[i] < 0.03f) continue;
			float scale = (float) (bpp * focal / sp[2]);
			if (scale < 0.12f || scale > 30f) continue;
			if (sp[0] < -200 || sp[0] > w + 200 || sp[1] < -100 || sp[1] > h + 100) continue;
			float prog = s.line.wordProgress(i, pos);
			if (prog > 0 && !s.burst[i]) {
				s.burst[i] = true;
				if (s.fadeStart < 0 && (effect() == 0 || effect() == 2)) burst(wp, s.textHeight, cols[2]);
			}
			// close-up fade so it doesn't fill your whole screen as you walk through it
			float near = (float) Math.min(1, (sp[2] - 0.6) / 2.5);
			drawWord(g, words[i], i, sp[0], sp[1], scale, angle, fade * near * s.seen[i], prog, cols);
		}
	}

	private static boolean blocked(Minecraft mc, Vec3 eye, Vec3 target) {
		try {
			var hit = mc.level.clip(new net.minecraft.world.level.ClipContext(eye, target,
					net.minecraft.world.level.ClipContext.Block.VISUAL, net.minecraft.world.level.ClipContext.Fluid.NONE, mc.player));
			return hit.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK
					&& hit.getLocation().distanceToSqr(eye) < target.distanceToSqr(eye) - 0.25;
		} catch (Throwable t) {
			return false;
		}
	}

	private static void burst(Vec3 at, double size, int colour) {
		int n = Modules.vfx() <= 1 ? 2 : 5;
		for (int k = 0; k < n; k++) {
			double ox = (RAND.nextDouble() - 0.5) * size, oy = (RAND.nextDouble() - 0.3) * size, oz = (RAND.nextDouble() - 0.5) * size;
			Compat.farParticle(ParticleTypes.END_ROD, at.x + ox, at.y + oy, at.z + oz, ox * 0.02, 0.02 + RAND.nextDouble() * 0.03, oz * 0.02);
		}
	}

	/* ------------------------------------------------ screen drawing ------------------------------------------------ */

	private static void drawScreen(GuiGraphicsExtractor g, Shown s, int w, int h, float fade, float rise, long pos, Lyrics.Line next) {
		float scale = 1.9f * sizeMul();
		String[] words = s.line.words();
		int space = Ui.font().width(Ui.inFont(" ", fontIndex(), true));
		int total = 0;
		int[] widths = new int[words.length];
		for (int i = 0; i < words.length; i++) {
			widths[i] = Ui.font().width(Ui.inFont(words[i], fontIndex(), true));
			total += widths[i] + (i > 0 ? space : 0);
		}
		// shrink long lines to fit
		scale = Math.min(scale, (w - 40) / (float) Math.max(1, total));
		float cy = h * 0.22f - rise * 16;
		float x = w / 2f - total * scale / 2f;
		int[] cols = colours();
		for (int i = 0; i < words.length; i++) {
			float cx = x + (widths[i] / 2f) * scale;
			drawWord(g, words[i], i, cx, cy, scale, 0, fade, s.line.wordProgress(i, pos), cols);
			x += (widths[i] + space) * scale;
		}
		if (next != null && fade > 0.05f) {
			String nt = next.text();
			float ns = Math.max(0.8f, scale * 0.5f);
			int nw = Ui.font().width(Ui.inFont(nt, fontIndex(), false));
			g.pose().pushMatrix();
			g.pose().translate(w / 2f - nw * ns / 2f, cy + 9 * scale * 0.7f);
			g.pose().scale(ns, ns);
			g.text(Ui.font(), Ui.inFont(nt, fontIndex(), false), 0, 0, Anim.argb(Ui.INK, 0.45f * fade), true);
			g.pose().popMatrix();
		}
	}

	/* ------------------------------------------------ one word ------------------------------------------------ */

	private static void drawWord(GuiGraphicsExtractor g, String word, int idx, float cx, float cy, float scale, float angle, float fade, float prog, int[] cols) {
		int an = anim();
		float a = fade;
		float pop = 0;
		float dy = 0;
		long now = System.currentTimeMillis();
		int lit = cols[1], dim = cols[0], glowC = cols[2];
		if (rainbow()) {
			float hue = ((now / 2600f) + idx * 0.11f) % 1f;
			lit = Ui.hsv(hue, 0.65f, 1f);
			dim = Ui.mix(lit, Ui.INK, 0.55f);
			glowC = lit;
		}
		int col;
		switch (an) {
			case 1 -> { // word pop: appear when sung
				if (prog <= 0) return;
				float e = Anim.clamp01(prog * 3f);
				a *= Anim.easeOutCubic(e);
				pop = (1 - Anim.easeOutBack(e)) * -0.4f;
				col = lit;
			}
			case 2 -> col = Ui.mix(dim, lit, 0.5f); // fade: whole line at once
			case 3 -> { // wave: the line ripples, sung words light up
				dy = (float) Math.sin(now / 260.0 - idx * 0.7) * 2.2f;
				col = Ui.mix(dim, lit, Anim.easeOutCubic(prog));
			}
			case 4 -> { // bounce: each word jumps when it's sung
				float b = prog > 0 && prog < 1 ? (float) Math.sin(Math.min(1, prog * 2.5) * Math.PI) : 0;
				dy = -b * 5f;
				pop = b * 0.15f;
				col = Ui.mix(dim, lit, Anim.easeOutCubic(prog));
				a *= 0.55f + 0.45f * Anim.clamp01(prog * 4);
			}
			case 5 -> { // typewriter: letters appear as they're sung
				if (prog <= 0) return;
				int n = Math.max(1, Math.round(word.length() * Anim.clamp01(prog * 1.6f)));
				word = word.substring(0, Math.min(word.length(), n));
				col = lit;
			}
			case 6 -> { // slide up: words rise into place
				if (prog <= 0) return;
				float e = Anim.easeOutCubic(Anim.clamp01(prog * 3f));
				dy = (1 - e) * 8f;
				a *= e;
				col = lit;
			}
			default -> { // karaoke: words light up as they're sung
				float e = Anim.easeOutCubic(prog);
				col = Ui.mix(dim, lit, e);
				a *= 0.5f + 0.5f * e;
				pop = (float) Math.sin(Math.min(1, prog * 2.2) * Math.PI) * 0.12f;
			}
		}
		if (a < 0.02f || word.isEmpty()) return;
		Component c = Ui.inFont(word, fontIndex(), true);
		int tw = an == 5 ? Ui.font().width(c) : Ui.font().width(c);
		float sc = scale * (1 + pop);
		g.pose().pushMatrix();
		g.pose().translate(cx, cy + dy * scale);
		g.pose().rotate(angle);
		g.pose().scale(sc, sc);
		int fx = -tw / 2, fy = -4;
		int fx2 = fx;
		int ef = effect();
		boolean litNow = an != 0 || prog > 0;
		if ((ef == 0 || ef == 1) && litNow) {
			int gc = Anim.argb(glowC, 0.22f * a * (an == 0 ? Math.max(0.3f, prog) : 1f));
			g.text(Ui.font(), c, fx2 - 1, fy, gc, false);
			g.text(Ui.font(), c, fx2 + 1, fy, gc, false);
			g.text(Ui.font(), c, fx2, fy - 1, gc, false);
			g.text(Ui.font(), c, fx2, fy + 1, gc, false);
		} else if (ef == 5 && litNow) { // neon: wide soft glow
			float k = 0.16f * a * (an == 0 ? Math.max(0.35f, prog) : 1f);
			for (int ox = -2; ox <= 2; ox++)
				for (int oy = -2; oy <= 2; oy++) {
					if (ox == 0 && oy == 0 || Math.abs(ox) + Math.abs(oy) > 3) continue;
					g.text(Ui.font(), c, fx2 + ox, fy + oy, Anim.argb(glowC, k / (Math.abs(ox) + Math.abs(oy))), false);
				}
		} else if (ef == 4) { // outline
			int oc = Anim.argb(0x000000, 0.85f * a);
			for (int ox = -1; ox <= 1; ox++)
				for (int oy = -1; oy <= 1; oy++) if (ox != 0 || oy != 0) g.text(Ui.font(), c, fx2 + ox, fy + oy, oc, false);
		} else if (ef == 6) { // long shadow
			g.text(Ui.font(), c, fx2 + 2, fy + 2, Anim.argb(Ui.mix(glowC, 0x000000, 0.6f), 0.8f * a), false);
		}
		g.text(Ui.font(), c, fx, fy, Anim.argb(col, a), ef != 4 && ef != 6);
		g.pose().popMatrix();
	}

	/**
	 * Draws a line of text in a given look, centred at (cx, cy). Used by the Lyrics menu for preset names and the preview.
	 * {@code progress} 0..1 is how far through the line we are (drives karaoke / pop / typewriter).
	 */
	public static void drawSample(GuiGraphicsExtractor g, Look look, String text, float cx, float cy, float scale, float alpha, float progress) {
		Look old = drawing;
		drawing = look;
		try {
			String[] words = text.split(" ");
			int space = Ui.font().width(Ui.inFont(" ", look.font(), true));
			int[] widths = new int[words.length];
			int total = 0;
			for (int i = 0; i < words.length; i++) {
				widths[i] = Ui.font().width(Ui.inFont(words[i], look.font(), true));
				total += widths[i] + (i > 0 ? space : 0);
			}
			float x = cx - total * scale / 2f;
			int[] cols = colours();
			for (int i = 0; i < words.length; i++) {
				float wp = Anim.clamp01(progress * words.length - i);
				drawWord(g, words[i], i, x + widths[i] * scale / 2f, cy, scale, 0, alpha, wp, cols);
				x += (widths[i] + space) * scale;
			}
		} finally {
			drawing = old;
		}
	}

	public static int sampleWidth(Look look, String text) {
		return Ui.font().width(Ui.inFont(text, look.font(), true));
	}

	/* ------------------------------------------------ now playing pill ------------------------------------------------ */

	/** Song pill at the top-center. Returns the y below it (so the compass can sit underneath). */
	public static int renderNowPlaying(GuiGraphicsExtractor g, Minecraft mc, int w, float hudAlpha) {
		boolean show = Modules.on("now_playing") && Music.active();
		pillShow += ((show ? 1f : 0f) - pillShow) * 0.15f;
		if (pillShow < 0.03f) return 4;
		float a = pillShow * hudAlpha;
		int pw = 250, ph = 22;
		int x = w / 2 - pw / 2;
		int y = Math.round(4 - (1 - pillShow) * 12);
		int accent = Ui.accent();

		Ui.round(g, x, y, x + pw, y + ph, 11, Anim.argb(Ui.GLASS, 0.75f * a));
		Compat.blitTint(g, AlbumArt.texture(), x + 3, y + 3, 16, 16, Anim.argb(0xFFFFFF, a));

		String text;
		List<Lyrics.Line> lines = Lyrics.lines();
		int idx = Lyrics.indexAt(Music.lyricPositionMs());
		if (Modules.on("lyrics") && mode() != 2 && idx >= 0 && idx < lines.size() && !lines.get(idx).text().isBlank()) {
			text = lines.get(idx).text();
		} else {
			text = Music.title + "  ·  " + Music.artist;
		}
		Ui.text(g, Ui.ellipsize(text, pw - 64), x + 24, y + 7, Anim.argb(Ui.TEXT, a));

		long pos = Music.positionMs(), dur = Math.max(1, Music.durationMs);
		String time = String.format("%d:%02d", pos / 60000, (pos / 1000) % 60);
		Ui.text(g, time, x + pw - 9 - Ui.width(time), y + 7, Anim.argb(Ui.MUTED, a));
		int barW = pw - 30;
		g.fill(x + 22, y + ph - 3, x + 22 + barW, y + ph - 2, Anim.argb(Ui.INK, 0.12f * a));
		g.fill(x + 22, y + ph - 3, x + 22 + Math.round(barW * (pos / (float) dur)), y + ph - 2, Anim.argb(accent, a));
		return y + ph + 4;
	}
}
