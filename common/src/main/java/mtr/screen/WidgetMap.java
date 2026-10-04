package mtr.screen;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.Tesselator;
import mtr.client.ClientData;
import mtr.client.IDrawing;
import mtr.data.*;
import mtr.mappings.SelectableMapper;
import mtr.mappings.Text;
import mtr.mappings.UtilitiesClient;
import mtr.mappings.WidgetMapper;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import mtr.mappings.GuiGraphics;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.util.Tuple;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.ConcurrentModificationException;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;


public class WidgetMap implements WidgetMapper, SelectableMapper, GuiEventListener, IGui {

	private int x;
	private int y;
	private int width;
	private int height;
	private double scale;
	private double centerX;
	private double centerY;
	private Tuple<Integer, Integer> drawArea1, drawArea2;
	private MapState mapState;
	private boolean showStations;

	private final TransportMode transportMode;
	private final OnDrawCorners onDrawCorners;
	private final Runnable onDrawCornersMouseRelease;
	private final Consumer<Long> onClickAddPlatformToRoute;
	private final Consumer<SavedRailBase> onClickEditSavedRail;
	private final BiFunction<Double, Double, Boolean> isRestrictedMouseArea;
	private final ClientLevel world;
	private final LocalPlayer player;
	private final Font textRenderer;

	private boolean isAnimating;
	private double animFromScale, animToScale;
	private long animStartMillis;
	private double anchorScreenX, anchorScreenY;
	private double anchorWorldX, anchorWorldY;

	private static final int ARGB_BLUE = 0xFF4285F4;
	private static final int SCALE_UPPER_LIMIT = 64;
	private static final double SCALE_LOWER_LIMIT = 1 / 128D;
	private static final int ZOOM_ANIMATION_DURATION = 250;

	private static final double OUTLINE_BASE_WORLD_WIDTH = 0.9;
	private static final double OUTLINE_MIN_SCREEN_WIDTH = 0.6;
	private static final double OUTLINE_MAX_SCREEN_WIDTH = 2.5;
	private static final double OUTLINE_MAX_AREA_RATIO = 0.4;

	public WidgetMap(TransportMode transportMode, OnDrawCorners onDrawCorners, Runnable onDrawCornersMouseRelease, Consumer<Long> onClickAddPlatformToRoute, Consumer<SavedRailBase> onClickEditSavedRail, BiFunction<Double, Double, Boolean> isRestrictedMouseArea) {
		this.transportMode = transportMode;
		this.onDrawCorners = onDrawCorners;
		this.onDrawCornersMouseRelease = onDrawCornersMouseRelease;
		this.onClickAddPlatformToRoute = onClickAddPlatformToRoute;
		this.onClickEditSavedRail = onClickEditSavedRail;
		this.isRestrictedMouseArea = isRestrictedMouseArea;

		final Minecraft minecraftClient = Minecraft.getInstance();
		world = minecraftClient.level;
		player = minecraftClient.player;
		textRenderer = minecraftClient.font;
		if (player == null) {
			centerX = 0;
			centerY = 0;
		} else {
			centerX = player.getX();
			centerY = player.getZ();
		}
		scale = 1;
		isAnimating = false;
		setShowStations(true);
	}

	public void render(PoseStack poseStack, int mouseX, int mouseY, float delta) {
		render(new GuiGraphics(poseStack), mouseX, mouseY, delta);
	}

