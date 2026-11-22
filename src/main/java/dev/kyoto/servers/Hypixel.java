package dev.kyoto.servers;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.kyoto.hud.Notifications;
import dev.kyoto.net.KyotoNet;
import dev.kyoto.util.Compat;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ArmorStand;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;

/**
 * Hypixel pack. Only reads the scoreboard, chat and the floating generator text; stats come from Hypixel's public API
 * through the Kyoto server. No packet tricks and nothing that clicks menus (Hypixel bans that).
 */
public final class Hypixel {
	private Hypixel() {}

	public enum Game { LOBBY, BEDWARS, SKYWARS, DUELS, SKYBLOCK, OTHER }

	public static Game game = Game.LOBBY;

	/** For Discord Status: "BedWars", "SkyWars"... or "" in the lobby. */
	public static String gameName() {
		return switch (game) {
			case BEDWARS -> "BedWars";
			case SKYWARS -> "SkyWars";
			case DUELS -> "Duels";
			case SKYBLOCK -> "SkyBlock";
			default -> "";
		};
	}
	public static boolean inGame = false;
	public static long gameStart = 0;
	public static int kills = 0, finals = 0, beds = 0;
	/** team colour -> "✔" / "✘" / players left */
	public static final Map<String, String> BEDS = new LinkedHashMap<>();
	public static final List<String> UPGRADES = new ArrayList<>();
	/** generator label ("Diamond", "Emerald") -> seconds, nearest one of each */
	public static final Map<String, Integer> GENS = new LinkedHashMap<>();
	public static final Set<String> PARTY = new LinkedHashSet<>();
	public static String opponent = "";
	public static long opponentAt = 0;
	public static int buildLimitY = Integer.MIN_VALUE;
	public static String lastPlay = "";
	public static double purse = -1, bank = -1;
	public static String slayer = "", dungeon = "";
	public static String[] recap = null;
	public static long recapAt = 0;

	/** name (lower) -> stats json, from the Kyoto server */
	public static final Map<String, JsonObject> STATS = new ConcurrentHashMap<>();
	private static final Set<String> ASKED = ConcurrentHashMap.newKeySet();

	public static void tick(Minecraft mc) {
		if (!Packs.is("hypixel") || mc.level == null || mc.player == null) return;
		String t = ServerData.title;
		Game g = Packs.matches("bw_title", t) ? Game.BEDWARS : Packs.matches("sw_title", t) ? Game.SKYWARS : Packs.matches("duels_title", t) ? Game.DUELS
				: Packs.matches("sb_title", t) ? Game.SKYBLOCK : t.isEmpty() ? Game.LOBBY : Game.OTHER;
		if (g != game) {
			game = g;
			BEDS.clear();
			GENS.clear();
		}
		if (mc.level.getGameTime() % 10 != 0) return;
		// beds + skyblock lines from the sidebar
		Matcher m;
		for (String l : ServerData.lines) {
			if (game == Game.BEDWARS && (m = Packs.match("bw_bed", l)) != null) BEDS.put(m.group(2), m.group(3));
			if (game == Game.SKYBLOCK) {
				if ((m = Packs.match("sb_purse", l)) != null) purse = Packs.money(m.group(1));
				if ((m = Packs.match("sb_bank", l)) != null) bank = Packs.money(m.group(1));
				if (Packs.matches("sb_slayer", l)) slayer = l;
				if ((m = Packs.match("sb_dungeon", l)) != null) dungeon = l;
			}
		}
		// generator countdowns: the floating "Spawns in 23 seconds" text over diamond/emerald generators
		if (game == Game.BEDWARS && Packs.on("hypixel_gens")) {
			GENS.clear();
			double best = 1e9;
			Map<String, double[]> nearest = new LinkedHashMap<>();
			String lastLabel = null;
			for (Entity e : mc.level.entitiesForRendering()) {
				if (!(e instanceof ArmorStand) || !e.hasCustomName() || e.getCustomName() == null) continue;
				String name = e.getCustomName().getString();
				String lower = name.toLowerCase(Locale.ROOT);
				String kind = lower.contains("diamond") ? "Diamond" : lower.contains("emerald") ? "Emerald" : null;
				if (kind != null) { lastLabel = kind; continue; }
				Matcher s = Packs.match("bw_gen", name);
				if (s == null) continue;
				int secs = Integer.parseInt(s.group(1));
				// the countdown stand sits right under the label stand: find the closest label
				String label = labelNear(mc, e);
				if (label == null) label = lastLabel == null ? "Gen" : lastLabel;
				double d = e.distanceToSqr(mc.player);
				double[] cur = nearest.get(label);
				if (cur == null || d < cur[1]) nearest.put(label, new double[]{secs, d});
			}
			for (var en : nearest.entrySet()) GENS.put(en.getKey(), (int) en.getValue()[0]);
		}
		// stats for players in your game (asks the Kyoto server once per name)
		if (Packs.on("hypixel_stats") && (game == Game.BEDWARS || game == Game.SKYWARS || game == Game.DUELS) && mc.getConnection() != null) {
			int asked = 0;
			for (var info : mc.getConnection().getOnlinePlayers()) {
				String n = Compat.profileName(info.getProfile());
				if (n == null || n.length() < 3 || !n.matches("\\w{3,16}") || !ASKED.add(n.toLowerCase(Locale.ROOT))) continue;
				fetch(n);
				if (++asked >= 3) break; // a few per half-second so we don't spam
			}
		}
	}

