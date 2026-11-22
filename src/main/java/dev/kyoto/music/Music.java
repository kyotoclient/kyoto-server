package dev.kyoto.music;

import dev.kyoto.module.Modules;

/**
 * What's playing right now, no matter where it comes from (Spotify API or system media).
 * The lyrics + Now Playing pill only read from here.
 */
public final class Music {
	private Music() {}

	public static volatile String source = "";
	public static volatile String trackId = null;
	public static volatile String title = "";
	public static volatile String artist = "";
	public static volatile String album = "";
	public static volatile long durationMs = 0;
	public static volatile boolean playing = false;
	public static volatile boolean exactPosition = true;
	private static volatile long progressMs = 0;
	private static volatile long progressAt = 0;
	private static volatile long lastUpdate = 0;

	/** 0 auto, 1 spotify, 2 system media */
	public static int wanted() {
		return Modules.opt("music.source");
	}

	public static boolean active() {
		return trackId != null && System.currentTimeMillis() - lastUpdate < 15000;
	}

	public static long positionMs() {
		if (trackId == null) return 0;
		long p = progressMs + (playing ? System.currentTimeMillis() - progressAt : 0);
		return durationMs > 0 ? Math.min(p, durationMs) : p;
	}

	/** Called by a source. Fetches lyrics when the song changes. */
	public static synchronized void update(String src, String id, String t, String a, String al, long dur, long pos, boolean isPlaying, boolean exact) {
		lastUpdate = System.currentTimeMillis();
		boolean changed = trackId == null || !trackId.equals(id);
		source = src;
		durationMs = dur;
		exactPosition = exact;
		if (changed) {
			trackId = id;
			title = t;
			artist = a;
			album = al == null ? "" : al;
			progressMs = pos;
			progressAt = System.currentTimeMillis();
			playing = isPlaying;
			Thread th = new Thread(() -> Lyrics.fetch(id, t, a, al == null ? "" : al, dur), "Kyoto-Lyrics");
			th.setDaemon(true);
			th.start();
			AlbumArt.request(id, t, a, al);
		} else {
			long drift = Math.abs(positionMs() - pos);
			boolean wasPlaying = playing;
			// small differences are just polling jitter - ignoring them keeps the lyrics from stuttering
			if ((exact && drift > 350) || drift > 2500 || isPlaying != wasPlaying) {
				progressMs = pos;
				progressAt = System.currentTimeMillis();
			}
		}
		playing = isPlaying;
	}

	/** Song position the lyrics should use (includes the Sync offset setting). */
	public static long lyricPositionMs() {
		int o = Modules.opt("lyrics.offset"); // index 3 = 0
		return positionMs() + (o - 3) * 200L + 120;
	}

	/** Play / pause / next / previous for whatever is playing. action: toggle, next, previous */
	public static void control(String action) {
		boolean spotifyApi = "Spotify".equals(source) && Spotify.connected();
		if (spotifyApi) {
			if (action.equals("toggle")) Spotify.control(playing ? "pause" : "play");
			else Spotify.control(action);
		} else {
			SystemMedia.control(action);
		}
		if (action.equals("toggle")) {
			// feels instant; the next poll corrects it
			progressMs = positionMs();
			progressAt = System.currentTimeMillis();
			playing = !playing;
		}
	}

	public static void stopped(String src) {
		if (src.equals(source)) playing = false;
	}

	public static void clear() {
		trackId = null;
		playing = false;
		Lyrics.clear();
	}
}
