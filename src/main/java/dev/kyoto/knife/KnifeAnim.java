package dev.kyoto.knife;

/**
 * Where your hand and knife are each frame: idle breathing, walk bob, look sway, the draw spin when you pull it out,
 * the slash when you swing, and a different inspect for each knife (butterfly flips, karambit spins on the finger ring,
 * flip knife folds and flicks open, the rest turn to show both sides).
 */
public final class KnifeAnim {
	private KnifeAnim() {}

	public static final class State {
		public float time;        // seconds, always running
		public float draw = 1;    // 0 = just pulled out .. 1 = done
		public float inspect = -1; // seconds into the inspect, or -1
		public float swing;       // 0..1 arm swing progress (0 = not swinging)
		public float bobX, bobY;  // walking bob
		public float swayX, swayY; // look lag
	}

	public static float inspectLength(String shape) {
		return switch (shape) {
			case "butterfly" -> 3.4f;
			case "karambit" -> 3.0f;
			case "flip" -> 2.8f;
			case "katana" -> 3.0f;
			default -> 2.6f;
		};
	}

	private static float ease(float t) {
		t = Math.max(0, Math.min(1, t));
		return t * t * (3 - 2 * t);
	}

	private static float easeOut(float t) {
		t = Math.max(0, Math.min(1, t));
		return 1 - (1 - t) * (1 - t) * (1 - t);
	}

	/** Smooth keyframes: times[] ascending, vals[][k]. */
	private static float key(float t, float[] times, float[] vals) {
		if (t <= times[0]) return vals[0];
		for (int i = 1; i < times.length; i++) {
			if (t <= times[i]) return vals[i - 1] + (vals[i] - vals[i - 1]) * ease((t - times[i - 1]) / (times[i] - times[i - 1]));
		}
		return vals[vals.length - 1];
	}

