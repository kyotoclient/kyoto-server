package dev.kyoto.social;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.kyoto.config.KyotoConfig;
import dev.kyoto.hud.Notifications;
import dev.kyoto.module.Modules;
import dev.kyoto.net.KyotoNet;
import dev.kyoto.util.Compat;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.SourceDataLine;
import javax.sound.sampled.TargetDataLine;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Voice Rooms: group voice chat with Kyoto players in game and on the website (up to 16 people).
 * Like friend calls, audio is Opus (Simple Voice Chat's codec) sent through the Kyoto server. Everyone gets their own decoder and
 * a small jitter buffer, then all voices are mixed into one speaker line. Voice activation (or push to talk), mute and deafen.
 */
public final class VoiceRooms {
	private VoiceRooms() {}

	private static final Logger LOG = LoggerFactory.getLogger("kyoto-voice");
	private static final int RATE = 48000, FRAME = 960;

	public record Member(int slot, String name, boolean muted, boolean deaf, boolean speaking, boolean host, String client) {}

	public static volatile String roomId = null, roomName = "", roomTopic = "", roomHost = "";
	public static volatile int you = 0;
	public static volatile List<Member> members = List.of();
	public static volatile boolean muted = false, deaf = false, connecting = false;
	public static volatile float level = 0;
	public static volatile long lastSpokeAt = 0;
	public static final Map<String, Float> VOLUME = new ConcurrentHashMap<>();

	private static volatile WebSocket ws;
	private static volatile Thread micThread, mixThread;
	private static volatile SourceDataLine speaker;
	private static volatile Calls.Codec encoder;
	private static boolean sending = false;

	/** One person: their decoder + a little queue of decoded 20 ms frames. */
	private static final class Voice {
		final Calls.Codec codec;
		final ArrayDeque<short[]> q = new ArrayDeque<>();
		boolean primed = false;

		Voice(Calls.Codec c) { codec = c; }
	}

	private static final Map<Integer, Voice> VOICES = new ConcurrentHashMap<>();

	public static boolean inRoom() {
		return roomId != null;
	}

	public static boolean available() {
		return Compat.voiceChat() && Calls.codecFactory != null;
	}

	/* ---------------- joining / leaving ---------------- */

	public static void join(String id) {
		if (!available()) {
			Notifications.push("Install Simple Voice Chat", "Voice Rooms use its voice codec (it's free on Modrinth).", Notifications.INFO);
			return;
		}
		if (!KyotoNet.loggedIn()) return;
		if (inRoom()) leave(true);
		if (Calls.inCall()) Calls.hangup();
		roomId = id;
		connecting = true;
		muted = false;
		deaf = false;
		String base = KyotoConfig.get().serverUrl.trim().replaceAll("/+$", "");
		String url = base.replaceFirst("^http", "ws") + "/voice?client=game&room=" + KyotoNet.enc(id) + "&token=" + KyotoConfig.get().netToken;
		HttpClient.newHttpClient().newWebSocketBuilder().buildAsync(URI.create(url), new Listener()).whenComplete((w, err) -> {
			connecting = false;
			if (err != null || !id.equals(roomId)) {
				if (err != null) Compat.runOnClient(() -> { Notifications.push("Couldn't join the voice room", "It may be full, closed or friends only.", Notifications.DANGER); roomId = null; });
				if (w != null) w.sendClose(WebSocket.NORMAL_CLOSURE, "bye");
				return;
			}
			ws = w;
			startAudio();
			Compat.runOnClient(() -> Notifications.push("Joined voice", "Use headphones · " + (ptt() ? "hold your push-to-talk key" : "just talk") + " · /kyoto voice leave", Notifications.GOOD));
		});
	}

	public static void leave(boolean silent) {
		WebSocket w = ws;
		ws = null;
		roomId = null;
		members = List.of();
		stopAudio();
		if (w != null) try { w.sendClose(WebSocket.NORMAL_CLOSURE, "bye"); } catch (Exception ignored) { }
		if (!silent) Notifications.push("Left voice", "", Notifications.INFO, 1500);
	}

	public static void setMuted(boolean on) {
		muted = on;
		send("{\"t\":\"mute\",\"on\":" + on + "}");
	}

	public static void setDeaf(boolean on) {
		deaf = on;
		if (on) setMuted(true);
		send("{\"t\":\"deaf\",\"on\":" + on + "}");
	}

	public static void kick(int slot) {
		send("{\"t\":\"kick\",\"slot\":" + slot + "}");
	}

	private static void send(String json) {
		WebSocket w = ws;
		if (w != null) try { w.sendText(json, true); } catch (Exception ignored) { }
	}

	public static boolean ptt() {
		return Modules.opt("call.mode") == 1;
	}

	/** Called every client tick. */
	public static void tick() {
		if (inRoom() && !connecting && ws == null) leave(true);
	}

	/* ---------------- socket ---------------- */

	private static final class Listener implements WebSocket.Listener {
		private final StringBuilder text = new StringBuilder();
		private final ByteArrayOutputStream bin = new ByteArrayOutputStream();

		@Override
		public CompletionStage<?> onText(WebSocket w, CharSequence data, boolean last) {
			text.append(data);
			if (last) {
				String s = text.toString();
				text.setLength(0);
				handle(s);
			}
			w.request(1);
			return null;
		}

		@Override
		public CompletionStage<?> onBinary(WebSocket w, ByteBuffer data, boolean last) {
			byte[] b = new byte[data.remaining()];
			data.get(b);
			bin.write(b, 0, b.length);
			if (last) {
				hear(bin.toByteArray());
				bin.reset();
			}
			w.request(1);
			return null;
		}

		@Override
		public CompletionStage<?> onClose(WebSocket w, int code, String reason) {
			if (ws == w) Minecraft.getInstance().execute(() -> { if (inRoom()) { Notifications.push("Voice room closed", "", Notifications.INFO); leave(true); } });
			return null;
		}

		@Override
		public void onError(WebSocket w, Throwable error) {
			if (ws == w) Minecraft.getInstance().execute(() -> leave(true));
		}
	}

	private static void handle(String json) {
		try {
			JsonObject o = JsonParser.parseString(json).getAsJsonObject();
			String t = o.get("t").getAsString();
			if (t.equals("kicked")) { Minecraft.getInstance().execute(() -> { Notifications.push("Removed from the voice room", "", Notifications.WARN); leave(true); }); return; }
			if (!t.equals("state")) return;
			JsonObject r = o.getAsJsonObject("room");
			you = o.get("you").getAsInt();
			roomName = r.get("name").getAsString();
			roomTopic = r.has("topic") ? r.get("topic").getAsString() : "";
			roomHost = r.get("host").getAsString();
			List<Member> l = new ArrayList<>();
			for (JsonElement e : r.getAsJsonArray("members")) {
				JsonObject m = e.getAsJsonObject();
				l.add(new Member(m.get("slot").getAsInt(), m.get("name").getAsString(), m.get("muted").getAsBoolean(), m.get("deaf").getAsBoolean(),
						m.get("speaking").getAsBoolean(), m.get("host").getAsBoolean(), m.get("client").getAsString()));
			}
			List<Member> before = members;
			members = l;
			// someone joined / left
			if (!before.isEmpty()) for (Member m : l) if (before.stream().noneMatch(b -> b.slot() == m.slot()) && m.slot() != you) Compat.runOnClient(() -> Compat.sound(net.minecraft.sounds.SoundEvents.NOTE_BLOCK_PLING.value(), 0.4f, 1.6f));
			for (Integer s : VOICES.keySet()) if (l.stream().noneMatch(m -> m.slot() == s)) { Voice v = VOICES.remove(s); if (v != null) try { v.codec.close(); } catch (Exception ignored) { } }
		} catch (Exception e) {
			LOG.debug("bad voice message", e);
		}
	}

	private static void hear(byte[] pkt) {
		if (deaf || pkt.length < 2 || speaker == null) return;
		int slot = pkt[0] & 0xFF;
		Voice v = VOICES.computeIfAbsent(slot, s -> {
			Calls.Codec c = Calls.codecFactory == null ? null : Calls.codecFactory.get();
			return c == null ? null : new Voice(c);
		});
		if (v == null) return;
		try {
			byte[] opus = new byte[pkt.length - 1];
			System.arraycopy(pkt, 1, opus, 0, opus.length);
			short[] pcm = v.codec.decode(opus);
			if (pcm == null) return;
			synchronized (v.q) {
				v.q.addLast(pcm);
				while (v.q.size() > 15) v.q.pollFirst(); // never let delay build up (300 ms max)
			}
		} catch (Exception e) {
			LOG.debug("decode failed", e);
		}
	}

	/* ---------------- audio ---------------- */

	private static void startAudio() {
		stopAudio();
		try {
			encoder = Calls.codecFactory.get();
			AudioFormat fmt = new AudioFormat(RATE, 16, 1, true, false);
			SourceDataLine out = AudioSystem.getSourceDataLine(fmt);
			out.open(fmt, FRAME * 2 * 6);
			out.start();
			speaker = out;
			Thread mix = new Thread(VoiceRooms::mixLoop, "Kyoto-VoiceMix");
			mix.setDaemon(true);
			mixThread = mix;
			mix.start();
			try {
				TargetDataLine mic = AudioSystem.getTargetDataLine(fmt);
				mic.open(fmt, FRAME * 2 * 4);
				mic.start();
				Thread t = new Thread(() -> micLoop(mic), "Kyoto-VoiceMic");
				t.setDaemon(true);
				micThread = t;
				t.start();
			} catch (Exception e) {
				muted = true;
				send("{\"t\":\"mute\",\"on\":true}");
				Compat.runOnClient(() -> Notifications.push("No microphone found", "You can still listen.", Notifications.WARN));
			}
		} catch (Exception e) {
			LOG.warn("voice audio failed", e);
			Compat.runOnClient(() -> Notifications.push("Voice audio failed", e.getMessage() == null ? "" : e.getMessage(), Notifications.DANGER));
		}
	}

	private static void micLoop(TargetDataLine mic) {
		byte[] raw = new byte[FRAME * 2];
		short[] pcm = new short[FRAME];
		long openUntil = 0;
		try {
			while (micThread == Thread.currentThread() && inRoom()) {
				int n = mic.read(raw, 0, raw.length);
				if (n < raw.length) continue;
				long sum = 0;
				for (int i = 0; i < FRAME; i++) {
					pcm[i] = (short) ((raw[i * 2] & 0xFF) | (raw[i * 2 + 1] << 8));
					sum += Math.abs(pcm[i]);
				}
				float lvl = sum / (float) FRAME / 6000f;
				level = Math.min(1f, level * 0.6f + lvl * 0.4f);
				float threshold = new float[]{0.015f, 0.03f, 0.06f}[Math.max(0, Math.min(2, Modules.opt("voice.sensitivity")))];
				boolean talk = ptt() ? Calls.pttDown : lvl > threshold;
				long now = System.currentTimeMillis();
				if (talk) openUntil = now + 350;
				boolean open = !muted && now < openUntil;
				if (sending && !open) send("{\"t\":\"quiet\"}");
				sending = open;
				if (!open) continue;
				lastSpokeAt = now;
				Calls.Codec c = encoder;
				WebSocket w = ws;
				if (c == null || w == null) continue;
				byte[] opus = c.encode(pcm);
				if (opus != null && opus.length > 0) w.sendBinary(ByteBuffer.wrap(opus), true).join();
			}
		} catch (Exception e) {
			LOG.debug("voice mic ended", e);
		} finally {
			mic.stop();
			mic.close();
		}
	}

	/** Every 20 ms: take one frame from everyone who's talking, add them up, play. */
	private static void mixLoop() {
		int[] acc = new int[FRAME];
		byte[] out = new byte[FRAME * 2];
		long next = System.nanoTime();
		try {
			while (mixThread == Thread.currentThread() && inRoom()) {
				java.util.Arrays.fill(acc, 0);
				boolean any = false;
				float master = new float[]{0.5f, 0.75f, 1f, 1.5f}[Math.max(0, Math.min(3, Modules.opt("call.volume")))];
				for (Map.Entry<Integer, Voice> e : VOICES.entrySet()) {
					Voice v = e.getValue();
					short[] f;
					synchronized (v.q) {
						if (!v.primed && v.q.size() < 3) continue; // ~60 ms buffer before someone starts
						v.primed = true;
						f = v.q.pollFirst();
						if (f == null) { v.primed = false; continue; }
					}
					float vol = master * VOLUME.getOrDefault(nameOf(e.getKey()), 1f);
					for (int i = 0; i < Math.min(FRAME, f.length); i++) acc[i] += Math.round(f[i] * vol);
					any = true;
				}
				SourceDataLine sp = speaker;
				if (sp == null) break;
				if (any || sp.available() > FRAME * 2 * 4) {
					for (int i = 0; i < FRAME; i++) {
						int s = Math.max(-32768, Math.min(32767, acc[i]));
						out[i * 2] = (byte) s;
						out[i * 2 + 1] = (byte) (s >> 8);
					}
					if (sp.getBufferSize() - sp.available() < FRAME * 2 * 5) sp.write(out, 0, out.length);
				}
				next += 20_000_000L;
				long sleep = next - System.nanoTime();
				if (sleep > 0) Thread.sleep(sleep / 1_000_000L, (int) (sleep % 1_000_000L));
				else if (sleep < -200_000_000L) next = System.nanoTime();
			}
		} catch (Exception e) {
			LOG.debug("voice mix ended", e);
		}
	}

	private static String nameOf(int slot) {
		for (Member m : members) if (m.slot() == slot) return m.name();
		return "";
	}

	private static void stopAudio() {
		micThread = null;
		mixThread = null;
		sending = false;
		SourceDataLine s = speaker;
		speaker = null;
		if (s != null) try { s.stop(); s.close(); } catch (Exception ignored) { }
		Calls.Codec c = encoder;
		encoder = null;
		if (c != null) try { c.close(); } catch (Exception ignored) { }
		for (Voice v : VOICES.values()) try { v.codec.close(); } catch (Exception ignored) { }
		VOICES.clear();
	}
}
