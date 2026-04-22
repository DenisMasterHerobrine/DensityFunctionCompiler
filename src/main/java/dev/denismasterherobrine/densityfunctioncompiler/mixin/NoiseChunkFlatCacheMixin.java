package dev.denismasterherobrine.densityfunctioncompiler.mixin;

import dev.denismasterherobrine.densityfunctioncompiler.cache.DfcCacheFastPath;
import dev.denismasterherobrine.densityfunctioncompiler.cache.DfcCellCacheAccess;
import dev.denismasterherobrine.densityfunctioncompiler.config.DfcConfig;
import net.minecraft.core.QuartPos;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.NoiseChunk;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * Mirrors {@code NoiseChunk.FlatCache#compute} in-buffer path
 * (2D layout over quart indices).
 */
@Mixin(targets = "net.minecraft.world.level.levelgen.NoiseChunk$FlatCache")
public class NoiseChunkFlatCacheMixin implements DfcCellCacheAccess {

    @Shadow
    @Final
    private NoiseChunk this$0;

    @Shadow
    @Final
    private double[][] values;

    @Override
    public double dfc$tryDirectRead(DensityFunction.FunctionContext context) {
        if (!DfcConfig.cacheWrapperDirectRead()) {
            return DfcCacheFastPath.CACHE_MISS;
        }
        int qx = QuartPos.fromBlock(context.blockX());
        int qz = QuartPos.fromBlock(context.blockZ());
        int i = qx - this$0.firstNoiseX;
        int j = qz - this$0.firstNoiseZ;
        int w = this.values.length;
        if (i < 0 || j < 0 || i >= w || j >= w) {
            return DfcCacheFastPath.CACHE_MISS;
        }
        return this.values[i][j];
    }
}
