package mtr.screen;

import mtr.data.Platform;
import mtr.data.TransportMode;
import mtr.mappings.ButtonMapper;
import mtr.mappings.Text;
import mtr.mappings.UtilitiesClient;
import mtr.packet.PacketTrainDataGuiClient;
import mtr.mappings.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

public class PlatformScreen extends SavedRailScreenBase<Platform> {

	private static final Component DWELL_TIME_TEXT = Text.translatable("gui.mtr.dwell_time");
	private static final Component ADC_TIME_TEXT = Text.translatable("gui.mtr.adc_time");
	private static final Component PSD_DISPLAY_MODE_TEXT = Text.translatable("gui.mtr.psd_display_mode");

	private final WidgetShorterSlider sliderAdcTimeMin;
	private final WidgetShorterSlider sliderAdcTimeSec;
	private final ButtonMapper buttonPsdDisplayMode;

	private int psdDisplayModeTemp;

	public PlatformScreen(Platform savedRailBase, TransportMode transportMode, DashboardScreen dashboardScreen) {
		super(savedRailBase, transportMode, dashboardScreen, DWELL_TIME_TEXT, ADC_TIME_TEXT);
		sliderAdcTimeMin = new WidgetShorterSlider(0, 0, (int) Math.floor(Platform.MAX_ADC_TIME / 2F / SECONDS_PER_MINUTE), value -> Text.translatable("gui.mtr.arrival_min", value).getString(), null);
		sliderAdcTimeSec = new WidgetShorterSlider(0, 0, SECONDS_PER_MINUTE * 2 - 1, 10, 2, value -> Text.translatable("gui.mtr.arrival_sec", value / 2F).getString(), null);
		buttonPsdDisplayMode = new ButtonMapper(0, 0, 0, SQUARE_SIZE / 2, Text.literal(""), button -> {
			psdDisplayModeTemp = (psdDisplayModeTemp + 1) % 3;
			button.setMessage(Text.translatable("gui.mtr.psd_display_mode_" + psdDisplayModeTemp));
		}) {};
	}

	@Override
	protected void init() {
		super.init();

		final int sliderTextWidth = Math.max(font.width(Text.translatable("gui.mtr.arrival_min", "88")), font.width(Text.translatable("gui.mtr.arrival_sec", "88.8"))) + TEXT_PADDING;

		UtilitiesClient.setWidgetY(sliderDwellTimeMin, SQUARE_SIZE * 5 / 2 + TEXT_FIELD_PADDING);
		UtilitiesClient.setWidgetY(sliderDwellTimeSec, SQUARE_SIZE * 3 + TEXT_FIELD_PADDING);

		int yBase = SQUARE_SIZE * 4 + TEXT_FIELD_PADDING + TEXT_HEIGHT * 2;
		UtilitiesClient.setWidgetX(sliderAdcTimeMin, SQUARE_SIZE + textWidth);
		sliderAdcTimeMin.setHeight(SQUARE_SIZE / 2);
		sliderAdcTimeMin.setWidth(width - textWidth - SQUARE_SIZE * 2 - sliderTextWidth);
		UtilitiesClient.setWidgetY(sliderAdcTimeMin, yBase);
		sliderAdcTimeMin.setValue((int) Math.floor(savedRailBase.getAdcTime() / 2F / SECONDS_PER_MINUTE));

		UtilitiesClient.setWidgetX(sliderAdcTimeSec, SQUARE_SIZE + textWidth);
		sliderAdcTimeSec.setHeight(SQUARE_SIZE / 2);
		sliderAdcTimeSec.setWidth(width - textWidth - SQUARE_SIZE * 2 - sliderTextWidth);
		UtilitiesClient.setWidgetY(sliderAdcTimeSec, yBase + SQUARE_SIZE / 2);
		sliderAdcTimeSec.setValue(savedRailBase.getAdcTime() % (SECONDS_PER_MINUTE * 2));

		if (showScheduleControls) {
			addDrawableChild(sliderAdcTimeMin);
			addDrawableChild(sliderAdcTimeSec);
		}

		psdDisplayModeTemp = savedRailBase.getPsdDisplayMode();

		final int psdButtonY = SQUARE_SIZE * 7 + TEXT_FIELD_PADDING;
		UtilitiesClient.setWidgetX(buttonPsdDisplayMode, SQUARE_SIZE + textWidth);
		buttonPsdDisplayMode.setWidth(width - textWidth - SQUARE_SIZE * 2 - sliderTextWidth);
		UtilitiesClient.setWidgetY(buttonPsdDisplayMode, psdButtonY);
		buttonPsdDisplayMode.setMessage(Text.translatable("gui.mtr.psd_display_mode_" + psdDisplayModeTemp));
		addDrawableChild(buttonPsdDisplayMode);
	}

	@Override
	public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float delta) {
		super.render(guiGraphics, mouseX, mouseY, delta);
		if (showScheduleControls) {
			guiGraphics.drawString(font, DWELL_TIME_TEXT, SQUARE_SIZE, SQUARE_SIZE * 5 / 2 + TEXT_FIELD_PADDING + TEXT_PADDING, ARGB_WHITE);
			int yBase = SQUARE_SIZE * 4 + TEXT_FIELD_PADDING + TEXT_HEIGHT * 2;
			guiGraphics.drawString(font, ADC_TIME_TEXT, SQUARE_SIZE, yBase + TEXT_PADDING, ARGB_WHITE);
			guiGraphics.drawString(font, PSD_DISPLAY_MODE_TEXT, SQUARE_SIZE, SQUARE_SIZE * 7 + TEXT_FIELD_PADDING + TEXT_PADDING, ARGB_WHITE);
		}
	}

	@Override
	public void onClose() {
		final int minutesDwell = sliderDwellTimeMin.getIntValue();
		final float secondDwell = sliderDwellTimeSec.getIntValue() / 2F;
		savedRailBase.setDwellTime((int) ((secondDwell + minutesDwell * SECONDS_PER_MINUTE) * 2), packet -> PacketTrainDataGuiClient.sendUpdate(PACKET_UPDATE_PLATFORM, packet));

		final int minutesAdc = sliderAdcTimeMin.getIntValue();
		final float secondAdc = sliderAdcTimeSec.getIntValue() / 2F;
		savedRailBase.setAdcTime((int) ((secondAdc + minutesAdc * SECONDS_PER_MINUTE) * 2), packet -> PacketTrainDataGuiClient.sendUpdate(PACKET_UPDATE_PLATFORM, packet));

		if (psdDisplayModeTemp != savedRailBase.getPsdDisplayMode()) {
			savedRailBase.setPsdDisplayMode(psdDisplayModeTemp, packet -> PacketTrainDataGuiClient.sendUpdate(PACKET_UPDATE_PLATFORM, packet));
		}

		super.onClose();
	}

	@Override
	protected String getNumberStringKey() {
		return "gui.mtr.platform_number";
	}

	@Override
	protected ResourceLocation getPacketIdentifier() {
		return PACKET_UPDATE_PLATFORM;
	}
}
