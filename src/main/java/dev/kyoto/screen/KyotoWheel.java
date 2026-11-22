package dev.kyoto.screen;

import dev.kyoto.feature.Dances;
import dev.kyoto.hud.Notifications;
import dev.kyoto.ui.Sounds;
import dev.kyoto.util.Anim;
import dev.kyoto.util.Compat;
import dev.kyoto.util.Ui;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;

/**
 * The Kyoto wheel: hold X (or Z for emotes). The inner ring is categories, the outer ring is what's in the one you
 * point at: your menus, style (wardrobe, knives, auras), emotes, friends, world tools and media.
 * Point and let go. Glass segments with smooth edges, springy hover and a centre that tells you what you're on.
 */
public class KyotoWheel extends Screen {
	private record Item(String name, String icon, String desc, Runnable run) {}

	private record Cat(String name, String icon, int color, List<Item> items) {}

	/* The ring pieces are drawn in code once (smooth edges), so they never show up as missing textures. */
	private static final java.util.Map<String, Compat.StreamTexture> TEX = new java.util.HashMap<>();
	private static final int TS = 384;

	/** kind: "inner" (6 pieces), "outer" (n pieces) or "glow". */
	private static Identifier tex(String kind, int n) {
		String key = kind + n;
		Compat.StreamTexture t = TEX.get(key);
		if (t != null) return t.id;
		byte[] px = new byte[TS * TS * 4];
		double r1 = kind.equals("inner") ? 0.33 : 0.655, r2 = kind.equals("inner") ? 0.6 : 0.98;
		double half = Math.PI / Math.max(1, n), pxs = 2.0 / TS, gap = 5 * pxs / 2 * (512.0 / TS), k = 0.035;
		for (int y = 0; y < TS; y++) {
			for (int x = 0; x < TS; x++) {
				double X = (x + 0.5) / TS * 2 - 1, Y = (y + 0.5) / TS * 2 - 1;
				double R = Math.hypot(X, Y), a;
				if (kind.equals("glow")) {
					double v = Math.max(0, 1 - Math.abs(R - 0.8) / 0.22);
					a = v * v;
				} else {
					double A = Math.atan2(X, -Y);
					double dr = Math.max(r1 - R, R - r2), da = (Math.abs(A) - half) * R + gap;
					double qx = dr + k, qy = da + k;
					double d = Math.hypot(Math.max(qx, 0), Math.max(qy, 0)) + Math.min(Math.max(qx, qy), 0) - k;
					a = Math.max(0, Math.min(1, 0.5 - d / pxs));
				}
				int i = (y * TS + x) * 4;
				px[i] = px[i + 1] = px[i + 2] = (byte) 255;
				px[i + 3] = (byte) Math.round(a * 255);
			}
		}
		t = Compat.streamTexture(Identifier.fromNamespaceAndPath("kyoto", "wheel_" + key), TS, TS);
		t.upload(px);
		TEX.put(key, t);
		return t.id;
	}
	private static int lastCat = 0;

	private final int key;
	private final List<Cat> cats = new ArrayList<>();
	private int cat, hoverItem = -1, hoverCat = -1, lastHoverItem = -1;
	private long openedAt, catAt;
	private final float[] catH = new float[8], itemH = new float[14];
	private boolean picked = false;

	public KyotoWheel(int key, boolean emotes) {
		super(Component.literal("Kyoto wheel"));
		this.key = key;
		build();
		cat = emotes ? 3 : Math.max(0, Math.min(cats.size() - 1, lastCat));
	}

	private static Item open(String name, String icon, String desc, java.util.function.Supplier<Screen> s) {
		return new Item(name, icon, desc, () -> Compat.openScreen(s.get()));
	}

