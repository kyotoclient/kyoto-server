package dev.kyoto.ui;

import dev.kyoto.module.Modules;
import dev.kyoto.util.Anim;
import dev.kyoto.util.Ui;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.AbstractSelectionList;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.events.ContainerEventHandler;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * Kyoto look for Minecraft's own menus (pause, options and every options page, singleplayer, multiplayer, create world...).
 * Realms, inventories and chat are left alone. Nothing is replaced: Kyoto just paints over the buttons, sliders and
 * switches after Minecraft draws them, so every button still does exactly what it did (and server icons, world icons
 * and list rows stay Minecraft's own).
 */
public final class VanillaSkin {
	private VanillaSkin() {}

	private static final Map<AbstractWidget, Float> HOVER = new WeakHashMap<>();
	private static final Map<Class<?>, java.lang.reflect.Field> SLIDER_VALUE = new java.util.HashMap<>();

	public static boolean styled(Object screen) {
		try {
			if (!Modules.on("kyoto_vanilla_menus")) return false;
		} catch (Throwable t) {
			return false;
		}
		if (screen instanceof TitleScreen) return false;
		String n = screen.getClass().getName();
		if (!n.startsWith("net.minecraft.client.gui.screens.")) return false;
		return !n.contains(".inventory.") && !n.contains("ChatScreen") && !n.toLowerCase().contains("realms") && !n.contains("InBedChat");
	}

	/** Behind the menu: the sakura sky (softly dimmed) outside a world; inside one, Minecraft's blur plus a rosy vignette. */
	public static boolean background(Screen s, GuiGraphicsExtractor g) {
		if (Minecraft.getInstance().level != null) return false;
		TitleTheme.sky(g, s.width, s.height, 1f);
		// Minecraft writes its titles and labels in white, so soften the sky behind them
		g.fillGradient(0, 0, s.width, s.height, 0x704A2A38, 0xA02A1820);
		return true;
	}

	public static void tint(Screen s, GuiGraphicsExtractor g) {
		if (Minecraft.getInstance().level == null) return;
		g.fillGradient(0, 0, s.width, s.height, 0x402A1820, 0x902A1820);
	}

	/** Paint every visible button / slider / checkbox (also the ones inside option lists). */
	public static void paint(Screen s, GuiGraphicsExtractor g, int mx, int my) {
		walk(s, g, mx, my, 0, 0, s.width, s.height, 0);
	}

	private static void walk(ContainerEventHandler parent, GuiGraphicsExtractor g, int mx, int my, int cx1, int cy1, int cx2, int cy2, int depth) {
		if (depth > 4) return;
		for (GuiEventListener l : parent.children()) {
			int x1 = cx1, y1 = cy1, x2 = cx2, y2 = cy2;
			if (l instanceof AbstractSelectionList<?> list) {
				AbstractWidget lw = (AbstractWidget) (Object) list;
				x1 = Math.max(cx1, lw.getX());
				y1 = Math.max(cy1, lw.getY());
				x2 = Math.min(cx2, lw.getX() + lw.getWidth());
				y2 = Math.min(cy2, lw.getY() + lw.getHeight());
			}
			if (l instanceof AbstractWidget w) {
				if (w.visible && w.getX() >= x1 && w.getY() >= y1 && w.getX() + w.getWidth() <= x2 && w.getY() + w.getHeight() <= y2) one(w, g, mx, my);
			}
			if (l instanceof ContainerEventHandler c) walk(c, g, mx, my, x1, y1, x2, y2, depth + 1);
		}
	}

	private static float hover(AbstractWidget w, int mx, int my) {
		boolean in = w.active && mx >= w.getX() && my >= w.getY() && mx < w.getX() + w.getWidth() && my < w.getY() + w.getHeight();
		float h = HOVER.getOrDefault(w, 0f);
		h += ((in ? 1f : 0f) - h) * (Ui.animSpeed() <= 0 ? 1f : 0.22f);
		HOVER.put(w, h);
		return h;
	}

	private static void one(AbstractWidget w, GuiGraphicsExtractor g, int mx, int my) {
		String cls = w.getClass().getSimpleName();
		if (w.getWidth() <= 24 || cls.contains("Sprite") || cls.contains("Image") || cls.contains("Lock") || cls.contains("Icon")) return;
		int x1 = w.getX(), y1 = w.getY(), x2 = x1 + w.getWidth(), y2 = y1 + w.getHeight();
		String label = w.getMessage().getString();
		if (w instanceof Checkbox cb) {
			float h = hover(w, mx, my);
			// the switch sits where the box was, the label stays Minecraft's
			int ty = y1 + (w.getHeight() - 10) / 2;
			Ui.round(g, x1, y1, x1 + Math.min(20, w.getHeight()), y1 + Math.min(20, w.getHeight()), 5, Anim.argb(Ui.GLASS, 0.95f));
			Ui.toggle(g, x1 + 1, ty, cb.selected() ? 1f : 0f, 0.85f + 0.15f * h);
			return;
		}
		if (w instanceof AbstractSliderButton) {
			float h = hover(w, mx, my);
			double v = sliderValue(w);
			int r = Math.min(8, w.getHeight() / 2);
			Ui.round(g, x1, y1, x2, y2, r, 0xFF0A0A0B);
			Ui.ring(g, x1, y1, x2, y2, r, Anim.argb(Ui.INK, 0.12f + 0.18f * h));
			if (v >= 0) {
				int fx = x1 + 2 + (int) Math.round((w.getWidth() - 4) * v);
				Ui.round(g, x1 + 2, y1 + 2, Math.max(x1 + 6, fx), y2 - 2, Math.max(0, r - 2), Anim.argb(Ui.INK, 0.10f + 0.05f * h));
				Ui.round(g, fx - 2, y1 + 3, fx + 2, y2 - 3, 2, Anim.argb(Ui.INK, 0.85f + 0.15f * h));
			}
			int tw = Ui.width(label);
			Ui.text(g, Ui.ellipsize(label, w.getWidth() - 12), Math.max(x1 + 6, (x1 + x2) / 2 - tw / 2), (y1 + y2) / 2 - 4, Anim.argb(w.active ? Ui.TEXT : Ui.DIM, 1f));
			return;
		}
		if (w instanceof AbstractButton) {
			float h = hover(w, mx, my);
			Ui.round(g, x1, y1, x2, y2, Math.min(8, w.getHeight() / 2), 0xFF0A0A0B); // fully cover Minecraft's grey button
			Ui.button(g, x1, y1, x2, y2, Ui.ellipsize(label, w.getWidth() - 10), h, w.active, 1f);
		}
	}

	private static double sliderValue(AbstractWidget w) {
		java.lang.reflect.Field f = SLIDER_VALUE.computeIfAbsent(AbstractSliderButton.class, k -> {
			for (java.lang.reflect.Field x : k.getDeclaredFields()) {
				if (x.getType() == double.class && !java.lang.reflect.Modifier.isStatic(x.getModifiers())) {
					x.setAccessible(true);
					return x;
				}
			}
			return null;
		});
		try {
			return f == null ? -1 : Math.max(0, Math.min(1, f.getDouble(w)));
		} catch (Throwable t) {
			return -1;
		}
	}

	/** List areas (server list, world list, option pages): a soft dark panel instead of Minecraft's textured one. */
	public static void listBackground(AbstractWidget list, GuiGraphicsExtractor g) {
		g.fill(list.getX(), list.getY(), list.getX() + list.getWidth(), list.getY() + list.getHeight(), 0x66000000);
	}

	public static void listSeparators(AbstractWidget list, GuiGraphicsExtractor g) {
		int x1 = list.getX(), x2 = list.getX() + list.getWidth();
		g.fill(x1, list.getY() - 1, x2, list.getY(), 0x22FFFFFF);
		g.fill(x1, list.getY() + list.getHeight(), x2, list.getY() + list.getHeight() + 1, 0x22FFFFFF);
	}
}
