package dev.kyoto.hud;

import dev.kyoto.feature.PickupFeed;
import dev.kyoto.feature.Performance;
import dev.kyoto.feature.Warnings;
import dev.kyoto.feature.Waypoints;
import dev.kyoto.feature.WorldFx;
import dev.kyoto.feature.Zoom;
import dev.kyoto.module.Modules;
import dev.kyoto.music.LyricsHud;
import dev.kyoto.util.Anim;
import dev.kyoto.util.Compat;
import dev.kyoto.util.Ui;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.List;

/** Draws every Kyoto HUD element. Left-side widgets stack automatically so nothing overlaps. */
public final class KyotoHud {
	private KyotoHud() {}

	private static long lastHitMs = 0;
	private static float hudFade = 1f;
	private static final Deque<Long> LEFT_CLICKS = new ArrayDeque<>();
	private static final Deque<Long> RIGHT_CLICKS = new ArrayDeque<>();
	private static boolean lastAttack, lastUse;
	private static final float[] KEY_ANIM = new float[7];

	// speedometer (updated each tick)
	private static Vec3 lastPos = null;
	private static double speedBps = 0;

	public static void onHit() {
		lastHitMs = System.currentTimeMillis();
		Crosshair.onHit();
	}

	public static double speed() {
		return speedBps;
	}

	public static void tick(Minecraft mc) {
		if (mc.player == null) {
			lastPos = null;
			return;
		}
		Vec3 now = mc.player.position();
		if (lastPos != null) {
			double dx = now.x - lastPos.x, dz = now.z - lastPos.z;
			double inst = Math.sqrt(dx * dx + dz * dz) * 20;
			speedBps += (inst - speedBps) * 0.3;
		}
		lastPos = now;
	}

	/** When the HUD last drew (the F1 hook uses this to know if it has to draw the lyrics itself). */
	public static volatile long lastExtractNanos = 0;

