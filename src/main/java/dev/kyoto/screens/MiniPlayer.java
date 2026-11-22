package dev.kyoto.screens;

import dev.kyoto.hud.HudLayout;
import dev.kyoto.module.Modules;
import dev.kyoto.util.Anim;
import dev.kyoto.util.Compat;
import dev.kyoto.util.Ui;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;

/**
 * The mini player: a YouTube video in a small window on YOUR screen instead of a screen placed in the world.
 * Only you see and hear it. Turn it on with World > Video Screens > Show as > Mini player on my screen.
 * Move and resize it in the HUD editor like any other HUD element.
 */
public final class MiniPlayer {
	private MiniPlayer() {}

	public static String url = "";
	public static boolean paused = true;
	private static double pausedPos = 0;
	private static long startedAt = 0;
	private static VideoStream stream;
	private static String streamKey = "";
	public static boolean hidden = false;

	public static boolean enabled() {
		return Modules.on("video_screens") && Modules.opt("screens.show") == 1;
	}

	public static double position() {
		if (paused) return pausedPos;
		long now = heldAt != 0 ? heldAt : System.currentTimeMillis();
		return Math.max(0, (now - startedAt) / 1000.0);
	}

	public static VideoStream stream() {
		return stream;
	}

	public static void play(String u) {
		url = u;
		pausedPos = 0;
		resume();
	}

	public static void resume() {
		startedAt = System.currentTimeMillis() - (long) (pausedPos * 1000);
		paused = false;
		hidden = false;
	}

	public static void pause() {
		pausedPos = position();
		paused = true;
	}

	public static void seek(double sec) {
		double s = Math.max(0, sec);
		if (stream != null && stream.duration > 0) s = Math.min(s, stream.duration - 1);
		pausedPos = s;
		startedAt = System.currentTimeMillis() - (long) (s * 1000);
		stopStream();
	}

	public static void stop() {
		url = "";
		paused = true;
		pausedPos = 0;
		stopStream();
	}

	private static void stopStream() {
		if (stream != null) stream.stop();
		stream = null;
		streamKey = "";
	}

	private static long heldAt = 0;

	public static void tick(Minecraft mc) {
		if (!enabled() || url.isBlank() || paused || mc.level == null) {
			stopStream();
			heldAt = 0;
			return;
		}
		// Esc menu: freeze it, then carry on from the same second
		if (Screens.menuOpen(mc)) {
			if (heldAt == 0) heldAt = System.currentTimeMillis();
			if (stream != null) stream.hold(true);
			return;
		}
		if (heldAt != 0) {
			startedAt += System.currentTimeMillis() - heldAt;
			heldAt = 0;
			if (stream != null) stream.hold(false);
		}
		int quality = new int[]{360, 480, 720, 1080}[Modules.opt("screens.quality")];
		String key = url + "|" + quality;
		if (stream != null && (!key.equals(streamKey) || stream.ended)) {
			if (stream.ended) {
				paused = true;
				pausedPos = 0;
			}
			stopStream();
			if (paused) return;
		}
		if (stream == null) {
			stream = new VideoStream(url, MiniPlayer::position, quality * 16 / 9, quality);
			streamKey = key;
		}
		stream.volume = new float[]{0.25f, 0.5f, 0.75f, 1f}[Modules.opt("screens.volume")];
		stream.pan = 0;
	}

	public static void render(GuiGraphicsExtractor g, int w, int h) {
		boolean placeholder = dev.kyoto.hud.HudLayout.editing && (url.isBlank() || hidden);
		if (!placeholder && (!enabled() || url.isBlank() || hidden)) return;
		int bw = 200, bh = 113;
		float x = w - bw - 6, y = h - bh - 60;
		HudLayout.begin(g, "mini_player", 0, 0, x, y, 1f);
		Ui.round(g, -2, -2, bw + 2, bh + 12, 6, Anim.argb(0x000000, 0.55f));
		Identifier tex = stream != null ? stream.texture() : null;
		if (tex != null) Compat.blitTint(g, tex, 0, 0, bw, bh, 0xFFFFFFFF);
		else g.fill(0, 0, bw, bh, 0xFF0B0B0E);
		String st = paused || heldAt != 0 ? "Paused" : stream == null ? "Starting..." : stream.state;
		if (!st.isEmpty()) {
			int tw = Ui.width(st);
			Ui.text(g, st, bw / 2 - tw / 2, bh / 2 - 4, 0xFFE6E8EC);
		}
		// progress
		double dur = stream != null ? stream.duration : -1;
		g.fill(0, bh + 4, bw, bh + 6, Anim.argb(0xFFFFFF, 0.15f));
		if (dur > 0) g.fill(0, bh + 4, (int) (bw * Math.min(1, position() / dur)), bh + 6, Anim.argb(Ui.accent(), 1f));
		HudLayout.end(g, bw, bh + 8);
	}
}
