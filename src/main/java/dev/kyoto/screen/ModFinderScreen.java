package dev.kyoto.screen;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.kyoto.util.Anim;
import dev.kyoto.util.Compat;
import dev.kyoto.util.Ui;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Search Modrinth for Fabric mods that support 26.3. Shows what each mod does, if it's client/server side,
 * and opens the download page (it never downloads or installs anything by itself).
 */
public class ModFinderScreen extends PanelScreen {
	private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();

	private record Hit(String slug, String title, String author, String desc, long downloads, String client, String server, List<String> cats) {}

	private static String query = "";
	private static List<Hit> results = new ArrayList<>();
	private static String state = "Search for anything: minimap, shaders, sodium...";
	private static Hit selected = null;
	private static String explain = null;
	private int page = 0;
	private String typed;
	/** Modrinth tags you can filter by. */
	private static final String[] TAGS = {"Any", "Optimization", "Utility", "Decoration", "Adventure", "Social", "Storage", "Worldgen", "Magic",
			"Technology", "Food", "Game mechanics", "Transportation", "Equipment", "Management", "Mobs", "Minigame", "Library"};
	private static int tag = 0;
	private static String installState = null;

	public ModFinderScreen(Screen parent) {
		this(parent, null);
	}

	public ModFinderScreen(Screen parent, String initialQuery) {
		super("Mod Finder", parent);
		if (initialQuery != null) {
			query = initialQuery;
			search();
		}
		typed = query;
	}

	@Override
	protected String subtitle() {
		return "Fabric mods for 26.3 from Modrinth - " + state;
	}

	private static String enc(String s) {
		return URLEncoder.encode(s, StandardCharsets.UTF_8);
	}

	private static void search() {
		state = "Searching...";
		selected = null;
		explain = null;
		String q = query;
		Thread t = new Thread(() -> {
			try {
				String tagFacet = tag == 0 ? "" : ",[\"categories:" + TAGS[tag].toLowerCase(java.util.Locale.ROOT).replace(' ', '-') + "\"]";
				String facets = enc("[[\"project_type:mod\"],[\"categories:fabric\"],[\"versions:26.3\"]" + tagFacet + "]");
				String url = "https://api.modrinth.com/v2/search?limit=40&index=relevance&query=" + enc(q) + "&facets=" + facets;
				HttpRequest req = HttpRequest.newBuilder(URI.create(url)).header("User-Agent", "KyotoClient/3.0 (Minecraft mod)").timeout(Duration.ofSeconds(12)).GET().build();
				HttpResponse<String> raw = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
				if (raw.statusCode() != 200) {
					state = "Modrinth said " + raw.statusCode() + " - try 'Open website'";
					return;
				}
				JsonObject res = JsonParser.parseString(raw.body()).getAsJsonObject();
				List<Hit> list = new ArrayList<>();
				JsonArray hitsArr = res.has("hits") && res.get("hits").isJsonArray() ? res.getAsJsonArray("hits") : new JsonArray();
				for (JsonElement e : hitsArr) {
					try {
						JsonObject o = e.getAsJsonObject();
						List<String> cats = new ArrayList<>();
						JsonElement ca = o.has("display_categories") ? o.get("display_categories") : o.get("categories");
						if (ca != null && ca.isJsonArray()) for (JsonElement c : ca.getAsJsonArray()) cats.add(c.getAsString());
						list.add(new Hit(str(o, "slug", str(o, "project_id", "")), str(o, "title", "?"), str(o, "author", "?"),
								str(o, "description", ""), o.has("downloads") ? o.get("downloads").getAsLong() : 0,
								str(o, "client_side", "unknown"), str(o, "server_side", "unknown"), cats));
					} catch (Exception ignored) {
						// skip one odd result instead of failing the whole search
					}
				}
				Minecraft.getInstance().execute(() -> {
					results = list;
					state = list.size() + " results";
					if (Minecraft.getInstance().gui.screen() instanceof ModFinderScreen s) s.rebuildWidgets();
				});
			} catch (Exception e) {
				state = "Couldn't reach Modrinth (" + e.getClass().getSimpleName() + ") - try 'Open website'";
			}
		}, "Kyoto-ModFinder");
		t.setDaemon(true);
		t.start();
	}

