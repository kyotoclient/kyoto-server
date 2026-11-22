package dev.kyoto.cosmetic;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.kyoto.config.KyotoConfig;
import dev.kyoto.extra.Nearby;
import dev.kyoto.module.Modules;
import dev.kyoto.util.Compat;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

import java.util.HashMap;
import java.util.Map;

/**
 * 3D pets. Each one follows its owner with a soft spring, turns to face where it's going, and has its own animations:
 * walking legs and a wagging tail, hops with squash and stretch, flapping wings, swimming wiggles, floaty bobbing...
 * Kyoto players near you see yours (and you see theirs).
 */
public final class Pets3D {
	private Pets3D() {}

	public static volatile String error = null;
	private static long lastDraw = 0;

	public static boolean active() {
		return System.currentTimeMillis() - lastDraw < 1500;
	}

	/** Per-owner pet state. */
	static final class State {
		double x, y, z, vx, vy, vz;
		float yaw, walk, speed, hop, land, wag = 1f, idleT;
		long seen, nextTrick = System.currentTimeMillis() + 4000, trickAt = 0;
		boolean placed;
		int trick;
	}

	private static final Map<String, State> STATES = new HashMap<>();
	private static long lastNanos = 0;
	/** Where each owner's pet is this frame (for name tags). */
	public static final Map<String, Vec3> WHERE = new HashMap<>();

	static boolean flyer(String t) {
		return t.equals("bee") || t.equals("ghost") || t.equals("dragon") || t.equals("star") || t.equals("axolotl");
	}

	static boolean hopper(String t) {
		return t.equals("bunny") || t.equals("frog") || t.equals("slime");
	}

