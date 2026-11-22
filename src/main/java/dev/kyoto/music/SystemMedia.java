package dev.kyoto.music;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;

/**
 * FREE music source - no account or API key needed.
 *  - Windows: reads the "now playing" info Windows itself shows (the media overlay when you press a volume key).
 *    Works with Spotify Free, YouTube / YouTube Music in a browser, Apple Music, Amazon Music, Tidal, VLC...
 *  - macOS: asks the Spotify or Music app directly (AppleScript).
 */
public final class SystemMedia {
	private SystemMedia() {}

	private static final Logger LOG = LoggerFactory.getLogger("kyoto-media");
	public static volatile String status = "Off";
	private static Thread thread;
	private static Process proc;

	private static final String WINDOWS_SCRIPT = String.join("\n",
			"$ErrorActionPreference = 'SilentlyContinue'",
			"Add-Type -AssemblyName System.Runtime.WindowsRuntime",
			"$asTask = ([System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object { $_.Name -eq 'AsTask' -and $_.GetParameters().Count -eq 1 -and $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1' })[0]",
			"function Await($op, $type) { $t = $asTask.MakeGenericMethod($type).Invoke($null, @($op)); $t.Wait(-1) | Out-Null; $t.Result }",
			"[Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager,Windows.Media.Control,ContentType=WindowsRuntime] | Out-Null",
			"$mgr = Await ([Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager]::RequestAsync()) ([Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager])",
			"while ($true) {",
			"  $s = $mgr.GetCurrentSession()",
			"  if ($s -eq $null) { Write-Output '{}' } else {",
			"    $p = Await ($s.TryGetMediaPropertiesAsync()) ([Windows.Media.Control.GlobalSystemMediaTransportControlsSessionMediaProperties])",
			"    $tl = $s.GetTimelineProperties(); $pb = $s.GetPlaybackInfo()",
			"    $o = @{ title = $p.Title; artist = $p.Artist; album = $p.AlbumTitle; pos = [long]$tl.Position.TotalMilliseconds; dur = [long]$tl.EndTime.TotalMilliseconds; status = \"$($pb.PlaybackStatus)\"; updated = [long]$tl.LastUpdatedTime.ToUnixTimeMilliseconds(); app = $s.SourceAppUserModelId }",
			"    Write-Output ($o | ConvertTo-Json -Compress)",
			"  }",
			"  [Console]::Out.Flush()",
			"  Start-Sleep -Milliseconds 1000",
			"}");

	private static final String MAC_SCRIPT = String.join("\n",
			"if application \"Spotify\" is running then",
			"  tell application \"Spotify\"",
			"    return \"Spotify|\" & (name of current track) & \"|\" & (artist of current track) & \"|\" & (album of current track) & \"|\" & (player position) & \"|\" & ((duration of current track) / 1000) & \"|\" & (player state as string)",
			"  end tell",
			"else if application \"Music\" is running then",
			"  tell application \"Music\"",
			"    return \"Music|\" & (name of current track) & \"|\" & (artist of current track) & \"|\" & (album of current track) & \"|\" & (player position) & \"|\" & (duration of current track) & \"|\" & (player state as string)",
			"  end tell",
			"end if",
			"return \"\"");

	public static void start() {
		if (thread != null) return;
		String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
		thread = new Thread(() -> {
			if (os.contains("win")) runWindows();
			else if (os.contains("mac")) runMac();
			else status = "System media isn't supported on Linux yet";
		}, "Kyoto-SystemMedia");
		thread.setDaemon(true);
		thread.start();
	}

	private static boolean enabled() {
		int w = Music.wanted();
		return w == 2 || (w == 0 && !Spotify.connected());
	}

