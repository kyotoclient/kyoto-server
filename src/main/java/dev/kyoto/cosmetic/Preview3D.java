package dev.kyoto.cosmetic;

import dev.kyoto.net.Heads;
import dev.kyoto.util.Compat;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.Map;

/**
 * A small 3D renderer for menus: draws YOUR skin (both layers, so hats, jackets and sleeves show) wearing the exact
 * same 3D cosmetics as in game, with your pet beside you. Rendered on the CPU into a texture, so it works the same on
 * every Minecraft version and graphics card.
 */
public final class Preview3D {
	private final int W, H;
	private final int[] color;
	private final float[] depth;
	private final byte[] rgba;
	private final Compat.StreamTexture tex;
	private static int counter = 0;

	public Preview3D(int w, int h) {
		this.W = w;
		this.H = h;
		color = new int[w * h];
		depth = new float[w * h];
		rgba = new byte[w * h * 4];
		tex = Compat.streamTexture(Identifier.fromNamespaceAndPath("kyoto", "preview3d_" + (counter++)), w, h);
	}

	private Heads.Skin override = null;

	/** Show this skin instead of the player's Mojang skin (Kyoto skins). */
	public void skinOverride(Heads.Skin s) {
		override = s;
	}

	/** A skin PNG (bytes) as ARGB pixels. */
	public static Heads.Skin decode(byte[] png, boolean slim) {
		try {
			java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(png));
			int[] px = new int[64 * 64];
			for (int y = 0; y < 64; y++) for (int x = 0; x < 64; x++) px[y * 64 + x] = y < img.getHeight() && x < img.getWidth() ? img.getRGB(x, y) : 0;
			return new Heads.Skin(px, slim);
		} catch (Throwable t) {
			return null;
		}
	}

	public Identifier texture() {
		return tex.id;
	}

	public void close() {
		tex.close();
	}

	/* ---------------- frame ---------------- */

	private Matrix4f view;
	private float scale, cx, cy;
	private static final Vector3f LIGHT = new Vector3f(0.35f, 0.85f, 0.55f).normalize();

	/**
	 * Draws one frame. yaw/pitch turn the camera around the player. wear = slot -> piece; pet = pet id or null.
	 * zoom 1 = whole body. focus moves the camera up towards the head (0 body .. 1 head).
	 */
	public void render(String player, float yaw, float pitch, Map<String, String> wear, String pet, float zoom, float focus) {
		java.util.Arrays.fill(color, 0);
		java.util.Arrays.fill(depth, Float.POSITIVE_INFINITY);
		// world units = skin pixels; the player stands at the origin, 32 px tall
		boolean withPet = pet != null && !pet.equals("none");
		float centreY = 16 + focus * 12;
		float fit = (withPet ? 44f : 38f) / zoom;
		scale = H / fit;
		cx = W / 2f + (withPet ? -W * 0.08f : 0);
		cy = H / 2f;
		view = new Matrix4f().rotateX(pitch).rotateY(yaw).translate(0, -centreY, 0);
		float t = System.currentTimeMillis() / 1000f;
		Heads.Skin skin = override != null ? override : player == null ? null : Heads.skin(player);
		drawPlayer(skin, t);
		if (wear != null) for (String slot : Wardrobe.SLOTS) {
			String id = wear.get(slot);
			Mesh3D.Model m = id == null ? null : Mesh3D.get(id);
			if (m == null) continue;
			Matrix4f root = new Matrix4f(view).translate(0, 24, 0);
			float[][] q = m.pose();
			if (slot.equals("halo")) {
				root.translate(0, (float) Math.sin(t * 2.2f) * 0.35f, 0);
				int i = m.part("halo");
				if (i >= 0) q[i][1] = id.equals("halo_sakura") ? t * 0.6f : (float) Math.sin(t * 0.8f) * 0.15f;
			}
			if (slot.equals("back")) {
				float flap = (float) Math.sin(t * 1.6f) * 0.08f;
				int l = m.part("wingL"), r = m.part("wingR");
				if (l >= 0) q[l][1] = -flap - 0.12f;
				if (r >= 0) q[r][1] = flap + 0.12f;
			}
			mesh(m, root, q);
		}
		if (withPet) {
			Mesh3D.Model m = Mesh3D.get(pet);
			if (m != null) {
				boolean fly = Pets3D.flyer(pet);
				float px = 15f, py = fly ? 18f + (float) Math.sin(t * 1.4f) * 0.8f : 0f;
				float k = 16f / 0.9375f;
				Matrix4f root = new Matrix4f(view).translate(px, py, 4).scale(k).rotateY(-0.5f + (float) Math.sin(t * 0.5f) * 0.25f);
				float[][] q = m.pose();
				int b = m.part("body");
				if (b >= 0) q[b][4] = 1f + (float) Math.sin(t * 2.4f) * 0.025f;
				int tail = m.part("tail");
				if (tail >= 0) q[tail][1] = (float) Math.sin(t * 5f) * 0.4f;
				int wl = m.part("wingL"), wr = m.part("wingR");
				float flap = pet.equals("bee") ? (float) Math.sin(t * 40f) * 0.5f : (float) Math.sin(t * 6f) * 0.5f;
				if (wl >= 0) q[wl][2] = -flap;
				if (wr >= 0) q[wr][2] = flap;
				mesh(m, root, q);
			}
		}
		// soft edges: resolve to RGBA bytes
		for (int i = 0, j = 0; i < color.length; i++, j += 4) {
			int c = color[i];
			rgba[j] = (byte) (c >> 16);
			rgba[j + 1] = (byte) (c >> 8);
			rgba[j + 2] = (byte) c;
			rgba[j + 3] = (byte) (c >>> 24);
		}
		tex.upload(rgba);
	}

	/**
	 * Where a point in player space (blocks, feet at 0, +x = their left, +z = forward) lands in this preview:
	 * {x 0..1, y 0..1, depth (positive = in front of the player)}.
	 */
	public float[] project(float bx, float by, float bz) {
		if (view == null) return null;
		Vector3f v = view.transformPosition(bx * 16f / 0.9375f, by * 16f / 0.9375f, bz * 16f / 0.9375f, new Vector3f());
		return new float[]{(cx + v.x * scale) / W, (cy - v.y * scale) / H, v.z};
	}

	/** Pixels per block on screen, for sizing sprites. */
	public float pixelsPerBlock() {
		return scale * 16f / 0.9375f;
	}

	/* ---------------- the player (classic Minecraft layout, both skin layers) ---------------- */

	private void drawPlayer(Heads.Skin skin, float t) {
		int[] px = skin == null ? null : skin.argb();
		boolean slim = skin != null && skin.slim();
		int aw = slim ? 3 : 4;
		float swing = (float) Math.sin(t * 1.2f) * 0.06f, breathe = (float) Math.sin(t * 2.4f) * 0.25f;
		// head
		Matrix4f head = new Matrix4f(view).translate(0, 24, 0).rotateY((float) Math.sin(t * 0.6f) * 0.12f);
		box(px, head, -4, 0, -4, 8, 8, 8, 0, 0, 0f);
		box(px, head, -4, 0, -4, 8, 8, 8, 32, 0, 0.5f);
		// body
		Matrix4f body = new Matrix4f(view).translate(0, 12 + breathe * 0.2f, 0);
		box(px, body, -4, 0, -2, 8, 12, 4, 16, 16, 0f);
		box(px, body, -4, 0, -2, 8, 12, 4, 16, 32, 0.25f);
		// arms (right arm = viewer's left when facing us)
		Matrix4f ra = new Matrix4f(view).translate(-4 - aw / 2f, 22, 0).rotateZ(-0.06f - swing).rotateX(swing * 0.5f).translate(0, -10, 0);
		box(px, ra, -aw / 2f, 0, -2, aw, 12, 4, 40, 16, 0f);
		box(px, ra, -aw / 2f, 0, -2, aw, 12, 4, 40, 32, 0.25f);
		Matrix4f la = new Matrix4f(view).translate(4 + aw / 2f, 22, 0).rotateZ(0.06f + swing).rotateX(-swing * 0.5f).translate(0, -10, 0);
		box(px, la, -aw / 2f, 0, -2, aw, 12, 4, 32, 48, 0f);
		box(px, la, -aw / 2f, 0, -2, aw, 12, 4, 48, 48, 0.25f);
		// legs
		Matrix4f rl = new Matrix4f(view).translate(-2, 12, 0).translate(0, -12, 0);
		box(px, rl, -2, 0, -2, 4, 12, 4, 0, 16, 0f);
		box(px, rl, -2, 0, -2, 4, 12, 4, 0, 32, 0.25f);
		Matrix4f ll = new Matrix4f(view).translate(2, 12, 0).translate(0, -12, 0);
		box(px, ll, -2, 0, -2, 4, 12, 4, 16, 48, 0f);
		box(px, ll, -2, 0, -2, 4, 12, 4, 0, 48, 0.25f);
	}

	/**
	 * A skin box. (x,y,z) is its min corner and (w,h,d) its size in skin pixels; (u,v) the top-left of its texture
	 * layout; inflate puts the outer layer slightly outside. Faces follow Minecraft's skin layout exactly.
	 */
	private void box(int[] px, Matrix4f m, float x, float y, float z, float w, float h, float d, int u, int v, float inflate) {
		float x0 = x - inflate, y0 = y - inflate, z0 = z - inflate, x1 = x + w + inflate, y1 = y + h + inflate, z1 = z + d + inflate;
		int W2 = (int) w, H2 = (int) h, D2 = (int) d;
		boolean outer = inflate > 0;
		// corners are top-left, top-right, bottom-right, bottom-left as seen from outside that face
		face(px, m, outer, x0, y1, z1, x1, y1, z1, x1, y0, z1, x0, y0, z1, u + D2, v + D2, W2, H2, 0, 0, 1);                 // front
		face(px, m, outer, x1, y1, z0, x0, y1, z0, x0, y0, z0, x1, y0, z0, u + D2 + W2 + D2, v + D2, W2, H2, 0, 0, -1);     // back
		face(px, m, outer, x0, y1, z0, x0, y1, z1, x0, y0, z1, x0, y0, z0, u, v + D2, D2, H2, -1, 0, 0);                   // right side
		face(px, m, outer, x1, y1, z1, x1, y1, z0, x1, y0, z0, x1, y0, z1, u + D2 + W2, v + D2, D2, H2, 1, 0, 0);          // left side
		face(px, m, outer, x0, y1, z0, x1, y1, z0, x1, y1, z1, x0, y1, z1, u + D2, v, W2, D2, 0, 1, 0);                    // top
		face(px, m, outer, x0, y0, z1, x1, y0, z1, x1, y0, z0, x0, y0, z0, u + D2 + W2, v, W2, D2, 0, -1, 0);              // bottom
	}

	private final Vector3f a = new Vector3f(), b = new Vector3f(), c = new Vector3f(), e = new Vector3f(), n = new Vector3f();

	/** One textured face: corners top-left, top-right, bottom-right, bottom-left as seen in the skin's layout. */
	private void face(int[] px, Matrix4f m, boolean outer, float ax, float ay, float az, float bx, float by, float bz, float cx2, float cy2, float cz,
					  float dx, float dy, float dz, int u, int v, int uw, int vh, float nx, float ny, float nz) {
		m.transformPosition(ax, ay, az, a);
		m.transformPosition(bx, by, bz, b);
		m.transformPosition(cx2, cy2, cz, c);
		m.transformPosition(dx, dy, dz, e);
		m.transformDirection(nx, ny, nz, n);
		n.normalize();
		if (n.z < -0.02f) return; // facing away
		float shade = 0.6f + 0.4f * Math.max(0, n.dot(LIGHT)) + 0.08f;
		// two triangles with texture coords
		tri(px, a, b, c, u, v, u + uw, v, u + uw, v + vh, shade, outer);
		tri(px, a, c, e, u, v, u + uw, v + vh, u, v + vh, shade, outer);
	}

	private void tri(int[] px, Vector3f p0, Vector3f p1, Vector3f p2, float u0, float v0, float u1, float v1, float u2, float v2, float shade, boolean alphaTest) {
		float x0 = cx + p0.x * scale, y0 = cy - p0.y * scale, x1 = cx + p1.x * scale, y1 = cy - p1.y * scale, x2 = cx + p2.x * scale, y2 = cy - p2.y * scale;
		float area = (x1 - x0) * (y2 - y0) - (x2 - x0) * (y1 - y0);
		if (Math.abs(area) < 1e-4f) return;
		int minX = Math.max(0, (int) Math.floor(Math.min(x0, Math.min(x1, x2)))), maxX = Math.min(W - 1, (int) Math.ceil(Math.max(x0, Math.max(x1, x2))));
		int minY = Math.max(0, (int) Math.floor(Math.min(y0, Math.min(y1, y2)))), maxY = Math.min(H - 1, (int) Math.ceil(Math.max(y0, Math.max(y1, y2))));
		for (int y = minY; y <= maxY; y++) {
			float sy = y + 0.5f;
			for (int x = minX; x <= maxX; x++) {
				float sx = x + 0.5f;
				float w0 = ((x1 - sx) * (y2 - sy) - (x2 - sx) * (y1 - sy)) / area;
				float w1 = ((x2 - sx) * (y0 - sy) - (x0 - sx) * (y2 - sy)) / area;
				float w2 = 1 - w0 - w1;
				if (w0 < -1e-4f || w1 < -1e-4f || w2 < -1e-4f) continue;
				float z = -(w0 * p0.z + w1 * p1.z + w2 * p2.z) - (alphaTest ? 0.01f : 0);
				int i = y * W + x;
				if (z >= depth[i]) continue;
				int argb;
				if (px == null) argb = 0xFFC9B5BE;
				else {
					int tu = Math.max(0, Math.min(63, (int) (w0 * u0 + w1 * u1 + w2 * u2 - 0.001f)));
					int tv = Math.max(0, Math.min(63, (int) (w0 * v0 + w1 * v1 + w2 * v2 - 0.001f)));
					argb = px[tv * 64 + tu];
				}
				if (alphaTest && (argb >>> 24) < 128) continue;
				if (!alphaTest) argb |= 0xFF000000;
				depth[i] = z;
				color[i] = 0xFF000000 | mul(argb, shade);
			}
		}
	}

	private static int mul(int c, float k) {
		int r = Math.min(255, (int) ((c >> 16 & 255) * k)), g = Math.min(255, (int) ((c >> 8 & 255) * k)), b = Math.min(255, (int) ((c & 255) * k));
		return r << 16 | g << 8 | b;
	}

	/* ---------------- Kyoto models (same meshes, poses and lighting as in game) ---------------- */

	private final Vector3f[] tp = {new Vector3f(), new Vector3f(), new Vector3f()};
	private final int[] tc = new int[3];

	private void mesh(Mesh3D.Model m, Matrix4f root, float[][] pose) {
		Matrix4f[] mats = new Matrix4f[m.parts.length];
		Vector3f nn = new Vector3f(), view = new Vector3f(0, 0, 1), half = new Vector3f();
		for (int i = 0; i < m.parts.length; i++) mats[i] = Mesh3D.partMatrix(m, i, root, pose, mats);
		for (int pi = 0; pi < m.parts.length; pi++) {
			Mesh3D.Part part = m.parts[pi];
			Matrix4f mt = mats[pi];
			for (int t = 0; t < part.idx.length; t += 3) {
				for (int k = 0; k < 3; k++) {
					int vi = part.idx[t + k];
					mt.transformPosition(part.v[vi * 3], part.v[vi * 3 + 1], part.v[vi * 3 + 2], tp[k]);
					mt.transformDirection(part.n[vi * 3], part.n[vi * 3 + 1], part.n[vi * 3 + 2], nn);
					float len = nn.length();
					if (len > 1e-6f) nn.div(len);
					float d = Math.max(0f, nn.dot(LIGHT));
					float rim = (float) Math.pow(1f - Math.abs(nn.dot(view)), 2.2);
					half.set(LIGHT).add(view).normalize();
					float spec = (float) Math.pow(Math.max(0f, nn.dot(half)), 24) * 0.16f;
					float k1 = 0.58f + 0.5f * d;
					int col = part.c[vi];
					int r = Math.min(255, Math.round((col >> 16 & 255) * k1 + 255 * (0.2f * rim + spec)));
					int g = Math.min(255, Math.round((col >> 8 & 255) * k1 + 235 * (0.2f * rim + spec)));
					int b = Math.min(255, Math.round((col & 255) * k1 + 242 * (0.2f * rim + spec)));
					tc[k] = r << 16 | g << 8 | b;
				}
				flat(tp[0], tp[1], tp[2], tc[0], tc[1], tc[2]);
			}
		}
	}

	/** A Gouraud-shaded triangle (no texture), both sides. */
	private void flat(Vector3f p0, Vector3f p1, Vector3f p2, int c0, int c1, int c2) {
		float x0 = cx + p0.x * scale, y0 = cy - p0.y * scale, x1 = cx + p1.x * scale, y1 = cy - p1.y * scale, x2 = cx + p2.x * scale, y2 = cy - p2.y * scale;
		float area = (x1 - x0) * (y2 - y0) - (x2 - x0) * (y1 - y0);
		if (Math.abs(area) < 1e-5f) return;
		int minX = Math.max(0, (int) Math.floor(Math.min(x0, Math.min(x1, x2)))), maxX = Math.min(W - 1, (int) Math.ceil(Math.max(x0, Math.max(x1, x2))));
		int minY = Math.max(0, (int) Math.floor(Math.min(y0, Math.min(y1, y2)))), maxY = Math.min(H - 1, (int) Math.ceil(Math.max(y0, Math.max(y1, y2))));
		for (int y = minY; y <= maxY; y++) {
			float sy = y + 0.5f;
			for (int x = minX; x <= maxX; x++) {
				float sx = x + 0.5f;
				float w0 = ((x1 - sx) * (y2 - sy) - (x2 - sx) * (y1 - sy)) / area;
				float w1 = ((x2 - sx) * (y0 - sy) - (x0 - sx) * (y2 - sy)) / area;
				float w2 = 1 - w0 - w1;
				if (w0 < -1e-4f || w1 < -1e-4f || w2 < -1e-4f) continue;
				float z = -(w0 * p0.z + w1 * p1.z + w2 * p2.z);
				int i = y * W + x;
				if (z >= depth[i]) continue;
				depth[i] = z;
				int r = (int) (w0 * (c0 >> 16 & 255) + w1 * (c1 >> 16 & 255) + w2 * (c2 >> 16 & 255));
				int g = (int) (w0 * (c0 >> 8 & 255) + w1 * (c1 >> 8 & 255) + w2 * (c2 >> 8 & 255));
				int b = (int) (w0 * (c0 & 255) + w1 * (c1 & 255) + w2 * (c2 & 255));
				color[i] = 0xFF000000 | Math.min(255, r) << 16 | Math.min(255, g) << 8 | Math.min(255, b);
			}
		}
	}
}
