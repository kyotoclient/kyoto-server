package dev.kyoto.mixin;

import dev.kyoto.util.Anim;
import dev.kyoto.util.Ui;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.LoadingOverlay;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Kyoto loading screen, drawn over Mojang's (whose own logic still runs, so loading finishes normally).
 * A soft pink sky, a sakura blossom that opens petal by petal and breathes, petals drifting down and a pill progress bar.
 * Uses only plain fills (no textures or fonts), because those aren't ready yet while Minecraft starts.
 */
@Mixin(LoadingOverlay.class)
public abstract class LoadingOverlayMixin {
	@Shadow private float currentProgress;
	@Shadow private long fadeOutStart;

	private static long kyoto$start = 0;
	private static float kyoto$shown = 0;
	private static float[][] kyoto$petals = null;

	@Inject(method = "extractRenderState", at = @At("TAIL"), require = 0)
	private void kyoto$draw(GuiGraphicsExtractor g, int mouseX, int mouseY, float partial, CallbackInfo ci) {
		try {
			if (!dev.kyoto.module.Modules.on("kyoto_loading")) return;
		} catch (Throwable notLoadedYet) {
			// config not ready on the very first load: show it anyway
		}
		try {
			var win = net.minecraft.client.Minecraft.getInstance().getWindow();
			int w = win.getGuiScaledWidth(), h = win.getGuiScaledHeight();
			long now = System.currentTimeMillis();
			if (kyoto$start == 0 || now - kyoto$start > 600000) kyoto$start = now;
			float a = fadeOutStart > -1 ? 1 - Anim.clamp01((now - fadeOutStart) / 900f) : 1f;
			if (a <= 0) return;
			double t = now / 1000.0;
			float intro = Anim.clamp01((now - kyoto$start) / 700f);
			// sky
			g.fill(0, 0, w, h, Anim.argb(0xFFF6F9, a));
			g.fillGradient(0, 0, w, h, Anim.argb(0xFFFAFC, a), Anim.argb(0xFFE2EB, a));
			// soft glow behind the blossom (stacked discs)
			float cx = w / 2f, cy = h / 2f - h * 0.05f;
			float size = Math.min(w, h) * 0.13f;
			for (int i = 6; i >= 1; i--) {
				int r = Math.round(size * (0.9f + i * 0.28f));
				Ui.roundPx(g, Math.round(cx - r), Math.round(cy - r), Math.round(cx + r), Math.round(cy + r), r, Anim.argb(0xFFC9D9, 0.06f * a * intro));
			}
			// drifting petals
			if (kyoto$petals == null) {
				java.util.Random r = new java.util.Random(11);
				kyoto$petals = new float[28][];
				for (int i = 0; i < kyoto$petals.length; i++) kyoto$petals[i] = new float[]{r.nextFloat(), r.nextFloat(), r.nextFloat() * 6.3f, 0.04f + r.nextFloat() * 0.05f, r.nextFloat(), 0.8f + r.nextFloat() * 1.6f};
			}
			for (float[] p : kyoto$petals) {
				float y = (float) ((p[1] + t * p[3]) % 1.15) - 0.07f;
				float x = (float) ((p[0] + t * 0.012 + Math.sin(t * 0.7 + p[2]) * 0.02) % 1.0);
				float s = (2.2f + p[4] * 3.2f) * Math.max(0.8f, Math.min(w, h) / 300f);
				float flip = 0.35f + 0.65f * Math.abs((float) Math.cos(t * p[5] + p[2]));
				Ui.blob(g, x * w, y * h, s * 0.62f * flip, s, (float) (t * 0.6 + p[2]), 0.18f, Anim.argb(Ui.mix(0xF6A3BE, 0xFFD7E3, p[4]), a * intro * (0.45f + 0.45f * p[4])));
			}
			// the blossom opens petal by petal, then breathes
			float open = (now - kyoto$start - 150) / 1300f;
			float breathe = 1 + 0.025f * (float) Math.sin(t * 2.0);
			float spin = (float) (t * 0.15);
			for (int i = 0; i < 5; i++) {
				float k = Anim.easeOutBack(Anim.clamp01(open * 1.6f - i * 0.14f));
				if (k <= 0.01f) continue;
				double ang = spin + i * Math.PI * 2 / 5 - Math.PI / 2;
				float d = size * 0.5f * k * breathe;
				float px = cx + (float) Math.cos(ang) * d, py = cy + (float) Math.sin(ang) * d;
				float rx = size * 0.34f * k * breathe, ry = size * 0.52f * k * breathe;
				float rot = (float) ang - (float) Math.PI / 2;
				Ui.blob(g, px, py, rx + 1.2f, ry + 1.2f, rot, 0.22f, Anim.argb(0xF4B6C9, 0.55f * a));
				Ui.blob(g, px, py, rx, ry, rot, 0.22f, Anim.argb(0xF58FB0, a));
				Ui.blob(g, px + (float) Math.cos(ang) * ry * 0.18f, py + (float) Math.sin(ang) * ry * 0.18f, rx * 0.8f, ry * 0.76f, rot, 0.22f, Anim.argb(0xFBC2D4, a));
				Ui.blob(g, px + (float) Math.cos(ang) * ry * 0.34f, py + (float) Math.sin(ang) * ry * 0.34f, rx * 0.55f, ry * 0.5f, rot, 0.22f, Anim.argb(0xFFE6EE, a));
			}
			float c = Anim.easeOutBack(Anim.clamp01(open - 0.55f));
			if (c > 0.01f) {
				int cr = Math.max(1, Math.round(size * 0.16f * c));
				Ui.roundPx(g, Math.round(cx - cr), Math.round(cy - cr), Math.round(cx + cr), Math.round(cy + cr), cr, Anim.argb(0xE0457A, a));
				for (int i = 0; i < 10; i++) {
					double aa = spin * 1.5 + i * Math.PI * 2 / 10;
					int sx = Math.round(cx + (float) Math.cos(aa) * cr * 1.9f), sy = Math.round(cy + (float) Math.sin(aa) * cr * 1.9f);
					int sr = Math.max(1, Math.round(cr * 0.28f));
					Ui.roundPx(g, sx - sr, sy - sr, sx + sr, sy + sr, sr, Anim.argb(0xF3C36B, a * c));
				}
			}
			// pill progress bar
			kyoto$shown += (currentProgress - kyoto$shown) * 0.1f;
			int bw = Math.round(Math.min(180, w * 0.32f)), bh = 5, by = Math.round(cy + size * 1.5f + 16);
			int bx = Math.round(cx - bw / 2f);
			Ui.roundPx(g, bx, by, bx + bw, by + bh, 2, Anim.argb(0xF2C9D6, a * intro));
			int fw = Math.max(bh, Math.round(bw * Anim.clamp01(kyoto$shown)));
			Ui.roundPx(g, bx, by, bx + fw, by + bh, 2, Anim.argb(0xEF6F98, a * intro));
			// a little shine running along the filled part
			float sh = (float) ((t * 0.8) % 1.0);
			int sxp = bx + Math.round(fw * sh);
			g.fill(Math.max(bx, sxp - 6), by + 1, Math.min(bx + fw, sxp + 6), by + 2, Anim.argb(0xFFFFFF, 0.6f * a * intro));
		} catch (Throwable ignored) {
			// never break loading
		}
	}
}
