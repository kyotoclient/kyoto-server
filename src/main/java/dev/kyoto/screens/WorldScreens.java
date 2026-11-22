package dev.kyoto.screens;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.kyoto.module.Modules;
import dev.kyoto.util.Compat;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Proxy;
import java.lang.reflect.Type;

/**
 * Draws video screens as real flat panels IN the world (like a painting or a map), instead of on top of the HUD:
 *  - blocks, players, your hand and the crosshair all go in front of them correctly
 *  - they stay perfectly still (no wobble from view bobbing) and never break up when you walk right up to them
 *  - they're still there with F1 (hidden HUD)
 *  - far away they fade out instead of popping
 *
 * Fabric renamed its world-render hook between versions (WorldRenderEvents -> LevelRenderEvents), so the hook and
 * the few renderer names that moved are looked up by name. If none are found the old HUD-style screens are used.
 */
public final class WorldScreens {
	private WorldScreens() {}

	private static final Logger LOG = LoggerFactory.getLogger("kyoto-screens");
	public static final int LIGHT = 0xF000F0;     // full bright
	public static final int OVERLAY = 10 << 16;   // "no overlay"
	private static final Identifier WHITE = Identifier.fromNamespaceAndPath("kyoto", "textures/misc/white.png");
	private static volatile long lastDraw = 0;
	/** The game's own immediate buffer source (its class moved between versions, so it's kept as a plain Object). */
	private static Object buffers;
	private static Method getBuffer, endBatch;
	private static Method typeFactory;
	private static boolean failed = false;

	/** True while the in-world renderer is working (the HUD fallback stays off). */
	public static boolean drawing() {
		return !failed && System.currentTimeMillis() - lastDraw < 600;
	}

	/* ---------------- hooking into world rendering ---------------- */

