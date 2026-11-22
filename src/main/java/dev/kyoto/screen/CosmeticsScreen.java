package dev.kyoto.screen;

import dev.kyoto.config.KyotoConfig;
import dev.kyoto.cosmetic.Accessories;
import dev.kyoto.cosmetic.Aura;
import dev.kyoto.cosmetic.Capes;
import dev.kyoto.cosmetic.Skins;
import dev.kyoto.module.Modules;
import dev.kyoto.net.KyotoNet;
import dev.kyoto.util.Anim;
import dev.kyoto.util.Compat;
import dev.kyoto.util.Ui;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.resources.Identifier;

import java.util.List;
import java.util.Locale;

/**
 * Cosmetics, with tabs:
 *  Capes        - PNG / GIF / animated strip capes + a big preview + link to a cape gallery
 *  Skins        - pick a skin from .minecraft/kyoto/skins, wide/slim arms, live front preview
 *  Accessories  - 26 particle accessories + colour, animated preview
 *  Nametag      - your own nametag + Kyoto badges
 */
public class CosmeticsScreen extends PanelScreen {
	private dev.kyoto.cosmetic.Preview3D skinPreview;
	private String skinPreviewFile = null;

	private static int tab = 0;
	private int page = 0;
	private static final String[] TABS = {"Capes", "Skins", "Particles", "Nametag", "Locker"};
	private static final int LOCKER = 4;

	public CosmeticsScreen(Screen parent) {
		super("Cosmetics", parent);
	}

	@Override
	protected String icon() {
		return "cosmetics";
	}

	@Override
	protected String subtitle() {
		return switch (tab) {
			case LOCKER -> "Everything you got from the Kyoto Store. Click one to wear it - every Kyoto player sees it.";
			case 0 -> "Put .png or .gif capes in .minecraft/kyoto/capes, then pick one.";
			case 1 -> "Put 64x64 skin .png files in .minecraft/kyoto/skins, then pick one.";
			case 2 -> "Particles around your player. Others with Kyoto see them after you Share.";
			default -> "Your nametag + the Kyoto badge next to Kyoto players' names.";
		};
	}

	private KyotoConfig c() {
		return KyotoConfig.get();
	}

	@Override
	protected void build() {
		int tx = px + 14;
		for (int i = 0; i < TABS.length; i++) {
			final int fi = i;
			int bw = Math.max(40, Ui.width(TABS[i]) + 22);
			btn(tx, py + 34, bw, (tab == i ? "▶ " : "") + TABS[i], () -> { tab = fi; page = 0; });
			tx += bw + 2;
		}
		switch (tab) {
			case 0 -> buildCapes();
			case 1 -> buildSkins();
			case 2 -> buildAccessories();
			case LOCKER -> buildLocker();
			default -> buildNametag();
		}
		if (tab != LOCKER) share();
	}

	private void share() {
		btn(px + 14, py + ph - 26, 150, KyotoNet.loggedIn() ? "Share with Kyoto players" : "Log in to share", () -> {
			if (!KyotoNet.loggedIn()) return;
			byte[] cape = Capes.localBytes();
			String type = c().capeFile.toLowerCase(Locale.ROOT).endsWith(".gif") ? "gif" : "png";
			KyotoNet.uploadCosmetics(cape, type, c().accessory);
			KyotoNet.uploadSkin(Skins.localBytes(), c().skinSlim);
		});
		btn(px + 168, py + ph - 26, 76, "Others: " + (Modules.on("others_cosmetics") ? "On" : "Off"), () -> Modules.get("others_cosmetics").toggle());
	}

	private void fileList(List<String> files, String selected, int y, java.util.function.Consumer<String> pick) {
		int perPage = Math.max(3, (py + ph - 80 - y) / 22);
		int pages = Math.max(1, (files.size() + perPage - 1) / perPage);
		page = clamp(page, 0, pages - 1);
		for (int i = page * perPage; i < Math.min(files.size(), (page + 1) * perPage); i++) {
			String f = files.get(i);
			boolean sel = f.equals(selected);
			String kind = f.toLowerCase(Locale.ROOT).endsWith(".gif") ? "  (animated)" : "";
			btn(px + 14, y, 230, (sel ? "▶ " : "") + Ui.ellipsize(f, 170) + kind, () -> pick.accept(f));
			y += 22;
		}
		if (files.isEmpty()) btn(px + 14, y, 230, "Folder is empty - open it ↑", () -> {});
		pager(px + 14, py + ph - 50, 230, page, pages, v -> page = v);
	}

