package dev.kyoto.screen;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.kyoto.hud.Notifications;
import dev.kyoto.net.KyotoNet;
import dev.kyoto.social.Emojis;
import dev.kyoto.social.Social;
import dev.kyoto.util.Anim;
import dev.kyoto.util.Compat;
import dev.kyoto.util.Ui;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Direct messages. Left: your conversations. Right: the chat, with emojis (the smiley button or :name:)
 * and pictures (friends only, PNG/JPG up to 2 MB). Pictures stay hidden until you click them.
 */
public class DmScreen extends PanelScreen {
	private static volatile String openWith = null;

	/** Who you're talking to: a name, or "g:<group id>" for a group chat. */
	private String with;
	private EditBox input;
	private String typed = "";
	private boolean emojiOpen = false, stickerOpen = false;
	private final List<JsonObject> threads = new ArrayList<>();
	private final ChatView chat = new ChatView();
	private JsonObject info = new JsonObject(), replyTo = null;
	private final List<JsonObject> pins = new ArrayList<>();
	private long lastFetch = 0;
	private volatile boolean loading = false;
	private Identifier bigImage = null;

	private record Hit(int x1, int y1, int x2, int y2, Runnable r) {}

	private final List<Hit> hits = new ArrayList<>();

	/** img id -> texture (null = still loading) */
	private static final Map<String, Identifier> IMAGES = new ConcurrentHashMap<>();
	private static final Map<String, int[]> IMAGE_SIZE = new ConcurrentHashMap<>();

	public DmScreen(net.minecraft.client.gui.screens.Screen parent, String with) {
		super("Messages", parent);
		this.with = with;
		chat.refresh = () -> { lastFetch = 0; fetch(true); };
		chat.onReply = m -> { replyTo = m; rebuildWidgets(); };
		chat.onProfile = n -> Compat.openScreen(new ProfileScreen(this, n));
		chat.onOpenTrade = id -> { HubScreen.tab = HubScreen.TRADES; Compat.openScreen(new HubScreen(this, HubScreen.TRADES)); };
	}

	public static DmScreen group(net.minecraft.client.gui.screens.Screen parent, String id) {
		return new DmScreen(parent, "g:" + id);
	}

	public static boolean isOpenWith(String name) {
		return name != null && name.equalsIgnoreCase(openWith);
	}

	private boolean isGroup() {
		return with != null && with.startsWith("g:");
	}

	@Override
	protected int maxW() {
		return 580;
	}

	@Override
	protected int maxH() {
		return 340;
	}

	@Override
	protected String icon() {
		return "dm";
	}

	@Override
	protected String subtitle() {
		if (with == null) return "Pick a conversation, message a friend, or make a group chat";
		if (isGroup()) return info.has("group") ? info.getAsJsonObject("group").getAsJsonArray("members").size() + " people · group chat" : "Group chat";
		String st = Social.statusText(with);
		int streak = info.has("streak") && info.get("streak").isJsonObject() && info.getAsJsonObject("streak").has("n") ? info.getAsJsonObject("streak").get("n").getAsInt() : 0;
		String mode = info.has("status") && info.get("status").isJsonObject() ? info.getAsJsonObject("status").get("mode").getAsString() : Social.mode(with);
		return Social.modeName(mode) + (st.isEmpty() ? "" : " · " + ChatView.clean(st)) + (streak > 0 ? " · " + streak + " day streak" : "");
	}

	@Override
	public void removed() {
		openWith = null;
		super.removed();
	}

