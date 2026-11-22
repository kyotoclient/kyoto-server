package dev.kyoto.stream;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.kyoto.config.KyotoConfig;
import dev.kyoto.module.Modules;
import dev.kyoto.net.KyotoNet;
import dev.kyoto.util.Anim;
import dev.kyoto.util.Compat;
import dev.kyoto.util.Ui;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.sounds.SoundEvents;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * TikTok LIVE gifts, follows, shares and like milestones as in-game alerts.
 * TikTok has no public LIVE API, so the Kyoto server watches your LIVE by username and the mod polls it.
 * Gift art is Kyoto's own (textures/gui/gifts), not TikTok's.
 */
public final class TikTokLive {
	private TikTokLive() {}

	/** Known gifts: icon file, coin value. Unknown gifts use the gift box. */
	public record GiftInfo(String name, String icon, int coins) {}

	public static final Map<String, GiftInfo> GIFTS = new LinkedHashMap<>();

	private static void gift(String name, String icon, int coins) {
		GIFTS.put(norm(name), new GiftInfo(name, icon, coins));
	}

	/** Other names TikTok uses for the same gift (and names in other languages) -> our gift. */
	private static final Map<String, String> ALIAS = new java.util.HashMap<>();

	/** Lower case, letters/numbers only (keeps non-English letters, so "Роза" works too). */
	public static String norm(String s) {
		return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}+]", "");
	}

	private static void alias(String gift, String... names) {
		for (String n : names) ALIAS.put(norm(n), norm(gift));
	}

	static {
		gift("Rose", "rose", 1);
		gift("TikTok", "note", 1);
		gift("GG", "gg", 1);
		gift("Ice Cream Cone", "ice_cream", 1);
		gift("Heart Me", "heart_me", 1);
		gift("Mini Speaker", "mini_speaker", 1);
		gift("Fire", "fire", 1);
		gift("Chocolate", "chocolate", 1);
		gift("Gingerbread", "gingerbread", 1);
		gift("Dumbbell", "dumbbell", 1);
		gift("Thumbs Up", "thumbs_up", 2);
		gift("Finger Heart", "finger_heart", 5);
		gift("Panda", "panda", 5);
		gift("Mic", "mic", 5);
		gift("Pancakes", "pancakes", 5);
		gift("Wave", "wave", 7);
		gift("Flower", "flower_box", 7);
		gift("Applause", "applause", 9);
		gift("Rosa", "rosa", 10);
		gift("Lollipop", "lollipop", 10);
		gift("Good Night", "good_night", 10);
		gift("Telephone", "telephone", 12);
		gift("Radio", "radio", 15);
		gift("Cool", "cool", 19);
		gift("Laugh", "laugh", 19);
		gift("Perfume", "perfume", 20);
		gift("Plus One", "plus_one", 25);
		gift("Roly-Poly", "roly_poly", 25);
		gift("Congrats", "congrats", 29);
		gift("LOL", "lol", 29);
		gift("Doughnut", "doughnut", 30);
		gift("Adore You", "adore", 49);
		gift("Blooming", "blossom", 49);
		gift("Paper Crane", "paper_crane", 99);
		gift("Throwing Tomatoes", "tomato", 99);
		gift("Hand Hearts", "hand_hearts", 100);
		gift("Confetti", "confetti", 100);
		gift("Crown", "crown", 100);
		gift("Butterfly", "butterfly", 169);
		gift("Sunglasses", "sunglasses", 199);
		gift("Hearts", "hearts", 199);
		gift("Glasses", "glasses", 199);
		gift("Love Lock", "love_lock", 199);
		gift("Rock 'n' Roll", "gift", 299);
		gift("Corgi", "corgi", 299);
		gift("Dancer", "gift", 300);
		gift("Swing", "swing", 399);
		gift("Necklace", "necklace", 400);
		gift("Coral", "coral", 499);
		gift("Money Gun", "money_gun", 500);
		gift("Record Player", "record_player", 600);
		gift("Galaxy", "galaxy", 1000);
		gift("Superstar", "superstar", 2999);
		gift("Lion", "lion", 29999);
		gift("TikTok Universe", "universe", 44999);
		alias("Doughnut", "donut", "пончик");
		alias("Ice Cream Cone", "ice cream", "icecream", "рожок мороженого");
		alias("Finger Heart", "сердце из пальцев");
		alias("Hand Hearts", "hand heart", "сердце в руках");
		alias("Perfume", "духи");
		alias("Rose", "роза");
		alias("Panda", "панда");
		alias("Fire", "огонь");
		alias("Mic", "microphone", "микрофон");
		alias("Thumbs Up", "thumbsup", "палец вверх");
		alias("Applause", "clap", "аплодисменты");
		alias("Wave", "hand wave", "помахать рукой");
		alias("Chocolate", "шоколад");
		alias("Pancakes", "русские блины");
		alias("Gingerbread", "gingerbread man", "имбирный пряник");
		alias("Adore You", "i love you", "обожаю");
		alias("Lollipop", "леденец");
		alias("Blooming", "цветочек", "little flower");
		alias("Good Night", "спокойной ночи");
		alias("Congrats", "congratulations", "поздравляю");
		alias("Radio", "радио");
		alias("Telephone", "phone", "телефон");
		alias("LOL", "хохочу");
		alias("Cool", "прекрасно");
		alias("Laugh", "смеюсь");
		alias("Roly-Poly", "неваляшка");
		alias("Plus One", "+1", "plus1");
		alias("Sunglasses", "солнечные очки");
		alias("Confetti", "конфетти");
		alias("Hearts", "сердечки");
		alias("Coral", "коралл");
		alias("Glasses", "очки");
		alias("Crown", "корона");
		alias("Swing", "качели");
		alias("Necklace", "ожерелье");
		alias("Butterfly", "бабочка");
		alias("Dancer", "танцор");
		alias("Rock 'n' Roll", "rock n roll", "rocknroll", "рок-н-ролл");
		alias("Love Lock", "lock", "замок с ключом");
		alias("Paper Crane", "crane", "журавлик");
		alias("Throwing Tomatoes", "tomato", "tomatoes", "бросить помидоры");
		alias("Mini Speaker", "speaker");
		alias("Record Player", "turntable");
		alias("Dumbbell", "dumbbells", "гантели");
		alias("Flower", "цветок", "flower box");
		alias("TikTok Universe", "universe");
		alias("Ice Cream Cone", "ice cream cone");
	}

	public static GiftInfo find(String name) {
		if (name == null) return null;
		String k = norm(name.replace('_', ' '));
		GiftInfo g = GIFTS.get(k);
		if (g != null) return g;
		String a = ALIAS.get(k);
		if (a != null && GIFTS.containsKey(a)) return GIFTS.get(a);
		for (Map.Entry<String, GiftInfo> e : GIFTS.entrySet()) if (!k.isEmpty() && k.length() >= 4 && (k.contains(e.getKey()) || e.getKey().contains(k))) return e.getValue();
		return null;
	}

	/** Tiers decide colour and how loud the alert is. */
	private static int tier(int totalCoins) {
		return totalCoins >= 5000 ? 3 : totalCoins >= 500 ? 2 : totalCoins >= 100 ? 1 : 0;
	}

	private static final int[] TIER_COL = {0xFF6FA8, 0x7FD8FF, 0xC08BFF, 0xFFD36B};
	private static final String[] TIER_NAME = {"GIFT", "NICE GIFT", "HUGE GIFT", "LEGENDARY GIFT"};

	/** One alert on screen. Gift streaks update {@code count} while they are still showing. */
	public static final class Alert {
		final String type; // gift, follow, share, likes, superfan
		final String user;
		final String gift;
		final String icon;
		final int coinsEach;
		final String key;
		final boolean test;
		volatile int count;
		volatile long bumpAt;
		volatile boolean done;
		long since;

		Alert(String type, String user, String gift, String icon, int coinsEach, int count, String key, boolean done, boolean test) {
			this.type = type; this.user = user; this.gift = gift; this.icon = icon; this.coinsEach = coinsEach;
			this.count = count; this.key = key; this.done = done; this.test = test; this.bumpAt = System.currentTimeMillis();
		}

		int coins() { return coinsEach * Math.max(1, count); }
		int tier() { return type.equals("gift") ? TikTokLive.tier(coins()) : type.equals("superfan") ? 2 : 0; }
	}

	public static volatile String status = "Not connected";
	public static volatile boolean live = false;
	private static final Deque<Alert> QUEUE = new ArrayDeque<>();
	private static final Map<String, Alert> STREAKS = new java.util.concurrent.ConcurrentHashMap<>();
	private static Alert showing;
	private static long since;
	private static long seq = 0;
	private static ScheduledExecutorService exec;
	private static volatile boolean wanted = false;

	public static String username() {
		return KyotoConfig.get().tiktokUser == null ? "" : KyotoConfig.get().tiktokUser;
	}

	public static void start() {
		if (exec != null) return;
		exec = Executors.newSingleThreadScheduledExecutor(r -> {
			Thread t = new Thread(r, "Kyoto-TikTok");
			t.setDaemon(true);
			return t;
		});
		exec.scheduleWithFixedDelay(TikTokLive::poll, 3, 1, TimeUnit.SECONDS);
		if (KyotoConfig.get().tiktokAuto && !username().isBlank()) wanted = true;
	}

	public static void connect(String user) {
		String u = user == null ? "" : user.trim().replaceFirst("^@", "");
		KyotoConfig.get().tiktokUser = u;
		KyotoConfig.get().tiktokAuto = true;
		KyotoConfig.save();
		seq = 0;
		wanted = true;
		live = false;
		status = "Connecting to @" + u + "...";
		KyotoNet.runAsync(TikTokLive::doConnect);
	}

	public static void disconnect() {
		wanted = false;
		live = false;
		KyotoConfig.get().tiktokAuto = false;
		KyotoConfig.save();
		status = "Not connected";
		KyotoNet.runAsync(() -> {
			try { KyotoNet.send("POST", "/tiktok/disconnect", new JsonObject(), true); } catch (Exception ignored) { }
		});
	}

	private static long lastTry = 0;

	private static void doConnect() {
		lastTry = System.currentTimeMillis();
		try {
			JsonObject b = new JsonObject();
			b.addProperty("username", username());
			JsonObject r = JsonParser.parseString(KyotoNet.send("POST", "/tiktok/connect", b, true)).getAsJsonObject();
			if (r.has("ok") && r.get("ok").getAsBoolean()) {
				live = true;
				status = "Connected to @" + username();
			} else {
				live = false;
				status = r.has("error") ? r.get("error").getAsString() : "Couldn't connect";
			}
		} catch (Exception e) {
			live = false;
			status = "Couldn't reach the Kyoto server";
		}
	}

	private static void poll() {
		try {
			if (!wanted) return;
			if (!live) {
				// not live yet (or the LIVE ended): retry every 60s while you want it on
				if (System.currentTimeMillis() - lastTry > 60000) doConnect();
				return;
			}
			JsonObject r = JsonParser.parseString(KyotoNet.send("GET", "/tiktok/events?since=" + seq, null, true)).getAsJsonObject();
			String state = r.has("state") ? r.get("state").getAsString() : "off";
			if (!state.equals("live") && !state.equals("connecting")) {
				live = false;
				status = state.equals("ended") ? "@" + username() + "'s LIVE ended" : "Reconnecting...";
				lastTry = System.currentTimeMillis() - 50000; // retry in ~10s
				return;
			}
			if (!r.has("events")) return;
			for (JsonElement e : r.getAsJsonArray("events")) {
				JsonObject ev = e.getAsJsonObject();
				seq = Math.max(seq, ev.get("seq").getAsLong());
				handle(ev);
			}
		} catch (Exception e) {
			// network blip: keep going
		}
	}

	private static String s(JsonObject o, String k) {
		return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : "";
	}

	private static void handle(JsonObject ev) {
		String type = s(ev, "type");
		JsonObject u = ev.has("user") && ev.get("user").isJsonObject() ? ev.getAsJsonObject("user") : new JsonObject();
		String name = s(u, "name").isBlank() ? s(u, "id") : s(u, "name");
		switch (type) {
			case "gift" -> {
				int count = ev.has("count") ? ev.get("count").getAsInt() : 1;
				boolean done = !ev.has("done") || ev.get("done").getAsBoolean();
				int coins = ev.has("coins") ? ev.get("coins").getAsInt() : 0;
				gift(name, s(u, "id"), s(ev, "gift"), coins, count, done, false);
			}
			case "follow" -> { if (Modules.opt("tiktok.follows") == 0) simple("follow", name, false); }
			case "share" -> { if (Modules.opt("tiktok.shares") == 0) simple("share", name, false); }
			case "superfan" -> simple("superfan", name, false);
			case "likes" -> { if (Modules.opt("tiktok.likes") == 0) simple("likes", String.valueOf(ev.get("count").getAsInt()), false); }
			default -> { }
		}
	}

	private static String clean(String n) {
		if (n == null || n.isBlank() || ContentFilter.isBlocked(n)) return "Someone";
		return ContentFilter.clean(n);
	}

	static void gift(String name, String userId, String giftName, int coinsEach, int count, boolean done, boolean test) {
		GiftInfo info = find(giftName);
		String gname = info != null ? info.name() : (giftName == null || giftName.isBlank() ? "Gift" : giftName);
		int each = coinsEach > 0 ? coinsEach : info != null ? info.coins() : 1;
		if (!test && each * Math.max(1, count) < new int[]{0, 5, 100}[Modules.opt("tiktok.min")]) return;
		String key = (userId.isBlank() ? name : userId) + "|" + gname;
		Alert cur = STREAKS.get(key);
		if (cur != null && !cur.done) { // a streak still going: just bump the counter
			if (count > cur.count) { cur.count = count; cur.bumpAt = System.currentTimeMillis(); }
			cur.done = done;
			if (done) STREAKS.remove(key);
			return;
		}
		Alert a = new Alert("gift", clean(name), gname, info != null ? info.icon() : "gift", each, Math.max(1, count), key, done, test);
		if (!done) STREAKS.put(key, a);
		add(a);
	}

	private static void simple(String type, String name, boolean test) {
		add(new Alert(type, type.equals("likes") ? name : clean(name), "", type.equals("likes") ? "heart_me" : type.equals("share") ? "confetti" : type.equals("superfan") ? "coin" : "note", 0, 1, "", true, test));
	}

	private static void add(Alert a) {
		synchronized (QUEUE) {
			if (QUEUE.size() < 40) QUEUE.add(a);
		}
	}

	/* ---------------- tests ---------------- */

	private static final String[] NAMES = {"StarGazer", "pixelpanda", "NightOwl", "cozybuilder", "lumi", "kai", "nova_", "sakura.dreams"};

	/** /kyoto testdono tiktok <gift> [count]. Returns false if the gift is unknown. */
	public static boolean test(String giftName, int count) {
		GiftInfo g = find(giftName);
		if (g == null && giftName != null && !giftName.isBlank() && !giftName.equalsIgnoreCase("random")) {
			String k = giftName.toLowerCase(Locale.ROOT);
			switch (k) {
				case "follow" -> { simple("follow", any(NAMES), true); return true; }
				case "share" -> { simple("share", any(NAMES), true); return true; }
				case "likes" -> { simple("likes", "10000", true); return true; }
				case "superfan" -> { simple("superfan", any(NAMES), true); return true; }
				case "all" -> { testAll(); return true; }
				default -> { return false; }
			}
		}
		if (g == null) g = GIFTS.values().stream().skip((long) (Math.random() * GIFTS.size())).findFirst().orElseThrow();
		int n = Math.max(1, Math.min(999, count));
		String who = any(NAMES);
		if (n > 1) { // play it like a real streak: the counter climbs
			final GiftInfo gi = g;
			gift(who, "test-" + who, gi.name(), gi.coins(), 1, false, true);
			for (int i = 2; i <= n; i++) {
				final int c = i;
				final boolean last = i == n;
				exec.schedule(() -> gift(who, "test-" + who, gi.name(), gi.coins(), c, last, true), (long) (i - 1) * Math.max(15, 2500 / n), TimeUnit.MILLISECONDS);
			}
		} else gift(who, "test-" + who, g.name(), g.coins(), 1, true, true);
		return true;
	}

	public static void testAll() {
		for (String k : new String[]{"Rose", "Finger Heart", "Doughnut", "Corgi", "Galaxy", "Lion"}) test(k, 1);
		simple("follow", any(NAMES), true);
	}

	private static String any(String[] a) {
		return a[(int) (Math.random() * a.length)];
	}

	public static void clear() {
		synchronized (QUEUE) {
			QUEUE.clear();
		}
		STREAKS.clear();
		showing = null;
	}

	/* ---------------- drawing ---------------- */

	private static long dur(Alert a) {
		long base = new long[]{5000, 8000, 12000}[Modules.opt("stream.duration")];
		return base + a.tier() * 1500L;
	}

	public static boolean busy() {
		return showing != null;
	}

	public static void render(GuiGraphicsExtractor g, int w, int h) {
		long now = System.currentTimeMillis();
		if (showing != null && !showing.done && now - since > dur(showing) - 600) since = now - dur(showing) + 600; // hold during a streak
		if (showing != null && now - showing.bumpAt < 1200 && now - since > dur(showing) - 1500) since = now - dur(showing) + 1500;
		if (showing == null || now - since > dur(showing)) {
			if (StreamAlerts.busy()) { showing = null; return; } // one alert at a time
			synchronized (QUEUE) {
				showing = QUEUE.poll();
			}
			since = now;
			if (showing != null && Modules.opt("stream.sound") == 0) {
				int t = showing.tier();
				Compat.sound(SoundEvents.NOTE_BLOCK_CHIME.value(), 0.8f, 1.4f + t * 0.1f);
				Compat.sound(SoundEvents.AMETHYST_BLOCK_CHIME, 0.9f, 1.2f);
				if (t >= 2) Compat.sound(SoundEvents.PLAYER_LEVELUP, 0.5f, t == 3 ? 0.8f : 1.2f);
				if (t == 3) Compat.sound(SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, 0.4f, 1.0f);
			}
		}
		if (showing == null) return;
		Alert al = showing;
		long age = now - since;
		long d = dur(al);
		float in = Anim.easeOutBack(Anim.clamp01(age / 500f));
		float out = 1 - Anim.easeInCubic(Anim.clamp01((age - d + 450) / 450f));
		float a = Math.min(Anim.clamp01(age / 220f), out);
		int tier = al.tier();
		int col = TIER_COL[tier];
		if (!al.type.equals("gift")) col = al.type.equals("follow") ? 0x7FD8FF : al.type.equals("share") ? 0x6EE7B7 : al.type.equals("superfan") ? 0xFFD36B : 0xFF6FA8;

		String title, sub;
		switch (al.type) {
			case "gift" -> { title = al.user; sub = "sent " + article(al.gift) + al.gift; }
			case "follow" -> { title = al.user; sub = "followed you"; }
			case "share" -> { title = al.user; sub = "shared your LIVE"; }
			case "superfan" -> { title = al.user; sub = "became a Super Fan"; }
			default -> { title = fmt(Integer.parseInt(al.user)) + " likes"; sub = "your LIVE hit a new milestone"; }
		}
		int cw = Math.min(w - 40, 250);
		int ch = 62;
		int pos = Modules.opt("stream.position");
		int x = w / 2 - cw / 2;
		int y = switch (pos) {
			case 1 -> h / 2 - ch / 2 - 40;
			case 2 -> h - ch - 60;
			default -> 26;
		};
		y += Math.round((1 - in) * (pos == 2 ? 30 : -30));

		// aura (bigger for bigger gifts)
		float pulse = 0.5f + 0.5f * (float) Math.sin(now / 170.0);
		if (tier >= 2) Ui.round(g, x - 9, y - 9, x + cw + 9, y + ch + 9, 20, Anim.argb(col, (0.08f + 0.08f * pulse) * a));
		Ui.round(g, x - 3, y - 3, x + cw + 3, y + ch + 3, 15, Anim.argb(col, 0.22f * a));
		Ui.round(g, x, y, x + cw, y + ch, 13, Anim.argb(Ui.GLASS, 0.93f * a));
		// soft colour wash on the left
		for (int i = 0; i < 40; i++) g.fill(x + 6 + i, y + 6, x + 7 + i, y + ch - 6, Anim.argb(col, 0.10f * (1 - i / 40f) * a));

		// gift icon: pops in, bobs, punches on every streak bump
		float bump = 1 - Anim.clamp01((now - al.bumpAt) / 260f);
		float pop = Anim.easeOutBack(Anim.clamp01(age / 420f));
		int size = Math.round((44 + 8 * bump) * pop);
		int bob = Math.round((float) Math.sin(now / 200.0) * 1.5f);
		int icx = x + 32, icy = y + ch / 2 + bob;
		if (size > 2) {
			Ui.round(g, icx - 25, icy - 25, icx + 25, icy + 25, 14, Anim.argb(col, (0.16f + 0.2f * bump) * a));
			Compat.blitTint(g, Ui.tex("textures/gui/gifts/" + al.icon + ".png"), icx - size / 2, icy - size / 2, size, size, Anim.argb(0xFFFFFF, a));
		}

		int tx = x + 64;
		Ui.small(g, al.type.equals("gift") ? TIER_NAME[tier] : al.type.equals("likes") ? "LIKES" : al.type.toUpperCase(Locale.ROOT), tx, y + 10, Anim.argb(col, a));
		if (al.test) {
			int tw = Ui.width("TEST");
			Ui.round(g, x + cw - tw - 18, y + 8, x + cw - 8, y + 20, 6, Anim.argb(Ui.INK, 0.12f * a));
			Ui.small(g, "TEST", x + cw - tw - 13, y + 10, Anim.argb(Ui.MUTED, a));
		}
		int maxText = cw - 64 - (al.type.equals("gift") ? 52 : 12);
		Ui.boldScaled(g, Ui.ellipsize(title, Math.round(maxText / 1.15f)), tx, y + 22, 1.15f, Anim.argb(Ui.INK, a));
		Ui.text(g, Ui.ellipsize(sub, maxText), tx, y + 38, Anim.argb(Ui.INK, a));

		if (al.type.equals("gift")) {
			// combo counter, grows on each bump
			String combo = "x" + al.count;
			float sc = 1.6f + 0.6f * bump;
			int cwid = Math.round(Ui.widthBold(combo) * sc);
			int cx = x + cw - 12 - cwid, cy = y + ch / 2 - 6 + (al.test ? 4 : 0);
			Ui.boldScaled(g, combo, cx + 1, cy + 1, sc, Anim.argb(0x000000, 0.5f * a));
			Ui.boldScaled(g, combo, cx, cy, sc, Anim.argb(Ui.mix(Ui.INK, col, 0.35f + 0.4f * bump), a));
			String c = fmt(al.coins()) + " coins";
			Ui.small(g, c, x + cw - 12 - Ui.widthSmall(c), y + ch - 15, Anim.argb(0xFFD36B, 0.85f * a));
		}

		// time bar
		float left = 1 - Anim.clamp01(age / (float) d);
		g.fill(x + 12, y + ch - 4, x + cw - 12, y + ch - 3, Anim.argb(Ui.INK, 0.08f * a));
		g.fill(x + 12, y + ch - 4, x + 12 + Math.round((cw - 24) * left), y + ch - 3, Anim.argb(col, a));

		// particles: hearts/sparkles rising, more for bigger gifts
		int n = 8 + tier * 10;
		for (int i = 0; i < n; i++) {
			double t = now / 900.0 + i * 0.37;
			double life = t % 1.0;
			int px = x + 10 + (int) (((i * 73) % 100) / 100.0 * (cw - 20) + Math.sin(t * 3 + i) * 6);
			int py = y + ch - (int) (life * (ch + 26));
			float pa = (float) (Math.sin(life * Math.PI)) * a * 0.8f;
			int pc = i % 3 == 0 ? Ui.INK : col;
			int s = (i % 4 == 0) ? 2 : 1;
			g.fill(px, py, px + s, py + s, Anim.argb(pc, pa));
		}
		// legendary: gold rays behind the card edge
		if (tier == 3) {
			for (int i = 0; i < 12; i++) {
				double ang = now / 1400.0 + i * Math.PI / 6;
				int rx = icx + (int) (Math.cos(ang) * 34), ry = icy + (int) (Math.sin(ang) * 34);
				g.fill(rx - 1, ry - 1, rx + 1, ry + 1, Anim.argb(0xFFE9A8, 0.7f * a));
			}
		}
	}

	private static String article(String gift) {
		return gift.isEmpty() ? "" : ("AEIOU".indexOf(Character.toUpperCase(gift.charAt(0))) >= 0 ? "an " : "a ");
	}

	private static String fmt(int n) {
		return n >= 1_000_000 ? String.format(Locale.ROOT, "%.1fM", n / 1e6) : n >= 10_000 ? String.format(Locale.ROOT, "%.1fk", n / 1e3) : String.format(Locale.ROOT, "%,d", n);
	}
}
