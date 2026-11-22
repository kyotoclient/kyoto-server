package dev.kyoto.net;

import dev.kyoto.cosmetic.Skins;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.kyoto.config.KyotoConfig;
import dev.kyoto.cosmetic.Capes;
import dev.kyoto.feature.Waypoints;
import dev.kyoto.hud.Notifications;
import dev.kyoto.module.Modules;
import dev.kyoto.util.Compat;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Talks to YOUR Kyoto server (the small Node.js app in the "kyoto-server" folder).
 * Login is secure: it uses Mojang's session check, the same way real Minecraft servers verify you,
 * so nobody can pretend to be you. Your password/token is never sent to the Kyoto server.
 */
public final class KyotoNet {
	private KyotoNet() {}

	private static final Logger LOG = LoggerFactory.getLogger("kyoto-net");
	private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
	private static ScheduledExecutorService exec;

	public static volatile int online = -1;
	public static volatile String status = "Not set up";
	public static final List<Friend> FRIENDS = new ArrayList<>();
	public static final List<String> INCOMING = new ArrayList<>();
	public static final List<String> OUTGOING = new ArrayList<>();

	/** uuid -> cosmetics of other Kyoto players */
	public static final Map<String, Cosmetic> COSMETICS = new ConcurrentHashMap<>();
	private static final Set<String> asked = ConcurrentHashMap.newKeySet();
	private static final Set<String> onlineFriendsBefore = new HashSet<>();

	public record Friend(String uuid, String name, boolean online, String server, String join) {}

	public record Cosmetic(String uuid, boolean hasCape, String capeType, int accessory, int model, long version) {}

	/** UUIDs of players who use Kyoto Client (for badges). Filled from the server. */
	public static final Set<String> KYOTO_USERS = ConcurrentHashMap.newKeySet();
	public static final Set<String> KYOTO_NAMES = ConcurrentHashMap.newKeySet();
	private static final Map<String, Long> SKIN_VERSIONS = new ConcurrentHashMap<>();

	/** Kyoto+ supporters (cosmetic badge only). */
	public static final Set<String> PLUS_USERS = ConcurrentHashMap.newKeySet();
	public static volatile boolean mePlus = false;
	public static volatile long mePlusUntil = 0;

	public static boolean isPlus(String nameOrUuid) {
		if (nameOrUuid == null) return false;
		if (nameOrUuid.equalsIgnoreCase(Compat.myName())) return mePlus;
		return PLUS_USERS.contains(nameOrUuid) || PLUS_USERS.contains(nameOrUuid.toLowerCase(java.util.Locale.ROOT));
	}

	/** The badge glyph for a player (font kyoto:badge): Kyoto+ gets the gold one. */
	public static String badgeGlyph(String... ids) {
		for (String i : ids) if (isPlus(i)) return "\ue901";
		return "\ue900";
	}

	public static void refreshMe() {
		runAsync(() -> {
			try {
				JsonObject m = json(send("GET", "/me", null, true));
				mePlus = m.has("plus") && m.get("plus").getAsBoolean();
				mePlusUntil = m.has("plusUntil") ? m.get("plusUntil").getAsLong() : 0;
			} catch (Exception ignored) {
			}
		});
	}

	/** Ask the Kyoto server about this player's cosmetics again on the next loop (picks up newly equipped capes). */
	public static void recheck(String uuid) {
		asked.remove(uuid);
	}

	public static boolean isKyoto(String nameOrUuid) {
		if (nameOrUuid == null) return false;
		if (nameOrUuid.equalsIgnoreCase(Compat.myName())) return true;
		return KYOTO_USERS.contains(nameOrUuid) || KYOTO_NAMES.contains(nameOrUuid.toLowerCase(java.util.Locale.ROOT));
	}

	public static void uploadSkin(byte[] png, boolean slim) {
		runAsync(() -> {
			try {
				JsonObject b = new JsonObject();
				if (png == null) b.addProperty("remove", true);
				else b.addProperty("skin", Base64.getEncoder().encodeToString(png));
				b.addProperty("slim", slim);
				send("PUT", "/skin", b, true);
			} catch (Exception e) {
				toast("Skin upload failed", e.getMessage(), Notifications.DANGER);
			}
		});
	}

	public static boolean configured() {
		return !KyotoConfig.get().serverUrl.isBlank();
	}

	public static boolean loggedIn() {
		KyotoConfig c = KyotoConfig.get();
		return configured() && !c.netToken.isBlank() && c.netTokenUuid.equals(String.valueOf(Compat.myUuid()));
	}

