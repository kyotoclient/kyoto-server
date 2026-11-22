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

	public static final String[] IDS = {"wave", "floss", "groove", "robot", "cheer", "bow", "hype", "shuffle", "spin", "clap"};
	public static final String[] NAMES = {"Wave", "Floss", "Groove", "Robot", "Cheer", "Sakura Bow", "Hype", "Shuffle", "Spin", "Clap"};
	public static final String[] ICONS = {"smile", "sparkle", "music", "cpu", "star", "halo", "bolt", "trail", "aura", "heart"};
	/** How long one loop lasts (seconds) and whether it loops until you move. */
	private static final float[] LOOP = {1.6f, 0.9f, 1.0f, 2.0f, 1.2f, 3.2f, 0.8f, 0.7f, 1.6f, 0.6f};
	private static final boolean[] REPEAT = {false, true, true, true, true, false, true, true, true, true};

	private record Playing(int dance, long at) {}

	/** name (lower case) -> what they're dancing. */
	private static final Map<String, Playing> ACTIVE = new ConcurrentHashMap<>();
	private static CameraType before = null;
	private static double sx, sy, sz;

	public static int index(String id) {
		for (int i = 0; i < IDS.length; i++) if (IDS[i].equals(id)) return i;
		return -1;
	}

	/** Start a dance for yourself (and tell Kyoto players near you). */
	public static void play(int i) {
		Minecraft mc = Minecraft.getInstance();
		String me = Compat.myName();
		if (me == null || mc.player == null || !Modules.on("dances")) return;
		ACTIVE.put(me.toLowerCase(), new Playing(i, System.currentTimeMillis()));
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
		if (me == null || ACTIVE.remove(me.toLowerCase()) == null) return;
		Minecraft mc = Minecraft.getInstance();
		if (before != null) mc.options.setCameraType(before);
		before = null;
		Nearby.emote("dance:stop");
	}

	/** From the server: someone near you started (or stopped) a dance. */
	public static void remote(String name, String id) {
		String key = name.toLowerCase();
		String me = Compat.myName();
		if (me != null && me.equalsIgnoreCase(name)) return;
		if (id.equals("stop")) { ACTIVE.remove(key); return; }
		int i = index(id);
		if (i < 0) return;
		Playing p = ACTIVE.get(key);
		if (p == null || p.dance != i) ACTIVE.put(key, new Playing(i, System.currentTimeMillis()));
	}

	public static boolean dancingMine() {
		String me = Compat.myName();
		return me != null && ACTIVE.containsKey(me.toLowerCase());
	}

	/** Every tick: stop your dance when you move, attack or it finishes. */
	public static void tick(Minecraft mc) {
		if (mc.player == null) return;
		String me = Compat.myName();
		Playing p = me == null ? null : ACTIVE.get(me.toLowerCase());
		long now = System.currentTimeMillis();
		ACTIVE.entrySet().removeIf(e -> !e.getKey().equalsIgnoreCase(me == null ? "" : me) && now - e.getValue().at > 30000);
		if (p == null) return;
		double moved = Math.abs(mc.player.getX() - sx) + Math.abs(mc.player.getZ() - sz) + Math.abs(mc.player.getY() - sy);
		boolean done = !REPEAT[p.dance] && now - p.at > LOOP[p.dance] * 1000 * 2;
		if (moved > 0.15 || dev.kyoto.util.Compat.swinging(mc.player) || dev.kyoto.util.Compat.currentScreen() instanceof net.minecraft.client.gui.screens.ChatScreen || done || now - p.at > 30000) stopMine();
	}

	/* ---------------- posing the model ---------------- */

	/** Render state -> the player it was made from (filled in by the render state hook). */
	public static final Map<Object, Entity> STATE_OWNER = java.util.Collections.synchronizedMap(new WeakHashMap<>());

	/** How much a dancing player's body turns this frame (degrees), used for spins. */
	public static float bodySpin(Entity e) {
		Playing p = of(e);
		if (p == null) return 0;
		float t = (System.currentTimeMillis() - p.at) / 1000f;
		return switch (IDS[p.dance]) {
			case "spin" -> t * 360f / LOOP[p.dance];
			case "robot" -> (float) Math.round(Math.sin(t * Math.PI) * 2) * 22.5f;
			default -> 0f;
		};
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

	/** Called after Minecraft posed a humanoid model: if its player is dancing, we take over. */
	public static void pose(Object model, Object state) {
		Entity e = STATE_OWNER.get(state);
		Playing p = of(e);
		if (p == null) return;
		Field[] f = parts(model);
		try {
			ModelPart head = (ModelPart) f[0].get(model), body = (ModelPart) f[1].get(model), ra = (ModelPart) f[2].get(model), la = (ModelPart) f[3].get(model),
					rl = (ModelPart) f[4].get(model), ll = (ModelPart) f[5].get(model);
			if (head == null || body == null || ra == null || la == null || rl == null || ll == null) return;
			float t = (System.currentTimeMillis() - p.at) / 1000f;
			float k = Math.min(1f, t / 0.25f); // blend in
			float L = LOOP[p.dance];
			float ph = (float) (t / L * Math.PI * 2);
			float s = (float) Math.sin(ph), c = (float) Math.cos(ph);
			float bounce = 0;
			// targets (radians); start from a neutral stance
			float hx = 0, hy = 0, hz = 0, bx = 0, by = 0, bz = 0, rax = 0, ray = 0, raz = 0, lax = 0, lay = 0, laz = 0, rlx = 0, rlz = 0, llx = 0, llz = 0;
			switch (IDS[p.dance]) {
				case "wave" -> {
					raz = 2.6f + s * 0.35f; rax = -0.2f; hz = s * 0.08f; laz = -0.1f;
				}
				case "floss" -> {
					float sw = s * 0.7f;
					rax = 0.25f; lax = 0.25f; raz = 0.35f + sw; laz = -0.35f + sw; bz = -sw * 0.12f; hz = sw * 0.1f;
					rlz = sw * 0.08f; llz = sw * 0.08f; bounce = Math.abs(s) * 0.5f;
				}
				case "groove" -> {
					bounce = (float) Math.abs(Math.sin(ph)) * 1.2f; hx = (float) Math.abs(s) * 0.25f - 0.1f; by = s * 0.2f;
					rax = -0.4f + c * 0.5f; lax = -0.4f - c * 0.5f; raz = 0.3f; laz = -0.3f; rlx = s * 0.25f; llx = -s * 0.25f;
				}
				case "robot" -> {
					float step = (float) Math.round(s * 2) / 2f, step2 = (float) Math.round(c * 2) / 2f;
					rax = -1.57f * (0.5f + 0.5f * step); lax = -1.57f * (0.5f - 0.5f * step); ray = 0; hy = step2 * 0.6f;
					raz = 0.1f; laz = -0.1f; bounce = Math.abs(step2) * 0.3f;
				}
				case "cheer" -> {
					raz = 2.7f + s * 0.25f; laz = -2.7f - s * 0.25f; rax = -0.2f; lax = -0.2f; bounce = (float) Math.abs(Math.sin(ph)) * 2.2f;
					hx = -0.2f; rlx = -(float) Math.abs(s) * 0.2f; llx = -(float) Math.abs(s) * 0.2f;
				}
				case "bow" -> {
					float b = (float) Math.sin(Math.min(1f, t / (L * 0.5f)) * Math.PI);
					bx = b * 0.7f; hx = b * 0.5f; rax = -b * 0.6f; lax = -b * 0.6f; raz = 0.15f; laz = -0.15f; ray = b * 0.3f; lay = -b * 0.3f;
				}
				case "hype" -> {
					rax = -2.9f + s * 0.3f; lax = -2.9f - s * 0.3f; raz = 0.4f; laz = -0.4f; bounce = (float) Math.abs(Math.sin(ph)) * 2.5f; hx = s * 0.15f;
				}
				case "shuffle" -> {
					rlx = s * 0.6f; llx = -s * 0.6f; rlz = 0.1f; llz = -0.1f; rax = -s * 0.5f; lax = s * 0.5f; bounce = (float) Math.abs(c) * 0.8f; by = s * 0.15f; hy = -s * 0.15f;
				}
				case "spin" -> {
					raz = 1.4f; laz = -1.4f; rax = -0.2f; lax = -0.2f; hx = -0.15f; bounce = 0.3f + (float) Math.abs(s) * 0.4f;
				}
				case "clap" -> {
					float clap = (float) Math.abs(Math.sin(ph));
					rax = -1.2f; lax = -1.2f; ray = -0.2f - clap * 0.55f; lay = 0.2f + clap * 0.55f; bounce = clap * 0.4f; hx = 0.1f;
				}
				default -> {
				}
			}
			mix(head, hx, hy, hz, k);
			mix(body, bx, by, bz, k);
			mix(ra, rax, ray, raz, k);
			mix(la, lax, lay, laz, k);
			mix(rl, rlx, 0, rlz, k);
			mix(ll, llx, 0, llz, k);
			float dy = -bounce * k;
			for (ModelPart m : new ModelPart[]{head, body, ra, la, rl, ll}) m.y += dy;
		} catch (Throwable ignored) {
		}
	}

	private static void mix(ModelPart m, float x, float y, float z, float k) {
		m.xRot += (x - m.xRot) * k;
		m.yRot += (y - m.yRot) * k;
		m.zRot += (z - m.zRot) * k;
	}
}
