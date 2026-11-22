package dev.kyoto.feature;

import dev.kyoto.module.Modules;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;

/**
 * Fullbright via a client-only, invisible Night Vision effect (no icon, no particles).
 * Only your game knows about it. Turning the module off removes only OUR effect,
 * never a real Night Vision potion you drank.
 */
public final class Fullbright {
	private Fullbright() {}

	private static boolean applied = false;

	public static boolean isOurs(MobEffectInstance e) {
		return applied && e.getEffect().equals(MobEffects.NIGHT_VISION) && e.isInfiniteDuration() && !e.isVisible() && !e.showIcon();
	}

	public static void tick(Minecraft mc) {
		LocalPlayer p = mc.player;
		if (p == null) {
			applied = false;
			return;
		}
		boolean want = Modules.on("fullbright");
		MobEffectInstance cur = p.getEffect(MobEffects.NIGHT_VISION);
		if (want) {
			if (cur == null) {
				p.addEffect(new MobEffectInstance(MobEffects.NIGHT_VISION, MobEffectInstance.INFINITE_DURATION, 0, false, false, false));
				applied = true;
			}
		} else if (applied) {
			if (cur != null && cur.isInfiniteDuration() && !cur.isVisible() && !cur.showIcon()) {
				p.removeEffect(MobEffects.NIGHT_VISION);
			}
			applied = false;
		}
	}
}
