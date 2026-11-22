package dev.kyoto.feature;

import dev.kyoto.util.Compat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Watches your inventory and turns gains into "+12 Cobblestone" entries.
 * Changes while a screen is open (chests, crafting) are ignored, so only real pickups show.
 */
public final class PickupFeed {
	private PickupFeed() {}

	public static final class Entry {
		public final Item item;
		public String name;
		public int amount;
		public long time;
		public long born;

		Entry(Item item, String name, int amount) {
			this.item = item;
			this.name = name;
			this.amount = amount;
			this.time = System.currentTimeMillis();
			this.born = this.time;
		}
	}

	public static final List<Entry> ENTRIES = new ArrayList<>();
	public static final long LIFETIME = 3200;

	private static Map<Item, Integer> last = null;
	private static boolean screenWasOpen = false;

	public static void tick(Minecraft mc) {
		LocalPlayer p = mc.player;
		if (p == null) {
			last = null;
			return;
		}
		ENTRIES.removeIf(e -> System.currentTimeMillis() - e.time > LIFETIME);

		Map<Item, Integer> now = new HashMap<>();
		Map<Item, String> names = new HashMap<>();
		for (int i = 0; i < p.getInventory().getContainerSize(); i++) {
			ItemStack s = p.getInventory().getItem(i);
			if (s.isEmpty()) continue;
			now.merge(s.getItem(), s.getCount(), Integer::sum);
			names.putIfAbsent(s.getItem(), s.getHoverName().getString());
		}

		boolean screenOpen = Compat.currentScreen() != null;
		if (last != null && !screenOpen && !screenWasOpen && !p.isDeadOrDying()) {
			for (Map.Entry<Item, Integer> e : now.entrySet()) {
				int gained = e.getValue() - last.getOrDefault(e.getKey(), 0);
				if (gained > 0) add(e.getKey(), names.get(e.getKey()), gained);
			}
		}
		last = now;
		screenWasOpen = screenOpen;
	}

	private static void add(Item item, String name, int amount) {
		for (Entry e : ENTRIES) {
			if (e.item == item) {
				e.amount += amount;
				e.time = System.currentTimeMillis();
				return;
			}
		}
		ENTRIES.add(0, new Entry(item, name, amount));
		while (ENTRIES.size() > 6) ENTRIES.remove(ENTRIES.size() - 1);
	}
}
