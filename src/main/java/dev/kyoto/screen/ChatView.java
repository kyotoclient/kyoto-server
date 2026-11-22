package dev.kyoto.screen;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.kyoto.config.KyotoConfig;
import dev.kyoto.hud.Notifications;
import dev.kyoto.net.KyotoNet;
import dev.kyoto.social.Emojis;
import dev.kyoto.social.Social;
import dev.kyoto.stream.ContentFilter;
import dev.kyoto.stream.StreamFilter;
import dev.kyoto.util.Anim;
import dev.kyoto.util.Compat;
import dev.kyoto.util.Ui;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * The message list used by the Hub chat, DMs and group chats: names, replies, text with emojis, pictures, stickers,
 * gifts, polls (click to vote), reactions (click to add yours), pinned marks, and hover tools (react, reply, pin, delete, report).
 */
public final class ChatView {
	/** 'chat' | 'dm:<name>' | 'g:<id>' */
	public String where = "chat";
	public boolean canPin = false, staff = false;
	public final List<JsonObject> messages = new ArrayList<>();
	public Consumer<JsonObject> onReply = m -> {};
	public Consumer<Integer> onOpenTrade = id -> {};
	public Runnable refresh = () -> {};
	public java.util.function.Consumer<String> onProfile = n -> {};

	private float scrollUp = 0, scrollTarget = 0, maxScroll = 0;
	private int pickerFor = -1, pickerX, pickerY;

	private record Hit(int x1, int y1, int x2, int y2, Runnable r) {}

	private final List<Hit> hits = new ArrayList<>();

	public void scroll(double dy) {
		scrollTarget = Math.max(0, Math.min(maxScroll, scrollTarget + (float) dy * 24));
	}

	public void toBottom() {
		scrollTarget = 0;
		scrollUp = 0;
	}

	public boolean click(double x, double y) {
		if (pickerFor >= 0) {
			for (int i = hits.size() - 1; i >= 0; i--) {
				Hit h = hits.get(i);
				if (Ui.inside(x, y, h.x1, h.y1, h.x2, h.y2)) { h.r.run(); return true; }
			}
			pickerFor = -1;
			return true;
		}
		for (int i = hits.size() - 1; i >= 0; i--) {
			Hit h = hits.get(i);
			if (Ui.inside(x, y, h.x1, h.y1, h.x2, h.y2)) {
				h.r.run();
				return true;
			}
		}
		return false;
	}

	/* ------------------------------------------------ drawing ------------------------------------------------ */

	public void draw(GuiGraphicsExtractor g, int x1, int y1, int x2, int y2, int mx, int my, float a) {
		hits.clear();
		boolean inside = my >= y1 && my < y2 && mx >= x1 && mx < x2;
		scrollUp += (Math.min(scrollTarget, maxScroll) - scrollUp) * 0.35f;
		int w = x2 - x1 - 8;
		// measure first: newest messages sit at the bottom
		int[] hs = new int[messages.size()];
		int total = 0;
		for (int i = 0; i < messages.size(); i++) {
			hs[i] = layout(g, messages.get(i), i > 0 ? messages.get(i - 1) : null, x1 + 4, 0, w, mx, my, a, true, false);
			total += hs[i] + 4;
		}
		int viewH = y2 - y1 - 8;
		maxScroll = Math.max(0, total - viewH);
		int y = y1 + 4 + Math.max(0, viewH - total) - Math.round(maxScroll - Math.min(scrollUp, maxScroll));
		g.enableScissor(x1, y1, x2, y2);
		JsonObject hoverMsg = null;
		int hoverY = 0, hoverH = 0;
		for (int i = 0; i < messages.size(); i++) {
			int h = hs[i];
			if (y + h >= y1 - 10 && y <= y2 + 10) {
				JsonObject m = messages.get(i);
				boolean hv = inside && my >= y - 2 && my < y + h + 2;
				if (hv && !m.has("sys")) { Ui.round(g, x1 + 1, y - 2, x2 - 1, y + h + 2, 5, Anim.argb(Ui.INK, 0.03f * a)); hoverMsg = m; hoverY = y; hoverH = h; }
				layout(g, m, i > 0 ? messages.get(i - 1) : null, x1 + 4, y, w, mx, my, a, false, inside);
			}
			y += h + 4;
		}
		if (hoverMsg != null && pickerFor < 0) tools(g, hoverMsg, x2 - 4, hoverY - 1, mx, my, a);
		g.disableScissor();
		if (messages.isEmpty()) Ui.text(g, "Nothing here yet - say hi!", x1 + 10, y1 + 8, Anim.argb(Ui.MUTED, a));
		if (pickerFor >= 0) picker(g, mx, my, a);
	}

