package dev.kyoto.extra;

import com.google.gson.JsonObject;
import dev.kyoto.servers.Packs;
import net.minecraft.client.Minecraft;

import java.util.Random;

/**
 * World seeds for the slime chunk finder. In singleplayer Kyoto reads the seed itself; on a server you type it
 * once with /kyoto seed <number> (servers like to keep it secret - many show it with /seed).
 */
public final class Seeds {
	private Seeds() {}

	private static Long spCache = null;
	private static Object spServer = null;

	public static Long seed() {
		Minecraft mc = Minecraft.getInstance();
		Object srv = mc.getSingleplayerServer();
		if (srv != null && mc.getCurrentServer() == null) {
			if (srv != spServer) { spServer = srv; spCache = readSp(srv); }
			return spCache;
		}
		String host = Packs.host;
		if (host == null || host.isEmpty()) return null;
		JsonObject o = Packs.read("seeds");
		return o.has(host) ? o.get(host).getAsLong() : null;
	}

	public static String set(long seed) {
		String host = Packs.host;
		if (host == null || host.isEmpty()) return "Join the server first, then run this again.";
		JsonObject o = Packs.read("seeds");
		o.addProperty(host, seed);
		Packs.write("seeds", o);
		Extras.slimeShown = true;
		return "Seed saved for " + host + ". Slime chunks show in green (Slime Chunks feature).";
	}

	private static Long readSp(Object server) {
		// server.getWorldData().worldGenOptions().seed() - names have moved around, so try a few
		try {
			Object wd = server.getClass().getMethod("getWorldData").invoke(server);
			for (String m : new String[]{"worldGenOptions", "worldGenSettings"}) {
				try {
					Object opts = wd.getClass().getMethod(m).invoke(wd);
					for (String s : new String[]{"seed", "getSeed"}) {
						try {
							return (Long) opts.getClass().getMethod(s).invoke(opts);
						} catch (NoSuchMethodException ignored) {
						}
					}
				} catch (NoSuchMethodException ignored) {
				}
			}
		} catch (Throwable ignored) {
		}
		try {
			Object level = server.getClass().getMethod("overworld").invoke(server);
			return (Long) level.getClass().getMethod("getSeed").invoke(level);
		} catch (Throwable ignored) {
		}
		return null;
	}

	/** The same maths Minecraft uses to pick slime chunks. */
	public static boolean slimeChunk(long seed, int x, int z) {
		Random r = new Random(seed + (long) (x * x * 0x4c1906) + (long) (x * 0x5ac0db) + (long) (z * z) * 0x4307a7L + (long) (z * 0x5f24f) ^ 0x3ad8025fL);
		return r.nextInt(10) == 0;
	}
}
