package dev.kyoto.servers;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.kyoto.config.KyotoConfig;
import dev.kyoto.hud.Notifications;
import dev.kyoto.module.Modules;
import dev.kyoto.net.KyotoNet;
import dev.kyoto.util.Compat;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Server Packs: Kyoto looks at the server address when you join and turns on a pack of extras made for that server.
 * Everything in a pack only READS what the server already shows you (scoreboard, chat, menus, tooltips) and draws it
 * nicer. Nothing clicks, moves, sells or fights for you, so it stays legit.
 *
 * Chat/menu wording changes when servers update, so every pattern lives in a small JSON that the Kyoto server can update
 * (GET /packs/{id}) without you rebuilding the mod. The defaults below are used when that isn't reachable.
 */
public final class Packs {
	private Packs() {}

	public record Pack(String id, String name, String icon, String desc, String[] hosts, int color) {}

	public static final Map<String, Pack> ALL = new LinkedHashMap<>();

	static {
		add(new Pack("donut", "DonutSMP", "server", "Money, shards, AH prices, bounties, spawners", new String[]{"donutsmp"}, 0xFFB86B));
		add(new Pack("hypixel", "Hypixel", "server", "Bedwars timers, stats, SkyBlock prices, chat cleanup", new String[]{"hypixel.net", "hypixel.io"}, 0xFFD36B));
		add(new Pack("lifesteal", "Lifesteal / PvP SMP", "combat", "Hearts, totems, fights, combat timer", new String[]{"lifesteal", "lifestealsmp", "pvp", "smp"}, 0xFF6B8A));
		add(new Pack("practice", "PvP Practice", "combat", "Elo, match history, kits", new String[]{"minemen", "pvp.land", "pvplegacy", "practice", "stray.gg", "mcpvp", "ranked"}, 0x7FB8FF));
		add(new Pack("mcc", "MCC Island", "star", "Event timers, quests, teams", new String[]{"mccisland", "mcchampionship"}, 0xB89CFF));
		add(new Pack("smp", "Any SMP", "world", "TPA buttons, homes, balance, mail, AFK", new String[]{}, 0x6EE7B7));
	}

	private static void add(Pack p) {
		ALL.put(p.id(), p);
	}

	/** The pack that's on right now (null = none). */
	public static Pack current = null;
	/** The detected server name for the menu tab ("DonutSMP", "play.example.net", "Singleplayer"). */
	public static String serverLabel = "";
	public static String host = "";
	private static String forced = null; // set from the menu's pack picker

	/* ================================================================ joining / leaving ================================================================ */

	public static void onJoin(Minecraft mc) {
		String ip = Compat.currentServerIp();
		host = ip == null ? "" : ip.toLowerCase(Locale.ROOT).replaceAll(":\\d+$", "");
		serverLabel = host.isEmpty() ? (mc.hasSingleplayerServer() ? "Singleplayer" : "This world") : prettyHost(host);
		String pick = KyotoConfig.get().packPicks.get(host);
		current = null;
		if (!host.isEmpty() && Modules.on("server_packs")) {
			if (pick != null && !pick.isEmpty()) current = "off".equals(pick) ? null : ALL.get(pick);
			else current = detect(host);
		}
		if (current != null) {
			serverLabel = current.id().equals("smp") ? prettyHost(host) : current.name();
			loadPatterns(current.id());
			applyLayout(current.id());
			ServerData.startSession();
			Notifications.push(current.name() + " pack on", "Open the menu > " + serverLabel + " tab to see what it adds", Notifications.GOOD, 5500, null, current.icon());
		}
		friendsHere();
	}

	public static void onLeave() {
		if (current != null) {
			ServerData.endSession();
			saveLayoutFor(current.id());
		}
		current = null;
		host = "";
		serverLabel = "";
	}

	public static Pack detect(String host) {
		for (Pack p : ALL.values()) for (String h : p.hosts()) if (!h.isEmpty() && host.contains(h) && !p.id().equals("smp") && !p.id().equals("lifesteal")) return p;
		// "lifesteal" and plain SMPs are guesses from the address
		if (host.contains("lifesteal")) return ALL.get("lifesteal");
		return ALL.get("smp");
	}

