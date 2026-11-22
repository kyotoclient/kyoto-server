package dev.kyoto.screen;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.kyoto.hud.Notifications;
import dev.kyoto.net.KyotoNet;
import dev.kyoto.social.Social;
import dev.kyoto.social.VoiceRooms;
import dev.kyoto.stream.ContentFilter;
import dev.kyoto.util.Anim;
import dev.kyoto.util.Compat;
import dev.kyoto.util.Ui;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Kyoto Hub inside the client (the same Hub as the website):
 *  Chat    - one public room shared with the website and the Kyoto Discord: stickers, reactions, replies, polls, pins
 *  DMs     - opens Messages (DMs + group chats, streaks, status)
 *  Voice   - Voice Rooms with Kyoto players in game and on the website
 *  LFG     - Looking For Group: find people to play with right now
 *  Servers - reviews from Kyoto players who actually played there
 *  Trades  - swap cosmetics with friends (both confirm), gift things
 *  Mods    - staff only
 */
public class HubScreen extends PanelScreen {
	public static int tab = 0;
	private static final String[] TABS = {"Chat", "DMs", "Voice", "LFG", "Servers", "Trades", "Mods"};
	public static final int CHAT = 0, DMS = 1, VOICE = 2, LFG = 3, SERVERS = 4, TRADES = 5, MODS = 6;

	private EditBox input;
	private String draft = "";
	private final ChatView chat = new ChatView();
	private JsonObject replyTo = null;
	private boolean stickers = false, showPins = false;
	private final List<JsonObject> pins = new ArrayList<>();
	private final List<JsonObject> items = new ArrayList<>();
	private JsonObject detail = null; // server / trade detail
	private boolean mod = false;
	private String error = null;
	private long lastPoll = 0, chatSince = 0;
	/** Hub chat channel: "" = General, otherwise the server you're playing on ("donutsmp.net"). */
	private static String chatCh = null;

	private static String serverCh() {
		String h = dev.kyoto.servers.Packs.host;
		return h == null ? "" : h.toLowerCase(java.util.Locale.ROOT).replaceAll("^(play|mc|join|hub)\\.", "");
	}

	private static String chName(String c) {
		return c.isEmpty() ? "General" : "#" + c.replaceAll("\\.(net|com|org|gg|co|io|me|club|uk)$", "");
	}

	private String ch() {
		if (chatCh == null) chatCh = dev.kyoto.module.Modules.on("hub_server_channel") ? serverCh() : "";
		return chatCh;
	}

	private void setCh(String c) {
		chatCh = c;
		chatSince = 0;
		chat.messages.clear();
		pins.clear();
		lastPoll = 0;
	}
	private float scroll = 0, scrollTarget = 0, maxScroll = 0;
	private static final Map<Integer, Identifier> IMAGES = new HashMap<>();
	private static final Set<Integer> LOADING = java.util.concurrent.ConcurrentHashMap.newKeySet();
	// LFG post form
	private static final String[] GAMES = {"Bedwars", "Skywars", "Duels", "SMP", "Survival", "Skyblock", "Factions", "Prison", "Minigames", "Parkour", "Build Battle", "Hardcore", "Speedrun", "Creative", "Just chill"};
	private static int lfgGame = 0, lfgSlots = 4;
	private static boolean lfgMic = false;
	// trade builder
	private int tradeStep = 0; // 0 list, 1 pick friend, 2 pick items
	private String tradeWith = null;
	private final List<JsonObject> myItems = new ArrayList<>(), theirItems = new ArrayList<>();
	private final Set<Integer> give = new LinkedHashSet<>(), want = new LinkedHashSet<>();
	private JsonObject editing = null;
	private int reviewStars = 0;

	private record Hit(int x1, int y1, int x2, int y2, Runnable r) {}

	private final List<Hit> hits = new ArrayList<>();

	public HubScreen(Screen parent) {
		super("Kyoto Hub", parent);
		chat.where = "chat";
		chat.refresh = () -> lastPoll = 0;
		chat.onReply = m -> { replyTo = m; rebuildWidgets(); };
		chat.onProfile = n -> Compat.openScreen(new ProfileScreen(this, n));
		chat.onOpenTrade = id -> { tab = TRADES; openTrade(id); };
	}

	public HubScreen(Screen parent, int startTab) {
		this(parent);
		tab = startTab;
	}

	@Override
	protected String icon() {
		return "hub";
	}

	@Override
	protected int maxW() {
		return 600;
	}

	@Override
	protected int maxH() {
		return 350;
	}

	@Override
	protected String subtitle() {
		if (!KyotoNet.loggedIn()) return "Log in to your Kyoto server first (see Help > Kyoto server).";
		return switch (tab) {
			case VOICE -> VoiceRooms.inRoom() ? "In \"" + VoiceRooms.roomName + "\" · " + VoiceRooms.members.size() + " connected" : "Talk with Kyoto players, in game and on the website.";
			case LFG -> "Find people to play with right now. Joining gets you a group chat.";
			case SERVERS -> "Real reviews from Kyoto players who played there.";
			case TRADES -> "Swap cosmetics with friends. Nothing happens until you both confirm.";
			case MODS -> "Approve new cosmetics, handle reports, ban or unban.";
			default -> "Stickers, replies, reactions and polls - shared with the website and Discord.";
		};
	}

	@Override
	protected void build() {
		int tx = px + 14;
		if (tab == MODS && !mod) tab = CHAT;
		for (int i = 0; i < TABS.length; i++) {
			if (i == MODS && !mod) continue;
			final int fi = i;
			String label = TABS[i] + (i == TRADES && Social.tradeAlerts > 0 ? " •" : "") + (i == VOICE && VoiceRooms.inRoom() ? " ●" : "");
			int w = Math.max(44, Ui.width(label) + 14);
			btn(tx, py + 34, w, (tab == i ? "▶ " : "") + label, () -> {
				if (fi == DMS) { Compat.openScreen(new DmScreen(this, null)); return; }
				tab = fi;
				items.clear();
				detail = null;
				chatSince = 0;
				scrollTarget = 0;
				lastPoll = 0;
				tradeStep = 0;
				stickers = false;
				if (fi == TRADES) Social.tradeAlerts = 0;
			});
			tx += w + 2;
		}
		int by = py + ph - 26;
		switch (tab) {
			case CHAT -> {
				input(by, pw - 300, replyTo != null ? "Reply to " + ChatView.str(replyTo, "name") + "..." : "Say something... (/poll Question | A | B)");
				btn(px + pw - 282, by, 50, "Send", this::submit);
				btn(px + pw - 230, by, 56, stickers ? "▼ Stickers" : "Stickers", () -> stickers = !stickers);
				btn(px + pw - 172, by, 50, "Picture", this::sendScreenshot);
				btn(px + pw - 120, by, 54, showPins ? "All" : "Pinned", () -> showPins = !showPins);
			}
			case VOICE -> {
				if (VoiceRooms.inRoom()) {
					btn(px + 14, by, 64, VoiceRooms.muted ? "Unmute" : "Mute", () -> VoiceRooms.setMuted(!VoiceRooms.muted));
					btn(px + 80, by, 64, VoiceRooms.deaf ? "Undeafen" : "Deafen", () -> VoiceRooms.setDeaf(!VoiceRooms.deaf));
					btn(px + 146, by, 54, "Leave", () -> VoiceRooms.leave(false));
				} else {
					input(by, pw - 200, "Room name (then press Make a room)");
					btn(px + pw - 182, by, 112, "Make a room", this::makeVoiceRoom);
				}
			}
			case LFG -> {
				btn(px + 14, by, 80, GAMES[lfgGame], () -> lfgGame = (lfgGame + 1) % GAMES.length);
				btn(px + 96, by, 20, "-", () -> lfgSlots = Math.max(2, lfgSlots - 1));
				btn(px + 118, by, 34, lfgSlots + "", () -> {});
				btn(px + 154, by, 20, "+", () -> lfgSlots = Math.min(16, lfgSlots + 1));
				btn(px + 176, by, 42, lfgMic ? "Mic ✔" : "Mic", () -> lfgMic = !lfgMic);
				input = new EditBox(this.font, px + 222, by + 1, pw - 344, 18, Component.literal("lfg"));
				input.setMaxLength(160);
				input.setValue(draft);
				input.setResponder(s -> draft = s);
				input.setHint(Component.literal("Message: need 2 more, chill..."));
				this.addRenderableWidget(input);
				btn(px + pw - 118, by, 52, "Post", this::postLfg);
			}
			case SERVERS -> {
				if (detail != null) {
					input(by, pw - 230, ChatView.bool(detail, "canReview") ? "Your review (pick stars above)" : "Play here with Kyoto to review it");
					btn(px + pw - 212, by, 80, "Post review", this::postReview);
					btn(px + pw - 130, by, 60, "Back", () -> { detail = null; lastPoll = 0; });
				} else {
					String ip = dev.kyoto.feature.Privacy.server(Compat.currentServerIp());
					if (ip != null && !ip.isBlank()) btn(px + 14, by, 160, "Rate this server", () -> openServer(ip));
				}
			}
			case TRADES -> {
				if (detail != null) {
					btn(px + pw - 130, by, 60, "Back", () -> { detail = null; lastPoll = 0; });
					if ("open".equals(ChatView.str(detail, "state"))) {
						boolean mine = ChatView.bool(detail, "iConfirmed");
						btn(px + 14, by, 110, mine ? "Undo confirm" : "Confirm trade", () -> tradeAction(mine ? "/trades/unconfirm" : "/trades/confirm"));
						btn(px + 126, by, 70, "Change it", () -> startEdit(detail));
						btn(px + 198, by, 70, ChatView.bool(detail, "mine") ? "Cancel" : "Decline", () -> tradeAction("/trades/decline"));
					}
				} else if (tradeStep == 2) {
					btn(px + 14, by, 120, editing != null ? "Send changes" : "Send trade offer", this::sendTrade);
					btn(px + 136, by, 120, "Gift selected", this::giftSelected);
					btn(px + pw - 130, by, 60, "Back", () -> { tradeStep = editing != null ? 0 : 1; editing = null; });
				} else if (tradeStep == 1) {
					btn(px + pw - 130, by, 60, "Back", () -> tradeStep = 0);
				} else btn(px + 14, by, 120, "New trade / gift", () -> { tradeStep = 1; give.clear(); want.clear(); editing = null; });
			}
			case MODS -> {
				input(by, pw - 270, "Player name to ban / unban");
				btn(px + pw - 252, by, 58, "Ban", () -> modAction("/mod/ban", draft.trim()));
				btn(px + pw - 190, by, 58, "Unban", () -> modAction("/mod/unban", draft.trim()));
			}
			default -> { }
		}
	}

