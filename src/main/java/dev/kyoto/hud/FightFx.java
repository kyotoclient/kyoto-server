package dev.kyoto.hud;

import dev.kyoto.module.Modules;
import dev.kyoto.ui.Sounds;
import dev.kyoto.util.Anim;
import dev.kyoto.util.Compat;
import dev.kyoto.util.Proj;
import dev.kyoto.util.Ui;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;

/**
 * PvP feedback from the Fight page: the kill banner, health bars over players and mobs, shield status and the death recap.
 * Only shows information - nothing here plays for you.
 */
public final class FightFx {
	private FightFx() {}

	/* ---------------- kill banner ---------------- */

	private static String bannerName = null;
	private static long bannerAt = 0;
	private static int streak = 0;
	private static long lastKillAt = 0;

	public static void onKill(LivingEntity victim) {
		long now = System.currentTimeMillis();
		streak = now - lastKillAt < 90_000 ? streak + 1 : 1;
		lastKillAt = now;
		if (!Modules.on("kill_banner")) return;
		bannerName = victim.getName().getString();
		bannerAt = now;
		if (Modules.opt("killbanner.sound") == 0) Sounds.play(Sounds.KILL);
	}

	private static void banner(GuiGraphicsExtractor g, int w, int h) {
		if (bannerName == null) return;
		long age = System.currentTimeMillis() - bannerAt;
		if (age > 3200) {
			bannerName = null;
			return;
		}
		float in = Anim.easeOutBack(Anim.clamp01(age / 380f));
		float out = 1 - Anim.easeInCubic(Anim.clamp01((age - 2700) / 500f));
		float al = Math.min(Anim.clamp01(age / 200f), out);
		String top = streak > 1 ? streakName(streak) : "ELIMINATED";
		String name = bannerName;
		int bw = Math.max(Ui.widthBold(name) + 60, Ui.widthSmall(top) + 60), bh = 30;
		int where = Modules.opt("killbanner.where");
		int y = where == 1 ? h / 2 - 60 : where == 2 ? h - 96 : 34;
		int x = w / 2 - bw / 2;
		g.pose().pushMatrix();
		g.pose().translate(w / 2f, y + bh / 2f + (1 - in) * -10);
		float sc = 0.85f + 0.15f * in;
		g.pose().scale(sc, sc);
		g.pose().translate(-w / 2f, -(y + bh / 2f));
		Ui.glass(g, x, y, x + bw, y + bh, 15, al);
		int acc = Ui.accent();
		// sweep of light across on entry
		float sweep = Anim.clamp01(age / 700f);
		int sx = Math.round(x + (bw + 40) * sweep - 20);
		g.enableScissor(x + 4, y + 1, x + bw - 4, y + bh - 1);
		g.fillGradient(sx - 14, y, sx + 14, y + bh, Anim.argb(0xFFFFFF, 0.35f * al * (1 - sweep)), Anim.argb(0xFFFFFF, 0f));
		g.disableScissor();
		Ui.circle(g, x + 16, y + bh / 2f, 8, Anim.argb(acc, 0.9f * al));
		Ui.iconTex(g, "sword", x + 12, y + bh / 2 - 4, 8, Anim.argb(0xFFFFFF, al));
		Ui.small(g, top, x + 30, y + 7, Anim.argb(acc, al));
		Ui.bold(g, name, x + 30, y + 16, Anim.argb(Ui.TEXT, al));
		if (streak > 1) {
			String s = "x" + streak;
			Ui.bold(g, s, x + bw - 10 - Ui.widthBold(s), y + 11, Anim.argb(acc, al));
		}
		g.pose().popMatrix();
	}

	private static String streakName(int n) {
		return switch (n) {
			case 2 -> "DOUBLE";
			case 3 -> "TRIPLE";
			case 4 -> "QUAD";
			default -> n + " IN A ROW";
		};
	}

	/* ---------------- death recap ---------------- */

	private static boolean wasDead = false;
	private static String[] recap = null; // who, with what, their health, your hits
	private static long recapAt = 0;

	public static void tick(Minecraft mc) {
		LocalPlayer p = mc.player;
		if (p == null) return;
		boolean dead = p.isDeadOrDying();
		if (dead && !wasDead) {
			streak = 0;
			if (Modules.on("fight_recap")) {
				LivingEntity by = p.getLastHurtByMob();
				if (by != null) {
					String item = by.getMainHandItem().isEmpty() ? "their hands" : by.getMainHandItem().getHoverName().getString();
					String hp = String.format(java.util.Locale.ROOT, "%.1f ❤ left", by.getHealth() / 2f);
					recap = new String[]{by.getName().getString(), item, hp, by instanceof Player ? by.getName().getString() : null};
				} else recap = new String[]{"The world", "", "", null};
				recapAt = System.currentTimeMillis();
			}
		}
		wasDead = dead;
		// smooth health for the bars
		if (Modules.on("health_bars") && mc.level != null) {
			long now = System.currentTimeMillis();
			for (Entity e : mc.level.entitiesForRendering()) {
				if (!(e instanceof LivingEntity le) || e == p || e.distanceTo(p) > 40) continue;
				float hp = Math.max(0, le.getHealth() / Math.max(1f, le.getMaxHealth()));
				Bar b = BARS.computeIfAbsent(e.getId(), k -> new Bar(hp));
				if (hp < b.target - 0.001f) b.ghostAt = now;
				b.target = hp;
				b.seen = now;
			}
			BARS.values().removeIf(b -> now - b.seen > 3000);
		}
	}

