package dev.kyoto.servers;

import com.google.gson.JsonObject;
import dev.kyoto.config.KyotoConfig;
import dev.kyoto.feature.Warnings;
import dev.kyoto.hud.HudLayout;
import dev.kyoto.module.Modules;
import dev.kyoto.util.Anim;
import dev.kyoto.util.Ui;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Server pack HUD cards. Every card can be moved and resized in the HUD editor like the rest of the HUD.
 * Default spots: right side, under the notifications, stacked so nothing overlaps.
 */
public final class PackHud {
	private PackHud() {}

	static {
		HudLayout.NAMES.put("pack_money", "Server: Money + Shards");
		HudLayout.NAMES.put("pack_combat", "Server: Combat Timer");
		HudLayout.NAMES.put("pack_bounty", "Server: Bounties");
		HudLayout.NAMES.put("pack_orders", "Server: My Orders");
		HudLayout.NAMES.put("pack_team", "Server: Team");
		HudLayout.NAMES.put("pack_game", "Server: Game Info");
		HudLayout.NAMES.put("pack_stats", "Server: Player Stats");
		HudLayout.NAMES.put("pack_party", "Server: Party");
		HudLayout.NAMES.put("pack_hearts", "Server: Hearts + Supplies");
		HudLayout.NAMES.put("pack_feed", "Server: Fight Feed");
		HudLayout.NAMES.put("pack_elo", "Server: Elo + Matches");
		HudLayout.NAMES.put("pack_mcc", "Server: Events + Quests");
		HudLayout.NAMES.put("pack_smp", "Server: Balance + Mail");
		HudLayout.NAMES.put("pack_big_money", "Server: Big Money Counter");
	}

	private static final int W = 132;

	public static void render(GuiGraphicsExtractor g, int w, int h, float a) {
		if (Packs.current == null || !Modules.on("hud_master")) return;
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer p = mc.player;
		if (p == null) return;
		float x = w - W - 4, y = 70; // under the notifications
		y = card(g, "pack_money", x, y, PackHud::money, a);
		y = card(g, "pack_bounty", x, y, PackHud::bounty, a);
		y = card(g, "pack_orders", x, y, PackHud::orders, a);
		y = card(g, "pack_team", x, y, PackHud::team, a);
		y = card(g, "pack_game", x, y, PackHud::game, a);
		y = card(g, "pack_stats", x, y, PackHud::stats, a);
		y = card(g, "pack_party", x, y, PackHud::party, a);
		y = card(g, "pack_hearts", x, y, PackHud::hearts, a);
		y = card(g, "pack_feed", x, y, PackHud::feed, a);
		y = card(g, "pack_elo", x, y, PackHud::elo, a);
		y = card(g, "pack_mcc", x, y, PackHud::mcc, a);
		card(g, "pack_smp", x, y, PackHud::smp, a);
		combat(g, w, h, a);
		bigMoney(g, w, h, a);
		recap(g, w, h, a);
		buildLimit(g, w, h, a);
		spawnBorder(g, w, h, a);
	}

	private interface Card {
		/** draws at (x, y) local coords, returns height (0 = nothing to show) */
		int draw(GuiGraphicsExtractor g, int x, int y, float a);
	}

	private static float card(GuiGraphicsExtractor g, String id, float x, float y, Card c, float a) {
		HudLayout.begin(g, id, 0, 0, x, y, 1f);
		int hh = c.draw(g, 0, 0, a);
		HudLayout.end(g, W, Math.max(hh, HudLayout.editing ? 20 : 0));
		if (hh == 0 && HudLayout.editing) {
			HudLayout.begin(g, id, 0, 0, x, y, 1f);
			Ui.hud(g, 0, 0, W, 20, 6, a * 0.6f);
			Ui.small(g, HudLayout.NAMES.get(id), 6, 7, Anim.argb(Ui.MUTED, a));
			HudLayout.end(g, W, 20);
			hh = 20;
		}
		return HudLayout.moved(id) || hh == 0 ? y : y + hh + 4;
	}

