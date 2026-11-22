package dev.kyoto.extra;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.kyoto.hud.Notifications;
import dev.kyoto.module.Modules;
import dev.kyoto.net.KyotoNet;
import dev.kyoto.servers.Packs;
import dev.kyoto.social.Social;
import dev.kyoto.util.Anim;
import dev.kyoto.util.Compat;
import dev.kyoto.util.Proj;
import dev.kyoto.util.Ui;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Kyoto players near you: their pets, their emotes, and friends sharing their live location.
 * Every couple of seconds the game asks the Kyoto server about the players it can see (names only).
 */
public final class Nearby {
	private Nearby() {}

	public static final String[] PETS = {"none", "fox", "axolotl", "bee", "frog", "cat", "ghost", "dragon", "slime", "star", "bunny"};
	public static final String[] EMOTES = Social.STICKERS;

	public record Pet(String type, String name) {}

	public record Emote(String id, long at) {}

	public record FriendPos(String name, int x, int y, int z, String dim, long at) {}

	public static final Map<String, Pet> PETS_OF = new ConcurrentHashMap<>();
	public static final Map<String, Emote> EMOTES_OF = new ConcurrentHashMap<>();
	public static volatile List<FriendPos> friends = List.of();
	public static volatile String myPet = null;

	/** Your pet (remembered between games). */
	public static String myPet() {
		if (myPet == null) myPet = dev.kyoto.config.KyotoConfig.get().petType;
		return myPet == null ? "none" : myPet;
	}
	private static long lastNear = 0, lastShare = 0, lastFriends = 0;
	private static volatile boolean busy = false;

	private static Identifier petTex(String id) {
		return Identifier.fromNamespaceAndPath("kyoto", "textures/pets/" + id.replaceAll("[^a-z]", "") + ".png");
	}

	/* ---------------- your choices ---------------- */

	public static void setPet(String type, String name) {
		myPet = type;
		dev.kyoto.config.KyotoConfig.get().petType = type;
		dev.kyoto.config.KyotoConfig.get().petName = name == null ? "" : name;
		dev.kyoto.config.KyotoConfig.save();
		JsonObject b = new JsonObject();
		b.addProperty("pet", type);
		b.addProperty("name", name == null ? "" : name);
		bg(() -> KyotoNet.send("POST", "/pet", b, true));
		String me = Compat.myName();
		if (me != null) {
			if (type.equals("none")) PETS_OF.remove(me);
			else PETS_OF.put(me, new Pet(type, name == null ? "" : name));
		}
	}

	public static void emote(String id) {
		String me = Compat.myName();
		if (me != null && !id.startsWith("dance:")) EMOTES_OF.put(me, new Emote(id, System.currentTimeMillis()));
		JsonObject b = new JsonObject();
		b.addProperty("e", id);
		bg(() -> KyotoNet.send("POST", "/emote", b, true));
	}

	/* ---------------- polling ---------------- */