	public static void extract(GuiGraphicsExtractor g, DeltaTracker delta) {
		lastExtractNanos = System.nanoTime();
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer p = mc.player;
		if (p == null) return;
		int w = mc.getWindow().getGuiScaledWidth();
		int h = mc.getWindow().getGuiScaledHeight();
		float partial = delta.getGameTimeDeltaPartialTick(false);
		trackClicks(mc);

		// World-anchored things first so the HUD sits on top of them
		dev.kyoto.screens.ScreenRenderer.render(g, mc, w, h, partial);
		if (Modules.on("waypoint_labels")) WaypointLabels.render(g, w, h, partial);
		dev.kyoto.extra.Extras.renderWorld(g, mc, w, h, partial);
		Nametags.render(g, mc, w, h, partial);
		LyricsHud.render(g, mc, w, h, partial);

		if (Modules.on("damage_flash")) damageFlash(g, p, w, h, partial);
		if (Modules.on("low_health_pulse")) lowHealthPulse(g, p, w, h);
		if (Zoom.isZooming() && Modules.opt("zoom.overlay") == 0) zoomVignette(g, w, h);
		String zl = Zoom.scrollLabel();
		if (zl != null) {
			int zw = Ui.widthBold(zl) + 14;
			Ui.hud(g, w / 2 - zw / 2, h / 2 + 22, w / 2 + zw / 2, h / 2 + 36, 6, 1f);
			Ui.bold(g, zl, w / 2 - Ui.widthBold(zl) / 2, h / 2 + 25, Anim.argb(Ui.accent(), 1f));
		}

		hudFade += ((Modules.on("hud_master") ? 1f : 0f) - hudFade) * 0.2f;
		int topCenter = 4;
		if (hudFade > 0.02f) {
			float a = hudFade;
			if (Notch.on()) {
				Notch.render(g, w, 0);
				topCenter = 26;
			} else {
				HudLayout.begin(g, "now_playing", w / 2f - 125, 4);
				topCenter = LyricsHud.renderNowPlaying(g, mc, w, a);
				HudLayout.end(g, 250, 22);
				if (HudLayout.moved("now_playing")) topCenter = 4;
			}
			int y = 4;
			if (KyotoF3.visible()) {
				KyotoF3.render(g, mc, p, w, h, partial);
				y = h; // F3 covers the left side
			}
			// left column, with HUD Style scale + opacity
			float hs = Modules.on("hud_style") ? new float[]{0.75f, 0.9f, 1f, 1.1f, 1.25f}[Modules.opt("hud.scale")] : 1f;
			float ha = a * (Modules.on("hud_style") ? new float[]{0.4f, 0.6f, 0.8f, 1f}[Modules.opt("hud.opacity")] : 1f);
			// left column: each one can be moved/resized in the HUD editor; the rest stack under it
			if (y < h) {
				float sy = 4;
				if (Modules.on("info_panel")) sy = left(g, "info_panel", sy, hs, ly -> infoPanel(g, mc, p, ly, ha));
				sy = left(g, "stats", sy, hs, ly -> statsRow(g, mc, ly, ha));
				if (Modules.on("potions")) sy = left(g, "potions", sy, hs, ly -> potions(g, p, ly, ha));
				if (Modules.on("keystrokes")) sy = left(g, "keystrokes", sy, hs, ly -> keystrokes(g, mc, ly, ha));
				if (Modules.on("friends_online")) sy = left(g, "friends_online", sy, hs, ly -> friendsOnline(g, ly, ha));
			}
			if (Modules.on("compass")) {
				HudLayout.begin(g, "compass", w / 2f - 90, topCenter);
				compass(g, p, w, topCenter, a);
				HudLayout.end(g, 180, 16);
			}
			if (Modules.on("armor_hud")) {
				HudLayout.begin(g, "armor", w / 2f - 169, h - 48);
				armor(g, p, w, h, a);
				HudLayout.end(g, 74, 44);
			}
			dev.kyoto.servers.PackHud.render(g, w, h, a);
			dev.kyoto.extra.Extras.renderHud(g, mc, w, h, a);
			if (Modules.on("pickup_feed")) {
				HudLayout.begin(g, "pickup", w - 140, h - 110);
				pickupFeed(g, w, h, a);
				HudLayout.end(g, 136, 60);
			}
		}
		if (Modules.on("elytra_gauge")) {
			HudLayout.begin(g, "elytra", w / 2f - 60, h - 80);
			elytra(g, p, w, h);
			HudLayout.end(g, 120, 30);
		}
		if (Modules.on("pearl_cooldown") || Modules.on("pearl_trajectory")) pearl(g, p, w, h, partial);
		if (Modules.on("hit_marker")) hitMarker(g, w, h);
		if (Modules.on("custom_crosshair")) Crosshair.drawInGame(g, w, h);
		combatHud(g, mc, w, h);
		CombatHud.render(g, mc, w, h, partial);
		FightFx.render(g, mc, w, h, partial);

		dev.kyoto.screens.MiniPlayer.render(g, w, h);
		dev.kyoto.social.Rooms.render(g, w, h);
		dev.kyoto.feature.Fishing.render(g, w, h);
		dev.kyoto.feature.BuildRecorder.renderHud(g, w, h);
		dev.kyoto.stream.StreamAlerts.render(g, w, h);
		dev.kyoto.stream.TikTokLive.render(g, w, h);
		dev.kyoto.extra.Extras.renderLast(g, mc, w, h);
		HudLayout.begin(g, "notifications", w - 170, 4);
		Notifications.render(g, w, vanillaEffectOffset(p));
		HudLayout.end(g, 166, 60);
	}

	/** Draws one left-column element at screen y = sy (its own code draws at y = 4) and returns where the next one goes. */
	private static float left(GuiGraphicsExtractor g, String id, float sy, float hs, java.util.function.IntUnaryOperator draw) {
		HudLayout.begin(g, id, 4, 4, 4, sy, hs);
		int ny = draw.applyAsInt(4);
		HudLayout.end(g, 128, Math.max(8, ny - 4));
		return HudLayout.moved(id) ? sy : sy + (ny - 4) * hs * HudLayout.scale(id);
	}

	/** Vanilla draws effect icons in the top-right; push our toasts below them. */
	private static int vanillaEffectOffset(LocalPlayer p) {
		boolean good = false, bad = false;
		for (MobEffectInstance e : p.getActiveEffects()) {
			if (!e.showIcon()) continue;
			if (e.getEffect().value().isBeneficial()) good = true;
			else bad = true;
		}
		return (good ? 26 : 0) + (bad ? 26 : 0);
	}

