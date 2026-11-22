package dev.kyoto.feature;

import dev.kyoto.module.Modules;
import dev.kyoto.hud.Notifications;
import dev.kyoto.util.Compat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Everything drawn in the world with particles. Particles are spawned only on YOUR client,
 * so other players never see them and servers don't care.
 */
public final class WorldFx {
	private WorldFx() {}

	private static final RandomSource RAND = RandomSource.create();
	private static int tick = 0;

	/** Filled by the pearl simulation so the HUD can show "lands in 23m". */
	public static Vec3 pearlLanding = null;
	public static boolean pearlPreviewActive = false;

	private static final Map<Integer, Vec3> LIVE_PEARLS = new HashMap<>();

	private static int vfx() {
		return Modules.vfx();
	}

	public static void tick(Minecraft mc) {
		LocalPlayer p = mc.player;
		ClientLevel level = mc.level;
		if (p == null || level == null) return;
		tick++;
		if (vfx() == 0) {
			pearlLanding = null;
			pearlPreviewActive = false;
			return;
		}

		if (Modules.on("waypoint_beams") && tick % 4 == 0) waypointBeams(p);
		if (Modules.on("death_trail") && Modules.on("death_waypoint") && tick % 3 == 0) deathTrail(p);
		if (Modules.on("crop_sparkle") && tick % 5 == 0) cropSparkle(p, level);
		if (Modules.on("light_sniff") && tick % 4 == 0) lightSniff(p, level);
		if (Modules.on("pearl_trail") || Modules.on("pearl_burst")) pearlTrails(level);
		pearlTrajectory(p, level, Modules.on("pearl_trajectory"));
	}

	/* ---------------- Waypoint beams ---------------- */

	private static void waypointBeams(LocalPlayer p) {
		for (Waypoints.Waypoint w : Waypoints.inCurrentDimension()) {
			double dist = w.distanceTo(p.getX(), p.getY(), p.getZ());
			if (dist > 96 || dist < 2.5) continue;
			float scale = dist > 40 ? 2.2f : 1.3f;
			DustParticleOptions dust = new DustParticleOptions(w.color, scale);
			int height = 10 + vfx() * 3;
			for (int i = 0; i < height; i += 2) {
				Compat.farParticle(dust, w.x + 0.5, w.y + 0.2 + i, w.z + 0.5, 0, 0.02, 0);
			}
			// slow orbiting sparkle at the base
			double a = tick * 0.25;
			Compat.farParticle(ParticleTypes.END_ROD, w.x + 0.5 + Math.cos(a) * 0.7, w.y + 0.3, w.z + 0.5 + Math.sin(a) * 0.7, 0, 0.01, 0);
		}
	}

	public static void waypointCreated(Waypoints.Waypoint w) {
		Vfx.play("waypoint", w.x + 0.5, w.y + 0.5, w.z + 0.5);
	}

	/* ---------------- Death breadcrumbs ---------------- */

	private static void deathTrail(LocalPlayer p) {
		Waypoints.Waypoint death = null;
		String dim = Compat.dimensionId();
		for (Waypoints.Waypoint w : Waypoints.all()) {
			if (w.type == Waypoints.Type.DEATH && dim.equals(w.dimension)) death = w; // newest wins
		}
		if (death == null || p.isDeadOrDying()) return;

		double dist = death.distanceTo(p.getX(), p.getY(), p.getZ());
		if (dist < 3) {
			Notifications.push("Death point reached", "Grab your stuff! Trail cleared.", Notifications.GOOD);
			Compat.sound(SoundEvents.EXPERIENCE_ORB_PICKUP, 0.7f, 0.8f);
			for (int i = 0; i < 30; i++) {
				Compat.particle(ParticleTypes.TOTEM_OF_UNDYING, death.x + 0.5, death.y + 0.5, death.z + 0.5,
						RAND.nextGaussian() * 0.2, 0.3, RAND.nextGaussian() * 0.2);
			}
			Waypoints.remove(death.name);
			return;
		}
		if (dist > 1000) return;

		Vec3 from = p.position();
		Vec3 to = new Vec3(death.x + 0.5, death.y + 0.3, death.z + 0.5);
		Vec3 dir = to.subtract(from).normalize();
		double len = Math.min(dist, 28);
		DustParticleOptions red = new DustParticleOptions(0xFF3030, 1.0f);
		// dots every 2 blocks, with a "moving" offset so the trail looks like it flows
		double offset = (tick % 12) / 6.0;
		for (double d = 1.5 + offset; d < len; d += 2) {
			Vec3 pt = from.add(dir.scale(d));
			Compat.particle(red, pt.x, pt.y + 0.15, pt.z, 0, 0, 0);
		}
	}

