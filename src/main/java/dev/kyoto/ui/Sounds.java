package dev.kyoto.ui;

import dev.kyoto.feature.ClickSounds;
import dev.kyoto.module.Modules;
import dev.kyoto.util.Compat;

/**
 * Kyoto's UI sounds: soft kalimba plucks (Sakura), glass bells (Glass) or quiet ticks (Soft click).
 * Settings > UI Sounds picks the set and the volume.
 */
public final class Sounds {
	private Sounds() {}

	public static final String OPEN = "open", CLOSE = "close", TAB = "tab", ON = "on", OFF = "off", SUCCESS = "success", NOTIFY = "notify", KILL = "kill";
	private static final String[] SETS = {"droplet", "sakura", "glass", "soft"};
	private static long lastTab = 0;

	public static void play(String what) {
		if (!Modules.on("button_sounds")) return;
		long now = System.currentTimeMillis();
		if (what.equals(TAB)) {
			if (now - lastTab < 45) return; // never machine-gun
			lastTab = now;
		}
		String set = SETS[Math.max(0, Math.min(SETS.length - 1, Modules.opt("ui.sound")))];
		float vol = new float[]{0.25f, 0.5f, 0.75f, 1f}[Math.max(0, Math.min(3, Modules.opt("ui.volume")))];
		float base = switch (what) { case TAB -> 0.22f; case OFF, CLOSE, OPEN -> 0.28f; case KILL, SUCCESS -> 0.32f; default -> 0.3f; };
		Compat.uiSound(ClickSounds.sound("ui." + set + "_" + what), base * vol, 0.985f + (float) Math.random() * 0.03f);
	}

	/** A soft chirp for a pet. */
	public static void pet(int i) {
		Compat.uiSound(ClickSounds.sound("pet.chirp" + Math.floorMod(i, 3)), 0.18f, 0.95f + (float) Math.random() * 0.12f);
	}
}
