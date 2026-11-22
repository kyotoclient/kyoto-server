package dev.kyoto.servers;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.kyoto.hud.Notifications;
import dev.kyoto.util.Anim;
import dev.kyoto.util.Compat;
import dev.kyoto.util.Ui;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;

/**
 * Price memory for server shops (DonutSMP /ah, /orders, /sell, Hypixel Bazaar...).
 * Every time YOU open one of those menus, Kyoto reads the prices that are already on screen and remembers them.
 * Tooltips then show what an item usually goes for, where it sells best, and cheap listings glow in the AH.
 */
public final class Market {
	private Market() {}

	public enum Menu { NONE, AH, ORDERS, SELL, WORTH, SHARDSHOP, BALTOP, LEADERBOARD, HOMES, BAZAAR }

	public static Menu menu = Menu.NONE;
	public static String menuTitle = "";
	private static int scanTick = 0;

	/** key -> kind -> recent unit prices */
	private static JsonObject DB = null;
	private static String dbFor = "";
	private static final Set<String> SEEN = new HashSet<>();
	private static boolean dirty = false;
	/** Slots to glow this frame: slot index -> colour */
	private static final Map<Integer, Integer> GLOW = new HashMap<>();

	private static JsonObject db() {
		String p = Packs.current == null ? "" : Packs.current.id();
		if (DB == null || !dbFor.equals(p)) {
			DB = Packs.read("market_" + p);
			dbFor = p;
			SEEN.clear();
		}
		return DB;
	}

	public static void save() {
		if (dirty && DB != null && !dbFor.isEmpty()) {
			Packs.write("market_" + dbFor, DB);
			dirty = false;
		}
	}

	public static String key(ItemStack s) {
		String id = BuiltInRegistries.ITEM.getKey(s.getItem()).getPath();
		// spawners and named "special" items are worth very different amounts, so key them by their name too
		if (s.is(Items.SPAWNER) || s.is(Items.TRIAL_SPAWNER) || s.has(DataComponents.CUSTOM_NAME) && id.contains("spawner"))
			return id + ":" + s.getHoverName().getString().toLowerCase(Locale.ROOT).replaceAll("[^a-z ]", "").trim();
		return id;
	}

	/* ================================================================ reading menus ================================================================ */

	public static void tick(Minecraft mc) {
		Screen s = Compat.currentScreen();
		if (!(s instanceof AbstractContainerScreen<?> cs) || Packs.current == null) {
			if (menu != Menu.NONE) save();
			menu = Menu.NONE;
			GLOW.clear();
			return;
		}
		if (++scanTick % 8 != 0) return;
		menuTitle = cs.getTitle().getString().replaceAll("§.", "");
		menu = classify(menuTitle);
		if (menu == Menu.NONE) return;
		GLOW.clear();
		List<String> rows = new ArrayList<>();
		for (Slot slot : cs.getMenu().slots) {
			if (slot.container instanceof Inventory) continue;
			ItemStack st = slot.getItem();
			if (st.isEmpty()) continue;
			List<String> lore = lore(st);
			String k = key(st);
			int count = Math.max(1, st.getCount());
			switch (menu) {
				case AH -> {
					double price = find("lore_price", lore);
					if (price > 0) {
						String seller = findStr("lore_seller", lore);
						record(k, "ah", price / count, seller + "@" + price + "x" + count);
						double usual = median(k, "ah");
						int n = samples(k, "ah");
						if (Packs.on("donut_deals") && n >= 4 && price / count < usual * 0.72) GLOW.put(slot.index, 0x6EE7B7);
						else if (Packs.on("donut_deals") && n >= 4 && price / count > usual * 1.6) GLOW.put(slot.index, 0xFF6B6B);
						watchCheck(k, st, price / count);
					}
				}
				case ORDERS -> {
					if (menuTitle.toLowerCase(Locale.ROOT).matches(".*\\b(your|my)\\b.*")) {
						for (String l : lore) {
							Matcher pm = Packs.match("lore_order_progress", l);
							if (pm != null) {
								try {
									Donut.MY_ORDERS.put(st.getHoverName().getString(), new int[]{(int) Packs.money(pm.group(1)), (int) Packs.money(pm.group(2))});
								} catch (Exception ignored) {
								}
								break;
							}
						}
					}
					double price = find("lore_order_price", lore);
					if (price <= 0) price = find("lore_price", lore);
					if (price > 0) record(k, "order", price, "o@" + price + slot.index);
				}
				case SELL, WORTH -> {
					double v = find("lore_worth", lore);
					if (v <= 0) v = find("lore_price", lore);
					if (v > 0) setSell(k, v / count);
				}
				case SHARDSHOP -> {
					double sh = find("lore_shard_price", lore);
					if (sh > 0) setValue(k, "shards", sh);
				}
				case BAZAAR -> {
					double b = find("bz_lore_buy", lore), se = find("bz_lore_sell", lore);
					if (b > 0) setValue(k, "bz_buy", b);
					if (se > 0) setValue(k, "bz_sell", se);
				}
				case BALTOP, LEADERBOARD -> {
					String name = st.getHoverName().getString();
					String line = name + (lore.isEmpty() ? "" : " " + lore.get(0));
					rows.add(line);
				}
				default -> {
				}
			}
		}
		if ((menu == Menu.BALTOP || menu == Menu.LEADERBOARD) && Packs.on("donut_leaderboards")) ServerData.snapshot(menu == Menu.BALTOP ? "baltop" : "leaderboard:" + menuTitle, rows);
		if (menu == Menu.SELL && Packs.on("donut_multiplier")) {
			// the sell menu usually shows your multiplier on an info item
			for (Slot slot : cs.getMenu().slots) {
				if (slot.container instanceof Inventory || slot.getItem().isEmpty()) continue;
				for (String l : lore(slot.getItem())) {
					Matcher m = Packs.match("multiplier", l);
					if (m != null) Donut.multiplier = Packs.money(m.group(1));
				}
			}
		}
	}