	/* ---------------- Crop ripeness sparkle ---------------- */

	private static void cropSparkle(LocalPlayer p, ClientLevel level) {
		BlockPos base = p.blockPosition();
		int tries = 30 * vfx();
		BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
		for (int i = 0; i < tries; i++) {
			m.set(base.getX() + RAND.nextInt(21) - 10, base.getY() + RAND.nextInt(7) - 3, base.getZ() + RAND.nextInt(21) - 10);
			BlockState s = level.getBlockState(m);
			if (s.getBlock() instanceof CropBlock crop && crop.isMaxAge(s)) {
				Compat.particle(ParticleTypes.HAPPY_VILLAGER, m.getX() + 0.2 + RAND.nextDouble() * 0.6, m.getY() + 0.6,
						m.getZ() + 0.2 + RAND.nextDouble() * 0.6, 0, 0.02, 0);
			}
		}
	}

	/* ---------------- Light level sniff (crouch + hold a torch) ---------------- */

	private static boolean holdingLight(LocalPlayer p) {
		for (ItemStack s : new ItemStack[]{p.getMainHandItem(), p.getOffhandItem()}) {
			if (s.is(Items.TORCH) || s.is(Items.SOUL_TORCH) || s.is(Items.LANTERN) || s.is(Items.SOUL_LANTERN)) return true;
		}
		return false;
	}

	private static void lightSniff(LocalPlayer p, ClientLevel level) {
		if (!p.isShiftKeyDown() || !holdingLight(p)) return;
		BlockPos base = p.blockPosition();
		int r = 7 + vfx();
		int shown = 0;
		DustParticleOptions red = new DustParticleOptions(0xFF2222, 1.1f);
		BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
		for (int dx = -r; dx <= r; dx++) {
			for (int dz = -r; dz <= r; dz++) {
				for (int dy = -3; dy <= 2; dy++) {
					m.set(base.getX() + dx, base.getY() + dy, base.getZ() + dz);
					if (!isSpawnable(level, m)) continue;
					Compat.particle(red, m.getX() + 0.5, m.getY() + 0.08, m.getZ() + 0.5, 0, 0, 0);
					if (++shown > 260) return;
				}
			}
		}
	}

	/** Same basic test hostile mobs use: empty space, sturdy floor, block light 0. */
	private static boolean isSpawnable(ClientLevel level, BlockPos pos) {
		BlockState here = level.getBlockState(pos);
		if (!here.getCollisionShape(level, pos).isEmpty() || !here.getFluidState().isEmpty()) return false;
		BlockState above = level.getBlockState(pos.above());
		if (!above.getCollisionShape(level, pos.above()).isEmpty()) return false;
		BlockPos below = pos.below();
		BlockState floor = level.getBlockState(below);
		if (!floor.isFaceSturdy(level, below, Direction.UP)) return false;
		if (floor.is(net.minecraft.world.level.block.Blocks.BEDROCK) || floor.is(net.minecraft.world.level.block.Blocks.BARRIER)) return false;
		return level.getBrightness(LightLayer.BLOCK, pos) == 0;
	}

	/* ---------------- Ender pearls: trail + landing burst ---------------- */

