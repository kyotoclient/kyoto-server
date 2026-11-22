package dev.kyoto.cosmetic;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.kyoto.module.Modules;
import dev.kyoto.screens.WorldScreens;
import dev.kyoto.util.Ui;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

/** Sword Trails: a glowing ribbon follows your blade through each swing (yours and other Kyoto players'). */
public final class SwordTrails {
	private SwordTrails() {}

	private static final Identifier TEX = Identifier.fromNamespaceAndPath("kyoto", "textures/misc/white.png");

	public static void render(PoseStack pose, Vec3 cam, float partial) throws Exception {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null || mc.player == null || !Modules.on("sword_trails")) return;
		Object type = WorldScreens.renderType(TEX);
		if (type == null) return;
		VertexConsumer vc = WorldScreens.vertexBuffer(type);
		Matrix4f m = pose.last().pose();
		int style = Modules.opt("trails.color");
		boolean any = false;
		for (Player p : mc.level.players()) {
			if (p.distanceTo(mc.player) > 32 || p.isInvisible()) continue;
			if (p != mc.player && !Modules.on("others_cosmetics")) continue;
			String item = p.getMainHandItem().getItem().toString();
			if (!item.contains("sword") && !item.contains("axe") && !item.contains("mace") && !item.contains("trident")) continue;
			float swing = dev.kyoto.util.Compat.swing(p, partial);
			if (swing <= 0.01f || swing >= 0.99f) continue;
			any = true;
			Vec3 at = p.getPosition(partial).subtract(cam);
			float yaw = (float) Math.toRadians(Mth.rotLerp(partial, p.yBodyRotO, p.yBodyRot));
			float pitch = (float) Math.toRadians(p.getViewXRot(partial));
			// the arc: from up-right to down-left in front of the player, drawn up to how far the swing has got
			int N = 18;
			float head = Math.min(1f, swing * 1.4f), tail = Math.max(0f, swing * 1.4f - 0.55f);
			float sx = 0, sy = 1.35f, sz = 0;
			float[] ix = new float[N + 1], iy = new float[N + 1], iz = new float[N + 1], ox = new float[N + 1], oy = new float[N + 1], oz = new float[N + 1];
			for (int i = 0; i <= N; i++) {
				float k = tail + (head - tail) * i / N;
				float a = (float) Math.toRadians(70 - k * 150);
				float r1 = 0.55f, r2 = 1.45f;
				// local: x = player's left, y up, z forward
				float lx1 = (float) Math.cos(a) * -r1 * 0.9f, ly1 = (float) Math.sin(a) * r1 * 0.6f, lz1 = 0.7f + (float) Math.cos(a) * 0.15f;
				float lx2 = (float) Math.cos(a) * -r2 * 0.9f, ly2 = (float) Math.sin(a) * r2 * 0.6f, lz2 = 0.9f + (float) Math.cos(a) * 0.4f;
				float[] w1 = local(lx1, ly1 + sy, lz1, yaw, pitch), w2 = local(lx2, ly2 + sy, lz2, yaw, pitch);
				ix[i] = (float) at.x + w1[0]; iy[i] = (float) at.y + w1[1]; iz[i] = (float) at.z + w1[2];
				ox[i] = (float) at.x + w2[0]; oy[i] = (float) at.y + w2[1]; oz[i] = (float) at.z + w2[2];
			}
			float fade = (float) Math.sin(swing * Math.PI);
			for (int i = 0; i < N; i++) {
				float k1 = i / (float) N, k2 = (i + 1) / (float) N;
				int c1 = color(style, k1, p), c2 = color(style, k2, p);
				int a1 = Math.round(220 * k1 * fade), a2 = Math.round(220 * k2 * fade);
				// outer glowing edge brighter, inner edge fades out
				v(vc, m, ix[i], iy[i], iz[i], c1, a1 / 6);
				v(vc, m, ox[i], oy[i], oz[i], c1, a1);
				v(vc, m, ox[i + 1], oy[i + 1], oz[i + 1], c2, a2);
				v(vc, m, ix[i + 1], iy[i + 1], iz[i + 1], c2, a2 / 6);
				// back side too, so it shows from every angle
				v(vc, m, ix[i + 1], iy[i + 1], iz[i + 1], c2, a2 / 6);
				v(vc, m, ox[i + 1], oy[i + 1], oz[i + 1], c2, a2);
				v(vc, m, ox[i], oy[i], oz[i], c1, a1);
				v(vc, m, ix[i], iy[i], iz[i], c1, a1 / 6);
			}
		}
		if (any) WorldScreens.flushBuffers();
	}

	private static int color(int style, float k, Player p) {
		return switch (style) {
			case 1 -> Ui.hsv((k * 120 + System.currentTimeMillis() / 10f) % 360, 0.6f, 1f);
			case 2 -> 0xFFFFFF;
			case 3 -> Ui.mix(0x7FD6FF, 0xB59AE8, k);
			default -> Ui.mix(0xFFE4EC, Ui.accent(), k);
		};
	}

	private static float[] local(float x, float y, float z, float yaw, float pitch) {
		// pitch tilts the arc up/down with where they look (around the shoulder height)
		float py = 1.35f;
		float yy = y - py, zz = z;
		float cp = (float) Math.cos(pitch * 0.6f), sp = (float) Math.sin(pitch * 0.6f);
		float y2 = yy * cp - zz * sp, z2 = yy * sp + zz * cp;
		float c = (float) Math.cos(yaw), s = (float) Math.sin(yaw);
		// +x local = player's left, +z = forward (yaw 0 faces +z)
		return new float[]{x * c - z2 * s, y2 + py, x * s + z2 * c};
	}

	private static void v(VertexConsumer vc, Matrix4f m, float x, float y, float z, int rgb, int a) {
		vc.addVertex(m, x, y, z).setColor(rgb >> 16 & 255, rgb >> 8 & 255, rgb & 255, Math.max(0, Math.min(255, a))).setUv(0.5f, 0.5f)
				.setOverlay(WorldScreens.OVERLAY).setLight(WorldScreens.LIGHT).setNormal(0f, 1f, 0f);
	}
}
