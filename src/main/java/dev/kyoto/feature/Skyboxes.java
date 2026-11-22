package dev.kyoto.feature;

import dev.kyoto.KyotoClient;
import dev.kyoto.module.Modules;
import net.minecraft.client.Minecraft;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Real skyboxes. Kyoto writes a small "Kyoto Skies" resource pack in Nuit's format (the skybox mod, formerly
 * FabricSkyBoxes) and turns it on. Nuit draws it. Without Nuit installed, Kyoto's own sky effects are used instead.
 */
public final class Skyboxes {
	private Skyboxes() {}

	public static final String PACK = "Kyoto Skies";
	public static final String[] IDS = {"", "sakura_dusk", "starry", "galaxy", "pastel_day"};
	public static final String NUIT_URL = "https://modrinth.com/mod/nuit";

	public static boolean nuit() {
		var l = net.fabricmc.loader.api.FabricLoader.getInstance();
		return l.isModLoaded("nuit") || l.isModLoaded("fabricskyboxes");
	}

	/** 0 = off, else index in IDS. */
	public static int current() {
		return Modules.on("skybox") ? Modules.opt("skybox.style") + 1 : 0;
	}

	public static void apply() {
		int sel = current();
		boolean on = sel > 0 && nuit();
		Path dir = net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir().resolve("resourcepacks").resolve(PACK);
		try {
			Path sky = dir.resolve("assets/nuit/sky");
			Path tex = dir.resolve("assets/kyoto/textures/sky");
			for (Path p : new Path[]{sky, tex}) if (Files.exists(p)) try (var s = Files.list(p)) { s.forEach(f -> { try { Files.delete(f); } catch (Exception ignored) { } }); }
			if (on) {
				Files.createDirectories(sky);
				Files.createDirectories(tex);
				int fmt = Knives.packFormat();
				Files.writeString(dir.resolve("pack.mcmeta"), "{\"pack\":{\"description\":\"Kyoto Skies (needs Nuit)\",\"pack_format\":" + fmt
						+ ",\"min_format\":" + fmt + ",\"max_format\":" + fmt + ",\"supported_formats\":[" + fmt + "," + fmt + "]}}");
				Knives.copy("/assets/kyoto/icon.png", dir.resolve("pack.png"));
				String id = IDS[sel];
				Knives.copy("/assets/kyoto/skies/" + id + ".png", tex.resolve(id + ".png"));
				boolean night = !id.equals("pastel_day") && !id.equals("sakura_dusk");
				// night skies fade in at dusk and out at dawn; day skies the other way round
				String keys = night ? "\"0\": 0.0, \"12000\": 0.0, \"13500\": 1.0, \"22500\": 1.0, \"23900\": 0.0"
						: "\"0\": 1.0, \"11500\": 1.0, \"13000\": 0.0, \"23000\": 0.0, \"23900\": 1.0";
				if (Modules.opt("skybox.when") == 1) keys = ""; // all day
				Files.writeString(sky.resolve("kyoto.json"), "{\n  \"schemaVersion\": 1,\n  \"type\": \"square-textured\",\n  \"texture\": \"kyoto:textures/sky/" + id + ".png\",\n"
						+ "  \"properties\": {\n    \"blend\": \"normal\",\n    \"fade\": {\"duration\": 24000, \"keyFrames\": {" + keys + "}}\n  }\n}\n");
			}
		} catch (Exception e) {
			KyotoClient.LOG.warn("Kyoto skies pack failed", e);
		}
		Minecraft.getInstance().execute(() -> Knives.select(PACK, on, "Skies", "sky"));
	}
}
