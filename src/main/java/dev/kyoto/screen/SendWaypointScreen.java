package dev.kyoto.screen;

import dev.kyoto.feature.Waypoints;
import dev.kyoto.net.KyotoNet;
import dev.kyoto.util.Ui;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Send a waypoint to someone. They see it with "by <you>". */
public class SendWaypointScreen extends PanelScreen {
	private final Waypoints.Waypoint wp;
	private String typed = "";

	public SendWaypointScreen(Screen parent, Waypoints.Waypoint wp) {
		super("Send \"" + wp.name + "\"", parent);
		this.wp = wp;
	}

	@Override
	protected String subtitle() {
		return KyotoNet.loggedIn() ? "Sent through your Kyoto server." : "Sent as a private /msg - they need Kyoto Client to auto-save it.";
	}

	@Override
	protected String icon() {
		return "waypoint";
	}

	@Override
	protected void build() {
		EditBox box = new EditBox(this.font, px + 16, py + 40, 160, 18, Component.literal("Player"));
		box.setMaxLength(16);
		box.setHint(Ui.t("Player name..."));
		box.setValue(typed);
		box.setResponder(s -> typed = s);
		this.addRenderableWidget(box);
		btn(px + 182, py + 39, 62, "Send", () -> {
			if (!typed.isBlank()) {
				Waypoints.sendTo(typed.trim(), wp);
				onClose();
			}
		});
		int y = py + 70;
		for (KyotoNet.Friend f : KyotoNet.FRIENDS) {
			if (y > py + ph - 40) break;
			btn(px + 14, y, 230, (f.online() ? "● " : "○ ") + f.name(), () -> {
				Waypoints.sendTo(f.name(), wp);
				onClose();
			});
			y += 22;
		}
	}
}
