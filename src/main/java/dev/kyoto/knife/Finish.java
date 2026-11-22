package dev.kyoto.knife;

/**
 * Knife finishes, worked out per pixel: CS-style Fade, Doppler, Gamma, Crimson Web, Tiger, Night, Sakura, plus plain
 * steel in each Minecraft material for "Vanilla". Gives colour, how metallic and how rough each spot is.
 */
public final class Finish {
	public static final String[] IDS = {"vanilla", "sakura", "fade", "doppler", "gamma", "crimson", "tiger", "night", "gold", "diamond", "darkmatter", "damascus", "digital", "molten", "nebula", "plasma", "prism",
			"arctic", "woodland", "desert", "carbon", "casehardened", "slaughter", "sapphire", "ruby", "emerald", "blackpearl",
			"galaxy", "aurora", "inferno", "glitch", "hologram", "storm", "ocean", "rainbow", "toxic", "frost"};
	public static final String[] MATS = {"wooden", "stone", "copper", "iron", "golden", "diamond", "netherite"};

	/** Output of {@link #sample}: r,g,b 0..1, metal 0..1, rough 0..1. */
	public float r, g, b, metal, rough, glow, spark;

	private void set(int rgb, float metal, float rough) {
		r = (rgb >> 16 & 255) / 255f; g = (rgb >> 8 & 255) / 255f; b = (rgb & 255) / 255f;
		this.metal = metal; this.rough = rough; this.glow = 0; this.spark = 0;
	}

	private void mix(int rgb, float t) {
		t = Math.max(0, Math.min(1, t));
		r += ((rgb >> 16 & 255) / 255f - r) * t; g += ((rgb >> 8 & 255) / 255f - g) * t; b += ((rgb & 255) / 255f - b) * t;
	}

	// ---- noise ----
	private static float hash(int x, int y) {
		int h = x * 374761393 + y * 668265263;
		h = (h ^ (h >>> 13)) * 1274126177;
		return ((h ^ (h >>> 16)) & 0xFFFFFF) / (float) 0xFFFFFF;
	}

	static float noise(float x, float y) {
		int xi = (int) Math.floor(x), yi = (int) Math.floor(y);
		float fx = x - xi, fy = y - yi;
		fx = fx * fx * (3 - 2 * fx); fy = fy * fy * (3 - 2 * fy);
		float a = hash(xi, yi), b = hash(xi + 1, yi), c = hash(xi, yi + 1), d = hash(xi + 1, yi + 1);
		return a + (b - a) * fx + (c - a) * fy + (a - b - c + d) * fx * fy;
	}

	/** Noise that repeats every p along x, so moving camos can slide along the blade without a seam. */
	static float noiseP(float x, float y, int p) {
		int xi = (int) Math.floor(x), yi = (int) Math.floor(y);
		float fx = x - xi, fy = y - yi;
		fx = fx * fx * (3 - 2 * fx); fy = fy * fy * (3 - 2 * fy);
		int x0 = Math.floorMod(xi, p), x1 = Math.floorMod(xi + 1, p);
		float a = hash(x0, yi), b = hash(x1, yi), c = hash(x0, yi + 1), d = hash(x1, yi + 1);
		return a + (b - a) * fx + (c - a) * fy + (a - b - c + d) * fx * fy;
	}

	static float fbmP(float x, float y, int p) {
		float s = 0, a = 0.5f;
		for (int i = 0; i < 4; i++) { s += a * noiseP(x, y, p); x *= 2; y = y * 2.01f + 3.7f; p *= 2; a *= 0.5f; }
		return s / 0.9375f;
	}

