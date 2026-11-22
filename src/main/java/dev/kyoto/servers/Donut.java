package dev.kyoto.servers;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.kyoto.config.KyotoConfig;
import dev.kyoto.hud.Notifications;
import dev.kyoto.util.Compat;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;

/**
 * DonutSMP pack: money + shards trackers, AH/orders toasts, bounties, combat timer, spawners, bases, flips, scam guard...
 * All of it reads chat/scoreboard/menus. Nothing runs commands on its own (the only commands sent are the ones YOU click).
 */
public final class Donut {
	private Donut() {}

	public static double multiplier = -1;

	/* ---------------- bounties ---------------- */
	/** player (lower) -> [name, amount, time] */
	public static final Map<String, Object[]> BOUNTIES = new LinkedHashMap<>();
	public static double myBounty = 0;

	/* ---------------- combat ---------------- */
	public static long combatUntil = 0;

	/* ---------------- AFK shards ---------------- */
	public static long lastShardAt = 0, shardInterval = 0, afkSince = 0;
	public static double afkShards = 0;
	private static double afkX, afkZ;

	/* ---------------- orders (yours) ---------------- */
	/** item -> [have, want] from the last time you opened your orders */
	public static final Map<String, int[]> MY_ORDERS = new LinkedHashMap<>();

	/* ---------------- duels ---------------- */
	public static String lastKiller = "";

	/* ---------------- RTP history ---------------- */
	private static long rtpAt = 0;
	private static double rtpFromX, rtpFromZ;

