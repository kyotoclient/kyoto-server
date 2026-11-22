package dev.kyoto.cosmetic;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.kyoto.config.KyotoConfig;
import dev.kyoto.module.Modules;
import dev.kyoto.net.KyotoNet;
import dev.kyoto.screens.WorldScreens;
import dev.kyoto.util.Ui;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;

/**
 * Accessories drawn as smooth glowing sprites (instead of blocky vanilla dust).
 * One description per accessory, used both in the world and in the Cosmetics menu preview, so what you see is what you get.
 *
 * Points are in player space: blocks, feet at y=0, +x = the player's left, +z = the way they face.
 */
public final class Aura {
	private Aura() {}

	public static final byte GLOW = 0, SPARK = 1, PETAL = 2, BUBBLE = 3, SQUARE = 4, HEART = 5, NOTE = 6, STAR = 7, FLAME = 8, SNOW = 9;
	public static final Identifier[] TEX = {
			Identifier.fromNamespaceAndPath("kyoto", "textures/misc/glow.png"),
			Identifier.fromNamespaceAndPath("kyoto", "textures/misc/spark.png"),
			Identifier.fromNamespaceAndPath("kyoto", "textures/misc/petal.png"),
			Identifier.fromNamespaceAndPath("kyoto", "textures/misc/bubble.png"),
			Identifier.fromNamespaceAndPath("kyoto", "textures/misc/white.png"),
			Identifier.fromNamespaceAndPath("kyoto", "textures/misc/heart.png"),
			Identifier.fromNamespaceAndPath("kyoto", "textures/misc/note.png"),
			Identifier.fromNamespaceAndPath("kyoto", "textures/misc/star.png"),
			Identifier.fromNamespaceAndPath("kyoto", "textures/misc/flame.png"),
			Identifier.fromNamespaceAndPath("kyoto", "textures/misc/snow.png")};

	/** One sprite: position, colour, alpha 0-1, size in blocks, which texture. */
	public static final class P {
		public float x, y, z, a, size; public int rgb; public byte tex;
		P(float x, float y, float z, int rgb, float a, float size, byte tex) { this.x = x; this.y = y; this.z = z; this.rgb = rgb; this.a = a; this.size = size; this.tex = tex; }
	}

	private static float rnd(int i) { float v = Mth.sin(i * 12.9898f + 78.233f) * 43758.547f; return v - (float) Math.floor(v); }
	private static float frac(double v) { return (float) (v - Math.floor(v)); }
	private static float ease(float x) { return x * x * (3 - 2 * x); }

	/** Colour for an accessory: the colour setting, or its own default. */
	private static int col(int def, double t, int i) {
		int c = KyotoConfig.get().accessoryColor;
		if (c <= 0 || c >= Accessories.COLOURS.length) return def;
		if (c == 1) return Ui.accent();
		if (c == Accessories.COLOURS.length - 1) return Ui.hsv((float) ((t * 60 + i * 25) % 360), 0.65f, 1f);
		return Accessories.colourRgb(c, def);
	}

	private static int lerpRgb(int a, int b, float k) { return Ui.mix(a, b, Mth.clamp(k, 0, 1)); }

	private static void add(List<P> o, double x, double y, double z, int rgb, double a, double s, byte tex) {
		if (a > 0.01) o.add(new P((float) x, (float) y, (float) z, rgb, (float) Math.min(1, a), (float) s, tex));
	}

