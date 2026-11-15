package mtr.client;

/**
 * Lightweight runtime detection of the ANTE addon.
 * <p>
 * ANTE replaces the door scan method with its own implementation which has side effects on
 * EyeCandy platform blocks (it pushes the train door target/value every scan). When ANTE is
 * present we must not skip the door scan while the doors are fully closed, otherwise the
 * last update would be skipped and an EyeCandy door could remain slightly open.
 */
public final class AnteCompat {

	private static Boolean installed;

	private AnteCompat() {
	}

	public static boolean isInstalled() {
		if (installed == null) {
			try {
				Class.forName("cn.zbx1425.mtrsteamloco.render.rail.RailRenderDispatcher");
				installed = true;
			} catch (ClassNotFoundException | LinkageError e) {
				installed = false;
			}
		}
		return installed;
	}
}
