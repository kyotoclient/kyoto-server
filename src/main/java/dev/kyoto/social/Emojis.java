package dev.kyoto.social;

import dev.kyoto.util.Anim;
import dev.kyoto.util.Compat;
import dev.kyoto.util.Ui;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Kyoto emojis: type :fire: (or pick one) and it shows as a picture in DMs. */
public final class Emojis {
	private Emojis() {}

	public static final String[] NAMES = {"smile", "grin", "laugh", "wink", "cool", "love", "think", "shock", "sad", "cry", "angry", "skull",
			"heart", "broken_heart", "fire", "sparkles", "star", "100", "gg", "thumbs_up", "thumbs_down", "clap", "party", "eyes",
			"check", "x", "crown", "sword", "diamond", "pickaxe", "creeper", "cake", "moon"};
	private static final Set<String> SET = Set.of(NAMES);
	private static final Pattern CODE = Pattern.compile(":([a-z0-9_]{1,20}):");

	public static Identifier tex(String name) {
		return Ui.tex("textures/emoji/" + name + ".png");
	}

	public static boolean exists(String name) {
		return SET.contains(name);
	}

	/** For pop-ups: leaves the text as is (":fire:" reads fine). */
	public static String plain(String s) {
		return s;
	}

	private record Token(String text, boolean emoji) {}

	private static List<Token> tokens(String s) {
		List<Token> out = new ArrayList<>();
		Matcher m = CODE.matcher(s);
		int last = 0;
		while (m.find()) {
			if (!exists(m.group(1))) continue;
			if (m.start() > last) for (String w : s.substring(last, m.start()).split("(?<= )")) if (!w.isEmpty()) out.add(new Token(w, false));
			out.add(new Token(m.group(1), true));
			last = m.end();
		}
		if (last < s.length()) for (String w : s.substring(last).split("(?<= )")) if (!w.isEmpty()) out.add(new Token(w, false));
		return out;
	}

	/** Draws text with emojis, wrapping at maxW. Returns the height used. Big = message is only emojis (shown larger). */
	public static int draw(GuiGraphicsExtractor g, String s, int x, int y, int maxW, int rgb, float a, boolean measureOnly) {
		List<Token> toks = tokens(s);
		boolean onlyEmoji = !toks.isEmpty() && toks.stream().allMatch(t -> t.emoji() || t.text().isBlank()) && toks.size() <= 6;
		int size = onlyEmoji ? 18 : 9;
		int lineH = onlyEmoji ? 20 : 10;
		int cx = 0, cy = 0;
		for (Token t : toks) {
			int tw = t.emoji() ? size + 1 : Ui.width(t.text());
			if (cx + tw > maxW && cx > 0) {
				cx = 0;
				cy += lineH;
			}
			if (!measureOnly) {
				if (t.emoji()) Compat.blitTint(g, tex(t.text()), x + cx, y + cy - (onlyEmoji ? 0 : 1), size, size, Anim.argb(0xFFFFFF, a));
				else Ui.text(g, t.text(), x + cx, y + cy, Anim.argb(rgb, a));
			}
			cx += tw;
		}
		return cy + lineH;
	}
}
