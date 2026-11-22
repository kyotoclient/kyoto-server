package dev.kyoto.feature;

import dev.kyoto.extra.Nearby;
import dev.kyoto.module.Modules;
import dev.kyoto.util.Compat;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Dance emotes. They animate the real player model (arms, legs, head, body, a bounce and spins), loop smoothly and
 * stop as soon as you move. Kyoto players near you see your dance and you see theirs.
 */
public final class Dances {
	private Dances() {}

	/*
	 * Each emote is a looping set of keyframes, written as "phase: channel=degrees ...". Phase is 0..1 of one loop.
	 * Channels: hx hy hz (head), bx by bz (body), rax ray raz / lax lay laz (arms), rlx rlz / llx llz (legs),
	 * up (bounce, pixels), sh (hip shift sideways, pixels), spin (body turn, degrees).
	 * Played back with Catmull-Rom curves so the motion flows, eased in and out when it starts and stops.
	 */
	private record Emote(String id, String name, String icon, boolean dance, float loop, boolean repeat, String keys) {}

	private static final Emote[] E = {
			// ---------------- dances ----------------
			new Emote("floss", "Floss", "sparkle", true, 0.8f, true,
					"0: rax=25 lax=-25 raz=40 laz=40 sh=-1 bz=-4 up=0 | .25: up=.6 | .5: rax=-25 lax=25 raz=-40 laz=-40 sh=1 bz=4 up=0 | .75: up=.6 | 1: rax=25 lax=-25 raz=40 laz=40 sh=-1 bz=-4"),
			new Emote("groove", "Groove", "music", true, 0.9f, true,
					"0: up=0 hx=10 rax=-30 lax=-50 raz=20 laz=-20 rlx=10 llx=-10 by=8 | .25: up=1.3 hx=-6 | .5: up=0 hx=10 rax=-50 lax=-30 rlx=-10 llx=10 by=-8 | .75: up=1.3 hx=-6 | 1: up=0 hx=10 rax=-30 lax=-50 raz=20 laz=-20 rlx=10 llx=-10 by=8"),
			new Emote("robot", "Robot", "cpu", true, 2.0f, true,
					"0: rax=-90 lax=0 hy=0 spin=0 | .2: rax=-90 lax=0 hy=0 | .25: rax=0 lax=-90 hy=30 spin=22 | .45: rax=0 lax=-90 hy=30 spin=22 | .5: rax=-90 lax=-90 hy=0 spin=0 up=.5 | .7: rax=-90 lax=-90 up=.5 | .75: rax=-45 lax=-135 hy=-30 spin=-22 | .95: rax=-45 lax=-135 hy=-30 spin=-22 | 1: rax=-90 lax=0 hy=0 spin=0"),
			new Emote("hype", "Hype", "bolt", true, 0.6f, true,
					"0: rax=-165 lax=-155 raz=20 laz=-20 up=0 hx=8 | .5: rax=-155 lax=-165 up=2.4 hx=-8 rlx=-12 llx=-12 | 1: rax=-165 lax=-155 raz=20 laz=-20 up=0 hx=8"),
			new Emote("shuffle", "Shuffle", "trail", true, 0.6f, true,
					"0: rlx=-35 llx=15 rax=25 lax=-25 sh=-1 up=.4 by=-10 | .5: rlx=15 llx=-35 rax=-25 lax=25 sh=1 up=.4 by=10 | .25: up=1 | .75: up=1 | 1: rlx=-35 llx=15 rax=25 lax=-25 sh=-1 up=.4 by=-10"),
			new Emote("spin", "Spin", "aura", true, 1.2f, true,
					"0: raz=85 laz=-85 rax=-10 lax=-10 hx=-8 spin=0 up=.4 | .25: raz=95 laz=-75 up=1 spin=90 | .5: raz=85 laz=-85 up=.4 spin=180 | .75: raz=75 laz=-95 up=1 spin=270 | 1: raz=85 laz=-85 rax=-10 lax=-10 hx=-8 spin=360 up=.4"),
			new Emote("disco", "Disco", "star", true, 1.0f, true,
					"0: rax=-20 raz=150 laz=-35 lax=-20 sh=-1.5 hz=-6 bz=-4 | .5: rax=-40 ray=-30 raz=-15 laz=-35 sh=1.5 hz=6 bz=4 | .25: up=.8 | .75: up=.8 | 1: rax=-20 raz=150 laz=-35 lax=-20 sh=-1.5 hz=-6 bz=-4"),
			new Emote("griddy", "Griddy", "trail", true, 0.5f, true,
					"0: bx=18 hx=-14 rax=40 lax=-50 rlx=-30 llx=10 up=.6 | .5: bx=18 hx=-14 rax=-50 lax=40 rlx=10 llx=-30 up=.6 | .25: up=1.4 | .75: up=1.4 | 1: bx=18 hx=-14 rax=40 lax=-50 rlx=-30 llx=10 up=.6"),
			new Emote("headbang", "Headbang", "music", true, 0.5f, true,
					"0: hx=-12 bx=0 rax=-45 raz=15 lax=-60 lay=35 | .45: hx=45 bx=14 rax=-30 lax=-55 up=.3 | 1: hx=-12 bx=0 rax=-45 raz=15 lax=-60 lay=35"),
			new Emote("airguitar", "Air Guitar", "music", true, 0.45f, true,
					"0: lax=-65 lay=40 laz=-10 rax=-55 ray=-10 raz=25 hx=10 hz=6 rlx=-8 | .5: rax=-20 ray=10 raz=35 hx=-4 hz=-4 up=.5 | 1: lax=-65 lay=40 laz=-10 rax=-55 ray=-10 raz=25 hx=10 hz=6 rlx=-8"),
			new Emote("sakura", "Sakura Twirl", "halo", true, 2.2f, true,
					"0: raz=100 laz=-100 rax=-10 lax=-10 up=.8 spin=0 hx=-10 | .25: raz=115 laz=-85 up=1.6 spin=90 | .5: raz=100 laz=-100 up=.8 spin=180 | .75: raz=85 laz=-115 up=1.6 spin=270 | 1: raz=100 laz=-100 rax=-10 lax=-10 up=.8 spin=360 hx=-10"),
			new Emote("kick", "Can-Can", "bolt", true, 0.8f, true,
					"0: raz=70 laz=-70 rlx=0 llx=0 up=0 | .25: rlx=-85 up=1 | .5: rlx=0 llx=0 up=0 | .75: llx=-85 up=1 | 1: raz=70 laz=-70 rlx=0 llx=0 up=0"),
			// ---------------- emotes ----------------
			new Emote("wave", "Wave", "smile", false, 1.2f, true,
					"0: raz=165 rax=-10 hz=6 laz=-6 | .25: raz=130 hz=-2 | .5: raz=165 hz=6 | .75: raz=130 hz=-2 | 1: raz=165 rax=-10 hz=6 laz=-6"),
			new Emote("cheer", "Cheer", "star", false, 1.0f, true,
					"0: raz=160 laz=-160 rax=-15 lax=-15 up=0 hx=-10 | .35: up=3.2 raz=175 laz=-175 rlx=-25 llx=-25 hx=-20 | .65: up=0 | 1: raz=160 laz=-160 rax=-15 lax=-15 up=0 hx=-10"),
			new Emote("bow", "Sakura Bow", "halo", false, 2.8f, false,
					"0: | .3: bx=55 hx=30 rax=-35 ray=-40 raz=10 lax=-20 lay=-10 up=-.5 | .7: bx=55 hx=30 rax=-35 ray=-40 raz=10 lax=-20 lay=-10 up=-.5 | 1:"),
			new Emote("clap", "Clap", "heart", false, 0.45f, true,
					"0: rax=-70 lax=-70 ray=-35 lay=35 up=0 | .5: rax=-74 lax=-74 ray=8 lay=-8 up=.4 hx=6 | 1: rax=-70 lax=-70 ray=-35 lay=35 up=0"),
			new Emote("dab", "Dab", "bolt", false, 1.8f, false,
					"0: | .18: rax=-100 raz=70 lax=-95 lay=55 laz=-15 hx=38 hy=-35 bz=-6 | .78: rax=-100 raz=70 lax=-95 lay=55 laz=-15 hx=38 hy=-35 bz=-6 | 1:"),
			new Emote("salute", "Salute", "flag", false, 2.0f, false,
					"0: | .2: rax=-150 ray=-45 raz=30 hx=-6 bx=-4 | .8: rax=-150 ray=-45 raz=30 hx=-6 bx=-4 | 1:"),
			new Emote("facepalm", "Facepalm", "help", false, 2.4f, false,
					"0: | .2: rax=-140 ray=-40 raz=15 hx=25 | .45: hy=-12 | .6: hy=12 | .75: hy=-8 | .85: rax=-140 ray=-40 raz=15 hx=25 hy=0 | 1:"),
			new Emote("laugh", "Laugh", "smile", false, 0.5f, true,
					"0: bx=12 hx=-22 rax=-30 lax=-30 ray=-25 lay=25 up=0 | .5: bx=-4 hx=8 up=.5 | 1: bx=12 hx=-22 rax=-30 lax=-30 ray=-25 lay=25 up=0"),
			new Emote("shrug", "Shrug", "help", false, 1.6f, false,
					"0: | .25: raz=40 laz=-40 rax=-30 lax=-30 ray=40 lay=-40 hz=12 up=.6 | .7: raz=40 laz=-40 rax=-30 lax=-30 ray=40 lay=-40 hz=12 up=.6 | 1:"),
			new Emote("heart", "Heart", "heart", false, 2.2f, false,
					"0: | .25: rax=-165 lax=-165 ray=-28 lay=28 hz=10 hx=-8 | .4: hz=-6 | .55: hz=10 | .8: rax=-165 lax=-165 ray=-28 lay=28 hz=10 hx=-8 | 1:"),
			new Emote("flex", "Flex", "bolt", false, 1.0f, true,
					"0: raz=95 laz=-95 rax=-20 lax=-20 bx=-6 hx=-6 up=0 | .5: raz=110 laz=-110 rax=-35 lax=-35 bx=-10 up=.6 | 1: raz=95 laz=-95 rax=-20 lax=-20 bx=-6 hx=-6 up=0"),
			new Emote("sit", "Sit", "home", false, 4f, true,
					"0: rlx=-88 llx=-88 rlz=6 llz=-6 rax=-28 lax=-28 up=-9.5 hx=4 | .5: hy=12 | 1: rlx=-88 llx=-88 rlz=6 llz=-6 rax=-28 lax=-28 up=-9.5 hx=4")};

