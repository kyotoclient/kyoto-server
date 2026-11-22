package dev.kyoto.social;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.kyoto.hud.HudLayout;
import dev.kyoto.hud.Notifications;
import dev.kyoto.module.Modules;
import dev.kyoto.net.KyotoNet;
import dev.kyoto.screens.VideoStream;
import dev.kyoto.util.Anim;
import dev.kyoto.util.Compat;
import dev.kyoto.util.Ui;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;

/**
 * Live Rooms: watch parties. Everyone in a room sees the same video at the same second, with a chat beside it.
 * Works together with the website (Hub > Rooms): people in the browser and in game watch together.
 */
public final class Rooms {
	private Rooms() {}

	public static volatile String roomId = null;
	public static volatile JsonObject room = null;
	public static final List<JsonObject> CHAT = new ArrayList<>();
	public static volatile String error = null;
	public static boolean overlay = true;

	private static long since = 0, lastPoll = 0, receivedAt = 0;
	private static double pos = 0;
	private static boolean playing = false;
	private static String url = "";
	private static VideoStream stream;
	private static String streamKey = "";
	private static long lastDurSent = 0;

	public static boolean inRoom() { return roomId != null; }

	/** Where the video should be right now, in seconds (the same for everyone). */
	public static double position() {
		return playing ? pos + (System.currentTimeMillis() - receivedAt) / 1000.0 : pos;
	}

	public static VideoStream stream() { return stream; }
	public static boolean playing() { return playing; }
	public static String url() { return url; }

	public static String title() {
		JsonObject r = room;
		if (r == null || !r.has("video") || r.get("video").isJsonNull()) return "";
		return r.getAsJsonObject("video").get("title").getAsString();
	}

	public static boolean canControl() {
		JsonObject r = room;
		return r != null && r.has("canControl") && r.get("canControl").getAsBoolean();
	}

	/* ---------------- joining ---------------- */

	public static void join(String id) {
		leave();
		roomId = id;
		CHAT.clear();
		since = 0;
		lastPoll = 0;
		error = null;
		overlay = true;
	}

	public static void leave() {
		String id = roomId;
		roomId = null;
		room = null;
		CHAT.clear();
		stopStream();
		url = "";
		if (id != null && KyotoNet.loggedIn()) {
			JsonObject b = new JsonObject();
			b.addProperty("id", id);
			KyotoNet.runAsync(() -> { try { KyotoNet.send("POST", "/rooms/leave", b, true); } catch (Exception ignored) { } });
		}
	}

	public static void create(String name, boolean friendsOnly, boolean everyone, String firstUrl, java.util.function.Consumer<String> done) {
		JsonObject b = new JsonObject();
		b.addProperty("name", name);
		b.addProperty("privacy", friendsOnly ? "friends" : "public");
		b.addProperty("control", everyone ? "everyone" : "host");
		if (firstUrl != null && !firstUrl.isBlank()) b.addProperty("url", firstUrl.trim());
		KyotoNet.runAsync(() -> {
			try {
				JsonObject r = JsonParser.parseString(KyotoNet.send("POST", "/rooms/create", b, true)).getAsJsonObject();
				String id = r.get("id").getAsString();
				Compat.runOnClient(() -> { join(id); done.accept(null); });
			} catch (Exception e) {
				Compat.runOnClient(() -> done.accept(msg(e)));
			}
		});
	}

	public static void control(String action, String key, Object value) {
		String id = roomId;
		if (id == null) return;
		JsonObject b = new JsonObject();
		b.addProperty("id", id);
		b.addProperty("action", action);
		if (key != null) {
			if (value instanceof Number n) b.addProperty(key, n);
			else b.addProperty(key, String.valueOf(value));
		}
		KyotoNet.runAsync(() -> {
			try {
				KyotoNet.send("POST", "/rooms/control", b, true);
				lastPoll = 0;
			} catch (Exception e) {
				Compat.runOnClient(() -> Notifications.push("Couldn't do that", msg(e), Notifications.DANGER));
			}
		});
	}

	public static void say(String text) {
		String id = roomId;
		if (id == null || text.isBlank()) return;
		JsonObject b = new JsonObject();
		b.addProperty("id", id);
		b.addProperty("text", text);
		KyotoNet.runAsync(() -> {
			try { KyotoNet.send("POST", "/rooms/chat", b, true); lastPoll = 0; }
			catch (Exception e) { Compat.runOnClient(() -> Notifications.push("Couldn't send", msg(e), Notifications.DANGER)); }
		});
	}

	private static String msg(Exception e) {
		String m = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
		return m.replaceFirst("^\\d{3} ", "");
	}

	/* ---------------- syncing ---------------- */

	private static volatile boolean polling = false;

	public static void tick(Minecraft mc) {
		if (roomId == null) return;
		long now = System.currentTimeMillis();
		if (!polling && now - lastPoll > 2000) {
			lastPoll = now;
			polling = true;
			String id = roomId;
			long s = since;
			KyotoNet.runAsync(() -> {
				try {
					JsonObject r = JsonParser.parseString(KyotoNet.send("GET", "/rooms/state?id=" + KyotoNet.enc(id) + "&since=" + s, null, true)).getAsJsonObject();
					Compat.runOnClient(() -> apply(id, r));
				} catch (Exception e) {
					String m = msg(e);
					Compat.runOnClient(() -> {
						error = m;
						if (m.contains("closed")) { Notifications.push("The room closed", "", Notifications.INFO); leave(); }
					});
				} finally {
					polling = false;
				}
			});
		}
		manageStream(mc);
	}

