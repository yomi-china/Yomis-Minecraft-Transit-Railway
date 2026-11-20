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

	private static Boolean mtr34;

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

	/**
	 * MTR-34 is a closed-source ANTE fork that ships its classes under a loader prefix
	 * ({@code forge.cn.zbx1425.mtrsteamloco} / {@code fabric.cn.zbx1425.mtrsteamloco}), so the plain class
	 * check in {@link #isInstalled()} does not see it. It still takes over MTR's rendering the ANTE way and
	 * still expects the pre-split {@code Siding#setUnlimitedTrains} call shape, so a few call sites have to
	 * keep that shape available for its mixins.
	 */
	public static boolean isMtr34() {
		if (mtr34 == null) {
			boolean found = false;
			for (final String name : new String[]{"forge.cn.zbx1425.mtrsteamloco.Main", "fabric.cn.zbx1425.mtrsteamloco.Main"}) {
				try {
					Class.forName(name);
					found = true;
					break;
				} catch (ClassNotFoundException | LinkageError ignored) {
				}
			}
			mtr34 = found;
		}
		return mtr34;
	}
}
