package dev.kyoto.stream;

import dev.kyoto.config.KyotoConfig;
import dev.kyoto.feature.Privacy;
import dev.kyoto.module.Modules;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Streamer Mode chat filter: hides things from viewers before they hit your screen.
 *  - coordinates (x y z in chat)        - swears        - slurs / adult words
 *  - personal info: emails, phone numbers, IP addresses, street addresses, and your own words (your real name, town...)
 * Each one can be switched in Right Shift > Streaming > Streamer Mode. Your own words: /kyoto hideword add <word>
 */
public final class StreamFilter {
	private StreamFilter() {}

	private static final String BAR = "\u2588\u2588\u2588";
	private static final Pattern COORDS = Pattern.compile("(?i)(?:\\bx\\s*[:=]?\\s*)?-?\\d{1,7}(?:\\.\\d+)?[,/\\s]+(?:y\\s*[:=]?\\s*)?-?\\d{1,3}(?:\\.\\d+)?[,/\\s]+(?:z\\s*[:=]?\\s*)?-?\\d{1,7}(?:\\.\\d+)?");
	private static final Pattern XZ = Pattern.compile("(?i)\\bx\\s*[:=]?\\s*-?\\d{2,7}[,\\s]+z\\s*[:=]?\\s*-?\\d{2,7}");
	private static final Pattern EMAIL = Pattern.compile("(?i)[a-z0-9._%+-]+@[a-z0-9.-]+\\.[a-z]{2,}");
	private static final Pattern IP = Pattern.compile("\\b\\d{1,3}(?:\\.\\d{1,3}){3}(?::\\d{2,5})?\\b");
	private static final Pattern PHONE = Pattern.compile("(?<![\\d.])(?:\\+?\\d{1,3}[\\s.-]?)?\\(?\\d{3}\\)?[\\s.-]?\\d{3}[\\s.-]?\\d{4}(?![\\d.])");
	private static final Pattern ADDRESS = Pattern.compile("(?i)\\b\\d{1,6}\\s+(?:[a-z]+\\s){1,3}(?:street|st|avenue|ave|road|rd|lane|ln|drive|dr|court|ct|boulevard|blvd|way|place|pl|circle|cir)\\b\\.?");

	public static boolean on() {
		return Privacy.on();
	}

	private static boolean hide(String key) {
		return Modules.opt(key) == 0;
	}

	/** Censors one piece of text. */
	public static String apply(String t) {
		if (t == null || t.isEmpty() || !on()) return t;
		if (hide("privacy.personal")) {
			t = EMAIL.matcher(t).replaceAll(BAR);
			t = IP.matcher(t).replaceAll(BAR);
			t = ADDRESS.matcher(t).replaceAll(BAR);
			t = PHONE.matcher(t).replaceAll(BAR);
			for (String w : KyotoConfig.get().streamHiddenWords) {
				if (w.isBlank()) continue;
				t = Pattern.compile("(?i)" + Pattern.quote(w.trim())).matcher(t).replaceAll(BAR);
			}
		}
		if (hide("privacy.chatcoords")) {
			t = COORDS.matcher(t).replaceAll(BAR);
			t = XZ.matcher(t).replaceAll(BAR);
		}
		if (hide("privacy.slurs")) t = ContentFilter.maskSlurs(t);
		if (hide("privacy.swears")) t = ContentFilter.maskSwears(t);
		return t;
	}

	/** Censors a chat line but keeps its colours. Returns null when nothing needed hiding. */
	public static Component apply(Component msg) {
		if (!on()) return null;
		String plain = msg.getString();
		String cleaned = apply(plain);
		if (cleaned.equals(plain)) return null;
		MutableComponent out = Component.empty();
		boolean[] whole = {true};
		msg.visit((style, text) -> {
			String c = apply(text);
			out.append(Component.literal(c).setStyle(style));
			return Optional.empty();
		}, Style.EMPTY);
		// something spread over several coloured pieces: fall back to one plain line
		if (!out.getString().equals(cleaned)) return Component.literal(cleaned);
		return out;
	}

	public static String describe() {
		return "Hidden on stream: " + (hide("privacy.chatcoords") ? "coords, " : "") + (hide("privacy.swears") ? "swears, " : "")
				+ (hide("privacy.slurs") ? "slurs, " : "") + (hide("privacy.personal") ? "personal info (" + KyotoConfig.get().streamHiddenWords.size() + " own words)" : "")
				.toLowerCase(Locale.ROOT);
	}
}
