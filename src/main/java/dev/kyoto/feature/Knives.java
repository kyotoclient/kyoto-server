package dev.kyoto.feature;

import dev.kyoto.KyotoClient;
import dev.kyoto.config.KyotoConfig;
import net.minecraft.client.Minecraft;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Knives: CS2-style knife skins for your swords (and axes if you want). Only changes how they look on your screen.
 * Kyoto writes a tiny resource pack ("Kyoto Knives") with the knife textures you picked and turns it on.
 */
public final class Knives {
	private Knives() {}

	public static final String[] SHAPES = {"karambit", "butterfly", "bayonet", "flip", "gut", "huntsman", "kunai", "katana"};
	public static final String[] SHAPE_NAMES = {"Karambit", "Butterfly Knife", "Bayonet", "Flip Knife", "Gut Knife", "Huntsman Knife", "Kunai", "Sakura Katana"};
	public static final String[] FINISHES = {"vanilla", "sakura", "fade", "doppler", "gamma", "crimson", "tiger", "night", "gold", "diamond", "darkmatter", "damascus", "digital", "molten", "nebula", "plasma", "prism"};
	public static final String[] FINISH_NAMES = {"Vanilla", "Sakura", "Fade", "Doppler", "Gamma Doppler", "Crimson Web", "Tiger Tooth", "Night", "Gold", "Diamond", "Dark Matter", "Damascus", "Digital", "Molten", "Nebula", "Plasma", "Prism"};
	public static final int[] FINISH_COLORS = {0xC9C9D2, 0xF7A1BC, 0xF072B0, 0x8A4CE8, 0x0FA36B, 0xB3122E, 0xE0A21E, 0x3A3F6E, 0xF5C84C, 0x9FD8FF, 0x6B2BD9, 0x8A9098, 0xE07AA0, 0xFF6A1E, 0x5B6BF0, 0x3FD8FF, 0xF0A0FF};
	public static final String[] ITEMS = {"wooden_sword", "stone_sword", "copper_sword", "iron_sword", "golden_sword", "diamond_sword", "netherite_sword",
			"wooden_axe", "stone_axe", "copper_axe", "iron_axe", "golden_axe", "diamond_axe", "netherite_axe"};
	private static final String PACK = "Kyoto Knives";

	public static Map<String, String> picks() {
		return KyotoConfig.get().knives;
	}

	public static String material(String item) {
		String m = item.substring(0, item.indexOf('_'));
		return switch (m) {
			case "wooden", "stone", "copper", "iron", "golden", "diamond", "netherite" -> m;
			default -> "iron";
		};
	}

	public static String niceItem(String item) {
		String[] p = item.split("_");
		String mat = switch (p[0]) { case "wooden" -> "Wooden"; case "golden" -> "Gold"; default -> Character.toUpperCase(p[0].charAt(0)) + p[0].substring(1); };
		return mat + " " + (p[1].equals("sword") ? "Sword" : "Axe");
	}

	/** Writes the pack for the current picks and turns it on (or off when nothing's picked). Reloads resources. */
	public static void apply() {
		Path dir = net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir().resolve("resourcepacks").resolve(PACK);
		boolean any = !picks().isEmpty();
		try {
			Path tex = dir.resolve("assets/minecraft/textures/item");
			if (Files.exists(tex)) try (var s = Files.list(tex)) { s.forEach(f -> { try { Files.delete(f); } catch (Exception ignored) { } }); }
			if (any) {
				Files.createDirectories(tex);
				int fmt = packFormat();
				Files.writeString(dir.resolve("pack.mcmeta"), "{\"pack\":{\"description\":\"Kyoto Knives (made by Kyoto Client)\",\"pack_format\":" + fmt
						+ ",\"min_format\":" + fmt + ",\"max_format\":" + fmt + ",\"supported_formats\":[" + fmt + "," + fmt + "]}}");
				copy("/assets/kyoto/icon.png", dir.resolve("pack.png"));
				for (var e : picks().entrySet()) {
					String k = e.getValue();
					if (k.endsWith("_vanilla")) k = k + "_" + material(e.getKey());
					copy("/assets/kyoto/knives/" + k + ".png", tex.resolve(e.getKey() + ".png"));
				}
			}
		} catch (Exception e) {
			KyotoClient.LOG.warn("Kyoto knives pack failed", e);
		}
		Minecraft.getInstance().execute(() -> select(any));
	}

	static void copy(String res, Path to) throws Exception {
		try (InputStream in = Knives.class.getResourceAsStream(res)) {
			if (in != null) Files.copy(in, to, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
		}
	}

	/** Turns the pack on/off in the resource pack list and reloads. All by name, so it survives Minecraft renaming things. */
	@SuppressWarnings("unchecked")
	private static void select(boolean on) {
		select(PACK, on, "Knives", "sword");
	}

	static void select(String PACK, boolean on, String what, String icon) {
		Minecraft mc = Minecraft.getInstance();
		try {
			Object repo = mc.getClass().getMethod("getResourcePackRepository").invoke(mc);
			repo.getClass().getMethod("reload").invoke(repo);
			List<String> ids = new ArrayList<>((Collection<String>) repo.getClass().getMethod("getSelectedIds").invoke(repo));
			String id = "file/" + PACK;
			ids.remove(id);
			if (on) ids.add(id); // last = on top
			repo.getClass().getMethod("setSelected", Collection.class).invoke(repo, ids);
			try {
				mc.options.getClass().getMethod("updateResourcePacks", repo.getClass()).invoke(mc.options, repo);
				return;
			} catch (Throwable ignored) {
			}
			try {
				var f = mc.options.getClass().getField("resourcePacks");
				List<String> list = (List<String>) f.get(mc.options);
				list.remove(id);
				if (on) list.add(id);
				mc.options.save();
			} catch (Throwable ignored) {
			}
			mc.reloadResourcePacks();
		} catch (Throwable t) {
			KyotoClient.LOG.warn("Kyoto: couldn't switch the pack " + PACK, t);
			dev.kyoto.hud.Notifications.push(what, "Turn on \"" + PACK + "\" in Resource Packs", dev.kyoto.hud.Notifications.INFO, 5000, null, icon);
		}
	}

	/** This version's resource pack format number, so the pack never shows as "made for another version". */
	static int packFormat() {
		try {
			Class<?> sc = Class.forName("net.minecraft.SharedConstants");
			Object ver = sc.getMethod("getCurrentVersion").invoke(null);
			Class<?> pt = Class.forName("net.minecraft.server.packs.PackType");
			Object client = pt.getField("CLIENT_RESOURCES").get(null);
			for (String n : new String[]{"packVersion", "getPackVersion", "resourcePackVersion"}) {
				for (var m : ver.getClass().getMethods()) {
					if (!m.getName().equals(n)) continue;
					Object v = m.getParameterCount() == 1 ? m.invoke(ver, client) : m.getParameterCount() == 0 ? m.invoke(ver) : null;
					if (v instanceof Integer i) return i;
					if (v != null) {
						var mm = java.util.regex.Pattern.compile("\\d+").matcher(v.toString());
						if (mm.find()) return Integer.parseInt(mm.group());
					}
				}
			}
		} catch (Throwable ignored) {
		}
		return 70;
	}
}
