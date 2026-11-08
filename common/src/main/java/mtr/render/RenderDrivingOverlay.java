package mtr.render;

import com.mojang.blaze3d.vertex.VertexConsumer;
import mtr.data.IGui;
import mtr.data.RailwayData;
import mtr.data.Train;
import mtr.data.TrainClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.util.Mth;
import org.joml.Matrix4f;

public class RenderDrivingOverlay implements IGui {

	private static TrainClient trainClient;
	private static int coolDown;

	private static final int EDGE_PADDING = 16;
	private static final int TOOL_SIZE = 96;
	private static final int RADIUS = TOOL_SIZE / 2;

	private static final int SPEEDOMETER_START_ANGLE = -60;
	private static final int SPEEDOMETER_SPAN = 300;

	/**
	 * The static part of the dial (face, rim and ticks) is cached in local coordinates and only
	 * rebuilt when the maximum speed of the driven train changes.
	 */
	private static float[] dialGeometry;
	private static int dialGeometryVertexCount;
	private static int cachedMaxSpeedKmh = -1;

	private static final int RING_SEGMENTS = 180;
	private static final int DIAL_BODY_SEGMENTS = 60;
	private static final int HUB_SEGMENTS = 20;
	private static final int VERTEX_STRIDE = 6;

	private static final float DIAL_RADIUS = RADIUS - 0.5F;
	private static final float RIM_INNER_RADIUS = RADIUS - 1.5F;
	private static final float RIM_FADE_RADIUS = RADIUS - 0.25F;
	private static final float RIM_OUTER_RADIUS = RADIUS + 0.25F;

	private static final float TICK_OUTER_RADIUS = RADIUS - 2;
	private static final int TICK_LENGTH_MAJOR = 8;
	private static final int TICK_LENGTH_MINOR = 4;
	private static final float TICK_THICKNESS = 2;

	private static final float LABEL_RADIUS = RADIUS - 14;
	private static final float LABEL_SCALE = 0.5F;

	private static final float NEEDLE_INNER_RADIUS = 2;
	private static final float NEEDLE_OUTER_RADIUS = RADIUS - 4;
	private static final float NEEDLE_CORE_WIDTH = 0.55F;
	private static final float NEEDLE_FEATHER_WIDTH = 0.95F;

	private static final float HUB_CORE_RADIUS = 1.9F;
	private static final float HUB_OUTER_RADIUS = 2.5F;

	private static final float MANUAL_TEXT_SCALE = 0.75F;

	private static final int PLATFORM_BAR_WIDTH = 6;
	private static final int PLATFORM_BAR_HEIGHT = 120;
	private static final int TEXT_PADDING = 4;

	private static final int BLUE_COLOR = 0xFFAACCFF;
	private static final int ORANGE_COLOR = 0xFFFF9900;
	private static final int GREEN_COLOR = 0xFF00FF00;
	private static final int RED_COLOR = 0xFFFF0000;
	private static final int DARK_GRAY = 0xFF333333;
	private static final int LIGHT_GRAY = 0xFFAAAAAA;
	private static final int DIAL_COLOR = 0xFF222222;

	public static void render(GuiGraphics guiGraphics) {
		final Minecraft client = Minecraft.getInstance();
		final LocalPlayer player = client.player;
		if (player == null || trainClient == null || coolDown <= 0) {
			return;
		}
		coolDown--;

		if (!Train.isHoldingKey(player) || !trainClient.isPlayerRiding(player)) {
			return;
		}

		final int screenWidth = guiGraphics.guiWidth();
		final int screenHeight = guiGraphics.guiHeight();

		renderPlatformBar(guiGraphics, client, screenWidth, screenHeight);
		renderSpeedometer(guiGraphics, client, screenWidth, screenHeight);
		renderStationInfo(guiGraphics, client, screenWidth, screenHeight);
	}

