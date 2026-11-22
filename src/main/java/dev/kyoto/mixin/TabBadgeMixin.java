package dev.kyoto.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dev.kyoto.module.Modules;
import dev.kyoto.net.KyotoNet;
import dev.kyoto.util.Compat;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

/** Hold TAB: Kyoto players get the Kyoto logo in front of their name in the player list. */
@Pseudo
@Mixin(targets = {"net.minecraft.client.gui.components.PlayerTabOverlay"})
public abstract class TabBadgeMixin {
	@ModifyReturnValue(method = "getNameForDisplay", at = @At("RETURN"), require = 0)
	private Component kyoto$tabBadge(Component original, PlayerInfo info) {
		try {
			if (!Modules.on("kyoto_badges") || Modules.opt("badge.tab") != 0) return original;
			Object profile = info.getProfile();
			String id = String.valueOf(get(profile, "id", "getId"));
			String name = String.valueOf(get(profile, "name", "getName"));
			if (!KyotoNet.isKyoto(id) && !KyotoNet.isKyoto(name)) return original;
			return Component.empty()
					.append(Compat.withFont(Component.literal(KyotoNet.badgeGlyph(id, name)), Identifier.fromNamespaceAndPath("kyoto", "badge")).withStyle(s -> s.withColor(0xFFFFFF)))
					.append(Component.literal(" "))
					.append(original);
		} catch (Throwable t) {
			return original;
		}
	}

	/** GameProfile became a record in newer versions (id() / name()) - older ones had getId() / getName(). */
	private static Object get(Object o, String... names) {
		for (String n : names) {
			try {
				return o.getClass().getMethod(n).invoke(o);
			} catch (Throwable ignored) {
			}
		}
		return "";
	}
}
