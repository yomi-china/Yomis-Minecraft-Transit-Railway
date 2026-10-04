package mtr.webdashboard.servlet;

import mtr.packet.IPacket;
import net.minecraft.resources.ResourceLocation;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * {@code PATCH /api/station/<id>} - edits a station's name, colour or zone. Which fields exist is decided by
 * {@code WebDashboardFields}, so the exits and the selection can be added without changing this class.
 */
public class StationPatchServlet extends WebDashboardWriteServlet {

	/** The collection path; the object id follows it. */
	public static final String PATH = "/api/station";

	@Override
	protected String getKind() {
		return "station";
	}

	@Override
	protected ResourceLocation getPacketId() {
		return IPacket.PACKET_UPDATE_STATION;
	}

	@Override
	protected void handlePatch(HttpServletRequest request, HttpServletResponse response) {
		applyPatchRequest(request, response);
	}
}
