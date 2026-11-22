package dev.kyoto.knife;

/**
 * Draws a knife (and your arm, from your skin) with real perspective, per-pixel lighting and smooth edges.
 * Pure Java on the CPU, so it looks the same on every PC and Minecraft version.
 * Pass 1 rasterises at SSxSS samples into a small G-buffer (depth, normal, finish coords); pass 2 lights each screen
 * pixel once and uses the sample coverage for smooth edges.
 */
public final class KnifeRaster {
	public final int W, H, SS;
	private final int iw, ih;
	private final float[] depth, gnx, gny, gnz, gu, gv;
	private final byte[] grole;
	private final int[] gcol;
	public final byte[] rgba;
	/** Camera: focal length and centre in OUTPUT pixels. Camera looks down -z, +y up. */
	public float focal, cx, cy;
	/** World light 0..1 (dark caves make the knife darker too). */
	public float light = 1f;
	/** Seconds, for moving camos. */
	public float time = 0f;
	private float[] anim;
	private float[][] baked;
	private int minX, minY, maxX, maxY; // dirty box (samples)

	public KnifeRaster(int w, int h, int ss) {
		W = w; H = h; SS = ss;
		iw = w * ss; ih = h * ss;
		int n = iw * ih;
		depth = new float[n]; gnx = new float[n]; gny = new float[n]; gnz = new float[n]; gu = new float[n]; gv = new float[n];
		grole = new byte[n]; gcol = new int[n];
		rgba = new byte[w * h * 4];
		java.util.Arrays.fill(depth, Float.POSITIVE_INFINITY);
		java.util.Arrays.fill(grole, (byte) -1);
		minX = 0; minY = 0; maxX = iw - 1; maxY = ih - 1;
	}

	/** Clears only what the last frame touched. */
	public void clear() {
		if (maxX >= minX) {
			for (int y = minY; y <= maxY; y++) {
				int r = y * iw;
				java.util.Arrays.fill(depth, r + minX, r + maxX + 1, Float.POSITIVE_INFINITY);
				java.util.Arrays.fill(grole, r + minX, r + maxX + 1, (byte) -1);
			}
		}
		minX = iw; minY = ih; maxX = -1; maxY = -1;
	}

	private boolean proj(float[] v, float[] out) {
		float z = -v[2];
		if (z < 0.02f) return false;
		float f = focal * SS;
		out[0] = cx * SS + f * v[0] / z;
		out[1] = cy * SS - f * v[1] / z;
		out[2] = z;
		return true;
	}

	// ---------------- knife ----------------
	public void knife(KMesh k, M4 root, float[][] pose, int fin, int mat) {
		if (k == null) return;
		baked = Finish.baked(fin, mat);
		anim = Finish.anim(fin);
		M4[] ms = k.matrices(root, pose);
		float[] a = new float[3], na = new float[3], Q = new float[3];
		for (int pi = 0; pi < k.parts.length; pi++) {
			KMesh.Part p = k.parts[pi];
			M4 m = ms[pi];
			int nv = p.v.length / 3;
			float[] tn = new float[nv * 3], ts = new float[nv * 3];
			boolean[] ok = new boolean[nv];
			for (int i = 0; i < nv; i++) {
				m.point(p.v[i * 3], p.v[i * 3 + 1], p.v[i * 3 + 2], a);
				m.dir(p.n[i * 3], p.n[i * 3 + 1], p.n[i * 3 + 2], na);
				float l = (float) Math.sqrt(na[0] * na[0] + na[1] * na[1] + na[2] * na[2]);
				if (l > 1e-6f) { na[0] /= l; na[1] /= l; na[2] /= l; }
				tn[i * 3] = na[0]; tn[i * 3 + 1] = na[1]; tn[i * 3 + 2] = na[2];
				ok[i] = proj(a, Q);
				ts[i * 3] = Q[0]; ts[i * 3 + 1] = Q[1]; ts[i * 3 + 2] = Q[2];
			}
			for (int t = 0; t < p.idx.length; t += 3) {
				int i0 = p.idx[t], i1 = p.idx[t + 1], i2 = p.idx[t + 2];
				if (!ok[i0] || !ok[i1] || !ok[i2]) continue;
				tri(ts, tn, p.uv, i0, i1, i2, p.role[i0], null, false);
			}
		}
	}

