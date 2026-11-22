package dev.kyoto.feature;

import dev.kyoto.module.Modules;
import dev.kyoto.util.Compat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Sky + ambient effects, all client-side particles:
 *  - shooting stars streaking across the night sky
 *  - aurora ribbons (or a pink/blue "Dreamy" aurora)
 *  - fireflies drifting around you at night
 *  - sprint trail, landing impact ring, kill effect
 */
public final class SkyFx {
	private SkyFx() {}

	private static final RandomSource RAND = RandomSource.create();
	private static int tick = 0;

	private static final class Star {
		Vec3 pos;
		final Vec3 vel;
		int life;

		Star(Vec3 pos, Vec3 vel, int life) {
			this.pos = pos;
			this.vel = vel;
			this.life = life;
		}
	}

	private static final List<Star> STARS = new ArrayList<>();

	// landing + kill tracking
	private static boolean wasOnGround = true;
	private static double airStartY = 0;
	private static int lastTargetId = -1;
	private static long lastTargetTime = 0;
	private static Entity lastTarget = null;


	public static void tick(Minecraft mc) {
		LocalPlayer p = mc.player;
		if (p == null || mc.level == null) return;
		tick++;
		int vfx = Modules.vfx();
		if (vfx == 0) {
			STARS.clear();
			return;
		}
		boolean night = Compat.isNight();
		boolean overworld = Compat.dimensionId().equals("minecraft:overworld");

		if (TimeChanger.active()) night = TimeChanger.night(); // your own time of day decides day/night skies too
		if (Modules.on("sky_fx") && overworld) {
			if (night) {
				int n = Modules.opt("sky.night");
				switch (n) {
					case 1 -> galaxy(p, vfx);
					case 2 -> { if (tick % 4 == 0) aurora(p, vfx, false); }
					case 3 -> shootingStars(p, vfx);
					case 4 -> nebula(p, vfx);
					case 5 -> { if (tick % 4 == 0) aurora(p, vfx, false); shootingStars(p, vfx); }
					case 6 -> { if (tick % 4 == 0) aurora(p, vfx, true); galaxy(p, vfx); }
					case 7 -> { galaxy(p, vfx); nebula(p, vfx); shootingStars(p, vfx); }
					case 8 -> { galaxy(p, vfx); if (tick % 2 == 0) shootingStars(p, vfx); shootingStars(p, vfx); }
					default -> {
					}
				}
			} else {
				int d = Modules.opt("sky.day");
				switch (d) {
					case 1 -> rainbow(p, vfx);
					case 2 -> sakura(p, vfx);
					case 3 -> clouds(p, vfx);
					case 4 -> lanterns(p, vfx);
					case 5 -> birds(p, vfx);
					default -> {
					}
				}
			}
		}
		updateBirds();
		updateStars();

		if (Modules.on("fireflies") && night && overworld && tick % 3 == 0) fireflies(p, vfx);
		if (Modules.on("sprint_trail") && p.isSprinting() && p.onGround() && tick % 2 == 0) {
			Compat.particle(new DustParticleOptions(dev.kyoto.util.Ui.accent(), 0.7f),
					p.getX() + RAND.nextGaussian() * 0.15, p.getY() + 0.05, p.getZ() + RAND.nextGaussian() * 0.15, 0, 0.01, 0);
		}
		landing(p, vfx);
	}

	/* ---------------- custom skies (real particles far away, so hills and trees hide them like a real sky) ---------------- */

	private static double skyDist() {
		int rd = Minecraft.getInstance().options.getEffectiveRenderDistance();
		return Math.max(60, Math.min(150, rd * 16 * 0.75));
	}

	private static int amount() {
		return new int[]{1, 2, 4}[Modules.opt("sky.amount")];
	}

	/** A random direction on the sky dome above the horizon. */
	private static Vec3 skyDir(double minY) {
		while (true) {
			Vec3 v = new Vec3(RAND.nextGaussian(), Math.abs(RAND.nextGaussian()), RAND.nextGaussian()).normalize();
			if (v.y >= minY) return v;
		}
	}

