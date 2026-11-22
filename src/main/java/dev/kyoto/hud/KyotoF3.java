package dev.kyoto.hud;

import dev.kyoto.module.Modules;
import dev.kyoto.util.Anim;
import dev.kyoto.util.Compat;
import dev.kyoto.util.Ui;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.ArrayList;
import java.util.List;

/**
 * Kyoto F3 (v6): cards instead of a wall of text.
 *  Left:  Performance (big FPS + frame graph), Position (big XYZ, facing dial), World.
 *  Right: Looking at, System, Developer.
 *  Compact = left column only, Full = + right column, Developer = + developer card.
 * Only shows while the real F3 screen is open (see DebugOverlayMixin).
 */
public final class KyotoF3 {
	private KyotoF3() {}

	public static volatile long vanillaWantedAt = 0;

	private static final float[] FRAMES = new float[90];
	private static int frameIdx = 0;
	private static long lastFrame = 0;
	private static long openedAt = 0;

	public static boolean visible() {
		boolean v = Modules.on("kyoto_f3") && System.currentTimeMillis() - vanillaWantedAt < 250;
		if (!v) openedAt = 0;
		else if (openedAt == 0) openedAt = System.currentTimeMillis();
		return v;
	}

	private record Row(String k, String v, int col) {}

	private static final int CARD_W = 158;

	public static void render(GuiGraphicsExtractor g, Minecraft mc, LocalPlayer p, int w, int h, float partial) {
		long now = System.nanoTime();
		if (lastFrame != 0) {
			FRAMES[frameIdx] = (now - lastFrame) / 1_000_000f;
			frameIdx = (frameIdx + 1) % FRAMES.length;
		}
		lastFrame = now;
		int style = Modules.opt("f3.style");
		int accent = Ui.accent();
		float in = Ui.animSpeed() <= 0 ? 1f : Anim.easeOutCubic((System.currentTimeMillis() - openedAt) / 220f);
		boolean hide = dev.kyoto.feature.Privacy.hideCoords() || Modules.on("coords_streamer");
		BlockPos bp = p.blockPosition();

		int x = 6 - Math.round((1 - in) * 20), y = 6;

		// ---- Performance
		int fps = mc.getFps();
		int fpsCol = fps >= 60 ? Ui.INK : fps >= 30 ? Ui.INK : 0xFF6B6B;
		int ph = Modules.opt("f3.graph") == 0 ? 58 : 34;
		card(g, x, y, CARD_W, ph, "Performance", "performance", accent, in);
		Ui.boldScaled(g, String.valueOf(fps), x + 8, y + 15, 1.8f, Anim.argb(fpsCol, in));
		int fw = Math.round(Ui.widthBold(String.valueOf(fps)) * 1.8f);
		Ui.small(g, "FPS", x + 12 + fw, y + 22, Anim.argb(Ui.MUTED, in));
		String ms = String.format("%.1f ms", avgFrame());
		Ui.text(g, ms, x + CARD_W - 8 - Ui.width(ms), y + 16, Anim.argb(Ui.TEXT, in));
		String lo = String.format("low %.0f", 1000f / Math.max(1f, maxFrame()));
		Ui.small(g, lo, x + CARD_W - 8 - Ui.widthSmall(lo), y + 26, Anim.argb(Ui.MUTED, in));
		if (Modules.opt("f3.graph") == 0) graph(g, x + 6, y + 34, CARD_W - 12, 18, in);
		y += ph + 5;

		// ---- Position
		card(g, x, y, CARD_W, 52, "Position", "waypoint", accent, in);
		if (hide) {
			Ui.text(g, "Hidden (streamer mode)", x + 8, y + 18, Anim.argb(Ui.MUTED, in));
		} else {
			String xyz = String.format("%.1f  %.1f  %.1f", p.getX(), p.getY(), p.getZ());
			Ui.bold(g, Ui.ellipsize(xyz, CARD_W - 40), x + 8, y + 16, Anim.argb(Ui.TEXT, in));
			Ui.small(g, "block " + bp.getX() + " " + bp.getY() + " " + bp.getZ() + "   chunk " + (bp.getX() >> 4) + " " + (bp.getZ() >> 4), x + 8, y + 28, Anim.argb(Ui.MUTED, in));
		}
		float yaw = net.minecraft.util.Mth.wrapDegrees(p.getYRot());
		String facing = cap(p.getDirection().getName()) + String.format("  %.0f° / %.0f°", yaw, p.getXRot());
		Ui.small(g, facing, x + 8, y + 39, Anim.argb(Ui.MUTED, in));
		dial(g, x + CARD_W - 20, y + 30, 11, yaw, accent, in);
		y += 57;

		// ---- World
		List<Row> world = new ArrayList<>();
		world.add(new Row("Biome", Compat.biomeName(bp), 0x9BE7C4));
		world.add(new Row("Dimension", Compat.pretty(Compat.dimensionId()), Ui.TEXT));
		try {
			int sky = mc.level.getBrightness(LightLayer.SKY, bp), block = mc.level.getBrightness(LightLayer.BLOCK, bp);
			world.add(new Row("Light", "sky " + sky + " · block " + block, block < 1 && sky < 8 ? 0xFF8A8A : Ui.TEXT));
		} catch (Throwable ignored) {
		}
		world.add(new Row("Speed", String.format("%.2f b/s", KyotoHud.speed()), Ui.TEXT));
		y = rows(g, x, y, "World", "world", world, accent, in) + 5;

		if (style == 0) return;

		// ---- right column
		int rx = w - 6 - CARD_W + Math.round((1 - in) * 20), ry = 6;
		List<Row> look = new ArrayList<>();
		HitResult hit = mc.hitResult;
		if (hit instanceof BlockHitResult bh && hit.getType() == HitResult.Type.BLOCK) {
			BlockPos tp = bh.getBlockPos();
			look.add(new Row("Block", Compat.pretty(String.valueOf(BuiltInRegistries.BLOCK.getKey(mc.level.getBlockState(tp).getBlock()))), 0x9BD3FF));
			if (!hide) look.add(new Row("At", tp.getX() + " " + tp.getY() + " " + tp.getZ(), Ui.MUTED));
			look.add(new Row("Face", cap(bh.getDirection().getName()), Ui.MUTED));
		} else if (hit instanceof EntityHitResult eh) {
			Entity e = eh.getEntity();
			look.add(new Row("Entity", Compat.pretty(String.valueOf(BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()))), 0xFFB38A));
			look.add(new Row("Distance", String.format("%.1f", e.distanceTo(p)), Ui.MUTED));
		} else look.add(new Row("Nothing", "-", Ui.MUTED));
		ry = rows(g, rx, ry, "Looking at", "eye", look, accent, in) + 5;

