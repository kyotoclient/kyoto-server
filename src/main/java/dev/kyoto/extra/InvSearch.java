package dev.kyoto.extra;

import dev.kyoto.module.Modules;
import dev.kyoto.servers.Market;
import dev.kyoto.util.Anim;
import dev.kyoto.util.Ui;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Rarity;

import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Inventory search: a search box above every chest / inventory. Matching items stay bright, everything else dims.
 * Ctrl+F jumps into the box. Also draws the item rarity glow (uncommon / rare / epic items get a coloured corner).
 */
public final class InvSearch {
	private InvSearch() {}

	private static final Map<AbstractContainerScreen<?>, EditBox> BOXES = new WeakHashMap<>();
	private static String last = "";

	/** The search box was removed (it got in the way). Only the rarity glow is left. */
	public static void register() {
	}

	public static String query(AbstractContainerScreen<?> cs) {
		return "";
	}

	private static boolean matches(ItemStack st, String q) {
		if (st.isEmpty()) return false;
		String name = st.getHoverName().getString().toLowerCase(Locale.ROOT);
		if (name.contains(q)) return true;
		String id = st.getItem().toString().toLowerCase(Locale.ROOT);
		if (id.contains(q.replace(' ', '_'))) return true;
		var lore = st.get(DataComponents.LORE);
		if (lore != null) for (Component c : lore.lines()) if (c.getString().toLowerCase(Locale.ROOT).contains(q)) return true;
		return false;
	}

	/** Drawn on top of the container every frame. */
	public static void draw(AbstractContainerScreen<?> cs, GuiGraphicsExtractor g) {
		boolean search = false, glow = Modules.on("rarity_glow");
		if (!search && !glow) return;
		String q = search ? query(cs) : "";
		int left = Market.field(cs, "leftPos"), top = Market.field(cs, "topPos");
		int found = 0;
		for (Slot slot : cs.getMenu().slots) {
			int x = left + slot.x, y = top + slot.y;
			ItemStack st = slot.getItem();
			if (!q.isEmpty()) {
				if (matches(st, q)) {
					found++;
					Ui.outline(g, x - 1, y - 1, x + 17, y + 17, Anim.argb(Ui.accent(), 0.95f));
				} else g.fill(x, y, x + 16, y + 16, Anim.argb(Ui.GLASS, 0.72f));
				continue;
			}
			if (glow && !st.isEmpty()) {
				int col = rarityColor(st);
				if (col != 0) {
					g.fill(x, y + 15, x + 16, y + 16, Anim.argb(col, 0.9f));
					g.fill(x, y, x + 16, y + 16, Anim.argb(col, 0.12f));
				}
			}
		}
		if (!q.isEmpty()) {
			String t = found + (found == 1 ? " match" : " matches");
			Ui.small(g, t, left, Math.max(2, top - 11), Anim.argb(found > 0 ? 0x6EE7A0 : 0xFF8A8A, 1f));
		}
	}

	private static int rarityColor(ItemStack st) {
		Rarity r = st.getRarity();
		if (r == Rarity.UNCOMMON) return Modules.opt("rarity.which") == 0 ? 0xFFE45C : 0;
		if (r == Rarity.RARE) return 0x5CE1FF;
		if (r == Rarity.EPIC) return 0xD27CFF;
		if (st.isEnchanted() && Modules.opt("rarity.which") == 0) return 0x9C8CFF;
		return 0;
	}
}
