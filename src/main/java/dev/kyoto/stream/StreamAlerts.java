package dev.kyoto.stream;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.kyoto.config.KyotoConfig;
import dev.kyoto.module.Modules;
import dev.kyoto.util.Anim;
import dev.kyoto.util.Compat;
import dev.kyoto.util.Ui;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.sounds.SoundEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Stream alerts on your screen: donations, YouTube Super Chats, Twitch bits, subs and follows.
 * Works through Streamlabs (free) - it already connects to Twitch, YouTube, Kick, PayPal, etc.
 * You paste your Streamlabs "Socket API token" once (Streamer menu), it's saved locally and never shown.
 */
public final class StreamAlerts {
	private StreamAlerts() {}

	private static final Logger LOG = LoggerFactory.getLogger("kyoto-stream");
	public static volatile String status = "Not connected";

	public record Alert(String kind, String name, String amount, String message, long at, boolean test) {
		/** $50+ donations, $50+ Super Chats, 5000+ bits and raids of 50+ get the big gold alert. */
		public boolean big() {
			String digits = amount.replaceAll("[^0-9.]", "");
			try {
				double v = Double.parseDouble(digits);
				return kind.equals("Bits") ? v >= 5000 : kind.equals("Raid") ? v >= 50 : (kind.equals("Donation") || kind.equals("Super Chat")) && v >= 50;
			} catch (Exception e) {
				return false;
			}
		}
	}

	private static final Deque<Alert> QUEUE = new ArrayDeque<>();
	private static Alert showing = null;
	private static long showingSince = 0;
	private static WebSocket ws = null;
	private static ScheduledExecutorService exec;
	private static volatile long pingMs = 25000;
	private static volatile long lastPing = 0;

	public static void start() {
		if (exec != null) return;
		exec = Executors.newSingleThreadScheduledExecutor(r -> {
			Thread t = new Thread(r, "Kyoto-Stream");
			t.setDaemon(true);
			return t;
		});
		exec.scheduleWithFixedDelay(StreamAlerts::keepAlive, 5, 5, TimeUnit.SECONDS);
	}

	public static boolean hasToken() {
		return !KyotoConfig.get().streamlabsToken.isBlank();
	}

	public static void setToken(String token) {
		KyotoConfig.get().streamlabsToken = token == null ? "" : token.trim();
		KyotoConfig.save();
		disconnect();
	}

	public static void disconnect() {
		if (ws != null) ws.abort();
		ws = null;
		status = hasToken() ? "Reconnecting..." : "Not connected";
	}

	private static void keepAlive() {
		try {
			if (!Modules.on("stream_alerts") || !hasToken()) {
				if (ws != null) disconnect();
				return;
			}
			if (ws == null) {
				connect();
				return;
			}
			if (System.currentTimeMillis() - lastPing > pingMs - 3000) {
				ws.sendText("2", true); // socket.io ping
				lastPing = System.currentTimeMillis();
			}
		} catch (Exception e) {
			LOG.debug("stream keepalive", e);
		}
	}

	private static void connect() {
		status = "Connecting...";
		String token = KyotoConfig.get().streamlabsToken;
		URI uri = URI.create("wss://sockets.streamlabs.com/socket.io/?token=" + java.net.URLEncoder.encode(token, java.nio.charset.StandardCharsets.UTF_8)
				+ "&EIO=3&transport=websocket");
		HttpClient.newHttpClient().newWebSocketBuilder().buildAsync(uri, new WebSocket.Listener() {
			private final StringBuilder buf = new StringBuilder();

			@Override
			public void onOpen(WebSocket socket) {
				ws = socket;
				status = "Connected to Streamlabs";
				socket.request(1);
			}

			@Override
			public CompletionStage<?> onText(WebSocket socket, CharSequence data, boolean last) {
				buf.append(data);
				if (last) {
					handle(buf.toString());
					buf.setLength(0);
				}
				socket.request(1);
				return null;
			}

			@Override
			public CompletionStage<?> onClose(WebSocket socket, int code, String reason) {
				ws = null;
				status = "Disconnected (" + code + ")";
				return null;
			}

			@Override
			public void onError(WebSocket socket, Throwable error) {
				ws = null;
				status = "Couldn't connect - check your token";
			}
		}).exceptionally(e -> {
			status = "Couldn't connect - check your token";
			return null;
		});
	}

