package dev.kyoto.screen;

import dev.kyoto.config.KyotoConfig;
import dev.kyoto.module.Modules;
import dev.kyoto.module.Modules.Category;
import dev.kyoto.module.Modules.Module;
import dev.kyoto.module.Modules.Setting;
import dev.kyoto.music.AlbumArt;
import dev.kyoto.music.Lyrics;
import dev.kyoto.music.Music;
import dev.kyoto.net.KyotoNet;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Kyoto menu (Right Shift) - the classic layout, polished.
 *  Left:  music player (album art, spinning vinyl, visualizer, play/pause) - or your Kyoto card when nothing plays.
 *  Right: glass panel with an icon rail (tabs), header, grouped rows with switches, segmented controls,
 *         colour swatches and font previews. Everything animates.
 *  The search box searches EVERYTHING: every tab, settings, tags, tools and help.
 */
public class ClickGui extends Screen {
	private static int lastTab = 0;
	private static final int TOOLS = Category.values().length; // tab index of the Tools page
	private static final int HUB = TOOLS + 1, HELP = TOOLS + 2;

	private int tab = lastTab;
	private EditBox search;
	private String query = "";

	private long openedAt;
	private long closingAt = -1;

	private float scroll = 0, scrollTarget = 0, maxScroll = 0;
	private float railScroll = 0, railScrollTarget = 0, railMax = 0;
	private float railSel = -1;
	private float contentFade = 1;
	private final Map<String, Float> anim = new HashMap<>();
	private final Map<String, Boolean> expanded = new HashMap<>();

	private record Hit(int x1, int y1, int x2, int y2, java.util.function.IntConsumer action) {}

	private final List<Hit> hits = new ArrayList<>();

	// layout
	private int px, py, pw, ph; // panel
	private int mx0, my0, mw; // music area
	private boolean showPlayer;

	private static final Identifier VINYL = Identifier.fromNamespaceAndPath("kyoto", "textures/gui/vinyl.png");
	private static final Identifier LOGO = Identifier.fromNamespaceAndPath("kyoto", "textures/gui/logo_k.png");

	public ClickGui() {
		super(Component.literal("Kyoto"));
	}

	/** Opens the menu on the Help tab. */
	public static ClickGui help() {
		lastTab = HELP;
		return new ClickGui();
	}

	@Override
	protected void init() {
		openedAt = System.currentTimeMillis();
		layout();
		search = new EditBox(this.font, px + pw - 120, py + 14, 92, 12, Component.literal("Search"));
		search.setBordered(false);
		search.setMaxLength(40);
		search.setTextColor(0xFF000000 | dev.kyoto.util.Ui.INK);
		search.setHint(Ui.t("Search..."));
		search.setValue(query);
		search.setResponder(s -> {
			query = s;
			scrollTarget = 0;
			contentFade = 0;
		});
		this.addRenderableWidget(search);
	}

	private void layout() {
		int panelW = Math.min(this.width - 16, 400);
		ph = Math.min(this.height - 20, 300);
		int playerW = 176;
		showPlayer = Modules.on("menu_player") && this.width >= panelW + playerW + 30;
		int total = panelW + (showPlayer ? playerW + 14 : 0);
		int startX = (this.width - total) / 2;
		pw = panelW;
		px = showPlayer ? startX + playerW + 14 : startX;
		py = (this.height - ph) / 2;
		mx0 = startX;
		my0 = py;
		mw = playerW;
	}

	private float openProgress() {
		float speed = Ui.animSpeed();
		if (speed <= 0) return closingAt > 0 ? 0 : 1;
		long now = System.currentTimeMillis();
		if (closingAt > 0) return 1 - Anim.clamp01((now - closingAt) / (160f * speed));
		return Anim.clamp01((now - openedAt) / (280f * speed));
	}

	@Override
	public void onClose() {
		lastTab = tab;
		if (Ui.animSpeed() <= 0) {
			Compat.openScreen(null);
			return;
		}
		if (closingAt < 0) closingAt = System.currentTimeMillis();
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	/* ============================================ drawing ============================================ */

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partial) {
		super.extractBackground(g, mouseX, mouseY, partial);
		float a = Anim.easeOutCubic(openProgress());
		// soft dark wash on the player side (like the reference UI)
		float dim = new float[]{0.6f, 1f, 1.5f}[Modules.opt("menu.dim")];
		g.fillGradient(0, 0, this.width, this.height, Anim.argb(Ui.GLASS, Math.min(0.9f, 0.35f * dim) * a), Anim.argb(Ui.GLASS, Math.min(0.95f, 0.55f * dim) * a));
		draw(g, mouseX, mouseY);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partial) {
		float open = openProgress();
		if (closingAt > 0 && open <= 0) {
			Compat.openScreen(null);
			return;
		}
		search.visible = open > 0.9f;
		super.extractRenderState(g, mouseX, mouseY, partial);
	}

	private float a(String key, boolean target) {
		return a(key, target ? 1f : 0f, 0.25f);
	}

	private float a(String key, float target, float speed) {
		float v = anim.getOrDefault(key, target);
		float sp = Ui.animSpeed() <= 0 ? 1f : Math.min(1f, speed / Ui.animSpeed());
		v += (target - v) * sp;
		if (Math.abs(target - v) < 0.002f) v = target;
		anim.put(key, v);
		return v;
	}

