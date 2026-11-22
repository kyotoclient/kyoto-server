package dev.kyoto.screen;

import dev.kyoto.KyotoClient;
import dev.kyoto.feature.Waypoints;
import dev.kyoto.util.Compat;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextColor;
import net.minecraft.network.chat.Style;

import java.util.List;

/**
 * Waypoint menu (default key: N).
 * Click a waypoint to track it on the compass bar, "Share" posts it in chat, "X" deletes it.
 */
public class WaypointScreen extends Screen {
	private int page = 0;

	public WaypointScreen() {
		super(Component.literal("Waypoints"));
	}

	@Override
	protected void init() {
		List<Waypoints.Waypoint> list = Waypoints.sortedByDistance();
		int rowH = 22;
		int rows = Math.max(3, (this.height - 90) / rowH);
		int pages = Math.max(1, (list.size() + rows - 1) / rows);
		page = Math.max(0, Math.min(page, pages - 1));
		int x = this.width / 2 - 150;
		int y = 32;

		this.addRenderableWidget(Button.builder(Component.literal("§lWaypoints §r(" + list.size() + ") §7- this world only"), b -> {})
				.bounds(this.width / 2 - 150, 6, 170, 20).build()).active = false;
		boolean deaths = dev.kyoto.module.Modules.on("death_waypoint");
		this.addRenderableWidget(Button.builder(Component.literal("Deaths: " + (deaths ? "§aOn" : "§cOff")), b -> {
			Waypoints.setDeathWaypoints(!dev.kyoto.module.Modules.on("death_waypoint"));
			this.rebuildWidgets();
		}).bounds(this.width / 2 + 24, 6, 74, 20).build());
		this.addRenderableWidget(Button.builder(Component.literal("Clear"), b -> {
			Waypoints.clearType(Waypoints.Type.DEATH);
			this.rebuildWidgets();
		}).bounds(this.width / 2 + 102, 6, 48, 20).build());

		var player = this.minecraft == null ? null : this.minecraft.player;
		String dim = Compat.dimensionId();
		Waypoints.Waypoint tracked = Waypoints.tracked();

		for (int i = page * rows; i < Math.min(list.size(), (page + 1) * rows); i++) {
			Waypoints.Waypoint w = list.get(i);
			String dist = (player != null && dim.equals(w.dimension))
					? Math.round(w.distanceTo(player.getX(), player.getY(), player.getZ())) + "m"
					: Compat.pretty(w.dimension);
			String mark = w == tracked ? "▶ " : "";
			String by = w.owner != null && !w.owner.isBlank() && !w.owner.equals(Compat.myName()) ? "  §8by " + w.owner : "";
			Component label = Component.literal(mark + w.name + "  ")
					.setStyle(Style.EMPTY.withColor(TextColor.fromRgb(w.color)))
					.append(Component.literal("§7" + dist + by));

			this.addRenderableWidget(Button.builder(label, b -> {
				Waypoints.track(w == Waypoints.tracked() ? null : w);
				this.rebuildWidgets();
			}).bounds(x, y, 170, 20).build());

			this.addRenderableWidget(Button.builder(Component.literal("Send"), b -> Compat.openScreen(new SendWaypointScreen(this, w)))
					.bounds(x + 174, y, 48, 20).build());

			this.addRenderableWidget(Button.builder(Component.literal("Chat"), b -> {
				KyotoClient.shareInChat(w.name, w.x, w.y, w.z);
				this.onClose();
			}).bounds(x + 226, y, 48, 20).build());

			this.addRenderableWidget(Button.builder(Component.literal("§cX"), b -> {
				Waypoints.remove(w.name);
				this.rebuildWidgets();
			}).bounds(x + 278, y, 22, 20).build());

			y += rowH;
		}

		int bottom = this.height - 28;
		this.addRenderableWidget(Button.builder(Component.literal("+ Add here"), b -> {
			Waypoints.Waypoint w = Waypoints.addHere(null, Waypoints.Type.MANUAL);
			if (w != null) Waypoints.sound(1.2f);
			this.rebuildWidgets();
		}).bounds(this.width / 2 - 150, bottom, 95, 20).build());

		if (pages > 1) {
			final int total = pages;
			this.addRenderableWidget(Button.builder(Component.literal("Page " + (page + 1) + "/" + total + " >"), b -> {
				page = (page + 1) % total;
				this.rebuildWidgets();
			}).bounds(this.width / 2 - 50, bottom, 95, 20).build());
		}

		this.addRenderableWidget(Button.builder(Component.literal("Done"), b -> this.onClose())
				.bounds(this.width / 2 + 55, bottom, 95, 20).build());
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
