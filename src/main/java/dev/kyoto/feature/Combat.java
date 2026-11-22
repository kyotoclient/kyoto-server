package dev.kyoto.feature;

import dev.kyoto.module.Modules;
import dev.kyoto.util.Compat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Legit PvP info: combo counter, reach of your last hit, target name/distance, hit/kill sounds.
 * Purely informational - it never attacks, aims or swaps anything for you.
 */
public final class Combat {
	private Combat() {}

	public static int combo = 0;
	public static long lastHitAt = 0;
	public static double lastReach = 0;
	public static Entity target = null;
	public static long targetAt = 0;
	private static int lastHurt = 0;

	// vanilla-option based toggles remember the original values
	private static Boolean savedSprint, savedSneak;
	private static Double savedTilt;

	public static void onAttack(Entity e) {
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer p = mc.player;
		if (p == null) return;
		long now = System.currentTimeMillis();
		combo = (now - lastHitAt < 2500 && target == e) ? combo + 1 : 1;
		lastHitAt = now;
		target = e;
		targetAt = now;
		Vec3 eye = p.getEyePosition();
		AABB box = e.getBoundingBox();
		double cx = Math.max(box.minX, Math.min(eye.x, box.maxX));
		double cy = Math.max(box.minY, Math.min(eye.y, box.maxY));
		double cz = Math.max(box.minZ, Math.min(eye.z, box.maxZ));
		lastReach = eye.distanceTo(new Vec3(cx, cy, cz));
		if (Modules.on("hit_sound") && !ClickSounds.on()) {
			SoundEvent s = switch (Modules.opt("hitsound.type")) {
				case 1 -> SoundEvents.NOTE_BLOCK_BELL.value();
				case 2 -> SoundEvents.NOTE_BLOCK_PLING.value();
				case 3 -> SoundEvents.AMETHYST_BLOCK_CHIME;
				default -> SoundEvents.NOTE_BLOCK_HAT.value();
			};
			Compat.sound(s, 0.6f, 1.6f);
		}
		if (Modules.on("hit_sparks")) Vfx.play("hit", e.getX(), e.getY() + e.getBbHeight() * 0.6, e.getZ());
	}

	public static void tick(Minecraft mc) {
		LocalPlayer p = mc.player;
		if (p == null) return;
		// getting hit breaks your combo
		if (p.hurtTime > 0 && lastHurt == 0) combo = 0;
		lastHurt = p.hurtTime;
		if (System.currentTimeMillis() - lastHitAt > 3000) combo = 0;

		if (target instanceof LivingEntity le && le.isDeadOrDying() && System.currentTimeMillis() - targetAt < 1500) {
			if (Modules.on("kill_effect")) Vfx.play("kill", le.getX(), le.getY() + le.getBbHeight() * 0.5, le.getZ());
			dev.kyoto.cosmetic.BurstFx.kill(le.getX(), le.getY() + le.getBbHeight() * 0.5, le.getZ());
			if (Modules.on("kill_sound") && !(ClickSounds.on() && Modules.opt("clicks.kill") == 0)) Compat.sound(SoundEvents.AMETHYST_BLOCK_RESONATE, 0.6f, 1.5f);
			Singleplayer.onKill();
			dev.kyoto.hud.CombatHud.onKill();
			dev.kyoto.hud.FightFx.onKill(le);
			target = null;
		}
		if (target != null && (target.isRemoved() || System.currentTimeMillis() - targetAt > 6000)) target = null;

		var o = mc.options;
		boolean ts = Modules.on("toggle_sprint");
		if (ts && savedSprint == null) {
			savedSprint = o.toggleSprint().get();
			o.toggleSprint().set(true);
		} else if (!ts && savedSprint != null) {
			o.toggleSprint().set(savedSprint);
			savedSprint = null;
		}
		boolean tc = Modules.on("toggle_sneak");
		if (tc && savedSneak == null) {
			savedSneak = o.toggleCrouch().get();
			o.toggleCrouch().set(true);
		} else if (!tc && savedSneak != null) {
			o.toggleCrouch().set(savedSneak);
			savedSneak = null;
		}
		boolean nh = Modules.on("no_hurt_cam");
		if (nh && savedTilt == null) {
			savedTilt = o.damageTiltStrength().get();
			o.damageTiltStrength().set(0.0);
		} else if (!nh && savedTilt != null) {
			o.damageTiltStrength().set(savedTilt);
			savedTilt = null;
		}
	}
}
