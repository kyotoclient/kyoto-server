package dev.kyoto.extra;

import dev.kyoto.screen.PanelScreen;
import dev.kyoto.util.Anim;
import dev.kyoto.util.Ui;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;

/** A little map of the chunks around you. Green = slime chunk (slimes spawn below Y 40 there). */
public class SlimeScreen extends PanelScreen {
	public SlimeScreen(Screen parent) {
		super("Slime chunks", parent);
	}

	@Override
	protected int maxW() { return 440; }

	@Override
	protected int maxH() { return 300; }

	@Override
	protected String icon() { return "world"; }

	@Override
	protected String subtitle() {
		return Seeds.seed() == null ? "Needs the world seed: /kyoto seed <number> (singleplayer works by itself)" : "Green = slime chunk · slimes spawn there below Y 40 · north is up";
	}

	@Override
	protected void build() {
		btn(px + 10, py + ph - 26, 140, Extras.slimeShown ? "Hide in world" : "Show in world", () -> Extras.slimeShown = !Extras.slimeShown);
	}

	@Override
	protected void drawContent(GuiGraphicsExtractor g, int mx, int my, float a) {
		Minecraft mc = Minecraft.getInstance();
		Long seed = Seeds.seed();
		if (seed == null || mc.player == null) return;
		int cols = 33, rows = 21, cell = Math.min((pw - 20) / cols, (ph - 72) / rows);
		int gx = px + pw / 2 - cols * cell / 2, gy = py + 36;
		int cx = mc.player.getBlockX() >> 4, cz = mc.player.getBlockZ() >> 4;
		String hover = null;
		for (int r = 0; r < rows; r++) for (int c = 0; c < cols; c++) {
			int x = cx + c - cols / 2, z = cz + r - rows / 2;
			boolean s = Seeds.slimeChunk(seed, x, z), me = x == cx && z == cz;
			int x1 = gx + c * cell, y1 = gy + r * cell;
			g.fill(x1, y1, x1 + cell - 1, y1 + cell - 1, Anim.argb(s ? 0x5BE36B : Ui.INK, (s ? 0.75f : 0.05f) * a));
			if (me) Ui.outline(g, x1 - 1, y1 - 1, x1 + cell, y1 + cell, Anim.argb(Ui.accent(), a));
			if (Ui.inside(mx, my, x1, y1, x1 + cell, y1 + cell)) hover = "Chunk " + x + ", " + z + " · blocks " + (x * 16) + " to " + (x * 16 + 15) + ", " + (z * 16) + " to " + (z * 16 + 15) + (s ? " · SLIME" : "");
		}
		if (hover != null) Ui.small(g, hover, gx, gy + rows * cell + 3, Anim.argb(Ui.TEXT, a));
	}
}
