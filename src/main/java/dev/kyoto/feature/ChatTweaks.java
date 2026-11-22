package dev.kyoto.feature;

import com.mojang.authlib.GameProfile;
import dev.kyoto.config.KyotoConfig;
import dev.kyoto.hud.Notifications;
import dev.kyoto.module.Modules;
import dev.kyoto.util.Compat;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.sounds.SoundEvents;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Chat upgrades (your side only): timestamps, mention pings (your username + extra words),
 * muted words, clickable coordinates, and server alerts (TPA / payments).
 */
public final class ChatTweaks {
	private ChatTweaks() {}

	private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");
	private static final Pattern COORDS = Pattern.compile("(?:x[:=]\\s*)?(-?\\d{1,7})[,\\s]+(?:y[:=]\\s*)?(-?\\d{1,3})[,\\s]+(?:z[:=]\\s*)?(-?\\d{1,7})", Pattern.CASE_INSENSITIVE);
	private static final Pattern SHARED = Pattern.compile("\\[WP]\\s*([A-Za-z0-9_\\-]+)\\s*@");
	private static final Pattern KWP = Pattern.compile("\\[KWP]\\s+(\\S+)\\s+(-?\\d+)\\s+(-?\\d+)\\s+(-?\\d+)\\s+(\\S+)");
	private static final Pattern E4MC = Pattern.compile("([a-z0-9-]+(?:\\.[a-z0-9-]+)*\\.e4mc\\.link)", Pattern.CASE_INSENSITIVE);
	public static volatile String lastHostAddress = null;
	private static final Pattern SENDER_ANGLE = Pattern.compile("^<([^>]+)>\\s");
	private static final Pattern SENDER_COLON = Pattern.compile("^(?:\\[[^\\]]*]\\s*|[^A-Za-z0-9_\\s]+\\s*)*([A-Za-z0-9_]{3,16})\\s*(?::|»|>|→|\\|)\\s");

	public static void register() {
		ClientReceiveMessageEvents.ALLOW_CHAT.register((message, signedMessage, sender, params, receptionTimestamp) ->
				handle(message, senderName(sender)));
		ClientReceiveMessageEvents.ALLOW_GAME.register((message, overlay) ->
				overlay || handle(message, null));
	}

	private static String senderName(GameProfile profile) {
		try {
			return Compat.profileName(profile);
		} catch (Throwable t) {
			return null;
		}
	}

