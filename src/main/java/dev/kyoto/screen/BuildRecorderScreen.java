package dev.kyoto.screen;

import dev.kyoto.feature.BuildRecorder;
import dev.kyoto.util.Anim;
import dev.kyoto.util.Compat;
import dev.kyoto.util.Ui;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

import java.nio.file.Path;
import java.util.List;

/** Build Recorder: start/stop recording, pick an area, and see your saved .kybuild files. */
public class BuildRecorderScreen extends PanelScreen {
	private static String draftName = "My Build";
	private static boolean areaMode = false;
	private EditBox nameBox;
	private int page = 0;
	private boolean noFiles = true;

	public BuildRecorderScreen(Screen parent) {
		super("Build Recorder", parent);
	}

	@Override
	protected String icon() {
		return "brush";
	}

	@Override
	protected int maxW() {
		return 500;
	}

	@Override
	protected int maxH() {
		return 320;
	}

	@Override
	protected String subtitle() {
		return "Record your build, then watch the timelapse and export a Litematica schematic in Kyoto Studio.";
	}

	@Override
	protected void build() {
		int x = px + 14, y = py + 40, w = 220;
		boolean rec = BuildRecorder.recording();
		if (!rec) {
			nameBox = new EditBox(this.font, x, y, w, 18, Component.literal("name"));
			nameBox.setMaxLength(40);
			nameBox.setValue(draftName);
			nameBox.setResponder(s -> draftName = s);
			nameBox.setHint(Component.literal("Name your build"));
			this.addRenderableWidget(nameBox);
			y += 26;
			btn(x, y, 108, (areaMode ? "" : "▶ ") + "Follow me", () -> areaMode = false);
			btn(x + 112, y, 108, (areaMode ? "▶ " : "") + "Area", () -> areaMode = true);
			y += 26;
			if (areaMode) {
				btn(x, y, 108, "Corner 1 here", () -> { BuildRecorder.corner1 = BuildRecorder.target(); BuildRecorder.showBox = true; });
				btn(x + 112, y, 108, "Corner 2 here", () -> { BuildRecorder.corner2 = BuildRecorder.target(); BuildRecorder.showBox = true; });
				y += 24;
				btn(x, y, 108, "Clear corners", () -> { BuildRecorder.corner1 = null; BuildRecorder.corner2 = null; });
				btn(x + 112, y, 108, "Box: " + (BuildRecorder.showBox ? "Shown" : "Hidden"), () -> BuildRecorder.showBox = !BuildRecorder.showBox);
				y += 28;
			}
			btn(x, y, w, "● Start recording", () -> {
				String err = areaMode ? BuildRecorder.startArea(draftName) : BuildRecorder.start(draftName);
				if (err != null) dev.kyoto.hud.Notifications.push("Can't start", err, dev.kyoto.hud.Notifications.DANGER);
				else onClose();
			});
		} else {
			btn(x, y, w, BuildRecorder.paused() ? "▶ Resume" : "❚❚ Pause", () -> BuildRecorder.pause(!BuildRecorder.paused()));
			y += 24;
			btn(x, y, w, "■ Stop and save", () -> { BuildRecorder.stop(); page = 0; });
			y += 24;
			btn(x, y, w, "Throw it away", BuildRecorder::cancel);
		}
		// saved builds
		int lx = px + 250, ly = py + 156, lw = pw - 264;
		List<Path> files = BuildRecorder.files();
		noFiles = files.isEmpty();
		int per = Math.max(1, (py + ph - 60 - ly) / 22);
		int pages = Math.max(1, (files.size() + per - 1) / per);
		page = clamp(page, 0, pages - 1);
		for (int i = page * per; i < Math.min(files.size(), (page + 1) * per); i++) {
			Path f = files.get(i);
			String n = f.getFileName().toString().replace(".kybuild", "").replace('_', ' ');
			btn(lx, ly, lw - 24, Ui.ellipsize(n, lw - 40), () -> Compat.openUrl(f.toAbsolutePath().toString()));
			btn(lx + lw - 22, ly, 22, "✕", () -> { try { java.nio.file.Files.deleteIfExists(f); } catch (Exception ignored) { } });
			ly += 22;
		}
		if (pages > 1) pager(lx, py + ph - 50, lw, page, pages, v -> page = v);
		btn(px + 14, py + ph - 26, 108, "Open folder", () -> Compat.openFolder(BuildRecorder.folder()));
		btn(px + 126, py + ph - 26, 108, "Get Kyoto Studio ↗", () -> Compat.openUrl("https://kyoto-server.onrender.com/studio"));
	}

