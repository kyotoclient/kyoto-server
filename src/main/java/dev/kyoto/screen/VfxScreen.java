package dev.kyoto.screen;

import dev.kyoto.config.KyotoConfig;
import dev.kyoto.feature.Vfx;
import dev.kyoto.module.Modules;
import dev.kyoto.util.Anim;
import dev.kyoto.util.Ui;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;

import java.util.ArrayList;
import java.util.List;

/** VFX creator: design the effect for every event (hit, kill, totem pop...). */
public class VfxScreen extends PanelScreen {
	private static String event = "kill";

	public VfxScreen(Screen parent) {
		super("VFX Creator", parent);
	}

	@Override
	protected String subtitle() {
		return "Pick an event and design its effect - the preview on the right updates live.";
	}

	private KyotoConfig.Vfx v() {
		return Vfx.get(event);
	}

	private void save() {
		KyotoConfig.save();
	}

	@Override
	protected void build() {
		// event tabs
		List<String> ids = new ArrayList<>(Vfx.EVENTS.keySet());
		int tx = px + 14, ty = py + 34;
		for (String id : ids) {
			String label = (id.equals(event) ? "▶ " : "") + Vfx.EVENTS.get(id);
			int w = Math.max(40, Ui.width(label) + 14);
			if (tx + w > px + pw - 14) {
				tx = px + 14;
				ty += 22;
			}
			btn(tx, ty, w, label, () -> event = id);
			tx += w + 4;
		}
		int y = ty + 24, step = 19;
		btn(px + 14, y, 112, "Effect: " + (v().enabled ? "ON" : "OFF"), () -> { v().enabled = !v().enabled; save(); });
		btn(px + 130, y, 114, "Glow layer: " + (v().glow ? "On" : "Off"), () -> { v().glow = !v().glow; save(); });
		y += step + 2;
		row(y, "Particle", () -> Vfx.PARTICLES[v().particle], () -> { v().particle = Math.floorMod(v().particle - 1, Vfx.PARTICLES.length); save(); },
				() -> { v().particle = (v().particle + 1) % Vfx.PARTICLES.length; save(); });
		y += step;
		row(y, "Shape", () -> Vfx.SHAPES[v().shape], () -> { v().shape = Math.floorMod(v().shape - 1, Vfx.SHAPES.length); save(); },
				() -> { v().shape = (v().shape + 1) % Vfx.SHAPES.length; save(); });
		y += step;
		row(y, "Amount", () -> String.valueOf(v().count), () -> { v().count = clamp(v().count - 4, 1, 120); save(); }, () -> { v().count = clamp(v().count + 4, 1, 120); save(); });
		y += step;
		row(y, "Speed", () -> String.valueOf(v().speed), () -> { v().speed = clamp(v().speed - 1, 1, 10); save(); }, () -> { v().speed = clamp(v().speed + 1, 1, 10); save(); });
		y += step;
		row(y, "Size / radius", () -> String.valueOf(v().size), () -> { v().size = clamp(v().size - 1, 1, 10); save(); }, () -> { v().size = clamp(v().size + 1, 1, 10); save(); });
		y += step;
		row(y, "Color", () -> Vfx.COLORS[v().colorMode], () -> { v().colorMode = Math.floorMod(v().colorMode - 1, 4); save(); }, () -> { v().colorMode = (v().colorMode + 1) % 4; save(); });
		y += step;
		row(y, "Hue", () -> Math.round(v().hue) + "°", () -> { v().hue = (v().hue + 345) % 360; save(); }, () -> { v().hue = (v().hue + 15) % 360; save(); });
		y += step;
		row(y, "Sound", () -> Vfx.SOUNDS[v().sound], () -> { v().sound = Math.floorMod(v().sound - 1, Vfx.SOUNDS.length); save(); },
				() -> { v().sound = (v().sound + 1) % Vfx.SOUNDS.length; save(); });

		btn(px + 14, py + ph - 26, 80, "Test in world", () -> {
			var p = this.minecraft == null ? null : this.minecraft.player;
			if (p != null) Vfx.play(v(), p.getX() + p.getLookAngle().x * 2.5, p.getEyeY() - 0.3, p.getZ() + p.getLookAngle().z * 2.5, Math.max(1, Modules.vfx()));
		});
		btn(px + 182, py + ph - 26, 74, "Replay", () -> previewAt = 0);
		btn(px + 98, py + ph - 26, 80, "Reset", () -> { KyotoConfig.get().vfx.put(event, Vfx.defaults(event)); save(); });
	}

	@Override
	protected String icon() {
		return "vfx";
	}

	/* ---------------- in-menu preview: a tiny 2D particle simulation of the real effect ---------------- */

	private static final class P {
		double x, y, z, vx, vy, vz;
		float life, max, size;
		int col, kind;
	}

	private final java.util.List<P> parts = new java.util.ArrayList<>();
	private final java.util.Random rnd = new java.util.Random();
	private long previewAt = 0, lastNanos = 0;
	private String previewKey = "";

