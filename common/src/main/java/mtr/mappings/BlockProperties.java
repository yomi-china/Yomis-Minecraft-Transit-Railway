package mtr.mappings;

import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.Material;
import net.minecraft.world.level.material.MaterialColor;

public final class BlockProperties {

	private BlockProperties() {
	}

	public static BlockBehaviour.Properties create() {
		return BlockBehaviour.Properties.of(Material.METAL, MaterialColor.QUARTZ);
	}
}
