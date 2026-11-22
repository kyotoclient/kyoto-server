package dev.kyoto.screen;

import dev.kyoto.config.KyotoConfig;
import dev.kyoto.ui.Sounds;
import dev.kyoto.util.Anim;
import dev.kyoto.util.Compat;
import dev.kyoto.util.Ui;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * Quick wheel: hold X, point at a command, let go and it runs (/spawn, /home, /ah...).
 * Right-click a slot to change what it runs. It only sends commands you could type yourself.
 */
public class QuickWheel extends Screen {
	private final int key;
	private long openedAt;
	private int hover = -1, editing = -1;
	private final float[] hv = new float[8];
	private EditBox box;

	public QuickWheel(int key) {
		super(Component.literal("Quick wheel"));
		this.key = key;
	}

	private static List<String> slots() {
		return KyotoConfig.get().quickWheel;
	}

	@Override
	protected void init() {
		openedAt = System.currentTimeMillis();
		box = new EditBox(font, width / 2 - 60, height / 2 - 6, 120, 12, Component.literal("Command"));
		box.setBordered(false);
		box.setMaxLength(60);
		box.setTextColor(0xFF000000 | Ui.INK);
		box.visible = false;
		addRenderableWidget(box);
		Sounds.play(Sounds.OPEN);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mx, int my, float partial) {
		float al = Anim.easeOutBack(Anim.clamp01((System.currentTimeMillis() - openedAt) / 260f));
		float fa = Anim.clamp01((System.currentTimeMillis() - openedAt) / 200f);
		g.fill(0, 0, width, height, Anim.argb(Ui.DARK ? 0x000000 : 0x2B1D23, 0.22f * fa));
		int cx = width / 2, cy = height / 2;
		float R = Math.min(width, height) * 0.28f, r = R * 0.45f;
		double ang = Math.atan2(my - cy, mx - cx), dist = Math.hypot(mx - cx, my - cy);
		if (editing < 0) hover = dist > r * 0.6 ? Math.floorMod((int) Math.round((ang + Math.PI / 2) / (Math.PI / 4)), 8) : -1;
		int D = Math.round(R * 2.35f * al);
		Ui.glass(g, cx - D / 2, cy - D / 2, cx + D / 2, cy + D / 2, D / 2, fa);
		int acc = Ui.accent();
		for (int i = 0; i < 8; i++) {
			hv[i] += ((i == hover || i == editing ? 1f : 0f) - hv[i]) * 0.25f;
			double a = i * Math.PI / 4 - Math.PI / 2;
			float rr = R * (0.95f + 0.06f * hv[i]) * al;
			float x = cx + (float) Math.cos(a) * rr, y = cy + (float) Math.sin(a) * rr;
			String c = slots().get(i);
			int w = Math.max(34, Ui.width(c) + 14);
			Ui.round(g, Math.round(x - w / 2f), Math.round(y - 9), Math.round(x + w / 2f), Math.round(y + 9), 9, Anim.argb(Ui.mix(Ui.GLASS_LIGHT, acc, hv[i]), 0.9f * fa));
			Ui.text(g, c, Math.round(x - Ui.width(c) / 2f), Math.round(y - 4), Anim.argb(Ui.mix(Ui.INK, 0xFFFFFF, hv[i]), fa));
		}
		Ui.circle(g, cx, cy, r, Anim.argb(Ui.GLASS, 0.92f * fa));
		if (editing >= 0) {
			box.visible = true;
			Ui.small(g, "Type a command, Enter to save", cx - Ui.widthSmall("Type a command, Enter to save") / 2f, cy + 10, Anim.argb(Ui.MUTED, fa));
		} else {
			box.visible = false;
			String c = hover >= 0 ? slots().get(hover) : "Quick wheel";
			Ui.bold(g, c, cx - Ui.widthBold(c) / 2, cy - 8, Anim.argb(Ui.TEXT, fa));
			String sub = hover >= 0 ? "let go to run  ·  right-click to change" : "point at one";
			Ui.small(g, sub, cx - Ui.widthSmall(sub) / 2f, cy + 4, Anim.argb(Ui.MUTED, fa));
		}
	}

	private void run() {
		if (hover >= 0) {
			String c = slots().get(hover).trim();
			if (!c.isEmpty()) Compat.sendCommand(c.startsWith("/") ? c.substring(1) : c);
			Sounds.play(Sounds.SUCCESS);
		}
		Compat.openScreen(null);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent e, boolean dbl) {
		if (editing >= 0) return super.mouseClicked(e, dbl);
		if (e.button() == 1 && hover >= 0) {
			editing = hover;
			box.setValue(slots().get(editing));
			setFocused(box);
			box.setFocused(true);
			return true;
		}
		run();
		return true;
	}

	@Override
	public boolean keyPressed(KeyEvent e) {
		if (editing >= 0 && (e.key() == 257 || e.key() == 335)) {
			String v = box.getValue().trim();
			if (!v.isEmpty() && !v.startsWith("/")) v = "/" + v;
			slots().set(editing, v);
			KyotoConfig.save();
			editing = -1;
			box.setFocused(false);
			setFocused(null);
			Sounds.play(Sounds.SUCCESS);
			return true;
		}
		return super.keyPressed(e);
	}

	@Override
	public boolean keyReleased(KeyEvent e) {
		if (editing < 0 && e.key() == key) {
			run();
			return true;
		}
		return super.keyReleased(e);
	}
}
