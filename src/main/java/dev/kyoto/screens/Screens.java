package dev.kyoto.screens;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import dev.kyoto.hud.Notifications;
import dev.kyoto.module.Modules;
import dev.kyoto.net.KyotoNet;
import dev.kyoto.util.Compat;
import dev.kyoto.util.Proj;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Video screens. On a server they are shared through the Kyoto server, so every Kyoto player there
 * sees the same screens playing the same second of the video. In singleplayer they're saved per world.
 *  - "See Other Screens" off: other people's screens are hidden.
 *  - "Hear Other Screens" off: they play muted.
 */
public final class Screens {
	private Screens() {}

	public static final class Screen {
		public String id = "";
		public String owner = "";
		public String dim = "";
		public double x, y, z;
		public float yaw;           // the way the screen FACES (towards the viewer)
		public float w = 4f, h = 2.25f;
		public String url = "";
		public long startedAt;      // ms (server clock) when the video was at 0:00
		public boolean paused = true;
		public double pausedPos = 0;
		/** Singleplayer: it was playing when you left the world - carry on from the same second when you come back. */
		public boolean autoResume = false;
		public transient boolean mine;

		public Screen copy() {
			Screen c = new Screen();
			c.id = id; c.owner = owner; c.dim = dim; c.x = x; c.y = y; c.z = z; c.yaw = yaw; c.w = w; c.h = h;
			c.url = url; c.startedAt = startedAt; c.paused = paused; c.pausedPos = pausedPos; c.mine = mine;
			return c;
		}

		public void setFrom(Screen o) {
			x = o.x; y = o.y; z = o.z; yaw = o.yaw; w = o.w; h = o.h; dim = o.dim;
		}

		public Vec3 center() {
			return new Vec3(x, y, z);
		}

		/** Unit vector to the screen's right, as seen by someone looking at it. */
		public Vec3 right() {
			double r = Math.toRadians(yaw);
			return new Vec3(Math.cos(r), 0, Math.sin(r));
		}

		public Vec3 normal() {
			double r = Math.toRadians(yaw);
			return new Vec3(-Math.sin(r), 0, Math.cos(r));
		}

		public double positionSec() {
			if (paused) return pausedPos;
			return Math.max(0, (now() - startedAt) / 1000.0);
		}
	}

	public static final List<Screen> SCREENS = new CopyOnWriteArrayList<>();
	/** Screens are drawn up to this far away, fading out over the last FADE blocks. */
	public static final double MAX_DIST = 72, FADE = 20;
	private static final Map<String, VideoStream> STREAMS = new HashMap<>();
	private static final Map<String, String> STREAM_KEYS = new HashMap<>();
	private static volatile long clockOffset = 0;
	private static long lastSync = 0;
	private static boolean syncing = false;
	private static final Gson GSON = new Gson();

	public static long now() {
		return System.currentTimeMillis() + clockOffset;
	}

	public static boolean singleplayer() {
		Minecraft mc = Minecraft.getInstance();
		return mc.getSingleplayerServer() != null && mc.getCurrentServer() == null;
	}

	/** A short hash of the server address, so the Kyoto server never stores where you play. */
	public static String serverKey() {
		String ip = Compat.currentServerIp();
		if (ip == null) return null;
		try {
			byte[] d = MessageDigest.getInstance("SHA-256").digest(ip.trim().toLowerCase(java.util.Locale.ROOT).getBytes(StandardCharsets.UTF_8));
			StringBuilder sb = new StringBuilder();
			for (int i = 0; i < 10; i++) sb.append(String.format("%02x", d[i]));
			return sb.toString();
		} catch (Exception e) {
			return null;
		}
	}

	/** The save file of the world you're in (remembered on join - by the time you leave, the world name is gone). */
	private static Path localPath = null;

	public static void onJoin() {
		SCREENS.clear();
		lastSync = 0;
		placing = null;
		held = false;
		localPath = singleplayer() ? localFile() : null;
		if (localPath != null) loadLocal();
	}

	public static void onLeave() {
		// singleplayer: freeze playing screens at the current second, so they carry on from there next time
		if (localPath != null && !SCREENS.isEmpty()) {
			long frozenAt = held ? heldAt : System.currentTimeMillis();
			for (Screen s : SCREENS) {
				if (s.paused) continue;
				s.pausedPos = Math.max(0, (frozenAt + clockOffset - s.startedAt) / 1000.0);
				s.paused = true;
				s.autoResume = true;
			}
			saveLocal();
		}
		for (VideoStream v : STREAMS.values()) v.stop();
		STREAMS.clear();
		STREAM_KEYS.clear();
		SCREENS.clear();
		placing = null;
		held = false;
		localPath = null;
	}

	/* ---------------- Esc menu: freeze every screen ---------------- */

