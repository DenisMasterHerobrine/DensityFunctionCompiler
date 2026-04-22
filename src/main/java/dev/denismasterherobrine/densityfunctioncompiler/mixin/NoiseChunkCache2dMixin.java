package dev.denismasterherobrine.densityfunctioncompiler.mixin;

import dev.denismasterherobrine.densityfunctioncompiler.cache.DfcCacheFastPath;
import dev.denismasterherobrine.densityfunctioncompiler.cache.DfcCellCacheAccess;
import dev.denismasterherobrine.densityfunctioncompiler.config.DfcConfig;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.DensityFunction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * 2D (XZ) one-slot cache: same (blockX, blockZ) as last call returns the stored value.
 */
@Mixin(targets = "net.minecraft.world.level.levelgen.NoiseChunk$Cache2D")
public class NoiseChunkCache2dMixin implements DfcCellCacheAccess {

    @Shadow
    private long lastPos2D;

    @Shadow
    private double lastValue;

    @Override
    public double dfc$tryDirectRead(DensityFunction.FunctionContext context) {
        if (!DfcConfig.cacheWrapperDirectRead()) {
            return DfcCacheFastPath.CACHE_MISS;
        }
        int bx = context.blockX();
        int bz = context.blockZ();
        if (this.lastPos2D == ChunkPos.asLong(bx, bz)) {
            return this.lastValue;
        }
        return DfcCacheFastPath.CACHE_MISS;
    }
}
