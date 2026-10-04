package mtr.mappings;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiComponent;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;

public final class GuiGraphics {

	private final PoseStack poseStack;
	private final GuiComponent guiComponent = new GuiComponent() {
	};

	public GuiGraphics(PoseStack poseStack) {
		this.poseStack = poseStack;
	}

	public PoseStack pose() {
		return poseStack;
	}

	public void renderWidget(AbstractWidget widget, int mouseX, int mouseY, float delta) {
		widget.render(poseStack, mouseX, mouseY, delta);
	}

	public void fill(int x1, int y1, int x2, int y2, int color) {
		GuiComponent.fill(poseStack, x1, y1, x2, y2, color);
	}

	public void vLine(int x, int y1, int y2, int color) {
		GuiComponent.fill(poseStack, x, y1, x + 1, y2, color);
	}

	public int drawString(Font font, String text, int x, int y, int color) {
		return font.draw(poseStack, text, x, y, color);
	}

	public int drawString(Font font, String text, int x, int y, int color, boolean shadow) {
		return shadow ? font.drawShadow(poseStack, text, x, y, color) : font.draw(poseStack, text, x, y, color);
	}

	public int drawString(Font font, Component text, int x, int y, int color) {
		return font.draw(poseStack, text, x, y, color);
	}

	public int drawString(Font font, Component text, int x, int y, int color, boolean shadow) {
		return shadow ? font.drawShadow(poseStack, text, x, y, color) : font.draw(poseStack, text, x, y, color);
	}

	public int drawString(Font font, FormattedCharSequence text, int x, int y, int color) {
		return font.draw(poseStack, text, x, y, color);
	}

	public void drawCenteredString(Font font, String text, int x, int y, int color) {
		font.drawShadow(poseStack, text, x - font.width(text) / 2F, y, color);
	}

	public void drawCenteredString(Font font, Component text, int x, int y, int color) {
		font.drawShadow(poseStack, text, x - font.width(text) / 2F, y, color);
	}

	public void blit(ResourceLocation location, int x, int y, int u, int v, int width, int height) {
		UtilitiesClient.beginDrawingTexture(location);
		guiComponent.blit(poseStack, x, y, u, v, width, height);
	}

	public void blit(ResourceLocation location, int x, int y, int u, int v, int width, int height, int textureWidth, int textureHeight) {
		UtilitiesClient.beginDrawingTexture(location);
		GuiComponent.blit(poseStack, x, y, u, v, width, height, textureWidth, textureHeight);
	}

	public void enableScissor(int x1, int y1, int x2, int y2) {
		RenderSystem.enableScissor(x1, y1, x2 - x1, y2 - y1);
	}

	public void disableScissor() {
		RenderSystem.disableScissor();
	}
}
