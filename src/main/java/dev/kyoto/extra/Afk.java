package dev.kyoto.extra;

import dev.kyoto.module.Modules;
import dev.kyoto.servers.Packs;
import dev.kyoto.servers.ServerData;
import dev.kyoto.util.Anim;
import dev.kyoto.util.Compat;
import dev.kyoto.util.Ui;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/**
 * AFK screen: when you haven't touched anything for a few minutes the game fades into a calm AFK screen with the
 * time, how long you've been away and your session numbers. Move or press anything to come back.
 */
public final class Afk {
	private Afk() {}

	public static volatile boolean afk = false;
	private static long lastInput = System.currentTimeMillis(), afkSince = 0;
	private static float yaw, pitch, fade = 0;
	private static double x, y, z;

	public static void tick(Minecraft mc) {
		if (mc.player == null) return;
		var p = mc.player;
		boolean moved = Math.abs(p.getYRot() - yaw) > 0.01 || Math.abs(p.getXRot() - pitch) > 0.01 || Math.abs(p.getX() - x) > 0.01 || Math.abs(p.getZ() - z) > 0.01 || Math.abs(p.getY() - y) > 0.3;
		boolean keys = mc.options.keyUp.isDown() || mc.options.keyDown.isDown() || mc.options.keyLeft.isDown() || mc.options.keyRight.isDown() || mc.options.keyJump.isDown()
				|| mc.options.keyAttack.isDown() || mc.options.keyUse.isDown() || Compat.currentScreen() != null;
		yaw = p.getYRot();
		pitch = p.getXRot();
		x = p.getX();
		y = p.getY();
		z = p.getZ();
		long now = System.currentTimeMillis();
		if (moved || keys) lastInput = now;
		int mins = new int[]{2, 5, 10}[Math.max(0, Math.min(2, Modules.opt("afk.after")))];
		boolean now2 = Modules.on("afk_screen") && now - lastInput > mins * 60000L;
		if (now2 && !afk) afkSince = lastInput;
		afk = now2;
	}

	public static void render(GuiGraphicsExtractor g, int w, int h) {
		fade += ((afk ? 1f : 0f) - fade) * 0.06f;
		if (fade < 0.01f) return;
		float a = fade;
		g.fillGradient(0, 0, w, h, Anim.argb(Ui.GLASS, 0.55f * a), Anim.argb(Ui.GLASS, 0.85f * a));
		int acc = Ui.accent();
		String time = LocalTime.now().format(DateTimeFormatter.ofPattern(Modules.opt("clock.format") == 1 ? "h:mm a" : "HH:mm"));
		g.pose().pushMatrix();
		g.pose().translate(w / 2f, h / 2f - 40);
		g.pose().scale(4f, 4f);
		Ui.bold(g, time, -Ui.widthBold(time) / 2, -4, Anim.argb(Ui.INK, a));
		g.pose().popMatrix();
		long away = (System.currentTimeMillis() - afkSince) / 60000;
		String sub = "AFK for " + (away >= 60 ? (away / 60) + "h " + (away % 60) + "m" : away + " min") + " · move to come back";
		Ui.text(g, sub, w / 2 - Ui.width(sub) / 2, h / 2 - 6, Anim.argb(acc, a));
		StringBuilder sb = new StringBuilder();
		if (Packs.current != null) sb.append(Packs.serverLabel).append("  ·  ");
		sb.append("Session ").append(ServerData.sessionTime());
		if (ServerData.money >= 0 || ServerData.sessionMoney() != 0) sb.append("  ·  ").append(ServerData.sessionMoney() >= 0 ? "+" : "").append(Packs.fmt(ServerData.sessionMoney()));
		if (ServerData.moneyPerHour() > 0) sb.append(" (").append(Packs.fmt(ServerData.moneyPerHour())).append("/h)");
		String l = sb.toString();
		Ui.small(g, l, w / 2 - Ui.widthSmall(l) / 2, h / 2 + 10, Anim.argb(Ui.MUTED, a));
		// slow breathing dots
		float t = (System.currentTimeMillis() % 3000) / 3000f;
		for (int i = 0; i < 3; i++) {
			float k = (float) (0.4 + 0.6 * Math.max(0, Math.sin((t - i * 0.15) * Math.PI * 2)));
			g.fill(w / 2 - 10 + i * 8, h / 2 + 26, w / 2 - 7 + i * 8, h / 2 + 29, Anim.argb(acc, k * a));
		}
	}
}
