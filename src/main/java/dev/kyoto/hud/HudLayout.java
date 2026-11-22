package dev.kyoto.hud;

import dev.kyoto.config.KyotoConfig;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Lets every HUD element be moved and resized (HUD Editor: Right Shift > Tools > Edit HUD, or /kyoto hud).
 * Each element draws exactly like before; we just wrap it in a move + scale. Offsets and sizes are saved in
 * the config as {dx, dy, scale} per element.
 */
public final class HudLayout {
	private HudLayout() {}

	/** id -> nice name, in the order they show up in the editor */
	public static final Map<String, String> NAMES = new LinkedHashMap<>();

	static {
		NAMES.put("info_panel", "Info Panel");
		NAMES.put("stats", "FPS / Ping / CPS");
		NAMES.put("potions", "Potion Effects");
		NAMES.put("keystrokes", "Keystrokes");
		NAMES.put("now_playing", "Now Playing");
		NAMES.put("compass", "Compass");
		NAMES.put("armor", "Armor");
		NAMES.put("pickup", "Pickup Feed");
		NAMES.put("elytra", "Elytra Gauge");
		NAMES.put("notifications", "Notifications");
		NAMES.put("mini_player", "Mini Video Player");
	}

	/** Where each element was drawn last frame (screen coords): x, y, w, h. The editor reads these. */
	public static final Map<String, float[]> BOXES = new LinkedHashMap<>();

	private static float[] get(String id) {
		float[] v = KyotoConfig.get().hudLayout.get(id);
		return v == null || v.length < 3 ? new float[]{0, 0, 1} : v;
	}

	public static boolean moved(String id) {
		float[] v = get(id);
		return v[0] != 0 || v[1] != 0;
	}

	public static float scale(String id) {
		return get(id)[2];
	}

	/** True while the HUD editor is open (elements show placeholders so you can grab them). */
	public static volatile boolean editing = false;

	private static String open = null;
	private static float ox, oy, os;

	/**
	 * Start drawing element `id`. The element draws itself at (localX, localY) as usual; it appears on screen at
	 * (screenX, screenY) + your offset, scaled by baseScale x your size.
	 */
	public static void begin(GuiGraphicsExtractor g, String id, float localX, float localY, float screenX, float screenY, float baseScale) {
		float[] v = get(id);
		float s = baseScale * v[2];
		open = id;
		ox = screenX + v[0];
		oy = screenY + v[1];
		os = s;
		g.pose().pushMatrix();
		g.pose().translate(ox, oy);
		g.pose().scale(s, s);
		g.pose().translate(-localX, -localY);
	}

	/** Same, when the element's local and screen position are the same. */
	public static void begin(GuiGraphicsExtractor g, String id, float x, float y) {
		begin(g, id, x, y, x, y, 1f);
	}

	/** Finish; w/h = the element's size in its own (unscaled) units, for the editor's box. */
	public static void end(GuiGraphicsExtractor g, float w, float h) {
		g.pose().popMatrix();
		if (open != null) BOXES.put(open, new float[]{ox, oy, Math.max(8, w * os), Math.max(6, h * os), System.currentTimeMillis()});
		open = null;
	}

	public static void set(String id, float dx, float dy, float scale) {
		KyotoConfig.get().hudLayout.put(id, new float[]{dx, dy, Math.max(0.4f, Math.min(3f, scale))});
	}

	public static float[] raw(String id) {
		return get(id).clone();
	}

	public static void reset(String id) {
		KyotoConfig.get().hudLayout.remove(id);
	}
}