	private static void trackClicks(Minecraft mc) {
		long now = System.currentTimeMillis();
		boolean atk = mc.options.keyAttack.isDown(), use = mc.options.keyUse.isDown();
		if (atk && !lastAttack) LEFT_CLICKS.add(now);
		if (use && !lastUse) RIGHT_CLICKS.add(now);
		lastAttack = atk;
		lastUse = use;
		while (!LEFT_CLICKS.isEmpty() && now - LEFT_CLICKS.peekFirst() > 1000) LEFT_CLICKS.pollFirst();
		while (!RIGHT_CLICKS.isEmpty() && now - RIGHT_CLICKS.peekFirst() > 1000) RIGHT_CLICKS.pollFirst();
	}

	/* ---------------- Info panel ---------------- */

	private static int infoPanel(GuiGraphicsExtractor g, Minecraft mc, LocalPlayer p, int y, float a) {
		String[][] rows = {
				{"XYZ", Modules.on("coords_streamer") || dev.kyoto.feature.Privacy.hideCoords() ? "hidden" : p.getBlockX() + " / " + p.getBlockY() + " / " + p.getBlockZ()},
				{"Facing", facing(p.getYRot())},
				{"Biome", Compat.biomeName(p.blockPosition())},
		};
		int lw = 0, vw = 0;
		for (String[] r : rows) {
			lw = Math.max(lw, Ui.width(r[0]));
			vw = Math.max(vw, Ui.width(r[1]));
		}
		int x = 4, height = rows.length * 12 + 8;
		String[] icons = {"waypoint", "sky", "leaf"};
		boolean iconsOn = Modules.opt("info.labels") == 0;
		int labelW = iconsOn ? 12 : lw + 6;
		Ui.hud(g, x, y, x + labelW + vw + 16, y + height, 6, a);
		for (int i = 0; i < rows.length; i++) {
			int ry = y + 5 + i * 12;
			if (iconsOn) Ui.iconTex(g, icons[i], x + 6, ry, 8, Anim.argb(Ui.accent(), a));
			else Ui.text(g, rows[i][0], x + 7, ry, Anim.argb(Ui.MUTED, a));
			Ui.text(g, rows[i][1], x + 9 + labelW, ry, Anim.argb(Ui.TEXT, a));
		}
		return y + height + 3;
	}

	private static String facing(float yaw) {
		String[] dirs = {"South +Z", "South-West", "West -X", "North-West", "North -Z", "North-East", "East +X", "South-East"};
		return dirs[Math.floorMod(Math.round(Mth.wrapDegrees(yaw) / 45f), 8)];
	}

	/* ---------------- FPS / ping / CPS / clock / speed chips ---------------- */

