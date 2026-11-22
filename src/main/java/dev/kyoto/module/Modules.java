package dev.kyoto.module;

import dev.kyoto.config.KyotoConfig;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Every toggleable feature in Kyoto. The click GUI is generated from this list,
 * so adding a module here makes it show up in the menu automatically.
 */
public final class Modules {
	private Modules() {}

	public enum Category {
		HUD("HUD", "▣", "hud", "Everything drawn on your screen"),
		COMBAT("Combat", "⚔", "combat", "PvP info and feedback - nothing that plays for you"),
		UTILITY("Utility", "✚", "tools", "Small things that save you installing ten other mods"),
		CHAT("Chat", "✉", "chat", "Timestamps, mentions and less spam"),
		SOCIAL("Social", "☺", "social", "Friends, DMs, calls and your Kyoto status"),
		SERVER("Server", "⌂", "server", "TPA pop-ups, payments, per-server tweaks"),
		SURVIVAL("Survival", "❤", "survival", "Heads-ups before something breaks or you starve"),
		VISUAL("Visual", "✦", "visual", "Zoom, screen effects, pearls"),
		WORLD("World", "☀", "world", "Waypoints, video screens, sky stuff"),
		COSMETICS("Cosmetics", "♛", "cosmetics", "Capes, skins, nametags"),
		MUSIC("Music", "♫", "music", "Lyrics in the world and what's playing"),
		TITLE("Title", "▧", "title", "Painted title screens that change with the season"),
		SINGLEPLAYER("Singleplayer", "◇", "world_sp", "Extras for your own worlds"),
		STREAMING("Streaming", "◉", "stream", "Donation alerts and hiding stuff on stream"),
		PERFORMANCE("Performance", "⚡", "performance", "Squeeze out more FPS"),
		CLIENT("Client", "⚙", "client", "Fonts, colours, F3, menus"),
		ANIMATION("Animations", "✋", "hand", "How your hand and items move");

		public final String title;
		public final String icon;
		public final String tex;
		public final String subtitle;

		Category(String title, String icon, String tex, String subtitle) {
			this.title = title;
			this.icon = icon;
			this.tex = tex;
			this.subtitle = subtitle;
		}
	}

	public static final class Setting {
		public final String key;
		public final String name;
		public final String[] options;
		public final int def;
		private Runnable onChange = () -> {};

		Setting(String key, String name, int def, String... options) {
			this.key = key;
			this.name = name;
			this.def = def;
			this.options = options;
		}

		public int get() {
			int v = KyotoConfig.get().choices.getOrDefault(key, def);
			return v < 0 || v >= options.length ? def : v;
		}

		public String label() {
			return options[get()];
		}

		public void cycle(int dir) {
			int v = Math.floorMod(get() + dir, options.length);
			KyotoConfig.get().choices.put(key, v);
			KyotoConfig.save();
			onChange.run();
		}

		public Setting onChange(Runnable r) {
			this.onChange = r;
			return this;
		}
	}

	public static final class Module {
		public final String id;
		public final String name;
		public final String desc;
		public final Category category;
		public final boolean def;
		public String section = "General";
		/** Server pack this belongs to (null = works everywhere). Shown in the menu's server tab. */
		public String pack = null;
		/** Shown with a "Recommended" badge and switched on by the Recommended preset. */
		public boolean recommended = false;
		/** Shows a NEW badge in the menu. */
		public boolean fresh = false;
		/** Might not be allowed on some servers: the menu warns before turning it on while you're on one. */
		public String risk = null;
		public final List<Setting> settings = new ArrayList<>();
		/** Extra words the search box matches (e.g. "pvp", "fps"). */
		public final List<String> tags = new ArrayList<>();
		private Consumer<Boolean> onToggle = v -> {};

		Module(String id, String name, String desc, Category category, boolean def) {
			this.id = id;
			this.name = name;
			this.desc = desc;
			this.category = category;
			this.def = def;
		}

		public boolean on() {
			return KyotoConfig.get().modules.getOrDefault(id, def);
		}

		public void set(boolean v) {
			if (v == on()) return;
			KyotoConfig.get().modules.put(id, v);
			KyotoConfig.save();
			onToggle.accept(v);
		}

		public void toggle() {
			set(!on());
		}

		public Module setting(String key, String name, int def, String... options) {
			settings.add(new Setting(key, name, def, options));
			return this;
		}

		public Module tags(String... t) {
			for (String x : t) tags.add(x);
			return this;
		}

		public Module pack(String p) {
			this.pack = p;
			return this;
		}

		public Module risky(String why) {
			this.risk = why;
			return this;
		}

		public Module fresh() {
			this.fresh = true;
			return this;
		}

		public Module rec() {
			this.recommended = true;
			return this;
		}

		public Module section(String s) {
			this.section = s;
			return this;
		}

		public Module onToggle(Consumer<Boolean> c) {
			this.onToggle = c;
			return this;
		}
	}

	private static final Map<String, Module> ALL = new LinkedHashMap<>();
	private static final Map<String, Setting> SETTINGS = new LinkedHashMap<>();

	private static String curSection = "General";

	private static Module add(String id, String name, String desc, Category cat, boolean def) {
		Module m = new Module(id, name, desc, cat, def);
		m.section = curSection;
		ALL.put(id, m);
		return m;
	}

	public static Module get(String id) {
		return ALL.get(id);
	}

	public static boolean on(String id) {
		Module m = ALL.get(id);
		return m != null && m.on();
	}

	/** Option index of a setting, e.g. opt("lyrics.where") == 0 means "Sky". */
	public static int opt(String key) {
		Setting s = SETTINGS.get(key);
		return s == null ? 0 : s.get();
	}

	public static Setting setting(String key) {
		return SETTINGS.get(key);
	}

	public static List<Module> all() {
		return new ArrayList<>(ALL.values());
	}

	public static List<Module> in(Category c) {
		List<Module> out = new ArrayList<>();
		for (Module m : ALL.values()) if (m.category == c) out.add(m);
		return out;
	}

	public static int enabledCount() {
		int n = 0;
		for (Module m : ALL.values()) if (m.on()) n++;
		return n;
	}

	/** 0 = all Kyoto particles off, 1 = low, 2 = normal, 3 = high */
	public static int vfx() {
		if (!on("vfx")) return 0;
		if (on("lite_vfx")) return 1;
		return opt("vfx.quality") + 1;
	}

