package dev.kyoto.extra;

import dev.kyoto.hud.Notifications;
import dev.kyoto.screen.PanelScreen;
import dev.kyoto.util.Anim;
import dev.kyoto.util.Compat;
import dev.kyoto.util.Ui;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.resources.Identifier;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/** Screenshot Studio: every screenshot and clip in one place. Click one to see it big, open it or delete it. */
public class StudioScreen extends PanelScreen {
	private static final Map<String, Identifier> THUMBS = new ConcurrentHashMap<>();
	private static final Map<String, int[]> SIZES = new ConcurrentHashMap<>();
	private static final java.util.Set<String> LOADING = ConcurrentHashMap.newKeySet();

	private boolean clips = false;
	private int page = 0;
	private Path open = null;
	private final List<Path> files = new ArrayList<>();

	public StudioScreen(Screen parent) {
		super("Screenshot Studio", parent);
	}

	@Override
	protected int maxW() { return 520; }

	@Override
	protected int maxH() { return 330; }

	@Override
	protected String icon() { return "image"; }

	@Override
	protected String subtitle() {
		return clips ? files.size() + " clips · Save clip key saves the last " + ReplayBuffer.seconds() + "s" : files.size() + " screenshots · F2, or the Clean screenshot key (no HUD)";
	}

	private static Path shotsDir() {
		return FabricLoader.getInstance().getGameDir().resolve("screenshots");
	}

	private void load() {
		files.clear();
		Path dir = clips ? ReplayBuffer.folder() : shotsDir();
		if (!Files.isDirectory(dir)) return;
		try (Stream<Path> s = Files.list(dir)) {
			s.filter(p -> p.toString().toLowerCase().endsWith(clips ? ".avi" : ".png")).sorted(Comparator.comparingLong((Path p) -> p.toFile().lastModified()).reversed()).limit(300).forEach(files::add);
		} catch (Exception ignored) {
		}
	}

	private static final int COLS = 4, ROWS = 3;

	@Override
	protected void build() {
		load();
		int y = py + ph - 26;
		btn(px + 10, y, 90, clips ? "Screenshots" : "Clips", () -> { clips = !clips; page = 0; open = null; });
		btn(px + 104, y, 70, "Folder", () -> Compat.openFolder(clips ? ReplayBuffer.folder() : shotsDir()));
		if (open != null) {
			btn(px + 178, y, 60, "Open", () -> Compat.openUrl(open.toAbsolutePath().toString()));
			btn(px + 242, y, 60, "Delete", () -> {
				try {
					Files.deleteIfExists(open);
					Notifications.push("Deleted", open.getFileName().toString(), Notifications.INFO, 2000, null, "trash");
				} catch (Exception ignored) {
				}
				open = null;
			});
			btn(px + 306, y, 60, "Back", () -> open = null);
		} else {
			int pages = Math.max(1, (files.size() + COLS * ROWS - 1) / (COLS * ROWS));
			page = Math.min(page, pages - 1);
			pager(px + 178, y, 130, page, pages, p -> page = p);
		}
	}

