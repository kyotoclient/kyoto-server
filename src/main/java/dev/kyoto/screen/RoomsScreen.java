package dev.kyoto.screen;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.kyoto.net.KyotoNet;
import dev.kyoto.social.Rooms;
import dev.kyoto.util.Anim;
import dev.kyoto.util.Compat;
import dev.kyoto.util.Ui;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;

/**
 * Live Rooms: pick a room (or make one) and watch together. Inside a room: the video, controls, who's watching, and chat.
 * Close the menu and the room keeps playing in a small player on your screen.
 */
public class RoomsScreen extends PanelScreen {
	private static String draftName = "", draftUrl = "", draftChat = "";
	private static boolean friendsOnly = false, everyone = false;
	private final List<JsonObject> list = new ArrayList<>();
	private String listError = null;
	private long lastList = 0;
	private EditBox chatBox, urlBox, nameBox;

	public RoomsScreen(Screen parent) {
		super("Live Rooms", parent);
	}

	@Override
	protected String icon() {
		return "play";
	}

	@Override
	protected int maxW() {
		return 600;
	}

	@Override
	protected int maxH() {
		return 360;
	}

	@Override
	protected String subtitle() {
		if (!KyotoNet.loggedIn()) return "Log in to your Kyoto account first.";
		return Rooms.inRoom() ? "Everyone sees the same second. Close this menu and it keeps playing in a small player."
				: "Watch YouTube together with Kyoto players, in game and on the website (Hub > Rooms).";
	}

	@Override
	protected void build() {
		if (!KyotoNet.loggedIn()) return;
		if (Rooms.inRoom()) buildRoom();
		else buildList();
	}

	/* ---------------- room list ---------------- */

	private void buildList() {
		refresh(false);
		int x = px + 14, y = py + 40;
		int lw = pw - 260;
		for (int i = 0; i < Math.min(list.size(), 9); i++) {
			JsonObject r = list.get(i);
			String id = r.get("id").getAsString();
			String now = r.get("now").getAsString();
			String label = r.get("name").getAsString() + "  ·  " + r.get("viewers").getAsInt() + " watching" + (now.isEmpty() ? "" : "  ·  " + now);
			btn(x, y, lw, Ui.ellipsize(label, lw - 12), () -> Rooms.join(id));
			y += 22;
		}
		btn(x, py + ph - 26, 80, "Refresh", () -> refresh(true));
		// make a room
		int cx = px + pw - 236, cy = py + 52, cw = 222;
		nameBox = new EditBox(this.font, cx, cy, cw, 18, Component.literal("room name"));
		nameBox.setMaxLength(40);
		nameBox.setValue(draftName);
		nameBox.setResponder(s -> draftName = s);
		nameBox.setHint(Component.literal(Compat.myName() + "'s room"));
		this.addRenderableWidget(nameBox);
		urlBox = new EditBox(this.font, cx, cy + 24, cw, 18, Component.literal("url"));
		urlBox.setMaxLength(300);
		urlBox.setValue(draftUrl);
		urlBox.setResponder(s -> draftUrl = s);
		urlBox.setHint(Component.literal("YouTube link to start with (optional)"));
		this.addRenderableWidget(urlBox);
		btn(cx, cy + 48, 109, friendsOnly ? "Friends only" : "Anyone can join", () -> friendsOnly = !friendsOnly);
		btn(cx + 113, cy + 48, 109, everyone ? "Everyone controls" : "Only I control", () -> everyone = !everyone);
		btn(cx, cy + 74, cw, "Create room", () -> Rooms.create(draftName, friendsOnly, everyone, draftUrl, err -> {
			if (err != null) dev.kyoto.hud.Notifications.push("Couldn't make the room", err, dev.kyoto.hud.Notifications.DANGER);
			draftUrl = "";
			rebuildWidgets();
		}));
	}

	private void refresh(boolean force) {
		if (!force && System.currentTimeMillis() - lastList < 4000) return;
		lastList = System.currentTimeMillis();
		KyotoNet.runAsync(() -> {
			try {
				JsonObject r = JsonParser.parseString(KyotoNet.send("GET", "/rooms", null, true)).getAsJsonObject();
				List<JsonObject> got = new ArrayList<>();
				for (JsonElement e : r.getAsJsonArray("rooms")) got.add(e.getAsJsonObject());
				Compat.runOnClient(() -> {
					list.clear();
					list.addAll(got);
					listError = null;
					if (Compat.currentScreen() == this && !Rooms.inRoom()) rebuildWidgets();
				});
			} catch (Exception e) {
				Compat.runOnClient(() -> listError = e.getMessage());
			}
		});
	}

	/* ---------------- inside a room ---------------- */

