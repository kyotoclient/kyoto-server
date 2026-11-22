package dev.kyoto.ui;

/**
 * The Help tab. Each topic has a few quick steps shown in game, and an optional link to a full guide.
 *
 *  >>> PUT YOUR PASTEBIN (or any) LINKS HERE <<<
 *  Paste the link between the quotes of the matching topic, e.g.  "https://pastebin.com/abc123"
 *  Leave it "" to hide the "Open full guide" button for that topic. Rebuild after changing.
 */
public final class Guides {
	private Guides() {}

	public record Topic(String icon, String title, String link, String... steps) {}

	/** >>> Your Discord invite link (e.g. "https://discord.gg/abc123"). Shows a Discord button on the title screen + Help. <<< */
	public static final String DISCORD_INVITE = "";

	public static final Topic[] TOPICS = {
			new Topic("sparkle", "Getting started", "",
					"Right Shift opens the menu. Click a card to turn it on or off.",
					"Click the › on a card (or right-click it) to open its settings.",
					"The search box at the top searches every tab at once."),
			new Topic("chat", "Kyoto Discord", DISCORD_INVITE,
					"Suggest features, report bugs and find people to play with.",
					"We read every suggestion - a lot of Kyoto came from them."),
			new Topic("screen", "Video screens", "",
					"Tools > Video Screens > New screen here, then paste a YouTube link.",
					"Kyoto players on the same server watch and hear it with you.",
					"Don't want to see other people's? World tab > See Other Screens."),
			new Topic("sky", "Sky + time of day", "",
					"Tools > Sky + Time: pick a day sky and a night sky.",
					"My time of day makes it day or night just for you.",
					"Only visual - the server, mobs and beds use the real time."),
			new Topic("cosmetics", "Capes + particles", "",
					"Tools > Cosmetics: pick a cape (.png or animated .gif) or particles.",
					"Press F5 to see yourself.",
					"Press Share so other Kyoto players see it."),
			new Topic("call", "Calling friends", "",
					"Install Simple Voice Chat (just the mod, any server works).",
					"Friends > Call next to an online friend. They get a ring to accept.",
					"Use headphones so they don't hear themselves back."),
			new Topic("dm", "Messages", "",
					"Tools > Messages, or Friends > Message.",
					"The smiley opens emojis, the picture button sends an image.",
					"Pictures only work between friends. Block anyone, anytime."),
			new Topic("music", "Music + lyrics", "",
					"Just play music (Spotify Free, YouTube...) - Windows picks it up.",
					"Spotify Premium: /kyoto spotify <client id>, then /kyoto spotify login.",
					"Music tab: lyric style, distance, walls, fade on pause, font."),
			new Topic("profile", "Your profile", "",
					"Tools > My Profile > Edit: name, bio, tags, links.",
					"Videos go in kyoto/profile_video, songs in kyoto/profile_audio.",
					"Press Refresh, pick them, then Save."),
			new Topic("cape", "Capes + skins", "",
					"Capes: .png/.gif in kyoto/capes. Skins: 64x64 .png in kyoto/skins.",
					"Cosmetics > Skins > Set as my Minecraft skin changes your real skin.",
					"Press Share so other Kyoto players see your cape."),
			new Topic("stream", "Stream alerts", "",
					"Make a free Streamlabs account and connect your stream.",
					"Tools > Streamer > Get my token, copy it, press Paste token.",
					"Test donation shows what viewers' alerts look like."),
			new Topic("mods", "Adding + removing mods", "",
					"Tools > Mods: switch any mod off or on.",
					"Minecraft loads mods once at startup, so it asks to restart.",
					"Kyoto's own features switch instantly, no restart."),
			new Topic("help", "Something broken?", "",
					"Crash: send the file from .minecraft/crash-reports.",
					"Anything weird: send .minecraft/logs/latest.log.",
					"Text as boxes: Client tab > Font > pick another font."),
			new Topic("eye", "Freelook", "",
					"Hold Left Alt to look around without turning your player.",
					"Change it to press-to-toggle in All features > Visual > Freelook.",
					"It turns itself off on servers that don't allow it (like Hypixel)."),
			new Topic("screen", "Clips (replay buffer)", "",
					"Turn on Replay Buffer (All features > Utility).",
					"Bind the Save clip key in Options > Controls > Kyoto.",
					"Press it after something cool happens: the last 30 s save to kyoto/clips."),
			new Topic("chat", "Chat tabs + copy", "",
					"Open chat: tabs (All, DMs, Team, Kyoto) sit above it.",
					"Right-click any message to copy it."),
			new Topic("heart", "Pets + emotes", "",
					"Tools > Pets: pick a pet that floats next to you.",
					"Tools > Emotes (or the Emotes key): a sticker pops above your head.",
					"Kyoto players near you see both."),
			new Topic("gift", "Coins: daily reward + weekly challenges", "",
					"Play each day for free coins (more for a streak).",
					"3 challenges every week. Tools > Rewards shows your progress."),
			new Topic("mic", "Nearby voice", "",
					"Needs Simple Voice Chat installed. Then: /kyoto nearbyvoice on",
					"Kyoto players near you on the same server hear you; closer = louder."),
			new Topic("world", "Light levels, chunks, slime chunks", "",
					"Bind the keys in Controls > Kyoto to show/hide each one.",
					"Slime chunks need the seed: /kyoto seed <number>. Map: /kyoto slime."),
	};
}
