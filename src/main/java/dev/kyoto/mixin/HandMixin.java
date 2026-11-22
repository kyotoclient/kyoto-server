package dev.kyoto.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.kyoto.feature.HandAnim;
import net.minecraft.world.InteractionHand;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Animations page: moves, sizes and tilts the first-person item (looks only), adds idle sway and a smoother item switch. */
// The hand renderer moved packages in 26.x, so it's named by string; whichever exists gets the hook (the others are skipped).
@Mixin(targets = {"net.minecraft.client.renderer.ItemInHandRenderer", "net.minecraft.client.renderer.item.ItemInHandRenderer",
		"net.minecraft.client.renderer.entity.ItemInHandRenderer", "net.minecraft.client.renderer.hand.ItemInHandRenderer"})
public abstract class HandMixin {
	@Inject(method = "renderArmWithItem", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/PoseStack;pushPose()V", shift = At.Shift.AFTER, ordinal = 0), require = 0)
	private void kyoto$hand(CallbackInfo ci, @Local(argsOnly = true) PoseStack pose, @Local(argsOnly = true) InteractionHand hand) {
		HandAnim.apply(pose, hand == InteractionHand.MAIN_HAND);
	}

	/** A 3D knife replaces the held sword: hide Minecraft's own one. */
	@Inject(method = "renderArmWithItem", at = @At("HEAD"), cancellable = true, require = 0)
	private void kyoto$knife(CallbackInfo ci, @Local(argsOnly = true) InteractionHand hand) {
		if (hand == InteractionHand.MAIN_HAND && dev.kyoto.knife.Viewmodel.active) ci.cancel();
	}

	@ModifyVariable(method = "renderArmWithItem", at = @At("HEAD"), argsOnly = true, ordinal = 3, require = 0)
	private float kyoto$equip(float equip) {
		return HandAnim.equip(equip);
	}
}
