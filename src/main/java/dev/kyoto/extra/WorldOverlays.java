package dev.kyoto.extra;

import dev.kyoto.module.Modules;
import dev.kyoto.util.Anim;
import dev.kyoto.util.Compat;
import dev.kyoto.util.Proj;
import dev.kyoto.util.Ui;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayList;
import java.util.List;

/**
 * Things drawn "in the world" on top of the game view: block outline styles, light levels,
 * chunk borders and slime chunks. All of it is just drawing, nothing is sent to the server.
 */
public final class WorldOverlays {
	private WorldOverlays() {}

	/* ---------------- 3D line helper (cut into short pieces so lines behind you don't vanish) ---------------- */

	static void line3(GuiGraphicsExtractor g, Vec3 a, Vec3 b, float partial, int w, int h, float thick, int argb) {
		double len = a.distanceTo(b);
		int n = Math.max(1, (int) Math.ceil(len / 1.5));
		float[] prev = Proj.project(a, partial, w, h);
		for (int i = 1; i <= n; i++) {
			Vec3 p = a.lerp(b, i / (double) n);
			float[] cur = Proj.project(p, partial, w, h);
			if (prev != null && cur != null) Compat.line(g, prev[0], prev[1], cur[0], cur[1], thick, argb);
			prev = cur;
		}
	}

	static void box(GuiGraphicsExtractor g, AABB b, float partial, int w, int h, float thick, int argb, boolean corners) {
		Vec3[] c = new Vec3[8];
		int i = 0;
		for (int xi = 0; xi < 2; xi++) for (int yi = 0; yi < 2; yi++) for (int zi = 0; zi < 2; zi++)
			c[i++] = new Vec3(xi == 0 ? b.minX : b.maxX, yi == 0 ? b.minY : b.maxY, zi == 0 ? b.minZ : b.maxZ);
		int[][] edges = {{0, 1}, {2, 3}, {4, 5}, {6, 7}, {0, 2}, {1, 3}, {4, 6}, {5, 7}, {0, 4}, {1, 5}, {2, 6}, {3, 7}};
		for (int[] e : edges) {
			if (!corners) {
				line3(g, c[e[0]], c[e[1]], partial, w, h, thick, argb);
				continue;
			}
			double len = c[e[0]].distanceTo(c[e[1]]), k = Math.min(0.3, len / 3) / Math.max(0.001, len);
			line3(g, c[e[0]], c[e[0]].lerp(c[e[1]], k), partial, w, h, thick, argb);
			line3(g, c[e[1]], c[e[1]].lerp(c[e[0]], k), partial, w, h, thick, argb);
		}
	}

	/* ---------------- block outline ---------------- */

	public static final String[] OUTLINE_STYLES = {"Thin", "Thick", "Corners", "Glow"};
	public static final String[] OUTLINE_COLORS = {"Black", "White", "Accent", "Rainbow", "Mint", "Pink"};

	public static boolean outlineOn() {
		return Modules.on("block_outline");
	}

	private static int outlineColor() {
		return switch (Modules.opt("outline.color")) {
			case 0 -> 0x000000;
			case 1 -> 0xFFFFFF;
			case 3 -> java.awt.Color.HSBtoRGB((System.currentTimeMillis() % 4000) / 4000f, 0.7f, 1f) & 0xFFFFFF;
			case 4 -> 0x5CFFB8;
			case 5 -> 0xD0D0D0;
			default -> Ui.accent();
		};
	}

	private static void outline(GuiGraphicsExtractor g, Minecraft mc, int w, int h, float partial) {
		HitResult hr = mc.hitResult;
		if (!(hr instanceof BlockHitResult bh) || hr.getType() != HitResult.Type.BLOCK) return;
		BlockPos pos = bh.getBlockPos();
		BlockState st = mc.level.getBlockState(pos);
		if (st.isAir()) return;
		VoxelShape shape = st.getShape(mc.level, pos);
		if (shape.isEmpty()) return;
		int style = Modules.opt("outline.style"), col = outlineColor();
		List<AABB> boxes = shape.toAabbs();
		for (AABB b : boxes) {
			AABB wb = b.move(pos.getX(), pos.getY(), pos.getZ()).inflate(0.004);
			if (style == 3) box(g, wb, partial, w, h, 3f, Anim.argb(col, 0.18f), false);
			box(g, wb, partial, w, h, style == 1 || style == 3 ? 1.6f : 0.9f, Anim.argb(col, 0.9f), style == 2);
		}
	}

	/* ---------------- light levels ---------------- */

	private record Spot(BlockPos pos, int block) {}

	private static List<Spot> spots = new ArrayList<>();
	private static int lightTick = 0;

