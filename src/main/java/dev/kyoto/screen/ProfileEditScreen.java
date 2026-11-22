package dev.kyoto.screen;

import dev.kyoto.config.KyotoConfig;
import dev.kyoto.profile.Profiles;
import dev.kyoto.util.Anim;
import dev.kyoto.util.Compat;
import dev.kyoto.util.Ui;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Edit your Kyoto profile: name, bio, tags, links, colors, effects, background video and music. */
public class ProfileEditScreen extends PanelScreen {
	public ProfileEditScreen(Screen parent) {
		super("Edit Profile", parent);
	}

	@Override
	protected String subtitle() {
		return "Videos: .minecraft/kyoto/profile_video (mp4/gif, muted)  ·  Music: .minecraft/kyoto/profile_audio (mp3/wav)";
	}

	private KyotoConfig.Profile p() {
		return KyotoConfig.get().profile;
	}

	private EditBox box(int x, int y, int w, String hint, String value, int max, Consumer<String> onChange) {
		EditBox b = new EditBox(this.font, x, y, w, 18, Component.literal(hint));
		b.setMaxLength(max);
		b.setHint(Ui.t(hint));
		b.setValue(value == null ? "" : value);
		b.setResponder(s -> {
			onChange.accept(s);
			KyotoConfig.save();
		});
		this.addRenderableWidget(b);
		return b;
	}

	@Override
	protected String icon() {
		return "profile";
	}

	@Override
	protected void build() {
		int lx = px + 14, y = py + 38, w = 220;
		box(lx, y, w, "Display name", p().displayName, 24, s -> p().displayName = s);
		y += 22;
		box(lx, y, w, "Bio - say something cool", p().bio, 160, s -> p().bio = s);
		y += 22;
		box(lx, y, w, "Tags, comma separated (max 5)", String.join(", ", p().tags), 80, s -> {
			List<String> tags = new ArrayList<>();
			for (String t : s.split(",")) {
				String tt = t.trim();
				if (!tt.isEmpty() && tags.size() < 5) tags.add(tt.length() > 16 ? tt.substring(0, 16) : tt);
			}
			p().tags = tags;
		});
		y += 26;
		for (int i = 0; i < 4; i++) {
			final int idx = i;
			box(lx, y, w, "Link " + (i + 1) + " (youtube.com/@you, discord.gg/...)", p().links.get(i), 120, s -> p().links.set(idx, s));
			y += 22;
		}

		int rx = px + 248, ry = py + 38, step = 22;
		List<String> videos = Profiles.files("profile_video", ".mp4", ".gif");
		List<String> audios = Profiles.files("profile_audio", ".mp3", ".wav");
		rowAt(rx, ry, "Color", () -> Math.round(p().hue) + "°", () -> { p().hue = (p().hue + 345) % 360; KyotoConfig.save(); }, () -> { p().hue = (p().hue + 15) % 360; KyotoConfig.save(); });
		ry += step;
		rowAt(rx, ry, "Name effect", () -> Profiles.NAME_EFFECTS[p().nameEffect % Profiles.NAME_EFFECTS.length],
				() -> { p().nameEffect = Math.floorMod(p().nameEffect - 1, Profiles.NAME_EFFECTS.length); KyotoConfig.save(); },
				() -> { p().nameEffect = (p().nameEffect + 1) % Profiles.NAME_EFFECTS.length; KyotoConfig.save(); });
		ry += step;
		rowAt(rx, ry, "Effect", () -> Profiles.BG_EFFECTS[p().bgEffect % Profiles.BG_EFFECTS.length],
				() -> { p().bgEffect = Math.floorMod(p().bgEffect - 1, Profiles.BG_EFFECTS.length); KyotoConfig.save(); },
				() -> { p().bgEffect = (p().bgEffect + 1) % Profiles.BG_EFFECTS.length; KyotoConfig.save(); });
		ry += step;
		rowAt(rx, ry, "Background", () -> p().videoFile.isBlank() ? "None" : p().videoFile,
				() -> { p().videoFile = cycle(videos, p().videoFile, -1); KyotoConfig.save(); },
				() -> { p().videoFile = cycle(videos, p().videoFile, 1); KyotoConfig.save(); });
		ry += step;
		rowAt(rx, ry, "Music", () -> p().audioFile.isBlank() ? "None" : p().audioFile,
				() -> { p().audioFile = cycle(audios, p().audioFile, -1); KyotoConfig.save(); },
				() -> { p().audioFile = cycle(audios, p().audioFile, 1); KyotoConfig.save(); });
		ry += step + 4;
		btn(rx, ry, 106, "Open video folder", () -> Compat.openFolder(Profiles.folder("profile_video")));
		btn(rx + 110, ry, 106, "Open music folder", () -> Compat.openFolder(Profiles.folder("profile_audio")));
		ry += step;
		btn(rx, ry, 106, "Preview", () -> Compat.openScreen(new ProfileScreen(this, Compat.myName())));
		btn(rx + 110, ry, 106, "Publish", Profiles::publish);
	}

	/** Steps through "None" + the files in a folder. */
	private static String cycle(List<String> files, String current, int dir) {
		List<String> all = new ArrayList<>();
		all.add("");
		all.addAll(files);
		int i = Math.max(0, all.indexOf(current));
		return all.get(Math.floorMod(i + dir, all.size()));
	}

	@Override
	protected void drawContent(GuiGraphicsExtractor g, int mx, int my, float a) {
		int x = px + 248, y = py + ph - 74;
		for (String s : wrap("Tip: big videos load slowly. Short, small MP4s (H.264, under 15 MB, ~10 seconds) work best. Backgrounds are always muted - pick your music separately.", 216, 5)) {
			Ui.text(g, s, x, y, Anim.argb(Ui.MUTED, a));
			y += 10;
		}
	}
}
