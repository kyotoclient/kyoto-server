package dev.kyoto.ui;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.kyoto.util.Anim;
import dev.kyoto.util.Compat;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * The launcher's sakura tree on the title screen: cut into its branches, each swaying around where it grows from,
 * with slow breezes and gusts. Same art and wind as the Kyoto Launcher.
 */
public final class SakuraTree {
	private SakuraTree() {}

	private static final class Layer {
		int id, parent, x, y, w, h, ox, oy, depth;
		boolean back;
		Identifier tex;
		float ph, f, k;
		final List<Layer> kids = new ArrayList<>();
	}

	private static List<Layer> roots;
	private static int W = 1920, H = 1080;
	private static float sway = 0, gust = 0, gustT = 4;
	private static long last = 0;

	private static void load() {
		roots = new ArrayList<>();
		try (var in = SakuraTree.class.getResourceAsStream("/assets/kyoto/tree_layers.json")) {
			if (in == null) return;
			JsonObject o = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
			W = o.get("W").getAsInt();
			H = o.get("H").getAsInt();
			java.util.Map<Integer, Layer> byId = new java.util.HashMap<>();
			List<Layer> all = new ArrayList<>();
			java.util.Random r = new java.util.Random(7);
			for (var e : o.getAsJsonArray("layers")) {
				JsonObject m = e.getAsJsonObject();
				Layer l = new Layer();
				l.id = m.get("id").getAsInt();
				l.parent = m.get("parent").getAsInt();
				l.x = m.get("x").getAsInt(); l.y = m.get("y").getAsInt(); l.w = m.get("w").getAsInt(); l.h = m.get("h").getAsInt();
				l.ox = m.get("ox").getAsInt(); l.oy = m.get("oy").getAsInt(); l.depth = m.get("depth").getAsInt();
				l.back = m.get("back").getAsBoolean();
				l.tex = Identifier.fromNamespaceAndPath("kyoto", "textures/gui/tree/" + m.get("png").getAsString());
				l.ph = r.nextFloat() * 6.28f; l.f = 0.7f + r.nextFloat() * 0.6f; l.k = 0.8f + r.nextFloat() * 0.5f;
				byId.put(l.id, l);
				all.add(l);
			}
			all.sort((a, b) -> a.back != b.back ? (a.back ? -1 : 1) : Integer.compare(a.depth, b.depth));
			for (Layer l : all) {
				Layer p = l.parent >= 0 ? byId.get(l.parent) : null;
				if (p != null && !l.back) p.kids.add(l);
				else roots.add(l);
			}
		} catch (Throwable ignored) {
		}
	}

	/** Draws the tree with its trunk near the right edge, in a box h tall (bottom-aligned). */
	public static void draw(GuiGraphicsExtractor g, int sw, int sh, float alpha) {
		if (roots == null) load();
		if (roots.isEmpty()) return;
		long now = System.currentTimeMillis();
		float dt = last == 0 ? 0.016f : Math.min(0.05f, (now - last) / 1000f);
		last = now;
		gustT -= dt;
		if (gustT <= 0) { gust = 0.5f + (float) Math.random() * 0.6f; gustT = 6 + (float) Math.random() * 9; }
		gust *= (float) Math.pow(0.35, dt);
		float t = now / 1000f;
		float target = (float) (Math.sin(t * 0.45) * 0.35 + Math.sin(t * 0.17 + 1) * 0.25) + gust * 3.2f;
		sway += (target - sway) * Math.min(1f, dt * 1.6f);
		float bh = sh * 1.0f, scale = bh / H, bw = W * scale;
		float left = sw * 0.92f - bw * 0.78f, top = sh - bh + sh * 0.03f;
		g.pose().pushMatrix();
		g.pose().translate(left, top);
		g.pose().scale(scale, scale);
		for (Layer l : roots) layer(g, l, t, alpha);
		g.pose().popMatrix();
	}

	public static float gust() {
		return gust;
	}

	private static void layer(GuiGraphicsExtractor g, Layer l, float t, float alpha) {
		float amp = l.back ? 0.5f : new float[]{0.18f, 0.9f, 1.6f}[Math.min(2, l.depth)];
		float flutter = (float) Math.sin(t * (1.3f + l.depth * 0.7f) * l.f + l.ph) * (0.25f + 0.35f * gust) * amp;
		float deg = sway * amp * l.k + flutter;
		g.pose().pushMatrix();
		g.pose().translate(l.ox, l.oy);
		g.pose().rotate((float) Math.toRadians(deg));
		g.pose().translate(-l.ox, -l.oy);
		Compat.blitTint(g, l.tex, l.x, l.y, l.w, l.h, Anim.argb(0xFFFFFF, alpha * (l.back ? 0.9f : 1f)));
		for (Layer k : l.kids) layer(g, k, t, alpha);
		g.pose().popMatrix();
	}
}