	public static void force(String packId) {
		if (host.isEmpty()) return;
		KyotoConfig.get().packPicks.put(host, packId == null ? "" : packId);
		KyotoConfig.save();
		Pack before = current;
		if (before != null) saveLayoutFor(before.id());
		current = packId == null || packId.isEmpty() ? detect(host) : "off".equals(packId) ? null : ALL.get(packId);
		if (current != null) {
			serverLabel = current.id().equals("smp") ? prettyHost(host) : current.name();
			loadPatterns(current.id());
			applyLayout(current.id());
			ServerData.startSession();
		}
	}

	public static boolean is(String id) {
		return current != null && current.id().equals(id);
	}

	/** True when this pack feature module is on AND its pack is active (or it's an "any SMP" feature on an SMP-ish pack). */
	public static boolean on(String moduleId) {
		Modules.Module m = Modules.get(moduleId);
		if (m == null || current == null || !m.on() || disallowed(moduleId)) return false;
		if (m.pack == null) return true;
		if (m.pack.equals(current.id())) return true;
		// the generic SMP helpers also work on DonutSMP and lifesteal servers
		return m.pack.equals("smp") && (current.id().equals("donut") || current.id().equals("lifesteal"));
	}

	private static String prettyHost(String h) {
		String[] parts = h.split("\\.");
		String base = parts.length >= 2 ? parts[parts.length - 2] : h;
		if (base.length() <= 3 && parts.length >= 3) base = parts[parts.length - 3];
		return base.isEmpty() ? h : Character.toUpperCase(base.charAt(0)) + base.substring(1);
	}

	/* ================================================================ patterns (updatable) ================================================================ */

	private static final Map<String, List<Pattern>> PATTERNS = new HashMap<>();
	private static final List<String> DISALLOWED = new ArrayList<>();
	public static final List<String[]> SUGGESTIONS = new ArrayList<>(); // [name, why, url] resource packs / tips