	/**
	 * Moving camos: {slide along the blade per second, glow pulse, star twinkle, colour shift per second, electric flicker}.
	 * null = still.
	 */
	public static float[] anim(int fin) {
		// {slide, pulse, twinkle, colour shift, flicker, (unused), wave, glitch}
		return switch (fin) {
			case 10 -> new float[]{0.05f, 0.45f, 1f, 0f, 0f, 0, 0, 0};
			case 13 -> new float[]{0.035f, 0.35f, 0f, 0f, 0f, 0, 0, 0};
			case 14 -> new float[]{0.03f, 0.2f, 1f, 0f, 0f, 0, 0, 0};
			case 15 -> new float[]{0.22f, 0.2f, 0f, 0f, 1f, 0, 0, 0};
			case 16 -> new float[]{0.06f, 0f, 0.6f, 0.25f, 0f, 0, 0, 0};
			case 27 -> new float[]{0.02f, 0.15f, 1f, 0.03f, 0f, 0, 0.3f, 0};   // galaxy
			case 28 -> new float[]{0.04f, 0.3f, 0.4f, 0.08f, 0f, 0, 1f, 0};    // aurora
			case 29 -> new float[]{0.45f, 0.35f, 0f, 0f, 0.4f, 0, 0.6f, 0};    // inferno
			case 30 -> new float[]{0.0f, 0f, 0f, 0f, 0f, 0, 0, 1f};            // glitch
			case 31 -> new float[]{0.08f, 0.25f, 0f, 0.15f, 0f, 0, 0, 0.25f}; // hologram
			case 32 -> new float[]{0.1f, 0f, 0f, 0f, 1f, 0, 0, 0};            // storm
			case 33 -> new float[]{0.05f, 0.2f, 0.5f, 0f, 0f, 0, 1.4f, 0};     // ocean
			case 34 -> new float[]{0.1f, 0f, 0f, 0.5f, 0f, 0, 0, 0};           // rainbow
			case 35 -> new float[]{0.06f, 0.7f, 0.3f, 0f, 0f, 0, 0.5f, 0};     // toxic
			case 36 -> new float[]{0.015f, 0f, 1f, 0f, 0f, 0, 0, 0};           // frost
			default -> null;
		};
	}

	static float fbm(float x, float y) {
		float s = 0, a = 0.5f;
		for (int i = 0; i < 4; i++) { s += a * noise(x, y); x = x * 2.03f + 17.1f; y = y * 2.01f + 3.7f; a *= 0.5f; }
		return s / 0.9375f;
	}

	// ---- baked lookup tables, so drawing is a cheap texture read per pixel ----
	public static final int BU = 256, BV = 64;
	private static final java.util.Map<Integer, float[][]> BAKED = new java.util.HashMap<>();

	/** For finish+material: per role (0..4) an array of BU*BV*6 floats {r,g,b,metal,rough,glow}. */
	public static synchronized float[][] baked(int fin, int mat) {
		int key = fin * 16 + mat;
		float[][] b = BAKED.get(key);
		if (b != null) return b;
		Finish f = new Finish();
		b = new float[5][BU * BV * 7];
		for (int role = 0; role < 5; role++) {
			float[] t = b[role];
			for (int y = 0; y < BV; y++)
				for (int x = 0; x < BU; x++) {
					f.sample(fin, mat, role, (x + 0.5f) / BU, (y + 0.5f) / BV);
					int i = (y * BU + x) * 7;
					t[i] = f.r; t[i + 1] = f.g; t[i + 2] = f.b; t[i + 3] = f.metal; t[i + 4] = f.rough; t[i + 5] = f.glow; t[i + 6] = f.spark;
				}
		}
		BAKED.put(key, b);
		return b;
	}

	/** finish index, material index (for vanilla), role, finish coords u (along blade) v (across), and a seed. */
	public void sample(int fin, int mat, int role, float u, float v) {
		switch (role) {
			case 0 -> blade(fin, mat, u, v);
			case 2 -> fittings(fin, mat);
			case 3 -> cord(fin, u, v);
			case 4 -> scales(fin, mat, u, v);
			default -> handle(fin, mat, u, v);
		}
	}

