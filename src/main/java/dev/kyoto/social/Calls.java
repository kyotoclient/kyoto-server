package dev.kyoto.social;

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
import javax.sound.sampled.FloatControl;
import javax.sound.sampled.SourceDataLine;
import javax.sound.sampled.TargetDataLine;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;

/**
 * Friend calls. Voice is compressed with Opus from Simple Voice Chat (that's why SVC is required) and
 * relayed through the Kyoto server, so it works even when you're on different servers or in singleplayer.
 * Only friends can call each other. Nothing is recorded or stored.
 */
public final class Calls {
	private Calls() {}

	private static final Logger LOG = LoggerFactory.getLogger("kyoto-calls");
	private static final int RATE = 48000, FRAME = 960; // 20 ms

	/** Opus codec handed to us by Simple Voice Chat (see VoicePlugin). Null = SVC not installed. */
	public interface Codec {
		byte[] encode(short[] pcm);

		short[] decode(byte[] opus);

		void close();
	}

	public static volatile Supplier<Codec> codecFactory = null;

	private static volatile WebSocket ws;
	private static volatile long lastConnectTry = 0;
	private static volatile String partner = null, ringingFrom = null, callingTo = null;
	private static volatile long ringStarted = 0;
	public static volatile boolean muted = false;
	public static volatile boolean pttDown = false;
	public static volatile float level = 0; // mic level for the UI

	private static volatile Thread micThread;
	private static volatile SourceDataLine speaker;
	private static volatile Codec codec;

	public static boolean inCall() {
		return partner != null;
	}

	public static String partner() {
		return partner;
	}

	public static String ringing() {
		return ringingFrom;
	}

	public static String calling() {
		return callingTo;
	}

	public static boolean available() {
		return Compat.voiceChat() && codecFactory != null && Modules.on("calls");
	}

	/* ---------------- connection (kept open in the background so friends can ring you) ---------------- */

	public static void tick() {
		if (!available() || !KyotoNet.loggedIn()) {
			if (ws != null && partner == null) closeWs();
			return;
		}
		if (ws == null && System.currentTimeMillis() - lastConnectTry > 15000) connect();
		if (ringingFrom != null && System.currentTimeMillis() - ringStarted > 30000) ringingFrom = null;
		if (callingTo != null && System.currentTimeMillis() - ringStarted > 30000) {
			send("hangup", null);
			callingTo = null;
			Notifications.push("No answer", "", Notifications.INFO);
		}
	}

	private static void connect() {
		lastConnectTry = System.currentTimeMillis();
		String base = KyotoConfig.get().serverUrl.trim().replaceAll("/+$", "");
		String url = base.replaceFirst("^http", "ws") + "/ws?token=" + KyotoConfig.get().netToken;
		HttpClient.newHttpClient().newWebSocketBuilder().buildAsync(URI.create(url), new Listener()).whenComplete((w, err) -> {
			if (err != null) LOG.debug("call socket failed", err);
			else ws = w;
		});
	}

	private static void closeWs() {
		WebSocket w = ws;
		ws = null;
		if (w != null) try {
			w.sendClose(WebSocket.NORMAL_CLOSURE, "bye");
		} catch (Exception ignored) {
		}
	}

	private static void send(String type, String name) {
		WebSocket w = ws;
		if (w == null) return;
		JsonObject o = new JsonObject();
		o.addProperty("t", type);
		if (name != null) o.addProperty("name", name);
		w.sendText(o.toString(), true);
	}

	private static final class Listener implements WebSocket.Listener {
		private final StringBuilder text = new StringBuilder();
		private final ByteArrayOutputStream bin = new ByteArrayOutputStream();

		@Override
		public CompletionStage<?> onText(WebSocket w, CharSequence data, boolean last) {
			text.append(data);
			if (last) {
				String s = text.toString();
				text.setLength(0);
				Minecraft.getInstance().execute(() -> handle(s));
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
				play(bin.toByteArray());
				bin.reset();
			}
			w.request(1);
			return null;
		}

		@Override
		public CompletionStage<?> onClose(WebSocket w, int code, String reason) {
			ws = null;
			Minecraft.getInstance().execute(Calls::endLocal);
			return null;
		}

		@Override
		public void onError(WebSocket w, Throwable error) {
			ws = null;
			Minecraft.getInstance().execute(Calls::endLocal);
		}
	}

	private static void handle(String json) {
		try {
			JsonObject o = JsonParser.parseString(json).getAsJsonObject();
			String t = o.get("t").getAsString();
			String name = o.has("name") ? o.get("name").getAsString() : null;
			switch (t) {
				case "ring" -> {
					ringingFrom = name;
					ringStarted = System.currentTimeMillis();
					Notifications.push(name + " is calling", "Friends > Answer, or /kyoto answer", Notifications.GOOD, 15000, name, null);
					Compat.sound(net.minecraft.sounds.SoundEvents.NOTE_BLOCK_BELL.value(), 0.8f, 1.2f);
				}
				case "start" -> {
					partner = name;
					ringingFrom = null;
					callingTo = null;
					startAudio();
					Notifications.push("On a call with " + name, "Use headphones · /kyoto hangup to end", Notifications.GOOD, 4000, name, null);
				}
				case "end" -> {
					if (partner != null || callingTo != null || ringingFrom != null) Notifications.push("Call ended", "", Notifications.INFO);
					endLocal();
				}
				case "error" -> {
					callingTo = null;
					Notifications.push("Call failed", o.has("msg") ? o.get("msg").getAsString() : "", Notifications.DANGER);
				}
				default -> {
				}
			}
		} catch (Exception e) {
			LOG.debug("bad call message", e);
		}
	}

