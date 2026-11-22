package dev.kyoto.screen;

import dev.kyoto.config.KyotoConfig;
import dev.kyoto.module.Modules;
import dev.kyoto.net.KyotoNet;
import dev.kyoto.profile.AudioPlayer;
import dev.kyoto.profile.Profiles;
import dev.kyoto.profile.Video;
import dev.kyoto.util.Anim;
import dev.kyoto.util.Compat;
import dev.kyoto.util.Ui;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * A player's Kyoto profile page (guns.lol style): looping muted background video, animated name,
 * badges (owner crown!), tags, bio, links, view counter and profile music.
 */
public class ProfileScreen extends Screen {
	private final Screen parent;
	private final String target;
	private Profiles.Data data;
	private Video video;
	private long openedAt;
	private final List<float[]> particles = new ArrayList<>();
	private final Random rand = new Random();
	private boolean musicStarted = false;
	private Identifier avatarTex, bgTex;
	private int bgW = 16, bgH = 9;
	private static int texIds = 0;

	public ProfileScreen(Screen parent, String name) {
		super(Component.literal("Profile"));
		this.parent = parent;
		this.target = name;
	}

	private boolean isMe() {
		return target.equalsIgnoreCase(Compat.myName());
	}

	@Override
	protected void init() {
		if (openedAt == 0) {
			openedAt = System.currentTimeMillis();
			load();
		}
		buildButtons();
	}

	private void load() {
		boolean useServer = KyotoNet.loggedIn() || (KyotoNet.configured() && !isMe());
		if (isMe() && !KyotoNet.loggedIn()) {
			show(Profiles.mine());
		} else if (useServer) {
			Profiles.fetch(target, d -> {
				// your own unpublished profile still previews from your settings
				if (isMe() && !d.found) show(Profiles.mine());
				else show(d);
			});
		} else {
			Profiles.Data d = new Profiles.Data();
			d.name = target;
			d.displayName = target;
			d.found = false;
			d.error = "Profiles of other players need your Kyoto server (see the guide).";
			if (Profiles.isOwner(target)) d.badges.add("owner");
			show(d);
		}
	}

	private void show(Profiles.Data d) {
		this.data = d;
		if (d.videoExt != null) {
			if (d.local && d.localVideo != null && Files.exists(d.localVideo)) video = Video.load(d.localVideo);
			else if (!d.local) {
				KyotoNet.runAsync(() -> {
					try {
						byte[] bytes = Profiles.download(d.name, "video");
						this.video = Video.load(bytes, d.videoExt);
					} catch (Exception ignored) {
					}
				});
			}
		}
		if (d.audioExt != null && Modules.on("profile_music")) startMusic(d);
		if (!d.local) {
			if (d.avatarExt != null) picture(d.name, "avatar", id -> avatarTex = id);
			if (d.imageExt != null && d.videoExt == null) picture(d.name, "image", id -> bgTex = id);
		}
		if (this.minecraft != null) this.rebuildWidgets();
	}

	/** Downloads a profile picture (avatar / background) made on the website and turns it into a texture. */
	private void picture(String name, String kind, java.util.function.Consumer<Identifier> done) {
		KyotoNet.runAsync(() -> {
			try {
				byte[] bytes = Profiles.download(name, kind);
				byte[] png = toPng(bytes, kind.equals("image"));
				Identifier id = Identifier.fromNamespaceAndPath("kyoto", "profile/" + kind + "_" + (texIds++));
				Compat.registerPng(id, png, () -> done.accept(id), true);
			} catch (Exception ignored) {
			}
		});
	}

