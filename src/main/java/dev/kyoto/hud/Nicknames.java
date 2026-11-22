package dev.kyoto.hud;

import dev.kyoto.config.KyotoConfig;
import dev.kyoto.module.Modules;
import dev.kyoto.util.Compat;
import dev.kyoto.util.Ui;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.world.entity.Entity;

import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/** Nickname Tags: names you give your friends. Only you see them (above heads, and in chat if you want). */
public final class Nicknames {
	private Nicknames() {}

	/** Sets (or with null removes) a nickname. Returns a message for chat. */
	public static String set(String player, String nick) {
		String key = null, real = player;
		var conn = Minecraft.getInstance().getConnection();
		if (conn != null) for (var i : conn.getOnlinePlayers()) {
			String n = Compat.profileName(i.getProfile());
			if (n.equalsIgnoreCase(player)) { key = i.getProfile().id().toString(); real = n; break; }
		}
		if (key == null) key = "name:" + player.toLowerCase(Locale.ROOT);
		var map = KyotoConfig.get().nicknames;
		if (nick == null || nick.isBlank()) {
			boolean had = map.remove(key) != null | map.remove("name:" + player.toLowerCase(Locale.ROOT)) != null;
			KyotoConfig.save();
			return had ? "Removed the nickname for " + real + "." : real + " has no nickname.";
		}
		nick = nick.trim().replaceAll("[\\u0000-\\u001f]", "");
		if (nick.length() > 24) nick = nick.substring(0, 24);
		map.put(key, nick);
		// keep the name too, so it still works before they're online
		map.put("name:" + real.toLowerCase(Locale.ROOT), nick);
		KyotoConfig.save();
		return real + " is now \"" + nick + "\" (only you see it).";
	}

	public static String of(UUID id, String name) {
		if (!Modules.on("nicknames")) return null;
		var map = KyotoConfig.get().nicknames;
		if (map.isEmpty()) return null;
		String n = id == null ? null : map.get(id.toString());
		if (n == null && name != null) n = map.get("name:" + name.toLowerCase(Locale.ROOT));
		return n;
	}

	private static int colour() {
		return switch (Modules.opt("nick.color")) {
			case 1 -> 0xFFFFFF;
			case 2 -> 0xE6E6E6;
			case 3 -> 0xD0D0D0;
			case 4 -> 0xC9CBD0;
			case 5 -> 0xFFFFFF;
			default -> Ui.accent();
		};
	}

	/** The nametag text for an entity: "Nickname" (+ small grey real name). Null = leave it alone. */
	public static Component nametag(Entity e, Component current) {
		if (!(e instanceof net.minecraft.world.entity.player.Player p)) return null;
		String real = p.getName().getString();
		String n = of(p.getUUID(), real);
		if (n == null) return null;
		MutableComponent c = Component.literal(n).withStyle(Style.EMPTY.withColor(colour()));
		if (Modules.opt("nick.real") == 0) c.append(Component.literal(" (" + real + ")").withStyle(ChatFormatting.GRAY));
		return c;
	}

	/** Chat: swaps real names for nicknames (keeps colours). Null = nothing changed. */
	public static Component chat(Component msg) {
		if (!Modules.on("nicknames") || Modules.opt("nick.chat") != 0) return null;
		var map = KyotoConfig.get().nicknames;
		if (map.isEmpty()) return null;
		String plain = msg.getString();
		boolean any = false;
		for (String k : map.keySet()) if (k.startsWith("name:") && plain.toLowerCase(Locale.ROOT).contains(k.substring(5))) { any = true; break; }
		if (!any) return null;
		MutableComponent out = Component.empty();
		msg.visit((style, text) -> {
			String t = text;
			for (var e : map.entrySet()) {
				if (!e.getKey().startsWith("name:")) continue;
				t = t.replaceAll("(?i)(?<![A-Za-z0-9_])" + java.util.regex.Pattern.quote(e.getKey().substring(5)) + "(?![A-Za-z0-9_])", java.util.regex.Matcher.quoteReplacement(e.getValue()));
			}
			out.append(Component.literal(t).setStyle(style));
			return Optional.empty();
		}, Style.EMPTY);
		return out;
	}
}
