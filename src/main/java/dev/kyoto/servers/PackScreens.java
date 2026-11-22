package dev.kyoto.servers;

import dev.kyoto.screen.PanelScreen;
import dev.kyoto.util.Anim;
import dev.kyoto.util.Compat;
import dev.kyoto.util.Ui;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;

import java.util.List;

/** Small pop-up screens for server packs: pay confirm, combat warning, homes + warps picker. */
public final class PackScreens {
	private PackScreens() {}

	/** Typo guard: "Pay $5,000,000 to Steve?" */
	public static class Confirm extends PanelScreen {
		private final String line1, line2, command, okLabel;
		private final int color;

		public Confirm(String title, String line1, String line2, String command, String okLabel, int color) {
			super(title, null);
			this.line1 = line1;
			this.line2 = line2;
			this.command = command;
			this.okLabel = okLabel;
			this.color = color;
		}

		@Override
		protected int maxW() {
			return 260;
		}

		@Override
		protected int maxH() {
			return 122;
		}

		@Override
		protected String icon() {
			return "coin";
		}

		@Override
		protected void build() {
			btn(px + 14, py + ph - 30, (pw - 34) / 2, "Cancel", this::onClose);
			btn(px + 20 + (pw - 34) / 2, py + ph - 30, (pw - 34) / 2, okLabel, () -> {
				ServerEvents.bypassOnce = command;
				Compat.openScreen(null);
				Compat.sendCommand(command);
			});
		}

		@Override
		protected void drawContent(GuiGraphicsExtractor g, int mx, int my, float a) {
			Ui.boldScaled(g, line1, px + pw / 2f - Ui.widthBold(line1) * 1.25f / 2, py + 42, 1.25f, Anim.argb(color, a));
			Ui.small(g, line2, px + pw / 2 - Ui.widthSmall(line2) / 2, py + 62, Anim.argb(Ui.MUTED, a));
		}

		@Override
		public void onClose() {
			Compat.openScreen(null);
		}
	}

	/** Homes + warps you've seen (from /homes, /warps or the homes menu). Click one to go there. */
	public static class Places extends PanelScreen {
		public Places(Screen parent) {
			super("Homes + warps", parent);
		}

		@Override
		protected String subtitle() {
			return "Type /homes or /warps once and Kyoto remembers them";
		}

		@Override
		protected String icon() {
			return "waypoint";
		}

		@Override
		protected void build() {
			int x = px + 14, y = py + 40, col = 0;
			List<String> homes = Smp.HOMES, warps = Smp.WARPS;
			for (String h : homes) {
				btn(x + col * 118, y, 112, "⌂ " + h, () -> { Compat.openScreen(null); Compat.sendCommand("home " + h); });
				if (++col == 2) { col = 0; y += 22; }
				if (y > py + ph - 60) break;
			}
			if (col != 0) { col = 0; y += 22; }
			y += 6;
			for (String w : warps) {
				btn(x + col * 118, y, 112, "➜ " + w, () -> { Compat.openScreen(null); Compat.sendCommand("warp " + w); });
				if (++col == 2) { col = 0; y += 22; }
				if (y > py + ph - 34) break;
			}
			if (homes.isEmpty() && warps.isEmpty()) {
				btn(x, y, 112, "Run /homes", () -> { Compat.openScreen(null); Compat.sendCommand("homes"); });
				btn(x + 118, y, 112, "Run /warps", () -> { Compat.openScreen(null); Compat.sendCommand("warps"); });
			}
		}
	}
}
