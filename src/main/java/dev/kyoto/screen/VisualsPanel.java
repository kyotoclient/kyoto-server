package dev.kyoto.screen;

import dev.kyoto.module.Modules;
import dev.kyoto.util.Anim;
import dev.kyoto.util.Compat;
import dev.kyoto.util.Ui;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Small floating panel from the petal menu: the look of Kyoto (Interface) and of the world (World). */
public class VisualsPanel extends Screen {
	private static final String[] INTERFACE = {"menu_glass", "ui_animations", "button_sounds", "custom_font", "accent", "hud_look", "dark_mode", "liquid_glass", "notch"};
	private static final String[] WORLD = {"player_trail", "atmosphere", "sakura_drift", "accessory", "sprint_trail", "fireflies", "block_outline", "low_fire", "fullbright"};
	private static int tab = 0;

	private final Screen parent;
	private long openedAt;
	private final Map<String, Float> anim = new HashMap<>();
	private float tabX = -1;

	private record Hit(int x1, int y1, int x2, int y2, int kind, String id) {}

	private final List<Hit> hits = new ArrayList<>();

	public VisualsPanel(Screen parent) {
		super(Component.literal("Visuals"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		openedAt = System.currentTimeMillis();
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void onClose() {
		Compat.openScreen(parent);
	}

	private float a(String k, float target) {
		float v = anim.getOrDefault(k, target);
		v += (target - v) * (Ui.animSpeed() <= 0 ? 1 : 0.22f);
		anim.put(k, v);
		return v;
	}

	private List<Modules.Module> rows() {
		List<Modules.Module> l = new ArrayList<>();
		for (String id : tab == 0 ? INTERFACE : WORLD) {
			Modules.Module m = Modules.get(id);
			if (m != null) l.add(m);
		}
		return l;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float partial) {
		super.extractRenderState(g, mx, my, partial);
		hits.clear();
		float open = Ui.animSpeed() <= 0 ? 1 : Anim.easeOutCubic(Anim.clamp01((System.currentTimeMillis() - openedAt) / 320f));
		List<Modules.Module> rows = rows();
		int w = 230, rowH = 18, h = 52 + rows.size() * rowH + 22;
		int x1 = (this.width - w) / 2, y1 = (this.height - h) / 2 + Math.round((1 - open) * 10);
		Ui.glass(g, x1, y1, x1 + w, y1 + h, 12, open);
		Ui.boldScaled(g, "Visuals", x1 + 12, y1 + 10, 1.15f, Anim.argb(Ui.TEXT, open));
		// segmented tabs with a sliding white pill
		int ty = y1 + 28, tw = 70;
		String[] tabs = {"Interface", "World"};
		float target = x1 + 12 + tab * (tw + 4);
		tabX = tabX < 0 ? target : tabX + (target - tabX) * (Ui.animSpeed() <= 0 ? 1 : 0.25f);
		Ui.round(g, x1 + 12, ty, x1 + 12 + tw * 2 + 4, ty + 14, 7, Anim.argb(Ui.INK, 0.05f * open));
		Ui.round(g, Math.round(tabX), ty, Math.round(tabX) + tw, ty + 14, 7, Anim.argb(Ui.INK, 0.92f * open));
		for (int i = 0; i < 2; i++) {
			int bx = x1 + 12 + i * (tw + 4);
			float sel = a("tab" + i, tab == i ? 1 : 0);
			int lw = Ui.width(tabs[i]);
			Ui.text(g, tabs[i], bx + tw / 2 - lw / 2, ty + 3, Anim.argb(Ui.mix(Ui.MUTED, Ui.GLASS, sel), open));
			hits.add(new Hit(bx, ty, bx + tw, ty + 14, 1, String.valueOf(i)));
		}
		int y = y1 + 50;
		for (int i = 0; i < rows.size(); i++) {
			Modules.Module m = rows.get(i);
			float rowIn = Ui.animSpeed() <= 0 ? 1 : Anim.easeOutCubic(Anim.clamp01((System.currentTimeMillis() - openedAt - 60 - i * 25) / 260f));
			float al = open * rowIn;
			boolean hov = Ui.inside(mx, my, x1 + 6, y, x1 + w - 6, y + rowH);
			float hv = a("h" + m.id, hov ? 1 : 0);
			Ui.round(g, x1 + 6, y, x1 + w - 6, y + rowH, 6, Anim.argb(Ui.INK, 0.05f * hv * al));
			Ui.text(g, m.name, x1 + 12 + Math.round(2 * hv) - Math.round((1 - rowIn) * 6), y + 5, Anim.argb(Ui.TEXT, al));
			if (!m.settings.isEmpty()) {
				String v = m.settings.get(0).label();
				int vw = Ui.widthSmall(v);
				Ui.small(g, v, x1 + w - 40 - vw, y + 6, Anim.argb(Ui.MUTED, al * (0.5f + 0.5f * hv)));
			}
			Ui.toggle(g, x1 + w - 32, y + 4, a("t" + m.id, m.on() ? 1 : 0), al);
			hits.add(new Hit(x1 + 6, y, x1 + w - 6, y + rowH, 2, m.id));
			y += rowH;
		}
		// back
		boolean hb = Ui.inside(mx, my, x1 + 6, y + 2, x1 + w - 6, y + 18);
		float bv = a("back", hb ? 1 : 0);
		Ui.round(g, x1 + 6, y + 2, x1 + w - 6, y + 18, 6, Anim.argb(Ui.INK, 0.05f * bv * open));
		Ui.text(g, "Back", x1 + 12, y + 6, Anim.argb(Ui.mix(Ui.MUTED, Ui.TEXT, bv), open));
		hits.add(new Hit(x1 + 6, y + 2, x1 + w - 6, y + 18, 3, ""));
		String tip = "Right-click a row to change its style";
		Ui.small(g, tip, x1 + w - 10 - Ui.widthSmall(tip), y + 7, Anim.argb(Ui.DIM, open));
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		for (Hit h : hits) {
			if (!Ui.inside(event.x(), event.y(), h.x1, h.y1, h.x2, h.y2)) continue;
			if (Modules.on("button_sounds")) Compat.sound(net.minecraft.sounds.SoundEvents.UI_BUTTON_CLICK.value(), 0.18f, 1.9f);
			switch (h.kind) {
				case 1 -> tab = Integer.parseInt(h.id);
				case 2 -> {
					Modules.Module m = Modules.get(h.id);
					if (m == null) break;
					if (event.button() == 1 && !m.settings.isEmpty()) m.settings.get(0).cycle(1);
					else m.toggle();
				}
				case 3 -> onClose();
				default -> {}
			}
			return true;
		}
		return super.mouseClicked(event, doubleClick);
	}
}
