package dev.kyoto.cosmetic;

import dev.kyoto.config.KyotoConfig;
import dev.kyoto.module.Modules;
import dev.kyoto.net.KyotoNet;
import dev.kyoto.util.Compat;
import dev.kyoto.util.Ui;
import net.minecraft.client.Minecraft;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.entity.player.Player;

import java.util.ArrayList;
import java.util.List;

/**
 * Particle accessories drawn around players: yours, plus other Kyoto players' (from your Kyoto server).
 * Yours are hidden in first person so they never block your view.
 */
public final class Accessories {
	private Accessories() {}

	public static final String[] NAMES = {"None", "Halo", "Angel Wings", "Flame Aura", "Frost Aura", "Heart Orbit", "Music Notes", "Sakura Petals", "Galaxy Ring",
			"Star Crown", "Rain Cloud", "Snow Cloud", "Ender Aura", "Soul Orbit", "Bubble Ring", "Spark Storm", "Firefly Swarm", "Rainbow Trail",
			"Shadow Aura", "Gold Sparkles", "Orbiting Moon", "Constellation", "Blossom Crown", "Dragon Wings", "Pixel Halo", "Comet Tail"};
	public static final String[] COLOURS = {"Default", "Theme", "White", "Red", "Orange", "Yellow", "Green", "Cyan", "Blue", "Purple", "Pink", "Rainbow"};
	private static final int[] COLOUR_RGB = {0, 0, 0xFFFFFF, 0xFF4D4D, 0xFF9A3C, 0xFFE45C, 0x5CFF7A, 0x5CF2FF, 0x5C8DFF, 0xB05CFF, 0xFF7AC8, 0};

	/** RGB for a colour index (Default/Theme/Rainbow have no fixed colour -> def). */
	public static int colourRgb(int idx, int def) {
		return idx > 1 && idx < COLOUR_RGB.length && COLOUR_RGB[idx] != 0 ? COLOUR_RGB[idx] : def;
	}

	/** Colour for dust-based accessories (the accessory colour setting, or its own default). */
	private static int col(int def, double t) {
		int c = KyotoConfig.get().accessoryColor;
		if (c <= 0 || c >= COLOURS.length) return def;
		if (c == 1) return Ui.accent();
		if (c == COLOURS.length - 1) return Ui.hsv((float) ((t * 60) % 360), 0.7f, 1f);
		return COLOUR_RGB[c];
	}

