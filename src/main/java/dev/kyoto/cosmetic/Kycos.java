package dev.kyoto.cosmetic;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.kyoto.config.KyotoConfig;
import dev.kyoto.module.Modules;
import dev.kyoto.net.KyotoNet;
import dev.kyoto.screens.WorldScreens;
import dev.kyoto.util.Compat;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Custom cosmetics made in the Kyoto Cosmetic Builder (.kycos files).
 * One per slot (head, back, arm, leg). They're synced through the Kyoto server, so other Kyoto players see them too.
 *
 * Coordinates are "skin pixels" (1/16 of a block before the player's 0.9375 scale), model space:
 * feet at y=0, +y up, +z = the way the player faces, +x = the player's left. Each cosmetic is relative to its slot pivot
 * (neck, top of the body, right shoulder, right hip) and follows that body part as it moves.
 */
public final class Kycos {
	private Kycos() {}

	private static final Logger LOG = LoggerFactory.getLogger("kyoto-kycos");
	public static final String[] SLOTS = {"head", "back", "arm", "leg"};
	private static final float S = 0.9375f / 16f; // blocks per skin pixel
	private static final Identifier WHITE = Identifier.fromNamespaceAndPath("kyoto", "textures/misc/white.png");

	/** A ready-to-draw cosmetic: quads in slot space (4 corners x xyz) + colours, or one textured plane. */
	public static final class Model {
		final String name, slot;
		final boolean mirror;
		final float[] quads;   // 12 floats per quad
		final int[] colours;   // argb per quad
		Identifier texture;    // texture kind
		float[] plane;         // 4 corners x xyz for the picture
		final int voxels;
		// animation (made in Kyoto Studio): moving parts with keyframes
		int[] quadGroup;       // part per quad (null = no animation)
		byte[] quadSide;       // -1 = left of its part's pivot (mirrored parts flap the other way)
		AnimGroup[] groups;
		float animLen = 1;
		boolean animLoop = true;

		Model(String name, String slot, boolean mirror, float[] quads, int[] colours, int voxels) {
			this.name = name; this.slot = slot; this.mirror = mirror; this.quads = quads; this.colours = colours; this.voxels = voxels;
		}
	}

	/** One moving part of an animated cosmetic. Keys: {t, rx, ry, rz (degrees), px, py, pz (pixels), scale}. */
	static final class AnimGroup {
		float[] pivot = {0, 0, 0};
		boolean mirror;
		int ease; // 0 linear, 1 smooth, 2 bounce, 3 step, 4 elastic
		float[][] keys = new float[0][];
	}

	private static float ease(int e, float k) {
		return switch (e) {
			case 0 -> k;
			case 2 -> { float n = 7.5625f, d = 2.75f, x = k; if (x < 1 / d) yield n * x * x; if (x < 2 / d) { x -= 1.5f / d; yield n * x * x + .75f; } if (x < 2.5f / d) { x -= 2.25f / d; yield n * x * x + .9375f; } x -= 2.625f / d; yield n * x * x + .984375f; }
			case 3 -> k < 1 ? 0 : 1;
			case 4 -> k == 0 || k == 1 ? k : (float) (Math.pow(2, -10 * k) * Math.sin((k * 10 - .75) * (2 * Math.PI / 3)) + 1);
			default -> k * k * (3 - 2 * k);
		};
	}

	/** {rx, ry, rz, px, py, pz, s} of a part at time t (seconds). */
	private static float[] pose(AnimGroup g, float t, float len, boolean loop) {
		float[][] k = g.keys;
		if (k.length == 0) return null;
		if (k.length == 1) return java.util.Arrays.copyOfRange(k[0], 1, 8);
		t = loop ? ((t % len) + len) % len : Math.min(t, len);
		float[] a = null, b = null;
		float ta = 0, tb = 0;
		for (int i = 0; i < k.length; i++) {
			if (k[i][0] > t) {
				b = k[i]; tb = k[i][0];
				if (i == 0) { a = k[k.length - 1]; ta = a[0] - len; if (!loop) return java.util.Arrays.copyOfRange(b, 1, 8); }
				else { a = k[i - 1]; ta = a[0]; }
				break;
			}
		}
		if (b == null) { // after the last key: back to the first one at the end of the loop
			a = k[k.length - 1]; ta = a[0];
			if (!loop) return java.util.Arrays.copyOfRange(a, 1, 8);
			b = k[0]; tb = k[0][0] + len;
		}
		float f = tb - ta <= 1e-4f ? 1 : ease(g.ease, Mth.clamp((t - ta) / (tb - ta), 0, 1));
		float[] o = new float[7];
		for (int i = 0; i < 7; i++) o[i] = a[i + 1] + (b[i + 1] - a[i + 1]) * f;
		return o;
	}

	/** uuid -> slot -> model */
	private static final Map<String, Map<String, Model>> WORN = new ConcurrentHashMap<>();
	private static final Map<String, Long> VERSIONS = new ConcurrentHashMap<>();
	private static final Set<String> LOADING = ConcurrentHashMap.newKeySet();
	/** What YOU wear, built from your own copy - shows straight away, no server round trip needed. */
	private static final Map<String, Model> LOCAL = new ConcurrentHashMap<>();
	private static boolean localLoaded = false;
	public static volatile String serverNote = null;
	private static volatile int drawnLast = 0;

	private static void loadLocal() {
		if (localLoaded) return;
		localLoaded = true;
		for (Map.Entry<String, String> e : new HashMap<>(KyotoConfig.get().wornKycos).entrySet()) {
			try { LOCAL.put(e.getKey(), parse(JsonParser.parseString(e.getValue()).getAsJsonObject(), "local_" + e.getKey() + "_" + e.getValue().hashCode())); }
			catch (Exception ex) { LOG.warn("Couldn't load your worn {} cosmetic", e.getKey(), ex); }
		}
	}

	/** Wear a cosmetic from its JSON (used by files, the Store and the test). Returns its name. */
	public static String wearLocal(String json) {
		JsonObject d = JsonParser.parseString(json).getAsJsonObject();
		String slot = d.get("slot").getAsString();
		Model m = parse(d, "local_" + slot + "_" + json.hashCode());
		LOCAL.put(slot, m);
		KyotoConfig.get().wornKycos.put(slot, json);
		KyotoConfig.get().wornKycosAt = System.currentTimeMillis();
		KyotoConfig.save();
		return m.name;
	}

	/** Your slots: server copy, with your local copy on top. */
	private static Map<String, Model> mine(String uuid) {
		Map<String, Model> out = new HashMap<>(WORN.getOrDefault(uuid, Map.of()));
		out.putAll(LOCAL);
		return out;
	}

	public static Path folder() {
		Path p = FabricLoader.getInstance().getGameDir().resolve("kyoto").resolve("cosmetics");
		try { Files.createDirectories(p); } catch (Exception ignored) { }
		return p;
	}

	public static List<String> files() {
		List<String> out = new ArrayList<>();
		try (var s = Files.list(folder())) {
			s.map(x -> x.getFileName().toString()).filter(n -> n.toLowerCase(Locale.ROOT).endsWith(".kycos")).sorted().forEach(out::add);
		} catch (Exception ignored) { }
		return out;
	}

	/* ---------------- parsing ---------------- */

	private static int hex(String s) {
		try { return Integer.parseInt(s.replace("#", ""), 16) & 0xFFFFFF; } catch (Exception e) { return 0xFFFFFF; }
	}

	private static int shade(int rgb, float k) {
		int r = Math.min(255, Math.round(((rgb >> 16) & 255) * k)), g = Math.min(255, Math.round(((rgb >> 8) & 255) * k)), b = Math.min(255, Math.round((rgb & 255) * k));
		return 0xFF000000 | r << 16 | g << 8 | b;
	}

	// face directions: +x, -x, +y, -y, +z, -z  (corner offsets in voxel units, counter-clockwise from outside)
	private static final int[][] DIR = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
	private static final float[][][] FACE = {
			{{1, 0, 0}, {1, 1, 0}, {1, 1, 1}, {1, 0, 1}}, {{0, 0, 1}, {0, 1, 1}, {0, 1, 0}, {0, 0, 0}},
			{{0, 1, 0}, {0, 1, 1}, {1, 1, 1}, {1, 1, 0}}, {{0, 0, 1}, {0, 0, 0}, {1, 0, 0}, {1, 0, 1}},
			{{1, 0, 1}, {1, 1, 1}, {0, 1, 1}, {0, 0, 1}}, {{0, 0, 0}, {0, 1, 0}, {1, 1, 0}, {1, 0, 0}}};
	private static final float[] LIGHT = {0.8f, 0.8f, 1.0f, 0.55f, 0.9f, 0.7f};

	/** Builds a model from .kycos JSON. key = unique id for its texture. */
	public static Model parse(JsonObject d, String key) {
		String slot = d.get("slot").getAsString();
		String name = d.has("name") ? d.get("name").getAsString() : "Cosmetic";
		boolean mirror = d.has("mirror") && d.get("mirror").getAsBoolean();
		String kind = d.has("kind") ? d.get("kind").getAsString() : "voxel";
		if (kind.equals("texture")) {
			JsonObject t = d.getAsJsonObject("texture");
			byte[] png = Base64.getDecoder().decode(t.get("png").getAsString());
			JsonArray size = t.getAsJsonArray("size"), off = t.getAsJsonArray("offset"), rot = t.getAsJsonArray("rot");
			float w = size.get(0).getAsFloat(), h = size.get(1).getAsFloat();
			float[] c = {-w / 2, -h / 2, 0, w / 2, -h / 2, 0, w / 2, h / 2, 0, -w / 2, h / 2, 0};
			float rx = (float) Math.toRadians(rot.get(0).getAsFloat()), ry = (float) Math.toRadians(rot.get(1).getAsFloat()), rz = (float) Math.toRadians(rot.get(2).getAsFloat());
			for (int i = 0; i < 4; i++) {
				float[] p = {c[i * 3], c[i * 3 + 1], c[i * 3 + 2]};
				rotZ(p, rz); rotX(p, rx); rotY(p, ry);
				c[i * 3] = p[0] + off.get(0).getAsFloat(); c[i * 3 + 1] = p[1] + off.get(1).getAsFloat(); c[i * 3 + 2] = p[2] + off.get(2).getAsFloat();
			}
			Model m = new Model(name, slot, mirror, new float[0], new int[0], 0);
			m.plane = c;
			readAnim(d, m);
			Identifier id = Identifier.fromNamespaceAndPath("kyoto", "kycos/" + key.replaceAll("[^a-z0-9_]", "_"));
			Compat.registerPng(id, png, () -> m.texture = id);
			return m;
		}
		JsonObject g = d.getAsJsonObject("grid");
		JsonArray n = g.getAsJsonArray("n"), center = g.getAsJsonArray("center");
		int nx = n.get(0).getAsInt(), ny = n.get(1).getAsInt(), nz = n.get(2).getAsInt();
		float vs = g.get("vs").getAsFloat();
		float cx = center.get(0).getAsFloat(), cy = center.get(1).getAsFloat(), cz = center.get(2).getAsFloat();
		JsonArray pal = d.getAsJsonArray("palette");
		int[] palette = new int[pal.size()];
		for (int i = 0; i < palette.length; i++) palette[i] = hex(pal.get(i).getAsString());
		Map<Long, Integer> cells = new HashMap<>();
		Map<Long, Integer> part = new HashMap<>();
		boolean animated = d.has("anim") && d.get("anim").isJsonObject();
		for (JsonElement e : d.getAsJsonArray("voxels")) {
			JsonArray v = e.getAsJsonArray();
			long c = cell(v.get(0).getAsInt(), v.get(1).getAsInt(), v.get(2).getAsInt());
			cells.put(c, v.get(3).getAsInt());
			if (animated) part.put(c, v.size() > 4 ? v.get(4).getAsInt() : 0);
		}
		List<float[]> quads = new ArrayList<>();
		List<Integer> cols = new ArrayList<>();
		List<Integer> qg = new ArrayList<>();
		List<Float> qx = new ArrayList<>(); // centre x of each quad's voxel (decides the side of a mirrored part)
		for (Map.Entry<Long, Integer> en : cells.entrySet()) {
			long k = en.getKey();
			int x = (int) (k >> 32 & 0xFFFF), y = (int) (k >> 16 & 0xFFFF), z = (int) (k & 0xFFFF);
			int rgb = palette[Math.min(palette.length - 1, en.getValue())];
			int myPart = animated ? part.getOrDefault(k, 0) : 0;
			for (int f = 0; f < 6; f++) {
				long nb = cell(x + DIR[f][0], y + DIR[f][1], z + DIR[f][2]);
				// hidden face (faces between two moving parts stay, they can come apart)
				if (cells.containsKey(nb) && (!animated || part.getOrDefault(nb, 0) == myPart)) continue;
				float[] q = new float[12];
				for (int i = 0; i < 4; i++) {
					q[i * 3] = cx + (x + FACE[f][i][0] - nx / 2f) * vs;
					q[i * 3 + 1] = cy + (y + FACE[f][i][1] - ny / 2f) * vs;
					q[i * 3 + 2] = cz + (z + FACE[f][i][2] - nz / 2f) * vs;
				}
				quads.add(q);
				cols.add(shade(rgb, LIGHT[f]));
				qg.add(myPart);
				qx.add(cx + (x + .5f - nx / 2f) * vs);
			}
		}
		float[] flat = new float[quads.size() * 12];
		int[] colours = new int[cols.size()];
		for (int i = 0; i < quads.size(); i++) { System.arraycopy(quads.get(i), 0, flat, i * 12, 12); colours[i] = cols.get(i); }
		Model m = new Model(name, slot, mirror, flat, colours, cells.size());
		if (animated) {
			readAnim(d, m);
			if (m.groups != null) {
				m.quadGroup = new int[qg.size()];
				m.quadSide = new byte[qg.size()];
				for (int i = 0; i < qg.size(); i++) {
					int g2 = qg.get(i) >= 0 && qg.get(i) < m.groups.length ? qg.get(i) : 0;
					m.quadGroup[i] = g2;
					m.quadSide[i] = (byte) (qx.get(i) < m.groups[g2].pivot[0] ? -1 : 1);
				}
			}
		}
		return m;
	}

	private static void readAnim(JsonObject d, Model m) {
		if (!d.has("anim") || !d.get("anim").isJsonObject()) return;
		try {
			JsonObject a = d.getAsJsonObject("anim");
			m.animLen = Math.max(0.1f, a.get("len").getAsFloat());
			m.animLoop = !a.has("loop") || a.get("loop").getAsBoolean();
			JsonArray gs = a.getAsJsonArray("groups");
			if (gs.isEmpty()) return;
			AnimGroup[] out = new AnimGroup[gs.size()];
			for (int i = 0; i < out.length; i++) {
				JsonObject go = gs.get(i).getAsJsonObject();
				AnimGroup g = new AnimGroup();
				JsonArray pv = go.getAsJsonArray("pivot");
				g.pivot = new float[]{pv.get(0).getAsFloat(), pv.get(1).getAsFloat(), pv.get(2).getAsFloat()};
				g.mirror = go.has("mirror") && go.get("mirror").getAsBoolean();
				String e = go.has("ease") ? go.get("ease").getAsString() : "smooth";
				g.ease = switch (e) { case "linear" -> 0; case "bounce" -> 2; case "step" -> 3; case "elastic" -> 4; default -> 1; };
				JsonArray ks = go.getAsJsonArray("keys");
				g.keys = new float[ks.size()][];
				for (int j = 0; j < ks.size(); j++) {
					JsonObject k = ks.get(j).getAsJsonObject();
					JsonArray r = k.getAsJsonArray("r"), p = k.getAsJsonArray("p");
					g.keys[j] = new float[]{k.get("t").getAsFloat(), (float) Math.toRadians(r.get(0).getAsFloat()), (float) Math.toRadians(r.get(1).getAsFloat()), (float) Math.toRadians(r.get(2).getAsFloat()),
							p.get(0).getAsFloat(), p.get(1).getAsFloat(), p.get(2).getAsFloat(), k.get("s").getAsFloat()};
				}
				java.util.Arrays.sort(g.keys, (x, y) -> Float.compare(x[0], y[0]));
				out[i] = g;
			}
			m.groups = out;
		} catch (Exception e) {
			LOG.warn("Couldn't read the animation of {}", m.name, e);
			m.groups = null;
		}
	}

	/** Moves a point of a moving part to where it is at this moment. */
	private static void animate(float[] q, AnimGroup g, float[] pose, int side) {
		if (pose == null) return;
		boolean flip = g.mirror && side < 0;
		float rx = pose[0], ry = flip ? -pose[1] : pose[1], rz = flip ? -pose[2] : pose[2];
		float px = flip ? -pose[3] : pose[3], py = pose[4], pz = pose[5], sc = pose[6];
		float[] v = {(q[0] - g.pivot[0]) * sc, (q[1] - g.pivot[1]) * sc, (q[2] - g.pivot[2]) * sc};
		if (rz != 0) rotZ(v, rz);
		if (rx != 0) rotX(v, rx);
		if (ry != 0) rotY(v, ry);
		q[0] = v[0] + g.pivot[0] + px; q[1] = v[1] + g.pivot[1] + py; q[2] = v[2] + g.pivot[2] + pz;
	}

	private static float animTime() {
		Minecraft mc = Minecraft.getInstance();
		return mc.level == null ? 0 : (mc.level.getGameTime() % 2_000_000L + WorldScreens.partialTick(mc)) / 20f;
	}

	private static long cell(int x, int y, int z) {
		if (x < 0 || y < 0 || z < 0) return -1;
		return ((long) x << 32) | ((long) y << 16) | z;
	}

	private static void rotX(float[] p, float a) { float c = Mth.cos(a), s = Mth.sin(a), y = p[1], z = p[2]; p[1] = y * c - z * s; p[2] = y * s + z * c; }
	private static void rotY(float[] p, float a) { float c = Mth.cos(a), s = Mth.sin(a), x = p[0], z = p[2]; p[0] = x * c + z * s; p[2] = -x * s + z * c; }
	private static void rotZ(float[] p, float a) { float c = Mth.cos(a), s = Mth.sin(a), x = p[0], y = p[1]; p[0] = x * c - y * s; p[1] = x * s + y * c; }

	/* ---------------- syncing ---------------- */

	/** From the Kyoto server's player list: uuid -> version. Downloads what changed. */
	public static void onVersions(JsonObject versions) {
		for (Map.Entry<String, JsonElement> e : versions.entrySet()) {
			long v = e.getValue().getAsLong();
			Long have = VERSIONS.get(e.getKey());
			if (have == null || have != v) fetch(e.getKey(), v);
		}
	}

	public static void fetch(String uuid, long version) {
		if (!LOADING.add(uuid)) return;
		KyotoNet.runAsync(() -> {
			try {
				JsonObject r = JsonParser.parseString(KyotoNet.send("GET", "/kycos?uuid=" + uuid, null, false)).getAsJsonObject();
				Map<String, Model> slots = new HashMap<>();
				JsonObject s = r.getAsJsonObject("slots");
				long v = r.has("version") ? r.get("version").getAsLong() : version;
				Long had = VERSIONS.get(uuid);
				if (had != null && had == v) return; // nothing changed
				for (Map.Entry<String, JsonElement> e : s.entrySet()) {
					try { slots.put(e.getKey(), parse(e.getValue().getAsJsonObject(), uuid + "_" + e.getKey() + "_" + v)); }
					catch (Exception ex) { LOG.debug("bad kycos for {}", uuid, ex); }
				}
				WORN.put(uuid, slots);
				VERSIONS.put(uuid, v);
				// you changed what you wear somewhere else (website, Store): that wins over your local copy
				var me = Compat.myUuid();
				if (me != null && me.toString().equals(uuid) && v > KyotoConfig.get().wornKycosAt + 5000) {
					LOCAL.clear();
					KyotoConfig.get().wornKycos.clear();
					for (Map.Entry<String, JsonElement> e : s.entrySet()) KyotoConfig.get().wornKycos.put(e.getKey(), e.getValue().toString());
					LOCAL.putAll(slots);
					KyotoConfig.get().wornKycosAt = v;
					KyotoConfig.save();
				}
			} catch (Exception e) {
				LOG.debug("kycos fetch failed", e);
			} finally {
				LOADING.remove(uuid);
			}
		});
	}

	private static final Map<String, Long> CHECKED = new ConcurrentHashMap<>();
	private static int ticks = 0;

	/** Every ~minute: re-check the Kyoto players around you (and yourself) so changed cosmetics show up without relogging. */
	public static void tick(Minecraft mc) {
		if (mc.level == null || ++ticks % 100 != 0) return; // every 5 s, each player at most once a minute
		long now = System.currentTimeMillis();
		int n = 0;
		for (Player p : mc.level.players()) {
			String id = p.getUUID().toString();
			if (p != mc.player && !KyotoNet.isKyoto(id)) continue;
			if (now - CHECKED.getOrDefault(id, 0L) < 60000) continue;
			CHECKED.put(id, now);
			KyotoNet.recheck(id); // capes, skins, accessories
			if (Modules.on("custom_cosmetics")) fetch(id, -1);
			if (++n >= 6) break;
		}
	}

	public static void refreshMine() {
		var me = Compat.myUuid();
		if (me != null) fetch(me.toString(), -1);
	}

	/** Wear a .kycos file from the cosmetics folder. Shows on you right away, then uploads so other Kyoto players see it. */
	public static void wear(String file, java.util.function.Consumer<String> done) {
		KyotoNet.runAsync(() -> {
			String json, name;
			try {
				Path p = folder().resolve(Path.of(file).getFileName().toString());
				json = Files.readString(p, StandardCharsets.UTF_8);
				name = wearLocal(json);
			} catch (Exception e) {
				done.accept("Couldn't read that file: " + e.getMessage());
				return;
			}
			upload(json, name, done);
		});
	}

	static void upload(String json, String name, java.util.function.Consumer<String> done) {
		try {
			JsonObject body = new JsonObject();
			body.addProperty("data", json);
			KyotoNet.send("PUT", "/kycos", body, true);
			serverNote = null;
			done.accept("Wearing " + name + ". Press F5 to see it. Other Kyoto players see it within a minute.");
		} catch (Exception e) {
			serverNote = e.getMessage();
			done.accept("Wearing " + name + " (only you can see it right now: " + e.getMessage() + ")");
		}
	}

	/** A small gold crown, to check that custom cosmetics draw on your PC. */
	public static void wearTest(java.util.function.Consumer<String> done) {
		StringBuilder v = new StringBuilder();
		int n = 0;
		for (int x = 0; x < 10; x++) for (int z = 0; z < 10; z++) {
			boolean edge = x == 0 || x == 9 || z == 0 || z == 9;
			if (!edge) continue;
			v.append(n++ > 0 ? "," : "").append("[").append(x).append(",0,").append(z).append(",0]");
			if ((x + z) % 3 == 0) v.append(",[").append(x).append(",1,").append(z).append(",").append((x * z) % 2 == 0 ? 1 : 0).append("]");
		}
		String json = "{\"format\":\"kycos\",\"version\":1,\"name\":\"Test Crown\",\"slot\":\"head\",\"kind\":\"voxel\",\"mirror\":false,"
				+ "\"grid\":{\"n\":[10,2,10],\"vs\":1,\"center\":[0,9,0]},\"palette\":[\"#ffcf4a\",\"#ff5577\"],\"voxels\":[" + v + "]}";
		KyotoNet.runAsync(() -> upload(json, wearLocal(json), done));
	}

	public static void remove(String slot, java.util.function.Consumer<String> done) {
		KyotoNet.runAsync(() -> {
			try {
				JsonObject body = new JsonObject();
				body.addProperty("remove", true);
				if (slot != null) body.addProperty("slot", slot);
				if (slot == null) { LOCAL.clear(); KyotoConfig.get().wornKycos.clear(); }
				else { LOCAL.remove(slot); KyotoConfig.get().wornKycos.remove(slot); }
				KyotoConfig.save();
				KyotoNet.send("PUT", "/kycos", body, true);
				refreshMine();
				done.accept(slot == null ? "Took off all custom cosmetics" : "Took off your " + slot + " cosmetic");
			} catch (Exception e) {
				done.accept("Couldn't do that: " + e.getMessage());
			}
		});
	}

	public static volatile String drawError = null;

	/** One line for /kyoto kycos: what you're wearing and whether drawing works. */
	public static String status() {
		loadLocal();
		var me = Compat.myUuid();
		Map<String, Model> mine = me == null ? new HashMap<>(LOCAL) : mine(me.toString());
		StringBuilder b = new StringBuilder();
		if (!Modules.on("custom_cosmetics")) b.append("Custom Cosmetics is OFF (Right Shift > Cosmetics). ");
		if (mine.isEmpty()) b.append("You're not wearing any custom cosmetics. Try /kyoto kycos test");
		else {
			b.append("Wearing: ");
			mine.values().forEach(m -> b.append(m.name).append(" (").append(m.slot).append(m.voxels > 0 ? ", " + m.voxels + " voxels" : "").append(")  "));
		}
		if (drawError != null) b.append(" | Can't draw them: ").append(drawError);
		else if (!WorldScreens.drawingAny()) b.append(" | The world drawing hook isn't running yet.");
		else if (drawnLast == 0 && !mine.isEmpty()) b.append(" | Press F5 to see yourself.");
		if (Aura.error != null) b.append(" | Particles can't draw: ").append(Aura.error);
		if (serverNote != null) b.append(" | Server: ").append(serverNote).append(" (others can't see it yet)");
		return b.toString();
	}

	public static Map<String, Model> worn(String uuid) {
		return WORN.getOrDefault(uuid, Map.of());
	}

	/* ---------------- drawing (called from the world render hook) ---------------- */

	private static Method walkPos, walkSpeed;
	private static Object walkField(Player p) {
		try { return p.getClass().getField("walkAnimation").get(p); } catch (Throwable t) { return null; }
	}

	public static void render(PoseStack pose, Vec3 cam, float partial) throws Exception {
		Minecraft mc = Minecraft.getInstance();
		loadLocal();
		drawnLast = 0;
		if (mc.level == null || mc.player == null || !Modules.on("custom_cosmetics") || (WORN.isEmpty() && LOCAL.isEmpty())) return;
		Matrix4f m = pose.last().pose();
		int drawn = 0;
		for (Player p : mc.level.players()) {
			if (drawn >= 16) break;
			boolean me = p == mc.player;
			if (me && mc.options.getCameraType().isFirstPerson()) continue;
			if (!me && (!Modules.on("others_cosmetics") || KyotoConfig.get().hiddenCosmeticsPlayers.contains(p.getUUID().toString()))) continue;
			if (p.isInvisible() || p.isSleeping() || p.distanceTo(mc.player) > 48) continue;
			Map<String, Model> slots = me ? mine(p.getUUID().toString()) : WORN.get(p.getUUID().toString());
			if (slots == null || slots.isEmpty()) continue;
			drawn++;
			drawnLast = drawn;
			drawPlayer(m, p, slots, cam, partial);
		}
	}

	private static void drawPlayer(Matrix4f m, Player p, Map<String, Model> slots, Vec3 cam, float partial) throws Exception {
		Vec3 pos = p.getPosition(partial).subtract(cam);
		float bodyYaw = Mth.rotLerp(partial, p.yBodyRotO, p.yBodyRot);
		float headYaw = Mth.rotLerp(partial, p.yHeadRotO, p.yHeadRot);
		float pitch = (float) Math.toRadians(p.getViewXRot(partial));
		float th = (float) Math.toRadians(bodyYaw);
		float fx = -Mth.sin(th), fz = Mth.cos(th), lx = Mth.cos(th), lz = Mth.sin(th);
		float wp = 0, ws = 0;
		Object wa = walkField(p);
		if (wa != null) {
			try {
				if (walkPos == null) { walkPos = wa.getClass().getMethod("position", float.class); walkSpeed = wa.getClass().getMethod("speed", float.class); }
				wp = (float) walkPos.invoke(wa, partial); ws = Math.min(1f, (float) walkSpeed.invoke(wa, partial));
			} catch (Throwable ignored) { }
		}
		float crouch = p.isCrouching() ? -3f : 0f;
		for (Model mod : slots.values()) {
			for (int side = 0; side < (mod.mirror ? 2 : 1); side++) {
				boolean left = side == 1;
				float[] pivot; float ax = 0, ay = 0;
				switch (mod.slot) {
					case "head" -> { pivot = new float[]{0, 24 + crouch, 0}; ax = pitch; ay = (float) -Math.toRadians(Mth.wrapDegrees(headYaw - bodyYaw)); }
					case "arm" -> { pivot = new float[]{left ? 5 : -5, 22 + crouch, 0}; ax = Mth.cos(wp * 0.6662f + (left ? 0 : Mth.PI)) * ws; }
					case "leg" -> { pivot = new float[]{left ? 1.9f : -1.9f, 12, 0}; ax = Mth.cos(wp * 0.6662f + (left ? Mth.PI : 0)) * 1.4f * ws; }
					default -> pivot = new float[]{0, 24 + crouch, 0};
				}
				final float fax = ax, fay = ay;
				final boolean mir = left;
				Xf xf = (q, out) -> {
					float[] v = {mir ? -q[0] : q[0], q[1], q[2]};
					if (fax != 0) rotX(v, fax);
					if (fay != 0) rotY(v, fay);
					float x = (v[0] + pivot[0]) * S, y = (v[1] + pivot[1]) * S, z = (v[2] + pivot[2]) * S;
					out[0] = (float) pos.x + lx * x + fx * z; out[1] = (float) pos.y + y; out[2] = (float) pos.z + lz * x + fz * z;
				};
				if (mod.plane != null) drawPlane(m, mod, xf, mir);
				else drawQuads(m, mod, xf, mir);
			}
		}
	}

	private interface Xf { void apply(float[] q, float[] out); }

	private static void drawQuads(Matrix4f m, Model mod, Xf xf, boolean mirrored) throws Exception {
		Object type = WorldScreens.renderType(WHITE);
		if (type == null) return;
		VertexConsumer vc = WorldScreens.vertexBuffer(type);
		float[] q = new float[3], o = new float[3];
		int quads = mod.colours.length;
		float[][] poses = null;
		if (mod.groups != null && mod.quadGroup != null) {
			float t = animTime();
			poses = new float[mod.groups.length][];
			for (int gi = 0; gi < mod.groups.length; gi++) poses[gi] = pose(mod.groups[gi], t, mod.animLen, mod.animLoop);
		}
		for (int i = 0; i < quads; i++) {
			int c = mod.colours[i], r = c >> 16 & 255, g = c >> 8 & 255, b = c & 255;
			int base = i * 12;
			for (int k = 0; k < 4; k++) {
				int j = mirrored ? 3 - k : k; // mirroring flips the winding
				q[0] = mod.quads[base + j * 3]; q[1] = mod.quads[base + j * 3 + 1]; q[2] = mod.quads[base + j * 3 + 2];
				if (poses != null) { int gi = mod.quadGroup[i]; animate(q, mod.groups[gi], poses[gi], mod.quadSide[i]); }
				xf.apply(q, o);
				vc.addVertex(m, o[0], o[1], o[2]).setColor(r, g, b, 255).setUv(0.5f, 0.5f).setOverlay(WorldScreens.OVERLAY).setLight(WorldScreens.LIGHT).setNormal(0f, 1f, 0f);
			}
		}
		WorldScreens.flushBuffers();
	}

	private static void drawPlane(Matrix4f m, Model mod, Xf xf, boolean mirrored) throws Exception {
		if (mod.texture == null) return;
		Object type = WorldScreens.renderType(mod.texture);
		if (type == null) return;
		VertexConsumer vc = WorldScreens.vertexBuffer(type);
		float[][] uv = {{0, 1}, {1, 1}, {1, 0}, {0, 0}};
		float[] q = new float[3], o = new float[3];
		float[] pz = mod.groups != null && mod.groups.length > 0 ? pose(mod.groups[0], animTime(), mod.animLen, mod.animLoop) : null;
		for (int pass = 0; pass < 2; pass++) { // both sides
			for (int k = 0; k < 4; k++) {
				int j = (pass == 0) != mirrored ? k : 3 - k;
				q[0] = mod.plane[j * 3]; q[1] = mod.plane[j * 3 + 1]; q[2] = mod.plane[j * 3 + 2];
				if (pz != null) animate(q, mod.groups[0], pz, 1);
				xf.apply(q, o);
				vc.addVertex(m, o[0], o[1], o[2]).setColor(255, 255, 255, 255).setUv(uv[j][0], uv[j][1]).setOverlay(WorldScreens.OVERLAY).setLight(WorldScreens.LIGHT).setNormal(0f, 1f, 0f);
			}
		}
		WorldScreens.flushBuffers();
	}
}
