package dev.kyoto.cosmetic;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.kyoto.screens.WorldScreens;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Smooth low-poly 3D models for pets and the wardrobe (assets/kyoto/models3d/*.json, made by Kyoto's model kit).
 * Each model is a few named parts with a pivot; animations rotate/scale/move parts every frame.
 * Lighting is done here per vertex (soft sun from above, a rim light and a little shine), so they look round and clean.
 */
public final class Mesh3D {
	private Mesh3D() {}

	public static final class Part {
		public final String name;
		final int parent;
		final float px, py, pz;
		final float[] v, n;
		final int[] c, idx;

		Part(String name, int parent, float px, float py, float pz, float[] v, float[] n, int[] c, int[] idx) {
			this.name = name; this.parent = parent; this.px = px; this.py = py; this.pz = pz; this.v = v; this.n = n; this.c = c; this.idx = idx;
		}
	}

	public static final class Model {
		public final String id, kind;
		public final boolean pixels;
		public final Part[] parts;
		final Map<String, Integer> byName = new HashMap<>();

		Model(String id, String kind, boolean pixels, Part[] parts) {
			this.id = id; this.kind = kind; this.pixels = pixels; this.parts = parts;
			for (int i = 0; i < parts.length; i++) byName.put(parts[i].name, i);
		}

		public int part(String name) {
			Integer i = byName.get(name);
			return i == null ? -1 : i;
		}

		/** A fresh pose: per part {rx, ry, rz, sx, sy, sz, tx, ty, tz}. */
		public float[][] pose() {
			float[][] p = new float[parts.length][9];
			for (float[] q : p) { q[3] = 1; q[4] = 1; q[5] = 1; }
			return p;
		}
	}

	private static final Map<String, Model> CACHE = new ConcurrentHashMap<>();
	private static final Map<String, Boolean> MISSING = new ConcurrentHashMap<>();
	private static final Identifier WHITE = Identifier.fromNamespaceAndPath("kyoto", "textures/misc/white.png");

	public static Model get(String id) {
		if (id == null || id.isEmpty() || MISSING.containsKey(id)) return null;
		Model m = CACHE.get(id);
		if (m != null) return m;
		try (InputStream in = Mesh3D.class.getResourceAsStream("/assets/kyoto/models3d/" + id.replaceAll("[^a-z0-9_]", "") + ".json")) {
			if (in == null) { MISSING.put(id, true); return null; }
			JsonObject o = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
			JsonArray ps = o.getAsJsonArray("parts");
			Part[] parts = new Part[ps.size()];
			for (int i = 0; i < parts.length; i++) {
				JsonObject p = ps.get(i).getAsJsonObject();
				JsonArray pv = p.getAsJsonArray("pivot");
				parts[i] = new Part(p.get("name").getAsString(), p.get("parent").getAsInt(), pv.get(0).getAsFloat(), pv.get(1).getAsFloat(), pv.get(2).getAsFloat(),
						floats(p.getAsJsonArray("v")), floats(p.getAsJsonArray("n")), ints(p.getAsJsonArray("c")), ints(p.getAsJsonArray("i")));
			}
			m = new Model(o.get("id").getAsString(), o.get("kind").getAsString(), "px".equals(o.get("unit").getAsString()), parts);
			CACHE.put(id, m);
			return m;
		} catch (Throwable t) {
			MISSING.put(id, true);
			dev.kyoto.KyotoClient.LOG.warn("Kyoto model {} failed to load", id, t);
			return null;
		}
	}

	/** The pet / wardrobe ids by kind, from models3d/index.json. */
	public static Map<String, List<String>> index() {
		Map<String, List<String>> out = new java.util.LinkedHashMap<>();
		try (InputStream in = Mesh3D.class.getResourceAsStream("/assets/kyoto/models3d/index.json")) {
			if (in == null) return out;
			JsonObject o = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
			for (String k : o.keySet()) {
				List<String> l = new java.util.ArrayList<>();
				for (var e : o.getAsJsonArray(k)) l.add(e.getAsString());
				out.put(k, l);
			}
		} catch (Throwable ignored) {
		}
		return out;
	}

	private static float[] floats(JsonArray a) {
		float[] f = new float[a.size()];
		for (int i = 0; i < f.length; i++) f[i] = a.get(i).getAsFloat();
		return f;
	}

	private static int[] ints(JsonArray a) {
		int[] f = new int[a.size()];
		for (int i = 0; i < f.length; i++) f[i] = a.get(i).getAsInt();
		return f;
	}

	/* ---------------- drawing ---------------- */

	private static final Vector3f LIGHT = new Vector3f(0.35f, 0.85f, 0.55f).normalize();

	/**
	 * Draws a model. root maps model space to camera-relative world space; pose comes from {@link Model#pose()} (null = rest).
	 * light 0..1 darkens it at night / in caves; alpha fades it in.
	 */
	public static void draw(Matrix4f cam, Model m, Matrix4f root, float[][] pose, float light, float alpha) throws Exception {
		Object type = WorldScreens.renderType(WHITE);
		if (type == null) return;
		VertexConsumer vc = WorldScreens.vertexBuffer(type);
		Matrix4f[] mats = new Matrix4f[m.parts.length];
		for (int i = 0; i < m.parts.length; i++) mats[i] = partMatrix(m, i, root, pose, mats);
		Vector3f p = new Vector3f(), nn = new Vector3f(), view = new Vector3f(), half = new Vector3f();
		float[] ax = new float[4], ay = new float[4], az = new float[4];
		int[] cr = new int[4], cg = new int[4], cb = new int[4];
		int a8 = Math.max(0, Math.min(255, Math.round(alpha * 255)));
		float amb = 0.42f + 0.16f * light, dif = 0.5f * (0.35f + 0.65f * light);
		for (int pi = 0; pi < m.parts.length; pi++) {
			Part part = m.parts[pi];
			Matrix4f mt = mats[pi];
			for (int t = 0; t < part.idx.length; t += 3) {
				for (int k = 0; k < 3; k++) {
					int vi = part.idx[t + k];
					mt.transformPosition(part.v[vi * 3], part.v[vi * 3 + 1], part.v[vi * 3 + 2], p);
					mt.transformDirection(part.n[vi * 3], part.n[vi * 3 + 1], part.n[vi * 3 + 2], nn);
					float len = nn.length();
					if (len > 1e-6f) nn.div(len);
					view.set(-p.x, -p.y, -p.z);
					float vl = view.length();
					if (vl > 1e-6f) view.div(vl);
					float d = Math.max(0f, nn.dot(LIGHT));
					float rim = (float) Math.pow(1f - Math.abs(nn.dot(view)), 2.2);
					half.set(LIGHT).add(view).normalize();
					float spec = (float) Math.pow(Math.max(0f, nn.dot(half)), 24) * 0.16f * light;
					float k1 = amb + dif * d;
					int col = part.c[vi];
					cr[k] = clamp((col >> 16 & 255) * k1 + 255 * (0.2f * rim * light + spec));
					cg[k] = clamp((col >> 8 & 255) * k1 + 235 * (0.2f * rim * light + spec));
					cb[k] = clamp((col & 255) * k1 + 242 * (0.2f * rim * light + spec));
					ax[k] = p.x; ay[k] = p.y; az[k] = p.z;
				}
				ax[3] = ax[2]; ay[3] = ay[2]; az[3] = az[2]; cr[3] = cr[2]; cg[3] = cg[2]; cb[3] = cb[2];
				for (int k = 0; k < 4; k++)
					vc.addVertex(cam, ax[k], ay[k], az[k]).setColor(cr[k], cg[k], cb[k], a8).setUv(0.5f, 0.5f).setOverlay(WorldScreens.OVERLAY).setLight(WorldScreens.LIGHT).setNormal(0f, 1f, 0f);
			}
		}
		WorldScreens.flushBuffers();
	}

	static Matrix4f partMatrix(Model m, int i, Matrix4f root, float[][] pose, Matrix4f[] done) {
		if (done[i] != null) return done[i];
		Part p = m.parts[i];
		Matrix4f base = p.parent >= 0 && p.parent < m.parts.length && p.parent != i ? partMatrix(m, p.parent, root, pose, done) : root;
		Matrix4f out = new Matrix4f(base);
		float[] q = pose == null ? null : pose[i];
		if (q != null) {
			out.translate(p.px + q[6], p.py + q[7], p.pz + q[8]);
			if (q[1] != 0) out.rotateY(q[1]);
			if (q[0] != 0) out.rotateX(q[0]);
			if (q[2] != 0) out.rotateZ(q[2]);
			if (q[3] != 1 || q[4] != 1 || q[5] != 1) out.scale(q[3], q[4], q[5]);
			out.translate(-p.px, -p.py, -p.pz);
		}
		done[i] = out;
		return out;
	}

	private static int clamp(float v) {
		return v < 0 ? 0 : v > 255 ? 255 : Math.round(v);
	}

	/** How lit a spot is (0 dark .. 1 daylight). Falls back to 1 if the version doesn't let us ask. */
	public static float lightAt(net.minecraft.world.phys.Vec3 at) {
		try {
			var mc = net.minecraft.client.Minecraft.getInstance();
			var pos = net.minecraft.core.BlockPos.containing(at);
			int block = mc.level.getBrightness(net.minecraft.world.level.LightLayer.BLOCK, pos);
			int sky = mc.level.getBrightness(net.minecraft.world.level.LightLayer.SKY, pos);
			float day = 1f;
			try {
				Object v = mc.level.getClass().getMethod("getSkyDarken").invoke(mc.level);
				if (v instanceof Integer i) day = 1f - i / 15f;
			} catch (Throwable ignored) { }
			return Math.max(0.25f, Math.max(block / 15f, sky / 15f * day));
		} catch (Throwable t) {
			return 1f;
		}
	}
}
