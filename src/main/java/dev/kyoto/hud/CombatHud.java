package dev.kyoto.hud;

import dev.kyoto.feature.Combat;
import dev.kyoto.module.Modules;
import dev.kyoto.util.Anim;
import dev.kyoto.util.Compat;
import dev.kyoto.util.Proj;
import dev.kyoto.util.Ui;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * v6 combat HUD: hitboxes, damage numbers, totem pops, target card, cooldown, combat timer,
 * arrows, kills/deaths and the item counter. All of it only DISPLAYS information the game already
 * gives your client - nothing aims, clicks or swaps for you.
 */
public final class CombatHud {
	private CombatHud() {}

	/* ---------------- state fed from ticks / packets ---------------- */

	private record Popup(Vec3 pos, String text, int color, long born) {}

	private static final List<Popup> POPUPS = new ArrayList<>();
	private static final Map<Integer, Float> LAST_HEALTH = new HashMap<>();
	/** name -> pops this fight */
	public static final Map<String, Integer> TOTEM_POPS = new HashMap<>();
	private static long lastTotemAt = 0;
	public static int kills = 0, deaths = 0;
	private static boolean wasDead = false;
	public static long lastCombatAt = 0;
	private static int lastHurt = 0;

	public static void tick(Minecraft mc) {
		LocalPlayer p = mc.player;
		if (p == null || mc.level == null) {
			LAST_HEALTH.clear();
			return;
		}
		// deaths
		boolean dead = p.isDeadOrDying();
		if (dead && !wasDead) deaths++;
		wasDead = dead;
		if (p.hurtTime > 0 && lastHurt == 0) lastCombatAt = System.currentTimeMillis();
		lastHurt = p.hurtTime;
		if (System.currentTimeMillis() - Combat.lastHitAt < 100) lastCombatAt = System.currentTimeMillis();
		// forget pops after a quiet minute
		if (!TOTEM_POPS.isEmpty() && System.currentTimeMillis() - lastTotemAt > 60000) TOTEM_POPS.clear();

		// damage numbers: watch health of nearby living things
		if (!Modules.on("damage_numbers")) {
			LAST_HEALTH.clear();
			return;
		}
		Map<Integer, Float> seen = new HashMap<>();
		for (Entity e : mc.level.entitiesForRendering()) {
			if (!(e instanceof LivingEntity le) || e == p || e.distanceTo(p) > 24) continue;
			float hp = le.getHealth();
			Float before = LAST_HEALTH.get(e.getId());
			if (before != null && hp < before - 0.01f && hp >= 0) {
				float lost = before - hp;
				boolean hearts = Modules.opt("dmg.style") == 0;
				String txt = hearts ? String.format("-%.1f ❤", lost / 2f) : String.format("-%.1f", lost);
				int col = e == Combat.target ? 0xFF6B6B : 0xFFB3B3;
				Vec3 at = e.position().add((Math.random() - 0.5) * 0.6, e.getBbHeight() + 0.2, (Math.random() - 0.5) * 0.6);
				POPUPS.add(new Popup(at, txt, col, System.currentTimeMillis()));
				if (POPUPS.size() > 40) POPUPS.remove(0);
			}
			seen.put(e.getId(), hp);
		}
		LAST_HEALTH.clear();
		LAST_HEALTH.putAll(seen);
	}

