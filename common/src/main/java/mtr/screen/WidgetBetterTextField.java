package mtr.screen;

import mtr.data.IGui;
import mtr.data.RailwayData;
import mtr.mappings.Text;
import mtr.mappings.UtilitiesClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;

import java.util.Locale;
import java.util.function.Consumer;

public class WidgetBetterTextField extends EditBox implements IGui {

	private final String filter;
	private final String suggestion;
	private final int newMaxLength;

	private static final int DEFAULT_MAX_LENGTH = 128;

	public WidgetBetterTextField(String suggestion) {
		this("", suggestion, DEFAULT_MAX_LENGTH);
	}

	public WidgetBetterTextField(String suggestion, int maxLength) {
		this("", suggestion, maxLength);
	}

	public WidgetBetterTextField(TextFieldFilter textFieldFilter, String suggestion, int maxLength) {
		this(textFieldFilter.filter, suggestion, maxLength);
	}

	public WidgetBetterTextField(String filter, String suggestion, int maxLength) {
		super(Minecraft.getInstance().font, 0, 0, 0, SQUARE_SIZE, Text.literal(""));
		this.filter = filter;
		this.suggestion = suggestion;
		newMaxLength = maxLength;
		setResponder(text -> {
		});
		setMaxLength(0);
	}

	@Override
	public void setResponder(Consumer<String> changedListener) {
		super.setResponder(text -> {
			String newText;
			if (filter.isEmpty()) {
				newText = trySetLength(text);
			} else {
				newText = trySetLength(text.toUpperCase(Locale.ENGLISH).replaceAll(filter, ""));
				if (!newText.equals(text)) {
					setValue(newText);
				}
			}
			if (filter.equals(TextFieldFilter.POSITIVE_FLOAT.filter)) {
				if (newText.chars().filter(ch -> ch == '.').count() > 1) {
					newText = newText.replaceAll("\\.(?=.*\\.)", "");
				}
				if (newText.startsWith(".")) {
					newText = "0" + newText;
				}
				if (!newText.equals(getValue())) {
					setValue(newText);
				}
			}
			setSuggestion(newText.isEmpty() && suggestion != null ? suggestion : "");
			changedListener.accept(newText);
		});
	}

	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		if (isVisible() && RailwayData.isBetween(mouseX, UtilitiesClient.getWidgetX(this), UtilitiesClient.getWidgetX(this) + width) && RailwayData.isBetween(mouseY, UtilitiesClient.getWidgetY(this), UtilitiesClient.getWidgetY(this) + height)) {
			if (button == 1) {
				setValue("");
			}
			return super.mouseClicked(mouseX, mouseY, 0);
		} else {
			setFocused(false);
			return false;
		}
	}

	@Override
	public void setMaxLength(int maxLength) {
		super.setMaxLength(Integer.MAX_VALUE);
	}

	/**
	 * Gives the field a height shorter than the widget default.
	 * <p>
	 * {@code AbstractWidget} offers no {@code setHeight} in this version, so the protected field is assigned
	 * directly. {@code EditBox} reads exactly this field for its frame, its text baseline and its hit test, so
	 * the field keeps behaving normally, it is just shorter - which is what lets two of them share one row
	 * while keeping a label line above each.
	 */
	public void setFieldHeight(int height) {
		this.height = height;
	}

	private String trySetLength(String text) {
		return text.isEmpty() ? "" : text.substring(0, Math.min(newMaxLength, text.length()));
	}

	public enum TextFieldFilter {
		POSITIVE_INTEGER("\\D"), INTEGER("[^-\\d]"), HEX("[^\\dA-F]"), LETTER("[^A-Z]"), POSITIVE_FLOAT("[^\\d.]");

		private final String filter;

		TextFieldFilter(String filter) {
			this.filter = filter;
		}
	}
}
