package dev.kyoto.util;

import dev.kyoto.module.Modules;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;

/** Shared look: custom font (with Minecraft-font fallback), rounded glass panels, accent colors. */
public final class Ui {
	private Ui() {}

	public static final Identifier FONT = Identifier.fromNamespaceAndPath("kyoto", "ui");
	public static final Identifier FONT_BOLD = Identifier.fromNamespaceAndPath("kyoto", "ui_bold");
	private static final java.util.Map<String, Identifier> FONT_CACHE = new java.util.HashMap<>();

	/* Kyoto sakura: white and pink glass, ink text. Dark mode dims the white instead of going black. Set by refresh(). */
	public static int INK = 0x2B1D23, TEXT = 0x2B1D23, MUTED = 0x8A7480, DIM = 0xB3A1A9, SOFT = 0xE9DDE2, GLASS = 0xFFF8FA, GLASS_LIGHT = 0xFFFFFF, LINE = 0xFFFFFF;
	public static boolean DARK = false;
	public static final int PINK = 0xEF6F98, PINK_SOFT = 0xFFB3C9, PINK_DEEP = 0xD94C7C;

	private static final int[] ACCENTS = {0xEF6F98, 0xF7A1BC, 0xE0457A, 0xF59E8B, 0xB59AE8, 0x7FB6F0, 0x6CCFAE};

	/** Background wash for menus (top and bottom of the gradient), set by the theme. */
	public static int BG_TOP = 0xFCEFF4, BG_BOTTOM = 0xF6DCE6;
	/* per theme: glass, bg top, bg bottom, ink (light mode) */
	private static final int[][] THEMES = {
			{0xFFF8FA, 0xFCEFF4, 0xF6DCE6, 0x2B1D23}, // Sakura
			{0xFFF9FB, 0xFFF3F7, 0xFBE6EE, 0x2E1C24}, // Blush
			{0xFFF6F8, 0xFBE8EE, 0xF2C9D6, 0x2E1820}, // Rose
			{0xFFF9F6, 0xFFF1EA, 0xFBDCCF, 0x2E2019}, // Peach
			{0xFBF8FF, 0xF3EEFC, 0xE3D8F7, 0x241D33}, // Lilac
			{0xF7FBFF, 0xEAF3FD, 0xD3E5F8, 0x1A2533}, // Sky
			{0xF6FFFB, 0xE6F7F0, 0xCDEEE1, 0x17302A}, // Mint
	};
	private static String themeKey = "";

	/** Which theme is on (index into the Theme page). */
	public static int theme() {
		return Modules.on("accent") ? Math.max(0, Modules.opt("accent.color")) : 0;
	}

	/** Re-reads Dark Mode and the theme. Cheap; called from the hot paths so a change shows up at once. */
	public static void refresh() {
		boolean d = Modules.on("dark_mode");
		int th = theme();
		int acc = th == 7 ? accent0() : th < ACCENTS.length ? ACCENTS[th] : PINK;
		String key = d + ":" + th + ":" + acc;
		if (key.equals(themeKey) && INK != 0) return;
		themeKey = key;
		DARK = d;
		int[] t = th < THEMES.length ? THEMES[th] : THEMES[0];
		if (d) {
			INK = TEXT = 0xF6EEF1;
			GLASS = mix(0x2A2226, acc, 0.07f);
			GLASS_LIGHT = mix(0x372B31, acc, 0.07f);
			BG_TOP = mix(0x221A1E, acc, 0.06f);
			BG_BOTTOM = mix(0x171114, acc, 0.05f);
			MUTED = mix(INK, GLASS, 0.32f);
			DIM = mix(INK, GLASS, 0.52f);
			SOFT = mix(INK, GLASS, 0.78f);
		} else {
			INK = TEXT = t[3];
			GLASS = th == 7 ? mix(0xFFFFFF, acc, 0.04f) : t[0];
			GLASS_LIGHT = 0xFFFFFF;
			BG_TOP = th == 7 ? mix(0xFFFFFF, acc, 0.1f) : t[1];
			BG_BOTTOM = th == 7 ? mix(0xFFFFFF, acc, 0.22f) : t[2];
			MUTED = mix(INK, GLASS, 0.45f);
			DIM = mix(INK, GLASS, 0.62f);
			SOFT = mix(INK, GLASS, 0.88f);
		}
	}

