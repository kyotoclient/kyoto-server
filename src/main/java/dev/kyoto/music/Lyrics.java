package dev.kyoto.music;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Synced lyrics from LRCLIB (free, community lyrics database - lrclib.net).
 * Spotify doesn't let apps read its own lyrics, so we look the song up here by title/artist/duration.
 */
public final class Lyrics {
	private Lyrics() {}

	/**
	 * One lyric line. words[i] starts singing at wordMs[i]. If the lyrics file has word timings
	 * ("enhanced" LRC with <mm:ss.xx> tags) those are used, otherwise they're estimated from the
	 * line length and the time until the next line.
	 */
	public record Line(long timeMs, String text, String[] words, long[] wordMs, long endMs) {
		public Line(long timeMs, String text) {
			this(timeMs, text, text.isBlank() ? new String[0] : text.trim().split("\\s+"), null, timeMs + 4000);
		}

		/** 0..1 how far the singer is through word i at this song position. */
		public float wordProgress(int i, long posMs) {
			if (wordMs == null || i >= wordMs.length) return posMs >= timeMs ? 1 : 0;
			long start = wordMs[i];
			long end = i + 1 < wordMs.length ? wordMs[i + 1] : Math.min(endMs, start + 900);
			if (posMs <= start) return 0;
			if (posMs >= end) return 1;
			return (posMs - start) / (float) Math.max(1, end - start);
		}
	}

	private static final Pattern WORD_TAG = Pattern.compile("<(\\d+):(\\d+(?:\\.\\d+)?)>");

	private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(6)).build();
	private static final Pattern TAG = Pattern.compile("\\[(\\d+):(\\d+(?:\\.\\d+)?)]");

	private static volatile List<Line> lines = Collections.emptyList();
	private static volatile String forTrack = null;
	public static volatile String state = "";

	public static List<Line> lines() {
		return lines;
	}

	public static void clear() {
		lines = Collections.emptyList();
		forTrack = null;
	}

	/** Index of the line that should be showing at this song position (or -1). */
	public static int indexAt(long posMs) {
		List<Line> l = lines;
		int idx = -1;
		for (int i = 0; i < l.size(); i++) {
			if (l.get(i).timeMs() <= posMs) idx = i;
			else break;
		}
		return idx;
	}

	/** Called from the Spotify thread when the song changes. */
	public static void fetch(String trackId, String title, String artist, String album, long durationMs) {
		lines = Collections.emptyList();
		forTrack = trackId;
		state = "Finding lyrics...";
		try {
			String synced = null;
			String q = "track_name=" + enc(title) + "&artist_name=" + enc(artist) + "&album_name=" + enc(album)
					+ "&duration=" + Math.round(durationMs / 1000.0);
			HttpResponse<String> res = get("https://lrclib.net/api/get?" + q);
			if (res.statusCode() == 200) {
				JsonObject j = JsonParser.parseString(res.body()).getAsJsonObject();
				synced = str(j, "syncedLyrics");
			}
			if (synced == null) {
				// fallback: search without album/duration
				HttpResponse<String> s = get("https://lrclib.net/api/search?track_name=" + enc(title) + "&artist_name=" + enc(artist));
				if (s.statusCode() == 200) {
					JsonArray arr = JsonParser.parseString(s.body()).getAsJsonArray();
					for (JsonElement e : arr) {
						String cand = str(e.getAsJsonObject(), "syncedLyrics");
						if (cand != null) {
							synced = cand;
							break;
						}
					}
				}
			}
			if (!trackId.equals(forTrack)) return; // song changed while we were loading
			if (synced == null) {
				state = "No synced lyrics for this song";
				return;
			}
			lines = parse(synced);
			state = lines.isEmpty() ? "No synced lyrics for this song" : "Lyrics synced";
		} catch (Exception e) {
			state = "Lyrics unavailable";
		}
	}

	static List<Line> parse(String lrc) {
		record Raw(long t, String body) {}
		List<Raw> raws = new ArrayList<>();
		for (String raw : lrc.split("\\r?\\n")) {
			Matcher m = TAG.matcher(raw);
			List<Long> times = new ArrayList<>();
			int end = 0;
			while (m.find() && m.start() == end) {
				times.add(ms(m.group(1), m.group(2)));
				end = m.end();
			}
			if (times.isEmpty()) continue;
			String body = raw.substring(end).trim();
			for (long t : times) raws.add(new Raw(t, body));
		}
		raws.sort((a, b) -> Long.compare(a.t(), b.t()));
		List<Line> out = new ArrayList<>();
		for (int i = 0; i < raws.size(); i++) {
			Raw r = raws.get(i);
			long next = i + 1 < raws.size() ? raws.get(i + 1).t() : r.t() + 5000;
			out.add(build(r.t(), r.body(), next));
		}
		return out;
	}

	/** Builds a line with per-word times (real ones from <mm:ss.xx> tags, or estimated). */
	private static Line build(long start, String body, long next) {
		List<String> words = new ArrayList<>();
		List<Long> times = new ArrayList<>();
		Matcher wm = WORD_TAG.matcher(body);
		if (wm.find()) {
			wm.reset();
			int last = 0;
			long cur = start;
			while (wm.find()) {
				addWords(body.substring(last, wm.start()), cur, words, times);
				cur = ms(wm.group(1), wm.group(2));
				last = wm.end();
			}
			addWords(body.substring(last), cur, words, times);
		}
		String text = body.replaceAll("<\\d+:\\d+(?:\\.\\d+)?>", "").replaceAll("\\s+", " ").trim();
		if (words.isEmpty() || words.size() != times.size()) {
			words.clear();
			times.clear();
			if (!text.isEmpty()) {
				String[] w = text.split(" ");
				int total = 0;
				for (String x : w) total += x.length() + 1;
				long gap = Math.max(300, next - start);
				// singers rarely use the whole gap - assume ~70ms per letter, up to 90% of the gap
				long span = Math.min((long) (gap * 0.9), Math.max(500, total * 70L + 200));
				int before = 0;
				for (String x : w) {
					words.add(x);
					times.add(start + span * before / Math.max(1, total));
					before += x.length() + 1;
				}
			}
		}
		long[] arr = new long[times.size()];
		for (int i = 0; i < arr.length; i++) arr[i] = times.get(i);
		return new Line(start, text, words.toArray(new String[0]), arr, Math.max(start + 400, next));
	}

	private static void addWords(String chunk, long t, List<String> words, List<Long> times) {
		boolean first = true;
		for (String w : chunk.trim().split("\\s+")) {
			if (w.isEmpty()) continue;
			words.add(w);
			times.add(first ? t : t + 120);
			first = false;
		}
	}

	private static long ms(String min, String sec) {
		return Long.parseLong(min) * 60_000L + Math.round(Double.parseDouble(sec) * 1000);
	}

	private static HttpResponse<String> get(String url) throws Exception {
		HttpRequest req = HttpRequest.newBuilder(URI.create(url))
				.header("User-Agent", "KyotoClient/2.0 (Minecraft Fabric mod)")
				.timeout(Duration.ofSeconds(10)).GET().build();
		return HTTP.send(req, HttpResponse.BodyHandlers.ofString());
	}

	private static String str(JsonObject j, String key) {
		if (!j.has(key) || j.get(key).isJsonNull()) return null;
		String s = j.get(key).getAsString();
		return s.isBlank() ? null : s;
	}

	private static String enc(String s) {
		return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8);
	}
}
