package dev.kyoto.ui;

import dev.kyoto.util.Anim;
import dev.kyoto.util.Ui;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.contents.TranslatableContents;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * The sakura title screen layout. Singleplayer, Multiplayer and the rest become a column of glass pills under the blossom;
 * Kyoto's own buttons (Hub, Friends, Profile...) sit in a macOS-style dock at the bottom that magnifies under the mouse.
 * The real buttons are just moved and resized, so clicks, keyboard focus and other mods keep working.
 */
public final class TitleLayout {
	private TitleLayout() {}

	private static final String[][] MAIN = {{"menu.singleplayer", "Singleplayer", "survival"}, {"menu.multiplayer", "Multiplayer", "server"}, {"menu.online", "Realms", "world"}, {"modmenu.title", "Mods", "mods"}};
	private static final String[][] PAIR = {{"menu.options", "Options", "gear"}, {"menu.quit", "Quit", "leave"}};
	private static final String[][] DOCK = {{"Kyoto", "client"}, {"Hub", "hub"}, {"Friends", "friends"}, {"Profile", "profile"}, {"Cosmetics", "cosmetics"},
			{"Mod Finder", "finder"}, {"Kyoto Mods", "mods"}, {"Help", "help"}, {"Discord", "link"}};

	private static final Map<AbstractWidget, Float> HOVER = new WeakHashMap<>();
	private static final Map<AbstractWidget, Float> MAG = new WeakHashMap<>();

	private record Item(AbstractWidget w, String label, String icon) {}

	private static String key(AbstractWidget w) {
		return w.getMessage().getContents() instanceof TranslatableContents tc ? tc.getKey() : "";
	}

	private static Item find(List<AbstractWidget> all, List<Item> taken, String k, String label, String icon) {
		for (AbstractWidget x : all) {
			if (!(x instanceof Button)) continue;
			boolean match = k.equals("modmenu.title") ? key(x).startsWith("modmenu") && x.getWidth() > 24 : key(x).equals(k);
			if (match && taken.stream().noneMatch(q -> q.w == x)) return new Item(x, label, icon);
		}
		return null;
	}

	/** Lays out + draws every widget. Returns false if this screen doesn't have the usual buttons. */
	public static boolean draw(List<AbstractWidget> all, GuiGraphicsExtractor g, int w, int h, int mx, int my, float partial, float intro) {
		List<Item> main = new ArrayList<>(), pair = new ArrayList<>(), dock = new ArrayList<>();
		for (String[] b : MAIN) { Item it = find(all, main, b[0], b[1], b[2]); if (it != null) main.add(it); }
		for (String[] b : PAIR) { Item it = find(all, pair, b[0], b[1], b[2]); if (it != null) pair.add(it); }
		for (String[] s : DOCK)
			for (AbstractWidget x : all)
				if (x instanceof Button && x.getMessage().getString().equals(s[0])) {
					dock.add(new Item(x, s[0], s[1]));
					break;
				}
		if (main.size() < 2) return false;
		List<AbstractWidget> rest = new ArrayList<>(all);
		for (List<Item> l : List.of(main, pair, dock)) for (Item it : l) rest.remove(it.w);

		// ---- main column: left aligned under the logo, the sakura scene keeps the right side ----
		int bw = Math.min(200, w / 3), bh = 22, gap = 6;
		int total = main.size() * (bh + gap) + (pair.isEmpty() ? 0 : bh + gap);
		int lx = TitleTheme.leftX(w);
		int y = Math.max(TitleTheme.contentTop(h), (h - total) / 2 + 20);
		int i = 0;
		for (Item it : main) {
			float a = stagger(intro, i++);
			place(it.w, lx, y, bw, bh);
			pill(g, it, lx, y, lx + bw, y + bh, mx, my, a, true);
			y += bh + gap;
		}
		if (!pair.isEmpty()) {
			int half = (bw - gap) / 2;
			for (int k = 0; k < pair.size(); k++) {
				Item it = pair.get(k);
				int x1 = pair.size() == 1 ? lx : lx + k * (half + gap);
				int x2 = pair.size() == 1 ? lx + bw : x1 + half;
				float a = stagger(intro, i++);
				place(it.w, x1, y, x2 - x1, bh);
				pill(g, it, x1, y, x2, y + bh, mx, my, a, true);
			}
		}
		// ---- everything else: small icon buttons + text pills along the top right, copyright bottom right ----
		int rx = w - 10, ry = 8;
		List<AbstractWidget> other = new ArrayList<>();
		for (AbstractWidget x : rest) {
			if (!x.visible) continue;
			String msg = x.getMessage().getString();
			if (msg.contains("Mojang") || msg.contains("Copyright")) {
				int cw = Ui.widthSmall(msg) + 4;
				place(x, w - cw - 8, h - 12, cw, 10);
				boolean hov = mx >= x.getX() && my >= x.getY() && mx < x.getX() + cw && my < x.getY() + 10;
				Ui.small(g, msg, w - cw - 6, h - 10, Anim.argb(hov ? Ui.INK : Ui.DIM, 0.8f * intro));
				continue;
			}
			if (x.getWidth() <= 24 || msg.isBlank()) {
				int s2 = 20;
				rx -= s2;
				place(x, rx, ry, s2, s2);
				float hv = hover(x, mx, my);
				Ui.glass(g, rx, ry, rx + s2, ry + s2, s2 / 2, intro);
				String ic = iconFor(x);
				if (ic != null) Ui.iconTex(g, ic, rx + 6, ry + 6, 8, Anim.argb(Ui.mix(Ui.MUTED, Ui.accent(), hv), intro));
				else {
					g.enableScissor(rx + 2, ry + 2, rx + s2 - 2, ry + s2 - 2);
					x.extractRenderState(g, mx, my, partial);
					g.disableScissor();
				}
				rx -= 6;
				continue;
			}
			int pw2 = Math.min(140, Ui.width(msg) + 24);
			rx -= pw2;
			place(x, rx, ry, pw2, 20);
			other.add(x);
			rx -= 6;
		}
		Buttons.draw(other, g, mx, my, partial, intro);

		// ---- dock ----
		if (!dock.isEmpty()) {
			float da = Anim.easeOutCubic(Anim.clamp01(intro * 1.4f - 0.45f));
			int base = 22, pad = 7, n = dock.size();
			float cx = w / 2f;
			float[] size = new float[n];
			float sum = 0;
			boolean overDock = my > h - 60 && da > 0.5f;
			for (int k = 0; k < n; k++) {
				AbstractWidget wd = dock.get(k).w;
				float target = 0;
				if (overDock) {
					float c = wd.getX() + wd.getWidth() / 2f;
					float d = Math.abs(mx - c) / (base * 2.6f);
					target = Math.max(0, 1 - d * d);
				}
				float m = MAG.getOrDefault(wd, 0f);
				m += (target - m) * (Ui.animSpeed() <= 0 ? 1f : 0.25f);
				MAG.put(wd, m);
				size[k] = base * (1 + 0.55f * m);
				sum += size[k];
			}
			float dw = sum + pad * (n + 1);
			int dx1 = Math.round(cx - dw / 2), dy2 = h - 8 + Math.round((1 - da) * 30), dy1 = dy2 - base - pad * 2;
			Ui.glass(g, dx1, dy1, Math.round(cx + dw / 2), dy2, 14, da);
			float x = dx1 + pad;
			for (int k = 0; k < n; k++) {
				Item it = dock.get(k);
				float s = size[k];
				int tx = Math.round(x), ty = Math.round(dy2 - pad - s);
				place(it.w, tx, ty, Math.round(s), Math.round(s));
				float hv = hover(it.w, mx, my);
				int r = Math.round(s * 0.5f);
				Ui.round(g, tx, ty + 1, tx + Math.round(s), ty + Math.round(s) + 1, r, Anim.argb(0x9C3D62, 0.12f * da));
				Ui.round(g, tx, ty, tx + Math.round(s), ty + Math.round(s), r, Anim.argb(hv > 0.01f ? Ui.mix(Ui.GLASS_LIGHT, Ui.accent(), hv) : Ui.GLASS_LIGHT, 0.95f * da));
				Ui.ring(g, tx, ty, tx + Math.round(s), ty + Math.round(s), r, 1, Anim.argb(0xFFFFFF, (Ui.DARK ? 0.15f : 0.9f) * da));
				int ic = Math.round(s * 0.5f);
				Ui.iconTex(g, it.icon, Math.round(tx + s / 2 - ic / 2f), Math.round(ty + s / 2 - ic / 2f), ic, Anim.argb(Ui.mix(Ui.accent(), 0xFFFFFF, hv), da));
				if (hv > 0.02f) {
					int lw = Ui.width(it.label) + 14;
					int tlx = Math.round(tx + s / 2 - lw / 2f), ly = Math.round(ty - 20 - 4 * hv);
					Ui.glass(g, tlx, ly, tlx + lw, ly + 15, 7, hv * da);
					Ui.text(g, it.label, tlx + 7, ly + 4, Anim.argb(Ui.INK, hv * da));
				}
				x += s + pad;
			}
		}
		return true;
	}

