package dev.kyoto.feature;

import dev.kyoto.hud.Notifications;
import dev.kyoto.module.Modules;
import dev.kyoto.util.Compat;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.sounds.SoundEvents;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads normal server messages (EssentialsX, CMI, HuskHomes, DonutSMP-style...) and turns
 * TPA requests and incoming payments into pop-ups with the player's head.
 * Payments only trigger on messages that say YOU received money.
 */
public final class ServerAlerts {
	private ServerAlerts() {}

	private static final String NAME = "([A-Za-z0-9_]{3,16})";
	private static final String MONEY = "\\$?([0-9][0-9,]*(?:\\.[0-9]+)?\\s?[kKmMbBtT]?)";

	/** "Steve has requested to teleport to you." */
	private static final List<Pattern> TPA_TO_YOU = List.of(
			Pattern.compile(NAME + " has requested to teleport to you", Pattern.CASE_INSENSITIVE),
			Pattern.compile(NAME + " wants to teleport to you", Pattern.CASE_INSENSITIVE),
			Pattern.compile(NAME + " (?:has )?sent you a (?:teleport|tpa) request", Pattern.CASE_INSENSITIVE),
			Pattern.compile("(?:teleport|tpa) request from " + NAME, Pattern.CASE_INSENSITIVE));

	/** "Steve has requested that you teleport to them." */
	private static final List<Pattern> TPA_HERE = List.of(
			Pattern.compile(NAME + " has requested that you teleport to them", Pattern.CASE_INSENSITIVE),
			Pattern.compile(NAME + " (?:wants|asks) you to teleport to them", Pattern.CASE_INSENSITIVE),
			Pattern.compile(NAME + " (?:has )?sent you a tpahere request", Pattern.CASE_INSENSITIVE));

	/** Money received. Group order differs, so each pattern says which group is the name. */
	private record PayPattern(Pattern p, int nameGroup, int amountGroup) {}

	private static final List<PayPattern> PAY = List.of(
			new PayPattern(Pattern.compile(MONEY + " has been received from " + NAME, Pattern.CASE_INSENSITIVE), 2, 1),
			new PayPattern(Pattern.compile("you (?:have )?received " + MONEY + " from " + NAME, Pattern.CASE_INSENSITIVE), 2, 1),
			new PayPattern(Pattern.compile(NAME + " (?:has )?(?:paid|sent|given) you " + MONEY, Pattern.CASE_INSENSITIVE), 1, 2),
			new PayPattern(Pattern.compile("received " + MONEY + " from " + NAME, Pattern.CASE_INSENSITIVE), 2, 1));

	/**
	 * Check one incoming message. Returns extra chat text to append (e.g. [Accept] [Deny]) or null.
	 */
	public static MutableComponent onMessage(String plain) {
		if (plain.length() > 200) return null;

		if (Modules.on("tpa_alerts")) {
			for (Pattern p : TPA_TO_YOU) {
				Matcher m = p.matcher(plain);
				if (m.find()) return tpa(m.group(1), m.group(1) + " wants to teleport to you");
			}
			for (Pattern p : TPA_HERE) {
				Matcher m = p.matcher(plain);
				if (m.find()) return tpa(m.group(1), m.group(1) + " wants you to teleport to them");
			}
		}

		if (Modules.on("pay_alerts") && !plain.toLowerCase().contains("you paid") && !plain.toLowerCase().startsWith("you sent")) {
			for (PayPattern pp : PAY) {
				Matcher m = pp.p.matcher(plain);
				if (m.find()) {
					String who = m.group(pp.nameGroup);
					String amount = m.group(pp.amountGroup).replace(" ", "");
					if (!amount.startsWith("$")) amount = "$" + amount;
					Notifications.push("+" + amount, "from " + who, Notifications.MONEY, 7000, who, null);
					if (Modules.on("warning_sounds")) {
						Compat.sound(SoundEvents.EXPERIENCE_ORB_PICKUP, 0.8f, 1.4f);
						Compat.sound(SoundEvents.AMETHYST_BLOCK_CHIME, 0.7f, 1.8f);
					}
					WorldFx.moneyBurst();
					return null;
				}
			}
		}
		return null;
	}

	public static String lastTpaFrom = null;

	private static MutableComponent tpa(String who, String text) {
		lastTpaFrom = who;
		Notifications.push("TPA request", text, 0x7FB8FF, 12000, who, null);
		if (Modules.on("warning_sounds")) Compat.sound(SoundEvents.NOTE_BLOCK_CHIME.value(), 0.9f, 1.3f);
		if (Modules.opt("tpa.buttons") != 0) return null;
		MutableComponent out = Component.literal("  ");
		out.append(Component.literal("[✔ Accept]").setStyle(Style.EMPTY.withColor(ChatFormatting.GREEN).withBold(true)
				.withClickEvent(new ClickEvent.RunCommand("/tpaccept"))
				.withHoverEvent(new HoverEvent.ShowText(Component.literal("/tpaccept")))));
		out.append(Component.literal(" "));
		out.append(Component.literal("[✖ Deny]").setStyle(Style.EMPTY.withColor(ChatFormatting.RED).withBold(true)
				.withClickEvent(new ClickEvent.RunCommand("/tpdeny"))
				.withHoverEvent(new HoverEvent.ShowText(Component.literal("/tpdeny")))));
		return out;
	}
}