	public static final String[] IDS, NAMES, ICONS;
	public static final boolean[] IS_DANCE;
	private static final float[][][] KEYS; // [emote][key][0=phase, 1..19 channels]
	private static final String[] CH = {"hx", "hy", "hz", "bx", "by", "bz", "rax", "ray", "raz", "lax", "lay", "laz", "rlx", "rlz", "llx", "llz", "up", "sh", "spin"};

	static {
		int n = E.length;
		IDS = new String[n]; NAMES = new String[n]; ICONS = new String[n]; IS_DANCE = new boolean[n]; KEYS = new float[n][][];
		for (int i = 0; i < n; i++) {
			IDS[i] = E[i].id; NAMES[i] = E[i].name; ICONS[i] = E[i].icon; IS_DANCE[i] = E[i].dance;
			String[] ks = E[i].keys.split("\\|");
			KEYS[i] = new float[ks.length][CH.length + 1];
			for (int k = 0; k < ks.length; k++) {
				String[] tp = ks[k].trim().split(":", 2);
				KEYS[i][k][0] = Float.parseFloat(tp[0].trim());
				if (tp.length > 1) for (String kv : tp[1].trim().split("\\s+")) {
					if (kv.isBlank()) continue;
					String[] q = kv.split("=");
					for (int c = 0; c < CH.length; c++) if (CH[c].equals(q[0])) KEYS[i][k][c + 1] = Float.parseFloat(q[1]);
				}
			}
			java.util.Arrays.sort(KEYS[i], (a, b) -> Float.compare(a[0], b[0]));
			// channels a key doesn't mention keep the curve going (filled from neighbours), except at the ends
			fill(KEYS[i], ks);
		}
	}