	private boolean grouped(JsonObject m, JsonObject prev) {
		if (prev == null || prev.has("sys") || m.has("sys") || m.has("reply") || m.has("poll") || m.has("gift")) return false;
		return str(prev, "name").equals(str(m, "name")) && m.get("at").getAsLong() - prev.get("at").getAsLong() < 5 * 60000;
	}

	/** Draws (or just measures) one message. Returns its height. */
	private int layout(GuiGraphicsExtractor g, JsonObject m, JsonObject prev, int x, int y, int w, int mx, int my, float a, boolean measure, boolean inside) {
		if (m.has("sys")) {
			String t = clean(str(m, "text"));
			int tw = Ui.widthSmall(t) + 14;
			boolean trade = m.has("trade");
			int bw = trade ? Ui.widthSmall("Open trade") + 10 : 0;
			int sx = x + (w - tw - bw - (trade ? 6 : 0)) / 2;
			if (!measure) {
				Ui.iconTex(g, m.has("streak") ? "flame" : trade ? "swap" : "sparkle", sx, y + 2, 9, Anim.argb(m.has("streak") ? Ui.MUTED : Ui.accent(), a));
				Ui.small(g, t, sx + 12, y + 3, Anim.argb(m.has("streak") ? Ui.MUTED : Ui.MUTED, a));
				if (trade) {
					int bx = sx + tw + 6, id = m.get("trade").getAsInt();
					pill(g, bx, y, "Open trade", Ui.TEXT, mx, my, a, inside, () -> onOpenTrade.accept(id));
				}
			}
			return 13;
		}
		boolean grp = grouped(m, prev), me = bool(m, "me"), discord = bool(m, "discord");
		int tx = x + 22, tw = w - 22, h = 0;
		// reply quote
		if (m.has("reply") && m.get("reply").isJsonObject()) {
			JsonObject r = m.getAsJsonObject("reply");
			String rt = r.has("sticker") && !r.get("sticker").isJsonNull() ? "[sticker]" : bool(r, "img") && str(r, "text").isEmpty() ? "[picture]" : clean(str(r, "text"));
			if (!measure) {
				g.fill(x + 8, y + 5, x + 9, y + 10, Anim.argb(Ui.DIM, a));
				g.fill(x + 8, y + 4, x + 20, y + 5, Anim.argb(Ui.DIM, a));
				Ui.head(g, str(r, "name"), tx, y, 8, a);
				String line = str(r, "name") + ": " + rt;
				Ui.small(g, Ui.ellipsize(line, Math.round((tw - 12) / 0.75f)), tx + 11, y + 1, Anim.argb(Ui.MUTED, a));
			}
			h += 11;
		}
		// name line
		if (!grp) {
			if (!measure) nameLine(g, m, x, y + h, a, inside, discord);
			h += 13;
		}
		// text
		String text = str(m, "text");
		if (!text.isEmpty() && !m.has("gift")) {
			String shown = StreamFilter.apply(ContentFilter.clean(text));
			int th = Emojis.draw(g, shown, tx, y + h + 1, tw, me ? Ui.INK : Ui.MUTED, a, measure);
			h += th + 1;
		}
		// picture
		String img = m.has("img") && !m.get("img").isJsonNull() ? m.get("img").getAsString() : "";
		if (!img.isEmpty()) {
			boolean dm = !"chat".equals(where);
			Pic p = pic(img, dm);
			int maxW = Math.min(160, tw), maxH = 90, pw = maxW, ph = 60;
			if (p != null) { float k = Math.min(maxW / (float) p.w(), maxH / (float) p.h()); pw = Math.max(8, Math.round(p.w() * k)); ph = Math.max(8, Math.round(p.h() * k)); }
			if (!measure) {
				int iy = y + h + 2;
				boolean hv = inside && Ui.inside(mx, my, tx, iy, tx + pw, iy + ph);
				Ui.round(g, tx - 1, iy - 1, tx + pw + 1, iy + ph + 1, 5, Anim.argb(hv ? Ui.accent() : 0x000000, (hv ? 0.6f : 0.35f) * a));
				if (p != null) Compat.blitTint(g, p.tex(), tx, iy, pw, ph, Anim.argb(0xFFFFFF, a));
				else Ui.small(g, "Loading picture...", tx + 6, iy + ph / 2 - 3, Anim.argb(Ui.MUTED, a));
				String url = KyotoNet.baseUrl() + (dm ? "/dm/img?id=" + img + "&auth=" + KyotoConfig.get().netToken : "/chat/img?id=" + img);
				if (inside) hits.add(new Hit(tx, iy, tx + pw, iy + ph, () -> Compat.openUrl(url)));
			}
			h += ph + 4;
		}
		// voice message: plays in your browser (the game can't play that audio format)
		if (m.has("voice") && m.get("voice").isJsonObject()) {
			JsonObject v = m.getAsJsonObject("voice");
			int dur = v.has("dur") ? v.get("dur").getAsInt() : 0;
			String label = "Voice message " + (dur / 60) + ":" + String.format("%02d", dur % 60);
			int vw = Math.min(tw, Ui.width(label) + 60), vh = 18;
			if (!measure) {
				int vy = y + h + 2;
				boolean hv = inside && Ui.inside(mx, my, tx, vy, tx + vw, vy + vh);
				Ui.round(g, tx, vy, tx + vw, vy + vh, 9, Anim.argb(hv ? Ui.accent() : Ui.INK, (hv ? 0.3f : 0.08f) * a));
				Ui.round(g, tx + 3, vy + 3, tx + 15, vy + 15, 6, Anim.argb(Ui.accent(), a));
				Ui.iconTex(g, "play", tx + 5, vy + 5, 8, Anim.argb(Ui.GLASS, a));
				Ui.small(g, label, tx + 20, vy + 6, Anim.argb(Ui.TEXT, a));
				Ui.small(g, "Play", tx + vw - Ui.widthSmall("Play") - 7, vy + 6, Anim.argb(Ui.accent(), a));
				String url = KyotoNet.baseUrl() + "/chat/voice?id=" + str(v, "id");
				if (inside) hits.add(new Hit(tx, vy, tx + vw, vy + vh, () -> Compat.openUrl(url)));
			}
			h += vh + 4;
		}
		// sticker
		if (m.has("sticker") && !m.get("sticker").isJsonNull()) {
			if (!measure) Compat.blitTint(g, Social.sticker(m.get("sticker").getAsString()), tx, y + h + 1, 52, 52, Anim.argb(0xFFFFFF, a));
			h += 55;
		}
		// gift
		if (m.has("gift") && m.get("gift").isJsonObject()) {
			JsonObject gi = m.getAsJsonObject("gift");
			int gw = Math.min(220, tw), gh = 34;
			if (!measure) {
				int gy = y + h + 2;
				Ui.round(g, tx, gy, tx + gw, gy + gh, 7, Anim.argb(Ui.MUTED, 0.14f * a));
				Ui.outline(g, tx, gy, tx + gw, gy + gh, Anim.argb(Ui.MUTED, 0.4f * a));
				Ui.iconTex(g, "gift", tx + 7, gy + 9, 16, Anim.argb(Ui.MUTED, a));
				Ui.small(g, me ? "You sent a gift" : str(m, "name") + " sent you a gift", tx + 29, gy + 4, Anim.argb(Ui.MUTED, a));
				Ui.bold(g, Ui.ellipsize(clean(str(gi, "title")), gw - 80), tx + 29, gy + 12, Anim.argb(Ui.TEXT, a));
				String note = clean(str(gi, "note"));
				if (!note.isEmpty()) Ui.small(g, Ui.ellipsize("\"" + note + "\"", Math.round((gw - 80) / 0.75f)), tx + 29, gy + 24, Anim.argb(Ui.MUTED, a));
				if (!me) {
					int id = gi.get("id").getAsInt();
					pill(g, tx + gw - Ui.widthSmall("Wear it") - 16, gy + 11, "Wear it", Ui.INK, mx, my, a, inside, () -> post("/library/wear", body("id", id), "Wearing it! Others see it within a minute."));
				}
			}
			h += gh + 4;
		}
		// poll
		if (m.has("poll") && m.get("poll").isJsonObject()) {
			JsonObject p = m.getAsJsonObject("poll");
			JsonArray opts = p.getAsJsonArray("opts");
			int pw = Math.min(230, tw), total = p.get("total").getAsInt();
			boolean closed = bool(p, "closed"), voted = false;
			for (JsonElement e : opts) if (bool(e.getAsJsonObject(), "me")) voted = true;
			boolean show = voted || closed;
			int ph = 16 + opts.size() * 15 + 12;
			if (!measure) {
				int py = y + h + 2;
				Ui.round(g, tx, py, tx + pw, py + ph, 7, Anim.argb(Ui.INK, 0.05f * a));
				Ui.iconTex(g, "poll", tx + 6, py + 4, 9, Anim.argb(Ui.accent(), a));
				Ui.bold(g, Ui.ellipsize(clean(str(p, "q")), pw - 24), tx + 18, py + 4, Anim.argb(Ui.TEXT, a));
				int oy = py + 16, id = m.get("id").getAsInt();
				for (int i = 0; i < opts.size(); i++) {
					JsonObject o = opts.get(i).getAsJsonObject();
					int n = o.get("n").getAsInt();
					boolean mine = bool(o, "me");
					boolean hv = !closed && inside && Ui.inside(mx, my, tx + 4, oy, tx + pw - 4, oy + 13);
					Ui.round(g, tx + 4, oy, tx + pw - 4, oy + 13, 4, Anim.argb(mine ? Ui.accent() : Ui.INK, (mine ? 0.18f : hv ? 0.1f : 0.05f) * a));
					if (show && total > 0) Ui.round(g, tx + 4, oy, tx + 4 + Math.round((pw - 8) * n / (float) total), oy + 13, 4, Anim.argb(Ui.accent(), 0.22f * a));
					Ui.small(g, Ui.ellipsize(clean(str(o, "t")), Math.round((pw - 50) / 0.75f)), tx + 9, oy + 4, Anim.argb(Ui.TEXT, a));
					if (show) { String pc = (total > 0 ? Math.round(n * 100f / total) : 0) + "%"; Ui.small(g, pc, tx + pw - 9 - Ui.widthSmall(pc), oy + 4, Anim.argb(Ui.MUTED, a)); }
					final int opt = i;
					if (!closed && inside) hits.add(new Hit(tx + 4, oy, tx + pw - 4, oy + 13, () -> { JsonObject b = body("id", id); b.addProperty("opt", opt); post("/msg/vote", b, null); }));
					oy += 15;
				}
				Ui.small(g, total + " vote" + (total == 1 ? "" : "s") + " · " + (bool(p, "multi") ? "pick any" : "pick one") + " · " + (closed ? "ended" : left(p.get("ends").getAsLong())), tx + 6, oy + 2, Anim.argb(Ui.MUTED, a));
			}
			h += ph + 4;
		}
		// reactions
		if (m.has("re") && m.get("re").isJsonArray() && !m.getAsJsonArray("re").isEmpty()) {
			if (!measure) {
				int rx = tx, ry = y + h + 1, id = m.get("id").getAsInt();
				for (JsonElement e : m.getAsJsonArray("re")) {
					JsonObject r = e.getAsJsonObject();
					String key = r.get("r").getAsString(), n = String.valueOf(r.get("n").getAsInt());
					int cw = 14 + Ui.widthSmall(n) + 4;
					boolean mine = bool(r, "me"), hv = inside && Ui.inside(mx, my, rx, ry, rx + cw, ry + 12);
					Ui.round(g, rx, ry, rx + cw, ry + 12, 4, Anim.argb(mine ? Ui.accent() : Ui.INK, (mine ? 0.25f : hv ? 0.12f : 0.06f) * a));
					Compat.blitTint(g, Social.reaction(key), rx + 2, ry + 1, 10, 10, Anim.argb(0xFFFFFF, a));
					Ui.small(g, n, rx + 14, ry + 3, Anim.argb(mine ? Ui.INK : Ui.MUTED, a));
					if (inside) hits.add(new Hit(rx, ry, rx + cw, ry + 12, () -> react(id, key)));
					rx += cw + 3;
				}
			}
			h += 14;
		}
		if (!measure && bool(m, "pinned")) Ui.iconTex(g, "pin", x + w - 8, y + 1, 7, Anim.argb(Ui.INK, a));
		return Math.max(h, grp ? 10 : 14);
	}