	private static boolean held = false;
	private static long heldAt = 0;

	/** True while the pause menu (or another pausing menu that isn't Kyoto's) is open. */
	public static boolean menuOpen(Minecraft mc) {
		var sc = Compat.currentScreen();
		if (sc == null) return false;
		if (sc.getClass().getName().startsWith("dev.kyoto")) return false;
		return sc.isPauseScreen() || mc.isPaused();
	}

	private static void holdTick(Minecraft mc) {
		boolean menu = menuOpen(mc);
		if (menu && !held) {
			held = true;
			heldAt = System.currentTimeMillis();
		} else if (!menu && held) {
			held = false;
			long d = System.currentTimeMillis() - heldAt;
			if (singleplayer()) {
				// the world was paused too: push every playing screen's start time forward
				for (Screen s : SCREENS) if (!s.paused) s.startedAt += d;
				saveLocal();
			} else if (d > 4000) {
				// on a server everyone else kept watching: jump back in where they are
				for (VideoStream v : STREAMS.values()) v.stop();
				STREAMS.clear();
				STREAM_KEYS.clear();
			}
		}
		for (VideoStream v : STREAMS.values()) v.hold(held);
	}

	public static boolean held() {
		return held;
	}

	/* ---------------- every tick ---------------- */

	public static void tick(Minecraft mc) {
		if (mc.player == null || mc.level == null) return;
		holdTick(mc);
		if (held) return;
		boolean on = Modules.on("video_screens");
		if (on && !singleplayer() && System.currentTimeMillis() - lastSync > 4000 && !syncing) sync();
		String dim = Compat.dimensionId();
		Vec3 cam = Proj.cameraPos(1f);
		float base = new float[]{0.25f, 0.5f, 0.75f, 1f}[Modules.opt("screens.volume")] * masterVolume(mc);
		int quality = new int[]{360, 480, 720, 1080}[Modules.opt("screens.quality")];
		int qw = quality * 16 / 9, qh = quality;

		Map<String, Boolean> keep = new HashMap<>();
		for (Screen s : SCREENS) {
			boolean visible = on && s.dim.equals(dim) && (s.mine || Modules.on("see_others_screens"))
					&& s.center().distanceTo(mc.player.position()) < MAX_DIST && !s.url.isBlank();
			if (!visible || s.paused) continue;
			keep.put(s.id, true);
			String key = s.url + "|" + qw;
			VideoStream v = STREAMS.get(s.id);
			double want = s.positionSec();
			if (v != null && (!key.equals(STREAM_KEYS.get(s.id)) || (!v.ended && v.state.isEmpty() && Math.abs(v.position() - want) > 5.0))) {
				v.stop();
				v = null;
			}
			if (v == null) {
				final Screen fs = s;
				v = new VideoStream(s.url, fs::positionSec, qw, qh);
				STREAMS.put(s.id, v);
				STREAM_KEYS.put(s.id, key);
			}
			// sound: fades with distance, pans left/right
			Vec3 toScreen = nearestPoint(s, cam).subtract(cam);
			double dist = toScreen.length();
			float vol = (float) Math.max(0, 1 - dist / 32.0);
			vol = vol * vol * base;
			if (!s.mine && !Modules.on("hear_others_screens")) vol = 0;
			v.volume = vol;
			Vec3[] basis = Proj.basis(1f);
			v.pan = dist < 0.5 ? 0 : (float) (toScreen.normalize().dot(basis[1]) * 0.8);
		}
		STREAMS.entrySet().removeIf(e -> {
			if (keep.containsKey(e.getKey())) return false;
			e.getValue().stop();
			STREAM_KEYS.remove(e.getKey());
			return true;
		});
	}

	public static VideoStream stream(Screen s) {
		return STREAMS.get(s.id);
	}

	private static Vec3 nearestPoint(Screen s, Vec3 p) {
		Vec3 c = s.center(), r = s.right();
		Vec3 d = p.subtract(c);
		double along = Math.max(-s.w / 2, Math.min(s.w / 2, d.dot(r)));
		double up = Math.max(-s.h / 2, Math.min(s.h / 2, d.y));
		return c.add(r.scale(along)).add(0, up, 0);
	}

	/** Minecraft's Master volume slider (the method name moved around, so look it up). */
	private static float masterVolume(Minecraft mc) {
		try {
			for (String n : new String[]{"getFinalSoundSourceVolume", "getSoundSourceVolume"}) {
				try {
					var m = mc.options.getClass().getMethod(n, net.minecraft.sounds.SoundSource.class);
					Object r = m.invoke(mc.options, net.minecraft.sounds.SoundSource.MASTER);
					if (r instanceof Float f) return f;
					if (r instanceof Double d) return d.floatValue();
				} catch (NoSuchMethodException ignored) {
				}
			}
		} catch (Throwable ignored) {
		}
		return 1f;
	}

