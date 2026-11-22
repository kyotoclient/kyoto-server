package dev.kyoto.ui;

import dev.kyoto.module.Modules;
import dev.kyoto.util.Anim;
import dev.kyoto.util.Compat;
import dev.kyoto.util.Ui;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.Month;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Kyoto's title screen: a soft sakura sky, swaying branches and drifting petals, the blossom and the wordmark.
 * No seasons. On holidays only the blossom changes colour.
 */
public final class TitleTheme {
	private TitleTheme() {}

	/** Order matters (SkyTint indexes by ordinal). The seasonal entries are kept only so old configs still load. */
	public enum Theme {
		STARRY("starry", "Kyoto"), COZY("starry", "Kyoto"), SPRING("starry", "Kyoto"), SUMMER("starry", "Kyoto"),
		FALL("starry", "Kyoto"), WINTER("starry", "Kyoto"), EASTER("easter", "Easter"),
		THANKSGIVING("thanksgiving", "Thanksgiving"), HALLOWEEN("halloween", "Halloween"),
		CHRISTMAS("christmas", "Christmas"), NEWYEAR("newyear", "New Year"), VALENTINES("valentines", "Valentine's");

		public final String file, title;
		public final int color = 0xFFFFFF;

		Theme(String file, String title) {
			this.file = file;
			this.title = title;
		}

		/** The blossom for this holiday. */
		public Identifier logo() {
			return Identifier.fromNamespaceAndPath("kyoto", "textures/gui/logo/logo_k_" + file + ".png");
		}

		public boolean holiday() {
			return this != STARRY && !file.equals("starry");
		}
	}

	private static final Theme[] PICK = {null, Theme.STARRY, Theme.NEWYEAR, Theme.VALENTINES, Theme.EASTER, Theme.HALLOWEEN, Theme.THANKSGIVING, Theme.CHRISTMAS};

	private static final String[] TAGLINES = {"Quiet. Fast. Yours.", "Soft as a petal, fast as the wind.", "Welcome back, %s.", "The client that's just a mod.",
			"Press Right Shift.", "Sakura season, all year.", "Good to see you, %s."};
	private static final String[] HOLIDAY_LINES = {"", "", "", "", "", "", "Happy Easter, %s.", "Thankful for you, %s.", "Happy Halloween, %s.",
			"Merry Christmas, %s.", "Happy New Year, %s.", "Happy Valentine's, %s."};

	/* ---------------------------------- picking the logo ---------------------------------- */

	public static Theme current() {
		int pick = Modules.opt("title.theme");
		if (pick > 0 && pick < PICK.length) return PICK[pick];
		return auto(LocalDate.now());
	}

	public static Theme auto(LocalDate d) {
		int y = d.getYear();
		if (on("title_newyear") && ((d.getMonth() == Month.DECEMBER && d.getDayOfMonth() >= 27) || (d.getMonth() == Month.JANUARY && d.getDayOfMonth() <= 2))) return Theme.NEWYEAR;
		if (on("title_christmas") && d.getMonth() == Month.DECEMBER && d.getDayOfMonth() <= 26) return Theme.CHRISTMAS;
		if (on("title_valentines") && d.getMonth() == Month.FEBRUARY && d.getDayOfMonth() >= 7 && d.getDayOfMonth() <= 14) return Theme.VALENTINES;
		if (on("title_easter")) {
			LocalDate e = easter(y);
			if (!d.isBefore(e.minusDays(6)) && !d.isAfter(e.plusDays(1))) return Theme.EASTER;
		}
		if (on("title_halloween") && d.getMonth() == Month.OCTOBER && d.getDayOfMonth() >= 15) return Theme.HALLOWEEN;
		if (on("title_thanksgiving") && d.getMonth() == Month.NOVEMBER) {
			LocalDate tg = LocalDate.of(y, 11, 1).with(TemporalAdjusters.dayOfWeekInMonth(4, DayOfWeek.THURSDAY));
			if (!d.isBefore(tg.minusDays(4)) && !d.isAfter(tg.plusDays(3))) return Theme.THANKSGIVING;
		}
		return Theme.STARRY;
	}