	private static int accent0() {
		var cfg = dev.kyoto.config.KyotoConfig.get();
		return hsv(cfg.accentHue, cfg.accentSat, cfg.accentBright);
	}

	public static Font font() {
		return Minecraft.getInstance().font;
	}

	public static boolean customFont() {
		return Modules.on("custom_font") && fontId(Modules.opt("font.family")) != null;
	}

	/** kyoto:<id> font for a FONTS index, or null for the Minecraft font. */
	public static String fontId(int index) {
		if (index < 0 || index >= Modules.FONT_IDS.length) return "ui";
		return Modules.FONT_IDS[index];
	}

	private static Identifier fontRes(String id, boolean bold) {
		String key = id + (bold ? "_bold" : "");
		return FONT_CACHE.computeIfAbsent(key, k -> Identifier.fromNamespaceAndPath("kyoto", k));
	}

	/** Text in any of the Kyoto fonts (index into Modules.FONTS). */
	public static Component inFont(String s, int fontIndex, boolean bold) {
		MutableComponent c = Component.literal(s);
		String id = fontId(fontIndex);
		if (id == null) return bold ? c.withStyle(st -> st.withBold(true)) : c;
		return Compat.withFont(c, fontRes(id, bold));
	}

	public static Component t(String s) {
		MutableComponent c = Component.literal(s);
		return customFont() ? Compat.withFont(c, fontRes(fontId(Modules.opt("font.family")), false)) : c;
	}

	public static Component tb(String s) {
		MutableComponent c = Component.literal(s);
		return customFont() ? Compat.withFont(c, fontRes(fontId(Modules.opt("font.family")), true)) : c.withStyle(st -> st.withBold(true));
	}

	/** Bold text drawn bigger (for headers). */
	public static void boldScaled(GuiGraphicsExtractor g, String s, float x, float y, float scale, int argb) {
		if ((argb >>> 24) < 6) return;
		g.pose().pushMatrix();
		g.pose().translate(x, y);
		g.pose().scale(scale, scale);
		g.text(font(), tb(s), 0, 0, argb, false);
		g.pose().popMatrix();
	}

	/** Small text (0.75x) for descriptions / labels. */
	public static void small(GuiGraphicsExtractor g, String s, float x, float y, int argb) {
		if ((argb >>> 24) < 6) return;
		g.pose().pushMatrix();
		g.pose().translate(x, y);
		g.pose().scale(0.75f, 0.75f);
		g.text(font(), t(s), 0, 0, argb, false);
		g.pose().popMatrix();
	}

	public static int widthSmall(String s) {
		return Math.round(width(s) * 0.75f);
	}

	/** Draws one of Kyoto's white PNG icons (assets/kyoto/textures/gui/icons) tinted with a colour. */
	public static void iconTex(GuiGraphicsExtractor g, String name, int x, int y, int size, int argb) {
		if ((argb >>> 24) < 6) return;
		Compat.blitTint(g, ICONS.computeIfAbsent(name, n -> Identifier.fromNamespaceAndPath("kyoto", "textures/gui/icons/" + n + ".png")), x, y, size, size, argb);
	}

	private static final java.util.Map<String, Identifier> ICONS = new java.util.HashMap<>();

	public static Identifier tex(String path) {
		return ICONS.computeIfAbsent("tex:" + path, n -> Identifier.fromNamespaceAndPath("kyoto", path));
	}

	public static int width(String s) {
		return font().width(t(s));
	}

	public static int widthBold(String s) {
		return font().width(tb(s));
	}

	public static void text(GuiGraphicsExtractor g, String s, int x, int y, int argb) {
		if ((argb >>> 24) < 6) return;
		g.text(font(), t(s), x, y, argb, false);
	}

	public static void textShadow(GuiGraphicsExtractor g, String s, int x, int y, int argb) {
		if ((argb >>> 24) < 6) return;
		g.text(font(), t(s), x, y, argb, true);
	}

	public static void bold(GuiGraphicsExtractor g, String s, int x, int y, int argb) {
		if ((argb >>> 24) < 6) return;
		g.text(font(), tb(s), x, y, argb, false);
	}

