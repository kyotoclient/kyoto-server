package dev.kyoto.feature;

import dev.kyoto.module.Modules;
import dev.kyoto.util.Compat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Click Sounds Pack: your own sounds for hits, crits and kills (only you hear them).
 * Sounds live in assets/kyoto/sounds/clicks/&lt;pack&gt;_&lt;hit|crit|kill&gt;.ogg.
 * A crit is detected the same way Minecraft decides one: falling, not on the ground/ladder/water, fully charged, not sprinting.
 */
public final class ClickSounds {
	private ClickSounds() {}

	public static final String[] PACKS = {"Kyoto", "Arcade", "Crisp", "Bubble", "Bass", "Drum", "Glass"};
	private static final Map<String, SoundEvent> EVENTS = new HashMap<>();

	private static Entity target = null;
	private static long targetAt = 0;
	private static boolean killPlayed = false;

	public static SoundEvent sound(String id) {
		return EVENTS.computeIfAbsent(id, k -> SoundEvent.createVariableRangeEvent(Identifier.fromNamespaceAndPath("kyoto", k)));
	}

	private static String pack() {
		int i = Math.max(0, Math.min(PACKS.length - 1, Modules.opt("clicks.pack")));
		return PACKS[i].toLowerCase(Locale.ROOT);
	}

	private static float volume() {
		return new float[]{0.25f, 0.5f, 0.75f, 1f}[Math.max(0, Math.min(3, Modules.opt("clicks.volume")))];
	}

	private static float pitch() {
		return Modules.opt("clicks.pitch") == 0 ? 0.94f + (float) Math.random() * 0.12f : 1f;
	}

	/** Plays one sound from the chosen pack: "hit", "crit" or "kill". */
	public static void play(String kind) {
		Compat.sound(sound("clicks." + pack() + "_" + kind), volume(), pitch());
	}

	public static boolean on() {
		return Modules.on("click_sounds");
	}

	/** Would Minecraft count this hit as a critical hit? */
	public static boolean isCrit(LocalPlayer p) {
		try {
			return p.fallDistance > 0 && !p.onGround() && !p.onClimbable() && !p.isInWater() && !p.isPassenger()
					&& !p.hasEffect(MobEffects.BLINDNESS) && !p.isSprinting() && p.getAttackStrengthScale(0.5f) > 0.9f;
		} catch (Throwable t) {
			return false;
		}
	}

	public static void onAttack(Entity e) {
		if (!on()) return;
		LocalPlayer p = Minecraft.getInstance().player;
		if (p == null) return;
		boolean crit = isCrit(p);
		if (crit && Modules.opt("clicks.crit") == 0) play("crit");
		else if (Modules.opt("clicks.hit") == 0) play("hit");
		target = e;
		targetAt = System.currentTimeMillis();
		killPlayed = false;
	}

	public static void tick(Minecraft mc) {
		if (!on() || target == null) return;
		if (!killPlayed && target instanceof LivingEntity le && le.isDeadOrDying() && System.currentTimeMillis() - targetAt < 1500) {
			if (Modules.opt("clicks.kill") == 0) play("kill");
			killPlayed = true;
			target = null;
		} else if (System.currentTimeMillis() - targetAt > 3000) target = null;
	}
}