	/** Every sprite for accessory {@code type} at time {@code t} (seconds), for a player {@code h} blocks tall. */
	public static List<P> points(int type, double t, float h) {
		List<P> o = new ArrayList<>();
		switch (type) {
			case 1 -> { // Halo: a soft glowing ring with a few sparkles sliding round it
				double y = h + 0.3 + Math.sin(t * 1.6) * 0.025;
				for (int i = 0; i < 36; i++) {
					double a = i * Math.PI * 2 / 36;
					add(o, Math.cos(a) * 0.3, y, Math.sin(a) * 0.3, col(0xFFE7A0, t, i), 0.55, 0.13, GLOW);
				}
				for (int i = 0; i < 3; i++) {
					double a = t * 1.2 + i * Math.PI * 2 / 3;
					add(o, Math.cos(a) * 0.3, y, Math.sin(a) * 0.3, 0xFFFFFF, 0.75 + 0.25 * Math.sin(t * 6 + i), 0.16, SPARK);
				}
			}
			case 2, 23 -> wings(o, t, h, type == 23);
			case 3 -> { // Flame aura: wisps that rise, shrink and cool from yellow to red
				for (int i = 0; i < 34; i++) {
					float life = frac(t * 0.7 + rnd(i));
					double a = rnd(i + 99) * Math.PI * 2 + t * 0.6;
					double r = 0.45 + 0.1 * Math.sin(life * 6 + i);
					int c = life < 0.35f ? lerpRgb(0xFFF1A0, 0xFFA235, life / 0.35f) : lerpRgb(0xFFA235, 0xE0301E, (life - 0.35f) / 0.65f);
					add(o, Math.cos(a) * r, 0.05 + life * 1.9, Math.sin(a) * r, col(c, t, i), (1 - life) * 0.9, 0.3 * (1 - life * 0.6), FLAME);
				}
			}
			case 4 -> { // Frost aura: slow icy sparkles orbiting in a tilted band
				for (int i = 0; i < 22; i++) {
					double a = t * 0.5 + i * Math.PI * 2 / 22;
					double y = 0.25 + (i % 5) * 0.32 + Math.sin(t + i) * 0.06;
					add(o, Math.cos(a) * 0.6, y, Math.sin(a) * 0.6, col(i % 3 == 0 ? 0xFFFFFF : 0xA8E6FF, t, i), 0.5 + 0.5 * Math.sin(t * 3 + i * 1.7), i % 3 == 0 ? 0.2 : 0.12, i % 3 == 0 ? SNOW : GLOW);
				}
			}
			case 5 -> { // Heart orbit: three hearts that bob and pulse
				for (int k = 0; k < 3; k++) {
					double a = t * 0.8 + k * Math.PI * 2 / 3;
					double cy = h * 0.75 + Math.sin(t * 2 + k) * 0.08;
					double pulse = 1 + 0.12 * Math.max(0, Math.sin(t * 6 + k));
					add(o, Math.cos(a) * 0.65, cy, Math.sin(a) * 0.65, col(0xFF5C8A, t, k), 0.95, 0.26 * pulse, HEART);
					add(o, Math.cos(a) * 0.65, cy, Math.sin(a) * 0.65, col(0xFF8FB0, t, k), 0.35, 0.5 * pulse, GLOW);
				}
			}
			case 6 -> { // Music notes floating up and fading
				for (int k = 0; k < 5; k++) {
					float life = frac(t * 0.35 + k * 0.2);
					double a = k * 1.7 + life * 1.2;
					double al = Math.sin(life * Math.PI);
					int c = col(Ui.hsv((k * 70 + 160) % 360, 0.5f, 1f), t, k);
					add(o, Math.cos(a) * 0.5 + Math.sin(life * 6 + k) * 0.06, h * 0.6 + life * 1.2, Math.sin(a) * 0.5, c, al, 0.2, NOTE);
				}
			}
			case 7 -> { // Sakura petals drifting down and swaying
				for (int i = 0; i < 18; i++) {
					float life = frac(t * 0.22 + rnd(i));
					double a = rnd(i + 7) * Math.PI * 2;
					double r = 0.4 + rnd(i + 3) * 0.5;
					add(o, Math.cos(a) * r + Math.sin(t * 1.5 + i) * 0.12, h + 0.6 - life * 2.2, Math.sin(a) * r, col(i % 4 == 0 ? 0xFFE3EE : 0xFFA8CB, t, i), Math.sin(life * Math.PI), 0.12, PETAL);
				}
			}
			case 8 -> { // Galaxy ring: a tilted, rotating band of colour with a bright inner dust
				for (int i = 0; i < 48; i++) {
					double a = t * 0.6 + i * Math.PI * 2 / 48;
					double r = 0.72 + rnd(i) * 0.12;
					double tilt = 0.25;
					add(o, Math.cos(a) * r, h * 0.55 + Math.sin(a) * r * tilt, Math.sin(a) * r, Ui.hsv((float) ((i * 7.5 + t * 30) % 360), 0.55f, 1f), 0.45 + rnd(i + 5) * 0.4, 0.1 + rnd(i + 9) * 0.06, i % 6 == 0 ? SPARK : GLOW);
				}
			}
			case 9, 22 -> { // Star crown / Blossom crown
				boolean bloom = type == 22;
				for (int i = 0; i < 5; i++) {
					double a = t * 0.4 + i * Math.PI * 2 / 5;
					double x = Math.cos(a) * 0.3, z = Math.sin(a) * 0.3, y = h + 0.2;
					if (bloom) {
						for (int p = 0; p < 5; p++) {
							double pa = p * Math.PI * 2 / 5 + t;
							add(o, x + Math.cos(pa) * 0.05 * -Math.sin(a), y + Math.sin(pa) * 0.05, z + Math.cos(pa) * 0.05 * Math.cos(a), col(0xFFB7D5, t, p), 0.9, 0.08, PETAL);
						}
						add(o, x, y, z, 0xFFF3A0, 1, 0.06, GLOW);
					} else {
						add(o, x, y + 0.12, z, col(0xFFE45C, t, i), 1, 0.17, SPARK);
						for (int s = 0; s < 3; s++) add(o, x, y + s * 0.04, z, col(0xFFC93C, t, i), 0.8, 0.07, GLOW);
					}
				}
				for (int i = 0; i < 24; i++) { double a = i * Math.PI * 2 / 24; add(o, Math.cos(a) * 0.3, h + 0.2, Math.sin(a) * 0.3, col(bloom ? 0x8BD48B : 0xFFD34A, t, i), 0.5, 0.06, GLOW); }
			}
			case 10, 11 -> { // Rain / snow cloud
				boolean snow = type == 11;
				for (int i = 0; i < 14; i++) {
					double x = (rnd(i) - 0.5) * 0.7, z = (rnd(i + 30) - 0.5) * 0.45;
					add(o, x + Math.sin(t * 0.7 + i) * 0.02, h + 0.75 + rnd(i + 60) * 0.12, z, snow ? 0xF4F7FF : 0x8E949E, 0.55, 0.32, GLOW);
				}
				for (int i = 0; i < 12; i++) {
					float life = frac(t * (snow ? 0.5 : 1.6) + rnd(i + 90));
					double x = (rnd(i + 120) - 0.5) * 0.6, z = (rnd(i + 150) - 0.5) * 0.4;
					if (snow) add(o, x + Math.sin(t * 2 + i) * 0.05, h + 0.68 - life * 1.4, z, 0xFFFFFF, 1 - life, 0.07, SPARK);
					else for (int s = 0; s < 3; s++) add(o, x, h + 0.68 - life * 1.4 + s * 0.05, z, 0x7FB8FF, (1 - life) * 0.8, 0.035, GLOW);
				}
			}
			case 12 -> spiral(o, t, h, 0xB05CFF, 0x5A1E9C, true);
			case 13 -> { // Soul orbit: cyan flames chasing each other
				for (int k = 0; k < 3; k++) for (int i = 0; i < 9; i++) {
					double a = t * 1.6 + k * Math.PI * 2 / 3 - i * 0.12;
					add(o, Math.cos(a) * 0.6, h * 0.55 + Math.sin(t * 2 + k) * 0.15, Math.sin(a) * 0.6, col(lerpRgb(0xC8FFFF, 0x2CB6D6, i / 8f), t, i), 1 - i / 9.0, 0.18 - i * 0.012, GLOW);
				}
			}
			case 14 -> { // Bubble ring
				for (int i = 0; i < 14; i++) {
					float life = frac(t * 0.35 + rnd(i));
					double a = rnd(i + 4) * Math.PI * 2;
					add(o, Math.cos(a) * 0.55, 0.1 + life * 2.0, Math.sin(a) * 0.55, col(0xBFEFFF, t, i), Math.sin(life * Math.PI) * 0.9, 0.08 + rnd(i + 2) * 0.08, BUBBLE);
				}
			}
			case 15 -> { // Spark storm: flickering electric sparks
				for (int i = 0; i < 16; i++) {
					int seed = i + (int) (t * 8) * 31;
					double x = (rnd(seed) - 0.5) * 1.0, y = rnd(seed + 1) * h, z = (rnd(seed + 2) - 0.5) * 1.0;
					add(o, x, y, z, col(i % 2 == 0 ? 0xE8F6FF : 0x8FD3FF, t, i), rnd(seed + 3) * 0.9 + 0.1, 0.12 + rnd(seed + 4) * 0.1, SPARK);
				}
			}
			case 16 -> { // Firefly swarm: wandering, blinking
				for (int i = 0; i < 14; i++) {
					double x = Math.sin(t * 0.5 + i * 2.1) * 0.8, y = 0.5 + Math.sin(t * 0.7 + i) * 0.5 + i * 0.07, z = Math.cos(t * 0.43 + i * 1.3) * 0.8;
					double blink = Math.max(0, Math.sin(t * 2.5 + i * 1.9));
					add(o, x, y, z, col(0xD8FF6A, t, i), 0.2 + blink * 0.8, 0.08 + blink * 0.06, GLOW);
				}
			}
			case 17 -> { // Rainbow trail: a rising rainbow spiral at your feet
				for (int i = 0; i < 40; i++) {
					double k = i / 40.0, a = t * 2 + k * Math.PI * 4;
					add(o, Math.cos(a) * 0.45, k * 0.6, Math.sin(a) * 0.45, Ui.hsv((float) ((k * 360 + t * 90) % 360), 0.6f, 1f), 1 - k, 0.11, GLOW);
				}
			}
			case 18 -> { // Shadow aura: dark smoke rising
				for (int i = 0; i < 26; i++) {
					float life = frac(t * 0.45 + rnd(i));
					double a = rnd(i + 11) * Math.PI * 2 + life;
					add(o, Math.cos(a) * (0.4 + life * 0.25), life * 1.9, Math.sin(a) * (0.4 + life * 0.25), col(i % 4 == 0 ? 0x4A2A6A : 0x15141A, t, i), Math.sin(life * Math.PI) * 0.75, 0.25 + life * 0.25, GLOW);
				}
			}
			case 19 -> { // Gold sparkles twinkling all over
				for (int i = 0; i < 18; i++) {
					float life = frac(t * 0.6 + rnd(i));
					int seed = i * 7 + (int) Math.floor(t * 0.6 + rnd(i)) * 13;
					add(o, (rnd(seed) - 0.5) * 1.0, rnd(seed + 1) * (h + 0.2), (rnd(seed + 2) - 0.5) * 1.0, col(0xFFD36B, t, i), Math.sin(life * Math.PI), 0.16 * Math.sin(life * Math.PI) + 0.04, SPARK);
				}
			}
			case 20 -> { // Orbiting moon with a fading trail
				for (int i = 0; i < 16; i++) {
					double a = t * 0.9 - i * 0.07;
					add(o, Math.cos(a) * 0.9, h * 0.65 + Math.sin(a) * 0.2, Math.sin(a) * 0.9, col(0xE6EEFF, t, i), i == 0 ? 1 : 0.4 * (1 - i / 16.0), i == 0 ? 0.36 : 0.12, GLOW);
				}
				double a = t * 0.9;
				add(o, Math.cos(a) * 0.9, h * 0.65 + Math.sin(a) * 0.2, Math.sin(a) * 0.9, 0xFFFFFF, 1, 0.16, GLOW);
			}
			case 21 -> { // Constellation: twinkling stars joined by faint lines
				double[][] st = new double[6][];
				for (int i = 0; i < 6; i++) { double a = i * 1.05 + t * 0.1; st[i] = new double[]{Math.cos(a) * 0.75, h + 0.15 + Math.sin(i * 2.3) * 0.3, Math.sin(a) * 0.75}; }
				for (int i = 0; i < 5; i++) for (int s = 1; s < 8; s++) {
					double k = s / 8.0;
					add(o, st[i][0] + (st[i + 1][0] - st[i][0]) * k, st[i][1] + (st[i + 1][1] - st[i][1]) * k, st[i][2] + (st[i + 1][2] - st[i][2]) * k, col(0xA8C4FF, t, i), 0.25, 0.03, GLOW);
				}
				for (int i = 0; i < 6; i++) add(o, st[i][0], st[i][1], st[i][2], col(0xFFFFFF, t, i), 0.6 + 0.4 * Math.sin(t * 3 + i * 2), 0.18, SPARK);
			}
			case 24 -> { // Pixel halo: crisp little squares
				for (int i = 0; i < 16; i++) {
					double a = i * Math.PI * 2 / 16 + t * 0.4;
					add(o, Math.cos(a) * 0.3, h + 0.3, Math.sin(a) * 0.3, col(0xFFFFFF, t, i), 1, 0.06, SQUARE);
				}
			}
			case 25 -> { // Comet tail streaming behind
				for (int i = 0; i < 26; i++) {
					double k = i / 26.0;
					double wob = Math.sin(t * 3 - k * 6) * 0.06 * k;
					add(o, wob, h * 0.62 + k * 0.1, -0.35 - k * 1.3, col(lerpRgb(0xFFFFFF, 0x6FB8FF, (float) k), t, i), 1 - k, 0.2 * (1 - k) + 0.04, GLOW);
				}
				add(o, 0, h * 0.62, -0.35, 0xFFFFFF, 1, 0.2, SPARK);
			}
			default -> { }
		}
		return o;
	}