	private void input(int by, int w, String hint) {
		input = new EditBox(this.font, px + 14, by + 1, w, 18, Component.literal("message"));
		input.setMaxLength(300);
		input.setValue(draft);
		input.setResponder(s -> draft = s);
		input.setHint(Component.literal(hint));
		this.addRenderableWidget(input);
	}

	/* ------------------------------------------- networking ------------------------------------------- */

	@Override
	public void tick() {
		super.tick();
		if (!KyotoNet.loggedIn()) return;
		long every = switch (tab) { case MODS -> 8000; case SERVERS -> 20000; case LFG -> 8000; case VOICE -> VoiceRooms.inRoom() ? 100000 : 3000; case TRADES -> 4000; default -> 2500; };
		if (System.currentTimeMillis() - lastPoll > every) {
			lastPoll = System.currentTimeMillis();
			poll();
		}
	}

	private void poll() {
		int t = tab;
		JsonObject det = detail;
		int step = tradeStep;
		KyotoNet.runAsync(() -> {
			try {
				List<JsonObject> got = new ArrayList<>(), pinsGot = new ArrayList<>();
				JsonObject newDetail = null;
				boolean isMod = mod;
				switch (t) {
					case CHAT -> {
						JsonObject r = json(KyotoNet.send("GET", "/chat?since=" + chatSince + (ch().isEmpty() ? "" : "&ch=" + KyotoNet.enc(ch())), null, true));
						for (JsonElement e : r.getAsJsonArray("messages")) got.add(e.getAsJsonObject());
						if (r.has("pins")) for (JsonElement e : r.getAsJsonArray("pins")) pinsGot.add(e.getAsJsonObject());
						if (r.has("now")) chatSince = Math.max(0, r.get("now").getAsLong() - 3000);
						try { isMod = json(KyotoNet.send("GET", "/mod/me", null, true)).get("staff").getAsBoolean(); } catch (Exception ignored) { }
					}
					case VOICE -> got.addAll(ChatView.list(KyotoNet.send("GET", "/voice", null, true), "rooms"));
					case LFG -> got.addAll(ChatView.list(KyotoNet.send("GET", "/lfg", null, true), "posts"));
					case SERVERS -> {
						if (det != null) newDetail = json(KyotoNet.send("GET", "/servers/one?ip=" + KyotoNet.enc(ChatView.str(det, "ip")), null, true));
						else got.addAll(ChatView.list(KyotoNet.send("GET", "/servers?sort=online", null, true), "servers"));
					}
					case TRADES -> {
						if (step == 0 || det != null) {
							got.addAll(ChatView.list(KyotoNet.send("GET", "/trades", null, true), "trades"));
							if (det != null) for (JsonObject o : got) if (o.get("id").getAsInt() == det.get("id").getAsInt()) newDetail = o;
						}
					}
					case MODS -> {
						JsonObject r = json(KyotoNet.send("GET", "/mod/queue", null, true));
						for (JsonElement e : r.getAsJsonArray("pending")) got.add(e.getAsJsonObject());
						for (JsonElement e : r.getAsJsonArray("reports")) { JsonObject o = e.getAsJsonObject(); o.addProperty("isReport", true); got.add(o); }
						isMod = true;
					}
					default -> { }
				}
				final boolean m = isMod;
				final JsonObject nd = newDetail;
				Compat.runOnClient(() -> {
					if (t != tab) return;
					boolean modChanged = m != mod;
					mod = m;
					error = null;
					if (t == CHAT) {
						for (JsonObject o : got) {
							boolean replaced = false;
							for (int i = 0; i < chat.messages.size(); i++) if (chat.messages.get(i).get("id").getAsInt() == o.get("id").getAsInt()) { chat.messages.set(i, o); replaced = true; break; }
							if (!replaced) chat.messages.add(o);
						}
						chat.messages.sort((a, b) -> Long.compare(a.get("at").getAsLong(), b.get("at").getAsLong()));
						while (chat.messages.size() > 150) chat.messages.remove(0);
						pins.clear();
						pins.addAll(pinsGot);
						chat.canPin = mod;
						chat.staff = mod;
					} else if (t == SERVERS && det != null) {
						if (nd != null && detail != null) detail = nd;
					} else if (t == TRADES && det != null) {
						if (nd != null && detail != null) { boolean changed = nd.get("rev").getAsInt() != detail.get("rev").getAsInt() || !ChatView.str(nd, "state").equals(ChatView.str(detail, "state")) || ChatView.bool(nd, "iConfirmed") != ChatView.bool(detail, "iConfirmed"); detail = nd; if (changed) rebuildWidgets(); }
						items.clear();
						items.addAll(got);
					} else {
						items.clear();
						items.addAll(got);
					}
					if (modChanged) rebuildWidgets();
				});
			} catch (Exception e) {
				Compat.runOnClient(() -> error = Social.msg(e));
			}
		});
	}

	private void submit() {
		String text = draft.trim();
		if (!KyotoNet.loggedIn() || text.isEmpty()) return;
		// "/poll Question | answer | answer"
		if (text.toLowerCase().startsWith("/poll ")) {
			String[] p = text.substring(6).split("\\|");
			if (p.length < 3) { Notifications.push("Poll format", "/poll Question | Answer 1 | Answer 2", Notifications.INFO); return; }
			JsonObject b = new JsonObject();
			b.addProperty("where", "chat");
			b.addProperty("q", p[0].trim());
			JsonArray opts = new JsonArray();
			for (int i = 1; i < p.length && i <= 6; i++) if (!p[i].isBlank()) opts.add(p[i].trim());
			b.add("options", opts);
			b.addProperty("minutes", 60);
			draft = "";
			rebuildWidgets();
			send("/msg/poll", b, "Poll posted");
			return;
		}
		JsonObject b = new JsonObject();
		b.addProperty("text", text);
		if (replyTo != null && replyTo.has("id")) b.addProperty("reply", replyTo.get("id").getAsInt());
		if (!ch().isEmpty()) b.addProperty("ch", ch());
		draft = "";
		replyTo = null;
		rebuildWidgets();
		send("/chat/send", b, null);
		chat.toBottom();
	}

	private void sendSticker(String id) {
		JsonObject b = new JsonObject();
		b.addProperty("sticker", id);
		if (replyTo != null && replyTo.has("id")) b.addProperty("reply", replyTo.get("id").getAsInt());
		if (!ch().isEmpty()) b.addProperty("ch", ch());
		replyTo = null;
		stickers = false;
		rebuildWidgets();
		send("/chat/send", b, null);
		chat.toBottom();
	}

