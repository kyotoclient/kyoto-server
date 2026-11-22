package dev.kyoto.feature;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.kyoto.hud.Notifications;
import dev.kyoto.screens.WorldScreens;
import dev.kyoto.util.Anim;
import dev.kyoto.util.Compat;
import dev.kyoto.util.Ui;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.joml.Matrix4f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

/**
 * Build Recorder: records every block you place and break, in order, and saves it as a .kybuild file.
 * Open it in Kyoto Studio to watch a timelapse of the build, see a list of materials, and export a real
 * Litematica schematic (.litematic).
 *
 * Two ways to record:
 *  - Follow me: records what YOU place and break, anywhere. Nothing to set up.
 *  - Area: pick two corners; every change inside the box is recorded (other players, WorldEdit, pistons...).
 *
 * It only reads blocks you can already see - nothing is sent to the server, nothing is changed.
 */
public final class BuildRecorder {
	private BuildRecorder() {}

	private static final Logger LOG = LoggerFactory.getLogger("kyoto-build");
	public static final int MAX_AREA = 4_194_304;      // 256 x 64 x 256
	private static final int MAX_EVENTS = 3_000_000;
	private static final int SCAN_PER_TICK = 40_000;

	public enum Mode { FOLLOW, AREA }

	/* ---------------- state ---------------- */
	private static boolean recording = false, paused = false, preparing = false;
	private static Mode mode = Mode.FOLLOW;
	private static String name = "My Build", dim = "";
	private static long startedAt = 0, pausedAt = 0, pausedTotal = 0;
	public static BlockPos corner1 = null, corner2 = null;

	// palette: block state text ("minecraft:oak_stairs[facing=north,...]") -> id
	private static final List<String> PALETTE = new ArrayList<>();
	private static final List<int[]> PALETTE_COLOR = new ArrayList<>(); // [rgb, flags, light]
	private static final List<float[]> PALETTE_BOX = new ArrayList<>();
	private static final Map<String, Integer> PALETTE_IDS = new HashMap<>();
	private static final Map<BlockState, Integer> STATE_IDS = new IdentityHashMap<>();

	private static final List<int[]> EVENTS = new ArrayList<>();       // {t, x, y, z, paletteId}
	private static final Map<Long, Integer> KNOWN = new HashMap<>();   // follow mode: last seen state
	private static final Map<Long, Integer> FIRST = new HashMap<>();   // state before the first change
	private static final Map<Long, Integer> CHECK = new HashMap<>();   // positions to look at for a few ticks
	private static final List<float[]> CAMERA = new ArrayList<>();     // {t, x, y, z, yaw, pitch}
	private static int placed = 0, broken = 0;

	// area mode
	private static int ax, ay, az, sx, sy, sz;
	private static int[] area = null;  // palette id per block, -1 = not loaded yet
	private static int scanCursor = 0, prepared = 0;

	public static boolean recording() { return recording; }
	public static boolean paused() { return paused; }
	public static boolean preparing() { return preparing; }
	public static Mode mode() { return mode; }
	public static String name() { return name; }
	public static int placed() { return placed; }
	public static int broken() { return broken; }
	public static int events() { return EVENTS.size(); }

	public static long elapsed() {
		if (!recording) return 0;
		long now = paused ? pausedAt : System.currentTimeMillis();
		return Math.max(0, now - startedAt - pausedTotal);
	}

	public static Path folder() {
		Path p = FabricLoader.getInstance().getGameDir().resolve("kyoto").resolve("builds");
		try { Files.createDirectories(p); } catch (Exception ignored) { }
		return p;
	}

	public static List<Path> files() {
		List<Path> out = new ArrayList<>();
		try (var s = Files.list(folder())) {
			s.filter(f -> f.getFileName().toString().endsWith(".kybuild")).forEach(out::add);
		} catch (Exception ignored) { }
		out.sort((a, b) -> Long.compare(b.toFile().lastModified(), a.toFile().lastModified()));
		return out;
	}

	/* ---------------- palette ---------------- */

