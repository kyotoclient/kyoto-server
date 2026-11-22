package dev.kyoto.ui;

import dev.kyoto.util.Ui;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;

import java.util.Map;
import java.util.WeakHashMap;

/** Draws vanilla buttons (title screen etc.) in the Kyoto style: hairline pills that fill white on hover. */
public final class Buttons {
	private Buttons() {}

	private static final Map<AbstractWidget, Float> HOVER = new WeakHashMap<>();

	public static void draw(Iterable<AbstractWidget> widgets, GuiGraphicsExtractor g, int mx, int my, float partial, float alpha) {
		for (AbstractWidget w : widgets) {
			if (!w.visible) continue;
			if (!(w instanceof Button) || w.getWidth() <= 24 || w.getMessage().getString().isBlank()) {
				w.extractRenderState(g, mx, my, partial);
				continue;
			}
			boolean in = mx >= w.getX() && my >= w.getY() && mx < w.getX() + w.getWidth() && my < w.getY() + w.getHeight();
			float h = HOVER.getOrDefault(w, 0f);
			h += ((in && w.active ? 1f : 0f) - h) * (Ui.animSpeed() <= 0 ? 1f : 0.2f);
			HOVER.put(w, h);
			String label = Ui.ellipsize(w.getMessage().getString(), w.getWidth() - 10);
			Ui.button(g, w.getX(), w.getY(), w.getX() + w.getWidth(), w.getY() + w.getHeight(), label, h, w.active, alpha);
		}
	}
}