	private void draw(GuiGraphicsExtractor g, int mx, int my) {
		layout();
		hits.clear();
		float open = openProgress();
		float al = Anim.easeOutCubic(open);
		int accent = Ui.accent();

		if (showPlayer) {
			g.pose().pushMatrix();
			g.pose().translate(-(1 - al) * 24, 0);
			drawPlayer(g, mx, my, al, accent);
			g.pose().popMatrix();
		}

		float scale = 0.95f + 0.05f * (closingAt > 0 ? al : Anim.easeOutBack(open));
		g.pose().pushMatrix();
		g.pose().translate(px + pw / 2f, py + ph / 2f);
		g.pose().scale(scale, scale);
		g.pose().translate(-(px + pw / 2f), -(py + ph / 2f));

		// soft shadow, thin light border, glass body (Client > Menu Transparency), faint top sheen
		for (int i = 4; i >= 1; i--) Ui.round(g, px - i * 2, py - i * 2 + 3, px + pw + i * 2, py + ph + i * 2 + 3, 12 + i * 2, Anim.argb(0x000000, 0.05f * al));
		Ui.round(g, px - 1, py - 1, px + pw + 1, py + ph + 1, 12, Anim.argb(Ui.INK, 0.08f * al));
		Ui.round(g, px, py, px + pw, py + ph, 11, Anim.argb(Ui.GLASS, Ui.panelAlpha() * al));
		g.fillGradient(px + 11, py + 1, px + pw - 11, py + 30, Anim.argb(Ui.INK, 0.035f * al), Anim.argb(Ui.INK, 0f));

		drawRail(g, mx, my, al, accent);
		drawContent(g, mx, my, al, accent);
		g.pose().popMatrix();
	}

	/* ------------------------------------------- rail (tabs) ------------------------------------------- */

	private static final int RAIL_W = 46, TAB_H = 32;

	private void drawRail(GuiGraphicsExtractor g, int mx, int my, float al, int accent) {
		int x1 = px + 4, x2 = px + RAIL_W;
		int top = py + 8, bottom = py + ph - 8;
		int count = TOOLS + 3;
		railMax = Math.max(0, count * TAB_H - (bottom - top));
		railScrollTarget = Math.max(0, Math.min(railMax, railScrollTarget));
		railScroll += (railScrollTarget - railScroll) * 0.3f;
		g.fill(px + RAIL_W + 3, py + 10, px + RAIL_W + 4, py + ph - 10, Anim.argb(Ui.INK, 0.06f * al));

		g.enableScissor(px, top, x2 + 2, bottom);
		int selTarget = top + tab * TAB_H - Math.round(railScroll);
		if (railSel < 0) railSel = selTarget;
		railSel += (selTarget - railSel) * (Ui.animSpeed() <= 0 ? 1f : 0.3f);
		if (query.isEmpty()) {
			int sy = Math.round(railSel);
			// soft glow behind the selected tab
			Ui.round(g, x1, sy - 1, x2, sy + TAB_H + 1, 10, Anim.argb(accent, 0.12f * al));
			Ui.round(g, x1 + 2, sy + 1, x2 - 2, sy + TAB_H - 1, 8, Anim.argb(accent, 0.95f * al));
		}
		for (int i = 0; i < count; i++) {
			int y = top + i * TAB_H - Math.round(railScroll);
			if (y + TAB_H < top || y > bottom) continue;
			String icon = i == TOOLS ? "tools" : i == HUB ? "hub" : i == HELP ? "help" : Category.values()[i].tex;
			String label = i == TOOLS ? "Tools" : i == HUB ? "Hub" : i == HELP ? "Help" : Category.values()[i].title;
			boolean sel = i == tab && query.isEmpty();
			boolean hov = Ui.inside(mx, my, x1, Math.max(top, y), x2, Math.min(bottom, y + TAB_H));
			float hv = a("tab" + i, hov);
			if (!sel && hv > 0.01f) Ui.round(g, x1 + 2, y + 1, x2 - 2, y + TAB_H - 1, 8, Anim.argb(Ui.INK, 0.06f * hv * al));
			int col = sel ? Ui.GLASS : Ui.mix(Ui.MUTED, Ui.INK, hv);
			int cx = (x1 + x2) / 2;
			Ui.iconTex(g, icon, cx - 6, y + 5, 12, Anim.argb(col, al));
			String lb = label.length() > 9 ? label.substring(0, 8) + "." : label;
			Ui.small(g, lb, cx - Ui.widthSmall(lb) / 2f, y + 20, Anim.argb(col, al));
			final int fi = i;
			if (y >= top - TAB_H / 2 && y + TAB_H <= bottom + TAB_H / 2) hits.add(new Hit(x1, Math.max(top, y), x2, Math.min(bottom, y + TAB_H), b -> selectTab(fi)));
		}
		g.disableScissor();
	}

	private void selectTab(int i) {
		if (i == HUB) {
			lastTab = tab;
			Compat.openScreen(new HubScreen(this));
			return;
		}
		if (tab != i || !query.isEmpty()) {
			tab = i;
			search.setValue("");
			query = "";
			scrollTarget = 0;
			scroll = 0;
			contentFade = 0;
		}
	}

	/* ------------------------------------------- content ------------------------------------------- */

