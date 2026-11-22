package dev.kyoto.hud;

import dev.kyoto.util.Anim;
import dev.kyoto.util.Ui;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Glass toasts in the top-right. Slide in with overshoot, optional player head,
 * shrinking timer bar, smooth re-stacking. Identical toasts merge into "x2".
 */
public final class Notifications {
	private Notifications() {}

	public static final int INFO = 0x7FB8FF;
	public static final int GOOD = 0xFFFFFF;
	public static final int WARN = 0xE6E6E6;
	public static final int DANGER = 0xFF5D5D;
	public static final int MAGIC = 0xBDBDBD;
	public static final int MONEY = 0x7CFF8A;

	private static final int MAX = 5;
	private static final List<Toast> TOASTS = new ArrayList<>();

	private static final class Toast {
		final String title, body, head, icon;
		final int color;
		final long duration;
		long start;
		int count = 1;
		float shownY = -1;

		Toast(String title, String body, int color, long duration, String head, String icon) {
			this.title = title;
			this.body = body == null ? "" : body;
			this.color = color;
			this.duration = duration;
			this.head = head;
			this.icon = icon;
			this.start = System.currentTimeMillis();
		}
	}

	public static void push(String title, String body, int color) {
		push(title, body, color, 3800, null, null);
	}

	public static void push(String title, String body, int color, long durationMs) {
		push(title, body, color, durationMs, null, null);
	}

	/** @param head player name whose face to show (or null) */
	public static void push(String title, String body, int color, long durationMs, String head, String icon) {
		try {
			if (Notch.on()) {
				Notch.push(title, body, color, durationMs, head, icon);
				return;
			}
		} catch (Throwable ignored) {
		}
		long now = System.currentTimeMillis();
		for (Toast t : TOASTS) {
			if (t.title.equals(title) && t.body.equals(body == null ? "" : body) && now - t.start < t.duration - 400) {
				t.count++;
				t.start = now - 300;
				return;
			}
		}
		TOASTS.add(0, new Toast(title, body, color, durationMs, head, icon));
		while (TOASTS.size() > MAX) TOASTS.remove(TOASTS.size() - 1);
	}

	public static void render(GuiGraphicsExtractor g, int screenW, int topOffset) {
		if (TOASTS.isEmpty()) return;
		long now = System.currentTimeMillis();
		float speed = Math.max(0.35f, Ui.animSpeed());
		long inMs = (long) (300 * speed), outMs = (long) (380 * speed);
		float targetY = 6 + topOffset;

		Iterator<Toast> it = TOASTS.iterator();
		while (it.hasNext()) {
			Toast t = it.next();
			long age = now - t.start;
			if (age > t.duration) {
				it.remove();
				continue;
			}
			String title = t.count > 1 ? t.title + "  x" + t.count : t.title;
			boolean hasLeft = t.head != null || t.icon != null;
			int left = hasLeft ? 30 : 10;
			int maxBody = 190;
			String body = Ui.ellipsize(t.body, maxBody);
			int w = Math.max(Ui.widthBold(title), Ui.width(body)) + left + 12;
			w = Math.max(w, 140);
			int h = body.isEmpty() ? 22 : 32;

			float slide, alpha = 1f;
			if (age < inMs) {
				slide = 1 - Anim.easeOutBack(age / (float) inMs);
			} else if (age > t.duration - outMs) {
				float p = (age - (t.duration - outMs)) / (float) outMs;
				slide = Anim.easeInCubic(p);
				alpha = 1 - p;
			} else {
				slide = 0;
			}
			if (t.shownY < 0) t.shownY = targetY;
			t.shownY += (targetY - t.shownY) * 0.25f;

			int x = Math.round(screenW - w - 6 + slide * (w + 14));
			int y = Math.round(t.shownY);
			float life = 1 - Anim.clamp01(age / (float) t.duration);

			Ui.hud(g, x, y, x + w, y + h, 6, alpha);
			// accent glow strip + timer
			Ui.round(g, x + 3, y + 5, x + 5, y + h - 5, 1, Anim.argb(t.color, alpha));
			g.fill(x + 6, y + h - 2, x + 6 + Math.round((w - 12) * life), y + h - 1, Anim.argb(t.color, 0.55f * alpha));

			if (t.head != null) {
				Ui.head(g, t.head, x + 10, y + h / 2 - 8, 16, alpha);
			} else if (t.icon != null) {
				Ui.round(g, x + 9, y + h / 2 - 9, x + 27, y + h / 2 + 9, 5, Anim.argb(t.color, 0.18f * alpha));
				Ui.icon(g, t.icon, x + 18 - Ui.font().width(t.icon) / 2, y + h / 2 - 4, Anim.argb(t.color, alpha));
			}
			int tc = t.color == 0xFFFFFF || t.color == 0xE6E6E6 || t.color == 0xBDBDBD ? Ui.INK : t.color;
			Ui.bold(g, title, x + left, y + (body.isEmpty() ? 7 : 5), Anim.argb(tc, alpha));
			if (!body.isEmpty()) Ui.text(g, body, x + left, y + 17, Anim.argb(Ui.INK, alpha));
			targetY += h + 4;
		}
	}
}
