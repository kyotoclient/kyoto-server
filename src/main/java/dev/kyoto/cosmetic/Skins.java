package dev.kyoto.cosmetic;

import dev.kyoto.config.KyotoConfig;
import dev.kyoto.module.Modules;
import dev.kyoto.util.Compat;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.resources.Identifier;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Skin customizer: drop skin PNGs (64x64 or old 64x32) in .minecraft/kyoto/skins and pick one.
 * You see it straight away; other Kyoto players see it after you press Share.
 * (It's client-side - non-Kyoto players still see your normal Minecraft skin.)
 */
public final class Skins {
	private Skins() {}

	public record Skin(Identifier tex, boolean slim) {}

	private static final Map<String, Skin> SKINS = new ConcurrentHashMap<>();
	private static volatile Skin local = null;
	private static int counter = 0;

	public static Path folder() {
		Path p = FabricLoader.getInstance().getGameDir().resolve("kyoto").resolve("skins");
		try {
			Files.createDirectories(p);
		} catch (Exception ignored) {
		}
		return p;
	}

	public static List<String> files() {
		List<String> out = new ArrayList<>();
		try (var s = Files.list(folder())) {
			s.map(x -> x.getFileName().toString()).filter(n -> n.toLowerCase(Locale.ROOT).endsWith(".png")).sorted().forEach(out::add);
		} catch (Exception ignored) {
		}
		return out;
	}

	public static Skin skinFor(UUID uuid) {
		if (uuid == null) return null;
		if (uuid.equals(Compat.myUuid())) return Modules.on("custom_skin") ? local : null;
		if (!Modules.on("others_cosmetics") || KyotoConfig.get().hiddenCosmeticsPlayers.contains(uuid.toString())) return null;
		return SKINS.get(uuid.toString());
	}

	public static Skin local() {
		return local;
	}

	public static void reloadLocal() {
		local = null;
		String f = KyotoConfig.get().skinFile;
		if (f == null || f.isBlank()) return;
		try {
			byte[] png = fix(Files.readAllBytes(folder().resolve(f)));
			if (png == null) return;
			Identifier id = Identifier.fromNamespaceAndPath("kyoto", "skins/local_" + (counter++));
			boolean slim = KyotoConfig.get().skinSlim;
			Compat.registerPng(id, png, () -> local = new Skin(id, slim));
		} catch (Exception ignored) {
		}
	}

	public static byte[] localBytes() {
		try {
			String f = KyotoConfig.get().skinFile;
			return f == null || f.isBlank() ? null : fix(Files.readAllBytes(folder().resolve(f)));
		} catch (Exception e) {
			return null;
		}
	}

	public static void loadRemote(String uuid, byte[] png, boolean slim) {
		byte[] fixed = fix(png);
		if (fixed == null) return;
		Identifier id = Identifier.fromNamespaceAndPath("kyoto", "skins/" + uuid.replaceAll("[^a-f0-9]", ""));
		Compat.registerPng(id, fixed, () -> SKINS.put(uuid, new Skin(id, slim)));
	}

	/** Makes sure it's a 64x64 skin (old 64x32 skins get their left arm/leg copied over). */
	static byte[] fix(byte[] data) {
		try {
			BufferedImage img = ImageIO.read(new ByteArrayInputStream(data));
			if (img == null || img.getWidth() != 64 || (img.getHeight() != 64 && img.getHeight() != 32)) return null;
			if (img.getHeight() == 32) {
				BufferedImage out = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
				Graphics2D g = out.createGraphics();
				g.drawImage(img, 0, 0, null);
				g.drawImage(img.getSubimage(0, 16, 16, 16), 16, 48, null); // leg
				g.drawImage(img.getSubimage(40, 16, 16, 16), 32, 48, null); // arm
				g.dispose();
				img = out;
			}
			ByteArrayOutputStream o = new ByteArrayOutputStream();
			ImageIO.write(img, "png", o);
			return o.toByteArray();
		} catch (Exception e) {
			return null;
		}
	}
}
