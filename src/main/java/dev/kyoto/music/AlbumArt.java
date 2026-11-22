package dev.kyoto.music;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.kyoto.module.Modules;
import dev.kyoto.util.Compat;
import net.minecraft.resources.Identifier;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Album covers for the menu player. Spotify gives us the cover directly; for everything else
 * (YouTube, Apple Music, Spotify Free via Windows...) we look the song up on the free iTunes Search API.
 */
public final class AlbumArt {
	private AlbumArt() {}

	public static final Identifier DEFAULT = Identifier.fromNamespaceAndPath("kyoto", "textures/gui/cover_default.png");
	private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(6))
			.followRedirects(HttpClient.Redirect.NORMAL).build();
	private static final Map<String, String> HINTS = new ConcurrentHashMap<>();

	private static volatile Identifier current = null;
	private static volatile String forTrack = null;
	/** Average colour of the cover (used to tint the player). */
	public static volatile int color = 0x2A6B5A;
	private static int counter = 0;

	/** Spotify tells us the cover URL before the song update arrives. */
	public static void hint(String trackId, String url) {
		if (trackId != null && url != null) HINTS.put(trackId, url);
	}

	public static Identifier texture() {
		Identifier c = current;
		return c != null && Music.trackId != null && Music.trackId.equals(forTrack) ? c : DEFAULT;
	}

	static void request(String trackId, String title, String artist, String album) {
		if (!Modules.on("album_art")) return;
		forTrack = trackId;
		Thread t = new Thread(() -> {
			try {
				String url = HINTS.get(trackId);
				if (url == null) url = itunes(title, artist);
				if (url == null || !trackId.equals(forTrack)) return;
				HttpResponse<byte[]> res = HTTP.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10))
						.header("User-Agent", "KyotoClient").GET().build(), HttpResponse.BodyHandlers.ofByteArray());
				if (res.statusCode() != 200) return;
				BufferedImage img = ImageIO.read(new ByteArrayInputStream(res.body()));
				if (img == null) return;
				BufferedImage sq = new BufferedImage(256, 256, BufferedImage.TYPE_INT_ARGB);
				var g2 = sq.createGraphics();
				g2.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
				g2.drawImage(img, 0, 0, 256, 256, null);
				g2.dispose();
				long r = 0, gg = 0, b = 0;
				for (int y = 0; y < 256; y += 8)
					for (int x = 0; x < 256; x += 8) {
						int px = sq.getRGB(x, y);
						r += (px >> 16) & 255;
						gg += (px >> 8) & 255;
						b += px & 255;
					}
				int n = 32 * 32;
				int avg = (int) ((r / n) << 16 | (gg / n) << 8 | (b / n));
				ByteArrayOutputStream out = new ByteArrayOutputStream();
				ImageIO.write(sq, "png", out);
				Identifier id = Identifier.fromNamespaceAndPath("kyoto", "album/cover_" + (counter++ % 4));
				Compat.registerPng(id, out.toByteArray(), () -> {
					if (trackId.equals(forTrack)) {
						current = id;
						color = avg;
					}
				}, true);
			} catch (Exception ignored) {
			}
		}, "Kyoto-AlbumArt");
		t.setDaemon(true);
		t.start();
	}

	private static String itunes(String title, String artist) throws Exception {
		String term = URLEncoder.encode((artist == null ? "" : artist + " ") + title, StandardCharsets.UTF_8);
		HttpResponse<String> res = HTTP.send(HttpRequest.newBuilder(URI.create("https://itunes.apple.com/search?entity=song&limit=1&term=" + term))
				.timeout(Duration.ofSeconds(8)).GET().build(), HttpResponse.BodyHandlers.ofString());
		if (res.statusCode() != 200) return null;
		JsonArray arr = JsonParser.parseString(res.body()).getAsJsonObject().getAsJsonArray("results");
		if (arr == null || arr.isEmpty()) return null;
		JsonObject o = arr.get(0).getAsJsonObject();
		if (!o.has("artworkUrl100")) return null;
		return o.get("artworkUrl100").getAsString().replace("100x100bb", "300x300bb");
	}
}