	/** Icons always use Minecraft's font because it has all the symbols. */
	public static void icon(GuiGraphicsExtractor g, String s, int x, int y, int argb) {
		if ((argb >>> 24) < 6) return;
		g.text(font(), Component.literal(s), x, y, argb, false);
	}

	/** Cuts text to fit a width, adding "…" */
	public static String ellipsize(String s, int maxWidth) {
		if (width(s) <= maxWidth) return s;
		String dots = "…";
		int end = s.length();
		while (end > 0 && width(s.substring(0, end) + dots) > maxWidth) end--;
		return s.substring(0, end) + dots;
	}

	public static int accent() {
		refresh();
		if (!Modules.on("accent")) return PINK;
		int c = Modules.opt("accent.color");
		var cfg = dev.kyoto.config.KyotoConfig.get();
		if (c == 7) return hsv(cfg.accentHue, cfg.accentSat, cfg.accentBright);
		if (c == 8) return shimmer(0);
		return ACCENTS[Math.min(c, ACCENTS.length - 1)];
	}

	/** Sakura shimmer: a slow blush-to-rose breathing colour. */
	public static int shimmer(float offset) {
		float speed = dev.kyoto.config.KyotoConfig.get().rgbSpeed;
		float k = 0.5f + 0.5f * (float) Math.sin(((System.currentTimeMillis() % 100000L) / 1000f * speed + offset / 60f) * 1.4f);
		return mix(PINK_SOFT, PINK_DEEP, k);
	}

	/** Rainbow color that moves over time. offset (degrees) lets neighbours differ. */
	public static int rgbCycle(float offset) {
		float speed = dev.kyoto.config.KyotoConfig.get().rgbSpeed;
		float hue = ((System.currentTimeMillis() % 100000L) / 1000f * 60f * speed + offset) % 360f;
		return hsv(hue, 0.55f, 1f);
	}

	/** hue 0-360, sat 0-1, val 0-1 -> 0xRRGGBB */
	public static int hsv(float h, float s, float v) {
		h = ((h % 360f) + 360f) % 360f;
		float c = v * s, x = c * (1 - Math.abs((h / 60f) % 2 - 1)), m = v - c;
		float r, g, b;
		if (h < 60) { r = c; g = x; b = 0; }
		else if (h < 120) { r = x; g = c; b = 0; }
		else if (h < 180) { r = 0; g = c; b = x; }
		else if (h < 240) { r = 0; g = x; b = c; }
		else if (h < 300) { r = x; g = 0; b = c; }
		else { r = c; g = 0; b = x; }
		return (Math.round((r + m) * 255) << 16) | (Math.round((g + m) * 255) << 8) | Math.round((b + m) * 255);
	}

	/** Animation speed multiplier from the Client > UI Animations setting. 0 = instant. */
	public static float animSpeed() {
		if (!Modules.on("ui_animations")) return 0f;
		return switch (Modules.opt("ui.speed")) {
			case 0 -> 0.65f;
			case 2 -> 1.6f;
			default -> 1f;
		};
	}

	/* ================================================================ smooth shapes ================================================================
	 * Every rounded shape is drawn from small anti-aliased textures (assets/kyoto/textures/gui/aa) cut into 9 pieces, so curves are real
	 * curves at any GUI scale instead of pixel stairs. The texture is picked by the on-screen pixel radius. */

	private static final int[] AA_N = {8, 16, 32, 64};
	private static final float[] RING_F = {1 / 16f, 1 / 10f, 1 / 7f, 1 / 5f, 1 / 3.5f, 1 / 2.4f};
	private static final Identifier[] DISC = new Identifier[4];
	private static final Identifier[][] RING = new Identifier[4][6];
	private static final Identifier SOFT_TEX = Identifier.fromNamespaceAndPath("kyoto", "textures/gui/aa/soft.png");
	private static final Identifier SHEEN = Identifier.fromNamespaceAndPath("kyoto", "textures/gui/aa/sheen.png");
	private static final Identifier GLASS_RIM = Identifier.fromNamespaceAndPath("kyoto", "textures/gui/aa/glass_rim.png");
	private static final Identifier GLASS_INNER = Identifier.fromNamespaceAndPath("kyoto", "textures/gui/aa/glass_inner.png");
	static {
		for (int i = 0; i < 4; i++) {
			DISC[i] = Identifier.fromNamespaceAndPath("kyoto", "textures/gui/aa/disc_" + AA_N[i] + ".png");
			for (int k = 0; k < 6; k++) RING[i][k] = Identifier.fromNamespaceAndPath("kyoto", "textures/gui/aa/ring_" + AA_N[i] + "_" + k + ".png");
		}
	}