	// ---------------- arm (Minecraft skin box) ----------------
	public void skinBox(int[] skin, M4 m, float x, float y, float z, float w, float h, float d, int u, int v, float inflate) {
		float x0 = x - inflate, y0 = y - inflate, z0 = z - inflate, x1 = x + w + inflate, y1 = y + h + inflate, z1 = z + d + inflate;
		int W2 = (int) w, H2 = (int) h, D2 = (int) d;
		boolean outer = inflate > 0;
		face(skin, m, outer, x0, y1, z1, x1, y1, z1, x1, y0, z1, x0, y0, z1, u + D2, v + D2, W2, H2, 0, 0, 1);
		face(skin, m, outer, x1, y1, z0, x0, y1, z0, x0, y0, z0, x1, y0, z0, u + D2 + W2 + D2, v + D2, W2, H2, 0, 0, -1);
		face(skin, m, outer, x0, y1, z0, x0, y1, z1, x0, y0, z1, x0, y0, z0, u, v + D2, D2, H2, -1, 0, 0);
		face(skin, m, outer, x1, y1, z1, x1, y1, z0, x1, y0, z0, x1, y0, z1, u + D2 + W2, v + D2, D2, H2, 1, 0, 0);
		face(skin, m, outer, x0, y1, z0, x1, y1, z0, x1, y1, z1, x0, y1, z1, u + D2, v, W2, D2, 0, 1, 0);
		face(skin, m, outer, x0, y0, z1, x1, y0, z1, x1, y0, z0, x0, y0, z0, u + D2 + W2, v, W2, D2, 0, -1, 0);
	}

	private final float[] fs = new float[12], fn = new float[12], fuv = new float[8];

	private void face(int[] skin, M4 m, boolean outer, float ax, float ay, float az, float bx, float by, float bz, float cx2, float cy2, float cz,
					  float dx, float dy, float dz, int u, int v, int uw, int vh, float nx, float ny, float nz) {
		float[] P = new float[3], S = new float[3], n = new float[3];
		float[][] src = {{ax, ay, az}, {bx, by, bz}, {cx2, cy2, cz}, {dx, dy, dz}};
		for (int i = 0; i < 4; i++) {
			m.point(src[i][0], src[i][1], src[i][2], P);
			if (!proj(P, S)) return;
			fs[i * 3] = S[0]; fs[i * 3 + 1] = S[1]; fs[i * 3 + 2] = S[2];
		}
		m.dir(nx, ny, nz, n);
		float l = (float) Math.sqrt(n[0] * n[0] + n[1] * n[1] + n[2] * n[2]);
		for (int i = 0; i < 4; i++) { fn[i * 3] = n[0] / l; fn[i * 3 + 1] = n[1] / l; fn[i * 3 + 2] = n[2] / l; }
		fuv[0] = u; fuv[1] = v; fuv[2] = u + uw; fuv[3] = v; fuv[4] = u + uw; fuv[5] = v + vh; fuv[6] = u; fuv[7] = v + vh;
		tri(fs, fn, fuv, 0, 1, 2, (byte) 9, skin, outer);
		tri(fs, fn, fuv, 0, 2, 3, (byte) 9, skin, outer);
	}

