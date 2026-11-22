package dev.kyoto.screen;

import dev.kyoto.hud.Notifications;
import dev.kyoto.module.Modules;
import dev.kyoto.screens.MediaTools;
import dev.kyoto.screens.Screens;
import dev.kyoto.screens.VideoStream;
import dev.kyoto.util.Anim;
import dev.kyoto.util.Compat;
import dev.kyoto.util.Ui;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * Tools > Video Screens. Make a screen where you're looking, paste a YouTube / Twitch / Kick / Vimeo link (or a video file in the mini player), then move,
 * resize, rotate, play, pause and seek. Other Kyoto players on the server watch in sync.
 */
public class ScreensScreen extends PanelScreen {
	private static String selectedId = null;
	private EditBox urlBox;
	private String typedUrl = "";
	private static int source = 0;

	public ScreensScreen(net.minecraft.client.gui.screens.Screen parent) {
		super("Video Screens", parent);
	}

	@Override
	protected int maxW() {
		return 470;
	}

	@Override
	protected int maxH() {
		return 290;
	}

	@Override
	protected String icon() {
		return "screen";
	}

	@Override
	protected String subtitle() {
		if (Minecraft.getInstance().level == null) return "Join a world first";
		return Screens.singleplayer() ? "Saved in this world" : "Kyoto players on this server see and hear these with you";
	}

	private List<Screens.Screen> mine() {
		List<Screens.Screen> out = new ArrayList<>();
		for (Screens.Screen s : Screens.SCREENS) if (s.mine) out.add(s);
		return out;
	}

	private Screens.Screen selected() {
		List<Screens.Screen> list = mine();
		for (Screens.Screen s : list) if (s.id.equals(selectedId)) return s;
		if (list.isEmpty()) return null;
		selectedId = list.get(0).id;
		return list.get(0);
	}