	public static float guiScale() {
		try {
			return (float) Minecraft.getInstance().getWindow().getGuiScale();
		} catch (Throwable t) {
			return 2f;
		}
	}

	private static int sizeIndex(float r) {
		float px = r * guiScale();
		for (int i = 0; i < AA_N.length; i++) if (AA_N[i] >= px) return i;
		return AA_N.length - 1;
	}

	/** Draws a 9-slice of a square texture (2N x 2N) whose corners are the quarters and whose middle is stretched. */
	private static void nine(GuiGraphicsExtractor g, Identifier tex, int n, int x1, int y1, int x2, int y2, int r, int argb) {
		int s = n * 2, w = x2 - x1, h = y2 - y1;
		Compat.blitRegion(g, tex, x1, y1, r, r, 0, 0, n, n, s, s, argb);
		Compat.blitRegion(g, tex, x2 - r, y1, r, r, n, 0, n, n, s, s, argb);
		Compat.blitRegion(g, tex, x1, y2 - r, r, r, 0, n, n, n, s, s, argb);
		Compat.blitRegion(g, tex, x2 - r, y2 - r, r, r, n, n, n, n, s, s, argb);
		if (w > 2 * r) {
			Compat.blitRegion(g, tex, x1 + r, y1, w - 2 * r, r, n - 1, 0, 2, n, s, s, argb);
			Compat.blitRegion(g, tex, x1 + r, y2 - r, w - 2 * r, r, n - 1, n, 2, n, s, s, argb);
		}
		if (h > 2 * r) {
			Compat.blitRegion(g, tex, x1, y1 + r, r, h - 2 * r, 0, n - 1, n, 2, s, s, argb);
			Compat.blitRegion(g, tex, x2 - r, y1 + r, r, h - 2 * r, n, n - 1, n, 2, s, s, argb);
		}
		if (w > 2 * r && h > 2 * r) Compat.blitRegion(g, tex, x1 + r, y1 + r, w - 2 * r, h - 2 * r, n - 1, n - 1, 2, 2, s, s, argb);
	}

	/** Filled rectangle with smooth rounded corners. */
	public static void round(GuiGraphicsExtractor g, int x1, int y1, int x2, int y2, int r, int argb) {
		if ((argb >>> 24) == 0 || x2 <= x1 || y2 <= y1) return;
		r = Math.min(r, Math.min(x2 - x1, y2 - y1) / 2);
		if (r <= 0) {
			g.fill(x1, y1, x2, y2, argb);
			return;
		}
		int i = sizeIndex(r);
		nine(g, DISC[i], AA_N[i], x1, y1, x2, y2, r, argb);
	}

	/** The old pixel-stepped rounded rectangle, for places that draw before textures exist (the loading screen). */
	public static void roundPx(GuiGraphicsExtractor g, int x1, int y1, int x2, int y2, int r, int argb) {
		if ((argb >>> 24) == 0 || x2 <= x1 || y2 <= y1) return;
		int w = x2 - x1, h = y2 - y1;
		r = Math.min(r, Math.min(w, h) / 2);
		if (r <= 0) {
			g.fill(x1, y1, x2, y2, argb);
			return;
		}
		g.fill(x1, y1 + r, x2, y2 - r, argb);
		for (int i = 0; i < r; i++) {
			double dy = r - i - 0.5;
			int inset = (int) Math.round(r - Math.sqrt(r * r - dy * dy));
			g.fill(x1 + inset, y1 + i, x2 - inset, y1 + i + 1, argb);
			g.fill(x1 + inset, y2 - i - 1, x2 - inset, y2 - i, argb);
		}
	}

	/** Circle centred on cx,cy. */
	public static void circle(GuiGraphicsExtractor g, float cx, float cy, float rad, int argb) {
		int r = Math.max(1, Math.round(rad));
		round(g, Math.round(cx - r), Math.round(cy - r), Math.round(cx + r), Math.round(cy + r), r, argb);
	}