	private void buildCapes() {
		int y = py + 58;
		btn(px + 14, y, 74, "Open folder", () -> Compat.openFolder(Capes.folder()));
		btn(px + 92, y, 74, "Get capes ↗", () -> Compat.openUrl("https://minecraftcapes.net/gallery"));
		btn(px + 170, y, 74, "Cape: " + (Modules.on("cape") ? "On" : "Off"), () -> Modules.get("cape").toggle());
		y += 24;
		btn(px + 14, y, 230, (c().capeFile.isBlank() ? "▶ " : "") + "No cape", () -> { c().capeFile = ""; c().capeChosenAt = System.currentTimeMillis(); KyotoConfig.save(); Capes.reloadLocal(); });
		fileList(Capes.files(), c().capeFile, y + 22, f -> { c().capeFile = f; c().capeChosenAt = System.currentTimeMillis(); KyotoConfig.save(); Capes.reloadLocal(); });
	}

	private void buildSkins() {
		int y = py + 58;
		btn(px + 14, y, 74, "Open folder", () -> Compat.openFolder(Skins.folder()));
		btn(px + 92, y, 74, "Get skins ↗", () -> Compat.openUrl("https://www.minecraftskins.com/"));
		btn(px + 170, y, 74, "Skin: " + (Modules.on("custom_skin") ? "On" : "Off"), () -> Modules.get("custom_skin").toggle());
		y += 24;
		btn(px + 14, y, 112, "Arms: " + (c().skinSlim ? "Slim" : "Wide"), () -> { c().skinSlim = !c().skinSlim; KyotoConfig.save(); Skins.reloadLocal(); });
		btn(px + 130, y, 114, (c().skinFile.isBlank() ? "▶ " : "") + "My normal skin", () -> { c().skinFile = ""; KyotoConfig.save(); Skins.reloadLocal(); });
		if (!c().skinFile.isBlank()) btn(px + 14, y + 24, 230, "Set as my Minecraft skin", () -> {
			byte[] png = Skins.localBytes();
			boolean slim = c().skinSlim;
			if (png == null) return;
			dev.kyoto.hud.Notifications.push("Changing your skin...", "Sending it to Minecraft", dev.kyoto.hud.Notifications.INFO);
			Thread t = new Thread(() -> {
				try {
					Compat.uploadMojangSkin(png, slim);
					dev.kyoto.net.Heads.refresh(Compat.myName());
					Compat.runOnClient(() -> dev.kyoto.hud.Notifications.push("Skin changed", "Everyone sees it after you rejoin a server", dev.kyoto.hud.Notifications.GOOD));
				} catch (Exception e) {
					Compat.runOnClient(() -> dev.kyoto.hud.Notifications.push("Skin not changed", e.getMessage(), dev.kyoto.hud.Notifications.DANGER));
				}
			}, "Kyoto-Skin");
			t.setDaemon(true);
			t.start();
		});
		fileList(Skins.files(), c().skinFile, y + (c().skinFile.isBlank() ? 24 : 48), f -> { c().skinFile = f; KyotoConfig.save(); Skins.reloadLocal(); });
	}

	private void buildAccessories() {
		int y = py + 58;
		btn(px + 14, y, 230, "Accessory: " + (Modules.on("accessory") ? "On" : "Off"), () -> Modules.get("accessory").toggle());
		y += 24;
		row(y, "Colour", () -> Accessories.COLOURS[Math.min(c().accessoryColor, Accessories.COLOURS.length - 1)],
				() -> { c().accessoryColor = Math.floorMod(c().accessoryColor - 1, Accessories.COLOURS.length); KyotoConfig.save(); },
				() -> { c().accessoryColor = (c().accessoryColor + 1) % Accessories.COLOURS.length; KyotoConfig.save(); });
		y += 24;
		int perPage = Math.max(4, (py + ph - 80 - y) / 22) * 2;
		int n = Accessories.NAMES.length;
		int pages = Math.max(1, (n + perPage - 1) / perPage);
		page = clamp(page, 0, pages - 1);
		int k = 0;
		for (int i = page * perPage; i < Math.min(n, (page + 1) * perPage); i++, k++) {
			final int fi = i;
			btn(px + 14 + (k % 2) * 116, y + (k / 2) * 22, 112, (c().accessory == i ? "▶ " : "") + Accessories.NAMES[i], () -> { c().accessory = fi; KyotoConfig.save(); });
		}
		pager(px + 14, py + ph - 50, 230, page, pages, v -> page = v);
	}

