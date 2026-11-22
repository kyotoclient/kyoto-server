package dev.kyoto.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dev.kyoto.feature.HandAnim;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Animations > Swing Speed: how long your own arm swing takes (just the animation). */
@Mixin(LivingEntity.class)
public abstract class SwingMixin {
	@ModifyReturnValue(method = "getCurrentSwingDuration", at = @At("RETURN"), require = 0)
	private int kyoto$swing(int original) {
		return HandAnim.swing((Object) this, original);
	}
}
