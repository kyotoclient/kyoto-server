package dev.kyoto.screen;

import dev.kyoto.util.Anim;
import dev.kyoto.util.Compat;
import dev.kyoto.util.ModToggles;
import dev.kyoto.util.Ui;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.entrypoint.EntrypointContainer;
import net.fabricmc.loader.api.metadata.ModMetadata;
import net.fabricmc.loader.api.metadata.Person;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Your installed mods, like Mod Menu: search, details, icons, a Configure button for every mod
 * that provides a settings screen, and On/Off switches (applied on the next launch - see ModToggles).
 */
public class ModsScreen extends PanelScreen {
	private static boolean showLibraries = false;
	private EditBox search;
	private String typed = "";
	private String selected = null;
	private int page = 0;
	private static final Map<String, Object> FACTORIES = new HashMap<>();
	private static boolean factoriesLoaded = false;
	private static final Map<String, Identifier> ICONS = new HashMap<>();

	public ModsScreen(Screen parent) {
		super("Mods", parent);
	}

	@Override
	protected String subtitle() {
		int off = ModToggles.disabled().size();
		return visible().size() + " mods" + (off > 0 ? " · " + off + " turned off" : "") + (showLibraries ? " (including libraries)" : "");
	}

	private static boolean isLibrary(ModContainer m) {
		String id = m.getMetadata().getId();
		return id.equals("java") || id.equals("minecraft") || id.equals("fabricloader") || id.startsWith("fabric-") || id.equals("mixinextras")
				|| m.getMetadata().getType().equals("builtin") || m.getContainingMod().isPresent();
	}

	private List<ModContainer> visible() {
		String q = typed.toLowerCase(Locale.ROOT);
		return FabricLoader.getInstance().getAllMods().stream()
				.filter(m -> showLibraries || !isLibrary(m))
				.filter(m -> q.isEmpty() || m.getMetadata().getName().toLowerCase(Locale.ROOT).contains(q) || m.getMetadata().getId().contains(q))
				.sorted((a, b) -> a.getMetadata().getName().compareToIgnoreCase(b.getMetadata().getName()))
				.collect(Collectors.toList());
	}

	private static void loadFactories() {
		if (factoriesLoaded) return;
		factoriesLoaded = true;
		for (EntrypointContainer<Object> c : FabricLoader.getInstance().getEntrypointContainers("modmenu", Object.class)) {
			try {
				Object api = c.getEntrypoint();
				String id = c.getProvider().getMetadata().getId();
				Method m = api.getClass().getMethod("getModConfigScreenFactory");
				Object f = m.invoke(api);
				if (f != null) FACTORIES.put(id, f);
				try {
					Method prov = api.getClass().getMethod("getProvidedConfigScreenFactories");
					Object map = prov.invoke(api);
					if (map instanceof Map<?, ?> mm) mm.forEach((k, v) -> FACTORIES.putIfAbsent(String.valueOf(k), v));
				} catch (Exception ignored) {
				}
			} catch (Throwable ignored) {
			}
		}
	}

	private Screen configScreen(String id) {
		Object f = FACTORIES.get(id);
		if (f == null) return null;
		for (Method m : f.getClass().getMethods()) {
			if (m.getName().equals("create") && m.getParameterCount() == 1) {
				try {
					m.setAccessible(true);
					Object s = m.invoke(f, this);
					return s instanceof Screen sc ? sc : null;
				} catch (Throwable t) {
					return null;
				}
			}
		}
		return null;
	}

	@Override
	protected String icon() {
		return "mods";
	}

	@Override
	protected void build() {
		loadFactories();
		search = new EditBox(this.font, px + 16, py + 36, 150, 18, Component.literal("Search"));
		search.setHint(Ui.t("Search mods..."));
		search.setValue(typed);
		search.setResponder(s -> {
			typed = s;
			page = 0;
		});
		this.addRenderableWidget(search);
		btn(px + 172, py + 35, 72, showLibraries ? "Libs: on" : "Libs: off", () -> showLibraries = !showLibraries);

		btn(px + 250, py + 35, 84, "Mods folder", () -> Compat.openFolder(ModToggles.modsDir()));
		btn(px + 338, py + 35, 84, "Find mods", () -> Compat.openScreen(new ModFinderScreen(this)));

		// loaded mods first, then the ones that are turned off
		List<Object> entries = new ArrayList<>(visible());
		String q = typed.toLowerCase(Locale.ROOT);
		for (ModToggles.Off o : ModToggles.disabled()) {
			if (q.isEmpty() || o.name().toLowerCase(Locale.ROOT).contains(q) || o.id().toLowerCase(Locale.ROOT).contains(q)) entries.add(o);
		}
		int perPage = Math.max(4, (ph - 100) / 22);
		int pages = Math.max(1, (entries.size() + perPage - 1) / perPage);
		page = clamp(page, 0, pages - 1);
		int y = py + 60;
		for (int i = page * perPage; i < Math.min(entries.size(), (page + 1) * perPage); i++) {
			Object e = entries.get(i);
			if (e instanceof ModContainer mc) {
				ModMetadata md = mc.getMetadata();
				String id = md.getId();
				boolean sel = id.equals(selected);
				if (ModToggles.canToggle(mc)) {
					boolean goingOff = ModToggles.turningOff(mc);
					btn(px + 14, y, 34, goingOff ? "Off" : "On", () -> {
						if (goingOff) ModToggles.cancel(mc);
						else {
							String err = ModToggles.turnOff(mc);
							if (err != null) dev.kyoto.hud.Notifications.push("Couldn't turn it off", err, dev.kyoto.hud.Notifications.DANGER);
						}
					});
				}
				btn(px + 52, y, 192, (sel ? "▶ " : "") + Ui.ellipsize(md.getName(), 160), () -> selected = id);
			} else if (e instanceof ModToggles.Off o) {
				btn(px + 14, y, 34, "Off", () -> {
					String err = ModToggles.turnOn(o);
					if (err != null) dev.kyoto.hud.Notifications.push("Couldn't turn it on", err, dev.kyoto.hud.Notifications.DANGER);
				});
				btn(px + 52, y, 192, "§7" + Ui.ellipsize(o.name(), 160), () -> selected = "off:" + o.file().getFileName());
			}
			y += 22;
		}
		pager(px + 14, py + ph - 26, 100, page, pages, v -> page = v);
		if (ModToggles.restartNeeded()) btn(px + pw - 124, py + ph - 26, 110, "Quit to apply", () -> {
			if (this.minecraft != null) this.minecraft.stop();
		});

		if (selected != null) {
			int bx = px + 256;
			int by = py + ph - 50;
			if (FACTORIES.containsKey(selected)) {
				btn(bx, by, 96, "Configure", () -> {
					Screen s = configScreen(selected);
					if (s != null) Compat.openScreen(s);
					else dev.kyoto.hud.Notifications.push("No settings screen", "This mod has no config menu", Ui.INK);
				});
			}
			if (!selected.equals("kyoto")) btn(bx + 100, by, 96, "Modrinth page", () -> Compat.openUrl("https://modrinth.com/mod/" + selected));
		}
	}

