package dev.kyoto.extra;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.kyoto.feature.Fishing;
import dev.kyoto.hud.Notifications;
import dev.kyoto.module.Modules;
import dev.kyoto.net.KyotoNet;
import dev.kyoto.servers.Packs;
import dev.kyoto.util.Compat;
import net.minecraft.client.Minecraft;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

import java.util.HashSet;
import java.util.Set;

/**
 * Daily login reward, weekly challenges and achievement pop-ups.
 * The game only counts things it can see (blocks you break/place, kills, distance, minutes, fish) and sends the
 * totals every 5 minutes. Coins go to your Kyoto account (Store / trades).
 */
public final class Rewards {
	private Rewards() {}

	public static volatile JsonObject weekly = null;
	public static volatile int streak = 0, coins = -1;
	private static double blocks, placed, dist, kills, mins, fish;
	private static int fishBase = -1;
	private static long lastSend = 0, lastAch = 0, lastMinute = 0, dailyTried = 0;
	private static Vec3 lastPos = null;
	private static Entity lastHit = null;
	private static long lastHitAt = 0;
	private static Set<String> seen = null;

	public static void onBreak() { blocks++; }

	public static void onPlace() { placed++; }

	public static void onAttack(Entity e) {
		lastHit = e;
		lastHitAt = System.currentTimeMillis();
	}

	public static void tick(Minecraft mc) {
		if (mc.player == null || !KyotoNet.loggedIn()) return;
		long now = System.currentTimeMillis();
		// kills: something you hit in the last 3 seconds died
		if (lastHit != null) {
			if (lastHit instanceof LivingEntity le && (le.isDeadOrDying() || le.isRemoved()) && now - lastHitAt < 3000) {
				if (le.isDeadOrDying()) kills++;
				lastHit = null;
			} else if (now - lastHitAt > 3000) lastHit = null;
		}
		Vec3 p = mc.player.position();
		if (lastPos != null && !Afk.afk) {
			double d = p.distanceTo(lastPos);
			if (d < 12) dist += d; // teleports don't count
		}
		lastPos = p;
		if (now - lastMinute > 60000) {
			lastMinute = now;
			if (!Afk.afk) mins++;
		}
		if (fishBase < 0) fishBase = Fishing.session;
		if (Fishing.session > fishBase) { fish += Fishing.session - fishBase; fishBase = Fishing.session; }

		if (Modules.on("daily_reward") && dailyTried == 0) {
			dailyTried = now;
			bg(() -> {
				JsonObject o = JsonParser.parseString(KyotoNet.send("POST", "/daily", new JsonObject(), true)).getAsJsonObject();
				streak = o.get("streak").getAsInt();
				coins = o.has("coins") ? o.get("coins").getAsInt() : coins;
				if (o.get("claimed").getAsBoolean()) {
					int r = o.get("reward").getAsInt();
					Compat.runOnClient(() -> {
						Notifications.push("Daily reward: +" + r + " coins", streak > 1 ? streak + " days in a row" + (streak % 7 == 0 ? " · week bonus!" : "") : "Come back tomorrow for more", Notifications.MAGIC, 6000, null, "gift");
						Compat.sound(SoundEvents.PLAYER_LEVELUP, 0.5f, 1.4f);
					});
				}
			});
		}
		if (Modules.on("weekly_challenges") && now - lastSend > 5 * 60000) {
			lastSend = now;
			JsonObject b = new JsonObject();
			b.addProperty("mins", Math.floor(mins));
			b.addProperty("blocks", Math.floor(blocks));
			b.addProperty("placed", Math.floor(placed));
			b.addProperty("dist", Math.floor(dist));
			b.addProperty("kills", Math.floor(kills));
			b.addProperty("fish", Math.floor(fish));
			mins = blocks = placed = dist = kills = fish = 0;
			bg(() -> {
				JsonObject o = JsonParser.parseString(KyotoNet.send("POST", "/challenges/progress", b, true)).getAsJsonObject();
				weekly = o;
				if (o.has("coins")) coins = o.get("coins").getAsInt();
				if (o.has("finished")) for (JsonElement e : o.getAsJsonArray("finished")) {
					String t = e.getAsString();
					Compat.runOnClient(() -> {
						Notifications.push("Challenge done!", t + " · +50 coins", Notifications.MAGIC, 7000, null, "crown");
						Compat.sound(SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, 0.5f, 1f);
					});
				}
			});
		}
		if (Modules.on("achievement_toasts") && now - lastAch > 8 * 60000) {
			lastAch = now;
			bg(() -> {
				JsonObject o = JsonParser.parseString(KyotoNet.send("GET", "/achievements", null, true)).getAsJsonObject();
				JsonArray l = o.getAsJsonArray("list");
				JsonObject store = Packs.read("achievements_seen");
				boolean first = !store.has("ids");
				Set<String> had = new HashSet<>();
				if (!first) for (JsonElement e : store.getAsJsonArray("ids")) had.add(e.getAsString());
				JsonArray ids = new JsonArray();
				for (JsonElement e : l) {
					JsonObject a = e.getAsJsonObject();
					if (a.get("at").getAsLong() <= 0) continue;
					String id = a.get("id").getAsString();
					ids.add(id);
					if (!first && !had.contains(id)) {
						String name = a.get("name").getAsString(), how = a.get("how").getAsString();
						int pts = a.get("points").getAsInt();
						Compat.runOnClient(() -> {
							Notifications.push("Achievement: " + name, how + " · +" + pts + " points", Notifications.MAGIC, 8000, null, "star");
							Compat.sound(SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, 0.5f, 1.2f);
						});
					}
				}
				store.add("ids", ids);
				Packs.write("achievements_seen", store);
			});
		}
	}

	/** For the Rewards screen. */
	public static void refresh() {
		bg(() -> {
			weekly = JsonParser.parseString(KyotoNet.send("GET", "/challenges", null, true)).getAsJsonObject();
			JsonObject d = JsonParser.parseString(KyotoNet.send("POST", "/daily", new JsonObject(), true)).getAsJsonObject();
			streak = d.get("streak").getAsInt();
			if (d.has("coins")) coins = d.get("coins").getAsInt();
		});
	}

	private interface Job {
		void run() throws Exception;
	}

	private static void bg(Job j) {
		Thread t = new Thread(() -> {
			try {
				j.run();
			} catch (Throwable ignored) {
			}
		}, "Kyoto rewards");
		t.setDaemon(true);
		t.start();
	}
}