	/** A key that doesn't name a channel takes the value it would have from its neighbours (so poses don't snap to 0). */
	private static void fill(float[][] keys, String[] src) {
		// which channels each key actually set
		boolean[][] set = new boolean[keys.length][CH.length];
		java.util.List<String> sorted = new java.util.ArrayList<>(java.util.Arrays.asList(src));
		sorted.sort((a, b) -> Float.compare(Float.parseFloat(a.trim().split(":")[0].trim()), Float.parseFloat(b.trim().split(":")[0].trim())));
		for (int k = 0; k < keys.length; k++) {
			String[] tp = sorted.get(k).trim().split(":", 2);
			if (tp.length > 1) for (String kv : tp[1].trim().split("\\s+")) {
				String nm = kv.split("=")[0];
				for (int c = 0; c < CH.length; c++) if (CH[c].equals(nm)) set[k][c] = true;
			}
		}
		boolean endsEmpty = !any(set[0]) || !any(set[keys.length - 1]);
		for (int c = 0; c < CH.length; c++) {
			for (int k = 0; k < keys.length; k++) {
				if (set[k][c]) continue;
				if ((k == 0 || k == keys.length - 1) && endsEmpty) continue; // neutral start/end for one-shot emotes
				int a = k - 1, b = k + 1;
				while (a >= 0 && !set[a][c]) a--;
				while (b < keys.length && !set[b][c]) b++;
				float va = a >= 0 ? keys[a][c + 1] : (b < keys.length ? keys[b][c + 1] : 0), vb = b < keys.length ? keys[b][c + 1] : va;
				float ta = a >= 0 ? keys[a][0] : 0, tb = b < keys.length ? keys[b][0] : 1;
				float u = tb > ta ? (keys[k][0] - ta) / (tb - ta) : 0;
				keys[k][c + 1] = va + (vb - va) * u;
			}
		}
	}