	private static void runWindows() {
		while (true) {
			try {
				if (!enabled()) {
					status = "Off";
					Thread.sleep(3000);
					continue;
				}
				String encoded = Base64.getEncoder().encodeToString(WINDOWS_SCRIPT.getBytes(StandardCharsets.UTF_16LE));
				proc = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-EncodedCommand", encoded)
						.redirectErrorStream(true).start();
				status = "Listening to Windows media";
				try (BufferedReader r = new BufferedReader(new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
					String line;
					while ((line = r.readLine()) != null) {
						if (!enabled()) break;
						line = line.trim();
						if (!line.startsWith("{")) continue;
						handleWindows(line);
					}
				}
				proc.destroy();
				Thread.sleep(3000);
			} catch (Exception e) {
				status = "Windows media error: " + e.getMessage();
				LOG.debug("system media", e);
				try {
					Thread.sleep(10000);
				} catch (InterruptedException ignored) {
				}
			}
		}
	}

	private static void handleWindows(String json) {
		JsonObject o = JsonParser.parseString(json).getAsJsonObject();
		if (!o.has("title") || o.get("title").isJsonNull() || o.get("title").getAsString().isBlank()) {
			Music.stopped("System");
			return;
		}
		String title = o.get("title").getAsString();
		String artist = o.has("artist") && !o.get("artist").isJsonNull() ? o.get("artist").getAsString() : "";
		String album = o.has("album") && !o.get("album").isJsonNull() ? o.get("album").getAsString() : "";
		long dur = o.get("dur").getAsLong();
		long pos = o.get("pos").getAsLong();
		long updated = o.get("updated").getAsLong();
		boolean playing = "Playing".equals(o.get("status").getAsString());
		// Windows reports where the song was at "updated" time - move it forward to now
		boolean exact = updated > 0 && dur > 0;
		if (exact && playing) pos += Math.max(0, System.currentTimeMillis() - updated);
		Music.update("System", "sys:" + artist + "|" + title, title, artist, album, dur, pos, playing, exact);
	}

	private static void runMac() {
		status = "Listening to Spotify/Music app";
		while (true) {
			try {
				if (!enabled()) {
					Thread.sleep(3000);
					continue;
				}
				Process p = new ProcessBuilder("osascript", "-e", MAC_SCRIPT).redirectErrorStream(true).start();
				String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
				p.waitFor();
				String[] parts = out.split("\\|");
				if (parts.length >= 7) {
					long pos = Math.round(Double.parseDouble(parts[4].replace(',', '.')) * 1000);
					long dur = Math.round(Double.parseDouble(parts[5].replace(',', '.')) * 1000);
					boolean playing = parts[6].toLowerCase(Locale.ROOT).contains("playing");
					Music.update("System", "sys:" + parts[2] + "|" + parts[1], parts[1], parts[2], parts[3], dur, pos, playing, true);
				} else {
					Music.stopped("System");
				}
				Thread.sleep(1500);
			} catch (Exception e) {
				status = "Mac media error: " + e.getMessage();
				try {
					Thread.sleep(5000);
				} catch (InterruptedException ignored) {
				}
			}
		}
	}

	/** Play/pause, next or previous on whatever Windows / macOS is playing. */
	public static void control(String action) {
		String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
		Thread t = new Thread(() -> {
			try {
				if (os.contains("win")) {
					String call = switch (action) {
						case "next" -> "TrySkipNextAsync";
						case "previous" -> "TrySkipPreviousAsync";
						default -> "TryTogglePlayPauseAsync";
					};
					String script = String.join("\n",
							WINDOWS_SCRIPT.substring(0, WINDOWS_SCRIPT.indexOf("while ($true)")),
							"$s = $mgr.GetCurrentSession()",
							"if ($s -ne $null) { Await ($s." + call + "()) ([bool]) | Out-Null }");
					String enc = Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE));
					new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-EncodedCommand", enc)
							.redirectErrorStream(true).start().waitFor();
				} else if (os.contains("mac")) {
					String cmd = switch (action) {
						case "next" -> "next track";
						case "previous" -> "previous track";
						default -> "playpause";
					};
					new ProcessBuilder("osascript", "-e", "if application \"Spotify\" is running then tell application \"Spotify\" to " + cmd
							+ "\nif application \"Music\" is running then tell application \"Music\" to " + cmd).start().waitFor();
				}
			} catch (Exception e) {
				LOG.debug("media control failed", e);
			}
		}, "Kyoto-MediaControl");
		t.setDaemon(true);
		t.start();
	}

	public static void stop() {
		if (proc != null) proc.destroy();
	}
}
