package dev.kyoto.screen;

import dev.kyoto.net.KyotoNet;
import dev.kyoto.social.Calls;
import dev.kyoto.util.Anim;
import dev.kyoto.util.Compat;
import dev.kyoto.util.Ui;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** Friends: add people, see who's online and where, join their server, message or call them. */
public class FriendsScreen extends PanelScreen {
	private EditBox search;
	private String typed = "";
	private int page = 0;

	private record Line(int y, String name, String status, int color) {}

	private final List<Line> lines = new ArrayList<>();

	public FriendsScreen(Screen parent) {
		super("Friends", parent);
	}

	@Override
	protected int maxW() {
		return 480;
	}

	@Override
	protected String subtitle() {
		if (!KyotoNet.loggedIn()) return "Connecting to Kyoto... (happens on its own)";
		return "● " + KyotoNet.online + " online on Kyoto" + (KyotoNet.unreadDms > 0 ? "  ·  " + KyotoNet.unreadDms + " unread" : "");
	}

	@Override
	protected String icon() {
		return "friends";
	}

	@Override
	protected void build() {
		lines.clear();
		search = new EditBox(this.font, px + 16, py + 38, 150, 18, Component.literal("Username"));
		search.setMaxLength(16);
		search.setHint(Ui.t("Add by username..."));
		search.setValue(typed);
		search.setResponder(s -> typed = s);
		this.addRenderableWidget(search);
		btn(px + 172, py + 37, 40, "Add", () -> {
			if (!typed.isBlank()) KyotoNet.friendRequest(typed.trim());
		});
		btn(px + 216, py + 37, 56, "Profile", () -> {
			if (!typed.isBlank()) Compat.openScreen(new ProfileScreen(this, typed.trim()));
		});
		btn(px + pw - 104, py + 37, 90, "Messages" + (KyotoNet.unreadDms > 0 ? " (" + KyotoNet.unreadDms + ")" : ""), () -> Compat.openScreen(new DmScreen(this, null)));
		if (Calls.inCall() || Calls.ringing() != null) btn(px + 278, py + 37, pw - 104 - 282, Calls.inCall() ? "Hang up" : "Answer " + Calls.ringing(), () -> {
			if (Calls.inCall()) Calls.hangup();
			else Calls.accept();
		});

		int y = py + 64;
		int perPage = Math.max(4, (ph - 100) / 22);
		List<Object[]> all = new ArrayList<>();
		for (String n : KyotoNet.INCOMING) all.add(new Object[]{"in", n});
		List<KyotoNet.Friend> friends = new ArrayList<>(KyotoNet.FRIENDS);
		friends.sort((a, b) -> Boolean.compare(b.online(), a.online()));
		for (KyotoNet.Friend f : friends) all.add(new Object[]{"friend", f});
		for (String n : KyotoNet.OUTGOING) all.add(new Object[]{"out", n});
		int pages = Math.max(1, (all.size() + perPage - 1) / perPage);
		page = clamp(page, 0, pages - 1);
		String myIp = Compat.currentServerIp();
		for (int i = page * perPage; i < Math.min(all.size(), (page + 1) * perPage); i++) {
			Object[] e = all.get(i);
			final int ry = y;
			int bx = px + pw - 14;
			switch ((String) e[0]) {
				case "in" -> {
					String n = (String) e[1];
					lines.add(new Line(ry, n, "wants to be your friend", Ui.INK));
					btn(bx - 128, ry, 62, "Accept", () -> KyotoNet.friendAccept(n));
					btn(bx - 62, ry, 62, "Decline", () -> KyotoNet.friendRemove(n));
				}
				case "out" -> {
					String n = (String) e[1];
					lines.add(new Line(ry, n, "request sent", Ui.MUTED));
					btn(bx - 62, ry, 62, "Cancel", () -> KyotoNet.friendRemove(n));
				}
				default -> {
					KyotoNet.Friend f = (KyotoNet.Friend) e[1];
					String mode = dev.kyoto.social.Social.mode(f.name()), msg = dev.kyoto.social.Social.statusText(f.name());
					int streak = dev.kyoto.social.Social.streak(f.name());
					String st = (f.online() ? (mode.equals("online") || mode.equals("offline") ? "Online" : dev.kyoto.social.Social.modeName(mode)) + (f.server().isBlank() ? "" : " · " + f.server()) : "Offline")
							+ (msg.isBlank() ? "" : " · \"" + dev.kyoto.stream.StreamFilter.apply(dev.kyoto.stream.ContentFilter.clean(msg)) + "\"") + (streak > 0 ? " · " + streak + " day streak" : "");
					lines.add(new Line(ry, f.name(), st, f.online() ? (mode.equals("busy") ? 0xFF5D7A : mode.equals("away") ? Ui.INK : Ui.INK) : Ui.MUTED));
					btn(bx - 20, ry, 20, "×", () -> KyotoNet.friendRemove(f.name()));
					btn(bx - 74, ry, 52, "Message", () -> Compat.openScreen(new DmScreen(this, f.name())));
					if (f.online()) {
						boolean sameServer = myIp != null && myIp.equalsIgnoreCase(f.join());
						if (!f.join().isBlank() && !sameServer) btn(bx - 116, ry, 40, "Join", () -> Compat.joinServer(f.join(), f.name() + "'s server"));
						btn(bx - (f.join().isBlank() || sameServer ? 116 : 158), ry, 40, "Call", () -> Calls.call(f.name()));
					}
				}
			}
			y += 22;
		}
		if (all.isEmpty()) lines.add(new Line(y, "", "No friends yet. Type a username above and press Add.", Ui.MUTED));
		pager(px + 14, py + ph - 26, 100, page, pages, v -> page = v);
	}

	@Override
	protected void drawContent(GuiGraphicsExtractor g, int mx, int my, float a) {
		for (Line l : lines) {
			if (!l.name().isEmpty()) {
				Ui.head(g, l.name(), px + 16, l.y() + 2, 16, a);
				Ui.bold(g, l.name(), px + 38, l.y() + 1, Anim.argb(Ui.TEXT, a));
				Ui.round(g, px + 38, l.y() + 13, px + 42, l.y() + 17, 2, Anim.argb(l.color(), a));
				Ui.text(g, Ui.ellipsize(l.status(), pw - 250), px + 46, l.y() + 11, Anim.argb(l.color(), a));
			} else {
				Ui.text(g, l.status(), px + 16, l.y() + 4, Anim.argb(l.color(), a));
			}
		}
		if (Calls.inCall()) {
			String s = "On a call with " + Calls.partner() + (Calls.muted ? " (muted, M to unmute)" : "");
			Ui.text(g, s, px + 130, py + ph - 20, Anim.argb(Ui.INK, a));
		} else if (!Compat.voiceChat()) {
			Ui.small(g, "Calls need the Simple Voice Chat mod installed.", px + 130, py + ph - 18, Anim.argb(Ui.MUTED, a));
		}
	}
}