	/* ---------------- syncing with the Kyoto server ---------------- */

	private static void sync() {
		String key = serverKey();
		if (key == null || !KyotoNet.loggedIn()) return;
		syncing = true;
		lastSync = System.currentTimeMillis();
		KyotoNet.runAsync(() -> {
			try {
				long before = System.currentTimeMillis();
				JsonObject r = JsonParser.parseString(KyotoNet.send("GET", "/screens?server=" + key, null, true)).getAsJsonObject();
				long after = System.currentTimeMillis();
				clockOffset = r.get("now").getAsLong() - (before + after) / 2;
				List<Screen> list = new ArrayList<>();
				String me = Compat.myName();
				for (JsonElement e : r.getAsJsonArray("screens")) {
					Screen s = GSON.fromJson(e, Screen.class);
					s.mine = s.owner.equalsIgnoreCase(me);
					list.add(s);
				}
				Minecraft.getInstance().execute(() -> {
					Screen pl = placing;
					if (pl != null) list.replaceAll(x -> !pl.id.isEmpty() && x.id.equals(pl.id) ? pl : x);
					SCREENS.clear();
					SCREENS.addAll(list);
				});
			} catch (Exception e) {
				// offline - keep what we have
			} finally {
				syncing = false;
			}
		});
	}

	/** Saves a screen you own (creates it if the id is empty). */
	public static void save(Screen s) {
		s.owner = Compat.myName();
		s.mine = true;
		if (singleplayer()) {
			if (s.id.isEmpty()) s.id = UUID.randomUUID().toString().substring(0, 8);
			if (!SCREENS.contains(s)) SCREENS.add(s);
			saveLocal();
			return;
		}
		String key = serverKey();
		if (key == null) return;
		if (!SCREENS.contains(s)) SCREENS.add(s);
		KyotoNet.runAsync(() -> {
			try {
				JsonObject b = GSON.toJsonTree(s).getAsJsonObject();
				b.addProperty("server", key);
				b.addProperty("pos", s.positionSec());
				JsonObject r = JsonParser.parseString(KyotoNet.send("POST", "/screens/save", b, true)).getAsJsonObject();
				String id = r.get("id").getAsString();
				Minecraft.getInstance().execute(() -> s.id = id);
				lastSync = 0;
			} catch (Exception e) {
				Minecraft.getInstance().execute(() -> Notifications.push("Screen not saved", e.getMessage(), Notifications.DANGER));
			}
		});
	}

	public static void delete(Screen s) {
		SCREENS.remove(s);
		VideoStream v = STREAMS.remove(s.id);
		if (v != null) v.stop();
		if (singleplayer()) {
			saveLocal();
			return;
		}
		KyotoNet.runAsync(() -> {
			try {
				JsonObject b = new JsonObject();
				b.addProperty("id", s.id);
				KyotoNet.send("POST", "/screens/delete", b, true);
				lastSync = 0;
			} catch (Exception ignored) {
			}
		});
	}

	public static void play(Screen s) {
		s.startedAt = now() - (long) (s.pausedPos * 1000);
		s.paused = false;
		save(s);
	}

	public static void pause(Screen s) {
		s.pausedPos = s.positionSec();
		s.paused = true;
		save(s);
	}

	public static void seek(Screen s, double sec) {
		sec = Math.max(0, sec);
		if (s.paused) s.pausedPos = sec;
		else s.startedAt = now() - (long) (sec * 1000);
		save(s);
	}

	/* ---------------- singleplayer storage ---------------- */

	private static Path localFile() {
		return FabricLoader.getInstance().getGameDir().resolve("kyoto").resolve("screens").resolve(Compat.worldKey() + ".json");
	}

	private static void loadLocal() {
		try {
			Path f = localPath != null ? localPath : localFile();
			if (!Files.exists(f)) return;
			List<Screen> list = GSON.fromJson(Files.readString(f), new TypeToken<List<Screen>>() {}.getType());
			if (list != null) for (Screen s : list) {
				s.mine = true;
				if (s.autoResume) {
					s.autoResume = false;
					s.paused = false;
					s.startedAt = now() - (long) (s.pausedPos * 1000);
				}
				SCREENS.add(s);
			}
		} catch (Exception ignored) {
		}
	}

	private static void saveLocal() {
		try {
			Path f = localPath != null ? localPath : localFile();
			Files.createDirectories(f.getParent());
			Files.writeString(f, GSON.toJson(new ArrayList<>(SCREENS)));
		} catch (Exception ignored) {
		}
	}

	/* ---------------- placing a screen like a block ---------------- */

	/** The screen following your crosshair right now (null when not placing). */
	public static volatile Screen placing = null;
	private static Screen placingBackup = null;
	private static boolean placingNew = false;

