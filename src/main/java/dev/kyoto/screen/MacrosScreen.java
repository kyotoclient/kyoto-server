package dev.kyoto.screen;

import dev.kyoto.config.KyotoConfig;
import dev.kyoto.ui.Sounds;
import dev.kyoto.util.Anim;
import dev.kyoto.util.Compat;
import dev.kyoto.util.Ui;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** Tools > Macros: what each macro key does. Steps split by ";" and "wait 1" pauses a second. */
public class MacrosScreen extends Screen {
	private final Screen parent;
	private final List<EditBox> boxes = new ArrayList<>();
	private long openedAt;

	public MacrosScreen(Screen parent) {
		super(Component.literal("Macros"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		openedAt = System.currentTimeMillis();
		boxes.clear();
		int pw = Math.min(width - 20, 460), px = (width - pw) / 2, py = (height - 250) / 2;
		for (int i = 0; i < 6; i++) {
			EditBox b = new EditBox(font, px + 90, py + 76 + i * 24, pw - 110, 12, Component.literal("Macro " + (i + 1)));
			b.setBordered(false);
			b.setMaxLength(240);
			b.setTextColor(0xFF000000 | Ui.INK);
			b.setHint(Ui.t(i == 0 ? "/home ; wait 1 ; /sell all" : "empty"));
			b.setValue(KyotoConfig.get().hotkeyActions.get(i));
			final int fi = i;
			b.setResponder(v -> { KyotoConfig.get().hotkeyActions.set(fi, v); KyotoConfig.save(); });
			boxes.add(b);
			addRenderableWidget(b);
		}
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mx, int my, float partial) {
		super.extractBackground(g, mx, my, partial);
		float al = Anim.easeOutCubic(Anim.clamp01((System.currentTimeMillis() - openedAt) / 280f));
		int pw = Math.min(width - 20, 460), ph = 250, px = (width - pw) / 2, py = (height - ph) / 2;
		g.fillGradient(-20, -20, width + 20, height + 20, Anim.argb(Ui.BG_TOP, 0.5f * al), Anim.argb(Ui.BG_BOTTOM, 0.65f * al));
		Ui.glass(g, px, py, px + pw, py + ph, 16, al);
		Ui.boldScaled(g, "Macros", px + 16, py + 12, 1.4f, Anim.argb(Ui.TEXT, al));
		// warning
		Ui.round(g, px + 14, py + 32, px + pw - 14, py + 62, 9, Anim.argb(0xF2B84B, 0.16f * al));
		Ui.circle(g, px + 26, py + 47, 6, Anim.argb(0xF2B84B, al));
		Ui.bold(g, "!", px + 25, py + 43, Anim.argb(0xFFFFFF, al));
		Ui.text(g, "Use at your own risk.", px + 38, py + 38, Anim.argb(Ui.TEXT, al));
		Ui.small(g, "Most servers do not allow macros and they are bannable.", px + 38, py + 50, Anim.argb(Ui.MUTED, al));
		for (int i = 0; i < 6; i++) {
			int y = py + 72 + i * 24;
			Ui.text(g, "Macro " + (i + 1), px + 18, y + 4, Anim.argb(Ui.MUTED, al));
			Ui.round(g, px + 84, y, px + pw - 14, y + 18, 9, Anim.argb(Ui.GLASS_LIGHT, (Ui.DARK ? 0.12f : 0.7f) * al));
			Ui.ring(g, px + 84, y, px + pw - 14, y + 18, 9, 1, Anim.argb(boxes.get(i).isFocused() ? Ui.accent() : 0xFFFFFF, (Ui.DARK ? 0.2f : 0.9f) * al));
		}
		Ui.small(g, "Split steps with ;   ·   \"wait 1\" pauses a second   ·   keys: Options > Controls > Kyoto Client", px + 18, py + ph - 20, Anim.argb(Ui.DIM, al));
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void onClose() {
		Sounds.play(Sounds.CLOSE);
		Compat.openScreen(parent);
	}
}
