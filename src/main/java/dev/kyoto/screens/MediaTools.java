package dev.kyoto.screens;

import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Video screens use two free tools:
 *   yt-dlp - turns a YouTube link into a stream address
 *   ffmpeg - decodes the video + sound
 * On Windows Kyoto downloads both (once, ~100 MB) into .minecraft/kyoto/tools after you say yes.
 * On Mac/Linux install them yourself (brew install yt-dlp ffmpeg / sudo apt install yt-dlp ffmpeg).
 */
public final class MediaTools {
	private MediaTools() {}

	private static final Logger LOG = LoggerFactory.getLogger("kyoto-media");
	private static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
	private static final String YTDLP_URL = "https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp.exe";
	private static final String FFMPEG_URL = "https://www.gyan.dev/ffmpeg/builds/ffmpeg-release-essentials.zip";

	public static volatile String status = "";
	public static volatile float progress = -1;
	public static volatile boolean downloading = false;

	public static Path dir() {
		Path p = FabricLoader.getInstance().getGameDir().resolve("kyoto").resolve("tools");
		try {
			Files.createDirectories(p);
		} catch (Exception ignored) {
		}
		return p;
	}

	public static String ytdlp() {
		String p = find("yt-dlp");
		if (p != null) selfUpdate(p);
		return p;
	}

	private static volatile boolean updated = false;

	/** YouTube changes often and old yt-dlp copies get slow or break - update our own copy once per game start. */
	private static void selfUpdate(String exe) {
		if (updated || !exe.startsWith(dir().toAbsolutePath().toString())) return;
		updated = true;
		Thread t = new Thread(() -> {
			try {
				Process p = new ProcessBuilder(exe, "-U").redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
				p.waitFor(60, java.util.concurrent.TimeUnit.SECONDS);
			} catch (Exception ignored) {
			}
		}, "Kyoto-ytdlp-update");
		t.setDaemon(true);
		t.start();
	}

	public static String ffmpeg() {
		return find("ffmpeg");
	}

	public static boolean ready() {
		return ytdlp() != null && ffmpeg() != null;
	}

	public static boolean canAutoInstall() {
		return WINDOWS;
	}

	/** Our own copy first, then whatever is on PATH. */
	private static String find(String name) {
		Path own = dir().resolve(WINDOWS ? name + ".exe" : name);
		if (Files.isRegularFile(own)) return own.toAbsolutePath().toString();
		String path = System.getenv("PATH");
		if (path != null) for (String d : path.split(java.io.File.pathSeparator)) {
			Path c = Path.of(d).resolve(WINDOWS ? name + ".exe" : name);
			if (Files.isRegularFile(c)) return c.toString();
		}
		for (String d : new String[]{"/opt/homebrew/bin", "/usr/local/bin", "/usr/bin"}) {
			Path c = Path.of(d, name);
			if (Files.isRegularFile(c)) return c.toString();
		}
		return null;
	}