	// ---------------- pass 1: rasterise into the G-buffer ----------------
	private void tri(float[] s, float[] n, float[] uv, int a, int b, int c, byte role, int[] skin, boolean alphaTest) {
		float x0 = s[a * 3], y0 = s[a * 3 + 1], x1 = s[b * 3], y1 = s[b * 3 + 1], x2 = s[c * 3], y2 = s[c * 3 + 1];
		float area = (x1 - x0) * (y2 - y0) - (x2 - x0) * (y1 - y0);
		if (Math.abs(area) < 1e-6f) return;
		int bx0 = Math.max(0, (int) Math.floor(Math.min(x0, Math.min(x1, x2)))), bx1 = Math.min(iw - 1, (int) Math.ceil(Math.max(x0, Math.max(x1, x2))));
		int by0 = Math.max(0, (int) Math.floor(Math.min(y0, Math.min(y1, y2)))), by1 = Math.min(ih - 1, (int) Math.ceil(Math.max(y0, Math.max(y1, y2))));
		if (bx0 > bx1 || by0 > by1) return;
		float inv = 1 / area;
		float iz0 = 1 / s[a * 3 + 2], iz1 = 1 / s[b * 3 + 2], iz2 = 1 / s[c * 3 + 2];
		boolean any = false;
		// per row: work out exactly where the triangle starts and ends instead of testing the whole box
		float b0 = (y1 - y2) * inv, b1 = (y2 - y0) * inv;
		for (int y = by0; y <= by1; y++) {
			float sy = y + 0.5f;
			float a0 = (x1 * (y2 - sy) - x2 * (y1 - sy)) * inv, a1 = (x2 * (y0 - sy) - x0 * (y2 - sy)) * inv;
			float a2 = 1 - a0 - a1, b2 = -b0 - b1;
			float lo = bx0, hi = bx1 + 1;
			final float E = -1e-5f;
			if (b0 > 0) lo = Math.max(lo, (E - a0) / b0 - 0.5f); else if (b0 < 0) hi = Math.min(hi, (E - a0) / b0 - 0.5f); else if (a0 < E) continue;
			if (b1 > 0) lo = Math.max(lo, (E - a1) / b1 - 0.5f); else if (b1 < 0) hi = Math.min(hi, (E - a1) / b1 - 0.5f); else if (a1 < E) continue;
			if (b2 > 0) lo = Math.max(lo, (E - a2) / b2 - 0.5f); else if (b2 < 0) hi = Math.min(hi, (E - a2) / b2 - 0.5f); else if (a2 < E) continue;
			int xs = Math.max(bx0, (int) Math.ceil(lo)), xe = Math.min(bx1, (int) Math.floor(hi));
			for (int x = xs; x <= xe; x++) {
				float sx = x + 0.5f;
				float w0 = a0 + b0 * sx, w1 = a1 + b1 * sx, w2 = 1 - w0 - w1;
				if (w0 < E || w1 < E || w2 < E) continue;
				float iz = w0 * iz0 + w1 * iz1 + w2 * iz2;
				float z = 1 / iz;
				int i = y * iw + x;
				if (z >= depth[i]) continue;
				float p0 = w0 * iz0 * z, p1 = w1 * iz1 * z, p2 = w2 * iz2 * z;
				float u = p0 * uv[a * 2] + p1 * uv[b * 2] + p2 * uv[c * 2], v = p0 * uv[a * 2 + 1] + p1 * uv[b * 2 + 1] + p2 * uv[c * 2 + 1];
				if (role == 9) {
					int tu = Math.max(0, Math.min(63, (int) (u - 0.001f))), tv = Math.max(0, Math.min(63, (int) (v - 0.001f)));
					int argb = skin == null ? 0xFFC99A7C : skin[tv * 64 + tu];
					if (alphaTest && (argb >>> 24) < 128) continue;
					gcol[i] = argb;
				}
				depth[i] = z;
				gnx[i] = p0 * n[a * 3] + p1 * n[b * 3] + p2 * n[c * 3];
				gny[i] = p0 * n[a * 3 + 1] + p1 * n[b * 3 + 1] + p2 * n[c * 3 + 1];
				gnz[i] = p0 * n[a * 3 + 2] + p1 * n[b * 3 + 2] + p2 * n[c * 3 + 2];
				gu[i] = u; gv[i] = v;
				grole[i] = role;
				any = true;
			}
		}
		if (any) {
			minX = Math.min(minX, bx0); maxX = Math.max(maxX, bx1);
			minY = Math.min(minY, by0); maxY = Math.max(maxY, by1);
		}
	}

	// ---------------- pass 2: light each screen pixel once ----------------
	private static final float L1X, L1Y, L1Z, L2X, L2Y, L2Z;