	public static void onChat(String plain, boolean fromPlayer) {
		Matcher m;
		long now = System.currentTimeMillis();
		// money
		if ((m = Packs.match("sold", plain)) != null) {
			double v = Packs.money(m.group(m.groupCount()));
			if (v > 0) ServerData.earnedChat += v;
		}
		if ((m = Packs.match("ah_sold", plain)) != null && Packs.on("donut_ah_toast")) {
			double v = Packs.money(m.group(m.groupCount()));
			String what = m.groupCount() >= 2 ? m.group(2) : "an item";
			if (v > 0) {
				ServerData.earnedChat += v;
				Notifications.push("Sold on the AH", what + " for " + Packs.fmt(v), Notifications.GOOD, 6000, m.group(1), "coin");
				StreamLive.event("sold", what + " for " + Packs.fmt(v));
				Compat.sound(SoundEvents.EXPERIENCE_ORB_PICKUP, 0.7f, 1.2f);
				if (Packs.on("donut_flips")) flip("sell", what, v);
			}
		}
		if ((m = Packs.match("ah_bought", plain)) != null && Packs.on("donut_flips")) {
			double v = Packs.money(m.group(m.groupCount()));
			if (v > 0) flip("buy", m.group(1), v);
		}
		if ((m = Packs.match("order_fill", plain)) != null && Packs.on("donut_order_toast")) {
			String who = m.groupCount() >= 2 ? m.group(1) : null;
			String what = m.groupCount() >= 2 ? m.group(2) : m.group(1);
			Notifications.push("Order filled", what, Notifications.GOOD, 6000, who, "coin");
			StreamLive.event("order", what);
			Compat.sound(SoundEvents.NOTE_BLOCK_BELL.value(), 0.6f, 1.4f);
		}
		if ((m = Packs.match("pay_in", plain)) != null) {
			double v = -1;
			for (int i = 1; i <= m.groupCount(); i++) { double x = Packs.money(m.group(i)); if (x > 0) { v = x; break; } }
			if (v > 0) ServerData.earnedChat += v;
		}
		// multiplier
		if ((m = Packs.match("multiplier", plain)) != null) {
			double v = Packs.money(m.group(1));
			if (v > 0 && v < 100) multiplier = v;
		}
		// bounties
		if ((m = Packs.match("bounty_on_you", plain)) != null) {
			double v = -1;
			for (int i = 1; i <= m.groupCount(); i++) { double x = Packs.money(m.group(i)); if (x > 0) { v = x; break; } }
			if (v > 0) myBounty += v;
			if (Packs.on("donut_bounty_alert")) {
				Notifications.push("Bounty on you!", (v > 0 ? Packs.fmt(v) : "Someone") + " - watch your back", Notifications.WARN, 9000, null, "flag");
				Compat.sound(SoundEvents.WITHER_SPAWN, 0.35f, 1.6f);
			}
			StreamLive.event("bounty", (v > 0 ? Packs.fmt(v) + " bounty" : "A bounty") + " on me!");
		} else if ((m = Packs.match("bounty_any", plain)) != null) {
			String target = m.group(m.groupCount());
			double v = -1;
			for (int i = 1; i <= m.groupCount(); i++) { double x = Packs.money(m.group(i)); if (x > 0) { v = x; break; } }
			if (target != null && v > 0) {
				String k = target.toLowerCase(Locale.ROOT);
				Object[] old = BOUNTIES.get(k);
				double total = old == null ? v : (double) old[1] + v;
				BOUNTIES.remove(k);
				BOUNTIES.put(k, new Object[]{target, total, now});
				while (BOUNTIES.size() > 40) BOUNTIES.remove(BOUNTIES.keySet().iterator().next());
			}
		}
		if ((m = Packs.match("bounty_claim", plain)) != null) {
			BOUNTIES.remove(m.group(m.groupCount()).toLowerCase(Locale.ROOT));
			String me = Compat.myName();
			if (me != null && m.group(m.groupCount()).equalsIgnoreCase(me)) myBounty = 0;
		}
		// combat timer (chat or action bar)
		if ((m = Packs.match("combat_on", plain)) != null) {
			int secs = 15;
			if (m.groupCount() >= 1 && m.group(1) != null) try { secs = Integer.parseInt(m.group(1)); } catch (Exception ignored) { }
			combatUntil = now + secs * 1000L;
		}
		if (Packs.matches("combat_off", plain)) combatUntil = 0;
		// shards (AFK)
		if ((m = Packs.match("shards_gain", plain)) != null) {
			double v = Packs.money(m.group(1));
			if (lastShardAt > 0 && now - lastShardAt > 20000 && now - lastShardAt < 30 * 60000) shardInterval = shardInterval == 0 ? now - lastShardAt : (shardInterval * 2 + (now - lastShardAt)) / 3;
			lastShardAt = now;
			if (afkSince > 0 && v > 0) afkShards += v;
		}
		// scam guard (only messages from other players)
		if (fromPlayer && Packs.on("donut_scam_guard") && Packs.matches("scam", plain)) {
			Compat.addChat(Component.literal("⚠ Kyoto: that message looks like a common scam. Never give first, and never \"double\" money.").withStyle(ChatFormatting.RED));
		}
		// duels
		Matcher d = Packs.match("kill_you", plain);
		if (d != null) {
			ServerData.sessionKills++;
			if (Packs.on("donut_duels") && d.group(1) != null) duel(d.group(1), true);
			StreamLive.event("kill", d.group(1) == null ? "Got a kill" : "Killed " + d.group(1));
		}
	}

	/* ---------------- deaths (called when the death screen opens) ---------------- */

	private static List<ItemStack> lastInv = new ArrayList<>();
	private static long invSnapAt = 0;