	private static boolean any(boolean[] b) {
		for (boolean x : b) if (x) return true;
		return false;
	}

	private record Playing(int dance, long at, long stopAt) {}

	/** name (lower case) -> what they're doing. */
	private static final Map<String, Playing> ACTIVE = new ConcurrentHashMap<>();
	private static CameraType before = null;
	private static double sx, sy, sz;

	public static int index(String id) {
		for (int i = 0; i < IDS.length; i++) if (IDS[i].equals(id)) return i;
		return -1;
	}

	/** Start an emote for yourself (and tell Kyoto players near you). */
	public static void play(int i) {
		Minecraft mc = Minecraft.getInstance();
		String me = Compat.myName();
		if (me == null || mc.player == null || !Modules.on("dances")) return;
		ACTIVE.put(me.toLowerCase(), new Playing(i, System.currentTimeMillis(), 0));
		sx = mc.player.getX();
		sy = mc.player.getY();
		sz = mc.player.getZ();
		if (before == null && Modules.opt("dance.camera") == 0) {
			before = mc.options.getCameraType();
			mc.options.setCameraType(CameraType.THIRD_PERSON_FRONT);
		}
		Nearby.emote("dance:" + IDS[i]);
	}

	public static void stopMine() {
		String me = Compat.myName();
		if (me == null) return;
		Playing p = ACTIVE.get(me.toLowerCase());
		if (p == null || p.stopAt > 0) return;
		ACTIVE.put(me.toLowerCase(), new Playing(p.dance, p.at, System.currentTimeMillis())); // ease out
		Minecraft mc = Minecraft.getInstance();
		if (before != null) mc.options.setCameraType(before);
		before = null;
		Nearby.emote("dance:stop");
	}

	/** From the server: someone near you started (or stopped) an emote. */
	public static void remote(String name, String id) {
		String key = name.toLowerCase();
		String me = Compat.myName();
		if (me != null && me.equalsIgnoreCase(name)) return;
		Playing p = ACTIVE.get(key);
		if (id.equals("stop")) { if (p != null) ACTIVE.put(key, new Playing(p.dance, p.at, System.currentTimeMillis())); return; }
		int i = index(id);
		if (i < 0) return;
		if (p == null || p.dance != i || p.stopAt > 0) ACTIVE.put(key, new Playing(i, System.currentTimeMillis(), 0));
	}

	public static boolean dancingMine() {
		String me = Compat.myName();
		Playing p = me == null ? null : ACTIVE.get(me.toLowerCase());
		return p != null && p.stopAt == 0;
	}

	/** Every tick: stop your emote when you move, attack or it finishes; clear faded ones. */
	public static void tick(Minecraft mc) {
		if (mc.player == null) return;
		String me = Compat.myName();
		long now = System.currentTimeMillis();
		ACTIVE.entrySet().removeIf(e -> (e.getValue().stopAt > 0 && now - e.getValue().stopAt > 400)
				|| (!e.getKey().equalsIgnoreCase(me == null ? "" : me) && now - e.getValue().at > 30000));
		Playing p = me == null ? null : ACTIVE.get(me.toLowerCase());
		if (p == null || p.stopAt > 0) return;
		double moved = Math.abs(mc.player.getX() - sx) + Math.abs(mc.player.getZ() - sz) + Math.abs(mc.player.getY() - sy);
		boolean done = !E[p.dance].repeat && now - p.at > E[p.dance].loop * 1000;
		if (moved > 0.15 || dev.kyoto.util.Compat.swinging(mc.player) || dev.kyoto.util.Compat.currentScreen() instanceof net.minecraft.client.gui.screens.ChatScreen || done || now - p.at > 60000) stopMine();
	}

	/* ---------------- posing the model ---------------- */

	/** Render state -> the player it was made from (filled in by the render state hook). */
	public static final Map<Object, Entity> STATE_OWNER = java.util.Collections.synchronizedMap(new WeakHashMap<>());

