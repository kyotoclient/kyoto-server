package dev.kyoto.feature;

import dev.kyoto.module.Modules;

/** Streamer Mode: one place that decides what gets hidden on screen. */
public final class Privacy {
	private Privacy() {}

	public static boolean on() {
		return Modules.on("streamer_privacy");
	}

	public static boolean hideCoords() {
		return on() && Modules.opt("privacy.coords") == 0;
	}

	public static boolean hideServer() {
		return on() && Modules.opt("privacy.server") == 0;
	}

	public static boolean hideName() {
		return on() && Modules.opt("privacy.name") == 0;
	}

	public static String server(String ip) {
		return ip == null ? null : hideServer() ? "hidden" : ip;
	}
}
