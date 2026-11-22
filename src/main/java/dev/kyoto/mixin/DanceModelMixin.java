package dev.kyoto.mixin;

import dev.kyoto.feature.Dances;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Dances: after Minecraft poses a humanoid model, a dancing player's arms, legs and head follow the dance instead. */
@Pseudo
@Mixin(targets = {"net.minecraft.client.model.HumanoidModel", "net.minecraft.client.model.player.PlayerModel", "net.minecraft.client.model.PlayerModel"})
public abstract class DanceModelMixin {
	@Inject(method = "setupAnim", at = @At("TAIL"), require = 0)
	private void kyoto$dance(@Coerce Object state, CallbackInfo ci) {
		if (!Dances.STATE_OWNER.isEmpty()) Dances.pose(this, state);
	}
}