	private static String labelNear(Minecraft mc, Entity e) {
		for (Entity o : mc.level.entitiesForRendering()) {
			if (!(o instanceof ArmorStand) || o == e || !o.hasCustomName() || o.getCustomName() == null) continue;
			if (Math.abs(o.getX() - e.getX()) < 0.6 && Math.abs(o.getZ() - e.getZ()) < 0.6 && Math.abs(o.getY() - e.getY()) < 1.5) {
				String l = o.getCustomName().getString().toLowerCase(Locale.ROOT);
				if (l.contains("diamond")) return "Diamond";
				if (l.contains("emerald")) return "Emerald";
			}
		}
		return null;
	}

	private static void fetch(String name) {
		Thread t = new Thread(() -> {
			try {
				JsonObject j = JsonParser.parseString(KyotoNet.send("GET", "/hypixel/player?name=" + name, null, false)).getAsJsonObject();
				if (j.has("name")) STATS.put(name.toLowerCase(Locale.ROOT), j);
			} catch (Exception ignored) {
			}
		}, "Kyoto stats");
		t.setDaemon(true);
		t.start();
	}

	public static void lookup(String name) {
		ASKED.add(name.toLowerCase(Locale.ROOT));
		fetch(name);
	}

	/** Threat score for sorting the stats card: bedwars FKDR x stars, etc. */
	public static double threat(JsonObject s) {
		return switch (game) {
			case BEDWARS -> num(s, "bw_fkdr") * Math.max(1, num(s, "bw_star"));
			case SKYWARS -> num(s, "sw_kdr") * Math.max(1, num(s, "sw_star"));
			case DUELS -> num(s, "duels_wlr") * Math.max(1, num(s, "duels_wins") / 100);
			default -> 0;
		};
	}

	public static double num(JsonObject s, String k) {
		return s.has(k) && !s.get(k).isJsonNull() ? s.get(k).getAsDouble() : 0;
	}

	/** Players in this lobby/game sorted by threat (for the stats card). */
	public static List<JsonObject> lobbyStats() {
		List<JsonObject> out = new ArrayList<>();
		Minecraft mc = Minecraft.getInstance();
		if (mc.getConnection() == null) return out;
		for (var info : mc.getConnection().getOnlinePlayers()) {
			String n = Compat.profileName(info.getProfile());
			if (n == null) continue;
			JsonObject s = STATS.get(n.toLowerCase(Locale.ROOT));
			if (s != null) out.add(s);
		}
		out.sort((a, b) -> Double.compare(threat(b), threat(a)));
		return out;
	}

