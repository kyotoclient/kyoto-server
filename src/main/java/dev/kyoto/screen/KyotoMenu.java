package dev.kyoto.screen;

import dev.kyoto.config.KyotoConfig;
import dev.kyoto.hud.Notifications;
import dev.kyoto.module.Modules;
import dev.kyoto.module.Modules.Category;
import dev.kyoto.module.Modules.Module;
import dev.kyoto.module.Modules.Setting;
import dev.kyoto.music.Music;
import dev.kyoto.servers.Donut;
import dev.kyoto.servers.Packs;
import dev.kyoto.servers.ServerData;
import dev.kyoto.ui.Sounds;
import dev.kyoto.util.Anim;
import dev.kyoto.util.Compat;
import dev.kyoto.util.Ui;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The Kyoto menu. A sakura glass window with a grouped sidebar (Fight / Look / Extras / Client), a Home dashboard and a page per area.
 *  - every feature is a small card: click turns it on or off, the cog opens its settings, right-click stars it
 *  - "On now" chips at the top of each page show what's running there
 *  - All / On / Off / starred filters, and a search box that finds anything
 * Everything moves with springs: the sidebar pill slides, pages fade in, cards appear one after another.
 */
public class KyotoMenu extends Screen {
	public enum Page {
		HOME("Home", "home", null, "Your dashboard"),
		PVP("PvP", "sword", "Fight", "Info and feedback while you fight. Nothing plays for you"),
		VISUALS("Visuals", "eye", "Look", "Effects, the world and the sky"),
		HUD("HUD", "hud", "Look", "Everything drawn on your screen"),
		COSMETICS("Cosmetics", "cosmetics", "Look", "Pets, hats, wings, capes and auras"),
		ANIMATIONS("Animations", "hand", "Look", "How your hand and items move"),
		SERVER("Server", "server", "Extras", ""),
		MEDIA("Media", "music", "Extras", "Music, lyrics and streaming"),
		TOOLS("Tools", "tools", "Extras", "Editors, chat and handy things"),
		SOCIAL("Friends", "friends", "Client", "Friends, DMs, calls and your Kyoto status"),
		THEME("Theme", "palette", "Client", "One click changes the whole look"),
		SETTINGS("Settings", "gear", "Client", "How the client behaves"),
		HELP("Help", "help", "Client", "How things work, step by step");

		final String title, icon, group, sub;

		Page(String title, String icon, String group, String sub) {
			this.title = title;
			this.icon = icon;
			this.group = group;
			this.sub = sub;
		}
	}

	/** Tabs per page: tab name -> categories shown under it. */
	private static final Map<Page, LinkedHashMap<String, Category[]>> TABS = new HashMap<>();
	static {
		tabs(Page.PVP, "Modules", new Category[]{Category.COMBAT}, "Survival", new Category[]{Category.SURVIVAL});
		tabs(Page.VISUALS, "Effects", new Category[]{Category.VISUAL}, "World", new Category[]{Category.WORLD}, "Singleplayer", new Category[]{Category.SINGLEPLAYER});
		tabs(Page.HUD, "Modules", new Category[]{Category.HUD});
		tabs(Page.COSMETICS, "Wardrobe", new Category[]{Category.COSMETICS});
		tabs(Page.ANIMATIONS, "Hand", new Category[]{Category.ANIMATION});
		tabs(Page.MEDIA, "Music", new Category[]{Category.MUSIC}, "Streaming", new Category[]{Category.STREAMING});
		tabs(Page.TOOLS, "Tools", new Category[]{}, "Utility", new Category[]{Category.UTILITY}, "Chat", new Category[]{Category.CHAT});
		tabs(Page.SOCIAL, "Friends", new Category[]{Category.SOCIAL});
		tabs(Page.SETTINGS, "Client", new Category[]{Category.CLIENT}, "Performance", new Category[]{Category.PERFORMANCE}, "Title screen", new Category[]{Category.TITLE});
	}