	private void nameLine(GuiGraphicsExtractor g, JsonObject o, int x, int y, float a, boolean inside, boolean discord) {
		String name = str(o, "name");
		if (discord) {
			Ui.round(g, x, y, x + 14, y + 14, 4, Anim.argb(0x5865F2, a));
			Ui.bold(g, "D", x + 4, y + 3, Anim.argb(Ui.INK, a));
			Ui.bold(g, name, x + 22, y + 3, Anim.argb(0xC9CDFB, a));
			int nx = x + 25 + Ui.widthBold(name);
			Ui.small(g, "DISCORD  " + ago(o.get("at").getAsLong()), nx, y + 4, Anim.argb(0x8B93F8, a));
			return;
		}
		Ui.head(g, name, x, y, 14, a);
		Ui.bold(g, name, x + 22, y + 3, Anim.argb(Ui.TEXT, a));
		int nx = x + 24 + Ui.widthBold(name);
		if (bool(o, "owner")) { Ui.iconTex(g, "crown", nx, y + 3, 8, Anim.argb(Ui.INK, a)); nx += 11; }
		else if (bool(o, "staff")) { Ui.small(g, "MOD", nx, y + 4, Anim.argb(0x7FB8FF, a)); nx += Ui.widthSmall("MOD") + 4; }
		if (bool(o, "plus")) { Ui.small(g, "K+", nx, y + 4, Anim.argb(Ui.MUTED, a)); nx += Ui.widthSmall("K+") + 4; }
		if (o.has("at")) Ui.small(g, ago(o.get("at").getAsLong()), nx + 2, y + 4, Anim.argb(Ui.MUTED, a));
		if (inside && !name.isBlank()) hits.add(new Hit(x, y, x + 24 + Ui.widthBold(name), y + 14, () -> onProfile.accept(name)));
	}