	/* ---------------- shared bits ---------------- */

	private static int head(GuiGraphicsExtractor g, int x, int y, int h, String icon, String title, float a) {
		Ui.hud(g, x, y, x + W, y + h, 6, a);
		return Ui.hudTitle(g, icon, title, x + 6, y + 5, Ui.accent(), a) + 1;
	}

	private static void kv(GuiGraphicsExtractor g, int x, int y, String k, String v, int vc, float a) {
		Ui.text(g, k, x + 6, y, Anim.argb(Ui.MUTED, a));
		Ui.text(g, v, x + W - 6 - Ui.width(v), y, Anim.argb(vc, a));
	}

	private static final int GOOD = 0x6EE7B7, BAD = 0xFF6B6B, GOLD = 0xFFD36B;

	/* ---------------- cards ---------------- */

	private static int money(GuiGraphicsExtractor g, int x, int y, float a) {
		if (!Packs.on("donut_money") && !Packs.on("donut_shards")) return 0;
		List<String[]> rows = new ArrayList<>();
		double sess = ServerData.sessionMoney(), ph = ServerData.moneyPerHour();
		if (Packs.on("donut_money")) {
			if (ServerData.money >= 0) rows.add(new String[]{"Balance", Packs.fmt(ServerData.money), String.valueOf(Ui.TEXT)});
			rows.add(new String[]{"This session", (sess >= 0 ? "+" : "") + Packs.fmt(sess), String.valueOf(sess >= 0 ? GOOD : BAD)});
			if (ph != 0) rows.add(new String[]{"Per hour", Packs.fmt(ph), String.valueOf(GOOD)});
		}
		if (Packs.on("donut_shards") && ServerData.shards >= 0) {
			rows.add(new String[]{"Shards", Packs.num(ServerData.shards) + (ServerData.sessionShards() > 0 ? "  +" + Packs.num(ServerData.sessionShards()) : ""), String.valueOf(0x9AD8FF)});
			if (Donut.shardInterval > 0) {
				long left = Math.max(0, Donut.shardInterval - (System.currentTimeMillis() - Donut.lastShardAt)) / 1000;
				rows.add(new String[]{"Next shards", left / 60 + ":" + String.format("%02d", left % 60), String.valueOf(0x9AD8FF)});
			}
		}
		if (Packs.on("donut_multiplier") && Donut.multiplier > 0) rows.add(new String[]{"Sell multiplier", String.format("%.2fx", Donut.multiplier), String.valueOf(GOLD)});
		if (Packs.on("donut_afk") && Donut.afkSince > 0) {
			long m = (System.currentTimeMillis() - Donut.afkSince) / 60000;
			rows.add(new String[]{"AFK zone", m + "m · +" + Packs.num(Donut.afkShards), String.valueOf(0x9AD8FF)});
		}
		if (rows.isEmpty()) return 0;
		int hh = 18 + rows.size() * 11 + 3;
		int yy = head(g, x, y, hh, "coin", Packs.current.name() + " · " + ServerData.sessionTime(), a);
		for (String[] r : rows) {
			kv(g, x, yy, r[0], r[1], Integer.parseInt(r[2]), a);
			yy += 11;
		}
		return hh;
	}

	private static int bounty(GuiGraphicsExtractor g, int x, int y, float a) {
		if (!Packs.on("donut_bounty_board")) return 0;
		List<Object[]> top = Donut.topBounties(5);
		if (top.isEmpty() && Donut.myBounty <= 0) return 0;
		int hh = 18 + top.size() * 11 + (Donut.myBounty > 0 ? 13 : 0) + 3;
		int yy = head(g, x, y, hh, "flag", "Bounties", a);
		if (Donut.myBounty > 0) {
			Ui.round(g, x + 4, yy - 1, x + W - 4, yy + 10, 3, Anim.argb(BAD, 0.22f * a));
			kv(g, x, yy + 1, "On you!", Packs.fmt(Donut.myBounty), BAD, a);
			yy += 13;
		}
		for (Object[] b : top) {
			kv(g, x, yy, Ui.ellipsize((String) b[0], 70), Packs.fmt((double) b[1]), GOLD, a);
			yy += 11;
		}
		return hh;
	}

