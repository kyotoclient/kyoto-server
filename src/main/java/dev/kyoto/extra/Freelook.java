package dev.kyoto.extra;

import dev.kyoto.hud.Notifications;
import dev.kyoto.module.Modules;
import dev.kyoto.servers.Packs;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;

/**
 * Freelook: hold the key to look around your player without turning. You keep walking the way you were facing.
 * Turns itself off on servers that don't allow it (the server pack rules, e.g. Hypixel).
 */
public final class Freelook {
	private Freelook() {}

	public static volatile boolean active = false;
	private static float yaw, pitch;
	private static CameraType before = null;
	private static boolean toggled = false, wasDown = false;
	private static long warnedAt = 0;

	public static float yaw() { return yaw; }

	public static float pitch() { return pitch; }

	public static boolean allowed() {
		return Modules.on("freelook") && !Packs.disallowedHere("freelook");
	}

	public static void tick(Minecraft mc, boolean keyDown) {
		boolean toggleMode = Modules.opt("freelook.mode") == 1;
		if (keyDown && !wasDown && toggleMode) toggled = !toggled;
		wasDown = keyDown;
		boolean want = (toggleMode ? toggled : keyDown) && mc.player != null && dev.kyoto.util.Compat.currentScreen() == null;
		if (want && !allowed()) {
			if (Modules.on("freelook") && System.currentTimeMillis() - warnedAt > 5000) {
				warnedAt = System.currentTimeMillis();
				Notifications.push("Freelook is off here", "This server doesn't allow it", Notifications.WARN, 3000, null, "eye");
			}
			want = false;
			toggled = false;
		}
		if (want && !active) {
			yaw = mc.player.getYRot();
			pitch = mc.player.getXRot();
			before = mc.options.getCameraType();
			if (Modules.opt("freelook.view") == 0 && before.isFirstPerson()) mc.options.setCameraType(CameraType.THIRD_PERSON_BACK);
			active = true;
		} else if (!want && active) {
			active = false;
			if (before != null) mc.options.setCameraType(before);
			before = null;
		}
	}

	/** Mouse movement while freelooking: turn the camera, not the player. */
	public static boolean turn(double dYaw, double dPitch) {
		if (!active) return false;
		yaw += (float) dYaw * 0.15f;
		pitch = Math.max(-90f, Math.min(90f, pitch + (float) dPitch * 0.15f));
		return true;
	}
}
