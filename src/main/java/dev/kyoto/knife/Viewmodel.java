package dev.kyoto.knife;

import dev.kyoto.feature.Knives;
import dev.kyoto.module.Modules;
import dev.kyoto.util.Compat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.Identifier;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Your knife in first person: a real 3D model in your hand (with your skin's arm), drawn in place of the sword.
 * Draw spin when you pull it out, slash when you swing, look sway, walk bob, and an inspect animation (G by default).
 * Only you see it; nothing about how you fight changes. The heavy drawing runs on a helper thread so FPS stays up.
 */
public final class Viewmodel {
	private Viewmodel() {}

	/** True while a knife is replacing the held sword (the vanilla item is hidden). */
	public static volatile boolean active = false;

	private static String lastKey = "";
	private static int lastSlot = -1;
	private static long drawAt = 0, inspectAt = -1;
	private static float bobPhase = 0, bobAmt = 0, swayX = 0, swayY = 0, lastYaw = Float.NaN, lastPitch = 0;
	private static long lastNanos = 0;

	private static KnifeRaster raster;
	private static Compat.StreamTexture tex;
	private static int texW, texH;
	private static final AtomicBoolean busy = new AtomicBoolean(false), ready = new AtomicBoolean(false);
	private static final java.util.concurrent.ExecutorService WORKER = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "Kyoto knife renderer");
		t.setDaemon(true);
		t.setPriority(Thread.NORM_PRIORITY - 1);
		return t;
	});

	public static void inspect() {
		if (active) inspectAt = System.currentTimeMillis();
	}

	/** The knife picked for the item in your main hand, as {shape, finish, material} or null. */
	private static String[] pick(LocalPlayer p) {
		if (!Modules.on("knives")) return null;
		var st = p.getMainHandItem();
		if (st.isEmpty()) return null;
		String id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(st.getItem()).getPath();
		String k = Knives.picks().get(id);
		if (k == null) return null;
		int us = k.lastIndexOf('_');
		if (us < 0) return null;
		return new String[]{k.substring(0, us), k.substring(us + 1), Knives.material(id), id};
	}

	private static int slot(LocalPlayer p) {
		try {
			Object inv = p.getInventory();
			try {
				return (int) inv.getClass().getMethod("getSelectedSlot").invoke(inv);
			} catch (NoSuchMethodException e) {
				var f = inv.getClass().getDeclaredField("selected");
				f.setAccessible(true);
				return f.getInt(inv);
			}
		} catch (Throwable t) {
			return 0;
		}
	}

	public static void render(GuiGraphicsExtractor g, int sw, int sh, float partial) {
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer p = mc.player;
		String[] k = p == null ? null : pick(p);
		boolean firstPerson = mc.options.getCameraType().isFirstPerson();
		if (k == null || !firstPerson || p.isSpectator() || p.isInvisible() || KMesh.get(k[0]) == null) {
			active = false;
			lastKey = "";
			return;
		}
		active = true;
		long now = System.currentTimeMillis();
		long nanos = System.nanoTime();
		float dt = lastNanos == 0 ? 0.016f : Math.min(0.1f, (nanos - lastNanos) / 1e9f);
		lastNanos = nanos;
		String key = k[0] + k[1] + k[3];
		int slot = slot(p);
		if (!key.equals(lastKey) || slot != lastSlot) {
			drawAt = now;
			inspectAt = -1;
			lastKey = key;
			lastSlot = slot;
		}
		// walk bob + look sway
		double speed = Math.sqrt(p.getDeltaMovement().horizontalDistanceSqr());
		boolean ground = p.onGround();
		bobAmt += ((ground ? (float) Math.min(1, speed * 7) : 0) - bobAmt) * Math.min(1, dt * 8);
		bobPhase += dt * (float) (6 + speed * 25);
		float yaw = p.getViewYRot(partial), pitch = p.getViewXRot(partial);
		if (Float.isNaN(lastYaw)) { lastYaw = yaw; lastPitch = pitch; }
		float dyaw = yaw - lastYaw, dpitch = pitch - lastPitch;
		lastYaw = yaw;
		lastPitch = pitch;
		float sx = Math.max(-1, Math.min(1, -dyaw * 0.01f)), sy = Math.max(-1, Math.min(1, dpitch * 0.01f));
		swayX += (sx * 0.12f - swayX) * Math.min(1, dt * 7);
		swayY += (sy * 0.12f - swayY) * Math.min(1, dt * 7);
		if (Modules.opt("knives.sway") == 1) { swayX = 0; swayY = 0; }

		KnifeAnim.State st = new KnifeAnim.State();
		st.time = now % 100000 / 1000f;
		st.draw = Math.min(1, (now - drawAt) / 650f);
		if (inspectAt >= 0) {
			float it = (now - inspectAt) / 1000f;
			if (it > KnifeAnim.inspectLength(k[0])) inspectAt = -1;
			else st.inspect = it;
		}
		float swing = Compat.swing(p, partial);
		if (swing > 0.01f) { st.swing = swing; inspectAt = -1; }
		st.bobX = (float) Math.sin(bobPhase) * bobAmt;
		st.bobY = -Math.abs((float) Math.cos(bobPhase)) * bobAmt;
		st.swayX = swayX;
		st.swayY = swayY;

		// the part of the screen the knife lives in (right side, bottom)
		int rx0 = Math.round(sw * 0.38f), ry0 = Math.round(sh * 0.12f), rw = sw - rx0, rh = sh - ry0;
		double gs = mc.getWindow().getGuiScale();
		float q = new float[]{0.4f, 0.55f, 0.75f}[Math.max(0, Math.min(2, Modules.opt("knives.quality")))];
		int ow = Math.max(64, Math.min(900, (int) (rw * gs * q))), oh = Math.max(64, Math.min(900, (int) (rh * gs * q)));
		if (raster == null || tex == null || texW != ow || texH != oh) {
			if (busy.get()) { blit(g, rx0, ry0, rw, rh); return; }
			if (tex != null) tex.close();
			raster = new KnifeRaster(ow, oh, 2);
			tex = Compat.streamTexture(Identifier.fromNamespaceAndPath("kyoto", "knife_view"), ow, oh);
			texW = ow;
			texH = oh;
			ready.set(false);
		}
		if (ready.getAndSet(false)) tex.upload(raster.rgba);
		if (!busy.get()) {
			busy.set(true);
			// camera: Minecraft's hand uses a 70 degree view, centred on the screen
			float fullH = sh, scale = oh / (float) rh;
			raster.focal = (fullH / 2f) / (float) Math.tan(Math.toRadians(35)) * scale;
			raster.cx = (sw / 2f - rx0) * scale;
			raster.cy = (sh / 2f - ry0) * scale;
			raster.light = dev.kyoto.cosmetic.Mesh3D.lightAt(p.getEyePosition(partial));
			raster.time = st.time;
			var skin = dev.kyoto.net.Heads.skin(p.getGameProfile().name());
			final int[] skinPx = skin == null ? null : skin.argb();
			final boolean slim = skin != null && skin.slim();
			final String shape = k[0];
			final int fin = Math.max(0, java.util.Arrays.asList(Finish.IDS).indexOf(k[1]));
			final int mat = Math.max(0, java.util.Arrays.asList(Finish.MATS).indexOf(k[2]));
			final KnifeRaster r = raster;
			WORKER.execute(() -> {
				try {
					KMesh mesh = KMesh.get(shape);
					M4 km = new M4(), am = new M4();
					float[][] pose = mesh.pose();
					KnifeAnim.build(shape, st, km, am, pose);
					r.clear();
					r.knife(mesh, km, pose, fin, mat);
					if (Modules.opt("knives.arm") == 0) {
						int aw = slim ? 3 : 4;
						r.skinBox(skinPx, am, -aw / 2f, 0, -2, aw, 12, 4, 40, 16, 0f);
						r.skinBox(skinPx, am, -aw / 2f, 0, -2, aw, 12, 4, 40, 32, 0.25f);
					}
					r.resolve();
					ready.set(true);
				} catch (Throwable t) {
					dev.kyoto.KyotoClient.LOG.warn("Kyoto knife view", t);
				} finally {
					busy.set(false);
				}
			});
		}
		blit(g, rx0, ry0, rw, rh);
	}

	private static void blit(GuiGraphicsExtractor g, int x, int y, int w, int h) {
		if (tex != null) Compat.blitTint(g, tex.id, x, y, w, h, 0xFFFFFFFF);
	}

	/* ---------------- the menu preview (spinning, with inspect) ---------------- */

	private static KnifeRaster pr;
	private static Compat.StreamTexture ptex;
	private static long pInspect = -1;
	private static String pKey = "";

	public static void previewInspect() {
		pInspect = System.currentTimeMillis();
	}

	/** Draws a big, slowly turning 3D preview of a knife into a box (for the Knives menu). */
	public static void preview(GuiGraphicsExtractor g, String shape, String finish, String item, int x, int y, int w, int h, float yawDeg, float alpha) {
		KMesh m = KMesh.get(shape);
		if (m == null) return;
		double gs = Minecraft.getInstance().getWindow().getGuiScale();
		int ow = Math.max(64, Math.min(760, (int) (w * gs * 0.75f))), oh = Math.max(64, Math.min(760, (int) (h * gs * 0.75f)));
		if (pr == null || pr.W != ow || pr.H != oh) {
			if (ptex != null) ptex.close();
			pr = new KnifeRaster(ow, oh, 2);
			ptex = Compat.streamTexture(Identifier.fromNamespaceAndPath("kyoto", "knife_preview"), ow, oh);
		}
		String key = shape + finish;
		if (!key.equals(pKey)) { pKey = key; pInspect = System.currentTimeMillis() - 2000; }
		pr.focal = oh * 1.1f;
		pr.cx = ow / 2f;
		pr.cy = oh / 2f;
		pr.light = 1f;
		pr.time = System.currentTimeMillis() % 100000 / 1000f;
		float L = m.maxY - m.minY, c = (m.maxY + m.minY) / 2;
		M4 km = new M4().translate(0, 0, -1.25f).rotZ((float) Math.toRadians(-90 + 8));
		float[][] pose = m.pose();
		float yaw = yawDeg;
		if (pInspect >= 0) {
			float t = (System.currentTimeMillis() - pInspect) / 1000f, len = KnifeAnim.inspectLength(shape);
			if (t > len) pInspect = -1;
			else {
				// reuse the in-hand inspect for the moving parts, and add a showy turn
				KnifeAnim.State st = new KnifeAnim.State();
				st.inspect = t;
				KnifeAnim.build(shape, st, new M4(), new M4(), pose);
				float u = t / len;
				yaw += (float) (Math.sin(u * Math.PI) * 180);
			}
		}
		km.rotY((float) Math.toRadians(yaw)).rotX((float) Math.toRadians(12)).scale(Math.min(0.85f * ow / oh, 1.6f) / L).translate(0, -c, 0);
		int fin = Math.max(0, java.util.Arrays.asList(Finish.IDS).indexOf(finish));
		int mat = Math.max(0, java.util.Arrays.asList(Finish.MATS).indexOf(Knives.material(item)));
		pr.clear();
		pr.knife(m, km, pose, fin, mat);
		ptex.upload(pr.resolve());
		Compat.blitTint(g, ptex.id, x, y, w, h, dev.kyoto.util.Anim.argb(0xFFFFFF, alpha));
	}
}