	/** Hover tools: react, reply, pin, delete, report. */
	private void tools(GuiGraphicsExtractor g, JsonObject m, int right, int y, int mx, int my, float a) {
		if (!m.has("id") || !KyotoNet.loggedIn()) return;
		int id = m.get("id").getAsInt();
		boolean me = bool(m, "me"), discord = bool(m, "discord");
		List<Object[]> t = new ArrayList<>();
		if (!discord) t.add(new Object[]{"React", Ui.TEXT, (Runnable) () -> { pickerFor = id; pickerX = right; pickerY = y; }});
		t.add(new Object[]{"Reply", Ui.TEXT, (Runnable) () -> onReply.accept(m)});
		if (canPin && !discord) t.add(new Object[]{bool(m, "pinned") ? "Unpin" : "Pin", Ui.INK, (Runnable) () -> post("/msg/pin", body("id", id), null)});
		if (me || (staff && "chat".equals(where))) t.add(new Object[]{"Delete", 0xFF8A8A, (Runnable) () -> post("/msg/delete", body("id", id), "Deleted")});
		if (!me) t.add(new Object[]{"Report", Ui.MUTED, (Runnable) () -> {
			JsonObject b = new JsonObject();
			b.addProperty("kind", "chat".equals(where) ? "chat" : where.startsWith("g:") ? "group" : "dm");
			b.addProperty("id", String.valueOf(id));
			b.addProperty("where", where);
			b.addProperty("who", str(m, "name"));
			b.addProperty("text", m.has("voice") ? "[voice message]" : str(m, "text"));
			b.addProperty("reason", "Reported in game");
			KyotoNet.runAsync(() -> { try { KyotoNet.send("POST", "/hub/report", b, true); Compat.runOnClient(() -> Notifications.push("Reported", "Thanks, a mod will look at it.", Notifications.GOOD)); } catch (Exception ignored) { } });
		}});
		int x = right;
		for (int i = t.size() - 1; i >= 0; i--) {
			String label = (String) t.get(i)[0];
			int w = Ui.widthSmall(label) + 8;
			x -= w;
			pill(g, x, y, label, (Integer) t.get(i)[1], mx, my, a, true, (Runnable) t.get(i)[2]);
			x -= 2;
		}
	}