	/** Milky-way band arching across the night sky. */
	private static void galaxy(LocalPlayer p, int vfx) {
		double R = skyDist();
		Vec3 u = new Vec3(1, 0, 0.3).normalize(), w = new Vec3(-0.25, 0.85, 0.55).normalize();
		int[] cols = {0xFFFFFF, 0xDDE6FF, 0xC9B8FF, 0xFFC9E8, 0xB8E2FF};
		for (int i = 0; i < 6 * vfx * amount(); i++) {
			double a = RAND.nextDouble() * Math.PI;
			Vec3 dir = u.scale(Math.cos(a)).add(w.scale(Math.sin(a)));
			Vec3 n = u.cross(w);
			double spread = RAND.nextGaussian() * (RAND.nextInt(4) == 0 ? 0.12 : 0.05);
			dir = dir.add(n.scale(spread)).normalize();
			if (dir.y < 0.06) continue;
			Vec3 at = p.getEyePosition().add(dir.scale(R));
			float size = RAND.nextInt(6) == 0 ? 2.6f : 1.1f + RAND.nextFloat();
			Compat.farParticle(new DustParticleOptions(cols[RAND.nextInt(cols.length)], size), at.x, at.y, at.z, 0, 0, 0);
		}
		// a few twinkling stars all over
		for (int i = 0; i < 2 * vfx; i++) {
			Vec3 at = p.getEyePosition().add(skyDir(0.15).scale(R));
			Compat.farParticle(new DustParticleOptions(0xFFFFFF, 0.9f + RAND.nextFloat()), at.x, at.y, at.z, 0, 0, 0);
		}
	}

	/** Soft coloured gas clouds in three spots. */
	private static void nebula(LocalPlayer p, int vfx) {
		double R = skyDist();
		Vec3[] spots = {new Vec3(0.5, 0.55, -0.65), new Vec3(-0.7, 0.4, 0.3), new Vec3(0.2, 0.8, 0.5)};
		int[][] cols = {{0xB36BFF, 0xFF6BC8, 0x6B8BFF}, {0x6BFFE0, 0x6B8BFF, 0xB8FFF4}, {0xFF9C6B, 0xFF6B8B, 0xFFE06B}};
		for (int s = 0; s < spots.length; s++) {
			for (int i = 0; i < vfx * amount(); i++) {
				Vec3 dir = spots[s].normalize().add(RAND.nextGaussian() * 0.12, RAND.nextGaussian() * 0.07, RAND.nextGaussian() * 0.12).normalize();
				Vec3 at = p.getEyePosition().add(dir.scale(R));
				Compat.farParticle(new DustParticleOptions(cols[s][RAND.nextInt(3)], 4.0f), at.x, at.y, at.z, 0, 0, 0);
			}
		}
	}

	/** A rainbow arc across the south sky. */
	private static void rainbow(LocalPlayer p, int vfx) {
		double R = skyDist();
		int[] bands = {0xFF4D4D, 0xFF9A3C, 0xFFE45C, 0x5CFF7A, 0x5CB8FF, 0x6B5CFF, 0xB05CFF};
		Vec3 c = p.getEyePosition().add(0, -R * 0.15, R * 0.95);
		double r0 = R * 0.6;
		for (int i = 0; i < 8 * vfx * amount(); i++) {
			double th = RAND.nextDouble() * Math.PI;
			int b = RAND.nextInt(bands.length);
			double r = r0 - b * R * 0.025;
			Vec3 at = c.add(Math.cos(th) * r, Math.sin(th) * r, 0);
			if (at.y < p.getEyeY() - 4) continue;
			Compat.farParticle(new DustParticleOptions(bands[b], 3.2f), at.x, at.y, at.z, 0, 0, 0);
		}
	}

	/** Big puffy clouds drifting slowly. */
	private static void clouds(LocalPlayer p, int vfx) {
		double R = skyDist() * 0.8;
		double drift = tick * 0.004;
		for (int k = 0; k < 6; k++) {
			double ang = k * Math.PI / 3 + drift;
			Vec3 centre = p.getEyePosition().add(Math.cos(ang) * R, 35 + (k % 3) * 9, Math.sin(ang) * R);
			for (int i = 0; i < vfx * amount(); i++) {
				Vec3 at = centre.add(RAND.nextGaussian() * 9, RAND.nextGaussian() * 2.5, RAND.nextGaussian() * 5);
				Compat.farParticle(new DustParticleOptions(RAND.nextBoolean() ? 0xFFFFFF : 0xEEF2F8, 4.0f), at.x, at.y, at.z, 0.02, 0, 0);
			}
		}
	}