	/** @return true = let vanilla show it, false = we showed our own version (or hid it). */
	private static boolean handle(Component message, String sender) {
		if (Compat.isOwn(message)) return true; // our own line -> never process again (this was the U-key crash)

		KyotoConfig c = KyotoConfig.get();
		String plain = message.getString();
		String lower = plain.toLowerCase(Locale.ROOT);

		// e4mc prints your public world address in chat - remember it for the Host World screen
		Matcher host = E4MC.matcher(plain);
		if (host.find()) lastHostAddress = host.group(1);

		if (ServerProfiles.filter(plain)) return false;

		if (Modules.on("muted_words")) {
			for (String w : c.mutedWords) {
				if (!w.isBlank() && lower.contains(w.toLowerCase(Locale.ROOT))) return false;
			}
		}

		boolean changed = false;
		MutableComponent out = Component.empty();

		if (Modules.on("timestamps")) {
			out.append(Component.literal("[" + LocalTime.now().format(TIME) + "] ").withStyle(ChatFormatting.DARK_GRAY));
			changed = true;
		}

		// ---- mentions ----
		String me = Minecraft.getInstance().getUser().getName();
		if (sender == null) sender = parseSender(plain);

		// ---- Kyoto badge in front of Kyoto players' messages ----
		if (Modules.on("kyoto_badges") && Modules.opt("badge.where") != 1 && sender != null && dev.kyoto.net.KyotoNet.isKyoto(sender)) {
			out.append(Compat.withFont(Component.literal(dev.kyoto.net.KyotoNet.badgeGlyph(sender)), net.minecraft.resources.Identifier.fromNamespaceAndPath("kyoto", "badge"))
					.withStyle(s -> s.withColor(0xFFFFFF)));
			out.append(Component.literal(" "));
			changed = true;
		}
		boolean fromMe = sender != null && sender.equalsIgnoreCase(me);
		String body = bodyOf(plain);
		boolean mention = false;
		if (Modules.on("mentions") && !fromMe) {
			String b = body.toLowerCase(Locale.ROOT);
			if (containsWord(b, me.toLowerCase(Locale.ROOT))) mention = true;
			for (String w : c.extraMentionWords) if (!w.isBlank() && containsWord(b, w.toLowerCase(Locale.ROOT))) mention = true;
			for (String p : c.mentionPatterns) {
				try {
					if (Pattern.compile(p, Pattern.CASE_INSENSITIVE).matcher(body).find()) mention = true;
				} catch (PatternSyntaxException ignored) {
				}
			}
		}
		if (mention) {
			out.append(Component.literal("» ").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
			out.append(message.copy().withStyle(s -> s.withColor(ChatFormatting.YELLOW)));
			String who = sender == null ? "Someone" : sender;
			Notifications.push("Who said my name?", who + ": " + body, 0xFFC861, 7000, sender, sender == null ? "!" : null);
			if (Modules.on("warning_sounds")) {
				Compat.sound(SoundEvents.NOTE_BLOCK_PLING.value(), 1f, 1.5f);
				Compat.sound(SoundEvents.NOTE_BLOCK_PLING.value(), 0.7f, 2.0f);
			}
			changed = true;
		} else {
			out.append(message);
		}

		// ---- waypoints whispered by other Kyoto players ----
		Matcher kwp = KWP.matcher(plain);
		if (kwp.find() && !fromMe && Modules.on("kyoto_waypoints_chat")) {
			Waypoints.receive(sender == null ? "Someone" : sender, kwp.group(1), Integer.parseInt(kwp.group(2)),
					Integer.parseInt(kwp.group(3)), Integer.parseInt(kwp.group(4)), kwp.group(5));
		}

		// ---- server alerts (tpa / pay) ----
		if (!fromMe) {
			MutableComponent extra = ServerAlerts.onMessage(plain);
			if (extra != null) {
				out.append(extra);
				changed = true;
			}
		}

		// ---- clickable coords ----
		if (Modules.on("coord_links")) {
			Matcher m = COORDS.matcher(plain);
			if (m.find()) {
				Matcher named = SHARED.matcher(plain);
				String name = named.find() ? named.group(1) : "Shared";
				String cmd = "/wp add " + name + " " + m.group(1) + " " + m.group(2) + " " + m.group(3);
				out.append(Component.literal(" [+ Waypoint]").setStyle(Style.EMPTY.withColor(ChatFormatting.AQUA)
						.withClickEvent(new ClickEvent.SuggestCommand(cmd))
						.withHoverEvent(new HoverEvent.ShowText(Component.literal("Click, then press Enter to save\n" + cmd)))));
				changed = true;
			}
		}

		if ((Modules.on("streamer_mode") || dev.kyoto.feature.Privacy.hideName()) && plain.contains(me)) {
			out = Component.literal(out.getString().replace(me, "You"));
			changed = true;
		}

		// ---- Nickname Tags in chat ----
		Component nicked = dev.kyoto.hud.Nicknames.chat(out);
		if (nicked != null) {
			out = nicked.copy();
			changed = true;
		}

		// ---- Streamer Mode: hide coords, swears, slurs and personal info from viewers ----
		Component censored = dev.kyoto.stream.StreamFilter.apply(out);
		if (censored != null) {
			out = censored.copy();
			changed = true;
		}

		if (!changed) return dev.kyoto.extra.ChatTabs.record(message, false);
		if (dev.kyoto.extra.ChatTabs.record(out, false)) {
			dev.kyoto.extra.ChatTabs.skipNext = true;
			Compat.addChat(out);
		}
		return false;
	}

	private static String parseSender(String plain) {
		Matcher m = SENDER_ANGLE.matcher(plain);
		if (m.find()) {
			String inside = m.group(1).trim();
			String[] parts = inside.split("\\s+");
			return parts[parts.length - 1].replaceAll("[^A-Za-z0-9_]", "");
		}
		m = SENDER_COLON.matcher(plain);
		if (m.find()) return m.group(1);
		return null;
	}

	private static String bodyOf(String plain) {
		int gt = plain.indexOf("> ");
		if (plain.startsWith("<") && gt > 0) return plain.substring(gt + 2);
		Matcher m = SENDER_COLON.matcher(plain);
		if (m.find()) return plain.substring(m.end());
		return plain;
	}

	private static boolean containsWord(String text, String word) {
		return Pattern.compile("(^|[^a-z0-9_])" + Pattern.quote(word) + "($|[^a-z0-9_])").matcher(text).find();
	}
}