	private void build() {
		Minecraft mc = Minecraft.getInstance();
		cats.add(new Cat("Kyoto", "client", 0xEF6F98, List.of(
				open("Kyoto menu", "home", "Everything in one place", KyotoMenu::create),
				open("Settings", "gear", "How Kyoto behaves", () -> KyotoMenu.openOn(KyotoMenu.Page.SETTINGS)),
				open("Edit HUD", "hud", "Drag your HUD around", () -> new HudEditorScreen(null)),
				open("Theme", "palette", "Colours, dark mode, fonts", () -> KyotoMenu.openOn(KyotoMenu.Page.THEME)),
				open("Animations", "hand", "How your hand moves", () -> KyotoMenu.openOn(KyotoMenu.Page.ANIMATIONS)),
				open("Help", "help", "How things work", () -> KyotoMenu.openOn(KyotoMenu.Page.HELP)))));
		cats.add(new Cat("Style", "cosmetics", 0xC86FEF, List.of(
				open("Wardrobe", "hat", "Hats, horns, halos, wings", () -> new dev.kyoto.cosmetic.WardrobeScreen(null, "hat")),
				open("Knives", "sword", "Karambit, butterfly and more", () -> new KnivesScreen(null)),
				new Item("Inspect knife", "eye", "Show off the one in your hand", dev.kyoto.knife.Viewmodel::inspect),
				open("Auras", "aura", "Particles around you", () -> new dev.kyoto.cosmetic.WardrobeScreen(null, "aura")),
				open("Pets", "paw", "A buddy that follows you", () -> new dev.kyoto.cosmetic.WardrobeScreen(null, "pet")),
				open("Capes & skins", "cape", "Your cape and Kyoto skin", () -> new CosmeticsScreen(null)))));
		List<Item> dn = new ArrayList<>(), em = new ArrayList<>();
		for (int i = 0; i < Dances.IDS.length; i++) {
			final int fi = i;
			(Dances.IS_DANCE[i] ? dn : em).add(new Item(Dances.NAMES[i], Dances.ICONS[i], Dances.IS_DANCE[i] ? "Dance. Moving stops it" : "Emote. Moving stops it", () -> Dances.play(fi)));
		}
		cats.add(new Cat("Dances", "music", 0xFF8A5C, dn));
		cats.add(new Cat("Emotes", "smile", 0xFFB347, em));
		cats.add(new Cat("Friends", "friends", 0x5BC0EB, List.of(
				open("Friends", "friends", "Who's online and where", () -> new FriendsScreen(null)),
				open("Messages", "dm", "Your DMs", () -> KyotoMenu.openOn(KyotoMenu.Page.SOCIAL)),
				open("Kyoto Hub", "hub", "Posts, capes and setups", () -> new HubScreen(null)),
				open("Rooms", "chat", "Voice + chat rooms", () -> new RoomsScreen(null)),
				open("My profile", "profile", "How others see you", () -> new ProfileScreen(null, mc.getUser().getName())))));
		cats.add(new Cat("World", "world", 0x4ADE80, List.of(
				open("Waypoints", "waypoint", "All your saved places", WaypointScreen::new),
				new Item("Mark this spot", "flag", "Save a waypoint here", () -> {
					var w = dev.kyoto.feature.Waypoints.addHere(null, dev.kyoto.feature.Waypoints.Type.MANUAL);
					if (w != null) Notifications.push("Waypoint added: " + w.name, w.x + ", " + w.y + ", " + w.z, w.color, 3500, null, "◎");
				}),
				new Item("Copy coords", "link", "X Y Z to your clipboard", () -> {
					var p = mc.player;
					if (p == null) return;
					String c = p.getBlockX() + " " + p.getBlockY() + " " + p.getBlockZ();
					Compat.copy(c);
					Notifications.push("Copied", c, Notifications.INFO, 2000, null, "check");
				}),
				open("Sky + time", "sky", "Skyboxes and your own time", () -> new SkyScreen(null)),
				open("Screenshot Studio", "image", "Pose, filter, share", () -> new dev.kyoto.extra.StudioScreen(null)),
				open("Video screens", "screen", "Watch things in the world", () -> new ScreensScreen(null)))));
		cats.add(new Cat("Media", "music", 0xFF7A90, List.of(
				new Item("Play / pause", "play", "Your music", () -> dev.kyoto.music.Music.control("toggle")),
				new Item("Next song", "swap", "Skip this one", () -> dev.kyoto.music.Music.control("next")),
				open("Lyrics", "lyrics", "Words, in time", () -> new LyricsScreen(null)),
				open("Media", "music", "Music, streaming, clips", () -> KyotoMenu.openOn(KyotoMenu.Page.MEDIA)))));
	}

