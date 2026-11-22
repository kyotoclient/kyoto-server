package dev.kyoto.screen;

import dev.kyoto.config.KyotoConfig;
import dev.kyoto.hud.HudLayout;
import dev.kyoto.hud.Notifications;
import dev.kyoto.module.Modules;
import dev.kyoto.module.Modules.Module;
import dev.kyoto.servers.Packs;
import dev.kyoto.util.Anim;
import dev.kyoto.util.Compat;
import dev.kyoto.util.Ui;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * HUD editor, made simple:
 *  - a list on the side with every HUD piece: a switch to show/hide it, click the name to select it
 *  - drag anything on screen to move it (snaps to edges + centre), scroll or use - / + to resize
 *  - the selected piece gets "Reset" and size buttons, arrow keys nudge it 1px (Shift = 10px)
 *  - Done saves; if you're on a server with a pack, the layout is kept for that server
 */
public class HudEditorScreen extends Screen {
	private final Screen parent;
	private String dragging = null, selected = null, hover = null;
	private double grabX, grabY;
	private float startDx, startDy;
	private boolean listOpen = true;
	private float listScroll = 0;
	private final long openedAt = System.currentTimeMillis();

	/** HUD piece -> the feature that turns it on/off */
	private static final Map<String, String> MODULE = new HashMap<>();

	static {
		MODULE.put("info_panel", "info_panel");
		MODULE.put("stats", "fps");
		MODULE.put("potions", "potions");
		MODULE.put("keystrokes", "keystrokes");
		MODULE.put("now_playing", "now_playing");
		MODULE.put("compass", "compass");
		MODULE.put("armor", "armor_hud");
		MODULE.put("pickup", "pickup_feed");
		MODULE.put("elytra", "elytra_gauge");
		MODULE.put("visualizer", "music_visualizer");
		MODULE.put("karaoke", "karaoke");
		MODULE.put("portal", "portal_linker");
		MODULE.put("durability", "durability_hud");
		MODULE.put("pack_money", "donut_money");
		MODULE.put("pack_combat", "donut_combat");
		MODULE.put("pack_bounty", "donut_bounty_board");
		MODULE.put("pack_orders", "donut_orders");
		MODULE.put("pack_team", "donut_team");
		MODULE.put("pack_game", "hypixel_beds");
		MODULE.put("pack_stats", "hypixel_stats");
		MODULE.put("pack_party", "hypixel_party");
		MODULE.put("pack_hearts", "ls_hearts");
		MODULE.put("pack_feed", "ls_feed");
		MODULE.put("pack_elo", "practice_elo");
		MODULE.put("pack_mcc", "mcc_timers");
		MODULE.put("pack_smp", "smp_balance");
		MODULE.put("pack_big_money", "donut_big_money");
	}

	private record Hit(int x1, int y1, int x2, int y2, Runnable action) {}

	private final List<Hit> hits = new ArrayList<>();

