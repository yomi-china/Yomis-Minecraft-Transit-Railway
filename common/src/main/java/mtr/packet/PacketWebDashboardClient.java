package mtr.packet;

import io.netty.buffer.Unpooled;
import mtr.RegistryClient;
import mtr.mappings.Text;
import mtr.webdashboard.WebDashboardRuntime;
import mtr.webdashboard.WebDashboardServer;
import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;

/**
 * Client side of the web dashboard sign-in.
 * <p>
 * Receives the one-time token from the server and opens the browser. With a token the browser signs
 * in; without one it opens read-only, which is the correct outcome when the reply was empty.
 */
public class PacketWebDashboardClient implements IPacket {

	/**
	 * Requests a sign-in token for the local player.
	 * <p>
	 * Called from the dashboard button's click handler. Refuses early when the service is not
	 * listening, rather than opening a browser onto a dead port: the reply would still arrive, but a
	 * connection error page is a worse answer than a clear message in chat.
	 */
	public static void requestLoginToken() {
		if (!WebDashboardRuntime.isRunning()) {
			notifyPlayer(Text.translatable("gui.mtr.web_dashboard_not_running").getString());
			return;
		}

		final FriendlyByteBuf packet = new FriendlyByteBuf(Unpooled.buffer());
		RegistryClient.sendToServer(PACKET_WEB_DASHBOARD_LOGIN_REQUEST, packet);
	}

	public static void openWebDashboard(Minecraft minecraftClient, FriendlyByteBuf packet) {
		final String token = readToken(packet);

		// Bounced onto the client thread: this runs on the network thread, and both opening a browser
		// and writing to chat must happen on the game thread.
		minecraftClient.execute(() -> {
			if (token == null || token.isEmpty()) {
				// No permission, or a token could not be issued. Still open the page: a visitor without
				// access is meant to be able to look, and the page says plainly that it is read-only.
				WebDashboardServer.openReadOnly();
			} else {
				WebDashboardServer.openWithToken(token);
			}
		});
	}

	private static void notifyPlayer(String message) {
		final Minecraft minecraft = Minecraft.getInstance();
		minecraft.execute(() -> {
			if (minecraft.player != null) {
				minecraft.player.displayClientMessage(Text.literal(message), false);
			}
		});
	}

	/**
	 * Reads the token defensively. A malformed or truncated packet from a mismatched server build
	 * should degrade to "read-only" rather than throw on the network thread.
	 */
	private static String readToken(FriendlyByteBuf packet) {
		try {
			return packet.readUtf();
		} catch (Exception e) {
			System.out.println("[MTR-WebDashboard] Could not read the sign-in token from the server: " + e);
			return "";
		}
	}
}