	static {
		float l = (float) Math.sqrt(0.45 * 0.45 + 0.75 * 0.75 + 0.5 * 0.5);
		L1X = -0.45f / l; L1Y = 0.75f / l; L1Z = 0.5f / l;
		float m = (float) Math.sqrt(0.7 * 0.7 + 0.2 * 0.2 + 0.4 * 0.4);
		L2X = 0.7f / m; L2Y = 0.2f / m; L2Z = -0.4f / m;
	}

	public byte[] resolve() {
		java.util.Arrays.fill(rgba, (byte) 0);
		if (maxX < minX) return rgba;
		int n = SS * SS;
		float f = focal * SS;
		int ox0 = minX / SS, ox1 = Math.min(W - 1, maxX / SS), oy0 = minY / SS, oy1 = Math.min(H - 1, maxY / SS);
		for (int y = oy0; y <= oy1; y++) {
			for (int x = ox0; x <= ox1; x++) {
				int cov = 0, best = -1;
				float bd = Float.POSITIVE_INFINITY;
				int sr = 0, sg = 0, sb = 0, skins = 0;
				for (int sy = 0; sy < SS; sy++) {
					int row = (y * SS + sy) * iw + x * SS;
					for (int sx = 0; sx < SS; sx++) {
						int i = row + sx;
						if (grole[i] < 0) continue;
						cov++;
						if (depth[i] < bd) { bd = depth[i]; best = i; }
					}
				}
				if (best < 0) continue;
				int c = shadeSample(best, f);
				int j = (y * W + x) * 4;
				rgba[j] = (byte) (c >> 16); rgba[j + 1] = (byte) (c >> 8); rgba[j + 2] = (byte) c; rgba[j + 3] = (byte) (cov * 255 / n);
			}
		}
		return rgba;
	}

	private int shadeSample(int i, float f) {
		int sx = i % iw, sy = i / iw;
		float vx = -((sx + 0.5f) - cx * SS) / f, vy = ((sy + 0.5f) - cy * SS) / f, vz = 1f; // towards the eye
		float vl = (float) Math.sqrt(vx * vx + vy * vy + vz * vz);
		vx /= vl; vy /= vl; vz /= vl;
		float nx = gnx[i], ny = gny[i], nz = gnz[i];
		float nl = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
		if (nl < 1e-6f) { nx = 0; ny = 0; nz = 1; } else { nx /= nl; ny /= nl; nz /= nl; }
		if (nx * vx + ny * vy + nz * vz < 0) { nx = -nx; ny = -ny; nz = -nz; }
		int role = grole[i];
		if (role == 9) {
			int argb = gcol[i];
			float d = Math.max(0, nx * L1X + ny * L1Y + nz * L1Z);
			float k = (0.55f + 0.5f * d) * light;
			return pack((argb >> 16 & 255) / 255f * k, (argb >> 8 & 255) / 255f * k, (argb & 255) / 255f * k);
		}
		float[] t = baked[Math.max(0, Math.min(4, role))];
		float u = gu[i], v = gv[i];
		boolean moving = anim != null && (role == 0 || role == 4);
		if (moving) u += time * anim[0];
		int tx = (int) ((u - (float) Math.floor(u)) * Finish.BU), ty = (int) (Math.max(0, Math.min(0.999f, v)) * Finish.BV);
		int ti = (Math.min(ty, Finish.BV - 1) * Finish.BU + Math.min(tx, Finish.BU - 1)) * 7;
		float r = t[ti], g = t[ti + 1], b = t[ti + 2], glow = t[ti + 5], spark = t[ti + 6];
		if (moving) {
			float uu = gu[i];
			if (anim[1] > 0) glow *= 1 - anim[1] * (0.5f + 0.5f * (float) Math.sin(time * 2.4f + uu * 14));
			if (anim[4] > 0) { // electric flicker
				float fl = (float) Math.sin(time * 37 + uu * 55) * (float) Math.sin(time * 23 - v * 31);
				glow *= Math.min(1f, 0.55f + 1.8f * fl * fl);
			}
			if (anim[3] > 0) { // colour shift: turn the hue around the grey axis
				float ang = (time * anim[3] + uu * 0.6f) * 6.2832f, cs = (float) Math.cos(ang), sn = (float) Math.sin(ang);
				float k = (1 - cs) / 3f, q = 0.57735f * sn;
				float nr = r * (cs + k) + g * (k - q) + b * (k + q);
				float ng = r * (k + q) + g * (cs + k) + b * (k - q);
				float nb = r * (k - q) + g * (k + q) + b * (cs + k);
				r = Math.max(0, nr); g = Math.max(0, ng); b = Math.max(0, nb);
			}
			if (spark > 0 && anim[2] > 0) { // stars twinkle on their own
				float ph = ((tx * 73 + ty * 151) & 255) / 40.7f;
				float tw = 0.5f + 0.5f * (float) Math.sin(time * 4.5f + ph);
				glow = Math.max(glow, spark * tw * anim[2]);
			}
		} else if (spark > 0) glow = Math.max(glow, spark * 0.7f);
		return shade(r, g, b, t[ti + 3], t[ti + 4], glow, nx, ny, nz, vx, vy, vz);
	}