	private void drawContent(GuiGraphicsExtractor g, int mx, int my, float al, int accent) {
		int cx1 = px + RAIL_W + 14, cx2 = px + pw - 12;
		boolean searching = !query.isBlank();
		String title = searching ? "Search" : tab == TOOLS ? "Tools" : tab == HELP ? "Help" : Category.values()[tab].title;
		String sub = searching ? "Results for \"" + query + "\"" : tab == TOOLS ? "Editors, friends, mods and folders"
				: tab == HELP ? "How to set everything up" : Category.values()[tab].subtitle;
		Ui.boldScaled(g, title, cx1, py + 11, 1.35f, Anim.argb(Ui.TEXT, al));
		Ui.small(g, sub, cx1, py + 25, Anim.argb(Ui.MUTED, al));
		anim.putIfAbsent("underline" + tab + searching, 0f);
		float ul = a("underline" + tab + searching, 1f, 0.12f);
		g.fill(cx1, py + 34, cx1 + Math.round((Ui.width(title) * 1.35f) * ul), py + 35, Anim.argb(accent, 0.8f * al));

		// close button
		int bx = px + pw - 22, by = py + 9;
		float ch = a("close", Ui.inside(mx, my, bx, by, bx + 14, by + 14));
		Ui.round(g, bx, by, bx + 14, by + 14, 7, Anim.argb(Ui.INK, (0.05f + 0.1f * ch) * al));
		Ui.iconTex(g, "close", bx + 4, by + 4, 6, Anim.argb(Ui.mix(Ui.MUTED, Ui.INK, ch), al));
		hits.add(new Hit(bx, by, bx + 14, by + 14, b -> onClose()));

		// search pill
		{
			int sx2 = bx - 6, sx1 = sx2 - 104;
			boolean focused = search.isFocused();
			Ui.round(g, sx1, by, sx2, by + 14, 7, Anim.argb(Ui.INK, (focused ? 0.1f : 0.05f) * al));
			Ui.iconTex(g, "search", sx1 + 5, by + 4, 6, Anim.argb(focused ? accent : Ui.MUTED, al));
			search.setX(sx1 + 14);
			search.setY(by + 3);
			search.setWidth(sx2 - sx1 - 18);
			hits.add(new Hit(sx1, by, sx1 + 14, by + 14, b -> search.setFocused(true)));
		}

		int top = py + 38, bottom = py + ph - 8;
		scroll += (scrollTarget - scroll) * 0.3f;
		contentFade += (1 - contentFade) * 0.16f;
		float fa = al * Anim.clamp01(contentFade * 1.3f);
		int slide = Math.round((1 - contentFade) * 10);

		g.enableScissor(cx1 - 2, top, cx2 + 4, bottom);
		int y = top - Math.round(scroll) + slide;
		if (searching) {
			y = drawModules(g, cx1, cx2, y, mx, my, fa, accent, top, bottom, true);
			y = drawTools(g, cx1, cx2, y, mx, my, fa, accent, top, bottom);
			y = drawHelp(g, cx1, cx2, y, mx, my, fa, accent, top, bottom);
		} else if (tab == TOOLS) y = drawTools(g, cx1, cx2, y, mx, my, fa, accent, top, bottom);
		else if (tab == HELP) y = drawHelp(g, cx1, cx2, y, mx, my, fa, accent, top, bottom);
		else y = drawModules(g, cx1, cx2, y, mx, my, fa, accent, top, bottom, false);
		int contentH = y + Math.round(scroll) - slide - top;
		maxScroll = Math.max(0, contentH - (bottom - top));
		scrollTarget = Math.max(0, Math.min(scrollTarget, maxScroll));
		g.disableScissor();

		if (maxScroll > 0) {
			int trackH = bottom - top;
			int barH = Math.max(18, Math.round(trackH * (trackH / (float) (trackH + maxScroll))));
			int barY = top + Math.round((trackH - barH) * (scroll / maxScroll));
			Ui.round(g, cx2 + 6, barY, cx2 + 8, barY + barH, 1, Anim.argb(Ui.INK, 0.22f * al));
		}
	}

	private List<Module> modulesForView(boolean searching) {
		if (!searching) return Modules.in(Category.values()[tab]);
		String q = query.toLowerCase(Locale.ROOT).trim();
		List<Module> out = new ArrayList<>();
		for (Module m : Modules.all()) {
			if (matches(m, q)) out.add(m);
		}
		return out;
	}

	/** Name, description, tab, section, tags, setting names and setting choices all count. */
	private static boolean matches(Module m, String q) {
		if (m.name.toLowerCase(Locale.ROOT).contains(q) || m.desc.toLowerCase(Locale.ROOT).contains(q)
				|| m.category.title.toLowerCase(Locale.ROOT).contains(q) || m.section.toLowerCase(Locale.ROOT).contains(q)) return true;
		for (String t : m.tags) if (t.toLowerCase(Locale.ROOT).contains(q)) return true;
		for (Setting s : m.settings) {
			if (s.name.toLowerCase(Locale.ROOT).contains(q)) return true;
			for (String o : s.options) if (o.length() > 2 && o.toLowerCase(Locale.ROOT).contains(q)) return true;
		}
		return false;
	}

	private static boolean toolMatches(String[] t, String q) {
		return (t[1] + " " + t[2]).toLowerCase(Locale.ROOT).contains(q);
	}

	private static boolean helpMatches(dev.kyoto.ui.Guides.Topic t, String q) {
		if (t.title().toLowerCase(Locale.ROOT).contains(q)) return true;
		for (String st : t.steps()) if (st.toLowerCase(Locale.ROOT).contains(q)) return true;
		return false;
	}

	private int drawModules(GuiGraphicsExtractor g, int x1, int x2, int y, int mx, int my, float al, int accent, int clipTop, int clipBottom, boolean searching) {
		Map<String, List<Module>> sections = new LinkedHashMap<>();
		for (Module m : modulesForView(searching)) {
			String sec = searching ? m.category.title : m.section;
			sections.computeIfAbsent(sec, k -> new ArrayList<>()).add(m);
		}
		if (sections.isEmpty()) {
			if (!searching) return y;
			String q = query.toLowerCase(Locale.ROOT).trim();
			boolean any = false;
			for (String[] t : TOOL_LIST) any |= toolMatches(t, q);
			for (dev.kyoto.ui.Guides.Topic t : dev.kyoto.ui.Guides.TOPICS) any |= helpMatches(t, q);
			if (!any) Ui.text(g, "Nothing found for \"" + query + "\".", x1, y + 6, Anim.argb(Ui.MUTED, al));
			return any ? y : y + 20;
		}
		boolean inClipY = my >= clipTop && my < clipBottom;
		for (Map.Entry<String, List<Module>> e : sections.entrySet()) {
			Ui.small(g, e.getKey().toUpperCase(Locale.ROOT), x1 + 2, y + 2, Anim.argb(Ui.DIM, al));
			y += 11;
			int boxTop = y;
			// measure
			int h = 0;
			for (Module m : e.getValue()) h += rowHeight(m);
			Ui.round(g, x1, boxTop, x2, boxTop + h, 8, Anim.argb(Ui.INK, 0.035f * al));
			int ry = boxTop;
			int i = 0;
			for (Module m : e.getValue()) {
				if (i++ > 0) g.fill(x1 + 8, ry, x2 - 8, ry + 1, Anim.argb(Ui.INK, 0.05f * al));
				drawRow(g, m, x1, x2, ry, mx, my, al, accent, inClipY, clipTop, clipBottom);
				ry += rowHeight(m);
			}
			y = boxTop + h + 10;
		}
		return y;
	}

