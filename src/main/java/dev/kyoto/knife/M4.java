package dev.kyoto.knife;

/** A tiny 4x4 matrix (column-major), so the knife renderer needs nothing from Minecraft. */
public final class M4 {
	public final float[] m = new float[16];

	public M4() {
		m[0] = m[5] = m[10] = m[15] = 1;
	}

	public M4(M4 o) {
		System.arraycopy(o.m, 0, m, 0, 16);
	}

	public M4 mul(M4 b) {
		float[] a = m.clone(), r = m;
		for (int c = 0; c < 4; c++)
			for (int rr = 0; rr < 4; rr++)
				r[c * 4 + rr] = a[rr] * b.m[c * 4] + a[4 + rr] * b.m[c * 4 + 1] + a[8 + rr] * b.m[c * 4 + 2] + a[12 + rr] * b.m[c * 4 + 3];
		return this;
	}

	public M4 translate(float x, float y, float z) {
		M4 t = new M4();
		t.m[12] = x; t.m[13] = y; t.m[14] = z;
		return mul(t);
	}

	public M4 scale(float x, float y, float z) {
		M4 t = new M4();
		t.m[0] = x; t.m[5] = y; t.m[10] = z;
		return mul(t);
	}

	public M4 scale(float s) {
		return scale(s, s, s);
	}

	public M4 rotX(float a) {
		if (a == 0) return this;
		M4 t = new M4();
		float c = (float) Math.cos(a), s = (float) Math.sin(a);
		t.m[5] = c; t.m[6] = s; t.m[9] = -s; t.m[10] = c;
		return mul(t);
	}

	public M4 rotY(float a) {
		if (a == 0) return this;
		M4 t = new M4();
		float c = (float) Math.cos(a), s = (float) Math.sin(a);
		t.m[0] = c; t.m[2] = -s; t.m[8] = s; t.m[10] = c;
		return mul(t);
	}

	public M4 rotZ(float a) {
		if (a == 0) return this;
		M4 t = new M4();
		float c = (float) Math.cos(a), s = (float) Math.sin(a);
		t.m[0] = c; t.m[1] = s; t.m[4] = -s; t.m[5] = c;
		return mul(t);
	}

	public void point(float x, float y, float z, float[] out) {
		out[0] = m[0] * x + m[4] * y + m[8] * z + m[12];
		out[1] = m[1] * x + m[5] * y + m[9] * z + m[13];
		out[2] = m[2] * x + m[6] * y + m[10] * z + m[14];
	}

	public void dir(float x, float y, float z, float[] out) {
		out[0] = m[0] * x + m[4] * y + m[8] * z;
		out[1] = m[1] * x + m[5] * y + m[9] * z;
		out[2] = m[2] * x + m[6] * y + m[10] * z;
	}
}