	public static void start() {
		if (exec != null) return;
		exec = Executors.newSingleThreadScheduledExecutor(r -> {
			Thread t = new Thread(r, "Kyoto-Net");
			t.setDaemon(true);
			return t;
		});
		exec.scheduleWithFixedDelay(KyotoNet::loop, 3, 15, TimeUnit.SECONDS);
		exec.scheduleWithFixedDelay(KyotoNet::cosmeticsLoop, 5, 6, TimeUnit.SECONDS);
		exec.scheduleWithFixedDelay(KyotoNet::dmLoop, 8, 5, TimeUnit.SECONDS);
	}

	public static void runAsync(Runnable r) {
		if (exec != null) exec.execute(r);
	}

	public static String baseUrl() {
		return base();
	}

	private static String base() {
		String u = KyotoConfig.get().serverUrl.trim();
		return u.endsWith("/") ? u.substring(0, u.length() - 1) : u;
	}

	/* ---------------- login ---------------- */

	public static void login() {
		runAsync(KyotoNet::loginNow);
	}

	private static long lastLoginTry = 0;

	/** Logs in with your Minecraft account (Mojang session check). Called automatically - you never need to press anything. */
	private static void loginNow() {
		lastLoginTry = System.currentTimeMillis();
		{
			try {
				if (!configured()) return;
				status = "Connecting...";
				wake();
				JsonObject ch = json(send("POST", "/auth/challenge", new JsonObject(), false));
				String serverId = ch.get("serverId").getAsString();
				Compat.joinSession(serverId); // tells Mojang "I'm joining serverId" - the Kyoto server checks this
				JsonObject body = new JsonObject();
				body.addProperty("name", Compat.myName());
				body.addProperty("serverId", serverId);
				JsonObject res = json(send("POST", "/auth/verify", body, false));
				KyotoConfig.get().netToken = res.get("token").getAsString();
				KyotoConfig.get().netTokenUuid = String.valueOf(Compat.myUuid());
				KyotoConfig.save();
				status = "Connected";
				LOG.info("Connected to Kyoto as {}", Compat.myName());
				refreshMe();
				dev.kyoto.cosmetic.Kycos.refreshMine();
			} catch (Exception e) {
				status = "Can't reach Kyoto right now (retrying)";
				LOG.debug("Kyoto login failed", e);
			}
		}
	}

	/**
	 * Render's free plan puts the server to sleep after 15 idle minutes and it takes ~30-60 s to wake up.
	 * This waits for it (up to 90 s) so the login right after doesn't time out.
	 */
	private static void wake() {
		try {
			HttpRequest req = HttpRequest.newBuilder(URI.create(base() + "/")).timeout(Duration.ofSeconds(90)).GET().build();
			HTTP.send(req, HttpResponse.BodyHandlers.discarding());
		} catch (Exception ignored) {
		}
	}

	private static int failedLogins = 0;

	/* ---------------- main loop: presence, friends, inbox ---------------- */

