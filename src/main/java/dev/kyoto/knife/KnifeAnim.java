package dev.kyoto.knife;

/**
 * Knife animation, done like a game viewmodel: everything is smooth keyframes (Catmull-Rom curves, so motion flows
 * through each pose instead of stopping), with anticipation, overshoot and settle.
 *  - idle breathing, walk bob in a figure-8, springy look sway, a dip when you land
 *  - draw: each knife has its own (the butterfly rolls open, the flip knife flicks out, the karambit spins in)
 *  - attacks: a 3-hit combo (forehand, backhand, stab) with a hit-stop shake when you actually hit something
 *  - inspect: a full routine per knife. The butterfly does a real set: rollover close, zen open, fan, aerial, chaplin,
 *    and a snap open.
 */
public final class KnifeAnim {
	private KnifeAnim() {}

	public static final class State {
		public float time;          // seconds, always running
		public float draw = 1;      // 0 = just pulled out .. 1 = done
		public float inspect = -1;  // seconds into the inspect, or -1
		public float swing;         // 0..1 attack progress (0 = none)
		public int combo;           // which attack in the combo (0 forehand, 1 backhand, 2 stab)
		public float hit = -1;      // seconds since the attack connected, or -1
		public float bobX, bobY;    // walking bob
		public float swayX, swayY;  // look lag (springy)
		public float land;          // 0..1 landing dip
	}

	/* channels: hand x y z (blocks), hand rot x y z (deg), knife spin x y z (deg), handle A, handle B (deg), fold 0..1 */
	private static final int HX = 0, HY = 1, HZ = 2, RX = 3, RY = 4, RZ = 5, SX = 6, SY = 7, SZ = 8, HA = 9, HB = 10, FOLD = 11, N = 12;

	public static float inspectLength(String shape) {
		float[][] k = inspectKeys(shape);
		return k[k.length - 1][0];
	}

	// ------------------------------------------------------------ curves
	private static float cr(float p0, float p1, float p2, float p3, float t) {
		float t2 = t * t, t3 = t2 * t;
		return 0.5f * ((2 * p1) + (-p0 + p2) * t + (2 * p0 - 5 * p1 + 4 * p2 - p3) * t2 + (-p0 + 3 * p1 - 3 * p2 + p3) * t3);
	}

	/** Samples keyframe rows {time, c0..c11} with Catmull-Rom per channel. */
	private static void sample(float[][] keys, float t, float[] out) {
		int n = keys.length;
		if (t <= keys[0][0]) { System.arraycopy(keys[0], 1, out, 0, N); return; }
		if (t >= keys[n - 1][0]) { System.arraycopy(keys[n - 1], 1, out, 0, N); return; }
		int i = 0;
		while (i < n - 2 && t > keys[i + 1][0]) i++;
		float[] a = keys[Math.max(0, i - 1)], b = keys[i], c = keys[i + 1], d = keys[Math.min(n - 1, i + 2)];
		float u = (t - b[0]) / (c[0] - b[0]);
		for (int ch = 0; ch < N; ch++) out[ch] = cr(a[ch + 1], b[ch + 1], c[ch + 1], d[ch + 1], u);
	}

	private static float[] k(float t, float hx, float hy, float hz, float rx, float ry, float rz, float sx, float sy, float sz, float ha, float hb, float fold) {
		return new float[]{t, hx, hy, hz, rx, ry, rz, sx, sy, sz, ha, hb, fold};
	}

	private static final float[] REST = new float[N];

	private static float easeOut(float t) {
		t = Math.max(0, Math.min(1, t));
		return 1 - (1 - t) * (1 - t) * (1 - t);
	}

	private static float smooth(float t) {
		t = Math.max(0, Math.min(1, t));
		return t * t * (3 - 2 * t);
	}

	// ------------------------------------------------------------ inspects (offsets from rest)
	private static final java.util.Map<String, float[][]> INSPECT = new java.util.HashMap<>();

	static float[][] inspectKeys(String shape) {
		return INSPECT.computeIfAbsent(shape, KnifeAnim::buildInspect);
	}