	/** One preview frame for the Cosmetics screen: points {x, y, colour} around a standing player (y up, blocks). */
	public static List<float[]> preview(int type, double t) {
		List<float[]> out = new ArrayList<>();
		double h = 1.8;
		switch (type) {
			case 1, 24 -> { for (int i = 0; i < 12; i++) { double a = t + i * Math.PI / 6; out.add(new float[]{(float) (Math.cos(a) * 0.32), (float) (h + 0.25 + Math.sin(a) * 0.06), col(type == 24 ? 0xFFFFFF : 0xFFE48A, t)}); } }
			case 2, 23 -> { for (int side = -1; side <= 1; side += 2) for (int i = 1; i <= 8; i++) { double s2 = i / 8.0; out.add(new float[]{(float) (side * (0.15 + s2 * 0.75)), (float) (h * 0.75 + Math.sin(s2 * Math.PI) * 0.35 - s2 * 0.2 + Math.sin(t * 1.5) * 0.08 * s2), col(type == 23 ? 0x8A3CFF : 0xFFFFFF, t)}); } }
			case 9, 22 -> { for (int i = 0; i < 10; i++) { double a = t * 0.5 + i * Math.PI / 5; out.add(new float[]{(float) (Math.cos(a) * 0.35), (float) (h + 0.2 + (i % 2) * 0.15), col(type == 22 ? 0xFFB7D5 : 0xFFE45C, t)}); } }
			case 10, 11 -> { for (int i = 0; i < 14; i++) out.add(new float[]{(float) ((i % 7) * 0.12 - 0.36), (float) (h + 0.75 + (i / 7) * 0.08), type == 10 ? 0x9AA0AA : 0xF2F6FF}); for (int i = 0; i < 4; i++) out.add(new float[]{(float) (i * 0.2 - 0.3), (float) (h + 0.6 - ((t * 2 + i * 0.3) % 1) * 0.9), type == 10 ? 0x5C9DFF : 0xFFFFFF}); }
			case 17 -> { for (int i = 0; i < 12; i++) out.add(new float[]{(float) (Math.sin(t * 2 + i) * 0.3 - i * 0.02), (float) (0.2 + i * 0.12), Ui.hsv((float) ((i * 30 + t * 60) % 360), 0.7f, 1f)}); }
			case 20 -> { double a = t; out.add(new float[]{(float) (Math.cos(a) * 0.8), (float) (h * 0.6 + Math.sin(a) * 0.2), 0xF2F2F2}); }
			case 25 -> { for (int i = 0; i < 10; i++) out.add(new float[]{(float) (0.3 + i * 0.07), (float) (h * 0.6 - i * 0.05), col(0x9AD8FF, t)}); }
			default -> {
				int n = 10;
				for (int i = 0; i < n; i++) {
					double a = t + i * Math.PI * 2 / n;
					int c = switch (type) {
						case 3 -> 0xFF8A2A; case 4 -> 0xCFEFFF; case 5 -> 0xFF4D6D; case 6 -> 0x5CFF7A; case 7 -> 0xFFB7D5;
						case 8 -> Ui.hsv((float) ((t * 40 + i * 36) % 360), 0.6f, 1f); case 12 -> 0xB05CFF; case 13 -> 0x5CE0FF;
						case 14 -> 0x9AD8FF; case 15 -> 0xE8F4FF; case 16 -> 0xE8FF7A; case 18 -> 0x2A2A30; case 19 -> 0xFFD36B;
						case 21 -> 0xFFFFFF; default -> 0xFFFFFF;
					};
					out.add(new float[]{(float) (Math.cos(a) * 0.55), (float) (0.3 + ((t * 0.4 + i / (double) n) % 1) * 1.6), col(c, t + i)});
				}
			}
		}
		return out;
	}
	private static int tick = 0;

	public static void tick(Minecraft mc) {
		if (mc.level == null || mc.player == null || Modules.vfx() == 0) return;
		tick++;
		// Accessories are drawn as smooth glowing sprites by Aura (same as the menu preview). Minecraft's own particles are never used.
		if (tick >= 0) return;
		if (tick % 2 != 0) return;
		for (Player p : mc.level.players()) {
			if (p.distanceTo(mc.player) > 32 || p.isInvisible()) continue;
			int acc;
			if (p == mc.player) {
				if (!Modules.on("accessory") || mc.options.getCameraType().isFirstPerson()) continue;
				acc = KyotoConfig.get().accessory;
			} else {
				if (!Modules.on("others_cosmetics")) continue;
				String id = p.getUUID().toString();
				if (KyotoConfig.get().hiddenCosmeticsPlayers.contains(id)) continue;
				KyotoNet.Cosmetic c = KyotoNet.COSMETICS.get(id);
				if (c == null) continue;
				acc = c.accessory();
			}
			if (acc > 0) draw(p, acc);
		}
	}