	private static boolean on(String id) {
		return Modules.on(id);
	}

	/** Easter Sunday (Gregorian, "anonymous" algorithm). */
	static LocalDate easter(int y) {
		int a = y % 19, b = y / 100, c = y % 100, d = b / 4, e = b % 4, f = (b + 8) / 25, g = (b - f + 1) / 3;
		int h = (19 * a + b - d - g + 15) % 30, i = c / 4, k = c % 4, l = (32 + 2 * e + 2 * i - h - k) % 7;
		int m = (a + 11 * h + 22 * l) / 451;
		int month = (h + l - 7 * m + 114) / 31, day = ((h + l - 7 * m + 114) % 31) + 1;
		return LocalDate.of(y, month, day);
	}

	private static String splash = null;
	private static Theme splashFor = null;

	public static String splash() {
		Theme t = current();
		int mode = Modules.opt("title.splash");
		if (mode == 2) return null;
		if (splash == null || splashFor != t) {
			String s = t.holiday() && mode == 0 && !HOLIDAY_LINES[t.ordinal()].isEmpty() ? HOLIDAY_LINES[t.ordinal()] : TAGLINES[R.nextInt(TAGLINES.length)];
			splash = s.contains("%s") ? String.format(s, Compat.myName()) : s;
			splashFor = t;
		}
		return splash;
	}

	public static void reroll() {
		splash = null;
	}

	/* ---------------------------------- the sakura sky ---------------------------------- */

	private static final Random R = new Random();
	private static final Identifier BRANCH_L = Identifier.fromNamespaceAndPath("kyoto", "textures/gui/sakura/branch_left.png");
	private static final Identifier BRANCH_R = Identifier.fromNamespaceAndPath("kyoto", "textures/gui/sakura/branch_right.png");
	private static final Identifier PETAL = Identifier.fromNamespaceAndPath("kyoto", "textures/gui/sakura/petal.png");
	private static final Identifier FLOWER = Identifier.fromNamespaceAndPath("kyoto", "textures/gui/sakura/flower.png");
	private static final Identifier SOFT = Identifier.fromNamespaceAndPath("kyoto", "textures/gui/aa/soft.png");

	/** A falling petal in 0..1 screen space. */
	private static final class Petal {
		float x, y, size, spin, spinSpeed, flip, flipSpeed, sway, swaySpeed, fall, depth;
		boolean flower;
	}

	private static final List<Petal> PETALS = new ArrayList<>();
	private static long lastNanos = 0;
	private static float gust = 0, gustTimer = 4, px = 0, py = 0;

	private static Petal spawn(boolean anywhere) {
		Petal p = new Petal();
		p.depth = R.nextFloat();
		p.x = anywhere ? R.nextFloat() : -0.05f + R.nextFloat() * 0.9f;
		p.y = anywhere ? R.nextFloat() : -0.05f - R.nextFloat() * 0.2f;
		p.size = 5 + p.depth * 9 + R.nextFloat() * 3;
		p.spin = R.nextFloat() * 6.28f;
		p.spinSpeed = (R.nextFloat() - 0.5f) * 1.6f;
		p.flip = R.nextFloat() * 6.28f;
		p.flipSpeed = 1.2f + R.nextFloat() * 2.2f;
		p.sway = R.nextFloat() * 6.28f;
		p.swaySpeed = 0.6f + R.nextFloat() * 0.9f;
		p.fall = 0.035f + p.depth * 0.05f + R.nextFloat() * 0.02f;
		p.flower = R.nextFloat() < 0.06f;
		return p;
	}

