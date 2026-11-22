package dev.kyoto.feature;

import dev.kyoto.config.KyotoConfig;
import dev.kyoto.module.Modules;
import dev.kyoto.util.Anim;
import dev.kyoto.util.Compat;
import dev.kyoto.util.Ui;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Fishing Assistant: tells you the moment a fish bites, and counts what you catch.
 *  - Ping: a clean sound + a small "!" over your crosshair.
 *  - Dopamine: the full show - a bouncing "!", rarity pop-ups, confetti, streaks and milestone celebrations.
 * It never reels in or casts for you: you still click. Totally legit.
 */
public final class Fishing {
	private Fishing() {}

	public enum Rarity {
		JUNK("Junk", 0x9AA0A8), COMMON("Common", 0xE6E8EC), UNCOMMON("Uncommon", 0x6EE7B7), RARE("Rare", 0x7FB8FF), LEGENDARY("Legendary", 0xFFC93C);
		public final String label; public final int color;
		Rarity(String l, int c) { label = l; color = c; }
	}

	/* ---------------- state ---------------- */
	private static boolean wasBiting = false, waitingCatch = false;
	private static long biteAt = 0, reeledAt = 0, lastCatchAt = 0;
	private static int hookTicksInWater = 0;
	private static Map<Item, Integer> before = null;
	public static int session = 0, sessionRare = 0, combo = 0, bestCombo = 0;

	private static final class Pop { String name; Rarity r; int combo; long at; int count; }
	private static final List<Pop> POPS = new ArrayList<>();
	private static final class Confetti { float x, y, vx, vy, rot, vr; int col; long born; }
	private static final List<Confetti> CONFETTI = new ArrayList<>();
	private static long milestoneAt = 0;
	private static int milestone = 0;

	private static EntityDataAccessor<Boolean> BITING = null;
	private static boolean bitingLookedUp = false;

	public static boolean on() { return Modules.on("fishing_assistant"); }
	private static boolean dopamine() { return Modules.opt("fish.mode") == 1; }

	/** Minecraft keeps "a fish is on the hook" in the bobber's synced data. Read it by name (the game isn't obfuscated). */
	@SuppressWarnings("unchecked")
	private static Boolean biting(FishingHook hook) {
		try {
			if (!bitingLookedUp) {
				bitingLookedUp = true;
				for (String n : new String[]{"DATA_BITING", "BITING"}) {
					try {
						var f = FishingHook.class.getDeclaredField(n);
						f.setAccessible(true);
						BITING = (EntityDataAccessor<Boolean>) f.get(null);
						break;
					} catch (Throwable ignored) { }
				}
			}
			if (BITING != null) return hook.getEntityData().get(BITING);
		} catch (Throwable ignored) { }
		return null;
	}

	public static void tick(Minecraft mc) {
		if (!on()) return;
		LocalPlayer p = mc.player;
		if (p == null) return;
		FishingHook hook = p.fishing;
		long now = System.currentTimeMillis();

		if (hook != null) {
			hookTicksInWater = hook.isInWater() ? hookTicksInWater + 1 : 0;
			Boolean b = biting(hook);
			// fallback: the bobber gets yanked under when something bites
			boolean bite = b != null ? b : hookTicksInWater > 20 && hook.getDeltaMovement().y < -0.06;
			if (bite && !wasBiting) onBite();
			wasBiting = bite;
		} else {
			if (wasBiting || now - biteAt < 1500) {
				// you reeled in right after a bite: whatever lands in your inventory now is the catch
				if (!waitingCatch && before != null) { waitingCatch = true; reeledAt = now; }
			}
			wasBiting = false;
			hookTicksInWater = 0;
		}

		if (waitingCatch) {
			if (now - reeledAt > 2500) { waitingCatch = false; before = null; }
			else {
				Map<Item, Integer> after = inventory(p);
				for (var e : after.entrySet()) {
					int was = before.getOrDefault(e.getKey(), 0);
					if (e.getValue() > was) {
						ItemStack stack = find(p, e.getKey());
						onCatch(stack, e.getValue() - was);
						waitingCatch = false;
						before = null;
						break;
					}
				}
			}
		}
		if (combo > 0 && now - lastCatchAt > 45000) combo = 0;
	}

