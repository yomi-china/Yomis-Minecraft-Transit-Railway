package mtr.webdashboard.servlet;

import mtr.packet.IPacket;
import net.minecraft.resources.ResourceLocation;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * {@code PATCH /api/route/<id>} - edits a route's name and colour. The stop list and per-stop settings will
 * extend the field table, not this class.
 */
public class RoutePatchServlet extends WebDashboardWriteServlet {

	/** The collection path; the object id follows it. */
	public static final String PATH = "/api/route";

	@Override
	protected String getKind() {
		return "route";
	}

	@Override
	protected ResourceLocation getPacketId() {
		return IPacket.PACKET_UPDATE_ROUTE;
	}

	@Override
	protected void handlePatch(HttpServletRequest request, HttpServletResponse response) {
		applyPatchRequest(request, response);
	}
}