	private void blade(int fin, int mat, float u, float v) {
		float x = u * 9, y = v * 2.2f;
		switch (fin) {
			case 1 -> { // sakura: mirror steel, pink at the spine, scattered blossoms
				set(0xE9E7EC, 0.95f, 0.14f);
				mix(0xF7A1BC, (0.45f - v) * 2.2f);
				float cx = x * 1.3f, cy = y * 1.6f;
				int ix = (int) Math.floor(cx), iy = (int) Math.floor(cy);
				float ox = cx - ix - 0.3f - hash(ix, iy) * 0.4f, oy = cy - iy - 0.3f - hash(iy, ix) * 0.4f;
				float d = (float) Math.sqrt(ox * ox + oy * oy), a = (float) Math.atan2(oy, ox);
				float petal = 0.22f + 0.1f * (float) Math.cos(a * 5 + hash(ix, iy) * 6);
				if (hash(ix * 3, iy * 7) > 0.45f && d < petal) { mix(d < 0.06f ? 0xF7C948 : 0xFF8DB4, 1); metal = 0.4f; rough = 0.3f; }
			}
			case 2 -> { // fade
				float t = Math.max(0, Math.min(1, u * 0.85f + v * 0.25f));
				int[] st = {0xF6D86B, 0xF4A04F, 0xEE5FA7, 0x8C5BE6, 0x4FB3EA};
				float p = t * (st.length - 1);
				int i = Math.min(st.length - 2, (int) p);
				set(st[i], 0.9f, 0.12f);
				mix(st[i + 1], p - i);
			}
			case 3 -> { // doppler
				float n = fbm(x * 0.8f + fbm(x * 0.5f, y) * 3, y * 1.3f);
				set(0x140E1A, 0.95f, 0.1f);
				mix(0x3A1E6E, n * 1.4f);
				mix(0xE0479E, (n - 0.5f) * 3.5f);
				mix(0x48A8F0, (fbm(x * 1.7f + 9, y * 2) - 0.62f) * 4);
			}
			case 4 -> { // gamma doppler
				float n = fbm(x * 0.8f + fbm(x * 0.5f + 4, y) * 3, y * 1.3f + 2);
				set(0x03140C, 0.95f, 0.1f);
				mix(0x0B6B3A, n * 1.5f);
				mix(0x2CF08A, (n - 0.52f) * 3.5f);
				mix(0xC8FF5A, (fbm(x * 1.9f, y * 2 + 5) - 0.66f) * 4);
			}
			case 5 -> { // crimson web
				set(0xB9BCC2, 0.95f, 0.22f);
				mix(0x8A1C26, 0.25f);
				float w1 = Math.abs((float) Math.sin((x * 1.1f + fbm(x, y) * 2.5f) * 2.2f));
				float w2 = Math.abs((float) Math.sin((y * 2.4f + fbm(y + 3, x) * 2f) * 2.0f));
				float line = Math.max(1 - w1 * 14, 1 - w2 * 14);
				if (line > 0) { mix(0xC0101E, Math.min(1, line * 1.5f)); rough = 0.35f; }
			}
			case 6 -> { // tiger tooth
				set(0xEDA43C, 0.92f, 0.16f);
				mix(0xFFD27A, (1 - v) * 0.4f);
				float s = (float) Math.sin(v * 5 + x * 2.1f + fbm(x * 0.7f, y) * 3.2f);
				if (s > 0.55f) mix(0x2B1A0D, Math.min(1, (s - 0.55f) * 6));
			}
			case 7 -> { // night
				set(0x2E3138, 0.75f, 0.42f);
				mix(0x51555F, (fbm(x * 0.6f, y * 3) - 0.45f) * 2);
			}
			case 8 -> { // gold camo: polished gold, fine brushed lines
				set(0xF5C84C, 1f, 0.1f);
				mix(0xFFE9A0, (float) Math.sin(y * 30 + noise(x * 2, y * 40) * 2) * 0.15f + 0.1f);
				mix(0xC9932A, (fbm(x * 0.8f, y * 1.5f) - 0.55f) * 0.8f);
			}
			case 9 -> { // diamond camo: icy facets that catch rainbow light
				float fx = x * 2.2f, fy = y * 3.5f;
				int ix = (int) Math.floor(fx), iy = (int) Math.floor(fy);
				float best = 9, h = 0;
				for (int a = -1; a <= 1; a++) for (int b2 = -1; b2 <= 1; b2++) {
					float px = ix + a + hash(ix + a, iy + b2), py = iy + b2 + hash(iy + b2, ix + a);
					float d = (px - fx) * (px - fx) + (py - fy) * (py - fy);
					if (d < best) { best = d; h = hash((ix + a) * 7, (iy + b2) * 13); }
				}
				set(0xE8F6FF, 0.85f, 0.04f);
				mix(0x9FD8FF, h * 0.6f);
				int[] rb = {0xFFB3D9, 0xB3FFF0, 0xFFF3B0, 0xC9B3FF};
				if (h > 0.78f) mix(rb[(int) (h * 37) & 3], 0.6f);
				glow = h > 0.93f ? 0.35f : 0;
			}
			case 10 -> { // dark matter (moves): black, drifting violet nebula, pulsing veins, twinkling stars
				float X = u * 8, n = fbmP(X + fbmP(X, y, 8) * 2.5f, y * 1.2f, 8);
				set(0x07050C, 0.9f, 0.15f);
				mix(0x2A0E5A, n * 1.2f);
				mix(0x5B2BD9, (n - 0.55f) * 3);
				float vein = 1 - Math.abs((float) Math.sin(fbmP(X, y * 1.4f + 9, 8) * 16)) * 4.5f;
				if (vein > 0) { mix(0xC86BFF, Math.min(1, vein)); glow = Math.min(1, vein) * 0.9f; rough = 0.3f; }
				if (noiseP(X * 4, y * 30, 32) > 0.9f) { mix(0xFFFFFF, 1); spark = 1; }
			}
			case 11 -> { // damascus: folded steel waves
				float w = (float) Math.sin(y * 7 + x * 1.2f + fbm(x * 0.6f, y * 1.2f) * 6 + (float) Math.sin(x * 2.4f) * 1.4f);
				set(0xC4C8CE, 0.95f, 0.22f);
				mix(0x4E535B, w * 0.5f + 0.35f);
			}
			case 12 -> { // digital: blocky pixel camo in Kyoto pinks
				int bx = (int) Math.floor(x * 3), by = (int) Math.floor(y * 6);
				float n = noise(bx * 0.45f, by * 0.45f) * 0.7f + hash(bx, by) * 0.3f;
				int c = n < 0.35f ? 0x1E1A22 : n < 0.55f ? 0x6B5A66 : n < 0.72f ? 0xE07AA0 : 0xF4D6E2;
				set(c, 0.35f, 0.5f);
			}
			case 13 -> { // molten (moves): black rock, lava flowing through the cracks
				float X = u * 8, n = fbmP(X, y * 1.6f, 8);
				set(0x161212, 0.2f, 0.85f);
				float crack = 1 - Math.abs((float) Math.sin(n * 15)) * 2.6f;
				if (crack > 0) { mix(crack > 0.6f ? 0xFFD25A : 0xFF5A1E, Math.min(1, crack * 1.4f)); glow = Math.min(1, crack); metal = 0; rough = 0.6f; }
				else mix(0x3A2A26, (fbmP(X * 3, y * 5, 24) - 0.5f) * 1.5f);
			}
			case 14 -> { // nebula (moves): deep space clouds in pink and blue, lots of stars
				float X = u * 8, w = fbmP(X, y * 1.3f, 8);
				float n = fbmP(X + w * 3, y * 1.6f + w, 8);
				set(0x05060F, 0.6f, 0.2f);
				mix(0x22155E, n * 1.4f);
				mix(0xE04F9E, (n - 0.55f) * 3.2f);
				mix(0x5FA8FF, (fbmP(X * 2 + 3, y * 3, 16) - 0.66f) * 3.5f);
				glow = Math.max(0, n - 0.6f) * 1.2f;
				float st = noiseP(X * 5, y * 36, 40);
				if (st > 0.88f) { mix(0xFFFFFF, 1); spark = (st - 0.88f) / 0.12f; }
			}
			case 15 -> { // plasma (moves): electric blue arcs that crackle down the blade
				float X = u * 8;
				set(0x070B1A, 0.85f, 0.18f);
				mix(0x10245A, fbmP(X, y * 2, 8));
				float arc = 1 - Math.abs((float) Math.sin(fbmP(X, y * 1.6f + 4, 8) * 9)) * 3.2f;
				float arc2 = 1 - Math.abs((float) Math.sin(fbmP(X * 2, y * 2 + 11, 16) * 7)) * 5;
				float e = Math.max(arc, arc2 * 0.8f);
				if (e > 0) { mix(e > 0.7f ? 0xF2FDFF : 0x3FD8FF, Math.min(1, e * 1.5f)); glow = Math.min(1, e); }
			}
			case 16 -> { // prism (moves): liquid chrome with rainbow that keeps shifting
				float X = u * 8, n = fbmP(X + fbmP(X, y, 8) * 2, y * 1.5f, 8);
				float h = n * 3;
				float rr = 0.5f + 0.5f * (float) Math.cos(6.2832f * (h + 0f)), gg = 0.5f + 0.5f * (float) Math.cos(6.2832f * (h + 0.33f)), bb = 0.5f + 0.5f * (float) Math.cos(6.2832f * (h + 0.67f));
				r = 0.3f + 0.7f * rr; g = 0.3f + 0.7f * gg; b = 0.3f + 0.7f * bb;
				metal = 1f; rough = 0.06f; glow = 0; spark = 0;
				if (noiseP(X * 4, y * 26, 32) > 0.94f) spark = 0.7f;
			}
			// ---------------- more camos ----------------
			case 17 -> { // arctic digital
				int bx = (int) Math.floor(x * 3), by = (int) Math.floor(y * 6);
				float n = noise(bx * 0.45f, by * 0.45f) * 0.7f + hash(bx, by) * 0.3f;
				set(n < 0.35f ? 0x8C9AA8 : n < 0.6f ? 0xD6E0E8 : n < 0.8f ? 0xF4F8FB : 0x5E6E7E, 0.3f, 0.5f);
			}
			case 18 -> blobs(x, y, 0x3E4A2A, 0x5E6B34, 0x2A2118, 0x8A7A4A);   // woodland
			case 19 -> blobs(x, y, 0xC9A66B, 0xA8834E, 0xE3CD97, 0x7A5C38);   // desert
			case 20 -> { // carbon fibre
				float wx = (float) Math.sin(x * 26) , wy = (float) Math.sin(y * 26);
				boolean a2 = ((int) Math.floor(x * 8.3f) + (int) Math.floor(y * 8.3f)) % 2 == 0;
				set(0x1A1B1F, 0.4f, 0.3f);
				mix(0x3A3D45, (a2 ? wx : wy) * 0.5f + 0.3f);
			}
			case 21 -> { // case hardened: blue and gold heat patina
				float n = fbm(x * 0.7f, y * 1.4f), m = fbm(x * 1.6f + 7, y * 2.5f);
				set(0xB7BBC2, 0.95f, 0.2f);
				mix(0x2C5DB8, (n - 0.4f) * 3);
				mix(0xD9A441, (m - 0.58f) * 4);
				mix(0x4A2E6E, (fbm(x * 2.5f + 3, y * 4) - 0.65f) * 4);
			}
			case 22 -> { // slaughter: pink-red swirls
				float n = fbm(x * 0.9f + fbm(x * 0.6f, y) * 3, y * 1.5f);
				set(0xB01A3A, 0.9f, 0.14f);
				mix(0xF08AA8, (n - 0.45f) * 3);
				mix(0x5A0A1A, (0.4f - n) * 3);
			}
			case 23 -> gem(x, y, 0x0A1A6A, 0x2A6AF0, 0x9FD0FF);   // sapphire
			case 24 -> gem(x, y, 0x5A0010, 0xE01030, 0xFF8090);   // ruby
			case 25 -> gem(x, y, 0x003A1E, 0x10C060, 0x9FFFC8);   // emerald
			case 26 -> gem(x, y, 0x0A0612, 0x3A2A7A, 0xB080FF);   // black pearl
			// moving
			case 27 -> { // galaxy: spiral arms full of stars
				float X = u * 8, n = fbmP(X * 0.5f + (float) Math.sin(y * 3) , y * 1.2f, 4);
				set(0x05030C, 0.7f, 0.15f);
				mix(0x3A1A7A, n * 1.3f);
				mix(0xFF7AC8, (n - 0.6f) * 3);
				mix(0x7AD8FF, (fbmP(X + 5, y * 2.2f, 8) - 0.66f) * 3.5f);
				glow = Math.max(0, n - 0.62f) * 1.5f;
				float st = noiseP(X * 6, y * 40, 48);
				if (st > 0.86f) { mix(0xFFFFFF, 1); spark = (st - 0.86f) / 0.14f; }
			}
			case 28 -> { // aurora: green and teal curtains over night steel
				float X = u * 8;
				set(0x0A1420, 0.8f, 0.2f);
				float band = (float) Math.sin(y * 6 + fbmP(X, y, 8) * 6);
				mix(0x2AF0A0, band * 0.8f);
				mix(0x7A4AF0, (fbmP(X * 2, y * 2 + 3, 16) - 0.6f) * 3);
				glow = Math.max(0, band) * 0.6f;
			}
			case 29 -> { // inferno: flames licking up the blade
				float X = u * 8, n = fbmP(X * 2, y * 2.5f, 16);
				float f = n + (1 - v) * 0.5f;
				set(0x1A0806, 0.3f, 0.6f);
				mix(0xC0200A, (f - 0.55f) * 3);
				mix(0xFF8A1A, (f - 0.75f) * 3);
				mix(0xFFE27A, (f - 0.95f) * 4);
				glow = Math.max(0, Math.min(1, (f - 0.6f) * 2));
			}
			case 30 -> { // glitch: neon on black, it corrupts as it moves
				int bx = (int) Math.floor(u * 24), by = (int) Math.floor(y * 5);
				float h = hash(bx, by);
				set(0x0B0B10, 0.6f, 0.3f);
				if (h > 0.7f) { mix(h > 0.86f ? 0xFF2A8A : 0x2AF0FF, 1); glow = 0.6f; }
				else if (h > 0.55f) mix(0x2A2A3A, 1);
			}
			case 31 -> { // hologram: shimmering iridescent with scan lines
				float n = fbmP(u * 8, y * 1.4f, 8), hh = n * 2 + u;
				r = 0.5f + 0.5f * (float) Math.cos(6.2832f * hh); g = 0.5f + 0.5f * (float) Math.cos(6.2832f * (hh + 0.33f)); b = 0.5f + 0.5f * (float) Math.cos(6.2832f * (hh + 0.67f));
				metal = 0.9f; rough = 0.08f; glow = 0; spark = 0;
				if (((int) (u * 120)) % 3 == 0) { r *= 0.5f; g *= 0.5f; b *= 0.5f; }
				glow = 0.25f;
			}
			case 32 -> { // storm: grey clouds, white-violet lightning
				float X = u * 8;
				set(0x2A2E38, 0.7f, 0.35f);
				mix(0x55606E, fbmP(X, y * 1.5f, 8));
				float bolt = 1 - Math.abs((float) Math.sin(fbmP(X * 1.5f, y * 2 + 7, 12) * 11)) * 4;
				if (bolt > 0) { mix(bolt > 0.6f ? 0xFFFFFF : 0xB89AFF, Math.min(1, bolt * 1.4f)); glow = Math.min(1, bolt); }
			}
			case 33 -> { // ocean: deep blue with rolling caustics
				float X = u * 8, n = fbmP(X, y * 2, 8);
				set(0x05264A, 0.85f, 0.12f);
				mix(0x0F6AA8, n * 1.4f);
				float c = 1 - Math.abs((float) Math.sin(fbmP(X * 2 + 3, y * 3, 16) * 14)) * 5;
				if (c > 0) { mix(0x9FF0FF, Math.min(1, c)); glow = c * 0.5f; }
			}
			case 34 -> { // rainbow: bright spectrum along the blade
				float hh = u * 1.5f + y * 0.15f;
				r = 0.5f + 0.5f * (float) Math.cos(6.2832f * hh); g = 0.5f + 0.5f * (float) Math.cos(6.2832f * (hh + 0.33f)); b = 0.5f + 0.5f * (float) Math.cos(6.2832f * (hh + 0.67f));
				metal = 0.9f; rough = 0.1f; glow = 0.2f; spark = 0;
			}
			case 35 -> { // toxic: bubbling green ooze
				float X = u * 8;
				set(0x0A1A08, 0.4f, 0.5f);
				float cx = X * 1.5f, cy = y * 3;
				int ix = (int) Math.floor(cx), iy = (int) Math.floor(cy);
				float dx = cx - ix - 0.5f, dy = cy - iy - 0.5f, d = (float) Math.sqrt(dx * dx + dy * dy);
				float rad = 0.15f + 0.25f * hash(Math.floorMod(ix, 12), iy);
				float n = fbmP(X, y * 1.5f, 8);
				mix(0x3ACF1A, n * 1.3f);
				if (d < rad) { mix(0xC8FF3A, 1 - d / rad * 0.5f); glow = 0.8f * (1 - d / rad); }
				else glow = Math.max(0, n - 0.6f);
			}
			case 36 -> { // frost: ice crystals that glint
				float X = u * 8, n = fbmP(X * 1.5f, y * 2.5f, 12);
				set(0xBFE4F5, 0.8f, 0.15f);
				mix(0x6AAED6, (0.5f - n) * 2);
				float cr = 1 - Math.abs((float) Math.sin(n * 25)) * 6;
				if (cr > 0) mix(0xFFFFFF, cr);
				if (noiseP(X * 5, y * 32, 40) > 0.9f) spark = 0.9f;
			}
			default -> vanilla(mat, x, y);
		}
	}