	private float expandAnim(Module m) {
		return a("exp" + m.id, Boolean.TRUE.equals(expanded.get(m.id)) ? 1f : 0f, 0.22f);
	}

	private int rowHeight(Module m) {
		float e = anim.getOrDefault("exp" + m.id, 0f);
		return 30 + Math.round(e * (m.settings.size() * 22 + 4));
	}

	private void drawRow(GuiGraphicsExtractor g, Module m, int x1, int x2, int y, int mx, int my, float al, int accent, boolean inClipY, int clipTop, int clipBottom) {
		float exp = expandAnim(m);
		int h = rowHeight(m);
		if (y > clipBottom || y + h < clipTop) return;
		boolean hov = inClipY && Ui.inside(mx, my, x1, y, x2, y + 30);
		float hv = a("row" + m.id, hov);
		if (hv > 0.01f) Ui.round(g, x1 + 2, y + 2, x2 - 2, y + 28, 6, Anim.argb(Ui.INK, 0.04f * hv * al));

		boolean on = m.on();
		float onK = a("onbar" + m.id, on ? 1f : 0f, 0.25f);
		if (onK > 0.01f) Ui.round(g, x1 + 3, y + 8, x1 + 5, y + 22, 1, Anim.argb(accent, 0.9f * onK * al));
		int textW = x2 - x1 - 70;
		Ui.text(g, Ui.ellipsize(m.name, textW), x1 + 10, y + 7, Anim.argb(Ui.TEXT, al));
		String desc = m.desc;
		while (Ui.widthSmall(desc) > textW && desc.length() > 4) desc = desc.substring(0, desc.length() - 2);
		if (!desc.equals(m.desc)) desc = desc.trim() + "…";
		Ui.small(g, desc, x1 + 10, y + 18, Anim.argb(Ui.MUTED, al));

		// switch
		int swX = x2 - 32, swY = y + 9;
		float k = a("knob" + m.id, on ? 1f : 0f, 0.28f);
		int track = Ui.mix(Ui.SOFT, accent, k);
		Ui.round(g, swX, swY, swX + 22, swY + 12, 6, Anim.argb(track, (0.7f + 0.3f * k) * al));
		if (k > 0.05f) Ui.round(g, swX - 2, swY - 2, swX + 24, swY + 14, 8, Anim.argb(accent, 0.12f * k * al));
		int kx = swX + 1 + Math.round(k * 10);
		Ui.round(g, kx, swY + 2, kx + 10, swY + 12, 5, Anim.argb(0x000000, 0.25f * al));
		Ui.round(g, kx, swY + 1, kx + 10, swY + 11, 5, Anim.argb(on ? Ui.GLASS : Ui.INK, al));

		// settings chevron
		if (!m.settings.isEmpty()) {
			int gx = swX - 20;
			boolean gh = inClipY && Ui.inside(mx, my, gx, swY - 2, gx + 16, swY + 14);
			float ghv = a("gear" + m.id, gh);
			Ui.round(g, gx, swY - 2, gx + 16, swY + 14, 5, Anim.argb(Ui.INK, (0.04f + 0.08f * ghv) * al));
			g.pose().pushMatrix();
			g.pose().translate(gx + 8, swY + 6);
			g.pose().rotate((float) (exp * Math.PI / 2));
			Ui.icon(g, "›", -Ui.font().width("›") / 2, -4, Anim.argb(exp > 0.5f ? accent : Ui.MUTED, al));
			g.pose().popMatrix();
			if (inClipY) hits.add(new Hit(gx, swY - 2, gx + 16, swY + 14, b -> expanded.put(m.id, !Boolean.TRUE.equals(expanded.get(m.id)))));
		}
		if (inClipY) hits.add(new Hit(x1, y, x2 - (m.settings.isEmpty() ? 0 : 52), y + 30, b -> {
			if (b == 1 && !m.settings.isEmpty()) expanded.put(m.id, !Boolean.TRUE.equals(expanded.get(m.id)));
			else m.toggle();
		}));
		if (inClipY) hits.add(new Hit(swX - 2, y, x2, y + 30, b -> m.toggle()));

		if (exp > 0.01f) {
			int sy = y + 30;
			int areaH = h - 30;
			g.enableScissor(x1, Math.max(clipTop, sy), x2, Math.min(clipBottom, sy + areaH));
			Ui.round(g, x1 + 6, sy, x2 - 6, sy + areaH - 4, 6, Anim.argb(0x000000, 0.18f * al * exp));
			int ry = sy + 2;
			for (Setting s : m.settings) {
				drawSetting(g, s, x1 + 6, x2 - 6, ry, mx, my, al * exp, accent, inClipY && my >= sy && my < sy + areaH);
				ry += 22;
			}
			g.disableScissor();
		}
	}

	/* ------------------------------------------- setting controls ------------------------------------------- */