	/** Built-in defaults: key -> regexes. Group 1 is usually the number/name we want. */
	private static Map<String, String[]> defaults(String pack) {
		Map<String, String[]> d = new HashMap<>();
		String money = "\\$?\\s?([\\d.,]+\\s?[kKmMbBtT]?)";
		// shared
		d.put("pay_in", new String[]{"(?i)you (?:have )?received " + money + " from (\\w+)", "(?i)(\\w+) (?:has )?(?:paid|sent) you " + money});
		d.put("pay_out", new String[]{"(?i)you (?:have )?(?:paid|sent) " + money + " to (\\w+)"});
		d.put("tpa", new String[]{"(?i)(\\w+) (?:has )?(?:requested|sent you a) (?:to )?(?:teleport|tpa)", "(?i)(\\w+) wants to teleport to you"});
		d.put("combat_on", new String[]{"(?i)(?:you are|you're) (?:now )?in combat", "(?i)combat(?:\\s*tag(?:ged)?)?[:!]?\\s*(\\d+)\\s*s", "(?i)do not log out"});
		d.put("combat_off", new String[]{"(?i)(?:you are|you're) no longer in combat", "(?i)out of combat", "(?i)you can (?:now )?log out"});
		d.put("death_you", new String[]{"(?i)you (?:died|were (?:slain|killed|shot|blown up)|fell)", "(?i)^☠?\\s*you died"});
		d.put("kill_you", new String[]{"(?i)you (?:killed|slain|eliminated) (\\w+)", "(?i)(\\w+) was (?:slain|killed|shot) by %me%"});
		d.put("scam", new String[]{"(?i)(double|2x|triple) (your|ur) (money|bal)", "(?i)drop (it|them|the items?) first", "(?i)(trust trade|trust me bro|give first)",
				"(?i)free (money|items|ranks?) (if|when) you", "(?i)(sell|buy) (accounts?|alts?) for real money", "(?i)(discord\\.gg/|dsc\\.gg/).*(free|nitro|giveaway)"});
		d.put("afk", new String[]{"(?i)you are now afk", "(?i)you(?:'re| are) now away"});
		d.put("afk_off", new String[]{"(?i)you are no longer afk", "(?i)welcome back"});
		d.put("mail", new String[]{"(?i)you have (\\d+) (?:new |unread )?(?:mail|messages?)"});
		d.put("dm_in", new String[]{"^\\[?(\\w+) (?:->|→|»|whispers to) (?:me|you)\\]?:?\\s*(.*)$", "(?i)^from (\\w+):\\s*(.*)$"});
		d.put("bal", new String[]{"(?i)(?:balance|bal|money)\\s*[:=]?\\s*" + money});
		d.put("homes", new String[]{"(?i)homes?(?:\\s*\\(\\d+\\))?:\\s*(.+)$"});
		d.put("warps", new String[]{"(?i)warps?(?:\\s*\\(\\d+\\))?:\\s*(.+)$"});
		// scoreboard labels (case-insensitive "starts with")
		d.put("sb_money", new String[]{"(?i)(?:money|balance|bal|coins|purse)\\b[^\\d$]*" + money});
		d.put("sb_shards", new String[]{"(?i)shards?\\b[^\\d]*([\\d.,]+[kKmMbB]?)"});
		d.put("sb_kills", new String[]{"(?i)kills?\\b[^\\d]*([\\d,]+)"});
		d.put("sb_deaths", new String[]{"(?i)deaths?\\b[^\\d]*([\\d,]+)"});
		d.put("sb_playtime", new String[]{"(?i)play\\s?time\\b[^\\d]*(.+)$"});
		d.put("sb_team", new String[]{"(?i)team\\b\\W*(.+)$"});
		d.put("sb_hearts", new String[]{"(?i)hearts?\\b[^\\d]*([\\d.]+)"});
		d.put("sb_elo", new String[]{"(?i)(?:elo|rating|mmr)\\b[^\\d]*(\\d{2,5})"});
		switch (pack) {
			case "donut" -> {
				d.put("ah_sold", new String[]{"(?i)(\\w+) (?:has )?(?:bought|purchased) (?:your )?(.+?) for " + money, "(?i)you sold (.+?) (?:on|in) (?:the )?(?:/?ah|auction(?: house)?) for " + money});
				d.put("ah_bought", new String[]{"(?i)you (?:bought|purchased) (.+?) (?:from (\\w+) )?for " + money});
				d.put("order_fill", new String[]{"(?i)(\\w+) (?:has )?(?:filled|delivered|completed) (?:your )?order(?: for)? (.+?)(?: for " + money + ")?$", "(?i)your order (?:for )?(.+?) (?:was|has been) (?:filled|completed)"});
				d.put("sold", new String[]{"(?i)you sold (.+?) for " + money, "(?i)sold (\\d+) items? for " + money});
				d.put("bounty_on_you", new String[]{"(?i)(\\w+) (?:has )?(?:placed|added|set|put) a bounty of " + money + " on (?:you|%me%)", "(?i)a bounty of " + money + " (?:was|has been) placed on (?:you|%me%)"});
				d.put("bounty_any", new String[]{"(?i)(\\w+) (?:has )?(?:placed|added|set|put) a bounty of " + money + " on (\\w+)", "(?i)bounty of " + money + " (?:on|for) (\\w+)"});
				d.put("bounty_claim", new String[]{"(?i)(\\w+) (?:has )?claimed (?:the|a) bounty of " + money + " on (\\w+)"});
				d.put("shards_gain", new String[]{"(?i)\\+\\s?([\\d,]+) shards?", "(?i)you (?:received|got|earned) ([\\d,]+) shards?"});
				d.put("multiplier", new String[]{"(?i)(?:sell )?multiplier[^\\d]*([\\d.]+)\\s?x", "(?i)([\\d.]+)x (?:sell )?multiplier"});
				d.put("menu_ah", new String[]{"(?i)auction", "(?i)^ah\\b"});
				d.put("menu_orders", new String[]{"(?i)orders?"});
				d.put("menu_sell", new String[]{"(?i)^sell", "(?i)sell (menu|gui)"});
				d.put("menu_worth", new String[]{"(?i)worth"});
				d.put("menu_shardshop", new String[]{"(?i)shard ?shop", "(?i)shards?"});
				d.put("menu_baltop", new String[]{"(?i)baltop", "(?i)(richest|top balances?)"});
				d.put("menu_leaderboard", new String[]{"(?i)leaderboard", "(?i)\\btop\\b"});
				d.put("menu_homes", new String[]{"(?i)homes?"});
				d.put("lore_price", new String[]{"(?i)(?:price|cost|buy ?now|bin)\\s*[:»-]?\\s*" + money, "(?i)^" + money + "\\s*(?:each|ea)?$"});
				d.put("lore_order_price", new String[]{"(?i)(?:paying|price per item|per item|each)\\s*[:»-]?\\s*" + money});
				d.put("lore_order_progress", new String[]{"(?i)([\\d,]+)\\s*/\\s*([\\d,]+)"});
				d.put("lore_worth", new String[]{"(?i)(?:worth|sell (?:value|price))\\s*[:»-]?\\s*" + money});
				d.put("lore_shard_price", new String[]{"(?i)([\\d,]+)\\s*shards?"});
				d.put("lore_seller", new String[]{"(?i)seller\\s*[:»-]?\\s*(\\w+)"});
			}
			case "hypixel" -> {
				d.put("bw_title", new String[]{"(?i)bed ?wars"});
				d.put("sw_title", new String[]{"(?i)sky ?wars"});
				d.put("duels_title", new String[]{"(?i)duels"});
				d.put("sb_title", new String[]{"(?i)sky ?block"});
				d.put("bw_gen", new String[]{"(?i)spawns? in\\s*(\\d+)", "(?i)(\\d+)\\s*seconds?"});
				d.put("bw_bed", new String[]{"^([A-Z])\\s+(Red|Blue|Green|Yellow|Aqua|White|Pink|Gray|Grey)\\s*:?\\s*(✔|✓|✘|✗|\\d+)"});
				d.put("bw_final", new String[]{"(?i)FINAL KILL!?$"});
				d.put("bw_bed_break", new String[]{"(?i)BED DESTRUCTION.*?(\\w+) Bed .*?by (\\w+)"});
				d.put("bw_upgrade", new String[]{"(?i)(\\w+) purchased (.+)$"});
				d.put("game_end", new String[]{"(?i)(1st Killer|Reward Summary|WINNER!|VICTORY|\\bwon the (?:game|duel))"});
				d.put("game_start", new String[]{"(?i)the game (?:has )?start(?:ed|s)", "(?i)protect your bed", "(?i)gates? (?:are )?open"});
				d.put("opponent", new String[]{"(?i)opponents?:\\s*(?:\\[[^]]+]\\s*)?(\\w+)", "(?i)you are fighting (\\w+)"});
				d.put("party_join", new String[]{"(?i)(?:\\[[^]]+]\\s*)?(\\w+) joined the party", "(?i)you have joined (?:\\[[^]]+]\\s*)?(\\w+)'s party"});
				d.put("party_leave", new String[]{"(?i)(?:\\[[^]]+]\\s*)?(\\w+) has (?:left|been removed from) the party", "(?i)the party was disbanded", "(?i)you left the party"});
				d.put("party_list", new String[]{"(?i)party (?:members|leader|moderators)\\s*(?:\\(\\d+\\))?:\\s*(.+)$"});
				d.put("lobby_spam", new String[]{"joined the lobby!", "(?i)found a .* mystery box", "(?i)has been gifted", "(?i)\\[mvp\\+\\+].*joined", "(?i)^\\s*>>>.*joined the lobby"});
				d.put("ad_spam", new String[]{"(?i)(join|need|lf|looking for)\\s*(\\d|a)?\\s*(bed ?wars|bw|sw) (party|p\\b)", "(?i)party (?:up|me)\\b", "(?i)/p join"});
				d.put("guild_motd", new String[]{"(?i)guild motd"});
				d.put("friend_status", new String[]{"(?i)^(friend|guild) > \\w+ (joined|left)\\.?$"});
				d.put("height_limit", new String[]{"(?i)build height limit", "(?i)you can't place blocks (?:here|above)"});
				d.put("sb_purse", new String[]{"(?i)(?:purse|piggy)\\s*:\\s*([\\d,.]+)"});
				d.put("sb_bank", new String[]{"(?i)bank\\s*:\\s*([\\d,.]+[kmb]?)"});
				d.put("sb_slayer", new String[]{"(?i)slayer quest", "(?i)slay the boss", "(?i)\\(([\\d,]+)/([\\d,]+)\\)\\s*combat xp"});
				d.put("sb_dungeon", new String[]{"(?i)cleared:\\s*(\\d+)%", "(?i)time elapsed:\\s*(.+)$"});
				d.put("bz_lore_buy", new String[]{"(?i)buy price:\\s*([\\d,.]+)", "(?i)instant ?buy.*?([\\d,.]+) coins"});
				d.put("bz_lore_sell", new String[]{"(?i)sell price:\\s*([\\d,.]+)", "(?i)instant ?sell.*?([\\d,.]+) coins"});
				d.put("play_cmd", new String[]{"(?i)^/?play ([a-z0-9_]+)$"});
			}
			case "practice" -> {
				d.put("elo_change", new String[]{"(?i)([+-]\\d+)\\s*elo", "(?i)elo[^\\d+-]*([+-]\\d+)", "(?i)\\((\\d{3,4})\\s*[→>-]+\\s*(\\d{3,4})\\)"});
				d.put("elo_now", new String[]{"(?i)(?:elo|rating)[:\\s]+(\\d{3,4})"});
				d.put("match_win", new String[]{"(?i)(?:winner|you won|victory)[:!]?\\s*(\\w+)?", "(?i)(\\w+) won the (?:match|duel)"});
				d.put("match_lose", new String[]{"(?i)(?:you lost|defeat)"});
				d.put("match_start", new String[]{"(?i)match (?:found|starting)", "(?i)(?:opponent|vs\\.?)\\s*:?\\s*(\\w+)"});
				d.put("kit", new String[]{"(?i)kit[:\\s]+(\\w+)", "(?i)(nodebuff|debuff|sumo|gapple|builduh|boxing|combo|uhc|axe|sword|mace|crystal|smp|pot|nethpot|diapot)"});
			}
			case "lifesteal" -> {
				d.put("heart_gain", new String[]{"(?i)you (?:gained|got|received) (?:a |an )?(\\d+)? ?hearts?", "(?i)\\+(\\d+) hearts?"});
				d.put("heart_lose", new String[]{"(?i)you (?:lost|have lost) (?:a |an )?(\\d+)? ?hearts?", "(?i)-(\\d+) hearts?"});
				d.put("fight", new String[]{"(?i)(\\w+) (?:was slain|was killed|was shot|died) (?:by|to) (\\w+)"});
			}
			case "mcc" -> {
				d.put("event_timer", new String[]{"(?i)(?:next event|event in|starts in|game starts in)[:\\s]+([\\d:]+)", "(?i)([\\d]+:[\\d]{2})"});
				d.put("quest", new String[]{"(?i)quest[:\\s]+(.+)", "(?i)\\((\\d+)/(\\d+)\\)"});
				d.put("badge", new String[]{"(?i)badge (?:unlocked|progress)[:\\s]+(.+)"});
				d.put("team_line", new String[]{"^\\[(\\w+)\\]\\s*(\\w+):"});
			}
			default -> {
			}
		}
		return d;
	}