	/** Classic camo blobs: 4 colours in layered noise. */
	private void blobs(float x, float y, int c0, int c1, int c2, int c3) {
		float a = fbm(x * 0.6f, y * 1.4f), b = fbm(x * 0.9f + 9, y * 2 + 4), c = fbm(x * 1.4f + 2, y * 3 + 8);
		set(c0, 0.2f, 0.65f);
		if (a > 0.55f) set(c1, 0.2f, 0.65f);
		if (b > 0.6f) set(c2, 0.2f, 0.65f);
		if (c > 0.65f) set(c3, 0.2f, 0.65f);
	}

	/** Gem doppler: deep colour with bright swirls of the same stone. */
	private void gem(float x, float y, int deep, int mid, int light) {
		float n = fbm(x * 0.8f + fbm(x * 0.5f, y) * 3, y * 1.3f);
		set(deep, 0.95f, 0.08f);
		mix(mid, n * 1.4f);
		mix(light, (n - 0.62f) * 4);
	}

	private void vanilla(int mat, float x, float y) {
		switch (mat) {
			case 0 -> { set(0xB48855, 0.05f, 0.65f); float gr = (float) Math.sin(y * 9 + fbm(x * 0.4f, y * 2) * 6); mix(0x7A5530, gr * 0.5f + 0.25f); }
			case 1 -> { set(0x8F9094, 0.12f, 0.75f); mix(0x5E5F63, (fbm(x * 3, y * 6) - 0.4f) * 2.2f); mix(0xB8B9BD, (noise(x * 14, y * 18) - 0.75f) * 4); }
			case 2 -> { set(0xD9825A, 0.85f, 0.3f); mix(0xF0A47C, 0.3f - y * 0.1f); mix(0x5FB59A, (fbm(x * 1.3f, y * 2.5f) - 0.6f) * 4); }
			case 4 -> { set(0xF3C75A, 1f, 0.16f); mix(0xFFF0A8, (0.5f - y / 2.2f) * 0.6f); }
			case 5 -> { set(0x6FE3DA, 0.55f, 0.06f); mix(0xD6FFFB, (noise(x * 4, y * 4) - 0.7f) * 4); }
			case 6 -> { set(0x4B4348, 0.85f, 0.35f); mix(0x6A5A64, (fbm(x, y * 3) - 0.5f) * 1.5f); }
			default -> { set(0xD9DDE2, 1f, 0.16f); }
		}
	}

