package dev.kyoto.feature;

import dev.kyoto.config.KyotoConfig;
import dev.kyoto.module.Modules;
import net.minecraft.client.Minecraft;

/**
 * Your own time of day - only for you, only visual. The server's real time doesn't change, mobs spawn
 * exactly like before, beds still work by the real time. We just tell your game to DRAW a different time
 * (see TimeOverrideMixin).
 *
 * Modes: Fixed (always the time you picked), or Fast cycle (a full day every few minutes, just for you).
 */
public final class TimeChanger {
	private TimeChanger() {}

	public static final String[] PRESETS = {"Sunrise", "Morning", "Noon", "Afternoon", "Sunset", "Dusk", "Night", "Midnight"};
	public static final int[] PRESET_TICKS = {23200, 1000, 6000, 9000, 12000, 13000, 15000, 18000};

	public static boolean active() {
		Minecraft mc = Minecraft.getInstance();
		return mc.level != null && Modules.on("custom_time");
	}

	/** The time your game should draw, keeping the real day number. */
	public static long visualTime(long real) {
		long day = Math.floorDiv(real, 24000L);
		return day * 24000L + target();
	}

	/** 0..23999 */
	public static long target() {
		KyotoConfig c = KyotoConfig.get();
		if (Modules.opt("time.mode") == 1) {
			// fast cycle: one full day every N minutes, starting from your picked time
			int minutes = new int[]{2, 5, 10, 20}[Modules.opt("time.speed")];
			long ms = System.currentTimeMillis() % (minutes * 60_000L);
			return Math.floorMod(c.timeTicks + ms * 24000L / (minutes * 60_000L), 24000L);
		}
		return Math.floorMod(c.timeTicks, 24000);
	}

	public static boolean night() {
		long t = target();
		return t >= 13000 && t <= 23000;
	}

	/** "14:30" for a tick value (0 = 6:00) */
	public static String clock(long ticks) {
		long tod = Math.floorMod(ticks, 24000L);
		int hours = (int) ((tod / 1000 + 6) % 24), mins = (int) ((tod % 1000) * 60 / 1000);
		return String.format("%02d:%02d", hours, mins);
	}

	public static void set(int ticks) {
		KyotoConfig.get().timeTicks = Math.floorMod(ticks, 24000);
		KyotoConfig.save();
	}
}
