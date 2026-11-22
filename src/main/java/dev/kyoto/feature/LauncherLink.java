package dev.kyoto.feature;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import dev.kyoto.util.Compat;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.TreeMap;

/**
 * Talks to the Kyoto Launcher through two small files in the game folder:
 *  kyoto/quickplay.json - written by the launcher's "Quick play"; on the title screen Kyoto joins that server or opens that world.
 *  kyoto/playtime.json  - minutes played per day, sessions and your longest session; the launcher charts it.
 */
public final class LauncherLink {
	private LauncherLink() {}

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static Path dir() { return FabricLoader.getInstance().getGameDir().resolve("kyoto"); }

	private static boolean quickChecked = false;
	private static int ticks = 0;
	private static long sessionStart = 0, playedTicks = 0;

	public static void tick(Minecraft mc) {
		if (!quickChecked && Compat.currentScreen() instanceof TitleScreen) {
			quickChecked = true;
			try { quickPlay(mc); } catch (Throwable t) { /* never break the title screen */ }
		}
		// play time: only while actually in a world and not paused on a menu for ages
		boolean inWorld = mc.level != null && mc.player != null;
		if (inWorld) {
			if (sessionStart == 0) sessionStart = System.currentTimeMillis();
			playedTicks++;
		} else if (sessionStart != 0) {
			flush(true);
			sessionStart = 0;
		}
		if (++ticks % 1200 == 0 && playedTicks > 0) flush(false); // every minute
	}

	/* ---------------- quick play ---------------- */
	private static void quickPlay(Minecraft mc) throws Exception {
		Path f = dir().resolve("quickplay.json");
		if (!Files.exists(f)) return;
		JsonObject q = GSON.fromJson(Files.readString(f, StandardCharsets.UTF_8), JsonObject.class);
		Files.deleteIfExists(f); // one shot
		if (q == null || !q.has("type") || !q.has("target")) return;
		long at = q.has("at") ? q.get("at").getAsLong() : 0;
		if (System.currentTimeMillis() - at > 15 * 60_000L) return; // stale: the launcher didn't start the game
		String target = q.get("target").getAsString(), name = q.has("name") ? q.get("name").getAsString() : target;
		if ("server".equals(q.get("type").getAsString())) Compat.joinServer(target, name);
		else if ("world".equals(q.get("type").getAsString())) mc.execute(() -> openWorld(mc, target));
	}

	/** Opens a singleplayer world by folder name. Reflection because WorldOpenFlows moves around between versions. */
	private static void openWorld(Minecraft mc, String folder) {
		try {
			Object flows = null;
			for (Method m : mc.getClass().getMethods()) if (m.getParameterCount() == 0 && m.getReturnType().getSimpleName().contains("WorldOpenFlows")) { flows = m.invoke(mc); break; }
			if (flows == null) throw new IllegalStateException("no flows");
			for (Method m : flows.getClass().getMethods()) {
				if (!m.getName().equals("openWorld")) continue;
				Class<?>[] t = m.getParameterTypes();
				if (t.length == 2 && t[0] == String.class && Runnable.class.isAssignableFrom(t[1])) {
					m.invoke(flows, folder, (Runnable) () -> Compat.openScreen(new TitleScreen()));
					return;
				}
			}
			throw new IllegalStateException("no openWorld");
		} catch (Throwable t) {
			// fall back to the world list so the player can click it
			try { Compat.openScreen(new net.minecraft.client.gui.screens.worldselection.SelectWorldScreen(new TitleScreen())); } catch (Throwable ignored) { /* */ }
		}
	}

	/* ---------------- play time ---------------- */
	private static void flush(boolean endSession) {
		double minutes = playedTicks / 1200.0;
		playedTicks = 0;
		try {
			Files.createDirectories(dir());
			Path f = dir().resolve("playtime.json");
			JsonObject j = Files.exists(f) ? GSON.fromJson(Files.readString(f, StandardCharsets.UTF_8), JsonObject.class) : null;
			if (j == null) j = new JsonObject();
			Map<String, Double> days = new TreeMap<>();
			if (j.has("days")) for (var e : j.getAsJsonObject("days").entrySet()) days.put(e.getKey(), e.getValue().getAsDouble());
			String today = LocalDate.now(ZoneOffset.UTC).toString();
			days.merge(today, minutes, Double::sum);
			while (days.size() > 400) days.remove(days.keySet().iterator().next());
			JsonObject d = new JsonObject();
			for (var e : days.entrySet()) d.addProperty(e.getKey(), Math.round(e.getValue() * 10) / 10.0);
			j.add("days", d);
			long now = System.currentTimeMillis();
			j.addProperty("last", now);
			if (endSession && sessionStart > 0) {
				double len = (now - sessionStart) / 60000.0;
				j.addProperty("sessions", (j.has("sessions") ? j.get("sessions").getAsInt() : 0) + 1);
				if (len > (j.has("longest") ? j.get("longest").getAsDouble() : 0)) j.addProperty("longest", Math.round(len));
			}
			Path tmp = dir().resolve("playtime.json.tmp");
			Files.writeString(tmp, GSON.toJson(j), StandardCharsets.UTF_8);
			Files.move(tmp, f, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
		} catch (Exception e) { /* disk full / read-only: skip */ }
	}
}