	/** Called from the entity-event packet hook (event 35 = totem of undying used). */
	public static void onTotem(Entity e) {
		if (e == null || !Modules.on("totem_tracker")) return;
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || e.distanceTo(mc.player) > 64) return;
		String name = e == mc.player ? "You" : e.getName().getString();
		int n = TOTEM_POPS.merge(name, 1, Integer::sum);
		lastTotemAt = System.currentTimeMillis();
		Notifications.push(name + (e == mc.player ? " popped a totem" : " popped a totem"), n + (n == 1 ? " pop" : " pops") + " this fight",
				Ui.INK, 2600, e instanceof Player && e != mc.player ? name : null, "✦");
	}

	public static void onKill() {
		kills++;
	}

	/* ---------------- drawing ---------------- */

	public static void render(GuiGraphicsExtractor g, Minecraft mc, int w, int h, float partial) {
		LocalPlayer p = mc.player;
		if (p == null) return;
		if (Modules.on("hitboxes")) hitboxes(g, mc, w, h, partial);
		if (Modules.on("damage_numbers")) popups(g, w, h, partial);
		if (Modules.on("target_hud")) target(g, mc, w, h);
		if (Modules.on("cooldown_indicator")) cooldown(g, p, w, h);
		int y = h - 60;
		if (Modules.on("combat_timer")) y = combatTimer(g, w, y);
		if (Modules.on("arrow_counter")) y = arrows(g, p, w, y);
		if (Modules.on("item_counter")) y = itemCount(g, p, w, y);
		if (Modules.on("session_kd")) kd(g, w, h);
	}

	private static int hitboxColour(Entity e) {
		return switch (Modules.opt("hitbox.color")) {
			case 1 -> 0xFFFFFF;
			case 2 -> 0xFF5D5D;
			case 3 -> 0xFFFFFF;
			case 4 -> Ui.accent();
			case 5 -> java.awt.Color.HSBtoRGB((System.currentTimeMillis() % 3000) / 3000f + e.getId() * 0.07f, 0.65f, 1f) & 0xFFFFFF;
			case 6 -> 0xD0D0D0;
			case 7 -> 0x5CA8FF;
			case 8 -> { // the colour of their team (name colour on the scoreboard)
				Integer c = e.getTeamColor();
				yield c == null || c == Ui.INK ? Ui.INK : c;
			}
			case 9 -> e instanceof LivingEntity le ? Anim.healthColor(le.getHealth() / Math.max(1f, le.getMaxHealth())) : 0xE6E8EC;
			default -> Ui.INK;
		};
	}

	private static void hitboxes(GuiGraphicsExtractor g, Minecraft mc, int w, int h, float partial) {
		int who = Modules.opt("hitbox.who");
		double range = new double[]{16, 32, 48}[Modules.opt("hitbox.range")];
		Vec3 eye = Proj.cameraPos(partial);
		boolean firstPerson = mc.options.getCameraType().isFirstPerson();
		for (Entity e : mc.level.entitiesForRendering()) {
			if (e == mc.player && firstPerson) continue;
			if (e.isInvisible()) continue;
			boolean isPlayer = e instanceof Player;
			if (who == 0 && !isPlayer) continue;
			if (who == 1 && !(e instanceof LivingEntity)) continue;
			if (e.distanceTo(mc.player) > range) continue;
			Vec3 pos = e.getPosition(partial);
			Vec3 off = pos.subtract(e.position());
			AABB b = e.getBoundingBox().move(off);
			// never show anyone through walls
			Vec3 center = b.getCenter(), top = new Vec3(center.x, b.maxY - 0.05, center.z);
			if (e != mc.player && Compat.blockedFromCamera(eye, center) && Compat.blockedFromCamera(eye, top)) continue;
			float[][] c = new float[8][];
			int i = 0;
			boolean ok = true;
			for (int xi = 0; xi < 2; xi++) for (int yi = 0; yi < 2; yi++) for (int zi = 0; zi < 2; zi++) {
				c[i] = Proj.project(new Vec3(xi == 0 ? b.minX : b.maxX, yi == 0 ? b.minY : b.maxY, zi == 0 ? b.minZ : b.maxZ), partial, w, h);
				if (c[i] == null) ok = false;
				i++;
			}
			if (!ok) continue;
			float dist = (float) e.distanceTo(mc.player);
			int argb = Anim.argb(hitboxColour(e), Math.max(0.35f, 1f - dist / (float) range * 0.6f));
			int[][] edges = {{0, 1}, {2, 3}, {4, 5}, {6, 7}, {0, 2}, {1, 3}, {4, 6}, {5, 7}, {0, 4}, {1, 5}, {2, 6}, {3, 7}};
			for (int[] ed : edges) Compat.line(g, c[ed[0]][0], c[ed[0]][1], c[ed[1]][0], c[ed[1]][1], 1f, argb);
			if (Modules.opt("hitbox.look") == 0 && e instanceof LivingEntity) {
				Vec3 from = e.getEyePosition(partial);
				Vec3 to = from.add(e.getViewVector(partial).scale(2.0));
				float[] a = Proj.project(from, partial, w, h), bb = Proj.project(to, partial, w, h);
				if (a != null && bb != null) Compat.line(g, a[0], a[1], bb[0], bb[1], 1f, Anim.argb(0x7FB8FF, 0.9f));
			}
		}
	}

	private static void popups(GuiGraphicsExtractor g, int w, int h, float partial) {
		long now = System.currentTimeMillis();
		Iterator<Popup> it = POPUPS.iterator();
		while (it.hasNext()) {
			Popup pp = it.next();
			long age = now - pp.born();
			if (age > 1100) {
				it.remove();
				continue;
			}
			float t = age / 1100f;
			Vec3 at = pp.pos().add(0, t * 0.8, 0);
			float[] s = Proj.project(at, partial, w, h);
			if (s == null) continue;
			float scale = (float) Math.max(0.5, Math.min(1.6, 6 / s[2])) * (1f + 0.3f * (1 - Anim.easeOutCubic(t * 4)));
			float a = t < 0.7f ? 1f : 1 - (t - 0.7f) / 0.3f;
			g.pose().pushMatrix();
			g.pose().translate(s[0], s[1]);
			g.pose().scale(scale, scale);
			Ui.textShadow(g, pp.text(), -Ui.width(pp.text()) / 2, -4, Anim.argb(pp.color(), a));
			g.pose().popMatrix();
		}
	}

	private static void target(GuiGraphicsExtractor g, Minecraft mc, int w, int h) {
		Entity t = Combat.target;
		if (t == null || mc.player == null) return;
		float a = Anim.clamp01(1 - (System.currentTimeMillis() - Combat.targetAt - 5000) / 1000f);
		String name = t.getName().getString();
		boolean showHp = Modules.opt("target.health") == 0 && t instanceof LivingEntity;
		boolean showArmor = Modules.opt("target.armor") == 0 && t instanceof LivingEntity;
		int bw = Math.max(120, Ui.widthBold(name) + 40);
		int bh = 28 + (showHp ? 8 : 0) + (showArmor ? 10 : 0);
		int x = w / 2 + 40, y = h / 2 + 18;
		Ui.hud(g, x, y, x + bw, y + bh, 7, a);
		if (t instanceof Player) Ui.head(g, name, x + 5, y + 5, 18, a);
		else Ui.round(g, x + 5, y + 5, x + 23, y + 23, 4, Anim.argb(Ui.accent(), 0.4f * a));
		Ui.bold(g, Ui.ellipsize(name, bw - 34), x + 28, y + 5, Anim.argb(Ui.TEXT, a));
		String info = String.format("%.1fm", t.distanceTo(mc.player)) + "  ·  " + Combat.combo + " hits";
		Ui.small(g, info, x + 28, y + 16, Anim.argb(Ui.MUTED, a));
		int cy = y + 27;
		if (showHp) {
			LivingEntity le = (LivingEntity) t;
			float hp = Anim.clamp01(le.getHealth() / Math.max(1, le.getMaxHealth()));
			float abs = le.getAbsorptionAmount();
			Ui.round(g, x + 5, cy, x + bw - 40, cy + 4, 2, Anim.argb(0x000000, 0.5f * a));
			Ui.round(g, x + 5, cy, x + 5 + Math.round((bw - 45) * hp), cy + 4, 2, Anim.argb(Anim.healthColor(hp), a));
			String hs = String.format("%.1f", le.getHealth() / 2f) + "❤" + (abs > 0 ? " +" + Math.round(abs / 2) : "");
			Ui.small(g, hs, x + bw - 36, cy - 1, Anim.argb(0xFFB3B3, a));
			cy += 8;
		}
		if (showArmor) {
			LivingEntity le = (LivingEntity) t;
			int ax = x + 5;
			for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
				ItemStack s = le.getItemBySlot(slot);
				float d = s.isEmpty() || !s.isDamageableItem() ? 0f : 1f - s.getDamageValue() / (float) Math.max(1, s.getMaxDamage());
				Ui.round(g, ax, cy + 1, ax + 18, cy + 5, 2, Anim.argb(Ui.INK, 0.08f * a));
				if (!s.isEmpty()) Ui.round(g, ax, cy + 1, ax + Math.max(2, Math.round(18 * d)), cy + 5, 2, Anim.argb(Anim.healthColor(d), a));
				ax += 21;
			}
			Ui.small(g, le.getArmorValue() + " armor", ax + 2, cy, Anim.argb(Ui.MUTED, a));
		}
		Integer pops = TOTEM_POPS.get(name);
		if (pops != null) {
			String ps = pops + " totem" + (pops == 1 ? "" : "s");
			Ui.small(g, ps, x + bw - 6 - Ui.widthSmall(ps), y + 6, Anim.argb(Ui.INK, a));
		}
	}

	private static void cooldown(GuiGraphicsExtractor g, LocalPlayer p, int w, int h) {
		float s = p.getAttackStrengthScale(0f);
		if (s >= 1f) return;
		int cx = w / 2, cy = h / 2 + 9;
		Ui.round(g, cx - 12, cy, cx + 12, cy + 2, 1, Anim.argb(0x000000, 0.5f));
		Ui.round(g, cx - 12, cy, cx - 12 + Math.max(1, Math.round(24 * s)), cy + 2, 1, Anim.argb(Ui.accent(), 0.9f));
	}

	private static int chip(GuiGraphicsExtractor g, int w, int y, String icon, String text, int col) {
		int tw = Ui.width(text) + 22;
		int x = w / 2 + 96;
		Ui.hud(g, x, y, x + tw, y + 13, 6, 0.9f);
		Ui.iconTex(g, icon, x + 4, y + 3, 7, Anim.argb(col, 1f));
		Ui.text(g, text, x + 15, y + 3, Anim.argb(Ui.TEXT, 1f));
		return y - 16;
	}

	private static int combatTimer(GuiGraphicsExtractor g, int w, int y) {
		int len = new int[]{10, 15, 20, 30}[Modules.opt("combat.timer")] * 1000;
		long left = len - (System.currentTimeMillis() - lastCombatAt);
		if (lastCombatAt == 0 || left <= 0) return y;
		return chip(g, w, y, "combat", "In combat " + (left / 1000 + 1) + "s", left < 3000 ? Ui.INK : 0xFF6B6B);
	}

	private static int arrows(GuiGraphicsExtractor g, LocalPlayer p, int w, int y) {
		ItemStack hand = p.getMainHandItem();
		if (!hand.is(Items.BOW) && !hand.is(Items.CROSSBOW) && !p.getOffhandItem().is(Items.BOW) && !p.getOffhandItem().is(Items.CROSSBOW)) return y;
		int n = 0;
		var inv = p.getInventory();
		for (int i = 0; i < inv.getContainerSize(); i++) {
			ItemStack s = inv.getItem(i);
			if (s.is(Items.ARROW) || s.is(Items.SPECTRAL_ARROW) || s.is(Items.TIPPED_ARROW)) n += s.getCount();
		}
		return chip(g, w, y, "crosshair", n + " arrows", n == 0 ? 0xFF6B6B : Ui.accent());
	}

	private static int itemCount(GuiGraphicsExtractor g, LocalPlayer p, int w, int y) {
		ItemStack hand = p.getMainHandItem();
		if (hand.isEmpty() || hand.getMaxStackSize() == 1) return y;
		int n = 0;
		var inv = p.getInventory();
		for (int i = 0; i < inv.getContainerSize(); i++) {
			ItemStack s = inv.getItem(i);
			if (ItemStack.isSameItem(s, hand)) n += s.getCount();
		}
		return chip(g, w, y, "tools", n + "x " + hand.getHoverName().getString(), Ui.accent());
	}

	private static void kd(GuiGraphicsExtractor g, int w, int h) {
		String s = kills + " K  ·  " + deaths + " D";
		int tw = Ui.width(s) + 10;
		int x = w / 2 - 91 - tw - 4, y = h - 22;
		Ui.hud(g, x, y, x + tw, y + 13, 6, 0.9f);
		Ui.text(g, s, x + 5, y + 3, Anim.argb(Ui.TEXT, 1f));
	}
}
