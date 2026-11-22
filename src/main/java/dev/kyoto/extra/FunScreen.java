package dev.kyoto.extra;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.kyoto.hud.Notifications;
import dev.kyoto.music.Spotify;
import dev.kyoto.screen.PanelScreen;
import dev.kyoto.social.Social;
import dev.kyoto.util.Anim;
import dev.kyoto.util.Compat;
import dev.kyoto.util.Ui;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.resources.Identifier;

/**
 * One screen for the fun stuff: Emotes (pop a sticker above your head), Pets (a little buddy that floats next to you),
 * Rewards (daily streak + this week's challenges) and Up next (your Spotify queue).
 */
public class FunScreen extends PanelScreen {
	public enum Tab { EMOTES, PETS, REWARDS, QUEUE }

	private Tab tab;

	public FunScreen(Screen parent, Tab tab) {
		super(switch (tab) {
			case EMOTES -> "Emotes";
			case PETS -> "Pets";
			case REWARDS -> "Rewards";
			case QUEUE -> "Up next";
		}, parent);
		this.tab = tab;
		if (tab == Tab.REWARDS) Rewards.refresh();
		if (tab == Tab.QUEUE) Spotify.fetchQueue();
	}

	@Override
	protected int maxW() { return 420; }

	@Override
	protected int maxH() { return 290; }

	@Override
	protected String icon() {
		return switch (tab) {
			case EMOTES -> "smile";
			case PETS -> "heart";
			case REWARDS -> "gift";
			case QUEUE -> "music";
		};
	}

	@Override
	protected String subtitle() {
		return switch (tab) {
			case EMOTES -> "Click one: it pops up above your head for Kyoto players near you";
			case PETS -> "Your pet floats next to you. Kyoto players near you see it too";
			case REWARDS -> (Rewards.coins >= 0 ? Rewards.coins + " coins · " : "") + (Rewards.streak > 0 ? Rewards.streak + " day streak" : "Log in every day for coins");
			case QUEUE -> Spotify.connected() ? "Click a song to skip to it (Spotify Premium)" : "Connect Spotify first: /kyoto spotify";
		};
	}

	@Override
	protected void build() {
		int x = px + 10, y = py + ph - 26;
		for (Tab t : Tab.values()) {
			String n = switch (t) { case EMOTES -> "Emotes"; case PETS -> "Pets"; case REWARDS -> "Rewards"; case QUEUE -> "Up next"; };
			btn(x, y, 62, (tab == t ? "› " : "") + n, () -> {
				tab = t;
				if (t == Tab.REWARDS) Rewards.refresh();
				if (t == Tab.QUEUE) Spotify.fetchQueue();
			});
			x += 66;
		}
	}

	private int[] grid(int cols, int i, int size, int gap) {
		int w = cols * size + (cols - 1) * gap;
		int x0 = px + pw / 2 - w / 2, y0 = py + 38;
		return new int[]{x0 + (i % cols) * (size + gap), y0 + (i / cols) * (size + gap)};
	}