	private static Map<Item, Integer> inventory(LocalPlayer p) {
		Map<Item, Integer> m = new HashMap<>();
		var inv = p.getInventory();
		for (int i = 0; i < inv.getContainerSize(); i++) {
			ItemStack s = inv.getItem(i);
			if (!s.isEmpty()) m.merge(s.getItem(), s.getCount(), Integer::sum);
		}
		return m;
	}

	private static ItemStack find(LocalPlayer p, Item item) {
		var inv = p.getInventory();
		for (int i = 0; i < inv.getContainerSize(); i++) if (inv.getItem(i).getItem() == item) return inv.getItem(i);
		return ItemStack.EMPTY;
	}

	private static void onBite() {
		biteAt = System.currentTimeMillis();
		LocalPlayer p = Minecraft.getInstance().player;
		before = p == null ? null : inventory(p);
		if (Modules.opt("fish.sound") == 0) {
			if (dopamine()) Compat.sound(ClickSounds.sound("fishing.fish_bite"), 0.9f, 1f);
			else Compat.sound(ClickSounds.sound("fishing.fish_ping"), 0.8f, 1f);
		}
	}

	public static Rarity rarity(ItemStack s) {
		if (s.isEmpty()) return Rarity.COMMON;
		String id = BuiltInRegistries.ITEM.getKey(s.getItem()).getPath();
		if (id.equals("enchanted_book")) return Rarity.LEGENDARY;
		if (id.equals("name_tag") || id.equals("saddle") || id.equals("nautilus_shell") || ((id.equals("bow") || id.equals("fishing_rod")) && s.isEnchanted())) return Rarity.RARE;
		if (id.equals("tropical_fish") || id.equals("pufferfish")) return Rarity.UNCOMMON;
		if (id.equals("cod") || id.equals("salmon")) return Rarity.COMMON;
		return Rarity.JUNK;
	}

	private static void onCatch(ItemStack stack, int count) {
		long now = System.currentTimeMillis();
		Rarity r = rarity(stack);
		combo = now - lastCatchAt < 45000 ? combo + 1 : 1;
		lastCatchAt = now;
		bestCombo = Math.max(bestCombo, combo);
		session++;
		if (r == Rarity.RARE || r == Rarity.LEGENDARY) sessionRare++;
		var st = KyotoConfig.get().fishStats;
		st.merge("total", 1, Integer::sum);
		st.merge(r.name().toLowerCase(), 1, Integer::sum);
		st.put("bestCombo", Math.max(st.getOrDefault("bestCombo", 0), combo));
		KyotoConfig.save();

		Pop pop = new Pop();
		pop.name = stack.isEmpty() ? "Something" : stack.getHoverName().getString();
		pop.r = r; pop.combo = combo; pop.at = now; pop.count = count;
		POPS.add(pop);
		while (POPS.size() > 3) POPS.remove(0);

		if (!dopamine()) {
			if (Modules.opt("fish.sound") == 0) Compat.sound(ClickSounds.sound("fishing.fish_common"), 0.5f, 1.2f);
			return;
		}
		String snd = switch (r) {
			case LEGENDARY -> "fishing.fish_legendary";
			case RARE -> "fishing.fish_rare";
			default -> "fishing.fish_common";
		};
		Compat.sound(ClickSounds.sound(snd), 0.9f, r == Rarity.JUNK ? 0.8f : 1f);
		if (combo > 1) Compat.sound(ClickSounds.sound("fishing.fish_combo"), 0.7f, Math.min(2f, 0.9f + combo * 0.08f));
		burst(r == Rarity.LEGENDARY ? 140 : r == Rarity.RARE ? 80 : r == Rarity.JUNK ? 10 : 36 + Math.min(40, combo * 6), r);
		for (int m : new int[]{10, 25, 50, 100, 250, 500, 1000}) if (st.getOrDefault("total", 0) == m) { milestone = m; milestoneAt = now; burst(160, Rarity.LEGENDARY); }
	}

