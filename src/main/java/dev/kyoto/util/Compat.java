package dev.kyoto.util;

import com.mojang.authlib.GameProfile;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.PlayerFaceExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Player;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Every Minecraft call that changed between 26.1, 26.2 and 26.3 lives here.
 * If the build fails, the error will almost always point at this file.
 */
public final class Compat {
	private Compat() {}

	private static Minecraft mc() {
		return Minecraft.getInstance();
	}

	/* ---------------- Screens ---------------- */

	/** How far through its arm swing an entity is (0..1). Found by name, so a renamed method doesn't break the build. */
	public static float swing(net.minecraft.world.entity.LivingEntity e, float partial) {
		for (String n : new String[]{"getAttackAnim", "getSwingProgress", "getAttackAnimation"}) {
			try {
				var m = net.minecraft.world.entity.LivingEntity.class.getMethod(n, float.class);
				return (float) m.invoke(e, partial);
			} catch (Throwable ignored) {
			}
		}
		try {
			float a = fieldF(e, "attackAnim"), o = fieldF(e, "oAttackAnim");
			float d = a - o;
			if (d < 0) d++;
			return o + d * partial;
		} catch (Throwable ignored) {
		}
		return 0;
	}

	public static boolean swinging(net.minecraft.world.entity.LivingEntity e) {
		try {
			var f = net.minecraft.world.entity.LivingEntity.class.getDeclaredField("swinging");
			f.setAccessible(true);
			return f.getBoolean(e);
		} catch (Throwable t) {
			return swing(e, 1f) > 0.01f;
		}
	}

	private static float fieldF(Object o, String name) throws Exception {
		var f = net.minecraft.world.entity.LivingEntity.class.getDeclaredField(name);
		f.setAccessible(true);
		return f.getFloat(o);
	}

	public static Screen currentScreen() {
		return mc().gui.screen();
	}

	/** Runs something on the game thread (safe place to touch screens/textures). */
	public static void runOnClient(Runnable r) {
		mc().execute(r);
	}

	public static void openScreen(Screen screen) {
		mc().gui.setScreen(screen);
	}

	/* ---------------- Chat ---------------- */

	private static boolean addingOwn = false;
	private static final Set<Component> OWN = Collections.newSetFromMap(new WeakHashMap<>());

	/** True while Kyoto itself is adding a chat line - chat filters must skip those (fixes the crash). */
	public static boolean isOwn(Component message) {
		return addingOwn || OWN.contains(message);
	}

	public static void addChat(Component message) {
		if (mc().player == null) return;
		if (dev.kyoto.extra.ChatTabs.skipNext) dev.kyoto.extra.ChatTabs.skipNext = false;
		else if (!dev.kyoto.extra.ChatTabs.record(message, true)) return;
		OWN.add(message);
		addingOwn = true;
		try {
			mc().player.sendSystemMessage(message);
		} finally {
			addingOwn = false;
		}
	}

	public static void sendPublicChat(String text) {
		if (mc().player != null) mc().player.connection.sendChat(text);
	}

	/** Runs a server command. Pass it WITHOUT the leading slash. */
	public static void sendCommand(String command) {
		if (mc().player != null) mc().player.connection.sendCommand(command);
	}

	public static String profileName(GameProfile profile) {
		return profile == null ? null : profile.name();
	}

	/* ---------------- Fonts ---------------- */

	public static MutableComponent withFont(MutableComponent c, Identifier font) {
		return c.withStyle(s -> s.withFont(new FontDescription.Resource(font)));
	}

	/* ---------------- Textures ---------------- */

	/** Draws a whole texture into a rectangle. */
	public static void blit(GuiGraphicsExtractor g, Identifier tex, int x, int y, int w, int h) {
		g.blit(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED, tex, x, y, 0, 0, w, h, w, h);
	}

	/** Draws a whole texture tinted with an ARGB colour (alpha = transparency). */
	public static void blitTint(GuiGraphicsExtractor g, Identifier tex, int x, int y, int w, int h, int argb) {
		g.blit(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED, tex, x, y, 0, 0, w, h, w, h, argb);
	}

	/** Draws part of a texture: (u,v,uw,vh) of a texW x texH image into x,y,w,h. */
	public static void blitRegion(GuiGraphicsExtractor g, Identifier tex, int x, int y, int w, int h, float u, float v, int uw, int vh, int texW, int texH, int argb) {
		g.blit(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED, tex, x, y, u, v, w, h, uw, vh, texW, texH, argb);
	}

