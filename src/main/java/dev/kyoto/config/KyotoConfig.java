package dev.kyoto.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Saved to .minecraft/config/kyoto.json */
public class KyotoConfig {
	private static final Logger LOG = LoggerFactory.getLogger("kyoto");
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("kyoto.json");

	public static KyotoConfig INSTANCE = new KyotoConfig();

	public Map<String, Boolean> modules = new HashMap<>();
	public Map<String, Integer> choices = new HashMap<>();

	// Chat
	public List<String> mutedWords = new ArrayList<>();
	/** Streamer Mode: your own words to hide on stream (real name, town, school...). Never shown or sent anywhere. */
	public List<String> streamHiddenWords = new ArrayList<>();
	public List<String> extraMentionWords = new ArrayList<>();
	public List<String> mentionPatterns = new ArrayList<>();
	public List<String> hotkeyActions = new ArrayList<>(List.of("", "", "", "", "", ""));

	// Music
	public String spotifyClientId = "";
	public String spotifyRefreshToken = "";

	// Discord
	public String discordAppId = "";

	// Kyoto network (your own server)
	/** Official Kyoto server (built in, so everyone connects automatically). */
	public static final String DEFAULT_SERVER = "https://kyoto-server.onrender.com/";
	public String serverUrl = DEFAULT_SERVER;
	public String netToken = "";
	public String netTokenUuid = "";

	// Theme
	public float accentHue = 262f;   // 0-360
	public float accentSat = 0.45f;  // 0-1
	public float accentBright = 1f;  // 0-1
	public float rgbSpeed = 1f;

	// Crosshair
	public Crosshair crosshair = new Crosshair();

	public static class Crosshair {
		public int style = 0;
		public int size = 5;
		public int gap = 2;
		public int thickness = 1;
		public boolean outline = true;
		public boolean dot = false;
		public boolean dynamic = true;
		public int colorMode = 0; // 0 theme, 1 white, 2 custom hue, 3 RGB
		public float hue = 0f;
		public float opacity = 1f;
		/** Painted crosshair: 15x15 pixels, 0 = empty, 1 = main colour, 2 = white, 3 = black, 4 = red, 5 = green, 6 = blue */
		public int[] pixels = new int[225];
		public boolean mirror = true;
		public boolean targetColor = true; // turns red when you look at a mob/player
		public boolean hitPulse = true;    // grows for a moment when you hit
		public boolean thirdPerson = false;
		public int rotation = 0;           // 0 or 45 degrees
		public float scale = 1f;
	}

	// VFX creator (event -> effect)
	public Map<String, Vfx> vfx = new HashMap<>();

	public static class Vfx {
		public boolean enabled = true;
		public int particle = 0;
		public int shape = 0;
		public int count = 16;
		public int speed = 3;   // 1-10
		public int size = 3;    // 1-10 (dust size / radius)
		public int colorMode = 0; // 0 theme, 1 custom hue, 2 RGB, 3 white
		public float hue = 0f;
		public int sound = 0;
		public boolean glow = true; // extra sparkle layer

		public Vfx copy() {
			Vfx v = new Vfx();
			v.enabled = enabled; v.particle = particle; v.shape = shape; v.count = count; v.speed = speed;
			v.size = size; v.colorMode = colorMode; v.hue = hue; v.sound = sound; v.glow = glow;
			return v;
		}
	}

	// Profile (guns.lol style)
	public Profile profile = new Profile();

	public static class Profile {
		public String displayName = "";
		public String bio = "";
		public List<String> tags = new ArrayList<>();
		public List<String> links = new ArrayList<>(List.of("", "", "", ""));
		public float hue = 262f;
		public int nameEffect = 1;   // 0 none, 1 glow, 2 rainbow, 3 wave, 4 typewriter
		public int bgEffect = 1;     // 0 none, 1 stars, 2 snow, 3 sakura, 4 rain, 5 fireflies
		public String videoFile = ""; // in .minecraft/kyoto/profile_video
		public String audioFile = ""; // in .minecraft/kyoto/profile_audio
		public float volume = 0.5f;
	}