	private static void tabs(Page p, Object... kv) {
		LinkedHashMap<String, Category[]> m = new LinkedHashMap<>();
		for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], (Category[]) kv[i + 1]);
		TABS.put(p, m);
	}

	private static Page lastPage = Page.HOME;
	private static final Map<Page, Integer> lastTab = new HashMap<>();

	public static Screen create() {
		if (Modules.on("classic_menu")) return new ClickGui();
		return new KyotoMenu();
	}

	public static Screen openOn(Page p) {
		lastPage = p;
		return create();
	}

	private Page page = lastPage;
	private int filter = 0; // 0 all, 1 on, 2 off, 3 starred
	private EditBox search;
	private String query = "";
	private float scroll = 0, scrollTarget = 0, maxScroll = 0;
	private long openedAt, closingAt = -1, pageAt = 0;
	private String expanded = null;
	private final Map<String, Float> anim = new HashMap<>();
	private float pillY = -1, tiltX = 0, tiltY = 0;
	private long lastScrollN = 0;

	private record Hit(int x1, int y1, int x2, int y2, java.util.function.IntConsumer action) {}

	private final List<Hit> hits = new ArrayList<>();
	private int px, py, pw, ph;
	private static final int SIDE = 118, HEAD = 46, FOOT = 16;
	private int cx1, cx2, cy1, cy2;
	private String tip = null;
	private String hoverDesc = null;
	private int hoverDescY = 0;
	private int cardIndex = 0;

	private static final Identifier LOGO = Identifier.fromNamespaceAndPath("kyoto", "textures/gui/logo_k.png");
	private static final Identifier BRANCH = Identifier.fromNamespaceAndPath("kyoto", "textures/gui/sakura/branch_right.png");
	private static final Identifier PETAL = Identifier.fromNamespaceAndPath("kyoto", "textures/gui/sakura/petal.png");

	public KyotoMenu() {
		super(Component.literal("Kyoto"));
	}

	@Override
	protected void init() {
		openedAt = System.currentTimeMillis();
		pageAt = openedAt;
		layout();
		search = new EditBox(this.font, px + 24, py + 36, SIDE - 34, 12, Component.literal("Search"));
		search.setBordered(false);
		search.setMaxLength(40);
		search.setTextColor(0xFF000000 | Ui.INK);
		search.setHint(Ui.t("Search..."));
		search.setValue(query);
		search.setResponder(s -> {
			query = s;
			scrollTarget = 0;
			pageAt = System.currentTimeMillis();
		});
		this.addRenderableWidget(search);
		Sounds.play(Sounds.OPEN);
		dev.kyoto.ui.MenuMedia.musicStart();
	}

	private void layout() {
		pw = Math.min(this.width - 20, 640);
		ph = Math.min(this.height - 20, 360);
		px = (this.width - pw) / 2;
		py = (this.height - ph) / 2;
		cx1 = px + SIDE + 14;
		cx2 = px + pw - 14;
		cy1 = py + HEAD + (TABS.containsKey(page) && query.isBlank() && TABS.get(page).size() > 1 ? 16 : 4);
		cy2 = py + ph - FOOT - 4;
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	private float openProgress() {
		float sp = Ui.animSpeed();
		if (sp <= 0) return closingAt > 0 ? 0 : 1;
		long now = System.currentTimeMillis();
		if (closingAt > 0) return 1 - Anim.clamp01((now - closingAt) / (170f * sp));
		return Anim.clamp01((now - openedAt) / (320f * sp));
	}

	@Override
	public void onClose() {
		lastPage = page;
		if (Minecraft.getInstance().level != null) dev.kyoto.ui.MenuMedia.musicStop();
		if (closingAt < 0) Sounds.play(Sounds.CLOSE);
		if (Ui.animSpeed() <= 0) {
			Compat.openScreen(null);
			return;
		}
		if (closingAt < 0) closingAt = System.currentTimeMillis();
	}

	/** Smoothly chases target (frame-rate independent-ish spring). */
	private float a(String k, float target, float speed) {
		float v = anim.getOrDefault(k, target);
		float sp = Ui.animSpeed() <= 0 ? 1f : Math.min(1f, speed / Ui.animSpeed());
		v += (target - v) * sp;
		if (Math.abs(target - v) < 0.002f) v = target;
		anim.put(k, v);
		return v;
	}

	private float a(String k, boolean on) {
		return a(k, on ? 1f : 0f, 0.22f);
	}

	/** 0..1 fade for the i-th thing drawn on a page, so cards appear one after another. */
	private float stagger(int i) {
		float sp = Math.max(0.01f, Ui.animSpeed());
		if (Ui.animSpeed() <= 0) return 1;
		float t = (System.currentTimeMillis() - pageAt - Math.min(i, 24) * 14f) / (240f * sp);
		return Anim.easeOutCubic(Anim.clamp01(t));
	}

	private int tab() {
		return lastTab.getOrDefault(page, 0);
	}

	/* ================================================================ drawing ================================================================ */

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partial) {
		int bg = Modules.opt("menu.bg"); // 0 blur + dim, 1 dim, 2 clear, 3 my video
		float al = Anim.easeOutCubic(openProgress());
		// "My video" plays inside the Kyoto window only (see draw); the game behind gets the normal blur
		if (bg == 3) bg = 0;
		if (bg == 0 || Minecraft.getInstance().level == null) super.extractBackground(g, mouseX, mouseY, partial);
		float dim = bg == 2 ? 0.15f : Modules.opt("menu.dim") == 0 ? 0.3f : Modules.opt("menu.dim") == 2 ? 0.75f : 0.5f;
		g.fillGradient(-40, -40, this.width + 40, this.height + 40, Anim.argb(Ui.BG_TOP, dim * 0.9f * al), Anim.argb(Ui.BG_BOTTOM, dim * al));
		if (Modules.on("menu_petals")) petals(g, al);
		int[] m = unmap(mouseX, mouseY);
		draw(g, m[0], m[1], al);
	}

	/* the whole window (and the search box with it) floats, tilts and scales as one */
	private float tfScale = 1, tfX = 0, tfY = 0;

	private void updateTransform(int mx, int my) {
		float o = openProgress(), al = Anim.easeOutCubic(o);
		tfScale = closingAt > 0 ? 0.97f + 0.03f * al : 0.94f + 0.06f * Anim.easeOutBack(o);
		float tx = 0, ty = 0;
		if (Modules.on("menu_tilt")) {
			tx = (mx - this.width / 2f) / (this.width / 2f) * 5f;
			ty = (my - this.height / 2f) / (this.height / 2f) * 3f;
		}
		tiltX += (tx - tiltX) * 0.12f;
		tiltY += (ty - tiltY) * 0.12f;
		tfX = tiltX;
		tfY = tiltY + (1 - al) * 10;
	}

	private int[] unmap(double mx, double my) {
		float cx = this.width / 2f, cy = this.height / 2f;
		return new int[]{(int) Math.round((mx - cx - tfX) / tfScale + cx), (int) Math.round((my - cy - tfY) / tfScale + cy)};
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partial) {
		float open = openProgress();
		if (closingAt > 0 && open <= 0) {
			Compat.openScreen(null);
			return;
		}
		search.visible = open > 0.85f;
		updateTransform(mouseX, mouseY);
		float cx = this.width / 2f, cy = this.height / 2f;
		g.pose().pushMatrix();
		g.pose().translate(cx + tfX, cy + tfY);
		g.pose().scale(tfScale, tfScale);
		g.pose().translate(-cx, -cy);
		super.extractRenderState(g, mouseX, mouseY, partial);
		g.pose().popMatrix();
		if (tip != null && open > 0.9f) drawTip(g, mouseX, mouseY, tip);
	}

	/** Petals drifting behind the menu. */
	private void petals(GuiGraphicsExtractor g, float al) {
		long now = System.currentTimeMillis();
		for (int i = 0; i < 18; i++) {
			float seed = i * 97.31f;
			float speed = 0.012f + (i % 5) * 0.004f;
			float t = ((now * speed / 1000f) + (seed % 1f) + i * 0.137f) % 1f;
			float x = (float) ((seed * 13.7) % this.width) + (float) Math.sin(t * 6.28 * 2 + i) * 30 - t * 60;
			float y = -12 + t * (this.height + 24);
			int s = 4 + (i % 4) * 2;
			float rot = t * 6.28f * (i % 2 == 0 ? 1 : -1) + i;
			g.pose().pushMatrix();
			g.pose().translate(x, y);
			g.pose().rotate(rot);
			g.pose().scale(1f, 0.55f + 0.45f * (float) Math.abs(Math.cos(t * 9 + i)));
			Compat.blitTint(g, PETAL, -s / 2, -s / 2, s, s, Anim.argb(0xFFFFFF, (0.35f + 0.25f * (i % 3) / 2f) * al));
			g.pose().popMatrix();
		}
	}

	private void draw(GuiGraphicsExtractor g, int mx, int my, float al) {
		layout();
		hits.clear();
		tip = null;
		hoverDesc = null;
		cardIndex = 0;
		int accent = Ui.accent();
		// window (with your video inside it, frosted so text stays readable)
		if (Modules.opt("menu.bg") == 3 && dev.kyoto.ui.MenuMedia.drawIn(g, px, py, pw, ph, 16, al)) {
			Ui.round(g, px, py, px + pw, py + ph, 16, Anim.argb(Ui.DARK ? 0x1C1519 : 0xFFF5F8, (Ui.DARK ? 0.58f : 0.52f) * al));
		}
		Ui.glass(g, px, py, px + pw, py + ph, 16, al);
		// sakura branch peeking over the top right corner
		g.enableScissor(px + SIDE, py, px + pw, py + ph);
		float sway = (float) Math.sin(System.currentTimeMillis() / 1900.0) * 0.02f;
		g.pose().pushMatrix();
		g.pose().translate(px + pw + 6, py - 8);
		g.pose().rotate(sway);
		int bw = Math.min(300, pw / 2), bh = bw * 9 / 16;
		Compat.blitTint(g, BRANCH, -bw, 0, bw, bh, Anim.argb(0xFFFFFF, (Ui.DARK ? 0.22f : 0.5f) * al));
		g.pose().popMatrix();
		g.disableScissor();
		// sidebar
		Ui.round(g, px + 5, py + 5, px + SIDE, py + ph - 5, 12, Anim.argb(Ui.DARK ? 0x000000 : 0xFFFFFF, (Ui.DARK ? 0.14f : 0.38f) * al));
		drawSide(g, mx, my, al, accent);
		drawHeader(g, mx, my, al, accent);
		// content (slides up as a page opens)
		float pf = stagger(0);
		g.enableScissor(cx1 - 4, cy1, cx2 + 4, cy2);
		long nowN = System.nanoTime();
		float dt = lastScrollN == 0 ? 0.016f : Math.min(0.05f, (nowN - lastScrollN) / 1e9f);
		lastScrollN = nowN;
		// ease towards the target (frame-rate independent); past the ends it springs back
		if (scrollTarget < 0) scrollTarget += (0 - scrollTarget) * Math.min(1f, dt * 12);
		else if (scrollTarget > maxScroll) scrollTarget += (maxScroll - scrollTarget) * Math.min(1f, dt * 12);
		scroll += (scrollTarget - scroll) * (1f - (float) Math.exp(-dt * 13));
		if (Math.abs(scrollTarget - scroll) < 0.05f) scroll = scrollTarget;
		int top = cy1 + 4 - Math.round(scroll) + Math.round((1 - pf) * 8);
		int end;
		if (!query.isBlank()) end = drawSearch(g, top, mx, my, al, accent);
		else end = switch (page) {
			case HOME -> drawHome(g, top, mx, my, al, accent);
			case SERVER -> drawServer(g, top, mx, my, al, accent);
			case COSMETICS -> drawCosmetics(g, top, mx, my, al, accent);
			case MEDIA -> tab() == 0 ? drawMusic(g, top, mx, my, al, accent) : drawModules(g, top, mx, my, al, accent, new String[][]{{"stream", "Streamer tools", "Streamer"}, {"link", "Make a stream overlay", "@overlay"}});
			case TOOLS -> tab() == 0 ? tiles(g, java.util.Arrays.asList(TOOLS), top, mx, my, al, accent) : drawModules(g, top, mx, my, al, accent, null);
			case THEME -> drawTheme(g, top, mx, my, al, accent);
			case HUD -> drawModules(g, top, mx, my, al, accent, new String[][]{{"hud", "Edit HUD", "Edit HUD"}});
			case SOCIAL -> drawModules(g, top, mx, my, al, accent, new String[][]{{"friends", "Friends", "Friends"}, {"dm", "Messages", "Messages"}, {"hub", "Kyoto Hub", "Kyoto Hub"}, {"profile", "My profile", "My Profile"}});
			case HELP -> drawHelp(g, top, mx, my, al, accent);
			default -> drawModules(g, top, mx, my, al, accent, null);
		};
		if (hoverDesc != null) descStrip(g, hoverDesc, hoverDescY, al, accent);
		g.disableScissor();
		maxScroll = Math.max(0, end + Math.round(scroll) - cy2 + 10);
		if (maxScroll > 0) {
			float f = scroll / Math.max(1, maxScroll);
			int bh2 = Math.max(16, Math.round((cy2 - cy1) * (cy2 - cy1) / (float) (cy2 - cy1 + maxScroll)));
			int by = cy1 + Math.round((cy2 - cy1 - bh2) * f);
			Ui.round(g, cx2 + 6, by, cx2 + 9, by + bh2, 1, Anim.argb(Ui.INK, 0.18f * al));
		}
		drawFooter(g, al, accent);
		if (pendingRisk != null) riskDialog(g, mx, my, al, accent);
		else maybeOnboarding(g, mx, my, al, accent);
	}

	/* ---------------- sidebar ---------------- */

	private void drawSide(GuiGraphicsExtractor g, int mx, int my, float al, int accent) {
		int x = px + 14, y = py + 13;
		// blossom logo blooms in
		float lo = Anim.easeOutBack(Anim.clamp01((System.currentTimeMillis() - openedAt) / 600f));
		g.pose().pushMatrix();
		g.pose().translate(x + 7, y + 7);
		g.pose().rotate((1 - lo) * -1.2f);
		g.pose().scale(0.4f + 0.6f * lo, 0.4f + 0.6f * lo);
		Compat.blitTint(g, LOGO, -7, -7, 14, 14, Anim.argb(0xFFFFFF, al));
		g.pose().popMatrix();
		Ui.bold(g, "Kyoto", x + 19, y, Anim.argb(Ui.TEXT, al));
		Ui.small(g, dev.kyoto.KyotoClient.version(), x + 19, y + 10, Anim.argb(Ui.DIM, al));
		// search box
		int sx1 = px + 11, sx2 = px + SIDE - 6, sy = py + 32;
		boolean f = search.isFocused();
		float fh = a("searchf", f);
		Ui.round(g, sx1, sy, sx2, sy + 18, 9, Anim.argb(Ui.GLASS_LIGHT, (Ui.DARK ? 0.12f : 0.6f) * al));
		Ui.ring(g, sx1, sy, sx2, sy + 18, 9, 1, Anim.argb(Ui.mix(0xFFFFFF, accent, fh), ((Ui.DARK ? 0.12f : 0.9f) * (1 - fh) + 0.8f * fh) * al));
		Ui.iconTex(g, "search", sx1 + 6, sy + 5, 8, Anim.argb(Ui.MUTED, al));
		search.setX(sx1 + 18);
		search.setY(sy + 5);
		search.setWidth(sx2 - sx1 - (query.isBlank() ? 22 : 34));
		if (!query.isBlank()) {
			Ui.iconTex(g, "close", sx2 - 13, sy + 5, 8, Anim.argb(Ui.MUTED, al));
			hits.add(new Hit(sx2 - 16, sy, sx2, sy + 18, b -> clearSearch()));
		}
		y = sy + 26;
		String group = null;
		float targetPill = -1;
		List<int[]> rows = new ArrayList<>();
		int listTop = y;
		for (Page p : Page.values()) {
			if (p.group != null && !p.group.equals(group)) {
				group = p.group;
				y += 3;
				Ui.small(g, group.toUpperCase(Locale.ROOT), px + 16, y, Anim.argb(Ui.DIM, al));
				y += 10;
			}
			boolean sel = page == p && query.isBlank();
			if (sel) targetPill = y;
			rows.add(new int[]{p.ordinal(), y});
			y += 16;
			if (y > py + ph - 40) break;
		}
		// sliding selection pill
		if (targetPill >= 0) {
			if (pillY < 0) pillY = targetPill;
			pillY += (targetPill - pillY) * (Ui.animSpeed() <= 0 ? 1f : 0.28f);
			int iy = Math.round(pillY);
			Ui.round(g, px + 10, iy - 1, px + SIDE - 5, iy + 14, 7, Anim.argb(accent, 0.2f * al));
			Ui.round(g, px + 10, iy - 1, px + 12, iy + 14, 1, Anim.argb(accent, al));
		}
		for (int[] r : rows) {
			Page p = Page.values()[r[0]];
			int ry = r[1];
			String label = p == Page.SERVER ? serverTabName() : p.title;
			boolean sel = page == p && query.isBlank();
			boolean hov = Ui.inside(mx, my, px + 10, ry - 1, px + SIDE - 5, ry + 14);
			float h = a("sideh" + p, hov);
			if (h > 0.01f && !sel) Ui.round(g, px + 10, ry - 1, px + SIDE - 5, ry + 14, 7, Anim.argb(Ui.INK, 0.05f * h * al));
			int col = sel ? Ui.mix(Ui.INK, accent, 0.25f) : Ui.mix(Ui.MUTED, Ui.INK, h);
			float nudge = a("siden" + p, sel || hov ? 1f : 0f, 0.25f) * 2f;
			Ui.iconTex(g, p.icon, Math.round(px + 17 + nudge), ry + 2, 9, Anim.argb(sel ? accent : col, al));
			Ui.text(g, Ui.ellipsize(label, SIDE - 58), Math.round(px + 31 + nudge), ry + 3, Anim.argb(col, al));
			int[] c = counts(p);
			if (c != null) {
				String cs = c[0] + "/" + c[1];
				Ui.small(g, cs, px + SIDE - 10 - Ui.widthSmall(cs), ry + 4, Anim.argb(c[0] > 0 ? Ui.MUTED : Ui.DIM, al));
			}
			if (p == Page.SERVER && Packs.current != null) Ui.circle(g, px + SIDE - 12, ry + 6, 2, Anim.argb(Packs.current.color(), al));
			hits.add(new Hit(px + 10, ry - 1, px + SIDE - 5, ry + 14, b -> select(p)));
		}
		// you
		int by = py + ph - 26;
		g.fill(px + 14, by - 5, px + SIDE - 8, by - 4, Anim.argb(Ui.INK, 0.07f * al));
		String me = Compat.myName();
		if (me != null) {
			Compat.drawHead(g, me, px + 14, by, 14);
			Ui.text(g, Ui.ellipsize(me, SIDE - 44), px + 32, by + 1, Anim.argb(Ui.TEXT, al));
			Ui.small(g, Modules.enabledCount() + " on", px + 32, by + 10, Anim.argb(Ui.DIM, al));
			hits.add(new Hit(px + 10, by - 2, px + SIDE - 6, by + 16, b -> Compat.openScreen(new ProfileScreen(this, me))));
		}
	}

	/** {on, total} of the modules shown on a page, or null when it isn't a module page. */
	private static int[] counts(Page p) {
		if (!TABS.containsKey(p) || p == Page.SOCIAL) return null;
		int on = 0, all = 0;
		for (Category[] cs : TABS.get(p).values()) for (Category c : cs) for (Module m : Modules.in(c)) {
			if (m.pack != null) continue;
			all++;
			if (m.on()) on++;
		}
		return all == 0 ? null : new int[]{on, all};
	}

	private String serverTabName() {
		if (Packs.current != null || !Packs.host.isEmpty()) return Packs.serverLabel;
		return Compat.currentServerIp() == null ? "Servers" : "This server";
	}

	private void select(Page p) {
		if (page != p) Sounds.play(Sounds.TAB);
		page = p;
		clearSearch();
		scroll = scrollTarget = 0;
		expanded = null;
		filter = 0;
		pageAt = System.currentTimeMillis();
	}

	private void clearSearch() {
		query = "";
		if (search != null) {
			search.setValue("");
			search.setFocused(false);
		}
		setFocused(null);
	}

	/* ---------------- header + footer ---------------- */

	private void drawHeader(GuiGraphicsExtractor g, int mx, int my, float al, int accent) {
		String title, sub;
		if (!query.isBlank()) {
			title = "Search";
			sub = "Everything that matches \"" + query.trim() + "\"";
		} else if (page == Page.SERVER) {
			title = serverTabName();
			sub = Packs.current != null ? Packs.current.name() + " · " + Packs.current.desc() : Packs.host.isEmpty() ? "Packs turn on by themselves when you join a server" : "No pack for this server - pick one below";
		} else {
			title = page.title;
			int[] c = counts(page);
			sub = c != null ? c[0] + " of " + c[1] + " on" : page.sub;
		}
		float hf = stagger(0);
		Ui.boldScaled(g, title, cx1, py + 12 + (1 - hf) * 4, 1.4f, Anim.argb(Ui.TEXT, al * hf));
		Ui.small(g, Ui.ellipsize(sub, Math.round((cx2 - cx1 - 160) / 0.75f)), cx1, py + 29, Anim.argb(Ui.MUTED, al * hf));
		// right side: filters on module pages, then the light/dark button
		int rx = cx2;
		boolean dark = Modules.on("dark_mode");
		String dl = dark ? "Light" : "Dark";
		int dw = Ui.widthSmall(dl) + 26;
		boolean dh = Ui.inside(mx, my, rx - dw, py + 12, rx, py + 30);
		float dhv = a("darkh", dh);
		Ui.round(g, rx - dw, py + 12, rx, py + 30, 9, Anim.argb(Ui.INK, (0.05f + 0.07f * dhv) * al));
		Ui.ring(g, rx - dw, py + 12, rx, py + 30, 9, 1, Anim.argb(0xFFFFFF, (Ui.DARK ? 0.12f : 0.8f) * al));
		float spin = a("darkspin", dark ? 1f : 0f, 0.15f);
		g.pose().pushMatrix();
		g.pose().translate(rx - dw + 12, py + 21);
		g.pose().rotate(spin * (float) Math.PI);
		Ui.iconTex(g, dark ? "sun" : "moon", -4, -4, 8, Anim.argb(Ui.mix(Ui.MUTED, accent, dhv), al));
		g.pose().popMatrix();
		Ui.small(g, dl, rx - dw + 20, py + 18, Anim.argb(Ui.mix(Ui.MUTED, Ui.INK, dhv), al));
		hits.add(new Hit(rx - dw, py + 12, rx, py + 30, b -> { Module m = Modules.get("dark_mode"); if (m != null) m.toggle(); Ui.refresh(); Sounds.play(Sounds.TAB); }));
		rx -= dw + 6;
		if (query.isBlank() && TABS.containsKey(page) && page != Page.COSMETICS && !(page == Page.TOOLS && tab() == 0) && !(page == Page.MEDIA && tab() == 0)) {
			String[] labels = {"All", "On", "Off"};
			int segW = 0;
			for (String l : labels) segW += Ui.widthSmall(l) + 12;
			int sx = rx - segW - 4;
			Ui.round(g, sx - 2, py + 12, rx, py + 30, 7, Anim.argb(Ui.INK, 0.05f * al));
			int fx = sx;
			for (int i = 0; i < labels.length; i++) {
				int w = Ui.widthSmall(labels[i]) + 12;
				float s = a("filt" + i, filter == i);
				if (s > 0.01f) Ui.round(g, fx, py + 14, fx + w, py + 28, 6, Anim.argb(Ui.GLASS_LIGHT, (Ui.DARK ? 0.18f : 0.95f) * s * al));
				Ui.small(g, labels[i], fx + 6, py + 18, Anim.argb(Ui.mix(Ui.MUTED, Ui.INK, s), al));
				final int fi = i;
				hits.add(new Hit(fx, py + 12, fx + w, py + 30, b -> { filter = fi; pageAt = System.currentTimeMillis(); }));
				fx += w;
			}
			rx = sx - 8;
			boolean starOn = filter == 3, sh = Ui.inside(mx, my, rx - 18, py + 12, rx, py + 30);
			Ui.round(g, rx - 18, py + 12, rx, py + 30, 7, Anim.argb(starOn ? accent : Ui.INK, (starOn ? 0.9f : sh ? 0.1f : 0.05f) * al));
			Ui.iconTex(g, "star", rx - 13, py + 17, 8, Anim.argb(starOn ? 0xFFFFFF : Ui.MUTED, al));
			if (sh) tip = "Starred only";
			hits.add(new Hit(rx - 18, py + 12, rx, py + 30, b -> { filter = filter == 3 ? 0 : 3; pageAt = System.currentTimeMillis(); }));
		}
		// tabs
		if (query.isBlank() && TABS.containsKey(page) && TABS.get(page).size() > 1) {
			int tx = cx1;
			int ty = py + HEAD - 6;
			int i = 0;
			for (String t : TABS.get(page).keySet()) {
				int w = Ui.width(t);
				boolean sel = tab() == i, hov = Ui.inside(mx, my, tx - 2, ty - 3, tx + w + 2, ty + 12);
				float s = a("tab" + page + i, sel), h = a("tabh" + page + i, hov);
				Ui.text(g, t, tx, ty, Anim.argb(Ui.mix(Ui.mix(Ui.MUTED, Ui.INK, h * 0.6f), Ui.INK, s), al));
				if (s > 0.01f) {
					int uw = Math.round(w * s);
					Ui.round(g, tx + (w - uw) / 2, ty + 11, tx + (w - uw) / 2 + uw, ty + 13, 1, Anim.argb(accent, al));
				}
				final int fi = i;
				hits.add(new Hit(tx - 2, ty - 3, tx + w + 2, ty + 13, b -> { if (tab() != fi) Sounds.play(Sounds.TAB); lastTab.put(page, fi); scroll = scrollTarget = 0; expanded = null; pageAt = System.currentTimeMillis(); }));
				tx += w + 14;
				i++;
			}
		}
		g.fill(cx1, cy1 - 3, cx2, cy1 - 2, Anim.argb(Ui.INK, 0.06f * al));
	}

	private void drawFooter(GuiGraphicsExtractor g, float al, int accent) {
		int y = py + ph - FOOT + 2;
		boolean liquid = Modules.on("liquid_glass");
		Ui.circle(g, cx1 + 2, y + 3, 2, Anim.argb(liquid ? accent : Ui.DIM, al));
		Ui.small(g, liquid ? "Liquid glass" : "Glass", cx1 + 8, y, Anim.argb(Ui.DIM, al));
		String hint = "Right Shift to close  ·  click: on or off  ·  cog: settings  ·  right-click: star";
		Ui.small(g, hint, cx2 - Ui.widthSmall(hint), y, Anim.argb(Ui.DIM, al));
	}

	/* ================================================================ pages ================================================================ */

	private int section(GuiGraphicsExtractor g, String t, int y, float al) {
		Ui.text(g, t, cx1 + 1, y + 4, Anim.argb(Ui.TEXT, al * stagger(cardIndex)));
		return y + 17;
	}

	private int note(GuiGraphicsExtractor g, String t, int y, float al) {
		for (String line : wrap(t, cx2 - cx1 - 4, true)) {
			Ui.small(g, line, cx1 + 2, y, Anim.argb(Ui.MUTED, al));
			y += 9;
		}
		return y + 3;
	}

	/** A soft card inside the window. */
	private void card(GuiGraphicsExtractor g, int x1, int y1, int x2, int y2, float al, float hover) {
		Ui.round(g, x1, y1, x2, y2, 9, Anim.argb(Ui.GLASS_LIGHT, ((Ui.DARK ? 0.05f : 0.42f) + 0.12f * hover) * al));
		Ui.ring(g, x1, y1, x2, y2, 9, 1, Anim.argb(0xFFFFFF, (Ui.DARK ? 0.08f + 0.08f * hover : 0.7f) * al));
	}

	/* ---------------- Home ---------------- */

	private static final String[][] START = {
			{"palette", "Themes", "Change the whole look", "#theme"},
			{"paw", "Pets", "A buddy that follows you", "#pets"},
			{"hat", "Wardrobe", "Hats, wings, halos", "#wardrobe"},
			{"sky", "Custom Sky", "Auroras, sunsets, sakura", "Sky + Time"},
			{"hud", "HUD Editor", "Move things on your screen", "Edit HUD"},
			{"sword", "Knives", "CS2-style knife skins", "#knives"},
			{"waypoint", "Waypoints", "Never get lost", "Waypoints"},
			{"friends", "Friends", "Who's online", "Friends"},
	};

	private static final String[][] PRESETS = {
			{"star", "Recommended", "The best of Kyoto"},
			{"sword", "PvP", "Keystrokes, CPS, hits"},
			{"brush", "Builder", "Coords, zoom, waypoints"},
			{"stream", "Streamer", "Hides coords and name"},
			{"eye", "Minimal", "Clean screen, just FPS"},
			{"performance", "Max FPS", "Turns off heavy effects"},
	};

	private int drawHome(GuiGraphicsExtractor g, int y, int mx, int my, float al, int accent) {
		int w = cx2 - cx1, gap = 8;
		int lw = Math.round(w * 0.58f);
		// greeting
		float f0 = stagger(cardIndex++);
		float a0 = al * f0;
		int yy = y + Math.round((1 - f0) * 6);
		card(g, cx1, yy, cx1 + lw, yy + 50, a0, 0);
		String me = Compat.myName();
		if (me != null) Compat.drawHead(g, me, cx1 + 10, yy + 10, 30);
		int hr = LocalTime.now().getHour();
		String greet = hr < 5 ? "Good night" : hr < 12 ? "Good morning" : hr < 18 ? "Good afternoon" : "Good evening";
		Ui.bold(g, greet + (me != null ? ", " + me : ""), cx1 + 48, yy + 11, Anim.argb(Ui.TEXT, a0));
		LocalDate d = LocalDate.now();
		Ui.small(g, d.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.ENGLISH) + ", " + d.getDayOfMonth() + " " + d.getMonth().getDisplayName(TextStyle.FULL, Locale.ENGLISH), cx1 + 48, yy + 24, Anim.argb(Ui.MUTED, a0));
		Ui.small(g, Modules.enabledCount() + " modules on", cx1 + 48, yy + 34, Anim.argb(Ui.DIM, a0));
		// clock
		float f1 = stagger(cardIndex++);
		float a1 = al * f1;
		yy = y + Math.round((1 - f1) * 6);
		int kx = cx1 + lw + gap;
		card(g, kx, yy, cx2, yy + 50, a1, 0);
		LocalTime now = LocalTime.now();
		String time = String.format(Locale.ROOT, "%02d:%02d", now.getHour(), now.getMinute());
		Ui.boldScaled(g, time, kx + 10, yy + 10, 2f, Anim.argb(Ui.TEXT, a1));
		Ui.small(g, String.valueOf(d.getYear()), cx2 - 10 - Ui.widthSmall(String.valueOf(d.getYear())), yy + 10, Anim.argb(Ui.DIM, a1));
		float dayK = now.toSecondOfDay() / 86400f;
		int bx1 = kx + 10, bx2 = cx2 - 10;
		Ui.round(g, bx1, yy + 38, bx2, yy + 41, 1, Anim.argb(Ui.INK, 0.08f * a1));
		Ui.round(g, bx1, yy + 38, bx1 + Math.max(3, Math.round((bx2 - bx1) * dayK)), yy + 41, 1, Anim.argb(accent, a1));
		y += 58;
		// start here
		float f2 = stagger(cardIndex++);
		float a2 = al * f2;
		int cols = w > 380 ? 4 : 2, tg = 6, tw = (w - 20 - tg * (cols - 1)) / cols, th = 30;
		int rows = (START.length + cols - 1) / cols;
		int sh = 24 + rows * (th + tg);
		yy = y + Math.round((1 - f2) * 6);
		card(g, cx1, yy, cx2, yy + sh, a2, 0);
		Ui.text(g, "Start here", cx1 + 10, yy + 9, Anim.argb(Ui.TEXT, a2));
		String most = "The most used things in Kyoto";
		Ui.small(g, most, cx2 - 10 - Ui.widthSmall(most), yy + 10, Anim.argb(Ui.DIM, a2));
		for (int i = 0; i < START.length; i++) {
			int tx = cx1 + 10 + (i % cols) * (tw + tg), ty = yy + 22 + (i / cols) * (th + tg);
			boolean hov = inContent(mx, my) && Ui.inside(mx, my, tx, ty, tx + tw, ty + th);
			float h = a("start" + i, hov);
			int lift = Math.round(h * 1.5f);
			Ui.round(g, tx, ty - lift, tx + tw, ty + th - lift, 7, Anim.argb(Ui.INK, (0.04f + 0.05f * h) * a2));
			Ui.iconTex(g, START[i][0], tx + 8, ty + 11 - lift, 9, Anim.argb(Ui.mix(Ui.MUTED, accent, h), a2));
			Ui.text(g, Ui.ellipsize(START[i][1], tw - 30), tx + 23, ty + 6 - lift, Anim.argb(Ui.TEXT, a2));
			Ui.small(g, Ui.ellipsize(START[i][2], Math.round((tw - 28) / 0.75f)), tx + 23, ty + 17 - lift, Anim.argb(Ui.MUTED, a2));
			final String[] s = START[i];
			if (inContent(mx, my)) hits.add(new Hit(tx, ty, tx + tw, ty + th, b -> openTool(s[3])));
		}
		y += sh + gap;
		// calendar + system
		float f3 = stagger(cardIndex++);
		float a3 = al * f3;
		yy = y + Math.round((1 - f3) * 6);
		int ch = 112;
		card(g, cx1, yy, cx1 + lw, yy + ch, a3, 0);
		Ui.text(g, d.getMonth().getDisplayName(TextStyle.FULL, Locale.ENGLISH) + " " + d.getYear(), cx1 + 10, yy + 9, Anim.argb(Ui.TEXT, a3));
		String[] dn = {"M", "T", "W", "T", "F", "S", "S"};
		int cw = (lw - 20) / 7;
		for (int i = 0; i < 7; i++) Ui.small(g, dn[i], cx1 + 10 + i * cw + cw / 2 - 2, yy + 23, Anim.argb(Ui.DIM, a3));
		LocalDate first = d.withDayOfMonth(1);
		int off = first.getDayOfWeek().getValue() - 1;
		int days = d.lengthOfMonth();
		for (int day = 1; day <= days; day++) {
			int idx = off + day - 1, cx = cx1 + 10 + (idx % 7) * cw + cw / 2, cy = yy + 36 + (idx / 7) * 12;
			String s = String.valueOf(day);
			boolean today = day == d.getDayOfMonth();
			if (today) Ui.circle(g, cx, cy + 3, 5.5f, Anim.argb(accent, a3));
			Ui.small(g, s, cx - Ui.widthSmall(s) / 2f, cy, Anim.argb(today ? 0xFFFFFF : Ui.TEXT, a3));
		}
		int sx = cx1 + lw + gap;
		card(g, sx, yy, cx2, yy + ch, a3, 0);
		Ui.text(g, "System", sx + 10, yy + 9, Anim.argb(Ui.TEXT, a3));
		Minecraft mc = Minecraft.getInstance();
		int fps = mc.getFps();
		Runtime rt = Runtime.getRuntime();
		float mem = (rt.totalMemory() - rt.freeMemory()) / (float) Math.max(1, rt.maxMemory());
		int ping = Compat.latency();
		String[][] stats = {{"FPS", String.valueOf(fps)}, {"Memory", Math.round(mem * 100) + "%"}, {"Ping", ping > 0 ? ping + " ms" : "-"}};
		float[] fills = {Math.min(1f, fps / 240f), mem, ping > 0 ? Math.min(1f, ping / 300f) : 0};
		for (int i = 0; i < 3; i++) {
			int ry = yy + 26 + i * 26;
			Ui.small(g, stats[i][0], sx + 10, ry, Anim.argb(Ui.MUTED, a3));
			Ui.small(g, stats[i][1], cx2 - 10 - Ui.widthSmall(stats[i][1]), ry, Anim.argb(Ui.TEXT, a3));
			float k = a("sys" + i, fills[i], 0.08f);
			Ui.round(g, sx + 10, ry + 10, cx2 - 10, ry + 13, 1, Anim.argb(Ui.INK, 0.08f * a3));
			Ui.round(g, sx + 10, ry + 10, sx + 10 + Math.max(3, Math.round((cx2 - sx - 20) * k)), ry + 13, 1, Anim.argb(i == 1 && mem > 0.85f ? 0xE5484D : accent, a3));
		}
		y += ch + gap;
		// quick setups
		y = section(g, "Quick setups", y, al);
		float f4 = stagger(cardIndex++);
		int pc = w > 380 ? 3 : 2, pwid = (w - 6 * (pc - 1)) / pc, phh = 28;
		for (int i = 0; i < PRESETS.length; i++) {
			int x = cx1 + (i % pc) * (pwid + 6), ty = y + (i / pc) * (phh + 6);
			boolean hov = inContent(mx, my) && Ui.inside(mx, my, x, ty, x + pwid, ty + phh);
			float h = a("pre" + i, hov);
			card(g, x, ty, x + pwid, ty + phh, al * f4, h);
			Ui.iconTex(g, PRESETS[i][0], x + 9, ty + 10, 9, Anim.argb(accent, al * f4));
			Ui.text(g, PRESETS[i][1], x + 24, ty + 5, Anim.argb(Ui.TEXT, al * f4));
			Ui.small(g, Ui.ellipsize(PRESETS[i][2], Math.round((pwid - 30) / 0.75f)), x + 24, ty + 16, Anim.argb(Ui.MUTED, al * f4));
			final int fi = i;
			if (inContent(mx, my)) hits.add(new Hit(x, ty, x + pwid, ty + phh, b -> applyPreset(fi)));
		}
		y += ((PRESETS.length + pc - 1) / pc) * (phh + 6) + 6;
		// last session
		if (ServerData.lastSummary != null && System.currentTimeMillis() - ServerData.lastSummaryAt < 6 * 3600000L) {
			String[] s = ServerData.lastSummary;
			y = section(g, "Last session · " + s[0], y, al);
			card(g, cx1, y, cx2, y + 24, al, 0);
			String line = s[1] + (s[2].isEmpty() ? "" : "   ·   " + s[2] + " made") + (s[3].isEmpty() ? "" : "   ·   +" + s[3] + " shards") + "   ·   " + s[4] + " kills · " + s[5] + " deaths";
			Ui.text(g, Ui.ellipsize(line, w - 16), cx1 + 8, y + 8, Anim.argb(Ui.TEXT, al));
			y += 30;
		}
		return y;
	}

	private void applyPreset(int i) {
		String[] on, off;
		switch (i) {
			case 0 -> {
				for (Module m : Modules.all()) if (m.recommended) m.set(true);
				on = new String[]{"hud_master"};
				off = new String[]{};
			}
			case 1 -> {
				on = new String[]{"keystrokes", "cps", "fps", "ping", "armor_hud", "hit_marker", "totem_counter", "potions", "target_hud", "combo_counter", "cooldown_indicator", "pearl_cooldown", "no_hurt_cam", "toggle_sprint", "health_bars", "kill_banner", "hud_master"};
				off = new String[]{"lyrics", "sky_fx"};
			}
			case 2 -> {
				on = new String[]{"info_panel", "compass", "zoom", "fullbright", "waypoint_labels", "waypoint_beams", "death_waypoint", "hud_master"};
				off = new String[]{"keystrokes", "cps", "target_hud", "combo_counter"};
			}
			case 3 -> {
				on = new String[]{"coords_streamer", "streamer_mode", "hud_master"};
				off = new String[]{"share_server", "location_share"};
			}
			case 4 -> {
				on = new String[]{"fps", "hud_master"};
				off = new String[]{"info_panel", "compass", "keystrokes", "cps", "ping", "potions", "armor_hud", "pickup_feed", "now_playing", "clock"};
			}
			default -> {
				on = new String[]{"fps"};
				off = new String[]{"sky_fx", "pearl_trail", "hit_sparks", "kill_effect", "others_cosmetics", "video_screens", "menu_petals"};
			}
		}
		for (String id : on) { Module m = Modules.get(id); if (m != null) m.set(true); }
		for (String id : off) { Module m = Modules.get(id); if (m != null) m.set(false); }
		if (i == 4 && Modules.setting("hud.look") != null) setTo(Modules.setting("hud.look"), 2);
		KyotoConfig.save();
		Sounds.play(Sounds.SUCCESS);
		Notifications.push(PRESETS[i][1] + " setup on", "Change anything you like after", Notifications.GOOD, 3500, null, PRESETS[i][0]);
	}

	/* ---------------- module pages ---------------- */

	private int drawModules(GuiGraphicsExtractor g, int y, int mx, int my, float al, int accent, String[][] buttons) {
		if (page == Page.SETTINGS && tab() == 0 && filter == 0) y = drawMenuMedia(g, y, mx, my, al, accent);
		Category[] cats = TABS.get(page).values().toArray(new Category[0][])[Math.min(tab(), TABS.get(page).size() - 1)];
		if (buttons != null) y = buttons(g, buttons, y, mx, my, al, accent);
		List<Module> all = new ArrayList<>();
		for (Category c : cats) for (Module m : Modules.in(c)) if (m.pack == null) all.add(m);
		if (page == Page.MEDIA && tab() == 1) {
			Module sl = Modules.get("stream_live");
			if (sl != null && !all.contains(sl)) all.add(sl);
		}
		// on now
		List<Module> onNow = new ArrayList<>();
		for (Module m : all) if (m.on()) onNow.add(m);
		if (!onNow.isEmpty() && filter == 0) {
			Ui.text(g, "On now", cx1 + 1, y + 2, Anim.argb(Ui.TEXT, al));
			y += 14;
			int x = cx1;
			int shown = 0;
			for (Module m : onNow) {
				int w = Ui.widthSmall(m.name) + 22;
				if (x + w > cx2) { x = cx1; y += 18; if (++shown > 1) break; }
				boolean hov = inContent(mx, my) && Ui.inside(mx, my, x, y, x + w, y + 14);
				float h = a("chip" + m.id, hov);
				Ui.round(g, x, y, x + w, y + 14, 7, Anim.argb(Ui.GLASS_LIGHT, ((Ui.DARK ? 0.08f : 0.55f) + 0.2f * h) * al));
				Ui.ring(g, x, y, x + w, y + 14, 7, 1, Anim.argb(Ui.mix(0xFFFFFF, accent, 0.4f + 0.6f * h), (Ui.DARK ? 0.3f : 0.85f) * al));
				Ui.small(g, m.name, x + 7, y + 4, Anim.argb(Ui.INK, al));
				Ui.iconTex(g, "check", x + w - 12, y + 4, 6, Anim.argb(accent, al));
				if (inContent(mx, my)) hits.add(new Hit(x, y, x + w, y + 14, b -> { if (b == 1) m.toggle(); else expanded = m.id.equals(expanded) ? null : m.id; }));
				x += w + 5;
			}
			y += 22;
		}
		// filter
		List<Module> list = new ArrayList<>();
		for (Module m : all) {
			boolean st = starred(m.id);
			if (filter == 1 && !m.on() || filter == 2 && m.on() || filter == 3 && !st) continue;
			list.add(m);
		}
		if (list.isEmpty()) return note(g, filter == 3 ? "Nothing starred here yet. Right-click a card to star it." : "Nothing here with this filter.", y + 4, al);
		// starred first, then by section
		Map<String, List<Module>> secs = new LinkedHashMap<>();
		List<Module> stars = new ArrayList<>();
		for (Module m : list) if (starred(m.id) && filter != 3) stars.add(m);
		if (!stars.isEmpty()) secs.put("Starred", stars);
		for (Module m : list) if (!stars.contains(m)) secs.computeIfAbsent(m.section, k -> new ArrayList<>()).add(m);
		for (var e : secs.entrySet()) {
			if (secs.size() > 1 || !e.getKey().equals("General")) y = section(g, e.getKey(), y, al);
			y = grid(g, e.getValue(), y, mx, my, al, accent);
		}
		return y;
	}

	/** Settings > Menu background: pick an MP4 and an MP3 from the backgrounds folder. */
	private int drawMenuMedia(GuiGraphicsExtractor g, int y, int mx, int my, float al, int accent) {
		y = section(g, "Menu background", y, al);
		var cfg = KyotoConfig.get();
		for (int kind = 0; kind < 2; kind++) {
			boolean videos = kind == 0;
			List<String> files = dev.kyoto.ui.MenuMedia.files(videos);
			String cur = videos ? cfg.menuVideo : cfg.menuMusic;
			Ui.small(g, videos ? "VIDEO (MP4)" : "MUSIC (MP3)", cx1 + 1, y + 2, Anim.argb(Ui.DIM, al));
			int x = cx1 + 66;
			List<String> opts = new ArrayList<>();
			opts.add("");
			opts.addAll(files);
			for (String f : opts) {
				String label = f.isEmpty() ? "None" : Ui.ellipsize(f, 90);
				int w = Ui.widthSmall(label) + 14;
				if (x + w > cx2) { x = cx1 + 66; y += 17; }
				boolean sel = f.equals(cur), hov = inContent(mx, my) && Ui.inside(mx, my, x, y, x + w, y + 14);
				float s = a("mm" + kind + f, sel);
				Ui.round(g, x, y, x + w, y + 14, 7, Anim.argb(sel ? accent : Ui.INK, (0.06f + (hov ? 0.06f : 0) + 0.84f * s) * al));
				Ui.small(g, label, x + 7, y + 4, Anim.argb(Ui.mix(Ui.MUTED, 0xFFFFFF, s), al));
				if (inContent(mx, my)) hits.add(new Hit(x, y, x + w, y + 14, b -> {
					if (videos) { cfg.menuVideo = f; dev.kyoto.ui.MenuMedia.reloadVideo(); if (!f.isEmpty()) { Setting bgS = Modules.setting("menu.bg"); if (bgS != null) setTo(bgS, 3); } }
					else { cfg.menuMusic = f; dev.kyoto.ui.MenuMedia.musicStop(); if (!f.isEmpty()) { Module mm = Modules.get("menu_music"); if (mm != null) mm.set(true); dev.kyoto.ui.MenuMedia.musicStart(); } }
					KyotoConfig.save();
					Sounds.play(Sounds.TAB);
				}));
				x += w + 4;
			}
			y += 20;
		}
		String hint = "Put MP4 / MP3 files in .minecraft/kyoto/backgrounds";
		Ui.small(g, hint, cx1 + 1, y, Anim.argb(Ui.MUTED, al));
		int bx = cx1 + Ui.widthSmall(hint) + 10;
		boolean oh = inContent(mx, my) && Ui.inside(mx, my, bx, y - 4, bx + 74, y + 10);
		Ui.round(g, bx, y - 4, bx + 74, y + 10, 7, Anim.argb(oh ? accent : Ui.INK, (oh ? 0.9f : 0.08f) * al));
		Ui.small(g, "Open folder", bx + 10, y, Anim.argb(oh ? 0xFFFFFF : Ui.INK, al));
		if (inContent(mx, my)) hits.add(new Hit(bx, y - 4, bx + 74, y + 10, b -> Compat.openFolder(dev.kyoto.ui.MenuMedia.dir())));
		return y + 18;
	}

	private static boolean starred(String id) {
		return Boolean.TRUE.equals(KyotoConfig.get().modules.get("_star_" + id));
	}

	private static void star(String id) {
		var mods = KyotoConfig.get().modules;
		if (starred(id)) mods.remove("_star_" + id);
		else mods.put("_star_" + id, true);
		KyotoConfig.save();
	}

	/** Module cards, three to a row. A module's settings open full-width under its row. */
	private int grid(GuiGraphicsExtractor g, List<Module> list, int y, int mx, int my, float al, int accent) {
		int w = cx2 - cx1, cols = w > 420 ? 3 : w > 260 ? 2 : 1, gap = 6, cw = (w - gap * (cols - 1)) / cols, ch = 24;
		for (int i = 0; i < list.size(); i += cols) {
			Module open = null;
			for (int c = 0; c < cols && i + c < list.size(); c++) {
				Module m = list.get(i + c);
				int x = cx1 + c * (cw + gap);
				moduleCard(g, m, x, y, cw, ch, mx, my, al, accent);
				if (m.id.equals(expanded)) open = m;
			}
			y += ch + gap;
			if (open != null) y = settingsPanel(g, open, y, mx, my, al, accent) + gap;
		}
		return y + 4;
	}

	private void moduleCard(GuiGraphicsExtractor g, Module m, int x, int y, int w, int h, int mx, int my, float al, int accent) {
		float f = stagger(cardIndex++);
		if (y > cy2 + 4 || y + h < cy1 - 4) return;
		al *= f;
		y += Math.round((1 - f) * 5);
		boolean blocked = Packs.disallowedHere(m.id);
		boolean hov = inContent(mx, my) && Ui.inside(mx, my, x, y, x + w, y + h);
		float hv = a("card" + m.id, hov);
		boolean on = m.on() && !blocked;
		float k = a("on" + m.id, on ? 1f : 0f, 0.2f);
		boolean open = m.id.equals(expanded);
		// body: off = faint, on = bright glass with an accent rim
		Ui.round(g, x, y, x + w, y + h, 8, Anim.argb(Ui.GLASS_LIGHT, ((Ui.DARK ? 0.03f : 0.25f) + (Ui.DARK ? 0.07f : 0.5f) * k + 0.1f * hv) * al));
		Ui.ring(g, x, y, x + w, y + h, 8, 1, Anim.argb(Ui.mix(0xFFFFFF, accent, k), (Ui.DARK ? 0.08f + 0.5f * k : 0.55f + 0.35f * k) * al));
		// icon + name
		Ui.iconTex(g, m.category.tex, x + 8, y + 8, 8, Anim.argb(Ui.mix(Ui.DIM, accent, k), al));
		int right = x + w - (m.settings.isEmpty() ? 8 : 20);
		int nx = x + 21;
		int badgeW = m.fresh ? Ui.widthSmall("NEW") + 6 : 0;
		Ui.text(g, Ui.ellipsize(m.name, right - nx - badgeW - (starred(m.id) ? 10 : 0)), nx, y + 8, Anim.argb(blocked ? Ui.DIM : Ui.mix(Ui.MUTED, Ui.INK, Math.max(k, hv)), al));
		int bx = right - badgeW;
		if (m.fresh) Ui.small(g, "NEW", bx, y + 9, Anim.argb(accent, al));
		if (m.risk != null) {
			Ui.circle(g, bx - (starred(m.id) ? 18 : 6), y + 12, 3.5f, Anim.argb(0xF2B84B, al));
			Ui.small(g, "!", bx - (starred(m.id) ? 19 : 7), y + 9, Anim.argb(0xFFFFFF, al));
		}
		if (starred(m.id)) Ui.iconTex(g, "star", bx - 10, y + 8, 7, Anim.argb(0xF2B84B, al));
		if (inContent(mx, my)) hits.add(new Hit(x, y, x + w, y + h, b -> {
			if (b == 1) {
				star(m.id);
				Sounds.play(Sounds.TAB);
			} else if (!blocked) {
				if (!m.on() && m.risk != null && Compat.currentServerIp() != null) {
					pendingRisk = m;
					Sounds.play(Sounds.NOTIFY);
					return;
				}
				m.toggle();
				Sounds.play(m.on() ? Sounds.ON : Sounds.OFF);
			}
		}));
		// cog
		if (!m.settings.isEmpty()) {
			int gx = x + w - 16, gy = y + 7;
			boolean gh = inContent(mx, my) && Ui.inside(mx, my, gx - 3, gy - 3, gx + 12, gy + 12);
			float rot = a("cog" + m.id, open ? 1f : 0f, 0.18f);
			g.pose().pushMatrix();
			g.pose().translate(gx + 4.5f, gy + 4.5f);
			g.pose().rotate(rot * (float) Math.PI / 2);
			Ui.iconTex(g, "gear", -4, -4, 9, Anim.argb(open ? accent : gh ? Ui.TEXT : Ui.DIM, al));
			g.pose().popMatrix();
			if (inContent(mx, my)) hits.add(new Hit(gx - 4, gy - 4, gx + 13, gy + 13, b -> { expanded = open ? null : m.id; Sounds.play(Sounds.TAB); }));
		}
		if (hov) {
			hoverDesc = blocked ? "This server doesn't allow it, so Kyoto keeps it off here." : m.risk != null ? m.desc + "  ⚠ " + m.risk : m.desc;
			hoverDescY = y + h + 3;
		}
	}

	/** The hovered card's description, as a wide strip under it. */
	private void descStrip(GuiGraphicsExtractor g, String text, int y, float al, int accent) {
		if (y + 16 > cy2) y -= 16 + 30;
		String t = Ui.ellipsize(text, cx2 - cx1 - 16);
		float f = a("descf", 1f, 0.3f);
		Ui.shadow(g, cx1, y, cx2, y + 16, 7, 0.4f * al);
		Ui.round(g, cx1, y, cx2, y + 16, 7, Anim.argb(Ui.GLASS, 0.97f * al * f));
		Ui.ring(g, cx1, y, cx2, y + 16, 7, 1, Anim.argb(0xFFFFFF, (Ui.DARK ? 0.12f : 0.9f) * al));
		Ui.text(g, t, cx1 + 8, y + 5, Anim.argb(Ui.TEXT, al * f));
	}

	private int settingsPanel(GuiGraphicsExtractor g, Module m, int y, int mx, int my, float al, int accent) {
		float f = a("open" + m.id, 1f, 0.25f);
		int h = 16 + m.settings.size() * 20;
		int vis = Math.round(h * Anim.easeOutCubic(f));
		card(g, cx1, y, cx2, y + vis, al, 0);
		g.enableScissor(cx1, Math.max(cy1, y), cx2, Math.min(cy2, y + vis));
		Ui.small(g, m.name.toUpperCase(Locale.ROOT) + " SETTINGS", cx1 + 10, y + 6, Anim.argb(Ui.DIM, al));
		int yy = y + 16;
		for (Setting s : m.settings) {
			setting(g, s, cx1 + 10, cx2 - 10, yy, mx, my, al, accent);
			yy += 20;
		}
		g.disableScissor();
		return y + vis;
	}

	private void setting(GuiGraphicsExtractor g, Setting s, int x1, int x2, int y, int mx, int my, float al, int accent) {
		Ui.text(g, s.name, x1, y + 4, Anim.argb(Ui.MUTED, al));
		int total = 0;
		for (String o : s.options) total += Ui.widthSmall(o) + 12;
		boolean seg = s.options.length <= 6 && total < (x2 - x1) - Ui.width(s.name) - 20;
		if (seg) {
			int x = x2 - total;
			Ui.round(g, x - 2, y, x2 + 2, y + 15, 7, Anim.argb(Ui.INK, 0.06f * al));
			// sliding highlight
			int sx = x, selW = 0;
			for (int i = 0; i < s.options.length; i++) {
				int ow = Ui.widthSmall(s.options[i]) + 12;
				if (i == s.get()) { selW = ow; break; }
				sx += ow;
			}
			float ax = a("segx" + s.key, sx, 0.3f), aw = a("segw" + s.key, selW, 0.3f);
			Ui.round(g, Math.round(ax), y + 2, Math.round(ax + aw), y + 13, 5, Anim.argb(accent, al));
			for (int i = 0; i < s.options.length; i++) {
				int ow = Ui.widthSmall(s.options[i]) + 12;
				boolean sel = s.get() == i;
				Ui.small(g, s.options[i], x + 6, y + 5, Anim.argb(sel ? 0xFFFFFF : Ui.MUTED, al));
				final int fi = i;
				if (inContent(mx, my)) hits.add(new Hit(x, y, x + ow, y + 15, b -> { setTo(s, fi); Sounds.play(Sounds.TAB); }));
				x += ow;
			}
		} else {
			String v = s.label();
			int cw = Ui.width(v) + 30, cx = x2 - cw;
			Ui.round(g, cx, y, x2, y + 15, 7, Anim.argb(Ui.INK, 0.07f * al));
			Ui.icon(g, "‹", cx + 5, y + 4, Anim.argb(Ui.MUTED, al));
			Ui.text(g, v, cx + 15, y + 4, Anim.argb(accent, al));
			Ui.icon(g, "›", x2 - 9, y + 4, Anim.argb(Ui.MUTED, al));
			int mid = cx + cw / 2;
			if (inContent(mx, my)) hits.add(new Hit(cx - 2, y, x2 + 2, y + 15, b -> { s.cycle(b == 1 || lastMx < mid ? -1 : 1); Sounds.play(Sounds.TAB); }));
		}
	}

	private static void setTo(Setting s, int index) {
		int diff = index - s.get();
		if (diff != 0) s.cycle(diff);
	}

	/* ---------------- Cosmetics ---------------- */

	private static final String[][] WARDROBE = {
			{"paw", "Pets", "3D buddies that follow you", "#pets"},
			{"sword", "Knives", "Karambit, butterfly, more", "#knives"},
			{"hat", "Hats", "Kasa, crowns, ears", "#wardrobe:hat"},
			{"wings", "Wings", "Sakura, angel, butterfly", "#wardrobe:back"},
			{"halo", "Halos", "Rings that float above you", "#wardrobe:halo"},
			{"aura", "Auras", "Rings, spirals and glow", "#wardrobe:aura"},
			{"cape", "Capes", "Ready-made or your own", "Cosmetics"},
			{"smile", "Emotes", "Pop a sticker above you", "Emotes"},
	};

	private int drawCosmetics(GuiGraphicsExtractor g, int y, int mx, int my, float al, int accent) {
		y = note(g, "Only you see these, unless others use Kyoto too.", y, al);
		int w = cx2 - cx1, cols = w > 380 ? 4 : 2, gap = 6, tw = (w - gap * (cols - 1)) / cols, th = 58;
		for (int i = 0; i < WARDROBE.length; i++) {
			float f = stagger(cardIndex++);
			int x = cx1 + (i % cols) * (tw + gap), ty = y + (i / cols) * (th + gap) + Math.round((1 - f) * 6);
			boolean hov = inContent(mx, my) && Ui.inside(mx, my, x, ty, x + tw, ty + th);
			float h = a("ward" + i, hov);
			card(g, x, ty, x + tw, ty + th, al * f, h);
			float bob = (float) Math.sin(System.currentTimeMillis() / 500.0 + i) * 1.5f * h;
			Ui.circle(g, x + tw / 2f, ty + 20 + bob, 11, Anim.argb(accent, (0.12f + 0.1f * h) * al * f));
			Ui.iconTex(g, WARDROBE[i][0], x + tw / 2 - 6, Math.round(ty + 14 + bob), 12, Anim.argb(accent, al * f));
			Ui.text(g, WARDROBE[i][1], x + tw / 2 - Ui.width(WARDROBE[i][1]) / 2, ty + 36, Anim.argb(Ui.TEXT, al * f));
			String sub = Ui.ellipsize(WARDROBE[i][2], Math.round((tw - 8) / 0.75f));
			Ui.small(g, sub, x + tw / 2f - Ui.widthSmall(sub) / 2f, ty + 47, Anim.argb(Ui.MUTED, al * f));
			final String[] s = WARDROBE[i];
			if (inContent(mx, my)) hits.add(new Hit(x, ty, x + tw, ty + th, b -> openTool(s[3])));
		}
		y += ((WARDROBE.length + cols - 1) / cols) * (th + gap) + 6;
		y = section(g, "Settings", y, al);
		List<Module> list = new ArrayList<>(Modules.in(Category.COSMETICS));
		list.removeIf(m -> m.pack != null);
		return grid(g, list, y, mx, my, al, accent);
	}

	/* ---------------- Theme ---------------- */

	private static final String[] THEME_NAMES = {"Sakura", "Blush", "Rose", "Peach", "Lilac", "Sky", "Mint", "Custom", "Shimmer"};
	private static final String[] THEME_SUBS = {"Soft pink, the Kyoto look", "Lighter and softer", "Deep rose", "Warm peach", "Calm lilac", "Clear sky blue", "Fresh mint", "Your own colour", "Breathes blush to rose"};

	private int drawTheme(GuiGraphicsExtractor g, int y, int mx, int my, float al, int accent) {
		Setting acc = Modules.setting("accent.color");
		int cur = acc == null ? 0 : acc.get();
		// current theme banner
		float f0 = stagger(cardIndex++);
		card(g, cx1, y, cx2, y + 40, al * f0, 0);
		Ui.small(g, "CURRENT THEME", cx1 + 10, y + 8, Anim.argb(Ui.DIM, al * f0));
		Ui.bold(g, THEME_NAMES[Math.min(cur, THEME_NAMES.length - 1)] + (Ui.DARK ? " · dark" : ""), cx1 + 10, y + 20, Anim.argb(Ui.TEXT, al * f0));
		int sw = cx2 - 10;
		int[] sws = {accent, Ui.PINK_SOFT, Ui.GLASS, Ui.INK, 0xFFFFFF};
		for (int i = sws.length - 1; i >= 0; i--) {
			sw -= 14;
			Ui.round(g, sw, y + 14, sw + 11, y + 25, 3, Anim.argb(sws[i], al * f0));
			Ui.ring(g, sw, y + 14, sw + 11, y + 25, 3, 1, Anim.argb(Ui.INK, 0.12f * al * f0));
		}
		y += 48;
		y = section(g, "All themes", y, al);
		int w = cx2 - cx1, cols = w > 380 ? 4 : 2, gap = 6, tw = (w - gap * (cols - 1)) / cols, th = 54;
		for (int i = 0; i < THEME_NAMES.length; i++) {
			float f = stagger(cardIndex++);
			int x = cx1 + (i % cols) * (tw + gap), ty = y + (i / cols) * (th + gap) + Math.round((1 - f) * 6);
			boolean hov = inContent(mx, my) && Ui.inside(mx, my, x, ty, x + tw, ty + th);
			boolean sel = cur == i;
			float h = a("theme" + i, hov), s = a("themes" + i, sel);
			int col = themeColor(i);
			card(g, x, ty, x + tw, ty + th, al * f, h);
			if (s > 0.01f) Ui.ring(g, x, ty, x + tw, ty + th, 9, 1.5f, Anim.argb(col, s * al * f));
			// tiny window preview in that colour
			int wx = x + 8, wy = ty + 7, ww = tw - 16, wh = 26;
			Ui.round(g, wx, wy, wx + ww, wy + wh, 5, Anim.argb(Ui.DARK ? 0x33272C : 0xFFF8FA, al * f));
			Ui.round(g, wx + 3, wy + 3, wx + 18, wy + wh - 3, 3, Anim.argb(Ui.INK, 0.05f * al * f));
			Ui.round(g, wx + 4, wy + 5, wx + 17, wy + 9, 2, Anim.argb(col, al * f));
			for (int r = 0; r < 3; r++) Ui.round(g, wx + 22, wy + 5 + r * 6, wx + 22 + (ww - 30) * (r == 0 ? 1 : r == 1 ? 3 : 2) / 3, wy + 8 + r * 6, 1, Anim.argb(r == 0 ? col : Ui.INK, (r == 0 ? 0.8f : 0.12f) * al * f));
			Ui.text(g, THEME_NAMES[i], x + 8, ty + 38, Anim.argb(Ui.TEXT, al * f));
			if (sel) Ui.iconTex(g, "check", x + tw - 15, ty + 39, 7, Anim.argb(col, al * f));
			if (hov) tip = THEME_SUBS[i];
			final int fi = i;
			if (inContent(mx, my)) hits.add(new Hit(x, ty, x + tw, ty + th, b -> {
				Module am = Modules.get("accent");
				if (am != null) am.set(true);
				if (acc != null) setTo(acc, fi);
				Sounds.play(Sounds.SUCCESS);
				if (fi == 7) Compat.openScreen(ClickGui.toolScreen("Theme", this));
			}));
		}
		y += ((THEME_NAMES.length + cols - 1) / cols) * (th + gap) + 6;
		y = section(g, "Look", y, al);
		List<Module> look = new ArrayList<>();
		for (String id : new String[]{"dark_mode", "liquid_glass", "menu_glass", "menu_style", "menu_tilt", "menu_petals", "button_sounds", "ui_animations", "kyoto_vanilla_menus", "title_screen"}) {
			Module m = Modules.get(id);
			if (m != null) look.add(m);
		}
		return grid(g, look, y, mx, my, al, accent);
	}

	private static int themeColor(int i) {
		int[] c = {0xEF6F98, 0xF7A1BC, 0xE0457A, 0xF59E8B, 0xB59AE8, 0x7FB6F0, 0x6CCFAE};
		if (i < c.length) return c[i];
		if (i == 7) { var cfg = KyotoConfig.get(); return Ui.hsv(cfg.accentHue, cfg.accentSat, cfg.accentBright); }
		return Ui.shimmer(0);
	}

	/* ---------------- Server ---------------- */

	private int drawServer(GuiGraphicsExtractor g, int y, int mx, int my, float al, int accent) {
		int w = cx2 - cx1;
		y = section(g, "Server pack", y, al);
		List<String> ids = new ArrayList<>();
		ids.add("");
		ids.addAll(Packs.ALL.keySet());
		ids.add("off");
		String picked = Packs.host.isEmpty() ? "" : KyotoConfig.get().packPicks.getOrDefault(Packs.host, "");
		int x = cx1;
		for (String id : ids) {
			String label = id.isEmpty() ? "Auto" : id.equals("off") ? "Off" : Packs.ALL.get(id).name();
			int bw = Ui.widthSmall(label) + 14;
			if (x + bw > cx2) { x = cx1; y += 18; }
			boolean sel = id.equals(picked);
			boolean hov = inContent(mx, my) && Ui.inside(mx, my, x, y, x + bw, y + 14);
			Ui.round(g, x, y, x + bw, y + 14, 7, Anim.argb(sel ? accent : Ui.INK, (sel ? 0.9f : hov ? 0.12f : 0.06f) * al));
			Ui.small(g, label, x + 7, y + 4, Anim.argb(sel ? 0xFFFFFF : Ui.MUTED, al));
			if (inContent(mx, my) && !Packs.host.isEmpty()) hits.add(new Hit(x, y, x + bw, y + 14, b -> { Packs.force(id); Notifications.push("Server pack", id.isEmpty() ? "Back to automatic" : label, Notifications.INFO, 2500, null, "server"); }));
			x += bw + 5;
		}
		y += 22;
		if (Packs.host.isEmpty()) {
			y = note(g, "You're not on a server. When you join one, Kyoto picks the right pack by itself and this page is named after the server.", y, al);
			for (Packs.Pack p : Packs.ALL.values()) {
				card(g, cx1, y, cx2, y + 26, al, 0);
				Ui.circle(g, cx1 + 10, y + 13, 3, Anim.argb(p.color(), al));
				Ui.text(g, p.name(), cx1 + 18, y + 5, Anim.argb(Ui.TEXT, al));
				Ui.small(g, p.desc(), cx1 + 18, y + 15, Anim.argb(Ui.MUTED, al));
				y += 30;
			}
			return y;
		}
		if (Packs.current == null) return note(g, "No pack is on for this server. Pick one above if it's like DonutSMP.", y, al);
		if (Packs.is("donut") || ServerData.money >= 0) {
			y = section(g, "Today · this session " + ServerData.sessionTime(), y, al);
			int bw = (w - 12) / 3;
			String[][] tiles = {{"This session", (ServerData.sessionMoney() >= 0 ? "+" : "") + Packs.fmt(ServerData.sessionMoney())}, {"Per hour", Packs.fmt(ServerData.moneyPerHour())},
					{"Shards", ServerData.shards >= 0 ? Packs.num(ServerData.shards) : "?"}};
			for (int i = 0; i < 3; i++) {
				int tx = cx1 + i * (bw + 6);
				card(g, tx, y, tx + bw, y + 32, al, 0);
				Ui.small(g, tiles[i][0], tx + 8, y + 6, Anim.argb(Ui.MUTED, al));
				Ui.boldScaled(g, tiles[i][1], tx + 8, y + 16, 1.15f, Anim.argb(Ui.TEXT, al));
			}
			y += 38;
			List<Object[]> week = ServerData.week(Packs.current.id());
			double max = 1;
			for (Object[] d : week) max = Math.max(max, (double) d[1]);
			int gh = 40, bw2 = (w - 6 * 6) / 7;
			card(g, cx1, y, cx2, y + gh + 16, al, 0);
			for (int i = 0; i < week.size(); i++) {
				Object[] d = week.get(i);
				int bx = cx1 + 4 + i * (bw2 + 6) - 2;
				float k = a("week" + i, (float) ((double) d[1] / max), 0.1f);
				int bh = Math.round(k * (gh - 6));
				Ui.round(g, bx + 4, y + 4 + gh - bh, bx + bw2 - 4, y + 4 + gh, 2, Anim.argb(accent, (i == 6 ? 0.95f : 0.5f) * al));
				Ui.small(g, (String) d[0], bx + bw2 / 2f - 2, y + gh + 6, Anim.argb(Ui.MUTED, al));
				if (inContent(mx, my) && Ui.inside(mx, my, bx, y, bx + bw2, y + gh + 14)) tip = Packs.fmt((double) d[1]) + " made · " + Math.round((double) d[2]) + " min played";
			}
			y += gh + 22;
		}
		List<Module> mods = new ArrayList<>();
		for (Module m : Modules.all()) {
			if (m.pack == null) continue;
			boolean mine = m.pack.equals(Packs.current.id()) || (m.pack.equals("smp") && Packs.is("donut"));
			if (mine) mods.add(m);
		}
		Map<String, List<Module>> secs = new LinkedHashMap<>();
		for (Module m : mods) secs.computeIfAbsent(m.section, k -> new ArrayList<>()).add(m);
		for (var e : secs.entrySet()) {
			y = section(g, e.getKey(), y, al);
			y = grid(g, e.getValue(), y, mx, my, al, accent);
		}
		y = drawPlaces(g, y, mx, my, al, accent);
		y = section(g, "This server", y + 4, al);
		String[][] btns = {{"hud", "Edit HUD for " + Packs.serverLabel, "hud"}, {"waypoint", "Homes + warps", "places"}, {"gear", "Save my key binds here", "keys"}};
		y = buttons(g, btns, y, mx, my, al, accent);
		if (!Packs.SUGGESTIONS.isEmpty()) {
			y = section(g, "Good with this server", y, al);
			for (String[] s : Packs.SUGGESTIONS) {
				Ui.text(g, s[0], cx1 + 4, y, Anim.argb(Ui.TEXT, al));
				Ui.small(g, s[1], cx1 + 4, y + 10, Anim.argb(Ui.MUTED, al));
				if (!s[2].isEmpty() && inContent(mx, my)) hits.add(new Hit(cx1, y - 2, cx2, y + 18, b -> Compat.openUrl(s[2])));
				y += 22;
			}
		}
		return y;
	}

	private int drawPlaces(GuiGraphicsExtractor g, int y, int mx, int my, float al, int accent) {
		boolean hide = dev.kyoto.feature.Privacy.hideCoords() || Modules.on("coords_streamer");
		if (!Packs.is("donut")) return y;
		var o = Donut.places();
		if (o.has("bases") && !o.getAsJsonObject("bases").isEmpty()) {
			y = section(g, "My bases" + (hide ? " (hidden for streaming)" : ""), y, al);
			for (String k : o.getAsJsonObject("bases").keySet()) {
				var b = o.getAsJsonObject("bases").getAsJsonObject(k);
				String c = hide ? "hidden" : b.get("x").getAsInt() + " " + b.get("y").getAsInt() + " " + b.get("z").getAsInt();
				y = listRow(g, "home", k, c, y, al, mx, my, hide ? null : () -> Compat.copy(c));
			}
		}
		if (o.has("rtp") && !o.getAsJsonArray("rtp").isEmpty()) {
			y = section(g, "Where /rtp sent you", y, al);
			var arr = o.getAsJsonArray("rtp");
			for (int i = arr.size() - 1; i >= Math.max(0, arr.size() - 4); i--) {
				var p = arr.get(i).getAsJsonObject();
				String c = hide ? "hidden" : p.get("x").getAsInt() + " " + p.get("y").getAsInt() + " " + p.get("z").getAsInt();
				y = listRow(g, "waypoint", c, "click to copy", y, al, mx, my, hide ? null : () -> Compat.copy(c));
			}
		}
		List<String> duels = Donut.duelList();
		if (Modules.on("donut_duels") && !duels.isEmpty()) {
			y = section(g, "Duels", y, al);
			for (String d : duels) y = listRow(g, "sword", d, "", y, al, mx, my, null);
		}
		return note(g, "Commands: /kyoto price <item> · /kyoto watch <item> <price> · /kyoto profit <in> <n> <out> <n> · /kyoto base add <name> · /kyoto flips", y + 2, al);
	}

	private int listRow(GuiGraphicsExtractor g, String icon, String text, String right, int y, float al, int mx, int my, Runnable click) {
		boolean hov = click != null && inContent(mx, my) && Ui.inside(mx, my, cx1, y, cx2, y + 18);
		card(g, cx1, y, cx2, y + 18, al, hov ? 1 : 0);
		Ui.iconTex(g, icon, cx1 + 6, y + 5, 8, Anim.argb(Ui.accent(), al));
		Ui.text(g, Ui.ellipsize(text, cx2 - cx1 - 30 - Ui.widthSmall(right)), cx1 + 19, y + 5, Anim.argb(Ui.TEXT, al));
		if (!right.isEmpty()) Ui.small(g, right, cx2 - 6 - Ui.widthSmall(right), y + 6, Anim.argb(Ui.MUTED, al));
		if (click != null && inContent(mx, my)) hits.add(new Hit(cx1, y, cx2, y + 18, b -> {
			click.run();
			Notifications.push("Copied", text, Notifications.INFO, 1500, null, "check");
		}));
		return y + 21;
	}

	/* ---------------- Media ---------------- */

	private int drawMusic(GuiGraphicsExtractor g, int y, int mx, int my, float al, int accent) {
		int w = cx2 - cx1, h = 88;
		float f = stagger(cardIndex++);
		card(g, cx1, y, cx2, y + h, al * f, 0);
		// spinning vinyl with the album art in the middle
		int vx = cx1 + 12, vy = y + 10, vs = h - 20;
		float spin = Music.active() && Music.playing ? (System.currentTimeMillis() % 6000L) / 6000f * (float) Math.PI * 2 : a("vinylrest", 0f, 0.05f);
		Ui.circle(g, vx + vs / 2f, vy + vs / 2f, vs / 2f, Anim.argb(0x2B1D23, al * f));
		for (int r = 0; r < 4; r++) Ui.ring(g, vx + 5 + r * 4, vy + 5 + r * 4, vx + vs - 5 - r * 4, vy + vs - 5 - r * 4, vs / 2 - 5 - r * 4, 1, Anim.argb(0xFFFFFF, 0.08f * al * f));
		g.pose().pushMatrix();
		g.pose().translate(vx + vs / 2f, vy + vs / 2f);
		g.pose().rotate(spin);
		int as = vs / 2 - 4;
		if (Music.active()) Compat.blitTint(g, dev.kyoto.music.AlbumArt.texture(), -as / 2, -as / 2, as, as, Anim.argb(0xFFFFFF, al * f));
		else Compat.blitTint(g, LOGO, -as / 2, -as / 2, as, as, Anim.argb(0xFFFFFF, al * f));
		g.pose().popMatrix();
		int tx = vx + vs + 14;
		Ui.small(g, "NOW PLAYING", tx, y + 14, Anim.argb(Ui.DIM, al * f));
		if (Music.active()) {
			Ui.boldScaled(g, Ui.ellipsize(Music.title, Math.round((cx2 - tx - 10) / 1.2f)), tx, y + 25, 1.2f, Anim.argb(Ui.TEXT, al * f));
			Ui.small(g, Ui.ellipsize(Music.artist, Math.round((cx2 - tx - 10) / 0.75f)), tx, y + 40, Anim.argb(Ui.MUTED, al * f));
		} else {
			Ui.boldScaled(g, "Nothing playing", tx, y + 25, 1.2f, Anim.argb(Ui.TEXT, al * f));
			Ui.small(g, "Play music in Spotify or any app and it shows up here", tx, y + 40, Anim.argb(Ui.MUTED, al * f));
		}
		// equaliser dots
		for (int i = 0; i < 12; i++) {
			float k = Music.active() && Music.playing ? 0.3f + 0.7f * (float) Math.abs(Math.sin(System.currentTimeMillis() / (180.0 + i * 23) + i)) : 0.2f;
			int bh = Math.max(2, Math.round(10 * k));
			Ui.round(g, cx2 - 70 + i * 5, y + 22 - bh, cx2 - 67 + i * 5, y + 22, 1, Anim.argb(accent, (0.4f + 0.6f * k) * al * f));
		}
		String[][] ctl = {{"Previous", "previous"}, {Music.active() && Music.playing ? "Pause" : "Play", "toggle"}, {"Next", "next"}};
		int bx = tx;
		for (String[] c : ctl) {
			int bw = Ui.width(c[0]) + 18;
			boolean hov = inContent(mx, my) && Ui.inside(mx, my, bx, y + 56, bx + bw, y + 74);
			float hv = a("mus" + c[1], hov);
			Ui.button(g, bx, y + 56, bx + bw, y + 74, c[0], c[1].equals("toggle") ? 1f : hv, true, al * f);
			if (inContent(mx, my)) hits.add(new Hit(bx, y + 56, bx + bw, y + 74, b -> Music.control(c[1])));
			bx += bw + 6;
		}
		y += h + 8;
		y = buttons(g, new String[][]{{"lyrics", "Lyrics menu", "@lyrics"}, {"music", "Up next", "Up next"}}, y, mx, my, al, accent);
		List<Module> list = new ArrayList<>(Modules.in(Category.MUSIC));
		list.removeIf(m -> m.pack != null);
		return grid(g, list, y, mx, my, al, accent);
	}

	/* ---------------- Tools + Help ---------------- */

	private static final String[][] TOOLS = {
			{"hud", "Edit HUD", "Drag and resize everything", "Edit HUD"},
			{"waypoint", "Waypoints", "Add, share and send waypoints", "Waypoints"},
			{"hub", "Kyoto Hub", "Chat, DMs, voice rooms", "Kyoto Hub"},
			{"friends", "Friends", "Who's online, join them", "Friends"},
			{"dm", "Messages", "DMs with emojis and pictures", "Messages"},
			{"play", "Live Rooms", "Watch videos together", "Live Rooms"},
			{"bolt", "Macros", "Keys that run commands (with warnings)", "#macros"},
			{"sword", "Knives", "CS2-style knife skins", "#knives"},
			{"crosshair", "Crosshair", "Draw your own crosshair", "Crosshair"},
			{"vfx", "VFX Creator", "Hit, kill and totem effects", "VFX Creator"},
			{"screen", "Video Screens", "Put up a screen, play a video", "Video Screens"},
			{"sky", "Sky + Time", "Custom skies and time of day", "Sky + Time"},
			{"stream", "Streamer", "Donation and TikTok alerts", "Streamer"},
			{"profile", "My Profile", "Your Kyoto profile page", "My Profile"},
			{"finder", "Mod Finder", "Search and install mods", "Mod Finder"},
			{"mods", "Mods", "Turn other mods on/off", "Mods"},
			{"folder", "Kyoto Folder", "Capes, skins, skies", "Kyoto Folder"},
			{"image", "Screenshot Studio", "Screenshots and clips", "Screenshot Studio"},
			{"gift", "Rewards", "Daily streak and challenges", "Rewards"},
			{"world", "Slime chunks", "Slime chunks around you", "Slime chunks"},
	};

	private int tiles(GuiGraphicsExtractor g, List<String[]> l, int y, int mx, int my, float al, int accent) {
		int w = cx2 - cx1, cols = w > 380 ? 3 : 2, gap = 6, cw = (w - gap * (cols - 1)) / cols, ch = 36;
		for (int i = 0; i < l.size(); i++) {
			String[] t = l.get(i);
			float f = stagger(cardIndex++);
			int x = cx1 + (i % cols) * (cw + gap), yy = y + (i / cols) * (ch + gap) + Math.round((1 - f) * 6);
			boolean hov = inContent(mx, my) && Ui.inside(mx, my, x, yy, x + cw, yy + ch);
			float h = a("tile" + t[1], hov);
			int lift = Math.round(h * 1.5f);
			card(g, x, yy - lift, x + cw, yy + ch - lift, al * f, h);
			Ui.round(g, x + 8, yy + 9 - lift, x + 26, yy + 27 - lift, 6, Anim.argb(accent, (0.14f + 0.12f * h) * al * f));
			Ui.iconTex(g, t[0], x + 12, yy + 13 - lift, 10, Anim.argb(accent, al * f));
			Ui.text(g, Ui.ellipsize(t[1], cw - 40), x + 33, yy + 9 - lift, Anim.argb(Ui.TEXT, al * f));
			Ui.small(g, Ui.ellipsize(t[2], Math.round((cw - 40) / 0.75f)), x + 33, yy + 21 - lift, Anim.argb(Ui.MUTED, al * f));
			if (inContent(mx, my)) hits.add(new Hit(x, yy, x + cw, yy + ch, b -> openTool(t[3])));
		}
		return y + ((l.size() + cols - 1) / cols) * (ch + gap) + 4;
	}

	private int buttons(GuiGraphicsExtractor g, String[][] b, int y, int mx, int my, float al, int accent) {
		int x = cx1;
		for (String[] t : b) {
			int bw = Ui.width(t[1]) + 30;
			if (x + bw > cx2) { x = cx1; y += 24; }
			boolean hov = inContent(mx, my) && Ui.inside(mx, my, x, y, x + bw, y + 20);
			float h = a("btn" + t[1], hov);
			Ui.button(g, x, y, x + bw, y + 20, "", h, true, al);
			Ui.iconTex(g, t[0], x + 9, y + 6, 8, Anim.argb(Ui.mix(accent, 0xFFFFFF, h), al));
			Ui.text(g, t[1], x + 21, y + 6, Anim.argb(Ui.mix(Ui.INK, 0xFFFFFF, h), al));
			if (inContent(mx, my)) hits.add(new Hit(x, y, x + bw, y + 20, k -> openTool(t[2])));
			x += bw + 6;
		}
		return y + 28;
	}

	private void openTool(String name) {
		lastPage = page;
		Sounds.play(Sounds.TAB);
		switch (name) {
			case "#theme" -> { select(Page.THEME); return; }
			case "#anim" -> { select(Page.ANIMATIONS); return; }
			case "#knives" -> { Compat.openScreen(new KnivesScreen(this)); return; }
			case "#macros" -> { Compat.openScreen(new MacrosScreen(this)); return; }
			case "#pets" -> { Compat.openScreen(new dev.kyoto.cosmetic.WardrobeScreen(this, "pet")); return; }
			case "#wardrobe" -> { Compat.openScreen(new dev.kyoto.cosmetic.WardrobeScreen(this, "hat")); return; }
			case "@studio" -> { Compat.openUrl(dev.kyoto.net.KyotoNet.baseUrl() + "/studio"); return; }
			case "@overlay" -> { Compat.openUrl(dev.kyoto.net.KyotoNet.baseUrl() + "/overlay/setup"); return; }
			case "@lyrics" -> { Compat.openScreen(new LyricsScreen(this)); return; }
			case "places" -> { Compat.openScreen(new dev.kyoto.servers.PackScreens.Places(this)); return; }
			case "keys" -> { Notifications.push("Key binds", Packs.saveKeys(), Notifications.GOOD, 4000, null, "gear"); return; }
			case "hud", "Edit HUD" -> { Compat.openScreen(new HudEditorScreen(this)); return; }
			case "Kyoto Folder" -> { Compat.openFolder(net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir().resolve("kyoto")); return; }
			case "Screenshot Studio" -> { Compat.openScreen(new dev.kyoto.extra.StudioScreen(this)); return; }
			case "Emotes" -> { Compat.openScreen(new dev.kyoto.extra.FunScreen(this, dev.kyoto.extra.FunScreen.Tab.EMOTES)); return; }
			case "Rewards" -> { Compat.openScreen(new dev.kyoto.extra.FunScreen(this, dev.kyoto.extra.FunScreen.Tab.REWARDS)); return; }
			case "Up next" -> { Compat.openScreen(new dev.kyoto.extra.FunScreen(this, dev.kyoto.extra.FunScreen.Tab.QUEUE)); return; }
			case "Slime chunks" -> { Compat.openScreen(new dev.kyoto.extra.SlimeScreen(this)); return; }
			default -> {
				if (name.startsWith("#wardrobe:")) {
					Compat.openScreen(new dev.kyoto.cosmetic.WardrobeScreen(this, name.substring(10)));
					return;
				}
			}
		}
		Compat.openScreen(ClickGui.toolScreen(name, this));
	}

	private int drawHelp(GuiGraphicsExtractor g, int y, int mx, int my, float al, int accent) {
		String q = query.toLowerCase(Locale.ROOT).trim();
		for (dev.kyoto.ui.Guides.Topic t : dev.kyoto.ui.Guides.TOPICS) {
			if (!q.isEmpty() && !(t.title().toLowerCase(Locale.ROOT).contains(q) || String.join(" ", t.steps()).toLowerCase(Locale.ROOT).contains(q))) continue;
			float f = stagger(cardIndex++);
			int h = 22 + t.steps().length * 10 + 6;
			card(g, cx1, y, cx2, y + h, al * f, 0);
			Ui.round(g, cx1 + 8, y + 7, cx1 + 22, y + 21, 5, Anim.argb(accent, 0.16f * al * f));
			Ui.iconTex(g, t.icon(), cx1 + 10, y + 9, 10, Anim.argb(accent, al * f));
			Ui.text(g, t.title(), cx1 + 28, y + 10, Anim.argb(Ui.TEXT, al * f));
			for (int i = 0; i < t.steps().length; i++) Ui.small(g, t.steps()[i], cx1 + 28, y + 23 + i * 10, Anim.argb(Ui.MUTED, al * f));
			if (!t.link().isBlank() && inContent(mx, my)) hits.add(new Hit(cx2 - 70, y + 4, cx2 - 4, y + 18, b -> Compat.openUrl(t.link())));
			if (!t.link().isBlank()) Ui.small(g, "Full guide ↗", cx2 - 8 - Ui.widthSmall("Full guide ↗"), y + 9, Anim.argb(accent, al * f));
			y += h + 6;
		}
		return y;
	}

	/* ---------------- Search ---------------- */

	private int drawSearch(GuiGraphicsExtractor g, int y, int mx, int my, float al, int accent) {
		String q = query.toLowerCase(Locale.ROOT).trim();
		List<Module> found = new ArrayList<>();
		for (Module m : Modules.all()) {
			String hay = (m.name + " " + m.desc + " " + String.join(" ", m.tags) + " " + m.category.title + " " + (m.pack == null ? "" : Packs.ALL.containsKey(m.pack) ? Packs.ALL.get(m.pack).name() : m.pack)).toLowerCase(Locale.ROOT);
			boolean all = true;
			for (String word : q.split("\\s+")) if (!hay.contains(word)) { all = false; break; }
			if (all) found.add(m);
		}
		List<String[]> tools = new ArrayList<>();
		for (String[] t : TOOLS) if ((t[1] + " " + t[2]).toLowerCase(Locale.ROOT).contains(q)) tools.add(t);
		for (String[] t : START) if ((t[1] + " " + t[2]).toLowerCase(Locale.ROOT).contains(q)) tools.add(new String[]{t[0], t[1], t[2], t[3]});
		if (!tools.isEmpty()) {
			y = section(g, "Tools", y, al);
			y = tiles(g, tools, y, mx, my, al, accent);
		}
		if (!found.isEmpty()) {
			y = section(g, found.size() + " feature" + (found.size() == 1 ? "" : "s"), y, al);
			y = grid(g, found.subList(0, Math.min(45, found.size())), y, mx, my, al, accent);
		}
		int before = y;
		y = section(g, "Help", y, al);
		int after = drawHelp(g, y, mx, my, al, accent);
		y = after == y ? before : after;
		if (found.isEmpty() && tools.isEmpty() && y == before) y = note(g, "Nothing found. Try a simpler word like \"zoom\", \"pet\" or \"cape\".", y, al);
		return y;
	}

	/* ================================================================ "are you sure" for things servers may not allow ================================================================ */

	private Module pendingRisk = null;

	private void riskDialog(GuiGraphicsExtractor g, int mx, int my, float al, int accent) {
		hits.clear();
		g.fill(-20, -20, this.width + 20, this.height + 20, Anim.argb(0x2B1D23, 0.35f * al));
		int bw = 260, bh = 104, bx = this.width / 2 - bw / 2, by = this.height / 2 - bh / 2;
		Ui.glass(g, bx, by, bx + bw, by + bh, 14, al);
		Ui.circle(g, bx + 22, by + 22, 9, Anim.argb(0xF2B84B, al));
		Ui.bold(g, "!", bx + 20, by + 18, Anim.argb(0xFFFFFF, al));
		Ui.bold(g, "Heads up: " + pendingRisk.name, bx + 38, by + 17, Anim.argb(Ui.TEXT, al));
		List<String> lines = wrap(pendingRisk.risk, bw - 28, false);
		for (int i = 0; i < lines.size(); i++) Ui.text(g, lines.get(i), bx + 14, by + 38 + i * 11, Anim.argb(Ui.MUTED, al));
		int y = by + bh - 28;
		boolean h1 = Ui.inside(mx, my, bx + bw - 124, y, bx + bw - 14, y + 18), h2 = Ui.inside(mx, my, bx + 14, y, bx + 84, y + 18);
		Ui.button(g, bx + bw - 124, y, bx + bw - 14, y + 18, "Turn on anyway", h1 ? 1f : 0.75f, true, al);
		Ui.button(g, bx + 14, y, bx + 84, y + 18, "Cancel", h2 ? 0.4f : 0.1f, true, al);
		Module m = pendingRisk;
		hits.add(new Hit(bx + bw - 124, y, bx + bw - 14, y + 18, b -> { m.set(true); pendingRisk = null; Sounds.play(Sounds.ON); }));
		hits.add(new Hit(bx + 14, y, bx + 84, y + 18, b -> { pendingRisk = null; Sounds.play(Sounds.OFF); }));
	}

	/* ================================================================ first-time tour ================================================================ */

	private int tourStep = -1;

	private void maybeOnboarding(GuiGraphicsExtractor g, int mx, int my, float al, int accent) {
		var mods = KyotoConfig.get().modules;
		if (tourStep < 0) {
			if (Boolean.TRUE.equals(mods.get("_menu_tour_done_v2"))) return;
			tourStep = 0;
		}
		String[][] steps = {
				{"Welcome to Kyoto", "Right Shift opens this menu any time.", "Home is your dashboard. Start here has the things people use most."},
				{"The sidebar", "Fight, Look, Extras and Client.", "The numbers show how many things are on in each page."},
				{"Cards", "Click a card to turn it on or off.", "The cog opens its settings. Right-click stars it."},
				{"Make it yours", "Theme changes the whole look.", "Cosmetics has 3D pets, hats, wings and halos."}};
		if (tourStep >= steps.length) {
			mods.put("_menu_tour_done_v2", true);
			KyotoConfig.save();
			return;
		}
		hits.clear();
		g.fill(0, 0, this.width, this.height, Anim.argb(Ui.DARK ? 0x000000 : 0x2B1D23, 0.35f * al));
		int bw = 250, bh = 90, bx = this.width / 2 - bw / 2, by = this.height / 2 - bh / 2;
		Ui.glass(g, bx, by, bx + bw, by + bh, 14, al);
		String[] s = steps[tourStep];
		Ui.small(g, (tourStep + 1) + " / " + steps.length, bx + bw - 28, by + 11, Anim.argb(Ui.DIM, al));
		Ui.bold(g, s[0], bx + 14, by + 12, Anim.argb(accent, al));
		Ui.text(g, s[1], bx + 14, by + 30, Anim.argb(Ui.TEXT, al));
		List<String> lines = wrap(s[2], bw - 28, true);
		for (int i = 0; i < lines.size(); i++) Ui.small(g, lines.get(i), bx + 14, by + 44 + i * 9, Anim.argb(Ui.MUTED, al));
		int nb = bx + bw - 74, ny = by + bh - 24;
		boolean hv = Ui.inside(mx, my, nb, ny, nb + 60, ny + 16);
		Ui.button(g, nb, ny, nb + 60, ny + 16, tourStep == steps.length - 1 ? "Got it" : "Next", hv ? 1f : 0.8f, true, al);
		hits.add(new Hit(nb, ny, nb + 60, ny + 16, b -> tourStep++));
		Ui.small(g, "Skip", bx + 14, ny + 5, Anim.argb(Ui.DIM, al));
		hits.add(new Hit(bx + 10, ny, bx + 44, ny + 16, b -> tourStep = steps.length));
	}

	/* ================================================================ helpers ================================================================ */

	private boolean inContent(int mx, int my) {
		return mx >= cx1 - 4 && mx < cx2 + 4 && my >= cy1 && my < cy2;
	}

	private static List<String> wrap(String s, int maxW, boolean small) {
		List<String> out = new ArrayList<>();
		StringBuilder line = new StringBuilder();
		for (String word : s.split(" ")) {
			String test = line.length() == 0 ? word : line + " " + word;
			int tw = small ? Ui.widthSmall(test) : Ui.width(test);
			if (tw > maxW && line.length() > 0) {
				out.add(line.toString());
				line = new StringBuilder(word);
			} else line = new StringBuilder(test);
		}
		if (line.length() > 0) out.add(line.toString());
		return out;
	}

	private void drawTip(GuiGraphicsExtractor g, int mx, int my, String t) {
		int w = Ui.width(t) + 14;
		int x = Math.min(mx + 10, this.width - w - 4), y = my - 20;
		Ui.shadow(g, x, y, x + w, y + 15, 7, 0.4f);
		Ui.round(g, x, y, x + w, y + 15, 7, Anim.argb(Ui.GLASS, 0.98f));
		Ui.ring(g, x, y, x + w, y + 15, 7, 1, Anim.argb(0xFFFFFF, Ui.DARK ? 0.12f : 0.9f));
		Ui.text(g, t, x + 7, y + 4, Anim.argb(Ui.TEXT, 1f));
	}

	private double lastMx = 0;

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		int[] um = unmap(event.x(), event.y());
		double mx = um[0], my = um[1];
		lastMx = mx;
		if (closingAt < 0) {
			for (int i = hits.size() - 1; i >= 0; i--) {
				Hit h = hits.get(i);
				if (Ui.inside(mx, my, h.x1, h.y1, h.x2, h.y2)) {
					if (search != null) search.setFocused(false);
					h.action.accept(event.button());
					return true;
				}
			}
		}
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
		if (event.key() == 256 && !query.isBlank()) {
			clearSearch();
			scrollTarget = 0;
			return true;
		}
		if ((event.key() == 257 || event.key() == 335) && search.isFocused()) {
			search.setFocused(false);
			setFocused(null);
			return true;
		}
		return super.keyPressed(event);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		// a glide per notch; a little give past the ends that springs back
		scrollTarget = Math.max(-28, Math.min(maxScroll + 28, scrollTarget - (float) scrollY * 46));
		return true;
	}
}