	private static void pearlTrails(ClientLevel level) {
		Set<Integer> seen = new HashSet<>();
		for (Entity e : level.entitiesForRendering()) {
			if (!net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString().contains("ender_pearl")) continue;
			seen.add(e.getId());
			LIVE_PEARLS.put(e.getId(), e.position());
			if (Modules.on("pearl_trail")) {
				for (int i = 0; i < vfx(); i++) {
					Compat.particle(ParticleTypes.REVERSE_PORTAL, e.getX(), e.getY(), e.getZ(),
							RAND.nextGaussian() * 0.02, RAND.nextGaussian() * 0.02, RAND.nextGaussian() * 0.02);
				}
				Compat.particle(new DustParticleOptions(0xB46CFF, 0.9f), e.getX(), e.getY(), e.getZ(), 0, 0, 0);
			}
		}
		// pearls that vanished this tick = they landed -> burst
		LIVE_PEARLS.entrySet().removeIf(entry -> {
			if (seen.contains(entry.getKey())) return false;
			if (Modules.on("pearl_burst")) landingBurst(entry.getValue());
			return true;
		});
	}

	private static void landingBurst(Vec3 at) {
		Vfx.play("pearl", at.x, at.y + 0.2, at.z);
	}

	/* ---------------- Ender pearl trajectory preview ---------------- */

	private static void pearlTrajectory(LocalPlayer p, ClientLevel level, boolean enabled) {
		pearlLanding = null;
		pearlPreviewActive = false;
		if (!enabled) return;
		ItemStack held = p.getMainHandItem().is(Items.ENDER_PEARL) ? p.getMainHandItem()
				: (p.getOffhandItem().is(Items.ENDER_PEARL) ? p.getOffhandItem() : null);
		if (held == null) return;
		pearlPreviewActive = true;

		// Mirrors vanilla: speed 1.5, gravity 0.03, air drag 0.99, plus the thrower's motion.
		float yaw = p.getYRot() * Mth.DEG_TO_RAD;
		float pitch = p.getXRot() * Mth.DEG_TO_RAD;
		double vx = -Mth.sin(yaw) * Mth.cos(pitch);
		double vy = -Mth.sin(pitch);
		double vz = Mth.cos(yaw) * Mth.cos(pitch);
		Vec3 vel = new Vec3(vx, vy, vz).normalize().scale(1.5);
		Vec3 motion = p.getDeltaMovement();
		vel = vel.add(motion.x, p.onGround() ? 0 : motion.y, motion.z);

		Vec3 pos = p.getEyePosition().subtract(0, 0.1, 0);
		DustParticleOptions dot = new DustParticleOptions(0xC08CFF, 0.55f);
		boolean draw = tick % 2 == 0;

		for (int step = 0; step < 160; step++) {
			Vec3 next = pos.add(vel);
			BlockHitResult hit = level.clip(new ClipContext(pos, next, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p));
			if (hit.getType() != HitResult.Type.MISS) {
				pearlLanding = hit.getLocation();
				break;
			}
			if (draw && step > 1 && step % 2 == 0) {
				Compat.farParticle(dot, next.x, next.y, next.z, 0, 0, 0);
			}
			pos = next;
			vel = vel.scale(0.99).subtract(0, 0.03, 0);
			if (pos.y < level.getMinY() - 16) break;
		}

		if (pearlLanding != null && draw) {
			DustParticleOptions ring = new DustParticleOptions(0xFF66FF, 1.0f);
			int n = 10;
			for (int i = 0; i < n; i++) {
				double a = i * (Math.PI * 2 / n) + tick * 0.1;
				Compat.farParticle(ring, pearlLanding.x + Math.cos(a) * 0.6, pearlLanding.y + 0.05, pearlLanding.z + Math.sin(a) * 0.6, 0, 0, 0);
			}
		}
	}

	/* ---------------- Hit sparks (called when YOU attack something) ---------------- */

	public static void hitSparks(Entity target) {
		// handled by Combat + Vfx now
	}

	/** Green sparkle + emerald-ish burst when you get paid. */
	public static void moneyBurst() {
		var p = net.minecraft.client.Minecraft.getInstance().player;
		if (p != null) Vfx.play("pay", p.getX(), p.getY() + 1, p.getZ());
	}

	/** Burst when a totem pops. */
	public static void totemBurst(LocalPlayer p) {
		Vfx.play("totem", p.getX(), p.getY() + 1, p.getZ());
	}
}
