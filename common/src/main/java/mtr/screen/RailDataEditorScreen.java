package mtr.screen;

import mtr.client.AnteRailCompat;
import mtr.client.IDrawing;
import mtr.mappings.ScreenMapper;
import mtr.mappings.Text;
import mtr.mappings.UtilitiesClient;
import mtr.screen.RailDataEditorClient.RailInfo;
import mtr.mappings.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.util.Mth;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class RailDataEditorScreen extends ScreenMapper {

	private static final int PANEL_WIDTH = 380;
	private static final int PANEL_MAX_HEIGHT = 300;
	private static final int PANEL_MARGIN = 8;
	private static final int HEADER_HEIGHT = 80;
	private static final int FOOTER_HEIGHT = 28;
	private static final int ROW_HEIGHT = 24;
	private static final int ROW_SPACING = 26;
	private static final float OPEN_ANIMATION_DURATION = 0.3F;
	private static final float CLOSE_ANIMATION_DURATION = 0.2F;
	private static final float SCROLL_ANIMATION_DURATION = 0.08F;
	private static final String TYPE_DELIMITER = "__type__";
	private static final String[] TYPES = {"string", "int", "float", "bool"};

	private final List<RailInfo> railInfos;
	private int railIndex = 0;
	private final List<Entry> entries = new ArrayList<>();

	private double scroll = 0;
	private double scrollTarget = 0;
	private double prevScroll = 0;
	private long scrollStartTime = -1;
	private double maxScroll = 0;

	private Button buttonPrevRail;
	private Button buttonNextRail;
	private Button buttonAdd;
	private Button buttonSave;
	private Button buttonClose;

	private final List<WidgetBetterTextField> nameFields = new ArrayList<>();
	private final List<Button> typeButtons = new ArrayList<>();
	private final List<WidgetBetterTextField> valueFields = new ArrayList<>();
	private final List<Button> deleteButtons = new ArrayList<>();

	private int panelX;
	private int panelY;
	private int panelHeight;
	private int listX;
	private int listY;
	private int listWidth;
	private int listHeight;

	private long animationStartTime = -1;
	private boolean closing = false;
	private long closeStartTime = -1;
	private boolean closed = false;

	private String hintKey;

	public static class Entry {
		public String name = "";
		public String type = "string";
		public String value = "";
	}

	public RailDataEditorScreen(List<RailInfo> railInfos) {
		super(Text.translatable("gui.mtr.rail_data_editor.title"));
		this.railInfos = railInfos;
	}

	@Override
	protected void init() {
		super.init();
		panelHeight = Math.max(160, Math.min(PANEL_MAX_HEIGHT, height - PANEL_MARGIN * 2));
		panelX = Math.max(PANEL_MARGIN, (width - PANEL_WIDTH) / 2);
		panelY = Math.max(PANEL_MARGIN, (height - panelHeight) / 2);
		listX = panelX + 8;
		listY = panelY + HEADER_HEIGHT;
		listWidth = PANEL_WIDTH - 16;
		listHeight = panelHeight - HEADER_HEIGHT - FOOTER_HEIGHT;

		buttonPrevRail = UtilitiesClient.newButton(Text.literal("◀"), button -> switchRail(-1));
		buttonNextRail = UtilitiesClient.newButton(Text.literal("▶"), button -> switchRail(1));
		buttonAdd = UtilitiesClient.newButton(Text.translatable("gui.mtr.rail_data_editor.add"), button -> addEntry());
		buttonSave = UtilitiesClient.newButton(Text.translatable("gui.mtr.rail_data_editor.save"), button -> save());
		buttonClose = UtilitiesClient.newButton(Text.translatable("gui.mtr.rail_data_editor.close"), button -> onClose());

		IDrawing.setPositionAndWidth(buttonPrevRail, panelX + 12, panelY + 52, 24);
		IDrawing.setPositionAndWidth(buttonNextRail, panelX + 42, panelY + 52, 24);
		IDrawing.setPositionAndWidth(buttonAdd, panelX + 76, panelY + 52, 96);
		IDrawing.setPositionAndWidth(buttonSave, panelX + PANEL_WIDTH - 12 - 70 - 8 - 70, panelY + 52, 70);
		IDrawing.setPositionAndWidth(buttonClose, panelX + PANEL_WIDTH - 12 - 70, panelY + 52, 70);

		addDrawableChild(buttonPrevRail);
		addDrawableChild(buttonNextRail);
		addDrawableChild(buttonAdd);
		addDrawableChild(buttonSave);
		addDrawableChild(buttonClose);
		buttonPrevRail.visible = railInfos.size() > 1;
		buttonNextRail.visible = railInfos.size() > 1;

		animationStartTime = System.currentTimeMillis();
		hintKey = "gui.mtr.rail_data_editor.hint_" + (1 + (int) (Math.random() * 3));
		loadFromRail();
	}

	@Override
	public void tick() {
		super.tick();
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
		if (mouseX >= listX && mouseX <= listX + listWidth && mouseY >= listY && mouseY <= listY + listHeight) {
			setScrollTarget(scrollTarget - amount * 10);
			return true;
		}
		return super.mouseScrolled(mouseX, mouseY, amount);
	}

	@Override
	public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float delta) {

		final boolean isClosing = closing;
		float openProgress = 1F;
		float closeProgress = 0F;

		if (isClosing) {
			final float closeElapsed = (System.currentTimeMillis() - closeStartTime) / 1000F;
			closeProgress = Math.min(1F, closeElapsed / CLOSE_ANIMATION_DURATION);
			closeProgress = closeProgress * closeProgress * closeProgress; // easeInCubic
			if (closeProgress >= 1F && !closed) {
				closed = true;
				super.onClose();
			}
		} else {
			final float elapsed = (System.currentTimeMillis() - animationStartTime) / 1000F;
			openProgress = Math.min(1F, elapsed / OPEN_ANIMATION_DURATION);
			openProgress = 1F - (1F - openProgress) * (1F - openProgress) * (1F - openProgress); // easeOutCubic
		}

		if (scrollStartTime >= 0) {
			final float scrollElapsed = (System.currentTimeMillis() - scrollStartTime) / 1000F;
			float scrollProgress = Math.min(1F, scrollElapsed / SCROLL_ANIMATION_DURATION);
			scrollProgress = 1F - (1F - scrollProgress) * (1F - scrollProgress) * (1F - scrollProgress); // easeOutCubic
			scroll = prevScroll + (scrollTarget - prevScroll) * scrollProgress;
			if (Math.abs(scroll - scrollTarget) < 0.01) {
				scroll = scrollTarget;
				scrollStartTime = -1;
			}
		} else {
			scroll = scrollTarget;
		}

		updateRowPositions();

		final float scale;
		final float offsetY;
		final int bgAlpha;
		if (isClosing) {
			scale = 1F - 0.06F * closeProgress;
			offsetY = 24F * closeProgress;
			bgAlpha = (int) (0x88 * (1F - closeProgress));
		} else {
			scale = 0.94F + 0.06F * openProgress;
			offsetY = (1F - openProgress) * 24F;
			bgAlpha = (int) (0x88 * openProgress);
		}

		guiGraphics.fill(0, 0, width, height, (bgAlpha << 24) | 0x000000);

		final float initialCenterY = panelY + panelHeight / 2F + offsetY;
		final float finalCenterY = panelY + panelHeight / 2F;

		guiGraphics.pose().pushPose();
		guiGraphics.pose().translate(width / 2F, finalCenterY, 0);
		guiGraphics.pose().scale(scale, scale, 1F);
		guiGraphics.pose().translate(-(width / 2F), -initialCenterY, 0);

		guiGraphics.fill(panelX - 1, panelY - 1, panelX + PANEL_WIDTH + 1, panelY + panelHeight + 1, 0xFF555555);
		guiGraphics.fill(panelX, panelY, panelX + PANEL_WIDTH, panelY + panelHeight, 0xF22B2B2B);

		guiGraphics.drawCenteredString(font, Text.translatable("gui.mtr.rail_data_editor.title").getString(), panelX + PANEL_WIDTH / 2, panelY + 12, 0xFFFFE082);
		final String railText = railInfos.size() > 1 ? (railIndex + 1) + "/" + railInfos.size() + "  " + railString(currentInfo()) : railString(currentInfo());
		guiGraphics.drawCenteredString(font, railText, panelX + PANEL_WIDTH / 2, panelY + 32, 0xFFB0B0B0);

		final float listX_T1 = (listX - width / 2F) * scale + width / 2F;
		final float listY_T1 = (listY - initialCenterY) * scale + finalCenterY;
		final float listX_T2 = (listX + listWidth - width / 2F) * scale + width / 2F;
		final float listY_T2 = (listY + listHeight - initialCenterY) * scale + finalCenterY;
		guiGraphics.enableScissor(
				(int) Math.floor(listX_T1),
				(int) Math.floor(listY_T1),
				(int) Math.ceil(listX_T2),
				(int) Math.ceil(listY_T2)
		);

		guiGraphics.fill(listX, listY, listX + listWidth, listY + listHeight, 0xAA1C1C1C);

		final int baseY = listY + 2;
		for (int i = 0; i < entries.size(); i++) {
			final int y = (int) Math.round(baseY + i * ROW_SPACING - scroll);
			if (y + ROW_HEIGHT > listY && y < listY + listHeight) {
				guiGraphics.fill(listX + 1, y, listX + listWidth - 1, y + ROW_HEIGHT, i % 2 == 0 ? 0x2EFFFFFF : 0x0EFFFFFF);
			}
		}

		for (int i = 0; i < entries.size(); i++) {
			final WidgetBetterTextField nameField = nameFields.get(i);
			if (nameField.visible) guiGraphics.renderWidget(nameField, mouseX, mouseY, delta);
			final Button typeButton = typeButtons.get(i);
			if (typeButton.visible) guiGraphics.renderWidget(typeButton, mouseX, mouseY, delta);
			final WidgetBetterTextField valueField = valueFields.get(i);
			if (valueField.visible) guiGraphics.renderWidget(valueField, mouseX, mouseY, delta);
			final Button deleteButton = deleteButtons.get(i);
			if (deleteButton.visible) guiGraphics.renderWidget(deleteButton, mouseX, mouseY, delta);
		}

		if (maxScroll > 0) {
			final int barHeight = Math.max(12, (int) (listHeight * listHeight / (entries.size() * ROW_SPACING + 4)));
			final int barY = listY + (int) ((listHeight - barHeight) * scroll / maxScroll);
			guiGraphics.fill(listX + listWidth - 3, barY, listX + listWidth, barY + barHeight, 0xFFAAAAAA);
		}

		guiGraphics.disableScissor();

		guiGraphics.drawString(font, Text.translatable(getHintKey()).getString(), panelX + 12, panelY + panelHeight - 12, 0xFF808080);

		guiGraphics.renderWidget(buttonPrevRail, mouseX, mouseY, delta);
		guiGraphics.renderWidget(buttonNextRail, mouseX, mouseY, delta);
		guiGraphics.renderWidget(buttonAdd, mouseX, mouseY, delta);
		guiGraphics.renderWidget(buttonSave, mouseX, mouseY, delta);
		guiGraphics.renderWidget(buttonClose, mouseX, mouseY, delta);

		guiGraphics.pose().popPose();
	}

	@Override
	public void onClose() {
		if (!closing) {
			closing = true;
			closeStartTime = System.currentTimeMillis();
		}
	}


	private void setScrollTarget(double target) {
		scrollTarget = Mth.clamp(target, 0, maxScroll);
		prevScroll = scroll;
		scrollStartTime = System.currentTimeMillis();
	}

	private RailInfo currentInfo() {
		return railInfos.get(railIndex);
	}

	private static String railString(RailInfo info) {
		return info.posStart.getX() + ", " + info.posStart.getY() + ", " + info.posStart.getZ() + "  →  " + info.posEnd.getX() + ", " + info.posEnd.getY() + ", " + info.posEnd.getZ();
	}

	private String getHintKey() {
		return hintKey;
	}

	private void switchRail(int delta) {
		if (railInfos.size() <= 1) {
			return;
		}
		saveInternal(false);
		railIndex = (railIndex + delta + railInfos.size()) % railInfos.size();
		scroll = 0;
		scrollTarget = 0;
		prevScroll = 0;
		scrollStartTime = -1;
		loadFromRail();
	}

	private void loadFromRail() {
		entries.clear();
		final RailInfo info = currentInfo();
		final Map<String, String> customConfigs = new HashMap<>(info.rail.getRailData());
		customConfigs.putAll(AnteRailCompat.getRailCustomConfigs(info.rail));
		for (Map.Entry<String, String> entry : customConfigs.entrySet()) {
			if (entry.getKey().startsWith(TYPE_DELIMITER)) {
				continue;
			}
			final Entry data = new Entry();
			data.name = entry.getKey();
			data.type = normalizeType(customConfigs.get(TYPE_DELIMITER + entry.getKey()));
			data.value = entry.getValue();
			entries.add(data);
		}
		rebuildRows();
	}

	private void addEntry() {
		final Entry entry = new Entry();
		entry.name = "data" + (entries.size() + 1);
		entries.add(entry);
		rebuildRows();
		setScrollTarget(maxScroll);
	}

	private void removeEntry(Entry entry) {
		entries.remove(entry);
		rebuildRows();
	}

	private void save() {
		saveInternal(true);
	}

	private void saveInternal(boolean close) {
		final RailInfo info = currentInfo();
		final Map<String, String> customConfigs = new HashMap<>();
		for (Entry entry : entries) {
			if (entry.name.isEmpty()) {
				continue;
			}
			customConfigs.put(entry.name, entry.value);
			customConfigs.put(TYPE_DELIMITER + entry.name, entry.type);
		}
		info.rail.setRailData(customConfigs);
		AnteRailCompat.setRailCustomConfigs(info.rail, customConfigs);
		RailDataEditorClient.sendUpdateC2S(customConfigs, info.posStart, info.posEnd);
		if (close) {
			onClose();
		}
	}

	private void rebuildRows() {
		for (WidgetBetterTextField textField : nameFields) {
			removeWidget(textField);
		}
		for (Button button : typeButtons) {
			removeWidget(button);
		}
		for (WidgetBetterTextField textField : valueFields) {
			removeWidget(textField);
		}
		for (Button button : deleteButtons) {
			removeWidget(button);
		}
		nameFields.clear();
		typeButtons.clear();
		valueFields.clear();
		deleteButtons.clear();

		for (Entry entry : entries) {
			final WidgetBetterTextField nameField = new WidgetBetterTextField(Text.translatable("gui.mtr.rail_data_editor.name").getString(), 64);
			nameField.setWidth(96);
			nameField.setValue(entry.name);
			nameField.setResponder(text -> entry.name = text);

			final Button typeButton = UtilitiesClient.newButton(Text.literal(entry.type), button -> {
				entry.type = nextType(entry.type);
				button.setMessage(Text.literal(entry.type));
			});

			final WidgetBetterTextField valueField = new WidgetBetterTextField(Text.translatable("gui.mtr.rail_data_editor.value").getString());
			valueField.setWidth(listWidth - 184 - 2);
			valueField.setValue(entry.value);
			valueField.setResponder(text -> entry.value = text);

			final Button deleteButton = UtilitiesClient.newButton(Text.literal("✕"), button -> removeEntry(entry));

			addDrawableChild(nameField);
			addDrawableChild(typeButton);
			addDrawableChild(valueField);
			addDrawableChild(deleteButton);
			nameFields.add(nameField);
			typeButtons.add(typeButton);
			valueFields.add(valueField);
			deleteButtons.add(deleteButton);
		}

		maxScroll = Math.max(0, entries.size() * ROW_SPACING - listHeight + 4);
		if (scrollTarget > maxScroll) {
			scrollTarget = maxScroll;
		}
		if (scroll > maxScroll) {
			scroll = maxScroll;
		}
		updateRowPositions();
	}

	private void updateRowPositions() {
		final int baseY = listY + 2;
		for (int i = 0; i < entries.size(); i++) {
			final int y = (int) Math.round(baseY + i * ROW_SPACING - scroll);
			final boolean visible = y + ROW_HEIGHT > listY && y < listY + listHeight;

			final WidgetBetterTextField nameField = nameFields.get(i);
			nameField.setVisible(visible);
			UtilitiesClient.setWidgetX(nameField, listX + 24);
			UtilitiesClient.setWidgetY(nameField, y);
			nameField.setWidth(96);

			final Button typeButton = typeButtons.get(i);
			typeButton.visible = visible;
			UtilitiesClient.setWidgetX(typeButton, listX + 124);
			UtilitiesClient.setWidgetY(typeButton, y);
			typeButton.setWidth(56);

			final WidgetBetterTextField valueField = valueFields.get(i);
			valueField.setVisible(visible);
			UtilitiesClient.setWidgetX(valueField, listX + 184);
			UtilitiesClient.setWidgetY(valueField, y);
			valueField.setWidth(listWidth - 184 - 2);

			final Button deleteButton = deleteButtons.get(i);
			deleteButton.visible = visible;
			UtilitiesClient.setWidgetX(deleteButton, listX + 2);
			UtilitiesClient.setWidgetY(deleteButton, y);
			deleteButton.setWidth(18);
		}
	}

	private static String nextType(String type) {
		for (int i = 0; i < TYPES.length; i++) {
			if (TYPES[i].equals(type)) {
				return TYPES[(i + 1) % TYPES.length];
			}
		}
		return TYPES[0];
	}

	private static String normalizeType(String type) {
		for (String candidate : TYPES) {
			if (candidate.equals(type)) {
				return candidate;
			}
		}
		return TYPES[0];
	}
}