	@Override
	protected void build() {
		openWith = isGroup() ? null : with;
		chat.where = with == null ? "" : isGroup() ? with : "dm:" + with;
		chat.canPin = true;
		int listW = 140;
		btn(px + 14, py + ph - 26, listW, "New group chat", this::newGroup);
		if (with != null) {
			int ix = px + listW + 22, iy = py + ph - 26;
			input = new EditBox(this.font, ix + 62, iy + 1, pw - listW - 36 - 62 - 62 - 62, 18, Component.literal("Message"));
			input.setMaxLength(300);
			input.setHint(Ui.t(replyTo != null ? "Reply to " + ChatView.str(replyTo, "name") + "..." : isGroup() ? "Message the group..." : "Message " + with + "..."));
			input.setValue(typed);
			input.setResponder(s -> typed = s);
			this.addRenderableWidget(input);
			this.setInitialFocus(input);
			btn(ix, iy, 20, "☺", () -> { emojiOpen = !emojiOpen; stickerOpen = false; });
			btn(ix + 21, iy, 20, "★", () -> { stickerOpen = !stickerOpen; emojiOpen = false; });
			btn(ix + 42, iy, 18, "▣", this::pickPicture);
			btn(px + pw - 124, iy, 58, "Send", this::sendText);
			if (isGroup()) {
				btn(px + pw - 196, py + 34, 50, "Voice", this::groupVoice);
				btn(px + pw - 144, py + 34, 40, "Add", () -> { String n = typed.trim(); if (n.isEmpty()) { Notifications.push("Add someone", "Type their name in the message box, then press Add.", Notifications.INFO); return; } groupPost("/groups/add", "name", n, "Added " + n); typed = ""; });
				btn(px + pw - 102, py + 34, 46, "Leave", () -> { groupPost("/groups/leave", null, null, "Left the group"); with = null; threads.clear(); });
			} else {
				btn(px + pw - 152, py + 34, 50, "Trade", () -> Compat.openScreen(new HubScreen(this, HubScreen.TRADES)));
				btn(px + pw - 100, py + 34, 44, "Block", () -> {
					post("/dm/block", "name", with);
					Notifications.push("Blocked " + with, "They can't message you anymore", Notifications.INFO);
				});
			}
		}
		fetch(true);
	}

	/* ---------------- networking ---------------- */

	private void fetch(boolean force) {
		if (loading || (!force && System.currentTimeMillis() - lastFetch < 2200) || !KyotoNet.loggedIn()) return;
		loading = true;
		lastFetch = System.currentTimeMillis();
		String w = with;
		KyotoNet.runAsync(() -> {
			try {
				JsonObject t = JsonParser.parseString(KyotoNet.send("GET", "/dm/threads", null, true)).getAsJsonObject();
				List<JsonObject> th = new ArrayList<>();
				if (t.has("groups")) for (JsonElement e : t.getAsJsonArray("groups")) th.add(e.getAsJsonObject());
				for (JsonElement e : t.getAsJsonArray("threads")) th.add(e.getAsJsonObject());
				th.sort((x, y) -> Long.compare(y.get("at").getAsLong(), x.get("at").getAsLong()));
				JsonObject r = null;
				if (w != null) r = JsonParser.parseString(KyotoNet.send("GET", w.startsWith("g:") ? "/groups/msgs?id=" + KyotoNet.enc(w.substring(2)) : "/dm/with?name=" + KyotoNet.enc(w), null, true)).getAsJsonObject();
				final JsonObject res = r;
				Minecraft.getInstance().execute(() -> {
					threads.clear();
					threads.addAll(th);
					if (res != null && w != null && w.equals(with)) {
						info = res;
						chat.messages.clear();
						for (JsonElement e : res.getAsJsonArray("messages")) chat.messages.add(e.getAsJsonObject());
						pins.clear();
						if (res.has("pins")) for (JsonElement e : res.getAsJsonArray("pins")) pins.add(e.getAsJsonObject());
					}
				});
			} catch (Exception e) {
				if (w != null && w.startsWith("g:")) Minecraft.getInstance().execute(() -> { if (w.equals(with)) { with = null; rebuildWidgets(); } });
			} finally {
				loading = false;
			}
		});
	}

	private void post(String path, String key, String value) {
		KyotoNet.runAsync(() -> {
			try {
				JsonObject b = new JsonObject();
				b.addProperty(key, value);
				KyotoNet.send("POST", path, b, true);
			} catch (Exception e) {
				Minecraft.getInstance().execute(() -> Notifications.push("Failed", Social.msg(e), Notifications.DANGER));
			}
		});
	}

	private void groupPost(String path, String key, String value, String ok) {
		if (!isGroup()) return;
		String id = with.substring(2);
		KyotoNet.runAsync(() -> {
			try {
				JsonObject b = new JsonObject();
				b.addProperty("id", id);
				if (key != null) b.addProperty(key, value);
				KyotoNet.send("POST", path, b, true);
				Minecraft.getInstance().execute(() -> { Notifications.push(ok, "", Notifications.GOOD); lastFetch = 0; fetch(true); });
			} catch (Exception e) {
				Minecraft.getInstance().execute(() -> Notifications.push("Failed", Social.msg(e), Notifications.DANGER));
			}
		});
	}