	private void spawnPreview() {
		parts.clear();
		KyotoConfig.Vfx v = v();
		int count = Math.max(1, v.count * 2 / 2);
		for (int i = 0; i < count; i++) {
			double[] q = Vfx.shapePoint(v, i, count, rnd);
			P p = new P();
			p.x = q[0]; p.y = q[1]; p.z = q[2]; p.vx = q[3]; p.vy = q[4]; p.vz = q[5];
			p.max = 0.8f + rnd.nextFloat() * 0.9f;
			p.size = Math.max(1.2f, Math.min(4f, v.size * 0.45f));
			p.col = particleColour(v, i);
			p.kind = v.particle;
			parts.add(p);
		}
		if (v.glow) for (int i = 0; i < Math.max(3, count / 5); i++) {
			double[] q = Vfx.shapePoint(v, i * 5, count, rnd);
			P p = new P();
			p.x = q[0]; p.y = q[1]; p.z = q[2]; p.vx = q[3] * 0.5; p.vy = q[4] * 0.5 + 0.01; p.vz = q[5] * 0.5;
			p.max = 1.2f;
			p.size = 1.2f;
			p.col = Ui.INK;
			p.kind = 1;
			parts.add(p);
		}
	}

	/** Roughly the colour each Minecraft particle looks like. */
	private static int particleColour(KyotoConfig.Vfx v, int i) {
		return switch (v.particle) {
			case 1 -> 0xFFFFF4;
			case 2 -> i % 3 == 0 ? 0xFFE27A : 0xFF8A2A;
			case 3 -> 0x6EE7FF;
			case 4 -> 0xBFE8FF;
			case 5 -> 0xE9E3C9;
			case 6 -> 0x8A6BFF;
			case 7 -> 0xFF4D6D;
			case 8 -> i % 2 == 0 ? 0xFFE45C : 0x7BE36E;
			case 9, 10 -> 0xBDBDBD;
			case 11, 12 -> 0xE6E6E6;
			case 13 -> Ui.hsv((i * 37) % 360, 0.8f, 1f);
			case 14 -> 0xEAF6FF;
			case 15 -> 0x5CFFC8;
			case 16 -> 0xFFFFFF;
			case 17 -> 0x7FE6FF;
			case 18 -> 0x5CFF7A;
			case 19 -> 0xFFB347;
			default -> Vfx.color(v, i);
		};
	}

	@Override
	protected void drawContent(GuiGraphicsExtractor g, int mx, int my, float a) {
		int x1 = px + 262, y1 = py + 36, x2 = px + pw - 14, y2 = py + ph - 32;
		if (x2 - x1 < 110) return;
		Ui.round(g, x1, y1, x2, y2, 8, Anim.argb(Ui.GLASS, 0.95f * a));
		// floor grid for depth
		int cx = (x1 + x2) / 2, cy = y1 + (y2 - y1) * 2 / 3;
		for (int i = -4; i <= 4; i++) {
			g.fill(cx + i * 14 - 1, cy - 1, cx + i * 14, cy, Anim.argb(Ui.INK, 0.06f * a));
		}
		Ui.round(g, cx - 50, cy - 3, cx + 50, cy + 3, 3, Anim.argb(Ui.INK, 0.05f * a));
		String key = event + v().particle + "/" + v().shape + "/" + v().count + "/" + v().speed + "/" + v().size + "/" + v().colorMode + "/" + v().hue + v().glow;
		long now = System.currentTimeMillis();
		if (!key.equals(previewKey) || now - previewAt > 2200) {
			previewKey = key;
			previewAt = now;
			spawnPreview();
			lastNanos = 0;
		}
		long nn = System.nanoTime();
		float dt = lastNanos == 0 ? 0.016f : Math.min(0.05f, (nn - lastNanos) / 1e9f);
		lastNanos = nn;
		double scale = (y2 - y1) * 0.22; // pixels per block
		double yaw = now / 2400.0; // slow turntable so 3D shapes read well
		g.enableScissor(x1, y1, x2, y2);
		for (P p : parts) {
			p.life += dt;
			if (p.life > p.max) continue;
			double gravity = (p.kind == 3 || p.kind == 5 || p.kind == 12) ? -0.6 : (p.kind == 2 || p.kind == 3 ? 0.05 : -0.1);
			p.vy += gravity * dt;
			p.x += p.vx * dt * 20;
			p.y += p.vy * dt * 20;
			p.z += p.vz * dt * 20;
			p.vx *= 1 - dt * 1.5;
			p.vz *= 1 - dt * 1.5;
			double rx = p.x * Math.cos(yaw) - p.z * Math.sin(yaw);
			double rz = p.x * Math.sin(yaw) + p.z * Math.cos(yaw);
			double persp = 1 / (1 + rz * 0.18);
			int sx = (int) Math.round(cx + rx * scale * persp);
			int sy = (int) Math.round(cy - p.y * scale * persp + rz * scale * 0.25);
			float fade = Math.min(1f, (p.max - p.life) / 0.35f) * a;
			int sz = Math.max(1, Math.round(p.size * (float) persp));
			if (p.kind == 7) Ui.iconTex(g, "heart", sx - 3, sy - 3, 7, Anim.argb(p.col, fade));
			else if (p.kind == 13) Ui.icon(g, "♪", sx - 2, sy - 4, Anim.argb(p.col, fade));
			else {
				g.fill(sx - sz, sy - sz, sx + sz + 1, sy + sz + 1, Anim.argb(p.col, 0.18f * fade));
				g.fill(sx - sz / 2, sy - sz / 2, sx + (sz + 1) / 2 + 1, sy + (sz + 1) / 2 + 1, Anim.argb(p.col, fade));
			}
		}
		g.disableScissor();
		Ui.small(g, "Live preview  ·  " + Vfx.SHAPES[Math.min(v().shape, Vfx.SHAPES.length - 1)] + "  ·  " + Vfx.PARTICLES[Math.min(v().particle, Vfx.PARTICLES.length - 1)],
				x1 + 6, y2 - 10, Anim.argb(Ui.MUTED, a));
	}
}
