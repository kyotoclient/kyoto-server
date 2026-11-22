package dev.kyoto.mixin;

import dev.kyoto.feature.Dances;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Dances: remembers which player each render state belongs to (so the model hook knows who's dancing),
 * and turns the body for spins.
 */
@Pseudo
@Mixin(targets = {"net.minecraft.client.renderer.entity.LivingEntityRenderer"})
public abstract class DanceStateMixin {
	@Inject(method = "extractRenderState", at = @At("TAIL"), require = 0)
	private void kyoto$owner(@Coerce Object entity, @Coerce Object state, float partial, CallbackInfo ci) {
		if (!(entity instanceof Player p)) return;
		Dances.STATE_OWNER.put(state, (Entity) p);
		float spin = Dances.bodySpin(p);
		if (spin == 0) return;
		try {
			var f = state.getClass().getField("bodyRot");
			f.setFloat(state, f.getFloat(state) + spin);
		} catch (Throwable ignored) {
		}
	}
}