	private static void loadExplain(Hit h) {
		explain = "Loading...";
		Thread t = new Thread(() -> {
			try {
				HttpRequest req = HttpRequest.newBuilder(URI.create("https://api.modrinth.com/v2/project/" + h.slug()))
						.header("User-Agent", "KyotoClient/3.0 (Minecraft mod)").timeout(Duration.ofSeconds(12)).GET().build();
				JsonObject o = JsonParser.parseString(HTTP.send(req, HttpResponse.BodyHandlers.ofString()).body()).getAsJsonObject();
				String body = o.get("body").getAsString()
						.replaceAll("!\\[[^]]*]\\([^)]*\\)", "")      // images
						.replaceAll("\\[([^]]*)]\\([^)]*\\)", "$1")   // links -> text
						.replaceAll("<[^>]+>", "")                   // html
						.replaceAll("[#*_`>|]", "")
						.replaceAll("\\n{2,}", "\n").trim();
				explain = body.length() > 900 ? body.substring(0, 900) + "..." : body;
			} catch (Exception e) {
				explain = "Couldn't load details.";
			}
		}, "Kyoto-ModFinder");
		t.setDaemon(true);
		t.start();
	}

	@Override
	protected String icon() {
		return "finder";
	}

	@Override
	protected void build() {
		EditBox box = new EditBox(this.font, px + 16, py + 36, 160, 18, Component.literal("Search"));
		box.setHint(Ui.t("Search Modrinth..."));
		box.setValue(typed == null ? "" : typed);
		box.setResponder(s -> typed = s);
		this.addRenderableWidget(box);
		btn(px + 182, py + 35, 62, "Search", () -> {
			query = typed == null ? "" : typed;
			page = 0;
			search();
		});
		btn(px + 256, py + 35, 20, "‹", () -> {
			tag = Math.floorMod(tag - 1, TAGS.length);
			query = typed == null ? "" : typed;
			page = 0;
			search();
		});
		btn(px + 278, py + 35, 110, "Tag: " + TAGS[tag], () -> {
			tag = (tag + 1) % TAGS.length;
			query = typed == null ? "" : typed;
			page = 0;
			search();
		});
		btn(px + 392, py + 35, px + pw - 14 - (px + 392), "Website ↗", () -> Compat.openUrl(websiteUrl(typed)));
		int perPage = Math.max(4, (ph - 100) / 22);
		int pages = Math.max(1, (results.size() + perPage - 1) / perPage);
		page = clamp(page, 0, pages - 1);
		int y = py + 60;
		for (int i = page * perPage; i < Math.min(results.size(), (page + 1) * perPage); i++) {
			Hit h = results.get(i);
			boolean installed = FabricLoader.getInstance().isModLoaded(h.slug());
			String label = (h == selected ? "▶ " : "") + (installed ? "✔ " : "") + Ui.ellipsize(h.title(), 150) + "  " + fmt(h.downloads());
			btn(px + 14, y, 230, label, () -> {
				selected = h;
				explain = null;
			});
			y += 22;
		}
		pager(px + 14, py + ph - 26, 100, page, pages, v -> page = v);
		if (selected != null) {
			Hit h = selected;
			btn(px + 256, py + ph - 50, 96, "Open page", () -> Compat.openUrl("https://modrinth.com/mod/" + h.slug()));
			btn(px + 356, py + ph - 50, 90, "Explain more", () -> loadExplain(h));
			if (!FabricLoader.getInstance().isModLoaded(h.slug())) btn(px + 450, py + ph - 50, 80, "Install", () -> install(h));
		}
	}

