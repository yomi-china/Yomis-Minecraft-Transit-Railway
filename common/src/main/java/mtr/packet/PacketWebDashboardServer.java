package mtr.packet;

import io.netty.buffer.Unpooled;
import mtr.Registry;
import mtr.mappings.Text;
import mtr.webdashboard.WebDashboardPermissions;
import mtr.webdashboard.WebDashboardRuntime;
import mtr.webdashboard.WebDashboardTokenStore;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;

/**
 * Server side of the web dashboard sign-in.
 * <p>
 * The client asks, this decides. Keeping the decision here rather than on the client means the
 * permission rule has exactly one implementation - {@link WebDashboardPermissions} - shared with the
 * HTTP layer, so the two cannot drift apart.
 */
public class PacketWebDashboardServer implements IPacket {

	/**
	 * Answers a client's request to open the dashboard, with a one-time login token when the player
	 * may edit and an empty string otherwise.
	 * <p>
	 * An empty token is a normal outcome, not an error: the client still opens the page, read-only.
	 * That is the intended behaviour for a player without access.
	 */
	public static void receiveLoginRequest(ServerPlayer player) {
		final String token;
		if (WebDashboardPermissions.canEdit(player)) {
			// The token is re-validated against the permission when it is redeemed, so a grant withdrawn
			// in the seconds before the browser loads still closes the door.
			token = WebDashboardTokenStore.issueLoginToken(player.getUUID(), player.getName().getString());
		} else {
			token = "";
		}

		final FriendlyByteBuf packet = new FriendlyByteBuf(Unpooled.buffer());
		packet.writeUtf(token);
		Registry.sendToPlayer(player, PACKET_WEB_DASHBOARD_LOGIN_TOKEN, packet);

		// Reported here rather than on the client so the message reflects the authoritative answer.
		if (token.isEmpty()) {
			player.displayClientMessage(Text.translatable("gui.mtr.web_dashboard_no_permission"), false);
		} else if (!WebDashboardRuntime.isRunning()) {
			// The service failed to bind earlier. The token is still valid, so the page still works once
			// the service is up; say so instead of implying the browser will show something useful.
			player.displayClientMessage(Text.translatable("gui.mtr.web_dashboard_not_running"), false);
		}
	}
}