	/** Downloads whatever is missing (Windows only). Runs on its own thread. */
	public static void install(Runnable done) {
		if (downloading) return;
		downloading = true;
		Thread t = new Thread(() -> {
			try {
				HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.ALWAYS).connectTimeout(Duration.ofSeconds(15)).build();
				if (ytdlp() == null) {
					status = "Downloading yt-dlp...";
					download(http, YTDLP_URL, dir().resolve("yt-dlp.exe"));
				}
				if (ffmpeg() == null) {
					status = "Downloading ffmpeg (about 100 MB)...";
					Path zip = dir().resolve("ffmpeg.zip");
					download(http, FFMPEG_URL, zip);
					status = "Unpacking ffmpeg...";
					try (ZipInputStream z = new ZipInputStream(Files.newInputStream(zip))) {
						ZipEntry e;
						while ((e = z.getNextEntry()) != null) {
							if (e.getName().endsWith("/bin/ffmpeg.exe")) {
								Files.copy(z, dir().resolve("ffmpeg.exe"), StandardCopyOption.REPLACE_EXISTING);
								break;
							}
						}
					}
					Files.deleteIfExists(zip);
				}
				status = ready() ? "Ready" : "Something went wrong - see logs/latest.log";
			} catch (Exception e) {
				status = "Download failed: " + e.getMessage();
				LOG.warn("media tools download failed", e);
			}
			progress = -1;
			downloading = false;
			if (done != null) net.minecraft.client.Minecraft.getInstance().execute(done);
		}, "Kyoto-MediaTools");
		t.setDaemon(true);
		t.start();
	}

	private static void download(HttpClient http, String url, Path to) throws Exception {
		HttpResponse<InputStream> res = http.send(HttpRequest.newBuilder(URI.create(url)).header("User-Agent", "KyotoClient").GET().build(),
				HttpResponse.BodyHandlers.ofInputStream());
		if (res.statusCode() >= 400) throw new IllegalStateException("HTTP " + res.statusCode());
		long total = res.headers().firstValueAsLong("content-length").orElse(-1);
		Path tmp = to.resolveSibling(to.getFileName() + ".part");
		try (InputStream in = res.body(); OutputStream out = Files.newOutputStream(tmp)) {
			byte[] buf = new byte[1 << 16];
			long got = 0;
			int n;
			while ((n = in.read(buf)) > 0) {
				out.write(buf, 0, n);
				got += n;
				progress = total > 0 ? got / (float) total : -1;
			}
		}
		Files.move(tmp, to, StandardCopyOption.REPLACE_EXISTING);
	}

	/** Only YouTube links are allowed on shared screens. */
	/** Sites screens can play. Kept to big video platforms so shared screens stay safe for everyone. */
	public static final String[] SITES = {"youtube.com", "youtu.be", "music.youtube.com", "twitch.tv", "clips.twitch.tv", "kick.com",
			"vimeo.com", "player.vimeo.com", "dailymotion.com", "dai.ly", "streamable.com"};

	/** The sources you can pick in Tools > Video Screens. */
	public static final String[] SOURCES = {"YouTube", "Twitch", "Kick", "Vimeo", "Other link", "File on my PC"};
	public static final String[] SOURCE_HINTS = {"Paste a YouTube link...", "Twitch channel name or link...", "Kick channel name or link...",
			"Paste a Vimeo link...", "YouTube, Twitch, Kick, Vimeo, Dailymotion or Streamable link...", "Press Choose file..."};

	public static boolean allowedUrl(String url) {
		if (url == null) return false;
		String u = url.trim().toLowerCase(Locale.ROOT);
		if (!u.startsWith("https://")) return false;
		String host = u.substring(8).split("[/?#]")[0];
		if (host.startsWith("www.")) host = host.substring(4);
		if (host.startsWith("m.")) host = host.substring(2);
		for (String site : SITES) if (host.equals(site)) return true;
		return false;
	}

	/** Turns what you typed into a full link: "xqc" with Twitch picked -> https://www.twitch.tv/xqc. */
	public static String normalize(int source, String typed) {
		String t = typed == null ? "" : typed.trim();
		if (t.isEmpty()) return t;
		if (t.startsWith("http://")) t = "https://" + t.substring(7);
		if (!t.startsWith("https://") && t.matches("[A-Za-z0-9_]{2,40}")) {
			if (source == 1) return "https://www.twitch.tv/" + t;
			if (source == 2) return "https://kick.com/" + t;
		}
		if (!t.startsWith("https://") && t.contains(".")) t = "https://" + t;
		return t;
	}

	/** Which source a saved link belongs to (picks the right tab when you reopen the menu). */
	public static int sourceOf(String url) {
		if (url == null || url.isBlank()) return 0;
		String u = url.toLowerCase(Locale.ROOT);
		if (u.startsWith("file:")) return 5;
		if (u.contains("twitch.tv")) return 1;
		if (u.contains("kick.com")) return 2;
		if (u.contains("vimeo.com")) return 3;
		if (u.contains("youtu")) return 0;
		return 4;
	}

	/** Native "open file" window for picking a video on your PC (null if cancelled). */
	public static String pickVideoFile() {
		try {
			Class<?> c = Class.forName("org.lwjgl.util.tinyfd.TinyFileDialogs");
			java.lang.reflect.Method m = c.getMethod("tinyfd_openFileDialog", CharSequence.class, CharSequence.class, org.lwjgl.PointerBuffer.class, CharSequence.class, boolean.class);
			return (String) m.invoke(null, "Pick a video (mp4, mkv, webm, mov)", "", null, null, false);
		} catch (Throwable t) {
			return null;
		}
	}
}