	private static void draw(Player p, int type) {
		double x = p.getX(), y = p.getY(), z = p.getZ(), h = p.getBbHeight();
		double yaw = Math.toRadians(p.yBodyRot);
		double t = tick * 0.12;
		switch (type) {
			case 1 -> { // Halo
				for (int i = 0; i < 8; i++) {
					double a = t + i * Math.PI / 4;
					Compat.particle(new DustParticleOptions(col(0xFFE48A, t), 0.6f), x + Math.cos(a) * 0.32, y + h + 0.25, z + Math.sin(a) * 0.32, 0, 0, 0);
				}
			}
			case 2 -> { // Wings (two curved outlines behind the back)
				double bx = Math.sin(yaw) * 0.25, bz = -Math.cos(yaw) * 0.25;
				double rx = Math.cos(yaw), rz = Math.sin(yaw);
				float flap = (float) Math.sin(t * 1.5) * 0.15f;
				for (int side = -1; side <= 1; side += 2) {
					for (int i = 1; i <= 7; i++) {
						double s = i / 7.0;
						double off = side * (0.15 + s * 0.75);
						double wy = y + h * 0.75 + Math.sin(s * Math.PI) * 0.35 - s * 0.2;
						double back = bx * (1 + s * (0.6 + flap)), backz = bz * (1 + s * (0.6 + flap));
						Compat.particle(new DustParticleOptions(col(0xFFFFFF, t), 0.7f), x + back + rx * off, wy, z + backz + rz * off, 0, 0, 0);
					}
				}
			}
			case 3 -> { // Flame aura
				double a = t * 2;
				Compat.particle(ParticleTypes.FLAME, x + Math.cos(a) * 0.5, y + 0.1 + (tick % 20) * 0.08, z + Math.sin(a) * 0.5, 0, 0.01, 0);
				Compat.particle(ParticleTypes.SMALL_FLAME, x + Math.cos(a + Math.PI) * 0.5, y + 0.1 + ((tick + 10) % 20) * 0.08, z + Math.sin(a + Math.PI) * 0.5, 0, 0.01, 0);
			}
			case 4 -> { // Frost aura
				double a = t * 1.5;
				Compat.particle(ParticleTypes.SNOWFLAKE, x + Math.cos(a) * 0.55, y + 0.2 + Math.sin(t) * 0.6 + 0.6, z + Math.sin(a) * 0.55, 0, -0.01, 0);
			}
			case 5 -> { // Heart orbit
				if (tick % 6 == 0) {
					double a = t;
					Compat.particle(ParticleTypes.HEART, x + Math.cos(a) * 0.6, y + h + 0.1, z + Math.sin(a) * 0.6, 0, 0, 0);
				}
			}
			case 6 -> { // Music notes
				if (tick % 8 == 0) Compat.particle(ParticleTypes.NOTE, x, y + h + 0.5, z, (tick % 24) / 24.0, 0, 0);
			}
			case 7 -> { // Sakura petals
				double a = t * 0.7;
				Compat.particle(new DustParticleOptions(col(0xFFB7D5, t), 0.8f), x + Math.cos(a) * 0.7, y + h + 0.4 - (tick % 30) * 0.06, z + Math.sin(a) * 0.7, 0, 0, 0);
			}
			case 8 -> { // Galaxy ring
				for (int i = 0; i < 6; i++) {
					double a = t + i * Math.PI / 3;
					int col = Ui.hsv((float) ((t * 40 + i * 60) % 360), 0.6f, 1f);
					Compat.particle(new DustParticleOptions(col, 0.7f), x + Math.cos(a) * 0.7, y + h * 0.55 + Math.sin(a * 2) * 0.1, z + Math.sin(a) * 0.7, 0, 0, 0);
				}
			}
			case 9, 22 -> { // star crown / blossom crown
				for (int i = 0; i < 10; i++) {
					double a = t * 0.5 + i * Math.PI / 5;
					Compat.particle(new DustParticleOptions(col(type == 22 ? 0xFFB7D5 : 0xFFE45C, t), 0.55f), x + Math.cos(a) * 0.35, y + h + 0.2 + (i % 2) * 0.15, z + Math.sin(a) * 0.35, 0, 0, 0);
				}
			}
			case 10, 11 -> { // rain / snow cloud
				for (int i = 0; i < 6; i++) Compat.particle(new DustParticleOptions(type == 10 ? 0x9AA0AA : 0xF2F6FF, 1.4f), x + (i - 2.5) * 0.14, y + h + 0.75, z + Math.sin(i) * 0.1, 0, 0, 0);
				if (tick % 4 == 0) Compat.particle(type == 10 ? ParticleTypes.FALLING_WATER : ParticleTypes.SNOWFLAKE, x + (Math.random() - 0.5) * 0.6, y + h + 0.65, z + (Math.random() - 0.5) * 0.6, 0, -0.05, 0);
			}
			case 12 -> Compat.particle(ParticleTypes.PORTAL, x + Math.cos(t * 2) * 0.6, y + 0.2 + Math.random() * h, z + Math.sin(t * 2) * 0.6, 0, 0, 0);
			case 13 -> { if (tick % 3 == 0) Compat.particle(ParticleTypes.SOUL_FIRE_FLAME, x + Math.cos(t * 1.4) * 0.6, y + h * 0.6, z + Math.sin(t * 1.4) * 0.6, 0, 0, 0); }
			case 14 -> { if (tick % 2 == 0) Compat.particle(ParticleTypes.BUBBLE_POP, x + Math.cos(t) * 0.55, y + 0.2 + (tick % 20) * 0.08, z + Math.sin(t) * 0.55, 0, 0.02, 0); }
			case 15 -> { if (tick % 2 == 0) Compat.particle(ParticleTypes.ELECTRIC_SPARK, x + (Math.random() - 0.5) * 0.8, y + Math.random() * h, z + (Math.random() - 0.5) * 0.8, 0, 0, 0); }
			case 16 -> { if (tick % 3 == 0) Compat.particle(new DustParticleOptions(col(0xE8FF7A, t), 0.5f), x + Math.sin(t * 1.7 + tick) * 0.9, y + 0.6 + Math.sin(t * 2.3) * 0.6, z + Math.cos(t * 1.3 + tick) * 0.9, 0, 0, 0); }
			case 17 -> Compat.particle(new DustParticleOptions(Ui.hsv((float) ((tick * 12) % 360), 0.7f, 1f), 0.9f), x, y + 0.15, z, 0, 0, 0);
			case 18 -> { if (tick % 2 == 0) Compat.particle(ParticleTypes.SMOKE, x + Math.cos(t * 2) * 0.5, y + 0.1 + (tick % 20) * 0.08, z + Math.sin(t * 2) * 0.5, 0, 0.01, 0); }
			case 19 -> { if (tick % 2 == 0) Compat.particle(new DustParticleOptions(col(0xFFD36B, t), 0.5f), x + (Math.random() - 0.5) * 0.9, y + Math.random() * h, z + (Math.random() - 0.5) * 0.9, 0, 0.01, 0); }
			case 20 -> Compat.particle(new DustParticleOptions(col(0xF2F2F2, t), 1.6f), x + Math.cos(t) * 0.9, y + h * 0.6 + Math.sin(t) * 0.2, z + Math.sin(t) * 0.9, 0, 0, 0);
			case 21 -> { // constellation: a few fixed stars that twinkle
				for (int i = 0; i < 5; i++) {
					double a = i * 1.256 + tick * 0.005;
					if ((tick + i * 7) % 20 < 14) Compat.particle(new DustParticleOptions(col(0xFFFFFF, t), 0.45f), x + Math.cos(a) * 0.8, y + h + 0.3 + Math.sin(i * 2.1) * 0.25, z + Math.sin(a) * 0.8, 0, 0, 0);
				}
			}
			case 23 -> { // dragon wings
				double bx = Math.sin(yaw) * 0.25, bz = -Math.cos(yaw) * 0.25, rx = Math.cos(yaw), rz = Math.sin(yaw);
				float flap = (float) Math.sin(t * 1.2) * 0.2f;
				for (int side = -1; side <= 1; side += 2)
					for (int i = 1; i <= 9; i++) {
						double s2 = i / 9.0, off = side * (0.2 + s2 * 1.0);
						Compat.particle(new DustParticleOptions(col(0x8A3CFF, t), 0.8f), x + bx * (1 + s2 * (0.8 + flap)) + rx * off, y + h * 0.8 + Math.sin(s2 * Math.PI) * 0.45 - s2 * 0.35, z + bz * (1 + s2 * (0.8 + flap)) + rz * off, 0, 0, 0);
					}
			}
			case 24 -> { // pixel halo
				for (int i = 0; i < 12; i++) {
					double a = i * Math.PI / 6;
					Compat.particle(new DustParticleOptions(col(0xFFFFFF, t), 0.45f), x + Math.cos(a) * 0.3, y + h + 0.28, z + Math.sin(a) * 0.3, 0, 0, 0);
				}
			}
			case 25 -> { // comet tail behind you
				Compat.particle(new DustParticleOptions(col(0x9AD8FF, t), 1.1f), x - Math.sin(yaw) * -0.4, y + h * 0.6, z + Math.cos(yaw) * -0.4, 0, 0, 0);
				Compat.particle(ParticleTypes.END_ROD, x - Math.sin(yaw) * -0.5, y + h * 0.6, z + Math.cos(yaw) * -0.5, 0, 0, 0);
			}
			default -> {
			}
		}
	}
}
