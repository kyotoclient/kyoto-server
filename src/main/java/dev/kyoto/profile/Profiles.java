package dev.kyoto.profile;

import dev.kyoto.util.Ui;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.kyoto.config.KyotoConfig;
import dev.kyoto.hud.Notifications;
import dev.kyoto.net.KyotoNet;
import dev.kyoto.util.Compat;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Kyoto profiles (guns.lol style): display name, tags, bio, links, colors, animated name,
 * background video (MP4/GIF, always muted) and profile music (MP3/WAV).
 *
 * Your media goes in:
 *   .minecraft/kyoto/profile_video   (mp4 / gif backgrounds)
 *   .minecraft/kyoto/profile_audio   (mp3 / wav music)
 */
public final class Profiles {
	private Profiles() {}

	/** Kyoto staff. Shown with a crown and "Owner of Kyoto Client". */
	public static final Set<String> OWNERS = Set.of("heffm");

	public static final String[] NAME_EFFECTS = {"None", "Glow", "Rainbow", "Wave", "Typewriter"};
	public static final String[] BG_EFFECTS = {"None", "Stars", "Snow", "Sakura", "Rain", "Fireflies", "Shooting stars"};
	public static final String[] LAYOUTS = {"Card", "Floating", "Compact"};
	public static final String[] CARD_STYLES = {"Glass", "Solid", "Outline", "None"};

	public static boolean isOwner(String name) {
		return name != null && OWNERS.contains(name.toLowerCase(Locale.ROOT));
	}

	/** A profile as shown on screen (yours from config, or someone's from the Kyoto server). */
	public static final class Data {
		public String name = "";
		public String displayName = "";
		public String bio = "";
		public List<String> tags = new ArrayList<>();
		public List<String> links = new ArrayList<>();
		public List<String> badges = new ArrayList<>();
		public float hue = 262f;
		public int nameEffect = 1;
		public int bgEffect = 1;
		public int views = 0;
		public String videoExt = null; // null = none
		public String audioExt = null;
		public String audioName = "";
		public boolean local = false;
		public Path localVideo, localAudio;
		public boolean found = true;
		public String error = null;
		// set on the website (Kyoto profile editor)
		public int accent = -1;       // -1 = from hue
		public int textColor = Ui.INK;
		public int layout = 0;        // 0 card, 1 floating, 2 compact
		public int cardStyle = 0;     // 0 glass, 1 solid, 2 outline, 3 none
		public int cardOpacity = 62;  // %
		public int bgDim = 45;        // % darkening over the background
		public String location = "";
		public boolean showViews = true;
		public boolean avatarGlow = true;
		public String avatarExt = null;  // custom avatar picture (png/jpg)
		public String imageExt = null;   // background picture (png/jpg)

		public int accentColor() {
			return accent >= 0 ? accent : dev.kyoto.util.Ui.hsv(hue, 0.5f, 1f);
		}
	}

	public static Path folder(String sub) {
		Path p = FabricLoader.getInstance().getGameDir().resolve("kyoto").resolve(sub);
		try {
			Files.createDirectories(p);
		} catch (Exception ignored) {
		}
		return p;
	}

	public static List<String> files(String sub, String... exts) {
		List<String> out = new ArrayList<>();
		try (var s = Files.list(folder(sub))) {
			s.map(x -> x.getFileName().toString()).filter(n -> {
				String l = n.toLowerCase(Locale.ROOT);
				for (String e : exts) if (l.endsWith(e)) return true;
				return false;
			}).sorted().forEach(out::add);
		} catch (Exception ignored) {
		}
		return out;
	}

	/** Your own profile from your settings (used offline and as a preview). */
	public static Data mine() {
		KyotoConfig.Profile p = KyotoConfig.get().profile;
		Data d = new Data();
		d.local = true;
		d.name = Compat.myName();
		d.displayName = p.displayName.isBlank() ? d.name : p.displayName;
		d.bio = p.bio;
		d.tags = new ArrayList<>(p.tags);
		for (String l : p.links) if (l != null && !l.isBlank()) d.links.add(l.trim());
		d.hue = p.hue;
		d.nameEffect = p.nameEffect;
		d.bgEffect = p.bgEffect;
		if (isOwner(d.name)) d.badges.add("owner");
		d.badges.add("kyoto");
		if (!p.videoFile.isBlank()) {
			d.localVideo = folder("profile_video").resolve(p.videoFile);
			d.videoExt = ext(p.videoFile);
		}
		if (!p.audioFile.isBlank()) {
			d.localAudio = folder("profile_audio").resolve(p.audioFile);
			d.audioExt = ext(p.audioFile);
			d.audioName = p.audioFile;
		}
		return d;
	}

	static String ext(String f) {
		int i = f.lastIndexOf('.');
		return i < 0 ? "" : f.substring(i + 1).toLowerCase(Locale.ROOT);
	}

