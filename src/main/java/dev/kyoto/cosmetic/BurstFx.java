package dev.kyoto.cosmetic;

import dev.kyoto.module.Modules;
import dev.kyoto.util.Ui;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Kyoto's own kill and explosion effects, drawn with Kyoto sprites (not Minecraft particles):
 *  - Kill: Sakura burst, Starfall, Heart pop, Soul rise or Shockwave where the mob/player died.
 *  - Explosions: when an end crystal or TNT near you blows up, a bright flash, a ring that rolls out,
 *    sparks and a soft cloud. Purely visual.
 */
public final class BurstFx {
	private BurstFx() {}

	/** x y z vx vy vz born life size rgb tex drag gravity spin */
	private static final List<double[]> P = new ArrayList<>();
	private static final Random R = new Random();
	private static final Map<Integer, double[]> WATCH = new HashMap<>(); // entity id -> last pos + kind
	public static final String[] KILL_STYLES = {"Sakura burst", "Starfall", "Heart pop", "Soul rise", "Shockwave"};

	private static void add(double x, double y, double z, double vx, double vy, double vz, double life, double size, int rgb, byte tex, double drag, double grav) {
		if (P.size() > 1500) return;
		P.add(new double[]{x, y, z, vx, vy, vz, System.currentTimeMillis(), life * 1000, size, rgb, tex, drag, grav, R.nextDouble() * 6.28});
	}

	private static double rs() {
		return R.nextDouble() * 2 - 1;
	}

	private static int amount(int n) {
		int q = Modules.vfx();
		return q <= 0 ? 0 : Math.max(4, n * q / 2);
	}

	/** Called when something you hit dies. */
	public static void kill(double x, double y, double z) {
		if (!Modules.on("kill_fx")) return;
		kill(Modules.opt("killfx.style"), x, y, z, Ui.accent());
	}

	public static void kill(int style, double x, double y, double z, int acc) {
		int n = amount(40);
		add(x, y, z, 0, 0, 0, 0.35, 2.4, 0xFFFFFF, Aura.GLOW, 1, 0); // flash
		switch (style) {
			case 1 -> { // starfall: stars shoot up then rain down
				for (int i = 0; i < n; i++) add(x, y, z, rs() * 0.12, 0.25 + R.nextDouble() * 0.2, rs() * 0.12, 1.6, 0.22 + R.nextDouble() * 0.15, i % 3 == 0 ? 0xFFE9A8 : 0xFFFFFF, Aura.STAR, 0.97, 0.018);
				for (int i = 0; i < n / 2; i++) add(x, y, z, rs() * 0.1, R.nextDouble() * 0.2, rs() * 0.1, 0.8, 0.1, 0xFFF4C0, Aura.SPARK, 0.93, 0.01);
			}
			case 2 -> { // heart pop
				for (int i = 0; i < n; i++) {
					double a = i / (double) n * Math.PI * 2;
					double hx = 16 * Math.pow(Math.sin(a), 3) / 16, hy = (13 * Math.cos(a) - 5 * Math.cos(2 * a) - 2 * Math.cos(3 * a) - Math.cos(4 * a)) / 16;
					add(x, y + 0.3, z, hx * 0.09, hy * 0.09, 0, 1.2, 0.24, i % 2 == 0 ? 0xFF6FA0 : 0xFFB3CB, Aura.HEART, 0.92, -0.002);
				}
			}
			case 3 -> { // soul rise: wisps drift up and fade
				for (int i = 0; i < n; i++) add(x + rs() * 0.4, y - 0.4, z + rs() * 0.4, rs() * 0.01, 0.03 + R.nextDouble() * 0.04, rs() * 0.01, 2.2, 0.35, i % 2 == 0 ? 0x9FE8FF : 0xD8F6FF, Aura.GLOW, 0.99, -0.001);
				for (int i = 0; i < n / 3; i++) add(x, y, z, rs() * 0.05, 0.06, rs() * 0.05, 1.8, 0.14, 0xFFFFFF, Aura.SPARK, 0.98, 0);
			}
			case 4 -> { // shockwave ring along the ground + sparks
				ring(x, y - 0.6, z, acc, n);
				for (int i = 0; i < n / 2; i++) add(x, y, z, rs() * 0.25, R.nextDouble() * 0.25, rs() * 0.25, 0.7, 0.12, 0xFFFFFF, Aura.SPARK, 0.88, 0.01);
			}
			default -> { // sakura burst
				for (int i = 0; i < n; i++) add(x, y, z, rs() * 0.22, rs() * 0.18 + 0.08, rs() * 0.22, 2.0, 0.2 + R.nextDouble() * 0.12, i % 3 == 0 ? 0xFFFFFF : i % 2 == 0 ? acc : 0xFFC2D6, Aura.PETAL, 0.9, 0.004);
				for (int i = 0; i < n / 3; i++) add(x, y, z, rs() * 0.3, rs() * 0.3, rs() * 0.3, 0.5, 0.1, 0xFFFFFF, Aura.SPARK, 0.86, 0);
			}
		}
	}