	private static final Map<String, Integer> SWATCH = Map.ofEntries(
			Map.entry("Silver", Ui.INK), Map.entry("Lavender", Ui.MUTED), Map.entry("Ice", Ui.MUTED), Map.entry("Mint", Ui.INK), Map.entry("Rose", Ui.MUTED),
			Map.entry("Gold", Ui.INK), Map.entry("Crimson", 0xFF5D5D), Map.entry("Dreamy", Ui.MUTED), Map.entry("Royal", Ui.INK),
			Map.entry("Sunset", Ui.MUTED), Map.entry("Clean", Ui.INK), Map.entry("Neon", 0x39FF88), Map.entry("Karaoke", 0x6FA8FF),
			Map.entry("Retro", 0xFF9F43), Map.entry("Elegant", 0xE9D8B4), Map.entry("Midnight", 0x5B6CFF), Map.entry("Fire", 0xFF5A1F),
			Map.entry("Ocean", 0x1FB6FF), Map.entry("Sakura", Ui.MUTED));
	/** Two-colour swatches (drawn as a split dot) so the gradient styles look like themselves. */
	private static final Map<String, int[]> SWATCH2 = Map.of(
			"Dreamy", new int[]{Ui.MUTED, Ui.MUTED}, "Sunset", new int[]{Ui.MUTED, 0xFFB86B}, "Royal", new int[]{Ui.INK, 0x9C6BFF},
			"Fire", new int[]{0xFFD34A, 0xFF3D1F}, "Ocean", new int[]{0x7FE7FF, 0x1F6BFF}, "Midnight", new int[]{0x8EA2FF, 0x2A2F7A},
			"Karaoke", new int[]{Ui.INK, 0x6FA8FF}, "Sakura", new int[]{0xFFE3EE, Ui.MUTED});

	private static boolean isColour(Setting s) {
		return s.key.equals("accent.color") || s.key.equals("lyrics.style");
	}

	private static boolean isFont(Setting s) {
		return s.key.equals("font.family") || s.key.equals("lyrics.font");
	}

	private void drawSetting(GuiGraphicsExtractor g, Setting s, int x1, int x2, int y, int mx, int my, float al, int accent, boolean canClick) {
		Ui.text(g, s.name, x1 + 10, y + 6, Anim.argb(Ui.MUTED, al));
		int right = x2 - 6;
		if (isColour(s)) {
			int n = s.options.length;
			int sz = 9, gap = 4;
			int sx = right - n * (sz + gap) + gap;
			for (int i = 0; i < n; i++) {
				String o = s.options[i];
				int col;
				if (o.equals("Custom")) col = Ui.hsv(KyotoConfig.get().accentHue, KyotoConfig.get().accentSat, KyotoConfig.get().accentBright);
				else if (o.equals("RGB")) col = Ui.rgbCycle(i * 40);
				else if (o.equals("Accent")) col = Ui.accent();
				else col = SWATCH.getOrDefault(o, Ui.INK);
				int x = sx + i * (sz + gap), yy = y + 6;
				boolean sel = s.get() == i;
				boolean hv = canClick && Ui.inside(mx, my, x - 1, yy - 1, x + sz + 1, yy + sz + 1);
				if (sel) Ui.round(g, x - 2, yy - 2, x + sz + 2, yy + sz + 2, 6, Anim.argb(Ui.INK, 0.85f * al));
				int[] two = SWATCH2.get(o);
				if (o.equals("Rainbow")) {
					for (int k = 0; k < sz; k++) g.fill(x + k, yy + 1, x + k + 1, yy + sz - 1, Anim.argb(Ui.hsv((k / (float) sz) * 360f, 0.7f, 1f), al));
				} else if (two != null) {
					Ui.round(g, x, yy, x + sz, yy + sz, 5, Anim.argb(two[1], al));
					g.fill(x + 1, yy + 1, x + sz / 2, yy + sz - 1, Anim.argb(two[0], al));
				} else Ui.round(g, x, yy, x + sz, yy + sz, 5, Anim.argb(col, al));
				if (sel) Ui.round(g, x + sz / 2 - 1, yy + sz / 2 - 1, x + sz / 2 + 2, yy + sz / 2 + 2, 2, Anim.argb(Ui.GLASS, 0.8f * al));
				if (hv) {
					int tw = Ui.widthSmall(o) + 8;
					Ui.round(g, x + sz / 2 - tw / 2, yy - 13, x + sz / 2 + tw / 2, yy - 3, 4, Anim.argb(0x000000, 0.8f * al));
					Ui.small(g, o, x + sz / 2f - tw / 2f + 4, yy - 11, Anim.argb(Ui.INK, al));
				}
				final int fi = i;
				if (canClick) hits.add(new Hit(x - 1, yy - 1, x + sz + 1, yy + sz + 1, b -> set(s, fi)));
			}
			return;
		}
		int segW = 0;
		for (String o : s.options) segW += Ui.widthSmall(o) + 12;
		if (s.options.length <= 3 && segW <= 150) {
			// segmented control
			int sx = right - segW - 4;
			Ui.round(g, sx, y + 3, right, y + 18, 7, Anim.argb(Ui.INK, 0.06f * al));
			int x = sx + 2;
			float target = 0;
			int[] xs = new int[s.options.length], ws = new int[s.options.length];
			for (int i = 0; i < s.options.length; i++) {
				ws[i] = Ui.widthSmall(s.options[i]) + 12;
				xs[i] = x;
				x += ws[i];
			}
			int sel = s.get();
			float px2 = a("seg" + s.key, xs[sel], 0.3f);
			float pw2 = a("segw" + s.key, ws[sel], 0.3f);
			Ui.round(g, Math.round(px2), y + 5, Math.round(px2 + pw2) - 2, y + 16, 5, Anim.argb(accent, al));
			for (int i = 0; i < s.options.length; i++) {
				boolean on = i == sel;
				Ui.small(g, s.options[i], xs[i] + 5, y + 8, Anim.argb(on ? Ui.GLASS : Ui.MUTED, al));
				final int fi = i;
				if (canClick) hits.add(new Hit(xs[i], y + 3, xs[i] + ws[i], y + 18, b -> set(s, fi)));
			}
			return;
		}
		// cycling chip (fonts are previewed in their own font)
		String val = s.label();
		Component shown = isFont(s) ? Ui.inFont(val, s.get(), false) : Ui.t(val);
		int vw = Ui.font().width(shown);
		int cw = vw + 30;
		int cx = right - cw;
		boolean hv = canClick && Ui.inside(mx, my, cx, y + 3, right, y + 18);
		float h = a("chip" + s.key, hv);
		Ui.round(g, cx, y + 3, right, y + 18, 7, Anim.argb(Ui.INK, (0.06f + 0.06f * h) * al));
		Ui.icon(g, "‹", cx + 5, y + 7, Anim.argb(Ui.MUTED, al));
		g.text(Ui.font(), shown, cx + 15, y + 7, Anim.argb(accent, al), false);
		Ui.icon(g, "›", right - 9, y + 7, Anim.argb(Ui.MUTED, al));
		// ‹ goes back one, › goes forward one (right-click anywhere also goes back)
		if (canClick) {
			final int mid = cx + cw / 2;
			hits.add(new Hit(cx - 4, y + 2, right + 2, y + 19, b -> s.cycle(b == 1 || mxLast < mid ? -1 : 1)));
		}
	}