	/** Warm sky lanterns rising in the distance. */
	private static void lanterns(LocalPlayer p, int vfx) {
		if (RAND.nextInt(Math.max(1, 6 / amount())) != 0) return;
		double R = skyDist() * (0.4 + RAND.nextDouble() * 0.5);
		Vec3 dir = skyDir(0.0);
		Vec3 at = p.getEyePosition().add(dir.x * R, -6 + RAND.nextDouble() * 20, dir.z * R);
		Compat.farParticle(new DustParticleOptions(RAND.nextBoolean() ? 0xFFB347 : 0xFFD27A, 2.4f), at.x, at.y, at.z, 0, 0.06, 0);
		Compat.farParticle(ParticleTypes.END_ROD, at.x, at.y, at.z, 0, 0.05, 0);
	}

	/** Little flocks of birds flying over. */
	private static final class Bird {
		Vec3 pos;
		final Vec3 vel;
		int life;
		final double phase;

		Bird(Vec3 pos, Vec3 vel, int life, double phase) {
			this.pos = pos;
			this.vel = vel;
			this.life = life;
			this.phase = phase;
		}
	}

	private static final List<Bird> BIRDS = new ArrayList<>();

	private static void birds(LocalPlayer p, int vfx) {
		if (BIRDS.size() > 40 || RAND.nextInt(Math.max(1, 220 / amount())) != 0) return;
		double ang = RAND.nextDouble() * Math.PI * 2, R = skyDist() * 0.7;
		Vec3 start = p.position().add(Math.cos(ang) * R, 30 + RAND.nextDouble() * 20, Math.sin(ang) * R);
		Vec3 vel = new Vec3(-Math.cos(ang), 0, -Math.sin(ang)).add(RAND.nextGaussian() * 0.3, 0, RAND.nextGaussian() * 0.3).normalize().scale(0.45);
		int n = 3 + RAND.nextInt(5);
		for (int i = 0; i < n; i++) {
			int row = (i + 1) / 2, side = i % 2 == 0 ? 1 : -1;
			Vec3 side3 = new Vec3(-vel.z, 0, vel.x).normalize();
			Vec3 off = vel.normalize().scale(-row * 2.2).add(side3.scale(side * row * 1.8));
			BIRDS.add(new Bird(start.add(off), vel, (int) (R * 2 / 0.45), RAND.nextDouble() * 6));
		}
	}

	private static void updateBirds() {
		Iterator<Bird> it = BIRDS.iterator();
		while (it.hasNext()) {
			Bird b = it.next();
			b.pos = b.pos.add(b.vel);
			if (tick % 2 == 0) {
				// a tiny "v": body + two wing tips flapping
				double flap = Math.sin(tick * 0.5 + b.phase) * 0.5;
				Vec3 side = new Vec3(-b.vel.z, 0, b.vel.x).normalize().scale(0.7);
				int col = 0x2A2C33;
				Compat.farParticle(new DustParticleOptions(col, 1.2f), b.pos.x, b.pos.y, b.pos.z, 0, 0, 0);
				Compat.farParticle(new DustParticleOptions(col, 0.9f), b.pos.x + side.x, b.pos.y + flap, b.pos.z + side.z, 0, 0, 0);
				Compat.farParticle(new DustParticleOptions(col, 0.9f), b.pos.x - side.x, b.pos.y + flap, b.pos.z - side.z, 0, 0, 0);
			}
			if (--b.life <= 0) it.remove();
		}
	}

	/* ---------------- sakura: pink petals drifting down around you ---------------- */

	private static void sakura(LocalPlayer p, int vfx) {
		for (int i = 0; i < vfx; i++) {
			double x = p.getX() + (RAND.nextDouble() - 0.5) * 30;
			double z = p.getZ() + (RAND.nextDouble() - 0.5) * 30;
			double y = p.getY() + 6 + RAND.nextDouble() * 8;
			int col = RAND.nextBoolean() ? 0xFFB7D5 : 0xFFD6E8;
			Compat.particle(new DustParticleOptions(col, 1.1f), x, y, z, 0.02, -0.03, 0.01);
		}
	}

	/* ---------------- shooting stars ---------------- */

