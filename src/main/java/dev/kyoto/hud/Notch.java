package dev.kyoto.hud;

import dev.kyoto.module.Modules;
import dev.kyoto.music.Music;
import dev.kyoto.util.Anim;
import dev.kyoto.util.Compat;
import dev.kyoto.util.Ui;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.ArrayDeque;

/**
 * The Kyoto notch: a Dynamic Island at the top of the screen. It rests as a small pill (clock, FPS or a dot) and springs open
 * for the song that's playing, pop-ups, friends joining and switches you flip, then shrinks back.
 */
public final class Notch {
	private Notch() {}

	private record Pop(String title, String body, int color, String head, String icon, long dur) {}

	private static final ArrayDeque<Pop> QUEUE = new ArrayDeque<>();
	private static Pop cur = null;
	private static long curAt = 0;
	private static String lastTrack = "";
	private static long trackAt = 0;
	private static float w = 60, h = 16, vw = 0, vh = 0, content = 0, shown = 0;
	private static int lastMode = -1;
	private static long modeAt = 0;

	private static long hoverAt = 0;

	private record Btn(int x1, int y1, int x2, int y2, Runnable r) {}

	private static final java.util.List<Btn> BUTTONS = new java.util.ArrayList<>();

	/** Clicks on the open notch (called for every screen). True = handled. */
	public static boolean click(double mx, double my) {
		for (Btn b : new java.util.ArrayList<>(BUTTONS)) {
			if (mx >= b.x1 && mx < b.x2 && my >= b.y1 && my < b.y2) {
				b.r.run();
				dev.kyoto.ui.Sounds.play(dev.kyoto.ui.Sounds.TAB);
				return true;
			}
		}
		return false;
	}