	public static void tick(Minecraft mc) {
		if (mc.player == null) return;
		long now = System.currentTimeMillis();
		if (now - invSnapAt > 2000 && mc.player.isAlive()) {
			invSnapAt = now;
			List<ItemStack> l = new ArrayList<>();
			var inv = mc.player.getInventory();
			for (int i = 0; i < inv.getContainerSize(); i++) if (!inv.getItem(i).isEmpty()) l.add(inv.getItem(i).copy());
			lastInv = l;
		}
		// AFK zone: ends when you walk away from where you started
		if (afkSince > 0) {
			double dx = mc.player.getX() - afkX, dz = mc.player.getZ() - afkZ;
			if (dx * dx + dz * dz > 30 * 30) afkSince = 0;
		}
		// RTP history: a big jump within 15 s of /rtp
		if (rtpAt > 0) {
			double dx = mc.player.getX() - rtpFromX, dz = mc.player.getZ() - rtpFromZ;
			if (dx * dx + dz * dz > 300 * 300) {
				rtpAt = 0;
				JsonObject o = Packs.read("donut_places");
				JsonArray a = o.has("rtp") ? o.getAsJsonArray("rtp") : new JsonArray();
				JsonObject p = new JsonObject();
				p.addProperty("x", mc.player.getBlockX());
				p.addProperty("y", mc.player.getBlockY());
				p.addProperty("z", mc.player.getBlockZ());
				p.addProperty("dim", Compat.dimensionId());
				p.addProperty("at", now);
				a.add(p);
				while (a.size() > 20) a.remove(0);
				o.add("rtp", a);
				Packs.write("donut_places", o);
			} else if (now - rtpAt > 15000) rtpAt = 0;
		}
		// spawner reminder
		if (Packs.on("donut_spawner_remind") && now - lastRemind > 60000) {
			lastRemind = now;
			JsonObject o = Packs.read("donut_places");
			if (o.has("spawners")) {
				double hours = KyotoConfig.get().packNums.getOrDefault("spawner_hours", 6.0);
				for (var e : o.getAsJsonArray("spawners")) {
					JsonObject s = e.getAsJsonObject();
					long emptied = s.has("emptied") ? s.get("emptied").getAsLong() : 0;
					boolean nagged = s.has("nagged") && s.get("nagged").getAsLong() > emptied;
					if (!nagged && emptied > 0 && now - emptied > hours * 3600000) {
						s.addProperty("nagged", now);
						Packs.write("donut_places", o);
						Notifications.push("Spawners are probably full", s.get("name").getAsString() + " (" + s.get("type").getAsString() + ")", Notifications.WARN, 8000, null, "tools");
						break;
					}
				}
			}
		}
	}

	private static long lastRemind = 0;

	public static void onDeath(Minecraft mc, String deathMessage) {
		ServerData.sessionDeaths++;
		StreamLive.event("death", lastKiller == null || lastKiller.isEmpty() ? "Died" : "Killed by " + lastKiller);
		if (!Packs.on("donut_death_recap") || mc.player == null) return;
		double value = 0;
		int known = 0;
		for (ItemStack s : lastInv) {
			String k = Market.key(s);
			double v = Market.median(k, "ah");
			if (v <= 0) v = Market.value(k, "sell");
			if (v > 0) { value += v * s.getCount(); known++; }
		}
		String where = mc.player.getBlockX() + " " + mc.player.getBlockY() + " " + mc.player.getBlockZ();
		String killer = lastKiller.isEmpty() ? "" : " by " + lastKiller;
		Notifications.push("You died" + killer, (value > 0 ? "Lost about " + Packs.fmt(value) + " of items · " : "") + where + " (waypoint saved)",
				Notifications.WARN, 10000, lastKiller.isEmpty() ? null : lastKiller, "flag");
		if (Packs.on("donut_duels") && !lastKiller.isEmpty()) duel(lastKiller, false);
		lastKiller = "";
	}

	/** Remembers who killed you from the death message. */
	public static void deathMessage(String plain) {
		String me = Compat.myName();
		if (me == null) return;
		Matcher m = java.util.regex.Pattern.compile("(?i)^\\W*" + java.util.regex.Pattern.quote(me) + " (?:was|got) [a-z ]+? by (\\w+)").matcher(plain);
		if (m.find()) lastKiller = m.group(1);
	}

	/* ---------------- duel stats ---------------- */

	private static void duel(String opponent, boolean won) {
		JsonObject o = Packs.read("donut_duels");
		String k = opponent.toLowerCase(Locale.ROOT);
		JsonObject e = o.has(k) ? o.getAsJsonObject(k) : new JsonObject();
		e.addProperty("name", opponent);
		e.addProperty(won ? "w" : "l", (e.has(won ? "w" : "l") ? e.get(won ? "w" : "l").getAsInt() : 0) + 1);
		e.addProperty("at", System.currentTimeMillis());
		o.add(k, e);
		Packs.write("donut_duels", o);
	}

