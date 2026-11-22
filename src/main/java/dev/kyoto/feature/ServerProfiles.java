package dev.kyoto.feature;

import dev.kyoto.hud.Notifications;
import dev.kyoto.module.Modules;
import dev.kyoto.util.Compat;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Detects popular servers and tunes Kyoto for them. Everything here is client-side display/chat
 * only (no movement, combat or packet tricks), so it's safe to use and not bannable.
 */
public final class ServerProfiles {
	private ServerProfiles() {}

	public enum Profile { NONE, HYPIXEL, DONUT, GENERIC_SMP }

	public static Profile current = Profile.NONE;
	private static long lastGg = 0;

	private static final Pattern GAME_END = Pattern.compile("(1st Killer|Reward Summary|WINNER!|Winners?:|VICTORY|\\bwon the game\\b)", Pattern.CASE_INSENSITIVE);
	private static final Pattern LOBBY_SPAM = Pattern.compile("(joined the lobby!|found a .* Mystery Box|spooky|has been gifted)", Pattern.CASE_INSENSITIVE);

	public static void onJoin() {
		String ip = Compat.currentServerIp();
		String host = ip == null ? "" : ip.toLowerCase(Locale.ROOT);
		if (!Modules.on("server_profiles") || host.isEmpty()) {
			current = Profile.NONE;
			return;
		}
		if (host.contains("hypixel")) current = Profile.HYPIXEL;
		else if (host.contains("donutsmp") || host.contains("donut")) current = Profile.DONUT;
		else current = Profile.GENERIC_SMP;
		switch (current) {
			case HYPIXEL -> Notifications.push("Hypixel profile", "Lobby filter + Auto GG ready (toggle in Server tab)", Notifications.WARN, 5000, null, "⌂");
			case DONUT -> Notifications.push("DonutSMP profile", "Pay + TPA alerts tuned for Donut", Notifications.GOOD, 5000, null, "⌂");
			default -> {
			}
		}
	}

	/** @return true to hide this message */
	public static boolean filter(String plain) {
		if (current == Profile.HYPIXEL && Modules.on("lobby_filter") && LOBBY_SPAM.matcher(plain).find()) return true;
		if (current == Profile.HYPIXEL && Modules.on("autogg") && GAME_END.matcher(plain).find()) {
			long now = System.currentTimeMillis();
			if (now - lastGg > 15000) {
				lastGg = now;
				new Thread(() -> {
					try {
						Thread.sleep(1200);
					} catch (InterruptedException ignored) {
					}
					net.minecraft.client.Minecraft.getInstance().execute(() -> Compat.sendCommand("ac gg"));
				}).start();
			}
		}
		return false;
	}
}
