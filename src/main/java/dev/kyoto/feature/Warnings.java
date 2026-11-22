package dev.kyoto.feature;

import dev.kyoto.module.Modules;
import dev.kyoto.hud.Notifications;
import dev.kyoto.util.Compat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.EnumMap;
import java.util.Map;

/**
 * Pop-up warnings. Each one fires ONCE, then re-arms when the situation is fixed
 * (repair the armor, heal up, eat...), so they never spam you.
 */
public final class Warnings {
	private Warnings() {}

	private static final EquipmentSlot[] ARMOR = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};
	private static final Map<EquipmentSlot, Boolean> ARMOR_WARNED = new EnumMap<>(EquipmentSlot.class);

	private static boolean toolWarned, healthWarned, hungerWarned, elytraWarned, totemHintWarned;
	private static ItemStack lastTool = ItemStack.EMPTY;
	private static int lastTotems = -1;
	private static int tick;

	public static float durability(ItemStack s) {
		if (s.isEmpty() || !s.isDamageableItem() || s.getMaxDamage() <= 0) return 1f;
		return 1f - (s.getDamageValue() / (float) s.getMaxDamage());
	}

	public static String slotName(EquipmentSlot slot) {
		return switch (slot) {
			case HEAD -> "Helmet";
			case CHEST -> "Chestplate";
			case LEGS -> "Leggings";
			case FEET -> "Boots";
			default -> slot.getName();
		};
	}

	public static void tick(Minecraft mc) {
		LocalPlayer p = mc.player;
		if (p == null || p.isDeadOrDying()) {
			lastTotems = -1;
			return;
		}

		// Totem pop check runs every tick so it feels instant
		int totems = countTotems(p);
		if (Modules.on("warn_totem") && lastTotems > 0 && totems < lastTotems && Compat.currentScreen() == null) {
			Notifications.push("Totem popped!", totems == 0 ? "That was your LAST totem" : totems + " totem" + (totems == 1 ? "" : "s") + " left",
					totems == 0 ? Notifications.DANGER : Notifications.WARN, 5000);
			play(SoundEvents.TOTEM_USE, 0.4f, 1.3f);
			WorldFx.totemBurst(p);
		}
		lastTotems = totems;

		if (++tick % 10 != 0) return;

		// Armor
		if (Modules.on("warn_armor")) {
			for (EquipmentSlot slot : ARMOR) {
				ItemStack s = p.getItemBySlot(slot);
				float d = durability(s);
				boolean warned = ARMOR_WARNED.getOrDefault(slot, false);
				if (!s.isEmpty() && s.isDamageableItem() && d < 0.10f && !warned) {
					ARMOR_WARNED.put(slot, true);
					Notifications.push(slotName(slot) + " almost broken!", Math.round(d * 100) + "% left - " + s.getHoverName().getString(), Notifications.DANGER, 5000);
					play(SoundEvents.ANVIL_LAND, 0.25f, 1.6f);
				} else if (warned && (s.isEmpty() || d > 0.15f)) {
					ARMOR_WARNED.put(slot, false);
				}
			}
		}

		// Held tool / weapon
		if (Modules.on("warn_tool")) {
			ItemStack held = p.getMainHandItem();
			if (!ItemStack.isSameItem(held, lastTool)) toolWarned = false;
			lastTool = held;
			float d = durability(held);
			if (!toolWarned && held.isDamageableItem() && d < 0.05f) {
				toolWarned = true;
				int left = held.getMaxDamage() - held.getDamageValue();
				Notifications.push(held.getHoverName().getString() + " about to break!", left + " uses left", Notifications.DANGER, 5000);
				play(SoundEvents.ANVIL_LAND, 0.25f, 1.9f);
			}
		}

		// Health
		float hp = p.getHealth() / p.getMaxHealth();
		if (Modules.on("warn_health")) {
			if (!healthWarned && hp <= 0.3f) {
				healthWarned = true;
				Notifications.push("Low health!", String.format("%.1f hearts left", p.getHealth() / 2f), Notifications.DANGER);
				play(SoundEvents.NOTE_BLOCK_BASS.value(), 0.8f, 0.6f);
			} else if (hp > 0.5f) {
				healthWarned = false;
			}
		}

		// Totem not in hand while low
		if (Modules.on("warn_totem")) {
			boolean inHand = p.getOffhandItem().is(Items.TOTEM_OF_UNDYING) || p.getMainHandItem().is(Items.TOTEM_OF_UNDYING);
			if (!totemHintWarned && hp <= 0.3f && totems > 0 && !inHand) {
				totemHintWarned = true;
				Notifications.push("Totem not equipped!", "You have " + totems + " in your inventory", Notifications.WARN);
			} else if (hp > 0.5f || inHand) {
				totemHintWarned = false;
			}
		}

		// Hunger
		if (Modules.on("warn_hunger")) {
			int food = p.getFoodData().getFoodLevel();
			if (!hungerWarned && food <= 6) {
				hungerWarned = true;
				Notifications.push("You're hungry", "Can't sprint soon - eat something", Notifications.WARN);
				play(SoundEvents.NOTE_BLOCK_BASS.value(), 0.6f, 1.0f);
			} else if (food > 10) {
				hungerWarned = false;
			}
		}

		// Elytra while flying
		if (Modules.on("warn_elytra")) {
			ItemStack chest = p.getItemBySlot(EquipmentSlot.CHEST);
			if (chest.is(Items.ELYTRA)) {
				float d = durability(chest);
				if (!elytraWarned && d < 0.10f && p.isFallFlying()) {
					elytraWarned = true;
					Notifications.push("Elytra breaking!", Math.round(d * 100) + "% left - land soon", Notifications.DANGER, 5000);
					play(SoundEvents.NOTE_BLOCK_BELL.value(), 1f, 0.7f);
				} else if (d > 0.15f) {
					elytraWarned = false;
				}
			}
		}
	}

	public static int countTotems(LocalPlayer p) {
		int n = 0;
		for (int i = 0; i < p.getInventory().getContainerSize(); i++) {
			ItemStack s = p.getInventory().getItem(i);
			if (s.is(Items.TOTEM_OF_UNDYING)) n += s.getCount();
		}
		return n;
	}

	private static void play(SoundEvent sound, float vol, float pitch) {
		if (Modules.on("warning_sounds")) Compat.sound(sound, vol, pitch);
	}
}
