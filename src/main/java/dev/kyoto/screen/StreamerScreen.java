package dev.kyoto.screen;

import dev.kyoto.module.Modules;
import dev.kyoto.stream.StreamAlerts;
import dev.kyoto.stream.TikTokLive;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import dev.kyoto.util.Anim;
import dev.kyoto.util.Compat;
import dev.kyoto.util.Ui;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;

/** Connect Streamlabs (Twitch / YouTube / Kick donations, Super Chats, bits, subs) + test alerts + streamer privacy. */
public class StreamerScreen extends PanelScreen {
	public StreamerScreen(Screen parent) {
		super("Streamer", parent);
	}

	@Override
	protected String icon() {
		return "stream";
	}

	@Override
	protected int maxH() {
		return 360;
	}

	@Override
	protected String subtitle() {
		return "Your token is saved on your PC only and is never shown on screen.";
	}

	private static int tab = 0; // 0 Streamlabs, 1 TikTok
	private static String ttDraft = null;

	@Override
	protected void build() {
		btn(px + 14, py + 36, 114, (tab == 0 ? "» " : "") + "Streamlabs", () -> tab = 0);
		btn(px + 130, py + 36, 114, (tab == 1 ? "» " : "") + "TikTok LIVE", () -> tab = 1);
		if (tab == 1) { buildTikTok(); return; }
		int y = py + 60;
		btn(px + 14, y, 230, "Stream alerts: " + (Modules.on("stream_alerts") ? "ON" : "OFF"), () -> Modules.get("stream_alerts").toggle());
		y += 24;
		btn(px + 14, y, 112, "Get my token ↗", () -> Compat.openUrl("https://streamlabs.com/dashboard#/settings/api-settings"));
		btn(px + 132, y, 112, "Paste token", () -> {
			String clip = this.minecraft == null ? "" : this.minecraft.keyboardHandler.getClipboard();
			if (clip != null && clip.trim().length() > 20 && !clip.contains(" ")) {
				StreamAlerts.setToken(clip.trim());
				if (this.minecraft != null) this.minecraft.keyboardHandler.setClipboard(""); // don't leave it on the clipboard
			}
		});
		y += 22;
		btn(px + 14, y, 230, "Forget token", () -> StreamAlerts.setToken(""));
		y += 26;
		// test alerts: work even with Stream alerts OFF and no token - see exactly how each one looks
		String[][] tests = {{"Dono", "Donation"}, {"Super Chat", "Super Chat"}, {"Bits", "Bits"}, {"Sub", "Subscription"},
				{"Follow", "New follower"}, {"Raid", "Raid"}, {"Big dono", "Big"}, {"All", "*"}};
		for (int i = 0; i < tests.length; i++) {
			String kind = tests[i][1];
			btn(px + 14 + (i % 4) * 58, y + (i / 4) * 20, 56, tests[i][0], () -> {
				if (kind.equals("*")) StreamAlerts.testAll();
				else StreamAlerts.test(kind);
			});
		}
		y += 40;
		btn(px + 14, y, 230, "Clear alerts", StreamAlerts::clear);
		y += 26;
		cycle(y, "stream.position");
		y += 22;
		cycle(y, "stream.duration");
		y += 22;
		cycle(y, "stream.messages");
		y += 22;
		cycle(y, "stream.sound");
		y += 26;
		btn(px + 14, y, 230, "Streamer Mode (hide coords, IP): " + (Modules.on("streamer_privacy") ? "ON" : "OFF"), () -> Modules.get("streamer_privacy").toggle());
	}

	private void buildTikTok() {
		int y = py + 60;
		if (ttDraft == null) ttDraft = TikTokLive.username();
		EditBox box = new EditBox(this.font, px + 14, y + 1, 150, 18, Component.literal("TikTok username"));
		box.setMaxLength(24);
		box.setValue(ttDraft);
		box.setResponder(v -> ttDraft = v);
		box.setHint(Component.literal("@username"));
		this.addRenderableWidget(box);
		boolean on = TikTokLive.live || TikTokLive.status.startsWith("Connecting") || TikTokLive.status.startsWith("Reconnecting");
		btn(px + 168, y, 76, on ? "Disconnect" : "Connect", () -> {
			if (on) TikTokLive.disconnect();
			else if (!ttDraft.isBlank()) TikTokLive.connect(ttDraft);
		});
		y += 26;
		// gift previews: see exactly how each one looks (works even when you're not live)
		String[][] gifts = {{"Rose", "Rose"}, {"Heart", "Finger Heart"}, {"Doughnut", "Doughnut"}, {"Corgi", "Corgi"},
				{"Money Gun", "Money Gun"}, {"Galaxy", "Galaxy"}, {"Lion", "Lion"}, {"Universe", "TikTok Universe"},
				{"Rose x25", "Rose*25"}, {"Follow", "follow"}, {"Likes", "likes"}, {"All", "all"}};
		for (int i = 0; i < gifts.length; i++) {
			String g = gifts[i][1];
			btn(px + 14 + (i % 4) * 58, y + (i / 4) * 20, 56, gifts[i][0], () -> {
				String[] p = g.split("\\*");
				TikTokLive.test(p[0], p.length > 1 ? Integer.parseInt(p[1]) : 1);
			});
		}
		y += 64;
		btn(px + 14, y, 230, "Clear alerts", () -> { StreamAlerts.clear(); TikTokLive.clear(); });
		y += 26;
		cycle(y, "tiktok.min");
		y += 22;
		cycle(y, "tiktok.follows");
		y += 22;
		cycle(y, "tiktok.likes");
		y += 22;
		cycle(y, "stream.position");
	}