	/* ---------------- actions ---------------- */

	public static void call(String name) {
		if (!Compat.voiceChat()) {
			Notifications.push("Install Simple Voice Chat", "Calls use its voice codec. Get it from Modrinth.", Notifications.INFO);
			return;
		}
		if (!available()) {
			Notifications.push("Calls are off", "Social > Friend Calls", Notifications.INFO);
			return;
		}
		if (ws == null) {
			connect();
			Notifications.push("Connecting...", "Try again in a second", Notifications.INFO);
			return;
		}
		if (inCall()) hangup();
		callingTo = name;
		ringStarted = System.currentTimeMillis();
		send("call", name);
		Notifications.push("Calling " + name + "...", "", Notifications.INFO, 4000, name, null);
	}

	public static void accept() {
		if (ringingFrom == null) return;
		send("accept", ringingFrom);
	}

	public static void hangup() {
		send("hangup", null);
		endLocal();
	}

	public static void toggleMute() {
		muted = !muted;
		Notifications.push(muted ? "Mic muted" : "Mic on", "", Notifications.INFO, 1500);
	}

	private static void endLocal() {
		partner = null;
		callingTo = null;
		ringingFrom = null;
		stopAudio();
	}

	/* ---------------- audio ---------------- */

	private static void startAudio() {
		stopAudio();
		if (VoiceRooms.inRoom()) VoiceRooms.leave(true); // one mic at a time
		try {
			codec = codecFactory.get();
			if (codec == null) throw new IllegalStateException("Simple Voice Chat's voice codec isn't available");
			AudioFormat fmt = new AudioFormat(RATE, 16, 1, true, false);
			SourceDataLine out = AudioSystem.getSourceDataLine(fmt);
			out.open(fmt, RATE * 2 / 5);
			out.start();
			speaker = out;
			TargetDataLine mic = AudioSystem.getTargetDataLine(fmt);
			mic.open(fmt, FRAME * 2 * 4);
			mic.start();
			Thread t = new Thread(() -> micLoop(mic), "Kyoto-CallMic");
			t.setDaemon(true);
			micThread = t;
			t.start();
		} catch (Exception e) {
			LOG.warn("call audio failed", e);
			Notifications.push("No microphone?", "Check your mic in Windows sound settings", Notifications.DANGER);
		}
	}

	private static void micLoop(TargetDataLine mic) {
		byte[] raw = new byte[FRAME * 2];
		short[] pcm = new short[FRAME];
		try {
			while (micThread == Thread.currentThread() && partner != null) {
				int n = mic.read(raw, 0, raw.length);
				if (n < raw.length) continue;
				long sum = 0;
				for (int i = 0; i < FRAME; i++) {
					pcm[i] = (short) ((raw[i * 2] & 0xFF) | (raw[i * 2 + 1] << 8));
					sum += Math.abs(pcm[i]);
				}
				level = Math.min(1f, sum / (float) FRAME / 6000f);
				boolean ptt = Modules.opt("call.mode") == 1;
				if (muted || (ptt && !pttDown)) continue;
				Codec c = codec;
				WebSocket w = ws;
				if (c == null || w == null) continue;
				byte[] opus = c.encode(pcm);
				if (opus != null && opus.length > 0) w.sendBinary(ByteBuffer.wrap(opus), true).join();
			}
		} catch (Exception e) {
			LOG.debug("mic loop ended", e);
		} finally {
			mic.stop();
			mic.close();
		}
	}

	private static void play(byte[] opus) {
		Codec c = codec;
		SourceDataLine out = speaker;
		if (c == null || out == null || partner == null) return;
		try {
			short[] pcm = c.decode(opus);
			if (pcm == null) return;
			float vol = new float[]{0.5f, 0.75f, 1f, 1.5f}[Modules.opt("call.volume")];
			byte[] b = new byte[pcm.length * 2];
			for (int i = 0; i < pcm.length; i++) {
				int v = Math.max(-32768, Math.min(32767, Math.round(pcm[i] * vol)));
				b[i * 2] = (byte) v;
				b[i * 2 + 1] = (byte) (v >> 8);
			}
			// don't let delay build up: if the buffer is very full, drop this packet
			if (out.getBufferSize() - out.available() > RATE * 2 * 3 / 10) return;
			out.write(b, 0, b.length);
		} catch (Exception e) {
			LOG.debug("decode failed", e);
		}
	}

	private static void stopAudio() {
		micThread = null;
		SourceDataLine s = speaker;
		speaker = null;
		if (s != null) {
			try {
				s.stop();
				s.close();
			} catch (Exception ignored) {
			}
		}
		Codec c = codec;
		codec = null;
		if (c != null) try {
			c.close();
		} catch (Exception ignored) {
		}
	}
}