	private void send(String path, JsonObject b, String ok) {
		KyotoNet.runAsync(() -> {
			try {
				KyotoNet.send("POST", path, b, true);
				lastPoll = 0;
				if (ok != null) Compat.runOnClient(() -> Notifications.push(ok, "", Notifications.GOOD));
			} catch (Exception e) {
				Compat.runOnClient(() -> Notifications.push("Couldn't do that", Social.msg(e), Notifications.DANGER));
			}
		});
	}

	/** Sends your newest screenshot (F2) to the chat. */
	private void sendScreenshot() {
		if (!KyotoNet.loggedIn()) return;
		String caption = draft.trim();
		draft = "";
		rebuildWidgets();
		KyotoNet.runAsync(() -> {
			try {
				java.nio.file.Path dir = net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir().resolve("screenshots");
				java.nio.file.Path newest = null;
				if (java.nio.file.Files.isDirectory(dir)) try (var st = java.nio.file.Files.list(dir)) {
					newest = st.filter(f -> f.toString().toLowerCase().endsWith(".png")).max(java.util.Comparator.comparingLong(f -> f.toFile().lastModified())).orElse(null);
				}
				if (newest == null) {
					Compat.runOnClient(() -> Notifications.push("No screenshots yet", "Press F2 in game to take one, then try again.", Notifications.INFO));
					return;
				}
				byte[] png = java.nio.file.Files.readAllBytes(newest);
				if (png.length > 2_800_000) png = shrinkPng(png);
				JsonObject b = new JsonObject();
				b.addProperty("image", Base64.getEncoder().encodeToString(png));
				b.addProperty("text", caption);
				if (!ch().isEmpty()) b.addProperty("ch", ch());
				KyotoNet.send("POST", "/chat/image", b, true);
				lastPoll = 0;
				String sentName = newest.getFileName().toString();
				Compat.runOnClient(() -> Notifications.push("Picture sent", sentName, Notifications.GOOD));
			} catch (Exception e) {
				Compat.runOnClient(() -> Notifications.push("Couldn't send the picture", Social.msg(e), Notifications.DANGER));
			}
		});
	}