	private static Menu classify(String t) {
		if (Packs.is("hypixel")) return t.toLowerCase(Locale.ROOT).contains("bazaar") ? Menu.BAZAAR : Menu.NONE;
		if (Packs.matches("menu_ah", t)) return Menu.AH;
		if (Packs.matches("menu_baltop", t)) return Menu.BALTOP;
		if (Packs.matches("menu_orders", t)) return Menu.ORDERS;
		if (Packs.matches("menu_sell", t)) return Menu.SELL;
		if (Packs.matches("menu_worth", t)) return Menu.WORTH;
		if (Packs.matches("menu_shardshop", t)) return Menu.SHARDSHOP;
		if (Packs.matches("menu_leaderboard", t)) return Menu.LEADERBOARD;
		if (Packs.matches("menu_homes", t)) return Menu.HOMES;
		return Menu.NONE;
	}

	static List<String> lore(ItemStack st) {
		List<String> out = new ArrayList<>();
		try {
			ItemLore l = st.get(DataComponents.LORE);
			if (l != null) for (Component c : l.lines()) out.add(c.getString().replaceAll("§.", "").trim());
		} catch (Throwable ignored) {
		}
		return out;
	}

	private static double find(String key, List<String> lore) {
		for (String l : lore) {
			Matcher m = Packs.match(key, l);
			if (m != null) {
				double v = Packs.money(m.group(1));
				if (v > 0) return v;
			}
		}
		return -1;
	}

	private static String findStr(String key, List<String> lore) {
		for (String l : lore) {
			Matcher m = Packs.match(key, l);
			if (m != null) return m.group(1);
		}
		return "";
	}

	/* ================================================================ storing ================================================================ */

	private static JsonObject entry(String k) {
		JsonObject d = db();
		if (!d.has(k)) d.add(k, new JsonObject());
		return d.getAsJsonObject(k);
	}

	private static void record(String k, String kind, double unit, String uniq) {
		if (!SEEN.add(k + "|" + kind + "|" + uniq)) return;
		JsonObject e = entry(k);
		JsonArray a = e.has(kind) ? e.getAsJsonArray(kind) : new JsonArray();
		JsonArray p = new JsonArray();
		p.add(unit);
		p.add(System.currentTimeMillis());
		a.add(p);
		while (a.size() > 40) a.remove(0);
		e.add(kind, a);
		dirty = true;
	}

	private static void setSell(String k, double unit) {
		setValue(k, "sell", unit);
	}

	private static void setValue(String k, String kind, double v) {
		JsonObject e = entry(k);
		if (e.has(kind) && Math.abs(e.get(kind).getAsDouble() - v) < 0.0001) return;
		e.addProperty(kind, v);
		dirty = true;
	}

	public static int samples(String k, String kind) {
		JsonObject d = db();
		if (!d.has(k) || !d.getAsJsonObject(k).has(kind) || !d.getAsJsonObject(k).get(kind).isJsonArray()) return 0;
		return d.getAsJsonObject(k).getAsJsonArray(kind).size();
	}