	private static void burst(int n, Rarity r) {
		Minecraft mc = Minecraft.getInstance();
		int w = mc.getWindow().getGuiScaledWidth(), h = mc.getWindow().getGuiScaledHeight();
		int[] cols = r == Rarity.LEGENDARY ? new int[]{0xFFC93C, 0xFFE58A, 0xFFFFFF, 0xFF8FC8}
				: r == Rarity.RARE ? new int[]{0x7FB8FF, 0xB794FF, 0xFFFFFF}
				: new int[]{0x6EE7B7, 0x7FD8FF, 0xFFE14D, 0xFF8FC8, 0xFFFFFF};
		for (int i = 0; i < n; i++) {
			Confetti c = new Confetti();
			c.x = w / 2f + (float) (Math.random() - .5) * 30; c.y = h * 0.28f;
			double ang = Math.random() * Math.PI * 2, sp = 2 + Math.random() * 6;
			c.vx = (float) (Math.cos(ang) * sp); c.vy = (float) (Math.sin(ang) * sp - 4);
			c.rot = (float) (Math.random() * 6); c.vr = (float) ((Math.random() - .5) * .5);
			c.col = cols[(int) (Math.random() * cols.length)];
			c.born = System.currentTimeMillis();
			CONFETTI.add(c);
		}
	}

	public static String summary() {
		var st = KyotoConfig.get().fishStats;
		return "Fishing: " + session + " caught this session (" + sessionRare + " rare, best streak x" + bestCombo + "). All time: " + st.getOrDefault("total", 0)
				+ " caught, " + st.getOrDefault("legendary", 0) + " legendary, " + st.getOrDefault("rare", 0) + " rare, " + st.getOrDefault("junk", 0) + " junk.";
	}

	/* ---------------- drawing ---------------- */