	private static float[][] buildInspect(String shape) {
		return switch (shape) {
			case "butterfly" -> new float[][]{
					// the full set
					k(0.00f, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0),
					k(0.30f, -0.07f, 0.06f, 0.05f, 12, 10, -12, 0, 0, 0, 0, 0, 0),           // bring it up
					// rollover close: knife rolls over the hand, latch handle chases the blade
					k(0.55f, -0.08f, 0.07f, 0.05f, 14, 12, -16, 0, 0, -200, 120, 0, 0),
					k(0.78f, -0.08f, 0.065f, 0.05f, 12, 12, -14, 0, 0, -360, 180, -180, 0),
					// zen rollover: opens back up the other way
					k(1.02f, -0.075f, 0.07f, 0.05f, 10, 14, -10, 0, 0, -540, 120, -40, 0),
					k(1.25f, -0.07f, 0.07f, 0.05f, 10, 12, -12, 0, 0, -720, 0, 0, 0),
					// fan: blade whips round the finger, wrist rolls with it
					k(1.45f, -0.06f, 0.08f, 0.04f, 4, 24, -6, 0, 0, -900, 20, -15, 0),
					k(1.65f, -0.06f, 0.08f, 0.04f, 4, 30, -4, 0, 0, -1080, 0, 0, 0),
					// aerial: toss it, it tumbles end over end with the handles flaring, catch
					k(1.80f, -0.05f, 0.03f, 0.04f, 18, 18, -8, 0, 0, -1080, 0, 0, 0),
					k(2.05f, -0.06f, 0.24f, 0.0f, -6, 20, -8, 200, 0, -1080, 110, -70, 0),
					k(2.30f, -0.07f, 0.17f, 0.02f, 4, 16, -8, 360, 0, -1080, 40, -150, 0),
					k(2.45f, -0.07f, 0.06f, 0.04f, 16, 14, -10, 360, 0, -1080, 0, 0, 0),
					// chaplin: over the back of the hand, handles folding shut
					k(2.75f, -0.08f, 0.07f, 0.05f, 12, 40, -16, 360, 180, -1080, 90, -60, 0),
					k(3.05f, -0.08f, 0.07f, 0.05f, 12, 55, -18, 360, 360, -1080, 180, -180, 0),
					// show it closed
					k(3.45f, -0.16f, 0.11f, 0.08f, 24, 42, -26, 360, 360, -1080, 180, -180, 0),
					k(3.75f, -0.16f, 0.11f, 0.08f, 22, 38, -24, 360, 360, -1080, 180, -180, 0),
					// snap open with a flourish
					k(3.95f, -0.08f, 0.10f, 0.05f, 8, 20, -10, 360, 360, -1260, 100, -180, 0),
					k(4.20f, -0.07f, 0.08f, 0.04f, 10, 10, -12, 360, 360, -1440, 0, 0, 0),
					k(4.32f, -0.07f, 0.085f, 0.04f, 13, 11, -15, 360, 360, -1452, -8, 6, 0),  // overshoot
					k(4.80f, 0, 0, 0, 0, 0, 0, 360, 360, -1440, 0, 0, 0)};
			case "karambit" -> new float[][]{
					k(0.00f, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0),
					k(0.30f, -0.07f, 0.07f, 0.05f, 10, 8, -6, 0, 0, 0, 0, 0, 0),
					k(0.42f, -0.07f, 0.06f, 0.05f, 8, 8, -4, 0, 0, 30, 0, 0, 0),             // wind up
					k(0.80f, -0.07f, 0.08f, 0.05f, 10, 8, -8, 0, 0, -360, 0, 0, 0),          // spin on the ring
					k(1.15f, -0.07f, 0.08f, 0.05f, 10, 8, -8, 0, 0, -720, 0, 0, 0),          // and again
					k(1.30f, -0.07f, 0.075f, 0.05f, 10, 8, -8, 0, 0, -735, 0, 0, 0),
					k(1.70f, -0.07f, 0.08f, 0.05f, 10, 8, -8, 0, 0, -360, 0, 0, 0),          // reverse spin
					k(2.10f, -0.10f, 0.10f, 0.08f, 18, 65, -30, 0, 0, -360, 0, 0, 0),        // show the edge
					k(2.55f, -0.10f, 0.10f, 0.08f, 14, 72, -26, 0, 0, -360, 0, 0, 0),
					k(2.85f, -0.08f, 0.08f, 0.05f, 8, -20, -8, 0, 0, -360, 0, 0, 0),         // flick back
					k(3.30f, 0, 0, 0, 0, 0, 0, 0, 0, -360, 0, 0, 0)};
			case "flip" -> new float[][]{
					k(0.00f, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0),
					k(0.30f, -0.07f, 0.06f, 0.05f, 12, 10, -10, 0, 0, 0, 0, 0, 0),
					k(0.60f, -0.08f, 0.07f, 0.06f, 14, 30, -14, 0, 0, 0, 0, 0, 1),           // fold it shut
					k(0.90f, -0.08f, 0.07f, 0.06f, 14, 36, -16, 0, 0, 0, 0, 0, 1),
					k(1.05f, -0.07f, 0.10f, 0.04f, 4, 20, -10, 0, 0, 0, 0, 0, 1),            // flick...
					k(1.17f, -0.07f, 0.08f, 0.04f, 8, 16, -10, 0, 0, 0, 0, 0, -0.06f),       // ...open, past
					k(1.30f, -0.07f, 0.08f, 0.04f, 10, 14, -12, 0, 0, 0, 0, 0, 0),
					k(1.55f, -0.07f, 0.16f, 0.03f, 0, 14, -12, 0, 0, -360, 0, 0, 0),         // toss + spin
					k(1.80f, -0.07f, 0.07f, 0.05f, 12, 14, -12, 0, 0, -360, 0, 0, 0),
					k(2.20f, -0.10f, 0.09f, 0.08f, 18, 64, -28, 0, 0, -360, 0, 0, 0),
					k(2.60f, -0.10f, 0.09f, 0.08f, 16, 70, -24, 0, 180, -360, 0, 0, 0),      // turn it over
					k(3.10f, 0, 0, 0, 0, 0, 0, 0, 360, -360, 0, 0, 0)};
			case "kunai" -> new float[][]{
					k(0.00f, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0),
					k(0.30f, -0.07f, 0.06f, 0.05f, 10, 8, -8, 0, 0, 0, 0, 0, 0),
					k(0.75f, -0.07f, 0.07f, 0.05f, 10, 8, -8, 0, 0, -360, 0, 0, 0),
					k(1.10f, -0.07f, 0.07f, 0.05f, 10, 8, -8, 0, 0, -720, 0, 0, 0),
					k(1.22f, -0.07f, 0.065f, 0.05f, 10, 8, -8, 0, 0, -732, 0, 0, 0),
					k(1.60f, -0.06f, 0.18f, 0.0f, -4, 12, -8, 360, 0, -720, 0, 0, 0),        // toss, flips end over end
					k(1.85f, -0.07f, 0.07f, 0.05f, 12, 10, -8, 360, 0, -720, 0, 0, 0),
					k(2.30f, -0.10f, 0.09f, 0.08f, 18, 64, -26, 360, 0, -720, 0, 0, 0),
					k(2.80f, 0, 0, 0, 0, 0, 0, 360, 0, -720, 0, 0, 0)};
			case "katana" -> new float[][]{
					k(0.00f, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0),
					k(0.45f, -0.12f, 0.05f, 0.02f, 8, 20, 60, 0, 0, 0, 0, 0, 0),             // lay it flat along the view
					k(1.10f, -0.14f, 0.06f, 0.02f, 6, 30, 72, 0, 0, 0, 0, 0, 0),
					k(1.50f, -0.14f, 0.06f, 0.02f, 6, 30, 72, 0, 180, 0, 0, 0, 0),           // turn the blade
					k(2.10f, -0.13f, 0.06f, 0.02f, 8, 24, 66, 0, 180, 0, 0, 0, 0),
					k(2.45f, -0.04f, 0.02f, 0.0f, -6, -8, 10, 0, 180, 0, 0, 0, 0),           // flick the blood off
					k(3.00f, 0, 0, 0, 0, 0, 0, 0, 360, 0, 0, 0, 0)};
			default -> new float[][]{ // bayonet, gut, huntsman
					k(0.00f, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0),
					k(0.35f, -0.09f, 0.08f, 0.07f, 16, 52, -24, 0, 0, 0, 0, 0, 0),           // show the face
					k(0.85f, -0.09f, 0.085f, 0.07f, 14, 58, -22, 0, 0, 0, 0, 0, 0),
					k(1.25f, -0.09f, 0.08f, 0.07f, 14, 58, -22, 0, 180, 0, 0, 0, 0),         // roll to the other side
					k(1.70f, -0.09f, 0.08f, 0.07f, 12, 54, -20, 0, 180, 0, 0, 0, 0),
					k(1.85f, -0.07f, 0.05f, 0.05f, 16, 20, -10, 0, 180, 0, 0, 0, 0),
					k(2.15f, -0.07f, 0.17f, 0.03f, 0, 16, -8, 0, 180, -360, 0, 0, 0),        // toss + spin, catch
					k(2.35f, -0.07f, 0.06f, 0.05f, 14, 16, -10, 0, 180, -360, 0, 0, 0),
					k(2.43f, -0.07f, 0.045f, 0.05f, 12, 16, -10, 0, 180, -360, 0, 0, 0),     // catch weight
					k(2.90f, 0, 0, 0, 0, 0, 0, 0, 360, -360, 0, 0, 0)};
		};
	}