	private static void apply(String id, JsonObject r) {
		if (!id.equals(roomId)) return;
		error = null;
		JsonObject rm = r.getAsJsonObject("room");
		room = rm;
		for (JsonElement e : r.getAsJsonArray("messages")) {
			JsonObject m = e.getAsJsonObject();
			CHAT.add(m);
			since = Math.max(since, m.get("at").getAsLong());
		}
		while (CHAT.size() > 100) CHAT.remove(0);
		if (rm.has("video") && !rm.get("video").isJsonNull()) {
			JsonObject v = rm.getAsJsonObject("video");
			url = v.get("url").getAsString();
			playing = v.get("playing").getAsBoolean();
			pos = v.get("pos").getAsDouble();
		} else {
			url = "";
			playing = false;
			pos = 0;
		}
		receivedAt = System.currentTimeMillis();
	}

	private static void manageStream(Minecraft mc) {
		if (url.isEmpty() || !playing || mc.level == null) {
			stopStream();
			return;
		}
		int quality = new int[]{360, 480, 720, 1080}[Modules.opt("screens.quality")];
		String key = url + "|" + quality;
		if (stream != null && (!key.equals(streamKey) || stream.ended)) stopStream();
		// someone skipped ahead / we fell behind: jump to where everyone else is
		if (stream != null && stream.duration > 0 && !stream.state.startsWith("Load") && Math.abs(stream.position() - position()) > 3.0) stopStream();
		if (stream == null) {
			stream = new VideoStream(url, Rooms::position, quality * 16 / 9, quality);
			streamKey = key;
		}
		stream.volume = new float[]{0.25f, 0.5f, 0.75f, 1f}[Modules.opt("screens.volume")];
		stream.pan = 0;
		if (stream.duration > 0 && System.currentTimeMillis() - lastDurSent > 30000) {
			lastDurSent = System.currentTimeMillis();
			control("duration", "dur", stream.duration);
		}
	}

	private static void stopStream() {
		if (stream != null) stream.stop();
		stream = null;
		streamKey = "";
	}

	/* ---------------- the little player on your screen ---------------- */

	public static void render(GuiGraphicsExtractor g, int w, int h) {
		if (roomId == null || !overlay) return;
		if (Compat.currentScreen() instanceof dev.kyoto.screen.RoomsScreen) return;
		int bw = 208, bh = 117;
		HudLayout.begin(g, "live_room", 0, 0, w - bw - 6, 40, 1f);
		Ui.round(g, -3, -14, bw + 3, bh + 46, 7, Anim.argb(0x000000, 0.6f));
		JsonObject r = room;
		String name = r == null ? "Joining..." : r.get("name").getAsString();
		Ui.round(g, 0, -10, 26, -2, 3, Anim.argb(0xFF4D5E, 0.9f));
		Ui.small(g, "LIVE", 4, -9, 0xFFFFFFFF);
		Ui.small(g, Ui.ellipsize(name + (r != null ? "  ·  " + r.get("viewers").getAsInt() + " watching" : ""), 220), 30, -9, Anim.argb(Ui.INK, 0.9f));
		Identifier tex = stream != null ? stream.texture() : null;
		if (tex != null) Compat.blitTint(g, tex, 0, 0, bw, bh, 0xFFFFFFFF);
		else g.fill(0, 0, bw, bh, 0xFF0B0B0E);
		String st = url.isEmpty() ? "Nothing playing" : !playing ? "Paused" : stream == null ? "Starting..." : stream.state;
		if (!st.isEmpty()) Ui.text(g, st, bw / 2 - Ui.width(st) / 2, bh / 2 - 4, 0xFFE6E8EC);
		double dur = stream != null ? stream.duration : -1;
		g.fill(0, bh + 2, bw, bh + 4, Anim.argb(Ui.INK, 0.15f));
		if (dur > 0) g.fill(0, bh + 2, (int) (bw * Math.min(1, position() / dur)), bh + 4, Anim.argb(Ui.accent(), 1f));
		// last chat lines
		int y = bh + 8;
		for (int i = Math.max(0, CHAT.size() - 3); i < CHAT.size(); i++) {
			JsonObject m = CHAT.get(i);
			boolean sys = m.has("sys") && m.get("sys").getAsBoolean();
			String line = sys ? m.get("text").getAsString() : m.get("name").getAsString() + ": " + m.get("text").getAsString();
			Ui.small(g, Ui.ellipsize(dev.kyoto.stream.StreamFilter.apply(line), 270), 2, y, Anim.argb(sys ? Ui.MUTED : Ui.INK, 1f));
			y += 10;
		}
		HudLayout.end(g, bw, bh + 40);
	}

	public static JsonArray members() {
		JsonObject r = room;
		return r == null ? new JsonArray() : r.getAsJsonArray("members");
	}
}