	private static void wings(List<P> o, double t, float h, boolean dragon) {
		double flap = Math.sin(t * (dragon ? 1.6 : 2.0)) * 0.22;
		int edge = dragon ? 0x9C5CFF : 0xFFFFFF, fill = dragon ? 0x4A1E8C : 0xDCEBFF;
		for (int side = -1; side <= 1; side += 2) {
			int feathers = dragon ? 4 : 6;
			for (int f = 0; f < feathers; f++) {
				double fk = f / (double) (feathers - 1);
				int n = dragon ? 11 : 9;
				for (int i = 0; i <= n; i++) {
					double k = i / (double) n;
					double span = (dragon ? 1.15 : 0.95) * k;
					double rise = Math.sin(k * Math.PI * 0.85) * (0.42 - fk * 0.3) - fk * 0.35 * k;
					double back = -0.2 - k * (0.25 + flap) - fk * 0.05;
					boolean rim = f == 0 || i == n;
					add(o, side * (0.12 + span), h * 0.78 + rise, back, col(rim ? edge : fill, t, f), rim ? 0.95 : 0.45 - fk * 0.15, rim ? 0.09 : 0.075, GLOW);
				}
			}
			if (dragon) for (int i = 0; i < 4; i++) add(o, side * (0.12 + 1.15), h * 0.78 + Math.sin(0.85 * Math.PI) * 0.42 + 0.05, -0.45 - flap, edge, 1, 0.07, SPARK);
		}
	}

