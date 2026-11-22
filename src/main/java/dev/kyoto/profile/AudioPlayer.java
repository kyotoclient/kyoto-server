package dev.kyoto.profile;

import javazoom.jl.decoder.Bitstream;
import javazoom.jl.decoder.Decoder;
import javazoom.jl.decoder.Header;
import javazoom.jl.decoder.SampleBuffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.FloatControl;
import javax.sound.sampled.SourceDataLine;
import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.Locale;

/** Plays profile music (MP3 or WAV) on a loop, with volume. One song at a time. */
public final class AudioPlayer {
	private AudioPlayer() {}

	private static final Logger LOG = LoggerFactory.getLogger("kyoto-audio");
	private static volatile Thread thread;
	private static volatile boolean stop;
	private static volatile float volume = 0.5f;
	private static volatile SourceDataLine line;
	public static volatile String nowPlaying = null;
	public static volatile boolean paused = false;

	public static void play(byte[] data, String name) {
		stop();
		if (data == null || data.length == 0) return;
		stop = false;
		paused = false;
		nowPlaying = name;
		Thread t = new Thread(() -> {
			while (!stop) {
				try {
					if (name.toLowerCase(Locale.ROOT).endsWith(".wav")) playWav(data);
					else playMp3(data);
				} catch (Exception e) {
					LOG.warn("audio failed", e);
					break;
				}
			}
		}, "Kyoto-Audio");
		t.setDaemon(true);
		thread = t;
		t.start();
	}

	public static void stop() {
		stop = true;
		nowPlaying = null;
		SourceDataLine l = line;
		if (l != null) {
			try {
				l.stop();
				l.close();
			} catch (Exception ignored) {
			}
		}
		line = null;
	}

	public static void setVolume(float v) {
		volume = Math.max(0, Math.min(1, v));
		applyVolume();
	}

	public static float volume() {
		return volume;
	}

	private static void applyVolume() {
		SourceDataLine l = line;
		if (l == null || !l.isControlSupported(FloatControl.Type.MASTER_GAIN)) return;
		FloatControl c = (FloatControl) l.getControl(FloatControl.Type.MASTER_GAIN);
		float db = volume <= 0.001f ? c.getMinimum() : (float) (20 * Math.log10(volume));
		c.setValue(Math.max(c.getMinimum(), Math.min(c.getMaximum(), db)));
	}

	private static void open(AudioFormat fmt) throws Exception {
		SourceDataLine l = (SourceDataLine) AudioSystem.getLine(new DataLine.Info(SourceDataLine.class, fmt));
		l.open(fmt);
		l.start();
		line = l;
		applyVolume();
	}

	private static void waitIfPaused() throws InterruptedException {
		while (paused && !stop) Thread.sleep(50);
	}

	private static void playMp3(byte[] data) throws Exception {
		InputStream in = new BufferedInputStream(new ByteArrayInputStream(data));
		Bitstream bs = new Bitstream(in);
		Decoder dec = new Decoder();
		boolean opened = false;
		Header h;
		while (!stop && (h = bs.readFrame()) != null) {
			waitIfPaused();
			SampleBuffer out = (SampleBuffer) dec.decodeFrame(h, bs);
			if (!opened) {
				open(new AudioFormat(out.getSampleFrequency(), 16, out.getChannelCount(), true, false));
				opened = true;
			}
			short[] s = out.getBuffer();
			int n = out.getBufferLength();
			byte[] b = new byte[n * 2];
			for (int i = 0; i < n; i++) {
				b[i * 2] = (byte) s[i];
				b[i * 2 + 1] = (byte) (s[i] >> 8);
			}
			SourceDataLine l = line;
			if (l != null) l.write(b, 0, b.length);
			bs.closeFrame();
		}
		bs.close();
		SourceDataLine l = line;
		if (l != null && !stop) l.drain();
	}

	private static void playWav(byte[] data) throws Exception {
		try (AudioInputStream ais = AudioSystem.getAudioInputStream(new BufferedInputStream(new ByteArrayInputStream(data)))) {
			open(ais.getFormat());
			byte[] buf = new byte[8192];
			int n;
			while (!stop && (n = ais.read(buf)) > 0) {
				waitIfPaused();
				SourceDataLine l = line;
				if (l != null) l.write(buf, 0, n);
			}
		}
	}
}
