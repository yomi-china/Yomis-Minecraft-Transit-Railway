package mtr.webdashboard.servlet;

import mtr.packet.IPacket;
import net.minecraft.resources.ResourceLocation;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * {@code PATCH /api/depot/<id>} - edits a depot's name and colour.
 *
 * Note for the later frequency and departure work: a depot has no per-field setter, its whole state travelling
 * in one packet built by {@code Depot.setData}. A depot edit must therefore submit every field it wants to
 * keep, since omitting one resets it.
 */
public class DepotPatchServlet extends WebDashboardWriteServlet {

	/** The collection path; the object id follows it. */
	public static final String PATH = "/api/depot";

	@Override
	protected String getKind() {
		return "depot";
	}

	@Override
	protected ResourceLocation getPacketId() {
		return IPacket.PACKET_UPDATE_DEPOT;
	}

	@Override
	protected void handlePatch(HttpServletRequest request, HttpServletResponse response) {
		applyPatchRequest(request, response);
	}
}
