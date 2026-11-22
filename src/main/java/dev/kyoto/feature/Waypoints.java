package dev.kyoto.feature;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import dev.kyoto.module.Modules;
import dev.kyoto.hud.Notifications;
import dev.kyoto.util.Compat;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.sounds.SoundEvents;

import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Waypoints are saved per server / per singleplayer world in
 * .minecraft/config/kyoto/waypoints/&lt;world&gt;.json
 */
public final class Waypoints {
	private Waypoints() {}

	public enum Type { MANUAL, DEATH, BED, SHARED }

	public static final class Waypoint {
		public String name;
		public int x, y, z;
		public String dimension;
		public int color;
		public Type type = Type.MANUAL;
		public String created;
		/** Who made it (your name, or the friend who shared it). */
		public String owner = "";

		public double distanceTo(double px, double py, double pz) {
			double dx = x + 0.5 - px, dy = y - py, dz = z + 0.5 - pz;
			return Math.sqrt(dx * dx + dy * dy + dz * dz);
		}
	}

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final int[] PALETTE = {0x4FA3FF, 0x5CDB6B, 0xFFB330, 0xB46CFF, 0x3FE0D0, 0xFF7AC6, 0xF2E85C};
	private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("MMM d, HH:mm");

	private static final List<Waypoint> LIST = new ArrayList<>();
	private static String loadedFor = null;
	private static String tracked = null;

	public static List<Waypoint> all() {
		return LIST;
	}

	public static List<Waypoint> inCurrentDimension() {
		String dim = Compat.dimensionId();
		List<Waypoint> out = new ArrayList<>();
		for (Waypoint w : LIST) if (dim.equals(w.dimension)) out.add(w);
		return out;
	}

	public static List<Waypoint> sortedByDistance() {
		LocalPlayer p = Minecraft.getInstance().player;
		List<Waypoint> out = new ArrayList<>(LIST);
		if (p != null) {
			String dim = Compat.dimensionId();
			out.sort(Comparator
					.comparing((Waypoint w) -> !dim.equals(w.dimension))
					.thenComparingDouble(w -> w.distanceTo(p.getX(), p.getY(), p.getZ())));
		}
		return out;
	}

	public static Waypoint tracked() {
		if (tracked == null) return null;
		for (Waypoint w : LIST) if (w.name.equalsIgnoreCase(tracked)) return w;
		return null;
	}

	public static void track(Waypoint w) {
		tracked = w == null ? null : w.name;
		if (w != null) {
			Notifications.push("Tracking " + w.name, w.x + ", " + w.y + ", " + w.z, w.color);
			sound(1.4f);
		}
	}

	public static Waypoint find(String name) {
		for (Waypoint w : LIST) if (w.name.equalsIgnoreCase(name)) return w;
		return null;
	}

	/* ---------------- add / remove ---------------- */

	public static Waypoint addHere(String name, Type type) {
		LocalPlayer p = Minecraft.getInstance().player;
		if (p == null) return null;
		return add(name, p.getBlockX(), p.getBlockY(), p.getBlockZ(), type);
	}

	public static Waypoint add(String name, int x, int y, int z, Type type) {
		if (name == null || name.isBlank()) name = nextName();
		name = uniqueName(name.trim().replace(' ', '_'));
		Waypoint w = new Waypoint();
		w.name = name;
		w.x = x;
		w.y = y;
		w.z = z;
		w.dimension = Compat.dimensionId();
		w.type = type;
		w.created = LocalDateTime.now().format(TIME);
		w.owner = Compat.myName();
		w.color = switch (type) {
			case DEATH -> 0xFF4A4A;
			case BED -> 0xFF9AD5;
			case SHARED -> 0x3FE0D0;
			default -> PALETTE[LIST.size() % PALETTE.length];
		};
		LIST.add(w);
		save();
		WorldFx.waypointCreated(w);
		return w;
	}

	public static boolean remove(String name) {
		Waypoint w = find(name);
		if (w == null) return false;
		LIST.remove(w);
		if (tracked != null && tracked.equalsIgnoreCase(name)) tracked = null;
		save();
		return true;
	}

	/** Turns death waypoints on/off. Off also removes the ones you already have (and their red trail). */
	public static void setDeathWaypoints(boolean on) {
		if (Modules.on("death_waypoint") != on) Modules.get("death_waypoint").toggle();
		if (!on) clearType(Type.DEATH);
		dev.kyoto.config.KyotoConfig.save();
	}

	public static int clearType(Type type) {
		int before = LIST.size();
		LIST.removeIf(w -> w.type == type);
		save();
		return before - LIST.size();
	}