	private static float cr(float p0, float p1, float p2, float p3, float t) {
		float t2 = t * t, t3 = t2 * t;
		return 0.5f * ((2 * p1) + (-p0 + p2) * t + (2 * p0 - 5 * p1 + 4 * p2 - p3) * t2 + (-p0 + 3 * p1 - 3 * p2 + p3) * t3);
	}

	/** All channels at a moment, plus how strongly to apply them (eases in at the start and out at the end). */
	private static float sample(Playing p, float[] out) {
		Emote em = E[p.dance];
		long now = System.currentTimeMillis();
		float t = (now - p.at) / 1000f;
		float ph = em.repeat ? (t / em.loop) % 1f : Math.min(1f, t / em.loop);
		float[][] k = KEYS[p.dance];
		int n = k.length, i = 0;
		while (i < n - 2 && ph > k[i + 1][0]) i++;
		float[] a = k[i == 0 ? (em.repeat ? n - 2 : 0) : i - 1], b = k[i], c = k[Math.min(n - 1, i + 1)], d = k[i + 2 < n ? i + 2 : (em.repeat ? 1 : n - 1)];
		float span = c[0] - b[0], u = span <= 0 ? 0 : Math.max(0, Math.min(1, (ph - b[0]) / span));
		for (int ch = 0; ch < CH.length; ch++) out[ch] = cr(a[ch + 1], b[ch + 1], c[ch + 1], d[ch + 1], u);
		// spins keep going loop after loop
		if (em.repeat) out[18] += (float) Math.floor(t / em.loop) * (k[n - 1][19] - k[0][19]);
		float in = Math.min(1f, t / 0.3f);
		in = in * in * (3 - 2 * in);
		float fade = p.stopAt > 0 ? Math.max(0, 1 - (now - p.stopAt) / 350f) : 1;
		fade = fade * fade * (3 - 2 * fade);
		return in * fade;
	}

	/** How much a player's body turns this frame (degrees), used for spins. */
	public static float bodySpin(Entity e) {
		Playing p = of(e);
		if (p == null) return 0;
		float[] c = new float[CH.length];
		float k = sample(p, c);
		return c[18] * k;
	}

	private static Playing of(Entity e) {
		if (!(e instanceof Player pl)) return null;
		String n = Compat.profileName(pl.getGameProfile());
		return n == null ? null : ACTIVE.get(n.toLowerCase());
	}

	private static final Map<Class<?>, Field[]> PARTS = new ConcurrentHashMap<>();

	private static Field[] parts(Object model) {
		return PARTS.computeIfAbsent(model.getClass(), c -> {
			String[] names = {"head", "body", "rightArm", "leftArm", "rightLeg", "leftLeg"};
			Field[] out = new Field[names.length];
			for (int i = 0; i < names.length; i++) {
				for (Class<?> k = c; k != null && out[i] == null; k = k.getSuperclass()) {
					try {
						Field f = k.getDeclaredField(names[i]);
						f.setAccessible(true);
						out[i] = f;
					} catch (Throwable ignored) {
					}
				}
			}
			return out;
		});
	}

	/** Called after Minecraft posed a humanoid model: if its player is doing an emote, we take over. */
	public static void pose(Object model, Object state) {
		Entity e = STATE_OWNER.get(state);
		Playing p = of(e);
		if (p == null) return;
		Field[] f = parts(model);
		try {
			ModelPart head = (ModelPart) f[0].get(model), body = (ModelPart) f[1].get(model), ra = (ModelPart) f[2].get(model), la = (ModelPart) f[3].get(model),
					rl = (ModelPart) f[4].get(model), ll = (ModelPart) f[5].get(model);
			if (head == null || body == null || ra == null || la == null || rl == null || ll == null) return;
			float[] c = new float[CH.length];
			float k = sample(p, c);
			final float D = (float) Math.PI / 180f;
			mix(head, c[0] * D, c[1] * D, c[2] * D, k);
			mix(body, c[3] * D, c[4] * D, c[5] * D, k);
			mix(ra, c[6] * D, c[7] * D, c[8] * D, k);
			mix(la, c[9] * D, c[10] * D, c[11] * D, k);
			mix(rl, c[12] * D, 0, c[13] * D, k);
			mix(ll, c[14] * D, 0, c[15] * D, k);
			float dy = -c[16] * k, dx = c[17] * k;
			for (ModelPart m : new ModelPart[]{head, body, ra, la, rl, ll}) { m.y += dy; m.x += dx; }
		} catch (Throwable ignored) {
		}
	}

	private static void mix(ModelPart m, float x, float y, float z, float k) {
		m.xRot += (x - m.xRot) * k;
		m.yRot += (y - m.yRot) * k;
		m.zRot += (z - m.zRot) * k;
	}
}