	public static void startPlacing(Screen s, boolean isNew) {
		placingBackup = isNew ? null : s.copy();
		placingNew = isNew;
		placing = s;
		Minecraft mc = Minecraft.getInstance();
		if (mc.player != null) aim(s, mc);
		Compat.openScreen(null);
		Notifications.push(isNew ? "Place your screen" : "Move your screen", "Right-click to place  ·  Scroll to resize  ·  Left-click to cancel", Notifications.INFO);
	}

	public static boolean isPlacing() {
		return placing != null;
	}

	/** START of every client tick (before Minecraft handles clicks): follow the crosshair, place / cancel on click. */
	public static void placingTick(Minecraft mc) {
		Screen s = placing;
		if (s == null) return;
		if (mc.player == null || mc.level == null) {
			placing = null;
			return;
		}
		if (Compat.currentScreen() != null) return;
		aim(s, mc);
		boolean place = false, cancel = false;
		while (mc.options.keyUse.consumeClick()) place = true;
		while (mc.options.keyAttack.consumeClick()) cancel = true;
		if (place) {
			placing = null;
			save(s);
			Notifications.push("Screen placed", s.url.isBlank() ? "Pick a source and paste a link" : "Locked in place", Notifications.GOOD);
			if (s.url.isBlank()) dev.kyoto.screen.ScreensScreen.openFor(s);
		} else if (cancel) {
			placing = null;
			if (!placingNew && placingBackup != null) s.setFrom(placingBackup);
			Notifications.push("Cancelled", placingNew ? "No screen was placed" : "The screen stayed where it was", Notifications.INFO);
		}
	}

	/** Scroll wheel while placing = bigger / smaller. */
	public static boolean consumeScroll(double v) {
		Screen s = placing;
		if (s == null || v == 0 || Compat.currentScreen() != null) return false;
		s.w = (float) Math.max(1, Math.min(16, Math.round(s.w * (v > 0 ? 1.12 : 1 / 1.12) * 4) / 4.0));
		s.h = s.w * 9f / 16f;
		return true;
	}

	/** Snaps the screen to what you're looking at: flat on a wall, standing on the floor, hanging from a ceiling, or floating in front of you. */
	public static void aim(Screen s, Minecraft mc) {
		var p = mc.player;
		Vec3 eye = p.getEyePosition();
		Vec3 look = p.getViewVector(1f);
		net.minecraft.world.phys.BlockHitResult hit = mc.level.clip(new net.minecraft.world.level.ClipContext(eye, eye.add(look.scale(24)),
				net.minecraft.world.level.ClipContext.Block.OUTLINE, net.minecraft.world.level.ClipContext.Fluid.NONE, p));
		s.dim = Compat.dimensionId();
		float facingMe = Math.round((p.getYRot() + 180) / 90f) * 90f;
		if (hit.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK) {
			net.minecraft.core.Direction d = hit.getDirection();
			Vec3 hp = hit.getLocation();
			if (d.getAxis().isHorizontal()) {
				s.yaw = (float) Math.toDegrees(Math.atan2(-d.getStepX(), d.getStepZ()));
				var bp = hit.getBlockPos();
				Vec3 n = new Vec3(d.getStepX(), 0, d.getStepZ());
				Vec3 face = new Vec3(bp.getX() + 0.5, 0, bp.getZ() + 0.5).add(n.scale(0.5 + 0.02));
				Vec3 r = s.right();
				double along = Math.round(hp.subtract(face).dot(r) * 2) / 2.0;
				Vec3 c = face.add(r.scale(along));
				s.x = c.x;
				s.z = c.z;
				s.y = Math.round(hp.y * 4) / 4.0;
			} else if (d == net.minecraft.core.Direction.UP) {
				s.yaw = facingMe;
				s.x = Math.round(hp.x * 2) / 2.0;
				s.z = Math.round(hp.z * 2) / 2.0;
				s.y = hp.y + s.h / 2 + 0.02;
			} else {
				s.yaw = facingMe;
				s.x = Math.round(hp.x * 2) / 2.0;
				s.z = Math.round(hp.z * 2) / 2.0;
				s.y = hp.y - s.h / 2 - 0.02;
			}
		} else {
			Vec3 flat = new Vec3(look.x, 0, look.z).normalize();
			Vec3 c = eye.add(flat.scale(5));
			s.yaw = Math.round((p.getYRot() + 180) / 15f) * 15f;
			s.x = c.x;
			s.y = Math.round(eye.y * 4) / 4.0;
			s.z = c.z;
		}
	}

	/** Server JSON helper used by the settings screen. */
	public static JsonArray toJson() {
		return GSON.toJsonTree(new ArrayList<>(SCREENS)).getAsJsonArray();
	}
}