	/**
	 * Fills the knife's camera-space matrix, the arm's matrix (in skin pixels) and the per-part pose.
	 * Units: camera space in blocks; models in 1/16 block.
	 */
	public static void build(String shape, State s, M4 knife, M4 arm, float[][] pose) {
		final float D = (float) Math.PI / 180f;
		// resting hand: low right, knife leaning forward and left
		float hx = 0.34f, hy = -0.30f, hz = -0.72f, rx = -42 * D, ry = 22 * D, rz = 32 * D;
		float spinZ = 0, spinY = 0; // knife spins inside the hand
		float scale = shape.equals("katana") ? 0.75f : 1.3f;

		// idle breathing + walk + sway
		hx += (float) Math.sin(s.time * 1.3f) * 0.004f + s.bobX * 0.03f + s.swayX;
		hy += (float) Math.sin(s.time * 2.6f) * 0.005f + s.bobY * 0.025f + s.swayY;
		rz += s.bobX * 0.05f;

		// draw: rises from below while spinning into the grip
		if (s.draw < 1) {
			float d = easeOut(s.draw);
			hy -= (1 - d) * 0.45f;
			hx += (1 - d) * 0.12f;
			rx += (1 - d) * 50 * D;
			if (!shape.equals("katana")) spinZ += (1 - d) * (float) Math.PI * 2;
		}

		// swing: a fast diagonal slash
		if (s.swing > 0 && s.swing < 1) {
			float p = s.swing, a = (float) Math.sin(p * Math.PI);
			float sweep = p < 0.5f ? ease(p * 2) : 1 - ease((p - 0.5f) * 2);
			hx -= sweep * 0.28f;
			hy += a * 0.08f - sweep * 0.1f;
			hz -= a * 0.1f;
			rz += sweep * 55 * D;
			rx -= a * 30 * D;
			ry -= sweep * 25 * D;
		}

		// inspect
		float t = s.inspect;
		if (t >= 0) {
			float L = inspectLength(shape), u = t / L;
			// shared: bring the hand up and in, then back
			float lift = key(u, new float[]{0, 0.14f, 0.86f, 1}, new float[]{0, 1, 1, 0});
			hx -= lift * 0.12f;
			hy += lift * 0.1f;
			hz += lift * 0.08f;
			rx += lift * 18 * D;
			switch (shape) {
				case "karambit" -> {
					// two spins around the finger ring, then show the edge
					float sp = key(u, new float[]{0.1f, 0.45f, 0.6f, 0.95f}, new float[]{0, 2, 2, 2});
					spinZ += sp * (float) Math.PI * 2;
					rz += key(u, new float[]{0.45f, 0.6f, 0.8f, 0.95f}, new float[]{0, -40, -40, 0}) * D;
					ry += key(u, new float[]{0.45f, 0.6f, 0.8f, 0.95f}, new float[]{0, 70, 70, 0}) * D;
				}
				case "butterfly" -> {
					// flip it closed and open twice while spinning it round
					spinZ += key(u, new float[]{0.08f, 0.3f, 0.5f, 0.72f}, new float[]{0, 1, 1, 2}) * (float) Math.PI * 2;
					ry += key(u, new float[]{0.72f, 0.82f, 0.9f}, new float[]{0, 80, 0}) * D;
				}
				case "flip" -> {
					rz += key(u, new float[]{0.1f, 0.25f, 0.75f, 0.9f}, new float[]{0, -30, -30, 0}) * D;
					spinY += key(u, new float[]{0.55f, 0.75f}, new float[]{0, 1}) * (float) Math.PI;
				}
				case "katana" -> {
					// hold it out flat along the blade, then turn it
					rz += key(u, new float[]{0.1f, 0.3f, 0.7f, 0.9f}, new float[]{0, 75, 75, 0}) * D;
					ry += key(u, new float[]{0.3f, 0.5f, 0.6f, 0.75f}, new float[]{0, 35, 35, -10}) * D;
					spinY += key(u, new float[]{0.45f, 0.65f}, new float[]{0, 1}) * (float) Math.PI;
				}
				default -> {
					// show one side, roll to the other, a little toss
					rz += key(u, new float[]{0.1f, 0.28f, 0.72f, 0.9f}, new float[]{0, -55, -55, 0}) * D;
					ry += key(u, new float[]{0.1f, 0.28f, 0.5f, 0.72f, 0.9f}, new float[]{0, 40, 40, -30, 0}) * D;
					spinY += key(u, new float[]{0.42f, 0.62f}, new float[]{0, 1}) * (float) Math.PI;
				}
			}
		}

		// parts that move on their own
		java.util.Arrays.stream(pose).forEach(q -> java.util.Arrays.fill(q, 0));
		if (shape.equals("butterfly") && pose.length >= 3) {
			// closed = both handles folded over the blade
			float closed = 0;
			if (s.draw < 1) closed = 1 - ease(s.draw * 1.3f);
			if (t >= 0) {
				float u = t / inspectLength(shape);
				closed = Math.max(closed, key(u, new float[]{0.08f, 0.2f, 0.3f, 0.42f, 0.5f, 0.62f, 0.72f}, new float[]{0, 1, 0, 0, 1, 1, 0}));
			}
			pose[1][2] = closed * (float) Math.PI;
			pose[2][2] = -closed * (float) Math.PI;
		}
		if (shape.equals("flip") && pose.length >= 1) {
			float closed = 0;
			if (s.draw < 1) closed = 1 - ease(s.draw * 1.4f);
			if (t >= 0) {
				float u = t / inspectLength(shape);
				closed = Math.max(closed, key(u, new float[]{0.18f, 0.32f, 0.42f, 0.48f}, new float[]{0, 1, 1, 0}));
			}
			pose[0][2] = -closed * (float) Math.PI * 0.94f;
		}

		// hand frame
		M4 hand = new M4();
		hand.translate(hx, hy, hz).rotY(ry).rotX(rx).rotZ(rz);
		// knife in the hand (models are in 1/16 block)
		knife.m[0] = 1;
		M4 k = new M4(hand).scale(scale / 16f);
		float pivotY = shape.equals("karambit") ? -6.25f : 0f;
		k.translate(0, pivotY, 0).rotZ(spinZ).translate(0, -pivotY, 0).rotY(spinY);
		System.arraycopy(k.m, 0, knife.m, 0, 16);
		// arm: the fist wraps the grip, the forearm runs down-right and back out of view (skin pixels)
		float dx = 0.62f - lift(s) * 0.25f, dy = -0.62f, dz = 0.48f;
		float dl = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
		dx /= dl; dy /= dl; dz /= dl;
		float ux = 0, uy = 0, uz = -1; // roughly "forward" for the arm's front face
		float xx = uy * dz - uz * dy, xy = uz * dx - ux * dz, xz = ux * dy - uy * dx;
		float xl = (float) Math.sqrt(xx * xx + xy * xy + xz * xz);
		xx /= xl; xy /= xl; xz /= xl;
		float zx = xy * dz - xz * dy, zy = xz * dx - xx * dz, zz = xx * dy - xy * dx;
		float[] g = new float[3];
		k.point(0, shape.equals("katana") ? -5f : -2.6f, 0, g); // the grip on the knife
		M4 a = new M4();
		M4 basis = new M4();
		basis.m[0] = xx; basis.m[1] = xy; basis.m[2] = xz;
		basis.m[4] = dx; basis.m[5] = dy; basis.m[6] = dz;
		basis.m[8] = zx; basis.m[9] = zy; basis.m[10] = zz;
		basis.m[12] = g[0]; basis.m[13] = g[1]; basis.m[14] = g[2];
		M4 out = new M4(a).mul(basis).scale(0.8f / 16f).translate(0, -1.5f, 0);
		System.arraycopy(out.m, 0, arm.m, 0, 16);
	}

	private static float lift(State s) {
		if (s.inspect < 0) return 0;
		return 0.5f;
	}
}
