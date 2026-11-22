package dev.kyoto.feature;

import dev.kyoto.module.Modules;
import dev.kyoto.ui.TitleTheme;

/**
 * Re-colours the sky + horizon fog so the world sky matches the Kyoto backgrounds
 * (deep gradients, soft glowing horizons). Purely visual, only you see it.
 */
public final class SkyTint {
	private SkyTint() {}

	/** {top sky, horizon} sampled from each title background (same order as TitleTheme.Theme). */
	private static final int[][] THEME = {
			{0x141826, 0x2A2C3C}, {0x1A1418, 0x5A3A34}, {0x1E1830, 0x7A5070}, {0x16203A, 0x7A6880},
			{0x1C1820, 0x7A4E44}, {0x121A2E, 0x4E5E7A}, {0x1E1E34, 0x6E6690}, {0x1E1618, 0x8A5A40},
			{0x140F20, 0x3E2C52}, {0x0E1426, 0x30425E}, {0x10101E, 0x2E2C4A}, {0x1E101C, 0x804060}};

	private static final int[][] NIGHT = { // after "Vanilla" and "Match my background"
			{0x0E1430, 0x2A2C5A}, // Midnight blue
			{0x1A1030, 0x4A2E66}, // Violet
			{0x220E1C, 0x6A2E4A}, // Rose
			{0x1C120E, 0x5E3424}, // Ember
	};
	private static final int[][] DAY = { // after "Vanilla" and "Match my background"
			{0x7AA2E6, 0xF4B88A}, // Golden hour
			{0x9DB4F2, 0xE6D8FF}, // Pastel
			{0xC79BE0, 0xFFC6DE}, // Sakura
			{0x4F7FD8, 0xBFE0FF}, // Crisp blue
	};

	private static int[] pick(boolean night) {
		int o = Modules.opt(night ? "sky.tint_night" : "sky.tint_day");
		if (o == 0) return null;
		if (o == 1) {
			int[] t = THEME[TitleTheme.current().ordinal() % THEME.length];
			if (night) return t;
			return new int[]{mix(t[0], 0x8FB4F0, 0.75f), mix(t[1], 0xF2DCE8, 0.7f)}; // daytime version of the same mood
		}
		int[][] arr = night ? NIGHT : DAY;
		return arr[Math.min(o - 2, arr.length - 1)];
	}

	private static boolean on() {
		if (!Modules.on("sky_fx")) return false;
		String d = dev.kyoto.util.Compat.dimensionId();
		return d.equals("minecraft:overworld") || d.equals("overworld") || d.equals("unknown");
	}

	/** 0 = bright day, 1 = full night, judged from the vanilla colour so sunsets blend smoothly. */
	private static float darkness(int rgb) {
		float lum = (((rgb >> 16) & 255) * 0.3f + ((rgb >> 8) & 255) * 0.59f + (rgb & 255) * 0.11f) / 255f;
		return Math.max(0, Math.min(1, 1 - lum / 0.55f));
	}

	public static int sky(int original) {
		if (!on()) return original;
		return blend(original, 0);
	}

	public static int horizon(int original) {
		if (!on()) return original;
		return blend(original, 1);
	}

	private static int blend(int original, int idx) {
		float d = darkness(original);
		int[] n = pick(true), dy = pick(false);
		if (n == null && dy == null) return original;
		int nightC = n == null ? original : n[idx], dayC = dy == null ? original : dy[idx];
		int target = mix(dayC, nightC, d);
		return (original & 0xFF000000) | (mix(original, target, 0.85f) & 0xFFFFFF);
	}

	public static float[] horizon(float r, float g, float b) {
		int c = horizon(((int) (r * 255) << 16) | ((int) (g * 255) << 8) | (int) (b * 255));
		return new float[]{((c >> 16) & 255) / 255f, ((c >> 8) & 255) / 255f, (c & 255) / 255f};
	}

	public static int mix(int a, int b, float t) {
		t = Math.max(0, Math.min(1, t));
		int r = Math.round(((a >> 16) & 255) * (1 - t) + ((b >> 16) & 255) * t);
		int g = Math.round(((a >> 8) & 255) * (1 - t) + ((b >> 8) & 255) * t);
		int bl = Math.round((a & 255) * (1 - t) + (b & 255) * t);
		return (r << 16) | (g << 8) | bl;
	}
}
