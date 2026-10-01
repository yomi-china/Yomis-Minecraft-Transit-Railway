package mtr.webdashboard;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import mtr.data.RailwayData;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Persisted settings for the web dashboard HTTP service.
 * <p>
 * Stored as JSON so that later stages can add fields (a permission list in stage 2, for example)
 * without breaking files written by older builds: every field is read with a default, so a missing
 * or unknown key is harmless. This is the same reasoning that makes
 * {@code mtr.servlet.Webserver}'s plain-text {@code mtr_webserver_port.txt} tolerant.
 * <p>
 * Unknown fields in the file are ignored on load and dropped on the next save, which keeps the file
 * tidy rather than letting stale keys accumulate.
 */
public final class WebDashboardSettings {

	public static final int DEFAULT_PORT = 8890;
	public static final boolean DEFAULT_ALLOW_LAN_ACCESS = false;

	private static final String FILE_NAME = "mtr_web_dashboard.json";
	private static final String KEY_PORT = "port";
	private static final String KEY_ALLOW_LAN_ACCESS = "allowLanAccess";

	private static final Gson GSON = new Gson();

	private static Path configDirectory = null;
	private static WebDashboardSettings instance = new WebDashboardSettings();

	private int port = DEFAULT_PORT;
	private boolean allowLanAccess = DEFAULT_ALLOW_LAN_ACCESS;

	private WebDashboardSettings() {
	}

	/**
	 * Reads the settings from {@code configDirectory/mtr_web_dashboard.json}, creating the file with
	 * defaults when it is absent or unreadable.
	 * <p>
	 * Safe to call more than once with the same directory - the cached instance is reused so that
	 * repeated calls cannot race each other. Each entry into a world therefore costs one read of a
	 * tiny file, and editing the file only takes effect on the next world load (or server restart).
	 *
	 * @param directory the {@code config} directory, i.e. {@code gameDirectory/config} on a client or
	 *                  integrated server and {@code serverDirectory/config} on a dedicated server.
	 * @return the loaded settings, never null.
	 */
	public static synchronized WebDashboardSettings load(Path directory) {
		if (directory == null) {
			return instance;
		}
		if (directory.equals(configDirectory)) {
			return instance;
		}

		configDirectory = directory;
		final WebDashboardSettings loaded = new WebDashboardSettings();
		final Path path = directory.resolve(FILE_NAME);

		boolean rewrite = false;
		try {
			if (Files.isRegularFile(path)) {
				final JsonObject object = GSON.fromJson(new String(Files.readAllBytes(path), StandardCharsets.UTF_8), JsonObject.class);
				if (object == null) {
					// An empty or whitespace-only file parses to null rather than throwing.
					rewrite = true;
				} else {
					loaded.readFrom(object);
				}
			} else {
				rewrite = true;
			}
		} catch (Exception e) {
			System.out.println("[MTR-WebDashboard] Could not read " + path + ", falling back to defaults: " + e);
			rewrite = true;
		}

		if (rewrite) {
			loaded.save(path);
		}

		instance = loaded;
		System.out.println("[MTR-WebDashboard] Settings loaded: port=" + loaded.port + ", allowLanAccess=" + loaded.allowLanAccess);
		return loaded;
	}

	/**
	 * @return the most recently loaded settings, or defaults when {@link #load(Path)} has not run yet.
	 */
	public static synchronized WebDashboardSettings get() {
		return instance;
	}

	/**
	 * @param rawPort the requested port; ignored when it falls outside the range a non-privileged
	 *                process may bind.
	 */
	public void setPort(int rawPort) {
		if (rawPort < 1025 || rawPort > 65535) {
			System.out.println("[MTR-WebDashboard] Port " + rawPort + " is out of range, using " + DEFAULT_PORT);
			port = DEFAULT_PORT;
		} else {
			port = rawPort;
		}
	}

	public int getPort() {
		return port;
	}

	public void setAllowLanAccess(boolean allowLanAccess) {
		this.allowLanAccess = allowLanAccess;
	}

	public boolean isAllowLanAccess() {
		return allowLanAccess;
	}

	/**
	 * @return the address the HTTP service binds to. Loopback by default; binding every interface is
	 * opt-in because the service is unauthenticated until stage 2 and would otherwise expose
	 * server data to the whole network.
	 */
	public String getBindHost() {
		return allowLanAccess ? "0.0.0.0" : "127.0.0.1";
	}

	/**
	 * @return the address to hand to a browser. Always loopback: an in-game click must reach the
	 * service even when it is bound to every interface.
	 */
	public String getClientHost() {
		return "127.0.0.1";
	}

	private void readFrom(JsonObject object) {
		if (isNumber(object, KEY_PORT)) {
			setPort(object.get(KEY_PORT).getAsInt());
		}
		if (isBoolean(object, KEY_ALLOW_LAN_ACCESS)) {
			allowLanAccess = object.get(KEY_ALLOW_LAN_ACCESS).getAsBoolean();
		}
	}

	/**
	 * Written through {@link RailwayData#prettyPrint} so the file stays hand-editable, matching the
	 * project's existing JSON output.
	 */
	private void save(Path path) {
		try {
			final Path parent = path.getParent();
			if (parent != null) {
				Files.createDirectories(parent);
			}
			final JsonObject object = new JsonObject();
			object.addProperty(KEY_PORT, port);
			object.addProperty(KEY_ALLOW_LAN_ACCESS, allowLanAccess);
			Files.write(path, RailwayData.prettyPrint(object).getBytes(StandardCharsets.UTF_8));
		} catch (Exception e) {
			System.out.println("[MTR-WebDashboard] Could not write " + path + ": " + e);
		}
	}

	/**
	 * Guards against a config file whose values have the wrong JSON type - {@code "port": "abc"}
	 * would otherwise throw from {@code getAsInt()}.
	 */
	private static boolean isNumber(JsonObject object, String key) {
		return object.has(key) && object.get(key).isJsonPrimitive() && object.getAsJsonPrimitive(key).isNumber();
	}

	private static boolean isBoolean(JsonObject object, String key) {
		return object.has(key) && object.get(key).isJsonPrimitive() && object.getAsJsonPrimitive(key).isBoolean();
	}
}
