package dev.kyoto.ui;

import dev.kyoto.config.KyotoConfig;
import dev.kyoto.module.Modules;
import dev.kyoto.profile.AudioPlayer;
import dev.kyoto.profile.Video;
import dev.kyoto.util.Anim;
import dev.kyoto.util.Compat;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Your own menu background: an MP4 (or GIF) behind the Kyoto menu and title screen, and an MP3 that plays while
 * they're open. Drop files into .minecraft/kyoto/backgrounds and pick them in Settings.
 */
public final class MenuMedia {
	private MenuMedia() {}

	private static Video video;
	private static String videoFile = "";
	private static boolean musicOn = false;

	public static Path dir() {
		Path d = net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir().resolve("kyoto").resolve("backgrounds");
		try { Files.createDirectories(d); } catch (Exception ignored) { }
		return d;
	}

	public static List<String> files(boolean videos) {
		List<String> out = new ArrayList<>();
		try (var s = Files.list(dir())) {
			s.forEach(p -> {
				String n = p.getFileName().toString(), l = n.toLowerCase(Locale.ROOT);
				boolean v = l.endsWith(".mp4") || l.endsWith(".webm") || l.endsWith(".mov") || l.endsWith(".mkv") || l.endsWith(".gif");
				boolean a = l.endsWith(".mp3") || l.endsWith(".wav");
				if (videos ? v : a) out.add(n);
			});
		} catch (Exception ignored) {
		}
		out.sort(String.CASE_INSENSITIVE_ORDER);
		return out;
	}

	public static boolean hasVideo() {
		return !KyotoConfig.get().menuVideo.isBlank();
	}

	/** Draws the video to fill the screen (cropped, never stretched). Returns false while there's nothing to show. */
	public static boolean draw(GuiGraphicsExtractor g, int w, int h, float alpha) {
		String f = KyotoConfig.get().menuVideo;
		if (f.isBlank()) return false;
		if (!f.equals(videoFile) || video == null) {
			if (video != null) video.close();
			videoFile = f;
			Path p = dir().resolve(f);
			video = Files.exists(p) ? Video.load(p) : null;
		}
		if (video == null) return false;
		Identifier frame = video.current();
		if (frame == null) return false;
		float vr = video.width / (float) Math.max(1, video.height), sr = w / (float) Math.max(1, h);
		int dw = vr > sr ? Math.round(h * vr) : w, dh = vr > sr ? h : Math.round(w / vr);
		Compat.blitTint(g, frame, (w - dw) / 2, (h - dh) / 2, dw, dh, Anim.argb(0xFFFFFF, alpha));
		return true;
	}

	/** The video inside a rounded box only (the Kyoto window), cover-fit, with properly rounded corners. */
	public static boolean drawIn(GuiGraphicsExtractor g, int x, int y, int w, int h, int r, float alpha) {
		String f = KyotoConfig.get().menuVideo;
		if (f.isBlank()) return false;
		if (!f.equals(videoFile) || video == null) {
			if (video != null) video.close();
			videoFile = f;
			Path p = dir().resolve(f);
			video = Files.exists(p) ? Video.load(p) : null;
		}
		if (video == null) return false;
		Identifier frame = video.current();
		if (frame == null) return false;
		float vr = video.width / (float) Math.max(1, video.height), sr = w / (float) Math.max(1, h);
		int dw = vr > sr ? Math.round(h * vr) : w, dh = vr > sr ? h : Math.round(w / vr);
		int vx = x + (w - dw) / 2, vy = y + (h - dh) / 2, col = Anim.argb(0xFFFFFF, alpha);
		// middle band
		g.enableScissor(x, y + r, x + w, y + h - r);
		Compat.blitTint(g, frame, vx, vy, dw, dh, col);
		g.disableScissor();
		// top and bottom bands, one row at a time so the corners follow the curve
		for (int i = 0; i < r; i++) {
			float dy = r - i - 0.5f;
			int in = Math.round(r - (float) Math.sqrt(Math.max(0, r * r - dy * dy)));
			g.enableScissor(x + in, y + i, x + w - in, y + i + 1);
			Compat.blitTint(g, frame, vx, vy, dw, dh, col);
			g.disableScissor();
			g.enableScissor(x + in, y + h - i - 1, x + w - in, y + h - i);
			Compat.blitTint(g, frame, vx, vy, dw, dh, col);
			g.disableScissor();
		}
		return true;
	}

	/** Start the menu music (if one is picked and Menu Music is on). */
	public static void musicStart() {
		String f = KyotoConfig.get().menuMusic;
		if (musicOn || f.isBlank() || !Modules.on("menu_music")) return;
		if (AudioPlayer.nowPlaying != null) return; // something else (a profile song) is already playing
		try {
			byte[] b = Files.readAllBytes(dir().resolve(f));
			AudioPlayer.setVolume(new float[]{0.2f, 0.4f, 0.6f, 0.8f}[Math.max(0, Math.min(3, Modules.opt("menumusic.volume")))]);
			AudioPlayer.play(b, f);
			musicOn = true;
		} catch (Exception ignored) {
		}
	}

	public static void musicStop() {
		if (!musicOn) return;
		musicOn = false;
		AudioPlayer.stop();
	}

	public static void reloadVideo() {
		if (video != null) video.close();
		video = null;
		videoFile = "";
	}
}
