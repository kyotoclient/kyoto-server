package dev.kyoto.cosmetic;

import dev.kyoto.extra.Nearby;
import dev.kyoto.hud.Notifications;
import dev.kyoto.module.Modules;
import dev.kyoto.ui.Sounds;
import dev.kyoto.util.Anim;
import dev.kyoto.util.Compat;
import dev.kyoto.util.Ui;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Cosmetics > Pets / Wardrobe. A big turntable preview (drag to turn) next to a grid of everything you can wear.
 * Previews are rendered from the same 3D models you see in game.
 */
public class WardrobeScreen extends Screen {
	private static final String[] TABS = {"pet", "hat", "halo", "back", "aura"};
	private static final String[] TAB_NAMES = {"Pets", "Hats", "Halos", "Wings + back", "Auras"};
	private static final Map<String, String> NAMES = new HashMap<>();
	static {
		String[][] n = {{"fox", "Fox"}, {"cat", "Cat"}, {"bunny", "Bunny"}, {"frog", "Frog"}, {"axolotl", "Axolotl"}, {"bee", "Bee"}, {"ghost", "Ghost"},
				{"dragon", "Dragon"}, {"slime", "Slime"}, {"star", "Star"}, {"kasa", "Sakura Kasa"}, {"crown", "Crown"}, {"cat_ears", "Cat Ears"},
				{"bunny_ears", "Bunny Ears"}, {"flower_crown", "Flower Crown"}, {"top_hat", "Top Hat"}, {"beanie", "Beanie"}, {"goat_horns", "Goat Horns"}, {"sakura_horns", "Sakura Horns"}, {"halo_gold", "Gold Halo"},
				{"halo_sakura", "Sakura Halo"}, {"wings_sakura", "Sakura Wings"}, {"wings_angel", "Angel Wings"}, {"wings_butterfly", "Butterfly Wings"}, {"backpack", "Sakura Pack"}};
		for (String[] x : n) NAMES.put(x[0], x[1]);
	}

	private final Screen parent;
	private String tab;
	private String focus;
	private final Map<String, List<String>> index = Mesh3D.index();
	private final Map<String, Float> anim = new HashMap<>();
	private EditBox petName;
	private long openedAt;
	private float turn = 0, turnVel = 0;
	private boolean dragging = false;
	private double lastDragX = 0;
	private int px, py, pw, ph;
	private Preview3D preview;

	private record Hit(int x1, int y1, int x2, int y2, Runnable r) {}

	private final List<Hit> hits = new ArrayList<>();

	public WardrobeScreen(Screen parent, String tab) {
		super(Component.literal("Wardrobe"));
		this.parent = parent;
		this.tab = tab == null ? "pet" : tab;
		this.focus = current();
	}

	private String current() {
		if (tab.equals("pet")) return Nearby.myPet().equals("none") ? null : Nearby.myPet();
		if (tab.equals("aura")) {
			int a = dev.kyoto.config.KyotoConfig.get().accessory;
			return a <= 0 || !Modules.on("accessory") ? null : "aura" + a;
		}
		return Wardrobe.mine().get(tab);
	}

	@Override
	protected void init() {
		openedAt = System.currentTimeMillis();
		layout();
		petName = new EditBox(this.font, px + 20, py + ph - 30, 120, 12, Component.literal("Pet name"));
		petName.setBordered(false);
		petName.setMaxLength(16);
		petName.setTextColor(0xFF000000 | Ui.INK);
		petName.setHint(Ui.t("Name your pet..."));
		petName.setValue(dev.kyoto.config.KyotoConfig.get().petName);
		addRenderableWidget(petName);
	}

	private void layout() {
		pw = Math.min(width - 20, 600);
		ph = Math.min(height - 20, 340);
		px = (width - pw) / 2;
		py = (height - ph) / 2;
	}

	private float a(String k, boolean on) {
		float v = anim.getOrDefault(k, on ? 1f : 0f);
		v += ((on ? 1f : 0f) - v) * 0.22f;
		anim.put(k, v);
		return v;
	}

