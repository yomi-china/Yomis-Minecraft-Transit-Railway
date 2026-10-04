package mtr.packet;

import io.netty.buffer.Unpooled;
import mtr.RegistryClient;
import mtr.mappings.ScreenMapper;
import mtr.mappings.Text;
import mtr.screen.WebDashboardUrlScreen;
import mtr.webdashboard.WebDashboardRuntime;
import mtr.webdashboard.WebDashboardServer;
import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;

/**
 * Client side of the web dashboard sign-in.
 * <p>
 * Receives the one-time token from the server and shows the address dialog. It deliberately does not
 * launch a browser: having one appear because a button was pressed inside a game is unwelcome, and
 * there was previously no way to obtain the address without letting it open.
 */
public class PacketWebDashboardClient implements IPacket {

	/**
	 * Requests a sign-in token for the local player.
	 * <p>
	 * Called from the dashboard button's click handler. Refuses early when the service is not
	 * listening, rather than offering a link to a dead port.
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

		// Bounced onto the client thread: this runs on the network thread, and opening a screen must
		// happen on the game thread.
		minecraftClient.execute(() -> {
			// Screens are switched through UtilitiesClient, which only accepts the mod's ScreenMapper base
			// class, so the current screen is narrowed here. Every screen that can reach the dashboard
			// button - and therefore this code - extends it; a vanilla screen in the way is treated as
			// "nothing to go back to".
			final net.minecraft.client.gui.screens.Screen current = minecraftClient.screen;
			if (current instanceof WebDashboardUrlScreen) {
				// Already showing it - the reply to an earlier press arrived late.
				return;
			}
			if (!(current instanceof ScreenMapper)) {
				// Nothing to go back to. Returning keeps an already-open valid dialog, and skips a token that
				// cannot be presented rather than dropping the player onto a blank screen when they close it.
				// Reaching here at all means something other than a dashboard screen asked to sign in.
				System.out.println("[MTR-WebDashboard] Ignoring a sign-in reply: the current screen is not a dashboard screen.");
				return;
			}
			final ScreenMapper previousScreen = (ScreenMapper) current;

			// With no token the plain address is handed over, and opening it lands on the read-only page.
			// That is the intended outcome for a player without edit access rather than a refusal, so the
			// dialog appears either way and the page explains the situation itself.
			//
			// The address shown and copied is the same one that Open uses, sign-in fragment included:
			// without the fragment the link would silently land read-only, which is worse than the token
			// being in the clipboard. It is never drawn on screen, only put in the clipboard - see
			// WebDashboardUrlScreen for why.
			final String url = WebDashboardServer.getLoginUrl(token);

			minecraftClient.setScreen(new WebDashboardUrlScreen(url, url, previousScreen));
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
	 * should degrade to "no token", i.e. the read-only link, rather than throw on the network thread.
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