	private void groupVoice() {
		if (!isGroup()) return;
		JsonObject g = info.has("group") ? info.getAsJsonObject("group") : new JsonObject();
		if (g.has("voice") && !g.get("voice").isJsonNull()) { dev.kyoto.social.VoiceRooms.join(g.get("voice").getAsString()); return; }
		String id = with.substring(2), name = ChatView.str(g, "name");
		KyotoNet.runAsync(() -> {
			try {
				JsonObject b = new JsonObject();
				b.addProperty("name", name);
				b.addProperty("group", id);
				String room = JsonParser.parseString(KyotoNet.send("POST", "/voice/create", b, true)).getAsJsonObject().get("id").getAsString();
				Minecraft.getInstance().execute(() -> dev.kyoto.social.VoiceRooms.join(room));
			} catch (Exception e) {
				Minecraft.getInstance().execute(() -> Notifications.push("Couldn't start voice", Social.msg(e), Notifications.DANGER));
			}
		});
	}

	/** Type friends' names in the box (comma separated), then press New group chat. */
	private void newGroup() {
		String names = typed.trim();
		if (names.isEmpty() || with != null && !names.contains(",")) {
			if (with != null) { with = null; typed = ""; rebuildWidgets(); }
			Notifications.push("New group chat", "Type friends' names in the box, like: Steve, Alex  then press New group chat again.", Notifications.INFO, 7000);
			return;
		}
		JsonObject b = new JsonObject();
		com.google.gson.JsonArray arr = new com.google.gson.JsonArray();
		for (String n : names.split("[,\\s]+")) if (!n.isBlank()) arr.add(n.trim());
		b.add("members", arr);
		b.addProperty("name", "Group chat");
		typed = "";
		KyotoNet.runAsync(() -> {
			try {
				String id = JsonParser.parseString(KyotoNet.send("POST", "/groups/create", b, true)).getAsJsonObject().get("id").getAsString();
				Minecraft.getInstance().execute(() -> { with = "g:" + id; replyTo = null; chat.messages.clear(); rebuildWidgets(); });
			} catch (Exception e) {
				Minecraft.getInstance().execute(() -> Notifications.push("Couldn't make the group", Social.msg(e), Notifications.DANGER));
			}
		});
	}

	private void sendText() {
		String t = typed.trim();
		if (t.isEmpty() || with == null) return;
		JsonObject b = new JsonObject();
		b.addProperty("text", t);
		sendBody(b);
	}

	private void sendSticker(String id) {
		JsonObject b = new JsonObject();
		b.addProperty("sticker", id);
		stickerOpen = false;
		sendBody(b);
	}

	private void sendBody(JsonObject b) {
		if (replyTo != null && replyTo.has("id")) b.addProperty("reply", replyTo.get("id").getAsInt());
		String path;
		if (isGroup()) { b.addProperty("id", with.substring(2)); path = "/groups/send"; }
		else { b.addProperty("to", with); path = "/dm/send"; }
		typed = "";
		replyTo = null;
		rebuildWidgets();
		chat.toBottom();
		KyotoNet.runAsync(() -> {
			try {
				KyotoNet.send("POST", path, b, true);
				lastFetch = 0;
				fetch(true);
			} catch (Exception e) {
				Minecraft.getInstance().execute(() -> Notifications.push("Not sent", Social.msg(e), Notifications.DANGER));
			}
		});
	}

	/** Opens your computer's file picker (falls back to the kyoto/pictures folder). */
	private void pickPicture() {
		if (with == null) return;
		String to = with;
		boolean grp = isGroup();
		Thread t = new Thread(() -> {
			Path file = null;
			try {
				String p = openDialog();
				if (p != null) file = Path.of(p);
			} catch (Throwable e) {
				Path dir = net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir().resolve("kyoto").resolve("pictures");
				try {
					Files.createDirectories(dir);
					try (var s = Files.list(dir)) {
						file = s.filter(f -> f.toString().toLowerCase().matches(".*\\.(png|jpe?g)$"))
								.max((a, b) -> Long.compare(a.toFile().lastModified(), b.toFile().lastModified())).orElse(null);
					}
				} catch (Exception ignored) {
				}
				if (file == null) {
					Minecraft.getInstance().execute(() -> {
						Notifications.push("Put the picture in kyoto/pictures", "Then press the picture button again", Notifications.INFO);
						Compat.openFolder(dir);
					});
					return;
				}
			}
			if (file == null) return;
			try {
				byte[] png = shrink(Files.readAllBytes(file), 1024);
				if (png.length > 2_000_000) throw new IllegalStateException("Picture is too big (max 2 MB)");
				JsonObject b = new JsonObject();
				if (grp) b.addProperty("id", to.substring(2)); else b.addProperty("to", to);
				b.addProperty("image", Base64.getEncoder().encodeToString(png));
				KyotoNet.send("POST", grp ? "/groups/image" : "/dm/image", b, true);
				lastFetch = 0;
				fetch(true);
			} catch (Exception e) {
				Minecraft.getInstance().execute(() -> Notifications.push("Picture not sent", e.getMessage(), Notifications.DANGER));
			}
		}, "Kyoto-Picture");
		t.setDaemon(true);
		t.start();
	}

