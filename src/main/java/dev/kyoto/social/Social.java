package dev.kyoto.social;

import dev.kyoto.util.Ui;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.kyoto.hud.Notifications;
import dev.kyoto.net.KyotoNet;
import dev.kyoto.util.Compat;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Kyoto Hub social bits the whole client shares: stickers + reactions (pictures bundled in the mod),
 * your status message, friends' status + streaks, and the notification feed (trades, gifts, replies, mentions, LFG, streaks).
 */
public final class Social {
	private Social() {}

	public static final String[] STICKERS = {"hi", "gg", "lol", "love", "cry", "mad", "wow", "cool", "sleep", "think", "party", "fire", "yes", "no", "w", "l", "clap", "rip", "pog", "brb", "gn", "sus", "flex", "shy"};
	public static final String[] REACTIONS = {"heart", "fire", "lol", "wow", "sad", "up", "gg", "skull"};
	private static final Map<String, Identifier> TEX = new HashMap<>();

	public static Identifier sticker(String id) {
		return TEX.computeIfAbsent("s:" + id, k -> Identifier.fromNamespaceAndPath("kyoto", "textures/stickers/" + id.replaceAll("[^a-z]", "") + ".png"));
	}

	public static Identifier reaction(String id) {
		for (String r : REACTIONS) if (r.equals(id)) return TEX.computeIfAbsent("r:" + id, k -> Identifier.fromNamespaceAndPath("kyoto", "textures/reactions/" + id + ".png"));
		return sticker(id);
	}

	/* ---------------- status ---------------- */

	public static volatile String myMode = "online";
	public static volatile String myText = "";
	public static final String[] MODES = {"online", "away", "busy", "invisible"};

	public static int modeColor(String mode) {
		return switch (mode == null ? "" : mode) {
			case "online" -> 0x3DDC84;
			case "away" -> 0xFFD166;
			case "busy" -> 0xFF5D7A;
			case "invisible" -> 0x8D919C;
			default -> Ui.DIM;
		};
	}

	public static String modeName(String mode) {
		return switch (mode == null ? "" : mode) {
			case "online" -> "Online";
			case "away" -> "Away";
			case "busy" -> "Do not disturb";
			case "invisible" -> "Invisible";
			default -> "Offline";
		};
	}

	/** name (lower case) -> {mode, statusText, streak:{n,today,risk}} from the friends list. */
	public static final Map<String, JsonObject> FRIENDS = new ConcurrentHashMap<>();

	public static void onFriends(JsonObject f) {
		try {
			Map<String, JsonObject> m = new HashMap<>();
			for (JsonElement e : f.getAsJsonArray("friends")) {
				JsonObject o = e.getAsJsonObject();
				m.put(o.get("name").getAsString().toLowerCase(Locale.ROOT), o);
			}
			FRIENDS.clear();
			FRIENDS.putAll(m);
			if (f.has("me") && f.get("me").isJsonObject()) {
				JsonObject me = f.getAsJsonObject("me");
				myMode = me.get("mode").getAsString();
				myText = me.has("text") ? me.get("text").getAsString() : "";
			}
		} catch (Exception ignored) {
		}
	}

	/** Kyoto friends who are online on this server right now. */
	public static java.util.List<String> friendsOn(String host) {
		java.util.List<String> out = new java.util.ArrayList<>();
		if (host == null || host.isEmpty()) return out;
		for (JsonObject o : FRIENDS.values()) {
			String s = o.has("server") && !o.get("server").isJsonNull() ? o.get("server").getAsString().toLowerCase(Locale.ROOT) : "";
			if (!s.isEmpty() && (s.contains(host) || host.contains(s.replaceAll(":\\d+$", "")))) out.add(o.get("name").getAsString());
		}
		return out;
	}

	public static String statusText(String name) {
		JsonObject o = FRIENDS.get(name.toLowerCase(Locale.ROOT));
		return o != null && o.has("statusText") ? o.get("statusText").getAsString() : "";
	}