	private static void scanLight(Minecraft mc) {
		List<Spot> out = new ArrayList<>();
		BlockPos c = mc.player.blockPosition();
		int r = new int[]{8, 12, 16}[Math.max(0, Math.min(2, Modules.opt("light.range")))];
		boolean all = Modules.opt("light.show") == 1;
		for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) for (int dy = -4; dy <= 3; dy++) {
			BlockPos p = c.offset(dx, dy, dz);
			BlockState at = mc.level.getBlockState(p);
			if (!at.isAir() && at.canOcclude()) continue;
			if (!at.getFluidState().isEmpty()) continue;
			BlockPos below = p.below();
			if (!mc.level.getBlockState(below).isFaceSturdy(mc.level, below, Direction.UP)) continue;
			int bl = mc.level.getBrightness(LightLayer.BLOCK, p);
			if (!all && bl > 0) continue;
			out.add(new Spot(p, bl));
			if (out.size() > 1500) break;
		}
		spots = out;
	}

	private static void light(GuiGraphicsExtractor g, Minecraft mc, int w, int h, float partial) {
		if (++lightTick % 10 == 1) scanLight(mc);
		Vec3 eye = Proj.cameraPos(partial);
		for (Spot s : spots) {
			Vec3 at = new Vec3(s.pos.getX() + 0.5, s.pos.getY() + 0.05, s.pos.getZ() + 0.5);
			double d = at.distanceTo(eye);
			if (d > 24) continue;
			float[] p = Proj.project(at, partial, w, h);
			if (p == null) continue;
			int col = s.block == 0 ? 0xFF4D5E : s.block < 8 ? 0xFFC93D : 0x6EE7A0;
			float sc = (float) Math.max(0.4, Math.min(1.4, 5 / p[2]));
			g.pose().pushMatrix();
			g.pose().translate(p[0], p[1]);
			g.pose().scale(sc, sc);
			String t = String.valueOf(s.block);
			Ui.textShadow(g, t, -Ui.width(t) / 2, -4, Anim.argb(col, (float) Math.max(0.35, 1 - d / 26)));
			g.pose().popMatrix();
		}
	}

	/* ---------------- chunk borders + slime chunks ---------------- */

	private static void chunks(GuiGraphicsExtractor g, Minecraft mc, int w, int h, float partial, boolean borders, boolean slime) {
		int cx = mc.player.getBlockX() >> 4, cz = mc.player.getBlockZ() >> 4;
		double py = mc.player.getY();
		int y0 = (int) Math.floor(py) - 6, y1 = (int) Math.floor(py) + 10;
		Long seed = slime ? Seeds.seed() : null;
		int acc = Ui.accent();
		for (int ox = -1; ox <= 1; ox++) for (int oz = -1; oz <= 1; oz++) {
			int x = (cx + ox) * 16, z = (cz + oz) * 16;
			boolean mine = ox == 0 && oz == 0;
			boolean isSlime = seed != null && Seeds.slimeChunk(seed, cx + ox, cz + oz);
			if (isSlime) {
				// green floor grid at your feet
				double fy = Math.floor(py) + 0.02;
				int col = Anim.argb(0x6BFF7A, mine ? 0.65f : 0.35f);
				for (int i = 0; i <= 16; i += 4) {
					line3(g, new Vec3(x + i, fy, z), new Vec3(x + i, fy, z + 16), partial, w, h, 1f, col);
					line3(g, new Vec3(x, fy, z + i), new Vec3(x + 16, fy, z + i), partial, w, h, 1f, col);
				}
			}
			if (!borders) continue;
			int col = Anim.argb(mine ? acc : Ui.INK, mine ? 0.85f : 0.3f);
			for (int[] c : new int[][]{{0, 0}, {16, 0}, {0, 16}, {16, 16}}) line3(g, new Vec3(x + c[0], y0, z + c[1]), new Vec3(x + c[0], y1, z + c[1]), partial, w, h, mine ? 1.4f : 1f, col);
			if (mine) {
				int faint = Anim.argb(acc, 0.28f);
				for (int y = y0; y <= y1; y += 2) {
					line3(g, new Vec3(x, y, z), new Vec3(x + 16, y, z), partial, w, h, 0.8f, faint);
					line3(g, new Vec3(x, y, z + 16), new Vec3(x + 16, y, z + 16), partial, w, h, 0.8f, faint);
					line3(g, new Vec3(x, y, z), new Vec3(x, y, z + 16), partial, w, h, 0.8f, faint);
					line3(g, new Vec3(x + 16, y, z), new Vec3(x + 16, y, z + 16), partial, w, h, 0.8f, faint);
				}
			}
		}
	}

	public static void render(GuiGraphicsExtractor g, Minecraft mc, int w, int h, float partial) {
		if (mc.level == null || mc.player == null) return;
		if (outlineOn()) outline(g, mc, w, h, partial);
		if (Modules.on("light_overlay") && Extras.lightShown) light(g, mc, w, h, partial);
		boolean borders = Modules.on("chunk_borders") && Extras.chunksShown, slime = Modules.on("slime_chunks") && Extras.slimeShown;
		if (borders || slime) chunks(g, mc, w, h, partial, borders, slime);
	}
}
