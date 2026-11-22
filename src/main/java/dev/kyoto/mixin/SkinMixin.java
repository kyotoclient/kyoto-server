package dev.kyoto.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dev.kyoto.cosmetic.Capes;
import dev.kyoto.cosmetic.Skins;
import dev.kyoto.util.Compat;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.PlayerSkin;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Kyoto skins + capes (yours + other Kyoto players'). The elytra uses the cape art. */
@Mixin(AbstractClientPlayer.class)
public abstract class SkinMixin {
	@ModifyReturnValue(method = "getSkin", at = @At("RETURN"), require = 0)
	private PlayerSkin kyoto$skinAndCape(PlayerSkin original) {
		if (original == null) return null;
		var uuid = ((AbstractClientPlayer) (Object) this).getUUID();
		Identifier cape = Capes.capeFor(uuid);
		Skins.Skin skin = Skins.skinFor(uuid);
		if (cape == null && skin == null) return original;
		var body = skin == null ? original.body() : Compat.capeAsset(skin.tex());
		var capeAsset = cape == null ? original.cape() : Compat.capeAsset(cape);
		var elytra = cape == null ? original.elytra() : Compat.capeAsset(cape);
		var model = skin == null ? original.model() : pick(original.model(), skin.slim() ? "SLIM" : "WIDE");
		return new PlayerSkin(body, capeAsset, elytra, model, original.secure());
	}

	private static <E extends Enum<E>> E pick(E current, String name) {
		try {
			return Enum.valueOf(current.getDeclaringClass(), name);
		} catch (Throwable t) {
			return current;
		}
	}
}