	@Override
	protected void build() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null) return;
		if (!MediaTools.ready()) {
			if (MediaTools.canAutoInstall() && !MediaTools.downloading)
				btn(px + 14, py + 92, 220, "Download video tools (~100 MB)", () -> MediaTools.install(this::rebuildWidgets));
			return;
		}
		if (!Modules.on("video_screens")) {
			btn(px + 14, py + 60, 160, "Turn Video Screens on", () -> Modules.get("video_screens").set(true));
			return;
		}
		int y = py + 38;
		if (dev.kyoto.screens.MiniPlayer.enabled()) {
			buildMini(y);
			return;
		}
		btn(px + pw - 120, y, 106, "Use mini player", () -> Modules.setting("screens.show").cycle(1));
		btn(px + 14, y, 120, "+ Place a screen", this::create);
		int x = px + 140;
		for (Screens.Screen s : mine()) {
			if (x > px + pw - 190) break;
			String label = (s.id.equals(selectedId) ? "● " : "") + "Screen " + (mine().indexOf(s) + 1);
			btn(x, y, 64, label, () -> {
				selectedId = s.id;
				typedUrl = s.url;
			});
			x += 66;
		}
		Screens.Screen s = selected();
		if (s == null) return;
		y += 28;
		y = sourceChips(y, false, s.url);
		urlBox = new EditBox(this.font, px + 14, y, pw - 98, 18, Component.literal("Video link"));
		urlBox.setMaxLength(300);
		urlBox.setHint(Ui.t(MediaTools.SOURCE_HINTS[Math.min(source, 4)]));
		urlBox.setValue(typedUrl.isEmpty() ? s.url : typedUrl);
		urlBox.setResponder(t -> typedUrl = t);
		this.addRenderableWidget(urlBox);
		btn(px + pw - 80, y - 1, 66, "Play", () -> {
			String u = MediaTools.normalize(source, urlBox.getValue());
			if (!MediaTools.allowedUrl(u)) {
				Notifications.push("That link doesn't work here", "YouTube, Twitch, Kick, Vimeo, Dailymotion or Streamable", Notifications.DANGER);
				return;
			}
			s.url = u;
			s.pausedPos = 0;
			Screens.play(s);
		});
		y += 26;
		btn(px + 14, y, 60, s.paused ? "▶ Play" : "❚❚ Pause", () -> {
			if (s.paused) Screens.play(s);
			else Screens.pause(s);
		});
		btn(px + 78, y, 50, "-10s", () -> Screens.seek(s, s.positionSec() - 10));
		btn(px + 132, y, 50, "+10s", () -> Screens.seek(s, s.positionSec() + 10));
		btn(px + 186, y, 60, "Restart", () -> Screens.seek(s, 0));
		btn(px + pw - 80, y, 66, "Delete", () -> {
			Screens.delete(s);
			selectedId = null;
			typedUrl = "";
		});
		y += 30;
		// position
		btn(px + 14, y, 110, "Move (place again)", () -> Screens.startPlacing(s, false));
		btn(px + 128, y, 60, "Face me", () -> {
			s.yaw = Minecraft.getInstance().player.getYRot() + 180;
			Screens.save(s);
		});
		btn(px + 192, y, 26, "↺", () -> nudgeYaw(s, -15));
		btn(px + 220, y, 26, "↻", () -> nudgeYaw(s, 15));
		y += 22;
		btn(px + 14, y, 26, "↑", () -> move(s, 0, 0.25, 0));
		btn(px + 42, y, 26, "↓", () -> move(s, 0, -0.25, 0));
		btn(px + 70, y, 26, "←", () -> moveRel(s, -0.25, 0));
		btn(px + 98, y, 26, "→", () -> moveRel(s, 0.25, 0));
		btn(px + 126, y, 46, "Closer", () -> moveRel(s, 0, 0.25));
		btn(px + 174, y, 46, "Away", () -> moveRel(s, 0, -0.25));
		y += 22;
		btn(px + 14, y, 60, "Smaller", () -> resize(s, 0.85f));
		btn(px + 78, y, 60, "Bigger", () -> resize(s, 1.18f));
	}

	private void nudgeYaw(Screens.Screen s, float d) {
		s.yaw += d;
		Screens.save(s);
	}

	private void move(Screens.Screen s, double dx, double dy, double dz) {
		s.x += dx;
		s.y += dy;
		s.z += dz;
		Screens.save(s);
	}

	/** right = along the screen, out = towards its front. */
	private void moveRel(Screens.Screen s, double right, double out) {
		Vec3 r = s.right().scale(right).add(s.normal().scale(out));
		move(s, r.x, 0, r.z);
	}

	private void resize(Screens.Screen s, float k) {
		s.w = Math.max(1f, Math.min(16f, s.w * k));
		s.h = s.w * 9f / 16f;
		Screens.save(s);
	}

	private void create() {
		long count = mine().size();
		if (count >= 3) {
			Notifications.push("3 screens max", "Delete one first", Notifications.INFO);
			return;
		}
		Screens.Screen s = new Screens.Screen();
		s.dim = Compat.dimensionId();
		typedUrl = "";
		// it follows your crosshair like a block in your hand - right-click to put it down
		Screens.startPlacing(s, true);
	}

	/** Opens this menu with a screen selected (after you place a new one). */
	public static void openFor(Screens.Screen s) {
		selectedId = s.id;
		Compat.openScreen(new ScreensScreen(null));
	}

	/** Puts the screen on the block you're looking at (up to 32 blocks away). */
	private boolean placeAtCrosshair(Screens.Screen s) {
		Minecraft mc = Minecraft.getInstance();
		var p = mc.player;
		Vec3 eye = p.getEyePosition();
		Vec3 end = eye.add(p.getViewVector(1f).scale(32));
		BlockHitResult hit = mc.level.clip(new ClipContext(eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, p));
		if (hit.getType() != HitResult.Type.BLOCK) return false;
		Direction d = hit.getDirection();
		Vec3 at = hit.getLocation().add(d.getStepX() * 0.03, d.getStepY() * 0.03, d.getStepZ() * 0.03);
		if (d.getAxis().isHorizontal()) {
			s.yaw = (float) Math.toDegrees(Math.atan2(-d.getStepX(), d.getStepZ()));
			at = at.add(0, s.h / 2 - 0.5, 0);
		} else {
			s.yaw = p.getYRot() + 180;
			at = at.add(0, d == Direction.UP ? s.h / 2 + 0.1 : -s.h / 2 - 0.1, 0);
		}
		s.x = at.x;
		s.y = at.y;
		s.z = at.z;
		s.dim = Compat.dimensionId();
		return true;
	}

	@Override
	protected void drawContent(GuiGraphicsExtractor g, int mx, int my, float a) {
		if (Minecraft.getInstance().level == null) {
			Ui.text(g, "Join a world or server to put up screens.", px + 16, py + 44, Anim.argb(Ui.MUTED, a));
			return;
		}
		if (!MediaTools.ready()) {
			int y = py + 42;
			for (String l : wrap("Screens need two free tools: yt-dlp (finds the video) and ffmpeg (plays it)."
					+ (MediaTools.canAutoInstall() ? " Kyoto can download them for you, once." : " Install them with 'brew install yt-dlp ffmpeg' (Mac) or your package manager (Linux), then reopen this."), pw - 32, 4)) {
				Ui.text(g, l, px + 16, y, Anim.argb(Ui.TEXT, a));
				y += 11;
			}
			if (MediaTools.downloading || !MediaTools.status.isEmpty()) {
				String st = MediaTools.status + (MediaTools.progress >= 0 ? "  " + Math.round(MediaTools.progress * 100) + "%" : "");
				Ui.text(g, st, px + 16, py + 120, Anim.argb(Ui.accent(), a));
			}
			return;
		}
		if (dev.kyoto.screens.MiniPlayer.enabled()) {
			VideoStream mv = dev.kyoto.screens.MiniPlayer.stream();
			String st = dev.kyoto.screens.MiniPlayer.url.isBlank() ? "Paste a link and press Play link." : dev.kyoto.screens.MiniPlayer.paused ? "Paused at " + fmt(dev.kyoto.screens.MiniPlayer.position())
					: mv == null || !mv.state.isEmpty() ? (mv == null ? "Starting..." : mv.state) : "Playing " + fmt(dev.kyoto.screens.MiniPlayer.position()) + (mv.duration > 0 ? " / " + fmt(mv.duration) : "");
			Ui.text(g, st, px + 16, py + ph - 52, Anim.argb(Ui.TEXT, a));
			Ui.small(g, "Mini player: only you see and hear it. Move it with Edit HUD.", px + 16, py + ph - 40, Anim.argb(Ui.MUTED, a));
			if (source == 5) Ui.small(g, pickedName, px + 140, py + 38 + 28 + 24 + 4, Anim.argb(Ui.MUTED, a));
			return;
		}
		Screens.Screen s = selected();
		if (s == null) {
			Ui.text(g, "Look at a wall and press \"+ New screen here\".", px + 16, py + 70, Anim.argb(Ui.MUTED, a));
			return;
		}
		int y = py + ph - 52;
		VideoStream v = Screens.stream(s);
		String status = s.url.isBlank() ? "No video" : s.paused ? "Paused at " + fmt(s.positionSec()) : (v == null ? "Starting..." : v.state.isEmpty() ? "Playing " + fmt(s.positionSec()) + (v.duration > 0 ? " / " + fmt(v.duration) : "") : v.state);
		Ui.text(g, status, px + 16, y, Anim.argb(Ui.TEXT, a));
		Ui.small(g, String.format("%.1f x %.1f blocks  ·  %d %d %d", s.w, s.h, Math.round(s.x), Math.round(s.y), Math.round(s.z)), px + 16, y + 12, Anim.argb(Ui.MUTED, a));
		Ui.small(g, "Volume + quality: Kyoto menu > World > Video Screens", px + 16, y + 22, Anim.argb(Ui.MUTED, a));
		if (Boolean.FALSE.equals(Compat.StreamTexture.lastFast))
			Ui.small(g, "Slow copy mode on this Minecraft version - send hef a screenshot of this", px + 16, y - 12, Anim.argb(Ui.INK, a));
	}

	/** Mini player mode: the video plays in a small window on your screen (only you see/hear it). */
	private void buildMini(int y) {
		btn(px + pw - 120, y, 106, "Use world screens", () -> Modules.setting("screens.show").cycle(-1));
		btn(px + 14, y, 120, "Move / resize it", () -> Compat.openScreen(new HudEditorScreen(this)));
		y += 28;
		y = sourceChips(y, true, dev.kyoto.screens.MiniPlayer.url);
		if (source == 5) {
			String cur = dev.kyoto.screens.MiniPlayer.url.startsWith("file:") ? dev.kyoto.screens.MiniPlayer.url.substring(5) : "";
			String name = cur.isEmpty() ? "No file picked" : java.nio.file.Path.of(cur).getFileName().toString();
			btn(px + 14, y - 1, 120, "Choose file...", () -> {
				// the file window blocks, so it gets its own thread (like sending a picture in DMs)
				Thread t = new Thread(() -> {
					String f = MediaTools.pickVideoFile();
					if (f != null && !f.isBlank()) Minecraft.getInstance().execute(() -> {
						dev.kyoto.screens.MiniPlayer.play("file:" + f);
						this.rebuildWidgets();
					});
				}, "Kyoto-PickVideo");
				t.setDaemon(true);
				t.start();
			});
			pickedName = name;
		} else {
			urlBox = new EditBox(this.font, px + 14, y, pw - 98, 18, Component.literal("Video link"));
			urlBox.setMaxLength(300);
			urlBox.setHint(Ui.t(MediaTools.SOURCE_HINTS[source]));
			urlBox.setValue(typedUrl.isEmpty() && !dev.kyoto.screens.MiniPlayer.url.startsWith("file:") ? dev.kyoto.screens.MiniPlayer.url : typedUrl);
			urlBox.setResponder(t -> typedUrl = t);
			this.addRenderableWidget(urlBox);
			btn(px + pw - 80, y - 1, 66, "Play", () -> {
				String u = MediaTools.normalize(source, urlBox.getValue());
				if (!MediaTools.allowedUrl(u)) {
					Notifications.push("That link doesn't work here", "YouTube, Twitch, Kick, Vimeo, Dailymotion or Streamable", Notifications.DANGER);
					return;
				}
				dev.kyoto.screens.MiniPlayer.play(u);
			});
		}
		y += 26;
		btn(px + 14, y, 60, dev.kyoto.screens.MiniPlayer.paused ? "▶ Play" : "❚❚ Pause", () -> {
			if (dev.kyoto.screens.MiniPlayer.paused) dev.kyoto.screens.MiniPlayer.resume();
			else dev.kyoto.screens.MiniPlayer.pause();
		});
		btn(px + 78, y, 50, "-10s", () -> dev.kyoto.screens.MiniPlayer.seek(dev.kyoto.screens.MiniPlayer.position() - 10));
		btn(px + 132, y, 50, "+10s", () -> dev.kyoto.screens.MiniPlayer.seek(dev.kyoto.screens.MiniPlayer.position() + 10));
		btn(px + 186, y, 60, "Restart", () -> dev.kyoto.screens.MiniPlayer.seek(0));
		btn(px + pw - 80, y, 66, "Stop", () -> {
			dev.kyoto.screens.MiniPlayer.stop();
			typedUrl = "";
		});
		y += 26;
		btn(px + 14, y, 150, dev.kyoto.screens.MiniPlayer.hidden ? "Show the player" : "Hide the player", () -> dev.kyoto.screens.MiniPlayer.hidden = !dev.kyoto.screens.MiniPlayer.hidden);
	}

	private String pickedName = "";

	/** Row of source tabs (YouTube / Twitch / Kick / Vimeo / Other / File). File only exists for the mini player. */
	private int sourceChips(int y, boolean allowFile, String current) {
		if (typedUrl.isEmpty() && current != null && !current.isBlank() && !sourceTouched) source = MediaTools.sourceOf(current);
		if (!allowFile && source == 5) source = 0;
		int x = px + 14;
		int n = allowFile ? MediaTools.SOURCES.length : MediaTools.SOURCES.length - 1;
		for (int i = 0; i < n; i++) {
			final int fi = i;
			String label = (source == i ? "▶ " : "") + MediaTools.SOURCES[i];
			int bw = Ui.width(label) + 14;
			btn(x, y, bw, label, () -> {
				source = fi;
				sourceTouched = true;
				typedUrl = "";
			});
			x += bw + 3;
		}
		return y + 24;
	}

	private static boolean sourceTouched = false;

	private static String fmt(double sec) {
		int t = (int) sec;
		return t / 60 + ":" + String.format("%02d", t % 60);
	}
}
