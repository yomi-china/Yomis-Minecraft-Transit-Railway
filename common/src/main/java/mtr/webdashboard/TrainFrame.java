package mtr.webdashboard;

/**
 * Immutable per-train snapshot consumed by the dashboard. Written on the game thread and read by the
 * HTTP/stream threads, so every field is final and the array is never handed out for mutation.
 */
public record TrainFrame(
		long id,
		long sidingId,
		long routeId,
		long depotId,
		long nextStopId,
		StopKind nextStopKind,
		int cars,
		int spacing,
		int width,
		int color,
		double speed,
		double doors,
		boolean onRoute,
		boolean manual,
		boolean reversed,
		double heading,
		double[] centers) {

	public enum StopKind {
		NONE("none"),
		PLATFORM("platform"),
		SIDING("siding");

		private final String json;

		StopKind(String json) {
			this.json = json;
		}

		public String json() {
			return json;
		}
	}
}
