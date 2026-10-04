package mtr.render;

import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.math.Matrix4f;
import com.mojang.math.Vector3f;
import mtr.client.IDrawing;
import mtr.data.*;
import mtr.mappings.UtilitiesClient;
import net.minecraft.client.Minecraft;
import mtr.mappings.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;

public class RenderDrivingOverlay implements IGui {

	private static TrainClient trainClient;
	private static int coolDown;

	public static void render(PoseStack poseStack) {
		render(new GuiGraphics(poseStack));
	}

	private static final int EDGE_PADDING = 16;
	private static final int TOOL_SIZE = 96;
	private static final int RADIUS = TOOL_SIZE / 2;
	private static final int SPEEDOMETER_CIRCLE_INTERVAL = 3;
	private static final double SPEEDOMETER_CIRCLE_EDGE_LENGTH = Math.tan(Math.toRadians(SPEEDOMETER_CIRCLE_INTERVAL) / 2) * TOOL_SIZE + 0.5;
	private static final int SPEEDOMETER_START_ANGLE = -60;
	private static final int SPEEDOMETER_SPAN = 300;
	private static final int SPEEDOMETER_TICK_INTERVAL = 5;

	private static final int PLATFORM_BAR_WIDTH = 6;
	private static final int PLATFORM_BAR_HEIGHT = 120;
	private static final int PLATFORM_BAR_OFFSET_Y = 20;
	private static final int TEXT_PADDING = 4;

	private static final int BLUE_COLOR = 0xFFAACCFF;
	private static final int ORANGE_COLOR = 0xFFFF9900;
	private static final int GREEN_COLOR = 0xFF00FF00;
	private static final int RED_COLOR = 0xFFFF0000;
	private static final int DARK_GRAY = 0xFF333333;
	private static final int LIGHT_GRAY = 0xFFAAAAAA;

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

		final Window window = client.getWindow();
		if (window == null) return;

		final int screenWidth = window.getGuiScaledWidth();
		final int screenHeight = window.getGuiScaledHeight();

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

		final PoseStack matrixStack = guiGraphics.pose();
		matrixStack.pushPose();
		matrixStack.translate(centerX, centerY, 0);

		final float speed = trainClient.getSpeed();
		final double speedKmh = speed * 20 * 3.6;
		final float doorValue = trainClient.getDoorValue();
		final int manualNotch = trainClient.getManualNotch();
		final boolean isManual = trainClient.isCurrentlyManual();
		final int maxSpeedKmh = trainClient.getMaxManualSpeedKmh();

		RenderSystem.enableBlend();
		final Tesselator tesselator = Tesselator.getInstance();
		final BufferBuilder buffer = tesselator.getBuilder();
		final float halfEdge = (float) SPEEDOMETER_CIRCLE_EDGE_LENGTH / 2;

		matrixStack.pushPose();
		for (int i = 0; i < 180; i += SPEEDOMETER_CIRCLE_INTERVAL) {
			final Matrix4f pose = matrixStack.last().pose();
			UtilitiesClient.beginDrawingRectangle(buffer);
			drawRectangle(buffer, pose, -RADIUS, -halfEdge, RADIUS, halfEdge, 0xFFAAAAAA);
			drawRectangle(buffer, pose, -RADIUS + 1, -halfEdge, RADIUS - 1, halfEdge, 0xFF222222);
			tesselator.end();
			UtilitiesClient.finishDrawingRectangle();
			matrixStack.mulPose(Vector3f.ZP.rotationDegrees(SPEEDOMETER_CIRCLE_INTERVAL));
		}
		matrixStack.popPose();

		matrixStack.pushPose();
		matrixStack.mulPose(Vector3f.ZP.rotationDegrees(SPEEDOMETER_START_ANGLE));
		for (int i = 0; i <= maxSpeedKmh; i += SPEEDOMETER_TICK_INTERVAL) {
			final boolean isMajor = (i % 20 == 0);
			final int tickLength = isMajor ? 8 : 4;
			guiGraphics.fill(-RADIUS + 2, -1, -RADIUS + 2 + tickLength, 1, LIGHT_GRAY);
			matrixStack.mulPose(Vector3f.ZP.rotationDegrees((float) SPEEDOMETER_TICK_INTERVAL * SPEEDOMETER_SPAN / maxSpeedKmh));
		}
		matrixStack.popPose();

