package dev.kyoto.feature;

import dev.kyoto.hud.Notifications;
import dev.kyoto.module.Modules;
import dev.kyoto.util.Compat;
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Utility tab: tooltips (shulker contents, exact durability, food values), auto reconnect,
 * item counter (drawn in CombatHud) and Auto XP for singleplayer.
 */
public final class Utility {
	private Utility() {}

	private static String lastServerIp = null, lastServerName = null;

	public static void register() {
		ItemTooltipCallback.EVENT.register((stack, context, type, lines) -> {
			try {
				tooltip(stack, lines);
			} catch (Throwable ignored) {
			}
		});
		ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
			if (!Modules.on("auto_reconnect") || lastServerIp == null) return;
			if (!screen.getClass().getSimpleName().contains("Disconnected")) return;
			addReconnect(screen, w, h);
		});
	}

	/** Remember the server you're on so Reconnect knows where to go. */
	public static void onJoin(Minecraft mc) {
		if (mc.getCurrentServer() != null) {
			lastServerIp = mc.getCurrentServer().ip;
			lastServerName = mc.getCurrentServer().name;
		}
	}

	/* ---------------- tooltips ---------------- */

	private static void tooltip(ItemStack stack, List<Component> lines) {
		if (Modules.on("durability_tooltip") && stack.isDamageableItem()) {
			int max = stack.getMaxDamage(), left = max - stack.getDamageValue();
			float f = left / (float) Math.max(1, max);
			ChatFormatting col = f > 0.5f ? ChatFormatting.GREEN : f > 0.2f ? ChatFormatting.YELLOW : ChatFormatting.RED;
			lines.add(Component.literal("Durability: ").withStyle(ChatFormatting.GRAY).append(Component.literal(left + " / " + max).withStyle(col)));
		}
		if (Modules.on("food_tooltip")) {
			var food = stack.get(DataComponents.FOOD);
			if (food != null) {
				lines.add(Component.literal("Hunger +" + food.nutrition() + "  ·  Saturation +" + String.format("%.1f", food.saturation())).withStyle(ChatFormatting.GOLD));
			}
		}
		if (Modules.on("shulker_preview")) {
			Object contents = stack.get(DataComponents.CONTAINER);
			if (contents != null) {
				Map<String, Integer> counts = new LinkedHashMap<>();
				for (ItemStack s : items(contents)) {
					if (s.isEmpty()) continue;
					counts.merge(s.getHoverName().getString(), s.getCount(), Integer::sum);
				}
				if (!counts.isEmpty()) {
					lines.add(Component.literal("Inside:").withStyle(ChatFormatting.GRAY));
					int shown = 0;
					for (Map.Entry<String, Integer> e : counts.entrySet()) {
						if (shown++ == 8) {
							lines.add(Component.literal("  and " + (counts.size() - 8) + " more...").withStyle(ChatFormatting.DARK_GRAY));
							break;
						}
						lines.add(Component.literal("  " + e.getValue() + "x " + e.getKey()).withStyle(ChatFormatting.WHITE));
					}
				}
			}
		}
	}

	/** ItemContainerContents changed method names a few times, so find whichever one exists. */
	@SuppressWarnings("unchecked")
	private static List<ItemStack> items(Object contents) {
		List<ItemStack> out = new ArrayList<>();
		for (String name : new String[]{"nonEmptyItemsCopy", "nonEmptyItems", "itemsCopy", "stream", "nonEmptyStream"}) {
			try {
				var m = contents.getClass().getMethod(name);
				Object r = m.invoke(contents);
				if (r instanceof Iterable<?> it) {
					for (Object o : it) if (o instanceof ItemStack s) out.add(s);
				} else if (r instanceof java.util.stream.Stream<?> st) {
					st.forEach(o -> {
						if (o instanceof ItemStack s) out.add(s);
					});
				}
				if (!out.isEmpty()) return out;
			} catch (Throwable ignored) {
			}
		}
		return out;
	}

	/* ---------------- auto reconnect ---------------- */

	private static void addReconnect(net.minecraft.client.gui.screens.Screen screen, int w, int h) {
		int delayOpt = Modules.opt("reconnect.delay");
		int seconds = new int[]{5, 10, 30, -1}[delayOpt];
		long start = System.currentTimeMillis();
		String ip = lastServerIp, name = lastServerName == null ? ip : lastServerName;
		Button b = Button.builder(Component.literal(label(seconds, start)), btn -> Compat.joinServer(ip, name))
				.bounds(w / 2 - 100, h - 30, 200, 20).build();
		Screens.getWidgets(screen).add(b);
		final boolean[] done = {false};
		ScreenEvents.afterTick(screen).register(s -> {
			if (done[0]) return;
			b.setMessage(Component.literal(label(seconds, start)));
			if (seconds > 0 && System.currentTimeMillis() - start > seconds * 1000L) {
				done[0] = true;
				Compat.joinServer(ip, name);
			}
		});
	}

	private static String label(int seconds, long start) {
		if (seconds < 0) return "Reconnect";
		long left = seconds - (System.currentTimeMillis() - start) / 1000;
		return "Reconnecting in " + Math.max(0, left) + "s (click to go now)";
	}

	/* ---------------- Auto XP (singleplayer only) ---------------- */

	private static int savedSlot = -1;
	private static float savedPitch = 0;
	private static int cooldown = 0;
	private static boolean warnedServer = false;

	public static void tick(Minecraft mc) {
		LocalPlayer p = mc.player;
		if (p == null || mc.gameMode == null) return;
		if (!Modules.on("auto_xp")) {
			stopXp(p);
			return;
		}
		boolean singleplayer = mc.getSingleplayerServer() != null && mc.getCurrentServer() == null;
		if (!singleplayer) {
			if (!warnedServer) {
				warnedServer = true;
				Notifications.push("Auto XP is singleplayer only", "On servers it would count as a macro, so it stays off.", 0xFFC861);
			}
			stopXp(p);
			return;
		}
		warnedServer = false;
		if (Compat.currentScreen() != null) return;
		if (cooldown-- > 0) return;
		float threshold = new float[]{0.5f, 0.75f, 0.9f}[Modules.opt("autoxp.min")];
		boolean repairing = savedSlot >= 0;
		float worst = worstMending(p);
		boolean need = repairing ? worst < 0.995f : worst < threshold;
		if (!need) {
			stopXp(p);
			return;
		}
		int slot = -1;
		for (int i = 0; i < 9; i++) if (p.getInventory().getItem(i).is(Items.EXPERIENCE_BOTTLE)) {
			slot = i;
			break;
		}
		if (slot < 0) {
			stopXp(p);
			return;
		}
		if (savedSlot < 0) {
			savedSlot = p.getInventory().getSelectedSlot();
			savedPitch = p.getXRot();
		}
		p.getInventory().setSelectedSlot(slot);
		p.setXRot(90f);
		mc.gameMode.useItem(p, InteractionHand.MAIN_HAND);
		cooldown = 2;
	}

	private static void stopXp(LocalPlayer p) {
		if (savedSlot < 0) return;
		p.getInventory().setSelectedSlot(savedSlot);
		p.setXRot(savedPitch);
		savedSlot = -1;
	}

	/** Lowest durability (0..1) among worn/held items that have Mending. 1 if none. */
	private static float worstMending(LocalPlayer p) {
		float worst = 1f;
		for (EquipmentSlot slot : EquipmentSlot.values()) {
			ItemStack s = p.getItemBySlot(slot);
			if (s.isEmpty() || !s.isDamageableItem() || !hasMending(s)) continue;
			worst = Math.min(worst, 1f - s.getDamageValue() / (float) Math.max(1, s.getMaxDamage()));
		}
		return worst;
	}

	private static boolean hasMending(ItemStack s) {
		try {
			for (var holder : s.getEnchantments().keySet()) if (holder.is(Enchantments.MENDING)) return true;
		} catch (Throwable ignored) {
		}
		return false;
	}
}