	private void cycle(int y, String key) {
		var s = Modules.setting(key);
		cycleRow(y, s);
	}

	@Override
	protected void drawContent(GuiGraphicsExtractor g, int mx, int my, float a) {
		int x = px + 262, y = py + 36, x2 = px + pw - 14;
		if (x2 - x < 120) return;
		if (tab == 1) {
			Ui.round(g, x, y, x2, py + ph - 32, 8, Anim.argb(Ui.GLASS, 0.95f * a));
			Ui.bold(g, "TikTok LIVE", x + 8, y + 7, Anim.argb(Ui.TEXT, a));
			int dot = TikTokLive.live ? Ui.INK : TikTokLive.status.startsWith("Not") ? Ui.MUTED : Ui.INK;
			Ui.round(g, x + 8, y + 22, x + 13, y + 27, 2, Anim.argb(dot, a));
			Ui.text(g, Ui.ellipsize(TikTokLive.status, x2 - x - 26), x + 18, y + 20, Anim.argb(Ui.MUTED, a));
			// gift showcase
			String[] show = {"rose", "finger_heart", "doughnut", "corgi", "galaxy", "lion", "universe", "money_gun"};
			int gx = x + 8, gy = y + 36;
			long now = System.currentTimeMillis();
			for (int i = 0; i < show.length; i++) {
				int cx = gx + (i % 4) * 26, cy = gy + (i / 4) * 26;
				int bob = Math.round((float) Math.sin(now / 300.0 + i) * 1.2f);
				Compat.blitTint(g, Ui.tex("textures/gui/gifts/" + show[i] + ".png"), cx, cy + bob, 22, 22, Anim.argb(0xFFFFFF, a));
			}
			String[] steps = {"1. Type your TikTok username", "   and press Connect.", "2. Go LIVE - gifts, follows and like", "   milestones show up in game.",
					"If you're not live yet it keeps", "trying every minute.", "", "Preview any gift:", "/kyoto testdono tiktok galaxy", "/kyoto testdono tiktok rose 25",
					"", "Unofficial: TikTok has no public", "LIVE API, so it can break when", "TikTok changes things."};
			for (int i = 0; i < steps.length; i++) Ui.small(g, steps[i], x + 8, gy + 58 + i * 9, Anim.argb(Ui.MUTED, a));
			return;
		}
		Ui.round(g, x, y, x2, py + ph - 32, 8, Anim.argb(Ui.GLASS, 0.95f * a));
		Ui.bold(g, "Status", x + 8, y + 7, Anim.argb(Ui.TEXT, a));
		boolean ok = StreamAlerts.status.startsWith("Connected");
		Ui.round(g, x + 8, y + 22, x + 13, y + 27, 2, Anim.argb(ok ? Ui.INK : StreamAlerts.hasToken() ? Ui.INK : Ui.MUTED, a));
		Ui.text(g, StreamAlerts.hasToken() ? StreamAlerts.status : "No token yet", x + 18, y + 20, Anim.argb(Ui.MUTED, a));
		String[] steps = {"1. Make a free streamlabs.com account", "   and connect Twitch / YouTube / Kick.", "2. Press \"Get my token\" and copy the",
				"   Socket API Token.", "3. Press \"Paste token\" here.", "4. Turn Stream alerts ON.", "", "Test buttons work even with alerts OFF.", "Or type /kyoto testdono 25 hello",
				"Messages are filtered: bad words are", "hidden and unsafe links removed."};
		for (int i = 0; i < steps.length; i++) Ui.small(g, steps[i], x + 8, y + 40 + i * 9, Anim.argb(Ui.MUTED, a));
	}
}