	private static float soft(float x) { // ~exp(-x) for x >= 0
		return 1f / (1f + x + x * x * 0.5f + x * x * x * 0.1667f);
	}

	private int shade(float r, float g, float b, float metal, float rough, float glow, float nx, float ny, float nz, float vx, float vy, float vz) {
		float ndv = Math.max(0, nx * vx + ny * vy + nz * vz);
		float rx = 2 * ndv * nx - vx, ry = 2 * ndv * ny - vy;
		float sharp = 1f / (0.02f + rough * rough * 1.6f);
		float env = ry > 0 ? 0.55f + 0.45f * ry : 0.5f + 0.38f * ry;
		float ax = rx + 0.35f, ay = ry - 0.55f, bx = rx - 0.6f, by = ry - 0.1f;
		env += 1.6f * soft((ax * ax + ay * ay) * sharp * 0.8f);
		env += 0.9f * soft((bx * bx + by * by * 4) * sharp * 0.8f);
		env = env * (1 - rough * 0.5f) + 0.45f * rough;
		float d1 = Math.max(0, nx * L1X + ny * L1Y + nz * L1Z), d2 = Math.max(0, nx * L2X + ny * L2Y + nz * L2Z);
		float diff = 0.32f + 0.62f * d1 + 0.22f * d2;
		float hx = L1X + vx, hy = L1Y + vy, hz = L1Z + vz, hl = (float) Math.sqrt(hx * hx + hy * hy + hz * hz);
		float nh = Math.max(0, (nx * hx + ny * hy + nz * hz) / hl);
		float pw = 8 + (1 - rough) * 160;
		float spec = nh / (pw - pw * nh + nh) * (1.2f - rough) * 1.3f; // Schlick's cheap power
		float q = 1 - ndv, q2 = q * q;
		float fres = 0.04f + 0.96f * q2 * q2 * q;
		float dm = 1 - metal, fe = dm * fres * env * 0.5f;
		float kr = r * diff * dm + metal * r * env + fe + spec * (metal > 0.5f ? (0.6f + 0.4f * r) : 1);
		float kg = g * diff * dm + metal * g * env + fe + spec * (metal > 0.5f ? (0.6f + 0.4f * g) : 1);
		float kb = b * diff * dm + metal * b * env + fe + spec * (metal > 0.5f ? (0.6f + 0.4f * b) : 1);
		float lt = 0.25f + 0.75f * light;
		// glowing bits (dark matter veins, lava) don't need light
		return pack(kr * lt * (1 - glow) + r * glow * 1.6f, kg * lt * (1 - glow) + g * glow * 1.6f, kb * lt * (1 - glow) + b * glow * 1.6f);
	}

	private static int pack(float r, float g, float b) {
		r = r / (1 + r * 0.18f) * 1.18f; g = g / (1 + g * 0.18f) * 1.18f; b = b / (1 + b * 0.18f) * 1.18f;
		return 0xFF000000 | c8(r) << 16 | c8(g) << 8 | c8(b);
	}

	private static int c8(float v) {
		return Math.max(0, Math.min(255, (int) (v * 255)));
	}
}