	private void handle(int fin, int mat, float u, float v) {
		switch (fin) {
			case 1 -> set(0xF2B3C8, 0f, 0.55f);
			case 5 -> set(0x2A1517, 0f, 0.6f);
			case 6 -> set(0x3B2617, 0.05f, 0.5f);
			case 8 -> set(0x2A2016, 0.1f, 0.5f);
			case 9 -> set(0xDDE8F0, 0.2f, 0.35f);
			case 10, 13, 14, 15, 27, 28, 29, 30, 32, 33, 35 -> set(0x0E0B12, 0f, 0.55f);
			case 18, 19 -> set(0x3A3424, 0f, 0.7f);
			case 17, 36 -> set(0xE6EEF4, 0f, 0.55f);
			case 16 -> set(0xD9DEE6, 0.6f, 0.25f);
			case 12 -> { int bx = (int) Math.floor(u * 20), by = (int) Math.floor(v * 4); set(hash(bx, by) > 0.5f ? 0x2A2430 : 0x6B5A66, 0f, 0.6f); }
			case 0 -> {
				int[] c = {0x6E4A2A, 0x4E4F53, 0x8D4C2D, 0x34353B, 0x3B2C1A, 0x2B5C64, 0x231E22};
				set(c[Math.max(0, Math.min(6, mat))], 0.05f, 0.6f);
				if (mat == 0) mix(0x4A2F18, (float) Math.sin(u * 60 + fbm(u * 8, v * 3) * 5) * 0.5f + 0.3f);
			}
			default -> set(0x1F1D23, 0f, 0.62f);
		}
		mix(0x000000, (noise(u * 120, v * 40) - 0.5f) * 0.15f); // grip texture
	}