	/** "Block{minecraft:oak_stairs}[facing=north,half=bottom]" -> "minecraft:oak_stairs[facing=north,half=bottom]" */
	static String stateText(BlockState s) {
		String t = s.toString();
		int a = t.indexOf('{'), b = t.indexOf('}');
		if (a >= 0 && b > a) return t.substring(a + 1, b) + t.substring(b + 1);
		return t;
	}

	private static int id(BlockState s, Level level, BlockPos pos) {
		Integer known = STATE_IDS.get(s);
		if (known != null) return known;
		String text = stateText(s);
		Integer byText = PALETTE_IDS.get(text);
		if (byText != null) { STATE_IDS.put(s, byText); return byText; }
		int id = PALETTE.size();
		PALETTE.add(text);
		PALETTE_IDS.put(text, id);
		STATE_IDS.put(s, id);
		int rgb = 0, flags = 0, light = 0;
		float[] box = {0, 0, 0, 1, 1, 1};
		try { rgb = s.getMapColor(level, pos).col; } catch (Throwable ignored) { }
		try { light = s.getLightEmission(); } catch (Throwable ignored) { }
		try {
			if (!s.getFluidState().isEmpty()) flags |= 2;
		} catch (Throwable ignored) { }
		try {
			VoxelShape shape = s.getShape(level, pos);
			if (shape.isEmpty()) { flags |= 8; box = new float[]{.3f, 0, .3f, .7f, .6f, .7f}; }
			else { AABB bb = shape.bounds(); box = new float[]{(float) bb.minX, (float) bb.minY, (float) bb.minZ, (float) bb.maxX, (float) bb.maxY, (float) bb.maxZ}; }
		} catch (Throwable ignored) { }
		if ((flags & 2) != 0) box = new float[]{0, 0, 0, 1, .875f, 1};
		String n = text.toLowerCase(Locale.ROOT);
		int br = n.indexOf('[');
		if (br >= 0) n = n.substring(0, br); // "waterlogged=false" isn't water
		if (n.contains("glass") || n.endsWith("ice") || n.endsWith(":water") || n.contains("leaves") || n.contains("slime") || n.contains("honey_block")) flags |= 1;
		PALETTE_COLOR.add(new int[]{rgb, flags, light});
		PALETTE_BOX.add(box);
		return id;
	}

	private static final int AIR_ID = 0;

	private static void resetPalette() {
		PALETTE.clear(); PALETTE_COLOR.clear(); PALETTE_BOX.clear(); PALETTE_IDS.clear(); STATE_IDS.clear();
		PALETTE.add("minecraft:air");
		PALETTE_IDS.put("minecraft:air", 0);
		PALETTE_COLOR.add(new int[]{0, 0, 0});
		PALETTE_BOX.add(new float[]{0, 0, 0, 1, 1, 1});
	}

	private static int read(Level level, BlockPos pos) {
		BlockState s = level.getBlockState(pos);
		if (s.isAir()) return AIR_ID;
		return id(s, level, pos);
	}

	/* ---------------- start / stop ---------------- */

