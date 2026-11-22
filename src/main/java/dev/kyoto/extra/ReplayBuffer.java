package dev.kyoto.extra;

import dev.kyoto.hud.Notifications;
import dev.kyoto.module.Modules;
import dev.kyoto.util.Compat;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Replay buffer: keeps the last 15-60 seconds of your screen in memory (small JPEG frames) and saves it as a video
 * when you press the "Save clip" key. Files go to .minecraft/kyoto/clips as .avi (plays in VLC, Windows Media Player,
 * and drops straight into CapCut / Premiere). No sound - record sound with OBS if you need it.
 * It's off by default because copying the screen every frame costs a little FPS.
 */
public final class ReplayBuffer {
	private ReplayBuffer() {}

	private record Frame(byte[] jpg, long at) {}

	private static final ArrayDeque<Frame> FRAMES = new ArrayDeque<>();
	private static final ExecutorService ENC = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "Kyoto clip encoder");
		t.setDaemon(true);
		t.setPriority(Thread.MIN_PRIORITY);
		return t;
	});
	private static long lastGrab = 0;
	private static volatile int pending = 0;
	private static volatile int outW = 0, outH = 0;
	private static Method shot;
	private static int shotKind = -1; // 0 = (target, consumer), 1 = (target, int, consumer)
	private static boolean broken = false;
	public static volatile boolean saving = false;

	public static int seconds() {
		return new int[]{15, 30, 60}[Math.max(0, Math.min(2, Modules.opt("clip.length")))];
	}

	private static int fps() {
		return new int[]{10, 15, 24}[Math.max(0, Math.min(2, Modules.opt("clip.fps")))];
	}

	private static int width() {
		return new int[]{640, 854, 1280}[Math.max(0, Math.min(2, Modules.opt("clip.quality")))];
	}

	/** Called every frame from the HUD. */
	public static void frame(Minecraft mc) {
		if (!Modules.on("replay_buffer") || broken || mc.player == null) {
			if (!FRAMES.isEmpty() && !Modules.on("replay_buffer")) synchronized (FRAMES) { FRAMES.clear(); }
			return;
		}
		long now = System.currentTimeMillis();
		if (now - lastGrab < 1000 / fps() || pending > 3) return;
		lastGrab = now;
		try {
			grab(mc, img -> handle(img, now));
		} catch (Throwable t) {
			broken = true;
			Notifications.push("Replay buffer can't run", "This version of Minecraft blocked screen capture", Notifications.WARN, 5000, null, "screen");
		}
	}

	private static void grab(Minecraft mc, Consumer<Object> done) throws Exception {
		Class<?> sc = Class.forName("net.minecraft.client.Screenshot");
		Object target = mc.getClass().getMethod("getMainRenderTarget").invoke(mc);
		if (shot == null) {
			for (Method m : sc.getMethods()) {
				if (!m.getName().equals("takeScreenshot") || !java.lang.reflect.Modifier.isStatic(m.getModifiers())) continue;
				Class<?>[] p = m.getParameterTypes();
				if (p.length == 2 && p[1] == Consumer.class) { shot = m; shotKind = 0; }
				if (p.length == 3 && p[1] == int.class && p[2] == Consumer.class) { shot = m; shotKind = 1; break; }
			}
			if (shot == null) throw new IllegalStateException("no takeScreenshot");
		}
		Consumer<Object> c = done;
		pending++;
		if (shotKind == 1) shot.invoke(null, target, 1, c);
		else shot.invoke(null, target, c);
	}

	/** Runs when the GPU hands the picture back: sample it down to the clip size, then JPEG it on a worker thread. */
	private static void handle(Object o, long at) {
		com.mojang.blaze3d.platform.NativeImage img = (com.mojang.blaze3d.platform.NativeImage) o;
		try {
			int w = img.getWidth(), h = img.getHeight();
			int ow = Math.min(width(), w) & ~1, oh = Math.max(2, Math.round(ow * h / (float) w)) & ~1;
			int[] px = new int[ow * oh];
			for (int y = 0; y < oh; y++) {
				int sy = y * h / oh;
				for (int x = 0; x < ow; x++) px[y * ow + x] = img.getPixel(x * w / ow, sy);
			}
			outW = ow;
			outH = oh;
			final int fw = ow, fh = oh;
			ENC.execute(() -> {
				try {
					byte[] jpg = jpeg(px, fw, fh);
					synchronized (FRAMES) {
						FRAMES.addLast(new Frame(jpg, at));
						long keep = at - seconds() * 1000L;
						while (!FRAMES.isEmpty() && FRAMES.peekFirst().at < keep) FRAMES.pollFirst();
					}
				} catch (Throwable ignored) {
				} finally {
					pending--;
				}
			});
		} catch (Throwable t) {
			pending--;
			broken = true;
		} finally {
			try {
				img.close();
			} catch (Throwable ignored) {
			}
		}
	}

	private static byte[] jpeg(int[] px, int w, int h) throws IOException {
		BufferedImage bi = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
		bi.setRGB(0, 0, w, h, px, 0, w);
		ByteArrayOutputStream out = new ByteArrayOutputStream(w * h / 6);
		ImageWriter wr = ImageIO.getImageWritersByFormatName("jpeg").next();
		ImageWriteParam p = wr.getDefaultWriteParam();
		p.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
		p.setCompressionQuality(0.78f);
		try (MemoryCacheImageOutputStream ios = new MemoryCacheImageOutputStream(out)) {
			wr.setOutput(ios);
			wr.write(null, new IIOImage(bi, null, null), p);
		} finally {
			wr.dispose();
		}
		return out.toByteArray();
	}

	/** "Save clip" key. */
	public static void save() {
		if (!Modules.on("replay_buffer")) {
			Notifications.push("Replay buffer is off", "Turn on Replay Buffer in the Kyoto menu first", Notifications.INFO, 3500, null, "screen");
			return;
		}
		List<Frame> copy;
		synchronized (FRAMES) {
			copy = new ArrayList<>(FRAMES);
		}
		if (copy.size() < 5 || saving) return;
		saving = true;
		int w = outW, h = outH;
		Notifications.push("Saving clip...", "Last " + Math.round((copy.get(copy.size() - 1).at - copy.get(0).at) / 1000f) + " seconds", Notifications.INFO, 2000, null, "screen");
		Thread t = new Thread(() -> {
			try {
				Path dir = FabricLoader.getInstance().getGameDir().resolve("kyoto").resolve("clips");
				Files.createDirectories(dir);
				Path f = dir.resolve("clip_" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")) + ".avi");
				double secs = Math.max(0.5, (copy.get(copy.size() - 1).at - copy.get(0).at) / 1000.0);
				int realFps = (int) Math.max(1, Math.round(copy.size() / secs));
				writeAvi(f, copy, w, h, realFps);
				Compat.runOnClient(() -> Notifications.push("Clip saved", f.getFileName() + " · kyoto/clips", Notifications.GOOD, 5000, null, "check"));
			} catch (Exception e) {
				Compat.runOnClient(() -> Notifications.push("Couldn't save the clip", String.valueOf(e.getMessage()), Notifications.WARN, 5000, null, "screen"));
			} finally {
				saving = false;
			}
		}, "Kyoto clip save");
		t.setDaemon(true);
		t.start();
	}

	public static Path folder() {
		return FabricLoader.getInstance().getGameDir().resolve("kyoto").resolve("clips");
	}

	/* ---------------- tiny MJPEG .avi writer ---------------- */

	private static void writeAvi(Path file, List<Frame> frames, int w, int h, int fps) throws IOException {
		int n = frames.size();
		long movi = 4;
		int maxFrame = 0;
		for (Frame f : frames) {
			movi += 8 + f.jpg.length + (f.jpg.length & 1);
			maxFrame = Math.max(maxFrame, f.jpg.length);
		}
		long hdrl = 4 + (8 + 56) + (8 + 4 + (8 + 56) + (8 + 40));
		long idx = 16L * n;
		long riff = 4 + (8 + hdrl) + (8 + movi) + (8 + idx);
		try (OutputStream os = new java.io.BufferedOutputStream(new FileOutputStream(file.toFile()), 1 << 16)) {
			DataOutputStream d = new DataOutputStream(os);
			fourcc(d, "RIFF"); le(d, riff); fourcc(d, "AVI ");
			fourcc(d, "LIST"); le(d, hdrl); fourcc(d, "hdrl");
			fourcc(d, "avih"); le(d, 56);
			le(d, 1000000 / fps); le(d, (long) maxFrame * fps); le(d, 0); le(d, 0x10); le(d, n); le(d, 0); le(d, 1); le(d, maxFrame); le(d, w); le(d, h);
			le(d, 0); le(d, 0); le(d, 0); le(d, 0);
			fourcc(d, "LIST"); le(d, 4 + (8 + 56) + (8 + 40)); fourcc(d, "strl");
			fourcc(d, "strh"); le(d, 56);
			fourcc(d, "vids"); fourcc(d, "MJPG"); le(d, 0); le16(d, 0); le16(d, 0); le(d, 0); le(d, 1); le(d, fps); le(d, 0); le(d, n); le(d, maxFrame); le(d, -1); le(d, 0);
			le16(d, 0); le16(d, 0); le16(d, w); le16(d, h);
			fourcc(d, "strf"); le(d, 40);
			le(d, 40); le(d, w); le(d, h); le16(d, 1); le16(d, 24); fourcc(d, "MJPG"); le(d, (long) w * h * 3); le(d, 0); le(d, 0); le(d, 0); le(d, 0);
			fourcc(d, "LIST"); le(d, movi); fourcc(d, "movi");
			for (Frame f : frames) {
				fourcc(d, "00dc"); le(d, f.jpg.length); d.write(f.jpg);
				if ((f.jpg.length & 1) == 1) d.write(0);
			}
			fourcc(d, "idx1"); le(d, idx);
			long off = 4;
			for (Frame f : frames) {
				fourcc(d, "00dc"); le(d, 0x10); le(d, off); le(d, f.jpg.length);
				off += 8 + f.jpg.length + (f.jpg.length & 1);
			}
			d.flush();
		}
	}

	private static void fourcc(DataOutputStream d, String s) throws IOException {
		d.writeBytes(s);
	}

	private static void le(DataOutputStream d, long v) throws IOException {
		d.write((int) (v & 0xFF)); d.write((int) ((v >> 8) & 0xFF)); d.write((int) ((v >> 16) & 0xFF)); d.write((int) ((v >> 24) & 0xFF));
	}

	private static void le16(DataOutputStream d, int v) throws IOException {
		d.write(v & 0xFF); d.write((v >> 8) & 0xFF);
	}
}