	/** Panel opacity from Client > Menu Transparency (Solid / Glass / Clear). */
	public static float panelAlpha() {
		if (liquid()) return DARK ? 0.5f : 0.34f;          // clear liquid glass
		if (!Modules.on("menu_glass")) return DARK ? 0.9f : 0.92f;
		return (DARK ? new float[]{0.88f, 0.74f, 0.6f} : new float[]{0.92f, 0.78f, 0.62f})[Math.min(2, Modules.opt("ui.glass"))];
	}

	public static boolean liquid() {
		return Modules.on("liquid_glass");
	}

	/** Mouse position in GUI coordinates (for the liquid glass highlight). */
	public static float mouseX() {
		var mc = Minecraft.getInstance();
		try {
			return (float) (mc.mouseHandler.xpos() * mc.getWindow().getGuiScaledWidth() / Math.max(1, mc.getWindow().getScreenWidth()));
		} catch (Throwable t) {
			return -1;
		}
	}

	public static float mouseY() {
		var mc = Minecraft.getInstance();
		try {
			return (float) (mc.mouseHandler.ypos() * mc.getWindow().getGuiScaledHeight() / Math.max(1, mc.getWindow().getScreenHeight()));
		} catch (Throwable t) {
			return -1;
		}
	}

	/**
	 * Glass panel. Soft shadow, frosted fill, a bright rim and a sheen on top.
	 * With Liquid Glass on, a highlight slides along the rim towards the mouse and a slow shimmer moves across the top.
	 */
	/**
	 * Liquid glass, like Apple's: a clear pane (the blurred world shows through), a bright rim that catches the light
	 * top-left with a glint bottom-right, and a soft inner glow along the top. Every layer is a rounded nine-slice,
	 * so nothing ever pokes out past the corners. With Liquid Glass off it's a frosted, more solid pane.
	 */
	public static void glass(GuiGraphicsExtractor g, int x1, int y1, int x2, int y2, int r, float alpha) {
		refresh();
		if (alpha <= 0.01f || x2 <= x1 || y2 <= y1) return;
		int w = x2 - x1, h = y2 - y1;
		r = Math.max(2, Math.min(r, Math.min(w, h) / 2));
		boolean liquid = liquid();
		shadow(g, x1, y1, x2, y2, r, (DARK ? 0.5f : 0.3f) * alpha);
		round(g, x1, y1, x2, y2, r, Anim.argb(GLASS, panelAlpha() * alpha));
		// faint tint from the top so the pane has depth
		nine(g, GLASS_INNER, 64, x1, y1, x2, y2, r, Anim.argb(0xFFFFFF, (DARK ? 0.12f : liquid ? 0.75f : 0.45f) * alpha));
		nine(g, GLASS_RIM, 64, x1, y1, x2, y2, r, Anim.argb(0xFFFFFF, (DARK ? 0.55f : 1f) * alpha));
		ring(g, x1, y1, x2, y2, r, 1, Anim.argb(DARK ? 0x000000 : 0x9C3D62, (DARK ? 0.25f : 0.07f) * alpha));
		if (liquid) {
			int strength = Modules.opt("glass.strength");
			float k = (0.45f + 0.3f * strength) * alpha;
			float mx = mouseX(), my = mouseY();
			// a glint that slides along the top rim towards the mouse
			if (mx >= 0 && w > r * 2 + 8) {
				int hw = Math.min(w - 2 * r, Math.max(30, w / 4));
				float hx = Math.max(x1 + r + hw / 2f, Math.min(x2 - r - hw / 2f, mx));
				float near = 1f - Math.min(1f, Math.abs(my - y1) / Math.max(80f, h));
				Compat.blitTint(g, SHEEN, Math.round(hx - hw / 2f), y1 - 2, hw, 5, Anim.argb(0xFFFFFF, (0.25f + 0.55f * near) * k));
			}
		}
	}

	/** Soft shadow under a rounded box. Pink-tinted in light mode, black in dark mode. */
	public static void shadow(GuiGraphicsExtractor g, int x1, int y1, int x2, int y2, int r, float alpha) {
		if (alpha <= 0.01f) return;
		int s = Math.max(6, Math.min(18, Math.min(x2 - x1, y2 - y1) / 6));
		int col = DARK ? 0x000000 : 0x9C3D62;
		nine(g, SOFT_TEX, 64, x1 - s, y1 - s + s / 3, x2 + s, y2 + s + s / 2, r + s, Anim.argb(col, alpha * (DARK ? 0.55f : 0.32f)));
	}

