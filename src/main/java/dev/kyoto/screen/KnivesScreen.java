package dev.kyoto.screen;

import dev.kyoto.feature.Knives;
import dev.kyoto.hud.Notifications;
import dev.kyoto.ui.Sounds;
import dev.kyoto.util.Anim;
import dev.kyoto.util.Compat;
import dev.kyoto.util.Ui;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Knives: pick which item to change, the knife and its finish. The preview plays a CS2-style inspect:
 * the knife flips over, turns and a light sweeps along the blade. Apply writes the "Kyoto Knives" pack and reloads.
 */
public class KnivesScreen extends Screen {
	private final Screen parent;
	private String item = "diamond_sword";
	private int shape = 0, finish = 1;
	private long openedAt, inspectAt;
	private final Map<String, Float> anim = new HashMap<>();
	private int px, py, pw, ph;
	private boolean dirty = false;

	private record Hit(int x1, int y1, int x2, int y2, Runnable r) {}

	private final List<Hit> hits = new ArrayList<>();

	public KnivesScreen(Screen parent) {
		super(Component.literal("Knives"));
		this.parent = parent;
		loadPick();
	}

	private void loadPick() {
		String k = Knives.picks().get(item);
		if (k == null) return;
		String[] p = k.split("_");
		for (int i = 0; i < Knives.SHAPES.length; i++) if (Knives.SHAPES[i].equals(p[0])) shape = i;
		for (int i = 0; i < Knives.FINISHES.length; i++) if (Knives.FINISHES[i].equals(p[1])) finish = i;
	}

	@Override
	protected void init() {
		openedAt = inspectAt = System.currentTimeMillis();
	}

	private float a(String k, boolean on) {
		float v = anim.getOrDefault(k, on ? 1f : 0f);
		v += ((on ? 1f : 0f) - v) * 0.22f;
		anim.put(k, v);
		return v;
	}

	private float yawNow = 0;