	private static byte[] shrinkPng(byte[] png) throws Exception {
		java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(png));
		int w = img.getWidth(), h = img.getHeight();
		float k = Math.min(1f, 1600f / Math.max(w, h));
		java.awt.image.BufferedImage out = new java.awt.image.BufferedImage(Math.round(w * k), Math.round(h * k), java.awt.image.BufferedImage.TYPE_INT_RGB);
		java.awt.Graphics2D g = out.createGraphics();
		g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
		g.drawImage(img, 0, 0, out.getWidth(), out.getHeight(), null);
		g.dispose();
		java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
		javax.imageio.ImageIO.write(out, "jpg", o);
		return o.toByteArray();
	}

	private void makeVoiceRoom() {
		JsonObject b = new JsonObject();
		b.addProperty("name", draft.trim());
		draft = "";
		KyotoNet.runAsync(() -> {
			try {
				String id = json(KyotoNet.send("POST", "/voice/create", b, true)).get("id").getAsString();
				Compat.runOnClient(() -> { VoiceRooms.join(id); rebuildWidgets(); });
			} catch (Exception e) {
				Compat.runOnClient(() -> Notifications.push("Couldn't make the room", Social.msg(e), Notifications.DANGER));
			}
		});
	}

	private void postLfg() {
		JsonObject b = new JsonObject();
		b.addProperty("game", GAMES[lfgGame]);
		b.addProperty("slots", lfgSlots);
		b.addProperty("mic", lfgMic);
		b.addProperty("text", draft.trim());
		String ip = Compat.currentServerIp();
		if (ip != null) b.addProperty("server", ip);
		draft = "";
		rebuildWidgets();
		send("/lfg/post", b, "Posted! You get a ping when people join.");
	}

	private void lfgAction(String path, int id, String ok) {
		JsonObject b = new JsonObject();
		b.addProperty("id", id);
		KyotoNet.runAsync(() -> {
			try {
				JsonObject r = json(KyotoNet.send("POST", path, b, true));
				lastPoll = 0;
				Compat.runOnClient(() -> {
					if (ok != null) Notifications.push(ok, r.has("server") && !r.get("server").isJsonNull() ? "Server: " + r.get("server").getAsString() : "Say hi in the group chat (Messages).", Notifications.GOOD);
					if (r.has("voice") && !r.get("voice").isJsonNull() && path.endsWith("join") && VoiceRooms.available()) VoiceRooms.join(r.get("voice").getAsString());
				});
			} catch (Exception e) {
				Compat.runOnClient(() -> Notifications.push("Couldn't do that", Social.msg(e), Notifications.DANGER));
			}
		});
	}

	private void openServer(String ip) {
		JsonObject d = new JsonObject();
		d.addProperty("ip", ip);
		detail = d;
		reviewStars = 0;
		tab = SERVERS;
		lastPoll = 0;
		rebuildWidgets();
	}

	private void postReview() {
		if (detail == null) return;
		if (reviewStars == 0) { Notifications.push("Pick some stars first", "Click the stars above.", Notifications.INFO); return; }
		JsonObject b = new JsonObject();
		b.addProperty("ip", ChatView.str(detail, "ip"));
		b.addProperty("stars", reviewStars);
		b.addProperty("text", draft.trim());
		draft = "";
		rebuildWidgets();
		send("/servers/review", b, "Thanks for the review!");
	}

	private void openTrade(int id) {
		for (JsonObject o : items) if (o.get("id").getAsInt() == id) { detail = o; rebuildWidgets(); return; }
		JsonObject d = new JsonObject();
		d.addProperty("id", id);
		d.addProperty("rev", -1);
		d.addProperty("state", "");
		detail = d;
		lastPoll = 0;
		rebuildWidgets();
	}

	private void tradeAction(String path) {
		if (detail == null) return;
		JsonObject b = new JsonObject();
		b.addProperty("id", detail.get("id").getAsInt());
		b.addProperty("rev", detail.get("rev").getAsInt());
		KyotoNet.runAsync(() -> {
			try {
				JsonObject r = json(KyotoNet.send("POST", path, b, true));
				lastPoll = 0;
				Compat.runOnClient(() -> {
					if (r.has("done") && r.get("done").getAsBoolean()) Notifications.push("Trade done!", "Everything is in your Locker.", Notifications.MONEY);
					else if (path.endsWith("/confirm")) Notifications.push("Confirmed", "Waiting for " + ChatView.str(detail == null ? new JsonObject() : detail, "with"), Notifications.GOOD);
				});
			} catch (Exception e) {
				Compat.runOnClient(() -> Notifications.push("Trade", Social.msg(e), Notifications.DANGER));
			}
		});
	}

	private void loadLockers(String other) {
		KyotoNet.runAsync(() -> {
			try {
				List<JsonObject> mine = ChatView.list(KyotoNet.send("GET", "/trade/locker", null, true), "items");
				List<JsonObject> theirs = other == null ? List.of() : ChatView.list(KyotoNet.send("GET", "/trade/locker?name=" + KyotoNet.enc(other), null, true), "items");
				Compat.runOnClient(() -> { myItems.clear(); myItems.addAll(mine); theirItems.clear(); theirItems.addAll(theirs); });
			} catch (Exception e) {
				Compat.runOnClient(() -> Notifications.push("Couldn't load the Lockers", Social.msg(e), Notifications.DANGER));
			}
		});
	}

	private void startEdit(JsonObject t) {
		editing = t;
		tradeWith = ChatView.str(t, "with");
		give.clear();
		want.clear();
		for (JsonElement e : t.getAsJsonArray("give")) give.add(e.getAsJsonObject().get("id").getAsInt());
		for (JsonElement e : t.getAsJsonArray("get")) want.add(e.getAsJsonObject().get("id").getAsInt());
		detail = null;
		tradeStep = 2;
		loadLockers(tradeWith);
		rebuildWidgets();
	}

	private void sendTrade() {
		if (tradeWith == null) return;
		JsonObject b = new JsonObject();
		JsonArray g1 = new JsonArray(), w1 = new JsonArray();
		give.forEach(g1::add);
		want.forEach(w1::add);
		b.add("give", g1);
		b.add("want", w1);
		String path;
		if (editing != null) { b.addProperty("id", editing.get("id").getAsInt()); path = "/trades/edit"; }
		else { b.addProperty("to", tradeWith); path = "/trades/new"; }
		KyotoNet.runAsync(() -> {
			try {
				KyotoNet.send("POST", path, b, true);
				Compat.runOnClient(() -> { Notifications.push(editing != null ? "Changes sent" : "Trade offer sent", tradeWith + " has to confirm, then you do.", Notifications.GOOD); tradeStep = 0; editing = null; lastPoll = 0; rebuildWidgets(); });
			} catch (Exception e) {
				Compat.runOnClient(() -> Notifications.push("Couldn't send it", Social.msg(e), Notifications.DANGER));
			}
		});
	}

	private void giftSelected() {
		if (tradeWith == null || give.size() != 1) { Notifications.push("Gift one thing", "Select exactly one of your items (left side), then Gift.", Notifications.INFO); return; }
		JsonObject b = new JsonObject();
		b.addProperty("to", tradeWith);
		b.addProperty("id", give.iterator().next());
		b.addProperty("mode", "give");
		KyotoNet.runAsync(() -> {
			try {
				KyotoNet.send("POST", "/gift", b, true);
				Compat.runOnClient(() -> { Notifications.push("Gift sent!", tradeWith + " got it in their Locker.", Notifications.MAGIC); tradeStep = 0; give.clear(); rebuildWidgets(); });
			} catch (Exception e) {
				Compat.runOnClient(() -> Notifications.push("Couldn't send the gift", Social.msg(e), Notifications.DANGER));
			}
		});
	}

	private void modAction(String path, String name) {
		if (name.isBlank()) return;
		JsonObject b = new JsonObject();
		b.addProperty("name", name);
		send(path, b, "Done: " + name);
		draft = "";
		rebuildWidgets();
	}

	private static JsonObject json(String s) {
		return JsonParser.parseString(s).getAsJsonObject();
	}

	/* ------------------------------------------- drawing ------------------------------------------- */

	@Override
	protected void drawContent(GuiGraphicsExtractor g, int mx, int my, float a) {
		hits.clear();
		int x1 = px + 14, x2 = px + pw - 14, y1 = py + 58, y2 = py + ph - 32;
		Ui.round(g, x1, y1, x2, y2, 8, Anim.argb(Ui.GLASS, 0.95f * a));
		if (!KyotoNet.loggedIn()) {
			Ui.text(g, "You're not logged in to a Kyoto server.", x1 + 10, y1 + 10, Anim.argb(Ui.MUTED, a));
			return;
		}
		if (tab == CHAT) { drawChat(g, x1, y1, x2, y2, mx, my, a); return; }
		scroll += (Math.min(scrollTarget, maxScroll) - scroll) * 0.3f;
		g.enableScissor(x1, y1 + 2, x2, y2 - 2);
		int y = y1 + 6 - Math.round(scroll);
		boolean inside = my >= y1 && my < y2;
		int end = switch (tab) {
			case VOICE -> drawVoice(g, x1 + 6, y, x2 - 6, mx, my, a, inside);
			case LFG -> drawLfg(g, x1 + 6, y, x2 - 6, mx, my, a, inside);
			case SERVERS -> detail != null ? drawServer(g, x1 + 6, y, x2 - 6, mx, my, a, inside) : drawServers(g, x1 + 6, y, x2 - 6, mx, my, a, inside);
			case TRADES -> drawTrades(g, x1 + 6, y, x2 - 6, mx, my, a, inside);
			case MODS -> drawMods(g, x1 + 6, y, x2 - 6, mx, my, a, inside);
			default -> y;
		};
		g.disableScissor();
		maxScroll = Math.max(0, (end + Math.round(scroll)) - y2 + 6);
		if (scrollTarget > maxScroll) scrollTarget = maxScroll;
		if (error != null) Ui.small(g, "Error: " + error, x1 + 8, y2 - 10, Anim.argb(0xFF8A8A, a));
	}

	private void drawChat(GuiGraphicsExtractor g, int x1, int y1, int x2, int y2, int mx, int my, float a) {
		int top = y1;
		// channels: General + the server you're on (+ the one you had open)
		{
			java.util.LinkedHashSet<String> chs = new java.util.LinkedHashSet<>();
			chs.add("");
			if (!serverCh().isEmpty()) chs.add(serverCh());
			chs.add(ch());
			int cx = x1 + 4;
			for (String c : chs) {
				String label = chName(c) + (c.equals(serverCh()) && !c.isEmpty() ? " (you're here)" : "");
				int w = Ui.widthSmall(label) + 14;
				boolean on = c.equals(ch()), hv = Ui.inside(mx, my, cx, top + 2, cx + w, top + 14);
				Ui.round(g, cx, top + 2, cx + w, top + 14, 6, Anim.argb(on ? Ui.accent() : Ui.INK, (on ? 0.9f : hv ? 0.14f : 0.07f) * a));
				Ui.small(g, label, cx + 7, top + 5, Anim.argb(on ? Ui.GLASS : Ui.MUTED, a));
				final String fc = c;
				if (!on) hits.add(new Hit(cx, top + 2, cx + w, top + 14, () -> setCh(fc)));
				cx += w + 4;
			}
			top += 16;
		}
		// pinned bar
		if (!pins.isEmpty()) {
			JsonObject last = pins.get(pins.size() - 1);
			boolean hv = Ui.inside(mx, my, x1 + 4, top + 3, x2 - 4, top + 15);
			Ui.round(g, x1 + 4, top + 3, x2 - 4, top + 15, 4, Anim.argb(Ui.INK, (hv ? 0.14f : 0.08f) * a));
			Ui.iconTex(g, "pin", x1 + 8, top + 5, 8, Anim.argb(Ui.INK, a));
			String t = pins.size() + " pinned · " + ChatView.str(last, "name") + ": " + ChatView.clean(ChatView.str(last, "text").isEmpty() ? "[sticker / poll]" : ChatView.str(last, "text"));
			Ui.small(g, Ui.ellipsize(t, Math.round((x2 - x1 - 40) / 0.75f)), x1 + 20, top + 7, Anim.argb(Ui.INK, a));
			hits.add(new Hit(x1 + 4, top + 3, x2 - 4, top + 15, () -> { showPins = !showPins; rebuildWidgets(); }));
			top += 17;
		}
		int bottom = y2 - (replyTo != null ? 13 : 0);
		List<JsonObject> keep = new ArrayList<>(chat.messages);
		if (showPins) { chat.messages.clear(); chat.messages.addAll(pins); }
		chat.draw(g, x1, top, x2, bottom, stickers ? -1 : mx, stickers ? -1 : my, a);
		if (showPins) { chat.messages.clear(); chat.messages.addAll(keep); }
		if (replyTo != null) {
			Ui.round(g, x1 + 4, y2 - 12, x2 - 4, y2 - 1, 4, Anim.argb(Ui.accent(), 0.12f * a));
			Ui.iconTex(g, "reply", x1 + 8, y2 - 10, 7, Anim.argb(Ui.accent(), a));
			Ui.small(g, Ui.ellipsize("Replying to " + ChatView.str(replyTo, "name") + ": " + ChatView.clean(ChatView.str(replyTo, "text")), Math.round((x2 - x1 - 50) / 0.75f)), x1 + 19, y2 - 9, Anim.argb(Ui.TEXT, a));
			hits.add(new Hit(x2 - 16, y2 - 12, x2 - 4, y2 - 1, () -> { replyTo = null; rebuildWidgets(); }));
			Ui.small(g, "x", x2 - 12, y2 - 9, Anim.argb(Ui.MUTED, a));
		}
		if (stickers) drawStickerPicker(g, x2, y2, mx, my, a);
	}

	private void drawStickerPicker(GuiGraphicsExtractor g, int right, int bottom, int mx, int my, float a) {
		int cols = 8, sz = 30, gap = 4, rows = (Social.STICKERS.length + cols - 1) / cols;
		int w = cols * (sz + gap) + 8, h = rows * (sz + gap) + 20, x = right - w - 4, y = bottom - h - 4;
		Ui.round(g, x, y, x + w, y + h, 8, Anim.argb(Ui.GLASS, 0.98f));
		Ui.outline(g, x, y, x + w, y + h, Anim.argb(Ui.INK, 0.1f));
		Ui.bold(g, "Stickers", x + 6, y + 5, Anim.argb(Ui.TEXT, a));
		for (int i = 0; i < Social.STICKERS.length; i++) {
			int sx = x + 4 + (i % cols) * (sz + gap), sy = y + 16 + (i / cols) * (sz + gap);
			boolean hv = Ui.inside(mx, my, sx, sy, sx + sz, sy + sz);
			if (hv) Ui.round(g, sx - 1, sy - 1, sx + sz + 1, sy + sz + 1, 6, Anim.argb(Ui.INK, 0.12f));
			int grow = hv ? 2 : 0;
			Compat.blitTint(g, Social.sticker(Social.STICKERS[i]), sx - grow, sy - grow, sz + grow * 2, sz + grow * 2, Anim.argb(0xFFFFFF, a));
			String id = Social.STICKERS[i];
			hits.add(new Hit(sx, sy, sx + sz, sy + sz, () -> sendSticker(id)));
		}
	}

	private int drawVoice(GuiGraphicsExtractor g, int x, int y, int x2, int mx, int my, float a, boolean inside) {
		if (!VoiceRooms.available()) {
			Ui.round(g, x, y, x2, y + 24, 6, Anim.argb(Ui.INK, 0.08f * a));
			Ui.small(g, "Voice Rooms need Simple Voice Chat installed (it's free on Modrinth). You can still see who's talking.", x + 6, y + 9, Anim.argb(Ui.INK, a));
			y += 30;
		}
		if (VoiceRooms.inRoom()) {
			Ui.bold(g, VoiceRooms.roomName, x + 2, y, Anim.argb(Ui.TEXT, a));
			Ui.small(g, (VoiceRooms.roomTopic.isEmpty() ? "" : ChatView.clean(VoiceRooms.roomTopic) + " · ") + "host " + VoiceRooms.roomHost + " · " + (VoiceRooms.ptt() ? "push to talk" : "voice activated") + " (Mods > Friend Calls)", x + 2, y + 11, Anim.argb(Ui.MUTED, a));
			y += 24;
			int tw = 92, th = 70, cols = Math.max(1, (x2 - x) / (tw + 6)), i = 0;
			boolean amHost = VoiceRooms.members.stream().anyMatch(m -> m.slot() == VoiceRooms.you && m.host());
			for (VoiceRooms.Member m : VoiceRooms.members) {
				int tx = x + (i % cols) * (tw + 6), ty = y + (i / cols) * (th + 6);
				boolean me = m.slot() == VoiceRooms.you;
				boolean speak = me ? System.currentTimeMillis() - VoiceRooms.lastSpokeAt < 300 : m.speaking();
				Ui.round(g, tx, ty, tx + tw, ty + th, 8, Anim.argb(me ? Ui.accent() : Ui.INK, (me ? 0.1f : 0.05f) * a));
				if (speak) Ui.outline(g, tx, ty, tx + tw, ty + th, Anim.argb(Ui.INK, 0.9f * a));
				int hx = tx + tw / 2 - 14;
				if (speak) Ui.round(g, hx - 3, ty + 5, hx + 31, ty + 39, 8, Anim.argb(Ui.INK, 0.5f * a));
				Ui.head(g, m.name(), hx, ty + 8, 28, a * (m.muted() ? 0.6f : 1f));
				String n = Ui.ellipsize(m.name(), tw - 8);
				Ui.bold(g, n, tx + tw / 2 - Ui.widthBold(n) / 2, ty + 41, Anim.argb(Ui.TEXT, a));
				String sub = (m.host() ? "host · " : "") + (m.client().equals("game") ? "in game" : "website") + (m.muted() ? " · muted" : "");
				Ui.small(g, sub, tx + tw / 2f - Ui.widthSmall(sub) / 2f, ty + 52, Anim.argb(Ui.MUTED, a));
				if (me) {
					int lw = Math.round((tw - 20) * Math.min(1f, VoiceRooms.level * 3f));
					Ui.round(g, tx + 10, ty + 62, tx + tw - 10, ty + 65, 1, Anim.argb(Ui.INK, 0.08f * a));
					Ui.round(g, tx + 10, ty + 62, tx + 10 + lw, ty + 65, 1, Anim.argb(Ui.INK, a));
				} else if (inside) {
					float vol = VoiceRooms.VOLUME.getOrDefault(m.name(), 1f);
					String vl = Math.round(vol * 100) + "%";
					Ui.small(g, "- " + vl + " +", tx + tw / 2f - Ui.widthSmall("- " + vl + " +") / 2f, ty + 61, Anim.argb(Ui.MUTED, a));
					hits.add(new Hit(tx + 4, ty + 58, tx + tw / 2, ty + th, () -> VoiceRooms.VOLUME.put(m.name(), Math.max(0f, vol - 0.25f))));
					hits.add(new Hit(tx + tw / 2, ty + 58, tx + tw - 4, ty + th, () -> VoiceRooms.VOLUME.put(m.name(), Math.min(2f, vol + 0.25f))));
					if (amHost) action(g, tx + tw - 18, ty + 3, "x", 0xFF8A8A, mx, my, a, inside, () -> VoiceRooms.kick(m.slot()));
				}
				i++;
			}
			return y + ((i + cols - 1) / cols) * (th + 6);
		}
		if (items.isEmpty()) { Ui.text(g, error != null ? "Error: " + error : "No voice rooms right now. Type a name below and press Make a room.", x + 4, y + 4, Anim.argb(Ui.MUTED, a)); return y + 20; }
		for (JsonObject r : items) {
			int h = 34;
			boolean hv = inside && Ui.inside(mx, my, x, y, x2, y + h);
			int size = r.get("size").getAsInt();
			Ui.round(g, x, y, x2, y + h, 7, Anim.argb(size > 0 ? Ui.INK : Ui.INK, (hv ? 0.1f : 0.05f) * a));
			Ui.iconTex(g, "mic", x + 7, y + 11, 12, Anim.argb(size > 0 ? Ui.INK : Ui.MUTED, a));
			Ui.bold(g, ChatView.clean(ChatView.str(r, "name")), x + 24, y + 6, Anim.argb(Ui.TEXT, a));
			String sub = (ChatView.str(r, "topic").isEmpty() ? "" : ChatView.clean(ChatView.str(r, "topic")) + " · ") + "host " + ChatView.str(r, "host") + (ChatView.str(r, "privacy").equals("public") ? "" : " · " + ChatView.str(r, "privacy"));
			Ui.small(g, Ui.ellipsize(sub, 260), x + 24, y + 19, Anim.argb(Ui.MUTED, a));
			int fx = x2 - 60;
			JsonArray mem = r.getAsJsonArray("members");
			for (int i = Math.min(mem.size(), 6) - 1; i >= 0; i--) {
				JsonObject m = mem.get(i).getAsJsonObject();
				if (ChatView.bool(m, "speaking")) Ui.round(g, fx - 1, y + 8, fx + 17, y + 26, 4, Anim.argb(Ui.INK, a));
				Ui.head(g, ChatView.str(m, "name"), fx, y + 9, 16, a);
				fx -= 19;
			}
			Ui.small(g, size + "/16", x2 - 30, y + 14, Anim.argb(Ui.MUTED, a));
			String id = ChatView.str(r, "id");
			if (inside) hits.add(new Hit(x, y, x2, y + h, () -> { VoiceRooms.join(id); rebuildWidgets(); }));
			y += h + 4;
		}
		return y;
	}

	private int drawLfg(GuiGraphicsExtractor g, int x, int y, int x2, int mx, int my, float a, boolean inside) {
		if (items.isEmpty()) { Ui.text(g, error != null ? "Error: " + error : "Nobody's looking right now. Pick a game below, add a message and Post.", x + 4, y + 4, Anim.argb(Ui.MUTED, a)); return y + 20; }
		for (JsonObject p : items) {
			int h = 44;
			boolean mine = ChatView.bool(p, "mine"), joined = ChatView.bool(p, "joined"), full = ChatView.bool(p, "full");
			Ui.round(g, x, y, x2, y + h, 7, Anim.argb(mine ? Ui.accent() : Ui.INK, (mine ? 0.1f : 0.05f) * a));
			String host = ChatView.str(p, "host");
			Ui.head(g, host, x + 6, y + 6, 16, a);
			String title = ChatView.clean(ChatView.str(p, "game")) + (ChatView.str(p, "mode").isEmpty() ? "" : " · " + ChatView.clean(ChatView.str(p, "mode")));
			Ui.bold(g, title, x + 27, y + 5, Anim.argb(Ui.TEXT, a));
			int tx = x + 30 + Ui.widthBold(title);
			if (ChatView.bool(p, "mic")) { Ui.iconTex(g, "mic", tx, y + 5, 8, Anim.argb(Ui.INK, a)); tx += 11; }
			if (ChatView.bool(p, "friend")) Ui.small(g, "FRIEND", tx, y + 6, Anim.argb(Ui.INK, a));
			Ui.small(g, host + (ChatView.bool(p, "hostOnline") ? " · online" : " · offline") + " · " + ChatView.left(p.get("exp").getAsLong()), x + 27, y + 16, Anim.argb(Ui.MUTED, a));
			String txt = ChatView.clean(ChatView.str(p, "text")) + (ChatView.str(p, "server").isEmpty() ? "" : "  [" + ChatView.str(p, "server") + "]");
			Ui.small(g, Ui.ellipsize(txt, Math.round((x2 - x - 160) / 0.75f)), x + 27, y + 27, Anim.argb(Ui.MUTED, a));
			// spots
			int slots = p.get("slots").getAsInt(), have = p.getAsJsonArray("members").size() + 1, sx = x2 - 96;
			Ui.small(g, have + "/" + slots, sx, y + 6, Anim.argb(full ? 0xFF8A8A : Ui.INK, a));
			for (int i = 0; i < Math.min(slots, 8); i++) Ui.round(g, sx + 22 + i * 8, y + 6, sx + 28 + i * 8, y + 12, 2, Anim.argb(i < have ? Ui.INK : Ui.INK, (i < have ? 0.9f : 0.15f) * a));
			int id = p.get("id").getAsInt(), ax = x2 - 96, ay = y + 26;
			if (mine) {
				if (p.has("group") && !p.get("group").isJsonNull()) { String gid = p.get("group").getAsString(); ax = action(g, ax, ay, "Chat", Ui.TEXT, mx, my, a, inside, () -> Compat.openScreen(DmScreen.group(this, gid))); }
				action(g, ax, ay, "Close", 0xFF8A8A, mx, my, a, inside, () -> lfgAction("/lfg/close", id, "Closed"));
			} else if (joined) {
				if (p.has("group") && !p.get("group").isJsonNull()) { String gid = p.get("group").getAsString(); ax = action(g, ax, ay, "Chat", Ui.TEXT, mx, my, a, inside, () -> Compat.openScreen(DmScreen.group(this, gid))); }
				action(g, ax, ay, "Leave", Ui.MUTED, mx, my, a, inside, () -> lfgAction("/lfg/leave", id, "Left"));
			} else action(g, ax, ay, full ? "Full" : "Join", full ? Ui.MUTED : Ui.INK, mx, my, a, inside && !full, () -> lfgAction("/lfg/join", id, "You're in!"));
			y += h + 4;
		}
		return y;
	}

	private int drawServers(GuiGraphicsExtractor g, int x, int y, int x2, int mx, int my, float a, boolean inside) {
		if (items.isEmpty()) { Ui.text(g, error != null ? "Error: " + error : "No servers yet: play on one with Kyoto and it shows up here.", x + 4, y + 4, Anim.argb(Ui.MUTED, a)); return y + 20; }
		for (JsonObject s : items) {
			int h = 26;
			boolean hv = inside && Ui.inside(mx, my, x, y, x2, y + h);
			Ui.round(g, x, y, x2, y + h, 6, Anim.argb(Ui.INK, (hv ? 0.09f : 0.045f) * a));
			Ui.iconTex(g, "server", x + 6, y + 7, 12, Anim.argb(Ui.MUTED, a));
			String ip = ChatView.str(s, "ip");
			Ui.bold(g, ip, x + 24, y + 4, Anim.argb(Ui.TEXT, a));
			int count = s.get("count").getAsInt();
			if (count > 0) { stars(g, x + 24, y + 15, s.get("avg").getAsFloat(), 7, a); Ui.small(g, String.format("%.1f · %d review%s", s.get("avg").getAsFloat(), count, count == 1 ? "" : "s"), x + 66, y + 16, Anim.argb(Ui.MUTED, a)); }
			else Ui.small(g, "No reviews yet", x + 24, y + 16, Anim.argb(Ui.MUTED, a));
			int on = s.get("online").getAsInt();
			if (on > 0) { String t = on + " Kyoto player" + (on == 1 ? "" : "s") + " on"; Ui.small(g, t, x2 - 8 - Ui.widthSmall(t), y + 10, Anim.argb(Ui.INK, a)); }
			if (inside) hits.add(new Hit(x, y, x2, y + h, () -> openServer(ip)));
			y += h + 3;
		}
		return y;
	}

	private int drawServer(GuiGraphicsExtractor g, int x, int y, int x2, int mx, int my, float a, boolean inside) {
		JsonObject d = detail;
		Ui.bold(g, ChatView.str(d, "ip"), x + 2, y, Anim.argb(Ui.TEXT, a));
		if (!d.has("count")) { Ui.small(g, "Loading...", x + 2, y + 14, Anim.argb(Ui.MUTED, a)); return y + 24; }
		int count = d.get("count").getAsInt();
		stars(g, x + 2, y + 13, d.get("avg").getAsFloat(), 9, a);
		Ui.small(g, count > 0 ? String.format("%.1f out of 5 · %d review%s · %d Kyoto player%s on now", d.get("avg").getAsFloat(), count, count == 1 ? "" : "s", d.get("online").getAsInt(), d.get("online").getAsInt() == 1 ? "" : "s") : "No reviews yet", x + 52, y + 15, Anim.argb(Ui.MUTED, a));
		y += 28;
		if (ChatView.bool(d, "canReview")) {
			Ui.round(g, x, y, x2, y + 24, 6, Anim.argb(Ui.accent(), 0.08f * a));
			Ui.small(g, "Your rating:", x + 6, y + 9, Anim.argb(Ui.TEXT, a));
			for (int i = 1; i <= 5; i++) {
				int sx = x + 56 + (i - 1) * 16;
				boolean hv = inside && Ui.inside(mx, my, sx, y + 4, sx + 14, y + 20);
				Ui.iconTex(g, "star", sx, y + 5, 13, Anim.argb(i <= reviewStars || hv ? Ui.INK : Ui.SOFT, a));
				final int st = i;
				if (inside) hits.add(new Hit(sx, y + 4, sx + 14, y + 20, () -> reviewStars = st));
			}
			Ui.small(g, "then write a few words below and press Post review", x + 142, y + 9, Anim.argb(Ui.MUTED, a));
			y += 30;
		} else {
			Ui.small(g, "Play on this server with Kyoto to leave a review (keeps them real).", x + 2, y, Anim.argb(Ui.MUTED, a));
			y += 12;
		}
		for (JsonElement e : d.getAsJsonArray("reviews")) {
			JsonObject r = e.getAsJsonObject();
			List<String> lines = wrap(ChatView.clean(ChatView.str(r, "text")), x2 - x - 40, 4);
			JsonArray tags = r.getAsJsonArray("tags");
			int h = 26 + lines.size() * 10 + (tags.isEmpty() ? 0 : 11);
			Ui.round(g, x, y, x2, y + h, 6, Anim.argb(Ui.INK, 0.045f * a));
			Ui.head(g, ChatView.str(r, "name"), x + 6, y + 6, 14, a);
			Ui.bold(g, ChatView.str(r, "name"), x + 26, y + 5, Anim.argb(Ui.TEXT, a));
			stars(g, x + 30 + Ui.widthBold(ChatView.str(r, "name")), y + 6, r.get("stars").getAsInt(), 7, a);
			Ui.small(g, ChatView.ago(r.get("at").getAsLong()), x2 - 30, y + 6, Anim.argb(Ui.MUTED, a));
			int ty = y + 17;
			if (!tags.isEmpty()) {
				int tx = x + 26;
				for (JsonElement t : tags) { String tg = t.getAsString(); boolean bad = tg.matches("Pay to win|Laggy|Cheaters|Toxic|Empty"); Ui.small(g, tg, tx, ty, Anim.argb(bad ? 0xFF8A9A : Ui.INK, a)); tx += Ui.widthSmall(tg) + 8; }
				ty += 11;
			}
			for (String l : lines) { Ui.text(g, l, x + 26, ty, Anim.argb(Ui.MUTED, a)); ty += 10; }
			if (!ChatView.bool(r, "me")) {
				String nm = ChatView.str(r, "name"), ip = ChatView.str(d, "ip");
				action(g, x2 - 70, y + h - 13, "Helpful " + r.get("helpful").getAsInt(), ChatView.bool(r, "helpedByMe") ? Ui.INK : Ui.MUTED, mx, my, a, inside, () -> {
					JsonObject b = new JsonObject(); b.addProperty("ip", ip); b.addProperty("name", nm); send("/servers/helpful", b, null);
				});
			}
			y += h + 3;
		}
		return y;
	}

	private void stars(GuiGraphicsExtractor g, int x, int y, float v, int size, float a) {
		for (int i = 1; i <= 5; i++) Ui.iconTex(g, "star", x + (i - 1) * (size + 1), y, size, Anim.argb(v >= i - 0.25f ? Ui.INK : Ui.SOFT, a));
	}

	private int drawTrades(GuiGraphicsExtractor g, int x, int y, int x2, int mx, int my, float a, boolean inside) {
		if (detail != null) {
			JsonObject t = detail;
			if (!t.has("give")) { Ui.small(g, "Loading...", x + 2, y + 4, Anim.argb(Ui.MUTED, a)); return y + 20; }
			String with = ChatView.str(t, "with");
			Ui.head(g, with, x + 2, y, 16, a);
			Ui.bold(g, "Trade with " + with + "  ·  " + ChatView.str(t, "state"), x + 24, y + 1, Anim.argb(Ui.TEXT, a));
			Ui.small(g, (ChatView.bool(t, "mine") ? "You started it" : with + " started it") + (ChatView.str(t, "note").isEmpty() ? "" : " · \"" + ChatView.clean(ChatView.str(t, "note")) + "\""), x + 24, y + 11, Anim.argb(Ui.MUTED, a));
			y += 24;
			int half = (x2 - x - 20) / 2;
			int h1 = itemColumn(g, x, y, x + half, "You give", t.getAsJsonArray("give"), t.get("coinsGive").getAsInt(), a);
			Ui.iconTex(g, "swap", x + half + 4, y + 20, 12, Anim.argb(Ui.accent(), a));
			int h2 = itemColumn(g, x + half + 20, y, x2, "You get", t.getAsJsonArray("get"), t.get("coinsGet").getAsInt(), a);
			y += Math.max(h1, h2) + 6;
			if ("open".equals(ChatView.str(t, "state"))) {
				boolean me = ChatView.bool(t, "iConfirmed"), them = ChatView.bool(t, "theyConfirmed");
				Ui.round(g, x, y, x + half, y + 16, 5, Anim.argb(me ? Ui.INK : Ui.INK, (me ? 0.15f : 0.05f) * a));
				Ui.small(g, me ? "You confirmed" : "You haven't confirmed", x + 6, y + 5, Anim.argb(me ? Ui.INK : Ui.MUTED, a));
				Ui.round(g, x + half + 20, y, x2, y + 16, 5, Anim.argb(them ? Ui.INK : Ui.INK, (them ? 0.15f : 0.05f) * a));
				Ui.small(g, them ? with + " confirmed" : with + " hasn't confirmed", x + half + 26, y + 5, Anim.argb(them ? Ui.INK : Ui.MUTED, a));
				y += 20;
				Ui.small(g, "Confirming means you agree to exactly this. Any change and you both confirm again.", x + 2, y, Anim.argb(Ui.MUTED, a));
				y += 12;
			}
			return y;
		}
		if (tradeStep == 1) {
			Ui.bold(g, "Who do you want to trade with (or gift)?", x + 2, y, Anim.argb(Ui.TEXT, a));
			y += 14;
			List<KyotoNet.Friend> fr = new ArrayList<>(KyotoNet.FRIENDS);
			if (fr.isEmpty()) { Ui.small(g, "Add friends first (/kyoto friends).", x + 2, y, Anim.argb(Ui.MUTED, a)); return y + 12; }
			int cx = x;
			for (KyotoNet.Friend f : fr) {
				int w = Ui.width(f.name()) + 28;
				if (cx + w > x2) { cx = x; y += 22; }
				boolean hv = inside && Ui.inside(mx, my, cx, y, cx + w, y + 18);
				Ui.round(g, cx, y, cx + w, y + 18, 5, Anim.argb(Ui.INK, (hv ? 0.12f : 0.05f) * a));
				Ui.head(g, f.name(), cx + 3, y + 3, 12, a);
				Ui.text(g, f.name(), cx + 19, y + 5, Anim.argb(f.online() ? Ui.INK : Ui.TEXT, a));
				String n = f.name();
				if (inside) hits.add(new Hit(cx, y, cx + w, y + 18, () -> { tradeWith = n; tradeStep = 2; give.clear(); want.clear(); loadLockers(n); rebuildWidgets(); }));
				cx += w + 4;
			}
			return y + 24;
		}
		if (tradeStep == 2) {
			Ui.bold(g, (editing != null ? "Change the trade with " : "Trade with ") + tradeWith, x + 2, y, Anim.argb(Ui.TEXT, a));
			Ui.small(g, "Click items to pick them. Only things you got from others can be traded. Coins: use the website.", x + 2, y + 11, Anim.argb(Ui.MUTED, a));
			y += 24;
			int half = (x2 - x - 20) / 2;
			int h1 = pickColumn(g, x, y, x + half, "You give", myItems, give, mx, my, a, inside);
			Ui.iconTex(g, "swap", x + half + 4, y + 20, 12, Anim.argb(Ui.accent(), a));
			int h2 = pickColumn(g, x + half + 20, y, x2, "You want", theirItems, want, mx, my, a, inside);
			return y + Math.max(h1, h2);
		}
		if (items.isEmpty()) { Ui.text(g, error != null ? "Error: " + error : "No trades yet. Press New trade / gift.", x + 4, y + 4, Anim.argb(Ui.MUTED, a)); return y + 20; }
		for (JsonObject t : items) {
			int h = 26;
			boolean open = "open".equals(ChatView.str(t, "state"));
			boolean hv = inside && Ui.inside(mx, my, x, y, x2, y + h);
			Ui.round(g, x, y, x2, y + h, 6, Anim.argb(open ? Ui.accent() : Ui.INK, (open ? (hv ? 0.16f : 0.09f) : (hv ? 0.08f : 0.035f)) * a));
			String with = ChatView.str(t, "with");
			Ui.head(g, with, x + 5, y + 5, 16, a);
			Ui.bold(g, (ChatView.bool(t, "mine") ? "To " : "From ") + with, x + 26, y + 4, Anim.argb(Ui.TEXT, a));
			Ui.small(g, t.getAsJsonArray("give").size() + " for " + t.getAsJsonArray("get").size() + " · " + ChatView.ago(t.get("upd").getAsLong()), x + 26, y + 15, Anim.argb(Ui.MUTED, a));
			String st = open ? (ChatView.bool(t, "iConfirmed") ? "Waiting for them" : ChatView.bool(t, "theyConfirmed") ? "Your turn: confirm" : "Open") : ChatView.str(t, "state");
			Ui.small(g, st, x2 - 8 - Ui.widthSmall(st), y + 10, Anim.argb(open ? Ui.accent() : Ui.MUTED, a));
			if (inside) hits.add(new Hit(x, y, x2, y + h, () -> { detail = t; rebuildWidgets(); }));
			y += h + 3;
		}
		return y;
	}

	private int itemColumn(GuiGraphicsExtractor g, int x, int y, int x2, String title, JsonArray list, int coins, float a) {
		Ui.round(g, x, y, x2, y + 14 + Math.max(1, list.size()) * 13 + (coins > 0 ? 12 : 0) + 4, 6, Anim.argb(Ui.INK, 0.04f * a));
		Ui.small(g, title.toUpperCase(), x + 6, y + 4, Anim.argb(Ui.MUTED, a));
		int iy = y + 14;
		if (list.isEmpty()) { Ui.small(g, "Nothing", x + 6, iy + 2, Anim.argb(Ui.MUTED, a)); iy += 13; }
		for (JsonElement e : list) { JsonObject o = e.getAsJsonObject(); Ui.iconTex(g, "cosmetics", x + 6, iy, 9, Anim.argb(Ui.accent(), a)); Ui.text(g, Ui.ellipsize(ChatView.clean(ChatView.str(o, "title")), x2 - x - 30), x + 18, iy + 1, Anim.argb(Ui.TEXT, a)); iy += 13; }
		if (coins > 0) { Ui.small(g, coins + " coins", x + 18, iy + 2, Anim.argb(Ui.INK, a)); iy += 12; }
		return iy - y + 4;
	}

	private int pickColumn(GuiGraphicsExtractor g, int x, int y, int x2, String title, List<JsonObject> list, Set<Integer> picked, int mx, int my, float a, boolean inside) {
		Ui.small(g, title.toUpperCase() + " (" + picked.size() + ")", x + 2, y, Anim.argb(Ui.MUTED, a));
		int iy = y + 10;
		if (list.isEmpty()) { Ui.small(g, "Nothing tradable", x + 2, iy + 2, Anim.argb(Ui.MUTED, a)); return 24; }
		for (JsonObject o : list) {
			int id = o.get("id").getAsInt();
			boolean on = picked.contains(id), hv = inside && Ui.inside(mx, my, x, iy, x2, iy + 15);
			Ui.round(g, x, iy, x2, iy + 15, 4, Anim.argb(on ? Ui.accent() : Ui.INK, (on ? 0.22f : hv ? 0.1f : 0.04f) * a));
			Ui.iconTex(g, on ? "check" : "cosmetics", x + 4, iy + 3, 9, Anim.argb(on ? Ui.INK : Ui.MUTED, a));
			Ui.text(g, Ui.ellipsize(ChatView.clean(ChatView.str(o, "title")), x2 - x - 60), x + 16, iy + 4, Anim.argb(Ui.TEXT, a));
			String s = ChatView.str(o, "slot");
			Ui.small(g, s, x2 - 6 - Ui.widthSmall(s), iy + 5, Anim.argb(Ui.MUTED, a));
			if (inside) hits.add(new Hit(x, iy, x2, iy + 15, () -> { if (!picked.remove(id)) picked.add(id); }));
			iy += 17;
		}
		return iy - y;
	}

	private int drawMods(GuiGraphicsExtractor g, int x, int y, int x2, int mx, int my, float a, boolean inside) {
		if (items.isEmpty()) { Ui.text(g, error != null ? "Error: " + error : "Nothing to approve and no reports. Nice.", x + 4, y + 4, Anim.argb(Ui.MUTED, a)); return y + 20; }
		for (JsonObject o : items) y += (o.has("isReport") ? report(g, o, x, y, x2, mx, my, a, inside) : postCard(g, o, x, y, x2, mx, my, a, inside)) + 4;
		return y;
	}

	private int report(GuiGraphicsExtractor g, JsonObject o, int x, int y, int x2, int mx, int my, float a, boolean inside) {
		JsonObject t = o.has("target") && o.get("target").isJsonObject() ? o.getAsJsonObject("target") : new JsonObject();
		Ui.round(g, x, y, x2, y + 36, 6, Anim.argb(0xFF5D5D, 0.08f * a));
		Ui.iconTex(g, "flag", x + 6, y + 7, 8, Anim.argb(0xFF8A8A, a));
		Ui.text(g, Ui.ellipsize(ChatView.str(o, "kind") + " by " + ChatView.str(t, "name") + "  ·  reported by " + ChatView.str(o, "byName"), x2 - x - 30), x + 18, y + 3, Anim.argb(Ui.TEXT, a));
		Ui.small(g, Ui.ellipsize(ChatView.str(o, "reason") + (ChatView.str(t, "text").isEmpty() ? "" : "  -  \"" + ContentFilter.clean(ChatView.str(t, "text")) + "\""), Math.round((x2 - x - 30) / 0.75f)), x + 18, y + 13, Anim.argb(Ui.MUTED, a));
		int ax = x + 18, ay = y + 23;
		boolean gone = ChatView.bool(t, "gone");
		String kind = ChatView.str(o, "kind");
		if (!gone) ax = action(g, ax, ay, "Delete it", 0xFF8A8A, mx, my, a, inside, () -> {
			JsonObject b = new JsonObject();
			b.addProperty("kind", kind.equals("chat") || kind.equals("review") ? kind : "post");
			b.addProperty("id", ChatView.str(o, "id"));
			send("/mod/delete", b, "Deleted");
		});
		action(g, ax, ay, "Dismiss", Ui.MUTED, mx, my, a, inside, () -> {
			JsonObject b = new JsonObject();
			b.addProperty("at", o.get("at").getAsLong());
			b.addProperty("by", ChatView.str(o, "by"));
			send("/mod/report/dismiss", b, "Dismissed");
		});
		return 36;
	}

	private int postCard(GuiGraphicsExtractor g, JsonObject o, int x, int y, int x2, int mx, int my, float a, boolean inside) {
		int id = o.get("id").getAsInt();
		String title = ContentFilter.clean(ChatView.str(o, "title")), text = ContentFilter.clean(ChatView.str(o, "text"));
		boolean cape = ChatView.bool(o, "hasImage");
		int textX = x + 8 + (cape ? 56 : 0);
		List<String> lines = text.isEmpty() ? List.of() : wrap(text, x2 - textX - 10, 4);
		int h = Math.max(cape ? 64 : 0, 34 + (title.isEmpty() ? 0 : 11) + lines.size() * 10) + 18;
		Ui.round(g, x, y, x2, y + h, 7, Anim.argb(Ui.INK, 0.045f * a));
		if (cape) {
			Identifier img = image(id);
			Ui.round(g, x + 6, y + 6, x + 56, y + 6 + 54, 4, Anim.argb(0x000000, 0.4f * a));
			if (img != null) Compat.blitRegion(g, img, x + 16, y + 9, 30, 48, 1, 1, 10, 16, 64, 32, Anim.argb(0xFFFFFF, a));
		}
		Ui.head(g, ChatView.str(o, "name"), textX, y + 6, 14, a);
		Ui.bold(g, ChatView.str(o, "name"), textX + 18, y + 9, Anim.argb(Ui.TEXT, a));
		int ty = y + 23;
		if (!title.isEmpty()) { Ui.bold(g, Ui.ellipsize(title, x2 - textX - 10), textX, ty, Anim.argb(Ui.INK, a)); ty += 11; }
		for (String l : lines) { Ui.text(g, l, textX, ty, Anim.argb(Ui.MUTED, a)); ty += 10; }
		int ay = y + h - 15, ax = textX;
		ax = action(g, ax, ay, "Approve", Ui.INK, mx, my, a, inside, () -> { JsonObject b = new JsonObject(); b.addProperty("id", id); send("/mod/approve", b, "Approved"); });
		ax = action(g, ax, ay, "Reject", 0xFF8A8A, mx, my, a, inside, () -> { JsonObject b = new JsonObject(); b.addProperty("id", id); send("/mod/delete", b, "Rejected"); });
		int price = o.has("price") ? o.get("price").getAsInt() : 0;
		Ui.small(g, ("kycos".equals(ChatView.str(o, "type")) ? "Cosmetic" : "Cape") + " · " + (price > 0 ? price + " coins" : "Free"), ax + 4, ay + 2, Anim.argb(Ui.MUTED, a));
		return h;
	}

	private Identifier image(int id) {
		Identifier tex = IMAGES.get(id);
		if (tex != null || LOADING.contains(id)) return tex;
		LOADING.add(id);
		KyotoNet.runAsync(() -> {
			try {
				byte[] png = KyotoNet.bytesAuth("/hub/image?id=" + id);
				if (png.length > 3 && png[0] == 'G') {
					java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(png));
					java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
					javax.imageio.ImageIO.write(img, "png", o);
					png = o.toByteArray();
				}
				Identifier t = Identifier.fromNamespaceAndPath("kyoto", "hub/" + id);
				Compat.registerPng(t, png, () -> IMAGES.put(id, t));
			} catch (Exception ignored) {
			}
		});
		return null;
	}

	private int action(GuiGraphicsExtractor g, int x, int y, String label, int col, int mx, int my, float a, boolean inside, Runnable r) {
		int w = Ui.widthSmall(label) + 10;
		boolean hv = inside && Ui.inside(mx, my, x, y, x + w, y + 11);
		Ui.round(g, x, y, x + w, y + 11, 5, Anim.argb(Ui.INK, (hv ? 0.14f : 0.06f) * a));
		Ui.small(g, label, x + 5, y + 2, Anim.argb(col, a));
		if (inside) hits.add(new Hit(x, y, x + w, y + 11, r));
		return x + w + 4;
	}

	/* ------------------------------------------- input ------------------------------------------- */

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		for (int i = hits.size() - 1; i >= 0; i--) {
			Hit h = hits.get(i);
			if (Ui.inside(event.x(), event.y(), h.x1, h.y1, h.x2, h.y2)) {
				h.r.run();
				return true;
			}
		}
		if (stickers && tab == CHAT) { stickers = false; rebuildWidgets(); return true; }
		if (tab == CHAT && chat.click(event.x(), event.y())) return true;
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		if (tab == CHAT) chat.scroll(scrollY);
		else scrollTarget = Math.max(0, Math.min(maxScroll, Math.min(scrollTarget, maxScroll) - (float) scrollY * 24));
		return true;
	}

	@Override
	public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
		if ((event.key() == 257 || event.key() == 335) && input != null && input.isFocused()) {
			switch (tab) {
				case CHAT -> submit();
				case VOICE -> makeVoiceRoom();
				case LFG -> postLfg();
				case SERVERS -> postReview();
				default -> { }
			}
			return true;
		}
		if (event.key() == 256 && replyTo != null) { replyTo = null; rebuildWidgets(); return true; }
		return super.keyPressed(event);
	}
}