	@Override
	protected void drawContent(GuiGraphicsExtractor g, int mx, int my, float a) {
		int x0 = px + 10, y0 = py + 36, w = pw - 20, h = ph - 68;
		if (files.isEmpty()) {
			String t = clips ? "No clips yet. Turn on Replay Buffer, then press the Save clip key." : "No screenshots yet. Press F2 in game.";
			Ui.text(g, t, px + pw / 2 - Ui.width(t) / 2, y0 + h / 2, Anim.argb(Ui.MUTED, a));
			return;
		}
		if (open != null) {
			if (clips) {
				Ui.bold(g, open.getFileName().toString(), x0, y0 + 10, Anim.argb(Ui.TEXT, a));
				Ui.text(g, sizeOf(open) + " · " + when(open), x0, y0 + 24, Anim.argb(Ui.MUTED, a));
				Ui.text(g, "Open plays it in your video player. Drag it into CapCut or Premiere to edit.", x0, y0 + 40, Anim.argb(Ui.MUTED, a));
				return;
			}
			Identifier t = thumb(open);
			int[] sz = SIZES.getOrDefault(open.toString(), new int[]{16, 9});
			int bw = w, bh = Math.round(bw * sz[1] / (float) sz[0]);
			if (bh > h - 12) { bh = h - 12; bw = Math.round(bh * sz[0] / (float) sz[1]); }
			int bx = px + pw / 2 - bw / 2;
			if (t != null) Compat.blitTint(g, t, bx, y0, bw, bh, Anim.argb(0xFFFFFF, a));
			Ui.small(g, open.getFileName() + " · " + when(open), bx, y0 + bh + 3, Anim.argb(Ui.MUTED, a));
			return;
		}
		int gap = 6, cw = (w - gap * (COLS - 1)) / COLS, ch = Math.min((h - gap * (ROWS - 1)) / ROWS, Math.round(cw * 9 / 16f));
		int start = page * COLS * ROWS;
		for (int i = 0; i < COLS * ROWS && start + i < files.size(); i++) {
			Path f = files.get(start + i);
			int x = x0 + (i % COLS) * (cw + gap), y = y0 + (i / COLS) * (ch + gap);
			boolean hov = Ui.inside(mx, my, x, y, x + cw, y + ch);
			Ui.round(g, x - 1, y - 1, x + cw + 1, y + ch + 1, 4, Anim.argb(hov ? Ui.accent() : Ui.INK, (hov ? 0.8f : 0.08f) * a));
			if (clips) {
				Ui.round(g, x, y, x + cw, y + ch, 4, Anim.argb(Ui.GLASS, a));
				Ui.iconTex(g, "play", x + cw / 2 - 6, y + ch / 2 - 10, 12, Anim.argb(Ui.accent(), a));
				Ui.small(g, Ui.ellipsize(when(f), Math.round((cw - 6) / 0.75f)), x + 4, y + ch - 10, Anim.argb(Ui.MUTED, a));
			} else {
				Identifier t = thumb(f);
				if (t != null) Compat.blitTint(g, t, x, y, cw, ch, Anim.argb(0xFFFFFF, a));
				else Ui.round(g, x, y, x + cw, y + ch, 4, Anim.argb(Ui.GLASS, a));
			}
		}
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (super.mouseClicked(event, doubleClick)) return true;
		if (open != null || files.isEmpty()) return false;
		int x0 = px + 10, y0 = py + 36, w = pw - 20, h = ph - 68;
		int gap = 6, cw = (w - gap * (COLS - 1)) / COLS, ch = Math.min((h - gap * (ROWS - 1)) / ROWS, Math.round(cw * 9 / 16f));
		int start = page * COLS * ROWS;
		for (int i = 0; i < COLS * ROWS && start + i < files.size(); i++) {
			int x = x0 + (i % COLS) * (cw + gap), y = y0 + (i / COLS) * (ch + gap);
			if (Ui.inside(event.x(), event.y(), x, y, x + cw, y + ch)) {
				open = files.get(start + i);
				rebuildWidgets();
				return true;
			}
		}
		return false;
	}

	private static String when(Path p) {
		return DateTimeFormatter.ofPattern("d MMM, HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(p.toFile().lastModified()));
	}

	private static String sizeOf(Path p) {
		long b = p.toFile().length();
		return b > 1 << 20 ? String.format("%.1f MB", b / 1048576.0) : (b / 1024) + " KB";
	}

	/** Small copy of the picture (so 60 screenshots don't eat your video memory). Made on a background thread. */
	private static Identifier thumb(Path p) {
		String k = p.toString();
		Identifier t = THUMBS.get(k);
		if (t != null || LOADING.contains(k)) return t;
		LOADING.add(k);
		Thread th = new Thread(() -> {
			try {
				BufferedImage src = ImageIO.read(p.toFile());
				if (src == null) return;
				int tw = 480, th2 = Math.max(1, Math.round(tw * src.getHeight() / (float) src.getWidth()));
				BufferedImage dst = new BufferedImage(tw, th2, BufferedImage.TYPE_INT_ARGB);
				Graphics2D gr = dst.createGraphics();
				gr.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
				gr.drawImage(src, 0, 0, tw, th2, null);
				gr.dispose();
				ByteArrayOutputStream out = new ByteArrayOutputStream();
				ImageIO.write(dst, "png", out);
				SIZES.put(k, new int[]{src.getWidth(), src.getHeight()});
				Identifier id = Identifier.fromNamespaceAndPath("kyoto", "shots/" + Integer.toHexString(k.hashCode()) + "_" + Long.toHexString(p.toFile().lastModified()));
				byte[] png = out.toByteArray();
				Compat.runOnClient(() -> Compat.registerPng(id, png, () -> THUMBS.put(k, id), true));
			} catch (Throwable ignored) {
			}
		}, "Kyoto thumbs");
		th.setDaemon(true);
		th.start();
		return null;
	}
}