	public static List<String> duelList() {
		JsonObject o = Packs.read("donut_duels");
		List<JsonObject> l = new ArrayList<>();
		for (String k : o.keySet()) l.add(o.getAsJsonObject(k));
		l.sort((a, b) -> Long.compare(b.get("at").getAsLong(), a.get("at").getAsLong()));
		List<String> out = new ArrayList<>();
		for (JsonObject e : l.subList(0, Math.min(10, l.size()))) {
			int w = e.has("w") ? e.get("w").getAsInt() : 0, lo = e.has("l") ? e.get("l").getAsInt() : 0;
			out.add(e.get("name").getAsString() + "  " + w + "W / " + lo + "L");
		}
		return out;
	}

	/* ---------------- flips ---------------- */

	public static String flip(String kind, String item, double price) {
		JsonObject o = Packs.read("donut_flips");
		JsonArray a = o.has("log") ? o.getAsJsonArray("log") : new JsonArray();
		JsonObject e = new JsonObject();
		e.addProperty("kind", kind);
		e.addProperty("item", item.replaceAll("§.", "").trim());
		e.addProperty("price", price);
		e.addProperty("at", System.currentTimeMillis());
		a.add(e);
		while (a.size() > 500) a.remove(0);
		o.add("log", a);
		Packs.write("donut_flips", o);
		return (kind.equals("buy") ? "Logged buy: " : "Logged sale: ") + item + " " + Packs.fmt(price) + ". Profit so far: " + Packs.fmt(flipProfit());
	}

	public static double flipProfit() {
		JsonObject o = Packs.read("donut_flips");
		double p = 0;
		if (o.has("log")) for (var e : o.getAsJsonArray("log")) {
			JsonObject x = e.getAsJsonObject();
			p += x.get("kind").getAsString().equals("sell") ? x.get("price").getAsDouble() : -x.get("price").getAsDouble();
		}
		return p;
	}

	/** /kyoto profit <in item> <in amount> <out item> <out amount> */
	public static String profit(String in, int inAmt, String out, int outAmt) {
		String ki = Market.normalize(in), ko = Market.normalize(out);
		double cost = best(ki), worth = best(ko);
		if (cost <= 0) return "No price for " + in + " yet - open /ah or /orders and look at it once";
		if (worth <= 0) return "No price for " + out + " yet - open /ah or /orders and look at it once";
		double c = cost * inAmt, w = worth * outAmt;
		return inAmt + " " + Compat.pretty(ki) + " (" + Packs.fmt(c) + ") -> " + outAmt + " " + Compat.pretty(ko) + " (" + Packs.fmt(w) + ") = "
				+ (w >= c ? "+" : "") + Packs.fmt(w - c) + (c > 0 ? String.format(Locale.ROOT, " (%.0f%%)", (w - c) / c * 100) : "");
	}

	private static double best(String k) {
		double ah = Market.median(k, "ah"), ord = Market.median(k, "order"), sell = Market.value(k, "sell");
		return Math.max(ah, Math.max(ord, sell * Math.max(1, multiplier)));
	}

	/* ---------------- spawners + bases ---------------- */