	private void picker(GuiGraphicsExtractor g, int mx, int my, float a) {
		String[] all = new String[Social.REACTIONS.length + 6];
		System.arraycopy(Social.REACTIONS, 0, all, 0, Social.REACTIONS.length);
		String[] extra = {"gg", "lol", "love", "pog", "w", "l"};
		for (int i = 0; i < 6; i++) all[Social.REACTIONS.length + i] = extra[i];
		int sz = 16, gap = 2, w = all.length * (sz + gap) + 6, x = Math.max(4, pickerX - w), y = pickerY - sz - 8;
		Ui.round(g, x, y, x + w, y + sz + 6, 6, Anim.argb(Ui.GLASS, 0.98f));
		Ui.outline(g, x, y, x + w, y + sz + 6, Anim.argb(Ui.INK, 0.12f));
		int id = pickerFor;
		for (int i = 0; i < all.length; i++) {
			int ix = x + 3 + i * (sz + gap), iy = y + 3;
			boolean hv = Ui.inside(mx, my, ix, iy, ix + sz, iy + sz);
			if (hv) Ui.round(g, ix - 1, iy - 1, ix + sz + 1, iy + sz + 1, 4, Anim.argb(Ui.INK, 0.15f));
			Identifier tex = i < Social.REACTIONS.length ? Social.reaction(all[i]) : Social.sticker(all[i]);
			Compat.blitTint(g, tex, ix + (hv ? -1 : 0), iy + (hv ? -1 : 0), sz + (hv ? 2 : 0), sz + (hv ? 2 : 0), Anim.argb(0xFFFFFF, a));
			String key = all[i];
			hits.add(new Hit(ix, iy, ix + sz, iy + sz, () -> { pickerFor = -1; react(id, key); }));
		}
	}

