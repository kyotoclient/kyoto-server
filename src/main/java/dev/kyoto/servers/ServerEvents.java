package dev.kyoto.servers;

import dev.kyoto.config.KyotoConfig;
import dev.kyoto.hud.Notifications;
import dev.kyoto.module.Modules;
import dev.kyoto.util.Compat;
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.network.chat.Component;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;

/** Wires server packs into Minecraft: chat in/out, tooltips, ticks, joining and leaving. */
public final class ServerEvents {
	private ServerEvents() {}

	/** A command the confirm screen already OK'd, so we let it through once. */
	public static volatile String bypassOnce = null;
	public static boolean teamChat = false;
	private static boolean deathSeen = false;
	private static final Map<String, Long> RECENT = new HashMap<>();

	public static void register() {
		ClientReceiveMessageEvents.GAME.register((message, overlay) -> safe(() -> incoming(message.getString(), false, overlay)));
		ClientReceiveMessageEvents.CHAT.register((message, signed, sender, params, ts) -> safe(() -> incoming(message.getString(), true, false)));
		ClientReceiveMessageEvents.ALLOW_GAME.register((message, overlay) -> overlay || !hide(message.getString(), false));
		ClientReceiveMessageEvents.ALLOW_CHAT.register((message, signed, sender, params, ts) -> !hide(message.getString(), true));
		ClientReceiveMessageEvents.MODIFY_GAME.register((message, overlay) -> {
			if (overlay || Packs.current == null) return message;
			try {
				Component c = Donut.richNames(message);
				return Minis.teamColor(c);
			} catch (Throwable t) {
				return message;
			}
		});
		ClientSendMessageEvents.ALLOW_COMMAND.register(ServerEvents::outgoingCommand);
		ClientSendMessageEvents.ALLOW_CHAT.register(ServerEvents::outgoingChat);
		ItemTooltipCallback.EVENT.register((stack, context, type, lines) -> {
			try {
				Market.tooltip(stack, lines);
			} catch (Throwable ignored) {
			}
		});
	}

	private static void safe(Runnable r) {
		try {
			r.run();
		} catch (Throwable ignored) {
		}
	}

	private static void incoming(String raw, boolean fromPlayer, boolean actionBar) {
		if (Packs.current == null) return;
		String plain = raw.replaceAll("§.", "").trim();
		if (plain.isEmpty()) return;
		Donut.deathMessage(plain);
		if (Packs.is("donut") || Packs.is("lifesteal") || Packs.is("smp")) Donut.onChat(plain, fromPlayer);
		if (actionBar) return;
		Smp.onChat(plain, fromPlayer);
		Minis.onChat(plain);
		if (Packs.is("hypixel")) Hypixel.onChat(plain, fromPlayer);
	}

	/** Chat cleaner + Hypixel blockers. */
	private static boolean hide(String raw, boolean fromPlayer) {
		if (Packs.current == null) return false;
		try {
			String plain = raw.replaceAll("§.", "").trim();
			if (Packs.is("hypixel")) return hypixelHide(plain, fromPlayer);
			if (Packs.on("donut_chat_clean")) {
				String l = plain.toLowerCase(Locale.ROOT);
				int mode = Modules.opt("chatclean.what");
				if ((mode == 0 || mode == 2) && (l.matches("^\\[\\+]\\s*\\w+.*") || l.matches("^\\[-]\\s*\\w+.*") || l.endsWith("joined the game") || l.endsWith("left the game"))) return true;
				if ((mode == 1 || mode == 2) && (l.contains("listed") && l.contains("auction") || l.contains("discord.gg") && !fromPlayer || l.contains("/store") || l.contains("store.") && !fromPlayer)) return true;
				// exact repeats within 5 seconds
				long now = System.currentTimeMillis();
				Long last = RECENT.get(plain);
				RECENT.put(plain, now);
				if (RECENT.size() > 200) RECENT.clear();
				if (last != null && now - last < 5000) return true;
			}
		} catch (Throwable ignored) {
		}
		return false;
	}

	private static boolean hypixelHide(String plain, boolean fromPlayer) {
		if (Packs.on("hypixel_lobby_filter") && Packs.matches("lobby_spam", plain)) return true;
		if (Packs.on("hypixel_ad_filter") && fromPlayer && Packs.matches("ad_spam", plain)) return true;
		return Packs.on("hypixel_ad_filter") && (Packs.matches("guild_motd", plain) || Packs.matches("friend_status", plain));
	}

