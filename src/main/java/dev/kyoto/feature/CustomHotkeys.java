package dev.kyoto.feature;

import dev.kyoto.config.KyotoConfig;
import dev.kyoto.hud.Notifications;
import dev.kyoto.module.Modules;
import dev.kyoto.util.Compat;
import net.minecraft.client.KeyMapping;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;

/**
 * Macros: 6 keys (Controls > Kyoto Client > Macro 1..6). Each runs one or more steps split by ";":
 *   /home ; wait 1 ; /sell all ; gg
 * "wait N" pauses N seconds. Commands start with /, anything else is sent as chat.
 * The first time you use one on a server, Kyoto reminds you that most servers don't allow macros.
 */
public final class CustomHotkeys {
	private CustomHotkeys() {}

	private record Step(long at, String text) {}

	private static final ArrayDeque<Step> QUEUE = new ArrayDeque<>();
	private static final Set<String> WARNED = new HashSet<>();

	public static void handle(KeyMapping[] keys) {
		for (int i = 0; i < keys.length; i++) {
			while (keys[i].consumeClick()) {
				if (!Modules.on("custom_hotkeys")) continue;
				String action = KyotoConfig.get().hotkeyActions.get(i);
				if (action == null || action.isBlank()) {
					Notifications.push("Macro " + (i + 1) + " is empty", "Set it in Tools > Macros", Notifications.WARN);
					continue;
				}
				run(action);
			}
		}
		long now = System.currentTimeMillis();
		while (!QUEUE.isEmpty() && QUEUE.peek().at() <= now) {
			String a = QUEUE.poll().text();
			if (a.startsWith("/")) Compat.sendCommand(a.substring(1));
			else Compat.sendPublicChat(a);
		}
	}

	public static void run(String action) {
		String server = Compat.currentServerIp();
		if (server != null && WARNED.add(server.toLowerCase()))
			Notifications.push("Macros: use at your own risk", "Most servers don't allow macros and they can get you banned.", Notifications.WARN, 7000, null, "bolt");
		long t = System.currentTimeMillis();
		for (String raw : action.split(";")) {
			String s = raw.trim();
			if (s.isEmpty()) continue;
			if (s.toLowerCase().startsWith("wait ")) {
				try { t += Math.round(Math.max(0, Math.min(30, Double.parseDouble(s.substring(5).trim()))) * 1000); } catch (Exception ignored) { }
				continue;
			}
			QUEUE.add(new Step(t, s));
			t += 120; // a tiny gap so the server never sees a burst
		}
	}
}
