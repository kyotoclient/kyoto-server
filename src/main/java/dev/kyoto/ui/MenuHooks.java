package dev.kyoto.ui;

import dev.kyoto.module.Modules;
import dev.kyoto.net.KyotoNet;
import dev.kyoto.screen.ClickGui;
import dev.kyoto.screen.CosmeticsScreen;
import dev.kyoto.screen.FriendsScreen;
import dev.kyoto.screen.HostWorldScreen;
import dev.kyoto.screen.ModFinderScreen;
import dev.kyoto.screen.ModsScreen;
import dev.kyoto.util.Compat;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.function.Supplier;

/**
 * Adds Kyoto buttons to the title screen and pause menu. Uses Fabric's screen API (no mixins),
 * so it plays nice with other mods that add their own buttons (Mod Menu etc).
 * The look of ALL buttons (vanilla + other mods) comes from Kyoto's built-in resource pack.
 */
public final class MenuHooks {
	private MenuHooks() {}

	public static void register() {
		// Kyoto screens: text boxes use the theme's ink colour (white text vanished on the new white glass)
		ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
			if (!screen.getClass().getName().startsWith("dev.kyoto")) return;
			dev.kyoto.util.Ui.refresh();
			for (AbstractWidget wd : Screens.getWidgets(screen))
				if (wd instanceof net.minecraft.client.gui.components.EditBox eb) eb.setTextColor(0xFF000000 | dev.kyoto.util.Ui.INK);
		});
		ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
			if (!Modules.on("kyoto_menus")) return;
			if (screen instanceof TitleScreen) title(screen, w, h);
			else if (screen instanceof PauseScreen) pause(screen, w, h);
		});
	}

	private static Button button(String label, int x, int y, int w, Supplier<Screen> open) {
		return Button.builder(Component.literal(label), b -> Compat.openScreen(open.get())).bounds(x, y, w, 20).build();
	}

	private static Button link(String label, int x, int y, int w, String url) {
		return Button.builder(Component.literal(label), b -> Compat.openUrl(url)).bounds(x, y, w, 20).build();
	}

	private static final String[] SPLASHES = {
			"Welcome back, %s!", "Kyoto Client on top!", "Made by heffm ♛", "Now with sky lyrics!", "Smooth zoom, finally!",
			"Shooting stars tonight!", "RGB mode: ON", "Glass UI edition", "Ping your friends!", "Capes that move!",
			"100% legit, 0% X-ray", "Try Right Shift!", "Craft your profile!", "26.3 Wilderness Bound!", "Sakura season!",
			"GG EZ (respectfully)", "Hydrate, gamer!", "Built different", "Touch grass (in-game)", "Powered by Fabric",
			"Your HUD, your rules", "Hosting worlds like console!", "Combos go brrr", "Lunar who?", "hef was here"};

	/** Swaps the yellow splash text for a Kyoto one (works whatever the field is called). */
	private static void kyotoSplash(Screen screen) {
		String pick = SPLASHES[(int) (Math.random() * SPLASHES.length)];
		String text = pick.contains("%s") ? String.format(pick, Compat.myName()) : pick;
		for (Class<?> c = screen.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
			for (java.lang.reflect.Field f : c.getDeclaredFields()) {
				if (!f.getType().getSimpleName().toLowerCase().contains("splash")) continue;
				try {
					f.setAccessible(true);
					Object splash = null;
					for (java.lang.reflect.Constructor<?> k : f.getType().getDeclaredConstructors()) {
						if (k.getParameterCount() != 1) continue;
						k.setAccessible(true);
						Class<?> p = k.getParameterTypes()[0];
						if (p == String.class) splash = k.newInstance(text);
						else if (p.isAssignableFrom(Component.class) || Component.class.isAssignableFrom(p)) splash = k.newInstance(Component.literal(text));
						if (splash != null) break;
					}
					if (splash != null) f.set(screen, splash);
				} catch (Throwable ignored) {
				}
				return;
			}
		}
	}

	private static void title(Screen screen, int w, int h) {
		if (!Modules.on("title_screen")) kyotoSplash(screen);
		List<AbstractWidget> list = Screens.getWidgets(screen);
		int x = 8, y = h / 2 - 80, bw = 120;
		// these go into the dock at the bottom of the title screen (see TitleLayout)
		list.add(button("Kyoto", x, y, bw, dev.kyoto.screen.KyotoMenu::create));
		list.add(button("Hub", x, y + 24, bw, () -> new dev.kyoto.screen.HubScreen(screen)));
		list.add(button("Friends", x, y + 48, bw, () -> new FriendsScreen(screen)));
		list.add(button("Profile", x, y + 72, bw, () -> new dev.kyoto.screen.ProfileScreen(screen, Compat.myName())));
		list.add(button("Cosmetics", x, y + 96, bw, () -> new CosmeticsScreen(screen)));
		list.add(button("Mod Finder", x, y + 120, bw, () -> new ModFinderScreen(screen)));
		list.add(button("Kyoto Mods", x, y + 144, bw, () -> new ModsScreen(screen)));
		list.add(button("Help", x, y + 168, bw, ClickGui::help));
		if (!Guides.DISCORD_INVITE.isBlank()) list.add(link("Discord", x, y + 192, bw, Guides.DISCORD_INVITE));

		// Essential puts its own icons in the top-right corner, so move ours out of the way when it's installed
		int oy = Compat.essential() ? h - 56 : 6;
		Button online = Button.builder(Component.literal(onlineText()), b -> Compat.openScreen(new FriendsScreen(screen)))
				.bounds(w - 128, oy, 120, 20).build();
		list.add(online);
		ScreenEvents.afterTick(screen).register(s -> online.setMessage(Component.literal(onlineText())));
	}

	private static String onlineText() {
		if (!Modules.on("kyoto_network")) return "● Kyoto: off";
		return KyotoNet.loggedIn() && KyotoNet.online >= 0 ? "● " + KyotoNet.online + " online" : "● connecting...";
	}

	private static void pause(Screen screen, int w, int h) {
		List<AbstractWidget> list = Screens.getWidgets(screen);
		int x = 8, y = 8, bw = 130;
		list.add(button("Kyoto", x, y, bw, dev.kyoto.screen.KyotoMenu::create));
		list.add(button("☺ Friends", x, y + 24, bw, () -> new FriendsScreen(screen)));
		list.add(button("♚ My Profile", x, y + 48, bw, () -> new dev.kyoto.screen.ProfileScreen(screen, Compat.myName())));
		list.add(button("✉ Kyoto Hub", x, y + 72, bw, () -> new dev.kyoto.screen.HubScreen(screen)));
		list.add(button("? Help", x, y + 96, bw, ClickGui::help));
		if (!Guides.DISCORD_INVITE.isBlank()) list.add(link("◆ Discord", x + bw + 4, y + 96, 70, Guides.DISCORD_INVITE));
		list.add(button("♛ Cosmetics", x, y + 120, bw, () -> new CosmeticsScreen(screen)));
		var mc = net.minecraft.client.Minecraft.getInstance();
		if (mc.getSingleplayerServer() != null) {
			list.add(button("⌂ Host Online World", x, y + 144, bw, () -> new HostWorldScreen(screen)));
		}
	}
}