	private static void spiral(List<P> o, double t, float h, int a1, int a2, boolean in) {
		for (int i = 0; i < 30; i++) {
			float life = frac(t * 0.5 + rnd(i));
			double r = 0.9 * (in ? 1 - life : life) + 0.1;
			double a = rnd(i + 3) * Math.PI * 2 + life * 4;
			add(o, Math.cos(a) * r, 0.2 + rnd(i + 8) * h, Math.sin(a) * r, col(lerpRgb(a1, a2, life), t, i), Math.sin(life * Math.PI), 0.1 + life * 0.05, i % 5 == 0 ? SPARK : GLOW);
		}
	}

	/* ---------------- world drawing ---------------- */

	private static volatile long lastDraw = 0;
	/** Why accessories can't draw (shown by /kyoto kycos), or null. */
	public static volatile String error = null;
	/** True while accessories are drawn as sprites (the old particle version stays off). */
	public static boolean active() { return System.currentTimeMillis() - lastDraw < 1500; }

	/* ---------------- player trail + atmosphere + sakura (world-space, not tied to a body) ---------------- */
	private static final java.util.ArrayDeque<double[]> TRAIL = new java.util.ArrayDeque<>(); // x, y, z, bornMillis, seed
	private static final List<double[]> AIR = new ArrayList<>();   // x, y, z, vx, vy, vz, born, life, seed, kind(0 mote,1 petal)
	private static long lastAirNanos = 0;
	private static final java.util.Random RNG = new java.util.Random();

