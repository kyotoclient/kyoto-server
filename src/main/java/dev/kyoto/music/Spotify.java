package dev.kyoto.music;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import dev.kyoto.config.KyotoConfig;
import dev.kyoto.hud.Notifications;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Spotify connection using the official Web API (PKCE login - no client secret needed).
 * Polls "currently playing" every 1.5 seconds on a background thread and asks Lyrics for synced lyrics.
 *
 * Setup: developer.spotify.com/dashboard -> Create app -> Redirect URI: http://127.0.0.1:43821/callback
 * -> tick "Web API" -> copy Client ID -> /kyoto spotify setup <id> -> /kyoto spotify login
 */
public final class Spotify {
	private Spotify() {}

	private static final Logger LOG = LoggerFactory.getLogger("kyoto-spotify");
	public static final int PORT = 43821;
	public static final String REDIRECT = "http://127.0.0.1:" + PORT + "/callback";
	private static final String SCOPES = "user-read-currently-playing user-read-playback-state user-modify-playback-state";

	private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(6)).build();
	private static ScheduledExecutorService exec;

	private static volatile String accessToken = null;
	private static volatile long accessExpires = 0;
	private static volatile String status = "Not connected - /kyoto spotify setup";
	private static HttpServer callbackServer;

	/* ---------------- current playback (read by the HUD) ---------------- */

	public static volatile String trackId = null;
	public static volatile String title = "";
	public static volatile String artist = "";
	public static volatile String album = "";
	public static volatile long durationMs = 0;
	private static volatile long progressMs = 0;
	private static volatile long progressAt = 0;
	public static volatile boolean playing = false;

	/** Song position right now, smoothly interpolated between polls. */
	public static long positionMs() {
		if (trackId == null) return 0;
		long p = progressMs + (playing ? System.currentTimeMillis() - progressAt : 0);
		return Math.min(p, durationMs);
	}

	public static boolean connected() {
		return !KyotoConfig.get().spotifyRefreshToken.isBlank();
	}

	public static String statusLine() {
		return status;
	}

	public static void start() {
		if (exec != null) return;
		exec = Executors.newSingleThreadScheduledExecutor(r -> {
			Thread t = new Thread(r, "Kyoto-Spotify");
			t.setDaemon(true);
			return t;
		});
		exec.scheduleWithFixedDelay(Spotify::poll, 2, 1500, TimeUnit.MILLISECONDS);
		if (connected()) status = "Connected";
	}

	/* ---------------- login (PKCE) ---------------- */

	public static String beginLogin() throws Exception {
		String clientId = KyotoConfig.get().spotifyClientId;
		if (clientId.isBlank()) throw new IllegalStateException("Run /kyoto spotify setup <clientId> first");

		byte[] buf = new byte[48];
		new SecureRandom().nextBytes(buf);
		String verifier = Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
		byte[] hash = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
		String challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(hash);

		if (callbackServer != null) callbackServer.stop(0);
		callbackServer = HttpServer.create(new InetSocketAddress("127.0.0.1", PORT), 0);
		callbackServer.createContext("/callback", ex -> {
			String q = ex.getRequestURI().getQuery();
			String code = param(q, "code");
			String error = param(q, "error");
			String html;
			if (code != null) {
				try {
					exchange("grant_type=authorization_code&code=" + enc(code) + "&redirect_uri=" + enc(REDIRECT)
							+ "&client_id=" + enc(clientId) + "&code_verifier=" + enc(verifier));
					html = page("Connected to Spotify!", "You can close this tab and go back to Minecraft.");
					status = "Connected";
					Minecraft.getInstance().execute(() ->
							Notifications.push("Spotify connected", "Play a song - lyrics will appear", 0x1ED760, 5000, null, "♫"));
				} catch (Exception e) {
					LOG.warn("Spotify token exchange failed", e);
					html = page("Login failed", e.getMessage());
					status = "Login failed: " + e.getMessage();
				}
			} else {
				html = page("Login cancelled", String.valueOf(error));
			}
			byte[] out = html.getBytes(StandardCharsets.UTF_8);
			ex.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
			ex.sendResponseHeaders(200, out.length);
			ex.getResponseBody().write(out);
			ex.close();
			new Thread(() -> {
				try {
					Thread.sleep(500);
				} catch (InterruptedException ignored) {
				}
				if (callbackServer != null) callbackServer.stop(0);
				callbackServer = null;
			}).start();
		});
		callbackServer.start();
		status = "Waiting for browser login...";

		return "https://accounts.spotify.com/authorize?response_type=code&client_id=" + enc(clientId)
				+ "&scope=" + enc(SCOPES) + "&redirect_uri=" + enc(REDIRECT)
				+ "&code_challenge_method=S256&code_challenge=" + challenge;
	}

	public static void logout() {
		KyotoConfig.get().spotifyRefreshToken = "";
		KyotoConfig.save();
		accessToken = null;
		trackId = null;
		playing = false;
		status = "Not connected";
		Music.clear();
	}

	private static void exchange(String body) throws Exception {
		HttpRequest req = HttpRequest.newBuilder(URI.create("https://accounts.spotify.com/api/token"))
				.header("Content-Type", "application/x-www-form-urlencoded")
				.POST(HttpRequest.BodyPublishers.ofString(body))
				.timeout(Duration.ofSeconds(10)).build();
		HttpResponse<String> res = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
		if (res.statusCode() != 200) throw new IllegalStateException("Spotify said " + res.statusCode() + ": " + res.body());
		JsonObject j = JsonParser.parseString(res.body()).getAsJsonObject();
		accessToken = j.get("access_token").getAsString();
		accessExpires = System.currentTimeMillis() + j.get("expires_in").getAsLong() * 1000L - 30_000;
		if (j.has("refresh_token")) {
			KyotoConfig.get().spotifyRefreshToken = j.get("refresh_token").getAsString();
			KyotoConfig.save();
		}
	}

	private static boolean ensureToken() {
		if (accessToken != null && System.currentTimeMillis() < accessExpires) return true;
		String refresh = KyotoConfig.get().spotifyRefreshToken;
		String clientId = KyotoConfig.get().spotifyClientId;
		if (refresh.isBlank() || clientId.isBlank()) return false;
		try {
			exchange("grant_type=refresh_token&refresh_token=" + enc(refresh) + "&client_id=" + enc(clientId));
			return true;
		} catch (Exception e) {
			status = "Reconnect needed: /kyoto spotify login";
			LOG.warn("Spotify refresh failed", e);
			return false;
		}
	}

	/* ---------------- polling ---------------- */

	private static void poll() {
		try {
			if (!connected() || Music.wanted() == 2 || !ensureToken()) return;
			HttpRequest req = HttpRequest.newBuilder(URI.create("https://api.spotify.com/v1/me/player/currently-playing"))
					.header("Authorization", "Bearer " + accessToken)
					.timeout(Duration.ofSeconds(6)).GET().build();
			HttpResponse<String> res = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
			if (res.statusCode() == 401) {
				accessToken = null;
				return;
			}
			if (res.statusCode() == 204 || res.body() == null || res.body().isBlank()) {
				playing = false;
				Music.stopped("Spotify");
				status = "Connected - nothing playing";
				return;
			}
			if (res.statusCode() != 200) {
				status = "Spotify error " + res.statusCode();
				return;
			}
			JsonObject j = JsonParser.parseString(res.body()).getAsJsonObject();
			JsonElement itemEl = j.get("item");
			if (itemEl == null || itemEl.isJsonNull()) {
				playing = false;
				return;
			}
			JsonObject item = itemEl.getAsJsonObject();
			String id = item.has("id") && !item.get("id").isJsonNull() ? item.get("id").getAsString() : item.get("name").getAsString();
			StringBuilder artists = new StringBuilder();
			JsonArray arr = item.has("artists") ? item.getAsJsonArray("artists") : new JsonArray();
			for (JsonElement a : arr) {
				if (!artists.isEmpty()) artists.append(", ");
				artists.append(a.getAsJsonObject().get("name").getAsString());
			}
			String albumName = item.has("album") ? item.getAsJsonObject("album").get("name").getAsString() : "";
			try {
				JsonArray imgs = item.getAsJsonObject("album").getAsJsonArray("images");
				if (imgs != null && !imgs.isEmpty()) AlbumArt.hint(id, imgs.get(0).getAsJsonObject().get("url").getAsString());
			} catch (Exception ignored) {
			}

			progressMs = j.has("progress_ms") && !j.get("progress_ms").isJsonNull() ? j.get("progress_ms").getAsLong() : 0;
			progressAt = System.currentTimeMillis();
			playing = j.has("is_playing") && j.get("is_playing").getAsBoolean();
			durationMs = item.get("duration_ms").getAsLong();

			trackId = id;
			title = item.get("name").getAsString();
			artist = artists.toString();
			album = albumName;
			status = (playing ? "Playing: " : "Paused: ") + title;
			String firstArtist = arr.isEmpty() ? "" : arr.get(0).getAsJsonObject().get("name").getAsString();
			Music.update("Spotify", id, title, firstArtist, albumName, durationMs, progressMs, playing, true);
		} catch (Exception e) {
			LOG.debug("Spotify poll failed", e);
		}
	}

	/* ---------------- playback controls (Premium) ---------------- */

	public static void control(String action) {
		if (exec == null) return;
		exec.execute(() -> {
			try {
				if (!ensureToken()) return;
				String url = "https://api.spotify.com/v1/me/player/" + action;
				HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).header("Authorization", "Bearer " + accessToken)
						.timeout(Duration.ofSeconds(6));
				if (action.equals("next") || action.equals("previous")) b.POST(HttpRequest.BodyPublishers.noBody());
				else b.PUT(HttpRequest.BodyPublishers.noBody());
				HTTP.send(b.build(), HttpResponse.BodyHandlers.discarding());
			} catch (Exception e) {
				LOG.debug("Spotify control failed", e);
			}
		});
	}

	/* ---------------- up next (queue) ---------------- */

	public record QItem(String title, String artist) {}

	public static volatile java.util.List<QItem> queue = java.util.List.of();
	public static volatile long queueAt = 0;

	public static void fetchQueue() {
		if (exec == null || !connected()) return;
		exec.execute(() -> {
			try {
				if (!ensureToken()) return;
				HttpRequest req = HttpRequest.newBuilder(URI.create("https://api.spotify.com/v1/me/player/queue"))
						.header("Authorization", "Bearer " + accessToken).timeout(Duration.ofSeconds(6)).GET().build();
				HttpResponse<String> res = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
				if (res.statusCode() != 200) return;
				JsonArray a = JsonParser.parseString(res.body()).getAsJsonObject().getAsJsonArray("queue");
				java.util.List<QItem> out = new java.util.ArrayList<>();
				if (a != null) for (JsonElement e : a) {
					JsonObject t = e.getAsJsonObject();
					String artist = "";
					if (t.has("artists") && t.getAsJsonArray("artists").size() > 0) artist = t.getAsJsonArray("artists").get(0).getAsJsonObject().get("name").getAsString();
					else if (t.has("show")) artist = t.getAsJsonObject("show").get("name").getAsString();
					out.add(new QItem(t.get("name").getAsString(), artist));
					if (out.size() >= 20) break;
				}
				queue = out;
				queueAt = System.currentTimeMillis();
			} catch (Exception e) {
				LOG.debug("Spotify queue failed", e);
			}
		});
	}

	/** Skips forward to song n in the queue (Premium). */
	public static void skipTo(int n) {
		if (exec == null) return;
		for (int i = 0; i <= n; i++) {
			control("next");
		}
		exec.schedule(Spotify::fetchQueue, 1500, TimeUnit.MILLISECONDS);
	}

	/* ---------------- helpers ---------------- */

	private static String enc(String s) {
		return URLEncoder.encode(s, StandardCharsets.UTF_8);
	}

	private static String param(String query, String key) {
		if (query == null) return null;
		for (String part : query.split("&")) {
			int eq = part.indexOf('=');
			if (eq > 0 && part.substring(0, eq).equals(key)) {
				return java.net.URLDecoder.decode(part.substring(eq + 1), StandardCharsets.UTF_8);
			}
		}
		return null;
	}

	private static String page(String title, String msg) {
		return "<html><body style='background:#121418;color:#eee;font-family:sans-serif;display:flex;align-items:center;"
				+ "justify-content:center;height:100vh;margin:0'><div style='text-align:center'><h2>" + title + "</h2><p style='color:#999'>"
				+ msg + "</p></div></body></html>";
	}
}
