package dev.kyoto.knife;

import java.io.DataInputStream;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/** A knife model: parts (blade, handle, butterfly halves...) with smooth normals, finish coords and material roles. */
public final class KMesh {
	public static final class Part {
		public String name;
		public int parent;
		public float px, py, pz;
		public float[] v, n, uv;
		public byte[] role;
		public int[] idx;
	}

	public Part[] parts;
	public float minY = 1e9f, maxY = -1e9f;

	public int part(String name) {
		for (int i = 0; i < parts.length; i++) if (parts[i].name.equals(name)) return i;
		return -1;
	}

	/** A fresh pose: per part {rx, ry, rz, tx, ty, tz}. */
	public float[][] pose() {
		return new float[parts.length][6];
	}

	private static final Map<String, KMesh> CACHE = new HashMap<>();

	public static synchronized KMesh get(String shape) {
		if (CACHE.containsKey(shape)) return CACHE.get(shape);
		KMesh m = null;
		try (InputStream in = KMesh.class.getResourceAsStream("/assets/kyoto/knives3d/" + shape + ".kmesh")) {
			if (in != null) m = read(in);
		} catch (Exception ignored) {
		}
		CACHE.put(shape, m);
		return m;
	}

	public static KMesh read(InputStream raw) throws Exception {
		DataInputStream in = new DataInputStream(new java.io.BufferedInputStream(raw));
		byte[] magic = new byte[4];
		in.readFully(magic);
		KMesh k = new KMesh();
		int np = in.readInt();
		k.parts = new Part[np];
		for (int p = 0; p < np; p++) {
			Part pt = new Part();
			byte[] nm = new byte[in.readShort()];
			in.readFully(nm);
			pt.name = new String(nm, java.nio.charset.StandardCharsets.UTF_8);
			pt.parent = in.readInt();
			pt.px = in.readFloat(); pt.py = in.readFloat(); pt.pz = in.readFloat();
			int nv = in.readInt();
			pt.v = new float[nv * 3]; pt.n = new float[nv * 3]; pt.uv = new float[nv * 2]; pt.role = new byte[nv];
			for (int i = 0; i < nv; i++) {
				pt.v[i * 3] = in.readFloat(); pt.v[i * 3 + 1] = in.readFloat(); pt.v[i * 3 + 2] = in.readFloat();
				pt.n[i * 3] = in.readFloat(); pt.n[i * 3 + 1] = in.readFloat(); pt.n[i * 3 + 2] = in.readFloat();
				pt.uv[i * 2] = in.readFloat(); pt.uv[i * 2 + 1] = in.readFloat();
				pt.role[i] = in.readByte();
				k.minY = Math.min(k.minY, pt.v[i * 3 + 1]);
				k.maxY = Math.max(k.maxY, pt.v[i * 3 + 1]);
			}
			int nt = in.readInt();
			pt.idx = new int[nt * 3];
			for (int i = 0; i < nt * 3; i++) pt.idx[i] = in.readInt();
			k.parts[p] = pt;
		}
		return k;
	}

	/** Each part's matrix for a pose (parents first). */
	public M4[] matrices(M4 root, float[][] pose) {
		M4[] out = new M4[parts.length];
		for (int i = 0; i < parts.length; i++) out[i] = mat(i, root, pose, out);
		return out;
	}

	private M4 mat(int i, M4 root, float[][] pose, M4[] done) {
		if (done[i] != null) return done[i];
		Part p = parts[i];
		M4 base = p.parent >= 0 && p.parent != i ? mat(p.parent, root, pose, done) : root;
		M4 o = new M4(base);
		float[] q = pose == null ? null : pose[i];
		if (q != null && (q[0] != 0 || q[1] != 0 || q[2] != 0 || q[3] != 0 || q[4] != 0 || q[5] != 0)) {
			o.translate(p.px + q[3], p.py + q[4], p.pz + q[5]).rotY(q[1]).rotX(q[0]).rotZ(q[2]).translate(-p.px, -p.py, -p.pz);
		}
		done[i] = o;
		return o;
	}
}