	/** Median of the prices seen in the last 7 days (falls back to all of them). */
	public static double median(String k, String kind) {
		JsonObject d = db();
		if (!d.has(k)) return -1;
		JsonObject e = d.getAsJsonObject(k);
		if (!e.has(kind)) return -1;
		if (!e.get(kind).isJsonArray()) return e.get(kind).getAsDouble();
		List<Double> v = new ArrayList<>(), all = new ArrayList<>();
		long now = System.currentTimeMillis();
		for (var x : e.getAsJsonArray(kind)) {
			JsonArray p = x.getAsJsonArray();
			all.add(p.get(0).getAsDouble());
			if (now - p.get(1).getAsLong() < 7 * 86400000L) v.add(p.get(0).getAsDouble());
		}
		if (v.size() < 3) v = all;
		if (v.isEmpty()) return -1;
		v.sort(Double::compare);
		return v.get(v.size() / 2);
	}

	public static double low(String k, String kind) {
		JsonObject d = db();
		if (!d.has(k) || !d.getAsJsonObject(k).has(kind) || !d.getAsJsonObject(k).get(kind).isJsonArray()) return -1;
		double lo = Double.MAX_VALUE;
		long now = System.currentTimeMillis();
		for (var x : d.getAsJsonObject(k).getAsJsonArray(kind)) {
			JsonArray p = x.getAsJsonArray();
			if (now - p.get(1).getAsLong() < 2 * 86400000L) lo = Math.min(lo, p.get(0).getAsDouble());
		}
		return lo == Double.MAX_VALUE ? -1 : lo;
	}

	public static double value(String k, String kind) {
		JsonObject d = db();
		if (!d.has(k) || !d.getAsJsonObject(k).has(kind)) return -1;
		var v = d.getAsJsonObject(k).get(kind);
		return v.isJsonArray() ? median(k, kind) : v.getAsDouble();
	}

	/* ================================================================ tooltips ================================================================ */

	public static void tooltip(ItemStack st, List<Component> lines) {
		if (Packs.current == null || st.isEmpty()) return;
		String k = key(st);
		int count = Math.max(1, st.getCount());
		if (Packs.is("hypixel")) {
			if (!Packs.on("hypixel_bazaar")) return;
			double b = value(k, "bz_buy"), s = value(k, "bz_sell");
			if (b > 0 || s > 0) lines.add(Component.literal("Bazaar  buy " + (b > 0 ? Packs.num(b) : "?") + "  ·  sell " + (s > 0 ? Packs.num(s) : "?")).withStyle(ChatFormatting.GOLD));
			return;
		}
		if (!Packs.on("donut_prices")) return;
		double ah = median(k, "ah"), ord = median(k, "order"), sell = value(k, "sell");
		if (ah <= 0 && ord <= 0 && sell <= 0) return;
		double mult = Donut.multiplier > 0 ? Donut.multiplier : 1;
		double sellNow = sell > 0 ? sell * mult : -1;
		StringBuilder sb = new StringBuilder();
		if (ah > 0) sb.append("AH ~").append(Packs.fmt(ah * count));
		if (ord > 0) sb.append(sb.length() > 0 ? "  ·  " : "").append("Orders ").append(Packs.fmt(ord * count));
		if (sellNow > 0) sb.append(sb.length() > 0 ? "  ·  " : "").append("/sell ").append(Packs.fmt(sellNow * count));
		lines.add(Component.literal(sb.toString()).withStyle(ChatFormatting.GREEN));
		if (Packs.on("donut_where_sell")) {
			String best = "AH";
			double bv = ah;
			if (ord > bv) { best = "Orders"; bv = ord; }
			if (sellNow > bv) { best = "/sell"; bv = sellNow; }
			if (bv > 0) lines.add(Component.literal("Best: " + best + (best.equals("AH") ? " (slower, pays most)" : best.equals("Orders") ? " (instant)" : " (instant, no waiting)")).withStyle(ChatFormatting.DARK_GREEN));
		}
		int n = samples(k, "ah");
		if (n >= 3)
			lines.add(Component.literal(n + " AH prices seen · cheapest lately " + Packs.fmt(Math.max(0, low(k, "ah")) * count)).withStyle(ChatFormatting.DARK_GRAY));
		double sh = value(k, "shards");
		if (sh > 0 && Packs.on("donut_shardshop")) {
			double have = ServerData.shards;
			lines.add(Component.literal(have >= 0 ? (have >= sh ? "You can afford this (" + Packs.num(have) + " shards)" : "Need " + Packs.num(sh - have) + " more shards") : sh + " shards")
					.withStyle(have >= sh ? ChatFormatting.AQUA : ChatFormatting.GRAY));
		}
	}

