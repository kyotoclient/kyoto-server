package dev.kyoto.extra;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.kyoto.hud.Notifications;
import dev.kyoto.module.Modules;
import dev.kyoto.net.KyotoNet;
import dev.kyoto.servers.Packs;
import dev.kyoto.social.VoiceRooms;
import dev.kyoto.util.Compat;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;

/**
 * Proximity voice: on any server, Kyoto players get put in one voice room for that server, and everyone's volume
 * follows how close they are in game (loud next to you, silent past the range). Uses Kyoto's voice rooms, so the
 * server doesn't need a voice plugin. Needs Simple Voice Chat installed (for the voice codec), like Voice Rooms.
 */
public final class ProxVoice {
	private ProxVoice() {}

	private static String joinedFor = "";
	private static volatile boolean asking = false;
	private static long lastTry = 0;

	public static void tick(Minecraft mc) {
		boolean want = Modules.on("prox_voice") && mc.player != null && !Packs.host.isEmpty() && KyotoNet.loggedIn();
		if (!want) {
			if (!joinedFor.isEmpty()) {
				if (VoiceRooms.inRoom()) VoiceRooms.leave(true);
				joinedFor = "";
				VoiceRooms.VOLUME.clear();
			}
			return;
		}
		String host = Packs.host;
		if (!host.equals(joinedFor) && !asking && System.currentTimeMillis() - lastTry > 15000) {
			if (!VoiceRooms.available()) {
				lastTry = System.currentTimeMillis();
				Notifications.push("Nearby voice needs Simple Voice Chat", "Install it (free on Modrinth) for the voice codec", Notifications.INFO, 5000, null, "mic");
				return;
			}
			if (VoiceRooms.inRoom() && joinedFor.isEmpty()) return; // you're in a normal voice room, leave that alone
			asking = true;
			lastTry = System.currentTimeMillis();
			JsonObject b = new JsonObject();
			b.addProperty("host", host);
			Thread t = new Thread(() -> {
				try {
					String id = JsonParser.parseString(KyotoNet.send("POST", "/voice/server", b, true)).getAsJsonObject().get("id").getAsString();
					Compat.runOnClient(() -> {
						VoiceRooms.join(id);
						joinedFor = host;
					});
				} catch (Exception ignored) {
				} finally {
					asking = false;
				}
			}, "Kyoto nearby voice");
			t.setDaemon(true);
			t.start();
			return;
		}
		if (!VoiceRooms.inRoom()) return;
		// volume by distance
		double range = new double[]{16, 32, 48, 64}[Math.max(0, Math.min(3, Modules.opt("prox.range")))];
		for (VoiceRooms.Member m : VoiceRooms.members) {
			Player p = null;
			for (Player pl : mc.level.players()) if (m.name().equalsIgnoreCase(Compat.profileName(pl.getGameProfile()))) { p = pl; break; }
			float vol;
			if (p == null) vol = 0f;
			else {
				double d = p.distanceTo(mc.player);
				vol = d >= range ? 0f : (float) Math.pow(1 - d / range, 1.4);
			}
			VoiceRooms.VOLUME.put(m.name(), vol);
		}
	}
}