	public static void render(PoseStack pose, Vec3 cam, float partial) throws Exception {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null || mc.player == null || !Modules.on("pets")) return;
		long nowN = System.nanoTime();
		float dt = lastNanos == 0 ? 0.016f : Math.min(0.05f, (nowN - lastNanos) / 1e9f);
		lastNanos = nowN;
		Matrix4f camM = pose.last().pose();
		long now = System.currentTimeMillis();
		WHERE.clear();
		int drawn = 0;
		for (Player p : mc.level.players()) {
			if (drawn >= 12) break;
			String name = Compat.profileName(p.getGameProfile());
			if (name == null) continue;
			Nearby.Pet pet = Nearby.PETS_OF.get(name);
			String type = pet == null ? null : pet.type();
			if (p == mc.player && (type == null || type.equals("none"))) type = Nearby.myPet();
			if (type == null || type.equals("none")) continue;
			if (p != mc.player && (!Modules.on("others_cosmetics") || KyotoConfig.get().hiddenCosmeticsPlayers.contains(p.getUUID().toString()))) continue;
			if (p.isInvisible() || p.isSpectator() || p.distanceTo(mc.player) > 48) continue;
			Mesh3D.Model m = Mesh3D.get(type);
			if (m == null) continue;
			State s = STATES.computeIfAbsent(name, k -> new State());
			step(s, p, type, partial, dt, now);
			Vec3 at = new Vec3(s.x, s.y, s.z);
			WHERE.put(name, at.add(0, flyer(type) ? 0.45 : 0.62, 0));
			float[][] q = m.pose();
			animate(type, m, q, s, now, p);
			Matrix4f root = new Matrix4f().translate((float) (s.x - cam.x), (float) (s.y - cam.y + s.hop), (float) (s.z - cam.z))
					.rotateY((float) -Math.toRadians(s.yaw));
			// squash + stretch on the whole pet for hoppers
			if (hopper(type)) {
				float st = 1f + s.hop * 0.7f - s.land * 0.22f;
				root.scale(1f / (float) Math.sqrt(Math.max(0.6f, st)), st, 1f / (float) Math.sqrt(Math.max(0.6f, st)));
			}
			Mesh3D.draw(camM, m, root, q, Mesh3D.lightAt(at.add(0, 0.3, 0)), 1f);
			drawn++;
		}
		STATES.values().removeIf(st -> now - st.seen > 5000);
		lastDraw = now;
	}

	/** Follow the owner: a spring towards a spot beside them. */
	private static void step(State s, Player owner, String type, float partial, float dt, long now) {
		Vec3 o = owner.getPosition(partial);
		float by = Mth.rotLerp(partial, owner.yBodyRotO, owner.yBodyRot);
		double th = Math.toRadians(by);
		double fx = -Math.sin(th), fz = Math.cos(th), rx = Math.cos(th), rz = Math.sin(th);
		boolean fly = flyer(type);
		double side = 0.85, back = fly ? 0.15 : 0.45;
		double tx = o.x - rx * side - fx * back, tz = o.z - rz * side - fz * back;
		double ty = o.y + (fly ? 1.05 + Math.sin(now / 700.0) * 0.08 : 0);
		if (!s.placed || Math.abs(s.x - tx) + Math.abs(s.z - tz) + Math.abs(s.y - ty) > 14) {
			s.x = tx; s.y = ty; s.z = tz; s.vx = s.vy = s.vz = 0; s.placed = true; s.yaw = by;
		}
		s.seen = now;
		double k = fly ? 10 : 14, damp = fly ? 5.5 : 7.5;
		s.vx += ((tx - s.x) * k - s.vx * damp) * dt;
		s.vz += ((tz - s.z) * k - s.vz * damp) * dt;
		if (fly) s.vy += ((ty - s.y) * k - s.vy * damp) * dt;
		else {
			// ground pets stay at the owner's height but ease towards it
			s.vy += ((ty - s.y) * 18 - s.vy * 9) * dt;
		}
		s.x += s.vx * dt; s.y += s.vy * dt; s.z += s.vz * dt;
		float sp = (float) Math.sqrt(s.vx * s.vx + s.vz * s.vz);
		s.speed += (sp - s.speed) * Math.min(1f, dt * 8);
		// face where we're going, or look at the owner when standing
		float want;
		if (s.speed > 0.35f) want = (float) Math.toDegrees(Math.atan2(-s.vx, s.vz));
		else want = (float) Math.toDegrees(Math.atan2(-(o.x - s.x), o.z - s.z)) + (float) Math.sin(now / 2300.0) * 25;
		s.yaw += Mth.wrapDegrees(want - s.yaw) * Math.min(1f, dt * (s.speed > 0.35f ? 9 : 2.5f));
		s.walk += dt * (2.5f + s.speed * 9f);
		// hops for bunnies, frogs and slimes
		if (hopper(type)) {
			boolean moving = s.speed > 0.25f;
			float rate = type.equals("frog") ? 1.6f : type.equals("slime") ? 1.9f : 2.6f;
			if (moving || s.hop > 0.001f || (now > s.nextTrick && now - s.trickAt > 2000)) {
				float ph = (s.walk * rate / 6.2832f) % 1f;
				float h = (float) Math.sin(ph * Math.PI);
				float hh = moving ? h * (type.equals("frog") ? 0.28f : 0.2f) : 0;
				if (hh < 0.01f && s.hop > 0.02f) s.land = 1f;
				s.hop = hh;
			}
			s.land = Math.max(0, s.land - dt * 5);
		} else s.hop = 0;
		// little tricks now and then when standing still
		if (s.speed < 0.2f) s.idleT += dt;
		else s.idleT = 0;
		if (now > s.nextTrick && s.idleT > 1.5f) {
			s.trick = (int) (Math.random() * 3);
			s.trickAt = now;
			s.nextTrick = now + 5000 + (long) (Math.random() * 7000);
			if (owner == Minecraft.getInstance().player && Modules.on("pet_sounds")) dev.kyoto.ui.Sounds.pet(s.trick);
		}
	}

	private static void set(Mesh3D.Model m, float[][] q, String part, float rx, float ry, float rz) {
		int i = m.part(part);
		if (i < 0) return;
		q[i][0] += rx; q[i][1] += ry; q[i][2] += rz;
	}

	private static void scale(Mesh3D.Model m, float[][] q, String part, float sx, float sy, float sz) {
		int i = m.part(part);
		if (i < 0) return;
		q[i][3] *= sx; q[i][4] *= sy; q[i][5] *= sz;
	}

	private static void move(Mesh3D.Model m, float[][] q, String part, float x, float y, float z) {
		int i = m.part(part);
		if (i < 0) return;
		q[i][6] += x; q[i][7] += y; q[i][8] += z;
	}

	/** Seconds since a trick started, 0..1 over its length, or -1. */
	private static float trick(State s, long now, float len) {
		float t = (now - s.trickAt) / 1000f;
		return t >= 0 && t <= len ? t / len : -1;
	}

	private static void animate(String type, Mesh3D.Model m, float[][] q, State s, long now, Player owner) {
		float t = now / 1000f;
		float walkK = Mth.clamp(s.speed / 2.5f, 0, 1);
		float w = s.walk;
		float breathe = 1f + (float) Math.sin(t * 2.4f) * 0.025f;
		scale(m, q, "body", 1f, breathe, 1f);
		// head looks around when standing still
		float look = (1 - walkK) * (float) Math.sin(t * 0.7f) * 0.35f;
		set(m, q, "head", (float) Math.sin(t * 1.3f) * 0.05f, look, 0);
		switch (type) {
			case "fox", "cat", "dragon" -> {
				float sw = 0.7f * walkK;
				set(m, q, "legFL", (float) Math.sin(w) * sw, 0, 0);
				set(m, q, "legBR", (float) Math.sin(w) * sw, 0, 0);
				set(m, q, "legFR", (float) -Math.sin(w) * sw, 0, 0);
				set(m, q, "legBL", (float) -Math.sin(w) * sw, 0, 0);
				float wag = type.equals("cat") ? 0.35f : 0.5f;
				float wagSpeed = type.equals("cat") ? 1.6f : 5f + 4f * (1 - walkK);
				set(m, q, "tail", -0.15f + walkK * 0.25f, (float) Math.sin(t * wagSpeed) * wag, 0);
				move(m, q, "body", 0, (float) Math.abs(Math.sin(w)) * 0.015f * walkK, 0);
				// ear twitch
				float tw = (float) Math.max(0, Math.sin(t * 7)) * (Math.sin(t * 0.9) > 0.85 ? 0.4f : 0f);
				set(m, q, "earL", 0, 0, -tw);
				set(m, q, "earR", 0, 0, tw * 0.6f);
				float tr = trick(s, now, 1.2f);
				if (tr >= 0 && s.trick == 0) set(m, q, "head", 0, 0, (float) Math.sin(tr * Math.PI) * 0.35f); // head tilt
				if (tr >= 0 && s.trick == 1) set(m, q, "tail", 0, (float) Math.sin(tr * 30) * 0.5f, 0);    // happy wag
				if (type.equals("dragon")) {
					float flap = (float) Math.sin(t * 7f) * 0.6f + 0.15f;
					set(m, q, "wingL", 0, 0, -flap);
					set(m, q, "wingR", 0, 0, flap);
				}
			}
			case "bunny" -> {
				float hop = s.hop * 6;
				set(m, q, "legBL", -hop * 0.8f, 0, 0);
				set(m, q, "legBR", -hop * 0.8f, 0, 0);
				set(m, q, "legFL", hop * 0.5f, 0, 0);
				set(m, q, "legFR", hop * 0.5f, 0, 0);
				set(m, q, "earL", -0.1f - hop * 0.4f + (float) Math.sin(t * 1.7f) * 0.06f, 0, -0.08f);
				set(m, q, "earR", -0.1f - hop * 0.4f + (float) Math.sin(t * 1.9f + 1) * 0.06f, 0, 0.08f);
				scale(m, q, "head", 1f, 1f + (float) Math.max(0, Math.sin(t * 14)) * 0.012f, 1f); // nose wiggle
				float tr = trick(s, now, 1.4f);
				if (tr >= 0 && s.trick == 2) { set(m, q, "earL", 0, 0, (float) -Math.sin(tr * Math.PI) * 0.5f); set(m, q, "earR", 0, 0, (float) Math.sin(tr * Math.PI) * 0.5f); }
			}
			case "frog" -> {
				float hop = s.hop * 5;
				set(m, q, "legBL", -hop, 0, -hop * 0.3f);
				set(m, q, "legBR", -hop, 0, hop * 0.3f);
				float puff = 1f + (float) Math.max(0, Math.sin(t * 3.2f)) * 0.35f;
				scale(m, q, "throat", puff, puff, puff);
				float tr = trick(s, now, 0.8f);
				if (tr >= 0) set(m, q, "head", (float) -Math.sin(tr * Math.PI) * 0.25f, 0, 0);
			}
			case "slime" -> {
				set(m, q, "core", 0, t * 0.8f, 0);
				move(m, q, "core", (float) Math.sin(t * 1.3f) * 0.015f, (float) Math.sin(t * 2.1f) * 0.01f, 0);
				float jig = (float) Math.sin(t * 5) * 0.03f * (1 - walkK);
				scale(m, q, "body", 1 + jig, 1 - jig, 1 + jig);
			}
			case "axolotl" -> {
				float sw = (float) Math.sin(t * 3f + w * 0.6f);
				set(m, q, "tail", 0, sw * (0.35f + walkK * 0.3f), 0);
				set(m, q, "body", 0, -sw * 0.08f, 0);
				set(m, q, "head", 0, sw * 0.12f, 0);
				for (String l : new String[]{"legFL", "legFR", "legBL", "legBR"}) set(m, q, l, (float) Math.sin(t * 4 + l.length() + (l.endsWith("L") ? 0 : 3)) * 0.4f, 0, 0);
				set(m, q, "body", (float) Math.sin(t * 1.1f) * 0.06f, 0, 0);
			}
			case "bee" -> {
				float flap = (float) Math.sin(t * 48f) * 0.55f;
				set(m, q, "wingL", 0, 0, -flap);
				set(m, q, "wingR", 0, 0, flap);
				set(m, q, "body", walkK * 0.35f, 0, (float) Math.sin(t * 1.4f) * 0.08f);
				set(m, q, "stinger", (float) Math.sin(t * 3f) * 0.1f, 0, 0);
				move(m, q, "body", 0, (float) Math.sin(t * 2.6f) * 0.03f, 0);
			}
			case "ghost" -> {
				for (int k = 0; k < 5; k++) set(m, q, "skirt" + k, (float) Math.sin(t * 3 + k * 1.3f) * 0.25f, 0, (float) Math.cos(t * 3 + k * 1.3f) * 0.15f);
				set(m, q, "armL", 0, 0, (float) Math.sin(t * 2f) * 0.3f);
				set(m, q, "armR", 0, 0, (float) -Math.sin(t * 2f + 0.6f) * 0.3f);
				set(m, q, "body", walkK * 0.3f, 0, (float) Math.sin(t * 1.2f) * 0.1f);
				move(m, q, "body", 0, (float) Math.sin(t * 1.8f) * 0.05f, 0);
				float tr = trick(s, now, 1.0f);
				if (tr >= 0 && s.trick == 0) set(m, q, "body", 0, (float) (tr * Math.PI * 2), 0); // twirl
			}
			case "star" -> {
				set(m, q, "body", (float) Math.sin(t * 1.1f) * 0.15f, (float) Math.sin(t * 0.8f) * 0.5f, (float) Math.sin(t * 1.7f) * 0.12f);
				for (int k = 0; k < 3; k++) {
					set(m, q, "spark" + k, 0, t * 1.4f + k, 0);
					float tw = 0.6f + 0.4f * (float) Math.abs(Math.sin(t * 3 + k));
					scale(m, q, "spark" + k, tw, tw, tw);
				}
				float tr = trick(s, now, 1.0f);
				if (tr >= 0) set(m, q, "body", 0, 0, (float) (tr * Math.PI * 2));
			}
			default -> {
			}
		}
	}
}