	public static String start(String buildName) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null || mc.player == null) return "Join a world first.";
		if (recording) return "Already recording - stop it first.";
		clear();
		mode = Mode.FOLLOW;
		name = clean(buildName);
		dim = Compat.dimensionId();
		startedAt = System.currentTimeMillis();
		recording = true;
		Notifications.push("Recording your build", "Place and break blocks like normal. /kyoto build stop when you're done.", 0xFF5D6C, 5000, null, "●");
		return null;
	}

	public static String startArea(String buildName) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null || mc.player == null) return "Join a world first.";
		if (recording) return "Already recording - stop it first.";
		if (corner1 == null || corner2 == null) return "Set both corners first (look at a block, then Corner 1 / Corner 2).";
		int x0 = Math.min(corner1.getX(), corner2.getX()), y0 = Math.min(corner1.getY(), corner2.getY()), z0 = Math.min(corner1.getZ(), corner2.getZ());
		int x1 = Math.max(corner1.getX(), corner2.getX()), y1 = Math.max(corner1.getY(), corner2.getY()), z1 = Math.max(corner1.getZ(), corner2.getZ());
		long vol = (long) (x1 - x0 + 1) * (y1 - y0 + 1) * (z1 - z0 + 1);
		if (vol > MAX_AREA) return "That area is too big (" + vol + " blocks, max " + MAX_AREA + "). Make it smaller.";
		clear();
		mode = Mode.AREA;
		name = clean(buildName);
		dim = Compat.dimensionId();
		ax = x0; ay = y0; az = z0; sx = x1 - x0 + 1; sy = y1 - y0 + 1; sz = z1 - z0 + 1;
		area = new int[(int) vol];
		java.util.Arrays.fill(area, -1);
		preparing = true; prepared = 0; scanCursor = 0;
		recording = true;
		startedAt = System.currentTimeMillis();
		Notifications.push("Getting the area ready", vol + " blocks - one moment", 0xFFD36B, 3000, null, "●");
		return null;
	}

	private static String clean(String n) {
		n = n == null ? "" : n.trim().replaceAll("[\\\\/:*?\"<>|]", "");
		return n.isEmpty() ? "My Build" : n.length() > 40 ? n.substring(0, 40) : n;
	}

	private static void clear() {
		resetPalette();
		EVENTS.clear(); KNOWN.clear(); FIRST.clear(); CHECK.clear(); CAMERA.clear();
		placed = 0; broken = 0; paused = false; pausedTotal = 0; preparing = false; area = null;
	}

	public static void pause(boolean p) {
		if (!recording || p == paused) return;
		if (p) pausedAt = System.currentTimeMillis();
		else pausedTotal += System.currentTimeMillis() - pausedAt;
		paused = p;
	}

	public static void cancel() {
		recording = false;
		clear();
		Notifications.push("Recording thrown away", "", Notifications.INFO);
	}

	/** Stops and writes the .kybuild file (in the background). */
	public static void stop() {
		if (!recording) return;
		Minecraft mc = Minecraft.getInstance();
		long dur = elapsed();
		// a last look at everything, so the schematic matches the world exactly
		JsonArray fin = new JsonArray();
		int[] bb = null;
		if (mc.level != null && dim.equals(Compat.dimensionId())) {
			if (mode == Mode.AREA) {
				bb = new int[]{ax, ay, az, ax + sx - 1, ay + sy - 1, az + sz - 1};
				finishArea(mc.level, fin);
			} else bb = finishFollow(mc.level, fin);
		}
		final int[] box = bb;
		final JsonArray fin2 = fin;
		JsonObject o = toJson(dur, box, fin2);
		recording = false;
		String file = name.replace(' ', '_') + "_" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm")) + ".kybuild";
		Path out = folder().resolve(file);
		int evs = EVENTS.size();
		clear();
		Thread t = new Thread(() -> {
			try (var w = new OutputStreamWriter(new GZIPOutputStream(Files.newOutputStream(out)), StandardCharsets.UTF_8)) {
				w.write(o.toString());
				Compat.runOnClient(() -> Notifications.push("Build saved", file + " · " + evs + " changes · open it in Kyoto Studio", Notifications.GOOD, 7000, null, "✦"));
			} catch (Exception e) {
				LOG.warn("Couldn't save build", e);
				Compat.runOnClient(() -> Notifications.push("Couldn't save the build", e.getMessage(), Notifications.DANGER));
			}
		}, "Kyoto-BuildSave");
		t.setDaemon(true);
		t.start();
	}

	/** Follow mode: the box around everything you changed, read block by block. */
	private static int[] finishFollow(Level level, JsonArray fin) {
		if (FIRST.isEmpty()) return null;
		int x0 = Integer.MAX_VALUE, y0 = Integer.MAX_VALUE, z0 = Integer.MAX_VALUE, x1 = Integer.MIN_VALUE, y1 = Integer.MIN_VALUE, z1 = Integer.MIN_VALUE;
		for (long k : FIRST.keySet()) {
			int x = BlockPos.getX(k), y = BlockPos.getY(k), z = BlockPos.getZ(k);
			x0 = Math.min(x0, x); y0 = Math.min(y0, y); z0 = Math.min(z0, z); x1 = Math.max(x1, x); y1 = Math.max(y1, y); z1 = Math.max(z1, z);
		}
		long vol = (long) (x1 - x0 + 1) * (y1 - y0 + 1) * (z1 - z0 + 1);
		BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
		if (vol <= MAX_AREA) {
			for (int y = y0; y <= y1; y++) for (int z = z0; z <= z1; z++) for (int x = x0; x <= x1; x++) {
				m.set(x, y, z);
				int p = level.isLoaded(m) ? read(level, m) : KNOWN.getOrDefault(m.asLong(), AIR_ID);
				if (p != AIR_ID) fin.add(arr(x - x0, y - y0, z - z0, p));
			}
		} else {
			// huge spread-out recording: just the blocks you touched
			for (long k : FIRST.keySet()) {
				m.set(k);
				int p = level.isLoaded(m) ? read(level, m) : KNOWN.getOrDefault(k, AIR_ID);
				if (p != AIR_ID) fin.add(arr(m.getX() - x0, m.getY() - y0, m.getZ() - z0, p));
			}
		}
		return new int[]{x0, y0, z0, x1, y1, z1};
	}

	private static void finishArea(Level level, JsonArray fin) {
		BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
		for (int i = 0; i < area.length; i++) {
			int x = i % sx, z = (i / sx) % sz, y = i / (sx * sz);
			m.set(ax + x, ay + y, az + z);
			int p = level.isLoaded(m) ? read(level, m) : area[i];
			if (p > 0) fin.add(arr(x, y, z, p));
		}
	}

	private static JsonArray arr(int... v) {
		JsonArray a = new JsonArray();
		for (int i : v) a.add(i);
		return a;
	}

	private static JsonObject toJson(long dur, int[] box, JsonArray fin) {
		JsonObject o = new JsonObject();
		o.addProperty("format", "kybuild");
		o.addProperty("version", 1);
		o.addProperty("name", name);
		o.addProperty("author", Compat.myName());
		o.addProperty("mode", mode == Mode.AREA ? "area" : "follow");
		o.addProperty("mc", gameVersion());
		o.addProperty("dataVersion", dataVersion());
		o.addProperty("created", System.currentTimeMillis());
		o.addProperty("duration", dur);
		o.addProperty("dimension", dim);
		int x0 = box == null ? 0 : box[0], y0 = box == null ? 0 : box[1], z0 = box == null ? 0 : box[2];
		o.add("origin", arr(x0, y0, z0));
		o.add("size", box == null ? arr(1, 1, 1) : arr(box[3] - box[0] + 1, box[4] - box[1] + 1, box[5] - box[2] + 1));
		JsonArray pal = new JsonArray();
		for (int i = 0; i < PALETTE.size(); i++) {
			JsonObject p = new JsonObject();
			p.addProperty("s", PALETTE.get(i));
			int[] c = PALETTE_COLOR.get(i);
			p.addProperty("c", c[0]);
			if (c[1] != 0) p.addProperty("f", c[1]);
			if (c[2] != 0) p.addProperty("l", c[2]);
			float[] b = PALETTE_BOX.get(i);
			if (!(b[0] == 0 && b[1] == 0 && b[2] == 0 && b[3] == 1 && b[4] == 1 && b[5] == 1)) {
				JsonArray ba = new JsonArray();
				for (float f : b) ba.add(Math.round(f * 1000) / 1000f);
				p.add("b", ba);
			}
			pal.add(p);
		}
		o.add("palette", pal);
		JsonArray start = new JsonArray();
		for (var e : FIRST.entrySet()) {
			long k = e.getKey();
			start.add(arr(BlockPos.getX(k) - x0, BlockPos.getY(k) - y0, BlockPos.getZ(k) - z0, e.getValue()));
		}
		o.add("start", start);
		JsonArray ev = new JsonArray();
		for (int[] e : EVENTS) ev.add(arr(e[0], e[1] - x0, e[2] - y0, e[3] - z0, e[4]));
		o.add("events", ev);
		o.add("final", fin);
		JsonArray cam = new JsonArray();
		for (float[] c : CAMERA) {
			JsonArray a = new JsonArray();
			a.add((int) c[0]); a.add(Math.round((c[1] - x0) * 10) / 10f); a.add(Math.round((c[2] - y0) * 10) / 10f); a.add(Math.round((c[3] - z0) * 10) / 10f);
			a.add(Math.round(c[4])); a.add(Math.round(c[5]));
			cam.add(a);
		}
		o.add("player", cam);
		JsonObject st = new JsonObject();
		st.addProperty("placed", placed);
		st.addProperty("broken", broken);
		o.add("stats", st);
		return o;
	}

	/* ---------------- version info (names moved between versions: read them by reflection) ---------------- */

	private static Object currentVersion() {
		try { return Class.forName("net.minecraft.SharedConstants").getMethod("getCurrentVersion").invoke(null); } catch (Throwable t) { return null; }
	}

	private static Object call(Object o, String... names) {
		if (o == null) return null;
		for (String n : names) try { return o.getClass().getMethod(n).invoke(o); } catch (Throwable ignored) { }
		return null;
	}

	static String gameVersion() {
		Object n = call(currentVersion(), "name", "getName", "id", "getId");
		return n == null ? "26.3" : n.toString();
	}

	static int dataVersion() {
		Object dv = call(currentVersion(), "dataVersion", "getDataVersion");
		Object v = call(dv, "version", "getVersion");
		if (v instanceof Integer i) return i;
		try {
			var f = Class.forName("net.minecraft.SharedConstants").getField("WORLD_VERSION");
			return f.getInt(null);
		} catch (Throwable ignored) { }
		return 4440;
	}

	/* ---------------- watching for changes ---------------- */

	/** You right clicked a block: the block (and the one in front of it) may change in the next few ticks. */
	public static void onUse(BlockPos pos, Direction face) {
		if (!recording || paused || preparing) return;
		watch(pos, 8);
		BlockPos front = pos.relative(face);
		watch(front, 8);
		for (Direction d : Direction.values()) watch(front.relative(d), 6); // doors, beds, tall plants are 2 blocks
	}

	/** You used an item (buckets...): watch what you're looking at. */
	public static void onUseItem() {
		if (!recording || paused || preparing) return;
		HitResult hr = Minecraft.getInstance().hitResult;
		if (hr instanceof BlockHitResult b && hr.getType() == HitResult.Type.BLOCK) onUse(b.getBlockPos(), b.getDirection());
	}

	/** You broke a block. */
	public static void onBreak(BlockPos pos, BlockState before) {
		if (!recording || paused || preparing) return;
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null) return;
		long k = pos.asLong();
		if (mode == Mode.FOLLOW && !KNOWN.containsKey(k)) KNOWN.put(k, before.isAir() ? AIR_ID : id(before, mc.level, pos));
		watch(pos, 8);
		watch(pos.above(), 6);
		watch(pos.below(), 6);
	}

	private static void watch(BlockPos pos, int ticks) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null) return;
		long k = pos.asLong();
		if (mode == Mode.AREA && areaIndex(pos.getX(), pos.getY(), pos.getZ()) < 0) return;
		// remember how it looked BEFORE anything happens
		if (mode == Mode.FOLLOW && !KNOWN.containsKey(k)) KNOWN.put(k, read(mc.level, pos));
		CHECK.merge(k, ticks, Math::max);
	}

	private static int areaIndex(int x, int y, int z) {
		int lx = x - ax, ly = y - ay, lz = z - az;
		if (lx < 0 || ly < 0 || lz < 0 || lx >= sx || ly >= sy || lz >= sz) return -1;
		return (ly * sz + lz) * sx + lx;
	}

	private static void changed(long k, int x, int y, int z, int before, int now) {
		if (before == now) return;
		if (EVENTS.size() >= MAX_EVENTS) return;
		FIRST.putIfAbsent(k, before);
		EVENTS.add(new int[]{(int) elapsed(), x, y, z, now});
		if (now == AIR_ID) broken++;
		else if (before == AIR_ID || (PALETTE_COLOR.get(before)[1] & 10) != 0) placed++;
	}

	private static int ticks = 0;

	public static void tick(Minecraft mc) {
		if (!recording || mc.level == null || mc.player == null) return;
		// changed dimension: pause automatically, carry on when you're back
		boolean here = dim.equals(Compat.dimensionId());
		if (!here && !paused) { pause(true); Notifications.push("Recording paused", "You left the dimension you're building in.", 0xFFD36B); }
		if (paused) return;
		ticks++;
		BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
		Level level = mc.level;

		if (mode == Mode.AREA && preparing) {
			int n = 0;
			while (scanCursor < area.length && n < SCAN_PER_TICK * 2) {
				int i = scanCursor++;
				int x = i % sx, z = (i / sx) % sz, y = i / (sx * sz);
				m.set(ax + x, ay + y, az + z);
				if (level.isLoaded(m)) { area[i] = read(level, m); prepared++; }
				n++;
			}
			if (scanCursor >= area.length) {
				preparing = false; scanCursor = 0;
				startedAt = System.currentTimeMillis(); pausedTotal = 0;
				Notifications.push("Recording the area", "Every change in the box is recorded. /kyoto build stop when you're done.", 0xFF5D6C, 5000, null, "●");
			}
			return;
		}

		// 1) the blocks you just clicked
		for (Iterator<Map.Entry<Long, Integer>> it = CHECK.entrySet().iterator(); it.hasNext(); ) {
			Map.Entry<Long, Integer> e = it.next();
			long k = e.getKey();
			m.set(k);
			if (level.isLoaded(m)) {
				int now = read(level, m);
				if (mode == Mode.AREA) {
					int i = areaIndex(m.getX(), m.getY(), m.getZ());
					if (i >= 0) { int before = area[i]; if (before >= 0 && before != now) changed(k, m.getX(), m.getY(), m.getZ(), before, now); area[i] = now; }
				} else {
					int before = KNOWN.getOrDefault(k, now);
					if (before != now) { changed(k, m.getX(), m.getY(), m.getZ(), before, now); KNOWN.put(k, now); }
				}
			}
			if (e.getValue() <= 1) it.remove(); else e.setValue(e.getValue() - 1);
		}

		// 2) follow mode: every second, re-check what you built nearby (falling sand, doors, fire, water...)
		if (mode == Mode.FOLLOW && ticks % 20 == 0) {
			BlockPos me = mc.player.blockPosition();
			for (Map.Entry<Long, Integer> e : KNOWN.entrySet()) {
				long k = e.getKey();
				if (!FIRST.containsKey(k)) continue;
				m.set(k);
				if (m.distManhattan(me) > 48 || !level.isLoaded(m)) continue;
				int now = read(level, m);
				if (now != e.getValue()) { changed(k, m.getX(), m.getY(), m.getZ(), e.getValue(), now); e.setValue(now); }
			}
		}

		// 3) area mode: sweep the whole box a slice per tick
		if (mode == Mode.AREA) {
			int n = 0;
			while (n < SCAN_PER_TICK) {
				int i = scanCursor;
				scanCursor = (scanCursor + 1) % area.length;
				int x = i % sx, z = (i / sx) % sz, y = i / (sx * sz);
				m.set(ax + x, ay + y, az + z);
				if (level.isLoaded(m)) {
					int now = read(level, m), before = area[i];
					if (before < 0) area[i] = now; // chunk just loaded: that's how it is, not a change
					else if (before != now) { changed(m.asLong(), m.getX(), m.getY(), m.getZ(), before, now); area[i] = now; }
				}
				n++;
				if (n >= area.length) break;
			}
		}

		// 4) where you are, twice a second (Kyoto Studio can show a little you building)
		if (ticks % 10 == 0) {
			var p = mc.player;
			CAMERA.add(new float[]{elapsed(), (float) p.getX(), (float) p.getY(), (float) p.getZ(), p.getYRot(), p.getXRot()});
		}
	}

	/* ---------------- corners ---------------- */

	/** The block you're looking at, or the one you're standing on. */
	public static BlockPos target() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.hitResult instanceof BlockHitResult b && mc.hitResult.getType() == HitResult.Type.BLOCK) return b.getBlockPos();
		return mc.player == null ? null : mc.player.blockPosition().below();
	}

	public static String areaSize() {
		if (corner1 == null || corner2 == null) return "";
		int x = Math.abs(corner1.getX() - corner2.getX()) + 1, y = Math.abs(corner1.getY() - corner2.getY()) + 1, z = Math.abs(corner1.getZ() - corner2.getZ()) + 1;
		return x + " x " + y + " x " + z + " = " + ((long) x * y * z) + " blocks";
	}

	/* ---------------- drawing ---------------- */

	/** The red REC pill in the top left. */
	public static void renderHud(GuiGraphicsExtractor g, int w, int h) {
		if (!recording) return;
		long t = elapsed() / 1000;
		String time = String.format("%02d:%02d", t / 60, t % 60);
		String label = preparing ? "GETTING READY " + (area == null ? 0 : (int) (100L * scanCursor / Math.max(1, area.length))) + "%"
				: paused ? "PAUSED  " + time : "REC  " + time;
		String sub = preparing ? name : name + "  ·  " + placed + " placed  ·  " + broken + " broken";
		int lw = Math.max(Ui.widthBold(label), Ui.widthSmall(sub)) + 26;
		int x = w / 2 - lw / 2, y = h - 74;
		Ui.glass(g, x, y, x + lw, y + 22, 7, 0.95f);
		float pulse = paused || preparing ? 1f : 0.55f + 0.45f * (float) Math.abs(Math.sin(System.currentTimeMillis() / 400.0));
		int dot = paused ? 0xFFD36B : preparing ? 0x7FB8FF : 0xFF4D5E;
		Ui.round(g, x + 7, y + 7, x + 15, y + 15, 4, Anim.argb(dot, pulse));
		Ui.bold(g, label, x + 20, y + 3, Anim.argb(0xFFFFFF, 1f));
		Ui.small(g, sub, x + 20, y + 13, Anim.argb(Ui.MUTED, 1f));
	}

	private static final Identifier WHITE = Identifier.fromNamespaceAndPath("kyoto", "textures/misc/white.png");

	/** Glowing outline of the area box (while you're setting it up or recording it). */
	public static void renderWorld(PoseStack pose, Vec3 cam) throws Exception {
		BlockPos c1 = corner1, c2 = corner2;
		if (c1 == null && c2 == null) return;
		if (recording && mode == Mode.FOLLOW) return;
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null || (!recording && !(Compat.currentScreen() instanceof dev.kyoto.screen.BuildRecorderScreen) && !showBox)) return;
		if (c1 == null) c1 = c2;
		if (c2 == null) c2 = c1;
		double x0 = Math.min(c1.getX(), c2.getX()) - cam.x - .002, y0 = Math.min(c1.getY(), c2.getY()) - cam.y - .002, z0 = Math.min(c1.getZ(), c2.getZ()) - cam.z - .002;
		double x1 = Math.max(c1.getX(), c2.getX()) + 1 - cam.x + .002, y1 = Math.max(c1.getY(), c2.getY()) + 1 - cam.y + .002, z1 = Math.max(c1.getZ(), c2.getZ()) + 1 - cam.z + .002;
		Object type = WorldScreens.renderType(WHITE);
		if (type == null) return;
		VertexConsumer vc = WorldScreens.vertexBuffer(type);
		Matrix4f mat = pose.last().pose();
		int col = recording ? 0xFF4D5E : 0x7FD8FF;
		double[][] p = {{x0, y0, z0}, {x1, y0, z0}, {x1, y0, z1}, {x0, y0, z1}, {x0, y1, z0}, {x1, y1, z0}, {x1, y1, z1}, {x0, y1, z1}};
		int[][] edges = {{0, 1}, {1, 2}, {2, 3}, {3, 0}, {4, 5}, {5, 6}, {6, 7}, {7, 4}, {0, 4}, {1, 5}, {2, 6}, {3, 7}};
		for (int[] e : edges) beam(vc, mat, p[e[0]], p[e[1]], col);
		// the corners you picked glow brighter
		WorldScreens.flushBuffers();
	}

	public static boolean showBox = false;

	private static void beam(VertexConsumer vc, Matrix4f m, double[] a, double[] b, int col) {
		double dx = b[0] - a[0], dy = b[1] - a[1], dz = b[2] - a[2];
		double mx = (a[0] + b[0]) / 2, my = (a[1] + b[1]) / 2, mz = (a[2] + b[2]) / 2;
		// side vector = line x (towards camera), so the beam always faces you
		double cx = -mx, cy = -my, cz = -mz;
		double sxv = dy * cz - dz * cy, syv = dz * cx - dx * cz, szv = dx * cy - dy * cx;
		double len = Math.sqrt(sxv * sxv + syv * syv + szv * szv);
		if (len < 1e-6) return;
		double dist = Math.sqrt(mx * mx + my * my + mz * mz);
		double wdt = Math.max(0.03, dist * 0.004);
		sxv = sxv / len * wdt; syv = syv / len * wdt; szv = szv / len * wdt;
		int r = col >> 16 & 255, g = col >> 8 & 255, bl = col & 255;
		vc.addVertex(m, (float) (a[0] - sxv), (float) (a[1] - syv), (float) (a[2] - szv)).setColor(r, g, bl, 230).setUv(0, 0).setOverlay(WorldScreens.OVERLAY).setLight(WorldScreens.LIGHT).setNormal(0, 1, 0);
		vc.addVertex(m, (float) (b[0] - sxv), (float) (b[1] - syv), (float) (b[2] - szv)).setColor(r, g, bl, 230).setUv(1, 0).setOverlay(WorldScreens.OVERLAY).setLight(WorldScreens.LIGHT).setNormal(0, 1, 0);
		vc.addVertex(m, (float) (b[0] + sxv), (float) (b[1] + syv), (float) (b[2] + szv)).setColor(r, g, bl, 230).setUv(1, 1).setOverlay(WorldScreens.OVERLAY).setLight(WorldScreens.LIGHT).setNormal(0, 1, 0);
		vc.addVertex(m, (float) (a[0] + sxv), (float) (a[1] + syv), (float) (a[2] + szv)).setColor(r, g, bl, 230).setUv(0, 1).setOverlay(WorldScreens.OVERLAY).setLight(WorldScreens.LIGHT).setNormal(0, 1, 0);
		// and the other side, so it never disappears from behind
		vc.addVertex(m, (float) (a[0] + sxv), (float) (a[1] + syv), (float) (a[2] + szv)).setColor(r, g, bl, 230).setUv(0, 1).setOverlay(WorldScreens.OVERLAY).setLight(WorldScreens.LIGHT).setNormal(0, 1, 0);
		vc.addVertex(m, (float) (b[0] + sxv), (float) (b[1] + syv), (float) (b[2] + szv)).setColor(r, g, bl, 230).setUv(1, 1).setOverlay(WorldScreens.OVERLAY).setLight(WorldScreens.LIGHT).setNormal(0, 1, 0);
		vc.addVertex(m, (float) (b[0] - sxv), (float) (b[1] - syv), (float) (b[2] - szv)).setColor(r, g, bl, 230).setUv(1, 0).setOverlay(WorldScreens.OVERLAY).setLight(WorldScreens.LIGHT).setNormal(0, 1, 0);
		vc.addVertex(m, (float) (a[0] - sxv), (float) (a[1] - syv), (float) (a[2] - szv)).setColor(r, g, bl, 230).setUv(0, 0).setOverlay(WorldScreens.OVERLAY).setLight(WorldScreens.LIGHT).setNormal(0, 1, 0);
	}
}
