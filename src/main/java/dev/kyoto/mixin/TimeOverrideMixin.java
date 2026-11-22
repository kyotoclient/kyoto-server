package dev.kyoto.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dev.kyoto.feature.TimeChanger;
import net.minecraft.client.multiplayer.ClientLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Visual time changer: when your own time is on, your game reads a different time of day for drawing
 * (sky, sun, moon, stars, light). Only the client's world is touched - the singleplayer server and every
 * real server keep the real time, so spawning, beds and farms are unaffected.
 * Method names cover 26.x ("world clocks") and the older names.
 */
@Pseudo
@Mixin(targets = {"net.minecraft.world.level.Level", "net.minecraft.client.multiplayer.ClientLevel"})
public abstract class TimeOverrideMixin {
	@ModifyReturnValue(method = {"getDayTime", "getOverworldClockTime"}, at = @At("RETURN"), require = 0)
	private long kyoto$visualTime(long original) {
		if ((Object) this instanceof ClientLevel && TimeChanger.active()) return TimeChanger.visualTime(original);
		return original;
	}
}
