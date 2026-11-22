package dev.kyoto.screens;

import dev.kyoto.util.Compat;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.FloatControl;
import javax.sound.sampled.SourceDataLine;
import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Plays one video: yt-dlp finds the streams, one ffmpeg decodes frames (RGBA at a fixed size),
 * another ffmpeg decodes sound (16-bit stereo 48 kHz) into Java's audio output.
 * Both are paced against the same clock, so they stay in sync. Everything runs off the render thread;
 * the newest frame is uploaded to a texture once per rendered frame.
 */
public final class VideoStream {
	private static final Logger LOG = LoggerFactory.getLogger("kyoto-video");
	private static final AtomicInteger IDS = new AtomicInteger();
	private final int FPS;
	private final boolean direct, muted, loop;
	/** Where the video SHOULD be right now (shared screens) - used to start at the right spot after loading. */
	private final java.util.function.DoubleSupplier target;

	/** yt-dlp is slow to start (it's a Python program), so remember what it found for each link for a while. */
	private record Found(String video, String audio, double duration, long at) {}

	private static final java.util.Map<String, Found> FOUND = new java.util.concurrent.ConcurrentHashMap<>();

	public final int w, h;
	public final String url;
	private volatile double startSec;
	private volatile boolean stopped = false;
	private final List<Process> procs = new ArrayList<>();

	public volatile String state = "Loading...";
	public volatile double duration = -1;
	public volatile boolean ended = false;
	public volatile float volume = 0f;   // 0..1, set every tick by Screens
	public volatile float pan = 0f;      // -1..1

	/*
	 * Frames live in 3 reusable native buffers (no new 4-15 MB array per frame - that was making Java's garbage
	 * collector stutter the whole game). The decoder fills a free buffer and publishes it as `latest`; the render
	 * thread takes it, copies it straight into the texture, and hands the buffer back.
	 */
	private final java.util.concurrent.ConcurrentLinkedQueue<java.nio.ByteBuffer> free = new java.util.concurrent.ConcurrentLinkedQueue<>();
	private final java.util.concurrent.atomic.AtomicReference<java.nio.ByteBuffer> latest = new java.util.concurrent.atomic.AtomicReference<>();
	private final java.util.List<java.nio.ByteBuffer> allBuffers = new java.util.ArrayList<>();
	/** The sound card's clock. The picture follows it, so sound and video always match. */
	private volatile SourceDataLine audioLine = null;
	private volatile long audioStartFrame = 0;
	private Compat.StreamTexture tex;
	private long t0Nanos;

	public VideoStream(String url, double startSec, int w, int h) {
		this(url, startSec, w, h, null, false, false, false, 30);
	}

	/** YouTube video that should be at target.get() seconds right now (screens + mini player). */
	public VideoStream(String url, java.util.function.DoubleSupplier target, int w, int h) {
		this(url, target.getAsDouble(), w, h, target, false, false, false, 30);
	}

	/** A local video file (profile backgrounds): no sound, loops forever, any frame rate. */
	public static VideoStream file(java.nio.file.Path file, int w, int h, int fps) {
		return new VideoStream(file.toAbsolutePath().toString(), 0, w, h, null, true, true, true, fps);
	}

	private VideoStream(String url, double startSec, int w, int h, java.util.function.DoubleSupplier target, boolean direct, boolean muted, boolean loop, int fps) {
		this.target = target;
		this.direct = direct;
		this.muted = muted;
		this.loop = loop;
		this.FPS = fps;
		this.url = url;
		this.startSec = Math.max(0, startSec);
		// if this Minecraft only allows the slow pixel-by-pixel copy, keep frames small so the game stays smooth
		if (Boolean.FALSE.equals(Compat.StreamTexture.lastFast) && w * h > 426 * 240) {
			w = 426;
			h = 240;
		}
		this.w = w;
		this.h = h;
		Thread t = new Thread(this::run, "Kyoto-Video-" + IDS.incrementAndGet());
		t.setDaemon(true);
		t.start();
	}

	/** Seconds into the video right now. */
	public double position() {
		if (t0Nanos == 0) return startSec;
		return startSec + clock();
	}

