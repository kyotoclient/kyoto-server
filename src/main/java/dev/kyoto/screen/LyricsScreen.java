package dev.kyoto.screen;

import dev.kyoto.config.KyotoConfig;
import dev.kyoto.module.Modules;
import dev.kyoto.music.AlbumArt;
import dev.kyoto.music.LyricsHud;
import dev.kyoto.music.LyricsHud.Look;
import dev.kyoto.music.Music;
import dev.kyoto.util.Anim;
import dev.kyoto.util.Compat;
import dev.kyoto.util.Ui;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;

import java.util.ArrayList;
import java.util.List;

/**
 * The Lyrics menu: a panel docked on the left so the game (and your lyrics) stay visible while you change them.
 * Tabs on the left, a live preview at the bottom. Every preset is drawn in its own style.
 * Open with K (rebindable) or /kyoto lyrics.
 */
public class LyricsScreen extends Screen {
	private final Screen parent;
	private static int tab = 1;
	private static final String[] TABS = {"General", "Presets", "Style", "Animation", "Layout", "Position", "Widget"};
	private static final String[] TAB_ICONS = {"gear", "preset", "theme", "sparkle", "display", "waypoint", "music"};
	private static final String SAMPLE = "under a midnight blue sky";

	private record Hit(int x1, int y1, int x2, int y2, Runnable action) {}

	private final List<Hit> hits = new ArrayList<>();
	private final long openedAt = System.currentTimeMillis();
	private float scroll = 0, scrollTarget = 0, scrollMax = 0;
	private int px, py, pw, ph, cx, cy, cw, ch;

