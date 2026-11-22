package dev.kyoto.feature;

import dev.kyoto.config.KyotoConfig;
import dev.kyoto.module.Modules;
import dev.kyoto.util.Compat;
import dev.kyoto.util.Ui;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.RandomSource;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The VFX creator's engine. Every event (hit, kill, totem, landing, pearl, waypoint)
 * has an editable effect: particle, shape, amount, speed, size, color, sound.
 */
public final class Vfx {
	private Vfx() {}

	public static final String[] PARTICLES = {"Colored Dust", "End Rod", "Flame", "Soul Flame", "Electric Spark", "Crit",
			"Magic Crit", "Heart", "Totem", "Portal", "Reverse Portal", "Cloud", "Poof", "Note", "Snowflake", "Glow",
			"Firework", "Soul", "Happy Villager", "Wax"};
	public static final String[] SHAPES = {"Burst", "Ring", "Spiral", "Fountain", "Double Helix", "Shockwave", "Pillar", "Star",
			"Sphere", "Vortex", "Heart", "Crown", "Rain", "Galaxy"};
	public static final String[] COLORS = {"Theme", "Custom hue", "RGB", "White"};
	public static final String[] SOUNDS = {"None", "Amethyst", "XP Orb", "Pling", "Anvil", "Firework", "Totem", "Bell", "Teleport"};

	/** Event id -> display name. */
	public static final Map<String, String> EVENTS = new LinkedHashMap<>();

	static {
		EVENTS.put("hit", "Hit");
		EVENTS.put("kill", "Kill");
		EVENTS.put("totem", "Totem Pop");
		EVENTS.put("land", "Big Landing");
		EVENTS.put("pearl", "Pearl Landing");
		EVENTS.put("waypoint", "Waypoint Created");
		EVENTS.put("pay", "Got Paid");
	}

	private static final RandomSource RAND = RandomSource.create();

	public static KyotoConfig.Vfx defaults(String event) {
		KyotoConfig.Vfx v = new KyotoConfig.Vfx();
		switch (event) {
			case "hit" -> { v.particle = 4; v.shape = 0; v.count = 8; v.speed = 3; }
			case "kill" -> { v.particle = 3; v.shape = 2; v.count = 28; v.speed = 3; v.sound = 1; }
			case "totem" -> { v.particle = 8; v.shape = 3; v.count = 40; v.speed = 5; }
			case "land" -> { v.particle = 12; v.shape = 5; v.count = 24; v.speed = 3; }
			case "pearl" -> { v.particle = 9; v.shape = 5; v.count = 32; v.speed = 6; }
			case "waypoint" -> { v.particle = 1; v.shape = 1; v.count = 24; v.speed = 2; }
			case "pay" -> { v.particle = 18; v.shape = 3; v.count = 24; v.speed = 2; }
			default -> {
			}
		}
		return v;
	}

	public static KyotoConfig.Vfx get(String event) {
		return KyotoConfig.get().vfx.computeIfAbsent(event, Vfx::defaults);
	}

	public static void play(String event, double x, double y, double z) {
		KyotoConfig.Vfx v = get(event);
		int q = Modules.vfx();
		if (!v.enabled || q == 0) return;
		play(v, x, y, z, q);
	}

	/**
	 * Where particle i of an effect starts and how it moves: {dx, dy, dz, vx, vy, vz} relative to the effect centre.
	 * Shared by the real effect and the in-menu preview, so the preview matches what you'll see in game.
	 */
	public static double[] shapePoint(KyotoConfig.Vfx v, int i, int count, java.util.Random rnd) {
		double t = i / (double) Math.max(1, count);
		double a = t * Math.PI * 2;
		double sp = v.speed * 0.05;
		double r = 0.2 + v.size * 0.12;
		double px = 0, py = 0, pz = 0, vx = 0, vy = 0, vz = 0;
		switch (v.shape) {
			case 1 -> { // ring
				px = Math.cos(a) * r; pz = Math.sin(a) * r;
				vx = Math.cos(a) * sp * 0.3; vz = Math.sin(a) * sp * 0.3;
			}
			case 2 -> { // spiral
				double ang = a * 3;
				px = Math.cos(ang) * r * (1 - t * 0.5); pz = Math.sin(ang) * r * (1 - t * 0.5); py = t * 1.6;
				vy = sp * 0.3;
			}
			case 3 -> { // fountain
				vx = rnd.nextGaussian() * sp * 0.4; vz = rnd.nextGaussian() * sp * 0.4; vy = sp * (1.2 + rnd.nextDouble());
			}
			case 4 -> { // double helix
				double ang = a * 4 + (i % 2) * Math.PI;
				px = Math.cos(ang) * r; pz = Math.sin(ang) * r; py = t * 2;
			}
			case 5 -> { // shockwave
				vx = Math.cos(a) * sp * 2; vz = Math.sin(a) * sp * 2; vy = 0.01;
			}
			case 6 -> { // pillar
				py = t * 3; px = rnd.nextGaussian() * 0.05; pz = rnd.nextGaussian() * 0.05; vy = sp * 0.2;
			}
			case 7 -> { // star (5 points)
				double rr = (i % 2 == 0) ? r * 1.6 : r * 0.6;
				double ang = (i % 10) * Math.PI / 5;
				px = Math.cos(ang) * rr * (0.4 + t); pz = Math.sin(ang) * rr * (0.4 + t);
				vy = sp * 0.1;
			}
			case 8 -> { // sphere (golden spiral)
				double yy = 1 - 2 * t, rad = Math.sqrt(1 - yy * yy), th = i * 2.39996;
				px = Math.cos(th) * rad * r * 1.4; py = yy * r * 1.4 + 0.6; pz = Math.sin(th) * rad * r * 1.4;
				vx = px * sp * 0.4; vy = (py - 0.6) * sp * 0.4; vz = pz * sp * 0.4;
			}
			case 9 -> { // vortex (spinning inward)
				double ang = a * 2;
				double rr = r * 1.8 * (1 - t);
				px = Math.cos(ang) * rr; pz = Math.sin(ang) * rr; py = t * 1.2;
				vx = -Math.sin(ang) * sp * 0.8; vz = Math.cos(ang) * sp * 0.8; vy = sp * 0.15;
			}
			case 10 -> { // heart (standing up, facing you)
				double hx = 16 * Math.pow(Math.sin(a), 3);
				double hy = 13 * Math.cos(a) - 5 * Math.cos(2 * a) - 2 * Math.cos(3 * a) - Math.cos(4 * a);
				px = hx / 16 * r * 1.3; py = hy / 16 * r * 1.3 + 1.0;
				vy = sp * 0.05;
			}
			case 11 -> { // crown (zig-zag ring above)
				double rr = r * 1.1;
				px = Math.cos(a) * rr; pz = Math.sin(a) * rr;
				py = 1.9 + (Math.abs(((t * 10) % 2) - 1)) * 0.45;
				vy = sp * 0.05;
			}
			case 12 -> { // rain (falls from above)
				px = (rnd.nextDouble() - 0.5) * r * 3; pz = (rnd.nextDouble() - 0.5) * r * 3; py = 2.2 + rnd.nextDouble() * 0.6;
				vy = -sp * 2;
			}
			case 13 -> { // galaxy (two spiral arms)
				double arm = (i % 2) * Math.PI;
				double rr = r * 2 * t;
				double ang = arm + t * 6;
				px = Math.cos(ang) * rr; pz = Math.sin(ang) * rr; py = 0.3 + rnd.nextGaussian() * 0.03;
				vx = -Math.sin(ang) * sp * 0.4; vz = Math.cos(ang) * sp * 0.4;
			}
			default -> { // burst
				vx = rnd.nextGaussian() * sp; vy = rnd.nextGaussian() * sp + sp * 0.4; vz = rnd.nextGaussian() * sp;
			}
		}
		return new double[]{px, py, pz, vx, vy, vz};
	}