	private static void renderPlatformBar(GuiGraphics guiGraphics, Minecraft client, int screenWidth, int screenHeight) {
		final double distanceToStop = trainClient.getDistanceToNextStop();
		if (distanceToStop == -1 || distanceToStop < -5) return;

		final int barX = EDGE_PADDING;
		final int barY = screenHeight / 2 - PLATFORM_BAR_HEIGHT / 2;
		final int zeroLineOffset = 12;

		guiGraphics.fill(barX, barY, barX + PLATFORM_BAR_WIDTH, barY + PLATFORM_BAR_HEIGHT, 0x80000000);

		final int zeroLineY = barY + zeroLineOffset;
		guiGraphics.fill(barX, zeroLineY, barX + PLATFORM_BAR_WIDTH, zeroLineY + 1, 0x40FFFFFF);

		final double maxDisplayDistance = 500.0;
		final double clampedDistance = Mth.clamp(distanceToStop, -5.0, maxDisplayDistance);

		final int indicatorY;
		if (clampedDistance >= 0) {
			indicatorY = barY + zeroLineOffset + (int) ((clampedDistance / maxDisplayDistance) * (PLATFORM_BAR_HEIGHT - zeroLineOffset));
		} else {
			indicatorY = barY + zeroLineOffset + (int) ((clampedDistance / 5.0) * zeroLineOffset);
		}

		final int visibleIndicatorY = Mth.clamp(indicatorY, barY, barY + PLATFORM_BAR_HEIGHT);

		guiGraphics.fill(barX - 1, visibleIndicatorY, barX + PLATFORM_BAR_WIDTH + 1, visibleIndicatorY + 1, RED_COLOR);

		final String distanceText = RailwayData.round(distanceToStop, 1) + " m";
		final int textX = barX + PLATFORM_BAR_WIDTH + TEXT_PADDING;
		final int textY = visibleIndicatorY - client.font.lineHeight / 2;
		guiGraphics.drawString(client.font, distanceText, textX, textY, ARGB_WHITE, true);
	}

	private static void renderSpeedometer(GuiGraphics guiGraphics, Minecraft client, int screenWidth, int screenHeight) {
		final int centerX = screenWidth - RADIUS - EDGE_PADDING;
		final int centerY = screenHeight - RADIUS - EDGE_PADDING;

		final float speed = trainClient.getSpeed();
		final double speedKmh = speed * 20 * 3.6;
		final float doorValue = trainClient.getDoorValue();
		final int manualNotch = trainClient.getManualNotch();
		final boolean isManual = trainClient.isCurrentlyManual();
		final int maxSpeedKmh = Math.max(20, trainClient.getMaxManualSpeedKmh());

		final var poseStack = guiGraphics.pose();
		poseStack.pushPose();
		poseStack.translate(centerX, centerY, 0);
		final Matrix4f pose = poseStack.last().pose();

		// Everything is written into the GUI vertex batch: no shader, blend or depth state is
		// changed, so this cannot leak into (or clash with) the rest of the HUD rendering.
		final VertexConsumer consumer = guiGraphics.bufferSource().getBuffer(RenderType.gui());

		ensureDialGeometry(maxSpeedKmh);
		for (int i = 0; i < dialGeometryVertexCount; i++) {
			final int index = i * VERTEX_STRIDE;
			writeVertex(consumer, pose, dialGeometry[index], dialGeometry[index + 1], dialGeometry[index + 3], dialGeometry[index + 4], dialGeometry[index + 5], dialGeometry[index + 2]);
		}

		final float speedRatio = Mth.clamp((float) (speedKmh / maxSpeedKmh), 0, 1);
		writeNeedle(consumer, pose, SPEEDOMETER_START_ANGLE + speedRatio * SPEEDOMETER_SPAN);
		writeHub(consumer, pose);

		drawSpeedLabels(guiGraphics, client, maxSpeedKmh);

		poseStack.pushPose();
		poseStack.translate(-RADIUS * 0.3F, -TOOL_SIZE * 0.1F, 0);
		drawNotchText(guiGraphics, client, manualNotch);
		poseStack.popPose();

		poseStack.pushPose();
		poseStack.translate(0, -TOOL_SIZE * 0.25F, 0);
		poseStack.scale(MANUAL_TEXT_SCALE, MANUAL_TEXT_SCALE, 1);
		drawCenteredText(guiGraphics, client, "MANUAL", isManual ? GREEN_COLOR : DARK_GRAY);
		poseStack.popPose();

		poseStack.pushPose();
		poseStack.translate(RADIUS * 0.3F, -TOOL_SIZE * 0.1F, 0);
		drawCenteredText(guiGraphics, client, doorValue > 0 ? "DO" : "DC", ARGB_WHITE);
		poseStack.translate(0, 8, 0);
		poseStack.scale(0.5F, 0.5F, 1);
		drawCenteredText(guiGraphics, client, "(" + (int) (doorValue * 100) + "%)", ARGB_WHITE);
		poseStack.popPose();

		poseStack.pushPose();
		poseStack.translate(0, TOOL_SIZE * 0.1F, 0);
		drawCenteredText(guiGraphics, client, RailwayData.round(speedKmh, 1) + "", ARGB_WHITE);
		poseStack.translate(0, 8, 0);
		poseStack.scale(0.5F, 0.5F, 1);
		drawCenteredText(guiGraphics, client, "km/h", ARGB_WHITE);
		poseStack.popPose();

		poseStack.popPose();
	}

