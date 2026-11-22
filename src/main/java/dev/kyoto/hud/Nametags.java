package dev.kyoto.hud;

import dev.kyoto.module.Modules;
import dev.kyoto.net.KyotoNet;
import dev.kyoto.util.Anim;
import dev.kyoto.util.Compat;
import dev.kyoto.util.Proj;
import dev.kyoto.util.Ui;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/**
 * - Kyoto badge: the Kyoto logo next to the nametag of every player who uses Kyoto.
 * - Show My Nametag: your own nametag (+ badge + health) in third person.
 */
public final class Nametags {
	private Nametags() {}

	private static final Identifier LOGO = Identifier.fromNamespaceAndPath("kyoto", "textures/gui/logo_k.png");
	private static final double NAMETAG_SCALE = 0.025; // blocks per nametag pixel (vanilla)

	private static final net.minecraft.resources.Identifier PLUS_LOGO = net.minecraft.resources.Identifier.fromNamespaceAndPath("kyoto", "textures/font/badge_plus.png");

	public static void render(GuiGraphicsExtractor g, Minecraft mc, int w, int h, float partial) {
		if (mc.level == null || mc.player == null) return;
		boolean badges = Modules.on("kyoto_badges") && Modules.opt("badge.where") != 2;
		boolean own = Modules.on("own_nametag") && !mc.options.getCameraType().isFirstPerson();
		if (!badges && !own) return;
		double focal = Proj.focal(h);
		float bsize = new float[]{6f, 8f, 10f}[Modules.opt("badge.size")];
		for (Player p : mc.level.players()) {
			boolean me = p == mc.player;
			if (me && !own) continue;
			if (!me && (!badges || p.isInvisible() || p.isDiscrete() || p.distanceTo(mc.player) > 64)) continue;
			if (!me && !KyotoNet.isKyoto(p.getUUID().toString()) && !KyotoNet.isKyoto(p.getName().getString())) continue;
			if (!me && !mc.player.hasLineOfSight(p)) continue;
			Vec3 pos = p.getPosition(partial).add(0, p.getBbHeight() + 0.5, 0);
			float[] s = Proj.project(pos, partial, w, h);
			if (s == null || s[2] < 0.5f) continue;
			float px = (float) (NAMETAG_SCALE * focal / s[2]); // screen pixels per nametag pixel
			if (px < 0.15f) continue;
			String name = p.getName().getString();
			net.minecraft.network.chat.Component nick = Nicknames.nametag(p, null);
			int nameW = Ui.font().width(nick != null ? nick : p.getDisplayName());
			if (me) {
				drawOwn(g, p, s[0], s[1], px, badges && Modules.on("kyoto_badges"));
			} else {
				// badge just left of the vanilla nametag
				float half = nameW * px / 2f;
				int size = Math.max(4, Math.round(bsize * px));
				int bx = Math.round(s[0] - half - size - 2 * px), by = Math.round(s[1] - size / 2f - 4 * px + 3 * px);
				boolean plus = KyotoNet.isPlus(p.getUUID().toString()) || KyotoNet.isPlus(name);
				Compat.blitTint(g, plus ? PLUS_LOGO : dev.kyoto.ui.TitleTheme.logo(), bx, by, size, size, Anim.argb(0xFFFFFF, 0.95f));
			}
		}
	}

	private static void drawOwn(GuiGraphicsExtractor g, Player p, float sx, float sy, float px, boolean badge) {
		String name = p.getName().getString();
		boolean glass = Modules.opt("nametag.style") == 0;
		g.pose().pushMatrix();
		g.pose().translate(sx, sy);
		g.pose().scale(px, px);
		int nw = Ui.width(name);
		int bw = badge ? 11 : 0;
		int tw = nw + bw + 8;
		if (glass) Ui.round(g, -tw / 2, -6, tw / 2, 6, 4, Anim.argb(0x000000, 0.45f));
		else g.fill(-tw / 2, -6, tw / 2, 6, Anim.argb(0x000000, 0.25f));
		if (badge) Compat.blitTint(g, KyotoNet.mePlus ? PLUS_LOGO : dev.kyoto.ui.TitleTheme.logo(), -tw / 2 + 3, -4, 8, 8, 0xFFFFFFFF);
		Ui.text(g, name, -tw / 2 + 4 + bw, -4, 0xFFFFFFFF);
		if (Modules.opt("nametag.health") == 0) {
			float hp = Math.max(0, Math.min(1, p.getHealth() / Math.max(1, p.getMaxHealth())));
			g.fill(-20, 8, 20, 10, Anim.argb(0x000000, 0.5f));
			g.fill(-20, 8, -20 + Math.round(40 * hp), 10, Anim.argb(Anim.healthColor(hp), 1f));
		}
		g.pose().popMatrix();
	}
}