	/** Loads someone's profile from your Kyoto server. */
	public static void fetch(String name, Consumer<Data> done) {
		KyotoNet.runAsync(() -> {
			Data d = new Data();
			d.name = name;
			try {
				JsonObject o = JsonParser.parseString(KyotoNet.send("GET", "/profile/" + KyotoNet.enc(name), null, false)).getAsJsonObject();
				d.name = o.get("name").getAsString();
				d.displayName = str(o, "displayName", d.name);
				d.bio = str(o, "bio", "");
				d.tags = strings(o.getAsJsonArray("tags"));
				d.links = strings(o.getAsJsonArray("links"));
				d.badges = strings(o.getAsJsonArray("badges"));
				d.hue = o.has("hue") ? o.get("hue").getAsFloat() : 262f;
				d.nameEffect = o.has("nameEffect") ? o.get("nameEffect").getAsInt() : 1;
				d.bgEffect = o.has("bgEffect") ? o.get("bgEffect").getAsInt() : 1;
				d.views = o.has("views") ? o.get("views").getAsInt() : 0;
				d.videoExt = o.has("videoExt") && !o.get("videoExt").isJsonNull() ? o.get("videoExt").getAsString() : null;
				d.audioExt = o.has("audioExt") && !o.get("audioExt").isJsonNull() ? o.get("audioExt").getAsString() : null;
				d.audioName = str(o, "audioName", "");
				d.accent = color(o, "accent", -1);
				d.textColor = color(o, "textColor", Ui.INK);
				d.layout = num(o, "layout", 0);
				d.cardStyle = num(o, "cardStyle", 0);
				d.cardOpacity = num(o, "cardOpacity", 62);
				d.bgDim = num(o, "bgDim", 45);
				d.location = str(o, "location", "");
				d.showViews = !o.has("showViews") || o.get("showViews").getAsBoolean();
				d.avatarGlow = !o.has("avatarGlow") || o.get("avatarGlow").getAsBoolean();
				d.avatarExt = o.has("avatarExt") && !o.get("avatarExt").isJsonNull() ? o.get("avatarExt").getAsString() : null;
				d.imageExt = o.has("imageExt") && !o.get("imageExt").isJsonNull() ? o.get("imageExt").getAsString() : null;
			} catch (Exception e) {
				d.found = false;
				d.error = e.getMessage() != null && e.getMessage().startsWith("404") ? "This player hasn't made a Kyoto profile yet." : "Couldn't load profile: " + e.getMessage();
				d.displayName = name;
				if (isOwner(name)) d.badges.add("owner");
			}
			Minecraft.getInstance().execute(() -> done.accept(d));
		});
	}

	public static byte[] download(String name, String kind) throws Exception {
		return KyotoNet.bytes("/profile/" + KyotoNet.enc(name) + "/" + kind);
	}

	/** Uploads your profile text + any changed media to your Kyoto server. */
	public static void publish() {
		if (!KyotoNet.loggedIn()) {
			Notifications.push("Not connected", "Set up your Kyoto server to publish your profile", Notifications.WARN);
			return;
		}
		KyotoConfig.Profile p = KyotoConfig.get().profile;
		KyotoNet.runAsync(() -> {
			try {
				JsonObject b = new JsonObject();
				b.addProperty("displayName", p.displayName);
				b.addProperty("bio", p.bio);
				b.add("tags", array(p.tags));
				List<String> links = new ArrayList<>();
				for (String l : p.links) if (l != null && !l.isBlank()) links.add(l.trim());
				b.add("links", array(links));
				b.addProperty("hue", p.hue);
				b.addProperty("nameEffect", p.nameEffect);
				b.addProperty("bgEffect", p.bgEffect);
				KyotoNet.send("PUT", "/profile", b, true);
				upload("video", p.videoFile.isBlank() ? null : folder("profile_video").resolve(p.videoFile), p.videoFile);
				upload("audio", p.audioFile.isBlank() ? null : folder("profile_audio").resolve(p.audioFile), p.audioFile);
				toast("Profile published", "Anyone on your Kyoto server can view it", Notifications.GOOD);
			} catch (Exception e) {
				toast("Publish failed", e.getMessage(), Notifications.DANGER);
			}
		});
	}

	private static void upload(String kind, Path file, String name) throws Exception {
		JsonObject b = new JsonObject();
		b.addProperty("kind", kind);
		if (file == null || !Files.exists(file)) {
			b.addProperty("remove", true);
		} else {
			byte[] data = Files.readAllBytes(file);
			long max = kind.equals("video") ? 15_000_000L : 10_000_000L;
			if (data.length > max) throw new IllegalStateException(name + " is too big (max " + (max / 1_000_000) + " MB)");
			b.addProperty("data", Base64.getEncoder().encodeToString(data));
			b.addProperty("ext", ext(name));
			b.addProperty("fileName", name);
		}
		KyotoNet.send("PUT", "/profile/media", b, true);
	}

	private static void toast(String t, String m, int c) {
		Minecraft.getInstance().execute(() -> Notifications.push(t, m, c));
	}

	private static JsonArray array(List<String> list) {
		JsonArray a = new JsonArray();
		for (String s : list) a.add(s);
		return a;
	}

	private static List<String> strings(JsonArray a) {
		List<String> out = new ArrayList<>();
		if (a != null) for (JsonElement e : a) out.add(e.getAsString());
		return out;
	}

	private static int num(JsonObject o, String k, int def) {
		try {
			return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsInt() : def;
		} catch (Exception e) {
			return def;
		}
	}

	/** "#a855f7" -> 0xA855F7 */
	private static int color(JsonObject o, String k, int def) {
		try {
			String v = str(o, k, "");
			if (v.startsWith("#")) v = v.substring(1);
			return v.length() == 6 ? Integer.parseInt(v, 16) : def;
		} catch (Exception e) {
			return def;
		}
	}

	private static String str(JsonObject o, String k, String def) {
		return o.has(k) && !o.get(k).isJsonNull() && !o.get(k).getAsString().isBlank() ? o.get(k).getAsString() : def;
	}
}