	/** @return true to hide the message (chat blockers). */
	public static boolean onChat(String plain, boolean fromPlayer) {
		if (!Packs.is("hypixel")) return false;
		Matcher m;
		String me = Compat.myName();
		if (Packs.matches("game_start", plain)) {
			inGame = true;
			gameStart = System.currentTimeMillis();
			kills = finals = beds = 0;
			UPGRADES.clear();
		}
		if (me != null && plain.contains(me)) {
			if (Packs.matches("bw_final", plain) && plain.matches("(?i).*\\bby " + java.util.regex.Pattern.quote(me) + "\\b.*")) finals++;
			else if (plain.matches("(?i).*(?:killed|slain|knocked|shot|thrown|pushed).* by " + java.util.regex.Pattern.quote(me) + "\\b.*")) kills++;
		}
		if ((m = Packs.match("bw_bed_break", plain)) != null && me != null && m.group(2).equalsIgnoreCase(me)) beds++;
		if ((m = Packs.match("bw_upgrade", plain)) != null && game == Game.BEDWARS && !fromPlayer) {
			String up = m.group(2).trim();
			if (!UPGRADES.contains(up)) UPGRADES.add(up);
		}
		if (Packs.matches("game_end", plain) && inGame) {
			inGame = false;
			if (Packs.on("hypixel_recap")) {
				long secs = (System.currentTimeMillis() - gameStart) / 1000;
				recap = new String[]{game.name().charAt(0) + game.name().substring(1).toLowerCase(Locale.ROOT), kills + " kills", finals + " finals", beds + " beds", (secs / 60) + "m " + (secs % 60) + "s"};
				recapAt = System.currentTimeMillis();
			}
		}
		if ((m = Packs.match("opponent", plain)) != null) {
			opponent = m.group(1);
			opponentAt = System.currentTimeMillis();
			if (Packs.on("hypixel_stats")) lookup(opponent);
		}
		if ((m = Packs.match("party_join", plain)) != null) PARTY.add(m.group(1));
		if ((m = Packs.match("party_leave", plain)) != null) {
			if (m.groupCount() >= 1 && m.group(1) != null) PARTY.remove(m.group(1));
			else PARTY.clear();
		}
		if ((m = Packs.match("party_list", plain)) != null) for (String s : m.group(1).split("●|,|\\s{2,}")) {
			String n = s.replaceAll("\\[[^]]+]", "").trim();
			if (n.matches("\\w{3,16}")) PARTY.add(n);
		}
		if (Packs.matches("height_limit", plain)) {
			Minecraft mc = Minecraft.getInstance();
			if (mc.player != null) buildLimitY = mc.player.getBlockY() + 2;
		}
		// blockers
		if (Packs.on("hypixel_lobby_filter") && Packs.matches("lobby_spam", plain)) return true;
		if (Packs.on("hypixel_ad_filter") && fromPlayer && Packs.matches("ad_spam", plain)) return true;
		if (Packs.on("hypixel_ad_filter") && (Packs.matches("guild_motd", plain) || Packs.matches("friend_status", plain))) return true;
		return false;
	}

	public static boolean onCommand(String cmd) {
		Matcher m = Packs.match("play_cmd", "/" + cmd.trim());
		if (m != null) lastPlay = m.group(1);
		return true;
	}

	/** Quick queue key: runs your last /play again. */
	public static void requeue() {
		if (!Packs.is("hypixel")) return;
		if (lastPlay.isEmpty()) {
			Notifications.push("Quick queue", "Type /play <mode> once, then this key repeats it", Notifications.INFO, 4000, null, "play");
			return;
		}
		Compat.sendCommand("play " + lastPlay);
	}

	public static void reset() {
		game = Game.LOBBY;
		inGame = false;
		BEDS.clear();
		GENS.clear();
		UPGRADES.clear();
		PARTY.clear();
		STATS.clear();
		ASKED.clear();
		buildLimitY = Integer.MIN_VALUE;
		recap = null;
	}
}