	/** Thin rounded outline. */
	public static void ring(GuiGraphicsExtractor g, int x1, int y1, int x2, int y2, int r, int argb) {
		ring(g, x1, y1, x2, y2, r, 1, argb);
	}

	/** Rounded outline of thickness t (GUI pixels). */
	public static void ring(GuiGraphicsExtractor g, int x1, int y1, int x2, int y2, int r, float t, int argb) {
		if ((argb >>> 24) == 0 || x2 <= x1 || y2 <= y1) return;
		r = Math.min(r, Math.min(x2 - x1, y2 - y1) / 2);
		if (r <= 1) {
			outline(g, x1, y1, x2, y2, argb);
			return;
		}
		float f = t / r;
		int k = 0;
		for (int i = 1; i < RING_F.length; i++) if (Math.abs(RING_F[i] - f) < Math.abs(RING_F[k] - f)) k = i;
		int i = sizeIndex(r);
		nine(g, RING[i][k], AA_N[i], x1, y1, x2, y2, r, argb);
	}

	/** Kyoto button: glass pill that fills with the accent when hovered (t = hover 0..1). */
	public static void button(GuiGraphicsExtractor g, int x1, int y1, int x2, int y2, String label, float t, boolean enabled, float alpha) {
		refresh();
		int r = (y2 - y1) / 2;
		int acc = accent();
		round(g, x1, y1 + 1, x2, y2 + 1, r, Anim.argb(DARK ? 0x000000 : 0x9C3D62, 0.08f * alpha));
		round(g, x1, y1, x2, y2, r, Anim.argb(GLASS, (DARK ? 0.7f : 0.78f) * alpha));
		if (enabled && t > 0.01f) round(g, x1, y1, x2, y2, r, Anim.argb(acc, 0.92f * t * alpha));
		if (y2 - y1 > 6) g.fillGradient(x1 + r, y1 + 1, x2 - r, (y1 + y2) / 2, Anim.argb(0xFFFFFF, (DARK ? 0.05f : 0.35f) * alpha), 0x00FFFFFF);
		ring(g, x1, y1, x2, y2, r, 1, Anim.argb(0xFFFFFF, (DARK ? 0.14f : 0.85f) * alpha));
		int col = enabled ? mix(INK, 0xFFFFFF, t) : DIM;
		int tw = width(label);
		text(g, label, (x1 + x2) / 2 - tw / 2, (y1 + y2) / 2 - 4, Anim.argb(col, alpha));
	}

	/** iOS-style switch, 18x10. t = 0 off .. 1 on (animate it). */
	public static void toggle(GuiGraphicsExtractor g, int x, int y, float t, float alpha) {
		refresh();
		round(g, x, y, x + 18, y + 10, 5, Anim.argb(mix(DARK ? 0x5A4A51 : 0xE3D3DA, accent(), t), alpha));
		float e = Anim.easeOutBack(Math.min(1f, t));
		int kx = x + 1 + Math.round(8 * e);
		round(g, kx, y + 2, kx + 8, y + 10, 4, Anim.argb(0x000000, 0.12f * alpha));
		round(g, kx, y + 1, kx + 8, y + 9, 4, Anim.argb(0xFFFFFF, alpha));
	}

	/**
	 * Organic "petal" blob: a superellipse (n ~ 2.5) with a little wobble, centred on cx,cy, rotated by rot (radians).
	 * Drawn as 1px scanlines under a rotated pose, so it stays smooth at any angle.
	 */
	public static void blob(GuiGraphicsExtractor g, float cx, float cy, float rx, float ry, float rot, float wobble, int argb) {
		if ((argb >>> 24) == 0 || rx < 1 || ry < 1) return;
		g.pose().pushMatrix();
		g.pose().translate(cx, cy);
		g.pose().rotate(rot);
		int hy = (int) Math.ceil(ry);
		for (int y = -hy; y < hy; y++) {
			float v = Math.min(1f, Math.abs((y + 0.5f) / ry));
			// superellipse half-width, wider at the outer end (y < 0) for a petal feel
			float half = (float) Math.pow(1 - Math.pow(v, 2.6), 1 / 2.6) * rx * (1 + wobble * (-(y + 0.5f) / ry));
			if (half <= 0.3f) continue;
			int hx = Math.round(half);
			g.fill(-hx, y, hx, y + 1, argb);
		}
		g.pose().popMatrix();
	}