	// ------------------------------------------------------------ attacks
	private static final float[][][] ATTACK = {
			{ // forehand: cock back up-right, slash down to the left, recover
					k(0.00f, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0),
					k(0.18f, 0.05f, 0.06f, 0.03f, -10, -10, -18, 0, 0, 0, 0, 0, 0),
					k(0.45f, -0.30f, -0.12f, -0.08f, 30, 20, 60, 0, 0, 0, 0, 0, 0),
					k(0.62f, -0.34f, -0.15f, -0.06f, 34, 22, 66, 0, 0, 0, 0, 0, 0),
					k(1.00f, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)},
			{ // backhand: across from the left, low to high
					k(0.00f, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0),
					k(0.18f, -0.22f, -0.06f, 0.02f, 10, 14, 40, 0, 0, 0, 0, 0, 0),
					k(0.48f, 0.10f, 0.08f, -0.08f, -20, -26, -40, 0, 0, 0, 0, 0, 0),
					k(0.64f, 0.12f, 0.09f, -0.06f, -22, -28, -44, 0, 0, 0, 0, 0, 0),
					k(1.00f, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)},
			{ // stab: pull back, punch forward
					k(0.00f, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0),
					k(0.20f, 0.02f, -0.02f, 0.08f, 10, 0, 0, 0, 0, 0, 0, 0, 0),
					k(0.42f, -0.16f, 0.02f, -0.30f, -48, -10, 6, 0, 0, 0, 0, 0, 0),
					k(0.58f, -0.16f, 0.02f, -0.28f, -46, -10, 6, 0, 0, 0, 0, 0, 0),
					k(1.00f, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)}};