	/* ---------------- Locker: what you own from the Store (no files) ---------------- */
	private static final java.util.List<com.google.gson.JsonObject> LOCKER_ITEMS = new java.util.ArrayList<>();
	private static String lockerMsg = "Loading your Locker...";
	private static long lockerAt = 0;
	private static int coins = 0;

	private void loadLocker(boolean force) {
		if (!KyotoNet.loggedIn()) { lockerMsg = "Log in to your Kyoto account first (Help > Kyoto server)."; return; }
		if (!force && System.currentTimeMillis() - lockerAt < 15000) return;
		lockerAt = System.currentTimeMillis();
		KyotoNet.runAsync(() -> {
			try {
				var r = com.google.gson.JsonParser.parseString(KyotoNet.send("GET", "/library", null, true)).getAsJsonObject();
				java.util.List<com.google.gson.JsonObject> got = new java.util.ArrayList<>();
				for (var e : r.getAsJsonArray("items")) got.add(e.getAsJsonObject());
				int cn = r.has("coins") ? r.get("coins").getAsInt() : 0;
				Compat.runOnClient(() -> {
					LOCKER_ITEMS.clear(); LOCKER_ITEMS.addAll(got); coins = cn;
					lockerMsg = got.isEmpty() ? "Your Locker is empty. Get capes and cosmetics on the Store." : null;
					if (tab == LOCKER) rebuildWidgets();
				});
			} catch (Exception e) {
				Compat.runOnClient(() -> lockerMsg = "Couldn't load your Locker: " + e.getMessage());
			}
		});
	}

	private void buildLocker() {
		loadLocker(false);
		int y = py + 58;
		btn(px + 14, y, 112, "Open the Store ↗", () -> Compat.openUrl(KyotoNet.baseUrl() + "/store"));
		btn(px + 130, y, 114, "Refresh", () -> loadLocker(true));
		y += 24;
		int perPage = Math.max(3, (py + ph - 80 - y) / 22);
		int pages = Math.max(1, (LOCKER_ITEMS.size() + perPage - 1) / perPage);
		page = clamp(page, 0, pages - 1);
		for (int i = page * perPage; i < Math.min(LOCKER_ITEMS.size(), (page + 1) * perPage); i++) {
			var o = LOCKER_ITEMS.get(i);
			boolean wearing = o.has("wearing") && o.get("wearing").getAsBoolean();
			String slot = o.get("slot").getAsString();
			String label = (wearing ? "▶ " : "") + Ui.ellipsize(o.get("title").getAsString(), 140) + "  (" + slot + ")";
			int id = o.get("id").getAsInt();
			btn(px + 14, y, 230, label, () -> wearItem(id, slot, wearing));
			y += 22;
		}
		pager(px + 14, py + ph - 50, 230, page, pages, v -> page = v);
		btn(px + 14, py + ph - 26, 230, "Take everything off", () -> {
			for (String sl : new String[]{"cape", "head", "back", "arm", "leg"}) wearItem(0, sl, true);
		});
	}

	private void wearItem(int id, String slot, boolean takeOff) {
		var b = new com.google.gson.JsonObject();
		if (takeOff) b.addProperty("slot", slot); else b.addProperty("id", id);
		KyotoNet.runAsync(() -> {
			try {
				KyotoNet.send("POST", takeOff ? "/library/unwear" : "/library/wear", b, true);
				var me = Compat.myUuid();
				if (me != null) KyotoNet.recheck(me.toString());
				dev.kyoto.cosmetic.Kycos.refreshMine();
				if (id != 0 || slot.equals("cape")) Compat.runOnClient(() -> dev.kyoto.hud.Notifications.push(takeOff ? "Taken off" : "Wearing it", takeOff ? "" : "Press F5 to see yourself. Other Kyoto players see it within a minute.", dev.kyoto.hud.Notifications.GOOD));
				loadLocker(true);
			} catch (Exception e) {
				Compat.runOnClient(() -> dev.kyoto.hud.Notifications.push("Couldn't do that", e.getMessage(), dev.kyoto.hud.Notifications.DANGER));
			}
		});
	}