	private static int statsRow(GuiGraphicsExtractor g, Minecraft mc, int y, float a) {
		List<String[]> chips = new ArrayList<>();
		if (Modules.on("fps")) {
			int fps = mc.getFps();
			String extra = Modules.on("dynamic_rd") ? "  RD " + Performance.currentRenderDistance() : "";
			chips.add(new String[]{fps + " fps" + extra, String.valueOf(fps >= 90 ? Ui.INK : fps >= 45 ? Ui.INK : 0xFF5D5D)});
		}
		if (Modules.on("ping")) {
			int ping = Compat.latency();
			String txt = ping <= 0 ? "Ping --" : ping + " ms";
			chips.add(new String[]{txt, String.valueOf(ping <= 0 ? Ui.MUTED : ping < 80 ? Ui.INK : ping < 180 ? Ui.INK : 0xFF5D5D), "ping", String.valueOf(ping)});
		}
		if (Modules.on("cps")) chips.add(new String[]{LEFT_CLICKS.size() + " | " + RIGHT_CLICKS.size() + " cps", String.valueOf(Ui.TEXT)});
		if (Modules.on("clock")) chips.add(new String[]{LocalTime.now().format(DateTimeFormatter.ofPattern(Modules.opt("clock.format") == 0 ? "HH:mm" : "h:mm a")), String.valueOf(Ui.TEXT)});
		if (Modules.on("speed")) chips.add(new String[]{String.format("%.1f b/s", speedBps), String.valueOf(Ui.TEXT)});
		if (Modules.on("day_counter")) chips.add(new String[]{dev.kyoto.feature.Singleplayer.dayLabel(mc), String.valueOf(Ui.TEXT)});
		if (Modules.on("world_playtime")) chips.add(new String[]{dev.kyoto.feature.Singleplayer.playtime(), String.valueOf(Ui.MUTED)});
		if (Modules.on("saturation")) {
			float sat = mc.player.getFoodData().getSaturationLevel();
			chips.add(new String[]{String.format("Saturation %.1f", sat), String.valueOf(sat < 2 ? Ui.INK : Ui.TEXT)});
		}
		if (Modules.on("session_stats")) {

			chips.add(new String[]{"⛏ " + dev.kyoto.feature.Singleplayer.blocksBroken + "  ⚔ " + dev.kyoto.feature.Singleplayer.kills
					+ "  ☠ " + dev.kyoto.feature.Singleplayer.deaths + "  " + Math.round(dev.kyoto.feature.Singleplayer.distance) + "m", String.valueOf(Ui.TEXT)});
		}
		if (Modules.on("totem_counter")) {
			int t = Warnings.countTotems(mc.player);
			chips.add(new String[]{"Totems " + t, String.valueOf(t == 0 ? 0xFF5D5D : Ui.INK)});
		}
		if (chips.isEmpty()) return y;

		int x = 4;
		for (String[] c : chips) {
			boolean ping = c.length > 2;
			int tw = Ui.width(c[0]) + (ping ? 12 : 0);
			if (x + tw + 12 > 220) {
				x = 4;
				y += 17;
			}
			Ui.hud(g, x, y, x + tw + 12, y + 14, 5, a);
			int col = Integer.parseInt(c[1]);
			Ui.text(g, c[0], x + 6, y + 3, Anim.argb(col, a));
			if (ping) {
				int ms = Integer.parseInt(c[3]);
				int bars = ms <= 0 ? 0 : ms < 80 ? 4 : ms < 150 ? 3 : ms < 250 ? 2 : 1;
				int bx = x + 6 + Ui.width(c[0]) + 3;
				for (int i = 0; i < 4; i++) {
					int bh = 2 + i * 2;
					g.fill(bx + i * 2, y + 11 - bh, bx + i * 2 + 1, y + 11, Anim.argb(i < bars ? col : Ui.SOFT, a));
				}
			}
			x += tw + 15;
		}
		return y + 17;
	}

	/* ---------------- Active potions ---------------- */

	/** Kyoto friends who are online right now, with their head and the server they're on. */
	private static int friendsOnline(GuiGraphicsExtractor g, int y, float a) {
		List<dev.kyoto.net.KyotoNet.Friend> on = new ArrayList<>();
		for (var f : dev.kyoto.net.KyotoNet.FRIENDS) if (f.online()) on.add(f);
		if (on.isEmpty() && Modules.opt("friends_online.empty") == 1) return y;
		int rows = Math.min(on.size(), 6);
		int h = 18 + Math.max(1, rows) * 14 + (on.size() > 6 ? 10 : 0);
		Ui.hud(g, 4, y, 128, y + h, 8, a);
		Ui.iconTex(g, "friends", 10, y + 5, 8, Anim.argb(Ui.accent(), a));
		Ui.bold(g, "Friends", 22, y + 5, Anim.argb(Ui.INK, a));
		String n = on.size() + " online";
		Ui.small(g, n, 122 - Ui.widthSmall(n), y + 6, Anim.argb(Ui.MUTED, a));
		int ry = y + 18;
		if (on.isEmpty()) Ui.small(g, "Nobody online", 10, ry + 3, Anim.argb(Ui.MUTED, a));
		for (int i = 0; i < rows; i++) {
			var f = on.get(i);
			Ui.head(g, f.name(), 10, ry + 1, 10, a);
			Ui.text(g, Ui.ellipsize(f.name(), 56), 24, ry + 2, Anim.argb(Ui.INK, a));
			String sv = f.server() == null || f.server().isBlank() ? "menu" : f.server();
			sv = Ui.ellipsize(sv, 60);
			Ui.small(g, sv, 122 - Ui.widthSmall(sv), ry + 3, Anim.argb(Ui.MUTED, a));
			Ui.circle(g, 19, ry + 10, 2f, Anim.argb(0x4ADE80, a));
			ry += 14;
		}
		if (on.size() > 6) Ui.small(g, "+" + (on.size() - 6) + " more", 10, ry, Anim.argb(Ui.MUTED, a));
		return y + h + 4;
	}

