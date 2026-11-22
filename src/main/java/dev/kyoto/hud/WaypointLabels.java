package dev.kyoto.hud;

import dev.kyoto.feature.Waypoints;
import dev.kyoto.util.Anim;
import dev.kyoto.util.Proj;
import dev.kyoto.util.Ui;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;

/**
 * A tall beam + floating label for every waypoint in your dimension, drawn on the HUD so it's
 * visible from ANY distance and through terrain: "Base · 1,204m · by hef".
 */
public final class WaypointLabels {
	private WaypointLabels() {}

	public static void render(GuiGraphicsExtractor g, int w, int h, float partial) {
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer p = mc.player;
		if (p == null) return;
		Waypoints.Waypoint tracked = Waypoints.tracked();
		for (Waypoints.Waypoint wp : Waypoints.inCurrentDimension()) {
			double dist = wp.distanceTo(p.getX(), p.getY(), p.getZ());
			if (dist < 3) continue;
			// beam: a dotted line from the waypoint up into the sky. Each piece is a small square,
			// so it can never blow up into a giant rectangle when you look straight up.
			float fade = (float) Math.min(1, dist / 12.0);
			int thick = dist < 60 ? 2 : 1;
			float[] prev = null;
			for (int i = 0; i <= 24; i++) {
				float[] s = Proj.project(new Vec3(wp.x + 0.5, wp.y + i * 10, wp.z + 0.5), partial, w, h);
				if (s == null || s[2] < 2f) {
					prev = null;
					continue;
				}
				if (prev != null) {
					float dx = s[0] - prev[0], dy = s[1] - prev[1];
					float len = (float) Math.sqrt(dx * dx + dy * dy);
					if (len < 600) {
						int steps = Math.max(1, Math.min(120, Math.round(len / 2f)));
						int col = Anim.argb(wp.color, (1 - i / 25f) * 0.6f * fade);
						for (int k = 0; k < steps; k++) {
							float t = k / (float) steps;
							int x = Math.round(prev[0] + dx * t), y = Math.round(prev[1] + dy * t);
							if (x < -4 || y < -4 || x > w + 4 || y > h + 4) continue;
							g.fill(x, y, x + thick, y + thick, col);
						}
					}
				}
				prev = s;
			}
			// label just above the waypoint
			float[] s = Proj.project(new Vec3(wp.x + 0.5, wp.y + 2.2, wp.z + 0.5), partial, w, h);
			if (s == null || s[2] < 1f) continue;
			int sx = Math.round(s[0]), sy = Math.round(s[1]);
			if (sx < -100 || sx > w + 100 || sy < -40 || sy > h + 40) continue;
			String d = dist >= 1000 ? String.format("%.1fkm", dist / 1000) : Math.round(dist) + "m";
			String line1 = wp.name + "  ·  " + d;
			String line2 = wp.owner == null || wp.owner.isBlank() ? "" : "by " + wp.owner;
			int tw = Math.max(Ui.width(line1), Ui.width(line2)) + 12;
			int th = line2.isEmpty() ? 13 : 22;
			float a = wp == tracked ? 1f : 0.85f;
			// sprite-like: bigger when you're close, smaller far away - and you can walk right past it
			float scale = (float) Math.max(0.6, Math.min(1.7, 16.0 / Math.max(1.0, s[2])));
			if (dist < 6) a *= (float) Math.max(0.25, (dist - 3) / 3.0);
			g.pose().pushMatrix();
			g.pose().translate(sx, sy);
			g.pose().scale(scale, scale);
			Ui.glass(g, -tw / 2, -th, tw / 2, 0, 5, a);
			Ui.round(g, -tw / 2 + 2, -th + 3, -tw / 2 + 4, -3, 1, Anim.argb(wp.color, a));
			Ui.text(g, line1, -tw / 2 + 7, -th + 3, Anim.argb(wp.color, a));
			if (!line2.isEmpty()) Ui.text(g, line2, -tw / 2 + 7, -th + 12, Anim.argb(Ui.MUTED, a));
			g.pose().popMatrix();
		}
	}
}