	private static int orders(GuiGraphicsExtractor g, int x, int y, float a) {
		if (!Packs.on("donut_orders") || Donut.MY_ORDERS.isEmpty()) return 0;
		int n = Math.min(5, Donut.MY_ORDERS.size());
		int hh = 18 + n * 13 + 3;
		int yy = head(g, x, y, hh, "coin", "My orders", a);
		int i = 0;
		for (Map.Entry<String, int[]> e : Donut.MY_ORDERS.entrySet()) {
			if (i++ >= n) break;
			int[] v = e.getValue();
			float f = v[1] <= 0 ? 0 : Math.min(1, v[0] / (float) v[1]);
			Ui.text(g, Ui.ellipsize(e.getKey(), 80), x + 6, yy, Anim.argb(Ui.TEXT, a));
			String pct = Math.round(f * 100) + "%";
			Ui.text(g, pct, x + W - 6 - Ui.width(pct), yy, Anim.argb(f >= 1 ? GOOD : Ui.MUTED, a));
			g.fill(x + 6, yy + 10, x + W - 6, yy + 11, Anim.argb(Ui.INK, 0.08f * a));
			g.fill(x + 6, yy + 10, x + 6 + Math.round((W - 12) * f), yy + 11, Anim.argb(f >= 1 ? GOOD : Ui.accent(), a));
			yy += 13;
		}
		return hh;
	}

	private static int team(GuiGraphicsExtractor g, int x, int y, float a) {
		if (!Packs.on("donut_team")) return 0;
		Minecraft mc = Minecraft.getInstance();
		if (mc.getConnection() == null || mc.player == null) return 0;
		var myTeam = mc.player.getTeam();
		List<String> mates = new ArrayList<>();
		if (myTeam != null) for (var info : mc.getConnection().getOnlinePlayers()) {
			if (info.getTeam() == myTeam) {
				String n = dev.kyoto.util.Compat.profileName(info.getProfile());
				if (n != null && !n.equals(dev.kyoto.util.Compat.myName())) mates.add(n);
			}
		}
		String teamName = ServerData.team;
		if (mates.isEmpty() && (teamName == null || teamName.isEmpty())) return 0;
		int n = Math.min(6, mates.size());
		int hh = 18 + Math.max(1, n) * 11 + 3;
		int yy = head(g, x, y, hh, "team", teamName == null || teamName.isEmpty() ? "Team" : "Team · " + teamName, a);
		if (mates.isEmpty()) Ui.text(g, "Nobody else online", x + 6, yy, Anim.argb(Ui.MUTED, a));
		for (int i = 0; i < n; i++) {
			g.fill(x + 7, yy + 3, x + 10, yy + 6, Anim.argb(GOOD, a));
			Ui.text(g, mates.get(i), x + 14, yy, Anim.argb(Ui.TEXT, a));
			yy += 11;
		}
		return hh;
	}