	private static void q(List<float[]> out, Vec3 cam, double x, double y, double z, int rgb, float a, float size, byte tex) {
		float rx = (float) (x - cam.x), ry = (float) (y - cam.y), rz = (float) (z - cam.z);
		if (a <= 0.01f || rx * rx + ry * ry + rz * rz < 0.36f) return;
		out.add(new float[]{rx, ry, rz, rgb >> 16 & 255, rgb >> 8 & 255, rgb & 255, Math.min(1, a) * 255, size, tex});
	}

	private static void trailAndAir(List<float[]> out, Minecraft mc, Vec3 cam, float partial) {
		long now = System.currentTimeMillis();
		int col = Ui.accent();
		BurstFx.collect(out, cam);
		// trail
		if (Modules.on("player_trail") && mc.player != null && !mc.player.isInvisible()) {
			Vec3 p = mc.player.getPosition(partial);
			double py = p.y + mc.player.getBbHeight() * 0.55;
			double[] last = TRAIL.peekLast();
			if (last == null || Math.abs(last[0] - p.x) + Math.abs(last[1] - py) + Math.abs(last[2] - p.z) > 0.07) TRAIL.addLast(new double[]{p.x, py, p.z, now, RNG.nextDouble()});
			double life = new double[]{550, 1000, 1700}[Math.min(2, Modules.opt("trail.length"))];
			while (!TRAIL.isEmpty() && now - TRAIL.peekFirst()[3] > life) TRAIL.removeFirst();
			while (TRAIL.size() > 160) TRAIL.removeFirst();
			int style = Modules.opt("trail.style");
			double[] prev = null;
			int i = 0;
			for (double[] pt : TRAIL) {
				double age = (now - pt[3]) / life; // 0 new .. 1 old
				float fade = (float) ((1 - age) * Math.min(1, age * 8 + 0.15));
				if (prev != null) {
					int steps = style == 1 ? 1 : 3;
					for (int k = 0; k < steps; k++) {
						double f = k / (double) steps;
						double x = prev[0] + (pt[0] - prev[0]) * f, y = prev[1] + (pt[1] - prev[1]) * f, z = prev[2] + (pt[2] - prev[2]) * f;
						double ph = (now / 1000.0) * 5 + (i + f) * 0.35;
						switch (style) {
							case 1 -> { // stardust: twinkling sparks scattered along the path
								double j = 0.35 * (1 - fade);
								float tw = 0.5f + 0.5f * Mth.sin((float) (now / 90.0 + pt[4] * 40));
								q(out, cam, x + (pt[4] - 0.5) * j, y + Math.sin(pt[4] * 20) * j - age * 0.3, z + (rnd(i) - 0.5) * j, col, fade * tw, 0.09f + 0.06f * (float) pt[4], SPARK);
							}
							case 2 -> { // comet: bright, thick, fading quickly
								q(out, cam, x, y, z, col, fade * 0.55f, (float) (0.32 * (1 - age) + 0.05), GLOW);
								q(out, cam, x, y, z, 0xFFFFFF, fade * 0.8f, (float) (0.10 * (1 - age) + 0.02), GLOW);
							}
							default -> { // ribbon: two strands twisting around each other
								double r = 0.22 * (0.4 + 0.6 * (1 - age));
								double ox = Math.cos(ph) * r, oy = Math.sin(ph) * r * 0.6;
								q(out, cam, x + ox, y + oy, z - ox * 0.5, col, fade * 0.5f, 0.16f * (float) (1 - age * 0.6), GLOW);
								q(out, cam, x - ox, y - oy, z + ox * 0.5, Ui.mix(col, 0x8E9096, 0.5f), fade * 0.35f, 0.12f * (float) (1 - age * 0.6), GLOW);
							}
						}
					}
				}
				prev = pt;
				i++;
			}
		} else TRAIL.clear();
		// atmosphere motes + sakura petals around the camera
		boolean motes = Modules.on("atmosphere"), petals = Modules.on("sakura_drift");
		long nn = System.nanoTime();
		float dt = lastAirNanos == 0 ? 0.016f : Math.min(0.05f, (nn - lastAirNanos) / 1e9f);
		lastAirNanos = nn;
		if (!motes && !petals) { AIR.clear(); return; }
		int want = (motes ? (Modules.on("lite_vfx") ? 25 : 55) : 0) + (petals ? (Modules.on("lite_vfx") ? 20 : 45) : 0);
		int haveM = 0, haveP = 0;
		for (double[] a : AIR) if (a[9] == 0) haveM++; else haveP++;
		while (AIR.size() < want) {
			boolean petal = petals && (!motes || haveP < want / 2) ? true : false;
			if (!petal && !motes) petal = true;
			double ang = RNG.nextDouble() * Math.PI * 2, d = 1.5 + RNG.nextDouble() * 9;
			double x = cam.x + Math.cos(ang) * d, z = cam.z + Math.sin(ang) * d;
			double y = petal ? cam.y + 3 + RNG.nextDouble() * 5 : cam.y - 2 + RNG.nextDouble() * 5;
			AIR.add(new double[]{x, y, z, (RNG.nextDouble() - 0.5) * 0.15, petal ? -(0.45 + RNG.nextDouble() * 0.4) : (RNG.nextDouble() - 0.4) * 0.08,
					(RNG.nextDouble() - 0.5) * 0.15, now, petal ? 9000 + RNG.nextDouble() * 5000 : 5000 + RNG.nextDouble() * 6000, RNG.nextDouble(), petal ? 1 : 0});
			if (petal) haveP++; else haveM++;
		}
		var it = AIR.iterator();
		double tt = now / 1000.0;
		while (it.hasNext()) {
			double[] a = it.next();
			boolean petal = a[9] == 1;
			if ((petal && !petals) || (!petal && !motes)) { it.remove(); continue; }
			double sway = petal ? Math.sin(tt * 1.3 + a[8] * 20) * 0.35 : Math.sin(tt * 0.7 + a[8] * 9) * 0.06;
			a[0] += (a[3] + sway) * dt;
			a[1] += a[4] * dt;
			a[2] += (a[5] + (petal ? Math.cos(tt * 1.1 + a[8] * 13) * 0.25 : 0)) * dt;
			double age = (now - a[6]) / a[7];
			double dx = a[0] - cam.x, dz = a[2] - cam.z;
			if (age > 1 || dx * dx + dz * dz > 160 || a[1] < cam.y - 6) { it.remove(); continue; }
			float fade = (float) (Math.min(1, age * 5) * Math.min(1, (1 - age) * 4));
			if (petal) q(out, cam, a[0], a[1], a[2], 0xF4F4F5, fade * 0.85f, 0.11f + 0.05f * (float) a[8], PETAL);
			else {
				float tw = 0.55f + 0.45f * Mth.sin((float) (tt * 2 + a[8] * 30));
				q(out, cam, a[0], a[1], a[2], 0xFFFFFF, fade * 0.45f * tw, 0.05f + 0.04f * (float) a[8], GLOW);
			}
		}
	}