	/** @return false to stop the command */
	private static boolean outgoingCommand(String cmd) {
		if (cmd.equals(bypassOnce)) {
			bypassOnce = null;
			return true;
		}
		if (Packs.current == null) return true;
		try {
			String c = cmd.toLowerCase(Locale.ROOT).trim();
			// pay confirm
			if (Packs.on("donut_pay_confirm") && c.matches("^(pay|eco give|money pay)\\s+\\w+\\s+\\S+.*")) {
				String[] p = cmd.trim().split("\\s+");
				double amt = Packs.money(p[p.length - 1]);
				double limit = new double[]{1e5, 1e6, 1e7, 1e8}[Math.min(3, Modules.opt("payconfirm.over"))];
				if (amt >= limit) {
					String who = p[p.length - 2];
					Minecraft.getInstance().execute(() -> Compat.openScreen(new PackScreens.Confirm("Check this payment",
							"Pay " + Packs.fmt(amt) + " to " + who + "?", "That's " + String.format(Locale.ROOT, "%,.0f", amt) + " exactly", cmd, "Pay", 0xFFD36B)));
					return false;
				}
			}
			// commands that fail while you're in combat
			if (Packs.on("donut_combat_block") && Donut.combatUntil > System.currentTimeMillis() && c.matches("^(home|homes|tpa|tpahere|spawn|warp|rtp|afk|back)\\b.*")) {
				long left = (Donut.combatUntil - System.currentTimeMillis() + 999) / 1000;
				Compat.addChat(Component.literal("⚠ Kyoto: you're in combat for " + left + "s more - /" + c.split(" ")[0] + " won't work yet. Send it again to try anyway.").withStyle(ChatFormatting.GOLD));
				bypassOnce = cmd;
				return false;
			}
			Donut.onCommand(cmd);
			Smp.onCommand(cmd);
			Hypixel.onCommand(cmd);
		} catch (Throwable ignored) {
		}
		return true;
	}

	/** Team chat mode: everything you type goes to your team until you press the key again. */
	private static boolean outgoingChat(String msg) {
		if (!teamChat || Packs.current == null || msg.startsWith("/")) return true;
		String cmd = new String[]{"team chat", "tc", "t chat", "teamchat", "party chat", "pc"}[Modules.opt("teamchat.cmd")];
		Minecraft.getInstance().execute(() -> Compat.sendCommand(cmd + " " + msg));
		return false;
	}

	public static void toggleTeamChat() {
		if (Packs.current == null) return;
		teamChat = !teamChat;
		Notifications.push(teamChat ? "Team chat ON" : "Team chat off", teamChat ? "Your messages go to your team" : "Back to everyone", teamChat ? Notifications.GOOD : Notifications.INFO, 2500, null, "team");
	}

	/* ---------------- ticks ---------------- */

	public static void tick(Minecraft mc) {
		if (Packs.current == null) return;
		try {
			ServerData.tick(mc);
			Market.tick(mc);
			if (Market.menu == Market.Menu.NONE && Compat.currentScreen() != null && Compat.currentScreen().getTitle().getString().toLowerCase(Locale.ROOT).contains("spawner"))
				Donut.openedSpawner(Compat.currentScreen().getTitle().getString());
			Donut.tick(mc);
			Smp.tick(mc);
			Hypixel.tick(mc);
			Minis.tick(mc);
			StreamLive.tick(mc);
			boolean dead = Compat.currentScreen() instanceof DeathScreen;
			if (dead && !deathSeen) Donut.onDeath(mc, "");
			deathSeen = dead;
		} catch (Throwable ignored) {
		}
	}

	public static void onJoin(Minecraft mc) {
		Donut.reset();
		Hypixel.reset();
		Minis.reset();
		Packs.onJoin(mc);
		Smp.loadLists();
		Packs.applyKeys();
		teamChat = false;
	}

	public static void onLeave() {
		Market.save();
		Packs.onLeave();
		teamChat = false;
	}

	/** Matcher helper for other classes. */
	static Matcher m(String key, String s) {
		return Packs.match(key, s);
	}
}