	// ------------------------------------------------------------ draws
	private static float[][] drawKeys(String shape) {
		return switch (shape) {
			case "butterfly" -> new float[][]{
					k(0.00f, 0.10f, -0.42f, 0.10f, 50, 0, 20, 0, 0, 0, 180, -180, 0),
					k(0.35f, -0.02f, -0.04f, 0.0f, 10, 6, -6, 0, 0, -200, 160, -60, 0),
					k(0.62f, -0.01f, 0.01f, 0.0f, 2, 2, -2, 0, 0, -360, 10, -6, 0),
					k(0.72f, 0, 0.005f, 0, -2, 0, 2, 0, 0, -365, -6, 4, 0),
					k(1.00f, 0, 0, 0, 0, 0, 0, 0, 0, -360, 0, 0, 0)};
			case "flip" -> new float[][]{
					k(0.00f, 0.10f, -0.42f, 0.10f, 50, 0, 20, 0, 0, 0, 0, 0, 1),
					k(0.45f, 0, -0.02f, 0, 6, 4, -4, 0, 0, 0, 0, 0, 1),
					k(0.62f, 0, 0.01f, 0, -2, 0, 0, 0, 0, 0, 0, 0, -0.06f),
					k(1.00f, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)};
			case "katana" -> new float[][]{
					k(0.00f, 0.25f, -0.35f, 0.15f, 40, -30, -40, 0, 0, 0, 0, 0, 0),
					k(0.55f, -0.02f, 0.01f, 0, -4, 2, 4, 0, 0, 0, 0, 0, 0),
					k(1.00f, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)};
			default -> new float[][]{
					k(0.00f, 0.10f, -0.42f, 0.10f, 50, 0, 20, 0, 0, 0, 0, 0, 0),
					k(0.55f, -0.01f, 0.01f, 0, 4, 2, -2, 0, 0, -350, 0, 0, 0),
					k(0.70f, 0, 0.004f, 0, -2, 0, 1, 0, 0, -362, 0, 0, 0),
					k(1.00f, 0, 0, 0, 0, 0, 0, 0, 0, -360, 0, 0, 0)};
		};
	}

