package dev.kyoto.cosmetic;

import dev.kyoto.config.KyotoConfig;
import dev.kyoto.module.Modules;
import dev.kyoto.net.KyotoNet;
import dev.kyoto.util.Compat;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Node;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageInputStream;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Capes: static PNG, animated GIF, or animated PNG strip (frames stacked vertically).
 * Put files in .minecraft/kyoto/capes/ and pick one in the Cosmetics menu.
 * Each frame becomes its own texture; we just switch which one the player uses.
 */
public final class Capes {
	private Capes() {}

	private static final Logger LOG = LoggerFactory.getLogger("kyoto-capes");

	public record Anim(List<Identifier> frames, int[] delaysMs, int totalMs) {
		Identifier current() {
			if (frames.size() == 1) return frames.get(0);
			long t = System.currentTimeMillis() % Math.max(1, totalMs);
			int acc = 0;
			for (int i = 0; i < frames.size(); i++) {
				acc += delaysMs[i];
				if (t < acc) return frames.get(i);
			}
			return frames.get(frames.size() - 1);
		}
	}

	private static final Map<String, Anim> CAPES = new ConcurrentHashMap<>();
	private static volatile String localKey = null;

	public static Path folder() {
		Path p = FabricLoader.getInstance().getGameDir().resolve("kyoto").resolve("capes");
		try {
			Files.createDirectories(p);
		} catch (Exception ignored) {
		}
		return p;
	}

	public static List<String> files() {
		List<String> out = new ArrayList<>();
		try (var s = Files.list(folder())) {
			s.map(x -> x.getFileName().toString())
					.filter(n -> n.toLowerCase(Locale.ROOT).endsWith(".png") || n.toLowerCase(Locale.ROOT).endsWith(".gif"))
					.sorted().forEach(out::add);
		} catch (Exception ignored) {
		}
		return out;
	}

	/** The cape texture a player should use right now, or null. */
	public static Identifier capeFor(UUID uuid) {
		if (uuid == null) return null;
		String id = uuid.toString();
		if (uuid.equals(Compat.myUuid())) {
			if (!Modules.on("cape")) return null;
			// a cape equipped from the website / launcher Store / Builder (newer than what you picked in game) wins
			KyotoNet.Cosmetic remote = KyotoNet.COSMETICS.get(id);
			if (remote != null && remote.hasCape() && remote.version() > KyotoConfig.get().capeChosenAt) {
				Anim r = CAPES.get(id);
				if (r != null) return r.current();
			}
			if (localKey == null) return null;
			Anim a = CAPES.get(localKey);
			return a == null ? null : a.current();
		}
		if (!Modules.on("others_cosmetics") || KyotoConfig.get().hiddenCosmeticsPlayers.contains(id)) return null;
		Anim a = CAPES.get(id);
		return a == null ? null : a.current();
	}