	public static void render(GuiGraphicsExtractor g, int w, int h) {
		if (!on()) return;
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer p = mc.player;
		if (p == null) return;
		long now = System.currentTimeMillis();
		boolean fishing = p.fishing != null;

		// bite alert over the crosshair
		long sinceBite = now - biteAt;
		if (wasBiting || sinceBite < 900) {
			float k = Math.min(1f, sinceBite / 900f);
			if (dopamine()) {
				if (Modules.opt("fish.flash") == 0) {
					float fl = (1 - k) * 0.35f;
					int c = 0x7FD8FF;
					g.fillGradient(0, 0, w, 18, Anim.argb(c, fl), Anim.argb(c, 0));
					g.fillGradient(0, h - 18, w, h, Anim.argb(c, 0), Anim.argb(c, fl));
				}
				float bounce = (float) Math.abs(Math.sin(now / 90.0)) * 4;
				float s = 2.2f + (1 - k) * 1.4f;
				int ring = Math.round(10 + k * 26);
				outline(g, w / 2, h / 2, ring, Anim.argb(0x7FD8FF, (1 - k) * 0.9f));
				g.pose().pushMatrix();
				g.pose().translate(w / 2f, h / 2f - 22 - bounce);
				g.pose().scale(s, s);
				Ui.bold(g, "!", -Ui.widthBold("!") / 2, -4, Anim.argb(0xFFE14D, 1f));
				g.pose().popMatrix();
				String t = "REEL IN!";
				Ui.bold(g, t, w / 2 - Ui.widthBold(t) / 2, h / 2 + 14, Anim.argb(0xFFFFFF, wasBiting ? 1f : 1 - k));
			} else {
				Ui.round(g, w / 2 - 6, h / 2 - 26, w / 2 + 6, h / 2 - 12, 4, Anim.argb(0x000000, 0.5f));
				Ui.bold(g, "!", w / 2 - Ui.widthBold("!") / 2, h / 2 - 23, Anim.argb(0x7FD8FF, 1f));
			}
		}

		// catch pop-ups
		int py = Math.round(h * 0.18f);
		for (Iterator<Pop> it = POPS.iterator(); it.hasNext(); ) {
			Pop pop = it.next();
			long age = now - pop.at;
			long life = dopamine() ? (pop.r.ordinal() >= Rarity.RARE.ordinal() ? 4200 : 2600) : 1800;
			if (age > life) { it.remove(); continue; }
			float in = Anim.easeOutCubic(Math.min(1f, age / 260f)), out = age > life - 400 ? (life - age) / 400f : 1f;
			float a = in * out;
			String top = dopamine() ? pop.r.label.toUpperCase() + (pop.r == Rarity.LEGENDARY ? "!!" : pop.r == Rarity.RARE ? "!" : "") : "Caught";
			String name = pop.name + (pop.count > 1 ? " x" + pop.count : "");
			int cw = Math.max(Ui.widthBold(top), Ui.width(name)) + 34;
			if (dopamine() && pop.combo > 1) cw += 46;
			float pop2 = dopamine() ? 1f + (1 - Math.min(1f, age / 180f)) * 0.25f : 1f;
			g.pose().pushMatrix();
			g.pose().translate(w / 2f, py + (1 - in) * -12);
			g.pose().scale(pop2, pop2);
			int x = -cw / 2;
			if (dopamine() && pop.r.ordinal() >= Rarity.RARE.ordinal()) {
				float glow = 0.25f + 0.2f * (float) Math.sin(now / 120.0);
				Ui.round(g, x - 4, -4, x + cw + 4, 30, 10, Anim.argb(pop.r.color, glow * a));
			}
			Ui.glass(g, x, 0, x + cw, 26, 8, a);
			g.fill(x + 4, 4, x + 7, 22, Anim.argb(pop.r.color, a));
			Ui.bold(g, top, x + 13, 3, Anim.argb(pop.r.color, a));
			Ui.text(g, name, x + 13, 14, Anim.argb(0xFFFFFF, a));
			if (dopamine() && pop.combo > 1) {
				String c = "x" + pop.combo;
				int cx = x + cw - 40;
				Ui.round(g, cx, 5, cx + 34, 21, 6, Anim.argb(0xFF8FC8, 0.25f * a));
				Ui.bold(g, c, cx + 17 - Ui.widthBold(c) / 2, 6, Anim.argb(0xFF8FC8, a));
				Ui.small(g, "STREAK", cx + 17 - Ui.widthSmall("STREAK") / 2, 15, Anim.argb(0xFFB7D5, a));
			}
			g.pose().popMatrix();
			py += 32;
		}

		// confetti
		if (!CONFETTI.isEmpty()) {
			for (Iterator<Confetti> it = CONFETTI.iterator(); it.hasNext(); ) {
				Confetti c = it.next();
				float age = (now - c.born) / 1000f;
				if (age > 2.2f || c.y > h + 10) { it.remove(); continue; }
				c.vy += 0.22f; c.vx *= 0.985f; c.x += c.vx; c.y += c.vy; c.rot += c.vr;
				float a = Math.min(1f, (2.2f - age) / 0.6f);
				g.pose().pushMatrix();
				g.pose().translate(c.x, c.y);
				g.pose().rotate(c.rot);
				float flip = (float) Math.abs(Math.cos(c.rot * 2));
				g.fill(-2, Math.round(-1 - flip), 2, Math.round(1 + flip), Anim.argb(c.col, a));
				g.pose().popMatrix();
			}
		}

		// milestone banner
		if (dopamine() && now - milestoneAt < 3500 && milestone > 0) {
			float a = Math.min(1f, Math.min((now - milestoneAt) / 250f, (3500 - (now - milestoneAt)) / 400f));
			String t = milestone + " FISH CAUGHT!";
			g.pose().pushMatrix();
			g.pose().translate(w / 2f, h * 0.36f);
			g.pose().scale(2f, 2f);
			Ui.bold(g, t, -Ui.widthBold(t) / 2, 0, Anim.argb(Ui.hsv((now / 8f) % 360, 0.6f, 1f), a));
			g.pose().popMatrix();
		}

		// counter (while you have your rod out, or right after a catch)
		if (Modules.opt("fish.counter") == 0 && (fishing || now - lastCatchAt < 8000) && session > 0) {
			String a1 = session + " caught", a2 = sessionRare + " rare" + (combo > 1 ? "  ·  x" + combo + " streak" : "");
			int bw = Math.max(Ui.widthBold(a1), Ui.widthSmall(a2)) + 16;
			int x = w - bw - 6, y = h / 2 - 14;
			Ui.glass(g, x, y, x + bw, y + 24, 7, 0.9f);
			Ui.bold(g, a1, x + 8, y + 3, Anim.argb(0x7FD8FF, 1f));
			Ui.small(g, a2, x + 8, y + 14, Anim.argb(Ui.MUTED, 1f));
		}
	}

	private static void outline(GuiGraphicsExtractor g, int cx, int cy, int r, int argb) {
		g.fill(cx - r, cy - r, cx + r, cy - r + 1, argb);
		g.fill(cx - r, cy + r - 1, cx + r, cy + r, argb);
		g.fill(cx - r, cy - r, cx - r + 1, cy + r, argb);
		g.fill(cx + r - 1, cy - r, cx + r, cy + r, argb);
	}
}