	/**
	 * HUD card (HUD > HUD Look): 0 = Kyoto (dark card, soft shadow, thin border), 1 = Glass (the old see-through look),
	 * 2 = Minimal (no box, just text), 3 = Accent (Kyoto card with a coloured edge).
	 */
	public static void hud(GuiGraphicsExtractor g, int x1, int y1, int x2, int y2, int r, float alpha) {
		refresh();
		int style = Modules.on("hud_look") ? Modules.opt("hud.look") : 0;
		r = Math.max(r, Math.min(9, (y2 - y1) / 2));
		switch (style) {
			case 1 -> glass(g, x1, y1, x2, y2, r, alpha * 0.85f);
			case 2 -> {
			}
			default -> {
				shadow(g, x1, y1, x2, y2, r, 0.3f * alpha);
				round(g, x1, y1, x2, y2, r, Anim.argb(GLASS, (DARK ? 0.82f : 0.86f) * alpha));
				ring(g, x1, y1, x2, y2, r, 1, Anim.argb(0xFFFFFF, (DARK ? 0.12f : 0.8f) * alpha));
				if (style == 3 && y2 - y1 > 6) round(g, x1 + 3, y1 + 4, x1 + 5, y2 - 4, 1, Anim.argb(accent(), 0.95f * alpha));
			}
		}
	}

	/** Small icon + caps title used at the top of HUD cards. Returns the y under it. */
	public static int hudTitle(GuiGraphicsExtractor g, String icon, String title, int x, int y, int color, float alpha) {
		if (icon != null) iconTex(g, icon, x, y, 8, Anim.argb(color, alpha));
		small(g, title.toUpperCase(java.util.Locale.ROOT), x + (icon != null ? 11 : 0), y + 1, Anim.argb(MUTED, alpha));
		return y + 11;
	}

	/** 1px rounded outline. */
	public static void outline(GuiGraphicsExtractor g, int x1, int y1, int x2, int y2, int argb) {
		g.fill(x1 + 2, y1, x2 - 2, y1 + 1, argb);
		g.fill(x1 + 2, y2 - 1, x2 - 2, y2, argb);
		g.fill(x1, y1 + 2, x1 + 1, y2 - 2, argb);
		g.fill(x2 - 1, y1 + 2, x2, y2 - 2, argb);
	}

	/** Mixes two RGB colours. */
	public static int mix(int c1, int c2, float t) {
		int r = Math.round(((c1 >> 16) & 0xFF) + (((c2 >> 16) & 0xFF) - ((c1 >> 16) & 0xFF)) * t);
		int gg = Math.round(((c1 >> 8) & 0xFF) + (((c2 >> 8) & 0xFF) - ((c1 >> 8) & 0xFF)) * t);
		int b = Math.round((c1 & 0xFF) + ((c2 & 0xFF) - (c1 & 0xFF)) * t);
		return (r << 16) | (gg << 8) | b;
	}

	public static boolean inside(double mx, double my, int x1, int y1, int x2, int y2) {
		return mx >= x1 && mx < x2 && my >= y1 && my < y2;
	}

	/** Fallback "head" when a player isn't in the tab list: colored square with their initial. */
	public static void head(GuiGraphicsExtractor g, String name, int x, int y, int size, float alpha) {
		// your own face always comes from your CURRENT skin (the tab list keeps the old one until you rejoin)
		if (name != null && alpha > 0.9f && name.equalsIgnoreCase(Compat.myName())) {
			Identifier mine = dev.kyoto.net.Heads.get(name);
			if (mine != null) {
				Compat.blit(g, mine, x, y, size, size);
				return;
			}
		}
		if (name != null && alpha > 0.9f && Compat.drawHead(g, name, x, y, size)) return;
		if (name != null && alpha > 0.9f) {
			Identifier tex = dev.kyoto.net.Heads.get(name);
			if (tex != null) {
				Compat.blit(g, tex, x, y, size, size);
				return;
			}
		}
		int col = name == null ? 0x555555 : (0x404040 | (name.hashCode() & 0x7F7F7F));
		round(g, x, y, x + size, y + size, 3, Anim.argb(col, alpha));
		String init = name == null || name.isEmpty() ? "?" : name.substring(0, 1).toUpperCase();
		g.text(font(), Component.literal(init), x + size / 2 - font().width(init) / 2, y + size / 2 - 4, Anim.argb(0xFFFFFF, alpha), true);
	}