	/* ================================================================ AH glow (drawn over the menu) ================================================================ */

	public static void drawOverlay(AbstractContainerScreen<?> cs, GuiGraphicsExtractor g) {
		if (GLOW.isEmpty() || menu != Menu.AH) return;
		int left = field(cs, "leftPos"), top = field(cs, "topPos");
		float pulse = 0.55f + 0.25f * (float) Math.sin(System.currentTimeMillis() / 220.0);
		for (Slot slot : cs.getMenu().slots) {
			Integer col = GLOW.get(slot.index);
			if (col == null) continue;
			int x = left + slot.x, y = top + slot.y;
			g.fill(x, y, x + 16, y + 16, Anim.argb(col, 0.22f * pulse));
			Ui.outline(g, x - 1, y - 1, x + 17, y + 17, Anim.argb(col, pulse));
		}
		// legend
		String t = "Green = cheap  ·  Red = overpriced";
		Ui.small(g, t, left, top - 9, Anim.argb(Ui.MUTED, 0.9f));
	}

	private static final Map<String, java.lang.reflect.Field> FIELDS = new HashMap<>();

	public static int field(AbstractContainerScreen<?> cs, String name) {
		try {
			java.lang.reflect.Field f = FIELDS.get(name);
			if (f == null) {
				f = AbstractContainerScreen.class.getDeclaredField(name);
				f.setAccessible(true);
				FIELDS.put(name, f);
			}
			return f.getInt(cs);
		} catch (Throwable t) {
			return (cs.width - 176) / 2;
		}
	}

	/* ================================================================ market watch + price check ================================================================ */

	private static final Map<String, Long> ALERTED = new HashMap<>();

	private static void watchCheck(String k, ItemStack st, double unit) {
		if (!Packs.on("donut_watch")) return;
		JsonObject w = Packs.read("watch_" + Packs.current.id());
		if (!w.has(k)) return;
		double target = w.get(k).getAsDouble();
		if (unit > target) return;
		Long last = ALERTED.get(k);
		if (last != null && System.currentTimeMillis() - last < 120000) return;
		ALERTED.put(k, System.currentTimeMillis());
		Notifications.push("Deal: " + st.getHoverName().getString(), Packs.fmt(unit) + " each (your limit " + Packs.fmt(target) + ")", Notifications.GOOD, 6000, null, "coin");
		Compat.sound(net.minecraft.sounds.SoundEvents.EXPERIENCE_ORB_PICKUP, 0.6f, 1.6f);
	}

	/** /kyoto watch <item> <price> */
	public static String watch(String item, double price) {
		if (Packs.current == null) return "Join a server first";
		JsonObject w = Packs.read("watch_" + Packs.current.id());
		String k = normalize(item);
		if (price <= 0) {
			w.remove(k);
			Packs.write("watch_" + Packs.current.id(), w);
			return "Stopped watching " + k;
		}
		w.addProperty(k, price);
		Packs.write("watch_" + Packs.current.id(), w);
		return "Watching " + k + ": you'll get a pop-up when the AH shows it at " + Packs.fmt(price) + " or less";
	}

	/** /kyoto price <item> */
	public static List<String> priceCheck(String item) {
		List<String> out = new ArrayList<>();
		String k = normalize(item);
		JsonObject d = db();
		List<String> keys = new ArrayList<>();
		for (String key : d.keySet()) if (key.equals(k) || key.contains(k)) keys.add(key);
		if (keys.isEmpty()) {
			out.add("No prices for \"" + item + "\" yet. Open /ah, /orders or /sell and look at it once.");
			return out;
		}
		for (String key : keys.subList(0, Math.min(5, keys.size()))) {
			double ah = median(key, "ah"), lo = low(key, "ah"), ord = median(key, "order"), sell = value(key, "sell");
			out.add(Compat.pretty(key) + ":  AH ~" + (ah > 0 ? Packs.fmt(ah) : "?") + (lo > 0 ? " (low " + Packs.fmt(lo) + ")" : "")
					+ "  ·  Orders " + (ord > 0 ? Packs.fmt(ord) : "?") + "  ·  /sell " + (sell > 0 ? Packs.fmt(sell * Math.max(1, Donut.multiplier)) : "?") + "  (each)");
		}
		return out;
	}

	public static String normalize(String item) {
		return item.toLowerCase(Locale.ROOT).replace("minecraft:", "").trim().replace(' ', '_');
	}

	/** Every item you know a price for, for command suggestions. */
	public static Set<String> known() {
		return db().keySet();
	}
}