	private void pill(GuiGraphicsExtractor g, int x, int y, String label, int col, int mx, int my, float a, boolean inside, Runnable r) {
		int w = Ui.widthSmall(label) + 8;
		boolean hv = inside && Ui.inside(mx, my, x, y, x + w, y + 11);
		Ui.round(g, x, y, x + w, y + 11, 4, Anim.argb(Ui.GLASS, (hv ? 1f : 0.92f) * a));
		Ui.round(g, x, y, x + w, y + 11, 4, Anim.argb(Ui.INK, (hv ? 0.14f : 0.05f) * a));
		Ui.small(g, label, x + 4, y + 2, Anim.argb(col, a));
		if (inside) hits.add(new Hit(x, y, x + w, y + 11, r));
	}

	/* ------------------------------------------------ actions ------------------------------------------------ */

	private JsonObject body(String k, int v) {
		JsonObject b = new JsonObject();
		b.addProperty("where", where);
		b.addProperty(k, v);
		return b;
	}

	private void react(int id, String r) {
		JsonObject b = body("id", id);
		b.addProperty("r", r);
		post("/msg/react", b, null);
	}

	private void post(String path, JsonObject b, String ok) {
		KyotoNet.runAsync(() -> {
			try {
				KyotoNet.send("POST", path, b, true);
				Compat.runOnClient(() -> { if (ok != null) Notifications.push(ok, "", Notifications.GOOD); refresh.run(); });
			} catch (Exception e) {
				Compat.runOnClient(() -> Notifications.push("Couldn't do that", Social.msg(e), Notifications.DANGER));
			}
		});
	}

