package dev.kyoto.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Watches entity events so Kyoto can tell you when someone pops a totem (event 35). Read-only. */
@Mixin(ClientPacketListener.class)
public abstract class EntityEventMixin {
	@Inject(method = "handleEntityEvent", at = @At("TAIL"), require = 0)
	private void kyoto$entityEvent(ClientboundEntityEventPacket packet, CallbackInfo ci) {
		try {
			Minecraft mc = Minecraft.getInstance();
			if (mc.level == null) return;
			int id = packet.getEventId();
			if (id == 29 || id == 30 || id == 35) dev.kyoto.extra.CombatSounds.onEvent(packet.getEntity(mc.level), id);
			if (id != 35) return;
			dev.kyoto.hud.CombatHud.onTotem(packet.getEntity(mc.level));
		} catch (Throwable ignored) {
		}
	}
}
