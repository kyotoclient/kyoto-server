package dev.kyoto.social;

import com.google.gson.JsonObject;
import dev.kyoto.config.KyotoConfig;
import dev.kyoto.module.Modules;
import dev.kyoto.music.Music;
import dev.kyoto.util.Compat;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.RandomAccessFile;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.UUID;

/**
 * Discord "Playing Kyoto Client" status, talking straight to the Discord app over its local
 * IPC pipe (no extra libraries). Needs a free Discord Application ID - see the guide.
 */
public final class DiscordRpc {
	private DiscordRpc() {}

	private static final Logger LOG = LoggerFactory.getLogger("kyoto-discord");
	public static volatile String status = "Off";
	private static final long START = System.currentTimeMillis() / 1000;
	private static Thread thread;

	private static RandomAccessFile pipe;     // Windows
	private static SocketChannel socket;      // Mac / Linux

	private static volatile String serverId = "";
	private static long lastFetch = 0;

	/** Everyone shares Kyoto's Discord app (set once on the server), so there's nothing to set up. */
	private static String serverAppId() {
		if (serverId.isBlank() && System.currentTimeMillis() - lastFetch > 60000) {
			lastFetch = System.currentTimeMillis();
			try {
				JsonObject o = com.google.gson.JsonParser.parseString(dev.kyoto.net.KyotoNet.send("GET", "/config", null, false)).getAsJsonObject();
				if (o.has("discordAppId")) serverId = o.get("discordAppId").getAsString().replaceAll("[^0-9]", "");
			} catch (Exception ignored) { }
		}
		return serverId;
	}

	public static void start() {
		if (thread != null) return;
		thread = new Thread(DiscordRpc::run, "Kyoto-Discord");
		thread.setDaemon(true);
		thread.start();
	}

	private static void run() {
		boolean connected = false;
		while (true) {
			try {
				String appId = KyotoConfig.get().discordAppId;
				if (appId.isBlank()) appId = serverAppId();
				if (!Modules.on("discord_rpc") || appId.isBlank()) {
					if (connected) close();
					connected = false;
					status = !Modules.on("discord_rpc") ? "Off" : "Waiting for the Kyoto server...";
					Thread.sleep(3000);
					continue;
				}
				if (!connected) {
					open();
					JsonObject hs = new JsonObject();
					hs.addProperty("v", 1);
					hs.addProperty("client_id", appId);
					write(0, hs.toString());
					read();
					connected = true;
					status = "Connected to Discord";
				}
				write(1, activity().toString());
				read();
				Thread.sleep(15000);
			} catch (Exception e) {
				connected = false;
				close();
				status = "Discord not running";
				LOG.debug("discord", e);
				try {
					Thread.sleep(15000);
				} catch (InterruptedException ignored) {
				}
			}
		}
	}

	private static JsonObject activity() {
		Minecraft mc = Minecraft.getInstance();
		String ip = dev.kyoto.feature.Privacy.server(Compat.currentServerIp());
		String details;
		if (mc.player == null) details = "In the menus";
		else if (ip != null) details = "Playing on " + ip;
		else details = "Playing singleplayer";
		String state = Music.active() && Music.playing ? "♫ " + Music.title + " - " + Music.artist : "Kyoto Client · 26.3";
		// per server: "On DonutSMP · +$1.2M this session" / "On Hypixel · BedWars" (Discord Status settings)
		int what = Modules.opt("discord.server");
		if (mc.player != null && ip != null && dev.kyoto.servers.Packs.current != null && what > 0) {
			details = "On " + (dev.kyoto.feature.Privacy.hideServer() ? "a server" : dev.kyoto.servers.Packs.serverLabel);
			double made = dev.kyoto.servers.ServerData.sessionMoney();
			if (what == 2 && made != 0) state = (made > 0 ? "+" : "") + dev.kyoto.servers.Packs.fmt(made) + " this session";
			else if (dev.kyoto.servers.Packs.is("hypixel") && !dev.kyoto.servers.Hypixel.gameName().isEmpty()) state = dev.kyoto.servers.Hypixel.gameName();
		}
		if (dev.kyoto.extra.Afk.afk) state = "AFK";

		JsonObject act = new JsonObject();
		act.addProperty("details", trim(details));
		act.addProperty("state", trim(state));
		JsonObject ts = new JsonObject();
		ts.addProperty("start", START);
		act.add("timestamps", ts);
		JsonObject assets = new JsonObject();
		assets.addProperty("large_image", "kyoto");
		assets.addProperty("large_text", "Kyoto Client");
		act.add("assets", assets);

		JsonObject args = new JsonObject();
		args.addProperty("pid", ProcessHandle.current().pid());
		args.add("activity", act);
		JsonObject cmd = new JsonObject();
		cmd.addProperty("cmd", "SET_ACTIVITY");
		cmd.add("args", args);
		cmd.addProperty("nonce", UUID.randomUUID().toString());
		return cmd;
	}

	private static String trim(String s) {
		return s.length() > 120 ? s.substring(0, 117) + "..." : s;
	}

	private static void open() throws Exception {
		String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
		Exception last = null;
		for (int i = 0; i < 10; i++) {
			try {
				if (os.contains("win")) {
					pipe = new RandomAccessFile("\\\\.\\pipe\\discord-ipc-" + i, "rw");
				} else {
					String dir = System.getenv("XDG_RUNTIME_DIR");
					if (dir == null) dir = System.getenv("TMPDIR");
					if (dir == null) dir = "/tmp";
					Path p = Path.of(dir, "discord-ipc-" + i);
					if (!Files.exists(p)) continue;
					socket = SocketChannel.open(UnixDomainSocketAddress.of(p));
				}
				return;
			} catch (Exception e) {
				last = e;
			}
		}
		throw last != null ? last : new IllegalStateException("Discord not found");
	}

	private static void write(int op, String json) throws Exception {
		byte[] data = json.getBytes(StandardCharsets.UTF_8);
		ByteBuffer buf = ByteBuffer.allocate(8 + data.length).order(ByteOrder.LITTLE_ENDIAN);
		buf.putInt(op).putInt(data.length).put(data);
		buf.flip();
		if (pipe != null) pipe.write(buf.array(), 0, buf.limit());
		else socket.write(buf);
	}

	private static void read() throws Exception {
		ByteBuffer head = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
		if (pipe != null) {
			byte[] h = new byte[8];
			pipe.readFully(h);
			head.put(h).flip();
			int len = head.getInt(4);
			pipe.readFully(new byte[len]);
		} else {
			while (head.hasRemaining()) socket.read(head);
			head.flip();
			int len = head.getInt(4);
			ByteBuffer body = ByteBuffer.allocate(len);
			while (body.hasRemaining()) socket.read(body);
		}
	}

	private static void close() {
		try {
			if (pipe != null) pipe.close();
		} catch (Exception ignored) {
		}
		try {
			if (socket != null) socket.close();
		} catch (Exception ignored) {
		}
		pipe = null;
		socket = null;
	}
}