	private static void drawSpeedLabels(GuiGraphics guiGraphics, Minecraft client, int maxSpeedKmh) {
		final int labelStep = getLabelStep(maxSpeedKmh);
		final var poseStack = guiGraphics.pose();

		for (int value = 0; value <= maxSpeedKmh; value += labelStep) {
			final float radians = (float) Math.toRadians(angleFor(value, maxSpeedKmh));
			final int labelX = Math.round(Mth.cos(radians) * LABEL_RADIUS);
			final int labelY = Math.round(Mth.sin(radians) * LABEL_RADIUS);
			final String label = String.valueOf(value);
			final int width = client.font.width(label);

			poseStack.pushPose();
			poseStack.translate(labelX, labelY, 0);
			poseStack.scale(LABEL_SCALE, LABEL_SCALE, 1);
			guiGraphics.drawString(client.font, label, -width / 2, -client.font.lineHeight / 2, ARGB_WHITE, false);
			poseStack.popPose();
		}
	}

	private static void drawNotchText(GuiGraphics guiGraphics, Minecraft client, int manualNotch) {
		final String notchText;
		final int notchColor;
		if (manualNotch <= Train.EB) {
			notchText = "EB";
			notchColor = RED_COLOR;
		} else if (manualNotch < 0) {
			notchText = "B" + (-manualNotch);
			notchColor = ORANGE_COLOR;
		} else if (manualNotch > 0) {
			notchText = "P" + manualNotch;
			notchColor = BLUE_COLOR;
		} else {
			notchText = "N";
			notchColor = ARGB_WHITE;
		}
		drawCenteredText(guiGraphics, client, notchText, notchColor);

		if (manualNotch != 0 && manualNotch > Train.EB) {
			final var poseStack = guiGraphics.pose();
			poseStack.pushPose();
			poseStack.translate(0, 8, 0);
			poseStack.scale(0.5F, 0.5F, 1);
			final int powerPercent = Math.round(Math.abs(Train.getManualNotchAccelerationMultiplier(manualNotch)) * 100);
			drawCenteredText(guiGraphics, client, "(" + powerPercent + "%)", notchColor);
			poseStack.popPose();
		}
	}