	private static int game(GuiGraphicsExtractor g, int x, int y, float a) {
		if (!Packs.is("hypixel")) return 0;
		List<String[]> rows = new ArrayList<>();
		String title = "Hypixel";
		switch (Hypixel.game) {
			case BEDWARS -> {
				title = "Bed Wars";
				if (Packs.on("hypixel_gens")) for (var e : Hypixel.GENS.entrySet()) rows.add(new String[]{e.getKey(), e.getValue() + "s", String.valueOf(e.getKey().equals("Diamond") ? 0x7FE7FF : GOOD)});
				if (Packs.on("hypixel_beds") && !Hypixel.BEDS.isEmpty()) {
					StringBuilder sb = new StringBuilder();
					for (var e : Hypixel.BEDS.entrySet()) sb.append(e.getKey().charAt(0)).append(e.getValue().matches("✔|✓") ? "✔ " : e.getValue().matches("✘|✗") ? "✘ " : e.getValue() + " ");
					rows.add(new String[]{"Beds", sb.toString().trim(), String.valueOf(Ui.TEXT)});
				}
				if (Packs.on("hypixel_kills")) rows.add(new String[]{"Kills · Finals · Beds", Hypixel.kills + " · " + Hypixel.finals + " · " + Hypixel.beds, String.valueOf(GOLD)});
				if (Packs.on("hypixel_upgrades")) for (String u : Hypixel.UPGRADES) rows.add(new String[]{"✓", Ui.ellipsize(u, 100), String.valueOf(GOOD)});
			}
			case SKYBLOCK -> {
				title = "SkyBlock";
				if (!Packs.on("hypixel_skyblock")) return 0;
				if (Hypixel.purse >= 0) rows.add(new String[]{"Purse", Packs.num(Hypixel.purse), String.valueOf(GOLD)});
				if (Hypixel.bank >= 0) rows.add(new String[]{"Bank", Packs.num(Hypixel.bank), String.valueOf(GOLD)});
				double ph = ServerData.moneyPerHour();
				if (ph > 0) rows.add(new String[]{"Coins / hour", Packs.num(ph), String.valueOf(GOOD)});
				if (!Hypixel.slayer.isEmpty()) rows.add(new String[]{"Slayer", Ui.ellipsize(Hypixel.slayer, 80), String.valueOf(Ui.TEXT)});
				if (!Hypixel.dungeon.isEmpty()) rows.add(new String[]{"Dungeon", Ui.ellipsize(Hypixel.dungeon, 80), String.valueOf(Ui.TEXT)});
			}
			case DUELS -> {
				title = "Duels";
				if (Packs.on("hypixel_kills")) rows.add(new String[]{"Kills", String.valueOf(Hypixel.kills), String.valueOf(GOLD)});
			}
			default -> {
				return 0;
			}
		}
		if (rows.isEmpty()) return 0;
		int hh = 18 + rows.size() * 11 + 3;
		int yy = head(g, x, y, hh, "server", title, a);
		for (String[] r : rows) {
			kv(g, x, yy, r[0], r[1], Integer.parseInt(r[2]), a);
			yy += 11;
		}
		return hh;
	}

	private static int stats(GuiGraphicsExtractor g, int x, int y, float a) {
		if (!Packs.on("hypixel_stats")) return 0;
		boolean duelsCard = !Hypixel.opponent.isEmpty() && System.currentTimeMillis() - Hypixel.opponentAt < 20000;
		List<JsonObject> l;
		if (duelsCard) {
			JsonObject o = Hypixel.STATS.get(Hypixel.opponent.toLowerCase());
			l = o == null ? List.of() : List.of(o);
		} else l = Hypixel.lobbyStats();
		if (l.isEmpty() || Hypixel.game == Hypixel.Game.LOBBY || Hypixel.game == Hypixel.Game.SKYBLOCK) return 0;
		int n = Math.min(6, l.size());
		int hh = 18 + n * 11 + 3;
		int yy = head(g, x, y, hh, "combat", duelsCard ? "Opponent" : "Top players here", a);
		for (int i = 0; i < n; i++) {
			JsonObject s = l.get(i);
			String name = s.get("name").getAsString();
			String v = switch (Hypixel.game) {
				case BEDWARS -> (int) Hypixel.num(s, "bw_star") + "✫ · " + String.format("%.1f", Hypixel.num(s, "bw_fkdr")) + " FKDR";
				case SKYWARS -> (int) Hypixel.num(s, "sw_star") + "✫ · " + String.format("%.1f", Hypixel.num(s, "sw_kdr")) + " KDR";
				case DUELS -> (int) Hypixel.num(s, "duels_wins") + "W · " + String.format("%.1f", Hypixel.num(s, "duels_wlr")) + " WLR";
				default -> "";
			};
			double t = Hypixel.threat(s);
			int col = t > 60 ? BAD : t > 15 ? GOLD : Ui.TEXT;
			Ui.text(g, Ui.ellipsize(name, 52), x + 6, yy, Anim.argb(col, a));
			Ui.text(g, v, x + W - 6 - Ui.width(v), yy, Anim.argb(Ui.MUTED, a));
			yy += 11;
		}
		return hh;
	}

