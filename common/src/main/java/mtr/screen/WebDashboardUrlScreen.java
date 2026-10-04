package mtr.screen;

import mtr.client.IDrawing;
import mtr.data.IGui;
import mtr.mappings.ScreenMapper;
import mtr.mappings.GuiGraphics;
import mtr.mappings.Text;
import mtr.mappings.UtilitiesClient;
import net.minecraft.Util;
import net.minecraft.client.gui.components.Button;

/**
 * Shown after pressing "Web Dashboard" in a dashboard screen: presents the address and lets the
 * player decide what to do with it.
 * <p>
 * Replaces the previous behaviour of launching the system browser from the click handler, which was
 * abrupt - not everyone wants a browser window to appear because they pressed a button in a game -
 * and had no way to get the address without letting it open. The address is also genuinely useful to
 * copy: a player on a different machine, or one who wants it in an existing browser profile, needs
 * the text rather than a launched window.
 * <p>
 * The signed-in link carries a one-time token in its fragment. It is handed to the clipboard and to
 * the browser, but deliberately never drawn on screen: it would otherwise end up in screenshots and
 * streams, which is exactly what keeping it out of the address bar's query string was meant to avoid.
 */
public class WebDashboardUrlScreen extends ScreenMapper implements IGui {

	/** Opens the given URL in the system browser. */
	private final String urlToOpen;
	/** Copied to the clipboard: the same address, including the sign-in fragment. */
	private final String urlToCopy;
	/**
	 * Where Cancel and the close key return to.
	 * <p>
	 * Typed as {@link ScreenMapper} rather than {@code Screen} because
	 * {@code UtilitiesClient.setScreen} only accepts a {@code ScreenMapper} - that wrapper exists to
	 * centralise screen switching across the two loaders.
	 */
	private final ScreenMapper previousScreen;

	private final Button buttonCopy;
	private final Button buttonOpen;
	private final Button buttonCancel;

	private static final int BUTTON_HEIGHT = 20;
	/** Sized to fit "Open in browser", the longest of the three labels, without the text spilling. */
	private static final int BUTTON_WIDTH = 116;
	private static final int BUTTON_GAP = 8;
	/** Guards against a nonsense width on a tiny window. */
	private static final int MIN_URL_WIDTH = 80;
	/**
	 * Enough for a sign-in address on a normal window. A signed-in link is around ninety characters,
	 * which at this font's width runs to four or five lines; wrapping too few would silently hide the
	 * end of the address.
	 */
	private static final int MAX_URL_LINES = 5;

	public WebDashboardUrlScreen(String urlToOpen, String urlToCopy, ScreenMapper previousScreen) {
		super(Text.translatable("gui.mtr.web_dashboard_dialog_title"));

		this.urlToOpen = urlToOpen;
		this.urlToCopy = urlToCopy;
		this.previousScreen = previousScreen;

		buttonCopy = UtilitiesClient.newButton(Text.translatable("gui.mtr.web_dashboard_copy_link"), button -> onCopy());
		buttonOpen = UtilitiesClient.newButton(Text.translatable("gui.mtr.web_dashboard_open_browser"), button -> onOpen());
		buttonCancel = UtilitiesClient.newButton(Text.translatable("gui.cancel"), button -> onClose());
	}

	@Override
	protected void init() {
		super.init();

		final int totalWidth = BUTTON_WIDTH * 3 + BUTTON_GAP * 2;
		final int startX = (width - totalWidth) / 2;
		// Below the wrapped address, which is drawn upwards from here.
		final int buttonY = Math.min(height - BUTTON_HEIGHT - 12, height / 2 + SQUARE_SIZE * 3);

		IDrawing.setPositionAndWidth(buttonCopy, startX, buttonY, BUTTON_WIDTH);
		IDrawing.setPositionAndWidth(buttonOpen, startX + BUTTON_WIDTH + BUTTON_GAP, buttonY, BUTTON_WIDTH);
		IDrawing.setPositionAndWidth(buttonCancel, startX + (BUTTON_WIDTH + BUTTON_GAP) * 2, buttonY, BUTTON_WIDTH);

		addDrawableChild(buttonCopy);
		addDrawableChild(buttonOpen);
		addDrawableChild(buttonCancel);
	}