	/** LWJGL's native file picker (looked up by name so Kyoto still builds if it's missing). */
	private static String openDialog() throws Exception {
		Class<?> c = Class.forName("org.lwjgl.util.tinyfd.TinyFileDialogs");
		java.lang.reflect.Method m = c.getMethod("tinyfd_openFileDialog", CharSequence.class, CharSequence.class, org.lwjgl.PointerBuffer.class, CharSequence.class, boolean.class);
		return (String) m.invoke(null, "Send a picture (PNG or JPG)", "", null, null, false);
	}

	/** Re-encodes any PNG/JPG as a PNG no bigger than max px on its long side. */
	private static byte[] shrink(byte[] data, int max) throws Exception {
		BufferedImage src = ImageIO.read(new ByteArrayInputStream(data));
		if (src == null) throw new IllegalStateException("Not a PNG or JPG");
		float k = Math.min(1f, max / (float) Math.max(src.getWidth(), src.getHeight()));
		int w = Math.max(1, Math.round(src.getWidth() * k)), h = Math.max(1, Math.round(src.getHeight() * k));
		BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = out.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
		g.drawImage(src, 0, 0, w, h, null);
		g.dispose();
		ByteArrayOutputStream bo = new ByteArrayOutputStream();
		ImageIO.write(out, "png", bo);
		return bo.toByteArray();
	}

	private static Identifier image(String id) {
		if (IMAGES.containsKey(id)) return IMAGES.get(id);
		IMAGES.put(id, Ui.tex("textures/gui/icons/image.png")); // placeholder while loading
		KyotoNet.runAsync(() -> {
			try {
				byte[] png = shrink(KyotoNet.bytesAuth("/dm/img?id=" + KyotoNet.enc(id)), 512);
				BufferedImage bi = ImageIO.read(new ByteArrayInputStream(png));
				IMAGE_SIZE.put(id, new int[]{bi.getWidth(), bi.getHeight()});
				Identifier tex = Identifier.fromNamespaceAndPath("kyoto", "dm/" + id.replaceAll("[^a-z0-9]", ""));
				Compat.registerPng(tex, png, () -> IMAGES.put(id, tex), true);
			} catch (Exception ignored) {
			}
		});
		return IMAGES.get(id);
	}

	/* ---------------- drawing ---------------- */

