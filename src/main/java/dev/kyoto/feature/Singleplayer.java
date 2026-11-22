package dev.kyoto.feature;

import dev.kyoto.config.KyotoConfig;
import dev.kyoto.hud.Notifications;
import dev.kyoto.module.Modules;
import dev.kyoto.util.Compat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;

/**
 * Singleplayer (and survival) tools:
 *  - Day counter + in-game clock      - World playtime (per world, saved)
 *  - Session stats: blocks broken, mobs killed, deaths, distance
 *  - Sleep reminder at night          - Death recap
 */
public final class Singleplayer {
	private Singleplayer() {}

	public static int blocksBroken = 0, kills = 0, deaths = 0;
	public static double distance = 0;
	private static Vec3 lastPos = null;
	private static boolean wasDead = false;
	private static long lastNightToast = -1;
	private static long playtimeTick = 0;
	private static String sessionWorld = null;

	public static void onKill() {
		kills++;
	}

	public static void onBlockBroken() {
		blocksBroken++;
	}

	/** Day/time (in ticks) via whatever this version calls it. */
	public static long dayTime(Minecraft mc) {
		if (mc.level == null) return 0;
		for (String n : new String[]{"getDayTime", "getOverworldClockTime", "dayTime"}) {
			try {
				Object v = mc.level.getClass().getMethod(n).invoke(mc.level);
				if (v instanceof Long l) return l;
			} catch (Throwable ignored) {
			}
		}
		return 0;
	}

	public static String dayLabel(Minecraft mc) {
		long t = dayTime(mc);
		long day = t / 24000 + 1;
		long tod = Math.floorMod(t, 24000L);
		int hours = (int) ((tod / 1000 + 6) % 24), mins = (int) ((tod % 1000) * 60 / 1000);
		boolean h24 = Modules.opt("clock.format") == 0;
		String clock = h24 ? String.format("%02d:%02d", hours, mins) : String.format("%d:%02d %s", hours % 12 == 0 ? 12 : hours % 12, mins, hours < 12 ? "AM" : "PM");
		return "Day " + day + "  ·  " + clock;
	}

	public static String playtime() {
		long ms = KyotoConfig.get().worldPlaytime.getOrDefault(Compat.worldKey(), 0L);
		long h = ms / 3_600_000, m = (ms / 60_000) % 60;
		return h > 0 ? h + "h " + m + "m played" : m + "m played";
	}

	public static void tick(Minecraft mc) {
		LocalPlayer p = mc.player;
		if (p == null || mc.level == null) {
			lastPos = null;
			return;
		}
		String world = Compat.worldKey();
		if (!world.equals(sessionWorld)) {
			sessionWorld = world;
			blocksBroken = kills = deaths = 0;
			distance = 0;
		}
		// distance
		Vec3 now = p.position();
		if (lastPos != null) {
			double d = now.distanceTo(lastPos);
			if (d < 10) distance += d;
		}
		lastPos = now;

		// playtime (saved every ~30s)
		if (Modules.on("world_playtime")) {
			playtimeTick++;
			KyotoConfig.get().worldPlaytime.merge(world, 50L, Long::sum);
			if (playtimeTick % 600 == 0) KyotoConfig.save();
		}

		// death recap
		boolean dead = p.isDeadOrDying();
		if (dead && !wasDead) {
			deaths++;
			if (Modules.on("death_recap")) {
				String where = Modules.on("coords_streamer") || dev.kyoto.feature.Privacy.hideCoords() ? "hidden" : p.getBlockX() + ", " + p.getBlockY() + ", " + p.getBlockZ();
				Notifications.push("You died", "at " + where + "  ·  " + Compat.pretty(Compat.dimensionId()) + "  ·  death #" + deaths + " this session",
						Notifications.DANGER, 9000, null, "☠");
			}
		}
		wasDead = dead;

		// sleep reminder (once per night, overworld)
		if (Modules.on("sleep_reminder") && Compat.dimensionId().endsWith("overworld")) {
			long t = dayTime(mc);
			long tod = Math.floorMod(t, 24000L), day = t / 24000;
			if (tod >= 12542 && tod < 13500 && lastNightToast != day) {
				lastNightToast = day;
				Notifications.push("Night is falling", "Sleep in a bed to skip it (and set your spawn).", Notifications.INFO, 6000, null, "☾");
			}
		}
	}
}