	private static Identifier sheet(String id) {
		return Identifier.fromNamespaceAndPath("kyoto", "textures/gui/wardrobe/" + id + ".png");
	}

	/** One frame of an item's turntable (16 frames, 128px each). */
	private static void frame(GuiGraphicsExtractor g, String id, int x, int y, int size, float angle, float alpha) {
		int f = Math.floorMod(Math.round(angle / 22.5f), 16);
		Compat.blitRegion(g, sheet(id), x, y, size, size, f * 128, 0, 128, 128, 2048, 128, Anim.argb(0xFFFFFF, alpha));
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mx, int my, float partial) {
		super.extractBackground(g, mx, my, partial);
		layout();
		hits.clear();
		float al = Anim.easeOutCubic(Anim.clamp01((System.currentTimeMillis() - openedAt) / 300f));
		int acc = Ui.accent();
		g.fillGradient(0, 0, width, height, Anim.argb(Ui.BG_TOP, 0.45f * al), Anim.argb(Ui.BG_BOTTOM, 0.6f * al));
		g.pose().pushMatrix();
		float sc = 0.95f + 0.05f * Anim.easeOutBack(al);
		g.pose().translate(width / 2f, height / 2f);
		g.pose().scale(sc, sc);
		g.pose().translate(-width / 2f, -height / 2f);
		Ui.glass(g, px, py, px + pw, py + ph, 16, al);
		Ui.boldScaled(g, "Cosmetics", px + 16, py + 12, 1.4f, Anim.argb(Ui.TEXT, al));
		Ui.small(g, "Only you see these, unless others use Kyoto", px + 16, py + 29, Anim.argb(Ui.MUTED, al));
		// close
		boolean ch = Ui.inside(mx, my, px + pw - 26, py + 10, px + pw - 10, py + 26);
		Ui.iconTex(g, "close", px + pw - 22, py + 14, 8, Anim.argb(ch ? Ui.TEXT : Ui.MUTED, al));
		hits.add(new Hit(px + pw - 26, py + 10, px + pw - 10, py + 26, this::onClose));
		// tabs
		int tx = px + 16, ty = py + 44;
		for (int i = 0; i < TABS.length; i++) {
			int w = Ui.widthSmall(TAB_NAMES[i]) + 18;
			boolean sel = tab.equals(TABS[i]);
			float s = a("tab" + i, sel);
			Ui.round(g, tx, ty, tx + w, ty + 16, 8, Anim.argb(sel ? acc : Ui.INK, (0.06f + 0.84f * s) * al));
			Ui.small(g, TAB_NAMES[i], tx + 9, ty + 5, Anim.argb(Ui.mix(Ui.MUTED, 0xFFFFFF, s), al));
			final String t = TABS[i];
			hits.add(new Hit(tx, ty, tx + w, ty + 16, () -> { if (!tab.equals(t)) Sounds.play(Sounds.TAB); tab = t; focus = current(); }));
			tx += w + 5;
		}
		// turntable
		int sx1 = px + 14, sy1 = py + 68, sx2 = px + Math.round(pw * 0.42f), sy2 = py + ph - (tab.equals("pet") ? 44 : 14);
		Ui.round(g, sx1, sy1, sx2, sy2, 12, Anim.argb(Ui.DARK ? 0x000000 : 0xFFFFFF, (Ui.DARK ? 0.18f : 0.45f) * al));
		Ui.ring(g, sx1, sy1, sx2, sy2, 12, 1, Anim.argb(0xFFFFFF, (Ui.DARK ? 0.1f : 0.8f) * al));
		int cxp = (sx1 + sx2) / 2, size = Math.min(sx2 - sx1 - 20, sy2 - sy1 - 30);
		// a soft glow disc behind the item
		Ui.circle(g, cxp, sy2 - 40, size * 0.3f, Anim.argb(acc, 0.08f * al));
		if (!dragging) {
			turn += turnVel;
			turnVel *= 0.92f;
			if (Math.abs(turnVel) < 0.3f) turn += 0.35f; // slow spin
		}
		String show = focus;
		// you, in 3D, wearing it (your real skin with both layers; the same models as in game)
		if (preview == null) preview = new Preview3D(240, 300);
		java.util.Map<String, String> wear = new java.util.HashMap<>(Wardrobe.mine());
		String pet = Nearby.myPet().equals("none") ? null : Nearby.myPet();
		if (tab.equals("pet")) pet = show;
		else if (show != null) wear.put(tab, show);
		else wear.remove(tab);
		float focusY = tab.equals("hat") || tab.equals("halo") ? 0.55f : 0.1f;
		preview.render(Compat.myName(), (float) Math.toRadians(turn) + (tab.equals("back") ? (float) Math.PI : 0f), 0.12f, wear, pet, tab.equals("hat") || tab.equals("halo") ? 1.25f : 1f, focusY);
		int ph2 = sy2 - sy1 - 34, pw2 = Math.round(ph2 * 240f / 300f);
		int auraType = tab.equals("aura") ? (show == null ? 0 : Integer.parseInt(show.substring(4))) : (Modules.on("accessory") ? dev.kyoto.config.KyotoConfig.get().accessory : 0);
		List<Aura.P> pts = auraType > 0 ? Aura.points(auraType, System.currentTimeMillis() / 1000.0, 1.8f) : List.of();
		int ox = cxp - pw2 / 2, oy = sy1 + 4;
		g.enableScissor(sx1 + 2, sy1 + 2, sx2 - 2, sy2 - 2);
		for (int pass = 0; pass < 2; pass++) {
			if (pass == 1) Compat.blitTint(g, preview.texture(), ox, oy, pw2, ph2, Anim.argb(0xFFFFFF, al));
			for (Aura.P p : pts) {
				float[] sp = preview.project(p.x, p.y, p.z);
				if (sp == null || (sp[2] >= 0) != (pass == 1)) continue;
				float sz = Math.max(2f, p.size * preview.pixelsPerBlock() * pw2 / 240f * 1.6f);
				Compat.blitTint(g, Aura.TEX[p.tex], Math.round(ox + sp[0] * pw2 - sz / 2), Math.round(oy + sp[1] * ph2 - sz / 2), Math.round(sz), Math.round(sz), Anim.argb(p.rgb, p.a * al));
			}
		}
		g.disableScissor();
		if (show != null) {
			String nm = show.startsWith("aura") ? Accessories.NAMES[Integer.parseInt(show.substring(4))] : NAMES.getOrDefault(show, show);
			Ui.bold(g, nm, cxp - Ui.widthBold(nm) / 2, sy2 - 26, Anim.argb(Ui.TEXT, al));
			boolean worn = show.equals(current());
			String st = worn ? "Wearing" : "Click it again to wear";
			if (tab.equals("pet")) st = worn ? "Following you" : "Click it again to bring it";
			Ui.small(g, st, cxp - Ui.widthSmall(st) / 2f, sy2 - 14, Anim.argb(worn ? acc : Ui.DIM, al));
		} else {
			String nn = tab.equals("pet") ? "No pet out" : "Nothing worn here";
			Ui.small(g, nn, cxp - Ui.widthSmall(nn) / 2f, sy2 - 14, Anim.argb(Ui.MUTED, al));
		}
		Ui.small(g, "Drag to turn", cxp - Ui.widthSmall("Drag to turn") / 2f, sy1 + 8, Anim.argb(Ui.DIM, al));
		if (Ui.inside(mx, my, sx1, sy1, sx2, sy2)) hits.add(new Hit(sx1, sy1, sx2, sy2, () -> { dragging = true; }));
		// pet name
		petName.visible = tab.equals("pet") && al > 0.9f;
		if (tab.equals("pet")) {
			int ny = py + ph - 36;
			Ui.round(g, sx1, ny, sx2 - 60, ny + 20, 10, Anim.argb(Ui.GLASS_LIGHT, (Ui.DARK ? 0.12f : 0.7f) * al));
			Ui.ring(g, sx1, ny, sx2 - 60, ny + 20, 10, 1, Anim.argb(0xFFFFFF, (Ui.DARK ? 0.1f : 0.9f) * al));
			petName.setX(sx1 + 10);
			petName.setY(ny + 6);
			petName.setWidth(sx2 - 60 - sx1 - 16);
			boolean sh = Ui.inside(mx, my, sx2 - 54, ny, sx2, ny + 20);
			Ui.button(g, sx2 - 54, ny, sx2, ny + 20, "Save", sh ? 1f : 0.6f, true, al);
			hits.add(new Hit(sx2 - 54, ny, sx2, ny + 20, () -> {
				String p = Nearby.myPet();
				if (!p.equals("none")) Nearby.setPet(p, petName.getValue().replaceAll("[^\\w ]", ""));
				Sounds.play(Sounds.SUCCESS);
			}));
		}
		// grid
		List<String> items = new ArrayList<>(index.getOrDefault(tab, List.of()));
		if (tab.equals("aura")) for (int i = 1; i < Accessories.NAMES.length; i++) items.add("aura" + i);
		items.add(0, "");
		int gx1 = sx2 + 12, gx2 = px + pw - 14, gy = py + 68;
		int cols = Math.max(2, (gx2 - gx1) / 74), gap = 6, cw = (gx2 - gx1 - gap * (cols - 1)) / cols, chh = cw + 14;
		String cur = current();
		for (int i = 0; i < items.size(); i++) {
			String id = items.get(i);
			float f = Anim.easeOutCubic(Anim.clamp01((System.currentTimeMillis() - openedAt - i * 25) / 260f));
			int x = gx1 + (i % cols) * (cw + gap), y = gy + (i / cols) * (chh + gap) + Math.round((1 - f) * 8);
			if (y + chh > py + ph - 8) break;
			boolean hov = Ui.inside(mx, my, x, y, x + cw, y + chh);
			boolean worn = id.isEmpty() ? cur == null : id.equals(cur);
			boolean foc = id.isEmpty() ? focus == null : id.equals(focus);
			float h = a("it" + tab + id, hov), w = a("worn" + tab + id, worn);
			int lift = Math.round(h * 2);
			Ui.round(g, x, y - lift, x + cw, y + chh - lift, 10, Anim.argb(Ui.GLASS_LIGHT, ((Ui.DARK ? 0.05f : 0.4f) + 0.2f * h) * al * f));
			Ui.ring(g, x, y - lift, x + cw, y + chh - lift, 10, foc ? 1.5f : 1f, Anim.argb(Ui.mix(0xFFFFFF, acc, Math.max(w, foc ? 0.6f : 0)), (Ui.DARK ? 0.12f + 0.6f * w : 0.75f + 0.25f * w) * al * f));
			if (id.startsWith("aura")) {
				int at = Integer.parseInt(id.substring(4));
				float tt = System.currentTimeMillis() / 1000f;
				g.enableScissor(x + 2, y + 2 - lift, x + cw - 2, y + cw - 4 - lift);
				for (Aura.P p : Aura.points(at, tt, 1.8f)) {
					float sx = x + cw / 2f - p.x * cw * 0.32f, syy = y + cw - 8 - lift - p.y * cw * 0.3f;
					float sz = Math.max(2f, p.size * cw * 0.5f);
					Compat.blitTint(g, Aura.TEX[p.tex], Math.round(sx - sz / 2), Math.round(syy - sz / 2), Math.round(sz), Math.round(sz), Anim.argb(p.rgb, p.a * al * f * (p.z < 0 ? 0.6f : 1f)));
				}
				g.disableScissor();
				String nm = Ui.ellipsize(Accessories.NAMES[at], Math.round((cw - 6) / 0.75f));
				Ui.small(g, nm, x + cw / 2f - Ui.widthSmall(nm) / 2f, y + cw - 2 - lift, Anim.argb(worn ? acc : Ui.MUTED, al * f));
			} else if (id.isEmpty()) {
				Ui.iconTex(g, "close", x + cw / 2 - 5, y + cw / 2 - 5 - lift, 10, Anim.argb(Ui.DIM, al * f));
				Ui.small(g, "None", x + cw / 2f - Ui.widthSmall("None") / 2f, y + cw - 2 - lift, Anim.argb(Ui.MUTED, al * f));
			} else if (!id.startsWith("aura")) {
				float ang = hov ? (System.currentTimeMillis() % 3600L) / 10f : 0;
				frame(g, id, x + 4, y + 2 - lift, cw - 8, ang, al * f);
				String nm = Ui.ellipsize(NAMES.getOrDefault(id, id), Math.round((cw - 6) / 0.75f));
				Ui.small(g, nm, x + cw / 2f - Ui.widthSmall(nm) / 2f, y + cw - 2 - lift, Anim.argb(worn ? acc : Ui.MUTED, al * f));
			}
			if (worn) Ui.circle(g, x + cw - 8, y + 8 - lift, 3, Anim.argb(acc, al * f));
			hits.add(new Hit(x, y, x + cw, y + chh, () -> pick(id)));
		}
		String err = Pets3D.error != null ? "3D pets: " + Pets3D.error : Wardrobe.error != null ? "Wardrobe: " + Wardrobe.error : dev.kyoto.cosmetic.Kycos.drawError;
		if (err != null && !err.isBlank()) Ui.small(g, Ui.ellipsize("Can't draw in the world: " + err, Math.round((gx2 - gx1) / 0.75f)), gx1, py + ph - 24, Anim.argb(0xE5484D, al));
		if (!Modules.on(tab.equals("pet") ? "pets" : tab.equals("aura") ? "accessory" : "wardrobe")) {
			String off = (tab.equals("pet") ? "Kyoto Pets" : tab.equals("aura") ? "Accessory" : "Wardrobe") + " is off in Cosmetics, so these won't show in game";
			Ui.small(g, off, gx1, py + ph - 14, Anim.argb(0xE5484D, al));
		}
		g.pose().popMatrix();
	}