	// Cosmetics
	public String capeFile = "";       // file name in .minecraft/kyoto/capes/
	public long capeChosenAt = 0;      // when you last picked a cape in game (a newer cape equipped on the website/launcher wins)
	public int accessory = 0;          // 0 none
	public String petType = "none";    // 3D pet (Cosmetics > Pets)
	public String petName = "";
	public java.util.Map<String, String> wear = new java.util.HashMap<>(); // wardrobe slot -> piece
	public String menuVideo = "", menuMusic = "";   // files in kyoto/backgrounds
	public java.util.Map<String, String> knives = new java.util.LinkedHashMap<>(); // item id -> "shape_finish"
	public java.util.List<String> quickWheel = new java.util.ArrayList<>(java.util.List.of("/spawn", "/home", "/ah", "/order", "/sell", "/rtp", "/bal", "/tpa")); 
	public int model = 0;
	/** HUD editor: element id -> {dx, dy, scale} */
	public java.util.Map<String, float[]> hudLayout = new HashMap<>();
	/** Server Packs: host -> pack id you picked ("" = auto, "off" = none); pack -> its own HUD layout / key binds. */
	public Map<String, String> packPicks = new HashMap<>();
	public Map<String, Map<String, float[]>> packLayouts = new HashMap<>();
	public Map<String, Map<String, String>> packKeys = new HashMap<>();
	/** Server pack numbers you set yourself: goals, thresholds, spawn border... */
	public Map<String, Double> packNums = new HashMap<>();
	public int timeTicks = 18000;      // My Time of Day (0 = 6:00, 6000 = noon, 18000 = midnight)
	public Map<String, Long> worldPlaytime = new HashMap<>();
	public String tiktokUser = "";       // TikTok username for LIVE gift alerts (public, not a secret)
	public boolean tiktokAuto = false;   // reconnect on launch
	public String streamlabsToken = "";  // secret - never shown on screen
	public String skinFile = "";       // file name in .minecraft/kyoto/skins/
	public boolean skinSlim = false;
	public int accessoryColor = 0;     // 0 theme, then hues
	public boolean nametagBadge = true;
	public List<String> hiddenCosmeticsPlayers = new ArrayList<>();
	/** Nicknames only you see: uuid (or lowercase name) -> nickname. */
	public Map<String, String> nicknames = new HashMap<>();
	/** Fishing Assistant lifetime stats. */
	public Map<String, Integer> fishStats = new HashMap<>();
	/** Custom cosmetics you wear, kept on this PC too (slot -> .kycos JSON) so they show even if the Kyoto server is down. */
	public Map<String, String> wornKycos = new HashMap<>();
	public long wornKycosAt = 0;

	/** Bumped when defaults change in a way old configs should pick up once. */
	public int configVersion = 0;