	/** Registers PNG bytes as a texture. Must run on the render thread (we schedule it). */
	public static void registerPng(Identifier id, byte[] png, Runnable done) {
		registerPng(id, png, done, false);
	}

	/** smooth = linear filtering (no pixelation when drawn bigger/smaller). */
	public static void registerPng(Identifier id, byte[] png, Runnable done, boolean smooth) {
		mc().execute(() -> {
			try {
				com.mojang.blaze3d.platform.NativeImage img = com.mojang.blaze3d.platform.NativeImage.read(new java.io.ByteArrayInputStream(png));
				net.minecraft.client.renderer.texture.DynamicTexture tex = new net.minecraft.client.renderer.texture.DynamicTexture(id::toString, img);
				if (smooth) smoothFilter(tex);
				mc().getTextureManager().register(id, tex);
				if (done != null) done.run();
			} catch (Exception e) {
				org.slf4j.LoggerFactory.getLogger("kyoto").warn("Texture load failed for " + id, e);
			}
		});
	}

	/** Turns on linear filtering if this Minecraft version lets us (looked up by name, never crashes). */
	private static void smoothFilter(Object tex) {
		for (Class<?> c = tex.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
			for (java.lang.reflect.Method m : c.getDeclaredMethods()) {
				if (!m.getName().equals("setFilter")) continue;
				try {
					m.setAccessible(true);
					Class<?>[] p = m.getParameterTypes();
					if (p.length == 2 && p[0] == boolean.class && p[1] == boolean.class) m.invoke(tex, true, false);
					else if (p.length == 1 && p[0] == boolean.class) m.invoke(tex, true);
					return;
				} catch (Throwable ignored) {
				}
			}
		}
	}

	/** Cape asset for PlayerSkin (texture already registered under this id). */
	public static net.minecraft.core.ClientAsset.Texture capeAsset(Identifier id) {
		return new net.minecraft.core.ClientAsset.ResourceTexture(id, id);
	}

	/* ---------------- Links / clipboard ---------------- */

	public static void copy(String text) {
		mc().keyboardHandler.setClipboard(text);
	}

	/**
	 * Opens a website or a folder. Minecraft runs "headless" on some setups, which made Java's Desktop
	 * API silently fail - so we ask the operating system directly. Also copies links to the clipboard.
	 */
	public static void openUrl(String url) {
		if (url == null || url.isBlank()) return;
		url = url.trim();
		if (url.matches("(?i)^(www\\.)?[a-z0-9-]+(\\.[a-z0-9-]+)*\\.[a-z]{2,}(/.*)?$")) url = "https://" + url;
		boolean web = url.startsWith("http://") || url.startsWith("https://");
		if (web) copy(url);
		String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
		try {
			ProcessBuilder pb;
			if (os.contains("win")) {
				pb = web ? new ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", url)
						: new ProcessBuilder("explorer.exe", url.replace('/', '\\'));
			} else if (os.contains("mac")) {
				pb = new ProcessBuilder("open", url);
			} else {
				pb = new ProcessBuilder("xdg-open", url);
			}
			pb.redirectErrorStream(true).start();
			return;
		} catch (Throwable ignored) {
		}
		try {
			Class<?> util;
			try {
				util = Class.forName("net.minecraft.util.Util");
			} catch (ClassNotFoundException e) {
				util = Class.forName("net.minecraft.Util");
			}
			Object platform = util.getMethod("getPlatform").invoke(null);
			platform.getClass().getMethod("openUri", java.net.URI.class).invoke(platform, java.net.URI.create(url));
		} catch (Throwable ignored) {
		}
	}

	/** Opens a folder in Explorer / Finder (creates it first). */
	public static void openFolder(java.nio.file.Path dir) {
		try {
			java.nio.file.Files.createDirectories(dir);
		} catch (Exception ignored) {
		}
		openUrl(dir.toAbsolutePath().toString());
	}

	/* ---------------- Mojang session (for Kyoto server login) ---------------- */

	public static void joinSession(String serverId) throws Exception {
		var user = mc().getUser();
		mc().services().sessionService().joinServer(user.getProfileId(), user.getAccessToken(), serverId);
	}

	public static String myName() {
		return mc().getUser().getName();
	}

	public static java.util.UUID myUuid() {
		return mc().getUser().getProfileId();
	}

	/* ---------------- LAN hosting ---------------- */

