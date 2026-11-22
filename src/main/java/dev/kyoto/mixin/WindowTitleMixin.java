package dev.kyoto.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dev.kyoto.module.Modules;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(Minecraft.class)
public abstract class WindowTitleMixin {
	@ModifyReturnValue(method = "createTitle", at = @At("RETURN"), require = 0)
	private String kyoto$title(String original) {
		return Modules.get("window_title") != null && Modules.on("window_title") ? "Kyoto Client  ·  26.3" : original;
	}
}
