package dev.kyoto.feature;

import dev.kyoto.module.Modules;
import dev.kyoto.util.Compat;
import net.minecraft.client.Minecraft;

/**
 * Zoomify-style zoom.
 *  - Hold C (or tap, in Toggle mode): the FOV eases in with the transition you pick.
 *  - Scroll while zoomed: zoom further in/out in smooth steps, from 1.2x up to 50x.
 *  - Sensitivity scales with the zoom, optional cinematic camera while zoomed.
 *
 * The FOV is changed every rendered frame by the FOV mixins (GameRenderer / Camera / player FOV).
 * Several hooks exist because Mojang keeps moving this code; only ONE of them applies the zoom
 * (the best one that's actually running), so it can never be applied twice.
 */
public final class Zoom {
	private Zoom() {}

	/* --- hook bookkeeping --- */
	public static final int HOOK_RENDERER = 3, HOOK_CAMERA_CALC = 2, HOOK_CAMERA = 1, HOOK_PLAYER = 0;
	private static final long[] hookSeen = new long[4];
	private static long lastAnimNanos = 0;
	/** The last real FOV (degrees) the game rendered with, after zoom. Used to project lyrics/waypoints. */
	public static volatile float lastRenderFov = -1;
	private static long lastRenderFovAt = 0;

	/* --- state --- */
	private static boolean active = false; // zoom key held / toggled
	private static boolean lastKey = false;
	private static boolean wide = false;
	/** 0..1 how far into the zoom transition we are */
	private static float progress = 0;
	/** zoom factor while zoomed (4 = 4x). Scroll changes target, current follows smoothly. */
	private static float zoomTarget = 4, zoomCurrent = 4;
	private static long lastScrollAt = 0;

	private static Double savedSens = null;
	private static Boolean savedSmoothCam = null;
	private static Integer savedFov = null;

	private static float startZoom() {
		if (wide) return 1f / 1.45f;
		return new float[]{2f, 3f, 4f, 6f, 10f}[Modules.opt("zoom.amount")];
	}

	private static float transitionSeconds() {
		return switch (Modules.opt("zoom.speed")) {
			case 0 -> 0.14f;
			case 2 -> 0.42f;
			default -> 0.24f;
		};
	}

	private static float ease(float t) {
		t = Math.max(0, Math.min(1, t));
		return switch (Modules.opt("zoom.transition")) {
			case 0 -> t * t * (3 - 2 * t); // smoothstep
			case 1 -> 1 - (1 - t) * (1 - t) * (1 - t); // ease out cubic
			case 2 -> { // elastic out
				if (t == 0 || t == 1) yield t;
				yield (float) (Math.pow(2, -10 * t) * Math.sin((t * 10 - 0.75) * (2 * Math.PI / 3)) + 1);
			}
			case 3 -> t;
			default -> t >= 0.5f ? 1 : 0;
		};
	}

	/** Current FOV multiplier (1 = no zoom, 0.25 = 4x). */
	public static float currentMultiplier() {
		float e = ease(progress);
		float z = zoomCurrent;
		// interpolate in "zoom space" so it feels even at any level
		float zoom = 1f + (z - 1f) * e;
		return 1f / Math.max(0.02f, zoom);
	}

	public static boolean isZooming() {
		return progress > 0.001f;
	}

	private static void animate() {
		long now = System.nanoTime();
		float dt = lastAnimNanos == 0 ? 0.016f : Math.min(0.1f, (now - lastAnimNanos) / 1_000_000_000f);
		lastAnimNanos = now;
		boolean instant = Modules.opt("zoom.transition") == 4;
		float step = instant ? 1f : dt / transitionSeconds();
		progress = active ? Math.min(1, progress + step) : Math.max(0, progress - step);
		// scroll smoothing (log space so steps feel the same at 2x and 30x)
		float k = 1f - (float) Math.exp(-dt * 16f);
		double lc = Math.log(zoomCurrent), lt = Math.log(zoomTarget);
		zoomCurrent = (float) Math.exp(lc + (lt - lc) * k);
		if (Math.abs(zoomCurrent - zoomTarget) < 0.001f) zoomCurrent = zoomTarget;
	}

	/** Which hook should apply the zoom right now: the best one seen in the last second. */
	private static int owner() {
		long now = System.currentTimeMillis();
		for (int i = hookSeen.length - 1; i >= 0; i--) if (now - hookSeen[i] < 1000) return i;
		return -1;
	}