	// ------------------------------------------------------------ build the frame
	public static void build(String shape, State s, M4 knife, M4 arm, float[][] pose) {
		final float D = (float) Math.PI / 180f;
		float[] c = new float[N], tmp = new float[N];
		// rest: low right like a game viewmodel, blade leaning forward-left
		float hx = 0.36f, hy = -0.25f, hz = -0.70f, rx = -36, ry = 20, rz = 28;
		float scale = shape.equals("katana") ? 0.5f : 0.88f;

		// idle: breathing + a slow drift
		hx += (float) Math.sin(s.time * 0.9f) * 0.003f;
		hy += (float) Math.sin(s.time * 1.8f) * 0.004f;
		rz += (float) Math.sin(s.time * 0.7f) * 0.8f;
		// walk: figure-8 bob
		hx += s.bobX * 0.022f;
		hy += s.bobY * 0.02f;
		rz += s.bobX * 2.2f;
		rx += s.bobY * 2f;
		// look sway (spring from the view model) + landing dip
		hx += s.swayX;
		hy += s.swayY;
		ry += s.swayX * 60;
		rx -= s.swayY * 60;
		hy -= s.land * 0.05f;
		rx += s.land * 6;

		if (s.draw < 1) { sample(drawKeys(shape), s.draw, tmp); add(c, tmp); }
		if (s.inspect >= 0) {
			sample(inspectKeys(shape), s.inspect, tmp);
			add(c, tmp);
			// bring the whole thing up into the middle of the view while the tricks happen, then back down
			float L = inspectLength(shape), env = smooth(s.inspect / 0.35f) * smooth((L - s.inspect) / 0.45f);
			c[HX] -= 0.13f * env;
			c[HY] += 0.19f * env;
			c[HZ] += 0.03f * env;
			c[RX] += 14 * env;
			c[RY] -= 8 * env;
		}
		if (s.swing > 0 && s.swing < 1) {
			boolean stabs = shape.equals("kunai") || shape.equals("bayonet") || shape.equals("karambit");
			float[][] keys = ATTACK[stabs ? s.combo % 3 : s.combo % 2];
			sample(keys, s.swing, tmp);
			add(c, tmp);
		}
		// hit-stop: a short, sharp shake when the attack connects
		if (s.hit >= 0 && s.hit < 0.22f) {
			float h = 1 - s.hit / 0.22f, w = (float) Math.sin(s.hit * 95);
			c[HX] += w * 0.012f * h;
			c[HY] += w * 0.008f * h;
			c[RZ] += w * 5 * h;
			c[RX] -= 4 * h;
		}

		hx += c[HX]; hy += c[HY]; hz += c[HZ];
		rx += c[RX]; ry += c[RY]; rz += c[RZ];

		java.util.Arrays.stream(pose).forEach(q -> java.util.Arrays.fill(q, 0));
		if (shape.equals("butterfly") && pose.length >= 3) {
			pose[1][2] = c[HA] * D;
			pose[2][2] = c[HB] * D;
		}
		if (shape.equals("flip") && pose.length >= 1) pose[0][2] = -c[FOLD] * (float) Math.PI * 0.94f;

		M4 hand = new M4().translate(hx, hy, hz).rotY(ry * D).rotX(rx * D).rotZ(rz * D);
		// the knife turns around where your fingers are (the ring for karambit/kunai)
		float pivotY = switch (shape) {
			case "karambit" -> -6.25f;
			case "kunai" -> -6.6f;
			case "butterfly" -> -0.6f;
			case "katana" -> -4f;
			default -> -2.2f;
		};
		M4 k = new M4(hand).scale(scale / 16f);
		k.translate(0, pivotY, 0).rotZ(c[SZ] * D).rotX(c[SX] * D).rotY(c[SY] * D).translate(0, -pivotY, 0);
		System.arraycopy(k.m, 0, knife.m, 0, 16);

		// arm: the fist stays on the hand (not the spinning knife); forearm runs down-right and back out of view
		float[] g = new float[3];
		new M4(hand).scale(scale / 16f).point(0, shape.equals("katana") ? -5f : -2.6f, 0, g);
		float dx = 0.62f + c[HX] * 0.8f, dy = -0.62f, dz = 0.48f;
		float dl = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
		dx /= dl; dy /= dl; dz /= dl;
		float ux = 0, uy = 0, uz = -1;
		float xx = uy * dz - uz * dy, xy = uz * dx - ux * dz, xz = ux * dy - uy * dx;
		float xl = (float) Math.sqrt(xx * xx + xy * xy + xz * xz);
		xx /= xl; xy /= xl; xz /= xl;
		float zx = xy * dz - xz * dy, zy = xz * dx - xx * dz, zz = xx * dy - xy * dx;
		M4 basis = new M4();
		basis.m[0] = xx; basis.m[1] = xy; basis.m[2] = xz;
		basis.m[4] = dx; basis.m[5] = dy; basis.m[6] = dz;
		basis.m[8] = zx; basis.m[9] = zy; basis.m[10] = zz;
		basis.m[12] = g[0]; basis.m[13] = g[1]; basis.m[14] = g[2];
		M4 out = new M4(basis).rotY(c[RZ] * D * 0.5f).scale(0.62f / 16f).translate(0, -1.5f, 0);
		System.arraycopy(out.m, 0, arm.m, 0, 16);
	}