	private static void loop() {
		try {
			if (!configured() || !Modules.on("kyoto_network")) return;
			if (!loggedIn()) {
				// automatic: keep trying until it works - quickly at first, then every 30 s
				long wait = failedLogins < 4 ? 10000 : 30000;
				if (System.currentTimeMillis() - lastLoginTry > wait) {
					loginNow();
					failedLogins = loggedIn() ? 0 : failedLogins + 1;
				}
				if (!loggedIn()) return;
			}
			JsonObject pres = new JsonObject();
			String ip = dev.kyoto.feature.Privacy.server(Compat.currentServerIp());
			pres.addProperty("server", Modules.on("share_server") && ip != null ? ip : (ip == null && Minecraft.getInstance().getSingleplayerServer() != null ? "Singleplayer" : ""));
			String realIp = Compat.currentServerIp();
			pres.addProperty("join", Modules.on("friend_join") && realIp != null ? realIp : "");
			pres.addProperty("calls", Compat.voiceChat() && Modules.on("calls"));
			// what you're listening to, for the Spotify widget on your website profile (turn off: Share Music)
			if (Modules.on("share_music") && dev.kyoto.music.Music.active() && dev.kyoto.music.Music.playing && !dev.kyoto.feature.Privacy.on()) {
				pres.addProperty("song", dev.kyoto.music.Music.title);
				pres.addProperty("artist", dev.kyoto.music.Music.artist);
			}
			JsonObject r = json(send("POST", "/presence", pres, true));
			online = r.get("online").getAsInt();
			status = "Connected";

			JsonObject f = json(send("GET", "/friends", null, true));
			dev.kyoto.social.Social.onFriends(f);
			List<Friend> list = new ArrayList<>();
			Set<String> nowOnline = new HashSet<>();
			for (JsonElement e : f.getAsJsonArray("friends")) {
				JsonObject o = e.getAsJsonObject();
				Friend fr = new Friend(o.get("uuid").getAsString(), o.get("name").getAsString(),
						o.get("online").getAsBoolean(), o.has("server") && !o.get("server").isJsonNull() ? o.get("server").getAsString() : "",
						o.has("join") && !o.get("join").isJsonNull() ? o.get("join").getAsString() : "");
				list.add(fr);
				if (fr.online()) nowOnline.add(fr.name());
			}
			List<String> in = strings(f.getAsJsonArray("incoming"));
			List<String> out = strings(f.getAsJsonArray("outgoing"));
			Minecraft.getInstance().execute(() -> {
				if (Modules.on("friend_join_alerts")) {
					for (String n : nowOnline) {
						if (!onlineFriendsBefore.isEmpty() && !onlineFriendsBefore.contains(n)) {
							Notifications.push(n + " is online", "Kyoto friend", Notifications.GOOD, 4000, n, null);
						}
					}
					for (String n : in) {
						if (!INCOMING.contains(n)) Notifications.push("Friend request", n + " wants to be friends (/kyoto friends)", Notifications.INFO, 6000, n, null);
					}
				}
				onlineFriendsBefore.clear();
				onlineFriendsBefore.addAll(nowOnline);
				onlineFriendsBefore.add("~"); // marks "first load done"
				FRIENDS.clear();
				FRIENDS.addAll(list);
				INCOMING.clear();
				INCOMING.addAll(in);
				OUTGOING.clear();
				OUTGOING.addAll(out);
			});

			JsonObject inbox = json(send("GET", "/waypoints/inbox", null, true));
			for (JsonElement e : inbox.getAsJsonArray("waypoints")) {
				JsonObject w = e.getAsJsonObject();
				String from = w.get("from").getAsString();
				Minecraft.getInstance().execute(() -> Waypoints.receive(from, w.get("name").getAsString(),
						w.get("x").getAsInt(), w.get("y").getAsInt(), w.get("z").getAsInt(), w.get("dim").getAsString()));
			}
		} catch (Exception e) {
			status = "Offline (" + e.getClass().getSimpleName() + ")";
			if (e.getMessage() != null && e.getMessage().contains("401")) {
				KyotoConfig.get().netToken = "";
				KyotoConfig.save();
				lastLoginTry = 0; // log straight back in next round
			}
			LOG.debug("Kyoto loop failed", e);
		}
	}

	/* ---------------- DM pop-ups ---------------- */

	/** name -> time of the newest message we already told you about */
	private static final Map<String, Long> DM_SEEN = new ConcurrentHashMap<>();
	private static boolean dmFirst = true;
	public static volatile int unreadDms = 0;

	private static void dmLoop() {
		try {
			if (!loggedIn()) return;
			JsonObject r = json(send("GET", "/dm/threads", null, true));
			int unread = 0;
			for (JsonElement e : r.getAsJsonArray("threads")) {
				JsonObject t = e.getAsJsonObject();
				String name = t.get("name").getAsString();
				long at = t.get("at").getAsLong();
				boolean fromMe = t.get("fromMe").getAsBoolean();
				if (t.has("unread")) unread += t.get("unread").getAsInt();
				Long seen = DM_SEEN.put(name, at);
				if (dmFirst || fromMe || (seen != null && seen >= at)) continue;
				if (dev.kyoto.screen.DmScreen.isOpenWith(name)) continue;
				String last = t.get("last").getAsString();
				if (Modules.on("dm_alerts")) Minecraft.getInstance().execute(() -> {
					Notifications.push(name, last.isEmpty() ? "sent you a picture" : dev.kyoto.social.Emojis.plain(last), Notifications.INFO, 5000, name, null);
					Compat.sound(net.minecraft.sounds.SoundEvents.NOTE_BLOCK_CHIME.value(), 0.5f, 1.6f);
				});
			}
			unreadDms = unread;
			dmFirst = false;
		} catch (Exception e) {
			LOG.debug("dm loop failed", e);
		}
	}

	/* ---------------- cosmetics for players around you ---------------- */