	private static String loadedFor = "";

	public static void loadPatterns(String pack) {
		PATTERNS.clear();
		DISALLOWED.clear();
		SUGGESTIONS.clear();
		loadedFor = pack;
		Map<String, String[]> d = defaults(pack);
		for (var e : d.entrySet()) put(e.getKey(), e.getValue());
		// cached remote copy first (works offline), then refresh in the background
		Path cache = dir().resolve("pack_" + pack + ".json");
		try {
			if (Files.exists(cache)) applyRemote(JsonParser.parseString(Files.readString(cache, StandardCharsets.UTF_8)).getAsJsonObject());
		} catch (Exception ignored) {
		}
		Thread t = new Thread(() -> {
			try {
				String raw = KyotoNet.send("GET", "/packs/" + pack, null, false);
				JsonObject j = JsonParser.parseString(raw).getAsJsonObject();
				Files.createDirectories(dir());
				Files.writeString(cache, raw, StandardCharsets.UTF_8);
				Minecraft.getInstance().execute(() -> {
					if (loadedFor.equals(pack)) applyRemote(j);
				});
			} catch (Exception ignored) {
			}
		}, "Kyoto pack " + pack);
		t.setDaemon(true);
		t.start();
	}

	private static void put(String key, String[] regexes) {
		List<Pattern> l = new ArrayList<>();
		for (String r : regexes) {
			try {
				l.add(Pattern.compile(r));
			} catch (Exception ignored) {
			}
		}
		PATTERNS.put(key, l);
	}