	public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float delta) {
		if (isAnimating) {
			final long elapsed = System.currentTimeMillis() - animStartMillis;
			final double progress = Mth.clamp((double) elapsed / ZOOM_ANIMATION_DURATION, 0.0, 1.0);
			final double easedProgress = 1.0 - Math.pow(1.0 - progress, 3);
			scale = animFromScale + (animToScale - animFromScale) * easedProgress;
			centerX = anchorWorldX - (anchorScreenX - width / 2.0) / scale;
			centerY = anchorWorldY - (anchorScreenY - height / 2.0) / scale;
			if (progress >= 1.0) {
				scale = animToScale;
				isAnimating = false;
				centerX = anchorWorldX - (anchorScreenX - width / 2.0) / scale;
				centerY = anchorWorldY - (anchorScreenY - height / 2.0) / scale;
			}
		}

		final Tesselator tesselator = Tesselator.getInstance();
		final BufferBuilder buffer = tesselator.getBuilder();
		UtilitiesClient.beginDrawingRectangle(buffer);
		RenderSystem.enableBlend();

		final Tuple<Integer, Integer> topLeft = coordsToWorldPos(0, 0);
		final Tuple<Integer, Integer> bottomRight = coordsToWorldPos(width, height);
		final int increment = scale >= 1 ? 1 : (int) Math.ceil(1 / scale);
		for (int i = topLeft.getA(); i <= bottomRight.getA(); i += increment) {
			for (int j = topLeft.getB(); j <= bottomRight.getB(); j += increment) {
				if (world != null) {
					final int color = divideColorRGB(world.getBlockState(RailwayData.newBlockPos(i, world.getHeight(Heightmap.Types.MOTION_BLOCKING, i, j) - 1, j)).getMaterial().getColor().col, 2);
					drawRectangleFromWorldCoords(buffer, i, j, i + increment, j + increment, ARGB_BLACK | color);
				}
			}
		}

		final Tuple<Double, Double> mouseWorldPos = coordsToWorldPos((double) mouseX - x, mouseY - y);

		try {
			if (showStations) {
				ClientData.DATA_CACHE.getPosToPlatforms(transportMode).forEach((platformPos, platforms) -> drawRectangleFromWorldCoords(buffer, platformPos.getX(), platformPos.getZ(), platformPos.getX() + 1, platformPos.getZ() + 1, ARGB_WHITE));
				for (final Station station : ClientData.STATIONS) {
					if (AreaBase.nonNullCorners(station)) {
						drawRectangleFromWorldCoords(buffer, station.corner1, station.corner2, ARGB_BLACK_MORE_TRANSLUCENT);
						drawOutlineFromWorldCoords(buffer, station.corner1, station.corner2, 0xFF000000 | station.color);
					}
				}
				mouseOnSavedRail(mouseWorldPos, (savedRail, x1, z1, x2, z2) -> drawRectangleFromWorldCoords(buffer, x1, z1, x2, z2, ARGB_WHITE), true);
			} else {
				ClientData.DATA_CACHE.getPosToSidings(transportMode).forEach((sidingPos, sidings) -> drawRectangleFromWorldCoords(buffer, sidingPos.getX(), sidingPos.getZ(), sidingPos.getX() + 1, sidingPos.getZ() + 1, ARGB_WHITE));
				for (final Depot depot : ClientData.DEPOTS) {
					if (depot.isTransportMode(transportMode) && AreaBase.nonNullCorners(depot)) {
						drawRectangleFromWorldCoords(buffer, depot.corner1, depot.corner2, ARGB_BLACK_MORE_TRANSLUCENT);
						drawOutlineFromWorldCoords(buffer, depot.corner1, depot.corner2, 0xFF000000 | depot.color);
					}
				}
				mouseOnSavedRail(mouseWorldPos, (savedRail, x1, z1, x2, z2) -> drawRectangleFromWorldCoords(buffer, x1, z1, x2, z2, ARGB_WHITE), false);
			}
		} catch (Exception e) {
			e.printStackTrace();
		}

		if (mapState == MapState.EDITING_AREA && drawArea1 != null && drawArea2 != null) {
			drawRectangleFromWorldCoords(buffer, drawArea1, drawArea2, ARGB_WHITE_TRANSLUCENT);
		}

		if (player != null) {
			drawFromWorldCoords(player.getX(), player.getZ(), (x1, y1) -> {
				drawRectangle(buffer, x1 - 2, y1 - 3, x1 + 2, y1 + 3, ARGB_WHITE);
				drawRectangle(buffer, x1 - 3, y1 - 2, x1 + 3, y1 + 2, ARGB_WHITE);
				drawRectangle(buffer, x1 - 2, y1 - 2, x1 + 2, y1 + 2, ARGB_BLUE);
			});
		}

		tesselator.end();
		RenderSystem.disableBlend();
		UtilitiesClient.finishDrawingRectangle();

		if (mapState == MapState.EDITING_AREA) {
			guiGraphics.drawString(textRenderer, Text.translatable("gui.mtr.edit_area").getString(), x + TEXT_PADDING, y + TEXT_PADDING, ARGB_WHITE);
		} else if (mapState == MapState.EDITING_ROUTE) {
			guiGraphics.drawString(textRenderer, Text.translatable("gui.mtr.edit_route").getString(), x + TEXT_PADDING, y + TEXT_PADDING, ARGB_WHITE);
		}

		if (scale >= 8) {
			try {
				if (showStations) {
					ClientData.DATA_CACHE.getPosToPlatforms(transportMode).forEach((platformPos, platforms) -> drawSavedRail(guiGraphics, platformPos, platforms));
				} else {
					ClientData.DATA_CACHE.getPosToSidings(transportMode).forEach((sidingPos, sidings) -> drawSavedRail(guiGraphics, sidingPos, sidings));
				}
			} catch (Exception e) {
				e.printStackTrace();
			}
		}

		final MultiBufferSource.BufferSource immediate = MultiBufferSource.immediate(Tesselator.getInstance().getBuilder());
		if (showStations) {
			for (final Station station : ClientData.STATIONS) {
				if (canDrawAreaText(station)) {
					final BlockPos pos = station.getCenter();
					final String stationString = String.format("%s|(%s)", station.name, Text.translatable("gui.mtr.zone_number", station.zone).getString());
					drawFromWorldCoords(pos.getX(), pos.getZ(), (x1, y1) -> IDrawing.drawStringWithFont(guiGraphics.pose(), textRenderer, immediate, stationString, x + x1.floatValue(), y + y1.floatValue(), MAX_LIGHT_GLOWING));
				}
			}
		} else {
			for (final Depot depot : ClientData.DEPOTS) {
				if (canDrawAreaText(depot)) {
					final BlockPos pos = depot.getCenter();
					drawFromWorldCoords(pos.getX(), pos.getZ(), (x1, y1) -> IDrawing.drawStringWithFont(guiGraphics.pose(), textRenderer, immediate, depot.name, x + x1.floatValue(), y + y1.floatValue(), MAX_LIGHT_GLOWING));
				}
			}
		}
		immediate.endBatch();

		// Right aligned, but stopped one button width short of the map edge: the zoom buttons are
		// stacked in that right hand column starting just below this line, and the readout would
		// otherwise run underneath them.
		final String mousePosText = String.format("(%s, %s)", RailwayData.round(mouseWorldPos.getA(), 1), RailwayData.round(mouseWorldPos.getB(), 1));
		guiGraphics.drawString(textRenderer, mousePosText, x + width - TEXT_PADDING - SQUARE_SIZE - TEXT_PADDING - textRenderer.width(mousePosText), y + TEXT_PADDING, ARGB_WHITE);
	}

	@Override
	public boolean mouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
		if (mapState == MapState.EDITING_AREA) {
			drawArea2 = coordsToWorldPos((int) Math.round(mouseX - x), (int) Math.round(mouseY - y));
			if (drawArea1.getA().equals(drawArea2.getA())) {
				drawArea2 = new Tuple<>(drawArea2.getA() + 1, drawArea2.getB());
			}
			if (drawArea1.getB().equals(drawArea2.getB())) {
				drawArea2 = new Tuple<>(drawArea2.getA(), drawArea2.getB() + 1);
			}
			onDrawCorners.onDrawCorners(drawArea1, drawArea2);
		} else {
			centerX -= deltaX / scale;
			centerY -= deltaY / scale;
			isAnimating = false;
		}
		return true;
	}

	@Override
	public boolean mouseReleased(double mouseX, double mouseY, int button) {
		if (mapState == MapState.EDITING_AREA) {
			onDrawCornersMouseRelease.run();
		}
		return true;
	}

	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		if (isMouseOver(mouseX, mouseY)) {
			if (ClientData.hasPermission()) {
				if (mapState == MapState.EDITING_AREA) {
					drawArea1 = coordsToWorldPos((int) (mouseX - x), (int) (mouseY - y));
					drawArea2 = null;
				} else if (mapState == MapState.EDITING_ROUTE) {
					final Tuple<Double, Double> mouseWorldPos = coordsToWorldPos(mouseX - x, mouseY - y);
					mouseOnSavedRail(mouseWorldPos, (savedRail, x1, z1, x2, z2) -> onClickAddPlatformToRoute.accept(savedRail.id), true);
				} else {
					final Tuple<Double, Double> mouseWorldPos = coordsToWorldPos(mouseX - x, mouseY - y);
					mouseOnSavedRail(mouseWorldPos, (savedRail, x1, z1, x2, z2) -> onClickEditSavedRail.accept(savedRail), showStations);
				}
			}
			return true;
		} else {
			return false;
		}
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
		// Without this guard the map zoomed on every wheel event anywhere on the screen, including
		// over the side panel and over the reserved button strips, while clicks in those places were
		// correctly ignored - an inconsistency that made the wheel feel like it belonged to the map
		// rather than to whatever was under the pointer.
		if (!isMouseOver(mouseX, mouseY)) {
			return false;
		}

		final double newTargetScale = Mth.clamp(scale * Math.pow(2, amount), SCALE_LOWER_LIMIT, SCALE_UPPER_LIMIT);
		if (Math.abs(newTargetScale - scale) > 1e-6) {
			anchorScreenX = mouseX - x;
			anchorScreenY = mouseY - y;
			final Tuple<Double, Double> worldPos = coordsToWorldPos(anchorScreenX, anchorScreenY);
			anchorWorldX = worldPos.getA();
			anchorWorldY = worldPos.getB();
			animFromScale = scale;
			animToScale = newTargetScale;
			animStartMillis = System.currentTimeMillis();
			isAnimating = true;
		}
		return true;
	}

	@Override
	public boolean isMouseOver(double mouseX, double mouseY) {
		// Two reserved areas hold widgets the screen draws on top of the map: the bottom-right strip with
		// the operations, options and web dashboard buttons, and the top-right corner with the two zoom
		// buttons. Without this the map would claim their clicks and the widgets would look dead.
		//
		// Only the actual button columns are reserved, not the full width, so panning and area selection
		// still work across the rest of the top edge. Clicking into a reserved area is deliberately not
		// routed anywhere; WidgetMap.stopEditing and friends make that a no-op.
		final boolean overBottomButtons = mouseX >= x + width - DashboardScreen.BOTTOM_BUTTON_REGION && mouseY >= y + height - SQUARE_SIZE;
		final boolean overZoomButtons = mouseX >= x + width - SQUARE_SIZE && mouseY >= y && mouseY < y + DashboardScreen.TOP_BUTTON_REGION;
		return mouseX >= x && mouseY >= y && mouseX < x + width && mouseY < y + height && !overBottomButtons && !overZoomButtons && !isRestrictedMouseArea.apply(mouseX, mouseY);
	}

	public void setFocused(boolean focused) {
	}

	public boolean isFocused() {
		return false;
	}

	public void setPositionAndSize(int x, int y, int width, int height) {
		this.x = x;
		this.y = y;
		this.width = width;
		this.height = height;
		isAnimating = false;
	}

	public void scale(double amount) {
		scale *= Math.pow(2, amount);
		scale = Mth.clamp(scale, SCALE_LOWER_LIMIT, SCALE_UPPER_LIMIT);
		isAnimating = false;
	}

	public void find(double x1, double z1, double x2, double z2) {
		centerX = (x1 + x2) / 2;
		centerY = (z1 + z2) / 2;
		scale = Math.max(2, scale);
		isAnimating = false;
	}

	public void find(BlockPos pos) {
		centerX = pos.getX();
		centerY = pos.getZ();
		scale = Math.max(8, scale);
		isAnimating = false;
	}

	public void startEditingArea(AreaBase editingArea) {
		mapState = MapState.EDITING_AREA;
		drawArea1 = editingArea.corner1;
		drawArea2 = editingArea.corner2;
	}

	public void startEditingRoute() {
		mapState = MapState.EDITING_ROUTE;
	}

	public void stopEditing() {
		mapState = MapState.DEFAULT;
	}

	public void setShowStations(boolean showStations) {
		this.showStations = showStations;
	}

	private void mouseOnSavedRail(Tuple<Double, Double> mouseWorldPos, MouseOnSavedRailCallback mouseOnSavedRailCallback, boolean isPlatform) {
		try {
			(isPlatform ? ClientData.DATA_CACHE.getPosToPlatforms(transportMode) : ClientData.DATA_CACHE.getPosToSidings(transportMode)).forEach((savedRailPos, savedRails) -> {
				final int savedRailCount = savedRails.size();
				for (int i = 0; i < savedRailCount; i++) {
					final float left = savedRailPos.getX();
					final float right = savedRailPos.getX() + 1;
					final float top = savedRailPos.getZ() + (float) i / savedRailCount;
					final float bottom = savedRailPos.getZ() + (i + 1F) / savedRailCount;
					if (RailwayData.isBetween(mouseWorldPos.getA(), left, right) && RailwayData.isBetween(mouseWorldPos.getB(), top, bottom)) {
						mouseOnSavedRailCallback.mouseOnSavedRailCallback(savedRails.get(i), left, top, right, bottom);
					}
				}
			});
		} catch (ConcurrentModificationException ignored) {
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	private Tuple<Integer, Integer> coordsToWorldPos(int mouseX, int mouseY) {
		final Tuple<Double, Double> worldPos = coordsToWorldPos((double) mouseX, mouseY);
		return new Tuple<>((int) Math.floor(worldPos.getA()), (int) Math.floor(worldPos.getB()));
	}

	private Tuple<Double, Double> coordsToWorldPos(double mouseX, double mouseY) {
		final double left = (mouseX - width / 2D) / scale + centerX;
		final double right = (mouseY - height / 2D) / scale + centerY;
		return new Tuple<>(left, right);
	}

	private void drawFromWorldCoords(double worldX, double worldZ, BiConsumer<Double, Double> callback) {
		final double coordsX = (worldX - centerX) * scale + width / 2D;
		final double coordsY = (worldZ - centerY) * scale + height / 2D;
		if (RailwayData.isBetween(coordsX, 0, width) && RailwayData.isBetween(coordsY, 0, height)) {
			callback.accept(coordsX, coordsY);
		}
	}

	private void drawRectangleFromWorldCoords(BufferBuilder buffer, Tuple<Integer, Integer> corner1, Tuple<Integer, Integer> corner2, int color) {
		drawRectangleFromWorldCoords(buffer, corner1.getA(), corner1.getB(), corner2.getA(), corner2.getB(), color);
	}

	private void drawOutlineFromWorldCoords(BufferBuilder buffer, Tuple<Integer, Integer> corner1, Tuple<Integer, Integer> corner2, int color) {
		final double minX = Math.min(corner1.getA(), corner2.getA());
		final double maxX = Math.max(corner1.getA(), corner2.getA());
		final double minZ = Math.min(corner1.getB(), corner2.getB());
		final double maxZ = Math.max(corner1.getB(), corner2.getB());

		double screenWidth = Mth.clamp(OUTLINE_BASE_WORLD_WIDTH * scale, OUTLINE_MIN_SCREEN_WIDTH, OUTLINE_MAX_SCREEN_WIDTH);
		double outlineWidth = screenWidth / scale;

		final double maxWorldWidth = Math.min(maxX - minX, maxZ - minZ) * OUTLINE_MAX_AREA_RATIO;
		if (outlineWidth > maxWorldWidth) {
			outlineWidth = maxWorldWidth;
		}

		drawRectangleFromWorldCoords(buffer, minX, minZ, maxX, minZ + outlineWidth, color); // 上
		drawRectangleFromWorldCoords(buffer, minX, maxZ - outlineWidth, maxX, maxZ, color); // 下
		drawRectangleFromWorldCoords(buffer, minX, minZ, minX + outlineWidth, maxZ, color); // 左
		drawRectangleFromWorldCoords(buffer, maxX - outlineWidth, minZ, maxX, maxZ, color); // 右
	}

	private void drawRectangleFromWorldCoords(BufferBuilder buffer, double posX1, double posZ1, double posX2, double posZ2, int color) {
		final double x1 = (posX1 - centerX) * scale + width / 2D;
		final double z1 = (posZ1 - centerY) * scale + height / 2D;
		final double x2 = (posX2 - centerX) * scale + width / 2D;
		final double z2 = (posZ2 - centerY) * scale + height / 2D;
		drawRectangle(buffer, x1, z1, x2, z2, color);
	}

	private void drawRectangle(BufferBuilder buffer, double xA, double yA, double xB, double yB, int color) {
		final double x1 = Math.min(xA, xB);
		final double y1 = Math.min(yA, yB);
		final double x2 = Math.max(xA, xB);
		final double y2 = Math.max(yA, yB);
		if (x1 < width && y1 < height && x2 >= 0 && y2 >= 0) {
			IDrawing.drawRectangle(buffer, x + Math.max(0, x1), y + y1, x + x2, y + y2, color);
		}
	}

	private boolean canDrawAreaText(AreaBase areaBase) {
		return areaBase.getCenter() != null && scale >= 80F / Math.max(Math.abs(areaBase.corner1.getA() - areaBase.corner2.getA()), Math.abs(areaBase.corner1.getB() - areaBase.corner2.getB()));
	}

	private void drawSavedRail(GuiGraphics guiGraphics, BlockPos savedRailPos, List<? extends SavedRailBase> savedRails) {
		final int savedRailCount = savedRails.size();
		for (int i = 0; i < savedRailCount; i++) {
			final int index = i;
			drawFromWorldCoords(savedRailPos.getX() + 0.5, savedRailPos.getZ() + (i + 0.5) / savedRailCount, (x1, y1) -> guiGraphics.drawCenteredString(textRenderer, savedRails.get(index).name, x + x1.intValue(), y + y1.intValue() - TEXT_HEIGHT / 2, ARGB_WHITE));
		}
	}

	private static int divideColorRGB(int color, int amount) {
		final int r = ((color >> 16) & 0xFF) / amount;
		final int g = ((color >> 8) & 0xFF) / amount;
		final int b = (color & 0xFF) / amount;
		return (r << 16) + (g << 8) + b;
	}

	@FunctionalInterface
	public interface OnDrawCorners {
		void onDrawCorners(Tuple<Integer, Integer> corner1, Tuple<Integer, Integer> corner2);
	}

	@FunctionalInterface
	private interface MouseOnSavedRailCallback {
		void mouseOnSavedRailCallback(SavedRailBase savedRail, double x1, double z1, double x2, double z2);
	}

	private enum MapState {DEFAULT, EDITING_AREA, EDITING_ROUTE}
}