	private static String iconFor(AbstractWidget x) {
		String k = key(x);
		if (k.contains("language")) return "world";
		if (k.contains("accessibility")) return "eye";
		if (k.contains("modmenu")) return "mods";
		return null;
	}

	private static float stagger(float intro, int i) {
		return Anim.easeOutCubic(Anim.clamp01(intro * 1.5f - 0.15f - i * 0.07f));
	}

	private static void pill(GuiGraphicsExtractor g, Item it, int x1, int y1, int x2, int y2, int mx, int my, float a, boolean icon) {
		if (a <= 0.01f) return;
		float hv = hover(it.w, mx, my);
		int lift = Math.round((1 - a) * 10);
		y1 += lift;
		y2 += lift;
		Ui.glass(g, x1, y1, x2, y2, (y2 - y1) / 2, a * (it.w.active ? 1f : 0.5f));
		if (hv > 0.01f) Ui.round(g, x1, y1, x2, y2, (y2 - y1) / 2, Anim.argb(Ui.accent(), 0.9f * hv * a));
		int col = Ui.mix(it.w.active ? Ui.INK : Ui.DIM, 0xFFFFFF, hv);
		int tw = Ui.width(it.label);
		int iconW = icon ? 13 : 0;
		int tx = (x1 + x2) / 2 - (tw + iconW) / 2;
		if (icon) Ui.iconTex(g, it.icon, tx, (y1 + y2) / 2 - 4, 9, Anim.argb(Ui.mix(Ui.accent(), 0xFFFFFF, hv), a));
		Ui.text(g, it.label, tx + iconW, (y1 + y2) / 2 - 4, Anim.argb(col, a));
	}

	private static void place(AbstractWidget w, int x, int y, int ww, int hh) {
		w.setX(x);
		w.setY(y);
		w.setWidth(ww);
		w.setHeight(hh);
	}

	private static float hover(AbstractWidget w, int mx, int my) {
		boolean in = w.active && mx >= w.getX() && my >= w.getY() && mx < w.getX() + w.getWidth() && my < w.getY() + w.getHeight();
		float h = HOVER.getOrDefault(w, 0f);
		h += ((in ? 1f : 0f) - h) * (Ui.animSpeed() <= 0 ? 1f : 0.2f);
		HOVER.put(w, h);
		return h;
	}
}
