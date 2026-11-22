package dev.kyoto;

import com.mojang.blaze3d.platform.InputConstants;
import dev.kyoto.config.KyotoConfig;
import dev.kyoto.feature.ChatTweaks;
import dev.kyoto.feature.Commands;
import dev.kyoto.feature.CustomHotkeys;
import dev.kyoto.feature.Fullbright;
import dev.kyoto.feature.Performance;
import dev.kyoto.feature.PickupFeed;
import dev.kyoto.feature.ServerAlerts;
import dev.kyoto.feature.SkyFx;
import dev.kyoto.feature.Warnings;
import dev.kyoto.feature.Waypoints;
import dev.kyoto.feature.WorldFx;
import dev.kyoto.feature.Zoom;
import dev.kyoto.hud.Notifications;
import dev.kyoto.hud.KyotoHud;
import dev.kyoto.module.Modules;
import dev.kyoto.music.Spotify;
import dev.kyoto.screen.ClickGui;
import dev.kyoto.screen.WaypointScreen;
import dev.kyoto.util.Compat;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class KyotoClient implements ClientModInitializer {
	public static final String MOD_ID = "kyoto";
	public static final Logger LOG = LoggerFactory.getLogger(MOD_ID);

	public static boolean openSettingsNextTick = false;

	private static String version;

	/** The mod's version, like "8.1.0". */
	public static String version() {
		if (version == null) version = net.fabricmc.loader.api.FabricLoader.getInstance().getModContainer(MOD_ID).map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("");
		return version;
	}
	/** Screens opened from chat commands wait one tick so the chat box can close first. */
	public static java.util.function.Supplier<net.minecraft.client.gui.screens.Screen> pendingScreen = null;

	private static KeyMapping menuKey, zoomKey, addWaypointKey, waypointMenuKey, toggleHudKey, shareKey, pearlKey,
			fullbrightKey, tpAcceptKey, tpDenyKey, musicPauseKey, musicNextKey, lyricsKey, wideZoomKey, pttKey, muteKey, answerKey;
	private static final KeyMapping[] CUSTOM = new KeyMapping[6];
	private static KeyMapping teamChatKey, requeueKey, placesKey;
	private static KeyMapping quickWheelKey, inspectKey;
	private static KeyMapping freelookKey, clipKey, cleanShotKey, emoteKey, lightKey, chunkKey, slimeKey, studioKey;

	@Override
	public void onInitializeClient() {
		dev.kyoto.feature.HandAnim.findRenderer();
		KyotoConfig.load();
		Modules.init();
		makeFolders();
		Performance.init();
		registerKeys();

		ClientTickEvents.END_CLIENT_TICK.register(KyotoClient::tick);
		// placing a video screen: runs before Minecraft handles clicks, so right/left click don't also use/break blocks
		ClientTickEvents.START_CLIENT_TICK.register(dev.kyoto.screens.Screens::placingTick);
		net.fabricmc.fabric.api.event.player.UseBlockCallback.EVENT.register((pl, lv, hand, hit) ->
				dev.kyoto.screens.Screens.isPlacing() ? net.minecraft.world.InteractionResult.FAIL : net.minecraft.world.InteractionResult.PASS);
		net.fabricmc.fabric.api.event.player.UseItemCallback.EVENT.register((pl, lv, hand) ->
				dev.kyoto.screens.Screens.isPlacing() ? net.minecraft.world.InteractionResult.FAIL : net.minecraft.world.InteractionResult.PASS);
		net.fabricmc.fabric.api.event.player.AttackBlockCallback.EVENT.register((pl, lv, hand, pos, dir) ->
				dev.kyoto.screens.Screens.isPlacing() ? net.minecraft.world.InteractionResult.FAIL : net.minecraft.world.InteractionResult.PASS);
		// Build Recorder: watch what you place / use / break (never changes anything)
		net.fabricmc.fabric.api.event.player.UseBlockCallback.EVENT.register((pl, lv, hand, hit) -> {
			if (pl == Minecraft.getInstance().player) {
				dev.kyoto.feature.BuildRecorder.onUse(hit.getBlockPos(), hit.getDirection());
				if (pl.getItemInHand(hand).getItem() instanceof net.minecraft.world.item.BlockItem) dev.kyoto.extra.Rewards.onPlace();
			}
			return net.minecraft.world.InteractionResult.PASS;
		});
		net.fabricmc.fabric.api.event.player.UseItemCallback.EVENT.register((pl, lv, hand) -> {
			if (pl == Minecraft.getInstance().player) dev.kyoto.feature.BuildRecorder.onUseItem();
			return net.minecraft.world.InteractionResult.PASS;
		});
		// video screens are drawn inside the world (behind blocks, your hand and the crosshair; still there with F1)
		dev.kyoto.screens.WorldScreens.init();
		// your knife in first person sits under the hotbar and the rest of the HUD
		HudElementRegistry.attachElementBefore(VanillaHudElements.MISC_OVERLAYS,
				Identifier.fromNamespaceAndPath(MOD_ID, "knife"), (g, delta) -> {
					var w = Minecraft.getInstance().getWindow();
					if (Minecraft.getInstance().player != null)
						dev.kyoto.knife.Viewmodel.render(g, w.getGuiScaledWidth(), w.getGuiScaledHeight(), delta.getGameTimeDeltaPartialTick(false));
				});
		HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT,
				Identifier.fromNamespaceAndPath(MOD_ID, "hud"), KyotoHud::extract);

		ChatTweaks.register();
		dev.kyoto.extra.Extras.register();
		dev.kyoto.servers.ServerEvents.register();
		dev.kyoto.feature.Utility.register();
		net.fabricmc.fabric.api.event.client.player.ClientPlayerBlockBreakEvents.AFTER.register((world, player, pos, state) -> {
			if (player == Minecraft.getInstance().player) {
				dev.kyoto.feature.Singleplayer.onBlockBroken();
				dev.kyoto.feature.BuildRecorder.onBreak(pos, state);
				dev.kyoto.extra.Rewards.onBreak();
			}
		});
		dev.kyoto.stream.StreamAlerts.start();
		dev.kyoto.stream.TikTokLive.start();
		Commands.register();
		Spotify.start();
		dev.kyoto.music.SystemMedia.start();
		dev.kyoto.net.KyotoNet.start();
		dev.kyoto.social.DiscordRpc.start();
		dev.kyoto.ui.MenuHooks.register();
		ClientLifecycleEvents.CLIENT_STARTED.register(client -> {
			dev.kyoto.cosmetic.Capes.reloadLocal();
			dev.kyoto.cosmetic.Skins.reloadLocal();
			if (dev.kyoto.net.KyotoNet.configured() && !dev.kyoto.net.KyotoNet.loggedIn()) dev.kyoto.net.KyotoNet.login();
		});

		AttackEntityCallback.EVENT.register((player, level, hand, entity, hitResult) -> {
			if (player == Minecraft.getInstance().player) {
				dev.kyoto.feature.Combat.onAttack(entity);
				dev.kyoto.extra.Rewards.onAttack(entity);
				dev.kyoto.feature.ClickSounds.onAttack(entity);
				KyotoHud.onHit();
			}
			return InteractionResult.PASS; // never changes the attack itself
		});

		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> client.execute(() -> {
			Waypoints.loadForCurrentWorld();
			dev.kyoto.feature.Utility.onJoin(client);
			dev.kyoto.screens.Screens.onJoin();
			dev.kyoto.feature.ServerProfiles.onJoin();
			dev.kyoto.servers.ServerEvents.onJoin(client);
			dev.kyoto.net.KyotoNet.resetCosmeticsCache();
			Notifications.push("Kyoto Client", "Right Shift = menu  ·  N = waypoints", Notifications.MAGIC, 6000, null, "✦");
		}));
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> client.execute(() -> {
			if (dev.kyoto.feature.BuildRecorder.recording()) dev.kyoto.feature.BuildRecorder.stop(); // never lose a build
			dev.kyoto.servers.ServerEvents.onLeave();
			dev.kyoto.social.Rooms.leave();
			Zoom.restore(client);
			Waypoints.unload();
			dev.kyoto.screens.Screens.onLeave();
			dev.kyoto.screens.MiniPlayer.stop();
		}));
		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
			Zoom.restore(client);
			Performance.restore();
			dev.kyoto.music.SystemMedia.stop();
			dev.kyoto.screens.Screens.onLeave();
			dev.kyoto.social.Calls.hangup();
			Waypoints.save();
			KyotoConfig.save();
		});
		LOG.info("Kyoto Client ready - {} modules", Modules.all().size());
	}

	/** Creates .minecraft/kyoto/... so you can drop files in straight away. */
	private static void makeFolders() {
		try {
			java.nio.file.Path root = net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir().resolve("kyoto");
			for (String sub : new String[]{"capes", "skins", "profile_video", "profile_audio"}) java.nio.file.Files.createDirectories(root.resolve(sub));
			java.nio.file.Path readme = root.resolve("READ ME.txt");
			if (!java.nio.file.Files.exists(readme)) {
				java.nio.file.Files.writeString(readme, String.join(System.lineSeparator(),
						"Kyoto Client folders",
						"",
						"capes/          PNG or GIF capes (64x32 or 512x256 image, GIF for animated). Pick one in Cosmetics.",
						"skins/          64x64 skin PNGs. Pick one in Cosmetics > Skins.",
						"profile_video/  MP4 or GIF background for your profile page (muted, max 15 MB).",
						"profile_audio/  MP3 or WAV song for your profile page (max 10 MB).",
						"",
						"After adding files, open My Profile > Edit (or Cosmetics) and press Refresh."));
			}
		} catch (Exception e) {
			LOG.warn("Couldn't create the kyoto folders", e);
		}
	}

	private static void registerKeys() {
		KeyMapping.Category cat = KeyMapping.Category.register(Identifier.fromNamespaceAndPath(MOD_ID, "main"));
		int none = InputConstants.UNKNOWN.getValue();
		menuKey = key("menu", InputConstants.KEY_RSHIFT, cat);
		zoomKey = key("zoom", InputConstants.KEY_C, cat);
		addWaypointKey = key("add_waypoint", InputConstants.KEY_B, cat);
		waypointMenuKey = key("waypoint_menu", InputConstants.KEY_N, cat);
		toggleHudKey = key("toggle_hud", InputConstants.KEY_H, cat);
		shareKey = key("share_location", InputConstants.KEY_U, cat);
		pearlKey = key("toggle_pearl", InputConstants.KEY_J, cat);
		fullbrightKey = key("fullbright", InputConstants.KEY_G, cat);
		tpAcceptKey = key("tpaccept", none, cat);
		tpDenyKey = key("tpdeny", none, cat);
		musicPauseKey = key("music_pause", none, cat);
		musicNextKey = key("music_next", none, cat);
		lyricsKey = key("lyrics_menu", InputConstants.KEY_K, cat);
		wideZoomKey = key("wide_zoom", InputConstants.KEY_V, cat);
		pttKey = key("call_ptt", none, cat);
		muteKey = key("call_mute", none, cat);
		answerKey = key("call_answer", none, cat);
		for (int i = 0; i < 6; i++) CUSTOM[i] = key("custom_" + (i + 1), none, cat);
		teamChatKey = key("team_chat", none, cat);
		requeueKey = key("requeue", none, cat);
		placesKey = key("places", none, cat);
		freelookKey = key("freelook", InputConstants.KEY_LALT, cat);
		clipKey = key("save_clip", none, cat);
		cleanShotKey = key("clean_shot", none, cat);
		emoteKey = key("emotes", InputConstants.KEY_Z, cat);
		quickWheelKey = key("quick_wheel", InputConstants.KEY_X, cat);
		inspectKey = key("inspect_knife", InputConstants.KEY_G, cat);
		lightKey = key("light_levels", none, cat);
		chunkKey = key("chunk_borders", none, cat);
		slimeKey = key("slime_chunks", none, cat);
		studioKey = key("screenshot_studio", none, cat);
	}

	/** The key a mapping is bound to right now (for hold-and-release menus). */
	private static int keyCode(KeyMapping k) {
		try {
			Object key = k.getClass().getMethod("getKey").invoke(k);
			return (int) key.getClass().getMethod("getValue").invoke(key);
		} catch (Throwable t) {
			return InputConstants.KEY_Z;
		}
	}

	private static KeyMapping key(String name, int code, KeyMapping.Category cat) {
		return KeyMappingHelper.registerKeyMapping(new KeyMapping("key." + MOD_ID + "." + name, InputConstants.Type.KEYBOARD, code, cat));
	}

	private static void tick(Minecraft mc) {
		if (openSettingsNextTick && Compat.currentScreen() == null) {
			openSettingsNextTick = false;
			Compat.openScreen(dev.kyoto.screen.KyotoMenu.create());
		}
		if (pendingScreen != null && Compat.currentScreen() == null) {
			var s = pendingScreen;
			pendingScreen = null;
			Compat.openScreen(s.get());
		}
		Performance.tick(mc);
		dev.kyoto.servers.ServerEvents.tick(mc);
		dev.kyoto.social.Calls.tick();
		dev.kyoto.social.Social.tick();
		dev.kyoto.social.VoiceRooms.tick();
		dev.kyoto.social.Rooms.tick(mc);
		dev.kyoto.feature.LauncherLink.tick(mc);
		dev.kyoto.extra.Extras.tick(mc, freelookKey != null && freelookKey.isDown() && Compat.currentScreen() == null);
		if (mc.player == null) {
			Zoom.tick(mc, false, false);
			return;
		}
		handleKeys(mc);
		Zoom.tick(mc, zoomKey.isDown() && Compat.currentScreen() == null, wideZoomKey.isDown() && Compat.currentScreen() == null);
		Fullbright.tick(mc);
		dev.kyoto.feature.Combat.tick(mc);
		dev.kyoto.hud.CombatHud.tick(mc);
		dev.kyoto.hud.FightFx.tick(mc);
		dev.kyoto.feature.Dances.tick(mc);
		dev.kyoto.cosmetic.BurstFx.tick(mc);
		if (mc.level != null && !(Compat.currentScreen() instanceof dev.kyoto.screen.KyotoMenu)) dev.kyoto.ui.MenuMedia.musicStop();
		dev.kyoto.feature.Utility.tick(mc);
		dev.kyoto.screens.Screens.tick(mc);
		dev.kyoto.screens.MiniPlayer.tick(mc);
		dev.kyoto.cosmetic.Accessories.tick(mc);
		dev.kyoto.cosmetic.Kycos.tick(mc);
		dev.kyoto.feature.BuildRecorder.tick(mc);
		dev.kyoto.feature.Fishing.tick(mc);
		dev.kyoto.feature.ClickSounds.tick(mc);
		Waypoints.tick(mc);
		Warnings.tick(mc);
		PickupFeed.tick(mc);
		WorldFx.tick(mc);
		SkyFx.tick(mc);
		KyotoHud.tick(mc);
		dev.kyoto.feature.Singleplayer.tick(mc);
	}

	private static void toggle(String id, String label) {
		Modules.Module m = Modules.get(id);
		m.toggle();
		Notifications.push(label + (m.on() ? " ON" : " OFF"), "", m.on() ? Notifications.GOOD : Notifications.INFO, 1500);
	}

	private static void handleKeys(Minecraft mc) {
		while (menuKey.consumeClick()) Compat.openScreen(dev.kyoto.screen.KyotoMenu.create());
		while (teamChatKey.consumeClick()) dev.kyoto.servers.ServerEvents.toggleTeamChat();
		while (requeueKey.consumeClick()) dev.kyoto.servers.Hypixel.requeue();
		while (placesKey.consumeClick()) Compat.openScreen(new dev.kyoto.servers.PackScreens.Places(null));
		while (clipKey.consumeClick()) dev.kyoto.extra.ReplayBuffer.save();
		while (cleanShotKey.consumeClick()) dev.kyoto.extra.Shots.cleanShot();
		while (inspectKey.consumeClick()) dev.kyoto.knife.Viewmodel.inspect();
		while (quickWheelKey.consumeClick()) if (Compat.currentScreen() == null) Compat.openScreen(new dev.kyoto.screen.KyotoWheel(keyCode(quickWheelKey), false));
		while (emoteKey.consumeClick()) if (Compat.currentScreen() == null) Compat.openScreen(new dev.kyoto.screen.KyotoWheel(keyCode(emoteKey), true));
		while (lightKey.consumeClick()) dev.kyoto.extra.Extras.toggleOverlay("light");
		while (chunkKey.consumeClick()) dev.kyoto.extra.Extras.toggleOverlay("chunks");
		while (slimeKey.consumeClick()) dev.kyoto.extra.Extras.toggleOverlay("slime");
		while (studioKey.consumeClick()) Compat.openScreen(new dev.kyoto.extra.StudioScreen(null));
		while (waypointMenuKey.consumeClick()) Compat.openScreen(new WaypointScreen());
		while (addWaypointKey.consumeClick()) {
			Waypoints.Waypoint w = Waypoints.addHere(null, Waypoints.Type.MANUAL);
			if (w != null) {
				Notifications.push("Waypoint added: " + w.name, w.x + ", " + w.y + ", " + w.z, w.color, 3500, null, "◎");
				Waypoints.sound(1.2f);
			}
		}
		while (toggleHudKey.consumeClick()) toggle("hud_master", "HUD");
		while (pearlKey.consumeClick()) toggle("pearl_trajectory", "Pearl trajectory");
		while (fullbrightKey.consumeClick()) toggle("fullbright", "Fullbright");
		while (shareKey.consumeClick()) {
			if (Modules.on("location_share")) shareInChat("Here", mc.player.getBlockX(), mc.player.getBlockY(), mc.player.getBlockZ());
		}
		while (tpAcceptKey.consumeClick()) Compat.sendCommand("tpaccept");
		while (tpDenyKey.consumeClick()) Compat.sendCommand("tpdeny");
		while (musicPauseKey.consumeClick()) dev.kyoto.music.Music.control("toggle");
		while (musicNextKey.consumeClick()) dev.kyoto.music.Music.control("next");
		while (lyricsKey.consumeClick()) if (Compat.currentScreen() == null) Compat.openScreen(new dev.kyoto.screen.LyricsScreen(null));
		CustomHotkeys.handle(CUSTOM);
		dev.kyoto.social.Calls.pttDown = pttKey.isDown();
		while (muteKey.consumeClick()) dev.kyoto.social.Calls.toggleMute();
		while (answerKey.consumeClick()) {
			if (dev.kyoto.social.Calls.ringing() != null) dev.kyoto.social.Calls.accept();
			else if (dev.kyoto.social.Calls.inCall()) dev.kyoto.social.Calls.hangup();
		}
	}

	/** Posts "[WP] name @ x y z" in public chat. Anyone with Kyoto gets a clickable [+ Waypoint]. */
	public static void shareInChat(String name, int x, int y, int z) {
		Compat.sendPublicChat("[WP] " + name + " @ " + x + " " + y + " " + z);
		Notifications.push("Shared in chat", name + " @ " + x + " " + y + " " + z, Notifications.GOOD);
	}
}