	private static int party(GuiGraphicsExtractor g, int x, int y, float a) {
		if (!Packs.on("hypixel_party") || Hypixel.PARTY.isEmpty()) return 0;
		List<String> l = new ArrayList<>(Hypixel.PARTY);
		int n = Math.min(8, l.size());
		int hh = 18 + n * 11 + 3;
		int yy = head(g, x, y, hh, "friends", "Party (" + l.size() + ")", a);
		for (int i = 0; i < n; i++) {
			Ui.text(g, l.get(i), x + 6, yy, Anim.argb(Ui.TEXT, a));
			yy += 11;
		}
		return hh;
	}

	private static int hearts(GuiGraphicsExtractor g, int x, int y, float a) {
		if (!Packs.on("ls_hearts") && !Packs.on("ls_supplies")) return 0;
		List<String[]> rows = new ArrayList<>();
		if (Packs.on("ls_hearts")) {
			double hNow = Smp.hearts(), diff = Smp.startHearts < 0 ? 0 : hNow - Smp.startHearts;
			rows.add(new String[]{"Hearts", (int) hNow + (diff != 0 ? "  (" + (diff > 0 ? "+" : "") + (int) diff + ")" : ""), String.valueOf(diff < 0 ? BAD : 0xFF7A9C)});
		}
		if (Packs.on("ls_supplies")) {
			Minecraft mc = Minecraft.getInstance();
			int totems = mc.player == null ? 0 : Warnings.countTotems(mc.player);
			rows.add(new String[]{"Totems", String.valueOf(totems), String.valueOf(totems == 0 ? BAD : GOLD)});
			rows.add(new String[]{"Pearls · Splash", Smp.pearls() + " · " + Smp.splashPotions(), String.valueOf(Ui.TEXT)});
		}
		int hh = 18 + rows.size() * 11 + 3;
		int yy = head(g, x, y, hh, "heart", "Hearts + supplies", a);
		for (String[] r : rows) {
			kv(g, x, yy, r[0], r[1], Integer.parseInt(r[2]), a);
			yy += 11;
		}
		return hh;
	}

	private static int feed(GuiGraphicsExtractor g, int x, int y, float a) {
		if (!Packs.on("ls_feed") || Smp.FEED.isEmpty()) return 0;
		long now = System.currentTimeMillis();
		List<String[]> l = new ArrayList<>();
		for (String[] f : Smp.FEED) if (now - Long.parseLong(f[1]) < 60000) l.add(f);
		if (l.isEmpty()) return 0;
		int hh = 18 + l.size() * 11 + 3;
		int yy = head(g, x, y, hh, "combat", "Fights nearby", a);
		for (String[] f : l) {
			Ui.text(g, Ui.ellipsize(f[0], W - 12), x + 6, yy, Anim.argb(Ui.TEXT, a));
			yy += 11;
		}
		return hh;
	}