	/** Font choices shared by the Client font and the Lyrics font settings. */
	public static final String[] FONTS = {"Poppins", "Poppins Light", "Inter", "Inter Light", "Lora", "DejaVu Sans", "DejaVu Serif", "Mono",
			"Liberation Sans", "Liberation Serif", "Liberation Mono", "Carlito", "Caladea", "FreeSans", "FreeSerif", "Minecraft"};
	public static final String[] FONT_IDS = {"ui", "poppins_light", "inter", "inter_light", "lora", "dejavu", "dejavu_serif", "mono",
			"liberation", "liberation_serif", "liberation_mono", "carlito", "caladea", "freesans", "freeserif", null};

	private static void sec(String s) {
		curSection = s;
	}

	public static void init() {
		Category H = Category.HUD;
		sec("General");
		add("info_panel", "Info Panel", "Coords, which way you're facing and the biome, top-left.", H, true).tags("coordinates", "xyz", "biome");
		add("friends_online", "Friends Online", "A small list of your Kyoto friends who are on right now and which server they're in.", H, true).tags("friends", "online", "social")
				.setting("friends_online.empty", "When nobody's on", 0, "Show", "Hide");
		add("compass", "Compass Bar", "Compass strip along the top. Your waypoints show up on it too.", H, true).tags("direction", "waypoints");
		add("fps", "FPS", "Frames per second. Goes yellow then red when things get choppy.", H, true).tags("performance", "frames");
		add("ping", "Ping", "Your ping to the server, with little signal bars.", H, true).tags("latency", "lag");
		add("cps", "CPS", "Clicks per second for left and right click.", H, true).tags("pvp", "clicks");
		add("keystrokes", "Keystrokes", "WASD, mouse buttons and space, lit up while you press them.", H, true).tags("pvp", "keys", "wasd")
				.setting("keystrokes.mouse", "Mouse buttons", 0, "Show", "Hide")
				.setting("keystrokes.space", "Space bar", 0, "Show", "Hide");
		add("potions", "Potion Effects", "Every effect you have, its level and how long is left.", H, true).tags("effects", "buffs");
		add("armor_hud", "Armor", "Durability of each armor piece as small bars.", H, true).tags("durability");
		add("clock", "Clock", "Your real-life time, so you know when to go to bed.", H, false).tags("time")
				.setting("clock.format", "Format", 0, "24h", "12h");
		add("pickup_feed", "Pickup Feed", "\"+12 Cobblestone\" pop-ups when stuff lands in your inventory.", H, true).tags("items", "loot");
		add("durability_hud", "Durability", "How worn the tool in your hand (and off hand) is, as a % and a bar.", H, false).tags("durability", "tool", "sword");
		add("hud_master", "Show HUD", "Turns the whole Kyoto HUD on or off. H does the same.", H, true);

		add("notch", "Notch", "A Dynamic Island at the top of your screen: the song playing, pop-ups, friends joining and switches you flip all show up in it.", H, true).rec().tags("dynamic island", "macos", "notifications", "music")
				.setting("notch.idle", "When idle", 0, "Clock", "FPS", "Small dot", "Hidden")
				.setting("notch.music", "Music", 0, "When the song changes", "Always", "Never")
				.setting("notch.hover", "Hover to open", 0, "On", "Off");

		sec("Look");
		add("hud_look", "HUD Look", "How every HUD box looks: Kyoto (soft glass cards), Glass, Minimal (no boxes) or Accent.", H, true).rec().tags("style", "theme")
				.setting("hud.look", "Style", 0, "Kyoto", "Glass", "Minimal", "Accent")
				.setting("info.labels", "Info panel labels", 0, "Icons", "Words");
		add("hud_style", "HUD Size", "Make the left HUD column bigger, smaller or more see-through.", H, true).tags("scale", "opacity")
				.setting("hud.scale", "Scale", 2, "75%", "90%", "100%", "110%", "125%")
				.setting("hud.opacity", "Opacity", 3, "40%", "60%", "80%", "100%");
		sec("Extras");
		add("custom_crosshair", "Custom Crosshair", "Use the crosshair you made in the Crosshair editor.", H, false).tags("crosshair", "pvp");
		add("waypoint_labels", "Waypoint Labels", "Name, distance and who made it, floating over each waypoint.", H, true).tags("waypoints");
		add("totem_counter", "Totem Count", "How many totems you're carrying right now.", H, false).tags("pvp", "totem");
		add("coords_streamer", "Hide Coordinates", "Blanks out XYZ in the info panel. For streaming.", H, false).tags("streamer", "privacy");

		Category CB = Category.COMBAT;
		sec("Feedback");
		add("hit_sparks", "Hit Effect", "Plays the hit effect you made in the VFX creator when you hit something.", CB, true).tags("vfx", "particles");
		add("kill_fx", "Kill FX", "A Kyoto effect where something you killed dies: sakura burst, starfall, heart pop, soul rise or shockwave.", CB, true).tags("kill", "vfx", "effect")
				.setting("killfx.style", "Style", 0, "Sakura burst", "Starfall", "Heart pop", "Soul rise", "Shockwave");
		add("explosion_fx", "Explosion FX", "Crystals and TNT that blow up near you get a bright flash, a ring and sparks. Visual only.", CB, true).tags("crystal", "tnt", "explosion", "vfx")
				.setting("explosion.smoke", "Smoke cloud", 0, "On", "Off");
		add("kill_effect", "Kill Effect", "Your kill effect from the VFX creator.", CB, true).tags("vfx", "particles");
		add("hit_marker", "Hit Marker", "A small X flashes on your crosshair when a hit lands.", CB, true).tags("crosshair")
				.setting("hitmarker.size", "Size", 1, "Small", "Normal", "Big");
		add("hit_sound", "Hit Sound", "An extra little sound on every hit.", CB, false).tags("sound")
				.setting("hitsound.type", "Sound", 0, "Click", "Bell", "Pling", "Chime");
		add("kill_sound", "Kill Sound", "A sound when the thing you hit dies.", CB, true).tags("sound");
		add("click_sounds", "Click Sounds Pack", "Custom sounds for your hits, crits and kills. Pick a pack.", CB, false).tags("sound", "hitsound", "pack")
				.setting("clicks.pack", "Pack", 0, dev.kyoto.feature.ClickSounds.PACKS)
				.setting("clicks.hit", "Hits", 0, "On", "Off")
				.setting("clicks.crit", "Crits", 0, "On", "Off")
				.setting("clicks.kill", "Kills", 0, "On", "Off")
				.setting("clicks.volume", "Volume", 2, "25%", "50%", "75%", "100%")
				.setting("clicks.pitch", "Pitch wobble", 0, "On", "Off");
		add("damage_numbers", "Damage Numbers", "Numbers pop off whatever you hit showing how much health it lost.", CB, true).tags("damage", "hearts", "pvp")
				.setting("dmg.style", "Style", 0, "Hearts", "Health");
		sec("Info");
		add("combo_counter", "Combo Counter", "Counts your hits in a row. Resets when you get hit.", CB, true).tags("pvp");
		add("target_hud", "Target HUD", "Who you're fighting: head, health, armor and distance.", CB, true).tags("pvp", "opponent")
				.setting("target.health", "Health bar", 0, "On", "Off")
				.setting("target.armor", "Armor", 0, "On", "Off");
		add("hitboxes", "Hitboxes", "Boxes around players and mobs, like F3+B but nicer. Nobody behind a wall gets an outline.", CB, false).tags("f3", "pvp", "hitbox")
				.setting("hitbox.who", "Show on", 0, "Players", "Players + mobs", "Everything")
				.setting("hitbox.color", "Colour", 0, "Silver", "White", "Red", "Mint", "Accent", "Rainbow", "Pink", "Blue", "Team colour", "Health")
				.setting("hitbox.look", "Look line", 0, "On", "Off")
				.setting("hitbox.range", "Range", 1, "16 blocks", "32 blocks", "48 blocks")
				.risky("Some servers don't allow hitboxes. Check the server's rules.");
		add("totem_tracker", "Totem Pops", "Tells you when someone nearby pops a totem and keeps a count for the fight.", CB, true).tags("totem", "pvp");
		add("cooldown_indicator", "Attack Cooldown", "Thin bar under the crosshair that fills up when your next swing does full damage.", CB, false).tags("pvp", "sword");
		add("pearl_cooldown", "Pearl Cooldown", "Small bar under the crosshair after you throw a pearl.", CB, true).tags("pearl");
		sec("Movement");
		add("toggle_sprint", "Toggle Sprint", "Sprint stays on without holding the key. It's the vanilla option.", CB, false).tags("sprint");
		add("toggle_sneak", "Toggle Sneak", "Same thing for sneaking.", CB, false).tags("sneak", "crouch");
		sec("Hits");
		add("kill_banner", "Kill Banner", "A soft banner slides in with who you took out and your streak.", CB, true).tags("kill", "streak", "finisher").fresh()
				.setting("killbanner.where", "Where", 0, "Top", "Centre", "Bottom")
				.setting("killbanner.sound", "Sound", 0, "Chime", "Off");
		add("health_bars", "Health Bars", "Rounded health bars over players and mobs near you. They slide smoothly when they take damage.", CB, true).tags("health", "hearts", "hp").fresh()
				.setting("healthbars.who", "Show on", 0, "Players", "Players + mobs", "Only what I hit")
				.setting("healthbars.numbers", "Numbers", 0, "Hearts", "Percent", "Off");
		add("shield_status", "Shield Status", "A small shield over players who are blocking right now.", CB, true).tags("shield", "axe", "block").fresh();
		add("fight_recap", "Death Recap", "When you die: who got you, with what, and how much health they had left.", CB, true).tags("death", "recap", "killed").fresh();
		add("no_hurt_cam", "No Hurt Shake", "Stops the camera wobble when you take damage.", CB, false).tags("camera");
		add("pop_sounds", "Shield + Totem Sounds", "A sound when a shield blocks, when a shield gets broken and when someone near you pops a totem.", CB, true).tags("shield", "totem", "sound", "pvp")
				.setting("popsound.totem", "Totem pop", 2, dev.kyoto.extra.CombatSounds.SOUNDS)
				.setting("popsound.shield", "Shield block", 0, dev.kyoto.extra.CombatSounds.SOUNDS)
				.setting("popsound.break", "Shield break", 3, dev.kyoto.extra.CombatSounds.SOUNDS);

		Category U = Category.UTILITY;
		sec("Tooltips");
		add("shulker_preview", "Shulker Preview", "Hover a shulker box to see what's inside without placing it.", U, true).tags("tooltip", "inventory");
		add("rarity_glow", "Item Rarity Glow", "Rare, epic and enchanted items get a coloured line in chests and your inventory.", U, false).tags("rarity", "enchanted", "inventory")
				.setting("rarity.which", "Show", 0, "Everything special", "Rare + epic only");
		add("replay_buffer", "Replay Buffer", "Keeps the last 15-60 seconds of your screen. Press the Save clip key to save it as a video (kyoto/clips). Costs a little FPS.", U, false).tags("clip", "record", "video", "replay", "medal")
				.setting("clip.length", "Length", 1, "15 seconds", "30 seconds", "60 seconds")
				.setting("clip.quality", "Size", 1, "480p", "720p", "1080p")
				.setting("clip.fps", "Smoothness", 1, "10 fps", "15 fps", "24 fps");
		add("durability_tooltip", "Durability Numbers", "Shows exact durability, like 1204 / 2031, on tools and armor.", U, true).tags("tooltip");
		add("food_tooltip", "Food Info", "Hunger and saturation on food tooltips.", U, true).tags("tooltip", "food", "appleskin");
		sec("Handy");
		add("auto_reconnect", "Auto Reconnect", "Adds a Reconnect button with a countdown when you get kicked or the server restarts.", U, true).tags("disconnect", "rejoin")
				.setting("reconnect.delay", "Wait", 1, "5s", "10s", "30s", "Button only");

		Category C = Category.CHAT;
		sec("General");
		add("timestamps", "Timestamps", "Puts the time, like [14:32], in front of every message.", C, true).tags("time");
		add("chat_copy", "Copy Messages", "Right-click a message (with chat open) to copy it.", C, true).tags("copy", "clipboard");
		add("mentions", "Mentions", "Pings you when someone says your name. You can add extra words too.", C, true).tags("ping", "name");
		add("muted_words", "Muted Words", "Hides messages with words you've muted (/kyoto mute).", C, true).tags("filter", "spam");
		add("streamer_mode", "Hide My Name", "Your name shows as \"You\" in chat.", C, false).tags("streamer", "privacy");

		Category SO = Category.SOCIAL;
		sec("Kyoto");
		add("kyoto_network", "Kyoto Network", "Connects you to Kyoto on its own when the game starts. Friends, DMs, capes and screens need it.", SO, true).tags("online", "login", "server");
		add("friend_join", "Let Friends Join Me", "Friends get a Join button that puts them on the same server as you.", SO, true).tags("join", "friends");
		add("friend_join_alerts", "Friend Alerts", "A pop-up when a friend comes online.", SO, true).tags("friends", "notifications");
		add("dm_alerts", "DM Pop-ups", "A pop-up when someone messages you, with a sound.", SO, true).tags("messages", "dm", "notifications");
		add("calls", "Friend Calls & Voice Rooms", "Voice calls with friends and group Voice Rooms (Kyoto Hub > Voice), even on different servers. Needs Simple Voice Chat installed.", SO, true).tags("voice", "call", "vc", "simple voice chat", "voice rooms")
				.setting("call.mode", "Mic", 0, "Always on", "Push to talk")
				.setting("call.volume", "Their volume", 2, "50%", "75%", "100%", "150%")
				.setting("voice.sensitivity", "Voice Rooms: mic sensitivity", 0, "High", "Medium", "Low");
		sec("Profiles");
		add("profile_video", "Profile Videos", "Background videos on profile pages, streamed smoothly with the video tools.", SO, true).tags("video")
				.setting("video.quality", "Quality", 1, "480p", "720p HD", "1080p Full HD", "1440p (strong PC)")
				.setting("video.fps", "Frame rate", 0, "30 fps", "60 fps", "24 fps");
		add("profile_music", "Profile Music", "Plays someone's profile song when you open their profile.", SO, true).tags("music");
		add("discord_rpc", "Discord Status", "Shows you're playing Kyoto Client (and where) on Discord. No setup needed.", SO, true).tags("discord", "rich presence")
				.setting("discord.server", "On servers", 1, "Server name", "Server + game", "Server + money made");
		add("share_music", "Share Music", "Shows the song you're playing on your website profile (Spotify widget). Off while streaming.", SO, true).tags("spotify", "profile", "widget", "now playing");
		add("live_share", "Share Live Location", "Your friends on the same server see a marker where you are. Only friends, only that server, only while it's on.", SO, false).tags("location", "friends", "waypoint", "share");
		add("prox_voice", "Nearby Voice", "Talk to Kyoto players near you on any server. The closer they are, the louder. Needs Simple Voice Chat.", SO, false).tags("voice", "proximity", "talk", "mic")
				.setting("prox.range", "Range", 1, "16 blocks", "32 blocks", "48 blocks", "64 blocks");
		add("daily_reward", "Daily Reward", "Free Kyoto coins the first time you play each day. Bigger every day in a row.", SO, true).tags("coins", "reward", "streak");
		add("weekly_challenges", "Weekly Challenges", "3 new challenges every week (mine, walk, fight, fish...). Each one gives coins.", SO, true).tags("challenges", "coins", "quests");

		Category S = Category.SERVER;
		sec("Alerts");
		add("tpa_alerts", "TPA Pop-ups", "Someone sends you a /tpa, you get a pop-up with their head.", S, true).tags("teleport")
				.setting("tpa.buttons", "Accept/Deny buttons", 0, "In chat", "Off");
		add("pay_alerts", "Payment Pop-ups", "Shows who paid you and how much. Only when you get paid.", S, true).tags("money", "economy");
		add("location_share", "Share Location", "Press U to post where you are in chat.", S, true).tags("coordinates");
		sec("Servers");
		add("autogg", "Auto GG", "Types gg when a game ends on Hypixel. It's just a chat message, so it's allowed.", S, false).tags("hypixel");

		/* ================= Server Packs (show up in the menu's server tab) ================= */
		sec("Server Packs");
		add("server_packs", "Server Packs", "Turns on extras made for the server you join (DonutSMP, Hypixel, Lifesteal...). Pick another in the server tab.", S, true).rec().tags("donut", "hypixel");
		add("friends_here", "Friends On This Server", "Pop-up when Kyoto friends are on the server you just joined.", S, true).tags("friends");
		add("stream_live", "Live Stats For Overlays", "Sends your server money, bounty, kills and deaths to your private stream overlay link.", S, false).tags("stream", "obs", "overlay");

		String D = "donut";
		sec("DonutSMP · Money");
		add("donut_money", "Money HUD", "Your balance, money made this session and money per hour.", S, true).pack(D).rec().tags("balance", "economy");
		add("donut_shards", "Shards HUD", "Shards, shards per hour and the time until your next AFK payout.", S, true).pack(D).rec().tags("afk");
		add("donut_prices", "Price Tooltips", "Hover an item: what it usually sells for on the AH, on orders and with /sell.", S, true).pack(D).rec().tags("ah", "auction", "worth");
		add("donut_watch", "Market Watch", "/kyoto watch <item> <price>: pop-up when the AH shows it that cheap.", S, true).pack(D).tags("auction");
		add("donut_orders", "My Orders", "Your open orders and how full they are (open your orders once).", S, true).pack(D).tags("orders");
		add("donut_order_toast", "Order Filled Pop-up", "Pop-up when one of your orders gets filled.", S, true).pack(D).tags("orders");
		add("donut_ah_toast", "AH Sold Pop-up", "Pop-up with the price when something of yours sells on the AH.", S, true).pack(D).tags("auction");
		add("donut_flips", "Flip Notebook", "Logs what you buy and sell on the AH and adds up your profit (/kyoto flips).", S, false).pack(D).tags("profit");
		sec("DonutSMP · PvP + safety");
		add("donut_combat", "Combat Timer", "Countdown until you're out of combat.", S, true).pack(D).rec().tags("pvp", "combat log");
		add("donut_combat_block", "Combat Command Warning", "Stops /home, /tpa, /spawn while you're in combat (they'd fail anyway). Send again to force it.", S, true).pack(D).tags("pvp");
		add("donut_bounty_board", "Bounty Board", "Biggest bounties you've seen, and the one on you.", S, true).pack(D).tags("bounty");
		add("donut_bounty_alert", "Bounty On You Alert", "Big warning when someone puts a bounty on you.", S, true).pack(D).rec().tags("bounty");
		add("donut_death_recap", "Death Recap", "Who killed you, what your items were worth and where (waypoint).", S, true).pack(D).tags("death");
		add("donut_duels", "Duel Stats", "Wins and losses against each player.", S, true).pack(D).tags("pvp");
		add("donut_scam_guard", "Scam Guard", "Warns you when someone uses classic scam lines.", S, true).pack(D).rec().tags("scam", "trade");
		add("donut_pay_confirm", "Pay Confirm", "Asks before big /pay amounts so a typo doesn't cost you.", S, true).pack(D).rec().tags("pay", "money")
				.setting("payconfirm.over", "Ask over", 1, "$100K", "$1M", "$10M", "$100M");
		sec("DonutSMP · Places + team");
		add("donut_team", "Team HUD", "Your team and who's online.", S, true).pack(D).tags("team");
		add("donut_chat_clean", "Chat Cleaner", "Hides join messages, AH spam and server ads, and repeats.", S, false).pack(D).tags("spam")
				.setting("chatclean.what", "Hide", 2, "Joins", "Ads + AH", "Both");
		add("team_chat_key", "Team Chat Key", "A key (Controls > Kyoto) that sends everything you type to team chat until you press it again.", S, true).pack("smp").tags("team")
				.setting("teamchat.cmd", "Command", 0, "/team chat", "/tc", "/t chat", "/teamchat", "/party chat", "/pc");

		String HY = "hypixel";
		sec("Hypixel");

		String LS = "lifesteal";
		sec("Lifesteal / PvP SMP");

		sec("PvP Practice");

		sec("MCC Island");

		String SMP = "smp";
		sec("Any SMP");

		Category SV = Category.SURVIVAL;
		sec("Warnings");
		add("warn_armor", "Armor Warning", "Lets you know when armor is under 10%.", SV, true).tags("durability");
		add("warn_tool", "Tool Warning", "Lets you know before your tool breaks.", SV, true).tags("durability");
		add("warn_health", "Low Health", "Warns you at 30% health.", SV, true).tags("hearts");
		add("warn_hunger", "Hunger", "Warns you before you can't sprint anymore.", SV, true).tags("food");
		add("warn_totem", "Totem Reminders", "Counts your totem pops and nags you if one isn't in your offhand.", SV, true).tags("totem");
		add("warning_sounds", "Warning Sounds", "Plays a sound with each warning.", SV, true).tags("sound");

		Category V = Category.VISUAL;
		sec("Camera");
		add("fullbright", "Fullbright", "See in caves and at night without torches.", V, false).tags("gamma", "brightness")
				.risky("Some servers count fullbright as an unfair advantage. Use at your own risk.");
		add("freelook", "Freelook", "Hold Left Alt to look around without turning. Turns itself off on servers that don't allow it.", V, true).rec().tags("perspective", "look around", "360", "alt")
				.setting("freelook.mode", "Key", 0, "Hold", "Press to toggle")
				.setting("freelook.view", "Camera", 0, "Third person", "Stay as is")
				.risky("A few servers don't allow freelook. Check the server's rules.");
		add("block_outline", "Block Outline", "Nicer outline on the block you're looking at: thin, thick, corners or glow, in any colour.", V, false).tags("outline", "selection", "highlight")
				.setting("outline.style", "Style", 2, dev.kyoto.extra.WorldOverlays.OUTLINE_STYLES)
				.setting("outline.color", "Colour", 2, dev.kyoto.extra.WorldOverlays.OUTLINE_COLORS);
		add("low_fire", "Low Fire", "Moves the fire on your screen down so you can see when you're burning.", V, true).tags("fire", "burning", "pvp")
				.setting("lowfire.amount", "How low", 1, "A bit", "Low", "Very low");
		add("zoom", "Zoom", "Hold C to zoom. Scroll while zooming to go further, up to 50x.", V, true).tags("zoomify", "optifine")
				.setting("zoom.amount", "Start zoom", 1, "2x", "3x", "4x", "6x", "10x")
				.setting("zoom.transition", "Transition", 0, "Smooth", "Ease out", "Elastic", "Linear", "Instant")
				.setting("zoom.speed", "Speed", 1, "Fast", "Normal", "Slow")
				.setting("zoom.mode", "Key mode", 0, "Hold", "Toggle")
				.setting("zoom.scroll", "Scroll zoom", 0, "On", "Off")
				.setting("zoom.relative_sens", "Sensitivity", 0, "Scaled", "Normal")
				.setting("zoom.cinematic", "Cinematic camera", 1, "On", "Off")
				.setting("zoom.overlay", "Vignette", 0, "On", "Off");
		sec("Screen effects");
		add("low_health_pulse", "Heartbeat", "Edges pulse red when you're low.", V, true).tags("health");
		add("atmosphere", "Atmosphere", "Tiny motes of light floating in the air around you.", V, false).tags("particles", "ambient", "dust");
		sec("Pearls");
		add("pearl_trajectory", "Pearl Path", "Shows where your pearl will land before you throw it. J toggles it.", V, true).tags("pearl", "trajectory");
		add("pearl_trail", "Pearl Trail", "Purple sparkles behind thrown pearls.", V, true).tags("pearl", "particles");

		Category W = Category.WORLD;
		sec("Video screens");
		add("video_screens", "Video Screens", "Put a screen anywhere and play a YouTube link on it. Kyoto players on the server watch along with you.", W, true).tags("youtube", "video", "tv", "cinema")
				.setting("screens.quality", "Quality", 2, "360p", "480p", "720p HD", "1080p Full HD")
				.setting("screens.show", "Show as", 0, "Screen in the world", "Mini player on my screen")
				.setting("screens.volume", "Volume", 2, "25%", "50%", "75%", "100%");
		sec("Waypoints");
		add("waypoint_beams", "Waypoint Beams", "Coloured pillars at your waypoints so you can find them from far away.", W, true).tags("waypoints");
		add("death_waypoint", "Death Waypoint", "Saves where you died.", W, true).tags("waypoints", "death");
		sec("World");
		add("light_overlay", "Light Levels", "Numbers on the ground: red 0 = mobs can spawn there. Use the Light levels key to show/hide.", W, true).tags("light level", "spawn", "torch", "mobs")
				.setting("light.show", "Show", 0, "Only dark spots", "Every spot")
				.setting("light.range", "Range", 1, "8 blocks", "12 blocks", "16 blocks");
		add("chunk_borders", "Chunk Borders", "Clean lines on the chunk you're in. Use the Chunk borders key to show/hide.", W, true).tags("chunk", "f3+g", "borders");
		add("slime_chunks", "Slime Chunks", "Green grid where slimes can spawn. Needs the seed (/kyoto seed). Map: /kyoto slime.", W, true).tags("slime", "seed", "farm");
		sec("Sky");
		add("skybox", "Skybox", "A real, full skybox (needs the free Nuit mod; the Kyoto Launcher can install it). Only you see it.", W, false).tags("sky", "skybox", "nuit")
				.setting("skybox.style", "Sky", 0, "Sakura dusk", "Starry night", "Galaxy", "Pastel day")
				.setting("skybox.when", "Show it", 0, "Day / night", "All the time");
		add("sky_fx", "Custom Sky", "Pick a different sky for day and for night. Only you see it. Tools > Sky + Time.", W, true).tags("stars", "aurora", "galaxy", "rainbow", "skybox")
				.setting("sky.day", "Day sky", 0, "Vanilla", "Rainbow", "Cherry petals", "Big clouds", "Sky lanterns", "Birds")
				.setting("sky.night", "Night sky", 5, "Vanilla", "Galaxy", "Aurora", "Shooting stars", "Nebula", "Aurora + stars", "Dreamy galaxy", "Everything", "Kyoto Starry")
				.setting("sky.amount", "Amount", 1, "Calm", "Normal", "Lots")
				.setting("sky.tint_day", "Day colours", 0, "Vanilla", "Match my background", "Golden hour", "Pastel", "Sakura", "Crisp blue")
				.setting("sky.tint_night", "Night colours", 1, "Vanilla", "Match my background", "Midnight blue", "Violet", "Rose", "Ember");
		add("custom_time", "My Time of Day", "Make it day or night just for you. Only visual: the server, mobs and beds still use the real time.", W, false).tags("time", "night", "day", "sun", "moon")
				.setting("time.mode", "Mode", 0, "Fixed", "Fast day cycle")
				.setting("time.speed", "Day length", 1, "2 min", "5 min", "10 min", "20 min");

		Category CO = Category.COSMETICS;
		sec("General");
		add("cape", "Cape", "Your cape, PNG or animated GIF. Pick it in Cosmetics.", CO, true).tags("cape");
		add("custom_skin", "Kyoto Skin", "A skin only Kyoto players see. To change your real skin, use Cosmetics > Skins > Set as my Minecraft skin.", CO, true).tags("skin");
		sec("Nametags");
		add("own_nametag", "My Nametag", "See your own nametag in third person.", CO, false).tags("nametag", "f5")
				.setting("nametag.style", "Style", 0, "Kyoto glass", "Vanilla-like")
				.setting("nametag.health", "Show health", 1, "On", "Off");
		add("nametag_font", "Nametag Font", "Changes the font on everyone's nametags. Only you see it.", CO, false).tags("nametag", "font")
				.setting("nametag.font", "Font", 0, FONTS);
		add("nicknames", "Nickname Tags", "Give friends nicknames that only you see above their heads. /kyoto nick <player> <nickname>", CO, true).tags("nickname", "nametag", "friends")
				.setting("nick.real", "Real name", 0, "Small under it", "Hidden")
				.setting("nick.color", "Colour", 0, "Accent", "White", "Gold", "Pink", "Aqua", "Green")
				.setting("nick.chat", "In chat too", 0, "On", "Off");
		add("kyoto_badges", "Kyoto Badges", "A small Kyoto logo next to Kyoto players' names, above their heads and in chat.", CO, true).tags("badge", "nametag")
				.setting("badge.where", "Show in", 0, "Nametags + chat", "Nametags", "Chat")
				.setting("badge.tab", "In the TAB list", 0, "Show", "Hide")
				.setting("badge.size", "Size", 1, "Small", "Normal", "Big");
		sec("Accessories");
		add("accessory", "Particle Accessory", "A particle effect around you. Pick one in Cosmetics > Particles.", CO, true).tags("particles");
		add("pets", "Kyoto Pets", "A 3D pet that follows you around, with real animations. Kyoto players near you see it. Pick one in Cosmetics > Pets.", CO, true).tags("pet", "buddy", "companion");
		add("emotes", "Emotes", "Pop a sticker above your head. Kyoto players near you see it. Bind the Emotes key.", CO, true).tags("emote", "sticker", "wave");
		add("wardrobe", "Wardrobe", "3D hats, wings and halos from Cosmetics > Wardrobe. Smooth, animated and made for Kyoto.", CO, true).tags("hat", "wings", "halo", "kasa", "crown", "ears").fresh();
		add("knives", "Knives", "Your knife skins as real 3D knives in first person: draw spin, slashes, sway and an inspect (G). Pick them in Tools > Knives.", CO, true).tags("knife", "karambit", "butterfly", "inspect", "cs2").fresh()
				.setting("knives.quality", "Quality", 1, "Fast", "Balanced", "Sharp")
				.setting("knives.arm", "Show arm", 0, "On", "Off")
				.setting("knives.sway", "Sway", 0, "On", "Off");
		add("sword_trails", "Sword Trails", "A glowing ribbon follows your blade when you swing.", CO, true).tags("trail", "swing", "sword", "slash").fresh()
				.setting("trails.color", "Colour", 0, "Sakura", "Rainbow", "White", "Frost");
		add("dances", "Dance Emotes", "Hold Z for the emote wheel: wave, floss, robot, spin and more. Moving stops it. Kyoto players near you see it.", CO, true).tags("emote", "dance", "wheel").fresh()
				.setting("dance.camera", "Camera", 0, "Show me dancing", "Stay first person");
		add("pet_sounds", "Pet Sounds", "Your pet makes soft little sounds now and then.", CO, false).tags("pet", "sound").fresh();
		add("custom_cosmetics", "Custom Cosmetics", "Cosmetics made in the Kyoto Cosmetic Builder (.kycos). /kyoto kycos to wear one.", CO, true).tags("kycos", "builder");
		add("others_cosmetics", "Other People's Cosmetics", "See other Kyoto players' capes, skins and accessories.", CO, true).tags("capes");

		Category M = Category.MUSIC;
		sec("Lyrics");
		add("lyrics", "Lyrics", "Synced lyrics floating in the world. You can walk right past them.", M, true).tags("spotify", "karaoke")
				.setting("lyrics.mode", "Lyrics style", 1, "Sky", "Floating", "Screen")
				.setting("lyrics.distance", "Distance", 1, "Near", "Medium", "Far")
				.setting("lyrics.follow", "Follow your view", 1, "On", "Off")
				.setting("lyrics.lock", "View lock", 0, "On", "Off")
				.setting("lyrics.walls", "Hide behind walls", 0, "On", "Off")
				.setting("lyrics.pause", "Fade when paused", 0, "On", "Off")
				.setting("lyrics.f1", "Show in F1", 0, "On", "Off")
				.setting("lyrics.offset", "Sync offset", 3, "-0.6s", "-0.4s", "-0.2s", "0", "+0.2s", "+0.4s", "+0.6s");
		sec("Look");
		add("music_visualizer", "Music Visualizer", "Bars that dance while music plays. (They follow the beat feel, Spotify doesn't share the real audio.)", M, false).tags("bars", "visualizer", "spectrum");
		sec("Player");
		add("lyrics_look", "Lyrics Look", "Colour, font, size and effects for the lyrics.", M, true).tags("font", "colour")
				.setting("lyrics.style", "Colour", 0, dev.kyoto.music.LyricsHud.STYLE_NAMES)
				.setting("lyrics.font", "Font", 0, FONTS)
				.setting("lyrics.size", "Size", 1, "Small", "Normal", "Big", "Huge")
				.setting("lyrics.anim", "Animation", 0, dev.kyoto.music.LyricsHud.ANIM_NAMES)
				.setting("lyrics.effect", "Effects", 0, dev.kyoto.music.LyricsHud.EFFECT_NAMES);
		add("now_playing", "Now Playing", "Song pill at the top of the screen with a progress bar.", M, true).tags("spotify");
		add("album_art", "Album Art", "Downloads album covers for the menu player.", M, true);

		Category T = Category.TITLE;
		sec("Title screen");
		add("title_screen", "Kyoto Title Screen", "Sakura branches, drifting petals and the Kyoto blossom.", T, true).tags("menu", "background")
				.setting("title.theme", "Logo", 0, "Auto (holidays)", "Plain", "New Year", "Valentine's", "Easter", "Halloween", "Thanksgiving", "Christmas")
				.setting("title.particles", "Falling petals", 0, "On", "Off")
				.setting("title.motion", "Parallax", 0, "On", "Off")
				.setting("title.splash", "Tagline", 0, "On", "Kyoto", "Off");
		add("kyoto_loading", "Kyoto Loading Screen", "Replaces the Mojang screen: a sakura blossom opens petal by petal while Minecraft loads.", T, true).tags("loading", "splash", "mojang");
		sec("Holiday logos (Auto)");
		add("title_valentines", "Valentine's Day", "A rose-red blossom with a heart, Feb 7 to 14.", T, true);
		add("title_easter", "Easter", "A lilac blossom, the week of Easter.", T, true);
		add("title_halloween", "Halloween", "A pumpkin-orange blossom, Oct 15 to 31.", T, true);
		add("title_thanksgiving", "Thanksgiving", "An amber maple-coloured blossom, Thanksgiving week.", T, true);
		add("title_christmas", "Christmas", "A red and gold blossom, Dec 1 to 26.", T, true);
		add("title_newyear", "New Year", "A champagne-gold blossom, Dec 27 to Jan 2.", T, true);
		sec("General");

		Category SPC = Category.SINGLEPLAYER;
		sec("HUD");
		add("session_stats", "Session Stats", "Blocks broken, mobs killed, deaths and distance since you loaded in.", SPC, false).tags("stats");
		add("saturation", "Saturation", "Shows the hidden saturation part of your hunger.", SPC, false).tags("food", "appleskin");
		sec("Helpers");
		add("death_recap", "Death Recap", "Where you died and how many times this session.", SPC, true).tags("death");

		Category ST = Category.STREAMING;
		sec("Alerts");
		add("stream_alerts", "Stream Alerts", "Donations, Super Chats, bits and subs show up in game. Set it up in Tools > Streamer.", ST, false).tags("streamlabs", "donations")
				.setting("stream.position", "Position", 0, "Top", "Middle", "Bottom")
				.setting("stream.duration", "Show for", 1, "5s", "8s", "12s")
				.setting("stream.sound", "Sound", 0, "On", "Off")
				.setting("stream.messages", "Messages", 0, "Show (filtered)", "Hide")
				.setting("stream.follows", "Follows", 1, "On", "Off")
				.setting("tiktok.min", "TikTok gifts", 0, "All", "5+ coins", "100+ coins")
				.setting("tiktok.follows", "TikTok follows", 0, "On", "Off")
				.setting("tiktok.shares", "TikTok shares", 1, "On", "Off")
				.setting("tiktok.likes", "Like milestones", 0, "On", "Off");
		sec("Privacy");
		add("streamer_privacy", "Streamer Mode", "Hides stuff you don't want on stream.", ST, false).tags("privacy", "coordinates")
				.setting("privacy.coords", "Coordinates", 0, "Hide", "Show")
				.setting("privacy.server", "Server address", 0, "Hide", "Show")
				.setting("privacy.name", "Your name in chat", 1, "Hide", "Show")
				.setting("privacy.chatcoords", "Coords in chat", 0, "Hide", "Show")
				.setting("privacy.swears", "Swears", 0, "Hide", "Show")
				.setting("privacy.slurs", "Slurs", 0, "Hide", "Show")
				.setting("privacy.personal", "Personal info", 0, "Hide", "Show");

		Category P = Category.PERFORMANCE;
		sec("Presets");
		add("perf_preset", "Performance Preset", "One-click video settings. Turn it off and your old settings come back.", P, false).tags("fps", "optimization")
				.setting("perf.preset", "Preset", 1, "Max FPS", "Balanced", "Quality");
		add("dynamic_rd", "Dynamic Render Distance", "Drops render distance when FPS tanks and brings it back when things smooth out.", P, false).tags("fps", "chunks")
				.setting("drd.target", "Target FPS", 1, "60", "90", "120", "144", "240");
		add("bg_fps", "Background FPS Cap", "Caps FPS when you tab out. Saves power and keeps your PC cooler.", P, true).tags("fps", "battery")
				.setting("bgfps.cap", "Cap", 1, "5", "15", "30");
		sec("Tweaks");
		add("min_particles", "Fewer Particles", "Sets vanilla particles to Minimal.", P, false).tags("fps");
		add("no_shadows", "No Mob Shadows", "Turns off the round shadows under mobs.", P, false).tags("fps");
		add("no_clouds", "No Clouds", "Turns clouds off.", P, false).tags("fps");
		add("fast_biome", "Fast Biome Blend", "Turns off biome colour blending. Chunks load a lot faster.", P, false).tags("fps", "chunks");
		add("entity_distance", "Shorter Entity Distance", "Draws mobs and players out to 75% of the normal distance.", P, false).tags("fps");
		add("lite_vfx", "Lite VFX", "Keeps every Kyoto effect on low particle counts.", P, false).tags("fps", "particles");

		Category CL = Category.CLIENT;
		sec("Look");
		add("custom_font", "Font", "The font for Kyoto menus. Off uses the Minecraft font.", CL, true).tags("font")
				.setting("font.family", "Font", 0, FONTS);
		add("accent", "Accent Colour", "Colour for switches, buttons and highlights. Custom uses the Theme editor.", CL, true).tags("colour", "theme")
				.setting("accent.color", "Colour", 0, "Sakura", "Blush", "Rose", "Peach", "Lilac", "Sky", "Mint", "Custom", "Shimmer");
		add("dark_mode", "Dark Mode", "Dims the white glass for night time. Still soft and pink, never pitch black.", CL, false).tags("dark", "night", "theme");
		add("liquid_glass", "Liquid Glass", "Glass that catches the light: highlights slide towards your mouse and a soft shimmer moves across panels.", CL, true).tags("glass", "shine", "macos", "dynamic")
				.setting("glass.strength", "Strength", 1, "Subtle", "Normal", "Strong");
		sec("Menus");
		add("kyoto_vanilla_menus", "Kyoto Menus", "Minecraft's own menus (pause, options, singleplayer, multiplayer...) in the Kyoto style. Realms stays as it is.", CL, true).tags("menu", "escape", "pause", "options", "multiplayer");
		add("kyoto_menus", "Kyoto Buttons", "Kyoto buttons on the title and pause screens.", CL, true).tags("menu");
		add("menu_style", "Menu Background", "What's behind the Kyoto menu while it's open.", CL, true).tags("blur", "dim", "background").fresh()
				.setting("menu.bg", "Background", 0, "Blur + dim", "Dim", "Clear", "My video")
				.setting("menu.dim", "Dim", 1, "Light", "Normal", "Dark");
		add("menu_music", "Menu Music", "Plays your MP3 (Settings > Menu background) while the Kyoto menu or title screen is open.", CL, false).tags("music", "mp3", "background").fresh()
				.setting("menumusic.volume", "Volume", 1, "20%", "40%", "60%", "80%");
		add("menu_tilt", "Menu Tilt", "The menu leans a little towards your mouse, like it's floating.", CL, true).tags("parallax", "3d", "float").fresh();
		add("menu_petals", "Menu Petals", "Sakura petals drift behind the menu.", CL, true).tags("sakura", "petals").fresh();
		add("button_sounds", "UI Sounds", "Soft chimes when you open menus, switch things and click.", CL, true).tags("sound", "click", "chime")
				.setting("ui.sound", "Sound", 0, "Droplet", "Sakura", "Glass", "Soft click")
				.setting("ui.volume", "Volume", 1, "25%", "50%", "75%", "100%");
		add("ui_animations", "Animations", "Smooth opening, sliding and switch animations.", CL, true).tags("animation")
				.setting("ui.speed", "Speed", 1, "Fast", "Normal", "Slow");
		sec("General");
		add("vfx", "Kyoto Particles", "Master switch for every Kyoto particle effect.", CL, true).tags("vfx", "particles")
				.setting("vfx.quality", "Quality", 1, "Low", "Normal", "High");
		add("custom_hotkeys", "Macros", "6 keys that run commands or chat messages, one after another with waits. Set them in Tools > Macros.", CL, true).tags("keybind", "macro", "hotkey")
				.risky("Use at your own risk: most servers don't allow macros and they can get you banned.");
		Category AN = Category.ANIMATION;
		sec("Hand");
		add("hand_anim", "Hand Position", "Move, size and tilt the item in your hand. Only changes how it looks.", AN, false).tags("item", "position", "viewmodel", "1.7").fresh()
				.setting("hand.size", "Item size", 3, "0.6x", "0.7x", "0.85x", "1.00x", "1.15x", "1.3x")
				.setting("hand.x", "Item X", 3, "-0.15", "-0.1", "-0.05", "0.00", "0.05", "0.1", "0.15")
				.setting("hand.y", "Item Y", 3, "-0.15", "-0.1", "-0.05", "0.00", "0.05", "0.1", "0.15")
				.setting("hand.z", "Item Z", 3, "-0.15", "-0.1", "-0.05", "0.00", "0.05", "0.1", "0.15")
				.setting("hand.tilt", "Item tilt", 2, "-20°", "-10°", "0°", "10°", "20°");
		add("swing_speed", "Swing Speed", "How fast your arm swings. Only the animation; your attacks stay the same.", AN, false).tags("swing", "animation", "slow").fresh()
				.setting("swing.speed", "Speed", 2, "Slow", "Relaxed", "Normal", "Snappy", "Fast");
		add("idle_sway", "Idle Sway", "Your hand breathes and sways gently when you stand still.", AN, false).tags("sway", "breathing", "idle").fresh()
				.setting("sway.amount", "Amount", 1, "Subtle", "Normal", "Strong");
		add("smooth_equip", "Smooth Item Switch", "Items glide in when you switch slots instead of popping up.", AN, false).tags("switch", "equip", "hotbar").fresh();
		for (Module m : ALL.values()) for (Setting s : m.settings) SETTINGS.put(s.key, s);
	}
}
