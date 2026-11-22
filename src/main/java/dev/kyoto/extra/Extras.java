package dev.kyoto.extra;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.kyoto.hud.Notifications;
import dev.kyoto.module.Modules;
import dev.kyoto.util.Compat;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;

/** Wires up the "phase 2" features: one place to register, tick and draw them. */
public final class Extras {
	private Extras() {}

	/** Toggled with keys (and remembered for the session). */
	public static boolean lightShown = false, chunksShown = false, slimeShown = false;

	public static void register() {
		HudBits.init();
		InvSearch.register();
		ChatTabs.register();
		// Shift + right-click a player with an empty hand: open their Kyoto profile card
		UseEntityCallback.EVENT.register((player, level, hand, entity, hit) -> {
			if (player != Minecraft.getInstance().player || hand != InteractionHand.MAIN_HAND || !(entity instanceof Player target)) return InteractionResult.PASS;
			if (!Modules.on("profile_card") || !player.isShiftKeyDown() || !player.getMainHandItem().isEmpty()) return InteractionResult.PASS;
			String name = Compat.profileName(target.getGameProfile());
			if (name == null) return InteractionResult.PASS;
			Minecraft.getInstance().execute(() -> Compat.openScreen(new dev.kyoto.screen.ProfileScreen(null, name)));
			return InteractionResult.FAIL; // don't send the click to the server
		});
	}

	public static void tick(Minecraft mc, boolean freelookDown) {
		Freelook.tick(mc, freelookDown);
		Shots.tick(mc);
		if (mc.player == null) return;
		HitColor.tick(mc);
		Afk.tick(mc);
		Nearby.tick(mc);
		Rewards.tick(mc);
		ProxVoice.tick(mc);
	}

	/** World-anchored drawing (before the HUD). */
	public static void renderWorld(GuiGraphicsExtractor g, Minecraft mc, int w, int h, float partial) {
		try {
			WorldOverlays.render(g, mc, w, h, partial);
		} catch (Throwable ignored) {
		}
		try {
			Nearby.render(g, mc, w, h, partial);
		} catch (Throwable ignored) {
		}
	}

	public static void renderHud(GuiGraphicsExtractor g, Minecraft mc, int w, int h, float a) {
		try {
			HudBits.render(g, mc, w, h, a);
		} catch (Throwable ignored) {
		}
	}

	/** Last thing every frame: the AFK screen covers everything, and the replay buffer grabs the frame. */
	public static void renderLast(GuiGraphicsExtractor g, Minecraft mc, int w, int h) {
		Afk.render(g, w, h);
		ReplayBuffer.frame(mc);
	}

	public static void toggleOverlay(String which) {
		boolean on;
		String name;
		switch (which) {
			case "light" -> { on = lightShown = !lightShown; name = "Light levels"; if (!Modules.on("light_overlay")) Modules.get("light_overlay").set(true); }
			case "chunks" -> { on = chunksShown = !chunksShown; name = "Chunk borders"; if (!Modules.on("chunk_borders")) Modules.get("chunk_borders").set(true); }
			default -> { on = slimeShown = !slimeShown; name = "Slime chunks"; if (!Modules.on("slime_chunks")) Modules.get("slime_chunks").set(true); }
		}
		String sub = which.equals("slime") && on && Seeds.seed() == null ? "Needs the seed: /kyoto seed <number>" : "";
		Notifications.push(name + (on ? " ON" : " OFF"), sub, on ? Notifications.GOOD : Notifications.INFO, 1800, null, "world");
	}

	/* ---------------- Low Fire (called from the mixin) ---------------- */

	private static PoseStack firePose = null;
	private static float fireShift = 0;

	public static PoseStack lowFireStart(PoseStack pose) {
		firePose = null;
		if (!Modules.on("low_fire") || pose == null) return pose;
		fireShift = new float[]{-0.15f, -0.3f, -0.45f}[Math.max(0, Math.min(2, Modules.opt("lowfire.amount")))];
		pose.translate(0f, fireShift, 0f);
		firePose = pose;
		return pose;
	}

	public static void lowFireEnd() {
		if (firePose != null) firePose.translate(0f, -fireShift, 0f);
		firePose = null;
	}
}
