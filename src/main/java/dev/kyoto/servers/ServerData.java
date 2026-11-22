package dev.kyoto.servers;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.kyoto.hud.Notifications;
import dev.kyoto.module.Modules;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerScoreEntry;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;

/**
 * Reads the sidebar scoreboard once a second and keeps the numbers packs care about (money, shards, kills...).
 * Also keeps this session's start values so the HUD can show "+$4.2M this session" and money per hour.
 */
public final class ServerData {
	private ServerData() {}

	/** Sidebar title + lines, top to bottom, plain text. */
	public static String title = "";
	public static final List<String> lines = new ArrayList<>();

	public static double money = -1, shards = -1, kills = -1, deaths = -1, hearts = -1, elo = -1;
	public static String playtime = "", team = "";

	// session
	public static long sessionStart = 0;
	public static double startMoney = -1, startShards = -1, startKills = -1, startDeaths = -1;
	public static double earnedChat = 0; // money from sells/AH/orders/pay seen in chat (works when the sidebar has no money line)
	public static int sessionKills = 0, sessionDeaths = 0;
	/** money samples for the per-hour rate: [time, money] */
	private static final List<double[]> SAMPLES = new ArrayList<>();

	private static int tick = 0;

	public static void tick(Minecraft mc) {
		if (Packs.current == null || mc.level == null) return;
		if (++tick % 20 != 0) return;
		readSidebar(mc);
		Matcher m;
		double mv = -1;
		for (String l : lines) {
			if (mv < 0 && (m = Packs.match("sb_money", l)) != null) mv = Packs.money(m.group(1));
			if ((m = Packs.match("sb_shards", l)) != null) shards = Packs.money(m.group(1));
			if ((m = Packs.match("sb_kills", l)) != null) kills = Packs.money(m.group(1));
			if ((m = Packs.match("sb_deaths", l)) != null) deaths = Packs.money(m.group(1));
			if ((m = Packs.match("sb_playtime", l)) != null) playtime = m.group(1).trim();
			if ((m = Packs.match("sb_team", l)) != null) team = m.group(1).trim();
			if ((m = Packs.match("sb_hearts", l)) != null) hearts = Packs.money(m.group(1));
			if ((m = Packs.match("sb_elo", l)) != null) elo = Packs.money(m.group(1));
		}
		if (mv >= 0) money = mv;
		if (startMoney < 0 && money >= 0) startMoney = money;
		if (startShards < 0 && shards >= 0) startShards = shards;
		if (startKills < 0 && kills >= 0) startKills = kills;
		if (startDeaths < 0 && deaths >= 0) startDeaths = deaths;
		if (money >= 0) {
			SAMPLES.add(new double[]{System.currentTimeMillis(), money});
			while (SAMPLES.size() > 3600) SAMPLES.remove(0);
		}
	}

	private static void readSidebar(Minecraft mc) {
		lines.clear();
		title = "";
		try {
			Scoreboard sb = mc.level.getScoreboard();
			Objective obj = sb.getDisplayObjective(DisplaySlot.SIDEBAR);
			if (obj == null) return;
			title = obj.getDisplayName().getString();
			Collection<PlayerScoreEntry> entries = sb.listPlayerScores(obj);
			List<PlayerScoreEntry> list = new ArrayList<>(entries);
			list.sort((a, b) -> Integer.compare(b.value(), a.value()));
			for (PlayerScoreEntry e : list) {
				if (e.isHidden()) continue;
				PlayerTeam t = sb.getPlayersTeam(e.owner());
				Component name = e.display() != null ? e.display() : PlayerTeam.formatNameForTeam(t, e.ownerName());
				String s = name.getString().replaceAll("§.", "").trim();
				if (!s.isEmpty()) lines.add(s);
				if (lines.size() >= 15) break;
			}
		} catch (Throwable ignored) {
			// scoreboard API moved: packs fall back to chat only
		}
	}

	/* ---------------- session ---------------- */

	public static void startSession() {
		sessionStart = System.currentTimeMillis();
		startMoney = startShards = startKills = startDeaths = -1;
		money = shards = kills = deaths = hearts = elo = -1;
		earnedChat = 0;
		sessionKills = sessionDeaths = 0;
		SAMPLES.clear();
		playtime = team = "";
	}

	public static double sessionMoney() {
		if (money >= 0 && startMoney >= 0) return money - startMoney;
		return earnedChat;
	}

	public static double sessionShards() {
		return shards >= 0 && startShards >= 0 ? shards - startShards : 0;
	}

	public static int sessionKillsTotal() {
		return kills >= 0 && startKills >= 0 ? (int) (kills - startKills) : sessionKills;
	}

	public static int sessionDeathsTotal() {
		return deaths >= 0 && startDeaths >= 0 ? (int) (deaths - startDeaths) : sessionDeaths;
	}

	/** Money per hour over the last 20 minutes (or the whole session if shorter). */
	public static double moneyPerHour() {
		long now = System.currentTimeMillis();
		if (SAMPLES.size() < 2) {
			long ms = now - sessionStart;
			return ms > 60000 ? sessionMoney() / (ms / 3600000.0) : 0;
		}
		double[] first = SAMPLES.get(0);
		for (double[] s : SAMPLES) if (now - s[0] <= 20 * 60000) { first = s; break; }
		double[] last = SAMPLES.get(SAMPLES.size() - 1);
		double hrs = (last[0] - first[0]) / 3600000.0;
		return hrs < 1.0 / 60 ? 0 : (last[1] - first[1]) / hrs;
	}

