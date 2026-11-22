package dev.kyoto.net;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.kyoto.util.Compat;
import net.minecraft.resources.Identifier;

import javax.imageio.ImageIO;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Player faces for menus (Hub, friends, DMs). Always the CURRENT skin: we ask Mojang for the player's skin
 * (not a cached head site), cut the face + hat out of it ourselves, and refresh every few minutes.
 * Your own face refreshes right after you change your skin with Kyoto.
 */
public final class Heads {
	private Heads() {}

	private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(6)).build();
	private record Entry(Identifier tex, long at) {}

	private static final Map<String, Entry> READY = new ConcurrentHashMap<>();
	private static final Map<String, Boolean> LOADING = new ConcurrentHashMap<>();
	private static final Map<String, Integer> VERSION = new ConcurrentHashMap<>();
	private static final long REFRESH_MS = 5 * 60_000L;

	/** Returns the texture if it's loaded (and starts a refresh when it's old), otherwise starts loading and returns null. */
	public static Identifier get(String name) {
		String key = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "");
		if (key.isEmpty()) return null;
		Entry e = READY.get(key);
		if (e == null || System.currentTimeMillis() - e.at() > REFRESH_MS) load(key);
		return e == null ? null : e.tex();
	}

	/** Forget a player's face (e.g. you just changed your skin) so the next draw downloads it again. */
	public static void refresh(String name) {
		String key = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "");
		Entry e = READY.get(key);
		if (e != null) READY.put(key, new Entry(e.tex(), 0)); // keep showing the old one until the new one is ready
		load(key);
	}

	private static void load(String key) {
		if (LOADING.putIfAbsent(key, true) != null) return;
		Thread t = new Thread(() -> {
			try {
				byte[] face = fromMojang(key);
				if (face == null) face = fromHeadSite(key);
				if (face != null) {
					int v = VERSION.merge(key, 1, Integer::sum);
					Identifier tex = Identifier.fromNamespaceAndPath("kyoto", "heads/" + key + "_" + v);
					byte[] png = face;
					Compat.registerPng(tex, png, () -> READY.put(key, new Entry(tex, System.currentTimeMillis())));
				}
			} catch (Exception ignored) {
			} finally {
				LOADING.remove(key);
			}
		}, "Kyoto-Head");
		t.setDaemon(true);
		t.start();
	}

	private static String http(String url) throws Exception {
		HttpRequest req = HttpRequest.newBuilder(URI.create(url)).header("User-Agent", "KyotoClient/3.0").timeout(Duration.ofSeconds(10)).GET().build();
		HttpResponse<String> res = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
		return res.statusCode() == 200 ? res.body() : null;
	}

	/** name -> uuid -> current skin URL (Mojang) -> crop face + hat to a 32x32 PNG */
	/** A player's full 64x64 skin as ARGB pixels (+ slim arms flag), for the 3D previews. Loads in the background; null until ready. */
	public record Skin(int[] argb, boolean slim) {}

	private static final Map<String, Skin> SKINS = new ConcurrentHashMap<>();
	private static final Map<String, Boolean> SKIN_LOADING = new ConcurrentHashMap<>();

	public static Skin skin(String name) {
		String key = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "");
		Skin s = SKINS.get(key);
		if (s != null || SKIN_LOADING.putIfAbsent(key, true) != null) return s;
		Thread t = new Thread(() -> {
			try {
				String p = http("https://api.mojang.com/users/profiles/minecraft/" + key);
				String uuid = JsonParser.parseString(p).getAsJsonObject().get("id").getAsString();
				String prof = http("https://sessionserver.mojang.com/session/minecraft/profile/" + uuid + "?unsigned=true");
				String url = null;
				boolean slim = false;
				for (var el : JsonParser.parseString(prof).getAsJsonObject().getAsJsonArray("properties")) {
					JsonObject o = el.getAsJsonObject();
					if (!"textures".equals(o.get("name").getAsString())) continue;
					JsonObject tex = JsonParser.parseString(new String(Base64.getDecoder().decode(o.get("value").getAsString()), StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonObject("textures");
					if (tex != null && tex.has("SKIN")) {
						JsonObject sk = tex.getAsJsonObject("SKIN");
						url = sk.get("url").getAsString().replace("http://", "https://");
						slim = sk.has("metadata") && sk.getAsJsonObject("metadata").has("model") && "slim".equals(sk.getAsJsonObject("metadata").get("model").getAsString());
					}
				}
				if (url == null) return;
				byte[] png = HTTP.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.ofByteArray()).body();
				java.awt.image.BufferedImage img = ImageIO.read(new java.io.ByteArrayInputStream(png));
				int[] px = new int[64 * 64];
				for (int y = 0; y < 64; y++) for (int x = 0; x < 64; x++) {
					int sy = img.getHeight() == 32 && y >= 32 ? -1 : y;
					px[y * 64 + x] = sy < 0 ? 0 : img.getRGB(x, sy);
				}
				SKINS.put(key, new Skin(px, slim));
			} catch (Throwable ignored) {
			} finally {
				SKIN_LOADING.remove(key);
			}
		}, "Kyoto skin");
		t.setDaemon(true);
		t.start();
		return null;
	}

	private static byte[] fromMojang(String name) {
		try {
			String p = http("https://api.mojang.com/users/profiles/minecraft/" + name);
			if (p == null) return null;
			String uuid = JsonParser.parseString(p).getAsJsonObject().get("id").getAsString();
			String prof = http("https://sessionserver.mojang.com/session/minecraft/profile/" + uuid + "?unsigned=true&t=" + System.currentTimeMillis() / 60000);
			if (prof == null) return null;
			String skinUrl = null;
			for (var el : JsonParser.parseString(prof).getAsJsonObject().getAsJsonArray("properties")) {
				JsonObject o = el.getAsJsonObject();
				if (!"textures".equals(o.get("name").getAsString())) continue;
				JsonObject tex = JsonParser.parseString(new String(Base64.getDecoder().decode(o.get("value").getAsString()), StandardCharsets.UTF_8)).getAsJsonObject();
				JsonObject t = tex.getAsJsonObject("textures");
				if (t != null && t.has("SKIN")) skinUrl = t.getAsJsonObject("SKIN").get("url").getAsString();
			}
			if (skinUrl == null) return null;
			if (skinUrl.startsWith("http://")) skinUrl = "https://" + skinUrl.substring(7);
			HttpResponse<byte[]> img = HTTP.send(HttpRequest.newBuilder(URI.create(skinUrl)).timeout(Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
			if (img.statusCode() != 200) return null;
			return face(img.body());
		} catch (Exception e) {
			return null;
		}
	}

	private static byte[] fromHeadSite(String name) {
		try {
			HttpRequest req = HttpRequest.newBuilder(URI.create("https://mc-heads.net/avatar/" + name + "/32"))
					.header("User-Agent", "KyotoClient/3.0").timeout(Duration.ofSeconds(10)).GET().build();
			HttpResponse<byte[]> res = HTTP.send(req, HttpResponse.BodyHandlers.ofByteArray());
			return res.statusCode() == 200 ? res.body() : null;
		} catch (Exception e) {
			return null;
		}
	}

	/** Face (8,8) with the hat layer (40,8) on top, scaled to 32x32 with sharp pixels. */
	public static byte[] face(byte[] skinPng) throws Exception {
		BufferedImage skin = ImageIO.read(new ByteArrayInputStream(skinPng));
		if (skin == null || skin.getWidth() < 64) return null;
		int s = skin.getWidth() / 64; // HD skins
		BufferedImage out = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
		var g = out.createGraphics();
		g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
		g.drawImage(skin.getSubimage(8 * s, 8 * s, 8 * s, 8 * s).getScaledInstance(32, 32, Image.SCALE_FAST), 0, 0, null);
		g.drawImage(skin.getSubimage(40 * s, 8 * s, 8 * s, 8 * s).getScaledInstance(32, 32, Image.SCALE_FAST), 0, 0, null);
		g.dispose();
		ByteArrayOutputStream bo = new ByteArrayOutputStream();
		ImageIO.write(out, "png", bo);
		return bo.toByteArray();
	}
}