	/** Opens the current singleplayer world to LAN. Returns the port, or -1. */
	public static int openToLan() {
		var server = mc().getSingleplayerServer();
		if (server == null) return -1;
		if (server.isPublished()) return server.getPort();
		int port = net.minecraft.util.HttpUtil.getAvailablePort();
		// 26.3 changed publishServer's arguments, so fill them in by type
		for (java.lang.reflect.Method m : server.getClass().getMethods()) {
			if (!m.getName().equals("publishServer")) continue;
			Class<?>[] types = m.getParameterTypes();
			Object[] args = new Object[types.length];
			boolean usable = true;
			for (int i = 0; i < types.length; i++) {
				Class<?> t = types[i];
				if (t == int.class) args[i] = port;
				else if (t == boolean.class) args[i] = false;
				else if (t.isInstance(server.getDefaultGameType())) args[i] = server.getDefaultGameType();
				else if (t.isEnum()) args[i] = pickScope(t);
				else usable = false;
			}
			if (!usable) continue;
			try {
				Object result = m.invoke(server, args);
				if (result instanceof Boolean b && !b) return -1;
				return port;
			} catch (Exception e) {
				org.slf4j.LoggerFactory.getLogger("kyoto").warn("Open to LAN failed", e);
			}
		}
		return -1;
	}

	/** Picks the most "open" multiplayer scope (LAN / public / friends), else the first option. */
	private static Object pickScope(Class<?> enumType) {
		Object[] values = enumType.getEnumConstants();
		for (String want : new String[]{"LAN", "PUBLIC", "FRIENDS", "OPEN"}) {
			for (Object v : values) if (((Enum<?>) v).name().contains(want)) return v;
		}
		return values.length > 0 ? values[values.length - 1] : null;
	}

	public static boolean isLanOpen() {
		var server = mc().getSingleplayerServer();
		return server != null && server.isPublished();
	}

	public static String currentServerIp() {
		return mc().getCurrentServer() == null ? null : mc().getCurrentServer().ip;
	}

	/* ---------------- Player heads ---------------- */

	/** Draws a player's face. Returns false if that player isn't in the tab list (caller draws a fallback). */
	public static boolean drawHead(GuiGraphicsExtractor g, String name, int x, int y, int size) {
		ClientPacketListener conn = mc().getConnection();
		if (conn == null || name == null) return false;
		PlayerInfo info = conn.getPlayerInfo(name);
		if (info == null) return false;
		PlayerFaceExtractor.extractRenderState(g, info.getSkin(), x, y, size);
		return true;
	}

	public static int latency() {
		ClientPacketListener conn = mc().getConnection();
		if (conn == null || mc().player == null) return -1;
		PlayerInfo info = conn.getPlayerInfo(mc().player.getUUID());
		return info == null ? -1 : info.getLatency();
	}

	/* ---------------- World ---------------- */

	public static boolean isNight() {
		return mc().level != null && mc().level.getSkyDarken() >= 6;
	}

	/* ---------------- Sounds ---------------- */

	public static void sound(SoundEvent event, float volume, float pitch) {
		Player p = mc().player;
		if (p == null || mc().level == null) return;
		mc().level.playLocalSound(p.getX(), p.getY(), p.getZ(), event, SoundSource.MASTER, volume, pitch, false);
	}

	/** A sound that isn't in the world (menus): works on the title screen too. */
	public static void uiSound(SoundEvent event, float volume, float pitch) {
		try {
			mc().getSoundManager().play(net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(event, pitch, volume));
		} catch (Throwable t) {
			sound(event, volume, pitch);
		}
	}

	/* ---------------- Particles ---------------- */

	public static void particle(ParticleOptions options, double x, double y, double z, double vx, double vy, double vz) {
		if (mc().level != null) mc().level.addParticle(options, x, y, z, vx, vy, vz);
	}

	public static void farParticle(ParticleOptions options, double x, double y, double z, double vx, double vy, double vz) {
		if (mc().level != null) mc().level.addAlwaysVisibleParticle(options, x, y, z, vx, vy, vz);
	}

	/* ---------------- Dimension / biome names ---------------- */

	public static String dimensionId() {
		if (mc().level == null) return "unknown";
		return keyPath(mc().level.dimension().toString());
	}

	public static String biomeName(BlockPos pos) {
		if (mc().level == null) return "?";
		return mc().level.getBiome(pos).unwrapKey()
				.map(k -> pretty(keyPath(k.toString())))
				.orElse("Unknown");
	}

