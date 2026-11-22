package mtr.render;

import mtr.MTRClient;
import mtr.client.Config;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;

/**
 * Conservative view-cone based visibility test for vehicles.
 * <p>
 * The test is intentionally very loose: it only culls a vehicle when it is at least
 * {@link #MIN_CULL_DISTANCE} blocks away and completely outside a cone built from the actual
 * camera FOV (using the screen diagonal, the worst case) plus a large safety margin.
 * The cone is rotation invariant, so camera roll / view bobbing cannot make it wrong.
 * <p>
 * No matrix assumptions are made: the camera look vector comes from the camera rotation and the
 * FOV is taken from the user's FOV option with an upper-bound multiplier, so sprinting / speed FOV
 * changes can never make the cone too small.
 */
public final class TrainRenderCulling {

	private static final double MIN_CULL_DISTANCE = 32;
	private static final double MIN_CULL_DISTANCE_SQUARED = MIN_CULL_DISTANCE * MIN_CULL_DISTANCE;
	private static final double DEFAULT_RADIUS = 16;
	private static final double SAFETY_MARGIN = 0.05;
	private static final double FOV_MARGIN_DEGREES = 25;
	private static final double FOV_UPPER_BOUND_MULTIPLIER = 1.2;

	private static boolean ready;
	private static double cameraX;
	private static double cameraY;
	private static double cameraZ;
	private static double lookX;
	private static double lookY = 1;
	private static double lookZ;
	private static double cosHalfAngle = -1;

	private TrainRenderCulling() {
	}

	public static void prepare(Camera camera) {
		clear();
		if (camera == null || MTRClient.isReplayMod() || !Config.cullVehiclesOutsideView()) {
			return;
		}

		final Vec3 position = camera.getPosition();
		final Vec3 look = Vec3.directionFromRotation(camera.getXRot(), camera.getYRot());
		if (look.lengthSqr() < 1.0E-6) {
			return;
		}

		final Minecraft minecraft = Minecraft.getInstance();
		final int baseFov = minecraft.options.fov().get();
		if (baseFov <= 0) {
			return;
		}
		// Upper bound: sprinting / speed effects can enlarge the FOV, and zooming (spyglass) can only reduce it.
		final double fov = Math.min(179, baseFov * FOV_UPPER_BOUND_MULTIPLIER);
		if (fov <= 0 || fov >= 180) {
			return;
		}

		final double aspect = (double) minecraft.getWindow().getWidth() / Math.max(1, minecraft.getWindow().getHeight());
		final double halfVertical = Math.toRadians(fov) / 2;
		final double halfDiagonal = Math.atan(Math.tan(halfVertical) * Math.sqrt(1 + aspect * aspect)) + Math.toRadians(FOV_MARGIN_DEGREES);
		if (halfDiagonal >= Math.PI / 2) {
			return;
		}

		cameraX = position.x;
		cameraY = position.y;
		cameraZ = position.z;
		lookX = look.x;
		lookY = look.y;
		lookZ = look.z;
		cosHalfAngle = Math.cos(halfDiagonal);
		ready = true;
	}

	public static void clear() {
		ready = false;
		cosHalfAngle = -1;
	}

	public static boolean isEnabled() {
		return ready;
	}

	public static double getDefaultRadius() {
		return DEFAULT_RADIUS;
	}

	/**
	 * @return false only when the point is completely outside the conservative view cone.
	 */
	public static boolean isVisible(double x, double y, double z, double radius) {
		if (!ready) {
			return true;
		}

		final double dx = x - cameraX;
		final double dy = y - cameraY;
		final double dz = z - cameraZ;
		final double distanceSquared = dx * dx + dy * dy + dz * dz;
		if (distanceSquared <= MIN_CULL_DISTANCE_SQUARED) {
			return true;
		}

		final double distance = Math.sqrt(distanceSquared);
		final double dot = (dx * lookX + dy * lookY + dz * lookZ) / distance;
		final double radiusAllowance = Math.min(1, Math.max(0, radius) / distance);
		return dot >= cosHalfAngle - radiusAllowance - SAFETY_MARGIN;
	}
}