	@Override
	protected void init() {
		openedAt = System.currentTimeMillis();
		catAt = openedAt;
		Sounds.play(Sounds.OPEN);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	private float R() {
		return Math.min(width, height) * 0.36f;
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mx, int my, float partial) {
		long now = System.currentTimeMillis();
		float o = Anim.clamp01((now - openedAt) / 320f), al = Anim.easeOutCubic(o), pop = Anim.easeOutBack(o);
		int cx = width / 2, cy = height / 2;
		float R = R() * (0.86f + 0.14f * pop);
		Cat c = cats.get(cat);
		int acc = c.color;
		// backdrop: dim plus a soft glow in the category colour
		g.fillGradient(0, 0, width, height, Anim.argb(0x120A10, 0.28f * al), Anim.argb(0x120A10, 0.42f * al));
		int gr = Math.round(R * 1.55f);
		Compat.blitTint(g, tex("glow", 0), cx - gr, cy - gr, gr * 2, gr * 2, Anim.argb(acc, 0.35f * al));

		// where the mouse points
		double dx = mx - cx, dy = my - cy, dist = Math.hypot(dx, dy) / R;
		double ang = Math.atan2(dx, -dy); // 0 = up, clockwise
		if (ang < 0) ang += Math.PI * 2;
		hoverCat = -1;
		hoverItem = -1;
		int n = c.items.size();
		if (dist > 0.3 && dist < 0.63) {
			int nc = cats.size();
			hoverCat = (int) Math.floor((ang + Math.PI / nc) / (Math.PI * 2 / nc)) % nc;
			if (hoverCat != cat && hoverCat < cats.size()) {
				cat = hoverCat;
				lastCat = cat;
				catAt = now;
				Sounds.play(Sounds.TAB);
				c = cats.get(cat);
				acc = c.color;
				n = c.items.size();
			}
		} else if (dist >= 0.63 && dist < 1.6) {
			hoverItem = (int) Math.floor((ang + Math.PI / n) / (Math.PI * 2 / n)) % n;
		}
		if (hoverItem != lastHoverItem && hoverItem >= 0) Sounds.play(Sounds.TAB);
		lastHoverItem = hoverItem;

		// the glass disc
		int D = Math.round(R * 2.12f);
		Ui.glass(g, cx - D / 2, cy - D / 2, cx + D / 2, cy + D / 2, D / 2, al);

		// inner ring: categories
		int size = Math.round(R * 2);
		int ncat = cats.size();
		for (int i = 0; i < ncat; i++) {
			boolean on = i == cat;
			catH[i] += ((on ? 1f : i == hoverCat ? 0.6f : 0f) - catH[i]) * 0.22f;
			float st = Anim.easeOutBack(Anim.clamp01((now - openedAt - i * 30) / 280f));
			float s = (0.92f + 0.08f * st) * (1 + 0.03f * catH[i]);
			int col = on ? cats.get(i).color : Ui.mix(Ui.GLASS_LIGHT, cats.get(i).color, catH[i] * 0.4f);
			seg(g, tex("inner", ncat), cx, cy, size * s, i * 360f / ncat, Anim.argb(col, (on ? 0.95f : Ui.DARK ? 0.35f : 0.7f) * al * st));
			double a = Math.toRadians(i * 360f / ncat);
			float ir = R * 0.465f;
			int ix = Math.round(cx + (float) Math.sin(a) * ir), iy = Math.round(cy - (float) Math.cos(a) * ir);
			int is = Math.round(R * 0.11f * (1 + 0.15f * catH[i]));
			Ui.iconTex(g, cats.get(i).icon, ix - is / 2, iy - is / 2, is, Anim.argb(on ? 0xFFFFFF : Ui.mix(Ui.MUTED, cats.get(i).color, catH[i]), al * st));
		}

		// outer ring: what's in this category (sweeps in when you switch)
		Identifier OUT = tex("outer", Math.max(3, Math.min(12, n)));
		for (int i = 0; i < n; i++) {
			boolean hv = i == hoverItem;
			itemH[i] += ((hv ? 1f : 0f) - itemH[i]) * 0.25f;
			float st = Anim.easeOutCubic(Anim.clamp01((now - catAt - i * 22) / 220f));
			float s = (0.9f + 0.1f * st) * (1 + 0.045f * itemH[i]);
			float rot = i * 360f / n - (1 - st) * 14f;
			int col = Ui.mix(Ui.DARK ? 0x3A2C33 : 0xFFFFFF, acc, itemH[i]);
			seg(g, OUT, cx, cy, size * s, rot, Anim.argb(col, (0.62f + 0.33f * itemH[i]) * al * st));
			double a = Math.toRadians(i * 360f / n);
			float ir = R * 0.815f * (1 + 0.04f * itemH[i]);
			int ix = Math.round(cx + (float) Math.sin(a) * ir), iy = Math.round(cy - (float) Math.cos(a) * ir);
			Item it = c.items.get(i);
			int is = Math.round(R * 0.12f * (1 + 0.2f * itemH[i]));
			Ui.iconTex(g, it.icon, ix - is / 2, iy - is / 2 - Math.round(R * 0.035f), is, Anim.argb(Ui.mix(acc, 0xFFFFFF, itemH[i]), al * st));
			String lb = Ui.ellipsize(it.name, Math.round(R * 0.32f / 0.75f));
			Ui.small(g, lb, ix - Ui.widthSmall(lb) / 2f, iy + is / 2f - R * 0.01f, Anim.argb(Ui.mix(Ui.INK, 0xFFFFFF, itemH[i]), al * st * (0.75f + 0.25f * itemH[i])));
		}

		// pointer: a little dot riding the hub's edge towards the mouse
		float hubR = R * 0.29f;
		if (dist > 0.15) {
			float px = cx + (float) Math.sin(ang) * (hubR + 4), py = cy - (float) Math.cos(ang) * (hubR + 4);
			Ui.circle(g, px, py, 2.5f, Anim.argb(acc, al));
		}
		// centre hub
		Ui.circle(g, cx, cy, hubR, Anim.argb(Ui.DARK ? 0x2A1F25 : 0xFFFFFF, 0.92f * al));
		Ui.circle(g, cx, cy, hubR - 3, Anim.argb(acc, 0.08f * al));
		if (hoverItem >= 0) {
			Item it = c.items.get(hoverItem);
			int is = Math.round(hubR * 0.5f);
			Ui.iconTex(g, it.icon, cx - is / 2, Math.round(cy - hubR * 0.62f), is, Anim.argb(acc, al));
			String t = Ui.ellipsize(it.name, Math.round(hubR * 1.7f));
			Ui.bold(g, t, cx - Ui.widthBold(t) / 2, cy + 2, Anim.argb(Ui.INK, al));
			String d = Ui.ellipsize(it.desc, Math.round(hubR * 1.75f / 0.75f));
			Ui.small(g, d, cx - Ui.widthSmall(d) / 2f, cy + 14, Anim.argb(Ui.MUTED, al));
		} else {
			Ui.boldScaled(g, c.name, cx - Ui.widthBold(c.name) * 1.2f / 2, cy - 9, 1.2f, Anim.argb(acc, al));
			String d = "Point at one, let go";
			Ui.small(g, d, cx - Ui.widthSmall(d) / 2f, cy + 7, Anim.argb(Ui.MUTED, al));
		}
	}

	/** One ring segment: a white sector texture turned to its angle and tinted. */
	private static void seg(GuiGraphicsExtractor g, Identifier tex, int cx, int cy, float size, float deg, int argb) {
		g.pose().pushMatrix();
		g.pose().translate(cx, cy);
		g.pose().rotate((float) Math.toRadians(deg));
		int s = Math.round(size);
		Compat.blitTint(g, tex, -s / 2, -s / 2, s, s, argb);
		g.pose().popMatrix();
	}

	private void pick() {
		if (picked) return;
		picked = true;
		Item it = hoverItem >= 0 && hoverItem < cats.get(cat).items.size() ? cats.get(cat).items.get(hoverItem) : null;
		onClose();
		if (it != null) {
			Sounds.play(Sounds.SUCCESS);
			Minecraft.getInstance().execute(it.run);
		}
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent e, boolean dbl) {
		if (hoverItem >= 0) pick();
		return true;
	}

	@Override
	public boolean keyReleased(KeyEvent e) {
		if (e.key() == key) {
			pick();
			return true;
		}
		return super.keyReleased(e);
	}
}
