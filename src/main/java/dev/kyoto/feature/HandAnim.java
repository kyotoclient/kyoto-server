package dev.kyoto.feature;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.kyoto.module.Modules;
import net.minecraft.client.Minecraft;

/** The Animations page. Only changes how your hand looks on your screen; nobody else sees it and nothing about how you play changes. */
public final class HandAnim {
	private HandAnim() {}

	private static final float[] SIZE = {0.6f, 0.7f, 0.85f, 1f, 1.15f, 1.3f};
	private static final float[] OFF = {-0.15f, -0.1f, -0.05f, 0f, 0.05f, 0.1f, 0.15f};
	private static final float[] TILT = {-20f, -10f, 0f, 10f, 20f};
	private static float still = 0;

	public static void apply(PoseStack pose, boolean main) {
		float side = main ? 1 : -1;
		if (Modules.on("hand_anim")) {
			float x = OFF[clamp(Modules.opt("hand.x"), OFF.length)], y = OFF[clamp(Modules.opt("hand.y"), OFF.length)], z = OFF[clamp(Modules.opt("hand.z"), OFF.length)];
			pose.translate(x * side, y, z);
			float tilt = TILT[clamp(Modules.opt("hand.tilt"), TILT.length)];
			if (tilt != 0) pose.mulPose(new org.joml.Matrix4f().rotationZ((float) Math.toRadians(tilt * side)));
			float s = SIZE[clamp(Modules.opt("hand.size"), SIZE.length)];
			if (s != 1f) pose.scale(s, s, s);
		}
		if (Modules.on("idle_sway")) {
			var p = Minecraft.getInstance().player;
			if (p == null) return;
			boolean moving = p.getDeltaMovement().horizontalDistanceSqr() > 0.0004;
			still += ((moving ? 0f : 1f) - still) * 0.04f;
			float amt = new float[]{0.006f, 0.012f, 0.022f}[clamp(Modules.opt("sway.amount"), 3)] * still;
			double t = System.currentTimeMillis() / 1000.0;
			pose.translate(Math.sin(t * 1.1) * amt * side, Math.sin(t * 2.2) * amt * 0.6, 0);
			pose.mulPose(new org.joml.Matrix4f().rotationZ((float) Math.toRadians(Math.sin(t * 0.9) * amt * 60 * side)));
		}
	}

	/** Eases the slot-switch dip so items glide instead of popping. */
	public static float equip(float v) {
		if (!Modules.on("smooth_equip")) return v;
		float k = Math.max(0, Math.min(1, v));
		return k * k * (3 - 2 * k);
	}

	public static int swing(Object who, int original) {
		if (!Modules.on("swing_speed") || who != Minecraft.getInstance().player) return original;
		float mul = new float[]{1.8f, 1.35f, 1f, 0.8f, 0.6f}[clamp(Modules.opt("swing.speed"), 5)];
		return Math.max(2, Math.round(original * mul));
	}

	/** Writes the real name of Minecraft's hand renderer to the log once, in case it moved again. */
	public static void findRenderer() {
		try {
			var src = net.minecraft.client.Minecraft.class.getProtectionDomain().getCodeSource().getLocation();
			java.nio.file.Path p = java.nio.file.Path.of(src.toURI());
			if (!java.nio.file.Files.isRegularFile(p)) return;
			try (var z = new java.util.zip.ZipFile(p.toFile())) {
				z.stream().filter(e -> e.getName().contains("InHand") && e.getName().endsWith(".class") && !e.getName().contains("$"))
						.forEach(e -> dev.kyoto.KyotoClient.LOG.info("[Kyoto] hand renderer class: {}", e.getName()));
			}
		} catch (Throwable t) {
			dev.kyoto.KyotoClient.LOG.info("[Kyoto] couldn't look for the hand renderer: {}", t.toString());
		}
	}

	private static int clamp(int i, int n) {
		return Math.max(0, Math.min(n - 1, i));
	}
}