	/**
	 * The torii drawn from plain rectangles (no texture, no font), so it works even while Minecraft is still loading.
	 * build 0..1 assembles it: pillars rise, then the beams slide out from the middle, the roof beam last.
	 * size = width in GUI pixels; cx,cy = centre.
	 */
	public static void torii(GuiGraphicsExtractor g, float cx, float cy, float size, float build, int argb) {
		if ((argb >>> 24) == 0 || size < 4) return;
		float k = size / 256f, ox = cx - 128 * k, oy = cy - 135 * k;
		float pil = Anim.easeOutCubic(Anim.clamp01(build / 0.42f));
		float nuki = Anim.easeOutCubic(Anim.clamp01((build - 0.3f) / 0.3f));
		float shim = Anim.easeOutCubic(Anim.clamp01((build - 0.45f) / 0.3f));
		float kasa = Anim.easeOutBack(Anim.clamp01((build - 0.58f) / 0.42f));
		// bases
		if (pil > 0.01f) {
			fillF(g, ox + 56 * k, oy + 226 * k, ox + 94 * k, oy + 238 * k, argb);
			fillF(g, ox + 162 * k, oy + 226 * k, ox + 200 * k, oy + 238 * k, argb);
		}
		// pillars: slightly wider at the foot, growing upwards
		float top = 230 - 152 * pil;
		int rows = pil > 0.01f ? Math.max(1, Math.round((230 - top) * k)) : 0;
		for (int r = 0; r < rows; r++) {
			float y0 = 230 - (r + 1) * (230 - top) / rows, y1 = 230 - r * (230 - top) / rows;
			float f = (y0 - 78) / 152f; // 0 at top, 1 at foot
			fillF(g, ox + (69 - 7 * f) * k, oy + y0 * k, ox + (85 + 3 * f) * k, oy + y1 * k, argb);
			fillF(g, ox + (171 - 3 * f) * k, oy + y0 * k, ox + (187 + 7 * f) * k, oy + y1 * k, argb);
		}
		// tie beam (nuki) + centre strut
		if (nuki > 0.01f) {
			float hw = 96 * nuki;
			fillF(g, ox + (128 - hw) * k, oy + 100 * k, ox + (128 + hw) * k, oy + 112 * k, argb);
		}
		if (shim > 0.01f) {
			fillF(g, ox + 120 * k, oy + (104 - 26 * shim) * k, ox + 136 * k, oy + 104 * k, argb);
			float hw = 98 * shim;
			fillF(g, ox + (128 - hw) * k, oy + 66 * k, ox + (128 + hw) * k, oy + 78 * k, argb);
		}
		// roof beam (kasagi): curved up at both ends, drawn as thin columns
		if (kasa > 0.01f) {
			float hw = 122 * Math.min(1.04f, kasa);
			int cols = Math.max(2, Math.round(hw * 2 * k));
			for (int c = 0; c < cols; c++) {
				float x0 = 128 - hw + c * (hw * 2 / cols), x1 = x0 + hw * 2 / cols + 0.5f;
				float u = ((x0 + x1) / 2 - 128) / 122f;
				float lift = 1 - u * u;
				float t0 = 32 + 16 * lift, t1 = 52 + 14 * lift;
				fillF(g, ox + x0 * k, oy + t0 * k, ox + x1 * k, oy + t1 * k, argb);
			}
		}
	}

	private static void fillF(GuiGraphicsExtractor g, float x1, float y1, float x2, float y2, int argb) {
		int a = Math.round(x1), b = Math.round(y1), c = Math.round(x2), d = Math.round(y2);
		if (c <= a) c = a + 1;
		if (d <= b) d = b + 1;
		g.fill(a, b, c, d, argb);
	}
}