	/** Downloads the newest Fabric 26.3 file straight from Modrinth into your mods folder (checked with its SHA-512). */
	private static void install(Hit h) {
		installState = "Downloading " + h.title() + "...";
		Thread t = new Thread(() -> {
			try {
				String filter = "?loaders=" + enc("[\"fabric\"]") + "&game_versions=" + enc("[\"26.3\"]");
				HttpRequest req = HttpRequest.newBuilder(URI.create("https://api.modrinth.com/v2/project/" + enc(h.slug()) + "/version" + filter))
						.header("User-Agent", "KyotoClient/3.0 (Minecraft mod)").timeout(Duration.ofSeconds(15)).GET().build();
				JsonArray versions = JsonParser.parseString(HTTP.send(req, HttpResponse.BodyHandlers.ofString()).body()).getAsJsonArray();
				if (versions.isEmpty()) throw new IllegalStateException("no 26.3 version");
				JsonObject v = versions.get(0).getAsJsonObject();
				JsonObject file = null;
				for (JsonElement f : v.getAsJsonArray("files")) if (f.getAsJsonObject().get("primary").getAsBoolean()) file = f.getAsJsonObject();
				if (file == null) file = v.getAsJsonArray("files").get(0).getAsJsonObject();
				String name = file.get("filename").getAsString().replaceAll("[\\\\/:*?\"<>|]", "_");
				if (!name.endsWith(".jar")) throw new IllegalStateException("not a jar");
				String sha = file.getAsJsonObject("hashes").get("sha512").getAsString();
				byte[] jar = HTTP.send(HttpRequest.newBuilder(URI.create(file.get("url").getAsString())).timeout(Duration.ofSeconds(120)).GET().build(),
						HttpResponse.BodyHandlers.ofByteArray()).body();
				StringBuilder hex = new StringBuilder();
				for (byte b : java.security.MessageDigest.getInstance("SHA-512").digest(jar)) hex.append(String.format("%02x", b));
				if (!hex.toString().equalsIgnoreCase(sha)) throw new IllegalStateException("download was corrupted");
				java.nio.file.Files.write(dev.kyoto.util.ModToggles.modsDir().resolve(name), jar);
				int deps = 0;
				if (v.has("dependencies")) for (JsonElement d : v.getAsJsonArray("dependencies")) if ("required".equals(str(d.getAsJsonObject(), "dependency_type", ""))) deps++;
				installState = "Installed " + h.title() + " - restart Minecraft to use it" + (deps > 0 ? " (it needs " + deps + " other mod" + (deps > 1 ? "s" : "") + ", see its page)" : "");
			} catch (Exception e) {
				installState = "Couldn't install " + h.title() + ": " + e.getMessage();
			}
			String msg = installState;
			Minecraft.getInstance().execute(() -> dev.kyoto.hud.Notifications.push("Mod Finder", msg, dev.kyoto.hud.Notifications.INFO, 6000));
		}, "Kyoto-ModInstall");
		t.setDaemon(true);
		t.start();
	}

	private static String fmt(long n) {
		if (n >= 1_000_000) return String.format("%.1fM", n / 1e6);
		if (n >= 1_000) return String.format("%.0fK", n / 1e3);
		return String.valueOf(n);
	}

	private static String str(JsonObject o, String key, String def) {
		return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : def;
	}

	/** The Modrinth website search for Fabric 26.3 mods (modrinth.com/discover/mods). */
	public static String websiteUrl(String q) {
		return "https://modrinth.com/discover/mods?g=categories:fabric&v=26.3" + (q == null || q.isBlank() ? "" : "&q=" + enc(q));
	}

	private static String side(String s) {
		return switch (s) {
			case "required" -> "needed";
			case "optional" -> "optional";
			case "unsupported" -> "not needed";
			default -> "see page";
		};
	}

	@Override
	protected void drawContent(GuiGraphicsExtractor g, int mx, int my, float a) {
		int x = px + 256, y = py + 60, w = px + pw - 14 - x;
		if (selected == null) {
			for (String s : wrap("Only Fabric mods that support 26.3 show up. Use Tag to narrow it down (Optimization, Utility...). Click a mod to see what it does, then Install puts it straight from Modrinth into your mods folder. New mods load after a restart.", w, 10)) {
				Ui.text(g, s, x, y, Anim.argb(Ui.MUTED, a));
				y += 11;
			}
			return;
		}
		Hit h = selected;
		Ui.bold(g, Ui.ellipsize(h.title(), w), x, y, Anim.argb(Ui.TEXT, a));
		Ui.text(g, "by " + h.author() + "  ·  " + fmt(h.downloads()) + " downloads", x, y + 12, Anim.argb(Ui.MUTED, a));
		Ui.text(g, "Your game: " + side(h.client()) + "  ·  Server: " + side(h.server()), x, y + 23, Anim.argb(Ui.accent(), a));
		if (!h.cats().isEmpty()) Ui.text(g, Ui.ellipsize(String.join(", ", h.cats()), w), x, y + 34, Anim.argb(Ui.MUTED, a));
		int ty = y + 48;
		String text = explain != null ? explain : h.desc();
		int max = Math.max(3, (py + ph - 60 - ty) / 11);
		for (String s : wrap(text, w, max)) {
			Ui.text(g, s, x, ty, Anim.argb(Ui.INK, a));
			ty += 11;
		}
		if (FabricLoader.getInstance().isModLoaded(h.slug())) Ui.text(g, "✔ Already installed", x, py + ph - 62, Anim.argb(Ui.INK, a));
		else if (installState != null) Ui.text(g, Ui.ellipsize(installState, w), x, py + ph - 62, Anim.argb(Ui.accent(), a));
	}
}