	public static String mode(String name) {
		JsonObject o = FRIENDS.get(name.toLowerCase(Locale.ROOT));
		return o != null && o.has("mode") ? o.get("mode").getAsString() : "offline";
	}

	public static int streak(String name) {
		JsonObject o = FRIENDS.get(name.toLowerCase(Locale.ROOT));
		if (o == null || !o.has("streak") || !o.get("streak").isJsonObject()) return 0;
		JsonObject s = o.getAsJsonObject("streak");
		return s.has("n") ? s.get("n").getAsInt() : 0;
	}

	public static void setStatus(String mode, String text, int hours) {
		JsonObject b = new JsonObject();
		b.addProperty("mode", mode);
		b.addProperty("text", text == null ? "" : text);
		b.addProperty("hours", hours);
		KyotoNet.runAsync(() -> {
			try {
				JsonObject r = JsonParser.parseString(KyotoNet.send("PUT", "/status", b, true)).getAsJsonObject().getAsJsonObject("status");
				myMode = r.get("mode").getAsString();
				myText = r.get("text").getAsString();
				Compat.runOnClient(() -> Notifications.push("Status: " + modeName(myMode), myText.isEmpty() ? "" : myText, Notifications.GOOD));
			} catch (Exception e) {
				Compat.runOnClient(() -> Notifications.push("Couldn't set your status", msg(e), Notifications.DANGER));
			}
		});
	}

	/* ---------------- notification feed ---------------- */

	private static long noteSince = -1, lastPoll = 0;
	private static volatile boolean polling = false;
	public static volatile int tradeAlerts = 0;

	public static void tick() {
		if (!KyotoNet.loggedIn() || polling || System.currentTimeMillis() - lastPoll < 15000) return;
		lastPoll = System.currentTimeMillis();
		polling = true;
		KyotoNet.runAsync(() -> {
			try {
				JsonObject r = JsonParser.parseString(KyotoNet.send("GET", "/notify?since=" + Math.max(0, noteSince), null, true)).getAsJsonObject();
				if (noteSince < 0) { noteSince = r.get("last").getAsLong(); return; } // don't replay old ones after joining
				for (JsonElement e : r.getAsJsonArray("notes")) {
					JsonObject n = e.getAsJsonObject();
					noteSince = Math.max(noteSince, n.get("id").getAsLong());
					String kind = n.get("kind").getAsString(), text = n.get("text").getAsString();
					boolean important = kind.startsWith("trade") || kind.equals("gift");
					if ("busy".equals(myMode) && !important) continue; // do not disturb
					if (kind.startsWith("trade")) tradeAlerts++;
					String title = switch (kind) {
						case "gift" -> "Gift!";
						case "trade" -> "Trade";
						case "trade_done" -> "Trade done";
						case "mention" -> "You were mentioned";
						case "reply" -> "New reply";
						case "streak" -> "Streak!";
						case "streak_risk" -> "Streak ending";
						case "lfg" -> "Looking For Group";
						case "group" -> "Group chat";
						default -> "Kyoto";
					};
					int col = switch (kind) {
						case "gift", "streak" -> Notifications.MAGIC;
						case "trade", "trade_done" -> Notifications.MONEY;
						case "streak_risk" -> Notifications.WARN;
						default -> Notifications.INFO;
					};
					Minecraft.getInstance().execute(() -> {
						Notifications.push(title, dev.kyoto.stream.StreamFilter.apply(text), col, 6000);
						Compat.sound(net.minecraft.sounds.SoundEvents.NOTE_BLOCK_CHIME.value(), 0.5f, important ? 1.9f : 1.5f);
					});
				}
			} catch (Exception ignored) {
			} finally {
				polling = false;
			}
		});
	}

	public static String msg(Exception e) {
		String m = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
		return m.replaceFirst("^\\d{3} ", "");
	}
}