		matrixStack.pushPose();
		matrixStack.mulPose(Vector3f.ZP.rotationDegrees(SPEEDOMETER_START_ANGLE));
		for (int i = 0; i <= maxSpeedKmh; i += 20) {
			matrixStack.pushPose();
			matrixStack.translate(-RADIUS + 12, 0, 0);
			matrixStack.mulPose(Vector3f.ZP.rotationDegrees(-SPEEDOMETER_START_ANGLE - (float) i * SPEEDOMETER_SPAN / maxSpeedKmh));
			matrixStack.scale(0.5F, 0.5F, 1);
			final String label = String.valueOf(i);
			final int width = client.font.width(label);
			guiGraphics.drawString(client.font, label, -width / 2, -4, ARGB_WHITE, false);
			matrixStack.popPose();
			matrixStack.mulPose(Vector3f.ZP.rotationDegrees(20F * SPEEDOMETER_SPAN / maxSpeedKmh));
		}
		matrixStack.popPose();

		matrixStack.pushPose();
		final float needleAngle = SPEEDOMETER_START_ANGLE + (float) speedKmh * SPEEDOMETER_SPAN / maxSpeedKmh;
		matrixStack.mulPose(Vector3f.ZP.rotationDegrees(needleAngle));
		final Matrix4f needlePose = matrixStack.last().pose();
		UtilitiesClient.beginDrawingRectangle(buffer);
		drawRectangle(buffer, needlePose, -RADIUS + 4, -1, 0, 1, RED_COLOR);
		tesselator.end();
		UtilitiesClient.finishDrawingRectangle();
		matrixStack.popPose();

		guiGraphics.fill(-2, -2, 2, 2, 0xFFFFFFFF);

		matrixStack.pushPose();
		matrixStack.translate(-RADIUS * 0.3, -TOOL_SIZE * 0.1, 0);
		String notchText;
		int notchColor;
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
			matrixStack.translate(0, 8, 0);
			matrixStack.scale(0.5F, 0.5F, 1);
			final int powerPercent = Math.round(Math.abs(Train.getManualNotchAccelerationMultiplier(manualNotch)) * 100);
			drawCenteredText(guiGraphics, client, "(" + powerPercent + "%)", notchColor);
		}
		matrixStack.popPose();

		matrixStack.pushPose();
		matrixStack.translate(0, -TOOL_SIZE * 0.25, 0);
		drawCenteredText(guiGraphics, client, "MANUAL", isManual ? GREEN_COLOR : DARK_GRAY);
		matrixStack.popPose();

		matrixStack.pushPose();
		matrixStack.translate(RADIUS * 0.3, -TOOL_SIZE * 0.1, 0);
		final String doorState = doorValue > 0 ? "DO" : "DC";
		drawCenteredText(guiGraphics, client, doorState, ARGB_WHITE);
		matrixStack.translate(0, 8, 0);
		matrixStack.scale(0.5F, 0.5F, 1);
		drawCenteredText(guiGraphics, client, "(" + (int) (doorValue * 100) + "%)", ARGB_WHITE);
		matrixStack.popPose();

		matrixStack.pushPose();
		matrixStack.translate(0, TOOL_SIZE * 0.1, 0);
		drawCenteredText(guiGraphics, client, RailwayData.round(speedKmh, 1) + "", ARGB_WHITE);
		matrixStack.translate(0, 8, 0);
		matrixStack.scale(0.5F, 0.5F, 1);
		drawCenteredText(guiGraphics, client, "km/h", ARGB_WHITE);
		matrixStack.popPose();

		matrixStack.popPose();
		RenderSystem.disableBlend();
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

	public static void setData(int accelerationSign, TrainClient trainClient) {
		RenderDrivingOverlay.trainClient = trainClient;
		coolDown = 2;
	}

	private static void drawCenteredText(GuiGraphics guiGraphics, Minecraft client, String text, int color) {
		final int width = client.font.width(text);
		guiGraphics.drawString(client.font, text, -width / 2, -client.font.lineHeight / 2, color, false);
	}

	private static void drawRectangle(BufferBuilder buffer, Matrix4f pose, float x1, float y1, float x2, float y2, int color) {
		final int a = (color >> 24) & 0xFF;
		final int r = (color >> 16) & 0xFF;
		final int g = (color >> 8) & 0xFF;
		final int b = color & 0xFF;
		buffer.vertex(pose, x1, y1, 0).color(r, g, b, a).endVertex();
		buffer.vertex(pose, x1, y2, 0).color(r, g, b, a).endVertex();
		buffer.vertex(pose, x2, y2, 0).color(r, g, b, a).endVertex();
		buffer.vertex(pose, x2, y1, 0).color(r, g, b, a).endVertex();
	}
}
