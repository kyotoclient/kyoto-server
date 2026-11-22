package dev.kyoto.util;

/** Small easing + color helpers used by every animated HUD piece. */
public final class Anim {
	private Anim() {}

	public static float clamp01(float v) {
		return v < 0 ? 0 : (v > 1 ? 1 : v);
	}

	public static float easeOutCubic(float t) {
		t = clamp01(t);
		float f = 1 - t;
		return 1 - f * f * f;
	}

	public static float easeInOut(float t) {
		return t < 0.5f ? 4 * t * t * t : 1 - (float) Math.pow(-2 * t + 2, 3) / 2;
	}

	public static float easeInCubic(float t) {
		t = clamp01(t);
		return t * t * t;
	}

	public static float easeOutBack(float t) {
		t = clamp01(t);
		float c1 = 1.70158f, c3 = c1 + 1;
		float f = t - 1;
		return 1 + c3 * f * f * f + c1 * f * f;
	}

	/** Combine an RGB color (0xRRGGBB) with an alpha from 0..1 into ARGB. */
	public static int argb(int rgb, float alpha) {
		int a = Math.round(clamp01(alpha) * 255);
		return (a << 24) | (rgb & 0xFFFFFF);
	}

	/** Multiply the alpha of an existing ARGB color. */
	public static int fade(int argb, float alpha) {
		int a = (argb >>> 24) & 0xFF;
		int na = Math.round(a * clamp01(alpha));
		return (na << 24) | (argb & 0xFFFFFF);
	}

	/** Green -> yellow -> red depending on how much is left (1 = full). */
	public static int healthColor(float fraction) {
		fraction = clamp01(fraction);
		if (fraction > 0.5f) {
			float t = (fraction - 0.5f) * 2;
			int r = Math.round(255 * (1 - t));
			return (r << 16) | (0xFF << 8);
		} else {
			float t = fraction * 2;
			int g = Math.round(255 * t);
			return (0xFF << 16) | (g << 8);
		}
	}
}