	private void buildNametag() {
		int y = py + 58;
		btn(px + 14, y, 230, "Show my nametag (F5): " + (Modules.on("own_nametag") ? "On" : "Off"), () -> Modules.get("own_nametag").toggle());
		y += 22;
		cycleRow(y, Modules.setting("nametag.style"));
		y += 22;
		cycleRow(y, Modules.setting("nametag.health"));
		y += 28;
		btn(px + 14, y, 230, "Kyoto badges: " + (Modules.on("kyoto_badges") ? "On" : "Off"), () -> Modules.get("kyoto_badges").toggle());
		y += 22;
		cycleRow(y, Modules.setting("badge.where"));
		y += 22;
		cycleRow(y, Modules.setting("badge.size"));
	}

	/* ---------------------------------------- previews ---------------------------------------- */

	@Override
	protected void drawContent(GuiGraphicsExtractor g, int mx, int my, float a) {
		int x1 = px + 262, y1 = py + 58, x2 = px + pw - 14, y2 = py + ph - 32;
		if (x2 - x1 < 100) return;
		Ui.round(g, x1, y1, x2, y2, 8, Anim.argb(Ui.GLASS, 0.95f * a));
		int cx = (x1 + x2) / 2;
		switch (tab) {
			case LOCKER -> {
				Ui.bold(g, "Your Locker", x1 + 8, y1 + 6, Anim.argb(Ui.TEXT, a));
				Ui.small(g, coins + " coins", x2 - 8 - Ui.widthSmall(coins + " coins"), y1 + 8, Anim.argb(0xFFD98A, a));
				if (lockerMsg != null) { int ly = y1 + 26; for (String l : wrapText(lockerMsg, x2 - x1 - 16)) { Ui.text(g, l, x1 + 8, ly, Anim.argb(Ui.MUTED, a)); ly += 11; } }
				else {
					int ly = y1 + 26;
					for (var o : LOCKER_ITEMS) {
						if (!(o.has("wearing") && o.get("wearing").getAsBoolean())) continue;
						Ui.text(g, "Wearing: " + o.get("title").getAsString(), x1 + 8, ly, Anim.argb(0x8EF0B4, a));
						Ui.small(g, "by " + o.get("creator").getAsString() + "  ·  " + o.get("slot").getAsString(), x1 + 8, ly + 11, Anim.argb(Ui.MUTED, a));
						ly += 26;
					}
					if (ly == y1 + 26) Ui.text(g, "Click something on the left to wear it.", x1 + 8, ly, Anim.argb(Ui.MUTED, a));
					Ui.small(g, "Click again to take it off.", x1 + 8, y2 - 12, Anim.argb(Ui.MUTED, a));
				}
			}
			case 0 -> {
				Identifier cape = Capes.capeFor(Compat.myUuid());
				Ui.bold(g, "Your cape", x1 + 8, y1 + 6, Anim.argb(Ui.TEXT, a));
				if (cape != null) {
					int h = Math.min(y2 - y1 - 40, 120), w = h * 10 / 16;
					// front of the cape (10x16 area at 1,1 of the 64x32 layout) + its back next to it
					Compat.blitRegion(g, cape, cx - w - 4, y1 + 24, w, h, 1, 1, 10, 16, 64, 32, Anim.argb(0xFFFFFF, a));
					Compat.blitRegion(g, cape, cx + 4, y1 + 24, w, h, 12, 1, 10, 16, 64, 32, Anim.argb(0xFFFFFF, a));
					Ui.small(g, "back", cx - w / 2f - 12, y1 + 28 + h, Anim.argb(Ui.MUTED, a));
					Ui.small(g, "front", cx + w / 2f - 6, y1 + 28 + h, Anim.argb(Ui.MUTED, a));
				} else {
					Ui.text(g, Modules.on("cape") ? "No cape selected" : "Custom Cape is off", x1 + 8, y1 + 24, Anim.argb(Ui.MUTED, a));
				}
			}
			case 1 -> {
				Ui.bold(g, "Your skin", x1 + 8, y1 + 6, Anim.argb(Ui.TEXT, a));
				// you in 3D, both skin layers (hat, jacket, sleeves, trousers), slowly turning
				if (skinPreview == null) skinPreview = new dev.kyoto.cosmetic.Preview3D(200, 260);
				String file = c().skinFile;
				if (!file.equals(skinPreviewFile)) {
					byte[] b = file.isBlank() ? null : Skins.localBytes();
					skinPreview.skinOverride(b == null ? null : dev.kyoto.cosmetic.Preview3D.decode(b, c().skinSlim));
					skinPreviewFile = file;
				}
				skinPreview.render(Compat.myName(), (float) (System.currentTimeMillis() / 2600.0), 0.1f, null, null, 1f, 0.1f);
				int ph3 = Math.max(60, y2 - y1 - 44), pw3 = ph3 * 200 / 260;
				Compat.blitTint(g, skinPreview.texture(), cx - pw3 / 2, y1 + 20, pw3, ph3, Anim.argb(0xFFFFFF, a));
				if (file.isBlank()) Ui.small(g, "Your normal skin", x1 + 8, y1 + 18, Anim.argb(Ui.MUTED, a));
				Ui.small(g, "Kyoto players see it. 'Set as my Minecraft skin' = everyone.", x1 + 8, y2 - 12, Anim.argb(Ui.MUTED, a));
			}
			case 2 -> {
				Ui.bold(g, Accessories.NAMES[Math.min(c().accessory, Accessories.NAMES.length - 1)], x1 + 8, y1 + 6, Anim.argb(Ui.TEXT, a));
				int scale = Math.max(30, Math.min(55, (y2 - y1 - 30) / 3));
				int baseY = y2 - 14;
				double t = System.currentTimeMillis() / 1000.0;
				java.util.List<Aura.P> pts = Aura.points(c().accessory, t, 1.8f);
				pts.sort((p1, p2) -> Float.compare(p1.z, p2.z)); // far side first
				drawAura(g, pts, cx, baseY, scale, a, true);   // behind you
				// your skin (or a soft silhouette)
				Skins.Skin sk = Skins.local();
				int px2 = Math.max(2, Math.round(scale * 1.8f / 32f));
				if (sk != null) drawSkinFront(g, sk.tex(), sk.slim(), cx, baseY - px2 * 32, px2, a);
				else {
					int bw = scale / 4;
					Ui.round(g, cx - bw, baseY - Math.round(scale * 1.8f), cx + bw, baseY - Math.round(scale * 1.38f), 4, Anim.argb(Ui.SOFT, a));
					Ui.round(g, cx - bw, baseY - Math.round(scale * 1.36f), cx + bw, baseY - Math.round(scale * 0.74f), 3, Anim.argb(Ui.SOFT, a));
					Ui.round(g, cx - bw, baseY - Math.round(scale * 0.72f), cx + bw, baseY, 3, Anim.argb(Ui.SOFT, a));
				}
				g.fill(cx - scale / 2, baseY, cx + scale / 2, baseY + 1, Anim.argb(Ui.INK, 0.08f * a));
				drawAura(g, pts, cx, baseY, scale, a, false);  // in front
			}
			default -> {
				Ui.bold(g, "Preview", x1 + 8, y1 + 6, Anim.argb(Ui.TEXT, a));
				String me = Compat.myName();
				int w = Ui.width(me) + 20;
				Ui.round(g, cx - w / 2, y1 + 40, cx + w / 2, y1 + 54, 7, Anim.argb(Ui.INK, 0.55f * a));
				Compat.blitTint(g, dev.kyoto.ui.TitleTheme.logo(), cx - w / 2 + 3, y1 + 43, 9, 9, Anim.argb(0xFFFFFF, a));
				Ui.text(g, me, cx - w / 2 + 15, y1 + 43, Anim.argb(Ui.INK, a));
				if (Modules.opt("nametag.health") == 0) {
					g.fill(cx - 20, y1 + 57, cx + 20, y1 + 59, Anim.argb(0x000000, 0.5f * a));
					g.fill(cx - 20, y1 + 57, cx + 12, y1 + 59, Anim.argb(0xFF5D5D, a));
				}
				Ui.small(g, "In chat:", x1 + 8, y1 + 80, Anim.argb(Ui.MUTED, a));
				Compat.blitTint(g, dev.kyoto.ui.TitleTheme.logo(), x1 + 8, y1 + 92, 8, 8, Anim.argb(0xFFFFFF, a));
				Ui.text(g, "<" + me + "> gg!", x1 + 19, y1 + 92, Anim.argb(Ui.INK, a));
			}
		}
	}

