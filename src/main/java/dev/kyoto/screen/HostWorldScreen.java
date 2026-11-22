package dev.kyoto.screen;

import dev.kyoto.feature.ChatTweaks;
import dev.kyoto.util.Anim;
import dev.kyoto.util.Compat;
import dev.kyoto.util.Ui;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;

/**
 * "Online world" like console editions: opens your singleplayer world and, with the free e4mc mod,
 * gives it a public address (something.e4mc.link). Geyser lets Bedrock players join too.
 */
public class HostWorldScreen extends PanelScreen {
	public HostWorldScreen(Screen parent) {
		super("Host Online World", parent);
	}

	private static boolean e4mc() {
		return FabricLoader.getInstance().isModLoaded("e4mc_minecraft") || FabricLoader.getInstance().isModLoaded("e4mc");
	}

	private static boolean geyser() {
		return FabricLoader.getInstance().isModLoaded("geyser-fabric") || FabricLoader.getInstance().isModLoaded("geyser");
	}

	@Override
	protected String subtitle() {
		return "Let friends join your singleplayer world from anywhere.";
	}

	@Override
	protected String icon() {
		return "world";
	}

	@Override
	protected void build() {
		boolean single = this.minecraft != null && this.minecraft.getSingleplayerServer() != null;
		int y = py + 40;
		if (!single) return;
		if (!Compat.isLanOpen()) {
			btn(px + 14, y, 230, "Open my world online", () -> {
				int port = Compat.openToLan();
				dev.kyoto.hud.Notifications.push(port > 0 ? "World is open" : "Couldn't open world",
						port > 0 ? (e4mc() ? "e4mc will post your address in chat" : "LAN port " + port) : "", port > 0 ? Ui.INK : 0xFF5D5D);
			});
		} else if (ChatTweaks.lastHostAddress != null) {
			btn(px + 14, y, 230, "Copy address: " + Ui.ellipsize(ChatTweaks.lastHostAddress, 140), () -> Compat.copy(ChatTweaks.lastHostAddress));
		}
		y += 24;
		if (!e4mc()) btn(px + 14, y, 230, "Get e4mc (public address) - Mod Finder", () -> Compat.openScreen(new ModFinderScreen(this, "e4mc")));
		y += 24;
		if (!geyser()) btn(px + 14, y, 230, "Get Geyser (Bedrock players) - Mod Finder", () -> Compat.openScreen(new ModFinderScreen(this, "geyser")));
	}

	@Override
	protected void drawContent(GuiGraphicsExtractor g, int mx, int my, float a) {
		boolean single = this.minecraft != null && this.minecraft.getSingleplayerServer() != null;
		int x = px + 16, y = py + 116;
		if (!single) {
			for (String s : wrap("Open a singleplayer world first, then come back here from the pause menu (Esc -> Host Online World).", pw - 32, 4)) {
				Ui.text(g, s, x, py + 44 + (y - py - 116), Anim.argb(Ui.MUTED, a));
				y += 11;
			}
			return;
		}
		line(g, x, y, "World open", Compat.isLanOpen(), a);
		line(g, x, y + 14, "e4mc installed (public Java address)", e4mc(), a);
		line(g, x, y + 28, "Geyser installed (Bedrock can join)", geyser(), a);
		String addr = ChatTweaks.lastHostAddress;
		Ui.text(g, "Address: " + (addr == null ? (Compat.isLanOpen() ? "waiting for e4mc..." : "-") : addr), x, y + 46, Anim.argb(Ui.TEXT, a));
		int ty = y + 64;
		for (String s : wrap("Java friends: join the address above. Bedrock friends: need Geyser + a UDP tunnel (the free playit.gg app) because e4mc only carries Java traffic. Plugins (Paper) can't run in singleplayer - use Fabric server mods instead.", pw - 32, 6)) {
			Ui.text(g, s, x, ty, Anim.argb(Ui.MUTED, a));
			ty += 11;
		}
	}

	private void line(GuiGraphicsExtractor g, int x, int y, String label, boolean ok, float a) {
		Ui.icon(g, ok ? "✔" : "✖", x, y, Anim.argb(ok ? Ui.INK : 0xFF5D5D, a));
		Ui.text(g, label, x + 12, y, Anim.argb(Ui.TEXT, a));
	}
}
