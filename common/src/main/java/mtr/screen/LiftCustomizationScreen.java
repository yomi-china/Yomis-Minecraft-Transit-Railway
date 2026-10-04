package mtr.screen;

import mtr.client.IDrawing;
import mtr.data.IGui;
import mtr.data.Lift;
import mtr.data.LiftClient;
import mtr.mappings.ScreenMapper;
import mtr.mappings.Text;
import mtr.mappings.UtilitiesClient;
import mtr.packet.IPacket;
import mtr.packet.PacketTrainDataGuiClient;
import net.minecraft.client.Minecraft;
import mtr.mappings.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

import java.util.Locale;

public class LiftCustomizationScreen extends ScreenMapper implements IGui, IPacket {

	private final LiftClient lift;
	private final Button buttonHeightMinus;
	private final Button buttonHeightAdd;
	private final Button buttonWidthMinus;
	private final Button buttonWidthAdd;
	private final Button buttonDepthMinus;
	private final Button buttonDepthAdd;
	private final Button buttonOffsetXMinus;
	private final Button buttonOffsetXAdd;
	private final Button buttonOffsetYMinus;
	private final Button buttonOffsetYAdd;
	private final Button buttonOffsetZMinus;
	private final Button buttonOffsetZAdd;
	private final WidgetBetterCheckbox buttonIsDoubleSided;
	private final Button buttonLiftStyle;
	private final Button buttonRotateAnticlockwise;
	private final Button buttonRotateClockwise;
	private final Button buttonAccelerationMinus;
	private final Button buttonAccelerationAdd;
	private final Button buttonMaxSpeedMinus;
	private final Button buttonMaxSpeedAdd;
	private final Button buttonDisplayColor;

	private final int totalWidth;
	private final int leftColumnX;
	private final int rightColumnX;
	private final int columnWidth;

	private static final int MIN_DIMENSION = 2;
	private static final int MAX_DIMENSION = 20;
	private static final int MAX_OFFSET = 20;
	private static final float MIN_ACCELERATION = 0.001F;
	private static final float MAX_ACCELERATION = 0.05F;
	private static final float MIN_SPEED = 0.1F;
	private static final float MAX_SPEED = 1.0F;
	private static final float ACCELERATION_STEP = 0.001F;
	private static final float SPEED_STEP = 0.05F;

	private static final int BACKGROUND_COLOR = 0xC8505050;
	private static final int COLUMN_SPACING = 20;