	public LyricsScreen(Screen parent) {
		super(Component.literal("Lyrics"));
		this.parent = parent;
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void onClose() {
		KyotoConfig.save();
		Compat.openScreen(parent);
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partial) {
		// no blur / dim: you see the game and your lyrics change live
	}

	private float alpha() {
		return Anim.easeOutCubic(Anim.clamp01((System.currentTimeMillis() - openedAt) / 200f));
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float partial) {
		hits.clear();
		float a = alpha();
		pw = Math.min(268, this.width - 20);
		ph = this.height - 16;
		px = 8 - Math.round((1 - a) * 20);
		py = 8;
		int accent = Ui.accent();
		long now = System.currentTimeMillis();

		// panel
		Ui.round(g, px - 1, py - 1, px + pw + 1, py + ph + 1, 10, Anim.argb(Ui.INK, 0.08f * a));
		Ui.round(g, px, py, px + pw, py + ph, 9, Anim.argb(Ui.GLASS, 0.94f * a));
		// header
		Ui.iconTex(g, "music", px + 9, py + 8, 10, Anim.argb(accent, a));
		Ui.bold(g, "KYOTO LYRICS", px + 23, py + 8, Anim.argb(Ui.INK, a));
		String np = Music.active() ? Music.title : "Nothing playing";
		Ui.small(g, Ui.ellipsize(np, Math.round((pw - 120) / 0.75f)), px + 102, py + 10, Anim.argb(Ui.MUTED, a));
		closeButton(g, mx, my, a);
		g.fill(px + 8, py + 24, px + pw - 8, py + 25, Anim.argb(Ui.INK, 0.06f * a));

		// tabs
		int tx = px + 6, ty = py + 30, tw = 70;
		for (int i = 0; i < TABS.length; i++) {
			int y = ty + i * 17;
			boolean sel = tab == i, hov = Ui.inside(mx, my, tx, y, tx + tw, y + 15);
			if (sel) Ui.round(g, tx, y, tx + tw, y + 15, 5, Anim.argb(accent, 0.2f * a));
			else if (hov) Ui.round(g, tx, y, tx + tw, y + 15, 5, Anim.argb(Ui.INK, 0.05f * a));
			Ui.iconTex(g, TAB_ICONS[i], tx + 5, y + 3, 9, Anim.argb(sel ? accent : Ui.MUTED, a));
			Ui.text(g, TABS[i], tx + 18, y + 4, Anim.argb(sel ? Ui.INK : Ui.MUTED, a));
			final int t = i;
			hit(tx, y, tx + tw, y + 15, () -> { tab = t; scrollTarget = scroll = 0; });
		}
		g.fill(px + 80, py + 30, px + 81, py + ph - 30, Anim.argb(Ui.INK, 0.05f * a));

		// content area
		cx = px + 88;
		cy = py + 30;
		cw = pw - 96;
		int previewH = 46;
		ch = ph - 30 - previewH - 34;
		scroll += (scrollTarget - scroll) * 0.35f;
		g.enableScissor(cx - 2, cy, cx + cw + 2, cy + ch);
		int y0 = cy - Math.round(scroll);
		int end = switch (tab) {
			case 0 -> general(g, y0, mx, my, a);
			case 1 -> presets(g, y0, mx, my, a, now);
			case 2 -> style(g, y0, mx, my, a);
			case 3 -> animation(g, y0, mx, my, a, now);
			case 4 -> layout(g, y0, mx, my, a);
			case 5 -> position(g, y0, mx, my, a);
			default -> widget(g, y0, mx, my, a);
		};
		g.disableScissor();
		scrollMax = Math.max(0, end - y0 - ch + 4);
		scrollTarget = Math.max(0, Math.min(scrollMax, scrollTarget));
		if (scrollMax > 0) { // scroll bar
			int bh = Math.max(16, Math.round(ch * ch / (float) (ch + scrollMax)));
			int by = cy + Math.round((ch - bh) * (scroll / scrollMax));
			Ui.round(g, cx + cw + 3, by, cx + cw + 5, by + bh, 1, Anim.argb(Ui.INK, 0.2f * a));
		}
		// drop hits that are scrolled out of view
		hits.removeIf(h -> h.x1() >= cx - 2 && h.x2() <= cx + cw + 2 && (h.y2() < cy || h.y1() > cy + ch));

		// preview
		int pvY = py + ph - previewH - 30;
		Ui.round(g, px + 8, pvY, px + pw - 8, pvY + previewH, 7, Anim.argb(0x000000, 0.45f * a));
		Ui.round(g, px + 8, pvY, px + pw - 8, pvY + 1, 0, Anim.argb(Ui.INK, 0.05f * a));
		Ui.small(g, "PREVIEW", px + 14, pvY + 4, Anim.argb(Ui.DIM, a));
		float prog = ((now % 4200) / 3200f);
		float scale = Math.min(1.5f * LyricsHud.current().size(), (pw - 30) / (float) Math.max(1, LyricsHud.sampleWidth(LyricsHud.current(), SAMPLE)));
		LyricsHud.drawSample(g, LyricsHud.current(), SAMPLE, px + pw / 2f, pvY + previewH / 2f + 3, scale, a * (prog > 1.25f ? 0.4f : 1f), Math.min(1, prog));

		// footer
		int fy = py + ph - 24;
		button(g, px + 8, fy, 62, "Reset tab", mx, my, a, false, this::resetTab);
		Ui.small(g, "K or Esc closes", px + pw / 2 - Ui.widthSmall("K or Esc closes") / 2, fy + 6, Anim.argb(Ui.DIM, a));
		button(g, px + pw - 58, fy, 50, "Done", mx, my, a, true, this::onClose);

		// now playing widget (top right) so you can see what the lyrics are following
		nowPlaying(g, a);
	}

	/* ------------------------------------------------ tabs ------------------------------------------------ */

	private int general(GuiGraphicsExtractor g, int y, int mx, int my, float a) {
		y = header(g, "Lyrics", y, a);
		y = toggle(g, y, "Show lyrics", "lyrics", mx, my, a);
		y = toggle(g, y, "Custom look", "lyrics_look", mx, my, a);
		y = cycle(g, y, "lyrics.offset", mx, my, a);
		y = cycle(g, y, "lyrics.pause", mx, my, a);
		y = cycle(g, y, "lyrics.f1", mx, my, a);
		y = header(g, "Music", y + 4, a);
		y = cycle(g, y, "music.source", mx, my, a);
		y = note(g, y, "System media is free and works with Spotify Free, YouTube Music and Apple Music.", a);
		return y;
	}

	private int presets(GuiGraphicsExtractor g, int y, int mx, int my, float a, long now) {
		Look cur = LyricsHud.current();
		for (int i = 0; i < LyricsHud.PRESETS.size(); i++) {
			LyricsHud.Preset p = LyricsHud.PRESETS.get(i);
			int h = 24;
			boolean sel = p.look().equals(cur) || (p.look().style() == cur.style() && p.look().font() == cur.font() && p.look().anim() == cur.anim() && p.look().effect() == cur.effect());
			boolean hov = Ui.inside(mx, my, cx, y, cx + cw, y + h);
			int[] pal = LyricsHud.palette(p.look().style());
			Ui.round(g, cx, y, cx + cw, y + h, 6, Anim.argb(sel ? Ui.GLASS : Ui.GLASS, a));
			if (sel || hov) Ui.round(g, cx, y, cx + cw, y + 1, 0, Anim.argb(pal[1], (sel ? 0.9f : 0.4f) * a));
			if (sel) {
				Ui.round(g, cx - 1, y - 1, cx + cw + 1, y + h + 1, 7, Anim.argb(pal[1], 0.35f * a));
				Ui.round(g, cx, y, cx + cw, y + h, 6, Anim.argb(Ui.GLASS, a));
			}
			// the name, written in the preset's own look; it plays its animation while you hover it
			float prog = hov || sel ? ((now + i * 200) % 2600) / 1800f : 1f;
			float sc = Math.min(1.25f, (cw - 16) / (float) Math.max(1, LyricsHud.sampleWidth(p.look(), p.name())));
			LyricsHud.drawSample(g, p.look(), p.name(), cx + cw / 2f, y + h / 2f + 1, sc, a, Math.min(1, prog));
			hit(cx, y, cx + cw, y + h, () -> { LyricsHud.apply(p.look()); click(); });
			y += h + 4;
		}
		return y;
	}

	private int style(GuiGraphicsExtractor g, int y, int mx, int my, float a) {
		Look cur = LyricsHud.current();
		y = header(g, "Colour", y, a);
		int cols = 3, gap = 4, bw = (cw - gap * (cols - 1)) / cols, bh = 22;
		String[] names = LyricsHud.STYLE_NAMES;
		for (int i = 0; i < names.length; i++) {
			int bx = cx + (i % cols) * (bw + gap), by = y + (i / cols) * (bh + gap);
			boolean sel = cur.style() == i;
			int[] pal = LyricsHud.palette(i);
			Ui.round(g, bx, by, bx + bw, by + bh, 5, Anim.argb(sel ? Ui.GLASS : Ui.GLASS, a));
			if (sel) Ui.round(g, bx, by + bh - 2, bx + bw, by + bh, 1, Anim.argb(pal[1], a));
			Look l = new Look(i, cur.font(), 2, cur.effect() == 3 ? 1 : cur.effect(), 1f);
			LyricsHud.drawSample(g, l, names[i], bx + bw / 2f, by + bh / 2f, Math.min(1f, (bw - 6) / (float) Math.max(1, LyricsHud.sampleWidth(l, names[i]))), a, 1f);
			final int s = i;
			hit(bx, by, bx + bw, by + bh, () -> set("lyrics.style", s));
		}
		y += ((names.length + cols - 1) / cols) * (bh + gap) + 6;
		y = header(g, "Font", y, a);
		String[] fonts = Modules.FONTS;
		cols = 2;
		bw = (cw - gap) / cols;
		bh = 18;
		for (int i = 0; i < fonts.length; i++) {
			int bx = cx + (i % cols) * (bw + gap), by = y + (i / cols) * (bh + gap);
			boolean sel = cur.font() == i;
			Ui.round(g, bx, by, bx + bw, by + bh, 5, Anim.argb(sel ? Ui.GLASS : Ui.GLASS, a));
			if (sel) Ui.round(g, bx, by + bh - 2, bx + bw, by + bh, 1, Anim.argb(Ui.accent(), a));
			Component c = Ui.inFont(fonts[i], i, false);
			int fw = Ui.font().width(c);
			g.text(Ui.font(), c, bx + bw / 2 - Math.min(fw, bw - 6) / 2, by + 5, Anim.argb(sel ? Ui.INK : Ui.MUTED, a), false);
			final int f = i;
			hit(bx, by, bx + bw, by + bh, () -> set("lyrics.font", f));
		}
		y += ((fonts.length + cols - 1) / cols) * (bh + gap) + 6;
		y = header(g, "Size", y, a);
		y = segmented(g, y, "lyrics.size", mx, my, a);
		return y;
	}

	private int animation(GuiGraphicsExtractor g, int y, int mx, int my, float a, long now) {
		Look cur = LyricsHud.current();
		y = header(g, "Animation", y, a);
		String[] an = LyricsHud.ANIM_NAMES;
		for (int i = 0; i < an.length; i++) {
			int h = 22;
			boolean sel = cur.anim() == i;
			Ui.round(g, cx, y, cx + cw, y + h, 6, Anim.argb(sel ? Ui.GLASS : Ui.GLASS, a));
			if (sel) Ui.round(g, cx, y, cx + 2, y + h, 1, Anim.argb(Ui.accent(), a));
			Ui.text(g, an[i], cx + 8, y + 7, Anim.argb(sel ? Ui.INK : Ui.MUTED, a));
			Look l = new Look(cur.style(), cur.font(), i, cur.effect(), 1f);
			float prog = ((now + i * 300) % 2400) / 1700f;
			LyricsHud.drawSample(g, l, "la la la", cx + cw - 42, y + h / 2f + 1, 0.9f, a, Math.min(1, prog));
			final int s = i;
			hit(cx, y, cx + cw, y + h, () -> set("lyrics.anim", s));
			y += h + 3;
		}
		y = header(g, "Effects", y + 6, a);
		String[] ef = LyricsHud.EFFECT_NAMES;
		int gap = 4, bw = (cw - gap) / 2, bh = 20;
		for (int i = 0; i < ef.length; i++) {
			int bx = cx + (i % 2) * (bw + gap), by = y + (i / 2) * (bh + gap);
			boolean sel = cur.effect() == i;
			Ui.round(g, bx, by, bx + bw, by + bh, 5, Anim.argb(sel ? Ui.GLASS : Ui.GLASS, a));
			if (sel) Ui.round(g, bx, by + bh - 2, bx + bw, by + bh, 1, Anim.argb(Ui.accent(), a));
			String t = Ui.ellipsize(ef[i], bw - 8);
			Ui.text(g, t, bx + bw / 2 - Ui.width(t) / 2, by + 6, Anim.argb(sel ? Ui.INK : Ui.MUTED, a));
			final int s = i;
			hit(bx, by, bx + bw, by + bh, () -> set("lyrics.effect", s));
		}
		return y + ((ef.length + 1) / 2) * (bh + gap);
	}

	private int layout(GuiGraphicsExtractor g, int y, int mx, int my, float a) {
		y = header(g, "Where lyrics show", y, a);
		String[][] modes = {{"Sky", "Huge lines across the sky"}, {"Floating", "Placed in the world, walk past them"}, {"Screen", "Subtitles on your screen"}};
		int cur = Modules.opt("lyrics.mode");
		for (int i = 0; i < modes.length; i++) {
			int h = 30;
			boolean sel = cur == i;
			Ui.round(g, cx, y, cx + cw, y + h, 6, Anim.argb(sel ? Ui.GLASS : Ui.GLASS, a));
			if (sel) Ui.round(g, cx, y, cx + 2, y + h, 1, Anim.argb(Ui.accent(), a));
			Ui.bold(g, modes[i][0], cx + 9, y + 6, Anim.argb(sel ? Ui.INK : Ui.MUTED, a));
			Ui.small(g, modes[i][1], cx + 9, y + 18, Anim.argb(Ui.MUTED, a));
			final int s = i;
			hit(cx, y, cx + cw, y + h, () -> set("lyrics.mode", s));
			y += h + 4;
		}
		y = header(g, "Distance", y + 4, a);
		y = segmented(g, y, "lyrics.distance", mx, my, a);
		return y;
	}

	private int position(GuiGraphicsExtractor g, int y, int mx, int my, float a) {
		y = header(g, "Placement", y, a);
		y = cycle(g, y, "lyrics.follow", mx, my, a);
		y = note(g, y, "On: new lines appear where you're looking. Off: always in front of where you face.", a);
		y = cycle(g, y, "lyrics.lock", mx, my, a);
		y = note(g, y, "Lines move with you instead of staying where they appeared.", a);
		y = cycle(g, y, "lyrics.walls", mx, my, a);
		y = note(g, y, "Words fade out when a block is in the way.", a);
		return y;
	}

	private int widget(GuiGraphicsExtractor g, int y, int mx, int my, float a) {
		y = header(g, "Now playing", y, a);
		y = toggle(g, y, "Song pill", "now_playing", mx, my, a);
		y = toggle(g, y, "Album art", "album_art", mx, my, a);
		y = note(g, y, "The pill sits at the top of your screen with a progress bar. In Floating and Sky mode it shows the current line too.", a);
		y = header(g, "Controls", y + 4, a);
		int bw = (cw - 8) / 3;
		button(g, cx, y, bw, "Prev", mx, my, a, false, () -> Music.control("previous"));
		button(g, cx + bw + 4, y, bw, Music.playing ? "Pause" : "Play", mx, my, a, false, () -> Music.control("toggle"));
		button(g, cx + (bw + 4) * 2, y, bw, "Next", mx, my, a, false, () -> Music.control("next"));
		return y + 22;
	}

	/* ------------------------------------------------ pieces ------------------------------------------------ */

	private int header(GuiGraphicsExtractor g, String t, int y, float a) {
		Ui.small(g, t.toUpperCase(java.util.Locale.ROOT), cx + 2, y + 2, Anim.argb(Ui.DIM, a));
		return y + 13;
	}

	private int note(GuiGraphicsExtractor g, int y, String t, float a) {
		for (String line : PanelScreen.wrap(t, Math.round(cw / 0.75f), 4)) {
			Ui.small(g, line, cx + 2, y, Anim.argb(Ui.DIM, a));
			y += 9;
		}
		return y + 4;
	}

	private int toggle(GuiGraphicsExtractor g, int y, String label, String module, int mx, int my, float a) {
		boolean on = Modules.on(module);
		int h = 20;
		boolean hov = Ui.inside(mx, my, cx, y, cx + cw, y + h);
		Ui.round(g, cx, y, cx + cw, y + h, 5, Anim.argb(hov ? Ui.GLASS : Ui.GLASS, a));
		Ui.text(g, label, cx + 8, y + 6, Anim.argb(Ui.INK, a));
		int sx = cx + cw - 26;
		Ui.round(g, sx, y + 5, sx + 20, y + 15, 5, Anim.argb(on ? Ui.accent() : Ui.SOFT, a));
		int kx = on ? sx + 11 : sx + 1;
		Ui.round(g, kx, y + 6, kx + 8, y + 14, 4, Anim.argb(Ui.INK, a));
		hit(cx, y, cx + cw, y + h, () -> { Modules.get(module).toggle(); click(); });
		return y + h + 3;
	}

	private int cycle(GuiGraphicsExtractor g, int y, String key, int mx, int my, float a) {
		Modules.Setting s = Modules.setting(key);
		int h = 20;
		boolean hov = Ui.inside(mx, my, cx, y, cx + cw, y + h);
		Ui.round(g, cx, y, cx + cw, y + h, 5, Anim.argb(hov ? Ui.GLASS : Ui.GLASS, a));
		Ui.text(g, Ui.ellipsize(s.name, cw / 2), cx + 8, y + 6, Anim.argb(Ui.INK, a));
		String v = Ui.ellipsize(s.label(), cw / 2 - 26);
		// ‹ value ›  - click the left side to go back, the right side to go forward
		int vx = cx + cw - 22 - Ui.width(v);
		Ui.text(g, v, vx + 10, y + 6, Anim.argb(Ui.accent(), a));
		boolean hl = hov && mx < vx + 10 + Ui.width(v) / 2, hr = hov && !hl;
		Ui.text(g, "‹", vx, y + 6, Anim.argb(hl ? Ui.INK : Ui.DIM, a));
		Ui.text(g, "›", cx + cw - 9, y + 6, Anim.argb(hr ? Ui.INK : Ui.DIM, a));
		final int mid = vx + 10 + Ui.width(v) / 2;
		hit(cx, y, mid, y + h, () -> { s.cycle(-1); click(); });
		hit(mid, y, cx + cw, y + h, () -> { s.cycle(1); click(); });
		return y + h + 3;
	}

	private int segmented(GuiGraphicsExtractor g, int y, String key, int mx, int my, float a) {
		Modules.Setting s = Modules.setting(key);
		int n = s.options.length, gap = 3, bw = (cw - gap * (n - 1)) / n, h = 18;
		for (int i = 0; i < n; i++) {
			int bx = cx + i * (bw + gap);
			boolean sel = s.get() == i;
			Ui.round(g, bx, y, bx + bw, y + h, 5, Anim.argb(sel ? Ui.accent() : Ui.GLASS, sel ? 0.85f * a : a));
			String t = Ui.ellipsize(s.options[i], bw - 4);
			Ui.text(g, t, bx + bw / 2 - Ui.width(t) / 2, y + 5, Anim.argb(sel ? Ui.INK : Ui.MUTED, a));
			final int v = i;
			hit(bx, y, bx + bw, y + h, () -> set(key, v));
		}
		return y + h + 4;
	}

	private void button(GuiGraphicsExtractor g, int x, int y, int w, String label, int mx, int my, float a, boolean primary, Runnable r) {
		boolean hov = Ui.inside(mx, my, x, y, x + w, y + 16);
		int bg = primary ? Ui.accent() : Ui.GLASS;
		Ui.round(g, x, y, x + w, y + 16, 5, Anim.argb(hov ? Ui.mix(bg, Ui.INK, 0.12f) : bg, a));
		Ui.text(g, label, x + w / 2 - Ui.width(label) / 2, y + 4, Anim.argb(Ui.INK, a));
		hit(x, y, x + w, y + 16, () -> { click(); r.run(); });
	}

	private void closeButton(GuiGraphicsExtractor g, int mx, int my, float a) {
		int x = px + pw - 20, y = py + 6;
		boolean hov = Ui.inside(mx, my, x, y, x + 14, y + 14);
		if (hov) Ui.round(g, x, y, x + 14, y + 14, 4, Anim.argb(0xFF5C6C, 0.3f * a));
		Ui.iconTex(g, "close", x + 3, y + 3, 8, Anim.argb(hov ? Ui.INK : Ui.MUTED, a));
		hit(x, y, x + 14, y + 14, this::onClose);
	}

	private void nowPlaying(GuiGraphicsExtractor g, float a) {
		if (!Music.active()) return;
		int w = 150, h = 34, x = this.width - w - 8, y = 8;
		if (x < px + pw + 8) return;
		Ui.round(g, x, y, x + w, y + h, 8, Anim.argb(Ui.GLASS, 0.85f * a));
		Compat.blitTint(g, AlbumArt.texture(), x + 5, y + 5, 24, 24, Anim.argb(0xFFFFFF, a));
		Ui.text(g, Ui.ellipsize(Music.title, w - 42), x + 34, y + 6, Anim.argb(Ui.INK, a));
		Ui.small(g, Ui.ellipsize(Music.artist, Math.round((w - 42) / 0.75f)), x + 34, y + 17, Anim.argb(Ui.MUTED, a));
		long pos = Music.positionMs(), dur = Math.max(1, Music.durationMs);
		g.fill(x + 34, y + h - 5, x + w - 8, y + h - 4, Anim.argb(Ui.INK, 0.12f * a));
		g.fill(x + 34, y + h - 5, x + 34 + Math.round((w - 42) * Math.min(1f, pos / (float) dur)), y + h - 4, Anim.argb(Ui.accent(), a));
	}

	/* ------------------------------------------------ actions ------------------------------------------------ */

	private void set(String key, int v) {
		KyotoConfig.get().choices.put(key, v);
		if (key.startsWith("lyrics.") && !key.equals("lyrics.mode") && !key.equals("lyrics.distance") && !Modules.on("lyrics_look")
				&& (key.equals("lyrics.style") || key.equals("lyrics.font") || key.equals("lyrics.anim") || key.equals("lyrics.effect") || key.equals("lyrics.size")))
			Modules.get("lyrics_look").toggle();
		KyotoConfig.save();
		click();
	}

	private static final String[][] TAB_KEYS = {
			{"lyrics.offset", "lyrics.pause", "lyrics.f1"},
			{"lyrics.style", "lyrics.font", "lyrics.anim", "lyrics.effect", "lyrics.size"},
			{"lyrics.style", "lyrics.font", "lyrics.size"},
			{"lyrics.anim", "lyrics.effect"},
			{"lyrics.mode", "lyrics.distance"},
			{"lyrics.follow", "lyrics.lock", "lyrics.walls"},
			{}};

	private void resetTab() {
		for (String k : TAB_KEYS[tab]) KyotoConfig.get().choices.remove(k);
		KyotoConfig.save();
	}

	private void hit(int x1, int y1, int x2, int y2, Runnable r) {
		hits.add(new Hit(x1, y1, x2, y2, r));
	}

	private static void click() {
		Compat.sound(SoundEvents.UI_BUTTON_CLICK.value(), 0.25f, 1.4f);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		double mx = event.x(), my = event.y();
		for (int i = hits.size() - 1; i >= 0; i--) {
			Hit h = hits.get(i);
			if (Ui.inside(mx, my, h.x1(), h.y1(), h.x2(), h.y2())) {
				h.action().run();
				return true;
			}
		}
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		if (Ui.inside(mouseX, mouseY, cx, cy, cx + cw + 6, cy + ch)) {
			scrollTarget = Math.max(0, Math.min(scrollMax, scrollTarget - (float) scrollY * 22));
			return true;
		}
		return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (event.key() == 75) { // K closes too
			onClose();
			return true;
		}
		return super.keyPressed(event);
	}
}