	private static void renderStationInfo(GuiGraphics guiGraphics, Minecraft client, int screenWidth, int screenHeight) {
		final String thisStation = trainClient.getThisStation() != null ? IGui.formatStationName(trainClient.getThisStation().name) : null;
		final String nextStation = trainClient.getNextStation() != null ? IGui.formatStationName(trainClient.getNextStation().name) : null;
		final String thisRoute = trainClient.getThisRoute() != null ? IGui.formatStationName(trainClient.getThisRoute().name) : null;
		final String lastStation = trainClient.getLastStation() != null ? IGui.formatStationName(trainClient.getLastStation().name) : null;

		final int barY = screenHeight / 2 - PLATFORM_BAR_HEIGHT / 2;
		final int barBottomY = barY + PLATFORM_BAR_HEIGHT;

		final int textX = EDGE_PADDING;
		int textY = barBottomY + TEXT_PADDING * 2;

		if (thisStation != null) {
			guiGraphics.drawString(client.font, thisStation, textX, textY, ARGB_WHITE, true);
			textY += client.font.lineHeight + 2;
		}
		if (nextStation != null) {
			guiGraphics.drawString(client.font, "> " + nextStation, textX, textY, ARGB_WHITE, true);
			textY += client.font.lineHeight + 2;
		}
		if (thisRoute != null) {
			guiGraphics.drawString(client.font, thisRoute, textX, textY, ARGB_WHITE, true);
			textY += client.font.lineHeight + 2;
		}
		if (lastStation != null) {
			guiGraphics.drawString(client.font, "> " + lastStation, textX, textY, ARGB_WHITE, true);
		}
	}

	private static void ensureDialGeometry(int maxSpeedKmh) {
		if (dialGeometry != null && maxSpeedKmh == cachedMaxSpeedKmh) {
			return;
		}
		cachedMaxSpeedKmh = maxSpeedKmh;

		final int labelStep = getLabelStep(maxSpeedKmh);
		final int minorStep = getMinorStep(labelStep);
		final int tickCount = maxSpeedKmh / minorStep + 1;
		final GeometryBuilder builder = new GeometryBuilder(DIAL_BODY_SEGMENTS * 4 + RING_SEGMENTS * 8 + tickCount * 4);

		// Dial face. Its polygon edge is hidden below the rim, so it can be built coarsely.
		addRingBand(builder, 0, DIAL_RADIUS, 255, 255, DIAL_COLOR, DIAL_BODY_SEGMENTS);

		// Rim, with a solid inner part and a feathered outer edge for smooth anti-aliased borders.
		addRingBand(builder, RIM_INNER_RADIUS, RIM_FADE_RADIUS, 255, 255, LIGHT_GRAY, RING_SEGMENTS);
		addRingBand(builder, RIM_FADE_RADIUS, RIM_OUTER_RADIUS, 255, 0, LIGHT_GRAY, RING_SEGMENTS);

		for (int value = 0; value <= maxSpeedKmh; value += minorStep) {
			final float length = value % labelStep == 0 ? TICK_LENGTH_MAJOR : TICK_LENGTH_MINOR;
			addTick(builder, angleFor(value, maxSpeedKmh), TICK_OUTER_RADIUS - length, TICK_OUTER_RADIUS, TICK_THICKNESS);
		}

		dialGeometry = builder.data;
		dialGeometryVertexCount = builder.size / VERTEX_STRIDE;
	}

	private static void addRingBand(GeometryBuilder builder, float innerRadius, float outerRadius, int innerAlpha, int outerAlpha, int color, int segments) {
		final float red = ((color >> 16) & 0xFF) / 255F;
		final float green = ((color >> 8) & 0xFF) / 255F;
		final float blue = (color & 0xFF) / 255F;

		for (int i = 0; i < segments; i++) {
			final float angle1 = (float) (i * Math.PI * 2 / segments);
			final float angle2 = (float) ((i + 1) * Math.PI * 2 / segments);
			final float cos1 = Mth.cos(angle1);
			final float sin1 = Mth.sin(angle1);
			final float cos2 = Mth.cos(angle2);
			final float sin2 = Mth.sin(angle2);

			builder.quad(
					cos1 * innerRadius, sin1 * innerRadius, innerAlpha,
					cos2 * innerRadius, sin2 * innerRadius, innerAlpha,
					cos2 * outerRadius, sin2 * outerRadius, outerAlpha,
					cos1 * outerRadius, sin1 * outerRadius, outerAlpha,
					red, green, blue
			);
		}
	}