	public static String addSpawner(String type, int stack, String base) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) return "Join a server first";
		JsonObject o = Packs.read("donut_places");
		JsonArray a = o.has("spawners") ? o.getAsJsonArray("spawners") : new JsonArray();
		JsonObject s = new JsonObject();
		s.addProperty("name", base);
		s.addProperty("type", type);
		s.addProperty("stack", stack);
		s.addProperty("x", mc.player.getBlockX());
		s.addProperty("y", mc.player.getBlockY());
		s.addProperty("z", mc.player.getBlockZ());
		s.addProperty("emptied", System.currentTimeMillis());
		a.add(s);
		o.add("spawners", a);
		Packs.write("donut_places", o);
		return "Saved " + stack + "x " + type + " spawner at " + base;
	}

	/** Opening a spawner's menu counts as emptying it (for the reminder). */
	public static void openedSpawner(String title) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) return;
		JsonObject o = Packs.read("donut_places");
		if (!o.has("spawners")) return;
		boolean ch = false;
		for (var e : o.getAsJsonArray("spawners")) {
			JsonObject s = e.getAsJsonObject();
			double dx = s.get("x").getAsInt() - mc.player.getX(), dz = s.get("z").getAsInt() - mc.player.getZ();
			if (dx * dx + dz * dz < 40 * 40) {
				s.addProperty("emptied", System.currentTimeMillis());
				ch = true;
			}
		}
		if (ch) Packs.write("donut_places", o);
	}

	public static String addBase(String name) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) return "Join a server first";
		JsonObject o = Packs.read("donut_places");
		JsonObject b = o.has("bases") ? o.getAsJsonObject("bases") : new JsonObject();
		JsonObject p = new JsonObject();
		p.addProperty("x", mc.player.getBlockX());
		p.addProperty("y", mc.player.getBlockY());
		p.addProperty("z", mc.player.getBlockZ());
		p.addProperty("dim", Compat.dimensionId());
		b.add(name, p);
		o.add("bases", b);
		Packs.write("donut_places", o);
		return "Saved base \"" + name + "\". It's private and hidden while Streamer Mode is on.";
	}

	public static JsonObject places() {
		return Packs.read("donut_places");
	}

	/* ---------------- commands you send ---------------- */

	/** @return false to stop the command (pay confirm / combat warning). */
	public static boolean onCommand(String cmd) {
		String c = cmd.toLowerCase(Locale.ROOT).trim();
		if (c.startsWith("rtp")) {
			Minecraft mc = Minecraft.getInstance();
			if (mc.player != null) { rtpAt = System.currentTimeMillis(); rtpFromX = mc.player.getX(); rtpFromZ = mc.player.getZ(); }
		}
		if (c.startsWith("warp afk") || c.equals("afk")) {
			Minecraft mc = Minecraft.getInstance();
			if (mc.player != null) {
				// wait for the teleport, then remember where the AFK spot is
				new Thread(() -> {
					try { Thread.sleep(4000); } catch (InterruptedException ignored) { }
					mc.execute(() -> { if (mc.player != null) { afkX = mc.player.getX(); afkZ = mc.player.getZ(); afkSince = System.currentTimeMillis(); afkShards = 0; } });
				}).start();
			}
		}
		return true;
	}

	/* ---------------- nametag + chat extras ---------------- */

	/** "$5M bounty" over heads. */
	public static Component nametag(Entity e, Component current) {
		if (!(e instanceof Player p) || !Packs.on("donut_bounty_tags")) return null;
		Object[] b = BOUNTIES.get(String.valueOf(Compat.profileName(p.getGameProfile())).toLowerCase(Locale.ROOT));
		if (b == null) return null;
		MutableComponent c = current.copy();
		c.append(Component.literal("  ⚑ " + Packs.fmt((double) b[1])).withStyle(ChatFormatting.GOLD));
		return c;
	}

	/** Bounty / baltop info after names in chat. */
	public static Component richNames(Component msg) {
		if (!Packs.on("donut_rich_chat") || BOUNTIES.isEmpty()) return msg;
		String plain = msg.getString();
		for (Object[] b : BOUNTIES.values()) {
			String n = (String) b[0];
			if (plain.contains(n + ":") || plain.contains(n + " »") || plain.contains("<" + n + ">")) {
				return msg.copy().append(Component.literal("  ⚑" + Packs.fmt((double) b[1])).withStyle(ChatFormatting.GOLD));
			}
		}
		return msg;
	}

	public static List<Object[]> topBounties(int n) {
		List<Object[]> l = new ArrayList<>(BOUNTIES.values());
		l.sort((a, b) -> Double.compare((double) b[1], (double) a[1]));
		return l.subList(0, Math.min(n, l.size()));
	}

	public static void reset() {
		BOUNTIES.clear();
		myBounty = 0;
		combatUntil = 0;
		afkSince = 0;
		multiplier = -1;
		MY_ORDERS.clear();
	}
}