	public static void tick(Minecraft mc) {
		if (mc.player == null || mc.level == null || !KyotoNet.loggedIn() || busy) return;
		long now = System.currentTimeMillis();
		if ((Modules.on("pets") || Modules.on("emotes") || Modules.on("wardrobe")) && now - lastNear > 2500) {
			lastNear = now;
			StringBuilder names = new StringBuilder();
			int n = 0;
			for (Player p : mc.level.players()) {
				if (p.distanceTo(mc.player) > 64 && p != mc.player) continue;
				String nm = Compat.profileName(p.getGameProfile());
				if (nm == null) continue;
				if (n++ > 0) names.append(',');
				names.append(nm);
				if (n >= 50) break;
			}
			String q = names.toString();
			busy = true;
			bg(() -> {
				try {
					JsonObject o = JsonParser.parseString(KyotoNet.send("GET", "/near?names=" + KyotoNet.enc(q), null, true)).getAsJsonObject();
					PETS_OF.clear();
					for (var e : o.getAsJsonObject("pets").entrySet()) {
						JsonObject p = e.getValue().getAsJsonObject();
						PETS_OF.put(e.getKey(), new Pet(p.get("pet").getAsString(), p.has("name") ? p.get("name").getAsString() : ""));
					}
					dev.kyoto.cosmetic.Wardrobe.WEAR_OF.clear();
					if (o.has("wear")) for (var e : o.getAsJsonObject("wear").entrySet()) {
						java.util.Map<String, String> m = new java.util.HashMap<>();
						for (var s2 : e.getValue().getAsJsonObject().entrySet()) m.put(s2.getKey(), s2.getValue().getAsString());
						dev.kyoto.cosmetic.Wardrobe.WEAR_OF.put(e.getKey(), m);
					}
					long t = System.currentTimeMillis();
					for (var e : o.getAsJsonObject("emotes").entrySet()) {
						String id = e.getValue().getAsJsonObject().get("e").getAsString();
						Emote old = EMOTES_OF.get(e.getKey());
						if (id.startsWith("dance:")) { dev.kyoto.feature.Dances.remote(e.getKey(), id.substring(6)); continue; }
						if (old == null || !old.id.equals(id) || t - old.at > 6000) EMOTES_OF.put(e.getKey(), new Emote(id, t));
					}
				} finally {
					busy = false;
				}
				return null;
			});
		}
		// live location: send yours, read your friends'
		String host = Packs.host;
		boolean sharing = Modules.on("live_share") && !host.isEmpty() && !dev.kyoto.feature.Privacy.hideCoords();
		if (sharing && now - lastShare > 3000) {
			lastShare = now;
			JsonObject b = new JsonObject();
			b.addProperty("host", host);
			b.addProperty("x", mc.player.getBlockX());
			b.addProperty("y", mc.player.getBlockY());
			b.addProperty("z", mc.player.getBlockZ());
			b.addProperty("dim", Compat.dimensionId());
			bg(() -> KyotoNet.send("POST", "/live/pos", b, true));
		}
		if (Modules.on("live_share_see") && !host.isEmpty() && now - lastFriends > 3000) {
			lastFriends = now;
			bg(() -> {
				JsonObject o = JsonParser.parseString(KyotoNet.send("GET", "/live/friends?host=" + KyotoNet.enc(host), null, true)).getAsJsonObject();
				List<FriendPos> l = new ArrayList<>();
				JsonArray a = o.getAsJsonArray("friends");
				long t = System.currentTimeMillis();
				for (var e : a) {
					JsonObject f = e.getAsJsonObject();
					l.add(new FriendPos(f.get("name").getAsString(), f.get("x").getAsInt(), f.get("y").getAsInt(), f.get("z").getAsInt(), f.get("dim").getAsString(), t));
				}
				List<String> before = friends.stream().map(FriendPos::name).toList();
				for (FriendPos f : l) if (!before.contains(f.name)) Compat.runOnClient(() -> Notifications.push(f.name + " is sharing their location", "Look for their marker", Notifications.INFO, 3500, f.name, null));
				friends = l;
				return null;
			});
		}
	}

	public static void stopSharing() {
		JsonObject b = new JsonObject();
		b.addProperty("off", true);
		bg(() -> KyotoNet.send("POST", "/live/pos", b, true));
	}

	private interface Job {
		Object run() throws Exception;
	}

	private static void bg(Job j) {
		Thread t = new Thread(() -> {
			try {
				j.run();
			} catch (Throwable ignored) {
			}
		}, "Kyoto nearby");
		t.setDaemon(true);
		t.start();
	}

	/* ---------------- drawing ---------------- */

	public static void render(GuiGraphicsExtractor g, Minecraft mc, int w, int h, float partial) {
		if (mc.level == null || mc.player == null) return;
		Vec3 eye = Proj.cameraPos(partial);
		boolean firstPerson = mc.options.getCameraType().isFirstPerson() && !Freelook.active;
		long now = System.currentTimeMillis();
		for (Player p : mc.level.players()) {
			String name = Compat.profileName(p.getGameProfile());
			if (name == null || p.isInvisible()) continue;
			boolean me = p == mc.player;
			if (me && firstPerson) {
				// your own emote shows bottom-centre when you can't see yourself
				Emote e = EMOTES_OF.get(name);
				if (e != null && now - e.at < 4000 && Modules.on("emotes")) bubbleFlat(g, e, w / 2, h - 70, now);
				continue;
			}
			double d = p.distanceTo(mc.player);
			if (d > 48) continue;
			Vec3 pos = p.getPosition(partial);
			Pet pet = PETS_OF.get(name);
			if (pet != null && Modules.on("pets") && !pet.type.equals("none")) drawPet(g, p, pet, pos, eye, partial, w, h, now);
			Emote e = EMOTES_OF.get(name);
			if (e != null && now - e.at < 4000 && Modules.on("emotes")) {
				Vec3 top = pos.add(0, p.getBbHeight() + 0.75, 0);
				if (!Compat.blockedFromCamera(eye, top)) {
					float[] s = Proj.project(top, partial, w, h);
					if (s != null) {
						float sc = (float) Math.max(0.5, Math.min(1.6, 7 / s[2]));
						g.pose().pushMatrix();
						g.pose().translate(s[0], s[1]);
						g.pose().scale(sc, sc);
						bubbleFlat(g, e, 0, 0, now);
						g.pose().popMatrix();
					}
				}
			}
		}
		if (Modules.on("live_share_see")) for (FriendPos f : friends) friendMarker(g, mc, f, partial, w, h, eye);
	}