	private static int potions(GuiGraphicsExtractor g, LocalPlayer p, int y, float a) {
		Collection<MobEffectInstance> effects = p.getActiveEffects();
		List<MobEffectInstance> list = new ArrayList<>();
		for (MobEffectInstance e : effects) {
			if (dev.kyoto.feature.Fullbright.isOurs(e)) continue;
			list.add(e);
		}
		if (list.isEmpty()) return y;
		int x = 4;
		for (MobEffectInstance e : list) {
			String name = e.getEffect().value().getDisplayName().getString();
			if (e.getAmplifier() > 0) name += " " + roman(e.getAmplifier() + 1);
			String time;
			boolean blink = false;
			if (e.isInfiniteDuration()) time = "∞";
			else {
				int secs = e.getDuration() / 20;
				time = String.format("%d:%02d", secs / 60, secs % 60);
				blink = secs < 10 && (System.currentTimeMillis() / 250) % 2 == 0;
			}
			int color = e.getEffect().value().getColor() & Ui.INK;
			int w = Ui.width(name) + Ui.width(time) + 26;
			Ui.hud(g, x, y, x + w, y + 14, 5, a);
			Ui.round(g, x + 5, y + 4, x + 11, y + 10, 3, Anim.argb(color, a));
			Ui.text(g, name, x + 15, y + 3, Anim.argb(Ui.TEXT, a));
			Ui.text(g, time, x + w - 6 - Ui.width(time), y + 3, Anim.argb(blink ? 0xFF5D5D : Ui.MUTED, a));
			y += 16;
		}
		return y + 1;
	}

	private static String roman(int n) {
		String[] r = {"I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X"};
		return n >= 1 && n <= 10 ? r[n - 1] : String.valueOf(n);
	}

	/* ---------------- Keystrokes ---------------- */

	private static int keystrokes(GuiGraphicsExtractor g, Minecraft mc, int y, float a) {
		var o = mc.options;
		boolean[] down = {o.keyUp.isDown(), o.keyLeft.isDown(), o.keyDown.isDown(), o.keyRight.isDown(),
				o.keyAttack.isDown(), o.keyUse.isDown(), o.keyJump.isDown()};
		for (int i = 0; i < 7; i++) KEY_ANIM[i] += ((down[i] ? 1f : 0f) - KEY_ANIM[i]) * 0.45f;

		int s = 18, gap = 2, x = 4;
		y += 2;
		key(g, "W", x + s + gap, y, s, s, KEY_ANIM[0], a);
		key(g, "A", x, y + s + gap, s, s, KEY_ANIM[1], a);
		key(g, "S", x + s + gap, y + s + gap, s, s, KEY_ANIM[2], a);
		key(g, "D", x + 2 * (s + gap), y + s + gap, s, s, KEY_ANIM[3], a);
		int yy = y + 2 * (s + gap);
		int full = 3 * s + 2 * gap;
		if (Modules.opt("keystrokes.mouse") == 0) {
			int half = (full - gap) / 2;
			key(g, "LMB " + LEFT_CLICKS.size(), x, yy, half, 14, KEY_ANIM[4], a);
			key(g, "RMB " + RIGHT_CLICKS.size(), x + half + gap, yy, half, 14, KEY_ANIM[5], a);
			yy += 14 + gap;
		}
		if (Modules.opt("keystrokes.space") == 0) {
			key(g, "▬▬", x, yy, full, 9, KEY_ANIM[6], a);
			yy += 9 + gap;
		}
		return yy + 3;
	}

	private static void key(GuiGraphicsExtractor g, String label, int x, int y, int w, int h, float press, float a) {
		int accent = Ui.accent();
		Ui.round(g, x, y, x + w, y + h, 4, Anim.argb(Ui.GLASS, (0.6f - 0.15f * press) * a));
		if (press > 0.02f) Ui.round(g, x, y, x + w, y + h, 4, Anim.argb(accent, 0.55f * press * a));
		int col = press > 0.5f ? Ui.GLASS : Ui.TEXT;
		if (label.startsWith("▬")) {
			g.fill(x + w / 2 - 10, y + h / 2, x + w / 2 + 10, y + h / 2 + 1, Anim.argb(col, a));
		} else {
			Ui.text(g, label, x + w / 2 - Ui.width(label) / 2, y + h / 2 - 4, Anim.argb(col, a));
		}
	}

