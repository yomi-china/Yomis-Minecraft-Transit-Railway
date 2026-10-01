package mtr.webdashboard;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import mtr.data.RailwayData;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Persisted settings for the web dashboard HTTP service.
 * <p>
 * Stored as JSON so that later stages can add fields without breaking files written by older
 * builds: every field is read with a default, so a missing or unknown key is harmless. This is the
 * same reasoning that makes {@code mtr.servlet.Webserver}'s plain-text
 * {@code mtr_webserver_port.txt} tolerant.
 * <p>
 * Unknown fields in the file are ignored on load and dropped on the next save, which keeps the file
 * tidy rather than letting stale keys accumulate.
 * <p>
 * <b>This class is a storage layer only.</b> The policy decision of who may edit lives in
 * {@link WebDashboardPermissions}; this class just holds the explicit grant table and the two
 * timeouts and knows how to read and write them.
 * <p>
 * Everything is synchronized because the instance is shared by the game thread and by Jetty
 * request threads.
 */
public final class WebDashboardSettings {

	public static final int DEFAULT_PORT = 8890;
	public static final boolean DEFAULT_ALLOW_LAN_ACCESS = false;
	/** Long enough to survive a browser launch, short enough that a leaked URL goes stale quickly. */
	public static final int DEFAULT_LOGIN_TOKEN_TTL_SECONDS = 120;
	/** Zero means sessions do not expire on a timer; they end when the server stops or access is revoked. */
	public static final int DEFAULT_SESSION_TTL_HOURS = 0;

	private static final String FILE_NAME = "mtr_web_dashboard.json";
	private static final String KEY_PORT = "port";
	private static final String KEY_ALLOW_LAN_ACCESS = "allowLanAccess";
	private static final String KEY_LOGIN_TOKEN_TTL_SECONDS = "loginTokenTtlSeconds";
	private static final String KEY_SESSION_TTL_HOURS = "sessionTtlHours";
	private static final String KEY_PERMISSIONS = "permissions";
	private static final String KEY_GRANTED = "granted";
	private static final String KEY_NAME = "name";

	private static final Gson GSON = new Gson();

	private static Path configDirectory = null;
	private static WebDashboardSettings instance = new WebDashboardSettings();

	private int port = DEFAULT_PORT;
	private boolean allowLanAccess = DEFAULT_ALLOW_LAN_ACCESS;
	private int loginTokenTtlSeconds = DEFAULT_LOGIN_TOKEN_TTL_SECONDS;
	private int sessionTtlHours = DEFAULT_SESSION_TTL_HOURS;

