package dev.denismasterherobrine.densityfunctioncompiler.mixin;

import dev.denismasterherobrine.densityfunctioncompiler.cache.DfcCacheFastPath;
import dev.denismasterherobrine.densityfunctioncompiler.cache.DfcCellCacheAccess;
import dev.denismasterherobrine.densityfunctioncompiler.config.DfcConfig;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.NoiseChunk;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * Per-cell 3D buffer: mirrors {@code NoiseChunk.CacheAllInCell#compute} when
 * {@code context ==} the owning {@link NoiseChunk} and interpolation is active.
 */
@Mixin(targets = "net.minecraft.world.level.levelgen.NoiseChunk$CacheAllInCell")
public class NoiseChunkCacheAllInCellMixin implements DfcCellCacheAccess {

    @Shadow
    @Final
    private NoiseChunk this$0;

    @Shadow
    @Final
    private double[] values;

    @Override
    public double dfc$tryDirectRead(DensityFunction.FunctionContext context) {
        if (!DfcConfig.cacheWrapperDirectRead()) {
            return DfcCacheFastPath.CACHE_MISS;
        }
        if (context != this$0) {
            return DfcCacheFastPath.CACHE_MISS;
        }
        if (!this$0.interpolating) {
            return DfcCacheFastPath.CACHE_MISS;
        }
        int inX = this$0.inCellX;
        int inY = this$0.inCellY;
        int inZ = this$0.inCellZ;
        int cellW = this$0.cellWidth;
        int cellH = this$0.cellHeight;
        if (inX < 0
                || inY < 0
                || inZ < 0
                || inX >= cellW
                || inY >= cellH
                || inZ >= cellW) {
            return DfcCacheFastPath.CACHE_MISS;
        }
        int index = (cellH - 1 - inY) * cellW * cellW + inX * cellW + inZ;
        return this.values[index];
    }
}