	public static void render(PoseStack pose, Vec3 cam, float partial) throws Exception {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null || mc.player == null || Modules.vfx() == 0) return;
		lastDraw = System.currentTimeMillis();
		double t = (mc.level.getGameTime() + partial) / 20.0;
		List<float[]> quads = new ArrayList<>(); // x,y,z of centre, r,g,b,a, size, tex
		for (Player p : mc.level.players()) {
			if (p.isInvisible() || p.distanceTo(mc.player) > 40) continue;
			int acc;
			if (p == mc.player) {
				if (!Modules.on("accessory") || mc.options.getCameraType().isFirstPerson()) continue;
				acc = KyotoConfig.get().accessory;
			} else {
				if (!Modules.on("others_cosmetics") || KyotoConfig.get().hiddenCosmeticsPlayers.contains(p.getUUID().toString())) continue;
				KyotoNet.Cosmetic c = KyotoNet.COSMETICS.get(p.getUUID().toString());
				if (c == null) continue;
				acc = c.accessory();
			}
			if (acc <= 0) continue;
			Vec3 pos = p.getPosition(partial).subtract(cam);
			float th = (float) Math.toRadians(Mth.rotLerp(partial, p.yBodyRotO, p.yBodyRot));
			float fx = -Mth.sin(th), fz = Mth.cos(th), lx = Mth.cos(th), lz = Mth.sin(th);
			float hgt = p.getBbHeight() * (p.isCrouching() ? 0.92f : 1f);
			for (P q : points(acc, t + (p.getId() % 7), hgt)) {
				quads.add(new float[]{(float) pos.x + lx * q.x + fx * q.z, (float) pos.y + q.y, (float) pos.z + lz * q.x + fz * q.z,
						q.rgb >> 16 & 255, q.rgb >> 8 & 255, q.rgb & 255, q.a * 255, q.size, q.tex});
			}
		}
		trailAndAir(quads, mc, cam, partial);
		if (quads.isEmpty()) return;
		// back to front, so the soft edges blend nicely
		quads.sort((a, b) -> Float.compare(b[0] * b[0] + b[1] * b[1] + b[2] * b[2], a[0] * a[0] + a[1] * a[1] + a[2] * a[2]));
		Matrix4f m = pose.last().pose();
		for (int tex = 0; tex < TEX.length; tex++) {
			Object type = WorldScreens.renderType(TEX[tex]);
			if (type == null) return;
			VertexConsumer vc = null;
			for (float[] q : quads) {
				if ((int) q[8] != tex) continue;
				if (vc == null) vc = WorldScreens.vertexBuffer(type);
				float len = (float) Math.sqrt(q[0] * q[0] + q[1] * q[1] + q[2] * q[2]);
				if (len < 0.05f) continue;
				float tx = -q[0] / len, ty = -q[1] / len, tz = -q[2] / len;       // towards the camera
				float rl = (float) Math.sqrt(tz * tz + tx * tx); if (rl < 1e-4f) rl = 1e-4f;
				float rx = tz / rl, rz = -tx / rl;                                   // right = up x toCam
				float ux = ty * rz, uy = tz * rx - tx * rz, uz = -ty * rx;           // up = toCam x right
				float s = q[7] / 2;
				int r = (int) q[3], g = (int) q[4], b = (int) q[5], a = (int) q[6];
				float[][] c = {{-1, -1, 0, 1}, {1, -1, 1, 1}, {1, 1, 1, 0}, {-1, 1, 0, 0}};
				for (float[] k : c) {
					vc.addVertex(m, q[0] + (rx * k[0] + ux * k[1]) * s, q[1] + uy * k[1] * s, q[2] + (rz * k[0] + uz * k[1]) * s)
							.setColor(r, g, b, a).setUv(k[2], k[3]).setOverlay(WorldScreens.OVERLAY).setLight(WorldScreens.LIGHT).setNormal(0f, 1f, 0f);
				}
			}
			if (vc != null) WorldScreens.flushBuffers();
		}
	}
}
