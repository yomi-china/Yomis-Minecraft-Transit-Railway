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

public final class WebDashboardSettings {

	public static final int DEFAULT_PORT = 8890;
	public static final boolean DEFAULT_ALLOW_LAN_ACCESS = false;
	public static final int DEFAULT_LOGIN_TOKEN_TTL_SECONDS = 120;
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

	private final Map<UUID, PermissionEntry> permissions = new LinkedHashMap<>();

	private WebDashboardSettings() {
	}

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

	public static synchronized WebDashboardSettings get() {
		return instance;
	}

	public synchronized boolean save() {
		if (configDirectory == null) {
			System.out.println("[MTR-WebDashboard] Cannot save settings: no config directory is known yet.");
			return false;
		}
		return saveNow(configDirectory.resolve(FILE_NAME));
	}

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

	public synchronized String getBindHost() {
		return allowLanAccess ? "0.0.0.0" : "127.0.0.1";
	}

	public synchronized String getClientHost() {
		return "127.0.0.1";
	}

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

	public synchronized int getSessionTtlHours() {
		return sessionTtlHours;
	}

	public synchronized boolean hasPermissionEntry(UUID uuid) {
		return uuid != null && permissions.containsKey(uuid);
	}

	public synchronized Boolean getPermissionEntry(UUID uuid) {
		final PermissionEntry entry = uuid == null ? null : permissions.get(uuid);
		return entry == null ? null : entry.granted();
	}

	public synchronized String getPermissionName(UUID uuid) {
		final PermissionEntry entry = uuid == null ? null : permissions.get(uuid);
		return entry == null ? null : entry.name();
	}

	public synchronized void setPermissionEntry(UUID uuid, boolean granted, String name) {
		if (uuid == null) {
			return;
		}
		final PermissionEntry existing = permissions.get(uuid);
		final String resolvedName = name == null || name.isEmpty() ? existing == null ? "" : existing.name() : name;
		permissions.put(uuid, new PermissionEntry(granted, resolvedName));
	}

	public synchronized boolean updatePermissionName(UUID uuid, String name) {
		if (uuid == null || name == null || name.isEmpty()) {
			return false;
		}
		final PermissionEntry existing = permissions.get(uuid);
		if (existing == null || name.equals(existing.name())) {
			return false;
		}
		permissions.put(uuid, new PermissionEntry(existing.granted(), name));
		return true;
	}

	public synchronized boolean removePermissionEntry(UUID uuid) {
		return uuid != null && permissions.remove(uuid) != null;
	}

	public synchronized List<PermissionEntryView> listPermissionEntries() {
		final List<PermissionEntryView> views = new ArrayList<>(permissions.size());
		permissions.forEach((uuid, entry) -> views.add(new PermissionEntryView(uuid, entry.granted(), entry.name())));
		views.sort((a, b) -> {
			final String nameA = a.name() == null ? "" : a.name().toLowerCase(Locale.ENGLISH);
			final String nameB = b.name() == null ? "" : b.name().toLowerCase(Locale.ENGLISH);
			final int byName = nameA.compareTo(nameB);
			return byName != 0 ? byName : a.uuid().compareTo(b.uuid());
		});
		return Collections.unmodifiableList(views);
	}

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

	private boolean saveNow(Path path) {
		try {
			final Path parent = path.getParent();
			if (parent != null) {
				Files.createDirectories(parent);
			}

			final JsonObject permissionObject = new JsonObject();
			permissions.forEach((uuid, entry) -> {
				final JsonObject value = new JsonObject();
				value.addProperty(KEY_GRANTED, entry.granted());
				value.addProperty(KEY_NAME, entry.name() == null ? "" : entry.name());
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

	private static boolean isNumber(JsonObject object, String key) {
		return object.has(key) && object.get(key).isJsonPrimitive() && object.getAsJsonPrimitive(key).isNumber();
	}

	private static boolean isBoolean(JsonObject object, String key) {
		return object.has(key) && object.get(key).isJsonPrimitive() && object.getAsJsonPrimitive(key).isBoolean();
	}

	private record PermissionEntry(boolean granted, String name) {
	}

	public record PermissionEntryView(UUID uuid, boolean granted, String name) {

		public String getDisplayName() {
			return name == null || name.isEmpty() ? uuid.toString().substring(0, 8) : name;
		}
	}
}