	private void scales(int fin, int mat, float u, float v) {
		if ((fin >= 2 && fin <= 4) || fin >= 8) { blade(fin, mat, u * 0.6f, v); metal *= 0.5f; rough = Math.max(rough, 0.3f); return; }
		handle(fin, mat, u, v);
		mix(0xFFFFFF, (fbm(u * 30, v * 6) - 0.55f) * 0.2f); // G10 layers
	}

	private void fittings(int fin, int mat) {
		if (fin == 6 || fin == 8 || (fin == 0 && mat == 4)) set(0xF0C25A, 1f, 0.18f);
		else if (fin == 9) set(0xE8F6FF, 1f, 0.08f);
		else if (fin == 10 || fin >= 13) set(0x2A2530, 0.9f, 0.3f);
		else if (fin == 1) set(0xE8AFA6, 1f, 0.18f);
		else if (fin == 7 || (fin == 0 && mat == 6)) set(0x3C3A40, 0.9f, 0.3f);
		else if (fin == 0 && mat == 2) set(0xD9825A, 0.9f, 0.25f);
		else set(0xC3C8D0, 1f, 0.2f);
	}

	private void cord(int fin, float u, float v) {
		set(fin == 1 ? 0xF7A1BC : fin == 5 ? 0x9E1626 : fin == 6 ? 0x3B2617 : 0x241C24, 0f, 0.8f);
		float w = (float) Math.sin(u * 140) * (float) Math.sin(v * 25);
		mix(0x000000, -w * 0.25f);
		mix(0xFFFFFF, w * 0.12f);
	}
}