	private static void cosmeticsLoop() {
		try {
			if (!configured()) return;
			var conn = Minecraft.getInstance().getConnection();
			if (conn == null) return;
			JsonArray ids = new JsonArray();
			Map<String, String> names = new HashMap<>();
			for (var info : conn.getOnlinePlayers()) {
				String id = info.getProfile().id().toString();
				names.put(id, Compat.profileName(info.getProfile()));
				if (!asked.contains(id)) ids.add(id);
				if (ids.size() >= 80) break;
			}
			if (ids.isEmpty()) return;
			JsonObject body = new JsonObject();
			body.add("uuids", ids);
			JsonObject res = json(send("POST", "/cosmetics/bulk", body, false));
			for (JsonElement e : ids) asked.add(e.getAsString());
			if (res.has("kyoto")) for (JsonElement e : res.getAsJsonArray("kyoto")) {
				String u = e.getAsString();
				KYOTO_USERS.add(u);
				String n = names.get(u);
				if (n != null) KYOTO_NAMES.add(n.toLowerCase(java.util.Locale.ROOT));
			}
			if (res.has("plus")) for (JsonElement e : res.getAsJsonArray("plus")) {
				String u = e.getAsString();
				PLUS_USERS.add(u);
				String n = names.get(u);
				if (n != null) PLUS_USERS.add(n.toLowerCase(java.util.Locale.ROOT));
			}
			if (res.has("kycos") && res.get("kycos").isJsonObject()) dev.kyoto.cosmetic.Kycos.onVersions(res.getAsJsonObject("kycos"));
			if (!Modules.on("others_cosmetics")) return;
			for (Map.Entry<String, JsonElement> en : res.getAsJsonObject("players").entrySet()) {
				JsonObject o = en.getValue().getAsJsonObject();
				Cosmetic c = new Cosmetic(en.getKey(), o.get("hasCape").getAsBoolean(),
						o.has("capeType") ? o.get("capeType").getAsString() : "png",
						o.get("accessory").getAsInt(), o.has("model") ? o.get("model").getAsInt() : 0, o.get("version").getAsLong());
				Cosmetic old = COSMETICS.put(en.getKey(), c);
				if (o.has("hasSkin") && o.get("hasSkin").getAsBoolean()) {
					long sv = o.has("skinVersion") ? o.get("skinVersion").getAsLong() : 0;
					Long before = SKIN_VERSIONS.put(en.getKey(), sv);
					if (before == null || before != sv) {
						byte[] png = bytes("/skin/" + en.getKey() + "?v=" + sv);
						Skins.loadRemote(en.getKey(), png, o.has("slim") && o.get("slim").getAsBoolean());
					}
				}
				if (c.hasCape() && (old == null || old.version() != c.version())) {
					byte[] img = bytes("/cape/" + en.getKey() + "?v=" + c.version());
					Capes.loadRemote(en.getKey(), img);
				}
			}
		} catch (Exception e) {
			LOG.debug("cosmetics loop failed", e);
		}
	}

	/** Forget cached cosmetics (e.g. after joining a new server). */
	public static void resetCosmeticsCache() {
		asked.clear();
	}

	/* ---------------- actions ---------------- */

	public static void friendRequest(String name) {
		action("/friends/request", "name", name, "Friend request sent to " + name);
	}

	public static void friendAccept(String name) {
		action("/friends/accept", "name", name, "You and " + name + " are now friends");
	}

	public static void friendRemove(String name) {
		action("/friends/remove", "name", name, "Removed " + name);
	}

	public static void sendWaypoint(String to, Waypoints.Waypoint w) {
		runAsync(() -> {
			try {
				JsonObject b = new JsonObject();
				b.addProperty("to", to);
				b.addProperty("name", w.name);
				b.addProperty("x", w.x);
				b.addProperty("y", w.y);
				b.addProperty("z", w.z);
				b.addProperty("dim", w.dimension);
				send("POST", "/waypoints/send", b, true);
				toast("Waypoint sent", w.name + " → " + to, Notifications.GOOD);
			} catch (Exception e) {
				toast("Couldn't send waypoint", e.getMessage(), Notifications.DANGER);
			}
		});
	}

	public static void uploadCosmetics(byte[] cape, String capeType, int accessory) {
		uploadCosmetics(cape, capeType, accessory, 0);
	}

	public static void uploadCosmetics(byte[] cape, String capeType, int accessory, int model) {
		runAsync(() -> {
			try {
				JsonObject b = new JsonObject();
				if (cape != null) {
					b.addProperty("cape", Base64.getEncoder().encodeToString(cape));
					b.addProperty("capeType", capeType);
				} else {
					b.addProperty("removeCape", true);
				}
				b.addProperty("accessory", accessory);
				b.addProperty("model", model);
				send("PUT", "/cosmetics", b, true);
				toast("Cosmetics saved", "Other Kyoto players can see them now", Notifications.GOOD);
			} catch (Exception e) {
				toast("Upload failed", e.getMessage(), Notifications.DANGER);
			}
		});
	}