	@Override
	protected void drawContent(GuiGraphicsExtractor g, int mx, int my, float a) {
		fetch(false);
		hits.clear();
		int listW = 140;
		int lx = px + 12, ly = py + 36;
		// conversations: group chats + DMs, newest first
		Ui.small(g, "CONVERSATIONS", lx + 2, ly, Anim.argb(Ui.DIM, a));
		int y = ly + 10;
		if (threads.isEmpty()) Ui.small(g, loading ? "Loading..." : "Nothing yet", lx + 2, y + 2, Anim.argb(Ui.MUTED, a));
		for (JsonObject t : threads) {
			if (y > py + ph - 56) break;
			boolean grp = t.has("group") && t.get("group").getAsBoolean();
			String key = grp ? "g:" + t.get("id").getAsString() : t.get("name").getAsString();
			String name = t.get("name").getAsString();
			boolean sel = key.equalsIgnoreCase(with == null ? "" : with);
			boolean hv = Ui.inside(mx, my, lx, y, lx + listW, y + 24);
			Ui.round(g, lx, y, lx + listW, y + 24, 6, Anim.argb(sel ? Ui.accent() : Ui.INK, (sel ? 0.18f : hv ? 0.07f : 0.03f) * a));
			if (grp) {
				int col = 0xA98BFF;
				try { col = Integer.parseInt(ChatView.str(t, "color").replace("#", ""), 16); } catch (Exception ignored) { }
				Ui.round(g, lx + 4, y + 4, lx + 20, y + 20, 4, Anim.argb(col, a));
				com.google.gson.JsonArray mem = t.getAsJsonArray("members");
				for (int i = 0; i < Math.min(2, mem.size()); i++) Ui.head(g, mem.get(i).getAsString(), lx + 5 + i * 7, y + 5 + i * 7, 8, a);
			} else {
				Ui.head(g, name, lx + 4, y + 4, 16, a);
				String mode = t.has("status") && t.get("status").isJsonObject() ? t.getAsJsonObject("status").get("mode").getAsString() : "offline";
				Ui.round(g, lx + 16, y + 16, lx + 22, y + 22, 3, Anim.argb(Social.modeColor(mode), a));
			}
			String nm = Ui.ellipsize(ChatView.clean(name), listW - 50);
			Ui.text(g, nm, lx + 25, y + 3, Anim.argb(Ui.TEXT, a));
			int streak = !grp && t.has("streak") && t.get("streak").isJsonObject() && t.getAsJsonObject("streak").has("n") ? t.getAsJsonObject("streak").get("n").getAsInt() : 0;
			if (streak > 0) {
				int sx = lx + 27 + Ui.width(nm);
				Ui.iconTex(g, "flame", sx, y + 2, 8, Anim.argb(Ui.MUTED, a));
				Ui.small(g, String.valueOf(streak), sx + 9, y + 4, Anim.argb(Ui.MUTED, a));
			}
			String st = !grp && t.has("status") && t.get("status").isJsonObject() ? ChatView.str(t.getAsJsonObject("status"), "text") : "";
			String last = grp ? ChatView.str(t, "last") : !st.isEmpty() ? st : (t.get("fromMe").getAsBoolean() ? "You: " : "") + ChatView.str(t, "last");
			Ui.small(g, Ui.ellipsize(ChatView.clean(last), Math.round((listW - 30) / 0.75f)), lx + 25, y + 13, Anim.argb(Ui.MUTED, a));
			hits.add(new Hit(lx, y, lx + listW, y + 24, () -> {
				with = key;
				typed = "";
				replyTo = null;
				info = new JsonObject();
				chat.messages.clear();
				lastFetch = 0;
				rebuildWidgets();
			}));
			y += 27;
		}
		g.fill(px + listW + 16, py + 34, px + listW + 17, py + ph - 10, Anim.argb(Ui.INK, 0.06f * a));
		if (with == null) {
			Ui.text(g, "Pick someone on the left, or open Friends > Message.", px + listW + 26, py + 60, Anim.argb(Ui.MUTED, a));
			Ui.small(g, "Group chat: type friends' names below (Steve, Alex) and press New group chat.", px + listW + 26, py + 74, Anim.argb(Ui.MUTED, a));
			return;
		}
		int cx1 = px + listW + 22, cx2 = px + pw - 12, top = py + 58, bottom = py + ph - 32;
		// title line
		if (isGroup() && info.has("group")) {
			JsonObject gi = info.getAsJsonObject("group");
			Ui.bold(g, Ui.ellipsize(ChatView.clean(ChatView.str(gi, "name")), cx2 - cx1 - 200), cx1, py + 40, Anim.argb(Ui.TEXT, a));
		} else if (!isGroup()) {
			Ui.head(g, with, cx1, py + 37, 14, a);
			Ui.bold(g, with, cx1 + 18, py + 40, Anim.argb(Ui.TEXT, a));
		}
		if (!pins.isEmpty()) {
			JsonObject last = pins.get(pins.size() - 1);
			Ui.round(g, cx1, top - 2, cx2, top + 10, 4, Anim.argb(Ui.INK, 0.08f * a));
			Ui.iconTex(g, "pin", cx1 + 3, top, 7, Anim.argb(Ui.INK, a));
			Ui.small(g, Ui.ellipsize(pins.size() + " pinned · " + ChatView.str(last, "name") + ": " + ChatView.clean(ChatView.str(last, "text")), Math.round((cx2 - cx1 - 20) / 0.75f)), cx1 + 13, top + 1, Anim.argb(Ui.INK, a));
			top += 12;
		}
		int chatBottom = bottom - (replyTo != null ? 12 : 0);
		boolean overlay = emojiOpen || stickerOpen;
		chat.draw(g, cx1 - 2, top, cx2 + 2, chatBottom, overlay ? -1 : mx, overlay ? -1 : my, a);
		if (replyTo != null) {
			Ui.round(g, cx1, bottom - 11, cx2, bottom, 4, Anim.argb(Ui.accent(), 0.12f * a));
			Ui.small(g, Ui.ellipsize("Replying to " + ChatView.str(replyTo, "name") + ": " + ChatView.clean(ChatView.str(replyTo, "text")), Math.round((cx2 - cx1 - 30) / 0.75f)), cx1 + 4, bottom - 8, Anim.argb(Ui.TEXT, a));
			Ui.small(g, "x", cx2 - 8, bottom - 8, Anim.argb(Ui.MUTED, a));
			hits.add(new Hit(cx2 - 14, bottom - 11, cx2, bottom, () -> { replyTo = null; rebuildWidgets(); }));
		}

		// emoji picker
		if (emojiOpen) {
			int cols = 11, sz = 14, gap = 3;
			int rows = (Emojis.NAMES.length + cols - 1) / cols;
			int ew = cols * (sz + gap) + 8, eh = rows * (sz + gap) + 8;
			int ex = cx1, ey = py + ph - 34 - eh;
			Ui.round(g, ex, ey, ex + ew, ey + eh, 8, Anim.argb(Ui.GLASS, 0.98f * a));
			for (int i = 0; i < Emojis.NAMES.length; i++) {
				String n = Emojis.NAMES[i];
				int x = ex + 4 + (i % cols) * (sz + gap), yy = ey + 4 + (i / cols) * (sz + gap);
				boolean hv = Ui.inside(mx, my, x, yy, x + sz, yy + sz);
				if (hv) Ui.round(g, x - 1, yy - 1, x + sz + 1, yy + sz + 1, 4, Anim.argb(Ui.INK, 0.15f * a));
				Compat.blitTint(g, Emojis.tex(n), x, yy, sz, sz, Anim.argb(0xFFFFFF, a));
				hits.add(new Hit(x, yy, x + sz, yy + sz, () -> {
					typed = typed + (typed.isEmpty() || typed.endsWith(" ") ? "" : " ") + ":" + n + ": ";
					if (input != null) {
						input.setValue(typed);
						input.setFocused(true);
					}
				}));
			}
		}
		// sticker picker
		if (stickerOpen) {
			int cols = 8, sz = 28, gap = 4, rows = (Social.STICKERS.length + cols - 1) / cols;
			int w = cols * (sz + gap) + 8, h = rows * (sz + gap) + 8, ex = cx1, ey = py + ph - 34 - h;
			Ui.round(g, ex, ey, ex + w, ey + h, 8, Anim.argb(Ui.GLASS, 0.98f * a));
			for (int i = 0; i < Social.STICKERS.length; i++) {
				int sx = ex + 4 + (i % cols) * (sz + gap), sy = ey + 4 + (i / cols) * (sz + gap);
				boolean hv = Ui.inside(mx, my, sx, sy, sx + sz, sy + sz);
				if (hv) Ui.round(g, sx - 1, sy - 1, sx + sz + 1, sy + sz + 1, 6, Anim.argb(Ui.INK, 0.12f * a));
				Compat.blitTint(g, Social.sticker(Social.STICKERS[i]), sx, sy, sz, sz, Anim.argb(0xFFFFFF, a));
				String id = Social.STICKERS[i];
				hits.add(new Hit(sx, sy, sx + sz, sy + sz, () -> sendSticker(id)));
			}
		}
		if (bigImage != null) {
			g.fill(0, 0, this.width, this.height, Anim.argb(0x000000, 0.85f));
			int s = Math.min(this.width, this.height) - 40;
			Compat.blit(g, bigImage, this.width / 2 - s / 2, this.height / 2 - s / 2, s, s);
			Ui.small(g, "Click anywhere to close", this.width / 2f - Ui.widthSmall("Click anywhere to close") / 2f, this.height - 14, Anim.argb(Ui.MUTED, 1f));
		}
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (bigImage != null) {
			bigImage = null;
			return true;
		}
		for (int i = hits.size() - 1; i >= 0; i--) {
			Hit h = hits.get(i);
			if (Ui.inside(event.x(), event.y(), h.x1(), h.y1(), h.x2(), h.y2())) {
				h.r().run();
				return true;
			}
		}
		if ((emojiOpen || stickerOpen) && event.y() < py + ph - 30) { emojiOpen = false; stickerOpen = false; return true; }
		if (with != null && chat.click(event.x(), event.y())) return true;
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		chat.scroll(scrollY);
		return true;
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if ((event.key() == 257 || event.key() == 335) && input != null && input.isFocused()) {
			sendText();
			return true;
		}
		if (event.key() == 256 && replyTo != null) { replyTo = null; rebuildWidgets(); return true; }
		return super.keyPressed(event);
	}
}
