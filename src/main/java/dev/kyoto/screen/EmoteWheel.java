package dev.kyoto.screen;

import dev.kyoto.feature.Dances;
import dev.kyoto.ui.Sounds;
import dev.kyoto.util.Anim;
import dev.kyoto.util.Compat;
import dev.kyoto.util.Ui;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * The emote wheel: hold the key, point at a dance, let go (or click). The middle shows what you're pointing at.
 * Moving cancels a dance.
 */
public class EmoteWheel extends Screen {
	private final int key;
	private long openedAt;
	private int hover = -1;
	private final float[] hv = new float[Dances.IDS.length];

	public EmoteWheel(int key) {
		super(Component.literal("Emotes"));
		this.key = key;
	}

	@Override
	protected void init() {
		openedAt = System.currentTimeMillis();
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
		g.fill(0, 0, width, height, Anim.argb(Ui.DARK ? 0x000000 : 0x2B1D23, 0.25f * fa));
		int cx = width / 2, cy = height / 2, n = Dances.IDS.length;
		float R = Math.min(width, height) * 0.3f, r = R * 0.42f;
		double ang = Math.atan2(my - cy, mx - cx), dist = Math.hypot(mx - cx, my - cy);
		hover = -1;
		if (dist > r * 0.6) hover = Math.floorMod((int) Math.round((ang + Math.PI / 2) / (Math.PI * 2 / n)), n);
		// glass disc
		int D = Math.round(R * 2.3f * al);
		Ui.glass(g, cx - D / 2, cy - D / 2, cx + D / 2, cy + D / 2, D / 2, fa);
		int acc = Ui.accent();
		for (int i = 0; i < n; i++) {
			hv[i] += ((i == hover ? 1f : 0f) - hv[i]) * 0.25f;
			double a = i * Math.PI * 2 / n - Math.PI / 2;
			float rr = R * (0.95f + 0.06f * hv[i]) * al;
			float x = cx + (float) Math.cos(a) * rr, y = cy + (float) Math.sin(a) * rr;
			float s = 13 + 4 * hv[i];
			Ui.circle(g, x, y, s, Anim.argb(Ui.mix(Ui.GLASS_LIGHT, acc, hv[i]), (0.85f) * fa));
			Ui.iconTex(g, Dances.ICONS[i], Math.round(x - 5), Math.round(y - 7), 10, Anim.argb(Ui.mix(acc, 0xFFFFFF, hv[i]), fa));
			String nm = Dances.NAMES[i];
			Ui.small(g, nm, x - Ui.widthSmall(nm) / 2f, y + 5, Anim.argb(Ui.mix(Ui.MUTED, 0xFFFFFF, hv[i]), fa));
		}
		// centre
		Ui.circle(g, cx, cy, r, Anim.argb(Ui.GLASS, 0.9f * fa));
		String c = hover >= 0 ? Dances.NAMES[hover] : "Emotes";
		Ui.bold(g, c, cx - Ui.widthBold(c) / 2, cy - 8, Anim.argb(Ui.TEXT, fa));
		String sub = hover >= 0 ? "let go to dance" : "point at one";
		Ui.small(g, sub, cx - Ui.widthSmall(sub) / 2f, cy + 4, Anim.argb(Ui.MUTED, fa));
	}

	private void pick() {
		if (hover >= 0) {
			Dances.play(hover);
			Sounds.play(Sounds.SUCCESS);
		}
		Compat.openScreen(null);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent e, boolean dbl) {
		pick();
		return true;
	}

	@Override
	public boolean keyReleased(KeyEvent e) {
		if (e.key() == key) {
			pick();
			return true;
		}
		return super.keyReleased(e);
	}
}
