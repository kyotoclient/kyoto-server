package dev.kyoto.servers;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.kyoto.hud.Notifications;
import dev.kyoto.util.Compat;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;

/** PvP practice servers (Elo, match history, kit layouts) and MCC Island (event timers, quests, team colours). */
public final class Minis {
	private Minis() {}

	/* ================================================================ practice ================================================================ */

	public static int elo = -1, eloChange = 0;
	public static String kit = "", opp = "";
	public static long matchStart = 0;

	public static void onChat(String plain) {
		Matcher m;
		if (Packs.is("practice")) {
			if ((m = Packs.match("kit", plain)) != null) kit = m.group(1);
			if ((m = Packs.match("match_start", plain)) != null) {
				matchStart = System.currentTimeMillis();
				if (m.groupCount() >= 1 && m.group(1) != null) opp = m.group(1);
			}
			if ((m = Packs.match("elo_change", plain)) != null) {
				if (m.groupCount() >= 2 && m.group(2) != null) {
					int a = Integer.parseInt(m.group(1).replace("+", "")), b = Integer.parseInt(m.group(2));
					elo = b;
					eloChange = b - a;
				} else {
					eloChange = Integer.parseInt(m.group(1).replace("+", ""));
					if (elo > 0) elo += eloChange;
				}
				if (Packs.on("practice_history")) logMatch(eloChange >= 0);
			} else if ((m = Packs.match("elo_now", plain)) != null) elo = Integer.parseInt(m.group(1));
			if (Packs.on("practice_history") && eloChange == 0) {
				if (Packs.matches("match_lose", plain)) logMatch(false);
				else if ((m = Packs.match("match_win", plain)) != null) {
					String me = Compat.myName();
					String who = m.groupCount() >= 1 ? m.group(1) : null;
					if (who == null || (me != null && who.equalsIgnoreCase(me))) logMatch(true);
				}
			}
		}
		if (Packs.is("mcc")) {
			if ((m = Packs.match("badge", plain)) != null && Packs.on("mcc_quests")) Notifications.push("Badge", m.group(1), Notifications.GOOD, 5000, null, "star");
		}
	}

	private static long lastLogged = 0;

	private static void logMatch(boolean won) {
		long now = System.currentTimeMillis();
		if (now - lastLogged < 4000) return; // one match = one entry
		lastLogged = now;
		JsonObject o = Packs.read("practice_" + Packs.host.replaceAll("[^a-z0-9.]", ""));
		JsonArray a = o.has("matches") ? o.getAsJsonArray("matches") : new JsonArray();
		JsonObject e = new JsonObject();
		e.addProperty("opp", opp);
		e.addProperty("kit", kit);
		e.addProperty("won", won);
		e.addProperty("elo", eloChange);
		e.addProperty("at", now);
		a.add(e);
		while (a.size() > 200) a.remove(0);
		o.add("matches", a);
		Packs.write("practice_" + Packs.host.replaceAll("[^a-z0-9.]", ""), o);
		eloChange = 0;
	}

	public static List<String> history(int n) {
		List<String> out = new ArrayList<>();
		JsonObject o = Packs.read("practice_" + Packs.host.replaceAll("[^a-z0-9.]", ""));
		if (!o.has("matches")) return out;
		JsonArray a = o.getAsJsonArray("matches");
		for (int i = a.size() - 1; i >= 0 && out.size() < n; i--) {
			JsonObject e = a.get(i).getAsJsonObject();
			int ch = e.get("elo").getAsInt();
			out.add((e.get("won").getAsBoolean() ? "W" : "L") + "  " + (e.get("opp").getAsString().isEmpty() ? "?" : e.get("opp").getAsString())
					+ (e.get("kit").getAsString().isEmpty() ? "" : "  · " + e.get("kit").getAsString()) + (ch != 0 ? "  " + (ch > 0 ? "+" : "") + ch : ""));
		}
		return out;
	}

	/** Kit layouts: saves where each item sits in your hotbar for this kit, so you can set it up the same way next time. */
	public static String saveKit(String name) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) return "Join a server first";
		JsonObject o = Packs.read("kits");
		JsonArray a = new JsonArray();
		for (int i = 0; i < 9; i++) {
			ItemStack s = mc.player.getInventory().getItem(i);
			a.add(s.isEmpty() ? "" : net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(s.getItem()).toString());
		}
		o.add(name.toLowerCase(Locale.ROOT), a);
		Packs.write("kits", o);
		return "Saved hotbar for kit \"" + name + "\". Kyoto shows it while you sort your inventory.";
	}

	/** Item ids for slots 0-8 of the saved layout for the current kit, or null. */
	public static String[] kitLayout() {
		if (kit.isEmpty()) return null;
		JsonObject o = Packs.read("kits");
		if (!o.has(kit.toLowerCase(Locale.ROOT))) return null;
		JsonArray a = o.getAsJsonArray(kit.toLowerCase(Locale.ROOT));
		String[] out = new String[9];
		for (int i = 0; i < 9 && i < a.size(); i++) out[i] = a.get(i).getAsString();
		return out;
	}

	/* ================================================================ MCC Island ================================================================ */

	public static String eventTimer = "", quest = "";

	public static void tick(Minecraft mc) {
		if (!Packs.is("mcc") || mc.level == null || mc.level.getGameTime() % 20 != 0) return;
		Matcher m;
		eventTimer = "";
		quest = "";
		for (String l : ServerData.lines) {
			if (eventTimer.isEmpty() && (m = Packs.match("event_timer", l)) != null) eventTimer = l;
			if (quest.isEmpty() && Packs.matches("quest", l)) quest = l;
		}
	}

	/** MCC team chat: colour "[Team] name:" lines by team. */
	public static Component teamColor(Component msg) {
		if (!Packs.is("mcc") || !Packs.on("mcc_team_chat")) return msg;
		Matcher m = Packs.match("team_line", msg.getString());
		if (m == null) return msg;
		ChatFormatting c = switch (m.group(1).toLowerCase(Locale.ROOT)) {
			case "red" -> ChatFormatting.RED;
			case "orange", "yellow" -> ChatFormatting.GOLD;
			case "lime", "green" -> ChatFormatting.GREEN;
			case "cyan", "aqua" -> ChatFormatting.AQUA;
			case "blue" -> ChatFormatting.BLUE;
			case "purple" -> ChatFormatting.DARK_PURPLE;
			case "pink" -> ChatFormatting.LIGHT_PURPLE;
			default -> null;
		};
		return c == null ? msg : msg.copy().withStyle(c);
	}

	public static void reset() {
		elo = -1;
		eloChange = 0;
		kit = opp = "";
		eventTimer = quest = "";
	}
}