	/**
	 * Called by the FOV mixins with the FOV (or FOV modifier) the game computed.
	 * Returns it zoomed if this hook is the one in charge, otherwise unchanged.
	 */
	public static float hook(int which, float original) {
		hookSeen[which] = System.currentTimeMillis();
		if (owner() != which) return original;
		if (which != HOOK_PLAYER) {
			animate();
			float out = original * currentMultiplier();
			if (which >= HOOK_CAMERA) {
				lastRenderFov = out;
				lastRenderFovAt = System.currentTimeMillis();
			}
			return out;
		}
		// player FOV modifier hook is called per tick, not per frame: animate in tick() instead
		return original * currentMultiplier();
	}

	/** FOV (degrees) to use when placing things on screen. */
	public static double projectionFov(Minecraft mc) {
		if (lastRenderFov > 1 && System.currentTimeMillis() - lastRenderFovAt < 500) return lastRenderFov;
		return mc.options.fov().get() * currentMultiplier();
	}

	/** Returns true if the scroll was used for zooming (so the hotbar should ignore it). */
	public static boolean consumeScroll(double amount) {
		if (!active || amount == 0 || Modules.opt("zoom.scroll") != 0 || wide) return false;
		float factor = amount > 0 ? 1.25f : 0.8f;
		zoomTarget = Math.max(1.2f, Math.min(50f, zoomTarget * factor));
		lastScrollAt = System.currentTimeMillis();
		return true;
	}

	/** "4.0x" label shown briefly after scrolling. */
	public static String scrollLabel() {
		if (System.currentTimeMillis() - lastScrollAt > 900 || !active) return null;
		return String.format(zoomTarget >= 10 ? "%.0fx" : "%.1fx", zoomTarget);
	}

	public static void tick(Minecraft mc, boolean keyDown, boolean wideDown) {
		boolean allowed = mc.player != null && Modules.on("zoom") && Compat.currentScreen() == null;
		boolean toggleMode = Modules.opt("zoom.mode") == 1;
		boolean want;
		if (!allowed) {
			want = false;
		} else if (wideDown) {
			want = true;
		} else if (toggleMode) {
			want = active && !wide;
			if (keyDown && !lastKey) want = !want;
		} else {
			want = keyDown;
		}
		lastKey = keyDown;
		if (want && !active) {
			wide = wideDown && !keyDown;
			zoomTarget = zoomCurrent = startZoom();
		}
		active = want;
		if (owner() < 0 || owner() == HOOK_PLAYER) animate();

		// last-resort fallback (no FOV hook running on this Minecraft version): use the FOV setting
		if (owner() < 0 && isZooming()) {
			if (savedFov == null) savedFov = mc.options.fov().get();
			mc.options.fov().set(Math.max(30, Math.min(110, Math.round(savedFov * currentMultiplier()))));
		} else if (savedFov != null && (!isZooming() || owner() >= 0)) {
			mc.options.fov().set(savedFov);
			savedFov = null;
		}

		// sensitivity follows the zoom
		if (isZooming() && Modules.opt("zoom.relative_sens") == 0 && !wide) {
			if (savedSens == null) savedSens = mc.options.sensitivity().get();
			double scale = Math.max(0.08, Math.min(1.0, Math.pow(currentMultiplier(), 0.85)));
			mc.options.sensitivity().set(savedSens * scale);
		} else if (savedSens != null) {
			mc.options.sensitivity().set(savedSens);
			savedSens = null;
		}
		// cinematic camera while zoomed
		if (active && Modules.opt("zoom.cinematic") == 0 && !wide) {
			if (savedSmoothCam == null) savedSmoothCam = mc.options.smoothCamera;
			mc.options.smoothCamera = true;
		} else if (savedSmoothCam != null && !active) {
			mc.options.smoothCamera = savedSmoothCam;
			savedSmoothCam = null;
		}
	}

	/** Called on disconnect / quit so nothing stays changed. */
	public static void restore(Minecraft mc) {
		active = false;
		progress = 0;
		if (savedSens != null) mc.options.sensitivity().set(savedSens);
		if (savedSmoothCam != null) mc.options.smoothCamera = savedSmoothCam;
		if (savedFov != null) mc.options.fov().set(savedFov);
		savedFov = null;
		savedSens = null;
		savedSmoothCam = null;
	}
}