	public LiftCustomizationScreen(LiftClient lift) {
		super(Text.literal(""));
		this.lift = lift;

		buttonHeightMinus = UtilitiesClient.newButton(Text.literal("-"), button -> {
			lift.liftHeight = Math.max(MIN_DIMENSION * 2, lift.liftHeight - 1);
			updateControls();
		});
		buttonHeightAdd = UtilitiesClient.newButton(Text.literal("+"), button -> {
			lift.liftHeight = Math.min(MAX_DIMENSION * 2, lift.liftHeight + 1);
			updateControls();
		});
		buttonWidthMinus = UtilitiesClient.newButton(Text.literal("-"), button -> {
			lift.liftWidth = Math.max(MIN_DIMENSION, lift.liftWidth - 1);
			updateControls();
		});
		buttonWidthAdd = UtilitiesClient.newButton(Text.literal("+"), button -> {
			lift.liftWidth = Math.min(MAX_DIMENSION, lift.liftWidth + 1);
			updateControls();
		});
		buttonDepthMinus = UtilitiesClient.newButton(Text.literal("-"), button -> {
			lift.liftDepth = Math.max(MIN_DIMENSION, lift.liftDepth - 1);
			updateControls();
		});
		buttonDepthAdd = UtilitiesClient.newButton(Text.literal("+"), button -> {
			lift.liftDepth = Math.min(MAX_DIMENSION, lift.liftDepth + 1);
			updateControls();
		});
		buttonOffsetXMinus = UtilitiesClient.newButton(Text.literal("-"), button -> {
			lift.liftOffsetX = Math.max(-MAX_OFFSET * 2, lift.liftOffsetX - 1);
			updateControls();
		});
		buttonOffsetXAdd = UtilitiesClient.newButton(Text.literal("+"), button -> {
			lift.liftOffsetX = Math.min(MAX_OFFSET * 2, lift.liftOffsetX + 1);
			updateControls();
		});
		buttonOffsetYMinus = UtilitiesClient.newButton(Text.literal("-"), button -> {
			lift.liftOffsetY = Math.max(-MAX_OFFSET, lift.liftOffsetY - 1);
			updateControls();
		});
		buttonOffsetYAdd = UtilitiesClient.newButton(Text.literal("+"), button -> {
			lift.liftOffsetY = Math.min(MAX_OFFSET, lift.liftOffsetY + 1);
			updateControls();
		});
		buttonOffsetZMinus = UtilitiesClient.newButton(Text.literal("-"), button -> {
			lift.liftOffsetZ = Math.max(-MAX_OFFSET * 2, lift.liftOffsetZ - 1);
			updateControls();
		});
		buttonOffsetZAdd = UtilitiesClient.newButton(Text.literal("+"), button -> {
			lift.liftOffsetZ = Math.min(MAX_OFFSET * 2, lift.liftOffsetZ + 1);
			updateControls();
		});
		buttonAccelerationMinus = UtilitiesClient.newButton(Text.literal("-"), button -> {
			lift.acceleration = Math.max(MIN_ACCELERATION, lift.acceleration - ACCELERATION_STEP);
			updateControls();
		});
		buttonAccelerationAdd = UtilitiesClient.newButton(Text.literal("+"), button -> {
			lift.acceleration = Math.min(MAX_ACCELERATION, lift.acceleration + ACCELERATION_STEP);
			updateControls();
		});
		buttonMaxSpeedMinus = UtilitiesClient.newButton(Text.literal("-"), button -> {
			lift.maxSpeed = Math.max(MIN_SPEED, lift.maxSpeed - SPEED_STEP);
			updateControls();
		});
		buttonMaxSpeedAdd = UtilitiesClient.newButton(Text.literal("+"), button -> {
			lift.maxSpeed = Math.min(MAX_SPEED, lift.maxSpeed + SPEED_STEP);
			updateControls();
		});
		buttonDisplayColor = UtilitiesClient.newButton(Text.literal(""), button -> {
			lift.displayColor = Lift.DisplayColor.values()[(lift.displayColor.ordinal() + 1) % Lift.DisplayColor.values().length];
			updateControls();
		});
		final Component doubleSidedText = Text.translatable("gui.mtr.lift_is_double_sided");
		final Component rotateAnticlockwiseText = Text.translatable("gui.mtr.rotate_anticlockwise");
		final Component rotateClockwiseText = Text.translatable("gui.mtr.rotate_clockwise");
		buttonIsDoubleSided = new WidgetBetterCheckbox(0, 0, 0, SQUARE_SIZE, doubleSidedText, checked -> lift.isDoubleSided = checked);
		buttonLiftStyle = UtilitiesClient.newButton(button -> {
			lift.liftStyle = Lift.LiftStyle.values()[(lift.liftStyle.ordinal() + 1) % Lift.LiftStyle.values().length];
			updateControls();
		});
		buttonRotateAnticlockwise = UtilitiesClient.newButton(rotateAnticlockwiseText, button -> lift.facing = lift.facing.getCounterClockWise());
		buttonRotateClockwise = UtilitiesClient.newButton(rotateClockwiseText, button -> lift.facing = lift.facing.getClockWise());

		font = Minecraft.getInstance().font;
		int textWidth = Math.max(Math.max(SQUARE_SIZE * 3, font.width(doubleSidedText)),
				Math.max(font.width(rotateAnticlockwiseText), font.width(rotateClockwiseText))) + TEXT_PADDING * 2;

		columnWidth = textWidth + SQUARE_SIZE * 2;
		totalWidth = columnWidth * 2 + COLUMN_SPACING + TEXT_PADDING * 2;
		leftColumnX = TEXT_PADDING;
		rightColumnX = leftColumnX + columnWidth + COLUMN_SPACING;
	}