	private static void applyRemote(JsonObject j) {
		if (j.has("patterns")) for (var e : j.getAsJsonObject("patterns").entrySet()) {
			JsonArray a = e.getValue().getAsJsonArray();
			String[] r = new String[a.size()];
			for (int i = 0; i < r.length; i++) r[i] = a.get(i).getAsString();
			// "+key" adds to the defaults, "key" replaces them
			if (e.getKey().startsWith("+")) {
				String k = e.getKey().substring(1);
				List<Pattern> l = new ArrayList<>(PATTERNS.getOrDefault(k, List.of()));
				for (String s : r) try { l.add(Pattern.compile(s)); } catch (Exception ignored) { }
				PATTERNS.put(k, l);
			} else put(e.getKey(), r);
		}
		if (j.has("disallow")) {
			DISALLOWED.clear();
			for (JsonElement e : j.getAsJsonArray("disallow")) DISALLOWED.add(e.getAsString());
		}
		if (j.has("suggest")) {
			SUGGESTIONS.clear();
			for (JsonElement e : j.getAsJsonArray("suggest")) {
				JsonObject o = e.getAsJsonObject();
				SUGGESTIONS.add(new String[]{o.has("name") ? o.get("name").getAsString() : "", o.has("why") ? o.get("why").getAsString() : "", o.has("url") ? o.get("url").getAsString() : ""});
			}
		}
	}