	@Override
	protected void drawContent(GuiGraphicsExtractor g, int mx, int my, float a) {
		int acc = Ui.accent();
		switch (tab) {
			case EMOTES -> {
				String[] e = Nearby.EMOTES;
				for (int i = 0; i < e.length; i++) {
					int[] p = grid(8, i, 40, 6);
					boolean h = Ui.inside(mx, my, p[0], p[1], p[0] + 40, p[1] + 40);
					Ui.round(g, p[0], p[1], p[0] + 40, p[1] + 40, 8, Anim.argb(h ? acc : Ui.INK, (h ? 0.25f : 0.05f) * a));
					Compat.blitTint(g, Social.sticker(e[i]), p[0] + 4, p[1] + 4 - (h ? 2 : 0), 32, 32, Anim.argb(0xFFFFFF, a));
				}
				Ui.small(g, "Tip: bind the Emotes key in Controls > Kyoto to open this fast", px + 12, py + ph - 40, Anim.argb(Ui.MUTED, a));
			}
			case PETS -> {
				String[] p = Nearby.PETS;
				for (int i = 0; i < p.length; i++) {
					int[] q = grid(5, i, 56, 10);
					boolean sel = p[i].equals(Nearby.myPet()), h = Ui.inside(mx, my, q[0], q[1], q[0] + 56, q[1] + 66);
					Ui.round(g, q[0], q[1], q[0] + 56, q[1] + 66, 8, Anim.argb(sel ? acc : Ui.INK, (sel ? 0.3f : h ? 0.12f : 0.05f) * a));
					if (i == 0) Ui.iconTex(g, "close", q[0] + 20, q[1] + 16, 16, Anim.argb(Ui.MUTED, a));
					else {
						float bob = h ? (float) Math.sin(System.currentTimeMillis() / 150.0) * 2 : 0;
						Compat.blitTint(g, Identifier.fromNamespaceAndPath("kyoto", "textures/pets/" + p[i] + ".png"), q[0] + 8, q[1] + 4 + Math.round(bob), 40, 40, Anim.argb(0xFFFFFF, a));
					}
					String n = i == 0 ? "No pet" : Character.toUpperCase(p[i].charAt(0)) + p[i].substring(1);
					Ui.small(g, n, q[0] + 28 - Ui.widthSmall(n) / 2, q[1] + 54, Anim.argb(sel ? Ui.TEXT : Ui.MUTED, a));
				}
				Ui.small(g, "Name it: /kyoto pet " + (Nearby.myPet().equals("none") ? "fox" : Nearby.myPet()) + " <name>", px + 12, py + ph - 40, Anim.argb(Ui.MUTED, a));
			}
			case REWARDS -> {
				JsonObject w = Rewards.weekly;
				int y = py + 40;
				Ui.bold(g, "This week's challenges", px + 14, y, Anim.argb(Ui.TEXT, a));
				if (w != null && w.has("ends")) {
					long left = (w.get("ends").getAsLong() - System.currentTimeMillis()) / 3600000;
					String t = left > 48 ? (left / 24) + " days left" : left + " hours left";
					Ui.small(g, t, px + pw - 14 - Ui.widthSmall(t), y + 2, Anim.argb(Ui.MUTED, a));
				}
				y += 16;
				if (w == null || !w.has("list")) {
					Ui.text(g, dev.kyoto.net.KyotoNet.loggedIn() ? "Loading..." : "Link your Kyoto account first: /kyoto link", px + 14, y, Anim.argb(Ui.MUTED, a));
					return;
				}
				for (JsonElement e : w.getAsJsonArray("list")) {
					JsonObject c = e.getAsJsonObject();
					boolean done = c.get("done").getAsBoolean();
					double have = c.get("have").getAsDouble(), goal = c.get("goal").getAsDouble();
					Ui.round(g, px + 12, y, px + pw - 12, y + 34, 8, Anim.argb(done ? 0x5BE36B : Ui.INK, (done ? 0.14f : 0.05f) * a));
					Ui.iconTex(g, done ? "check" : "star", px + 20, y + 8, 10, Anim.argb(done ? 0x6EE7A0 : acc, a));
					Ui.text(g, c.get("name").getAsString(), px + 36, y + 6, Anim.argb(Ui.TEXT, a));
					String r = done ? "Done · +50" : "+50 coins";
					Ui.small(g, r, px + pw - 20 - Ui.widthSmall(r), y + 7, Anim.argb(done ? 0x6EE7A0 : Ui.MUTED, a));
					int bx1 = px + 36, bx2 = px + pw - 20;
					g.fill(bx1, y + 22, bx2, y + 25, Anim.argb(Ui.INK, 0.1f * a));
					g.fill(bx1, y + 22, bx1 + (int) Math.round((bx2 - bx1) * Math.min(1, have / goal)), y + 25, Anim.argb(done ? 0x6EE7A0 : acc, a));
					String p = fmt(c.get("key").getAsString(), have) + " / " + fmt(c.get("key").getAsString(), goal);
					Ui.small(g, p, bx2 - Ui.widthSmall(p), y + 27, Anim.argb(Ui.MUTED, a));
					y += 40;
				}
				Ui.small(g, "Do all 3 for a +150 bonus. Progress uploads every 5 minutes.", px + 14, y + 2, Anim.argb(Ui.MUTED, a));
			}
			case QUEUE -> {
				int y = py + 38;
				var q = Spotify.queue;
				if (q.isEmpty()) {
					Ui.text(g, Spotify.connected() ? "Nothing queued (or still loading)" : "Spotify isn't connected", px + 14, y + 4, Anim.argb(Ui.MUTED, a));
					return;
				}
				for (int i = 0; i < Math.min(9, q.size()); i++) {
					boolean h = Ui.inside(mx, my, px + 12, y, px + pw - 12, y + 22);
					Ui.round(g, px + 12, y, px + pw - 12, y + 22, 6, Anim.argb(h ? acc : Ui.INK, (h ? 0.2f : 0.04f) * a));
					Ui.small(g, String.valueOf(i + 1), px + 20, y + 8, Anim.argb(Ui.MUTED, a));
					Ui.text(g, Ui.ellipsize(q.get(i).title(), pw - 160), px + 34, y + 4, Anim.argb(Ui.TEXT, a));
					Ui.small(g, Ui.ellipsize(q.get(i).artist(), Math.round(120 / 0.75f)), px + pw - 20 - Math.min(120, Ui.widthSmall(q.get(i).artist())), y + 8, Anim.argb(Ui.MUTED, a));
					y += 24;
				}
			}
		}
	}

	private static String fmt(String key, double v) {
		return switch (key) {
			case "mins" -> v >= 60 ? String.format("%.1fh", v / 60) : Math.round(v) + "m";
			case "dist" -> v >= 1000 ? String.format("%.1f km", v / 1000) : Math.round(v) + " m";
			default -> String.format("%,d", Math.round(v));
		};
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (super.mouseClicked(event, doubleClick)) return true;
		double mx = event.x(), my = event.y();
		switch (tab) {
			case EMOTES -> {
				for (int i = 0; i < Nearby.EMOTES.length; i++) {
					int[] p = grid(8, i, 40, 6);
					if (Ui.inside(mx, my, p[0], p[1], p[0] + 40, p[1] + 40)) {
						Nearby.emote(Nearby.EMOTES[i]);
						onClose();
						return true;
					}
				}
			}
			case PETS -> {
				for (int i = 0; i < Nearby.PETS.length; i++) {
					int[] q = grid(5, i, 56, 10);
					if (Ui.inside(mx, my, q[0], q[1], q[0] + 56, q[1] + 66)) {
						Nearby.setPet(Nearby.PETS[i], "");
						Notifications.push(i == 0 ? "Pet put away" : "Say hi to your " + Nearby.PETS[i], i == 0 ? "" : "Kyoto players near you can see it", Notifications.GOOD, 2500, null, "heart");
						return true;
					}
				}
			}
			case QUEUE -> {
				int y = py + 38;
				for (int i = 0; i < Math.min(9, Spotify.queue.size()); i++) {
					if (Ui.inside(mx, my, px + 12, y, px + pw - 12, y + 22)) {
						Spotify.skipTo(i);
						Notifications.push("Skipping to", Spotify.queue.get(i).title(), Notifications.INFO, 2000, null, "next");
						return true;
					}
					y += 24;
				}
			}
			default -> {
			}
		}
		return false;
	}
}
