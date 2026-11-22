package dev.kyoto.profile;

import dev.kyoto.util.Compat;
import net.minecraft.resources.Identifier;
import org.jcodec.api.FrameGrab;
import org.jcodec.common.DemuxerTrackMeta;
import org.jcodec.common.io.NIOUtils;
import org.jcodec.common.model.Picture;
import org.jcodec.scale.AWTUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A looping, MUTED background video for profiles.
 * MP4 (H.264) is decoded with JCodec (pure Java); GIFs work too. Quality (320p-960p) and frame rate
 * come from Settings > Social > Profile Videos; frames are drawn with smooth filtering.
 */
public final class Video {
	private static final Logger LOG = LoggerFactory.getLogger("kyoto-video");
	private static final AtomicInteger IDS = new AtomicInteger();
	/** Width from the Profile Videos > Quality setting; frame count is limited by a memory budget. */
	private static int maxW() {
		return new int[]{480, 720, 960, 960}[dev.kyoto.module.Modules.opt("video.quality")];
	}

	private static double targetFps() {
		return new double[]{30, 30, 24}[dev.kyoto.module.Modules.opt("video.fps")];
	}

	/** Streaming size for the smooth (ffmpeg) path. */
	private static int streamH() {
		return new int[]{480, 720, 1080, 1440}[dev.kyoto.module.Modules.opt("video.quality")];
	}

	private static int streamFps() {
		return new int[]{30, 60, 24}[dev.kyoto.module.Modules.opt("video.fps")];
	}

	/**
	 * Smooth path: when the video tools (ffmpeg) are installed, the video is streamed at full frame rate and
	 * resolution instead of being pre-decoded into a short, memory-limited loop. MP4, WEBM, MOV, MKV all work.
	 */
	private dev.kyoto.screens.VideoStream stream;

	public void close() {
		if (stream != null) stream.stop();
		stream = null;
	}

	private static final long MEMORY_BUDGET = 192L * 1024 * 1024; // ~192 MB of frames max
	private int maxFrames = 180;

	public final List<Identifier> frames = new ArrayList<>();
	public volatile int frameMs = 66;
	public volatile float progress = 0;
	public volatile boolean done = false;
	public volatile String error = null;
	public volatile int width = 16, height = 9;
	private final long started = System.currentTimeMillis();

	public Identifier current() {
		if (stream != null) {
			Identifier t = stream.texture();
			if (t != null) done = true;
			if (stream.state.startsWith("Video error")) error = stream.state;
			return t;
		}
		if (frames.isEmpty()) return null;
		int i = (int) (((System.currentTimeMillis() - started) / Math.max(10, frameMs)) % frames.size());
		return frames.get(i);
	}

	/** Load a local file (mp4 or gif) on a background thread. */
	public static Video load(Path file) {
		Video v = new Video();
		String n = file.getFileName().toString().toLowerCase(Locale.ROOT);
		if (!n.endsWith(".gif") && dev.kyoto.screens.MediaTools.ready()) {
			int h = streamH(), w = h * 16 / 9;
			v.width = w;
			v.height = h;
			v.stream = dev.kyoto.screens.VideoStream.file(file, w, h, streamFps());
			return v;
		}
		Thread t = new Thread(() -> v.decode(file), "Kyoto-Video");
		t.setDaemon(true);
		t.start();
		return v;
	}

	/** Load downloaded bytes (written to a temp file first, JCodec needs a file). */
	public static Video load(byte[] data, String ext) {
		try {
			Path tmp = Files.createTempFile("kyoto-video", "." + ext);
			Files.write(tmp, data);
			tmp.toFile().deleteOnExit();
			return load(tmp);
		} catch (Exception e) {
			Video v = new Video();
			v.error = e.getMessage();
			v.done = true;
			return v;
		}
	}

	private void decode(Path file) {
		try {
			String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
			if (name.endsWith(".gif")) decodeGif(file);
			else decodeMp4(file.toFile());
		} catch (Throwable e) {
			error = "Can't play this video (" + e.getClass().getSimpleName() + "). Use an H.264 .mp4 or a .gif";
			LOG.warn("video decode failed", e);
		}
		done = true;
	}

	private void decodeMp4(File file) throws Exception {
		try (var ch = NIOUtils.readableChannel(file)) {
			FrameGrab grab = FrameGrab.createFrameGrab(ch);
			double srcFps = 30;
			int total = 0;
			try {
				DemuxerTrackMeta meta = grab.getVideoTrack().getMeta();
				if (meta.getTotalDuration() > 0 && meta.getTotalFrames() > 0) {
					srcFps = meta.getTotalFrames() / meta.getTotalDuration();
					total = meta.getTotalFrames();
				}
			} catch (Throwable ignored) {
			}
			int skip = Math.max(1, (int) Math.round(srcFps / targetFps()));
			frameMs = (int) Math.round(1000.0 * skip / srcFps);
			Picture pic;
			int index = 0;
			while ((pic = grab.getNativeFrame()) != null && frames.size() < maxFrames) {
				if (index++ % skip != 0) continue;
				add(AWTUtil.toBufferedImage(pic));
				progress = total > 0 ? Math.min(1f, index / (float) Math.min(total, maxFrames * skip)) : frames.size() / (float) maxFrames;
			}
		}
	}

	private void decodeGif(Path file) throws Exception {
		List<BufferedImage> imgs = new ArrayList<>();
		List<Integer> delays = new ArrayList<>();
		dev.kyoto.cosmetic.Capes.readGifFrames(Files.readAllBytes(file), imgs, delays);
		if (!delays.isEmpty()) frameMs = Math.max(20, delays.get(0));
		for (int i = 0; i < imgs.size() && frames.size() < maxFrames; i++) {
			add(imgs.get(i));
			progress = (i + 1) / (float) imgs.size();
		}
	}

	private void add(BufferedImage src) throws Exception {
		int w = Math.min(maxW(), src.getWidth());
		int h = Math.max(1, Math.round(src.getHeight() * (w / (float) src.getWidth())));
		width = w;
		height = h;
		if (frames.isEmpty()) maxFrames = (int) Math.max(48, Math.min(360, MEMORY_BUDGET / ((long) w * h * 4)));
		BufferedImage small = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = small.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
		g.drawImage(src, 0, 0, w, h, null);
		g.dispose();
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ImageIO.write(small, "png", out);
		Identifier id = Identifier.fromNamespaceAndPath("kyoto", "profile_video/" + IDS.incrementAndGet());
		Compat.registerPng(id, out.toByteArray(), null, true);
		synchronized (frames) {
			frames.add(id);
		}
	}
}