	private static void addTick(GeometryBuilder builder, float angleDegrees, float innerRadius, float outerRadius, float thickness) {
		final float radians = (float) Math.toRadians(angleDegrees);
		final float cos = Mth.cos(radians);
		final float sin = Mth.sin(radians);
		final float offsetX = -sin * thickness / 2;
		final float offsetY = cos * thickness / 2;

		final float shade = 0xAA / 255F;
		builder.quad(
				cos * innerRadius - offsetX, sin * innerRadius - offsetY, 255,
				cos * innerRadius + offsetX, sin * innerRadius + offsetY, 255,
				cos * outerRadius + offsetX, sin * outerRadius + offsetY, 255,
				cos * outerRadius - offsetX, sin * outerRadius - offsetY, 255,
				shade, shade, shade
		);
	}

	private static void writeNeedle(VertexConsumer consumer, Matrix4f pose, float angleDegrees) {
		final float radians = (float) Math.toRadians(angleDegrees);
		final float cos = Mth.cos(radians);
		final float sin = Mth.sin(radians);
		final float offsetX = -sin;
		final float offsetY = cos;

		final float red = 1;
		final float green = 0;
		final float blue = 0;

		writeNeedleBand(consumer, pose, cos, sin, offsetX, offsetY, -NEEDLE_CORE_WIDTH, NEEDLE_CORE_WIDTH, red, green, blue);
		writeNeedleBand(consumer, pose, cos, sin, offsetX, offsetY, -NEEDLE_FEATHER_WIDTH, -NEEDLE_CORE_WIDTH, red, green, blue);
		writeNeedleBand(consumer, pose, cos, sin, offsetX, offsetY, NEEDLE_CORE_WIDTH, NEEDLE_FEATHER_WIDTH, red, green, blue);
	}

	private static void writeNeedleBand(VertexConsumer consumer, Matrix4f pose, float cos, float sin, float offsetX, float offsetY, float side1, float side2, float red, float green, float blue) {
		final float inner1X = cos * NEEDLE_INNER_RADIUS + offsetX * side1;
		final float inner1Y = sin * NEEDLE_INNER_RADIUS + offsetY * side1;
		final float outer1X = cos * NEEDLE_OUTER_RADIUS + offsetX * side1;
		final float outer1Y = sin * NEEDLE_OUTER_RADIUS + offsetY * side1;
		final float outer2X = cos * NEEDLE_OUTER_RADIUS + offsetX * side2;
		final float outer2Y = sin * NEEDLE_OUTER_RADIUS + offsetY * side2;
		final float inner2X = cos * NEEDLE_INNER_RADIUS + offsetX * side2;
		final float inner2Y = sin * NEEDLE_INNER_RADIUS + offsetY * side2;

		// The outer edge of each band fades out to keep the needle edges smooth.
		final float alpha1 = Math.abs(side1) > NEEDLE_CORE_WIDTH ? 0 : 1;
		final float alpha2 = Math.abs(side2) > NEEDLE_CORE_WIDTH ? 0 : 1;

		writeQuad(consumer, pose,
				inner1X, inner1Y, alpha1,
				inner2X, inner2Y, alpha2,
				outer2X, outer2Y, alpha2,
				outer1X, outer1Y, alpha1,
				red, green, blue
		);
	}

	private static void writeHub(VertexConsumer consumer, Matrix4f pose) {
		writeRingBand(consumer, pose, 0, HUB_CORE_RADIUS, 1, 1, HUB_SEGMENTS, 1, 1, 1);
		writeRingBand(consumer, pose, HUB_CORE_RADIUS, HUB_OUTER_RADIUS, 1, 0, HUB_SEGMENTS, 1, 1, 1);
	}

