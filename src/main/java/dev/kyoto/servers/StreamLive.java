package dev.kyoto.servers;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.kyoto.module.Modules;
import dev.kyoto.net.KyotoNet;
import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.List;

/**
 * Sends your live server numbers to the Kyoto server for your stream overlays (money counter, bounty alert,
 * death counter, goal bar). Only while "Live stats for overlays" is on, and only to your own private overlay link.
 */
public final class StreamLive {
	private StreamLive() {}

	private static long lastSent = 0;
	private static final List<JsonObject> EVENTS = new ArrayList<>();
	public static int streamDeaths = 0, streamKills = 0;

	public static void event(String type, String text) {
		if (!Modules.on("stream_live")) return;
		if (type.equals("death")) streamDeaths++;
		if (type.equals("kill")) streamKills++;
		JsonObject e = new JsonObject();
		e.addProperty("type", type);
		e.addProperty("text", text == null ? "" : text);
		e.addProperty("at", System.currentTimeMillis());
		synchronized (EVENTS) {
			EVENTS.add(e);
		}
		lastSent = 0; // push right away
	}

	public static void tick(Minecraft mc) {
		if (!Modules.on("stream_live") || !KyotoNet.loggedIn() || mc.player == null) return;
		long now = System.currentTimeMillis();
		if (now - lastSent < 8000) return;
		lastSent = now;
		JsonObject b = new JsonObject();
		b.addProperty("server", Packs.current == null ? Packs.serverLabel : Packs.current.name());
		b.addProperty("pack", Packs.current == null ? "" : Packs.current.id());
		b.addProperty("money", ServerData.money);
		b.addProperty("session", ServerData.sessionMoney());
		b.addProperty("perHour", ServerData.moneyPerHour());
		b.addProperty("shards", ServerData.shards);
		b.addProperty("kills", streamKills);
		b.addProperty("deaths", streamDeaths);
		b.addProperty("bounty", Donut.myBounty);
		b.addProperty("hearts", Smp.hearts());
		b.addProperty("elo", Minis.elo);
		JsonArray ev = new JsonArray();
		synchronized (EVENTS) {
			for (JsonObject e : EVENTS) ev.add(e);
			EVENTS.clear();
		}
		b.add("events", ev);
		Thread t = new Thread(() -> {
			try {
				KyotoNet.send("POST", "/overlay/live", b, true);
			} catch (Exception ignored) {
			}
		}, "Kyoto overlay live");
		t.setDaemon(true);
		t.start();
	}

	public static void resetCounters() {
		streamDeaths = streamKills = 0;
	}
}