	/** First match of any pattern for this key, with %me% replaced by your name. */
	public static Matcher match(String key, String text) {
		List<Pattern> l = PATTERNS.get(key);
		if (l == null || text == null) return null;
		String me = Compat.myName();
		for (Pattern p : l) {
			Pattern use = p;
			if (me != null && p.pattern().contains("%me%")) use = Pattern.compile(p.pattern().replace("%me%", Pattern.quote(me)), p.flags());
			Matcher m = use.matcher(text);
			if (m.find()) return m;
		}
		return null;
	}

	public static boolean matches(String key, String text) {
		return match(key, text) != null;
	}

	/** The server rules check: features a server doesn't allow are switched off while you're on it. */
	public static boolean disallowed(String moduleId) {
		if (DISALLOWED.contains(moduleId)) return true;
		// built-in: things we know a server bans even if the pack file is missing
		if (current != null && current.id().equals("hypixel")) return moduleId.equals("freelook") || moduleId.equals("light_overlay_mobs");
		return false;
	}

	public static boolean disallowedHere(String moduleId) {
		return current != null && disallowed(moduleId);
	}

	/* ================================================================ per-server HUD layouts + keybinds ================================================================ */

	private static void applyLayout(String pack) {
		if (!Modules.on("pack_layouts")) return;
		Map<String, float[]> saved = KyotoConfig.get().packLayouts.get(pack);
		if (saved == null) return;
		KyotoConfig.get().hudLayout.clear();
		KyotoConfig.get().hudLayout.putAll(saved);
	}

	/** Called by the HUD editor when you press Done: keeps this layout for the server pack you're on. */
	public static boolean saveLayoutNow() {
		if (current == null || !Modules.on("pack_layouts")) return false;
		saveLayoutFor(current.id());
		dev.kyoto.config.KyotoConfig.save();
		return true;
	}

	private static void saveLayoutFor(String pack) {
		if (!Modules.on("pack_layouts")) return;
		KyotoConfig.get().packLayouts.put(pack, new LinkedHashMap<>(KyotoConfig.get().hudLayout));
		KyotoConfig.save();
	}

