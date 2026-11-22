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
	private static float bobPhase = 0, bobAmt = 0, swayX = 0, swayY = 0, swayVX = 0, swayVY = 0, lastYaw = Float.NaN, lastPitch = 0, land = 0, lastSwing = 0, fallV = 0;
	private static long attackAt = -1, hitAt = -1;
	private static int combo = 0;
	private static boolean wasGround = true;
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

	private static long lastCall = 0, frames = 0, uploads = 0;
	private static boolean packChecked = false;
	private static String lastError = "none";

	/** For /kyoto knife: everything about why the 3D knife is or isn't showing. */
	public static java.util.List<String> status() {
		java.util.List<String> out = new java.util.ArrayList<>();
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer p = mc.player;
		out.add("Kyoto " + dev.kyoto.KyotoClient.version() + "  ·  Knives module: " + (Modules.get("knives") == null ? "missing" : Modules.on("knives") ? "on" : "OFF"));
		if (p == null) return out;
		var st = p.getMainHandItem();
		String id = st.isEmpty() ? "(empty hand)" : net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(st.getItem()).getPath();
		out.add("Holding: " + id + "  ·  knife picked: " + Knives.picks().getOrDefault(id, "none") + "  ·  all picks: " + Knives.picks());
		String[] k = pick(p);
		if (k != null) out.add("Model " + k[0] + ": " + (KMesh.get(k[0]) == null ? "NOT FOUND in the jar" : "loaded"));
		out.add("First person: " + mc.options.getCameraType().isFirstPerson() + "  ·  active: " + active + "  ·  HUD calls: " + frames + "  ·  frames drawn: " + uploads);
		out.add("Last problem: " + (told.isEmpty() || told.equals("on") ? lastError : told));
		return out;
	}
	private static String told = "";

	/** Tells you once, on screen, why the 3D knife isn't showing (instead of silently showing the flat sword). */
	private static void why(String msg) {
		if (msg.equals(told)) return;
		told = msg;
		dev.kyoto.hud.Notifications.push("3D knife", msg, dev.kyoto.hud.Notifications.INFO, 5000, null, "sword");
		dev.kyoto.KyotoClient.LOG.info("[Kyoto] 3D knife: {}", msg);
	}

	public static void render(GuiGraphicsExtractor g, int sw, int sh, float partial) {
		// called from two places (its own HUD layer and the Kyoto HUD); only draw once a frame
		long nn = System.nanoTime();
		if (nn - lastCall < 2_000_000L) return;
		lastCall = nn;
		frames++;
		try {
			draw(g, sw, sh, partial);
		} catch (Throwable t) {
			active = false;
			why("Couldn't draw: " + t.getClass().getSimpleName() + (t.getMessage() == null ? "" : " " + t.getMessage()));
			lastError = t.toString();
			dev.kyoto.KyotoClient.LOG.warn("Kyoto knife view", t);
		}
	}

	private static void draw(GuiGraphicsExtractor g, int sw, int sh, float partial) {
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer p = mc.player;
		String[] k = p == null ? null : pick(p);
		if (k == null) {
			active = false;
			lastKey = "";
			return;
		}
		boolean firstPerson = mc.options.getCameraType().isFirstPerson();
		if (Modules.get("knives") != null && !Modules.on("knives")) { active = false; why("Turn on Knives (Cosmetics page) to see it in 3D"); return; }
		if (KMesh.get(k[0]) == null) { active = false; why("Knife model missing: " + k[0] + ". Re-unzip the update and rebuild"); return; }
		if (!firstPerson || p.isSpectator() || p.isInvisible()) {
			active = false;
			lastKey = "";
			return;
		}
		// knives picked before this version: rebuild the pack once so the flat sword is hidden in first person
		if (!packChecked) {
			packChecked = true;
			java.nio.file.Path j = net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir().resolve("resourcepacks/Kyoto Knives/assets/minecraft/items/" + k[3] + ".json");
			if (!java.nio.file.Files.exists(j)) Knives.apply();
		}
		if (!told.equals("on")) { told = "on"; dev.kyoto.KyotoClient.LOG.info("[Kyoto] 3D knife on: {} {}", k[0], k[1]); }
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
		// springy sway: lags behind your look, overshoots a little, settles
		float k2 = 90f, damp = 13f;
		swayVX += ((sx * 0.1f - swayX) * k2 - swayVX * damp) * dt;
		swayVY += ((sy * 0.1f - swayY) * k2 - swayVY * damp) * dt;
		swayX += swayVX * dt;
		swayY += swayVY * dt;
		if (Modules.opt("knives.sway") == 1) { swayX = 0; swayY = 0; swayVX = 0; swayVY = 0; }
		// landing dip
		if (!ground) fallV = Math.max(fallV, (float) -p.getDeltaMovement().y);
		if (ground && !wasGround) { land = Math.min(1, 0.4f + fallV * 1.2f); fallV = 0; }
		wasGround = ground;
		land *= (float) Math.pow(0.02, dt);

		KnifeAnim.State st = new KnifeAnim.State();
		st.time = now % 100000 / 1000f;
		st.draw = Math.min(1, (now - drawAt) / 650f);
		if (inspectAt >= 0) {
			float it = (now - inspectAt) / 1000f;
			if (it > KnifeAnim.inspectLength(k[0])) inspectAt = -1;
			else st.inspect = it;
		}
		// attacks: our own timing (smoother than the vanilla swing), a combo, and a hit-stop when it connects
		float swing = Compat.swing(p, partial);
		if (swing > 0.01f && lastSwing <= 0.01f) {
			combo = now - attackAt < 1100 ? combo + 1 : 0;
			attackAt = now;
			inspectAt = -1;
			hitAt = mc.hitResult instanceof net.minecraft.world.phys.EntityHitResult ? now + 110 : -1;
		}
		lastSwing = swing;
		if (attackAt >= 0) {
			float at = (now - attackAt) / 430f;
			if (at < 1) { st.swing = Math.max(0.001f, at); st.combo = combo; }
		}
		if (hitAt >= 0 && now >= hitAt) st.hit = (now - hitAt) / 1000f;
		st.land = land;
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
		if (ready.getAndSet(false)) { tex.upload(raster.rgba); uploads++; }
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
					if (Modules.opt("knives.arm") == 0) {
						int aw = slim ? 3 : 4;
						r.skinBox(skinPx, am, -aw / 2f, 0, -2, aw, 12, 4, 40, 16, 0f);
						r.skinBox(skinPx, am, -aw / 2f, 0, -2, aw, 12, 4, 40, 32, 0.25f);
						if (KnifeAnim.knifeOnTop(st)) r.overArm();
					}
					r.knife(mesh, km, pose, fin, mat);
					r.resolve();
					ready.set(true);
				} catch (Throwable t) {
					lastError = "renderer: " + t;
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
		boolean insp = false;
		float it = 0;
		if (pInspect >= 0) {
			it = (System.currentTimeMillis() - pInspect) / 1000f;
			if (it > KnifeAnim.inspectLength(shape)) pInspect = -1;
			else insp = true;
		}
		km.rotY((float) Math.toRadians(yawDeg)).rotX((float) Math.toRadians(12)).scale(Math.min(0.85f * ow / oh, 1.6f) / L).translate(0, -c, 0);
		if (insp) KnifeAnim.previewInspect(shape, it, km, pose);
		int fin = Math.max(0, java.util.Arrays.asList(Finish.IDS).indexOf(finish));
		int mat = Math.max(0, java.util.Arrays.asList(Finish.MATS).indexOf(Knives.material(item)));
		pr.clear();
		pr.knife(m, km, pose, fin, mat);
		ptex.upload(pr.resolve());
		Compat.blitTint(g, ptex.id, x, y, w, h, dev.kyoto.util.Anim.argb(0xFFFFFF, alpha));
	}
}