	private static String keyPath(String raw) {
		int slash = raw.lastIndexOf(" / ");
		String s = slash >= 0 ? raw.substring(slash + 3) : raw;
		if (s.endsWith("]")) s = s.substring(0, s.length() - 1);
		return s.trim();
	}

	public static String pretty(String id) {
		String path = id.contains(":") ? id.substring(id.indexOf(':') + 1) : id;
		StringBuilder out = new StringBuilder();
		for (String part : path.split("[_/]")) {
			if (part.isEmpty()) continue;
			if (!out.isEmpty()) out.append(' ');
			out.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
		}
		return out.toString();
	}

	/* ---------------- Per-world waypoint file name ---------------- */

	private static Object keyFor = null;
	private static String keyCache = "unknown";

	public static String worldKey() {
		Minecraft mc = mc();
		Object who = mc.getCurrentServer() != null ? mc.getCurrentServer() : mc.getSingleplayerServer();
		if (who != null && who == keyFor) return keyCache;
		keyCache = computeWorldKey(mc);
		keyFor = who;
		return keyCache;
	}

	private static String computeWorldKey(Minecraft mc) {
		String raw;
		if (mc.getCurrentServer() != null) {
			raw = "server_" + mc.getCurrentServer().ip;
		} else if (mc.getSingleplayerServer() != null) {
			// the save FOLDER is unique ("New World", "New World (1)"...); the display name isn't
			String folder = null;
			try {
				Object srv = mc.getSingleplayerServer();
				Class<?> lr = Class.forName("net.minecraft.world.level.storage.LevelResource");
				Object root = lr.getField("ROOT").get(null);
				java.nio.file.Path p = (java.nio.file.Path) srv.getClass().getMethod("getWorldPath", lr).invoke(srv, root);
				java.nio.file.Path n = p.toAbsolutePath().normalize();
				if (n.getFileName() != null) folder = n.getFileName().toString();
			} catch (Throwable ignored) { }
			raw = "world_" + (folder != null && !folder.isBlank() ? folder : mc.getSingleplayerServer().getWorldData().getLevelName());
		} else {
			raw = "unknown";
		}
		return raw.replaceAll("[^a-zA-Z0-9._-]", "_");
	}

	/* ---------------- Other mods ---------------- */

	public static boolean modLoaded(String id) {
		return net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded(id);
	}

	/** Essential mod is installed (it has its own menus/cosmetics - Kyoto stays out of its way). */
	public static boolean essential() {
		return modLoaded("essential") || modLoaded("essential-container");
	}

	/** Simple Voice Chat is installed (needed for Kyoto calls). */
	public static boolean voiceChat() {
		return modLoaded("voicechat");
	}

	/* ---------------- Joining a server ---------------- */

	/** Leaves the current world (if any) and connects to ip. Uses reflection because these signatures move a lot. */
	public static void joinServer(String ip, String label) {
		Minecraft mc = mc();
		mc.execute(() -> {
			if (mc.level != null) {
				leaveWorld(mc);
				mc.execute(() -> connectNow(mc, ip, label));
			} else connectNow(mc, ip, label);
		});
	}

	private static void connectNow(Minecraft mc, String ip, String label) {
		{
			try {
				Class<?> cs = Class.forName("net.minecraft.client.gui.screens.ConnectScreen");
				Object addr = net.minecraft.client.multiplayer.resolver.ServerAddress.parseString(ip);
				Object data = new net.minecraft.client.multiplayer.ServerData(label, ip, net.minecraft.client.multiplayer.ServerData.Type.OTHER);
				Screen parent = new net.minecraft.client.gui.screens.TitleScreen();
				for (java.lang.reflect.Method m : cs.getMethods()) {
					if (!m.getName().equals("startConnecting") || !java.lang.reflect.Modifier.isStatic(m.getModifiers())) continue;
					Class<?>[] t = m.getParameterTypes();
					Object[] args = new Object[t.length];
					for (int i = 0; i < t.length; i++) {
						if (t[i].isInstance(parent)) args[i] = parent;
						else if (t[i].isInstance(mc)) args[i] = mc;
						else if (t[i].isInstance(addr)) args[i] = addr;
						else if (t[i].isInstance(data)) args[i] = data;
						else if (t[i] == boolean.class) args[i] = false;
						else args[i] = null;
					}
					m.invoke(null, args);
					return;
				}
				throw new IllegalStateException("no startConnecting");
			} catch (Throwable e) {
				org.slf4j.LoggerFactory.getLogger("kyoto").warn("Join server failed", e);
				dev.kyoto.hud.Notifications.push("Couldn't join", "Add " + ip + " in Multiplayer instead", 0xFF6B6B);
			}
		}
	}