	/* ------------------------------------------------ pictures ------------------------------------------------ */

	private record Pic(Identifier tex, int w, int h) {}

	private static final Map<String, Pic> PICS = new ConcurrentHashMap<>();
	private static final java.util.Set<String> LOADING = ConcurrentHashMap.newKeySet();

	private static Pic pic(String id, boolean dm) {
		Pic p = PICS.get(id);
		if (p != null || LOADING.contains(id)) return p;
		LOADING.add(id);
		KyotoNet.runAsync(() -> {
			try {
				byte[] data = dm ? KyotoNet.bytesAuth("/dm/img?id=" + id) : KyotoNet.bytes("/chat/img?id=" + id);
				java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(data));
				if (img == null) return;
				float k = Math.min(1f, 512f / Math.max(img.getWidth(), img.getHeight()));
				java.awt.image.BufferedImage small = new java.awt.image.BufferedImage(Math.max(1, Math.round(img.getWidth() * k)), Math.max(1, Math.round(img.getHeight() * k)), java.awt.image.BufferedImage.TYPE_INT_ARGB);
				java.awt.Graphics2D g = small.createGraphics();
				g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
				g.drawImage(img, 0, 0, small.getWidth(), small.getHeight(), null);
				g.dispose();
				java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
				javax.imageio.ImageIO.write(small, "png", o);
				Identifier t = Identifier.fromNamespaceAndPath("kyoto", "chatpic/" + id);
				int w = small.getWidth(), h = small.getHeight();
				Compat.registerPng(t, o.toByteArray(), () -> PICS.put(id, new Pic(t, w, h)));
			} catch (Exception ignored) {
			}
		});
		return null;
	}

	/* ------------------------------------------------ helpers ------------------------------------------------ */

	static String str(JsonObject o, String k) {
		return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : "";
	}

	static boolean bool(JsonObject o, String k) {
		return o.has(k) && !o.get(k).isJsonNull() && o.get(k).getAsBoolean();
	}

	static String clean(String s) {
		return StreamFilter.apply(ContentFilter.clean(s));
	}

	static String ago(long at) {
		long s = Math.max(0, (System.currentTimeMillis() - at) / 1000);
		if (s < 60) return "now";
		if (s < 3600) return s / 60 + "m";
		if (s < 86400) return s / 3600 + "h";
		return s / 86400 + "d";
	}

	static String left(long t) {
		long s = Math.max(0, (t - System.currentTimeMillis()) / 1000);
		return s < 3600 ? (s / 60 + 1) + " min left" : s < 86400 ? Math.round(s / 3600f) + " h left" : Math.round(s / 86400f) + " days left";
	}

	static List<JsonObject> list(String json, String key) {
		List<JsonObject> out = new ArrayList<>();
		JsonObject r = JsonParser.parseString(json).getAsJsonObject();
		if (r.has(key)) for (JsonElement e : r.getAsJsonArray(key)) out.add(e.getAsJsonObject());
		return out;
	}
}