	@Override
	protected void init() {
		super.init();

		int row = 0;
		IDrawing.setPositionAndWidth(buttonHeightMinus, leftColumnX, SQUARE_SIZE * row, SQUARE_SIZE);
		IDrawing.setPositionAndWidth(buttonHeightAdd, leftColumnX + columnWidth - SQUARE_SIZE, SQUARE_SIZE * row, SQUARE_SIZE);
		row++;

		IDrawing.setPositionAndWidth(buttonWidthMinus, leftColumnX, SQUARE_SIZE * row, SQUARE_SIZE);
		IDrawing.setPositionAndWidth(buttonWidthAdd, leftColumnX + columnWidth - SQUARE_SIZE, SQUARE_SIZE * row, SQUARE_SIZE);
		row++;

		IDrawing.setPositionAndWidth(buttonDepthMinus, leftColumnX, SQUARE_SIZE * row, SQUARE_SIZE);
		IDrawing.setPositionAndWidth(buttonDepthAdd, leftColumnX + columnWidth - SQUARE_SIZE, SQUARE_SIZE * row, SQUARE_SIZE);
		row++;

		IDrawing.setPositionAndWidth(buttonOffsetXMinus, leftColumnX, SQUARE_SIZE * row, SQUARE_SIZE);
		IDrawing.setPositionAndWidth(buttonOffsetXAdd, leftColumnX + columnWidth - SQUARE_SIZE, SQUARE_SIZE * row, SQUARE_SIZE);
		row++;

		IDrawing.setPositionAndWidth(buttonOffsetYMinus, leftColumnX, SQUARE_SIZE * row, SQUARE_SIZE);
		IDrawing.setPositionAndWidth(buttonOffsetYAdd, leftColumnX + columnWidth - SQUARE_SIZE, SQUARE_SIZE * row, SQUARE_SIZE);
		row++;

		IDrawing.setPositionAndWidth(buttonOffsetZMinus, leftColumnX, SQUARE_SIZE * row, SQUARE_SIZE);
		IDrawing.setPositionAndWidth(buttonOffsetZAdd, leftColumnX + columnWidth - SQUARE_SIZE, SQUARE_SIZE * row, SQUARE_SIZE);
		row++;

		IDrawing.setPositionAndWidth(buttonIsDoubleSided, leftColumnX, SQUARE_SIZE * row, columnWidth);
		row++;

		IDrawing.setPositionAndWidth(buttonLiftStyle, leftColumnX, SQUARE_SIZE * row, columnWidth);
		row++;

		IDrawing.setPositionAndWidth(buttonRotateAnticlockwise, leftColumnX, SQUARE_SIZE * row, columnWidth);
		row++;

		IDrawing.setPositionAndWidth(buttonRotateClockwise, leftColumnX, SQUARE_SIZE * row, columnWidth);

		int rightRow = 0;
		IDrawing.setPositionAndWidth(buttonAccelerationMinus, rightColumnX, SQUARE_SIZE * rightRow, SQUARE_SIZE);
		IDrawing.setPositionAndWidth(buttonAccelerationAdd, rightColumnX + columnWidth - SQUARE_SIZE, SQUARE_SIZE * rightRow, SQUARE_SIZE);
		rightRow++;

		IDrawing.setPositionAndWidth(buttonMaxSpeedMinus, rightColumnX, SQUARE_SIZE * rightRow, SQUARE_SIZE);
		IDrawing.setPositionAndWidth(buttonMaxSpeedAdd, rightColumnX + columnWidth - SQUARE_SIZE, SQUARE_SIZE * rightRow, SQUARE_SIZE);
		rightRow++;
		IDrawing.setPositionAndWidth(buttonDisplayColor, rightColumnX, SQUARE_SIZE * rightRow, columnWidth);

		addDrawableChild(buttonHeightMinus);
		addDrawableChild(buttonHeightAdd);
		addDrawableChild(buttonWidthMinus);
		addDrawableChild(buttonWidthAdd);
		addDrawableChild(buttonDepthMinus);
		addDrawableChild(buttonDepthAdd);
		addDrawableChild(buttonOffsetXMinus);
		addDrawableChild(buttonOffsetXAdd);
		addDrawableChild(buttonOffsetYMinus);
		addDrawableChild(buttonOffsetYAdd);
		addDrawableChild(buttonOffsetZMinus);
		addDrawableChild(buttonOffsetZAdd);
		addDrawableChild(buttonAccelerationMinus);
		addDrawableChild(buttonAccelerationAdd);
		addDrawableChild(buttonMaxSpeedMinus);
		addDrawableChild(buttonMaxSpeedAdd);
		addDrawableChild(buttonDisplayColor);
		addDrawableChild(buttonIsDoubleSided);
        addDrawableChild(buttonLiftStyle);
		addDrawableChild(buttonRotateAnticlockwise);
		addDrawableChild(buttonRotateClockwise);
		updateControls();
	}

