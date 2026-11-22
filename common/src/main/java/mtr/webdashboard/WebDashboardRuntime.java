package mtr.webdashboard;

import net.minecraft.server.MinecraftServer;

public final class WebDashboardRuntime {

	private static volatile Integer activePort = null;
	private static volatile MinecraftServer server = null;

	private WebDashboardRuntime() {
	}

	static void set(int port) {
		activePort = port;
	}

	static void clear() {
		activePort = null;
	}

	public static boolean isRunning() {
		return activePort != null;
	}

	public static int getPort() {
		final Integer port = activePort;
		return port == null ? -1 : port;
	}

	public static void setServer(MinecraftServer newServer) {
		server = newServer;
	}

	public static MinecraftServer getServer() {
		return server;
	}
}