	private static String nextName() {
		int i = 1;
		while (find("Waypoint_" + i) != null) i++;
		return "Waypoint_" + i;
	}

	private static String uniqueName(String base) {
		if (find(base) == null) return base;
		int i = 2;
		while (find(base + "_" + i) != null) i++;
		return base + "_" + i;
	}

	/* ---------------- sharing between Kyoto players ---------------- */

	/** A waypoint someone sent you (through your Kyoto server or a whisper). */
	public static void receive(String from, String name, int x, int y, int z, String dim) {
		if (loadedFor == null) return;
		Waypoint w = add(name, x, y, z, Type.SHARED);
		w.owner = from;
		if (dim != null && !dim.isBlank()) w.dimension = dim;
		save();
		Notifications.push(from + " shared a waypoint", w.name + "  " + x + ", " + y + ", " + z, 0x3FE0D0, 6000, from, null);
		sound(1.5f);
	}

	/** Sends a waypoint to a player: through your Kyoto server if connected, otherwise as a private whisper. */
	public static void sendTo(String player, Waypoint w) {
		if (dev.kyoto.net.KyotoNet.loggedIn()) {
			dev.kyoto.net.KyotoNet.sendWaypoint(player, w);
		} else {
			Compat.sendCommand("msg " + player + " [KWP] " + w.name + " " + w.x + " " + w.y + " " + w.z + " " + w.dimension);
			Notifications.push("Waypoint whispered", w.name + " → " + player + " (they need Kyoto)", Notifications.GOOD);
		}
	}

	/* ---------------- automatic waypoints ---------------- */

	private static boolean wasDead = false;
	private static boolean wasSleeping = false;

	public static void tick(Minecraft mc) {
		LocalPlayer p = mc.player;
		if (p == null || mc.level == null) return;
		// never keep one world's waypoints in another (e.g. if a join event was missed)
		if (loadedFor == null || !loadedFor.equals(Compat.worldKey())) { loadForCurrentWorld(); return; }

		boolean dead = p.isDeadOrDying();
		if (dead && !wasDead && Modules.on("death_waypoint")) {
			// keep only the 5 most recent deaths
			List<Waypoint> deaths = new ArrayList<>();
			for (Waypoint w : LIST) if (w.type == Type.DEATH) deaths.add(w);
			while (deaths.size() >= 5) LIST.remove(deaths.remove(0));

			Waypoint w = add("Death", p.getBlockX(), p.getBlockY(), p.getBlockZ(), Type.DEATH);
			tracked = w.name;
			Notifications.push("You died", "Saved " + w.x + ", " + w.y + ", " + w.z + " - follow the red trail", Notifications.DANGER, 6000);
		}
		wasDead = dead;

		boolean sleeping = p.isSleeping();
		if (sleeping && !wasSleeping && Modules.on("bed_waypoint")) {
			String dim = Compat.dimensionId();
			LIST.removeIf(w -> w.type == Type.BED && dim.equals(w.dimension));
			Waypoint w = add("Bed", p.getBlockX(), p.getBlockY(), p.getBlockZ(), Type.BED);
			Notifications.push("Bed saved", w.x + ", " + w.y + ", " + w.z, 0xFF9AD5);
		}
		wasSleeping = sleeping;
	}

	/* ---------------- saving ---------------- */

	private static Path file() {
		return FabricLoader.getInstance().getConfigDir().resolve("kyoto").resolve("waypoints").resolve(Compat.worldKey() + ".json");
	}

	public static void loadForCurrentWorld() {
		LIST.clear();
		tracked = null;
		wasDead = false;
		wasSleeping = false;
		loadedFor = Compat.worldKey();
		Path f = file();
		if (!Files.exists(f)) return;
		try (Reader r = Files.newBufferedReader(f)) {
			List<Waypoint> loaded = GSON.fromJson(r, new TypeToken<List<Waypoint>>() {}.getType());
			if (loaded != null) LIST.addAll(loaded);
		} catch (Exception ignored) {
		}
	}

	public static void save() {
		if (loadedFor == null) return;
		try {
			Path f = file();
			Files.createDirectories(f.getParent());
			try (Writer w = Files.newBufferedWriter(f)) {
				GSON.toJson(LIST, w);
			}
		} catch (Exception ignored) {
		}
	}

	public static void unload() {
		save();
		LIST.clear();
		loadedFor = null;
		tracked = null;
	}

	public static void sound(float pitch) {
		if (Modules.on("warning_sounds")) {
			Compat.sound(SoundEvents.AMETHYST_BLOCK_CHIME, 0.8f, pitch);
		}
	}
}