	private static void drawPet(GuiGraphicsExtractor g, Player p, Pet pet, Vec3 pos, Vec3 eye, float partial, int w, int h, long now) {
		// 3D pets draw themselves in the world; here we only add the name tag over them
		if (dev.kyoto.cosmetic.Pets3D.active()) {
			Vec3 at3 = dev.kyoto.cosmetic.Pets3D.WHERE.get(Compat.profileName(p.getGameProfile()));
			if (at3 == null || pet.name.isEmpty() || Compat.blockedFromCamera(eye, at3)) return;
			float[] s3 = Proj.project(at3, partial, w, h);
			if (s3 == null || s3[2] > 12) return;
			String n = pet.name;
			int tw = Ui.widthSmall(n) + 8;
			Ui.round(g, Math.round(s3[0] - tw / 2f), Math.round(s3[1] - 10), Math.round(s3[0] + tw / 2f), Math.round(s3[1]), 5, Anim.argb(Ui.GLASS, 0.7f));
			Ui.small(g, n, s3[0] - Ui.widthSmall(n) / 2f, s3[1] - 7, Anim.argb(Ui.INK, 0.9f));
			return;
		}
		// floats beside the player's shoulder and bobs a little
		double yaw = Math.toRadians(p.getViewYRot(partial) + 90);
		double t = (now + (p.getId() * 397L)) / 1000.0;
		Vec3 at = pos.add(Math.cos(yaw + 1.1) * 0.85, p.getBbHeight() * 0.85 + Math.sin(t * 2.2) * 0.08, Math.sin(yaw + 1.1) * 0.85);
		if (Compat.blockedFromCamera(eye, at)) return;
		float[] s = Proj.project(at, partial, w, h);
		if (s == null) return;
		int size = Math.round((float) Math.max(8, Math.min(64, 0.55 * Proj.focal(h) / s[2])));
		float squish = 1f + 0.06f * (float) Math.sin(t * 6);
		g.pose().pushMatrix();
		g.pose().translate(s[0], s[1]);
		g.pose().scale(1f / squish, squish);
		g.fill(-size / 3, size / 2 + 1, size / 3, size / 2 + 2, Anim.argb(0x000000, 0.25f));
		Compat.blitTint(g, petTex(pet.type), -size / 2, -size / 2, size, size, 0xFFFFFFFF);
		g.pose().popMatrix();
		if (!pet.name.isEmpty() && s[2] < 10) {
			String n = pet.name;
			Ui.small(g, n, Math.round(s[0] - Ui.widthSmall(n) / 2f), Math.round(s[1] - size / 2f - 8), Anim.argb(Ui.INK, 0.85f));
		}
	}

	private static void bubbleFlat(GuiGraphicsExtractor g, Emote e, int x, int y, long now) {
		float age = (now - e.at) / 1000f;
		float pop = age < 0.25f ? Anim.easeOutBack(age / 0.25f) : 1f;
		float fade = age > 3.4f ? Math.max(0, 1 - (age - 3.4f) / 0.6f) : 1f;
		int s = Math.round(30 * pop);
		if (s <= 0) return;
		Ui.round(g, x - s / 2 - 3, y - s - 6, x + s / 2 + 3, y, 8, Anim.argb(Ui.GLASS, 0.75f * fade));
		g.fill(x - 2, y, x + 2, y + 3, Anim.argb(Ui.GLASS, 0.75f * fade));
		Compat.blitTint(g, Social.sticker(e.id), x - s / 2, y - s - 3, s, s, Anim.argb(0xFFFFFF, fade));
	}

	private static void friendMarker(GuiGraphicsExtractor g, Minecraft mc, FriendPos f, float partial, int w, int h, Vec3 eye) {
		if (!f.dim.equals(Compat.dimensionId())) return;
		Vec3 at = new Vec3(f.x + 0.5, f.y + 2.2, f.z + 0.5);
		double d = at.distanceTo(mc.player.position());
		if (d < 6) return; // you can see them
		float[] s = Proj.project(at, partial, w, h);
		if (s == null) return;
		int x = Math.round(Math.max(20, Math.min(w - 20, s[0]))), y = Math.round(Math.max(20, Math.min(h - 30, s[1])));
		String t = f.name + "  " + Math.round(d) + "m";
		int tw = Ui.width(t) + 22;
		Ui.hud(g, x - tw / 2, y - 7, x + tw / 2, y + 7, 7, 0.9f);
		Compat.drawHead(g, f.name, x - tw / 2 + 3, y - 5, 10);
		Ui.text(g, t, x - tw / 2 + 16, y - 3, Anim.argb(Ui.TEXT, 1f));
		g.fill(x - 1, y + 7, x + 1, y + 12, Anim.argb(Ui.accent(), 0.9f));
	}
}