	/** Seconds since playback started: from the audio device when sound is playing (exact), else the wall clock. */
	private double clock() {
		SourceDataLine l = audioLine;
		if (l != null) {
			try {
				return (l.getLongFramePosition() - audioStartFrame) / 48000.0 + audioSkipped / 48000.0;
			} catch (Exception ignored) {
			}
		}
		return ((held ? heldSince : System.nanoTime()) - t0Nanos) / 1e9;
	}

	/* ---- hold: freezes picture + sound in place (Esc menu), then carries on from the same frame ---- */
	private volatile boolean held = false;
	private volatile long heldSince = 0;

	public boolean held() {
		return held;
	}

	public synchronized void hold(boolean h) {
		if (h == held) return;
		if (h) {
			heldSince = System.nanoTime();
			held = true;
			SourceDataLine l = audioLine;
			if (l != null) try {
				l.stop();
			} catch (Exception ignored) {
			}
		} else {
			// shift the start time by how long we were frozen, so nothing thinks it's running late
			t0Nanos += System.nanoTime() - heldSince;
			held = false;
			SourceDataLine l = audioLine;
			if (l != null) try {
				l.start();
			} catch (Exception ignored) {
			}
		}
	}

	private volatile long audioSkipped = 0; // frames skipped to catch up

	/** Render thread: push the newest frame to the GPU and return the texture (null until the first frame). */
	public Identifier texture() {
		if (stopped) return null;
		if (tex == null && latest.get() == null) return null;
		if (tex == null) tex = Compat.streamTexture(Identifier.fromNamespaceAndPath("kyoto", "screen/" + IDS.incrementAndGet()), w, h);
		synchronized (allBuffers) {
			if (stopped) return null;
			java.nio.ByteBuffer f = latest.getAndSet(null);
			if (f != null) {
				tex.upload(f);
				free.offer(f);
			}
		}
		return tex.id;
	}

	public void stop() {
		stopped = true;
		synchronized (procs) {
			for (Process p : procs) p.destroyForcibly();
			procs.clear();
		}
		if (tex != null) {
			Compat.StreamTexture t = tex;
			tex = null;
			net.minecraft.client.Minecraft.getInstance().execute(t::close);
		}
	}

	private Process start(List<String> cmd) throws Exception {
		ProcessBuilder pb = new ProcessBuilder(cmd);
		pb.redirectError(ProcessBuilder.Redirect.DISCARD);
		Process p = pb.start();
		synchronized (procs) {
			if (stopped) {
				p.destroyForcibly();
				throw new IllegalStateException("stopped");
			}
			procs.add(p);
		}
		return p;
	}