	public static void init() {
		String[] classes = {"net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents",
				"net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents",
				"net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents"};
		String[] preferred = {"AFTER_ENTITIES", "BEFORE_TRANSLUCENT", "AFTER_TRANSLUCENT_TERRAIN", "BEFORE_DEBUG_RENDER", "AFTER_SOLID_FEATURES",
				"AFTER_TRANSLUCENT_FEATURES", "BEFORE_TRANSLUCENT_FEATURES", "END_MAIN", "LAST"};
		for (String cn : classes) {
			Class<?> c;
			try {
				c = Class.forName(cn);
			} catch (Throwable t) {
				continue;
			}
			// every event this Fabric version has, the ones we like first (names change between versions)
			java.util.List<String> events = new java.util.ArrayList<>(java.util.List.of(preferred));
			java.util.List<String> all = new java.util.ArrayList<>();
			for (Field f : c.getFields()) if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) all.add(f.getName());
			LOG.info("Kyoto world hook: {} has {}", c.getSimpleName(), all);
			events.retainAll(all);
			for (String n : all) if (!events.contains(n) && !n.startsWith("START") && !n.contains("BLOCK_OUTLINE") && !n.contains("DEBUG")) events.add(n);
			for (String en : events) {
				try {
					Field f = c.getField(en);
					Object event = f.get(null);
					Type gt = f.getGenericType();
					if (!(gt instanceof ParameterizedType pt) || !(pt.getActualTypeArguments()[0] instanceof Class<?> listener)) continue;
					Object proxy = Proxy.newProxyInstance(listener.getClassLoader(), new Class<?>[]{listener}, (obj, m, args) -> {
						if (m.getDeclaringClass() == Object.class) {
							return switch (m.getName()) {
								case "hashCode" -> System.identityHashCode(obj);
								case "equals" -> obj == args[0];
								default -> "KyotoScreens";
							};
						}
						render(args != null && args.length > 0 ? args[0] : null);
						return m.getReturnType() == boolean.class ? Boolean.TRUE : null;
					});
					Method reg = null;
					for (Method m : event.getClass().getMethods()) if (m.getName().equals("register") && m.getParameterCount() == 1) reg = m;
					if (reg == null) continue;
					reg.invoke(event, proxy);
					LOG.info("Video screens draw in the world ({}.{})", c.getSimpleName(), en);
					return;
				} catch (Throwable t) {
					LOG.debug("screen hook {}.{} failed", cn, en, t);
				}
			}
		}
		failed = true;
		dev.kyoto.cosmetic.Kycos.drawError = "no world render hook on this Fabric version";
		LOG.warn("No world render hook found - video screens fall back to HUD drawing");
	}

	/** When the event doesn't hand us a pose stack: build the camera's view rotation ourselves. */
	private static PoseStack viewFromCamera(Minecraft mc) {
		PoseStack p = new PoseStack();
		try {
			Object cam = call(mc.gameRenderer, "getMainCamera", "mainCamera");
			Object q = call(cam, "rotation");
			if (q instanceof org.joml.Quaternionfc r) p.last().pose().rotation(new org.joml.Quaternionf(r).conjugate());
		} catch (Throwable ignored) {
		}
		return p;
	}

	/* ---------------- small reflection helpers ---------------- */

	private static Object call(Object o, String... names) {
		if (o == null) return null;
		for (String n : names) {
			try {
				Method m = o.getClass().getMethod(n);
				m.setAccessible(true);
				return m.invoke(o);
			} catch (Throwable ignored) {
			}
		}
		return null;
	}

	private static Object field(Object o, String... names) {
		if (o == null) return null;
		for (String n : names) {
			try {
				Field f = o.getClass().getField(n);
				return f.get(o);
			} catch (Throwable ignored) {
			}
		}
		return null;
	}

	/** Exactly where the camera is this frame (with smooth movement), so screens never jitter. */
	private static Vec3 camera(Object ctx) {
		Object st = call(ctx, "levelState", "worldState");
		Object cam = field(field(st, "cameraRenderState"), "pos");
		if (cam instanceof Vec3 v) return v;
		Object c = call(call(Minecraft.getInstance().gameRenderer, "getMainCamera", "mainCamera"), "position", "getPosition", "pos");
		if (c instanceof Vec3 v) return v;
		return dev.kyoto.util.Proj.cameraPos(1f);
	}

	/** A render type that draws a texture flat, full-bright, with see-through (fade) support. Names moved in 26.x. */
	private static Object type(Identifier tex) {
		try {
			if (typeFactory == null) {
				outer:
				for (String cn : new String[]{"net.minecraft.client.renderer.rendertype.RenderTypes", "net.minecraft.client.renderer.RenderTypes",
						"net.minecraft.client.renderer.RenderType"}) {
					Class<?> c;
					try {
						c = Class.forName(cn);
					} catch (Throwable t) {
						continue;
					}
					for (String mn : new String[]{"text", "entityTranslucent", "entityCutout", "entityCutoutNoCull"}) {
						try {
							typeFactory = c.getMethod(mn, Identifier.class);
							break outer;
						} catch (NoSuchMethodException ignored) {
						}
					}
				}
				if (typeFactory == null) throw new IllegalStateException("no render type");
			}
			return typeFactory.invoke(null, tex);
		} catch (Throwable t) {
			failed = true;
			LOG.warn("Video screens: no usable render type, using HUD drawing", t);
			return null;
		}
	}

	private static VertexConsumer buffer(Object type) throws Exception {
		return (VertexConsumer) getBuffer.invoke(buffers, type);
	}

	private static void flush() throws Exception {
		endBatch.invoke(buffers);
	}

	/* ---------------- drawing ---------------- */

	/** For other in-world drawing (custom cosmetics): same buffers and render types as the screens. */
	public static Object renderType(Identifier tex) {
		return type(tex);
	}

	public static VertexConsumer vertexBuffer(Object type) throws Exception {
		return buffer(type);
	}

	public static void flushBuffers() throws Exception {
		flush();
	}

	private static void setupBuffers() {
		if (buffers != null) return;
		Minecraft mcc = Minecraft.getInstance();
		Object rb = call(mcc, "renderBuffers");
		if (rb == null) rb = call(mcc.gameRenderer, "renderBuffers", "getRenderBuffers");
		buffers = call(rb, "bufferSource");
		if (buffers == null) throw new IllegalStateException("no buffer source");
		for (Method m : buffers.getClass().getMethods()) {
			if (m.getName().equals("getBuffer") && m.getParameterCount() == 1) getBuffer = m;
			if (m.getName().equals("endBatch") && m.getParameterCount() == 0) endBatch = m;
		}
		if (getBuffer == null || endBatch == null) { buffers = null; throw new IllegalStateException("buffer source methods missing"); }
	}

	private static boolean cosmeticsFailed = false, auraFailed = false, petsFailed = false, wardrobeFailed = false, trailsFailed = false;
	private static volatile long lastCosmetics = 0;

	/** True while the world hook is running (it isn't in first person menus etc.). */
	public static boolean drawingAny() {
		return System.currentTimeMillis() - lastCosmetics < 1000;
	}

	public static float partialTick(Minecraft mc) {
		try {
			Object dt = call(mc, "getDeltaTracker", "deltaTracker", "getTimer");
			for (String n : new String[]{"getGameTimeDeltaPartialTick", "getGameTimeDeltaTicks"}) {
				try { return (float) dt.getClass().getMethod(n, boolean.class).invoke(dt, false); } catch (Throwable ignored) { }
			}
		} catch (Throwable ignored) { }
		return 1f;
	}

	private static void render(Object ctx) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || mc.level == null) return;
		Object ps = call(ctx, "poseStack", "matrices", "matrixStack");
		PoseStack pose = ps instanceof PoseStack p ? p : viewFromCamera(mc);
		// custom cosmetics (.kycos) - their own try so a problem there never breaks screens
		if (!cosmeticsFailed) {
			try {
				setupBuffers();
				float partial = partialTick(mc);
				Vec3 cpos = camera(ctx);
				dev.kyoto.cosmetic.Kycos.render(pose, cpos, partial);
				lastCosmetics = System.currentTimeMillis();
			} catch (Throwable t) {
				cosmeticsFailed = true;
				dev.kyoto.cosmetic.Kycos.drawError = t.getClass().getSimpleName() + (t.getMessage() == null ? "" : ": " + t.getMessage());
				LOG.warn("Custom cosmetics can't draw on this version", t);
			}
		}
		// particle accessories: their own try, so a cosmetic problem never hides them (and the other way round)
		if (!auraFailed) {
			try {
				setupBuffers();
				dev.kyoto.cosmetic.Aura.render(pose, camera(ctx), partialTick(mc));
			} catch (Throwable t) {
				auraFailed = true;
				dev.kyoto.cosmetic.Aura.error = t.getClass().getSimpleName() + (t.getMessage() == null ? "" : ": " + t.getMessage());
				LOG.warn("Particle accessories can't draw", t);
			}
		}
		// 3D pets + wardrobe: each in its own try so one never hides the other
		if (!petsFailed) {
			try {
				setupBuffers();
				dev.kyoto.cosmetic.Pets3D.render(pose, camera(ctx), partialTick(mc));
			} catch (Throwable t) {
				petsFailed = true;
				dev.kyoto.cosmetic.Pets3D.error = t.getClass().getSimpleName() + (t.getMessage() == null ? "" : ": " + t.getMessage());
				LOG.warn("3D pets can't draw", t);
			}
		}
		if (!trailsFailed) {
			try {
				setupBuffers();
				dev.kyoto.cosmetic.SwordTrails.render(pose, camera(ctx), partialTick(mc));
			} catch (Throwable t) {
				trailsFailed = true;
				LOG.warn("Sword trails can't draw", t);
			}
		}
		if (!wardrobeFailed) {
			try {
				setupBuffers();
				dev.kyoto.cosmetic.Wardrobe.render(pose, camera(ctx), partialTick(mc));
			} catch (Throwable t) {
				wardrobeFailed = true;
				dev.kyoto.cosmetic.Wardrobe.error = t.getClass().getSimpleName() + (t.getMessage() == null ? "" : ": " + t.getMessage());
				LOG.warn("Wardrobe can't draw", t);
			}
		}
		try {
			setupBuffers();
			dev.kyoto.feature.BuildRecorder.renderWorld(pose, camera(ctx));
		} catch (Throwable ignored) {
		}
		if (failed || !Modules.on("video_screens")) return;
		Screens.Screen ghost = Screens.placing;
		if (Screens.SCREENS.isEmpty() && ghost == null) {
			lastDraw = System.currentTimeMillis();
			return;
		}
		try {
			setupBuffers();
			Vec3 cam = camera(ctx);
			String dim = Compat.dimensionId();
			for (Screens.Screen s : Screens.SCREENS) {
				if (s == ghost || !s.dim.equals(dim) || (!s.mine && !Modules.on("see_others_screens"))) continue;
				double d = nearest(s, cam).distanceTo(cam);
				if (d > Screens.MAX_DIST) continue;
				float alpha = (float) Math.max(0, Math.min(1, (Screens.MAX_DIST - d) / Screens.FADE));
				drawScreen(pose, s, cam, alpha, false);
			}
			if (ghost != null && ghost.dim.equals(dim)) drawScreen(pose, ghost, cam, 1f, true);
			flush();
			lastDraw = System.currentTimeMillis();
		} catch (Throwable t) {
			failed = true;
			LOG.warn("Video screens: in-world drawing failed, using HUD drawing", t);
		}
	}

	private static Vec3 nearest(Screens.Screen s, Vec3 p) {
		Vec3 c = s.center(), r = s.right();
		Vec3 d = p.subtract(c);
		double along = Math.max(-s.w / 2, Math.min(s.w / 2, d.dot(r)));
		double up = Math.max(-s.h / 2, Math.min(s.h / 2, d.y));
		return c.add(r.scale(along)).add(0, up, 0);
	}

	private static void drawScreen(PoseStack pose, Screens.Screen s, Vec3 cam, float alpha, boolean ghost) throws Exception {
		Vec3 c = s.center().subtract(cam), r = s.right(), n = s.normal();
		Vec3 up = new Vec3(0, 1, 0);
		VideoStream v = Screens.stream(s);
		Identifier vid = v != null && !ghost ? v.texture() : null;
		Identifier logo = dev.kyoto.ui.TitleTheme.logo();
		float hw = s.w / 2f, hh = s.h / 2f, bz = Math.max(0.05f, s.w * 0.012f);
		int a = Math.round(255 * alpha * (ghost ? 0.7f : 1f));
		if (a < 4) return;
		var last = pose.last();

		// frame (both sides) - a dark rim a little bigger than the picture; white while placing
		Object frameType = type(WHITE);
		if (frameType == null) return;
		VertexConsumer vc = buffer(frameType);
		int fr = ghost ? 235 : 22, fg = ghost ? 238 : 22, fb = ghost ? 245 : 27;
		quad(vc, last, c.add(n.scale(0.0015)), r, up, hw + bz, hh + bz, fr, fg, fb, a, 0.5f, 0.5f, 0.5f, 0.5f, false);
		quad(vc, last, c.subtract(n.scale(0.0015)), r, up, hw + bz, hh + bz, fr, fg, fb, a, 0.5f, 0.5f, 0.5f, 0.5f, true);
		// back of the screen: a plain dark panel
		quad(vc, last, c.subtract(n.scale(0.003)), r, up, hw, hh, 14, 14, 17, a, 0.5f, 0.5f, 0.5f, 0.5f, true);
		flush();

		// the picture
		if (vid != null) {
			Object t = type(vid);
			if (t == null) return;
			quad(buffer(t), last, c.add(n.scale(0.003)), r, up, hw, hh, 255, 255, 255, a, 0, 0, 1, 1, false);
		} else {
			// nothing playing yet: black panel with the Kyoto logo in the middle
			quad(buffer(frameType), last, c.add(n.scale(0.003)), r, up, hw, hh, 9, 9, 12, a, 0.5f, 0.5f, 0.5f, 0.5f, false);
			float ls = Math.min(hw, hh) * 0.45f;
			Object lt = type(logo);
			if (lt != null) quad(buffer(lt), last, c.add(n.scale(0.004)), r, up, ls, ls, 255, 255, 255, Math.round(a * 0.9f), 0, 0, 1, 1, false);
		}
		flush();

		// status text in the middle (Loading / Paused / tips), and whose screen it is
		String msg = null;
		if (ghost) msg = "Right-click to place";
		else if (s.url.isBlank()) msg = "No video yet";
		else if (Screens.held()) msg = null;
		else if (s.paused) msg = "Paused";
		else if (v != null && !v.state.isEmpty()) msg = v.state;
		else if (v == null) msg = "Loading...";
		if (msg != null) text(pose, c.add(n.scale(0.006)).subtract(up.scale(vid == null ? hh * 0.62 : 0)), r, n, s.h / 140f, msg, a, true);
		if (!s.mine && !ghost) text(pose, c.add(n.scale(0.006)).add(r.scale(-hw + 0.08)).add(up.scale(hh - 0.1)), r, n, s.h / 260f, s.owner + "'s screen", Math.round(a * 0.7f), false);
	}

	/** One flat rectangle centred on c. back = wound to face the other way (the back side). */
	private static void quad(VertexConsumer vc, PoseStack.Pose p, Vec3 c, Vec3 r, Vec3 up, float hw, float hh,
	                         int cr, int cg, int cb, int ca, float u0, float v0, float u1, float v1, boolean back) {
		Vec3 bl = c.add(r.scale(-hw)).add(up.scale(-hh)), br = c.add(r.scale(hw)).add(up.scale(-hh));
		Vec3 tr = c.add(r.scale(hw)).add(up.scale(hh)), tl = c.add(r.scale(-hw)).add(up.scale(hh));
		Vec3[] pts = back ? new Vec3[]{br, bl, tl, tr} : new Vec3[]{bl, br, tr, tl};
		float[][] uv = back ? new float[][]{{u1, v1}, {u0, v1}, {u0, v0}, {u1, v0}} : new float[][]{{u0, v1}, {u1, v1}, {u1, v0}, {u0, v0}};
		var m = p.pose();
		for (int i = 0; i < 4; i++) {
			vc.addVertex(m, (float) pts[i].x, (float) pts[i].y, (float) pts[i].z).setColor(cr, cg, cb, ca).setUv(uv[i][0], uv[i][1])
					.setOverlay(OVERLAY).setLight(LIGHT).setNormal(0f, 1f, 0f);
		}
	}

	private static Method drawText;

	/** Text lying flat on the screen surface (scale = blocks per font pixel). */
	private static void text(PoseStack pose, Vec3 at, Vec3 r, Vec3 n, float scale, String msg, int alpha, boolean centered) {
		if (alpha < 8) return;
		try {
			var font = Minecraft.getInstance().font;
			int tw = font.width(msg);
			float x = centered ? -tw / 2f : 0, y = -4;
			// pill behind the text
			if (centered) {
				Object t = type(WHITE);
				if (t != null) {
					quad(buffer(t), pose.last(), at.subtract(n.scale(0.001)), r, new Vec3(0, 1, 0), (tw / 2f + 6) * scale, 7 * scale, 0, 0, 0, Math.round(alpha * 0.6f), 0.5f, 0.5f, 0.5f, 0.5f, false);
					flush();
				}
			}
			Matrix4f m = new Matrix4f(pose.last().pose());
			m.translate((float) at.x, (float) at.y, (float) at.z);
			m.mul(new Matrix4f((float) r.x * scale, (float) r.y * scale, (float) r.z * scale, 0,
					0, -scale, 0, 0,
					(float) n.x, (float) n.y, (float) n.z, 0,
					0, 0, 0, 1));
			if (drawText == null) {
				for (Method mm : font.getClass().getMethods()) {
					Class<?>[] pt = mm.getParameterTypes();
					if (mm.getName().equals("drawInBatch") && pt.length == 10 && pt[0] == String.class) drawText = mm;
				}
			}
			if (drawText == null) return;
			Class<?>[] pt = drawText.getParameterTypes();
			Object mode = null;
			for (Object e : pt[7].getEnumConstants()) if (e.toString().equals("NORMAL")) mode = e;
			int col = (Math.max(0, Math.min(255, alpha)) << 24) | 0xE8EAEE;
			drawText.invoke(font, msg, x, y, col, false, m, buffers, mode, 0, LIGHT);
			flush();
		} catch (Throwable ignored) {
			// text is optional - the picture still shows
		}
	}
}