	public static void load() {
		try {
			if (Files.exists(FILE)) {
				try (Reader r = Files.newBufferedReader(FILE)) {
					KyotoConfig loaded = GSON.fromJson(r, KyotoConfig.class);
					if (loaded != null) INSTANCE = loaded;
				}
			}
		} catch (Exception e) {
			LOG.warn("Could not read kyoto.json, using defaults", e);
			INSTANCE = new KyotoConfig();
		}
		KyotoConfig c = INSTANCE;
		if (c.modules == null) c.modules = new HashMap<>();
		if (c.wear == null) c.wear = new java.util.HashMap<>();
		if (c.menuVideo == null) c.menuVideo = "";
		if (c.menuMusic == null) c.menuMusic = "";
		if (c.knives == null) c.knives = new java.util.LinkedHashMap<>();
		if (c.quickWheel == null || c.quickWheel.size() != 8) c.quickWheel = new java.util.ArrayList<>(java.util.List.of("/spawn", "/home", "/ah", "/order", "/sell", "/rtp", "/bal", "/tpa"));
		if (c.petType == null) c.petType = "none";
		if (c.petName == null) c.petName = "";
		if (c.choices == null) c.choices = new HashMap<>();
		if (c.configVersion < 4) {
			// v4: new mint look + new zoom/lyrics settings
			c.choices.put("accent.color", 2);
			c.choices.remove("zoom.fov");
			c.choices.remove("zoom.smooth");
			c.choices.remove("lyrics.where");
			c.choices.remove("lyrics.style");
			c.configVersion = 4;
		}
		if (c.configVersion < 5) {
			// v5: starry silver brand, font list changed, mentions use your own username
			c.choices.put("accent.color", 0);
			c.choices.remove("lyrics.style");
			c.choices.remove("font.family");
			c.choices.remove("lyrics.font");
			c.choices.remove("title.theme");
			if (c.mentionPatterns != null) c.mentionPatterns.remove("\\bhef+\\b");
			c.configVersion = 5;
		}
		if (c.configVersion < 6) {
			// v6: everyone uses the official Kyoto server, glass menus
			c.serverUrl = DEFAULT_SERVER;
			c.netToken = "";
			c.configVersion = 6;
		}
		if (c.configVersion < 7) {
			// v6.2.3: video settings changed meaning - go back to the smooth defaults (720p, 30 fps)
			c.choices.remove("video.quality");
			c.choices.remove("video.fps");
			c.choices.remove("screens.quality");
			c.configVersion = 7;
		}
		c.serverUrl = DEFAULT_SERVER; // built in - always the official server
		if (c.mutedWords == null) c.mutedWords = new ArrayList<>();
		if (c.streamHiddenWords == null) c.streamHiddenWords = new ArrayList<>();
		if (c.extraMentionWords == null) c.extraMentionWords = new ArrayList<>();
		if (c.mentionPatterns == null) c.mentionPatterns = new ArrayList<>();
		if (c.hotkeyActions == null) c.hotkeyActions = new ArrayList<>();
		while (c.hotkeyActions.size() < 6) c.hotkeyActions.add("");
		if (c.spotifyClientId == null) c.spotifyClientId = "";
		if (c.spotifyRefreshToken == null) c.spotifyRefreshToken = "";
		if (c.discordAppId == null) c.discordAppId = "";
		if (c.serverUrl == null || c.serverUrl.isBlank()) c.serverUrl = DEFAULT_SERVER;
		if (c.netToken == null) c.netToken = "";
		if (c.netTokenUuid == null) c.netTokenUuid = "";
		if (c.crosshair == null) c.crosshair = new Crosshair();
		if (c.vfx == null) c.vfx = new HashMap<>();
		if (c.hudLayout == null) c.hudLayout = new HashMap<>();
		if (c.packPicks == null) c.packPicks = new HashMap<>();
		if (c.packLayouts == null) c.packLayouts = new HashMap<>();
		if (c.packKeys == null) c.packKeys = new HashMap<>();
		if (c.packNums == null) c.packNums = new HashMap<>();
		if (c.capeFile == null) c.capeFile = "";
		if (c.skinFile == null) c.skinFile = "";
		if (c.worldPlaytime == null) c.worldPlaytime = new HashMap<>();
		if (c.streamlabsToken == null) c.streamlabsToken = "";
		if (c.tiktokUser == null) c.tiktokUser = "";
		if (c.hiddenCosmeticsPlayers == null) c.hiddenCosmeticsPlayers = new ArrayList<>();
		if (c.nicknames == null) c.nicknames = new HashMap<>();
		if (c.fishStats == null) c.fishStats = new HashMap<>();
		if (c.wornKycos == null) c.wornKycos = new HashMap<>();
		if (c.profile == null) c.profile = new Profile();
		if (c.profile.tags == null) c.profile.tags = new ArrayList<>();
		if (c.profile.links == null) c.profile.links = new ArrayList<>();
		while (c.profile.links.size() < 4) c.profile.links.add("");
		if (c.profile.displayName == null) c.profile.displayName = "";
		if (c.profile.bio == null) c.profile.bio = "";
		if (c.profile.videoFile == null) c.profile.videoFile = "";
		if (c.profile.audioFile == null) c.profile.audioFile = "";
		save();
	}

	public static void save() {
		try {
			Files.createDirectories(FILE.getParent());
			try (Writer w = Files.newBufferedWriter(FILE)) {
				GSON.toJson(INSTANCE, w);
			}
		} catch (Exception e) {
			LOG.warn("Could not save kyoto.json", e);
		}
	}

	public static KyotoConfig get() {
		return INSTANCE;
	}
}