	/** The whole scene: soft pink sky, glows, swaying sakura branches and drifting petals. Also used behind Kyoto screens. */
	public static void sky(GuiGraphicsExtractor g, int w, int h, float alpha) {
		Ui.refresh();
		long now = System.nanoTime();
		float dt = lastNanos == 0 ? 0.016f : Math.min(0.05f, (now - lastNanos) / 1e9f);
		lastNanos = now;
		double t = System.currentTimeMillis() / 1000.0;
		boolean dark = Ui.DARK;
		g.fill(0, 0, w, h, Anim.argb(dark ? 0x2A1F24 : 0xFFF6F9, alpha));
		g.fillGradient(0, 0, w, h, Anim.argb(dark ? 0x3A2A31 : 0xFFFAFC, alpha), Anim.argb(dark ? 0x241A1F : 0xFFE4EC, alpha));
		// slow glows
		for (int i = 0; i < 3; i++) {
			float gx = w * (0.25f + 0.5f * i / 2f) + (float) Math.sin(t * 0.07 + i * 2) * w * 0.06f;
			float gy = h * (i == 1 ? 0.25f : 0.85f) + (float) Math.cos(t * 0.05 + i) * h * 0.05f;
			int gr = Math.round(Math.max(w, h) * (0.45f + 0.1f * i));
			Compat.blitTint(g, SOFT, Math.round(gx - gr), Math.round(gy - gr), gr * 2, gr * 2, Anim.argb(dark ? 0x8C3A5A : (i == 1 ? 0xFFC2D4 : 0xFFD9E5), (dark ? 0.18f : 0.55f) * alpha));
		}
		// parallax towards the mouse
		boolean motion = Modules.opt("title.motion") == 0;
		float mx = Ui.mouseX(), my = Ui.mouseY();
		float tx = motion && mx >= 0 ? (mx / Math.max(1, w) - 0.5f) : 0, ty = motion && my >= 0 ? (my / Math.max(1, h) - 0.5f) : 0;
		px += (tx - px) * 0.05f;
		py += (ty - py) * 0.05f;
		float bAlpha = (dark ? 0.82f : 1f) * alpha;
		// the launcher's sakura tree, swaying branch by branch (moves a little with the mouse)
		g.pose().pushMatrix();
		g.pose().translate(-px * 14, -py * 8);
		SakuraTree.draw(g, w, h, bAlpha);
		g.pose().popMatrix();
		// petals
		if (Modules.opt("title.particles") == 0) {
			gustTimer -= dt;
			if (gustTimer <= 0) {
				gust = 0.25f + R.nextFloat() * 0.35f;
				gustTimer = 6 + R.nextFloat() * 9;
			}
			gust *= (float) Math.pow(0.35, dt);
			int want = Math.max(18, Math.min(70, w * h / 9000));
			if (PETALS.isEmpty()) for (int i = 0; i < want; i++) PETALS.add(spawn(true));
			while (PETALS.size() < want) PETALS.add(spawn(false));
			for (int i = PETALS.size() - 1; i >= 0; i--) {
				Petal p = PETALS.get(i);
				p.sway += p.swaySpeed * dt;
				p.spin += p.spinSpeed * dt;
				p.flip += p.flipSpeed * dt;
				p.y += p.fall * dt;
				p.x += (0.012f + (float) Math.sin(p.sway) * 0.018f + gust * (0.4f + p.depth)) * dt;
				if (p.y > 1.08f || p.x > 1.1f) {
					PETALS.set(i, spawn(false));
					continue;
				}
				float sx = p.x * w - px * 30 * p.depth, sy = p.y * h - py * 16 * p.depth;
				float sz = p.size * (p.flower ? 1.6f : 1f) * Math.max(0.8f, Math.min(1.6f, Math.min(w, h) / 360f));
				float flip = 0.35f + 0.65f * Math.abs((float) Math.cos(p.flip));
				g.pose().pushMatrix();
				g.pose().translate(sx, sy);
				g.pose().rotate(p.spin);
				g.pose().scale(1f, flip);
				int s2 = Math.round(sz);
				Compat.blitTint(g, p.flower ? FLOWER : PETAL, -s2 / 2, -s2 / 2, s2, s2, Anim.argb(0xFFFFFF, alpha * (0.45f + 0.5f * p.depth) * (dark ? 0.85f : 1f)));
				g.pose().popMatrix();
			}
		}
	}