	private void run() {
		try {
			if (!MediaTools.ready()) {
				state = "Video tools not installed - open Tools > Video Screens";
				return;
			}
			if (direct) {
				// local file / direct media link: ffmpeg reads it as-is
				state = "Loading...";
				t0Nanos = System.nanoTime() + 300_000_000L;
				do {
					videoLoop(url);
					if (!loop || stopped) break;
					startSec = 0;
					t0Nanos = System.nanoTime();
				} while (!stopped);
				if (!loop && !stopped) {
					ended = true;
					state = "Ended";
				}
				return;
			}
			int height = h <= 360 ? 360 : h <= 480 ? 480 : h <= 720 ? 720 : 1080;
			String key = url + "@" + height;
			Found f = FOUND.get(key);
			boolean local = url.startsWith("file:");
			if (local) {
				// a video file on your PC (mini player): ffmpeg reads it directly, with sound
				String path = url.substring(5);
				if (!java.nio.file.Files.exists(java.nio.file.Path.of(path))) {
					state = "That file is gone (moved or deleted?)";
					return;
				}
				f = new Found(path, path, -1, System.currentTimeMillis());
			} else if (f == null || System.currentTimeMillis() - f.at() > 3 * 3600_000L) {
				state = "Finding the video...";
				// H.264 first: it decodes much faster than VP9/AV1, so the picture keeps up with the sound
				String fmt = "bv*[height<=" + height + "][vcodec^=avc1]+ba[ext=m4a]/bv*[height<=" + height + "][vcodec!^=av01]+ba/b[height<=" + height + "]/b";
				List<String> cmd = List.of(MediaTools.ytdlp(), "--no-warnings", "--no-playlist", "--no-check-certificates", "-f", fmt,
						"--print", "duration", "--print", "urls", url);
				Process yt = start(new ArrayList<>(cmd));
				List<String> lines = new ArrayList<>();
				try (BufferedReader r = new BufferedReader(new InputStreamReader(yt.getInputStream(), StandardCharsets.UTF_8))) {
					String l;
					while ((l = r.readLine()) != null) if (!l.isBlank()) lines.add(l.trim());
				}
				yt.waitFor();
				List<String> urls = new ArrayList<>();
				double dur = -1;
				for (String l : lines) {
					if (l.startsWith("http")) urls.add(l);
					else try {
						dur = Double.parseDouble(l);
					} catch (NumberFormatException ignored) {
					}
				}
				if (urls.isEmpty()) {
					state = "Couldn't load that video (private, age-restricted or removed?) - try updating video tools";
					return;
				}
				f = new Found(urls.get(0), urls.get(urls.size() - 1), dur, System.currentTimeMillis());
				FOUND.put(key, f);
			}
			duration = f.duration();
			// live streams (Twitch, Kick, YouTube live) have no length and can't seek: just join live
			boolean live = !local && duration <= 0;
			if (stopped) return;
			String videoUrl = f.video(), audioUrl = f.audio();
			if (duration > 0 && startSec >= duration) {
				ended = true;
				state = "Ended";
				return;
			}
			state = "Buffering...";
			// Finding the video took a few seconds - start where everyone else is NOW (+ the time ffmpeg needs to connect),
			// otherwise the screen is always behind and keeps restarting to catch up (that was the big lag).
			double connect = 2.5;
			if (live) startSec = 0;
			else if (target != null) startSec = Math.max(0, target.getAsDouble() + connect);
			if (duration > 0 && startSec >= duration) {
				ended = true;
				state = "Ended";
				return;
			}
			t0Nanos = System.nanoTime() + (long) (connect * 1e9);
			if (!muted) {
				Thread audio = new Thread(() -> audioLoop(audioUrl), "Kyoto-VideoAudio");
				audio.setDaemon(true);
				audio.start();
			}
			videoLoop(videoUrl);
			if (!stopped) {
				ended = true;
				state = "Ended";
			}
		} catch (Exception e) {
			if (!stopped) {
				state = "Video error: " + e.getMessage();
				LOG.warn("video stream failed", e);
			}
		} finally {
			// the decoder is done with the buffers; free them (under the same lock the render thread uses)
			stopped = stopped || !loop;
			synchronized (allBuffers) {
				latest.set(null);
				free.clear();
				for (java.nio.ByteBuffer b : allBuffers) org.lwjgl.system.MemoryUtil.memFree(b);
				allBuffers.clear();
			}
		}
	}

	private List<String> ffmpegInput(String streamUrl) {
		return ffmpegInput(streamUrl, false);
	}

	private List<String> ffmpegInput(String streamUrl, boolean video) {
		List<String> c = new ArrayList<>(List.of(MediaTools.ffmpeg(), "-hide_banner", "-loglevel", "error", "-nostdin",
				"-probesize", "1000000", "-analyzeduration", "1000000"));
		// let the graphics card decode the video when it can (much less CPU); ffmpeg falls back to the CPU on its own
		if (video) c.addAll(List.of("-hwaccel", "auto", "-threads", "0"));
		else c.addAll(List.of("-threads", "1"));
		if (streamUrl.startsWith("http")) c.addAll(List.of("-reconnect", "1", "-reconnect_streamed", "1", "-reconnect_delay_max", "5"));
		if (startSec > 0.05) c.addAll(List.of("-ss", String.format(java.util.Locale.ROOT, "%.2f", startSec)));
		c.addAll(List.of("-i", streamUrl));
		return c;
	}