	/** The open notch: time, what's playing with controls, friends online and quick switches. */
	private static void expandedView(GuiGraphicsExtractor g, int x1, int y1, int x2, int y2, float ca, long now, float mx, float my) {
		Minecraft mc = Minecraft.getInstance();
		int acc = Ui.accent();
		// top row: time + date, fps / ping on the right
		java.time.LocalTime lt = java.time.LocalTime.now();
		Ui.boldScaled(g, lt.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm")), x1 + 14, y1 + 9, 1.35f, Anim.argb(Ui.INK, ca));
		java.time.LocalDate d = java.time.LocalDate.now();
		Ui.small(g, d.getDayOfWeek().getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.ENGLISH) + " " + d.getDayOfMonth() + " " + d.getMonth().getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.ENGLISH), x1 + 56, y1 + 13, Anim.argb(Ui.MUTED, ca));
		int ping = Compat.latency();
		String right = mc.getFps() + " fps" + (ping > 0 ? "  ·  " + ping + " ms" : "");
		Ui.small(g, right, x2 - 14 - Ui.widthSmall(right), y1 + 13, Anim.argb(Ui.MUTED, ca));
		// music card
		int my1 = y1 + 28, my2 = y1 + 62;
		Ui.round(g, x1 + 10, my1, x2 - 10, my2, 10, Anim.argb(Ui.INK, 0.05f * ca));
		boolean music = Music.active() && !Music.title.isBlank();
		int art = my2 - my1 - 8;
		if (music) Compat.blitTint(g, dev.kyoto.music.AlbumArt.texture(), x1 + 14, my1 + 4, art, art, Anim.argb(0xFFFFFF, ca));
		else {
			Ui.round(g, x1 + 14, my1 + 4, x1 + 14 + art, my1 + 4 + art, 6, Anim.argb(acc, 0.15f * ca));
			Ui.iconTex(g, "music", x1 + 14 + art / 2 - 5, my1 + 4 + art / 2 - 5, 10, Anim.argb(acc, ca));
		}
		int tx = x1 + 20 + art;
		Ui.bold(g, Ui.ellipsize(music ? Music.title : "Nothing playing", x2 - tx - 76), tx, my1 + 7, Anim.argb(Ui.INK, ca));
		Ui.small(g, Ui.ellipsize(music ? Music.artist : "Play music in any app", Math.round((x2 - tx - 76) / 0.75f)), tx, my1 + 19, Anim.argb(Ui.MUTED, ca));
		String[][] ctl = {{"previous", "‹‹"}, {"toggle", Music.playing ? "❚❚" : "▶"}, {"next", "››"}};
		int bx = x2 - 74;
		for (String[] c : ctl) {
			boolean hv = mx >= bx && mx < bx + 20 && my >= my1 + 7 && my < my1 + 27;
			Ui.circle(g, bx + 10, my1 + 17, 9, Anim.argb(hv ? acc : Ui.INK, (hv ? 0.9f : 0.07f) * ca));
			Ui.text(g, c[1], bx + 10 - Ui.width(c[1]) / 2, my1 + 13, Anim.argb(hv ? 0xFFFFFF : Ui.INK, ca));
			final String act = c[0];
			BUTTONS.add(new Btn(bx, my1 + 7, bx + 20, my1 + 27, () -> Music.control(act)));
			bx += 22;
		}
		if (music && Music.durationMs > 0) {
			float f = Anim.clamp01(Music.positionMs() / (float) Music.durationMs);
			Ui.round(g, tx, my2 - 7, x2 - 80, my2 - 5, 1, Anim.argb(Ui.INK, 0.1f * ca));
			Ui.round(g, tx, my2 - 7, tx + Math.max(2, Math.round((x2 - 80 - tx) * f)), my2 - 5, 1, Anim.argb(acc, ca));
		}
		// quick chips
		int online = 0;
		for (var f : dev.kyoto.net.KyotoNet.FRIENDS) if (f.online()) online++;
		final int onl = online;
		String[][] chips = {{"friends", onl + " friends on"}, {"waypoint", "Copy coords"}, {"hud", dev.kyoto.module.Modules.on("hud_master") ? "Hide HUD" : "Show HUD"}, {"client", "Kyoto"}};
		int cx = x1 + 10, cyy = y1 + 70;
		for (int i = 0; i < chips.length; i++) {
			int cw = Ui.widthSmall(chips[i][1]) + 24;
			boolean hv = mx >= cx && mx < cx + cw && my >= cyy && my < cyy + 18;
			Ui.round(g, cx, cyy, cx + cw, cyy + 18, 9, Anim.argb(hv ? acc : Ui.INK, (hv ? 0.9f : 0.06f) * ca));
			Ui.iconTex(g, chips[i][0], cx + 7, cyy + 5, 8, Anim.argb(hv ? 0xFFFFFF : acc, ca));
			Ui.small(g, chips[i][1], cx + 18, cyy + 6, Anim.argb(hv ? 0xFFFFFF : Ui.INK, ca));
			final int fi = i;
			BUTTONS.add(new Btn(cx, cyy, cx + cw, cyy + 18, () -> {
				switch (fi) {
					case 0 -> Compat.openScreen(new dev.kyoto.screen.FriendsScreen(null));
					case 1 -> {
						var p = mc.player;
						if (p != null) Compat.copy(p.getBlockX() + " " + p.getBlockY() + " " + p.getBlockZ());
					}
					case 2 -> { var hm = dev.kyoto.module.Modules.get("hud_master"); if (hm != null) hm.toggle(); }
					default -> Compat.openScreen(dev.kyoto.screen.KyotoMenu.create());
				}
			}));
			cx += cw + 5;
		}
	}

	public static boolean on() {
		return Modules.on("notch");
	}

	public static void push(String title, String body, int color, long dur, String head, String icon) {
		for (Pop p : QUEUE) if (p.title.equals(title) && p.body.equals(body == null ? "" : body)) return;
		if (cur != null && cur.title.equals(title) && System.currentTimeMillis() - curAt < cur.dur - 500) {
			curAt = System.currentTimeMillis() - 300;
			return;
		}
		QUEUE.add(new Pop(title, body == null ? "" : body, color, head, icon, Math.max(1800, Math.min(dur, 5000))));
		while (QUEUE.size() > 6) QUEUE.poll();
	}

	public static void render(GuiGraphicsExtractor g, int sw, int top) {
		if (!on()) return;
		Ui.refresh();
		long now = System.currentTimeMillis();
		if (cur != null && now - curAt > cur.dur) cur = null;
		if (cur == null && !QUEUE.isEmpty()) {
			cur = QUEUE.poll();
			curAt = now;
		}
		// what to show
		int mode; // 0 idle, 1 pop, 2 music
		boolean music = Music.active() && Music.playing && !Music.title.isBlank();
		int mm = Modules.opt("notch.music");
		if (music && !Music.title.equals(lastTrack)) {
			lastTrack = Music.title;
			trackAt = now;
		}
		// hover it (with chat or any menu open) to open the full notch
		Minecraft mcN = Minecraft.getInstance();
		float mxN = Ui.mouseX(), myN = Ui.mouseY();
		boolean cursor = Compat.currentScreen() != null && mxN >= 0;
		boolean over = cursor && mxN >= sw / 2f - w / 2 - 6 && mxN <= sw / 2f + w / 2 + 6 && myN >= top && myN <= top + h + 10;
		if (over) hoverAt = now;
		boolean expanded = cursor && now - hoverAt < 350 && Modules.opt("notch.hover") == 0;
		if (expanded) mode = 3;
		else if (cur != null) mode = 1;
		else if (music && mm != 2 && (mm == 1 || now - trackAt < 5000)) mode = 2;
		else mode = 0;
		if (mode != lastMode) {
			lastMode = mode;
			modeAt = now;
			content = 0;
		}
		int idle = Modules.opt("notch.idle");
		float tw, th;
		String idleText = idle == 0 ? java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm")) : idle == 1 ? Minecraft.getInstance().getFps() + " fps" : "";
		if (mode == 3) {
			tw = 300;
			th = 104;
		} else if (mode == 1) {
			int left = cur.head != null || cur.icon != null ? 30 : 14;
			String body = Ui.ellipsize(cur.body, 220);
			tw = Math.min(300, Math.max(170, Math.max(Ui.widthBold(cur.title), Ui.width(body)) + left + 18));
			th = body.isEmpty() ? 22 : 32;
		} else if (mode == 2) {
			tw = 230;
			th = 34;
		} else if (idle == 3) {
			tw = 30;
			th = 8;
		} else if (idle == 2) {
			tw = 34;
			th = 10;
		} else {
			tw = Math.max(54, Ui.width(idleText) + 30);
			th = 16;
		}
		float target = mode == 0 && idle == 3 ? 0 : 1;
		shown += (target - shown) * 0.15f;
		if (shown < 0.01f && target == 0) return;
		// springy morph
		float k = Ui.animSpeed() <= 0 ? 1f : 0.2f;
		vw = (vw + (tw - w) * k) * 0.68f;
		vh = (vh + (th - h) * k) * 0.68f;
		w += vw;
		h += vh;
		if (Ui.animSpeed() <= 0) {
			w = tw;
			h = th;
		}
		float settle = 1 - Anim.clamp01((Math.abs(tw - w) + Math.abs(th - h)) / 24f);
		content += (settle - content) * 0.35f;
		int x1 = Math.round(sw / 2f - w / 2), y1 = top + 4, x2 = Math.round(sw / 2f + w / 2), y2 = Math.round(y1 + h);
		int r = Math.round(h / 2);
		Ui.glass(g, x1, y1, x2, y2, r, shown);
		float ca = content * shown;
		if (ca < 0.03f) return;
		g.enableScissor(x1, y1, x2, y2);
		int cy = (y1 + y2) / 2;
		BUTTONS.clear();
		if (mode == 3) {
			expandedView(g, x1, y1, x2, y2, ca, now, mxN, myN);
		} else if (mode == 1) {
			Pop p = cur;
			float life = 1 - Anim.clamp01((now - curAt) / (float) p.dur);
			int col = p.color == 0xFFFFFF || p.color == 0xE6E6E6 || p.color == 0xBDBDBD ? Ui.accent() : p.color;
			int left = 14;
			if (p.head != null) {
				Ui.head(g, p.head, x1 + 10, cy - 8, 16, ca);
				left = 32;
			} else if (p.icon != null) {
				Ui.circle(g, x1 + 18, cy, 9, Anim.argb(col, 0.18f * ca));
				Ui.icon(g, p.icon, x1 + 18 - Ui.font().width(p.icon) / 2, cy - 4, Anim.argb(col, ca));
				left = 32;
			}
			String body = Ui.ellipsize(p.body, 220);
			Ui.bold(g, Ui.ellipsize(p.title, x2 - x1 - left - 14), x1 + left, body.isEmpty() ? cy - 4 : y1 + 6, Anim.argb(Ui.INK, ca));
			if (!body.isEmpty()) Ui.text(g, Ui.ellipsize(body, x2 - x1 - left - 14), x1 + left, y1 + 17, Anim.argb(Ui.MUTED, ca));
			int lw = Math.round((x2 - x1 - r * 2) * life);
			g.fill(x1 + r, y2 - 2, x1 + r + lw, y2 - 1, Anim.argb(col, 0.6f * ca));
		} else if (mode == 2) {
			int art = Math.round(h - 10);
			Ui.round(g, x1 + 6, y1 + 5, x1 + 6 + art, y1 + 5 + art, 6, Anim.argb(0x000000, 0.08f * ca));
			Compat.blitTint(g, dev.kyoto.music.AlbumArt.texture(), x1 + 6, y1 + 5, art, art, Anim.argb(0xFFFFFF, ca));
			int tx = x1 + art + 13, maxW = x2 - tx - 30;
			Ui.bold(g, Ui.ellipsize(Music.title, maxW), tx, y1 + 7, Anim.argb(Ui.INK, ca));
			Ui.small(g, Ui.ellipsize(Music.artist, Math.round(maxW / 0.75f)), tx, y1 + 18, Anim.argb(Ui.MUTED, ca));
			// equalizer
			double t = now / 1000.0;
			for (int i = 0; i < 4; i++) {
				float bh = 3 + 7 * (0.5f + 0.5f * (float) Math.sin(t * (5 + i * 1.3) + i * 1.7));
				int bx = x2 - 22 + i * 4;
				Ui.round(g, bx, Math.round(cy - bh / 2), bx + 2, Math.round(cy + bh / 2), 1, Anim.argb(Ui.accent(), ca));
			}
			if (Music.durationMs > 0) {
				float f = Anim.clamp01(Music.positionMs() / (float) Music.durationMs);
				int bx1 = tx, bx2 = x2 - 30;
				Ui.round(g, bx1, y2 - 6, bx2, y2 - 4, 1, Anim.argb(Ui.INK, 0.1f * ca));
				Ui.round(g, bx1, y2 - 6, bx1 + Math.max(2, Math.round((bx2 - bx1) * f)), y2 - 4, 1, Anim.argb(Ui.accent(), ca));
			}
		} else if (idle <= 1) {
			Compat.blitTint(g, dev.kyoto.ui.TitleTheme.logo(), x1 + 6, cy - 5, 10, 10, Anim.argb(0xFFFFFF, ca));
			Ui.text(g, idleText, x1 + 20, cy - 4, Anim.argb(Ui.INK, ca));
		} else if (idle == 2) {
			Ui.circle(g, (x1 + x2) / 2f, cy, 2.5f, Anim.argb(Ui.accent(), ca));
		}
		g.disableScissor();
	}
}
