package dev.kyoto.mixin;

import dev.kyoto.module.Modules;
import dev.kyoto.util.Compat;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Nametag Font: re-styles the nametag text the game is about to draw. Only changes what YOU see. */
@Mixin(EntityRenderer.class)
public abstract class NametagFontMixin {
	@Inject(method = "extractRenderState", at = @At("TAIL"), require = 0)
	private void kyoto$nametagFont(Entity entity, EntityRenderState state, float partial, CallbackInfo ci) {
		try {
			if (state.nameTag == null) return;
			// Nickname Tags: names you gave your friends (only you see them)
			net.minecraft.network.chat.Component nick = dev.kyoto.hud.Nicknames.nametag(entity, state.nameTag);
			if (nick != null) state.nameTag = nick;
			if (!Modules.on("nametag_font")) return;
			String id = dev.kyoto.util.Ui.fontId(Modules.opt("nametag.font"));
			if (id == null) return;
			MutableComponent c = state.nameTag.copy();
			state.nameTag = Compat.withFont(c, Identifier.fromNamespaceAndPath("kyoto", id));
		} catch (Throwable ignored) {
		}
	}
}