	/** Any PNG/JPG -> PNG (Minecraft only reads PNG). Remembers the background's size for "cover" fitting. */
	private byte[] toPng(byte[] data, boolean bg) throws Exception {
		java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(data));
		if (img == null) throw new IllegalStateException("not an image");
		if (bg) {
			bgW = img.getWidth();
			bgH = img.getHeight();
		}
		java.awt.image.BufferedImage out = new java.awt.image.BufferedImage(img.getWidth(), img.getHeight(), java.awt.image.BufferedImage.TYPE_INT_ARGB);
		out.getGraphics().drawImage(img, 0, 0, null);
		java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
		javax.imageio.ImageIO.write(out, "png", bo);
		return bo.toByteArray();
	}

	private void startMusic(Profiles.Data d) {
		if (musicStarted) return;
		musicStarted = true;
		AudioPlayer.setVolume(KyotoConfig.get().profile.volume);
		Thread t = new Thread(() -> {
			try {
				byte[] bytes = d.local ? Files.readAllBytes(d.localAudio) : Profiles.download(d.name, "audio");
				String name = d.audioName.isBlank() ? "song." + d.audioExt : d.audioName;
				AudioPlayer.play(bytes, name);
			} catch (Exception ignored) {
			}
		}, "Kyoto-ProfileMusic");
		t.setDaemon(true);
		t.start();
	}

	private Button add(int x, int y, int w, String label, Runnable r) {
		return this.addRenderableWidget(Button.builder(Component.literal(label), b -> {
			r.run();
			if (this.minecraft != null && this.minecraft.gui.screen() == this) this.rebuildWidgets();
		}).bounds(x, y, w, 20).build());
	}

	private void buildButtons() {
		add(8, 8, 70, "← Back", this::onClose);
		if (isMe()) {
			add(82, 8, 110, "Edit on website", KyotoNet::openProfileEditor);
			add(196, 8, 80, "Quick edit", () -> Compat.openScreen(new ProfileEditScreen(this)));
			add(280, 8, 60, "Publish", Profiles::publish);
		} else if (KyotoNet.loggedIn()) {
			add(82, 8, 90, "+ Add friend", () -> KyotoNet.friendRequest(target));
		}
		if (data == null) return;

		// links
		int cw = cardW();
		int lx = this.width / 2 - cw / 2 + 14, ly = cardY() + cardH() - 30;
		if (data.layout == 2) {
			lx = this.width / 2 - cw / 2 + 96;
			cw = cw - 96 + 14;
		}
		int bw = data.links.isEmpty() ? 0 : Math.min(110, (cw - 28 - (data.links.size() - 1) * 4) / Math.max(1, data.links.size()));
		for (String link : data.links) {
			String url = link.startsWith("http") ? link : "https://" + link;
			add(lx, ly, bw, linkLabel(url), () -> Compat.openUrl(url));
			lx += bw + 4;
		}
		// music controls
		if (data.audioExt != null) {
			int my = this.height - 30;
			add(this.width / 2 + 60, my, 22, AudioPlayer.paused ? "▶" : "❚❚", () -> {
				if (!musicStarted) startMusic(data);
				else AudioPlayer.paused = !AudioPlayer.paused;
			});
			add(this.width / 2 + 86, my, 22, "-", () -> vol(-0.1f));
			add(this.width / 2 + 112, my, 22, "+", () -> vol(0.1f));
		}
	}

	private void vol(float d) {
		AudioPlayer.setVolume(AudioPlayer.volume() + d);
		KyotoConfig.get().profile.volume = AudioPlayer.volume();
		KyotoConfig.save();
	}

	private static String linkLabel(String url) {
		String u = url.toLowerCase(Locale.ROOT);
		if (u.contains("youtube.com") || u.contains("youtu.be")) return "YouTube";
		if (u.contains("tiktok")) return "TikTok";
		if (u.contains("discord")) return "Discord";
		if (u.contains("twitch")) return "Twitch";
		if (u.contains("kick.com")) return "Kick";
		if (u.contains("github")) return "GitHub";
		if (u.contains("x.com") || u.contains("twitter")) return "X";
		if (u.contains("instagram")) return "Instagram";
		if (u.contains("spotify")) return "Spotify";
		if (u.contains("roblox")) return "Roblox";
		if (u.contains("steam")) return "Steam";
		if (u.contains("guns.lol")) return "guns.lol";
		return "Website";
	}

	private int cardW() {
		if (data != null && data.layout == 2) return Math.min(this.width - 40, 420);
		return Math.min(this.width - 40, 340);
	}

	private int cardH() {
		if (data != null && data.layout == 2) return Math.min(this.height - 90, 150);
		return Math.min(this.height - 90, 230);
	}

	private int cardY() {
		return Math.max(36, (this.height - cardH()) / 2 - 6);
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mx, int my, float partial) {
		// our own background: video (or animated gradient) + effects
		int w = this.width, h = this.height;
		Identifier frame = video == null ? null : video.current();
		float hue = data == null ? 262f : data.hue;
		float dim = data == null ? 0.45f : data.bgDim / 100f;
		if (frame == null && bgTex != null) {
			float vr = bgW / (float) Math.max(1, bgH), sr = w / (float) h;
			int dw = vr > sr ? Math.round(h * vr) : w, dh = vr > sr ? h : Math.round(w / vr);
			Compat.blit(g, bgTex, (w - dw) / 2, (h - dh) / 2, dw, dh);
			g.fill(0, 0, w, h, Anim.argb(0x000000, dim));
		} else if (frame != null) {
			// "cover" fit: fill the screen, crop the edges
			float vr = video.width / (float) Math.max(1, video.height), sr = w / (float) h;
			int dw = vr > sr ? Math.round(h * vr) : w, dh = vr > sr ? h : Math.round(w / vr);
			Compat.blit(g, frame, (w - dw) / 2, (h - dh) / 2, dw, dh);
			g.fill(0, 0, w, h, Anim.argb(0x000000, dim));
		} else {
			float t = (System.currentTimeMillis() % 20000) / 20000f;
			int c1 = Ui.hsv(hue + (float) Math.sin(t * Math.PI * 2) * 20, 0.55f, 0.22f);
			int c2 = Ui.hsv(hue + 40, 0.6f, 0.08f);
			g.fillGradient(0, 0, w, h, Anim.argb(c1, 1f), Anim.argb(c2, 1f));
		}
		effects(g, w, h, data == null ? 1 : data.bgEffect, hue);
		drawCard(g, mx, my);
	}

	private void effects(GuiGraphicsExtractor g, int w, int h, int type, float hue) {
		if (type == 0) return;
		int want = switch (type) {
			case 1 -> 70;
			case 4 -> 90;
			default -> 50;
		};
		while (particles.size() < want) {
			particles.add(new float[]{rand.nextFloat() * w, rand.nextFloat() * h, rand.nextFloat(), rand.nextFloat() * 6.28f});
		}
		long now = System.currentTimeMillis();
		for (float[] p : particles) {
			switch (type) {
				case 1 -> { // twinkling stars
					float a = 0.3f + 0.7f * (0.5f + 0.5f * (float) Math.sin(now / 400.0 + p[3] * 5));
					int s = p[2] > 0.85f ? 2 : 1;
					g.fill((int) p[0], (int) p[1], (int) p[0] + s, (int) p[1] + s, Anim.argb(Ui.INK, a * 0.9f));
				}
				case 2 -> { // snow
					p[1] += 0.3f + p[2] * 0.6f;
					p[0] += (float) Math.sin(now / 900.0 + p[3]) * 0.3f;
					g.fill((int) p[0], (int) p[1], (int) p[0] + 2, (int) p[1] + 2, Anim.argb(Ui.INK, 0.8f));
				}
				case 3 -> { // sakura
					p[1] += 0.35f + p[2] * 0.5f;
					p[0] += 0.3f + (float) Math.sin(now / 700.0 + p[3]) * 0.4f;
					Ui.round(g, (int) p[0], (int) p[1], (int) p[0] + 4, (int) p[1] + 3, 1, Anim.argb(p[2] > 0.5f ? Ui.MUTED : 0xFFD6E8, 0.85f));
				}
				case 4 -> { // rain
					p[1] += 5 + p[2] * 4;
					p[0] -= 0.6f;
					g.fill((int) p[0], (int) p[1], (int) p[0] + 1, (int) p[1] + 6, Anim.argb(0xA8C8FF, 0.45f));
				}
				case 6 -> { // stars + a shooting star now and then
					float tw = 0.3f + 0.7f * (0.5f + 0.5f * (float) Math.sin(now / 400.0 + p[3] * 5));
					g.fill((int) p[0], (int) p[1], (int) p[0] + 1, (int) p[1] + 1, Anim.argb(Ui.INK, tw * 0.8f));
				}
				default -> { // fireflies
					p[0] += (float) Math.cos(now / 1500.0 + p[3]) * 0.3f;
					p[1] += (float) Math.sin(now / 1300.0 + p[3] * 2) * 0.3f;
					float a = 0.4f + 0.6f * (0.5f + 0.5f * (float) Math.sin(now / 300.0 + p[3] * 3));
					Ui.round(g, (int) p[0], (int) p[1], (int) p[0] + 3, (int) p[1] + 3, 1, Anim.argb(Ui.hsv(hue, 0.5f, 1f), a));
				}
			}
			if (p[1] > h + 6) {
				p[1] = -6;
				p[0] = rand.nextFloat() * w;
			}
			if (p[0] < -6) p[0] = w + 4;
			if (p[0] > w + 6) p[0] = -4;
		}
	}

	private float shootT = -1, shootX, shootY;

	private void shootingStar(GuiGraphicsExtractor g, int w, int h) {
		long now = System.currentTimeMillis();
		if (shootT < 0 && rand.nextInt(240) == 0) {
			shootT = now;
			shootX = w * (0.1f + rand.nextFloat() * 0.6f);
			shootY = h * (0.05f + rand.nextFloat() * 0.25f);
		}
		if (shootT < 0) return;
		float k = (now - shootT) / 900f;
		if (k > 1) {
			shootT = -1;
			return;
		}
		float a = Anim.clamp01(k / 0.15f) * Anim.clamp01((1 - k) / 0.35f);
		float hx = shootX + k * w * 0.45f, hy = shootY + k * w * 0.18f;
		g.pose().pushMatrix();
		g.pose().translate(hx, hy);
		g.pose().rotate((float) Math.atan2(0.18, 0.45));
		float tail = w * 0.14f * Anim.clamp01(k / 0.3f);
		for (int i = 0; i < 24; i++) {
			float f = i / 24f, al = a * (1 - f) * (1 - f);
			g.fill(Math.round(-tail * (f + 1 / 24f)), 0, Math.round(-tail * f) + 1, 1, Anim.argb(Ui.INK, 0.85f * al));
		}
		g.fill(-2, -2, 3, 3, Anim.argb(Ui.INK, 0.25f * a));
		g.fill(-1, -1, 2, 2, Anim.argb(Ui.INK, a));
		g.pose().popMatrix();
	}

	private void cardBox(GuiGraphicsExtractor g, int x, int y, int cw, int ch, int accent, float a) {
		if (data == null) {
			Ui.round(g, x, y, x + cw, y + ch, 12, Anim.argb(Ui.GLASS, 0.62f * a));
			return;
		}
		float op = data.cardOpacity / 100f;
		if (data.layout == 1) return; // floating: no card at all
		switch (data.cardStyle) {
			case 1 -> Ui.round(g, x, y, x + cw, y + ch, 12, Anim.argb(0x111216, Math.max(0.85f, op) * a));
			case 2 -> {
				Ui.round(g, x - 1, y - 1, x + cw + 1, y + ch + 1, 13, Anim.argb(accent, 0.55f * a));
				Ui.round(g, x, y, x + cw, y + ch, 12, Anim.argb(Ui.GLASS, op * a));
			}
			case 3 -> {
			}
			default -> {
				Ui.round(g, x, y, x + cw, y + ch, 12, Anim.argb(Ui.GLASS, op * a));
				g.fill(x + 12, y, x + cw - 12, y + 1, Anim.argb(Ui.INK, 0.10f * a));
			}
		}
	}

	private void drawCard(GuiGraphicsExtractor g, int mx, int my) {
		float a = Anim.easeOutCubic(Math.min(1f, (System.currentTimeMillis() - openedAt) / 350f));
		int cw = cardW(), ch = cardH();
		int x = this.width / 2 - cw / 2, y = cardY() + Math.round((1 - a) * 14);
		if (data != null && data.bgEffect == 6) shootingStar(g, this.width, this.height);
		cardBox(g, x, y, cw, ch, data == null ? Ui.INK : data.accentColor(), a);
		if (data == null) {
			String l = "Loading profile...";
			Ui.text(g, l, this.width / 2 - Ui.width(l) / 2, y + ch / 2 - 4, Anim.argb(Ui.MUTED, a));
			return;
		}
		int accent = data.accentColor();
		boolean owner = data.badges.contains("owner");
		boolean compact = data.layout == 2;
		int cx = compact ? x + 96 + (cw - 96) / 2 : this.width / 2;

		// avatar with glowing ring
		int av = compact ? 64 : 44, ax = compact ? x + 18 : cx - av / 2, ay = compact ? y + (ch - av) / 2 - 10 : y + 14;
		float pulse = 0.5f + 0.5f * (float) Math.sin(System.currentTimeMillis() / 500.0);
		if (data.avatarGlow) Ui.round(g, ax - 4, ay - 4, ax + av + 4, ay + av + 4, 10, Anim.argb(owner ? Ui.INK : accent, (0.35f + 0.25f * pulse) * a));
		Ui.round(g, ax - 2, ay - 2, ax + av + 2, ay + av + 2, 9, Anim.argb(Ui.GLASS, a));
		if (avatarTex != null) Compat.blitTint(g, avatarTex, ax, ay, av, av, Anim.argb(0xFFFFFF, a));
		else Ui.head(g, data.name, ax, ay, av, a);
		if (owner) Ui.iconTex(g, "crown", ax + av / 2 - 5, ay - 14, 10, Anim.argb(Ui.INK, a));

		// display name (2x) with effect
		int ny = compact ? y + 18 : ay + av + 10;
		drawName(g, data.displayName, cx, ny, accent, a);
		String handle = "@" + data.name + (data.location.isBlank() ? "" : "   ·   " + data.location);
		int hw = Ui.width(handle) + (data.showViews && data.views > 0 ? Ui.width(String.valueOf(data.views)) + 22 : 0);
		int hx = cx - hw / 2;
		Ui.text(g, handle, hx, ny + 22, Anim.argb(Ui.MUTED, a));
		if (data.showViews && data.views > 0) {
			int vx = hx + Ui.width(handle) + 10;
			Ui.iconTex(g, "eye", vx, ny + 21, 9, Anim.argb(Ui.MUTED, a));
			Ui.text(g, String.valueOf(data.views), vx + 11, ny + 22, Anim.argb(Ui.MUTED, a));
		}

		// badges + tags
		List<String[]> pills = new ArrayList<>();
		if (owner) pills.add(new String[]{"Owner of Kyoto Client", "FFD36B"});
		if (data.badges.contains("kyoto") || data.found) pills.add(new String[]{"Kyoto", Integer.toHexString(accent)});
		for (String t : data.tags) pills.add(new String[]{t, Integer.toHexString(accent)});
		int total = 0;
		for (String[] p : pills) total += Ui.width(p[0]) + 14 + 4;
		int px = cx - total / 2, py = ny + 36;
		for (String[] p : pills) {
			int col = Integer.parseInt(p[1].length() > 6 ? p[1].substring(p[1].length() - 6) : p[1], 16);
			int pw = Ui.width(p[0]) + 14;
			Ui.round(g, px, py, px + pw, py + 13, 6, Anim.argb(col, 0.18f * a));
			Ui.text(g, p[0], px + 7, py + 3, Anim.argb(col, a));
			px += pw + 4;
		}

		// bio
		int by = py + 20;
		String bio = data.found ? data.bio : data.error;
		if (bio != null && !bio.isBlank()) {
			for (String line : PanelScreen.wrap(bio, cw - 36, 4)) {
				Ui.text(g, line, cx - Ui.width(line) / 2, by, Anim.argb(data.found ? Ui.mix(data.textColor, Ui.INK, 0.15f) : Ui.INK, a));
				by += 11;
			}
		}

		// music bar
		if (data.audioExt != null) {
			int mby = this.height - 30;
			Ui.iconTex(g, "music", cx - 112, mby + 6, 9, Anim.argb(accent, a));
			String song = "   " + Ui.ellipsize(data.audioName.isBlank() ? "Profile music" : data.audioName.replaceAll("\\.(mp3|wav)$", ""), 130)
					+ "  " + Math.round(AudioPlayer.volume() * 100) + "%";
			Ui.round(g, cx - 120, mby - 2, cx + 140, mby + 22, 8, Anim.argb(Ui.GLASS, 0.6f * a));
			Ui.text(g, song, cx - 112, mby + 7, Anim.argb(Ui.TEXT, a));
		}
		if (video != null && !video.done && video.frames.isEmpty()) {
			String l = "Loading background " + Math.round(video.progress * 100) + "%";
			Ui.text(g, l, 8, this.height - 14, Anim.argb(Ui.MUTED, a));
		}
		if (video != null && video.error != null) Ui.text(g, video.error, 8, this.height - 14, Anim.argb(0xFF7777, a));
	}

	private void drawName(GuiGraphicsExtractor g, String name, int cx, int y, int accent, float a) {
		int effect = data.nameEffect;
		long now = System.currentTimeMillis();
		String shown = name;
		if (effect == 4) { // typewriter
			int n = (int) Math.min(name.length(), (now - openedAt) / 70);
			shown = name.substring(0, n) + ((now / 400) % 2 == 0 ? "_" : " ");
		}
		int w = Ui.widthBold(name);
		g.pose().pushMatrix();
		g.pose().translate(cx - w, y);
		g.pose().scale(2f, 2f);
		if (effect == 1) { // glow: soft colored copies behind
			for (int i = 0; i < 4; i++) {
				int ox = (i % 2 == 0 ? 1 : -1), oy = (i < 2 ? 1 : -1);
				g.text(Ui.font(), Ui.tb(shown), ox, oy, Anim.argb(accent, 0.25f * a), false);
			}
		}
		if (effect == 2 || effect == 3) { // per-letter rainbow / wave
			int x = 0;
			for (int i = 0; i < shown.length(); i++) {
				String ch = String.valueOf(shown.charAt(i));
				int col = effect == 2 ? Ui.hsv((now / 8f + i * 25) % 360, 0.55f, 1f) : accent;
				int oy = effect == 3 ? Math.round((float) Math.sin(now / 180.0 + i * 0.6) * 1.5f) : 0;
				g.text(Ui.font(), Ui.tb(ch), x, oy, Anim.argb(col, a), true);
				x += Ui.widthBold(ch);
			}
		} else {
			g.text(Ui.font(), Ui.tb(shown), 0, 0, Anim.argb(data.textColor, a), true);
		}
		g.pose().popMatrix();
	}

	@Override
	public void onClose() {
		AudioPlayer.stop();
		if (video != null) video.close();
		Compat.openScreen(parent);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