	private static int elo(GuiGraphicsExtractor g, int x, int y, float a) {
		if (!Packs.is("practice") || !Packs.on("practice_elo")) return 0;
		List<String> hist = Packs.on("practice_history") ? Minis.history(3) : List.of();
		int hh = 18 + 11 + hist.size() * 11 + (Minis.kit.isEmpty() ? 0 : 11) + 3;
		int yy = head(g, x, y, hh, "star", "Ranked", a);
		kv(g, x, yy, "Elo", Minis.elo < 0 ? "?" : String.valueOf(Minis.elo), GOLD, a);
		yy += 11;
		if (!Minis.kit.isEmpty()) {
			String[] lay = Minis.kitLayout();
			kv(g, x, yy, "Kit", Minis.kit + (lay != null ? " (layout saved)" : ""), Ui.TEXT, a);
			yy += 11;
		}
		for (String s : hist) {
			Ui.text(g, Ui.ellipsize(s, W - 12), x + 6, yy, Anim.argb(s.startsWith("W") ? GOOD : BAD, a));
			yy += 11;
		}
		return hh;
	}

	private static int mcc(GuiGraphicsExtractor g, int x, int y, float a) {
		if (!Packs.is("mcc")) return 0;
		List<String> rows = new ArrayList<>();
		if (Packs.on("mcc_timers") && !Minis.eventTimer.isEmpty()) rows.add(Minis.eventTimer);
		if (Packs.on("mcc_quests") && !Minis.quest.isEmpty()) rows.add(Minis.quest);
		if (rows.isEmpty()) return 0;
		int hh = 18 + rows.size() * 11 + 3;
		int yy = head(g, x, y, hh, "star", "MCC Island", a);
		for (String r : rows) {
			Ui.text(g, Ui.ellipsize(r, W - 12), x + 6, yy, Anim.argb(Ui.TEXT, a));
			yy += 11;
		}
		return hh;
	}

	private static int smp(GuiGraphicsExtractor g, int x, int y, float a) {
		List<String[]> rows = new ArrayList<>();
		if (Packs.on("smp_balance") && Smp.balance >= 0 && ServerData.money < 0) rows.add(new String[]{"Balance", Packs.fmt(Smp.balance), String.valueOf(GOLD)});
		if (Packs.on("smp_mail") && Smp.unreadMail > 0) rows.add(new String[]{"Mail", Smp.unreadMail + " unread", String.valueOf(0x7FB8FF)});
		if (Packs.on("smp_afk_warn") && System.currentTimeMillis() - Smp.lastMove > 60000) {
			long idle = (System.currentTimeMillis() - Smp.lastMove) / 60000;
			rows.add(new String[]{"Idle", idle + " min", String.valueOf(idle >= new double[]{10, 15, 20, 30}[Math.min(3, dev.kyoto.module.Modules.opt("afkkick.min"))] - 3 ? BAD : Ui.MUTED)});
		}
		if (rows.isEmpty()) return 0;
		int hh = 18 + rows.size() * 11 + 3;
		int yy = head(g, x, y, hh, "world", Packs.serverLabel, a);
		for (String[] r : rows) {
			kv(g, x, yy, r[0], r[1], Integer.parseInt(r[2]), a);
			yy += 11;
		}
		return hh;
	}

	/* ---------------- centre widgets ---------------- */

	private static void combat(GuiGraphicsExtractor g, int w, int h, float a) {
		if (!Packs.on("donut_combat")) return;
		long left = Donut.combatUntil - System.currentTimeMillis();
		if (left <= 0 && !HudLayout.editing) return;
		float f = Math.max(0, Math.min(1, left / 15000f));
		String t = left > 0 ? "In combat · " + (left + 999) / 1000 + "s" : "Combat timer";
		int bw = 120;
		HudLayout.begin(g, "pack_combat", 0, 0, w / 2f - bw / 2f, h - 76, 1f);
		Ui.hud(g, 0, 0, bw, 18, 6, a);
		g.fill(4, 13, bw - 4, 15, Anim.argb(Ui.INK, 0.08f * a));
		g.fill(4, 13, 4 + Math.round((bw - 8) * f), 15, Anim.argb(BAD, a));
		Ui.text(g, t, bw / 2 - Ui.width(t) / 2, 3, Anim.argb(BAD, a));
		HudLayout.end(g, bw, 18);
	}