	/* ---------------- Compass ---------------- */

	private static void compass(GuiGraphicsExtractor g, LocalPlayer p, int screenW, int y, float a) {
		int barW = 200, half = barW / 2, cx = screenW / 2;
		float yaw = Mth.wrapDegrees(p.getYRot());
		float pxPerDeg = barW / 180f;
		Ui.hud(g, cx - half, y, cx + half, y + 14, 5, a);
		g.fill(cx, y + 1, cx + 1, y + 4, Anim.argb(Ui.accent(), a));
		String[] names = {"S", "SW", "W", "NW", "N", "NE", "E", "SE"};
		for (int deg = -180; deg < 180; deg += 15) {
			float diff = Mth.wrapDegrees(deg - yaw);
			if (Math.abs(diff) > 86) continue;
			int x = cx + Math.round(diff * pxPerDeg);
			float edge = 1 - Math.abs(diff) / 90f;
			if (deg % 45 == 0) {
				String n = names[Math.floorMod(deg / 45, 8)];
				int col = n.length() == 1 ? (n.equals("N") ? 0xFF6B6B : Ui.TEXT) : Ui.MUTED;
				Ui.text(g, n, x - Ui.width(n) / 2, y + 3, Anim.argb(col, a * edge));
			} else {
				g.fill(x, y + 9, x + 1, y + 12, Anim.argb(Ui.DIM, a * edge));
			}
		}
		Waypoints.Waypoint tracked = Waypoints.tracked();
		for (Waypoints.Waypoint wp : Waypoints.inCurrentDimension()) {
			double dx = wp.x + 0.5 - p.getX(), dz = wp.z + 0.5 - p.getZ();
			float target = (float) (Mth.atan2(dz, dx) * Mth.RAD_TO_DEG) - 90f;
			float diff = Mth.wrapDegrees(target - yaw);
			boolean isTracked = wp == tracked;
			if (Math.abs(diff) > 86) {
				if (!isTracked) continue;
				diff = Math.copySign(86, diff);
			}
			int x = cx + Math.round(diff * pxPerDeg);
			Ui.round(g, x - 2, y + 15, x + 3, y + 20, 2, Anim.argb(wp.color, a));
			if (isTracked || Math.abs(diff) < 10) {
				String label = wp.name + "  " + Math.round(wp.distanceTo(p.getX(), p.getY(), p.getZ())) + "m";
				int lw = Ui.width(label);
				Ui.hud(g, x - lw / 2 - 4, y + 22, x + lw / 2 + 4, y + 34, 4, a);
				Ui.text(g, label, x - lw / 2, y + 24, Anim.argb(wp.color, a));
			}
		}
	}

	/* ---------------- Armor ---------------- */