	/** Load the cape chosen in config for yourself. */
	public static void reloadLocal() {
		String file = KyotoConfig.get().capeFile;
		localKey = null;
		if (file == null || file.isBlank()) return;
		try {
			byte[] data = Files.readAllBytes(folder().resolve(file));
			String key = "local_" + file.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "_");
			load(key, data);
			localKey = key;
		} catch (Exception e) {
			LOG.warn("Could not load cape " + file, e);
		}
	}

	public static byte[] localBytes() {
		try {
			String file = KyotoConfig.get().capeFile;
			if (file == null || file.isBlank()) return null;
			return Files.readAllBytes(folder().resolve(file));
		} catch (Exception e) {
			return null;
		}
	}

	public static void loadRemote(String uuid, byte[] data) {
		if (data == null || data.length < 8) return;
		load(uuid, data);
	}

	private static void load(String key, byte[] data) {
		try {
			List<BufferedImage> frames = new ArrayList<>();
			List<Integer> delays = new ArrayList<>();
			boolean gif = data.length > 3 && data[0] == 'G' && data[1] == 'I' && data[2] == 'F';
			if (gif) readGif(data, frames, delays);
			else {
				BufferedImage img = ImageIO.read(new ByteArrayInputStream(data));
				if (img == null) return;
				// tall PNG = vertical strip of 2:1 frames
				int frameH = img.getWidth() / 2;
				if (frameH > 0 && img.getHeight() >= frameH * 2 && img.getHeight() % frameH == 0) {
					for (int y = 0; y < img.getHeight(); y += frameH) {
						frames.add(img.getSubimage(0, y, img.getWidth(), frameH));
						delays.add(100);
					}
				} else {
					frames.add(img);
					delays.add(1000);
				}
			}
			if (frames.isEmpty()) return;
			if (frames.size() > 120) frames = frames.subList(0, 120);
			List<Identifier> ids = new ArrayList<>();
			int[] d = new int[frames.size()];
			int total = 0;
			String safe = key.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_./-]", "_");
			for (int i = 0; i < frames.size(); i++) {
				Identifier id = Identifier.fromNamespaceAndPath("kyoto", "capes/" + safe + "/" + i + "_" + System.nanoTime() % 100000);
				Compat.registerPng(id, toPng(normalize(frames.get(i))), null);
				ids.add(id);
				d[i] = Math.max(20, delays.get(Math.min(i, delays.size() - 1)));
				total += d[i];
			}
			CAPES.put(key, new Anim(ids, d, total));
		} catch (Exception e) {
			LOG.warn("Cape decode failed for " + key, e);
		}
	}

	/** Makes sure the image is 2:1 (cape layout) with power-of-two-ish width. */
	private static BufferedImage normalize(BufferedImage in) {
		int w = in.getWidth(), h = in.getHeight();
		if (w == h * 2) return in;
		BufferedImage out = new BufferedImage(w, w / 2, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = out.createGraphics();
		g.drawImage(in, 0, 0, null);
		g.dispose();
		return out;
	}

	private static byte[] toPng(BufferedImage img) throws Exception {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ImageIO.write(img, "png", out);
		return out.toByteArray();
	}

	/** Shared GIF decoder (also used for profile backgrounds). */
	public static void readGifFrames(byte[] data, List<BufferedImage> frames, List<Integer> delays) throws Exception {
		readGif(data, frames, delays);
	}

	private static void readGif(byte[] data, List<BufferedImage> frames, List<Integer> delays) throws Exception {
		Iterator<ImageReader> readers = ImageIO.getImageReadersByFormatName("gif");
		if (!readers.hasNext()) return;
		ImageReader reader = readers.next();
		try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(data))) {
			reader.setInput(in);
			int n = reader.getNumImages(true);
			BufferedImage canvas = null;
			for (int i = 0; i < n && i < 120; i++) {
				BufferedImage frame = reader.read(i);
				IIOMetadata meta = reader.getImageMetadata(i);
				int x = 0, y = 0, delay = 100;
				Node root = meta.getAsTree("javax_imageio_gif_image_1.0");
				for (Node c = root.getFirstChild(); c != null; c = c.getNextSibling()) {
					if (c.getNodeName().equals("ImageDescriptor")) {
						x = Integer.parseInt(((IIOMetadataNode) c).getAttribute("imageLeftPosition"));
						y = Integer.parseInt(((IIOMetadataNode) c).getAttribute("imageTopPosition"));
					} else if (c.getNodeName().equals("GraphicControlExtension")) {
						delay = Integer.parseInt(((IIOMetadataNode) c).getAttribute("delayTime")) * 10;
					}
				}
				if (canvas == null) {
					int w = reader.getWidth(0), h = reader.getHeight(0);
					canvas = new BufferedImage(Math.max(w, frame.getWidth() + x), Math.max(h, frame.getHeight() + y), BufferedImage.TYPE_INT_ARGB);
				}
				Graphics2D g = canvas.createGraphics();
				g.drawImage(frame, x, y, null);
				g.dispose();
				BufferedImage copy = new BufferedImage(canvas.getWidth(), canvas.getHeight(), BufferedImage.TYPE_INT_ARGB);
				Graphics2D cg = copy.createGraphics();
				cg.drawImage(canvas, 0, 0, null);
				cg.dispose();
				frames.add(copy);
				delays.add(delay <= 0 ? 100 : delay);
			}
		} finally {
			reader.dispose();
		}
	}
}