	@Override
	public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float delta) {
		try {
			guiGraphics.fill(0, 0, totalWidth, height, BACKGROUND_COLOR);
			super.render(guiGraphics, mouseX, mouseY, delta);

			int row = 0;
			guiGraphics.drawCenteredString(font, Text.translatable("tooltip.mtr.rail_action_height", lift.liftHeight / 2F),
					leftColumnX + columnWidth / 2, SQUARE_SIZE * row + TEXT_PADDING, ARGB_WHITE);
			row++;

			guiGraphics.drawCenteredString(font, Text.translatable("tooltip.mtr.rail_action_width", lift.liftWidth),
					leftColumnX + columnWidth / 2, SQUARE_SIZE * row + TEXT_PADDING, ARGB_WHITE);
			row++;

			guiGraphics.drawCenteredString(font, Text.translatable("tooltip.mtr.rail_action_depth", lift.liftDepth),
					leftColumnX + columnWidth / 2, SQUARE_SIZE * row + TEXT_PADDING, ARGB_WHITE);
			row++;

			guiGraphics.drawCenteredString(font, Text.translatable("gui.mtr.offset_x", lift.liftOffsetX / 2F),
					leftColumnX + columnWidth / 2, SQUARE_SIZE * row + TEXT_PADDING, ARGB_WHITE);
			row++;

			guiGraphics.drawCenteredString(font, Text.translatable("gui.mtr.offset_y", lift.liftOffsetY),
					leftColumnX + columnWidth / 2, SQUARE_SIZE * row + TEXT_PADDING, ARGB_WHITE);
			row++;

			guiGraphics.drawCenteredString(font, Text.translatable("gui.mtr.offset_z", lift.liftOffsetZ / 2F),
					leftColumnX + columnWidth / 2, SQUARE_SIZE * row + TEXT_PADDING, ARGB_WHITE);

			int rightRow = 0;
			guiGraphics.drawCenteredString(font, Text.translatable("gui.mtr.lift_acceleration", String.format("%.3f", lift.acceleration)),
					rightColumnX + columnWidth / 2, SQUARE_SIZE * rightRow + TEXT_PADDING, ARGB_WHITE);
			rightRow++;

			guiGraphics.drawCenteredString(font, Text.translatable("gui.mtr.lift_max_speed", String.format("%.2f", lift.maxSpeed)),
					rightColumnX + columnWidth / 2, SQUARE_SIZE * rightRow + TEXT_PADDING, ARGB_WHITE);
			rightRow++;
			guiGraphics.drawCenteredString(font, Text.translatable("gui.mtr.lift_display_color", Text.translatable("gui.mtr.lift_display_color_" + lift.displayColor.name().toLowerCase(Locale.ENGLISH))),
					rightColumnX + columnWidth / 2, SQUARE_SIZE * rightRow + TEXT_PADDING, ARGB_WHITE);
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	@Override
	public void onClose() {
		super.onClose();
		lift.setExtraData(packet -> PacketTrainDataGuiClient.sendUpdate(PACKET_UPDATE_LIFT, packet));
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	private void updateControls() {
		buttonHeightMinus.active = lift.liftHeight > MIN_DIMENSION * 2;
		buttonHeightAdd.active = lift.liftHeight < MAX_DIMENSION * 2;
		buttonWidthMinus.active = lift.liftWidth > MIN_DIMENSION;
		buttonWidthAdd.active = lift.liftWidth < MAX_DIMENSION;
		buttonDepthMinus.active = lift.liftDepth > MIN_DIMENSION;
		buttonDepthAdd.active = lift.liftDepth < MAX_DIMENSION;
		buttonOffsetXMinus.active = lift.liftOffsetX > -MAX_OFFSET * 2;
		buttonOffsetXAdd.active = lift.liftOffsetX < MAX_OFFSET * 2;
		buttonOffsetYMinus.active = lift.liftOffsetY > -MAX_OFFSET;
		buttonOffsetYAdd.active = lift.liftOffsetY < MAX_OFFSET;
		buttonOffsetZMinus.active = lift.liftOffsetZ > -MAX_OFFSET * 2;
		buttonOffsetZAdd.active = lift.liftOffsetZ < MAX_OFFSET * 2;
		buttonAccelerationMinus.active = lift.acceleration > MIN_ACCELERATION;
		buttonAccelerationAdd.active = lift.acceleration < MAX_ACCELERATION;
		buttonMaxSpeedMinus.active = lift.maxSpeed > MIN_SPEED;
		buttonMaxSpeedAdd.active = lift.maxSpeed < MAX_SPEED;
		buttonIsDoubleSided.setChecked(lift.isDoubleSided);
		buttonLiftStyle.setMessage(Text.translatable("gui.mtr.lift_style", Text.translatable("gui.mtr.lift_style_" + lift.liftStyle.toString().toLowerCase(Locale.ENGLISH))));
		buttonDisplayColor.setMessage(Text.translatable("gui.mtr.lift_display_color", Text.translatable("gui.mtr.lift_display_color_" + lift.displayColor.name().toLowerCase(Locale.ENGLISH))));
	}
}