	/**
	 * Explicit grants and denials, keyed by player UUID. An entry present here overrides the
	 * op-level rule in {@link WebDashboardPermissions}; the absence of an entry falls through to it.
	 * <p>
	 * Insertion ordered so the JSON file reads in the order entries were added rather than in
	 * UUID-hash order.
	 */
	private final Map<UUID, PermissionEntry> permissions = new LinkedHashMap<>();

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
			loaded.saveNow(path);
		}

		instance = loaded;
		System.out.println("[MTR-WebDashboard] Settings loaded: port=" + loaded.port + ", allowLanAccess=" + loaded.allowLanAccess
				+ ", loginTokenTtlSeconds=" + loaded.loginTokenTtlSeconds + ", sessionTtlHours=" + loaded.sessionTtlHours
				+ ", permissionEntries=" + loaded.permissions.size());
		return loaded;
	}

	/**
	 * @return the most recently loaded settings, or defaults when {@link #load(Path)} has not run yet.
	 */
	public static synchronized WebDashboardSettings get() {
		return instance;
	}

	/**
	 * Writes the current values back to the file loaded by {@link #load(Path)}.
	 * <p>
	 * Called after a permission change. Doing small synchronous I/O here is deliberate: the
	 * alternative is a background writer, and a lost grant is much worse than a few milliseconds on
	 * the game thread when an operator runs a command.
	 *
	 * @return true when the file was written.
	 */
	public synchronized boolean save() {
		if (configDirectory == null) {
			System.out.println("[MTR-WebDashboard] Cannot save settings: no config directory is known yet.");
			return false;
		}
		return saveNow(configDirectory.resolve(FILE_NAME));
	}

	// ---- service settings -------------------------------------------------

	public synchronized void setPort(int rawPort) {
		if (rawPort < 1025 || rawPort > 65535) {
			System.out.println("[MTR-WebDashboard] Port " + rawPort + " is out of range, using " + DEFAULT_PORT);
			port = DEFAULT_PORT;
		} else {
			port = rawPort;
		}
	}

	public synchronized int getPort() {
		return port;
	}

	public synchronized void setAllowLanAccess(boolean allowLanAccess) {
		this.allowLanAccess = allowLanAccess;
	}

	public synchronized boolean isAllowLanAccess() {
		return allowLanAccess;
	}

	/**
	 * @return the address the HTTP service binds to. Loopback by default; binding every interface is
	 *         opt-in, and over plain HTTP that means sign-in tokens and session cookies are readable
	 *         by anything on the same network segment.
	 */
	public synchronized String getBindHost() {
		return allowLanAccess ? "0.0.0.0" : "127.0.0.1";
	}

	/**
	 * @return the address to hand to a browser. Always loopback: an in-game click must reach the
	 *         service even when it is bound to every interface.
	 */
	public synchronized String getClientHost() {
		return "127.0.0.1";
	}

	// ---- token lifetimes --------------------------------------------------

	public synchronized void setLoginTokenTtlSeconds(int seconds) {
		if (seconds < 10 || seconds > 3600) {
			System.out.println("[MTR-WebDashboard] loginTokenTtlSeconds " + seconds + " is out of range, using " + DEFAULT_LOGIN_TOKEN_TTL_SECONDS);
			loginTokenTtlSeconds = DEFAULT_LOGIN_TOKEN_TTL_SECONDS;
		} else {
			loginTokenTtlSeconds = seconds;
		}
	}

	public synchronized int getLoginTokenTtlSeconds() {
		return loginTokenTtlSeconds;
	}

	public synchronized void setSessionTtlHours(int hours) {
		sessionTtlHours = Math.max(0, hours);
	}

	/**
	 * @return the session lifetime in hours, or 0 for "no timer".
	 */
	public synchronized int getSessionTtlHours() {
		return sessionTtlHours;
	}

	// ---- permission table -------------------------------------------------

	public synchronized boolean hasPermissionEntry(UUID uuid) {
		return uuid != null && permissions.containsKey(uuid);
	}

	/**
	 * @return the explicit value for this player, or null when there is no entry and the op-level
	 *         rule should decide.
	 */
	public synchronized Boolean getPermissionEntry(UUID uuid) {
		final PermissionEntry entry = uuid == null ? null : permissions.get(uuid);
		return entry == null ? null : entry.granted;
	}

	/**
	 * @return the name recorded with the entry, or null. Used to show something human readable for a
	 *         player who is currently offline.
	 */
	public synchronized String getPermissionName(UUID uuid) {
		final PermissionEntry entry = uuid == null ? null : permissions.get(uuid);
		return entry == null ? null : entry.name;
	}

	/**
	 * Creates or replaces an explicit entry. Does not write the file; callers go through
	 * {@link WebDashboardPermissions}, which saves.
	 */
	public synchronized void setPermissionEntry(UUID uuid, boolean granted, String name) {
		if (uuid == null) {
			return;
		}
		final PermissionEntry existing = permissions.get(uuid);
		// Keep the previous name when the caller has none, so a later name-less update does not erase it.
		final String resolvedName = name == null || name.isEmpty() ? existing == null ? "" : existing.name : name;
		permissions.put(uuid, new PermissionEntry(granted, resolvedName));
	}

	/**
	 * Updates the recorded name without touching the grant, for refreshing a stale name when the
	 * player joins.
	 *
	 * @return true when something actually changed.
	 */
	public synchronized boolean updatePermissionName(UUID uuid, String name) {
		if (uuid == null || name == null || name.isEmpty()) {
			return false;
		}
		final PermissionEntry existing = permissions.get(uuid);
		if (existing == null || name.equals(existing.name)) {
			return false;
		}
		permissions.put(uuid, new PermissionEntry(existing.granted, name));
		return true;
	}

	/**
	 * Removes an explicit entry so the player falls back to the op-level rule.
	 *
	 * @return true when an entry was actually removed.
	 */
	public synchronized boolean removePermissionEntry(UUID uuid) {
		return uuid != null && permissions.remove(uuid) != null;
	}

	/**
	 * @return an immutable snapshot, ordered by name then UUID so command output is stable.
	 */
	public synchronized List<PermissionEntryView> listPermissionEntries() {
		final List<PermissionEntryView> views = new ArrayList<>(permissions.size());
		permissions.forEach((uuid, entry) -> views.add(new PermissionEntryView(uuid, entry.granted, entry.name)));
		views.sort((a, b) -> {
			final String nameA = a.name == null ? "" : a.name.toLowerCase(Locale.ENGLISH);
			final String nameB = b.name == null ? "" : b.name.toLowerCase(Locale.ENGLISH);
			final int byName = nameA.compareTo(nameB);
			return byName != 0 ? byName : a.uuid.compareTo(b.uuid);
		});
		return Collections.unmodifiableList(views);
	}

	public synchronized int getPermissionEntryCount() {
		return permissions.size();
	}

	// ---- serialisation ----------------------------------------------------

	private void readFrom(JsonObject object) {
		if (isNumber(object, KEY_PORT)) {
			setPort(object.get(KEY_PORT).getAsInt());
		}
		if (isBoolean(object, KEY_ALLOW_LAN_ACCESS)) {
			allowLanAccess = object.get(KEY_ALLOW_LAN_ACCESS).getAsBoolean();
		}
		if (isNumber(object, KEY_LOGIN_TOKEN_TTL_SECONDS)) {
			setLoginTokenTtlSeconds(object.get(KEY_LOGIN_TOKEN_TTL_SECONDS).getAsInt());
		}
		if (isNumber(object, KEY_SESSION_TTL_HOURS)) {
			setSessionTtlHours(object.get(KEY_SESSION_TTL_HOURS).getAsInt());
		}
		readPermissions(object);
	}

	/**
	 * Hand-edited files are expected, so a malformed entry is skipped with a log line rather than
	 * failing the whole load - losing every other grant because of one bad UUID would be far worse.
	 */
	private void readPermissions(JsonObject object) {
		if (!object.has(KEY_PERMISSIONS) || !object.get(KEY_PERMISSIONS).isJsonObject()) {
			return;
		}

		for (final Map.Entry<String, JsonElement> entry : object.getAsJsonObject(KEY_PERMISSIONS).entrySet()) {
			try {
				final UUID uuid = UUID.fromString(entry.getKey());
				if (!entry.getValue().isJsonObject()) {
					System.out.println("[MTR-WebDashboard] Skipping permission entry " + entry.getKey() + ": not a JSON object");
					continue;
				}
				final JsonObject value = entry.getValue().getAsJsonObject();
				if (!isBoolean(value, KEY_GRANTED)) {
					System.out.println("[MTR-WebDashboard] Skipping permission entry " + entry.getKey() + ": no boolean \"" + KEY_GRANTED + "\"");
					continue;
				}
				final String name = value.has(KEY_NAME) && value.get(KEY_NAME).isJsonPrimitive() ? value.get(KEY_NAME).getAsString() : "";
				permissions.put(uuid, new PermissionEntry(value.get(KEY_GRANTED).getAsBoolean(), name));
			} catch (Exception e) {
				System.out.println("[MTR-WebDashboard] Skipping malformed permission entry \"" + entry.getKey() + "\": " + e);
			}
		}
	}

	/**
	 * Written through {@link RailwayData#prettyPrint} so the file stays hand-editable, matching the
	 * project's existing JSON output.
	 */
	private boolean saveNow(Path path) {
		try {
			final Path parent = path.getParent();
			if (parent != null) {
				Files.createDirectories(parent);
			}

			final JsonObject permissionObject = new JsonObject();
			permissions.forEach((uuid, entry) -> {
				final JsonObject value = new JsonObject();
				value.addProperty(KEY_GRANTED, entry.granted);
				value.addProperty(KEY_NAME, entry.name == null ? "" : entry.name);
				permissionObject.add(uuid.toString(), value);
			});

			final JsonObject object = new JsonObject();
			object.addProperty(KEY_PORT, port);
			object.addProperty(KEY_ALLOW_LAN_ACCESS, allowLanAccess);
			object.addProperty(KEY_LOGIN_TOKEN_TTL_SECONDS, loginTokenTtlSeconds);
			object.addProperty(KEY_SESSION_TTL_HOURS, sessionTtlHours);
			object.add(KEY_PERMISSIONS, permissionObject);

			Files.write(path, RailwayData.prettyPrint(object).getBytes(StandardCharsets.UTF_8));
			return true;
		} catch (Exception e) {
			System.out.println("[MTR-WebDashboard] Could not write " + path + ": " + e);
			return false;
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

	/**
	 * Stored form of one permission entry. Mutable state lives in the enclosing settings object, so
	 * this is a plain carrier.
	 */
	private static final class PermissionEntry {

		private final boolean granted;
		private final String name;

		private PermissionEntry(boolean granted, String name) {
			this.granted = granted;
			this.name = name;
		}
	}

	/**
	 * Read-only view of one entry, so callers outside this package can list permissions without
	 * holding a lock or being able to mutate the map.
	 */
	public static final class PermissionEntryView {

		public final UUID uuid;
		public final boolean granted;
		public final String name;

		private PermissionEntryView(UUID uuid, boolean granted, String name) {
			this.uuid = uuid;
			this.granted = granted;
			this.name = name;
		}

		/**
		 * @return the recorded name, or a shortened UUID when the player was never seen online.
		 */
		public String getDisplayName() {
			return name == null || name.isEmpty() ? uuid.toString().substring(0, 8) : name;
		}
	}
}