	/** For the menu preview: applies the inspect's spins and moving parts to a knife already placed in view. */
	public static void previewInspect(String shape, float t, M4 knife, float[][] pose) {
		final float D = (float) Math.PI / 180f;
		float[] c = new float[N];
		sample(inspectKeys(shape), t, c);
		java.util.Arrays.stream(pose).forEach(q -> java.util.Arrays.fill(q, 0));
		if (shape.equals("butterfly") && pose.length >= 3) { pose[1][2] = c[HA] * D; pose[2][2] = c[HB] * D; }
		if (shape.equals("flip") && pose.length >= 1) pose[0][2] = -c[FOLD] * (float) Math.PI * 0.94f;
		float pivotY = shape.equals("karambit") ? -6.25f : shape.equals("kunai") ? -6.6f : shape.equals("butterfly") ? -0.6f : -2.2f;
		knife.translate(0, pivotY, 0).rotZ(c[SZ] * D).rotX(c[SX] * D).rotY(c[SY] * D).translate(0, -pivotY, 0);
		knife.translate(0, c[HY] * 16f, 0);
	}

	/** True while the knife should be drawn over the hand (tricks pass in front of the fingers). */
	public static boolean knifeOnTop(State s) {
		return s.inspect >= 0.2f || s.draw < 0.6f;
	}

	private static void add(float[] a, float[] b) {
		for (int i = 0; i < N; i++) a[i] += b[i];
	}
}