		Runtime rt = Runtime.getRuntime();
		long used = (rt.totalMemory() - rt.freeMemory()) >> 20, max = rt.maxMemory() >> 20;
		float memF = used / (float) Math.max(1, max);
		card(g, rx, ry, CARD_W, 74, "System", "client", accent, in);
		Ui.small(g, "Memory", rx + 8, ry + 17, Anim.argb(Ui.MUTED, in));
		String mem = used + " / " + max + " MB";
		Ui.small(g, mem, rx + CARD_W - 8 - Ui.widthSmall(mem), ry + 17, Anim.argb(memF > 0.85f ? 0xFF8A8A : Ui.TEXT, in));
		Ui.round(g, rx + 8, ry + 25, rx + CARD_W - 8, ry + 28, 1, Anim.argb(Ui.INK, 0.1f * in));
		Ui.round(g, rx + 8, ry + 25, rx + 8 + Math.round((CARD_W - 16) * memF), ry + 28, 1, Anim.argb(memF > 0.85f ? 0xFF8A8A : accent, in));
		String ip = dev.kyoto.feature.Privacy.server(Compat.currentServerIp());
		int ping = Compat.latency();
		kv(g, rx, ry + 33, "Server", ip == null ? (mc.getSingleplayerServer() != null ? "Singleplayer" : "-") : ip, Ui.TEXT, in);
		kv(g, rx, ry + 43, "Ping", ping > 0 ? ping + " ms" : "-", ping <= 0 ? Ui.MUTED : ping < 80 ? Ui.INK : ping < 160 ? Ui.INK : 0xFF6B6B, in);
		int ents = 0;
		for (Entity ignored : mc.level.entitiesForRendering()) ents++;
		kv(g, rx, ry + 53, "Render", mc.options.renderDistance().get() + " chunks · " + ents + " entities", Ui.TEXT, in);
		kv(g, rx, ry + 63, "Window", mc.getWindow().getWidth() + " x " + mc.getWindow().getHeight(), Ui.TEXT, in);
		ry += 79;

