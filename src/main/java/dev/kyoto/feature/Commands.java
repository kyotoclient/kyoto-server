package dev.kyoto.feature;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import dev.kyoto.KyotoClient;
import dev.kyoto.config.KyotoConfig;
import dev.kyoto.util.Compat;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.Style;
import dev.kyoto.music.Lyrics;
import dev.kyoto.music.Spotify;
import java.net.URI;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;

/**
 * Client-only commands. The server never sees them, so they work everywhere.
 *   /wp add [name] [x y z]   /wp remove name   /wp list   /wp track name   /wp untrack
 *   /wp share name   /wp clear deaths
 *   /kyoto                 (opens settings)
 *   /kyoto mute word | unmute word | mention word | unmention word
 */
public final class Commands {
	private Commands() {}

	public static void register() {
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
			registerWp(dispatcher);
			registerKyotoCmd(dispatcher);
		});
	}

	private static void registerWp(CommandDispatcher<FabricClientCommandSource> d) {
		d.register(literal("wp")
				.executes(ctx -> list(ctx))
				.then(literal("add")
						.executes(ctx -> addHere(ctx, null))
						.then(argument("name", StringArgumentType.word())
								.executes(ctx -> addHere(ctx, StringArgumentType.getString(ctx, "name")))
								.then(argument("x", IntegerArgumentType.integer())
										.then(argument("y", IntegerArgumentType.integer())
												.then(argument("z", IntegerArgumentType.integer())
														.executes(ctx -> {
															String name = StringArgumentType.getString(ctx, "name");
															Waypoints.Type type = name.toLowerCase().startsWith("shared") ? Waypoints.Type.SHARED : Waypoints.Type.MANUAL;
															Waypoints.Waypoint w = Waypoints.add(name,
																	IntegerArgumentType.getInteger(ctx, "x"),
																	IntegerArgumentType.getInteger(ctx, "y"),
																	IntegerArgumentType.getInteger(ctx, "z"), type);
															ok(ctx, "Added waypoint " + w.name + " at " + w.x + " " + w.y + " " + w.z);
															Waypoints.sound(1.2f);
															return 1;
														}))))))
				.then(literal("remove").then(argument("name", StringArgumentType.word())
						.executes(ctx -> {
							String n = StringArgumentType.getString(ctx, "name");
							if (Waypoints.remove(n)) ok(ctx, "Removed " + n);
							else err(ctx, "No waypoint called " + n);
							return 1;
						})))
				.then(literal("list").executes(Commands::list))
				.then(literal("track").then(argument("name", StringArgumentType.word())
						.executes(ctx -> {
							Waypoints.Waypoint w = Waypoints.find(StringArgumentType.getString(ctx, "name"));
							if (w == null) err(ctx, "No waypoint with that name");
							else Waypoints.track(w);
							return 1;
						})))
				.then(literal("untrack").executes(ctx -> {
					Waypoints.track(null);
					ok(ctx, "Stopped tracking");
					return 1;
				}))
				.then(literal("share").then(argument("name", StringArgumentType.word())
						.executes(ctx -> {
							Waypoints.Waypoint w = Waypoints.find(StringArgumentType.getString(ctx, "name"));
							if (w == null) {
								err(ctx, "No waypoint with that name");
								return 0;
							}
							KyotoClient.shareInChat(w.name, w.x, w.y, w.z);
							return 1;
						})))
				.then(literal("send").then(argument("name", StringArgumentType.word()).then(argument("player", StringArgumentType.word())
						.executes(ctx -> {
							Waypoints.Waypoint w = Waypoints.find(StringArgumentType.getString(ctx, "name"));
							if (w == null) {
								err(ctx, "No waypoint with that name");
								return 0;
							}
							Waypoints.sendTo(StringArgumentType.getString(ctx, "player"), w);
							return 1;
						}))))
				.then(literal("clear").then(literal("deaths").executes(ctx -> {
					ok(ctx, "Removed " + Waypoints.clearType(Waypoints.Type.DEATH) + " death waypoints");
					return 1;
				}))));
	}

	private static void registerKyotoCmd(CommandDispatcher<FabricClientCommandSource> d) {
		d.register(literal("kyoto")
				.executes(ctx -> {
					KyotoClient.openSettingsNextTick = true; // chat closes first, then the menu opens
					return 1;
				})
				.then(literal("dm").executes(ctx -> {
					dev.kyoto.KyotoClient.pendingScreen = () -> new dev.kyoto.screen.DmScreen(null, null);
					return 1;
				}))
				.then(literal("plus").executes(ctx -> {
					dev.kyoto.net.KyotoNet.refreshMe();
					boolean on = dev.kyoto.net.KyotoNet.mePlus;
					long until = dev.kyoto.net.KyotoNet.mePlusUntil;
					ctx.getSource().sendFeedback(Component.literal(on
							? "You have Kyoto+" + (until > 0 ? " until " + java.time.Instant.ofEpochMilli(until).atZone(java.time.ZoneId.systemDefault()).toLocalDate() : " forever") + ". Thanks for supporting Kyoto!"
							: "Kyoto+ is an optional supporter badge (cosmetic only). Opening Ko-fi - put your Minecraft name in the message.").withStyle(ChatFormatting.LIGHT_PURPLE));
					if (!on) dev.kyoto.net.KyotoNet.runAsync(() -> {
						String url = "https://ko-fi.com";
						try {
							com.google.gson.JsonObject o = com.google.gson.JsonParser.parseString(dev.kyoto.net.KyotoNet.send("GET", "/config", null, false)).getAsJsonObject();
							if (o.has("kofiUrl") && o.get("kofiUrl").getAsString().startsWith("https://")) url = o.get("kofiUrl").getAsString();
						} catch (Exception ignored) { }
						final String u = url;
						Minecraft.getInstance().execute(() -> dev.kyoto.util.Compat.openUrl(u));
					});
					return 1;
				}))
				.then(literal("friends").executes(ctx -> open(() -> new dev.kyoto.screen.FriendsScreen(null))))
				.then(literal("hud").executes(ctx -> open(() -> new dev.kyoto.screen.HudEditorScreen(null))))
				// ---------------- server packs ----------------
				.then(literal("price").then(argument("item", StringArgumentType.greedyString())
						.suggests((c, b) -> { for (String k : dev.kyoto.servers.Market.known()) if (k.startsWith(b.getRemainingLowerCase())) b.suggest(k); return b.buildFuture(); })
						.executes(ctx -> { for (String l : dev.kyoto.servers.Market.priceCheck(StringArgumentType.getString(ctx, "item"))) say(l); return 1; })))
				.then(literal("watch").then(argument("item", StringArgumentType.word())
						.suggests((c, b) -> { for (String k : dev.kyoto.servers.Market.known()) if (k.startsWith(b.getRemainingLowerCase())) b.suggest(k); return b.buildFuture(); })
						.then(argument("price", StringArgumentType.word()).executes(ctx -> {
							say(dev.kyoto.servers.Market.watch(StringArgumentType.getString(ctx, "item"), dev.kyoto.servers.Packs.money(StringArgumentType.getString(ctx, "price"))));
							return 1;
						}))))
				.then(literal("flips").executes(ctx -> { say("Flip profit so far: " + dev.kyoto.servers.Packs.fmt(dev.kyoto.servers.Donut.flipProfit())); return 1; }))
				.then(literal("base").then(literal("add").then(argument("name", StringArgumentType.greedyString()).executes(ctx -> {
					say(dev.kyoto.servers.Donut.addBase(StringArgumentType.getString(ctx, "name")));
					return 1;
				}))))
				.then(literal("emote").executes(ctx -> { open(() -> new dev.kyoto.extra.FunScreen(null, dev.kyoto.extra.FunScreen.Tab.EMOTES)); return 1; })
						.then(argument("name", StringArgumentType.word()).executes(ctx -> { dev.kyoto.extra.Nearby.emote(StringArgumentType.getString(ctx, "name").toLowerCase()); return 1; })))
				.then(literal("pet").executes(ctx -> { open(() -> new dev.kyoto.cosmetic.WardrobeScreen(null, "pet")); return 1; })
						.then(argument("type", StringArgumentType.word()).executes(ctx -> { pet(StringArgumentType.getString(ctx, "type"), ""); return 1; })
								.then(argument("petname", StringArgumentType.greedyString()).executes(ctx -> { pet(StringArgumentType.getString(ctx, "type"), StringArgumentType.getString(ctx, "petname")); return 1; }))))
				.then(literal("profile")
						.executes(ctx -> open(() -> new dev.kyoto.screen.ProfileScreen(null, dev.kyoto.util.Compat.myName())))
						.then(argument("player", StringArgumentType.word()).executes(ctx -> {
							String n = StringArgumentType.getString(ctx, "player");
							return open(() -> new dev.kyoto.screen.ProfileScreen(null, n));
						})))
				.then(literal("music")
						.then(literal("play").executes(ctx -> music("play")))
						.then(literal("pause").executes(ctx -> music("pause")))
						.then(literal("next").executes(ctx -> music("next")))
						.then(literal("prev").executes(ctx -> music("previous")))));
	}

	private static int open(java.util.function.Supplier<net.minecraft.client.gui.screens.Screen> s) {
		KyotoClient.pendingScreen = s;
		return 1;
	}

	private static int music(String action) {
		dev.kyoto.music.Music.control(action.equals("play") || action.equals("pause") ? "toggle" : action);
		return 1;
	}

	private static int addHere(CommandContext<FabricClientCommandSource> ctx, String name) {
		Waypoints.Waypoint w = Waypoints.addHere(name, Waypoints.Type.MANUAL);
		if (w != null) {
			ok(ctx, "Added waypoint " + w.name + " at " + w.x + " " + w.y + " " + w.z);
			Waypoints.sound(1.2f);
		}
		return 1;
	}

	private static int list(CommandContext<FabricClientCommandSource> ctx) {
		LocalPlayer p = Minecraft.getInstance().player;
		if (Waypoints.all().isEmpty()) {
			ok(ctx, "No waypoints yet. Press B or use /wp add <name>");
			return 1;
		}
		ctx.getSource().sendFeedback(Component.literal("Waypoints:").withStyle(ChatFormatting.GOLD));
		for (Waypoints.Waypoint w : Waypoints.sortedByDistance()) {
			String dist = p == null ? "" : " - " + Math.round(w.distanceTo(p.getX(), p.getY(), p.getZ())) + "m";
			ctx.getSource().sendFeedback(Component.literal(" • " + w.name).withStyle(s -> s.withColor(w.color))
					.append(Component.literal(" " + w.x + " " + w.y + " " + w.z + dist + "  (" + w.dimension.replace("minecraft:", "") + ")")
							.withStyle(ChatFormatting.GRAY)));
		}
		return 1;
	}

	private static void ok(CommandContext<FabricClientCommandSource> ctx, String msg) {
		ctx.getSource().sendFeedback(Component.literal("[Kyoto] ").withStyle(ChatFormatting.AQUA).append(Component.literal(msg).withStyle(ChatFormatting.WHITE)));
	}

	private static void err(CommandContext<FabricClientCommandSource> ctx, String msg) {
		ctx.getSource().sendError(Component.literal(msg));
	}

	private static final java.util.List<String> TIKTOK_SUGGEST = new java.util.ArrayList<>();

	static {
		for (dev.kyoto.stream.TikTokLive.GiftInfo g : dev.kyoto.stream.TikTokLive.GIFTS.values())
			TIKTOK_SUGGEST.add(dev.kyoto.stream.TikTokLive.norm(g.name()));
		TIKTOK_SUGGEST.addAll(java.util.List.of("follow", "share", "likes", "superfan", "all", "random"));
	}

	private static int tiktokTest(CommandContext<FabricClientCommandSource> ctx, String gift, int count) {
		if (!dev.kyoto.stream.TikTokLive.test(gift.replace('_', ' '), count))
			ctx.getSource().sendFeedback(Component.literal("Unknown gift. Try: " + String.join(", ", TIKTOK_SUGGEST)).withStyle(ChatFormatting.GRAY));
		return 1;
	}

	private static int buildMsg(com.mojang.brigadier.context.CommandContext<net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource> ctx, String err, String ok) {
		if (err != null) ctx.getSource().sendFeedback(Component.literal(err).withStyle(ChatFormatting.RED));
		else if (ok != null) ctx.getSource().sendFeedback(Component.literal(ok).withStyle(ChatFormatting.LIGHT_PURPLE));
		return 1;
	}

	private static void pet(String type, String name) {
		type = type.toLowerCase();
		if (!java.util.Arrays.asList(dev.kyoto.extra.Nearby.PETS).contains(type)) {
			say("Pets: " + String.join(", ", dev.kyoto.extra.Nearby.PETS));
			return;
		}
		dev.kyoto.extra.Nearby.setPet(type, name);
		say(type.equals("none") ? "Pet put away." : "Your " + type + (name.isEmpty() ? "" : " \"" + name + "\"") + " is out!");
	}

	private static void say(String text) {
		Compat.addChat(Component.literal("[Kyoto] ").withStyle(ChatFormatting.LIGHT_PURPLE).append(Component.literal(text).withStyle(ChatFormatting.GRAY)));
	}
}