	/** Disconnects from the current server / closes the singleplayer world. */
	public static void leaveWorld(Minecraft mc) {
		Screen title = new net.minecraft.client.gui.screens.TitleScreen();
		// 26.x: disconnect(Screen, boolean) - older: disconnect(Screen) / disconnect()
		for (String name : new String[]{"disconnect", "disconnectWithSavingScreen", "disconnectFromWorld"}) {
			for (java.lang.reflect.Method m : Minecraft.class.getMethods()) {
				if (!m.getName().equals(name)) continue;
				Class<?>[] t = m.getParameterTypes();
				Object[] args = new Object[t.length];
				boolean ok = true;
				for (int i = 0; i < t.length; i++) {
					if (t[i].isInstance(title)) args[i] = title;
					else if (t[i] == boolean.class) args[i] = false;
					else if (Component.class.isAssignableFrom(t[i])) args[i] = Component.literal("Joining a friend");
					else ok = false;
				}
				if (!ok) continue;
				try {
					m.invoke(mc, args);
					return;
				} catch (Throwable ignored) {
				}
			}
		}
	}

	/* ---------------- Streaming textures (video screens) ---------------- */

	/** A texture we rewrite every frame. Create + upload on the render thread only. */
	public static final class StreamTexture {
		public final Identifier id;
		public final int w, h;
		final com.mojang.blaze3d.platform.NativeImage img;
		final net.minecraft.client.renderer.texture.DynamicTexture tex;
		private long ptr = -1;

		StreamTexture(Identifier id, int w, int h) {
			this.id = id;
			this.w = w;
			this.h = h;
			this.img = new com.mojang.blaze3d.platform.NativeImage(w, h, false);
			this.tex = new net.minecraft.client.renderer.texture.DynamicTexture(id::toString, img);
			smoothFilter(tex);
			mc().getTextureManager().register(id, tex);
			// direct memory access = one fast copy per frame (the per-pixel fallback is MUCH slower)
			for (String n : new String[]{"getPointer", "pointer", "getPixelsPointer"}) {
				try {
					java.lang.reflect.Method gp = img.getClass().getMethod(n);
					ptr = (long) gp.invoke(img);
					if (ptr > 0) break;
				} catch (Throwable ignored) {
				}
			}
			if (ptr <= 0) {
				long bytes = (long) w * h * 4;
				for (java.lang.reflect.Field f : img.getClass().getDeclaredFields()) {
					if (f.getType() != long.class || java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
					String n = f.getName().toLowerCase(java.util.Locale.ROOT);
					// the pixel address is the long field that isn't the byte size (works even if Mojang renames it)
					if (n.contains("size")) continue;
					try {
						f.setAccessible(true);
						if (f.getLong(img) == bytes) continue;
					} catch (Throwable ignored) {
						continue;
					}
					try {
						f.setAccessible(true);
						long v = f.getLong(img);
						if (v > 0x10000) {
							ptr = v;
							break;
						}
					} catch (Throwable ignored) {
					}
				}
			}
			lastFast = ptr > 0;
			for (Class<?> c = tex.getClass(); c != null && uploadM == null; c = c.getSuperclass()) {
				try {
					uploadM = c.getDeclaredMethod("upload");
					uploadM.setAccessible(true);
				} catch (Throwable ignored) {
				}
			}
		}

		private java.lang.reflect.Method uploadM;
		/** Last StreamTexture made: true = fast one-copy uploads, false = slow pixel-by-pixel (tell us!). */
		public static volatile Boolean lastFast = null;

		public boolean fast() {
			return ptr > 0;
		}

		/** Same as upload(byte[]) but straight from native memory: one memcpy, no Java arrays. */
		public void upload(java.nio.ByteBuffer rgba) {
			int n = Math.min(rgba.remaining(), w * h * 4);
			if (ptr > 0) {
				org.lwjgl.system.MemoryUtil.memCopy(org.lwjgl.system.MemoryUtil.memAddress(rgba), ptr, n);
			} else {
				int i = rgba.position();
				for (int y = 0; y < h; y++) {
					for (int x = 0; x < w; x++, i += 4) {
						int argb = ((rgba.get(i + 3) & 0xFF) << 24) | ((rgba.get(i) & 0xFF) << 16) | ((rgba.get(i + 1) & 0xFF) << 8) | (rgba.get(i + 2) & 0xFF);
						img.setPixel(x, y, argb);
					}
				}
			}
			try {
				if (uploadM != null) uploadM.invoke(tex);
			} catch (Throwable ignored) {
			}
		}

		/** rgba = w*h*4 bytes, R,G,B,A order (ffmpeg -pix_fmt rgba). */
		public void upload(byte[] rgba) {
			if (ptr > 0) {
				org.lwjgl.system.MemoryUtil.memByteBuffer(ptr, w * h * 4).put(0, rgba, 0, Math.min(rgba.length, w * h * 4));
			} else {
				int i = 0;
				for (int y = 0; y < h; y++) {
					for (int x = 0; x < w; x++, i += 4) {
						int argb = ((rgba[i + 3] & 0xFF) << 24) | ((rgba[i] & 0xFF) << 16) | ((rgba[i + 1] & 0xFF) << 8) | (rgba[i + 2] & 0xFF);
						img.setPixel(x, y, argb);
					}
				}
			}
			try {
				if (uploadM != null) uploadM.invoke(tex);
			} catch (Throwable ignored) {
			}
		}

		public void close() {
			try {
				mc().getTextureManager().release(id);
			} catch (Throwable ignored) {
			}
		}
	}

