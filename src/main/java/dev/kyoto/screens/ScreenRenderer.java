package dev.kyoto.screens;

import dev.kyoto.module.Modules;
import dev.kyoto.util.Anim;
import dev.kyoto.util.Compat;
import dev.kyoto.util.Proj;
import dev.kyoto.util.Ui;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix3x2f;

/**
 * Draws video screens in the world. The picture is split into a grid of tiles and each tile is drawn
 * with its own skew (affine) transform, which gives a correct-looking perspective at any angle.
 * Screens hide when a wall is in the way.
 */
public final class ScreenRenderer {
	private ScreenRenderer() {}

	private static final int NX = 16, NY = 9;

	public static void render(GuiGraphicsExtractor g, Minecraft mc, int w, int h, float partial) {
		if (mc.player == null) return;
		if (Screens.isPlacing()) placingHint(g, w, h);
		// normally screens are drawn in the world itself (WorldScreens); this HUD version is only a fallback
		if (WorldScreens.drawing()) return;
		if (!Modules.on("video_screens") || Screens.SCREENS.isEmpty()) return;
		String dim = Compat.dimensionId();
		Vec3 cam = Proj.cameraPos(partial);
		for (Screens.Screen s : Screens.SCREENS) {
			if (!s.dim.equals(dim) || (!s.mine && !Modules.on("see_others_screens"))) continue;
			if (s.center().distanceTo(cam) > 64) continue;
			// only the front side shows the picture
			boolean front = cam.subtract(s.center()).dot(s.normal()) > 0;
			if (!visible(s, cam)) continue;
			draw(g, s, front, partial, w, h);
		}
	}

	private static void placingHint(GuiGraphicsExtractor g, int w, int h) {
		String t = "Right-click: place   ·   Scroll: size   ·   Left-click: cancel";
		int tw = Ui.width(t);
		int y = h - 74;
		Ui.round(g, w / 2 - tw / 2 - 10, y - 6, w / 2 + tw / 2 + 10, y + 12, 9, Anim.argb(0x0C0C0E, 0.85f));
		Ui.text(g, t, w / 2 - tw / 2, y, Anim.argb(0xE8EAEE, 1f));
	}

	private static boolean visible(Screens.Screen s, Vec3 cam) {
		Vec3 c = s.center(), r = s.right();
		Vec3 n = s.normal().scale(0.06);
		Vec3[] pts = {c, c.add(r.scale(s.w * 0.4)).add(0, s.h * 0.4, 0), c.add(r.scale(-s.w * 0.4)).add(0, s.h * 0.4, 0),
				c.add(r.scale(s.w * 0.4)).add(0, -s.h * 0.4, 0), c.add(r.scale(-s.w * 0.4)).add(0, -s.h * 0.4, 0)};
		for (Vec3 p : pts) {
			Vec3 toward = cam.subtract(p).dot(s.normal()) > 0 ? p.add(n) : p.subtract(n);
			if (!Compat.blockedFromCamera(cam, toward)) return true;
		}
		return false;
	}

	/** Point on the screen: u,v in 0..1 (u left->right, v top->bottom) as seen from the front. */
	private static Vec3 at(Screens.Screen s, double u, double v) {
		return s.center().add(s.right().scale((u - 0.5) * s.w)).add(0, (0.5 - v) * s.h, 0);
	}

	private static void draw(GuiGraphicsExtractor g, Screens.Screen s, boolean front, float partial, int w, int h) {
		float[][][] p = new float[NX + 1][NY + 1][];
		for (int i = 0; i <= NX; i++) for (int j = 0; j <= NY; j++) {
			double u = i / (double) NX;
			p[i][j] = Proj.project(at(s, front ? u : 1 - u, j / (double) NY), partial, w, h);
			if (p[i][j] == null) return; // partly behind the camera - skip this frame
		}
		VideoStream v = Screens.stream(s);
		Identifier tex = front && v != null ? v.texture() : null;
		int tw = v != null ? v.w : 640, th = v != null ? v.h : 360;
		float tileW = tw / (float) NX, tileH = th / (float) NY;
		for (int i = 0; i < NX; i++) for (int j = 0; j < NY; j++) {
			float[] a = p[i][j], b = p[i + 1][j], c = p[i][j + 1];
			int u0 = Math.round(i * tileW), v0 = Math.round(j * tileH);
			int uw = Math.round((i + 1) * tileW) - u0, vh = Math.round((j + 1) * tileH) - v0;
			g.pose().pushMatrix();
			g.pose().mul(new Matrix3x2f((b[0] - a[0]) / uw, (b[1] - a[1]) / uw, (c[0] - a[0]) / vh, (c[1] - a[1]) / vh, a[0], a[1]));
			if (tex != null) {
				// 1px overlap hides hairline seams between tiles
				Compat.blitRegion(g, tex, 0, 0, uw + 1, vh + 1, u0, v0, Math.min(uw + 1, tw - u0), Math.min(vh + 1, th - v0), tw, th, 0xFFFFFFFF);
			} else {
				g.fill(0, 0, uw + 1, vh + 1, front ? 0xFF0B0B0E : 0xFF1A1A1E);
			}
			g.pose().popMatrix();
		}
		// bezel
		float[] tl = p[0][0], tr = p[NX][0], bl = p[0][NY], br = p[NX][NY];
		int bez = Anim.argb(0x1E1F24, 1f);
		Compat.line(g, tl[0], tl[1], tr[0], tr[1], 2f, bez);
		Compat.line(g, bl[0], bl[1], br[0], br[1], 2f, bez);
		Compat.line(g, tl[0], tl[1], bl[0], bl[1], 2f, bez);
		Compat.line(g, tr[0], tr[1], br[0], br[1], 2f, bez);
		if (!front) return;
		// status text (loading / ended / paused) in the middle
		String msg = null;
		if (s.url.isBlank()) msg = "No video yet";
		else if (s.paused) msg = "Paused";
		else if (v != null && !v.state.isEmpty()) msg = v.state;
		if (msg != null) {
			float[] mid = Proj.project(at(s, 0.5, 0.5), partial, w, h);
			if (mid != null) {
				float scale = (float) Math.max(0.5, Math.min(2.5, s.h * 6 / mid[2]));
				g.pose().pushMatrix();
				g.pose().translate(mid[0], mid[1]);
				g.pose().scale(scale, scale);
				int mw = Ui.width(msg);
				Ui.round(g, -mw / 2 - 6, -7, mw / 2 + 6, 7, 6, Anim.argb(0x000000, 0.6f));
				Ui.text(g, msg, -mw / 2, -4, Anim.argb(0xE6E8EC, 1f));
				g.pose().popMatrix();
			}
		}
		if (!s.mine) {
			float[] lab = Proj.project(at(s, 0.02, 0.04), partial, w, h);
			if (lab != null) {
				float scale = (float) Math.max(0.4, Math.min(1.5, s.h * 3 / lab[2]));
				g.pose().pushMatrix();
				g.pose().translate(lab[0], lab[1]);
				g.pose().scale(scale, scale);
				Ui.small(g, s.owner + "'s screen", 0, 0, Anim.argb(0xFFFFFF, 0.6f));
				g.pose().popMatrix();
			}
		}
	}
}
