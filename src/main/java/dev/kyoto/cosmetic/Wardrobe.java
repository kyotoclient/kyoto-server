package dev.kyoto.cosmetic;

import com.google.gson.JsonObject;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.kyoto.config.KyotoConfig;
import dev.kyoto.module.Modules;
import dev.kyoto.net.KyotoNet;
import dev.kyoto.util.Compat;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The 3D wardrobe: one hat, one halo and one back piece (wings or a backpack) on your player.
 * Hats hide while you wear a helmet. Wings flap softly, faster when you sprint or fall; halos float and turn.
 */
public final class Wardrobe {
	private Wardrobe() {}

	public static final String[] SLOTS = {"hat", "halo", "back"};
	/** Other Kyoto players' wardrobes (name -> slot -> piece), from the server. */
	public static final Map<String, Map<String, String>> WEAR_OF = new ConcurrentHashMap<>();
	private static final float S = 0.9375f / 16f; // blocks per skin pixel
	public static volatile String error = null;

	public static Map<String, String> mine() {
		return KyotoConfig.get().wear;
	}

	public static void set(String slot, String piece) {
		Map<String, String> w = mine();
		if (piece == null || piece.isEmpty()) w.remove(slot);
		else w.put(slot, piece);
		KyotoConfig.save();
		JsonObject b = new JsonObject(), wear = new JsonObject();
		for (var e : w.entrySet()) wear.addProperty(e.getKey(), e.getValue());
		b.add("wear", wear);
		if (KyotoNet.loggedIn()) {
			Thread t = new Thread(() -> { try { KyotoNet.send("POST", "/wear", b, true); } catch (Throwable ignored) { } }, "Kyoto wardrobe");
			t.setDaemon(true);
			t.start();
		}
	}

	public static void render(PoseStack pose, Vec3 cam, float partial) throws Exception {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null || mc.player == null || !Modules.on("wardrobe")) return;
		Matrix4f camM = pose.last().pose();
		int drawn = 0;
		for (Player p : mc.level.players()) {
			if (drawn >= 16) break;
			boolean me = p == mc.player;
			if (me && mc.options.getCameraType().isFirstPerson()) continue;
			if (p.isInvisible() || p.isSpectator() || p.isSleeping() || p.distanceTo(mc.player) > 48) continue;
			if (!me && (!Modules.on("others_cosmetics") || KyotoConfig.get().hiddenCosmeticsPlayers.contains(p.getUUID().toString()))) continue;
			Map<String, String> w = me ? mine() : WEAR_OF.get(Compat.profileName(p.getGameProfile()));
			if (w == null || w.isEmpty()) continue;
			drawn++;
			for (String slot : SLOTS) {
				String id = w.get(slot);
				if (id == null) continue;
				if (slot.equals("hat") && !p.getItemBySlot(EquipmentSlot.HEAD).isEmpty()) continue;
				Mesh3D.Model m = Mesh3D.get(id);
				if (m == null) continue;
				drawOn(camM, p, m, slot, cam, partial);
			}
		}
	}

	private static void drawOn(Matrix4f camM, Player p, Mesh3D.Model m, String slot, Vec3 cam, float partial) throws Exception {
		Vec3 pos = p.getPosition(partial).subtract(cam);
		float bodyYaw = Mth.rotLerp(partial, p.yBodyRotO, p.yBodyRot);
		float headYaw = Mth.rotLerp(partial, p.yHeadRotO, p.yHeadRot);
		float pitch = (float) Math.toRadians(p.getViewXRot(partial));
		boolean crouch = p.isCrouching();
		float t = System.currentTimeMillis() / 1000f;
		Matrix4f root = new Matrix4f().translate((float) pos.x, (float) pos.y, (float) pos.z)
				.rotateY((float) -Math.toRadians(bodyYaw)).scale(S)
				.translate(0, 24 + (crouch ? -3 : 0), 0);
		float[][] q = m.pose();
		switch (slot) {
			case "hat", "halo" -> {
				root.rotateY((float) -Math.toRadians(Mth.wrapDegrees(headYaw - bodyYaw))).rotateX(pitch);
				if (slot.equals("halo")) {
					root.translate(0, (float) Math.sin(t * 2.2f) * 0.35f, 0);
					int i = m.part("halo");
					if (i >= 0) q[i][1] = id(m).equals("halo_sakura") ? t * 0.6f : (float) Math.sin(t * 0.8f) * 0.15f;
					if (i >= 0) q[i][0] = (float) Math.sin(t * 1.3f) * 0.05f;
				}
				if (id(m).equals("cat_ears") || id(m).equals("bunny_ears")) {
					float tw = (float) Math.max(0, Math.sin(t * 6)) * (Math.sin(t * 0.7) > 0.8 ? 0.35f : 0f);
					wiggle(m, q, "earL", -tw * 0.4f, 0, -tw);
					wiggle(m, q, "earR", -tw * 0.2f, 0, tw * 0.5f);
					float walk = walk(p, partial);
					wiggle(m, q, "earL", -walk * 0.25f, 0, 0);
					wiggle(m, q, "earR", -walk * 0.25f, 0, 0);
				}
			}
			case "back" -> {
				if (crouch) root.rotateX(0.5f);
				float walk = walk(p, partial);
				boolean air = !p.onGround();
				float speed = p.isSprinting() ? 1f : walk;
				float flap = (float) Math.sin(t * (air ? 7f : 1.6f + speed * 3f)) * (air ? 0.45f : 0.08f + 0.18f * speed) + (air ? 0.15f : 0f);
				wiggle(m, q, "wingL", 0, -flap * 0.9f - 0.12f, 0);
				wiggle(m, q, "wingR", 0, flap * 0.9f + 0.12f, 0);
				int b = m.part("pack");
				if (b >= 0) q[b][7] = (float) Math.abs(Math.sin(t * 9f)) * 0.35f * walk;
			}
			default -> {
			}
		}
		Mesh3D.draw(camM, m, root, q, Mesh3D.lightAt(p.getEyePosition(partial)), 1f);
	}

	private static String id(Mesh3D.Model m) {
		return m.id;
	}

	private static java.lang.reflect.Method walkSpeed;

	/** 0 standing .. 1 running (from the player's walk animation). */
	private static float walk(Player p, float partial) {
		try {
			Object wa = p.getClass().getField("walkAnimation").get(p);
			if (walkSpeed == null) walkSpeed = wa.getClass().getMethod("speed", float.class);
			return Math.min(1f, (float) walkSpeed.invoke(wa, partial));
		} catch (Throwable t) {
			return 0f;
		}
	}

	private static void wiggle(Mesh3D.Model m, float[][] q, String part, float rx, float ry, float rz) {
		int i = m.part(part);
		if (i < 0) return;
		q[i][0] += rx; q[i][1] += ry; q[i][2] += rz;
	}
}