	private static void action(String path, String key, String value, String okMsg) {
		runAsync(() -> {
			try {
				JsonObject b = new JsonObject();
				b.addProperty(key, value);
				send("POST", path, b, true);
				toast(okMsg, "", Notifications.GOOD);
				loop();
			} catch (Exception e) {
				toast("Failed", e.getMessage(), Notifications.DANGER);
			}
		});
	}

	private static void toast(String t, String b, int c) {
		Minecraft.getInstance().execute(() -> Notifications.push(t, b, c));
	}

	/* ---------------- http ---------------- */

	public static String send(String method, String path, JsonObject body, boolean auth) throws Exception {
		boolean big = body != null && body.toString().length() > 400_000;
		HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base() + path))
				.timeout(Duration.ofSeconds(big ? 180 : 15))
				.header("Content-Type", "application/json")
				.header("User-Agent", "KyotoClient/3.0");
		if (auth) b.header("Authorization", "Bearer " + KyotoConfig.get().netToken);
		HttpRequest.BodyPublisher pub = body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body.toString());
		b.method(method, pub);
		HttpResponse<String> res = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
		if (res.statusCode() >= 400) {
			String msg = res.body();
			try {
				msg = JsonParser.parseString(res.body()).getAsJsonObject().get("error").getAsString();
			} catch (Exception ignored) {
			}
			throw new IllegalStateException(res.statusCode() + " " + msg);
		}
		return res.body();
	}

	public static byte[] bytes(String path) throws Exception {
		HttpRequest req = HttpRequest.newBuilder(URI.create(base() + path)).timeout(Duration.ofSeconds(90)).GET().build();
		return HTTP.send(req, HttpResponse.BodyHandlers.ofByteArray()).body();
	}

	public static byte[] bytesAuth(String path) throws Exception {
		HttpRequest req = HttpRequest.newBuilder(URI.create(base() + path)).timeout(Duration.ofSeconds(60))
				.header("Authorization", "Bearer " + KyotoConfig.get().netToken).GET().build();
		HttpResponse<byte[]> r = HTTP.send(req, HttpResponse.BodyHandlers.ofByteArray());
		if (r.statusCode() >= 400) throw new IllegalStateException("HTTP " + r.statusCode());
		return r.body();
	}

	private static JsonObject json(String s) {
		return JsonParser.parseString(s).getAsJsonObject();
	}

	private static List<String> strings(JsonArray a) {
		List<String> out = new ArrayList<>();
		if (a != null) for (JsonElement e : a) out.add(e.getAsString());
		return out;
	}

	/** The website (served by your Kyoto server). */
	public static String website() {
		return base();
	}

	/**
	 * Gets a one-time 6-digit code that logs you in on the Kyoto website (proves you own this Minecraft account).
	 * open = also open the profile editor in your browser with the code filled in.
	 */
	public static void linkCode(boolean open) {
		if (!loggedIn()) {
			toast("Not connected yet", "Wait a few seconds for Kyoto to connect, then try again", Notifications.WARN);
			return;
		}
		runAsync(() -> {
			try {
				JsonObject res = json(send("POST", "/link/code", new JsonObject(), true));
				String code = res.get("code").getAsString();
				Minecraft.getInstance().execute(() -> {
					Minecraft mc = Minecraft.getInstance();
					try {
						mc.keyboardHandler.setClipboard(code);
					} catch (Throwable ignored) {
					}
					dev.kyoto.util.Compat.addChat(net.minecraft.network.chat.Component.literal(
							"§d[Kyoto] §fWebsite code: §l" + code + "§r§7  (copied, works for 10 minutes)"));
					Notifications.push("Website code: " + code, "Copied. Type it on the Kyoto website to log in", Notifications.GOOD);
					if (open) dev.kyoto.util.Compat.openUrl(base() + "/edit?name=" + enc(dev.kyoto.util.Compat.myName()) + "&code=" + code);
				});
			} catch (Exception e) {
				toast("Couldn't get a code", e.getMessage(), Notifications.DANGER);
			}
		});
	}

	/** Opens your profile editor on the website, already logged in. */
	public static void openProfileEditor() {
		linkCode(true);
	}

	public static String enc(String s) {
		return URLEncoder.encode(s, StandardCharsets.UTF_8);
	}
}