	private static void bigMoney(GuiGraphicsExtractor g, int w, int h, float a) {
		if (!Packs.on("donut_big_money")) return;
		double v = ServerData.sessionMoney();
		String t = (v >= 0 ? "+" : "") + Packs.fmt(v);
		HudLayout.begin(g, "pack_big_money", 0, 0, w / 2f - 70, 26, 1f);
		Ui.hud(g, 0, 0, 140, 34, 8, a);
		Ui.small(g, "MADE THIS VIDEO", 70 - Ui.widthSmall("MADE THIS VIDEO") / 2, 4, Anim.argb(Ui.MUTED, a));
		Ui.boldScaled(g, t, 70 - Ui.widthBold(t) * 1.5f / 2, 14, 1.5f, Anim.argb(v >= 0 ? GOOD : BAD, a));
		HudLayout.end(g, 140, 34);
	}

	private static void recap(GuiGraphicsExtractor g, int w, int h, float a) {
		if (Hypixel.recap == null) return;
		long age = System.currentTimeMillis() - Hypixel.recapAt;
		if (age > 9000) return;
		float al = a * Math.min(1, Math.min(age / 300f, (9000 - age) / 600f));
		String[] r = Hypixel.recap;
		int bw = 200, bh = 46, x = w / 2 - bw / 2, y = h / 2 - 80;
		Ui.hud(g, x, y, x + bw, y + bh, 8, al);
		Ui.bold(g, r[0] + " recap", x + 8, y + 6, Anim.argb(Ui.accent(), al));
		Ui.text(g, r[4], x + bw - 8 - Ui.width(r[4]), y + 6, Anim.argb(Ui.MUTED, al));
		int cx = x + 8;
		for (int i = 1; i <= 3; i++) {
			Ui.round(g, cx, y + 20, cx + 58, y + 38, 5, Anim.argb(Ui.INK, 0.06f * al));
			Ui.text(g, r[i], cx + 29 - Ui.width(r[i]) / 2, y + 25, Anim.argb(GOLD, al));
			cx += 62;
		}
	}

	private static void buildLimit(GuiGraphicsExtractor g, int w, int h, float a) {
		if (!Packs.on("hypixel_height") || Hypixel.buildLimitY == Integer.MIN_VALUE || Hypixel.game != Hypixel.Game.BEDWARS) return;
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) return;
		int dy = Hypixel.buildLimitY - mc.player.getBlockY();
		if (dy > 8) return;
		String t = dy <= 0 ? "At build limit" : dy + " blocks to build limit";
		Ui.text(g, t, w / 2 - Ui.width(t) / 2, h / 2 + 14, Anim.argb(dy <= 1 ? BAD : GOLD, a));
	}

	private static void spawnBorder(GuiGraphicsExtractor g, int w, int h, float a) {
		if (!Packs.on("ls_spawn_border")) return;
		var n = KyotoConfig.get().packNums;
		Double r = n.get("sb_r:" + Packs.host);
		if (r == null || r <= 0) return;
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) return;
		double dx = mc.player.getX() - n.get("sb_x:" + Packs.host), dz = mc.player.getZ() - n.get("sb_z:" + Packs.host);
		double d = Math.sqrt(dx * dx + dz * dz) - r;
		if (Math.abs(d) > 30) return;
		String t = d < 0 ? "Spawn protection · " + (int) -d + " blocks to the edge" : "PvP zone · " + (int) d + " blocks from spawn";
		Ui.hud(g, w / 2 - Ui.width(t) / 2 - 6, h - 96, w / 2 + Ui.width(t) / 2 + 6, h - 84, 5, a);
		Ui.text(g, t, w / 2 - Ui.width(t) / 2, h - 94, Anim.argb(d < 0 ? GOOD : BAD, a));
	}
}
