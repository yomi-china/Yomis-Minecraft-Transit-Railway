package mtr.screen;

import mtr.client.IDrawing;
import mtr.data.IGui;
import mtr.data.Route;
import mtr.data.SavedRailBase;
import mtr.mappings.ScreenMapper;
import mtr.mappings.Text;
import mtr.mappings.UtilitiesClient;
import net.minecraft.Util;
import mtr.mappings.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;

public class RoutePlatformScreen extends ScreenMapper implements IGui {

    private final Route.RoutePlatform routePlatform;
	private final String routeName;
	private final String stationName;
	private final String platformName;
	private final int routeColor;
	private final Runnable onSave;
	private final Screen parentScreen;

	private WidgetBetterTextField textFieldCustomDestination;
	private WidgetBetterCheckbox buttonStopWithoutOpeningDoors;
	private WidgetBetterCheckbox buttonCustomDwellTime;
	private WidgetBetterTextField textFieldDwellTimeMin;
	private WidgetBetterTextField textFieldDwellTimeSec;
	private WidgetBetterCheckbox buttonCustomAdcTime;
	private WidgetBetterTextField textFieldAdcTimeMin;
	private WidgetBetterTextField textFieldAdcTimeSec;

    private long startTime;

	private static final int PAD = 14;
	private static final int HEADER_H = 40;
	private static final int ROW_H = 20;
	private static final int GAP = 6;

	public RoutePlatformScreen(Route route, int platformIndex, String routeName, String stationName,
	                           String platformName, int routeColor, Runnable onSave, Screen parentScreen) {
		super(Text.literal(""));
        this.routePlatform = route.platformIds.get(platformIndex);
		this.routeName = routeName;
		this.stationName = stationName;
		this.platformName = platformName;
		this.routeColor = routeColor;
		this.onSave = onSave;
		this.parentScreen = parentScreen;
	}

	@Override
	protected void init() {
		super.init();
		startTime = Util.getMillis();

		final int contentX = PAD;
		final int contentW = width - PAD * 2;
		int y = HEADER_H + 22;

		textFieldCustomDestination = new WidgetBetterTextField(
				Text.translatable("gui.mtr.custom_destination_suggestion").getString());
		textFieldCustomDestination.setValue(routePlatform.customDestination);
		IDrawing.setPositionAndWidth(textFieldCustomDestination, contentX, y, contentW);
		y += ROW_H + GAP;

		buttonStopWithoutOpeningDoors = new WidgetBetterCheckbox(0, 0, 0, ROW_H,
				Text.translatable("gui.mtr.stop_without_opening_doors"), ignored -> {});
		buttonStopWithoutOpeningDoors.setChecked(routePlatform.stopWithoutOpeningDoors);
		IDrawing.setPositionAndWidth(buttonStopWithoutOpeningDoors, contentX, y, contentW);
		y += ROW_H + GAP;

		buttonCustomDwellTime = new WidgetBetterCheckbox(0, 0, 0, ROW_H,
				Text.translatable("gui.mtr.custom_dwell_time"), ignored -> toggleDwellFields());
		buttonCustomDwellTime.setChecked(routePlatform.customDwellTime);
		IDrawing.setPositionAndWidth(buttonCustomDwellTime, contentX, y, contentW);
		y += ROW_H + GAP;

		final int dwellMin = routePlatform.dwellTime / 120;
		final int dwellSec = (routePlatform.dwellTime / 2) % 60;
		final int halfW = (contentW - PAD) / 2;
		textFieldDwellTimeMin = new WidgetBetterTextField("min");
		textFieldDwellTimeSec = new WidgetBetterTextField("sec");
		textFieldDwellTimeMin.setValue(String.valueOf(dwellMin));
		textFieldDwellTimeSec.setValue(String.valueOf(dwellSec));
		IDrawing.setPositionAndWidth(textFieldDwellTimeMin, contentX, y, halfW);
		IDrawing.setPositionAndWidth(textFieldDwellTimeSec, contentX + PAD + halfW, y, halfW);
		y += ROW_H + GAP;

		buttonCustomAdcTime = new WidgetBetterCheckbox(0, 0, 0, ROW_H,
				Text.translatable("gui.mtr.custom_adc_time"), ignored -> toggleAdcFields());
		buttonCustomAdcTime.setChecked(routePlatform.customAdcTime);
		IDrawing.setPositionAndWidth(buttonCustomAdcTime, contentX, y, contentW);
		y += ROW_H + GAP;

		final int adcMin = routePlatform.adcTime / 120;
		final int adcSec = (routePlatform.adcTime / 2) % 60;
		textFieldAdcTimeMin = new WidgetBetterTextField("min");
		textFieldAdcTimeSec = new WidgetBetterTextField("sec");
		textFieldAdcTimeMin.setValue(String.valueOf(adcMin));
		textFieldAdcTimeSec.setValue(String.valueOf(adcSec));
		IDrawing.setPositionAndWidth(textFieldAdcTimeMin, contentX, y, halfW);
		IDrawing.setPositionAndWidth(textFieldAdcTimeSec, contentX + PAD + halfW, y, halfW);
		y += ROW_H + GAP;

		y += 8;
		final int btnW = (contentW - PAD) / 2;
        Button buttonCancel = UtilitiesClient.newButton(Text.translatable("gui.cancel"), button -> onClose());
        Button buttonDone = UtilitiesClient.newButton(Text.translatable("gui.done"), button -> onDone());
		IDrawing.setPositionAndWidth(buttonCancel, contentX, y, btnW);
		IDrawing.setPositionAndWidth(buttonDone, contentX + PAD + btnW, y, btnW);

		toggleDwellFields();
		toggleAdcFields();

		addDrawableChild(textFieldCustomDestination);
		addDrawableChild(buttonStopWithoutOpeningDoors);
		addDrawableChild(buttonCustomDwellTime);
		addDrawableChild(textFieldDwellTimeMin);
		addDrawableChild(textFieldDwellTimeSec);
		addDrawableChild(buttonCustomAdcTime);
		addDrawableChild(textFieldAdcTimeMin);
		addDrawableChild(textFieldAdcTimeSec);
		addDrawableChild(buttonDone);
		addDrawableChild(buttonCancel);
	}