	@Override
	public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float delta) {
		try {
			renderBackground(guiGraphics);

			final int wrapWidth = Math.max(MIN_URL_WIDTH, width - SQUARE_SIZE * 2);
			final String[] urlLines = wrap(urlToCopy, wrapWidth, MAX_URL_LINES);
			final int lineHeight = TEXT_HEIGHT + TEXT_PADDING;

			// The title sits above the address, which is drawn as a centred block between the title and
			// the buttons.
			final int blockTop = height / 2 - SQUARE_SIZE * 2 - urlLines.length * lineHeight;
			guiGraphics.drawCenteredString(font, title, width / 2, blockTop - lineHeight, ARGB_WHITE);
			int y = blockTop;
			for (final String line : urlLines) {
				guiGraphics.drawCenteredString(font, Text.literal(line), width / 2, y, ARGB_WHITE);
				y += lineHeight;
			}

			super.render(guiGraphics, mouseX, mouseY, delta);
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	@Override
	public void onClose() {
		if (minecraft != null) {
			UtilitiesClient.setScreen(minecraft, previousScreen);
		}
	}

	/**
	 * Copies the link, confirming on the button itself.
	 * <p>
	 * The clipboard lives on {@code Minecraft.keyboardHandler} in this version - {@code Screen} has no
	 * {@code setClipboard} of its own, which is what vanilla's own "copy to clipboard" buttons use. The
	 * {@code Exception} catch is because the underlying GLFW clipboard call can fail on a restricted
	 * environment, and a failure to copy must not take the screen down.
	 */
	private void onCopy() {
		try {
			minecraft.keyboardHandler.setClipboard(urlToCopy);
			buttonCopy.setMessage(Text.translatable("gui.mtr.web_dashboard_copied"));
		} catch (Exception e) {
			System.out.println("[MTR-WebDashboard] Could not copy the link to the clipboard: " + e);
			buttonCopy.setMessage(Text.translatable("gui.mtr.web_dashboard_copy_failed"));
		}
	}

	/**
	 * Hands the address to the system browser. The screen closes first so the player is not left
	 * staring at a dialog if the browser takes a moment to appear.
	 */
	private void onOpen() {
		try {
			Util.getPlatform().openUri(urlToOpen);
		} catch (Exception e) {
			System.out.println("[MTR-WebDashboard] Could not open a browser for " + urlToOpen + ": " + e);
		}
		onClose();
	}

	/**
	 * Splits text into lines that fit a pixel width.
	 * <p>
	 * There is no wrapping helper in the screen API and an address has no spaces to break on, so this
	 * breaks on characters. It prefers to break just after a path separator, which keeps the host and
	 * the first path segment readable instead of chopping mid-word, but only once enough of the line is
	 * already filled for that to be an improvement - otherwise a short host would be split for nothing.
	 *
	 * @param maxLines lines to keep; anything beyond that is dropped rather than overflowing the screen.
	 */
	private String[] wrap(String text, int maxPixelWidth, int maxLines) {
		final java.util.List<String> lines = new java.util.ArrayList<>();
		StringBuilder current = new StringBuilder();

		for (int i = 0; i < text.length(); i++) {
			current.append(text.charAt(i));
			final char character = text.charAt(i);

			final boolean overflowing = font.width(current.toString()) > maxPixelWidth;
			final boolean goodBreakPoint = character == '/' && font.width(current.toString()) > maxPixelWidth / 2;

			if (overflowing || goodBreakPoint) {
				if (overflowing && current.length() > 1) {
					// The character that overflowed belongs to the next line.
					current.deleteCharAt(current.length() - 1);
					i--;
				}
				lines.add(current.toString());
				current = new StringBuilder();
				if (lines.size() >= maxLines) {
					return lines.toArray(new String[0]);
				}
			}
		}

		if (current.length() > 0 && lines.size() < maxLines) {
			lines.add(current.toString());
		}
		return lines.toArray(new String[0]);
	}
}
