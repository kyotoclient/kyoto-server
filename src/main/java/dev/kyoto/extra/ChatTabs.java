package dev.kyoto.extra;

import dev.kyoto.hud.Notifications;
import dev.kyoto.module.Modules;
import dev.kyoto.util.Compat;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Chat tabs (All, DMs, Team, Kyoto), chat search and copy-a-message.
 * Kyoto remembers the last 400 messages; switching tab clears the chat box and puts back only the matching ones.
 * Right-click a message (with chat open) to copy it.
 */
public final class ChatTabs {
	private ChatTabs() {}

	public static final String[] TABS = {"All", "DMs", "Team", "Kyoto"};
	private static final Pattern DM = Pattern.compile("(?i)(whispers? to you|\\bwhispers?\\b|^\\[?from |^\\[?to |-> ?me\\b|me ?->|\\bmsg\\b|\\[dm]|^\\(from|^\\(to)");
	private static final Pattern TEAM = Pattern.compile("(?i)^\\s*(\\[(team|party|guild|clan|faction|ally|t|p|g)]|(team|party|guild|clan) ?[>:»])");

	private record Msg(Component c, String plain, int kind) {}

	private static final ArrayDeque<Msg> HISTORY = new ArrayDeque<>();
	public static int tab = 0;
	private static final String search = "";
	private static boolean replaying = false;
	public static boolean skipNext = false;

	private static int kindOf(String plain, boolean kyoto) {
		if (kyoto || plain.startsWith("[Kyoto]")) return 3;
		if (DM.matcher(plain).find()) return 1;
		if (TEAM.matcher(plain).find()) return 2;
		return 0;
	}

	private static boolean shows(Msg m) {
		if (!search.isEmpty() && !m.plain.toLowerCase(Locale.ROOT).contains(search)) return false;
		return tab == 0 || m.kind == tab;
	}

	/** Every message that reaches the chat box. @return false = hide it (it's not for the open tab). */
	public static boolean record(Component c, boolean kyoto) {
		if (replaying) return true;
		String plain = c.getString();
		Msg m = new Msg(c, plain, kindOf(plain, kyoto));
		HISTORY.addLast(m);
		while (HISTORY.size() > 400) HISTORY.pollFirst();
		return !Modules.on("chat_tabs") || shows(m);
	}

	public static void setTab(int t) {
		tab = t;
		replay();
	}

	private static void replay() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) return;
		Object chat = chat(mc);
		if (chat == null) return;
		try {
			chat.getClass().getMethod("clearMessages", boolean.class).invoke(chat, false);
		} catch (Throwable ignored) {
		}
		replaying = true;
		try {
			for (Msg m : HISTORY) if (shows(m)) Compat.addChat(m.c);
		} finally {
			replaying = false;
		}
	}

	/* ---------------- tab buttons + search in the chat screen ---------------- */

	public static void register() {
		ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
			if (!(screen instanceof ChatScreen) || !Modules.on("chat_tabs")) return;
			int x = w - 4;
			Button[] btns = new Button[TABS.length];
			for (int i = TABS.length - 1; i >= 0; i--) {
				int bw = client.font.width("› " + TABS[i]) + 10;
				x -= bw;
				final int t = i;
				btns[i] = Button.builder(Component.literal((tab == i ? "› " : "") + TABS[i]), btn -> {
					setTab(t);
					for (int k = 0; k < btns.length; k++) btns[k].setMessage(Component.literal((tab == k ? "› " : "") + TABS[k]));
				}).bounds(x, h - 31, bw, 15).build();
				Screens.getWidgets(screen).add(btns[i]);
				x -= 2;
			}
		});
		// the open notch takes clicks on any screen
		ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> ScreenMouseEvents.allowMouseClick(screen).register((s, e) -> !dev.kyoto.hud.Notch.click(e.x(), e.y())));
		ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
			if (!(screen instanceof ChatScreen)) return;
			ScreenMouseEvents.allowMouseClick(screen).register((s, e) -> {
				if (e.button() != 1 || !Modules.on("chat_copy")) return true;
				String text = messageAt(client, e.x(), e.y());
				if (text == null) return true;
				Compat.copy(text);
				Notifications.push("Copied", text.length() > 60 ? text.substring(0, 60) + "..." : text, Notifications.INFO, 1800, null, "check");
				return false;
			});
		});
	}

	/* ---------------- copy a message (reads the chat box's own lines) ---------------- */

	private static Object chat(Minecraft mc) {
		try {
			return mc.gui.getClass().getMethod("getChat").invoke(mc.gui);
		} catch (Throwable ignored) {
		}
		for (Method m : mc.gui.getClass().getMethods())
			if (m.getParameterCount() == 0 && m.getReturnType().getSimpleName().equals("ChatComponent")) try {
				return m.invoke(mc.gui);
			} catch (Throwable ignored) {
			}
		return null;
	}

	private static String messageAt(Minecraft mc, double mx, double my) {
		try {
			Object chat = chat(mc);
			if (chat == null) return null;
			Class<?> cc = chat.getClass();
			Method sx = declared(cc, "screenToChatX", double.class), sy = declared(cc, "screenToChatY", double.class), at = declared(cc, "getMessageLineIndexAt", double.class, double.class);
			if (sx == null || sy == null || at == null) return null;
			double cx = (double) sx.invoke(chat, mx), cy = (double) sy.invoke(chat, my);
			int idx = (int) at.invoke(chat, cx, cy);
			if (idx < 0) return null;
			Field tf = null;
			for (Field f : cc.getDeclaredFields()) if (f.getName().equals("trimmedMessages")) tf = f;
			if (tf == null) return null;
			tf.setAccessible(true);
			List<?> lines = (List<?>) tf.get(chat);
			if (idx >= lines.size()) return null;
			int lo = idx, hi = idx;
			while (lo > 0 && !endOf(lines.get(lo))) lo--;
			while (hi + 1 < lines.size() && !endOf(lines.get(hi + 1))) hi++;
			StringBuilder sb = new StringBuilder();
			for (int i = hi; i >= lo; i--) {
				FormattedCharSequence seq = (FormattedCharSequence) lines.get(i).getClass().getMethod("content").invoke(lines.get(i));
				seq.accept((pos, style, cp) -> { sb.appendCodePoint(cp); return true; });
				if (i > lo) sb.append(' ');
			}
			String t = sb.toString().trim();
			return t.isEmpty() ? null : t;
		} catch (Throwable t) {
			return null;
		}
	}

	private static boolean endOf(Object line) {
		try {
			return (boolean) line.getClass().getMethod("endOfEntry").invoke(line);
		} catch (Throwable t) {
			return true;
		}
	}

	private static Method declared(Class<?> c, String name, Class<?>... p) {
		try {
			Method m = c.getDeclaredMethod(name, p);
			m.setAccessible(true);
			return m;
		} catch (Throwable t) {
			return null;
		}
	}

	public static List<String> recent(int n) {
		List<String> out = new ArrayList<>();
		for (Msg m : HISTORY) out.add(m.plain);
		return out.subList(Math.max(0, out.size() - n), out.size());
	}
}
