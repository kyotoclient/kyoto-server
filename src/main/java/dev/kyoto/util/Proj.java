package dev.kyoto.util;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;

/** Projects a point in the world onto the screen (used for sky lyrics and waypoint labels/beams). */
public final class Proj {
	private Proj() {}

	private static final double THIRD_PERSON_DIST = 4.0;

	private static boolean front() {
		return Minecraft.getInstance().options.getCameraType().isMirrored();
	}

	private static boolean third() {
		return !Minecraft.getInstance().options.getCameraType().isFirstPerson();
	}

	/** Where the camera actually is (handles F5 third person, approximately). */
	public static Vec3 cameraPos(float partial) {
		LocalPlayer p = Minecraft.getInstance().player;
		Vec3 eye = p.getEyePosition(partial);
		if (!third()) return eye;
		Vec3 look = p.getViewVector(partial);
		if (dev.kyoto.extra.Freelook.active) {
			double y = Math.toRadians(dev.kyoto.extra.Freelook.yaw()), x = Math.toRadians(dev.kyoto.extra.Freelook.pitch());
			look = new Vec3(-Math.sin(y) * Math.cos(x), -Math.sin(x), Math.cos(y) * Math.cos(x));
			return eye.subtract(look.scale(THIRD_PERSON_DIST));
		}
		return front() ? eye.add(look.scale(THIRD_PERSON_DIST)) : eye.subtract(look.scale(THIRD_PERSON_DIST));
	}

	private static double camYaw(float partial) {
		if (dev.kyoto.extra.Freelook.active) return dev.kyoto.extra.Freelook.yaw();
		LocalPlayer p = Minecraft.getInstance().player;
		return p.getViewYRot(partial) + (front() ? 180 : 0);
	}

	private static double camPitch(float partial) {
		if (dev.kyoto.extra.Freelook.active) return dev.kyoto.extra.Freelook.pitch();
		LocalPlayer p = Minecraft.getInstance().player;
		return front() ? -p.getViewXRot(partial) : p.getViewXRot(partial);
	}

	/** Camera basis vectors {forward, right, up} for this frame. */
	public static Vec3[] basis(float partial) {
		double yaw = Math.toRadians(camYaw(partial));
		double pitch = Math.toRadians(camPitch(partial));
		Vec3 f = new Vec3(-Math.sin(yaw) * Math.cos(pitch), -Math.sin(pitch), Math.cos(yaw) * Math.cos(pitch));
		Vec3 right = new Vec3(-Math.cos(yaw), 0, -Math.sin(yaw));
		return new Vec3[]{f, right, right.cross(f)};
	}

	/** Pixels per block at depth 1 (multiply by size/depth). */
	public static double focal(int h) {
		double fov = Math.toRadians(dev.kyoto.feature.Zoom.projectionFov(Minecraft.getInstance()));
		return (h / 2.0) / Math.tan(fov / 2.0);
	}

	/** @return {screenX, screenY, depth} or null if behind the camera */
	public static float[] project(Vec3 world, float partial, int w, int h) {
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer p = mc.player;
		if (p == null) return null;
		Vec3 eye = cameraPos(partial);
		Vec3 rel = world.subtract(eye);
		double yaw = Math.toRadians(camYaw(partial));
		double pitch = Math.toRadians(camPitch(partial));
		Vec3 f = new Vec3(-Math.sin(yaw) * Math.cos(pitch), -Math.sin(pitch), Math.cos(yaw) * Math.cos(pitch));
		Vec3 right = new Vec3(-Math.cos(yaw), 0, -Math.sin(yaw));
		Vec3 up = right.cross(f);
		double z = rel.dot(f);
		if (z < 0.2) return null;
		double fov = Math.toRadians(dev.kyoto.feature.Zoom.projectionFov(mc));
		double focal = (h / 2.0) / Math.tan(fov / 2.0);
		return new float[]{(float) (w / 2.0 + rel.dot(right) / z * focal), (float) (h / 2.0 - rel.dot(up) / z * focal), (float) z};
	}
}
