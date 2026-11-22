package dev.kyoto.feature;

import dev.kyoto.hud.Notifications;
import dev.kyoto.module.Modules;
import dev.kyoto.util.Compat;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.server.level.ParticleStatus;

/**
 * Performance options. Every one only changes normal video settings (or Kyoto's own effects),
 * and turning it off puts your previous value back.
 *
 * For a much bigger boost, also install real performance mods that support 26.3
 * (search Modrinth for: Sodium, Lithium, FerriteCore, ImmediatelyFast, EntityCulling, ModernFix).
 */
public final class Performance {
	private Performance() {}

	// values saved when a module turns on, restored when it turns off
	private static ParticleStatus savedParticles = null;
	private static Boolean savedShadows = null;
	private static CloudStatus savedClouds = null;
	private static Integer savedBlend = null;
	private static Double savedEntityDist = null;
	private static int[] savedPreset = null; // render, simulation, fps limit, blend

	private static Integer userFps = null;
	private static int drdMax = -1;
	private static int lowCount = 0, highCount = 0, tick = 0;

	private static Options o() {
		return Minecraft.getInstance().options;
	}

	public static void init() {
		Modules.get("min_particles").onToggle(on -> {
			if (on) {
				savedParticles = o().particles().get();
				o().particles().set(ParticleStatus.MINIMAL);
			} else o().particles().set(savedParticles != null ? savedParticles : ParticleStatus.ALL);
			o().save();
		});
		Modules.get("no_shadows").onToggle(on -> {
			if (on) {
				savedShadows = o().entityShadows().get();
				o().entityShadows().set(false);
			} else o().entityShadows().set(savedShadows != null ? savedShadows : true);
			o().save();
		});
		Modules.get("no_clouds").onToggle(on -> {
			if (on) {
				savedClouds = o().cloudStatus().get();
				o().cloudStatus().set(CloudStatus.OFF);
			} else o().cloudStatus().set(savedClouds != null ? savedClouds : CloudStatus.FANCY);
			o().save();
		});
		Modules.get("fast_biome").onToggle(on -> {
			if (on) {
				savedBlend = o().biomeBlendRadius().get();
				o().biomeBlendRadius().set(0);
			} else o().biomeBlendRadius().set(savedBlend != null ? savedBlend : 2);
			o().save();
		});
		Modules.get("entity_distance").onToggle(on -> {
			if (on) {
				savedEntityDist = o().entityDistanceScaling().get();
				o().entityDistanceScaling().set(0.75);
			} else o().entityDistanceScaling().set(savedEntityDist != null ? savedEntityDist : 1.0);
			o().save();
		});
		Modules.get("perf_preset").onToggle(on -> {
			if (on) {
				savedPreset = new int[]{o().renderDistance().get(), o().simulationDistance().get(), o().framerateLimit().get(), o().biomeBlendRadius().get()};
				applyPreset();
			} else if (savedPreset != null) {
				o().renderDistance().set(savedPreset[0]);
				o().simulationDistance().set(savedPreset[1]);
				o().framerateLimit().set(savedPreset[2]);
				o().biomeBlendRadius().set(savedPreset[3]);
				o().save();
				Notifications.push("Preset off", "Your old video settings are back", Notifications.INFO);
			}
		});
		Modules.setting("perf.preset").onChange(() -> {
			if (Modules.on("perf_preset")) applyPreset();
		});
		Modules.get("dynamic_rd").onToggle(on -> {
			drdMax = on ? o().renderDistance().get() : -1;
			if (!on && drdMax > 0) o().renderDistance().set(drdMax);
		});
	}

	private static void applyPreset() {
		int preset = Modules.opt("perf.preset");
		switch (preset) {
			case 0 -> { // Max FPS
				o().renderDistance().set(8);
				o().simulationDistance().set(6);
				o().framerateLimit().set(260); // 260 = unlimited
				o().biomeBlendRadius().set(0);
			}
			case 2 -> { // Quality
				o().renderDistance().set(16);
				o().simulationDistance().set(10);
				o().framerateLimit().set(260);
				o().biomeBlendRadius().set(3);
			}
			default -> { // Balanced
				o().renderDistance().set(12);
				o().simulationDistance().set(8);
				o().framerateLimit().set(260);
				o().biomeBlendRadius().set(1);
			}
		}
		o().save();
		String[] names = {"Max FPS", "Balanced", "Quality"};
		Notifications.push("Preset: " + names[preset], "Video settings applied", Notifications.GOOD, 2500, null, "⚡");
	}

	public static int currentRenderDistance() {
		return o().renderDistance().get();
	}

	public static void tick(Minecraft mc) {
		tick++;
		fpsLimiters(mc);
		if (Modules.on("dynamic_rd") && mc.player != null && tick % 20 == 0) dynamicRenderDistance(mc);
	}

	/** Background / menu FPS caps. Remembers your real FPS limit and restores it. */
	private static void fpsLimiters(Minecraft mc) {
		int cap = -1;
		if (Modules.on("bg_fps") && !mc.isWindowActive()) {
			cap = new int[]{5, 15, 30}[Modules.opt("bgfps.cap")];
		} else if (Modules.on("menu_fps") && Compat.currentScreen() != null && mc.player != null) {
			cap = new int[]{30, 60, 120}[Modules.opt("menufps.cap")];
		}
		if (cap > 0) {
			if (userFps == null) userFps = o().framerateLimit().get();
			if (o().framerateLimit().get() != Math.min(cap, userFps)) o().framerateLimit().set(Math.min(cap, userFps));
		} else if (userFps != null) {
			o().framerateLimit().set(userFps);
			userFps = null;
		}
	}

	private static void dynamicRenderDistance(Minecraft mc) {
		if (drdMax < 0) drdMax = o().renderDistance().get();
		int target = new int[]{60, 90, 120, 144, 240}[Modules.opt("drd.target")];
		int fps = mc.getFps();
		int rd = o().renderDistance().get();
		if (fps < target - 5) {
			highCount = 0;
			if (++lowCount >= 3 && rd > 4) {
				o().renderDistance().set(rd - 1);
				lowCount = 0;
			}
		} else if (fps > target + 30) {
			lowCount = 0;
			if (++highCount >= 6 && rd < drdMax) {
				o().renderDistance().set(rd + 1);
				highCount = 0;
			}
		} else {
			lowCount = 0;
			highCount = 0;
		}
	}

	/** Put the user's real FPS limit back (called on quit). */
	public static void restore() {
		if (userFps != null) {
			o().framerateLimit().set(userFps);
			userFps = null;
		}
		if (drdMax > 0) o().renderDistance().set(drdMax);
	}
}