	@Override
	protected void drawContent(GuiGraphicsExtractor g, int mx, int my, float a) {
		int x = px + 250, y = py + 40, w = pw - 264;
		Ui.round(g, x, y, x + w, y + 100, 8, Anim.argb(Ui.GLASS, 0.95f * a));
		if (BuildRecorder.recording()) {
			long t = BuildRecorder.elapsed() / 1000;
			boolean p = BuildRecorder.paused(), prep = BuildRecorder.preparing();
			int dot = p ? Ui.INK : prep ? 0x7FB8FF : 0xFF4D5E;
			float pulse = p || prep ? 1f : 0.55f + 0.45f * (float) Math.abs(Math.sin(System.currentTimeMillis() / 400.0));
			Ui.round(g, x + 10, y + 11, x + 18, y + 19, 4, Anim.argb(dot, pulse * a));
			Ui.bold(g, prep ? "Getting the area ready..." : p ? "Paused" : "Recording", x + 24, y + 10, Anim.argb(Ui.TEXT, a));
			Ui.text(g, BuildRecorder.name(), x + 10, y + 28, Anim.argb(Ui.INK, a));
			Ui.text(g, String.format("%02d:%02d", t / 60, t % 60) + "  ·  " + (BuildRecorder.mode() == BuildRecorder.Mode.AREA ? "Area" : "Follow me"), x + 10, y + 42, Anim.argb(Ui.MUTED, a));
			Ui.text(g, BuildRecorder.placed() + " placed  ·  " + BuildRecorder.broken() + " broken  ·  " + BuildRecorder.events() + " changes", x + 10, y + 56, Anim.argb(Ui.MUTED, a));
			Ui.small(g, "Close this menu and keep building. The REC sign shows it's on.", x + 10, y + 80, Anim.argb(Ui.MUTED, a));
		} else if (areaMode) {
			Ui.bold(g, "Area", x + 10, y + 8, Anim.argb(Ui.TEXT, a));
			BlockPos c1 = BuildRecorder.corner1, c2 = BuildRecorder.corner2;
			Ui.text(g, "Corner 1: " + (c1 == null ? "not set" : c1.getX() + " " + c1.getY() + " " + c1.getZ()), x + 10, y + 24, Anim.argb(c1 == null ? Ui.MUTED : Ui.MUTED, a));
			Ui.text(g, "Corner 2: " + (c2 == null ? "not set" : c2.getX() + " " + c2.getY() + " " + c2.getZ()), x + 10, y + 36, Anim.argb(c2 == null ? Ui.MUTED : Ui.MUTED, a));
			Ui.text(g, BuildRecorder.areaSize(), x + 10, y + 50, Anim.argb(Ui.MUTED, a));
			Ui.small(g, "Look at a block and press Corner 1, then the opposite corner.", x + 10, y + 70, Anim.argb(Ui.MUTED, a));
			Ui.small(g, "Records every change in the box: friends, pistons, WorldEdit too.", x + 10, y + 82, Anim.argb(Ui.MUTED, a));
		} else {
			Ui.bold(g, "Follow me", x + 10, y + 8, Anim.argb(Ui.TEXT, a));
			Ui.small(g, "Records every block YOU place and break,", x + 10, y + 26, Anim.argb(Ui.MUTED, a));
			Ui.small(g, "anywhere in this dimension. Nothing to set up.", x + 10, y + 37, Anim.argb(Ui.MUTED, a));
			Ui.small(g, "When you stop, it saves a .kybuild file. Open it", x + 10, y + 55, Anim.argb(Ui.MUTED, a));
			Ui.small(g, "in Kyoto Studio for the timelapse + .litematic.", x + 10, y + 66, Anim.argb(Ui.MUTED, a));
			Ui.small(g, "Commands: /kyoto build start | stop | pause", x + 10, y + 84, Anim.argb(Ui.MUTED, a));
		}
		Ui.bold(g, "Saved builds", x, py + 144, Anim.argb(Ui.TEXT, a));
		if (noFiles) Ui.small(g, "Nothing yet. Click one to open it in Kyoto Studio.", x, py + 160, Anim.argb(Ui.MUTED, a));
	}
}
