package dev.kyoto.stream;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Cleans text from other people (donation messages, Kyoto Hub posts, chat, DMs):
 *  - links are removed unless they're on a safe list (YouTube, Twitch, Modrinth, GitHub...)
 *  - .onion / IP-address / link-shortener links are always removed
 *  - bad words are masked; slurs and adult words make {@link #isBlocked} true
 * The Kyoto server runs the same rules, this is a second layer.
 */
public final class ContentFilter {
	private ContentFilter() {}

	private static final Pattern URL = Pattern.compile("(?i)\\b((?:https?://|www\\.)\\S+|[a-z0-9-]+(?:\\.[a-z0-9-]+)*\\.(?:com|net|org|gg|tv|io|me|co|xyz|ru|onion|link|ly|to|cc|lol|app|dev|site|online|live|club|info|biz|us|uk|de|fr)(?:/\\S*)?)");
	private static final String[] SAFE = {"youtube.com", "youtu.be", "twitch.tv", "modrinth.com", "github.com", "minecraftcapes.net",
			"namemc.com", "minecraftskins.com", "curseforge.com", "minecraft.net", "kick.com", "x.com", "twitter.com", "tiktok.com", "instagram.com", "guns.lol", "pastebin.com"};
	private static final String[] SHORTENERS = {"bit.ly", "tinyurl", "t.co/", "cutt.ly", "is.gd", "rb.gy", "shorturl", "grabify", "iplogger", "2no.co"};

	/** Base64 so the source doesn't spell them out. Adult/hateful words: a message containing one is blocked. */
	private static final String[] BLOCK_B64 = {
			"cG9ybg==", "bnNmdw==", "aGVudGFp", "eHh4", "b25seWZhbnM=", "bnVkZXM=", "bnVkZQ==", "c2V4", "Y29jaw==", "cHVzc3k=", "ZGljaw==",
			"Y3Vt", "Ym9vYnM=", "bmlnZ2Vy", "bmlnZ2E=", "ZmFnZ290", "ZmFn", "cmV0YXJk", "a3lz", "a2lsbCB5b3Vyc2VsZg==", "dHJhbm55", "Y2hpbms=",
			"c3BpYw==", "a2lrZQ==", "d2V0YmFjaw==", "Y29vbg==", "cmFwZQ=="};
	/** Mild swears: masked with *** but the message still shows. */
	private static final String[] MASK_B64 = {"ZnVjaw==", "c2hpdA==", "Yml0Y2g=", "YXNz", "ZGFtbg==", "Y3JhcA==", "YmFzdGFyZA==", "d2hvcmU=", "c2x1dA=="};

	private static final List<Pattern> BLOCK = compile(BLOCK_B64);
	private static final List<Pattern> MASK = compile(MASK_B64);

	private static List<Pattern> compile(String[] b64) {
		List<Pattern> out = new ArrayList<>();
		for (String s : b64) {
			String w = new String(Base64.getDecoder().decode(s), StandardCharsets.UTF_8);
			StringBuilder rx = new StringBuilder("(?i)(?<![a-z])");
			for (char ch : w.toCharArray()) {
				if (ch == ' ') rx.append("\\s+");
				else rx.append("[").append(ch).append(leet(ch)).append("]+");
			}
			rx.append("(?![a-z])");
			out.add(Pattern.compile(rx.toString()));
		}
		return out;
	}

	private static String leet(char c) {
		return switch (Character.toLowerCase(c)) {
			case 'a' -> "4@";
			case 'e' -> "3";
			case 'i' -> "1!|";
			case 'o' -> "0";
			case 's' -> "5$";
			case 't' -> "7";
			case 'g' -> "9";
			default -> "";
		};
	}

	public static boolean isBlocked(String text) {
		if (text == null) return false;
		for (Pattern p : BLOCK) if (p.matcher(text).find()) return true;
		String low = text.toLowerCase(Locale.ROOT);
		return low.contains(".onion") || low.contains("porn") || low.contains("hentai");
	}

	/** Removes unsafe links and masks mild swears. */
	public static String clean(String text) {
		if (text == null) return "";
		String t = text.replaceAll("[\\u0000-\\u001f\\u202e\\u200b]", "");
		Matcher m = URL.matcher(t);
		StringBuilder sb = new StringBuilder();
		while (m.find()) m.appendReplacement(sb, Matcher.quoteReplacement(safeLink(m.group(1)) ? m.group(1) : "[link removed]"));
		m.appendTail(sb);
		t = sb.toString();
		for (Pattern p : MASK) t = p.matcher(t).replaceAll("***");
		return t;
	}

	/** Streamer Mode: swears -> ****. */
	public static String maskSwears(String t) {
		for (Pattern p : MASK) t = p.matcher(t).replaceAll(m -> "*".repeat(Math.max(3, m.group().length())));
		return t;
	}

	/** Streamer Mode: slurs and adult words -> ****. */
	public static String maskSlurs(String t) {
		for (Pattern p : BLOCK) t = p.matcher(t).replaceAll(m -> "*".repeat(Math.max(3, m.group().length())));
		return t;
	}

	public static boolean safeLink(String url) {
		String u = url.toLowerCase(Locale.ROOT);
		if (u.contains(".onion")) return false;
		if (u.matches("(?:https?://)?\\d{1,3}(?:\\.\\d{1,3}){3}.*")) return false;
		for (String s : SHORTENERS) if (u.contains(s)) return false;
		String host = u.replaceFirst("^https?://", "").replaceFirst("^www\\.", "");
		int slash = host.indexOf('/');
		if (slash >= 0) host = host.substring(0, slash);
		for (String s : SAFE) if (host.equals(s) || host.endsWith("." + s)) return true;
		return false;
	}
}