	/** A branch texture that sways a little around its anchor corner. */
	private static void branch(GuiGraphicsExtractor g, Identifier tex, int x, int y, int w, int h, int ax, int ay, float rot, float alpha) {
		g.pose().pushMatrix();
		g.pose().translate(ax, ay);
		g.pose().rotate(rot);
		g.pose().translate(-ax, -ay);
		Compat.blitTint(g, tex, x, y, w, h, Anim.argb(0xFFFFFF, alpha));
		g.pose().popMatrix();
	}

	/* ---------------------------------- drawing ---------------------------------- */

	/** The blossom for today (changes only on holidays). */
	public static Identifier logo() {
		return current().logo();
	}

	private static final Identifier WORDMARK = Identifier.fromNamespaceAndPath("kyoto", "textures/gui/wordmark.png");
	private static long openedAt = 0;

	public static long openedAt() {
		return openedAt;
	}

	public static void opened() {
		openedAt = System.currentTimeMillis();
		reroll();
	}

	public static void background(GuiGraphicsExtractor g, int w, int h) {
		MenuMedia.musicStart();
		sky(g, w, h, 1f);
		// other themes wash the sakura sky in their own colours
		int th = Ui.theme();
		if (th != 0 && th != 8) g.fillGradient(0, 0, w, h, Anim.argb(Ui.BG_TOP, 0.55f), Anim.argb(Ui.BG_BOTTOM, 0.55f));
	}

	/** Kept for old callers; the new layout always uses the full title. */
	public static boolean compact = false;

	/** Blossom + wordmark + tagline, with a short staged intro. Returns nothing; the buttons go under it. */
	public static void foreground(GuiGraphicsExtractor g, int w, int h) {
		long since = System.currentTimeMillis() - openedAt;
		float a1 = Anim.clamp01(since / 1000f);
		float a2 = Anim.easeOutCubic(Anim.clamp01((since - 300) / 800f));
		float a3 = Anim.easeOutCubic(Anim.clamp01((since - 650) / 800f));
		int logo = Math.round(Math.max(30, Math.min(46, h * 0.09f)));
		int top = logoTop(h);
		int lx = leftX(w);
		int cx = lx + logo / 2;
		double t = System.currentTimeMillis() / 1000.0;
		float bloom = Anim.easeOutBack(a1);
		// halo
		int hr = Math.round(logo * 1.3f);
		Compat.blitTint(g, Identifier.fromNamespaceAndPath("kyoto", "textures/gui/aa/soft.png"), cx - hr, top + logo / 2 - hr, hr * 2, hr * 2, Anim.argb(Ui.DARK ? 0xEF6F98 : 0xFFFFFF, (Ui.DARK ? 0.25f : 0.85f) * a1));
		g.pose().pushMatrix();
		g.pose().translate(cx, top + logo / 2f);
		g.pose().rotate((float) ((1 - bloom) * -1.2 + Math.sin(t * 0.4) * 0.05));
		g.pose().scale(Math.max(0.01f, bloom), Math.max(0.01f, bloom));
		Compat.blitTint(g, logo(), -logo / 2, -logo / 2, logo, logo, Anim.argb(0xFFFFFF, Math.min(1f, a1 * 1.5f)));
		g.pose().popMatrix();
		// wordmark sits to the right of the blossom, both left aligned with the buttons
		int wmH = Math.round(logo * 0.95f), wmW = Math.round(wmH / 0.385f);
		float rise = (1 - a2) * 6;
		Compat.blitTint(g, WORDMARK, lx + logo + 8, Math.round(top + logo / 2f - wmH / 2f + rise), wmW, wmH, Anim.argb(Ui.INK, a2));
		String s = splash();
		if (s != null) Ui.text(g, s, lx + 2, top + logo + 12, Anim.argb(Ui.MUTED, a3));
	}

	/** Left edge of the title column (logo, wordmark and buttons line up here). */
	public static int leftX(int w) {
		return Math.max(24, Math.round(w * 0.09f));
	}

	private static int logoTop(int h) {
		return Math.max(14, Math.round(h * 0.26f) - 20);
	}

	/** Bottom of the logo block, so the buttons can start under it. */
	public static int contentTop(int h) {
		int logo = Math.round(Math.max(30, Math.min(46, h * 0.09f)));
		return logoTop(h) + logo + 30;
	}
}
