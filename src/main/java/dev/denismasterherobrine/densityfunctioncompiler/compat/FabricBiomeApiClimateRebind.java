package dev.denismasterherobrine.densityfunctioncompiler.compat;

import net.minecraft.world.level.biome.Climate;
import net.fabricmc.fabric.impl.biome.MultiNoiseSamplerHooks;

/**
 * Propagates Fabric biome API seed from the vanilla wired {@link Climate.Sampler} to
 * a DFC-rebuilt instance using {@link MultiNoiseSamplerHooks} (no field reflection).
 *
 * <p>When Forgified Fabric + Connector are not installed, the runtime never loads this class
 * because {@code RandomStateMixin} gates on {@code ModList} before calling — callers must not
 * reference this class without that check.</p>
 */
public final class FabricBiomeApiClimateRebind {

    private FabricBiomeApiClimateRebind() {}

    public static void propagateToCompiledSampler(Climate.Sampler from, Climate.Sampler to, long levelSeed) {
        if (to == from) {
            return;
        }
        Object toObj = to;
        if (!(toObj instanceof MultiNoiseSamplerHooks toHooks)) {
            return;
        }
        long s = levelSeed;
        Object fromObj = from;
        if (fromObj instanceof MultiNoiseSamplerHooks fromHooks) {
            try {
                s = fromHooks.fabric_getSeed();
            } catch (Throwable ignored) {
            }
        }
        toHooks.fabric_setSeed(s);
    }
}