	private static void handle(String msg) {
		try {
			if (msg.startsWith("0{")) {
				JsonObject o = JsonParser.parseString(msg.substring(1)).getAsJsonObject();
				if (o.has("pingInterval")) pingMs = o.get("pingInterval").getAsLong();
				return;
			}
			if (!msg.startsWith("42")) return;
			JsonArray arr = JsonParser.parseString(msg.substring(2)).getAsJsonArray();
			if (arr.size() < 2 || !arr.get(1).isJsonObject()) return;
			JsonObject ev = arr.get(1).getAsJsonObject();
			String type = str(ev, "type");
			JsonElement m = ev.get("message");
			if (m == null || !m.isJsonArray()) return;
			for (JsonElement e : m.getAsJsonArray()) {
				if (!e.isJsonObject()) continue;
				JsonObject d = e.getAsJsonObject();
				String name = str(d, "name");
				if (name.isEmpty()) name = str(d, "from");
				switch (type) {
					case "donation" -> push("Donation", name, str(d, "formatted_amount").isEmpty() ? "$" + str(d, "amount") : str(d, "formatted_amount"), str(d, "message"));
					case "superchat" -> push("Super Chat", name, str(d, "displayString"), str(d, "comment"));
					case "bits" -> push("Bits", name, str(d, "amount") + " bits", str(d, "message"));
					case "subscription", "resub" -> push("Subscription", name, str(d, "months").isEmpty() ? "" : str(d, "months") + " months", str(d, "message"));
					case "follow" -> { if (Modules.opt("stream.follows") == 0) push("New follower", name, "", ""); }
					case "raid", "host" -> push("Raid", name, str(d, "raiders").isEmpty() ? "" : str(d, "raiders") + " viewers", "");
					default -> { }
				}
			}
		} catch (Exception e) {
			LOG.debug("stream message", e);
		}
	}

	private static String str(JsonObject o, String k) {
		return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : "";
	}

	public static void push(String kind, String name, String amount, String message) {
		push(kind, name, amount, message, false);
	}

	public static void push(String kind, String name, String amount, String message, boolean test) {
		String msg = message == null ? "" : message;
		if (ContentFilter.isBlocked(msg) || (Modules.opt("stream.messages") == 1)) msg = "";
		msg = ContentFilter.clean(msg);
		String safeName = ContentFilter.isBlocked(name) ? "Someone" : ContentFilter.clean(name);
		synchronized (QUEUE) {
			if (QUEUE.size() < 30) QUEUE.add(new Alert(kind, safeName.isBlank() ? "Someone" : safeName, amount == null ? "" : amount, msg, System.currentTimeMillis(), test));
		}
	}

	/* ---------------- test alerts (work even with Stream alerts OFF and no token) ---------------- */

	private static final String[] NAMES = {"StarGazer", "pixelpanda", "NightOwl", "hef fan club", "cozybuilder", "lumi", "kai", "nova_"};
	private static final String[] MSGS = {"love the stream, gg!", "clutch!!", "first time here, this client looks so clean", "hi from the UK",
			"build a giant sakura tree next", "", "that sky is unreal", "W streamer"};

	private static String any(String[] a) {
		return a[(int) (Math.random() * a.length)];
	}

	/** kind: Donation, Super Chat, Bits, Subscription, New follower, Raid, Big. */
	public static void test(String kind) {
		switch (kind) {
			case "Donation" -> push("Donation", any(NAMES), any(new String[]{"$1.00", "$5.00", "$10.00", "$20.00"}), any(MSGS), true);
			case "Super Chat" -> push("Super Chat", any(NAMES), any(new String[]{"$2.00", "$5.00", "$20.00"}), any(MSGS), true);
			case "Bits" -> push("Bits", any(NAMES), any(new String[]{"100", "500", "1000"}) + " bits", any(MSGS), true);
			case "Subscription" -> push("Subscription", any(NAMES), any(new String[]{"1 months", "3 months", "12 months"}), any(MSGS), true);
			case "New follower" -> push("New follower", any(NAMES), "", "", true);
			case "Raid" -> push("Raid", any(NAMES), any(new String[]{"12", "40", "150"}) + " viewers", "", true);
			case "Big" -> push("Donation", any(NAMES), "$100.00", "keep going, you earned this one", true);
			default -> test();
		}
	}

	/** One of each, back to back. */
	public static void testAll() {
		for (String k : new String[]{"Donation", "Super Chat", "Bits", "Subscription", "New follower", "Raid", "Big"}) test(k);
	}

	/** The old "Test donation" button / "/kyoto testdono" with no arguments. */
	public static void test() {
		test(new String[]{"Donation", "Super Chat", "Bits", "Subscription"}[(int) (Math.random() * 4)]);
	}

	/** /kyoto testdono <amount> [message] */
	public static void testCustom(String amount, String message) {
		String a = amount.startsWith("$") ? amount : "$" + amount;
		push("Donation", Compat.myName(), a, message == null ? "" : message, true);
	}

	/** Skip whatever alert is showing (and everything queued). */
	public static void clear() {
		synchronized (QUEUE) {
			QUEUE.clear();
		}
		showing = null;
	}

	public static boolean busy() {
		return showing != null;
	}

	private static long durationMs() {
		return new long[]{5000, 8000, 12000}[Modules.opt("stream.duration")];
	}

	private static long dur(Alert a) {
		return durationMs() + (a != null && a.big() ? 3000 : 0);
	}

