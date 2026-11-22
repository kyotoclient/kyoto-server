package dev.kyoto.servers;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.kyoto.config.KyotoConfig;
import dev.kyoto.hud.Notifications;
import dev.kyoto.util.Compat;
import net.minecraft.client.Minecraft;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.Items;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;

/**
 * "Any SMP" helpers (EssentialsX-style servers) + the Lifesteal / PvP SMP pack.
 * Homes/warps/balance/mail are read from what the server prints after YOU run /homes, /warps, /bal.
 */
public final class Smp {
	private Smp() {}

	public static final List<String> HOMES = new ArrayList<>(), WARPS = new ArrayList<>();
	public static double balance = -1;
	public static int unreadMail = 0;
	public static long afkSince = 0, lastMove = System.currentTimeMillis();
	private static double lx, lz;
	private static float lyaw;
	private static boolean afkWarned = false;
	private static boolean capturingRules = false;
	private static long rulesUntil = 0;
	private static final List<String> RULES = new ArrayList<>();

	// lifesteal
	public static double startHearts = -1;
	public static final Deque<String[]> FEED = new ArrayDeque<>(); // [text, time]
	private static boolean heartWarned = false;

	public static void onChat(String plain, boolean fromPlayer) {
		Matcher m;
		if ((m = Packs.match("homes", plain)) != null) {
			HOMES.clear();
			for (String s : m.group(1).split("[,\\s]+")) if (!s.isBlank()) HOMES.add(s.trim());
			saveList("homes", HOMES);
		}
		if ((m = Packs.match("warps", plain)) != null) {
			WARPS.clear();
			for (String s : m.group(1).split("[,\\s]+")) if (!s.isBlank()) WARPS.add(s.trim());
			saveList("warps", WARPS);
		}
		if (!fromPlayer && (m = Packs.match("bal", plain)) != null) balance = Packs.money(m.group(1));
		if ((m = Packs.match("mail", plain)) != null && Packs.on("smp_mail")) {
			unreadMail = Integer.parseInt(m.group(1));
			if (unreadMail > 0) Notifications.push("You've got mail", unreadMail + " unread - type /mail read", Notifications.INFO, 6000, null, "dm");
		}
		if ((m = Packs.match("dm_in", plain)) != null && Packs.on("smp_mail")) {
			if (dev.kyoto.util.Compat.currentScreen() == null) return;
			Notifications.push("Message from " + m.group(1), m.groupCount() >= 2 ? m.group(2) : "", Notifications.INFO, 5000, m.group(1), "dm");
		}
		if (Packs.matches("afk", plain)) afkSince = System.currentTimeMillis();
		if (Packs.matches("afk_off", plain)) afkSince = 0;
		// /rules: keep everything printed in the next 3 seconds
		if (capturingRules && System.currentTimeMillis() < rulesUntil) RULES.add(plain);
		// lifesteal fight feed
		if (Packs.is("lifesteal") && (m = Packs.match("fight", plain)) != null && Packs.on("ls_feed")) {
			FEED.addFirst(new String[]{m.group(2) + "  ⚔  " + m.group(1), String.valueOf(System.currentTimeMillis())});
			while (FEED.size() > 6) FEED.removeLast();
		}
		if (Packs.is("lifesteal") && Packs.matches("heart_gain", plain)) Compat.sound(SoundEvents.PLAYER_LEVELUP, 0.5f, 1.4f);
	}

	public static boolean onCommand(String cmd) {
		String c = cmd.toLowerCase(Locale.ROOT).trim();
		if (c.equals("rules") || c.startsWith("rules ")) {
			capturingRules = true;
			RULES.clear();
			rulesUntil = System.currentTimeMillis() + 3000;
			new Thread(() -> {
				try { Thread.sleep(3200); } catch (InterruptedException ignored) { }
				Minecraft.getInstance().execute(Smp::saveRules);
			}).start();
		}
		return true;
	}

	private static void saveRules() {
		capturingRules = false;
		if (RULES.isEmpty() || Packs.host.isEmpty()) return;
		JsonObject o = Packs.read("rules");
		JsonArray a = new JsonArray();
		for (String s : RULES) a.add(s);
		o.add(Packs.host, a);
		Packs.write("rules", o);
		Notifications.push("Rules saved", "Read them any time in the menu's server tab", Notifications.GOOD, 4000, null, "help");
	}