	private double mxLast = 0;

	private void set(Setting s, int index) {
		int diff = index - s.get();
		if (diff != 0) s.cycle(diff);
	}

	/* ------------------------------------------- tools page ------------------------------------------- */

	private static final String[][] TOOL_LIST = {
			{"hud", "Edit HUD", "Drag and resize every HUD element"},
			{"waypoint", "Waypoints", "Add, edit, share and send waypoints"},
			{"crosshair", "Crosshair", "Draw your own crosshair or pick a style"},
			{"vfx", "VFX Creator", "Hit, kill, totem and landing effects"},
			{"theme", "Theme", "Accent colour and RGB speed"},
			{"cosmetics", "Cosmetics", "Capes, skins, particles, nametags"},
			{"screen", "Video Screens", "Put up a screen and play a YouTube video"},
			{"sky", "Sky + Time", "Custom day/night skies, your own time of day"},
			{"profile", "My Profile", "Your custom profile page"},
			{"friends", "Friends", "Who's online, join them, call them"},
			{"dm", "Messages", "DMs with emojis and pictures"},
			{"hub", "Kyoto Hub", "Chat, DMs, pictures, Discord"},
			{"play", "Live Rooms", "Watch videos together, synced, with chat"},
			{"brush", "Build Recorder", "Timelapses + Litematica schematics of your builds"},
			{"stream", "Streamer", "Donation alerts and streamer mode"},
			{"mods", "Mods", "Turn mods on/off and open their settings"},
			{"finder", "Mod Finder", "Search and install 26.3 mods"},
			{"folder", "Kyoto Folder", "Capes, skins, skies, profile video + music"},
	};

	private int drawHelp(GuiGraphicsExtractor g, int x1, int x2, int y, int mx, int my, float al, int accent, int clipTop, int clipBottom) {
		boolean inClip = my >= clipTop && my < clipBottom;
		String q = query.toLowerCase(Locale.ROOT).trim();
		boolean header = false;
		for (dev.kyoto.ui.Guides.Topic t : dev.kyoto.ui.Guides.TOPICS) {
			if (!q.isEmpty() && !helpMatches(t, q)) continue;
			if (!q.isEmpty() && !header) {
				Ui.small(g, "HELP", x1 + 2, y + 2, Anim.argb(Ui.DIM, al));
				y += 11;
				header = true;
			}
			int h = 20 + t.steps().length * 10 + 6;
			boolean hasLink = !t.link().isBlank();
			Ui.round(g, x1, y, x2, y + h, 8, Anim.argb(Ui.INK, 0.04f * al));
			Ui.round(g, x1 + 7, y + 6, x1 + 21, y + 20, 5, Anim.argb(accent, 0.18f * al));
			Ui.iconTex(g, t.icon(), x1 + 9, y + 8, 10, Anim.argb(accent, al));
			Ui.text(g, t.title(), x1 + 27, y + 9, Anim.argb(Ui.TEXT, al));
			for (int i = 0; i < t.steps().length; i++) Ui.small(g, t.steps()[i], x1 + 27, y + 21 + i * 10, Anim.argb(Ui.MUTED, al));
			if (hasLink) {
				String lb = "Full guide ↗";
				int bw = Ui.widthSmall(lb) + 12, bx = x2 - bw - 6, by = y + 6;
				boolean hv = inClip && Ui.inside(mx, my, bx, by, bx + bw, by + 13);
				Ui.round(g, bx, by, bx + bw, by + 13, 6, Anim.argb(accent, (hv ? 1f : 0.85f) * al));
				Ui.small(g, lb, bx + 6, by + 3, Anim.argb(Ui.GLASS, al));
				if (inClip) hits.add(new Hit(bx, by, bx + bw, by + 13, b -> Compat.openUrl(t.link())));
			}
			y += h + 5;
		}
		return y;
	}

	private int drawTools(GuiGraphicsExtractor g, int x1, int x2, int y, int mx, int my, float al, int accent, int clipTop, int clipBottom) {
		int cols = (x2 - x1) > 300 ? 2 : 1;
		int gap = 6;
		int cw = (x2 - x1 - gap * (cols - 1)) / cols, chh = 38;
		boolean inClip = my >= clipTop && my < clipBottom;
		List<String[]> list = new ArrayList<>();
		String q = query.toLowerCase(Locale.ROOT).trim();
		for (String[] t : TOOL_LIST) if (q.isEmpty() || toolMatches(t, q)) list.add(t);
		if (list.isEmpty()) return y;
		if (!q.isEmpty()) {
			Ui.small(g, "TOOLS", x1 + 2, y + 2, Anim.argb(Ui.DIM, al));
			y += 11;
		}
		for (int i = 0; i < list.size(); i++) {
			String[] t = list.get(i);
			int cx = x1 + (i % cols) * (cw + gap), cy = y + (i / cols) * (chh + gap);
			boolean hov = inClip && Ui.inside(mx, my, cx, cy, cx + cw, cy + chh);
			float hv = a("tool" + i, hov);
			Ui.round(g, cx, cy, cx + cw, cy + chh, 8, Anim.argb(Ui.INK, (0.04f + 0.05f * hv) * al));
			Ui.round(g, cx + 7, cy + 7, cx + 31, cy + 31, 7, Anim.argb(accent, (0.16f + 0.2f * hv) * al));
			Ui.iconTex(g, t[0], cx + 13, cy + 13, 12, Anim.argb(accent, al));
			Ui.text(g, t[1], cx + 38 + Math.round(hv * 2), cy + 9, Anim.argb(Ui.TEXT, al));
			Ui.small(g, Ui.ellipsize(t[2], cw - 50), cx + 38 + Math.round(hv * 2), cy + 21, Anim.argb(Ui.MUTED, al));
			if (inClip) hits.add(new Hit(cx, cy, cx + cw, cy + chh, b -> openTool(t[1])));
		}
		int rows = (list.size() + cols - 1) / cols;
		return y + rows * (chh + gap) + 4;
	}