	private static final EquipmentSlot[] ARMOR = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};

	private static void armor(GuiGraphicsExtractor g, LocalPlayer p, int w, int h, float a) {
		int x = w / 2 - 91 - 78, y = h - 48, row = 0;
		for (EquipmentSlot slot : ARMOR) {
			ItemStack s = p.getItemBySlot(slot);
			if (s.isEmpty() || !s.isDamageableItem()) continue;
			float d = Warnings.durability(s);
			int yy = y + row * 11;
			boolean blink = d < 0.1f && (System.currentTimeMillis() / 300) % 2 == 0;
			Ui.hud(g, x, yy, x + 74, yy + 10, 3, a * 0.8f);
			Ui.text(g, Warnings.slotName(slot).substring(0, 4), x + 3, yy + 1, Anim.argb(Ui.MUTED, a));
			g.fill(x + 26, yy + 4, x + 50, yy + 6, Anim.argb(0x000000, 0.5f * a));
			g.fill(x + 26, yy + 4, x + 26 + Math.round(24 * d), yy + 6, Anim.argb(Anim.healthColor(d), a));
			String pct = Math.round(d * 100) + "%";
			Ui.text(g, pct, x + 71 - Ui.width(pct), yy + 1, Anim.argb(blink ? Ui.INK : Anim.healthColor(d), a));
			row++;
		}
	}

	/* ---------------- Pickup feed ---------------- */

	private static void pickupFeed(GuiGraphicsExtractor g, int w, int h, float a) {
		long now = System.currentTimeMillis();
		int y = h - 62;
		for (PickupFeed.Entry e : PickupFeed.ENTRIES) {
			long age = now - e.time, since = now - e.born;
			float in = Anim.easeOutCubic(since / 220f);
			float out = age > PickupFeed.LIFETIME - 500 ? 1 - (age - (PickupFeed.LIFETIME - 500)) / 500f : 1f;
			float alpha = Anim.clamp01(in * out) * a;
			String amt = "+" + e.amount;
			int tw = Ui.width(amt + " " + e.name);
			int x = Math.round(w - tw - 12 + (1 - in) * 40);
			Ui.hud(g, x - 5, y - 3, x + tw + 5, y + 10, 4, alpha);
			Ui.text(g, amt, x, y, Anim.argb(Ui.INK, alpha));
			Ui.text(g, " " + e.name, x + Ui.width(amt), y, Anim.argb(Ui.TEXT, alpha));
			y -= 15;
		}
	}

	/* ---------------- Elytra ---------------- */

	private static float elytraShow = 0;

	private static void elytra(GuiGraphicsExtractor g, LocalPlayer p, int w, int h) {
		ItemStack chest = p.getItemBySlot(EquipmentSlot.CHEST);
		boolean flying = p.isFallFlying() && chest.is(Items.ELYTRA);
		elytraShow += ((flying ? 1f : 0f) - elytraShow) * 0.15f;
		if (elytraShow < 0.03f) return;
		int rockets = 0;
		for (int i = 0; i < p.getInventory().getContainerSize(); i++) {
			ItemStack s = p.getInventory().getItem(i);
			if (s.is(Items.FIREWORK_ROCKET)) rockets += s.getCount();
		}
		float dur = Warnings.durability(chest);
		double speed = p.getDeltaMovement().length() * 20;
		float a = elytraShow;
		int x = w / 2 + 100 + Math.round((1 - a) * 30), y = h - 62;
		Ui.hud(g, x, y, x + 100, y + 44, 6, a);
		Ui.bold(g, "Elytra", x + 6, y + 4, Anim.argb(Ui.accent(), a));
		String sp = String.format("%.0f b/s", speed);
		Ui.text(g, sp, x + 94 - Ui.width(sp), y + 4, Anim.argb(Ui.TEXT, a));
		Ui.text(g, "Rockets " + rockets, x + 6, y + 16, Anim.argb(rockets < 4 ? 0xFF5D5D : Ui.TEXT, a));
		String range = "~" + rockets * 55 + "m";
		Ui.text(g, range, x + 94 - Ui.width(range), y + 16, Anim.argb(Ui.MUTED, a));
		Ui.text(g, "Wings " + Math.round(dur * 100) + "%", x + 6, y + 27, Anim.argb(Anim.healthColor(dur), a));
		Ui.round(g, x + 6, y + 38, x + 94, y + 41, 1, Anim.argb(0x000000, 0.6f * a));
		Ui.round(g, x + 6, y + 38, x + 6 + Math.max(2, Math.round(88 * dur)), y + 41, 1, Anim.argb(Anim.healthColor(dur), a));
	}

	/* ---------------- Pearl ---------------- */

	private static void pearl(GuiGraphicsExtractor g, LocalPlayer p, int w, int h, float partial) {
		int cx = w / 2, cy = h / 2;
		if (Modules.on("pearl_cooldown")) {
			float cd = p.getCooldowns().getCooldownPercent(new ItemStack(Items.ENDER_PEARL), partial);
			if (cd > 0) {
				Ui.round(g, cx - 20, cy + 12, cx + 20, cy + 15, 1, Anim.argb(0x000000, 0.55f));
				Ui.round(g, cx - 20, cy + 12, cx - 20 + Math.max(2, Math.round(40 * (1 - cd))), cy + 15, 1, 0xFFBDBDBD);
			}
		}
		if (WorldFx.pearlPreviewActive) {
			String label = WorldFx.pearlLanding != null
					? "Pearl lands " + Math.round(WorldFx.pearlLanding.distanceTo(p.position())) + "m"
					: "Pearl: no landing spot";
			int col = WorldFx.pearlLanding != null ? 0xE0C4FF : 0xFF7777;
			Ui.textShadow(g, label, cx - Ui.width(label) / 2, cy + 19, Anim.argb(col, 0.95f));
		}
	}

	/* ---------------- Combat HUD: combo, reach, target ---------------- */

	private static void combatHud(GuiGraphicsExtractor g, Minecraft mc, int w, int h) {
		long since = System.currentTimeMillis() - dev.kyoto.feature.Combat.lastHitAt;
		int cx = w / 2, cy = h / 2;
		if (since < 2500) {
			float a = since < 2000 ? 1f : 1 - (since - 2000) / 500f;
			int y = cy - 26;
			if (Modules.on("combo_counter") && dev.kyoto.feature.Combat.combo >= 2) {
				String c = dev.kyoto.feature.Combat.combo + " COMBO";
				float pop = Anim.easeOutBack(Math.min(1f, since / 180f));
				g.pose().pushMatrix();
				g.pose().translate(cx + 30, y);
				g.pose().scale(0.8f + 0.2f * pop, 0.8f + 0.2f * pop);
				Ui.bold(g, c, 0, 0, Anim.argb(Ui.accent(), a));
				g.pose().popMatrix();
			}
			if (Modules.on("reach_display")) {
				String r = String.format("%.2f", dev.kyoto.feature.Combat.lastReach) + " blocks";
				Ui.textShadow(g, r, cx + 30, y + 12, Anim.argb(Ui.TEXT, a));
			}
		}
	}

	/* ---------------- Screen effects ---------------- */

	private static void damageFlash(GuiGraphicsExtractor g, LocalPlayer p, int w, int h, float partial) {
		if (p.hurtTime <= 0) return;
		float alpha = Anim.clamp01((p.hurtTime - partial) / 10f) * 0.45f;
		int edge = Anim.argb(0xFF1A1A, alpha), clear = Anim.argb(0xFF1A1A, 0);
		int band = Math.max(20, h / 7);
		g.fillGradient(0, 0, w, band, edge, clear);
		g.fillGradient(0, h - band, w, h, clear, edge);
	}

	private static void lowHealthPulse(GuiGraphicsExtractor g, LocalPlayer p, int w, int h) {
		if (p.isDeadOrDying() || p.getHealth() > 6f) return;
		float pulse = (Mth.sin(System.currentTimeMillis() / 180f) + 1) / 2f;
		float alpha = pulse * ((1 - p.getHealth() / 6f) * 0.5f + 0.15f) * 0.5f;
		int edge = Anim.argb(0xAA0000, alpha), clear = Anim.argb(0xAA0000, 0);
		int band = Math.max(16, h / 9);
		g.fillGradient(0, 0, w, band, edge, clear);
		g.fillGradient(0, h - band, w, h, clear, edge);
		g.fill(0, 0, 3, h, edge);
		g.fill(w - 3, 0, w, h, edge);
	}

	private static void hitMarker(GuiGraphicsExtractor g, int w, int h) {
		long age = System.currentTimeMillis() - lastHitMs;
		if (age > 220) return;
		float a = 1 - age / 220f;
		int cx = w / 2, cy = h / 2, col = Anim.argb(Ui.INK, a);
		int o = 4 + Math.round((1 - a) * 3);
		int len = new int[]{2, 3, 5}[Modules.opt("hitmarker.size")];
		for (int i = 0; i < len; i++) {
			g.fill(cx - o - i, cy - o - i, cx - o - i + 1, cy - o - i + 1, col);
			g.fill(cx + o + i, cy - o - i, cx + o + i + 1, cy - o - i + 1, col);
			g.fill(cx - o - i, cy + o + i, cx - o - i + 1, cy + o + i + 1, col);
			g.fill(cx + o + i, cy + o + i, cx + o + i + 1, cy + o + i + 1, col);
		}
	}

	private static void zoomVignette(GuiGraphicsExtractor g, int w, int h) {
		int edge = Anim.argb(0x000000, 0.35f), clear = Anim.argb(0x000000, 0);
		int band = h / 6;
		g.fillGradient(0, 0, w, band, edge, clear);
		g.fillGradient(0, h - band, w, h, clear, edge);
	}
}
