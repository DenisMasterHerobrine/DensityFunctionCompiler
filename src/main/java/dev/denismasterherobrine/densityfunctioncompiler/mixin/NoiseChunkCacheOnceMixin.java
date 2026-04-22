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
 * One-slot and optional array-row cache: mirrors the fast branches of
 * {@code NoiseChunk.CacheOnce#compute}.
 */
@Mixin(targets = "net.minecraft.world.level.levelgen.NoiseChunk$CacheOnce")
public class NoiseChunkCacheOnceMixin implements DfcCellCacheAccess {

    @Shadow
    @Final
    private NoiseChunk this$0;

    @Shadow
    private long lastCounter;

    @Shadow
    private long lastArrayCounter;

    @Shadow
    private double lastValue;

    @Shadow
    private double[] lastArray;

    @Override
    public double dfc$tryDirectRead(DensityFunction.FunctionContext context) {
        if (!DfcConfig.cacheWrapperDirectRead()) {
            return DfcCacheFastPath.CACHE_MISS;
        }
        if (context != this$0) {
            return DfcCacheFastPath.CACHE_MISS;
        }
        if (this.lastArray != null
                && this.lastArrayCounter == this$0.arrayInterpolationCounter) {
            return this.lastArray[this$0.arrayIndex];
        }
        if (this.lastCounter == this$0.interpolationCounter) {
            return this.lastValue;
        }
        return DfcCacheFastPath.CACHE_MISS;
    }
}