	private static void shootingStars(LocalPlayer p, int vfx) {
		int amount = Modules.opt("sky.amount");
		int chance = switch (amount) {
			case 0 -> 220;
			case 2 -> 18;
			default -> 70;
		};
		if (RAND.nextInt(chance) != 0) return;
		double ang = RAND.nextDouble() * Math.PI * 2;
		double dist = 70 + RAND.nextDouble() * 50;
		Vec3 start = p.position().add(Math.cos(ang) * dist, 55 + RAND.nextDouble() * 40, Math.sin(ang) * dist);
		double dir = RAND.nextDouble() * Math.PI * 2;
		double speed = 2.4 + RAND.nextDouble() * 1.6;
		Vec3 vel = new Vec3(Math.cos(dir) * speed, -0.35 - RAND.nextDouble() * 0.5, Math.sin(dir) * speed);
		STARS.add(new Star(start, vel, 14 + RAND.nextInt(10)));
		if (amount == 2 && RAND.nextInt(3) == 0) STARS.add(new Star(start.add(4, 3, -2), vel, 12));
	}

	private static void updateStars() {
		Iterator<Star> it = STARS.iterator();
		while (it.hasNext()) {
			Star s = it.next();
			// a few points per tick so the streak looks continuous
			for (int i = 0; i < 3; i++) {
				Vec3 at = s.pos.add(s.vel.scale(i / 3.0));
				Compat.farParticle(ParticleTypes.END_ROD, at.x, at.y, at.z, 0, 0, 0);
			}
			if (s.life % 3 == 0) Compat.farParticle(ParticleTypes.FIREWORK, s.pos.x, s.pos.y, s.pos.z, 0, -0.02, 0);
			s.pos = s.pos.add(s.vel);
			if (--s.life <= 0) it.remove();
		}
	}

	/* ---------------- aurora ---------------- */

	private static void aurora(LocalPlayer p, int vfx, boolean dreamy) {
		int[] cols = dreamy ? new int[]{0xFF8FD8, 0x9C8BFF, 0x7FD8FF} : new int[]{0x4DFFB0, 0x5CC8FF, 0xA06BFF};
		double t = tick * 0.02;
		int points = 10 * vfx;
		// a ribbon arcing across the north sky, slowly waving
		for (int i = 0; i < points; i++) {
			double u = i / (double) points;
			double x = (u - 0.5) * 220;
			double z = -90 + Math.sin(u * 6 + t) * 18;
			double y = 72 + Math.sin(u * 9 + t * 1.7) * 6;
			int col = cols[(int) (u * cols.length) % cols.length];
			for (int k = 0; k < 2; k++) {
				Compat.farParticle(new DustParticleOptions(col, 4.0f),
						p.getX() + x + RAND.nextGaussian(), p.getY() + y + k * 5 + RAND.nextGaussian(), p.getZ() + z, 0, 0.02, 0);
			}
		}
	}

	/* ---------------- fireflies ---------------- */

	private static void fireflies(LocalPlayer p, int vfx) {
		for (int i = 0; i < vfx; i++) {
			double x = p.getX() + (RAND.nextDouble() - 0.5) * 24;
			double z = p.getZ() + (RAND.nextDouble() - 0.5) * 24;
			double y = p.getY() + RAND.nextDouble() * 4;
			int col = RAND.nextBoolean() ? 0xE8FF7A : 0xB6FF6B;
			Compat.particle(new DustParticleOptions(col, 0.6f), x, y, z, 0, 0.01, 0);
			if (RAND.nextInt(4) == 0) Compat.particle(ParticleTypes.END_ROD, x, y, z, RAND.nextGaussian() * 0.01, 0.005, RAND.nextGaussian() * 0.01);
		}
	}

	/* ---------------- landing impact ---------------- */

	private static void landing(LocalPlayer p, int vfx) {
		boolean ground = p.onGround();
		if (!ground && wasOnGround) airStartY = p.getY();
		if (!ground) airStartY = Math.max(airStartY, p.getY());
		if (ground && !wasOnGround && Modules.on("landing_impact")) {
			double drop = airStartY - p.getY();
			if (drop > 3.5 && !p.isFallFlying()) Vfx.play("land", p.getX(), p.getY() + 0.1, p.getZ());
		}
		wasOnGround = ground;
	}

}