		if (style >= 2) {
			List<Row> dev = new ArrayList<>();
			dev.add(new Row("Java", System.getProperty("java.version"), Ui.TEXT));
			dev.add(new Row("CPU", rt.availableProcessors() + " threads", Ui.TEXT));
			dev.add(new Row("OS", System.getProperty("os.name", "?"), Ui.TEXT));
			dev.add(new Row("Mods", String.valueOf(net.fabricmc.loader.api.FabricLoader.getInstance().getAllMods().size()), Ui.TEXT));
			dev.add(new Row("Frames", String.format("%.1f - %.1f ms", minFrame(), maxFrame()), Ui.TEXT));
			dev.add(new Row("Velocity", String.format("%.2f %.2f %.2f", p.getDeltaMovement().x, p.getDeltaMovement().y, p.getDeltaMovement().z), Ui.TEXT));
			dev.add(new Row("On ground", p.onGround() ? "yes" : "no", Ui.TEXT));
			rows(g, rx, ry, "Developer", "gear", dev, accent, in);
		}
	}

	private static String cap(String s) {
		return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
	}

	private static void card(GuiGraphicsExtractor g, int x, int y, int w, int h, String title, String icon, int accent, float a) {
		Ui.round(g, x, y, x + w, y + h, 8, Anim.argb(Ui.GLASS, 0.72f * a));
		g.fill(x + 8, y, x + w - 8, y + 1, Anim.argb(Ui.INK, 0.08f * a));
		Ui.iconTex(g, icon, x + 7, y + 5, 7, Anim.argb(accent, a));
		Ui.small(g, title.toUpperCase(java.util.Locale.ROOT), x + 17, y + 6, Anim.argb(Ui.DIM, a));
	}

	private static void kv(GuiGraphicsExtractor g, int x, int y, String k, String v, int col, float a) {
		Ui.small(g, k, x + 8, y, Anim.argb(Ui.MUTED, a));
		v = Ui.ellipsize(v, Math.round((CARD_W - 60) / 0.75f));
		Ui.small(g, v, x + CARD_W - 8 - Ui.widthSmall(v), y, Anim.argb(col, a));
	}

	private static int rows(GuiGraphicsExtractor g, int x, int y, String title, String icon, List<Row> rows, int accent, float a) {
		int h = 18 + rows.size() * 11 + 2;
		card(g, x, y, CARD_W, h, title, icon, accent, a);
		int cy = y + 17;
		for (Row r : rows) {
			Ui.text(g, r.k(), x + 8, cy, Anim.argb(Ui.MUTED, a));
			String v = Ui.ellipsize(r.v(), CARD_W - 20 - Ui.width(r.k()));
			Ui.text(g, v, x + CARD_W - 8 - Ui.width(v), cy, Anim.argb(r.col(), a));
			cy += 11;
		}
		return y + h;
	}

	/** Little compass dial: needle points the way you face (N up). */
	private static void dial(GuiGraphicsExtractor g, int cx, int cy, int r, float yaw, int accent, float a) {
		Ui.round(g, cx - r, cy - r, cx + r, cy + r, r, Anim.argb(Ui.INK, 0.06f * a));
		Ui.small(g, "N", cx - 2, cy - r + 1, Anim.argb(Ui.MUTED, a));
		double ang = Math.toRadians(yaw + 180); // yaw 180 = north
		float ex = cx + (float) Math.sin(ang) * (r - 3), ey = cy - (float) Math.cos(ang) * (r - 3);
		Compat.line(g, cx, cy, ex, ey, 2f, Anim.argb(accent, a));
		Ui.round(g, cx - 1, cy - 1, cx + 2, cy + 2, 1, Anim.argb(Ui.INK, a));
	}

	private static void graph(GuiGraphicsExtractor g, int x, int y, int w, int h, float a) {
		float max = Math.max(25f, maxFrame());
		int n = FRAMES.length;
		float bw = w / (float) n;
		for (int i = 0; i < n; i++) {
			float ms = FRAMES[(frameIdx + i) % n];
			int bh = Math.max(1, Math.round(Math.min(1f, ms / max) * h));
			int col = ms < 17 ? Ui.INK : ms < 34 ? Ui.INK : 0xFF6B6B;
			int bx = x + Math.round(i * bw);
			g.fill(bx, y + h - bh, bx + Math.max(1, Math.round(bw) - 1), y + h, Anim.argb(col, 0.75f * a));
		}
		int line = y + h - Math.round((16.7f / max) * h);
		g.fill(x, line, x + w, line + 1, Anim.argb(Ui.INK, 0.18f * a));
	}

	private static float avgFrame() {
		float s = 0;
		int n = 0;
		for (float f : FRAMES) if (f > 0) {
			s += f;
			n++;
		}
		return n == 0 ? 0 : s / n;
	}

	private static float minFrame() {
		float m = Float.MAX_VALUE;
		for (float f : FRAMES) if (f > 0) m = Math.min(m, f);
		return m == Float.MAX_VALUE ? 0 : m;
	}

	private static float maxFrame() {
		float m = 0;
		for (float f : FRAMES) m = Math.max(m, f);
		return m;
	}
}