	private void openTool(String name) {
		lastTab = tab;
		if (name.equals("Kyoto Folder")) {
			Compat.openFolder(net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir().resolve("kyoto"));
			return;
		}
		Compat.openScreen(toolScreen(name, this));
	}

	/** Used by the new Kyoto menu too. */
	public static Screen toolScreen(String name, Screen parent) {
		return switch (name) {
			case "Waypoints" -> new WaypointScreen();
			case "Crosshair" -> new CrosshairScreen(parent);
			case "VFX Creator" -> new VfxScreen(parent);
			case "Theme" -> new ThemeScreen(parent);
			case "Cosmetics" -> new CosmeticsScreen(parent);
			case "Video Screens" -> new ScreensScreen(parent);
			case "Sky + Time" -> new SkyScreen(parent);
			case "Edit HUD" -> new HudEditorScreen(parent);
			case "Messages" -> new DmScreen(parent, null);
			case "Friends" -> new FriendsScreen(parent);
			case "My Profile" -> new ProfileScreen(parent, Compat.myName());
			case "Streamer" -> new StreamerScreen(parent);
			case "Kyoto Hub" -> new HubScreen(parent);
			case "Live Rooms" -> new RoomsScreen(parent);
			case "Build Recorder" -> new BuildRecorderScreen(parent);
			case "Mods" -> new ModsScreen(parent);
			default -> new ModFinderScreen(parent);
		};
	}

	/* ------------------------------------------- music player ------------------------------------------- */

	private final float[] bars = new float[30];
	private float vinylAngle = 0;
	private long lastPlayerNanos = 0;

	private void drawPlayer(GuiGraphicsExtractor g, int mx, int my, float al, int accent) {
		long now = System.nanoTime();
		float dt = lastPlayerNanos == 0 ? 0.016f : Math.min(0.05f, (now - lastPlayerNanos) / 1e9f);
		lastPlayerNanos = now;
		int x = mx0, y = my0 + 6;
		if (!Music.active()) {
			drawProfileCard(g, x, y, al, accent);
			return;
		}
		int art = 92;
		// vinyl peeking out from behind the cover
		if (Music.playing) vinylAngle += dt * 2.2f;
		float slideOut = a("vinyl", Music.playing ? 1f : 0.35f, 0.08f);
		int vs = art - 8;
		int vcx = x + art - 4 + Math.round(slideOut * 22), vcy = y + art / 2;
		g.pose().pushMatrix();
		g.pose().translate(vcx, vcy);
		g.pose().rotate(vinylAngle);
		Compat.blitTint(g, VINYL, -vs / 2, -vs / 2, vs, vs, Anim.argb(0xFFFFFF, al));
		g.pose().popMatrix();
		// cover + shadow
		Ui.round(g, x - 2, y - 1, x + art + 3, y + art + 4, 6, Anim.argb(0x000000, 0.35f * al));
		Compat.blitTint(g, AlbumArt.texture(), x, y, art, art, Anim.argb(0xFFFFFF, al));
		Ui.outline(g, x, y, x + art, y + art, Anim.argb(Ui.INK, 0.12f * al));

		int ty = y + art + 10;
		String title = Ui.ellipsize(Music.title, Math.round(mw / 1.6f));
		Ui.boldScaled(g, title, x, ty, 1.6f, Anim.argb(Ui.INK, al));
		Ui.text(g, Ui.ellipsize(Music.artist, mw), x, ty + 17, Anim.argb(Ui.MUTED, al));
		Ui.small(g, Music.source + (Lyrics.lines().isEmpty() ? "  ·  " + Lyrics.state : "  ·  lyrics synced"), x, ty + 28, Anim.argb(Ui.DIM, al));

		// progress
		long pos = Music.positionMs(), dur = Math.max(1, Music.durationMs);
		int pyb = ty + 42;
		g.fill(x, pyb, x + mw - 6, pyb + 2, Anim.argb(Ui.INK, 0.12f * al));
		g.fill(x, pyb, x + Math.round((mw - 6) * Math.min(1f, pos / (float) dur)), pyb + 2, Anim.argb(accent, al));
		Ui.small(g, fmt(pos), x, pyb + 5, Anim.argb(Ui.MUTED, al));
		String d = fmt(dur);
		Ui.small(g, d, x + mw - 6 - Ui.widthSmall(d), pyb + 5, Anim.argb(Ui.MUTED, al));

		// visualizer (left) + controls (right), like the reference
		int vy = my0 + ph - 18;
		int barsW = mw - 70;
		float t = System.currentTimeMillis() / 1000f;
		float beat = beatPulse();
		for (int i = 0; i < bars.length; i++) {
			float target;
			if (Music.playing) {
				double n = Math.abs(Math.sin(t * (2.1 + i * 0.37) + i * 1.7)) * 0.6 + Math.abs(Math.sin(t * (3.3 + i * 0.21) + i)) * 0.4;
				target = (float) (0.15 + n * (0.55 + 0.45 * beat));
			} else target = 0.06f;
			bars[i] += (target - bars[i]) * Math.min(1, dt * 14);
			int bw = Math.max(1, barsW / bars.length - 2);
			int bx = x + i * (barsW / bars.length);
			int bh = Math.max(2, Math.round(bars[i] * 22));
			g.fill(bx, vy - bh / 2, bx + bw, vy + (bh + 1) / 2, Anim.argb(accent, (Music.playing ? 0.85f : 0.5f) * al));
		}
		int cxp = x + mw - 22;
		control(g, "prev", cxp - 40, vy, 14, mx, my, al, false, () -> Music.control("previous"));
		control(g, Music.playing ? "pause" : "play", cxp - 8, vy, 22, mx, my, al, true, () -> Music.control("toggle"));
		control(g, "next", cxp + 20, vy, 14, mx, my, al, false, () -> Music.control("next"));
	}