	private Identifier icon(ModContainer m) {
		String id = m.getMetadata().getId();
		if (ICONS.containsKey(id)) return ICONS.get(id);
		ICONS.put(id, null);
		m.getMetadata().getIconPath(64).flatMap(m::findPath).ifPresent(path -> {
			try {
				byte[] png = Files.readAllBytes(path);
				Identifier tex = Identifier.fromNamespaceAndPath("kyoto", "modicons/" + id.replaceAll("[^a-z0-9_.-]", "_"));
				Compat.registerPng(tex, png, () -> ICONS.put(id, tex));
			} catch (Exception ignored) {
			}
		});
		return null;
	}

	@Override
	protected void drawContent(GuiGraphicsExtractor g, int mx, int my, float a) {
		if (ModToggles.restartNeeded()) {
			Ui.text(g, "Restart Minecraft to finish turning mods on/off.", px + 120, py + ph - 20, Anim.argb(Ui.INK, a));
		}
		if (selected != null && selected.startsWith("off:")) {
			int x = px + 256, y = py + 60, w = px + pw - 14 - x;
			for (ModToggles.Off o : ModToggles.disabled()) {
				if (!selected.equals("off:" + o.file().getFileName())) continue;
				Ui.bold(g, Ui.ellipsize(o.name(), w), x, y + 2, Anim.argb(Ui.TEXT, a));
				Ui.text(g, "Turned off  ·  " + o.file().getFileName(), x, y + 14, Anim.argb(Ui.MUTED, a));
				int ty = y + 32;
				for (String s : wrap(o.desc().isBlank() ? "No description." : o.desc(), w, 10)) {
					Ui.text(g, s, x, ty, Anim.argb(Ui.INK, a));
					ty += 11;
				}
				Ui.text(g, "Press Off on the left to turn it back on.", x, ty + 8, Anim.argb(Ui.MUTED, a));
			}
			return;
		}
		if (selected == null) {
			Ui.text(g, "Pick a mod on the left.", px + 260, py + 64, Anim.argb(Ui.MUTED, a));
			return;
		}
		ModContainer m = FabricLoader.getInstance().getModContainer(selected).orElse(null);
		if (m == null) return;
		ModMetadata md = m.getMetadata();
		int x = px + 256, y = py + 60, w = px + pw - 14 - x;
		Identifier ic = icon(m);
		if (ic != null) Compat.blit(g, ic, x, y, 32, 32);
		else Ui.round(g, x, y, x + 32, y + 32, 6, Anim.argb(Ui.accent(), 0.3f * a));
		Ui.bold(g, Ui.ellipsize(md.getName(), w - 40), x + 38, y + 2, Anim.argb(Ui.TEXT, a));
		Ui.text(g, "v" + md.getVersion().getFriendlyString() + "  ·  " + md.getId(), x + 38, y + 14, Anim.argb(Ui.MUTED, a));
		String authors = md.getAuthors().stream().map(Person::getName).collect(Collectors.joining(", "));
		if (!authors.isEmpty()) Ui.text(g, Ui.ellipsize("by " + authors, w - 40), x + 38, y + 24, Anim.argb(Ui.MUTED, a));
		int ty = y + 42;
		for (String s : wrap(md.getDescription().isBlank() ? "No description." : md.getDescription(), w, 12)) {
			Ui.text(g, s, x, ty, Anim.argb(Ui.INK, a));
			ty += 11;
		}
		if (FACTORIES.containsKey(selected)) Ui.text(g, "✔ Has a settings screen", x, py + ph - 64, Anim.argb(Ui.INK, a));
	}
}