	public static List<String> savedRules() {
		List<String> out = new ArrayList<>();
		JsonObject o = Packs.read("rules");
		if (o.has(Packs.host)) for (var e : o.getAsJsonArray(Packs.host)) out.add(e.getAsString());
		return out;
	}

	private static void saveList(String kind, List<String> l) {
		if (Packs.host.isEmpty()) return;
		JsonObject o = Packs.read("smp_" + kind);
		JsonArray a = new JsonArray();
		for (String s : l) a.add(s);
		o.add(Packs.host, a);
		Packs.write("smp_" + kind, o);
	}

	public static void loadLists() {
		HOMES.clear();
		WARPS.clear();
		JsonObject h = Packs.read("smp_homes"), w = Packs.read("smp_warps");
		if (h.has(Packs.host)) for (var e : h.getAsJsonArray(Packs.host)) HOMES.add(e.getAsString());
		if (w.has(Packs.host)) for (var e : w.getAsJsonArray(Packs.host)) WARPS.add(e.getAsString());
		balance = -1;
		unreadMail = 0;
		afkSince = 0;
		startHearts = -1;
		FEED.clear();
	}

	public static void tick(Minecraft mc) {
		if (mc.player == null) return;
		long now = System.currentTimeMillis();
		// AFK kick warning: no movement and no turning for a while
		double dx = mc.player.getX() - lx, dz = mc.player.getZ() - lz;
		if (dx * dx + dz * dz > 0.01 || Math.abs(mc.player.getYRot() - lyaw) > 1) {
			lastMove = now;
			afkWarned = false;
		}
		lx = mc.player.getX();
		lz = mc.player.getZ();
		lyaw = mc.player.getYRot();
		double kickMin = new double[]{10, 15, 20, 30}[Math.min(3, dev.kyoto.module.Modules.opt("afkkick.min"))];
		if (Packs.on("smp_afk_warn") && !afkWarned && now - lastMove > (kickMin - 1) * 60000) {
			afkWarned = true;
			Notifications.push("You'll be AFK-kicked soon", "Move a little to stay on (" + Math.round(kickMin) + " min limit)", Notifications.WARN, 10000, null, "clock");
			Compat.sound(SoundEvents.NOTE_BLOCK_PLING.value(), 0.8f, 0.8f);
		}
		// lifesteal hearts
		if (Packs.is("lifesteal") || Packs.is("donut")) {
			double max = mc.player.getAttributeValue(Attributes.MAX_HEALTH) / 2.0;
			if (startHearts < 0) startHearts = max;
			double warnAt = KyotoConfig.get().packNums.getOrDefault("heart_warn", 5.0);
			if (Packs.on("ls_withdraw_warn") && max <= warnAt && !heartWarned) {
				heartWarned = true;
				Notifications.push("Low on hearts", "You're at " + (int) max + " hearts - don't withdraw any more", Notifications.WARN, 8000, null, "heart");
			} else if (max > warnAt) heartWarned = false;
		}
	}

	public static double hearts() {
		Minecraft mc = Minecraft.getInstance();
		return mc.player == null ? 0 : mc.player.getAttributeValue(Attributes.MAX_HEALTH) / 2.0;
	}

	public static int splashPotions() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) return 0;
		int n = 0;
		var inv = mc.player.getInventory();
		for (int i = 0; i < inv.getContainerSize(); i++) if (inv.getItem(i).is(Items.SPLASH_POTION)) n += inv.getItem(i).getCount();
		return n;
	}

	public static int pearls() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) return 0;
		int n = 0;
		var inv = mc.player.getInventory();
		for (int i = 0; i < inv.getContainerSize(); i++) if (inv.getItem(i).is(Items.ENDER_PEARL)) n += inv.getItem(i).getCount();
		return n;
	}

	/** Spawn protection border: set from where you stand (/kyoto spawnborder <radius>). */
	public static String setSpawnBorder(int radius) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || Packs.host.isEmpty()) return "Join a server first";
		var n = KyotoConfig.get().packNums;
		n.put("sb_x:" + Packs.host, mc.player.getX());
		n.put("sb_z:" + Packs.host, mc.player.getZ());
		n.put("sb_r:" + Packs.host, (double) radius);
		KyotoConfig.save();
		return radius <= 0 ? "Spawn border off" : "Spawn border set: " + radius + " blocks around here";
	}
}