	public static StreamTexture streamTexture(Identifier id, int w, int h) {
		return new StreamTexture(id, w, h);
	}

	/* ---------------- Line of sight ---------------- */

	/** True if a block is between the camera and the point (used so world screens / hitboxes don't show through walls). */
	public static boolean blockedFromCamera(net.minecraft.world.phys.Vec3 eye, net.minecraft.world.phys.Vec3 target) {
		Minecraft mc = mc();
		if (mc.level == null || mc.player == null) return false;
		try {
			var hit = mc.level.clip(new net.minecraft.world.level.ClipContext(eye, target,
					net.minecraft.world.level.ClipContext.Block.VISUAL, net.minecraft.world.level.ClipContext.Fluid.NONE, mc.player));
			return hit.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK
					&& hit.getLocation().distanceToSqr(eye) < target.distanceToSqr(eye) - 0.04;
		} catch (Throwable t) {
			return false;
		}
	}

	/** Draws a 1px line (screen space) using a rotated fill. */
	public static void line(GuiGraphicsExtractor g, float x1, float y1, float x2, float y2, float thick, int argb) {
		float dx = x2 - x1, dy = y2 - y1;
		float len = (float) Math.sqrt(dx * dx + dy * dy);
		if (len < 0.5f || len > 4000) return;
		g.pose().pushMatrix();
		g.pose().translate(x1, y1);
		g.pose().rotate((float) Math.atan2(dy, dx));
		g.pose().scale(1f, Math.max(0.5f, thick));
		g.fill(0, 0, Math.round(len), 1, argb);
		g.pose().popMatrix();
	}

	/* ---------------- Mojang skin (your real Minecraft skin) ---------------- */

	/** Uploads a skin to your actual Minecraft account (same API the official launcher uses). Token goes ONLY to Mojang. */
	public static void uploadMojangSkin(byte[] png, boolean slim) throws Exception {
		String token = mc().getUser().getAccessToken();
		String boundary = "----kyoto" + System.nanoTime();
		java.io.ByteArrayOutputStream body = new java.io.ByteArrayOutputStream();
		String nl = "\r\n";
		body.write(("--" + boundary + nl + "Content-Disposition: form-data; name=\"variant\"" + nl + nl + (slim ? "slim" : "classic") + nl).getBytes());
		body.write(("--" + boundary + nl + "Content-Disposition: form-data; name=\"file\"; filename=\"skin.png\"" + nl + "Content-Type: image/png" + nl + nl).getBytes());
		body.write(png);
		body.write((nl + "--" + boundary + "--" + nl).getBytes());
		var req = java.net.http.HttpRequest.newBuilder(java.net.URI.create("https://api.minecraftservices.com/minecraft/profile/skins"))
				.header("Authorization", "Bearer " + token)
				.header("Content-Type", "multipart/form-data; boundary=" + boundary)
				.POST(java.net.http.HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()))
				.timeout(java.time.Duration.ofSeconds(30)).build();
		var res = java.net.http.HttpClient.newHttpClient().send(req, java.net.http.HttpResponse.BodyHandlers.ofString());
		if (res.statusCode() >= 300) throw new IllegalStateException("Mojang said " + res.statusCode() + (res.statusCode() == 401 ? " (restart the launcher and try again)" : ""));
	}
}