	private static final java.util.Random JR = new java.util.Random();

	public static void play(KyotoConfig.Vfx v, double x, double y, double z, int quality) {
		int count = Math.max(1, v.count * quality / 2);
		for (int i = 0; i < count; i++) {
			double[] q = shapePoint(v, i, count, JR);
			Compat.particle(particle(v, i), x + q[0], y + q[1], z + q[2], q[3], q[4], q[5]);
		}
		// polish: a soft glow layer + a bright flash in the middle
		if (v.glow && quality >= 2) {
			int extra = Math.max(3, count / 5);
			for (int i = 0; i < extra; i++) {
				double[] q = shapePoint(v, i * 5, count, JR);
				Compat.particle(ParticleTypes.END_ROD, x + q[0], y + q[1], z + q[2], q[3] * 0.5, q[4] * 0.5 + 0.01, q[5] * 0.5);
			}
		}
		SoundEvent s = sound(v.sound);
		if (s != null) Compat.sound(s, 0.6f, 1.2f + RAND.nextFloat() * 0.2f);
	}

	private static ParticleOptions particle(KyotoConfig.Vfx v, int i) {
		return switch (v.particle) {
			case 1 -> ParticleTypes.END_ROD;
			case 2 -> ParticleTypes.FLAME;
			case 3 -> ParticleTypes.SOUL_FIRE_FLAME;
			case 4 -> ParticleTypes.ELECTRIC_SPARK;
			case 5 -> ParticleTypes.CRIT;
			case 6 -> ParticleTypes.ENCHANTED_HIT;
			case 7 -> ParticleTypes.HEART;
			case 8 -> ParticleTypes.TOTEM_OF_UNDYING;
			case 9 -> ParticleTypes.PORTAL;
			case 10 -> ParticleTypes.REVERSE_PORTAL;
			case 11 -> ParticleTypes.CLOUD;
			case 12 -> ParticleTypes.POOF;
			case 13 -> ParticleTypes.NOTE;
			case 14 -> ParticleTypes.SNOWFLAKE;
			case 15 -> ParticleTypes.GLOW;
			case 16 -> ParticleTypes.FIREWORK;
			case 17 -> ParticleTypes.SOUL;
			case 18 -> ParticleTypes.HAPPY_VILLAGER;
			case 19 -> ParticleTypes.WAX_ON;
			default -> new DustParticleOptions(color(v, i), Math.max(0.3f, Math.min(4f, v.size * 0.35f)));
		};
	}

	public static int color(KyotoConfig.Vfx v, int i) {
		return switch (v.colorMode) {
			case 1 -> Ui.hsv(v.hue, 0.65f, 1f);
			case 2 -> Ui.rgbCycle(i * 12f);
			case 3 -> 0xFFFFFF;
			default -> Ui.accent();
		};
	}

	private static SoundEvent sound(int s) {
		return switch (s) {
			case 1 -> SoundEvents.AMETHYST_BLOCK_CHIME;
			case 2 -> SoundEvents.EXPERIENCE_ORB_PICKUP;
			case 3 -> SoundEvents.NOTE_BLOCK_PLING.value();
			case 4 -> SoundEvents.ANVIL_LAND;
			case 5 -> SoundEvents.FIREWORK_ROCKET_BLAST;
			case 6 -> SoundEvents.TOTEM_USE;
			case 7 -> SoundEvents.NOTE_BLOCK_BELL.value();
			case 8 -> SoundEvents.ENDERMAN_TELEPORT;
			default -> null;
		};
	}
}