	public HudEditorScreen(Screen parent) {
		super(Component.literal("Edit HUD"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		HudLayout.editing = true;
	}

	@Override
	public void onClose() {
		HudLayout.editing = false;
		KyotoConfig.save();
		if (Packs.saveLayoutNow()) Notifications.push("HUD saved for " + Packs.serverLabel, "It comes back every time you join", Notifications.GOOD, 3000, null, "hud");
		Compat.openScreen(parent);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partial) {
		// no dark background: you see the real HUD and move it
	}

	private boolean visible(float[] b) {
		return System.currentTimeMillis() - (long) b[4] <= 600;
	}

	private String at(double mx, double my) {
		String best = null;
		float bestArea = Float.MAX_VALUE;
		for (Map.Entry<String, float[]> e : HudLayout.BOXES.entrySet()) {
			float[] b = e.getValue();
			if (!visible(b)) continue;
			if (mx >= b[0] && mx <= b[0] + b[2] && my >= b[1] && my <= b[1] + b[3]) {
				float area = b[2] * b[3];
				if (area < bestArea) {
					bestArea = area;
					best = e.getKey();
				}
			}
		}
		return best;
	}

	private int panelW() {
		return listOpen ? 132 : 0;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float partial) {
		hits.clear();
		int accent = Ui.accent();
		hover = dragging != null ? dragging : (mx > panelW() ? at(mx, my) : null);
		// grid + centre guides
		float fade = Anim.clamp01((System.currentTimeMillis() - openedAt) / 250f);
		for (int x = 0; x < this.width; x += 20) g.fill(x, 0, x + 1, this.height, Anim.argb(Ui.INK, 0.025f * fade));
		for (int y = 0; y < this.height; y += 20) g.fill(0, y, this.width, y + 1, Anim.argb(Ui.INK, 0.025f * fade));
		g.fill(this.width / 2, 0, this.width / 2 + 1, this.height, Anim.argb(accent, 0.25f * fade));
		g.fill(0, this.height / 2, this.width, this.height / 2 + 1, Anim.argb(accent, 0.25f * fade));
		// boxes
		for (Map.Entry<String, float[]> e : HudLayout.BOXES.entrySet()) {
			float[] b = e.getValue();
			if (!visible(b)) continue;
			String id = e.getKey();
			boolean h = id.equals(hover), sel = id.equals(selected);
			int x1 = Math.round(b[0]) - 2, y1 = Math.round(b[1]) - 2, x2 = Math.round(b[0] + b[2]) + 2, y2 = Math.round(b[1] + b[3]) + 2;
			g.fill(x1, y1, x2, y2, Anim.argb(accent, sel ? 0.2f : h ? 0.14f : 0.05f));
			Ui.outline(g, x1, y1, x2, y2, Anim.argb(sel || h ? accent : Ui.INK, sel ? 1f : h ? 0.8f : 0.3f));
			if (h || sel) {
				String name = HudLayout.NAMES.getOrDefault(id, id) + "  ·  " + Math.round(HudLayout.scale(id) * 100) + "%";
				int tw = Ui.width(name) + 10;
				int ty = y1 > 16 ? y1 - 15 : y2 + 3;
				Ui.round(g, x1, ty, x1 + tw, ty + 13, 5, Anim.argb(sel ? accent : Ui.GLASS, 0.92f));
				Ui.text(g, name, x1 + 5, ty + 3, Anim.argb(sel ? Ui.GLASS : Ui.INK, 1f));
			}
		}
		drawList(g, mx, my, accent);
		drawBar(g, mx, my, accent);
		super.extractRenderState(g, mx, my, partial);
	}

	/* ---------------- the list of pieces ---------------- */

	private void drawList(GuiGraphicsExtractor g, int mx, int my, int accent) {
		int x1 = 6, y1 = 6, x2 = 6 + 126, y2 = this.height - 40;
		if (!listOpen) {
			button(g, "Show list", "hud", 6, 6, mx, my, accent, false, () -> listOpen = true);
			return;
		}
		Ui.round(g, x1, y1, x2, y2, 10, Anim.argb(Ui.GLASS, 0.9f));
		Ui.outline(g, x1, y1, x2, y2, Anim.argb(Ui.INK, 0.08f));
		Ui.bold(g, "HUD pieces", x1 + 8, y1 + 8, Anim.argb(Ui.TEXT, 1f));
		Ui.small(g, "Switch = show / hide", x1 + 8, y1 + 20, Anim.argb(Ui.MUTED, 1f));
		Ui.icon(g, "‹", x2 - 12, y1 + 8, Anim.argb(Ui.MUTED, 1f));
		hits.add(new Hit(x2 - 16, y1 + 4, x2 - 2, y1 + 18, () -> listOpen = false));
		int top = y1 + 32;
		g.enableScissor(x1, top, x2, y2 - 4);
		int y = top - Math.round(listScroll);
		boolean serverHeader = false;
		List<Map.Entry<String, String>> order = new ArrayList<>();
		for (Map.Entry<String, String> e : HudLayout.NAMES.entrySet()) if (!e.getKey().startsWith("pack_")) order.add(e);
		for (Map.Entry<String, String> e : HudLayout.NAMES.entrySet()) if (e.getKey().startsWith("pack_")) order.add(e);
		for (Map.Entry<String, String> e : order) {
			String id = e.getKey();
			String mod = MODULE.get(id);
			Module m = mod == null ? null : Modules.get(mod);
			if (mod != null && m == null) continue; // that feature was removed
			if (id.startsWith("pack_")) {
				// only show server pieces for the pack you're on (or all, when you're not on a server)
				if (m != null && m.pack != null && Packs.current != null && !m.pack.equals(Packs.current.id())) continue;
				if (!serverHeader) {
					y += 4;
					Ui.small(g, Packs.current != null ? Packs.serverLabel.toUpperCase() : "SERVER PIECES", x1 + 8, y, Anim.argb(Ui.DIM, 1f));
					y += 11;
					serverHeader = true;
				}
			}
			boolean sel = id.equals(selected);
			boolean rowHov = Ui.inside(mx, my, x1 + 4, y, x2 - 4, y + 16) && my >= top && my < y2 - 4;
			if (sel || rowHov) Ui.round(g, x1 + 4, y, x2 - 4, y + 16, 5, Anim.argb(sel ? accent : Ui.INK, sel ? 0.18f : 0.06f));
			String label = e.getValue().replace("Server: ", "");
			boolean on = m == null || m.on();
			Ui.text(g, Ui.ellipsize(label, 80), x1 + 9, y + 4, Anim.argb(on ? Ui.TEXT : Ui.DIM, 1f));
			if (my >= top && my < y2 - 4) hits.add(new Hit(x1 + 4, y, x2 - 30, y + 16, () -> selected = id));
			if (m != null) {
				int sx = x2 - 28, sy = y + 3;
				Ui.round(g, sx, sy, sx + 18, sy + 10, 5, Anim.argb(on ? accent : Ui.SOFT, 1f));
				int kx = on ? sx + 9 : sx + 1;
				Ui.round(g, kx, sy + 1, kx + 8, sy + 9, 4, Anim.argb(Ui.INK, 1f));
				if (my >= top && my < y2 - 4) hits.add(new Hit(sx - 2, y, x2 - 4, y + 16, () -> {
					m.toggle();
					KyotoConfig.save();
				}));
			}
			y += 18;
		}
		g.disableScissor();
		int content = y + Math.round(listScroll) - top;
		maxList = Math.max(0, content - (y2 - 4 - top));
	}

	private int maxList = 0;

	/* ---------------- bottom bar ---------------- */

	private void drawBar(GuiGraphicsExtractor g, int mx, int my, int accent) {
		int y = this.height - 30;
		String tip;
		if (selected != null) tip = HudLayout.NAMES.getOrDefault(selected, selected) + ": drag it, scroll or - / + to resize, arrow keys to nudge";
		else tip = "Drag anything to move it  ·  Scroll on it to resize  ·  Right-click to put it back";
		if (Packs.current != null && Modules.on("pack_layouts")) tip += "  ·  Saved for " + Packs.serverLabel;
		int tw = Ui.width(tip) + 16;
		int tx = this.width / 2 - tw / 2;
		Ui.round(g, tx, y - 18, tx + tw, y - 4, 7, Anim.argb(Ui.GLASS, 0.85f));
		Ui.text(g, tip, tx + 8, y - 15, Anim.argb(Ui.INK, 1f));
		// buttons
		List<Object[]> b = new ArrayList<>();
		if (selected != null) {
			String s = selected;
			b.add(new Object[]{"-", "", (Runnable) () -> resize(s, -0.1f)});
			b.add(new Object[]{"+", "", (Runnable) () -> resize(s, 0.1f)});
			b.add(new Object[]{"Reset this", "prev", (Runnable) () -> HudLayout.reset(s)});
		}
		b.add(new Object[]{"Reset all", "trash", (Runnable) () -> {
			KyotoConfig.get().hudLayout.clear();
			KyotoConfig.save();
			Notifications.push("HUD reset", "Everything is back where it started", Notifications.INFO, 2500, null, "hud");
		}});
		b.add(new Object[]{"Done", "check", (Runnable) this::onClose});
		int total = 0;
		for (Object[] o : b) total += bw((String) o[0], (String) o[1]) + 6;
		int x = this.width / 2 - total / 2;
		for (Object[] o : b) {
			boolean primary = o[0].equals("Done");
			x = button(g, (String) o[0], (String) o[1], x, y, mx, my, accent, primary, (Runnable) o[2]) + 6;
		}
	}

	private int bw(String label, String icon) {
		return Ui.width(label) + (icon.isEmpty() ? 14 : 26);
	}

	private int button(GuiGraphicsExtractor g, String label, String icon, int x, int y, int mx, int my, int accent, boolean primary, Runnable r) {
		int w = bw(label, icon);
		boolean h = Ui.inside(mx, my, x, y, x + w, y + 20);
		int bg = primary || h ? accent : Ui.GLASS;
		Ui.round(g, x, y, x + w, y + 20, 10, Anim.argb(bg, primary ? (h ? 1f : 0.9f) : h ? 0.85f : 0.92f));
		int fg = primary || h ? Ui.GLASS : Ui.TEXT;
		int tx = x + 7;
		if (!icon.isEmpty()) {
			Ui.iconTex(g, icon, x + 8, y + 6, 8, Anim.argb(fg, 1f));
			tx = x + 20;
		}
		Ui.text(g, label, tx, y + 6, Anim.argb(fg, 1f));
		hits.add(new Hit(x, y, x + w, y + 20, r));
		return x + w;
	}

	private void resize(String id, float d) {
		float[] r = HudLayout.raw(id);
		HudLayout.set(id, r[0], r[1], r[2] + d);
	}

	/* ---------------- input ---------------- */

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		for (int i = hits.size() - 1; i >= 0; i--) {
			Hit h = hits.get(i);
			if (Ui.inside(event.x(), event.y(), h.x1, h.y1, h.x2, h.y2)) {
				h.action.run();
				return true;
			}
		}
		if (event.x() < panelW() + 6) return true;
		String id = at(event.x(), event.y());
		if (id == null) {
			selected = null;
			return super.mouseClicked(event, doubleClick);
		}
		selected = id;
		if (event.button() == 1) {
			HudLayout.reset(id);
			return true;
		}
		dragging = id;
		grabX = event.x();
		grabY = event.y();
		float[] r = HudLayout.raw(id);
		startDx = r[0];
		startDy = r[1];
		return true;
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
		if (dragging == null) return super.mouseDragged(event, dx, dy);
		float[] r = HudLayout.raw(dragging);
		float ndx = startDx + (float) (event.x() - grabX), ndy = startDy + (float) (event.y() - grabY);
		// snap the box to the edges / centre lines when close
		float[] b = HudLayout.BOXES.get(dragging);
		if (b != null) {
			float bx = b[0] - r[0] + ndx, by = b[1] - r[1] + ndy;
			float snap = 5;
			if (Math.abs(bx - 2) < snap) ndx += 2 - bx;
			if (Math.abs(by - 2) < snap) ndy += 2 - by;
			if (Math.abs(bx + b[2] - (this.width - 2)) < snap) ndx += (this.width - 2) - (bx + b[2]);
			if (Math.abs(by + b[3] - (this.height - 2)) < snap) ndy += (this.height - 2) - (by + b[3]);
			if (Math.abs(bx + b[2] / 2 - this.width / 2f) < snap) ndx += this.width / 2f - (bx + b[2] / 2);
			if (Math.abs(by + b[3] / 2 - this.height / 2f) < snap) ndy += this.height / 2f - (by + b[3] / 2);
		}
		HudLayout.set(dragging, ndx, ndy, r[2]);
		return true;
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		if (dragging != null) {
			dragging = null;
			KyotoConfig.save();
			return true;
		}
		return super.mouseReleased(event);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		if (listOpen && mouseX < panelW() + 6) {
			listScroll = Math.max(0, Math.min(maxList, listScroll - (float) scrollY * 18));
			return true;
		}
		String id = at(mouseX, mouseY);
		if (id == null) return false;
		resize(id, (float) scrollY * 0.1f);
		return true;
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (selected != null) {
			int k = event.key();
			int step = (event.modifiers() & 1) != 0 ? 10 : 1;
			float[] r = HudLayout.raw(selected);
			switch (k) {
				case 262 -> { HudLayout.set(selected, r[0] + step, r[1], r[2]); return true; } // right
				case 263 -> { HudLayout.set(selected, r[0] - step, r[1], r[2]); return true; } // left
				case 264 -> { HudLayout.set(selected, r[0], r[1] + step, r[2]); return true; } // down
				case 265 -> { HudLayout.set(selected, r[0], r[1] - step, r[2]); return true; } // up
				case 261, 259 -> { HudLayout.reset(selected); return true; } // delete / backspace
				default -> {
				}
			}
		}
		return super.keyPressed(event);
	}
}
