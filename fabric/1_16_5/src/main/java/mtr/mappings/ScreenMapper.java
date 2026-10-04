package mtr.mappings;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public abstract class ScreenMapper extends Screen {

	protected ScreenMapper(Component title) {
		super(title);
	}

	public <T extends AbstractWidget> void addDrawableChild(T child) {
		addButton(child);
	}

	@Override
	public final void render(PoseStack poseStack, int mouseX, int mouseY, float delta) {
		render(new GuiGraphics(poseStack), mouseX, mouseY, delta);
	}

	public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float delta) {
		super.render(guiGraphics.pose(), mouseX, mouseY, delta);
	}

	public void renderBackground(GuiGraphics guiGraphics) {
		super.renderBackground(guiGraphics.pose());
	}

	protected void removeWidget(AbstractWidget widget) {
		children.remove(widget);
		buttons.remove(widget);
	}
}