	public static double shardsPerHour() {
		long ms = System.currentTimeMillis() - sessionStart;
		return ms > 60000 ? sessionShards() / (ms / 3600000.0) : 0;
	}

	public static String sessionTime() {
		long s = (System.currentTimeMillis() - sessionStart) / 1000;
		return s >= 3600 ? (s / 3600) + "h " + (s % 3600 / 60) + "m" : (s / 60) + "m";
	}

	/** Session summary card when you leave + it goes into your per-server history (graph in the menu). */
	public static void endSession() {
		if (Packs.current == null || sessionStart == 0) return;
		long ms = System.currentTimeMillis() - sessionStart;
		if (ms < 60000) return;
		double made = sessionMoney();
		JsonObject hist = Packs.read("history_" + Packs.current.id());
		String day = LocalDate.now().toString();
		JsonObject d = hist.has(day) ? hist.getAsJsonObject(day) : new JsonObject();
		d.addProperty("money", (d.has("money") ? d.get("money").getAsDouble() : 0) + made);
		d.addProperty("shards", (d.has("shards") ? d.get("shards").getAsDouble() : 0) + sessionShards());
		d.addProperty("minutes", (d.has("minutes") ? d.get("minutes").getAsDouble() : 0) + ms / 60000.0);
		d.addProperty("kills", (d.has("kills") ? d.get("kills").getAsInt() : 0) + sessionKillsTotal());
		d.addProperty("deaths", (d.has("deaths") ? d.get("deaths").getAsInt() : 0) + sessionDeathsTotal());
		hist.add(day, d);
		// keep 120 days
		List<String> keys = new ArrayList<>(hist.keySet());
		keys.sort(String::compareTo);
		while (keys.size() > 120) hist.remove(keys.remove(0));
		Packs.write("history_" + Packs.current.id(), hist);
		if (Modules.on("session_summary")) {
			lastSummary = new String[]{Packs.current.name(), sessionTime(), made != 0 ? Packs.fmt(made) : "", sessionShards() > 0 ? Packs.num(sessionShards()) : "",
					String.valueOf(sessionKillsTotal()), String.valueOf(sessionDeathsTotal())};
			lastSummaryAt = System.currentTimeMillis();
		}
		sessionStart = 0;
	}

	/** Shown on the title/multiplayer screen after you leave: [server, time, money, shards, kills, deaths] */
	public static String[] lastSummary = null;
	public static long lastSummaryAt = 0;

	/** Last 7 days of money for the menu graph: [day label, money] */
	public static List<Object[]> week(String pack) {
		JsonObject hist = Packs.read("history_" + pack);
		List<Object[]> out = new ArrayList<>();
		for (int i = 6; i >= 0; i--) {
			LocalDate d = LocalDate.now().minusDays(i);
			JsonObject o = hist.has(d.toString()) ? hist.getAsJsonObject(d.toString()) : null;
			out.add(new Object[]{d.getDayOfWeek().toString().substring(0, 1), o == null ? 0.0 : o.get("money").getAsDouble(), o == null ? 0.0 : o.get("minutes").getAsDouble()});
		}
		return out;
	}

	/* ---------------- leaderboard / baltop snapshots ---------------- */

	public static void snapshot(String kind, List<String> rows) {
		if (Packs.current == null || rows.isEmpty()) return;
		JsonObject all = Packs.read("snapshots_" + Packs.current.id());
		JsonArray list = all.has(kind) ? all.getAsJsonArray(kind) : new JsonArray();
		JsonObject snap = new JsonObject();
		snap.addProperty("at", System.currentTimeMillis());
		JsonArray r = new JsonArray();
		for (String s : rows) r.add(s);
		snap.add("rows", r);
		// one per hour at most
		if (!list.isEmpty()) {
			JsonObject last = list.get(list.size() - 1).getAsJsonObject();
			if (System.currentTimeMillis() - last.get("at").getAsLong() < 3600000) list.remove(list.size() - 1);
		}
		list.add(snap);
		while (list.size() > 60) list.remove(0);
		all.add(kind, list);
		Packs.write("snapshots_" + Packs.current.id(), all);
	}

	/** Where you've been on a leaderboard: your rank in each snapshot (0 = not on it). */
	public static Map<Long, Integer> myRanks(String kind) {
		Map<Long, Integer> out = new LinkedHashMap<>();
		if (Packs.current == null) return out;
		JsonObject all = Packs.read("snapshots_" + Packs.current.id());
		if (!all.has(kind)) return out;
		String me = dev.kyoto.util.Compat.myName();
		for (var e : all.getAsJsonArray(kind)) {
			JsonObject s = e.getAsJsonObject();
			int rank = 0, i = 0;
			for (var r : s.getAsJsonArray("rows")) {
				i++;
				if (me != null && r.getAsString().toLowerCase().contains(me.toLowerCase())) { rank = i; break; }
			}
			out.put(s.get("at").getAsLong(), rank);
		}
		return out;
	}

	static void toast(String title, String body, int color, String icon) {
		Notifications.push(title, body, color, 5000, null, icon);
	}
}
