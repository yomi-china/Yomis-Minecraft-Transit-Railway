package mtr.render;

import mtr.mappings.RenderLayerMapper;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;

import java.util.HashMap;
import java.util.Map;

public class MoreRenderLayers extends RenderLayerMapper {

	private static final Map<ResourceLocation, RenderType[]> LIGHT_CACHE = new HashMap<>();
	/**
	 * Interior and exterior are both cutout render types created from the same factory and the same texture, so they
	 * share one cache entry. Sharing the same {@link RenderType} instance also means the buffer source does not have
	 * to flush between the interior and exterior stages.
	 */
	private static final Map<ResourceLocation, RenderType> CUTOUT_CACHE = new HashMap<>();
	private static final Map<ResourceLocation, RenderType> INTERIOR_TRANSLUCENT_CACHE = new HashMap<>();
	private static final Map<ResourceLocation, RenderType> EXTERIOR_TRANSLUCENT_CACHE = new HashMap<>();

	public static RenderType getLight(ResourceLocation texture, boolean isTranslucent) {
		RenderType[] cached = LIGHT_CACHE.get(texture);
		if (cached == null) {
			cached = new RenderType[2];
			LIGHT_CACHE.put(texture, cached);
		}
		final int index = isTranslucent ? 1 : 0;
		if (cached[index] == null) {
			cached[index] = beaconBeam(texture, isTranslucent);
		}
		return cached[index];
	}

	public static RenderType getInterior(ResourceLocation texture) {
		return checkCache(texture, () -> entityCutout(texture), CUTOUT_CACHE);
	}

	public static RenderType getInteriorTranslucent(ResourceLocation texture) {
		return checkCache(texture, () -> entityTranslucentCull(texture), INTERIOR_TRANSLUCENT_CACHE);
	}

	public static RenderType getExterior(ResourceLocation texture) {
		return checkCache(texture, () -> entityCutout(texture), CUTOUT_CACHE);
	}

	public static RenderType getExteriorTranslucent(ResourceLocation texture) {
		return checkCache(texture, () -> entityTranslucentCull(texture), EXTERIOR_TRANSLUCENT_CACHE);
	}

	private static RenderType checkCache(ResourceLocation identifier, java.util.function.Supplier<RenderType> supplier, Map<ResourceLocation, RenderType> cache) {
		RenderType renderType = cache.get(identifier);
		if (renderType == null) {
			renderType = supplier.get();
			cache.put(identifier, renderType);
		}
		return renderType;
	}
}