	private static java.util.List<String> wrapText(String t, int w) {
		java.util.List<String> out = new java.util.ArrayList<>();
		StringBuilder line = new StringBuilder();
		for (String word : t.split(" ")) {
			if (line.length() > 0 && Ui.width(line + " " + word) > w) { out.add(line.toString()); line.setLength(0); }
			if (line.length() > 0) line.append(' ');
			line.append(word);
		}
		if (line.length() > 0) out.add(line.toString());
		return out;
	}

	private static void drawAura(GuiGraphicsExtractor g, java.util.List<Aura.P> pts, int cx, int baseY, int scale, float a, boolean back) {
		for (Aura.P p : pts) {
			if ((p.z < 0) != back) continue;
			int sx = Math.round(cx + p.x * scale), sy = Math.round(baseY - p.y * scale);
			int sz = Math.max(3, Math.round(p.size * scale * (1 + p.z * 0.25f)));
			Compat.blitTint(g, Aura.TEX[p.tex], sx - sz / 2, sy - sz / 2, sz, sz, Anim.argb(p.rgb, p.a * (back ? 0.7f : 1f) * a));
		}
	}

	/** Flat front view of a 64x64 skin: head, body, arms, legs (+ their outer layers). */
	public static void drawSkinFront(GuiGraphicsExtractor g, Identifier tex, boolean slim, int cx, int y, int px, float a) {
		int col = Anim.argb(Ui.INK, a);
		int aw = slim ? 3 : 4;
		// head + hat
		Compat.blitRegion(g, tex, cx - 4 * px, y, 8 * px, 8 * px, 8, 8, 8, 8, 64, 64, col);
		Compat.blitRegion(g, tex, cx - 4 * px, y, 8 * px, 8 * px, 40, 8, 8, 8, 64, 64, col);
		int by = y + 8 * px;
		// body + jacket
		Compat.blitRegion(g, tex, cx - 4 * px, by, 8 * px, 12 * px, 20, 20, 8, 12, 64, 64, col);
		Compat.blitRegion(g, tex, cx - 4 * px, by, 8 * px, 12 * px, 20, 36, 8, 12, 64, 64, col);
		// right arm (your right = left on screen) + sleeve
		Compat.blitRegion(g, tex, cx - (4 + aw) * px, by, aw * px, 12 * px, 44, 20, aw, 12, 64, 64, col);
		Compat.blitRegion(g, tex, cx - (4 + aw) * px, by, aw * px, 12 * px, 44, 36, aw, 12, 64, 64, col);
		// left arm + sleeve
		Compat.blitRegion(g, tex, cx + 4 * px, by, aw * px, 12 * px, 36, 52, aw, 12, 64, 64, col);
		Compat.blitRegion(g, tex, cx + 4 * px, by, aw * px, 12 * px, 52, 52, aw, 12, 64, 64, col);
		int ly = by + 12 * px;
		// legs + pants
		Compat.blitRegion(g, tex, cx - 4 * px, ly, 4 * px, 12 * px, 4, 20, 4, 12, 64, 64, col);
		Compat.blitRegion(g, tex, cx - 4 * px, ly, 4 * px, 12 * px, 4, 36, 4, 12, 64, 64, col);
		Compat.blitRegion(g, tex, cx, ly, 4 * px, 12 * px, 20, 52, 4, 12, 64, 64, col);
		Compat.blitRegion(g, tex, cx, ly, 4 * px, 12 * px, 4, 52, 4, 12, 64, 64, col);
	}
}