	@Override
	public void tick() {
		textFieldCustomDestination.tick();
		textFieldDwellTimeMin.tick();
		textFieldDwellTimeSec.tick();
		textFieldAdcTimeMin.tick();
		textFieldAdcTimeSec.tick();
	}

	@Override
	public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float delta) {
		guiGraphics.fill(0, 0, width, height, 0xE6101010);

		final float elapsed = (Util.getMillis() - startTime) / 1000f;
		final float animT = Math.min(1, elapsed / 0.3f);
		final float eased = easeOutCubic(animT);
		final int headW = (int) (eased * width);

		guiGraphics.fill(0, 0, headW, HEADER_H, routeColor | 0xFF000000);

		final int textColor = isColorLight(routeColor) ? 0xFF000000 : 0xFFFFFFFF;

		final int textX = PAD;
		final String label = Text.translatable("gui.mtr.route_name").getString();
		guiGraphics.drawString(font, label, textX, 6, (textColor & 0x00FFFFFF) | 0x80000000);
		guiGraphics.drawString(font, routeName, textX, 22, textColor);

		final String info = stationName + "  >  " + platformName;
		guiGraphics.drawCenteredString(font, info, width / 2, HEADER_H + 8, 0xFFAAAAAA);

		final int sepY = height - 36;
		guiGraphics.fill(PAD, sepY, width - PAD, sepY + 1, 0x25FFFFFF);

		super.render(guiGraphics, mouseX, mouseY, delta);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void onClose() {
		if (minecraft != null) {
			minecraft.setScreen(parentScreen);
		}
	}

	private void onDone() {
		routePlatform.customDestination = textFieldCustomDestination.getValue();
		routePlatform.stopWithoutOpeningDoors = buttonStopWithoutOpeningDoors.selected();
		routePlatform.customDwellTime = buttonCustomDwellTime.selected();
		routePlatform.customAdcTime = buttonCustomAdcTime.selected();

		if (routePlatform.customDwellTime) {
			routePlatform.dwellTime = parseTime(textFieldDwellTimeMin, textFieldDwellTimeSec,
					SavedRailBase.DEFAULT_DWELL_TIME);
		}
		if (routePlatform.customAdcTime) {
			routePlatform.adcTime = parseTime(textFieldAdcTimeMin, textFieldAdcTimeSec, 0);
		}

		onSave.run();
		if (minecraft != null) {
			minecraft.setScreen(parentScreen);
		}
	}

	private void toggleDwellFields() {
		final boolean show = buttonCustomDwellTime.selected();
		textFieldDwellTimeMin.visible = show;
		textFieldDwellTimeSec.visible = show;
	}

	private void toggleAdcFields() {
		final boolean show = buttonCustomAdcTime.selected();
		textFieldAdcTimeMin.visible = show;
		textFieldAdcTimeSec.visible = show;
	}

	private int parseTime(WidgetBetterTextField minField, WidgetBetterTextField secField, int defaultVal) {
		final String minStr = minField.getValue().replaceAll("[^0-9]", "");
		final String secStr = secField.getValue().replaceAll("[^0-9]", "");
		final int minutes = Math.max(0, Math.min(minStr.isEmpty() ? 0 : Integer.parseInt(minStr), 10));
		final int seconds = Math.max(0, Math.min(secStr.isEmpty() ? 0 : Integer.parseInt(secStr), 59));
		int result = (minutes * 60 + seconds) * 2;
		if (result <= 0 || result > SavedRailBase.MAX_DWELL_TIME) {
			result = defaultVal;
		}
		minField.setValue(String.valueOf(result / 120));
		secField.setValue(String.valueOf((result / 2) % 60));
		return result;
	}

	private static float easeOutCubic(float t) {
		return 1 - (1 - t) * (1 - t) * (1 - t);
	}

	private static boolean isColorLight(int argb) {
		final int r = (argb >> 16) & 0xFF;
		final int g = (argb >> 8) & 0xFF;
		final int b = argb & 0xFF;
		return (0.299 * r + 0.587 * g + 0.114 * b) > 140;
	}
}