	/** Pulses on every new lyric word so the visualizer "hits" with the vocals. */
	private float beatPulse() {
		List<Lyrics.Line> lines = Lyrics.lines();
		long pos = Music.lyricPositionMs();
		int idx = Lyrics.indexAt(pos);
		if (idx < 0 || idx >= lines.size()) return 0.5f;
		Lyrics.Line l = lines.get(idx);
		if (l.wordMs() == null) return 0.5f;
		long best = Long.MAX_VALUE;
		for (long w : l.wordMs()) if (w <= pos) best = Math.min(best, pos - w);
		return best == Long.MAX_VALUE ? 0.3f : (float) Math.exp(-best / 180.0);
	}

	private void control(GuiGraphicsExtractor g, String icon, int cx, int cy, int size, int mx, int my, float al, boolean primary, Runnable r) {
		int x1 = cx - size / 2, y1 = cy - size / 2;
		boolean hov = Ui.inside(mx, my, x1, y1, x1 + size, y1 + size);
		float hv = a("ctl" + icon + primary, hov);
		if (primary) {
			int grow = Math.round(hv * 2);
			Ui.round(g, x1 - grow, y1 - grow, x1 + size + grow, y1 + size + grow, size / 2 + grow, Anim.argb(Ui.accent(), al));
			Ui.iconTex(g, icon, cx - 4, cy - 4, 8, Anim.argb(Ui.GLASS, al));
		} else {
			Ui.iconTex(g, icon, cx - 4, cy - 4, 8, Anim.argb(Ui.mix(Ui.MUTED, Ui.INK, hv), al));
		}
		hits.add(new Hit(x1, y1, x1 + size, y1 + size, b -> r.run()));
	}

	private void drawProfileCard(GuiGraphicsExtractor g, int x, int y, float al, int accent) {
		Compat.blitTint(g, dev.kyoto.ui.TitleTheme.logo(), x, y, 64, 64, Anim.argb(0xFFFFFF, al));
		Ui.boldScaled(g, "Kyoto Client", x, y + 74, 1.5f, Anim.argb(Ui.INK, al));
		Ui.text(g, "#1 Minecraft Client", x, y + 90, Anim.argb(accent, al));

		int cy = y + 112;
		Ui.round(g, x, cy, x + mw - 6, cy + 34, 8, Anim.argb(Ui.INK, 0.05f * al));
		String me = Compat.myName();
		Ui.head(g, me, x + 7, cy + 7, 20, al);
		Ui.text(g, Ui.ellipsize(me, mw - 50), x + 33, cy + 7, Anim.argb(Ui.TEXT, al));
		String status;
		int dot;
		if (!KyotoNet.configured()) {
			status = "Kyoto network: not set up";
			dot = Ui.MUTED;
		} else if (KyotoNet.online >= 0) {
			status = KyotoNet.online + (KyotoNet.online == 1 ? " player online" : " players online");
			dot = Ui.INK;
		} else {
			status = "Connecting...";
			dot = Ui.INK;
		}
		Ui.round(g, x + 33, cy + 21, x + 37, cy + 25, 2, Anim.argb(dot, al));
		Ui.small(g, status, x + 40, cy + 20, Anim.argb(Ui.MUTED, al));

		int on = Modules.enabledCount(), total = Modules.all().size();
		Ui.small(g, on + " of " + total + " modules turned on", x, cy + 42, Anim.argb(Ui.DIM, al));
		Ui.small(g, "Play a song (Spotify, YouTube...) to see", x, cy + 58, Anim.argb(Ui.DIM, al));
		Ui.small(g, "the player + synced lyrics here.", x, cy + 67, Anim.argb(Ui.DIM, al));
	}

	private static String fmt(long ms) {
		return String.format("%d:%02d", ms / 60000, (ms / 1000) % 60);
	}

	/* ============================================ input ============================================ */

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		double mx = event.x(), my = event.y();
		mxLast = mx;
		if (closingAt < 0) {
			for (int i = hits.size() - 1; i >= 0; i--) {
				Hit h = hits.get(i);
				if (Ui.inside(mx, my, h.x1, h.y1, h.x2, h.y2)) {
					if (!Ui.inside(mx, my, search.getX() - 14, search.getY() - 3, search.getX() + search.getWidth(), search.getY() + 11)) search.setFocused(false);
					h.action.accept(event.button());
					if (Modules.on("button_sounds")) Compat.sound(net.minecraft.sounds.SoundEvents.UI_BUTTON_CLICK.value(), 0.22f, 1.7f);
					return true;
				}
			}
		}
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		if (mouseX >= px && mouseX < px + RAIL_W + 4) {
			railScrollTarget = Math.max(0, Math.min(railMax, railScrollTarget - (float) scrollY * 24));
		} else {
			scrollTarget = Math.max(0, Math.min(maxScroll, scrollTarget - (float) scrollY * 28));
		}
		return true;
	}
}