	private void videoLoop(String streamUrl) throws Exception {
		List<String> c = ffmpegInput(streamUrl, true);
		c.addAll(List.of("-an", "-sn", "-vf", "fps=" + FPS + ",scale=" + w + ":" + h + ":force_original_aspect_ratio=decrease:flags=bicubic,pad=" + w + ":" + h + ":(ow-iw)/2:(oh-ih)/2:black",
				"-pix_fmt", "rgba", "-f", "rawvideo", "-"));
		Process p = start(c);
		int size = w * h * 4;
		synchronized (allBuffers) {
			while (allBuffers.size() < 3 && !stopped) {
				java.nio.ByteBuffer b = org.lwjgl.system.MemoryUtil.memAlloc(size);
				allBuffers.add(b);
				free.offer(b);
			}
		}
		long lastShown = 0;
		try (java.nio.channels.ReadableByteChannel in = java.nio.channels.Channels.newChannel(p.getInputStream())) {
			long frame = 0;
			while (!stopped) {
				java.nio.ByteBuffer buf = free.poll();
				if (buf == null) {
					// the game hasn't drawn the last frame yet - take that one back and reuse it
					buf = latest.getAndSet(null);
					if (buf == null) {
						Thread.sleep(2);
						continue;
					}
				}
				buf.clear();
				while (buf.hasRemaining()) {
					if (in.read(buf) < 0) {
						free.offer(buf);
						return; // end of the video
					}
				}
				buf.flip();
				double pts = frame / (double) FPS;
				frame++;
				double now = clock();
				long wall = System.nanoTime();
				if (pts < now - 0.06 && wall - lastShown < 100_000_000L) {
					// running late: drop this frame, but still show at least ~10 a second so the picture never freezes
					free.offer(buf);
					continue;
				}
				if (pts > now) {
					// early: wait until the sound reaches this frame (re-checking, the audio clock moves in steps)
					while (!stopped) {
						double ahead = pts - clock();
						if (ahead <= 0.005) break;
						Thread.sleep(Math.max(1, Math.min(20, (long) (ahead * 1000))));
					}
				}
				java.nio.ByteBuffer old = latest.getAndSet(buf);
				if (old != null) free.offer(old);
				lastShown = System.nanoTime();
				state = "";
			}
		}
	}

	private void audioLoop(String streamUrl) {
		SourceDataLine line = null;
		try {
			List<String> c = ffmpegInput(streamUrl);
			c.addAll(List.of("-vn", "-ac", "2", "-ar", "48000", "-f", "s16le", "-"));
			Process p = start(c);
			AudioFormat fmt = new AudioFormat(48000, 16, 2, true, false);
			line = AudioSystem.getSourceDataLine(fmt);
			line.open(fmt, 48000 * 4 / 5); // ~200 ms buffer
			FloatControl gain = line.isControlSupported(FloatControl.Type.MASTER_GAIN) ? (FloatControl) line.getControl(FloatControl.Type.MASTER_GAIN) : null;
			FloatControl panC = line.isControlSupported(FloatControl.Type.PAN) ? (FloatControl) line.getControl(FloatControl.Type.PAN)
					: line.isControlSupported(FloatControl.Type.BALANCE) ? (FloatControl) line.getControl(FloatControl.Type.BALANCE) : null;
			// wait for the shared start time
			long wait = (t0Nanos - System.nanoTime()) / 1_000_000;
			if (wait > 0) Thread.sleep(wait);
			line.start();
			audioStartFrame = line.getLongFramePosition();
			audioLine = line;
			byte[] buf = new byte[48000 * 4 / 25]; // 40 ms
			InputStream in = p.getInputStream();
			int n;
			long written = 0;
			final double bytesPerSec = 48000 * 4;
			while (!stopped && (n = in.readNBytes(buf, 0, buf.length)) > 0) {
				while (held && !stopped) Thread.sleep(20);
				// if the sound arrived late (slow connection), skip ahead so it matches the picture
				double shouldHave = (System.nanoTime() - t0Nanos) / 1e9 * bytesPerSec;
				if (shouldHave - written > 0.3 * bytesPerSec) {
					written += n;
					audioSkipped += n / 4;
					continue;
				}
				written += n;
				float v = volume;
				if (gain != null) {
					float db = v <= 0.0001f ? gain.getMinimum() : (float) (20 * Math.log10(v));
					gain.setValue(Math.max(gain.getMinimum(), Math.min(gain.getMaximum(), db)));
				} else if (v <= 0.0001f) java.util.Arrays.fill(buf, 0, n, (byte) 0);
				if (panC != null) panC.setValue(Math.max(-1f, Math.min(1f, pan)));
				line.write(buf, 0, n);
			}
		} catch (Exception e) {
			if (!stopped) LOG.debug("video audio stopped", e);
		} finally {
			audioLine = null;
			if (line != null) {
				try {
					line.stop();
					line.close();
				} catch (Exception ignored) {
				}
			}
		}
	}
}