	private int vx, vy, vw, vh;

	private void buildRoom() {
		vx = px + 14; vy = py + 40; vw = pw - 236; vh = Math.round(vw * 9 / 16f);
		if (vy + vh > py + ph - 80) { vh = py + ph - 80 - vy; vw = Math.round(vh * 16 / 9f); }
		int by = vy + vh + 8;
		if (Rooms.canControl()) {
			btn(vx, by, 52, Rooms.playing() ? "❚❚ Pause" : "▶ Play", () -> Rooms.control(Rooms.playing() ? "pause" : "play", null, null));
			btn(vx + 54, by, 40, "-10s", () -> Rooms.control("seek", "pos", Math.max(0, Rooms.position() - 10)));
			btn(vx + 96, by, 40, "+10s", () -> Rooms.control("seek", "pos", Rooms.position() + 10));
			btn(vx + 138, by, 40, "Skip", () -> Rooms.control("skip", null, null));
		}
		urlBox = new EditBox(this.font, vx, by + 24, vw - 126, 18, Component.literal("url"));
		urlBox.setMaxLength(300);
		urlBox.setValue(draftUrl);
		urlBox.setResponder(s -> draftUrl = s);
		urlBox.setHint(Component.literal("Paste a YouTube link"));
		this.addRenderableWidget(urlBox);
		if (Rooms.canControl()) btn(vx + vw - 124, by + 23, 60, "Play now", () -> { Rooms.control("load", "url", draftUrl.trim()); draftUrl = ""; });
		btn(vx + vw - 62, by + 23, 62, "Add to queue", () -> { Rooms.control("queue", "url", draftUrl.trim()); draftUrl = ""; });
		// chat
		int cx = px + pw - 212, cw = 198;
		chatBox = new EditBox(this.font, cx, py + ph - 52, cw - 44, 18, Component.literal("chat"));
		chatBox.setMaxLength(200);
		chatBox.setValue(draftChat);
		chatBox.setResponder(s -> draftChat = s);
		chatBox.setHint(Component.literal("Say something..."));
		this.addRenderableWidget(chatBox);
		btn(cx + cw - 42, py + ph - 53, 42, "Send", this::send);
		btn(px + 14, py + ph - 26, 80, "Leave room", () -> { Rooms.leave(); rebuildWidgets(); });
		btn(px + 98, py + ph - 26, 110, "Small player: " + (Rooms.overlay ? "On" : "Off"), () -> Rooms.overlay = !Rooms.overlay);
	}

	private void send() {
		Rooms.say(draftChat.trim());
		draftChat = "";
		rebuildWidgets();
		if (chatBox != null) setFocused(chatBox);
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if ((event.key() == 257 || event.key() == 335) && chatBox != null && chatBox.isFocused()) {
			send();
			return true;
		}
		return super.keyPressed(event);
	}

	private boolean lastCanControl = false;

	@Override
	public void tick() {
		super.tick();
		if (Rooms.inRoom() && Rooms.canControl() != lastCanControl) { lastCanControl = Rooms.canControl(); rebuildWidgets(); }
	}

