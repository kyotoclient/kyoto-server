package dev.kyoto.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dev.kyoto.feature.Zoom;
import net.minecraft.client.player.AbstractClientPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Zoom hook #4 (backup): the player's FOV modifier (the same one speed/sprinting changes). */
@Mixin(AbstractClientPlayer.class)
public abstract class ZoomPlayerMixin {
	@ModifyReturnValue(method = "getFieldOfViewModifier", at = @At("RETURN"), require = 0)
	private float kyoto$zoomPlayerFov(float original) {
		return Zoom.hook(Zoom.HOOK_PLAYER, original);
	}
}