	/** Saves your current key binds for this server's pack; they're put back every time you join it. */
	public static String saveKeys() {
		if (current == null) return "Join a server first";
		Map<String, String> keys = new LinkedHashMap<>();
		for (var k : Minecraft.getInstance().options.keyMappings) keys.put(k.getName(), k.saveString());
		KyotoConfig.get().packKeys.put(current.id(), keys);
		KyotoConfig.save();
		return "Saved " + keys.size() + " key binds for " + current.name();
	}

	public static void applyKeys() {
		if (current == null || !Modules.on("pack_keys")) return;
		Map<String, String> keys = KyotoConfig.get().packKeys.get(current.id());
		if (keys == null) return;
		try {
			var opts = Minecraft.getInstance().options;
			for (var k : opts.keyMappings) {
				String v = keys.get(k.getName());
				if (v != null) k.setKey(com.mojang.blaze3d.platform.InputConstants.getKey(v));
			}
			net.minecraft.client.KeyMapping.resetMapping();
		} catch (Throwable ignored) {
		}
	}

	/* ================================================================ friends on this server ================================================================ */

	private static void friendsHere() {
		if (!Modules.on("friends_here") || host.isEmpty()) return;
		try {
			List<String> here = dev.kyoto.social.Social.friendsOn(host);
			if (!here.isEmpty()) Notifications.push(here.size() == 1 ? here.get(0) + " is here too" : here.size() + " friends are here",
					String.join(", ", here.subList(0, Math.min(4, here.size()))), Notifications.INFO, 6000, here.get(0), "friends");
		} catch (Throwable ignored) {
		}
	}

	/* ================================================================ small JSON store per pack ================================================================ */

	static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	public static Path dir() {
		return FabricLoader.getInstance().getConfigDir().resolve("kyoto").resolve("servers");
	}

	public static JsonObject read(String name) {
		try {
			Path f = dir().resolve(name + ".json");
			if (Files.exists(f)) return JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8)).getAsJsonObject();
		} catch (Exception ignored) {
		}
		return new JsonObject();
	}

	public static void write(String name, JsonObject o) {
		try {
			Files.createDirectories(dir());
			Path tmp = dir().resolve(name + ".json.tmp");
			Files.writeString(tmp, GSON.toJson(o), StandardCharsets.UTF_8);
			Files.move(tmp, dir().resolve(name + ".json"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
		} catch (Exception ignored) {
		}
	}

	/* ================================================================ money parsing ================================================================ */

	/** "$1.2M" -> 1200000, "12,345" -> 12345, "3.4k" -> 3400. -1 if it isn't a number. */
	public static double money(String s) {
		if (s == null) return -1;
		s = s.replace("$", "").replace(",", "").replace(" ", "").trim();
		if (s.isEmpty()) return -1;
		double mult = 1;
		char last = Character.toLowerCase(s.charAt(s.length() - 1));
		if (last == 'k') mult = 1e3;
		else if (last == 'm') mult = 1e6;
		else if (last == 'b') mult = 1e9;
		else if (last == 't') mult = 1e12;
		if (mult > 1) s = s.substring(0, s.length() - 1);
		try {
			return Double.parseDouble(s) * mult;
		} catch (NumberFormatException e) {
			return -1;
		}
	}

	/** 1234567 -> "$1.23M" */
	public static String fmt(double v) {
		String sign = v < 0 ? "-" : "";
		v = Math.abs(v);
		if (v >= 1e12) return sign + "$" + trim(v / 1e12) + "T";
		if (v >= 1e9) return sign + "$" + trim(v / 1e9) + "B";
		if (v >= 1e6) return sign + "$" + trim(v / 1e6) + "M";
		if (v >= 1e3) return sign + "$" + trim(v / 1e3) + "K";
		return sign + "$" + Math.round(v);
	}

	public static String num(double v) {
		return fmt(v).replace("$", "");
	}

	private static String trim(double v) {
		String s = v >= 100 ? String.valueOf(Math.round(v)) : v >= 10 ? String.format(Locale.ROOT, "%.1f", v) : String.format(Locale.ROOT, "%.2f", v);
		if (s.contains(".")) s = s.replaceAll("0+$", "").replaceAll("\\.$", "");
		return s;
	}
}