	private static void writeRingBand(VertexConsumer consumer, Matrix4f pose, float innerRadius, float outerRadius, float innerAlpha, float outerAlpha, int segments, float red, float green, float blue) {
		for (int i = 0; i < segments; i++) {
			final float angle1 = (float) (i * Math.PI * 2 / segments);
			final float angle2 = (float) ((i + 1) * Math.PI * 2 / segments);
			final float cos1 = Mth.cos(angle1);
			final float sin1 = Mth.sin(angle1);
			final float cos2 = Mth.cos(angle2);
			final float sin2 = Mth.sin(angle2);

			writeQuad(consumer, pose,
					cos1 * innerRadius, sin1 * innerRadius, innerAlpha,
					cos2 * innerRadius, sin2 * innerRadius, innerAlpha,
					cos2 * outerRadius, sin2 * outerRadius, outerAlpha,
					cos1 * outerRadius, sin1 * outerRadius, outerAlpha,
					red, green, blue
			);
		}
	}

	private static void writeQuad(VertexConsumer consumer, Matrix4f pose, float x1, float y1, float alpha1, float x2, float y2, float alpha2, float x3, float y3, float alpha3, float x4, float y4, float alpha4, float red, float green, float blue) {
		writeVertex(consumer, pose, x1, y1, red, green, blue, alpha1);
		writeVertex(consumer, pose, x2, y2, red, green, blue, alpha2);
		writeVertex(consumer, pose, x3, y3, red, green, blue, alpha3);
		writeVertex(consumer, pose, x4, y4, red, green, blue, alpha4);
	}

	private static void writeVertex(VertexConsumer consumer, Matrix4f pose, float x, float y, float red, float green, float blue, float alpha) {
		// Transforming by hand avoids the temporary vector allocated by VertexConsumer#vertex(Matrix4f, ...).
		final float transformedX = pose.m00() * x + pose.m10() * y + pose.m30();
		final float transformedY = pose.m01() * x + pose.m11() * y + pose.m31();
		final float transformedZ = pose.m02() * x + pose.m12() * y + pose.m32();
		consumer.vertex(transformedX, transformedY, transformedZ).color(red, green, blue, alpha).endVertex();
	}

	private static float angleFor(int value, int maxSpeedKmh) {
		return SPEEDOMETER_START_ANGLE + (float) value * SPEEDOMETER_SPAN / maxSpeedKmh;
	}

	private static int getLabelStep(int maxSpeedKmh) {
		if (maxSpeedKmh >= 100) {
			return 20;
		} else if (maxSpeedKmh >= 40) {
			return 10;
		} else if (maxSpeedKmh >= 20) {
			return 5;
		} else {
			return 1;
		}
	}

	private static int getMinorStep(int labelStep) {
		return Math.max(1, labelStep / 4);
	}

	public static void setData(int accelerationSign, TrainClient trainClient) {
		RenderDrivingOverlay.trainClient = trainClient;
		coolDown = 2;
	}

	private static void drawCenteredText(GuiGraphics guiGraphics, Minecraft client, String text, int color) {
		final int width = client.font.width(text);
		guiGraphics.drawString(client.font, text, -width / 2, -client.font.lineHeight / 2, color, false);
	}

	private static final class GeometryBuilder {

		private float[] data;
		private int size;

		private GeometryBuilder(int vertexCapacity) {
			data = new float[Math.max(4, vertexCapacity) * VERTEX_STRIDE];
		}

		private void quad(float x1, float y1, float alpha1, float x2, float y2, float alpha2, float x3, float y3, float alpha3, float x4, float y4, float alpha4, float red, float green, float blue) {
			vertex(x1, y1, alpha1, red, green, blue);
			vertex(x2, y2, alpha2, red, green, blue);
			vertex(x3, y3, alpha3, red, green, blue);
			vertex(x4, y4, alpha4, red, green, blue);
		}

		private void vertex(float x, float y, float alpha, float red, float green, float blue) {
			if (size + VERTEX_STRIDE > data.length) {
				final float[] grown = new float[data.length * 2];
				System.arraycopy(data, 0, grown, 0, size);
				data = grown;
			}
			data[size++] = x;
			data[size++] = y;
			data[size++] = alpha / 255F;
			data[size++] = red;
			data[size++] = green;
			data[size++] = blue;
		}
	}
}
