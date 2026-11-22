package dev.kyoto.extra;

import dev.kyoto.hud.Notifications;
import dev.kyoto.module.Modules;
import dev.kyoto.util.Compat;
import net.minecraft.client.Minecraft;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

/** Extra sounds for shield blocks, shield breaks and totem pops (yours and other people's). */
public final class CombatSounds {
	private CombatSounds() {}

	public static final String[] SOUNDS = {"Off", "Pling", "Bell", "Anvil", "XP", "Bass", "Chime"};

	private static SoundEvent sound(int i) {
		return switch (i) {
			case 1 -> SoundEvents.NOTE_BLOCK_PLING.value();
			case 2 -> SoundEvents.NOTE_BLOCK_BELL.value();
			case 3 -> SoundEvents.ANVIL_LAND;
			case 4 -> SoundEvents.EXPERIENCE_ORB_PICKUP;
			case 5 -> SoundEvents.NOTE_BLOCK_BASS.value();
			case 6 -> SoundEvents.NOTE_BLOCK_CHIME.value();
			default -> null;
		};
	}

	/** Entity event from the server: 29 = shield blocked a hit, 30 = shield got broken (axe), 35 = totem popped. */
	public static void onEvent(Entity e, int id) {
		if (!Modules.on("pop_sounds") || e == null) return;
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || e.distanceTo(mc.player) > 32) return;
		boolean me = e == mc.player;
		switch (id) {
			case 29 -> play(Modules.opt("popsound.shield"), me ? 1.4f : 1.1f, 0.5f);
			case 30 -> {
				play(Modules.opt("popsound.break"), 0.7f, 0.8f);
				if (me) Notifications.push("Shield broken!", "Wait a moment before blocking", Notifications.WARN, 2500, null, "shield");
			}
			case 35 -> {
				if (e instanceof Player) play(Modules.opt("popsound.totem"), me ? 0.8f : 1.2f, 0.8f);
			}
			default -> {
			}
		}
	}

	private static void play(int i, float pitch, float vol) {
		SoundEvent s = sound(i);
		if (s != null) Compat.sound(s, vol, pitch);
	}
}