	private void pick(String id) {
		String want = id.isEmpty() ? null : id;
		boolean same = want == null ? focus == null : want.equals(focus);
		focus = want;
		if (!same && want != null) {
			Sounds.play(Sounds.TAB);
			turn = -25;
			return; // first click previews, second click wears
		}
		if (tab.equals("aura")) {
			var cfg = dev.kyoto.config.KyotoConfig.get();
			cfg.accessory = want == null ? 0 : Integer.parseInt(want.substring(4));
			dev.kyoto.config.KyotoConfig.save();
			var am = Modules.get("accessory");
			if (am != null && want != null && !am.on()) am.set(true);
			if (dev.kyoto.net.KyotoNet.loggedIn()) dev.kyoto.net.KyotoNet.uploadCosmetics(Capes.localBytes(), cfg.capeFile.toLowerCase(java.util.Locale.ROOT).endsWith(".gif") ? "gif" : "png", cfg.accessory);
		} else if (tab.equals("pet")) {
			Nearby.setPet(want == null ? "none" : want, petName == null ? "" : petName.getValue());
			Notifications.push(want == null ? "Pet put away" : "Say hi to your " + NAMES.getOrDefault(want, want).toLowerCase(), want == null ? "" : "Kyoto players near you can see it", Notifications.GOOD, 2500, null, "paw");
		} else {
			Wardrobe.set(tab, want);
		}
		Sounds.play(want == null ? Sounds.OFF : Sounds.SUCCESS);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent e, boolean dbl) {
		lastDragX = e.x();
		for (int i = hits.size() - 1; i >= 0; i--) {
			Hit h = hits.get(i);
			if (Ui.inside(e.x(), e.y(), h.x1, h.y1, h.x2, h.y2)) {
				h.r.run();
				if (!dragging) return true;
				break;
			}
		}
		return super.mouseClicked(e, dbl);
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent e, double dx, double dy) {
		if (dragging) {
			turn += (float) dx * 2.2f;
			turnVel = (float) dx * 2.2f;
			return true;
		}
		return super.mouseDragged(e, dx, dy);
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent e) {
		dragging = false;
		return super.mouseReleased(e);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void onClose() {
		if (preview != null) preview.close();
		preview = null;
		Compat.openScreen(parent);
	}
}