	private static Identifier tex(int s, int f) {
		return Identifier.fromNamespaceAndPath("kyoto", "textures/gui/knives/" + Knives.SHAPES[s] + "_" + Knives.FINISHES[f] + ".png");
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mx, int my, float partial) {
		super.extractBackground(g, mx, my, partial);
		hits.clear();
		pw = Math.min(width - 20, 620);
		ph = Math.min(height - 20, 420);
		px = (width - pw) / 2;
		py = (height - ph) / 2;
		long now = System.currentTimeMillis();
		float al = Anim.easeOutCubic(Anim.clamp01((now - openedAt) / 300f));
		int acc = Ui.accent();
		g.fillGradient(-20, -20, width + 20, height + 20, Anim.argb(Ui.BG_TOP, 0.5f * al), Anim.argb(Ui.BG_BOTTOM, 0.65f * al));
		Ui.glass(g, px, py, px + pw, py + ph, 18, al);
		Ui.boldScaled(g, "Knives", px + 18, py + 14, 1.4f, Anim.argb(Ui.TEXT, al));
		Ui.small(g, "Knife skins for your swords. Only you see them, and nothing about how you fight changes.", px + 18, py + 31, Anim.argb(Ui.MUTED, al));
		boolean ch = Ui.inside(mx, my, px + pw - 28, py + 10, px + pw - 10, py + 28);
		Ui.iconTex(g, "close", px + pw - 23, py + 15, 8, Anim.argb(ch ? Ui.TEXT : Ui.MUTED, al));
		hits.add(new Hit(px + pw - 28, py + 10, px + pw - 10, py + 28, this::onClose));

		// ---- inspect preview ----
		int sx1 = px + 14, sy1 = py + 48, sx2 = px + Math.round(pw * 0.44f), sy2 = py + ph - 46;
		Ui.round(g, sx1, sy1, sx2, sy2, 14, Anim.argb(Ui.DARK ? 0x000000 : 0xFFFFFF, (Ui.DARK ? 0.2f : 0.35f) * al));
		Ui.ring(g, sx1, sy1, sx2, sy2, 14, 1, Anim.argb(0xFFFFFF, (Ui.DARK ? 0.1f : 0.85f) * al));
		int fc = Knives.FINISH_COLORS[finish];
		float cx = (sx1 + sx2) / 2f, cy = (sy1 + sy2) / 2f - 6;
		Ui.circle(g, cx, cy, Math.min(sx2 - sx1, sy2 - sy1) * 0.36f, Anim.argb(fc, 0.16f * al));
		Ui.circle(g, cx, cy, Math.min(sx2 - sx1, sy2 - sy1) * 0.22f, Anim.argb(fc, 0.12f * al));
		// a real 3D knife, turning slowly; click for its inspect animation (same one as in game)
		float look = Ui.inside(mx, my, sx1, sy1, sx2, sy2) ? (mx - cx) / ((sx2 - sx1) / 2f) * 40f : 0;
		yawNow += (look + (float) Math.sin(now / 2200.0) * 28f - yawNow) * 0.08f;
		g.enableScissor(sx1 + 2, sy1 + 2, sx2 - 2, sy2 - 2);
		dev.kyoto.knife.Viewmodel.preview(g, Knives.SHAPES[shape], Knives.FINISHES[finish], item, sx1, sy1 - 6, sx2 - sx1, sy2 - sy1 - 24, yawNow, al);
		g.disableScissor();
		String title = "★ " + Knives.SHAPE_NAMES[shape];
		Ui.bold(g, title, Math.round(cx - Ui.widthBold(title) / 2f), sy2 - 30, Anim.argb(Ui.TEXT, al));
		String sub = Knives.FINISH_NAMES[finish] + "  ·  on your " + Knives.niceItem(item);
		Ui.small(g, sub, cx - Ui.widthSmall(sub) / 2f, sy2 - 17, Anim.argb(Ui.MUTED, al));
		hits.add(new Hit(sx1, sy1, sx2, sy2, () -> { inspectAt = System.currentTimeMillis(); dev.kyoto.knife.Viewmodel.previewInspect(); Sounds.play(Sounds.TAB); }));
		Ui.small(g, "Click to inspect  ·  G in game", sx1 + 8, sy1 + 8, Anim.argb(Ui.DIM, al));

		// ---- 1. item ----
		int rx = sx2 + 14, ry = py + 48, rx2 = px + pw - 14;
		Ui.small(g, "1  WHICH ITEM", rx, ry, Anim.argb(Ui.DIM, al));
		ry += 10;
		int x = rx;
		for (String it : Knives.ITEMS) {
			String label = Knives.niceItem(it);
			int w = Ui.widthSmall(label) + 16;
			if (x + w > rx2) { x = rx; ry += 17; }
			boolean sel = it.equals(item), has = Knives.picks().containsKey(it);
			float s = a("it" + it, sel), h = a("ith" + it, Ui.inside(mx, my, x, ry, x + w, ry + 14));
			Ui.round(g, x, ry, x + w, ry + 14, 7, Anim.argb(sel ? acc : Ui.INK, (0.06f + 0.06f * h + 0.84f * s) * al));
			if (has && !sel) Ui.circle(g, x + w - 5, ry + 4, 2, Anim.argb(acc, al));
			Ui.small(g, label, x + 8, ry + 4, Anim.argb(Ui.mix(Ui.MUTED, 0xFFFFFF, s), al));
			hits.add(new Hit(x, ry, x + w, ry + 14, () -> { item = it; loadPick(); inspectAt = System.currentTimeMillis(); Sounds.play(Sounds.TAB); }));
			x += w + 4;
		}
		ry += 24;
		// ---- 2. knife ----
		Ui.small(g, "2  KNIFE", rx, ry, Anim.argb(Ui.DIM, al));
		ry += 10;
		int cols = 4, gap = 5, cw = (rx2 - rx - gap * (cols - 1)) / cols, chh = Math.min(54, cw);
		for (int i = 0; i < Knives.SHAPES.length; i++) {
			int kx = rx + (i % cols) * (cw + gap), ky = ry + (i / cols) * (chh + gap);
			boolean sel = i == shape, hov = Ui.inside(mx, my, kx, ky, kx + cw, ky + chh);
			float s = a("sh" + i, sel), h = a("shh" + i, hov);
			int lift = Math.round(h * 2);
			Ui.round(g, kx, ky - lift, kx + cw, ky + chh - lift, 10, Anim.argb(Ui.GLASS_LIGHT, ((Ui.DARK ? 0.05f : 0.4f) + 0.2f * h) * al));
			Ui.ring(g, kx, ky - lift, kx + cw, ky + chh - lift, 10, sel ? 1.5f : 1f, Anim.argb(Ui.mix(0xFFFFFF, acc, s), (Ui.DARK ? 0.12f + 0.6f * s : 0.75f + 0.25f * s) * al));
			int is = chh - 16;
			Compat.blitTint(g, tex(i, finish), kx + cw / 2 - is / 2, ky + 2 - lift, is, is, Anim.argb(0xFFFFFF, al));
			String nm = Ui.ellipsize(Knives.SHAPE_NAMES[i], Math.round((cw - 6) / 0.75f));
			Ui.small(g, nm, kx + cw / 2f - Ui.widthSmall(nm) / 2f, ky + chh - 11 - lift, Anim.argb(sel ? acc : Ui.MUTED, al));
			final int fi = i;
			hits.add(new Hit(kx, ky, kx + cw, ky + chh, () -> { shape = fi; dirty = true; inspectAt = System.currentTimeMillis(); Sounds.play(Sounds.TAB); }));
		}
		ry += 2 * (chh + gap) + 6;
		// ---- 3. finish ----
		Ui.small(g, "3  FINISH", rx, ry, Anim.argb(Ui.DIM, al));
		ry += 10;
		x = rx;
		for (int i = 0; i < Knives.FINISHES.length; i++) {
			if (i == 8) { x = rx; ry += 19; Ui.small(g, "CAMOS", rx, ry, Anim.argb(Ui.DIM, al)); ry += 10; }
			if (i == 14) { x = rx; ry += 19; Ui.small(g, "MOVING CAMOS", rx, ry, Anim.argb(Ui.DIM, al)); ry += 10; }
			String label = Knives.FINISH_NAMES[i];
			int w = Ui.widthSmall(label) + 22;
			if (x + w > rx2) { x = rx; ry += 17; }
			boolean sel = i == finish;
			float s = a("fi" + i, sel);
			Ui.round(g, x, ry, x + w, ry + 14, 7, Anim.argb(Ui.INK, (0.06f + 0.06f * s) * al));
			if (s > 0.01f) Ui.ring(g, x, ry, x + w, ry + 14, 7, 1.5f, Anim.argb(Knives.FINISH_COLORS[i], s * al));
			Ui.circle(g, x + 8, ry + 7, 4, Anim.argb(Knives.FINISH_COLORS[i], al));
			Ui.small(g, label, x + 15, ry + 4, Anim.argb(Ui.mix(Ui.MUTED, Ui.INK, s), al));
			final int fi = i;
			hits.add(new Hit(x, ry, x + w, ry + 14, () -> { finish = fi; dirty = true; inspectAt = System.currentTimeMillis(); Sounds.play(Sounds.TAB); }));
			x += w + 4;
		}
		// ---- buttons ----
		int by = py + ph - 34;
		boolean ah = Ui.inside(mx, my, rx2 - 110, by, rx2, by + 20);
		Ui.button(g, rx2 - 110, by, rx2, by + 20, "Apply", ah ? 1f : 0.85f, true, al);
		hits.add(new Hit(rx2 - 110, by, rx2, by + 20, this::apply));
		boolean has = Knives.picks().containsKey(item);
		if (has) {
			boolean rh = Ui.inside(mx, my, rx2 - 200, by, rx2 - 116, by + 20);
			Ui.button(g, rx2 - 200, by, rx2 - 116, by + 20, "Remove", rh ? 0.5f : 0.1f, true, al);
			hits.add(new Hit(rx2 - 200, by, rx2 - 116, by + 20, () -> {
				Knives.picks().remove(item);
				dev.kyoto.config.KyotoConfig.save();
				Knives.apply();
				Sounds.play(Sounds.OFF);
			}));
		}
		Ui.small(g, Knives.picks().size() + " item" + (Knives.picks().size() == 1 ? "" : "s") + " changed  ·  applying reloads textures for a moment", sx1, by + 7, Anim.argb(Ui.DIM, al));
	}

	private void apply() {
		Knives.picks().put(item, Knives.SHAPES[shape] + "_" + Knives.FINISHES[finish]);
		dev.kyoto.config.KyotoConfig.save();
		Sounds.play(Sounds.SUCCESS);
		Notifications.push("★ " + Knives.SHAPE_NAMES[shape] + " | " + Knives.FINISH_NAMES[finish], "On your " + Knives.niceItem(item), Notifications.GOOD, 3000, null, "sword");
		Knives.apply();
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent e, boolean dbl) {
		for (int i = hits.size() - 1; i >= 0; i--) {
			Hit h = hits.get(i);
			if (Ui.inside(e.x(), e.y(), h.x1, h.y1, h.x2, h.y2)) {
				h.r.run();
				return true;
			}
		}
		return super.mouseClicked(e, dbl);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void onClose() {
		Compat.openScreen(parent);
	}
}