	@Override
	protected void drawContent(GuiGraphicsExtractor g, int mx, int my, float a) {
		if (!KyotoNet.loggedIn()) {
			Ui.text(g, "Log in to your Kyoto account to use Live Rooms.", px + 14, py + 44, Anim.argb(Ui.MUTED, a));
			return;
		}
		if (!Rooms.inRoom()) {
			if (list.isEmpty()) Ui.text(g, listError != null ? "Error: " + listError : "No rooms open right now. Make one!", px + 18, py + 46, Anim.argb(Ui.MUTED, a));
			int cx = px + pw - 236;
			Ui.bold(g, "Make a room", cx, py + 40, Anim.argb(Ui.TEXT, a));
			Ui.small(g, "People on the website can join too.", cx, py + 156, Anim.argb(Ui.MUTED, a));
			return;
		}
		// video
		Ui.round(g, vx - 2, vy - 2, vx + vw + 2, vy + vh + 2, 6, Anim.argb(0x000000, 0.6f * a));
		Identifier tex = Rooms.stream() != null ? Rooms.stream().texture() : null;
		if (tex != null) Compat.blitTint(g, tex, vx, vy, vw, vh, Anim.argb(0xFFFFFF, a));
		else g.fill(vx, vy, vx + vw, vy + vh, Anim.argb(Ui.GLASS, a));
		String st = Rooms.url().isEmpty() ? (Rooms.canControl() ? "Paste a YouTube link below" : "Waiting for the host to play something") : !Rooms.playing() ? "Paused" : Rooms.stream() == null ? "Starting..." : Rooms.stream().state;
		if (!st.isEmpty()) Ui.text(g, st, vx + vw / 2 - Ui.width(st) / 2, vy + vh / 2 - 4, Anim.argb(Ui.INK, a));
		double dur = Rooms.stream() != null ? Rooms.stream().duration : -1;
		g.fill(vx, vy + vh + 3, vx + vw, vy + vh + 5, Anim.argb(Ui.INK, 0.12f * a));
		if (dur > 0) g.fill(vx, vy + vh + 3, vx + (int) (vw * Math.min(1, Rooms.position() / dur)), vy + vh + 5, Anim.argb(Ui.accent(), a));
		String t = Rooms.title();
		JsonObject r = Rooms.room;
		int tx = Rooms.canControl() ? vx + 184 : vx;
		if (!t.isEmpty()) Ui.text(g, Ui.ellipsize(t, vx + vw - tx - 4), tx, vy + vh + 14, Anim.argb(Ui.INK, a));
		if (dur > 0) Ui.small(g, time(Rooms.position()) + " / " + time(dur), tx, vy + vh + 24, Anim.argb(Ui.MUTED, a));
		// side: room + members + chat
		int cx = px + pw - 212, cw = 198, cy = py + 40;
		Ui.round(g, cx - 4, cy - 2, cx + cw + 2, py + ph - 58, 8, Anim.argb(Ui.GLASS, 0.95f * a));
		if (r != null) {
			Ui.bold(g, Ui.ellipsize(r.get("name").getAsString(), cw - 10), cx + 2, cy + 2, Anim.argb(Ui.TEXT, a));
			Ui.small(g, r.get("viewers").getAsInt() + " watching  ·  host " + r.get("host").getAsString() + ("everyone".equals(r.get("control").getAsString()) ? "  ·  everyone controls" : ""), cx + 2, cy + 13, Anim.argb(Ui.MUTED, a));
			int hx = cx + 2;
			for (JsonElement e : Rooms.members()) {
				if (hx > cx + cw - 14) break;
				Ui.head(g, e.getAsJsonObject().get("name").getAsString(), hx, cy + 24, 12, a);
				hx += 14;
			}
		}
		g.fill(cx, cy + 40, cx + cw - 2, cy + 41, Anim.argb(Ui.INK, 0.06f * a));
		// chat, newest at the bottom
		int bottom = py + ph - 64, y = bottom;
		for (int i = Rooms.CHAT.size() - 1; i >= 0 && y > cy + 46; i--) {
			JsonObject m = Rooms.CHAT.get(i);
			boolean sys = m.has("sys") && m.get("sys").getAsBoolean();
			String text = dev.kyoto.stream.StreamFilter.apply(m.get("text").getAsString());
			List<String> lines = wrap(sys ? text : text, cw - 8);
			int hgt = lines.size() * 10 + (sys ? 0 : 10);
			y -= hgt + 3;
			if (y < cy + 46) break;
			int ly = y;
			if (!sys) {
				Ui.bold(g, m.get("name").getAsString(), cx + 2, ly, Anim.argb(m.has("me") && m.get("me").getAsBoolean() ? Ui.accent() : Ui.INK, a));
				ly += 10;
			}
			for (String l : lines) { Ui.text(g, l, cx + 2, ly, Anim.argb(sys ? Ui.MUTED : Ui.INK, a)); ly += 10; }
		}
		if (Rooms.CHAT.isEmpty()) Ui.small(g, "No messages yet.", cx + 2, bottom - 10, Anim.argb(Ui.MUTED, a));
		// queue under the controls
		if (r != null && r.has("queue")) {
			var q = r.getAsJsonArray("queue");
			if (q.size() > 0) Ui.small(g, "Up next: " + q.get(0).getAsJsonObject().get("title").getAsString() + (q.size() > 1 ? "  (+" + (q.size() - 1) + " more)" : ""), vx, vy + vh + 56, Anim.argb(Ui.MUTED, a));
		}
	}

	private static String time(double s) {
		int t = (int) s;
		return t / 3600 > 0 ? String.format("%d:%02d:%02d", t / 3600, t / 60 % 60, t % 60) : String.format("%d:%02d", t / 60, t % 60);
	}

	private static List<String> wrap(String s, int w) {
		List<String> out = new ArrayList<>();
		StringBuilder line = new StringBuilder();
		for (String word : s.split(" ")) {
			if (line.length() > 0 && Ui.width(line + " " + word) > w) { out.add(line.toString()); line.setLength(0); }
			if (line.length() > 0) line.append(' ');
			line.append(word);
		}
		if (line.length() > 0) out.add(line.toString());
		if (out.isEmpty()) out.add("");
		return out;
	}
}
