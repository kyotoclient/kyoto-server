package dev.kyoto.util;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipFile;

/**
 * Turning other mods on/off from inside the game.
 *
 * Honest limits: Fabric loads every mod once, when the game starts. Nothing can load or unload a mod's code
 * while the game is running (their mixins are already baked into Minecraft's classes). So "off" = the jar is
 * renamed to .jar.disabled and the change happens on the next launch.
 *
 * Windows locks jars while the game runs, so there the rename is queued and done by a tiny helper the moment
 * the game closes. Kyoto's own modules are different - those switch instantly.
 */
public final class ModToggles {
	private ModToggles() {}

	public record Off(Path file, String id, String name, String desc) {}

	/** file to rename -> new file name (queued until the game closes on Windows) */
	private static final Map<Path, Path> PENDING = new LinkedHashMap<>();
	private static boolean hookAdded = false;

	public static Path modsDir() {
		return FabricLoader.getInstance().getGameDir().resolve("mods");
	}

	private static boolean windows() {
		return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
	}

	/** The jar a loaded mod came from, or null (built-in / nested library). */
	public static Path jarOf(ModContainer m) {
		try {
			if (m.getContainingMod().isPresent()) return null;
			for (Path p : m.getOrigin().getPaths()) {
				if (p.getFileName() != null && p.getFileName().toString().endsWith(".jar") && p.toAbsolutePath().startsWith(modsDir().toAbsolutePath())) return p;
			}
		} catch (Throwable ignored) {
		}
		return null;
	}

	public static boolean canToggle(ModContainer m) {
		String id = m.getMetadata().getId();
		if (id.equals("kyoto") || id.equals("fabric-api") || id.equals("fabricloader") || id.equals("minecraft") || id.equals("java")) return false;
		return jarOf(m) != null;
	}

	/** True if this loaded mod is set to be off after the next restart. */
	public static boolean turningOff(ModContainer m) {
		Path j = jarOf(m);
		return j != null && (PENDING.containsKey(j) || !Files.exists(j));
	}

	public static boolean restartNeeded() {
		return !PENDING.isEmpty() || changedNow;
	}

	private static boolean changedNow = false;

	/** Mods sitting in the mods folder as .jar.disabled */
	public static List<Off> disabled() {
		List<Off> out = new ArrayList<>();
		try (var s = Files.list(modsDir())) {
			for (Path p : s.toList()) {
				String n = p.getFileName().toString();
				if (!n.endsWith(".jar.disabled")) continue;
				String id = n, name = n.replace(".jar.disabled", ""), desc = "";
				try (ZipFile z = new ZipFile(p.toFile())) {
					var e = z.getEntry("fabric.mod.json");
					if (e != null) try (InputStream in = z.getInputStream(e)) {
						JsonObject o = JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
						if (o.has("id")) id = o.get("id").getAsString();
						if (o.has("name")) name = o.get("name").getAsString();
						if (o.has("description")) desc = o.get("description").getAsString();
					}
				} catch (Exception ignored) {
				}
				out.add(new Off(p, id, name, desc));
			}
		} catch (Exception ignored) {
		}
		out.sort((a, b) -> a.name().compareToIgnoreCase(b.name()));
		return out;
	}

	/** Turn a loaded mod off (takes effect next launch). */
	public static String turnOff(ModContainer m) {
		Path j = jarOf(m);
		if (j == null) return "This one can't be turned off from here";
		if (PENDING.remove(j) != null) return null; // was queued -> undo
		return rename(j, j.resolveSibling(j.getFileName() + ".disabled"));
	}

	/** Undo a queued "off" for a loaded mod. */
	public static void cancel(ModContainer m) {
		Path j = jarOf(m);
		if (j == null) return;
		PENDING.remove(j);
		Path d = j.resolveSibling(j.getFileName() + ".disabled");
		if (!Files.exists(j) && Files.exists(d)) rename(d, j);
	}

	/** Turn a disabled jar back on (takes effect next launch). */
	public static String turnOn(Off o) {
		String n = o.file().getFileName().toString();
		return rename(o.file(), o.file().resolveSibling(n.substring(0, n.length() - ".disabled".length())));
	}

	private static String rename(Path from, Path to) {
		try {
			Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
			changedNow = true;
			return null;
		} catch (Exception e) {
			if (!windows()) return "Couldn't rename: " + e.getMessage();
			PENDING.put(from, to); // locked by the running game - do it as soon as the game closes
			addHook();
			return null;
		}
	}

	private static void addHook() {
		if (hookAdded) return;
		hookAdded = true;
		Runtime.getRuntime().addShutdownHook(new Thread(ModToggles::applyAfterExit, "Kyoto-ModToggles"));
	}

	/** Starts a hidden cmd that waits a few seconds (until the jar is unlocked), then renames. */
	private static void applyAfterExit() {
		if (PENDING.isEmpty()) return;
		StringBuilder cmd = new StringBuilder("ping -n 4 127.0.0.1 >nul");
		for (var e : PENDING.entrySet()) {
			cmd.append(" & move /y \"").append(e.getKey().toAbsolutePath()).append("\" \"").append(e.getValue().toAbsolutePath()).append("\" >nul");
		}
		try {
			new ProcessBuilder("cmd", "/c", "start", "\"\"", "/min", "cmd", "/c", cmd.toString()).start();
		} catch (Exception ignored) {
		}
	}
}