	private static void recap(GuiGraphicsExtractor g, int w, int h) {
		if (recap == null) return;
		long age = System.currentTimeMillis() - recapAt;
		if (age > 9000) {
			recap = null;
			return;
		}
		float al = Math.min(Anim.clamp01(age / 300f), 1 - Anim.clamp01((age - 8400) / 600f));
		float in = Anim.easeOutCubic(Anim.clamp01(age / 450f));
		int bw = 190, bh = recap[1].isEmpty() ? 34 : 52;
		int x = w - bw - 10 + Math.round((1 - in) * 30), y = h / 2 - bh / 2;
		Ui.glass(g, x, y, x + bw, y + bh, 12, al);
		Ui.small(g, "DEATH RECAP", x + 10, y + 8, Anim.argb(Ui.accent(), al));
		int tx = x + 10;
		if (recap[3] != null && Compat.drawHead(g, recap[3], x + 10, y + 18, 14)) tx = x + 28;
		Ui.bold(g, Ui.ellipsize(recap[0], bw - (tx - x) - 10), tx, y + 20, Anim.argb(Ui.TEXT, al));
		if (!recap[1].isEmpty()) {
			Ui.small(g, "with " + Ui.ellipsize(recap[1], Math.round((bw - 20) / 0.75f) - 30), x + 10, y + 34, Anim.argb(Ui.MUTED, al));
			Ui.small(g, recap[2], x + 10, y + 42, Anim.argb(Ui.MUTED, al));
		}
	}

	/* ---------------- health bars + shields ---------------- */

	private static final class Bar {
		float target, shown, ghost;
		long ghostAt, seen;

		Bar(float hp) {
			target = shown = ghost = hp;
		}
	}

	private static final Map<Integer, Bar> BARS = new HashMap<>();

	private static void bars(GuiGraphicsExtractor g, Minecraft mc, int w, int h, float partial) {
		LocalPlayer me = mc.player;
		int who = Modules.opt("healthbars.who"); // 0 players, 1 players + mobs, 2 only what I hit
		int nums = Modules.opt("healthbars.numbers");
		boolean shields = Modules.on("shield_status");
		Vec3 eye = Proj.cameraPos(partial);
		long now = System.currentTimeMillis();
		for (Entity e : mc.level.entitiesForRendering()) {
			if (!(e instanceof LivingEntity le) || e == me || le.isInvisible() || le.isDeadOrDying()) continue;
			boolean player = e instanceof Player;
			if (who == 0 && !player) continue;
			if (who == 2 && e != dev.kyoto.feature.Combat.target) continue;
			if (who == 1 && !player && !(e instanceof net.minecraft.world.entity.Mob)) continue;
			double dist = e.distanceTo(me);
			if (dist > 32) continue;
			Vec3 pos = e.getPosition(partial).add(0, e.getBbHeight() + (player ? 0.75 : 0.35), 0);
			if (Compat.blockedFromCamera(eye, pos)) continue;
			float[] s = Proj.project(pos, partial, w, h);
			if (s == null) continue;
			Bar b = Modules.on("health_bars") ? BARS.get(e.getId()) : null;
			boolean blocking = shields && le.isBlocking();
			if (b == null && !blocking) continue;
			float fade = (float) Math.min(1, (32 - dist) / 6);
			float sc = (float) Math.max(0.55, Math.min(1.0, 10.0 / Math.max(1, s[2])));
			int bw = Math.round(44 * sc), bh = Math.max(3, Math.round(5 * sc));
			int x = Math.round(s[0] - bw / 2f), y = Math.round(s[1]);
			if (blocking) {
				int sx = b == null ? Math.round(s[0]) - 4 : x - 12;
				Ui.circle(g, sx + 4, y + bh / 2f, 6, Anim.argb(0x7FB6F0, 0.9f * fade));
				Ui.iconTex(g, "shield", sx, y + bh / 2 - 4, 8, Anim.argb(0xFFFFFF, fade));
			}
			if (b == null) continue;
			b.shown += (b.target - b.shown) * 0.25f;
			if (now - b.ghostAt > 450) b.ghost += (b.shown - b.ghost) * 0.08f;
			if (b.ghost < b.shown) b.ghost = b.shown;
			int col = b.target > 0.5f ? Ui.mix(0xF2C14E, 0x6CCFAE, (b.target - 0.5f) * 2) : Ui.mix(0xE5484D, 0xF2C14E, b.target * 2);
			Ui.round(g, x - 1, y - 1, x + bw + 1, y + bh + 1, (bh + 2) / 2, Anim.argb(0x2B1D23, 0.45f * fade));
			int gw = Math.round(bw * Math.min(1, b.ghost));
			if (gw > 1) Ui.round(g, x, y, x + gw, y + bh, bh / 2, Anim.argb(0xFFFFFF, 0.75f * fade));
			int fw = Math.round(bw * Math.min(1, b.shown));
			if (fw > 1) Ui.round(g, x, y, x + fw, y + bh, bh / 2, Anim.argb(col, fade));
			if (nums != 2 && sc > 0.7f) {
				String t = nums == 0 ? String.format(java.util.Locale.ROOT, "%.1f ❤", le.getHealth() / 2f) : Math.round(b.target * 100) + "%";
				Ui.small(g, t, s[0] - Ui.widthSmall(t) / 2f, y - 9, Anim.argb(0xFFFFFF, 0.9f * fade));
			}
		}
	}

	public static void render(GuiGraphicsExtractor g, Minecraft mc, int w, int h, float partial) {
		if (mc.player == null || mc.level == null) return;
		if (Modules.on("health_bars") || Modules.on("shield_status")) {
			bars(g, mc, w, h, partial);
		}
		banner(g, w, h);
		recap(g, w, h);
	}
}
