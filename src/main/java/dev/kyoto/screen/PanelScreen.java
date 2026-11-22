package dev.kyoto.screen;

import dev.kyoto.util.Anim;
import dev.kyoto.util.Compat;
import dev.kyoto.util.Ui;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Base for every Kyoto tool screen: centered glass panel, title, fade/scale-in,
 * "- value +" rows, and helpers for buttons. Built from vanilla widgets so it works with every mod.
 */
public abstract class PanelScreen extends Screen {
	protected final Screen parent;
	protected int px, py, pw, ph;
	private long openedAt;

	protected record Row(int x, int y, String name, Supplier<String> value) {}

	protected final List<Row> rows = new ArrayList<>();

	protected PanelScreen(String title, Screen parent) {
		super(Component.literal(title));
		this.parent = parent;
	}

	@Override
	protected void init() {
		if (openedAt == 0) openedAt = System.currentTimeMillis();
		pw = Math.min(this.width - 16, maxW());
		ph = Math.min(this.height - 16, maxH());
		px = (this.width - pw) / 2;
		py = (this.height - ph) / 2;
		rows.clear();
		build();
		btn(px + pw - 62, py + ph - 26, 54, "Done", this::onClose);
	}

	protected abstract void build();

	protected int maxW() {
		return 460;
	}

	protected int maxH() {
		return 300;
	}

	protected String subtitle() {
		return "";
	}

	/** Icon name from assets/kyoto/textures/gui/icons (shown in the header). */
	protected String icon() {
		return "sparkle";
	}

	/** Extra drawing on top of the widgets (previews, lists, text). */
	protected void drawContent(GuiGraphicsExtractor g, int mx, int my, float a) {
	}

	protected float alpha() {
		float s = Ui.animSpeed();
		return s <= 0 ? 1f : Anim.easeOutCubic((System.currentTimeMillis() - openedAt) / (220f * s));
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partial) {
		super.extractBackground(g, mouseX, mouseY, partial);
		float a = alpha();
		int lift = Math.round((1 - a) * 8);
		g.fillGradient(0, 0, this.width, this.height, Anim.argb(Ui.BG_TOP, 0.45f * a), Anim.argb(Ui.BG_BOTTOM, 0.6f * a));
		// soft shadow + glass + 1px rim
		Ui.shadow(g, px, py + lift, px + pw, py + ph + lift, 12, 0.6f * a);
		Ui.round(g, px - 1, py - 1 + lift, px + pw + 1, py + ph + 1 + lift, 12, Anim.argb(Ui.INK, 0.07f * a));
		Ui.round(g, px, py + lift, px + pw, py + ph + lift, 11, Anim.argb(Ui.GLASS, Ui.panelAlpha() * a));
		// header: icon tile, title, subtitle, divider
		int accent = Ui.accent();
		Ui.round(g, px + 10, py + 7 + lift, px + 28, py + 25 + lift, 6, Anim.argb(accent, 0.18f * a));
		Ui.iconTex(g, icon(), px + 14, py + 11 + lift, 10, Anim.argb(accent, a));
		Ui.bold(g, this.title.getString(), px + 34, py + 8 + lift, Anim.argb(Ui.TEXT, a));
		String sub = subtitle();
		if (!sub.isEmpty()) Ui.small(g, Ui.ellipsize(sub, Math.round((pw - 60) / 0.75f)), px + 34, py + 19 + lift, Anim.argb(Ui.MUTED, a));
		g.fill(px + 10, py + 29 + lift, px + pw - 10, py + 30 + lift, Anim.argb(Ui.INK, 0.06f * a));
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partial) {
		super.extractRenderState(g, mouseX, mouseY, partial);
		float a = alpha();
		for (Row r : rows) {
			Ui.text(g, r.name(), r.x(), r.y() + 6, Anim.argb(Ui.MUTED, a));
			String v = Ui.ellipsize(r.value().get(), 50);
			int cx = r.x() + 168;
			Ui.text(g, v, cx - Ui.width(v) / 2, r.y() + 6, Anim.argb(Ui.accent(), a));
		}
		drawContent(g, mouseX, mouseY, a);
	}

	@Override
	public void onClose() {
		Compat.openScreen(parent);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	/* ---------------- helpers ---------------- */

	protected Button btn(int x, int y, int w, String label, Runnable action) {
		return this.addRenderableWidget(Button.builder(Component.literal(label), b -> {
			action.run();
			if (this.minecraft != null && this.minecraft.gui.screen() == this) this.rebuildWidgets();
		}).bounds(x, y, w, 20).build());
	}

	/** A "Name      [-]  value  [+]" row. */
	protected void row(int y, String name, Supplier<String> value, Runnable minus, Runnable plus) {
		rowAt(px + 14, y, name, value, minus, plus);
	}

	protected void rowAt(int x, int y, String name, Supplier<String> value, Runnable minus, Runnable plus) {
		rows.add(new Row(x, y, name, value));
		btn(x + 120, y, 20, "-", minus);
		btn(x + 196, y, 20, "+", plus);
	}

	/** A setting with ‹ › buttons, so you can go back one instead of cycling all the way round. */
	protected void cycleRow(int y, dev.kyoto.module.Modules.Setting s) {
		row(y, s.name, s::label, () -> s.cycle(-1), () -> s.cycle(1));
	}

	/** ‹  Page 2/5  › - both arrows work. */
	protected void pager(int x, int y, int w, int page, int pages, java.util.function.IntConsumer set) {
		if (pages <= 1) return;
		btn(x, y, 20, "‹", () -> set.accept(Math.floorMod(page - 1, pages)));
		btn(x + 22, y, w - 44, "Page " + (page + 1) + "/" + pages, () -> set.accept((page + 1) % pages));
		btn(x + w - 20, y, 20, "›", () -> set.accept((page + 1) % pages));
	}

	protected static int clamp(int v, int lo, int hi) {
		return Math.max(lo, Math.min(hi, v));
	}

	protected static float clampF(float v, float lo, float hi) {
		return Math.max(lo, Math.min(hi, v));
	}

	protected static List<String> wrap(String text, int width, int maxLines) {
		List<String> lines = new ArrayList<>();
		for (String para : text.split("\n")) {
			StringBuilder cur = new StringBuilder();
			for (String word : para.split(" ")) {
				String test = cur.isEmpty() ? word : cur + " " + word;
				if (Ui.width(test) > width && !cur.isEmpty()) {
					lines.add(cur.toString());
					cur = new StringBuilder(word);
				} else cur = new StringBuilder(test);
				if (lines.size() >= maxLines) return lines;
			}
			if (!cur.isEmpty()) lines.add(cur.toString());
			if (lines.size() >= maxLines) return lines;
		}
		return lines;
	}
}