	/** Draws the current alert (call every frame from the HUD). */
	public static void render(GuiGraphicsExtractor g, int w, int h) {
		boolean on = Modules.on("stream_alerts");
		long now = System.currentTimeMillis();
		if (showing == null || now - showingSince > dur(showing)) {
			if (TikTokLive.busy()) { showing = null; return; } // one alert at a time
			synchronized (QUEUE) {
				showing = QUEUE.poll();
				// real alerts only show with Stream alerts ON; test ones always do
				while (showing != null && !on && !showing.test()) showing = QUEUE.poll();
			}
			showingSince = now;
			if (showing != null && Modules.opt("stream.sound") == 0) {
				Compat.sound(SoundEvents.PLAYER_LEVELUP, 0.6f, showing.big() ? 1.0f : 1.6f);
				Compat.sound(SoundEvents.NOTE_BLOCK_CHIME.value(), 0.8f, 1.2f);
				if (showing.big()) Compat.sound(SoundEvents.PLAYER_LEVELUP, 0.5f, 0.8f);
			}
		}
		if (showing == null) return;
		Alert al = showing;
		float age = (now - showingSince) / (float) dur(al);
		float in = Anim.easeOutBack(Anim.clamp01((now - showingSince) / 450f));
		float out = 1 - Anim.easeInCubic(Anim.clamp01((now - showingSince - dur(al) + 400) / 400f));
		float a = Math.min(Anim.clamp01((now - showingSince) / 250f), out);

		int cw = Math.min(w - 40, 260);
		List<String> lines = new java.util.ArrayList<>();
		StringBuilder cur = new StringBuilder();
		for (String word : al.message().split(" ")) {
			String test = cur.isEmpty() ? word : cur + " " + word;
			if (Ui.width(test) > cw - 24 && !cur.isEmpty()) {
				lines.add(cur.toString());
				cur = new StringBuilder(word);
			} else cur = new StringBuilder(test);
		}
		if (!cur.isEmpty()) lines.add(cur.toString());
		while (lines.size() > 3) lines.remove(lines.size() - 1);
		int ch = 46 + lines.size() * 10;
		int pos = Modules.opt("stream.position");
		int x = w / 2 - cw / 2;
		int y = switch (pos) {
			case 1 -> h / 2 - ch / 2 - 40;
			case 2 -> h - ch - 60;
			default -> 26;
		};
		y += Math.round((1 - in) * (pos == 2 ? 30 : -30));
		int accent = Ui.accent();
		int kindCol = switch (al.kind()) {
			case "Super Chat" -> 0xFFCA28;
			case "Bits" -> 0xB794FF;
			case "Subscription" -> 0x6EE7B7;
			case "New follower", "Raid" -> 0x7FD8FF;
			default -> accent;
		};
		boolean big = al.big();
		if (big) kindCol = 0xFFD36B;
		// glow + card
		if (big) Ui.round(g, x - 7, y - 7, x + cw + 7, y + ch + 7, 17, Anim.argb(kindCol, (0.12f + 0.08f * (float) Math.sin(now / 160.0)) * a));
		Ui.round(g, x - 3, y - 3, x + cw + 3, y + ch + 3, 13, Anim.argb(kindCol, 0.18f * a));
		Ui.round(g, x, y, x + cw, y + ch, 11, Anim.argb(Ui.GLASS, 0.92f * a));
		Ui.round(g, x + 10, y + 10, x + 34, y + 34, 8, Anim.argb(kindCol, 0.22f * a));
		float bob = (float) Math.sin(now / 180.0) * 1.5f;
		Ui.iconTex(g, al.kind().equals("Super Chat") || al.kind().equals("Donation") ? "gift" : al.kind().equals("Subscription") ? "star" : "heart",
				x + 16, y + 16 + Math.round(bob), 12, Anim.argb(kindCol, a));
		String title = al.name() + (al.amount().isBlank() ? "" : "  ·  " + al.amount());
		Ui.boldScaled(g, Ui.ellipsize(title, Math.round((cw - 50) / 1.15f)), x + 42, y + 11, 1.15f, Anim.argb(Ui.INK, a));
		Ui.small(g, (big ? "BIG " : "") + al.kind().toUpperCase(), x + 42, y + 25, Anim.argb(kindCol, a));
		if (al.test()) {
			int tw = Ui.width("TEST");
			Ui.round(g, x + cw - tw - 18, y + 9, x + cw - 8, y + 21, 6, Anim.argb(Ui.INK, 0.12f * a));
			Ui.small(g, "TEST", x + cw - tw - 13, y + 11, Anim.argb(Ui.MUTED, a));
		}
		for (int i = 0; i < lines.size(); i++) Ui.text(g, lines.get(i), x + 12, y + 40 + i * 10, Anim.argb(Ui.INK, a));
		// time bar
		g.fill(x + 10, y + ch - 4, x + cw - 10, y + ch - 3, Anim.argb(Ui.INK, 0.1f * a));
		g.fill(x + 10, y + ch - 4, x + 10 + Math.round((cw - 20) * (1 - age)), y + ch - 3, Anim.argb(kindCol, a));
		// sparkles
		for (int i = 0; i < (big ? 18 : 6); i++) {
			double t = now / 600.0 + i;
			int sx = x + (int) ((Math.sin(t * 1.3 + i) * 0.5 + 0.5) * cw), sy = y - 4 + (int) ((t * 20 + i * 13) % (ch + 8));
			g.fill(sx, sy, sx + 1, sy + 1, Anim.argb(Ui.INK, 0.6f * a));
		}
	}
}