	private static void ring(double x, double y, double z, int rgb, int n) {
		int k = Math.max(16, n);
		for (int i = 0; i < k; i++) {
			double a = i / (double) k * Math.PI * 2;
			add(x, y, z, Math.cos(a) * 0.3, 0, Math.sin(a) * 0.3, 0.6, 0.35, rgb, Aura.GLOW, 0.9, 0);
		}
	}

	/** A crystal or TNT went off. */
	public static void explosion(double x, double y, double z, boolean crystal) {
		if (!Modules.on("explosion_fx")) return;
		int n = amount(60);
		int core = crystal ? 0xF2A6FF : 0xFFB066, hot = crystal ? 0xFFFFFF : 0xFFF0B0;
		add(x, y, z, 0, 0, 0, 0.3, 6, hot, Aura.GLOW, 1, 0);
		add(x, y, z, 0, 0, 0, 0.6, 4, core, Aura.GLOW, 1, 0);
		ring(x, y - 0.4, z, core, n);
		for (int i = 0; i < n; i++) {
			double sp = 0.3 + R.nextDouble() * 0.4;
			add(x, y, z, rs() * sp, rs() * sp * 0.7 + 0.1, rs() * sp, 0.6 + R.nextDouble() * 0.5, 0.12, i % 2 == 0 ? hot : core, Aura.SPARK, 0.9, 0.02);
		}
		if (Modules.opt("explosion.smoke") == 0)
			for (int i = 0; i < n / 3; i++) add(x + rs(), y + rs() * 0.5, z + rs(), rs() * 0.03, 0.02 + R.nextDouble() * 0.02, rs() * 0.03, 2.5, 1.1 + R.nextDouble(), crystal ? 0x6A4A80 : 0x6A6A6A, Aura.GLOW, 0.97, -0.0005);
	}

	/** Watches nearby crystals/TNT; when one vanishes it blew up (or was picked up, which isn't possible for both). */
	public static void tick(Minecraft mc) {
		if (mc.level == null || mc.player == null) {
			WATCH.clear();
			return;
		}
		if (!Modules.on("explosion_fx")) {
			WATCH.clear();
			return;
		}
		java.util.Set<Integer> seen = new java.util.HashSet<>();
		for (Entity e : mc.level.entitiesForRendering()) {
			if (e.distanceToSqr(mc.player) > 64 * 64) continue;
			String n = e.getType().toShortString();
			boolean crystal = n.equals("end_crystal");
			if (!crystal && !n.equals("tnt") && !n.equals("tnt_minecart")) continue;
			seen.add(e.getId());
			WATCH.put(e.getId(), new double[]{e.getX(), e.getY() + e.getBbHeight() / 2, e.getZ(), crystal ? 1 : 0, mc.level.getGameTime()});
		}
		Iterator<Map.Entry<Integer, double[]>> it = WATCH.entrySet().iterator();
		while (it.hasNext()) {
			var en = it.next();
			if (seen.contains(en.getKey())) continue;
			double[] w = en.getValue();
			it.remove();
			// only if it vanished while close by (not when it just went out of render range)
			if (mc.player.distanceToSqr(w[0], w[1], w[2]) < 48 * 48 && mc.level.getGameTime() - (long) w[4] < 3) explosion(w[0], w[1], w[2], w[3] == 1);
		}
	}

	/** Adds the live sprites to Aura's draw list (same format: x y z rel. camera, r g b a, size, tex). */
	static void collect(List<float[]> out, Vec3 cam) {
		if (P.isEmpty()) return;
		long now = System.currentTimeMillis();
		Iterator<double[]> it = P.iterator();
		while (it.hasNext()) {
			double[] p = it.next();
			double age = (now - p[6]) / p[7];
			if (age >= 1) {
				it.remove();
				continue;
			}
			// integrate at ~60 steps a second, frame-rate independent enough for effects
			p[3] *= p[11]; p[4] *= p[11]; p[5] *= p[11];
			p[4] -= p[12];
			p[0] += p[3] * 0.33; p[1] += p[4] * 0.33; p[2] += p[5] * 0.33;
			float a = (float) (age < 0.1 ? age / 0.1 : 1 - Math.pow((age - 0.1) / 0.9, 1.6));
			float size = (float) (p[8] * ((byte) p[10] == Aura.GLOW && p[7] < 700 ? 0.6 + age * 0.8 : 1 - age * 0.3));
			int rgb = (int) p[9];
			float rx = (float) (p[0] - cam.x), ry = (float) (p[1] - cam.y), rz = (float) (p[2] - cam.z);
			out.add(new float[]{rx, ry, rz, rgb >> 16 & 255, rgb >> 8 & 255, rgb & 255, Math.max(0, Math.min(1, a)) * 255, size, (float) p[10]});
		}
	}
}
